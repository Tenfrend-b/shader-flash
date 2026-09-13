package dev.shaderflash;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 用 Sodium 的“逐区块排队重建”代替 {@code WorldRenderer.reload()}。
 *
 * <h2>为什么</h2>
 * 切换主副光影时，如果两个包在“会被烘焙进区块网格”的设置上不同（材质表、渲染类型表、
 * AO/明暗等级等），网格就必须重做一次。Iris 自己的做法是 {@code allChanged()}：
 * 一次性丢掉所有区块网格再重建 —— 玩家会看到地形整片消失又慢慢长回来，也就是
 * “明显的区块卸载/重载”。
 *
 * <p>Sodium 提供了更温和的入口：{@code scheduleRebuildForChunks(...)} 只是把区块**排队**重做，
 * 旧网格会一直渲染到新网格建好为止，因此整个过程是看不见的。
 *
 * <p>这个类引用了 Sodium 的类型，所以在没装 Sodium 的环境里**不能被加载**；
 * 调用点必须先用 {@code isModLoaded("sodium")} 判断（见 {@link DuoManager#swapPrimary()}）。
 */
public final class SodiumRemesh {
	/**
	 * 待投递的区块段（{@code [x, y, z]}，按“由远到近”排好）。
	 *
	 * <p>为什么要分批：以前一次性把 11 万多个区块段全排进 Sodium 的队列，
	 * 那一帧的 CPU 尖峰 + 随后跑满所有构建线程，玩家在前台能明显感到掉帧。
	 * 现在按策略每 tick 只投递一小批（见 {@link dev.shaderflash.DuoConfig#remeshPolicy}），
	 * 地形依旧“旧网格渲染到新网格建好”，但前台不再被一次性打满。
	 */
	private static List<long[]> pending;
	private static int pendingIndex;
	private static int totalSections;
	private static int submittedSections;
	/**
	 * 这一轮待投递列表是否允许“分批”。
	 *
	 * <p><b>只有「副管线截图」的取景那一轮是 true</b>：那时玩家正看着进度条，分批投递让前台平稳。
	 * F7 主副互换、以及截图结束后的材质还原都属于“玩家马上要看结果”的路径，
	 * 一律一次性投完（{@code Integer.MAX_VALUE}），不受
	 * {@link dev.shaderflash.DuoConfig#remeshPolicy} 影响。
	 */
	private static boolean pendingBatched;

	/** Sodium 内部“已登记待重建”的计数缓存（反射读取，失败就退化为未知）。 */
	private static Field rebuildListsField;
	private static Field managerField;
	private static boolean reflectionTried;

	private SodiumRemesh() {
	}

	// ------------------------------------------------------------------
	// 分批投递
	// ------------------------------------------------------------------

	/**
	 * 建立这一轮的待投递列表（由远到近）。返回本轮打算重建的区块段总数。
	 *
	 * <p>由 {@code DuoManager.remeshFor()} 调用，真正的投递交给每 tick 的 {@link #submitBatch}。
	 */
	public static int prepare(MinecraftClient client, boolean batched) {
		SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
		ClientWorld world = client.world;
		if (renderer == null || world == null || client.player == null) {
			return -1;
		}

		ChunkPos center = client.player.getChunkPos();
		int radius = Math.max(2, client.options.getClampedViewDistance() + 2);
		int minY = world.getBottomSectionCoord();
		int maxY = world.getTopSectionCoord() - 1;

		// 先按“距玩家由远到近”把区块列排好：Sodium 的 important 队列是 addFirst（后进先出），
		// 所以从远到近投递，最终执行顺序就是从玩家脚下向外扩散（正常的“由近到远”补齐）。
		List<long[]> columns = new ArrayList<>();
		for (int chunkX = center.x - radius; chunkX <= center.x + radius; chunkX++) {
			for (int chunkZ = center.z - radius; chunkZ <= center.z + radius; chunkZ++) {
				long dx = chunkX - center.x;
				long dz = chunkZ - center.z;
				columns.add(new long[]{chunkX, chunkZ, dx * dx + dz * dz});
			}
		}
		columns.sort(Comparator.comparingLong(column -> -column[2]));

		List<long[]> sections = new ArrayList<>(columns.size() * Math.max(1, maxY - minY + 1));
		for (long[] column : columns) {
			for (int y = minY; y <= maxY; y++) {
				sections.add(new long[]{column[0], y, column[1]});
			}
		}

		pending = sections;
		pendingIndex = 0;
		totalSections = sections.size();
		submittedSections = 0;
		pendingBatched = batched;
		return totalSections;
	}

	/** 这一轮是否允许分批投递（只有截图取景是）。 */
	public static boolean isPendingBatched() {
		return pending != null && pendingBatched;
	}

	/** 本 tick 投递最多 {@code budget} 个区块段；返回 true 表示还有剩余（下一 tick 继续）。 */
	public static boolean submitBatch(int budget) {
		List<long[]> queue = pending;
		if (queue == null) {
			return false;
		}
		SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
		if (renderer == null) {
			pending = null;
			return false;
		}
		int limit = Math.max(1, budget);
		int submittedNow = 0;
		while (pendingIndex < queue.size() && submittedNow < limit) {
			long[] section = queue.get(pendingIndex++);
			renderer.scheduleRebuildForChunk((int) section[0], (int) section[1], (int) section[2], true);
			submittedNow++;
		}
		submittedSections += submittedNow;
		if (pendingIndex >= queue.size()) {
			pending = null;
			pendingIndex = 0;
			pendingBatched = false;
			return false;
		}
		return true;
	}

	public static boolean hasPendingSubmission() {
		return pending != null;
	}

	public static int totalSections() {
		return totalSections;
	}

	public static int submittedSections() {
		return submittedSections;
	}

	// ------------------------------------------------------------------
	// 进度（给截图提示用）
	// ------------------------------------------------------------------

	/** 还差多少区块段没做完（= 还没投递的 + Sodium 手里还在排队/在跑的）。未知时返回 -1。 */
	public static int pendingCount() {
		if (totalSections <= 0) {
			return -1;
		}
		int notYetSubmitted = Math.max(0, totalSections - submittedSections);
		int inSodium = sodiumPending();
		if (inSodium < 0) {
			return notYetSubmitted > 0 ? totalSections - submittedSections : -1;
		}
		return Math.min(totalSections, notYetSubmitted + inSodium);
	}

	/** 已完成比例（0~1）；无法统计时返回 -1。 */
	public static float progress() {
		int total = totalSections;
		if (total <= 0) {
			return -1.0F;
		}
		int pendingNow = pendingCount();
		int done;
		if (pendingNow < 0) {
			// 拿不到 Sodium 队列：至少用“已投递”作为下界，剩下的按“是否还有活”粗略表示。
			done = submittedSections;
			if (!isTerrainComplete()) {
				done = Math.min(done, total - 1);
			}
		} else {
			done = total - pendingNow;
		}
		if (done < 0) {
			done = 0;
		}
		if (done > total) {
			done = total;
		}
		return total == 0 ? 1.0F : (float) done / (float) total;
	}

	/** Sodium 手里还在排队/正在构建的区块段数（{@code rebuildLists} 的登记数 + 队列 + 忙碌线程）；未知返回 -1。 */
	private static int sodiumPending() {
		SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
		if (renderer == null) {
			return -1;
		}
		try {
			if (!reflectionTried) {
				reflectionTried = true;
				managerField = SodiumWorldRenderer.class.getDeclaredField("renderSectionManager");
				managerField.setAccessible(true);
				rebuildListsField = RenderSectionManager.class.getDeclaredField("rebuildLists");
				rebuildListsField.setAccessible(true);
			}
			if (managerField == null || rebuildListsField == null) {
				return -1;
			}
			Object manager = managerField.get(renderer);
			if (!(manager instanceof RenderSectionManager sectionManager)) {
				return -1;
			}
			int registered = 0;
			Object lists = rebuildListsField.get(sectionManager);
			if (lists instanceof java.util.Map<?, ?> map) {
				for (Object value : map.values()) {
					if (value instanceof Collection<?> collection) {
						registered += collection.size();
					}
				}
			}
			ChunkBuilder builder = sectionManager.getBuilder();
			return registered + builder.getScheduledJobCount() + builder.getBusyThreadCount();
		} catch (Throwable error) {
			rebuildListsField = null;
			managerField = null;
			return -1;
		}
	}

	/**
	 * 地形重建是否已经全部完成（Sodium 的构建队列为空）。
	 *
	 * <p>用于「副管线截图」等在切换之后才拍的场景：重建没做完就截图，
	 * 拍到的会是「一半旧材质、一半新材质」的地形。
	 *
	 * <p>调用方必须先确认装了 Sodium（本类引用了 Sodium 的类型）。
	 */
	public static boolean isTerrainComplete() {
		SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
		return renderer == null || renderer.isTerrainRenderComplete();
	}
}
