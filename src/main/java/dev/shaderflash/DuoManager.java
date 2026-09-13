package dev.shaderflash;

import com.google.common.collect.ImmutableList;
import dev.shaderflash.mixin.IrisRenderingPipelineAccessor;
import dev.shaderflash.mixin.GameRendererAccessor;
import dev.shaderflash.mixin.PipelineManagerAccessor;
import dev.shaderflash.mixin.DuoMixinPlugin;
import dev.shaderflash.mixin.WorldRendererAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderMap;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 双管线管理器：负责副管线的建立 / 销毁 / 离屏渲染 / 主副互换。
 *
 * <h2>为什么这样就能“同时加载两套渲染管线”</h2>
 * Iris 的 {@link IrisRenderingPipeline} 本身是自包含的：每条管线自己持有渲染目标、
 * 阴影贴图、以及一整套编译好的着色器程序。Iris 唯一的前提是“同一时刻只有一条管线在渲染”，
 * 于是它把当前管线存在 {@link PipelineManager#getPipelineNullable()} 和
 * “每个维度一条管线”的映射表里。
 *
 * <p>我们额外创建第二条 {@link IrisRenderingPipeline} 并长期持有（这就是“同时加载两套”），
 * 然后在正常那一遍世界渲染结束后，临时把它换进 Iris 的映射表、把主帧缓冲换成离屏 FBO，
 * 再调用一次 {@link GameRenderer#renderWorld} —— 于是同一帧里两条管线各渲染了一遍，
 * 副管线那遍的像素只落在离屏 FBO 里，永远不上屏。
 *
 * <h2>为什么切换会变快</h2>
 * 正常切换光影包时，Iris 要做：解析整包 → 编译几十个程序 → 分配缓冲区/贴图 → 重建全部区块
 * 网格。双管线模式下这些工作在“预编译”阶段就做完了，切换只剩指针交换，外加材质 ID 表
 * 不同时的一次区块重建（如果两包材质表一致，连这个都省了）。
 */
public final class DuoManager {
	/**
	 * 预编译里“解包 + 解析”两段的工作线程。
	 *
	 * <p>刻意只用<b>一个</b>线程：这几步都是磁盘 I/O 与 CPU 文本处理，再开更多线程也不快，
	 * 反而会让“玩家快速来回换包”时的多个任务同时写同一个 packcache 目录。
	 */
	private static final ExecutorService PREP_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "shaderflash-prewarm");
		thread.setDaemon(true);
		// 解包只是文件 I/O，别和游戏线程抢 CPU（“后台”就该有后台的优先级）。
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private static PackSlot secondary;
	private static boolean building;
	private static boolean ghostPassActive;
	private static boolean ghostBroken;
	private static boolean mixinVerified;
	private static boolean extendedFormatWarningLogged;
	private static long lastGhostNanos = -1L;
	/** 主渲染那一遍抓到的管线状态，供“兼容性自检”显示（自检按钮是在渲染之外触发的）。 */
	private static String lastRenderSnapshot;

	/**
	 * 「副管线截图」期间临时借用的现场。
	 *
	 * <p>截图要把<b>副包的材质表</b>临时应用到全局并让地形按它重建，拍完再原样还原；
	 * 全程不碰管线指针、不切屏（像素直接从离屏 FBO 读，见 {@link ScreenshotCapture}）。
	 */
	private static boolean captureActive;
	private static WorldSettings capturePrimarySettings;
	private static boolean captureBumpedShaderVersion;
	/** 取景期间是否真的把区块网格重建过（决定还原时要不要再重建一次）。 */
	private static boolean captureRebuilt;
	private static String captureName = "";

	private static long lastSwapMillis = -1L;
	private static String lastError = "";

	/** 正在进行的后台预编译任务；null 表示空闲。 */
	private static PrewarmJob prewarm;
	/** 预编译完成后是否自动把副管线切成主管线（玩家按 F7 时副管线还没就绪的情况）。 */
	private static boolean swapWhenReady;

	/** 最近一次“排队重建区块”的时间戳（纳秒）。供副管线截图判断地形是否还在替换中。 */
	private static long lastRemeshNanos = -1L;
	/**
	 * 最近一次重建是否已经“真正跑完”。
	 *
	 * <p>为什么不能只看 Sodium 的队列是否为空：任务是<b>先登记、后入队</b>的——
	 * `scheduleRebuildForChunk` 只是给区块打上“待重建”标记，真正的构建任务要到
	 * Sodium 下一次 `update()` 才进队列。所以刚调度完的那一瞬间队列仍为空，
	 * “队列为空”会被误判成“已经重建完”。
	 *
	 * <p>这里改成跟踪一次完整的“忙 → 空”过程：只有先看到队列非空，之后再变空，才算真的做完了。
	 */
	private static boolean remeshBusySeen;
	private static boolean remeshSettled = true;
	/** 排队后最多观察这么久：再没看到“忙”就当这轮重建已经做完，避免无限等待。 */
	private static final long REMESH_OBSERVE_GRACE_NANOS = 3_000_000_000L;

	/**
	 * {@code GameRenderer.renderWorld} <b>入口时</b>那个 PoseStack 顶层的姿态快照。
	 *
	 * <p>为什么需要它：{@code renderWorld} 会就地改写传进来的 PoseStack
	 * （对同一个栈做 {@code mulPose(相机 pitch)} 与 {@code mulPose(相机 yaw + 180)}），
	 * 所以到了尾部，那个栈里已经含有相机旋转。副管线那一遍必须从“入口时的姿态”起算，
	 * 否则相机旋转会被叠加两次。
	 *
	 * <p>注意<b>不能</b>用 push/pop 的办法：原版 {@code LevelRenderer.renderLevel} 在中段会调
	 * {@code checkPoseStack()}，要求栈里只有 1 帧（{@code PoseStack.clear() == true}），
	 * 多出一帧会直接抛 {@code IllegalStateException: Pose stack not empty} 崩游戏。
	 * 所以这里只是“记下来”，尾部另建一个内容相同的栈交给副管线那一遍，完全不动原版那个栈。
	 */
	private static Matrix4f entryPose;
	private static Matrix3f entryNormal;

	private DuoManager() {
	}

	public static String secondaryName() {
		return secondary == null ? "" : secondary.name;
	}

	public static long lastSwapMillis() {
		return lastSwapMillis;
	}

	/** 最近一次重建是否已经真正做完（先忙后空）。没有待处理的重建时返回 true。 */
	public static boolean isRemeshSettled() {
		return remeshSettled;
	}

	/** 地形是否已经重建完毕（未装 Sodium 时视为已完成）。 */
	public static boolean isTerrainSettled() {
		if (!FabricLoader.getInstance().isModLoaded("sodium")) {
			return true;
		}
		try {
			// 还没把这一轮要重建的区块段投递完（见 SodiumRemesh 的分批投递）也算“没做完”。
			if (SodiumRemesh.hasPendingSubmission()) {
				return false;
			}
			return SodiumRemesh.isTerrainComplete();
		} catch (Throwable error) {
			return true;
		}
	}

	public static long lastGhostMicros() {
		return lastGhostNanos < 0 ? -1L : lastGhostNanos / 1000L;
	}

	// ------------------------------------------------------------------
	// 生命周期
	// ------------------------------------------------------------------

	/** 每个客户端 tick 调用一次（渲染线程）。这里只做“安全时刻”的活儿：建/销毁管线。 */
	public static void onClientTick(MinecraftClient client) {
		if (!ShaderFlashSupport.available()) {
			return;
		}
		// 安全网：光影管线下全局「扩展顶点格式」必须是 true（见 keepExtendedVertexFormat）。
		keepExtendedVertexFormat();
		// 截图那一帧的兜底（万一没渲染出来，也要把主管线换回去）
		ScreenshotCapture.tick();
		// 按当前策略分批投递**截图取景那一轮**的区块重建
		// （避免一次性打满 Sodium 的构建线程，前台掉帧）。
		// 注意：只有截图取景的待投递列表是“可分批”的（SodiumRemesh.isPendingBatched），
		// F7 主副互换与截图后的材质还原都是一次性投完，不受这里的预算影响。
		if (SodiumRemesh.hasPendingSubmission()) {
			try {
				SodiumRemesh.submitBatch(SodiumRemesh.isPendingBatched()
					? remeshBatchBudget() : Integer.MAX_VALUE);
			} catch (Throwable error) {
				ShaderFlash.LOGGER.warn("[ShaderFlash] 分批投递区块重建失败，改为一次性投完", error);
				try {
					SodiumRemesh.submitBatch(Integer.MAX_VALUE);
				} catch (Throwable ignored) {
					// 下一 tick 还会再试
				}
			}
		}
		// 跟踪“重建到底做完没有”：先看到队列非空，之后变空才算完成。
		if (!remeshSettled) {
			if (!isTerrainSettled()) {
				remeshBusySeen = true;
			} else if (remeshBusySeen) {
				remeshSettled = true;
			} else if (System.nanoTime() - lastRemeshNanos > REMESH_OBSERVE_GRACE_NANOS) {
				// 队列一直是空的，而且已经过了足够长的时间：说明这一轮重建要么根本没排队成功、
				// 要么快到一个 tick 都没被观测到。不能就此永远等下去（截图会一直不触发）。
				remeshSettled = true;
			}
		}
		DuoConfig config = DuoConfig.get();

		boolean hasWorld = client.world != null;
		boolean inWorld = hasWorld && client.player != null;

		if (!hasWorld) {
			// 回到主菜单/退出世界时会走 destroyPipeline，这里再兜一层底。
			cancelPrewarm("离开世界");
			swapWhenReady = false;
			if (secondary != null) {
				destroySecondary("离开世界");
			}
			// 世界已经卸载，离屏 FBO 也不会再被用到；在这里释放，别让它常驻显存。
			releaseGhostTarget();
			return;
		}
		if (!inWorld) {
			return;
		}
		if (!config.enabled) {
			cancelPrewarm("已关闭双管线");
			swapWhenReady = false;
			if (secondary != null) {
				destroySecondary("已关闭双管线");
			}
			releaseGhostTarget();
			return;
		}

		// 后台预编译：把任务推进一帧（工作线程跑解包 + 解析，渲染线程只负责最后编译）。
		tickPrewarm();

		// 0.3.2 起**不做任何自动预编译**：进世界、Iris 重载、换维度都不会替玩家建副管线。
		//
		// 原因见 docs/19：进世界那一刻主管线往往还没构造好，这时自动建出来的副管线会踩到 Iris 的
		// 全局材质状态（表现为实体渲染不出来）；游戏中由玩家主动触发时不存在这个时间窗。
		// 所以现在只有三种建管线的时机，全部由玩家发起：
		//   1) 设置界面里的「预编译 / 重建副管线」按钮；
		//   2) 按下 F7 时若还没有可用副管线（就地构建）；
		//   3) 打开双管线总开关（onEnabledChanged）。
		// 换维度会让已有的副管线失效 —— 这里只把它释放掉并提示玩家手动重建。
		if (secondary != null && !secondary.dimension.equals(Iris.getCurrentDimension())) {
			destroySecondary("维度变化");
			feedback(ModTexts.text(ModTexts.MESSAGE_PRELOAD_AFTER_DIMENSION_CHANGE));
		}
	}

	/** 打开/关闭开关时调用。 */
	public static void onEnabledChanged() {
		DuoConfig config = DuoConfig.get();
		if (config.enabled) {
			ensureSecondary(true);
		} else {
			destroySecondary("已关闭双管线");
			releaseGhostTarget();
		}
	}

	/** Iris 重载（切换光影包、F3+R、按 R 键）时会销毁自己的全部管线。 */
	public static void onIrisPipelinesDestroyed() {
		// 预编译过程中 Iris 自己重载了：正在编译的那份 ProgramSet 已经过期，直接丢掉。
		cancelPrewarm("Iris 重新加载管线");
		swapWhenReady = false;
		if (secondary != null) {
			destroySecondary("Iris 重新加载管线");
		}
		// 0.3.2 起不再自动重建（理由同上、见 docs/19）：只释放，等玩家手动预编译。
		ShaderFlash.LOGGER.info("[ShaderFlash] Iris 已重载；副管线已释放，需要时请手动预编译");
	}

	/**
	 * 释放离屏 FBO。
	 *
	 * <p>只能在渲染之外的安全时刻调用（tick 回调、开关切换、离开世界），
	 * 绝不能在副管线那一遍渲染的中途删除 GL 资源。
	 */
	private static void releaseGhostTarget() {
		if (ghostPassActive) {
			return;
		}
		try {
			GhostFramebuffer.destroy();
		} catch (Throwable error) {
			ShaderFlash.LOGGER.warn("[ShaderFlash] 释放离屏帧缓冲失败", error);
		}
	}

	// ------------------------------------------------------------------
	// 副管线的创建与销毁
	// ------------------------------------------------------------------

	/** @return 副管线是否可用 */
	public static boolean ensureSecondary(boolean report) {
		if (!ShaderFlashSupport.available()) {
			lastError = ModTexts.raw(ModTexts.ERROR_NO_IRIS);
			if (report) {
				feedback(ModTexts.text(ModTexts.MESSAGE_NO_IRIS));
			}
			return false;
		}
		DuoConfig config = DuoConfig.get();
		String name = config.secondaryPack;
		if (name == null || name.isBlank()) {
			lastError = ModTexts.raw(ModTexts.ERROR_NO_SECONDARY_PACK);
			if (report) {
				feedback(ModTexts.text(ModTexts.MESSAGE_NO_PACK));
			}
			return false;
		}
		ShaderPack primaryPack = Iris.getCurrentPack().orElse(null);
		if (primaryPack == null) {
			lastError = ModTexts.raw(ModTexts.ERROR_NO_SHADER_PACK);
			if (report) {
				feedback(ModTexts.text(ModTexts.MESSAGE_NO_SHADER));
			}
			return false;
		}
		NamespacedId dimension = Iris.getCurrentDimension();
		if (dimension == null) {
			lastError = ModTexts.raw(ModTexts.ERROR_UNKNOWN_DIMENSION);
			return false;
		}
		if (secondary != null && secondary.name.equals(name) && secondary.dimension.equals(dimension)) {
			return true;
		}
		if (prewarm != null && prewarm.name.equals(name)) {
			if (report) {
				feedback(ModTexts.text(ModTexts.MESSAGE_PREWARM_RUNNING, name));
			}
			return false;
		}

		Path resolved = PackScanner.resolvePackPath(name);
		if (resolved == null) {
			lastError = ModTexts.str(ModTexts.ERROR_PACK_MISSING, name);
			if (report) {
				feedback(ModTexts.text(ModTexts.MESSAGE_PACK_MISSING, name));
			}
			return false;
		}

		destroySecondary("重建副管线");
		startPrewarm(name, resolved.getFileName().toString(), dimension, resolved, report);
		return false;
	}

	// ------------------------------------------------------------------
	// 后台预编译
	// ------------------------------------------------------------------

	/**
	 * 一次副管线预编译任务。
	 *
	 * <h2>为什么分成两段</h2>
	 * 一次完整预编译 = 解包（纯文件 I/O）+ 解析光影包 + 编译着色器。
	 *
	 * <ul>
	 *   <li><b>解包</b>：把 zip / 目录铺成 {@code config/shaderflash/packcache/<包名>/}，纯文件 I/O，
	 *       放到工作线程上跑（40 MB 的包解起来要几秒），游戏在此期间照常出帧；</li>
	 *   <li><b>解析 + 编译</b>：只能在渲染线程。编译必须在渲染线程是显然的
	 *       （Iris 在管线构造函数里直接调 GL 编译链接）；解析同样离不开 ——
	 *       实测把整个解析丢到工作线程后，三个真实光影包无一例外都抛
	 *       {@code IllegalStateException: Rendersystem called from wrong thread}
	 *       （Iris 解析 {@code shaders.properties} 时会做 GL 查询），所以不绕圈子，
	 *       直接在渲染线程做。</li>
	 * </ul>
	 */
	private static final class PrewarmJob {
		final String name;
		final String fileName;
		final NamespacedId dimension;
		final Path source;
		/**
		 * Iris 的标准宏。必须<b>在渲染线程上先算好</b>再交给工作线程：
		 * 它会调 {@code glGetString} / {@code glGetStringi} 去问 GL 版本与扩展列表，
		 * 那是真正的 GL 调用，在别的线程上跑会直接抛异常。
		 */
		final ImmutableList<StringPair> defines;
		final long startedNanos = System.nanoTime();
		/** 是否允许把“解包 + 解析”放到工作线程。 */
		volatile boolean background;
		volatile ModTexts.LText phase = ModTexts.PREWARM_PHASE_PREPARE;
		/** 0 ~ 1，仅用于界面显示。 */
		volatile float progress;
		volatile Throwable error;
		volatile boolean unpacked;
		volatile boolean parsed;
		volatile Path packRoot;
		volatile ShaderPack pack;
		volatile long unpackMillis = -1L;
		volatile long parseMillis = -1L;
		volatile boolean workerStarted;

		PrewarmJob(String name, String fileName, NamespacedId dimension, Path source,
				   ImmutableList<StringPair> defines, boolean background) {
			this.name = name;
			this.fileName = fileName;
			this.dimension = dimension;
			this.source = source;
			this.defines = defines;
			this.background = background;
		}
	}

	private static void startPrewarm(String name, String fileName, NamespacedId dimension, Path source,
									 boolean report) {
		boolean background = DuoConfig.get().backgroundPrewarm;
		// 标准宏要在渲染线程上先算：里面会做 GL 查询（见 PrewarmJob#defines）。
		ImmutableList<StringPair> defines = StandardMacros.createStandardEnvironmentDefines();
		prewarm = new PrewarmJob(name, fileName, dimension, source, defines, background);
		ShaderFlash.LOGGER.info("[ShaderFlash] 开始预编译副管线 {}{}", name, background ? "（后台）" : "（同步）");
		if (report) {
			feedback(ModTexts.text(ModTexts.MESSAGE_PREWARM_STARTED, name));
		}
	}

	/** 由渲染线程每 tick 推进一次。 */
	private static void tickPrewarm() {
		PrewarmJob job = prewarm;
		if (job == null) {
			return;
		}

		// 阶段一：解包（纯 I/O，可以在工作线程上跑）。
		if (!job.unpacked) {
			if (job.error != null) {
				failPrewarm(job);
				return;
			}
			if (job.background) {
				if (!job.workerStarted) {
					startPrewarmWorker(job);
				}
				return; // 工作线程还在解包，下一 tick 再看
			}
			runUnpack(job);
			if (job.error != null) {
				failPrewarm(job);
				return;
			}
		}

		// 阶段二：解析（Iris 解析期会做 GL 查询，只能在渲染线程）。
		if (!job.parsed) {
			runParse(job);
			if (job.error != null) {
				failPrewarm(job);
				return;
			}
		}

		// 阶段三：编译并分配显存。
		compilePrewarm(job);
	}

	/** 每 tick 允许投递多少区块段重建（策略越低越碎，前台越顺）。 */
	private static int remeshBatchBudget() {
		return switch (DuoConfig.get().remeshPolicy) {
			case DuoConfig.POLICY_REALTIME -> Integer.MAX_VALUE;
			case DuoConfig.POLICY_HIGH -> 4000;
			case DuoConfig.POLICY_MEDIUM -> 1500;
			case DuoConfig.POLICY_LOW -> 700;
			default -> 300;
		};
	}

	private static void startPrewarmWorker(PrewarmJob job) {
		job.workerStarted = true;
		// 所有预编译的解包都走同一个工作线程：这样即使玩家很快地来回换包
		// （上一个任务被取消、下一个任务紧接着开跑），也不会出现两个线程同时写
		// 同一个 packcache 目录的竞态。
		PREP_EXECUTOR.execute(() -> runUnpack(job));
	}

	/** 阶段一：解包。纯文件 I/O，工作线程与渲染线程都能跑。 */
	private static void runUnpack(PrewarmJob job) {
		try {
			long start = System.nanoTime();
			job.phase = ModTexts.PREWARM_PHASE_UNPACK;
			job.progress = 0.05F;
			// 铺一份自己的目录副本：避开 zipfs 的生命周期问题（副管线是常驻的，
			// 而 Iris 自己的 zipfs 会在重载时关掉），也避免主副同包时重复打开同一个 zip。
			PackPreparer.Result prepared = PackPreparer.prepare(job.name, job.source);
			job.packRoot = prepared.root();
			job.unpackMillis = (System.nanoTime() - start) / 1_000_000L;
			job.progress = 0.25F;
			job.unpacked = true;
		} catch (Throwable error) {
			job.error = error;
		}
	}

	/** 阶段二：解析光影包。Iris 解析期会做 GL 查询，必须留在渲染线程。 */
	private static void runParse(PrewarmJob job) {
		try {
			long start = System.nanoTime();
			job.phase = ModTexts.PREWARM_PHASE_PARSE;
			Map<String, String> options = PackScanner.readOptions(job.name);
			// 解析：读 properties / shader 源码并构建 ProgramSet（尚未编译任何程序）。
			job.pack = new ShaderPack(job.packRoot, options, job.defines);
			job.parseMillis = (System.nanoTime() - start) / 1_000_000L;
			job.progress = 0.5F;
			job.parsed = true;
		} catch (Throwable error) {
			job.error = error;
		}
	}

	/** 阶段三：在渲染线程上编译整套程序并分配显存。 */
	private static void compilePrewarm(PrewarmJob job) {
		// 编译之前再核对一次前置条件：这一段时间里玩家可能切了包、换了维度或退出了世界。
		if (!ShaderFlashSupport.available()) {
			job.error = new IllegalStateException(ModTexts.raw(ModTexts.ERROR_IRIS_UNAVAILABLE));
			failPrewarm(job);
			return;
		}
		NamespacedId current = Iris.getCurrentDimension();
		if (current == null || !job.dimension.equals(current)) {
			ShaderFlash.LOGGER.info("[ShaderFlash] 预编译期间维度已变化，放弃本次结果");
			cancelPrewarm("维度变化");
			return;
		}
		if (Iris.getCurrentPack().isEmpty()) {
			ShaderFlash.LOGGER.info("[ShaderFlash] 预编译期间主管线被关闭，放弃本次结果");
			cancelPrewarm("主管线已关闭");
			return;
		}

			job.phase = ModTexts.PREWARM_PHASE_COMPILE;
		job.progress = 0.6F;
		building = true;
		long start = System.nanoTime();
		// 程序链接失败时允许“释放后重试一次”（第一次尝试占用的驱动资源会被释放）。
		boolean healthRetry = true;
		try {
			ShaderPack pack = job.pack;
			ProgramSet programSet = pack.getProgramSet(job.dimension);

			WorldSettings snapshot = WorldSettings.capture();
			String fileName = job.fileName;
			IrisRenderingPipeline pipeline;
			// 让 Iris 在“当前包就是这个包”的条件下构造管线。
			//
			// 玩家实测规律：一个光影只有经过 Iris 界面主动加载之后，它作为主/副管线渲染时
			// 实体才正常；直接由我们 new 出来的管线会出现实体花斑。
			// Iris 自己建管线时 Iris.currentPack / currentPackName 指向的正是这个包，
			// 这里把这两个字段临时对齐（构造完立刻还原），消除这个已知差异。
			ShaderPack previousPack = Iris.getCurrentPack().orElse(null);
			String previousPackName = Iris.getCurrentPackName();
			try {
				IrisState.setCurrentPack(pack);
				IrisState.setCurrentPackName(fileName);
				pipeline = new IrisRenderingPipeline(programSet);
			} finally {
				IrisState.setCurrentPack(previousPack);
				IrisState.setCurrentPackName(previousPackName);
				// 构造函数会改写 Iris 的全局材质设置，立刻恢复成主管线那一份。
				// 注意这里**不能**直接还原构造前的快照，理由见 restorePrimarySettings。
				restorePrimarySettings(previousPack, job.dimension, snapshot);
			}

			// Iris 会在第一次 beginLevelRendering 里重设全局材质表并触发全区块重建。
			// 我们提前把它标记成“已初始化”，让副管线那一遍完全不碰全局状态、也不重建区块。
			IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
			accessor.shaderflash$setInitializedBlockIds(true);
			ensureShadowTargets(accessor, pipeline, programSet);

			// 关键一步：**确认这条管线的程序真的链接成功了**。
			//
			// 驱动资源不足时（本机 Intel 核显 + iterationT 这类大包实测），部分程序会链接失败，
			// 而 Iris/原版只打一条 WARN 就继续：日志里是
			//   'GLSL link failed for program 261, "": ... Out of resource error.'
			//   'Error encountered when linking program containing VS sky_textured and FS sky_textured.'
			// 用未链接的程序绘制 = 什么都不输出 → 提升为主管线后手部与实体就“消失”了。
			// 所以这里查一遍；不健康就丢掉这次结果重来一次（第一次尝试占用的资源会被释放），
			// 仍然不健康就明确失败，**绝不把坏管线交给玩家用**。
			List<String> unlinked = PipelineHealth.findUnlinkedPrograms(pipeline);
			if (!unlinked.isEmpty() && healthRetry) {
				ShaderFlash.LOGGER.warn("[ShaderFlash] 副管线 {} 有 {} 个程序没有链接成功（{}）——"
						+ "这是驱动资源不足的典型表现，正在释放并重试一次",
					job.name, unlinked.size(), PipelineHealth.describe(unlinked));
				try {
					pipeline.destroy();
				} catch (Throwable error) {
					ShaderFlash.LOGGER.warn("[ShaderFlash] 释放未链接成功的管线时出错", error);
				}
				healthRetry = false;
				return; // 任务还在 prewarm 里，下一 tick 会重新构造一次
			}
			if (!unlinked.isEmpty()) {
				job.error = new IllegalStateException("shader program link failure: " + PipelineHealth.describe(unlinked));
				ShaderFlash.LOGGER.error("[ShaderFlash] 副管线 {} 的程序链接失败（{}），放弃这条管线："
						+ "提升它会让手部与实体无法渲染（它们用的正是这些程序）",
					job.name, PipelineHealth.describe(unlinked));
				feedback(ModTexts.text(ModTexts.MESSAGE_LINKED_FAILED, job.name, PipelineHealth.describe(unlinked))
					.formatted(Formatting.RED));
				feedback(ModTexts.text(ModTexts.HINT_LINKED_RESOURCES).formatted(Formatting.YELLOW));
				pipeline.destroy();
				failPrewarm(job);
				return;
			}

			long compileMillis = (System.nanoTime() - start) / 1_000_000L;
			long totalMillis = (System.nanoTime() - job.startedNanos) / 1_000_000L;
			String name = job.name;
			secondary = new PackSlot(name, fileName, pack, pipeline, job.dimension, totalMillis);
			lastError = "";
			prewarm = null;
			ShaderFlash.LOGGER.info(
				"[ShaderFlash] 副管线 {} 预编译完成，总用时 {} ms（解包 {} ms / 解析 {} ms / 编译 {} ms）",
				name, totalMillis, job.unpackMillis, job.parseMillis, compileMillis);
			feedback(ModTexts.text(ModTexts.MESSAGE_PREWARMED, name, totalMillis));
			if (swapWhenReady) {
				swapWhenReady = false;
				ShaderFlash.LOGGER.info("[ShaderFlash] 预编译完成，继续玩家之前请求的主副切换");
				swapPrimary();
			}
		} catch (Throwable error) {
			job.error = error;
			failPrewarm(job);
		} finally {
			building = false;
		}
	}

	/**
	 * 把这条管线的「阴影渲染目标」补齐。
	 *
	 * <h2>为什么需要这一步</h2>
	 * Iris 的阴影渲染目标是<b>懒创建</b>的，而且它内部有一处硬假设：Sodium 的地形程序在编译时用
	 * {@code Objects.requireNonNull(shadowRenderTargets)} 取阴影贴图，依据是
	 * “同一个程序的非 Sodium 版早就编译过了，那一步会把阴影目标建出来”。
	 *
	 * <p>这个假设在部分光影包上不成立。MakeUp 的 {@code shaders.properties} 里没有 {@code shadow.enabled}
	 * （于是构造期不建阴影目标），但它的 {@code lib/config.glsl} 默认 {@code #define SHADOW_CASTING}，
	 * 地形着色器里确实声明了 {@code shadowtex} —— 于是 Sodium 懒编译地形程序时直接 NPE 崩游戏
	 * （玩家日志：{@code IrisRenderingPipeline.lambda$new$6(IrisRenderingPipeline.java:351)}
	 * ← {@code SodiumTerrainPipeline.initTerrainSamplers}）。
	 *
	 * <p>补建的前提是「这个包确实带阴影程序」：没有阴影程序的包不需要阴影贴图，不白占显存。
	 */
	private static void ensureShadowTargets(IrisRenderingPipelineAccessor accessor, IrisRenderingPipeline pipeline,
											ProgramSet programSet) {
		boolean hasShadowProgram = programSet.get(ProgramId.Shadow).isPresent();
		ShaderFlash.LOGGER.info("[ShaderFlash] 副管线阴影渲染目标：{}，阴影程序：{}",
			pipeline.hasShadowRenderTargets() ? "已就位" : "缺失",
			hasShadowProgram ? "有" : "无");
		if (pipeline.hasShadowRenderTargets() || !hasShadowProgram) {
			return;
		}
		accessor.shaderflash$getShadowTargetsSupplier().get();
		ShaderFlash.LOGGER.info("[ShaderFlash] 已补建阴影渲染目标（否则 Iris 懒编译地形程序时会 NPE）");
	}

	private static void failPrewarm(PrewarmJob job) {
		prewarm = null;
		swapWhenReady = false;
		Throwable error = job.error != null ? job.error
			: new IllegalStateException(ModTexts.raw(ModTexts.ERROR_UNKNOWN));
		lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
		ShaderFlash.LOGGER.error("[ShaderFlash] 创建副管线失败（{}）", job.name, error);
		feedback(ModTexts.text(ModTexts.MESSAGE_BUILD_FAILED, String.valueOf(error.getMessage())));
		String hint = ShaderErrorHints.hintFor(error.getMessage());
		if (hint != null) {
			feedback(Text.literal(hint).formatted(Formatting.YELLOW));
		}
	}

	/** 放弃当前预编译任务。已经解包的部分留在缓存目录里，下次直接用。 */
	private static void cancelPrewarm(String reason) {
		if (prewarm == null) {
			return;
		}
		ShaderFlash.LOGGER.info("[ShaderFlash] 取消副管线预编译（{}）", reason);
		prewarm = null;
		swapWhenReady = false;
	}

	/** 取消正在进行的预编译（设置界面的“取消”按钮）。 */
	public static void cancelPrewarm() {
		cancelPrewarm("玩家取消");
		swapWhenReady = false;
	}

	public static void destroySecondary(String reason) {
		if (secondary == null) {
			return;
		}
		PackSlot slot = secondary;
		secondary = null;
		release(slot);
		ShaderFlash.LOGGER.info("[ShaderFlash] 释放副管线 {}（{}）", slot.name, reason);
	}

	private static void release(PackSlot slot) {
		try {
			slot.pipeline.destroy();
		} catch (Throwable error) {
			ShaderFlash.LOGGER.warn("[ShaderFlash] 销毁管线 {} 时出错", slot.name, error);
		}
	}

	// ------------------------------------------------------------------
	// 主副互换
	// ------------------------------------------------------------------

	/**
	 * 进入「副管线截图」的取景状态：把<b>副包的材质表</b>临时应用到全局，并让地形按它重建。
	 *
	 * <h2>取景怎么拿到像素</h2>
	 * 0.1.7 及以前是“把屏幕渲染切到副管线 → 拍主帧缓冲 → 切回来”，玩家会看到闪一帧。
	 * 现在取景发生在某一帧的最开头（{@code GameRenderer.renderWorld} 的 HEAD，
	 * 见 {@link #captureSecondaryFrame}）：<b>完全不动管线指针</b>，
	 * 像素要么来自主帧缓冲（「跟随窗口」分辨率）、要么来自模组自己那块离屏目标（高清档位），
	 * 两种情况下这一帧随后的正常渲染都把痕迹覆盖掉。
	 *
	 * <h2>为什么还要切材质表</h2>
	 * 副管线那一遍虽然用的是副包的着色器，但区块网格里烘焙的方块材质 ID 来自当前主管线。
	 * 要让截图等于“用副包玩这个画面”，就得和 F7 一样把材质表换成副包的、并重建一次区块网格，
	 * 否则拍到的地形材质分类是主管线的。做完由 {@link #endSecondaryCapture()} 原样还原。
	 *
	 * @return 是否成功进入取景状态
	 */
	public static boolean beginSecondaryCapture() {
		if (isCaptureActive()) {
			return false;
		}
		PackSlot ghost = secondary;
		if (ghost == null) {
			return false;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return false;
		}
		NamespacedId dimension = Iris.getCurrentDimension();
		if (dimension == null || !ghost.dimension.equals(dimension)) {
			return false;
		}

		WorldSettings.MeshState before = WorldSettings.MeshState.capture();
		capturePrimarySettings = WorldSettings.capture();
			captureBumpedShaderVersion = false;
			captureRebuilt = false;
			captureName = ghost.name;
			ghost.warmupFrames = 0; // 每次取景都从头预热（光影包的“人眼适应”是按时序自己爬的）
		// 先立起标志：下面任何一步抛错，endSecondaryCapture() 才会真的去还原材质表。
		captureActive = true;
		try {
			WorldSettings.applyFor(ghost.pack, ghost.pipeline, dimension, true);
			// 这个标志由我们自己消费：不消费的话 Iris 会在别处触发一次硬重载。
			WorldRenderingSettings.INSTANCE.clearReloadRequired();
			WorldSettings.MeshState after = WorldSettings.MeshState.capture();
			List<String> differences = before.differences(after);

			// 有一类设置是编译期被 Iris 烘焙进地形着色器源码的（分离 AO），变了就必须让地形程序重编。
			if (!differences.isEmpty() && before.requiresShaderRecompile(after)
				&& FabricLoader.getInstance().isModLoaded("sodium")) {
				bumpTerrainShaderVersion();
				captureBumpedShaderVersion = true;
			}
			if (differences.isEmpty()) {
				// 两包在“会烘焙进网格”的设置上一致：现有网格可以直接用，不必重建。
				ShaderFlash.LOGGER.info("[ShaderFlash] 副管线截图：与主管线材质设置一致，无需重建区块");
			} else if (DuoConfig.get().rebuildChunksOnSwitch) {
				// 只有这一轮（取景前切到副包材质）走「截图性能调度」的分批投递。
				captureRebuilt = scheduleTerrainRebuild(
					"副管线截图：切到副包材质（" + String.join("、", differences) + "）", true) > 0;
			} else {
				ShaderFlash.LOGGER.info("[ShaderFlash] 副管线截图：两包差异（{}），但已关闭切换重建，沿用现有区块网格",
					String.join("、", differences));
			}
			return true;
		} catch (Throwable error) {
			ShaderFlash.LOGGER.error("[ShaderFlash] 进入副管线截图取景失败", error);
			endSecondaryCapture();
			return false;
		}
	}

	/** 取景是否在进行中（截图流程用它决定是否强制渲染副管线那一遍）。 */
	public static boolean isCaptureActive() {
		return captureActive;
	}

	/** 还原 {@link #beginSecondaryCapture()} 借走的材质表，并把地形重建回主管线的样子。 */
	public static void endSecondaryCapture() {
		if (!captureActive) {
			return;
		}
		captureActive = false;
		try {
			if (capturePrimarySettings != null) {
				capturePrimarySettings.restore();
			}
			WorldRenderingSettings.INSTANCE.clearReloadRequired();
			if (captureBumpedShaderVersion && FabricLoader.getInstance().isModLoaded("sodium")) {
				// 还原时同样要重编一次地形程序，否则顶点数据与着色器里的 AO 取值对不上。
				bumpTerrainShaderVersion();
			}
			if (DuoConfig.get().rebuildChunksOnSwitch && captureRebuilt) {
					// 只有取景时真的重建过，才需要把地形再建回主管线那一份；
					// 两包材质设置本来就一致时什么都不用做（否则每次截图都会白重建一遍全视距）。
					// 还原属于“玩家马上要看回正常画面”，一次性投完。
					scheduleTerrainRebuild("副管线截图：还原主包材质", false);
			}
		} catch (Throwable error) {
			ShaderFlash.LOGGER.error("[ShaderFlash] 还原主包材质失败", error);
		} finally {
			// 防御性清理，理由同副渲染那一遍的收尾：这个全局标志若残留为 true，
			// Sodium 的区块可见性列表会被切到“阴影用”的那一份，地形会整片消失。
			ShadowRenderer.ACTIVE = false;
			capturePrimarySettings = null;
			captureBumpedShaderVersion = false;
			captureRebuilt = false;
			captureName = "";
		}
	}

	/** 让 Iris 丢掉地形程序缓存并在新取值下重编一次（分离 AO 是同一条管线内编译期烘焙的）。 */
	private static void bumpTerrainShaderVersion() {
		try {
			PipelineManagerAccessor accessor = (PipelineManagerAccessor) Iris.getPipelineManager();
			int counter = accessor.shaderflash$getVersionCounter();
			accessor.shaderflash$setVersionCounter(counter + 1);
			ShaderFlash.LOGGER.info("[ShaderFlash] 已让地形程序重编一次（版本计数器 {} -> {}）", counter, counter + 1);
		} catch (Throwable error) {
			ShaderFlash.LOGGER.warn("[ShaderFlash] 无法重编地形程序", error);
		}
	}

	/**
	 * 把副管线提升为主管线，原来的主管线降为副管线。
	 *
	 * <p>这就是“光影秒切”的入口：两条管线的程序、渲染目标、阴影贴图全程不销毁，
	 * 只是换了个身份。
	 */
	public static void swapPrimary() {
		DuoConfig config = DuoConfig.get();
		if (!config.enabled) {
			feedback(ModTexts.text(ModTexts.MESSAGE_DISABLED_HINT));
			return;
		}
		if (secondary == null && !ensureSecondary(true)) {
			// 副管线还没就绪：预编译已经在后台跑起来了，就等它完成再自动切换，
			// 免得玩家按一次 F7 换来一次几秒钟的卡顿。
			if (prewarm != null) {
				swapWhenReady = true;
				ShaderFlash.LOGGER.info("[ShaderFlash] 副管线尚未就绪，已登记“预编译完成后自动切换”");
			}
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			feedback(ModTexts.text(ModTexts.MESSAGE_NO_WORLD));
			return;
		}
		PackSlot incoming = secondary;
		NamespacedId dimension = Iris.getCurrentDimension();
		if (!incoming.dimension.equals(dimension)) {
			destroySecondary("维度变化");
			if (!ensureSecondary(true)) {
				return;
			}
			incoming = secondary;
			dimension = Iris.getCurrentDimension();
		}

		// 提升之前再确认一次：这条管线的程序都链接上了吗？
		// （见 PipelineHealth：链接失败的程序画不出任何东西，提升它 = 手部与实体消失。）
		List<String> unlinked = PipelineHealth.findUnlinkedPrograms(incoming.pipeline);
		if (!unlinked.isEmpty()) {
			incoming.unhealthyPrograms = true;
			ShaderFlash.LOGGER.error("[ShaderFlash] 拒绝把副管线 {} 提升为主管线：它有 {} 个程序没有链接成功（{}）",
				incoming.name, unlinked.size(), PipelineHealth.describe(unlinked));
			feedback(ModTexts.text(ModTexts.MESSAGE_LINKED_REFUSE,
				incoming.name, PipelineHealth.describe(unlinked)).formatted(Formatting.RED));
			feedback(ModTexts.text(ModTexts.HINT_LINKED_RESOURCES).formatted(Formatting.YELLOW));
			return;
		}
		incoming.unhealthyPrograms = false;

		PipelineManager manager = Iris.getPipelineManager();
		WorldRenderingPipeline oldPrimaryPipeline = manager.getPipelineNullable();
		ShaderPack oldPrimaryPack = Iris.getCurrentPack().orElse(null);
		String oldPrimaryName = Iris.getCurrentPackName();
		if (oldPrimaryName == null) {
			oldPrimaryName = "";
		}

		long start = System.nanoTime();
		try {
			PipelineManagerAccessor accessor = (PipelineManagerAccessor) manager;
			Map<NamespacedId, WorldRenderingPipeline> pipelines = accessor.shaderflash$getPipelinesPerDimension();
			pipelines.put(dimension, incoming.pipeline);
			accessor.shaderflash$setPipeline(incoming.pipeline);
			IrisState.setCurrentPack(incoming.pack);
			// 用真实条目名（带 .zip）：Iris 的界面、配置与重载都会拿它去找包。
			IrisState.setCurrentPackName(incoming.fileName);

			// 材质表/顶点格式跟着新主管线走。
			//
			// 注意不能只依赖 WorldRenderingSettings 里的 reloadRequired 标志：Iris 只在
			// “新建管线”那个分支里消费它，而我们是把已经存在的管线塞进映射表，那个分支不会走，
			// 标志会一直挂着、区块网格永远不会按新包重建（材质 ID 就会和着色器对不上）。
			WorldSettings.MeshState before = WorldSettings.MeshState.capture();
			WorldSettings.applyFor(incoming.pack, incoming.pipeline, dimension, true);
			WorldSettings.MeshState after = WorldSettings.MeshState.capture();
			List<String> meshDifferences = before.differences(after);
			// 这个标志由我们自己负责消费，别留给 Iris 在别处触发一次硬重载。
			WorldRenderingSettings.INSTANCE.clearReloadRequired();

			boolean rebuild = !meshDifferences.isEmpty() && config.rebuildChunksOnSwitch;

			// 有一类设置是在**编译期**被 Iris 烘焙进地形着色器源码的（目前是“分离 AO”，
			// 见 WorldSettings.MeshState#requiresShaderRecompile）。这类设置变了以后，
			// 光靠重建区块网格不够：缓存里的地形程序仍然带着旧包的取值，
			// 顶点数据与着色器对不上（表现是副包切为主包后画面明暗/材质异常）。
			//
			// 这里 bump 一次版本计数器，让 Iris 丢掉地形程序并在新取值下重编。
			// 注意必须与“重建区块网格”同时发生：只重编着色器而不重建顶点数据，
			// 同样会造成两边对不上，反而更糟。
			if (rebuild && before.requiresShaderRecompile(after)
				&& FabricLoader.getInstance().isModLoaded("sodium")) {
				int counter = accessor.shaderflash$getVersionCounter();
				accessor.shaderflash$setVersionCounter(counter + 1);
				ShaderFlash.LOGGER.info("[ShaderFlash] 两包在“会被烘焙进地形着色器”的设置上不同（分离 AO），"
					+ "已让地形程序重编一次（版本计数器 {} -> {}）", counter, counter + 1);
			}

			if (rebuild) {
				remeshFor(client, meshDifferences, before.requiresHardReload(after),
					before.requiresChunkBlockIdRefresh(after));
			} else if (!meshDifferences.isEmpty()) {
				ShaderFlash.LOGGER.info("[ShaderFlash] 两包差异（{}），但已关闭切换重建，沿用现有区块网格",
					String.join("、", meshDifferences));
			}

			// 注意：这里刻意**不**再去 bump versionCounterForSodiumShaderReload。
			// 那个计数器一变，Iris 会调用 IrisChunkProgramOverrides.deleteShaders()，把两条管线的
			// 地形着色器缓存全部丢掉，每次切换都要重编一遍（几百毫秒的卡顿）。
			// 现在由 SodiumTerrainProgramsMixin 按管线切换缓存，两条管线的地形程序都常驻显存。

			// Iris 配置里存的是 shaderpacks 下的真实条目名（例如 xxx.zip）。存去掉扩展名的名字，
			// 重启游戏或按 R 重载时 Iris 会找不到这个包。
			IrisConfig irisConfig = Iris.getIrisConfig();
			irisConfig.setShaderPackName(incoming.fileName);
			irisConfig.save();
		} catch (Throwable error) {
			lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
			ShaderFlash.LOGGER.error("[ShaderFlash] 切换管线失败", error);
			feedback(ModTexts.text(ModTexts.MESSAGE_SWAP_FAILED, String.valueOf(error.getMessage())));
			return;
		}
		long millis = (System.nanoTime() - start) / 1_000_000L;
		lastSwapMillis = millis;

		if (config.keepOldPrimaryAsSecondary && oldPrimaryPipeline != null && oldPrimaryPack != null
			&& !oldPrimaryName.isEmpty()) {
			// 老主管线直接留作新的副管线：反向切换同样零编译。
			secondary = new PackSlot(oldPrimaryName, oldPrimaryName, oldPrimaryPack, oldPrimaryPipeline,
				dimension, incoming.buildMillis);
		} else {
			secondary = null;
		}

		String saved = incoming.buildMillis >= 0 ? (incoming.buildMillis + " ms") : "?";
		ShaderFlash.LOGGER.info("[ShaderFlash] 已切换到 {}，耗时 {} ms（重新编译这条管线本来要 {}）",
			incoming.name, millis, saved);
		feedback(ModTexts.text(ModTexts.MESSAGE_SWAPPED, incoming.name, millis, saved));
	}

	/**
	 * 两个包在“会影响区块网格”的设置上不同时才需要重做网格。
	 *
	 * <p>Sodium 环境下走逐区块排队重建（玩家看不到卸载）；否则退回 Iris 自己那条
	 * {@code reload()} 硬重建。
	 */
	private static void remeshFor(MinecraftClient client, List<String> differences, boolean hardReloadRequired,
								  boolean blockIdsChanged) {
		String reason = String.join("、", differences);
		if (hardReloadRequired) {
			client.worldRenderer.reload();
			ShaderFlash.LOGGER.info("[ShaderFlash] 顶点格式变化（{}），已整体重建区块网格", reason);
			return;
		}
		boolean sodium = FabricLoader.getInstance().isModLoaded("sodium");
		int scheduled = -1;
		if (sodium && !config().seamlessRemesh) {
			sodium = false; // 玩家显式关掉了“逐区块后台重建”，退回硬重建
		}
		if (sodium && blockIdsChanged && !DuoMixinPlugin.canRefreshChunkBlockIds()) {
			// 这个环境里逐区块重建**拿不到正确的方块 ID 表**：Iris 在 ChunkBuildBuffers
			// 构造时就把表捕获进 BlockContextHolder，而该对象的生命周期是整个
			// RenderSectionManager。只有重建 RenderSectionManager（整世界重载）才能刷新它。
			// 不退回硬重载的话，重建出来的网格会一直带着旧包的材质 ID。
			sodium = false;
			ShaderFlash.LOGGER.warn("[ShaderFlash] 当前环境无法让逐区块重建使用新的方块材质表，"
				+ "退回整世界重载以免区块网格一直使用旧包的材质 ID");
		}
		if (sodium) {
			try {
				// 主副互换是“玩家马上要看结果”的路径：一次性投完，不受截图性能调度影响。
				scheduled = SodiumRemesh.prepare(client, false);
			} catch (Throwable error) {
				ShaderFlash.LOGGER.warn("[ShaderFlash] 逐区块重建失败，退回整体重建", error);
			}
		}
		if (scheduled > 0) {
			lastRemeshNanos = System.nanoTime();
			// 新的一轮重建：标记成“还没做完”，由 tick 里的跟踪逻辑在“忙 → 空”之后清掉。
			remeshBusySeen = false;
			remeshSettled = false;
			try {
				SodiumRemesh.submitBatch(Integer.MAX_VALUE);
			} catch (Throwable error) {
				ShaderFlash.LOGGER.warn("[ShaderFlash] 区块重建投递失败", error);
			}
			ShaderFlash.LOGGER.info("[ShaderFlash] 两包差异（{}），已把 {} 个区块段排队后台重建（无可见卸载）",
				reason, scheduled);
		} else {
			client.worldRenderer.reload();
			ShaderFlash.LOGGER.info("[ShaderFlash] 两包差异（{}），已整体重建区块网格", reason);
		}
	}

	private static DuoConfig config() {
		return DuoConfig.get();
	}

	/**
	 * 让地形按“当前全局材质表”重建一次（副管线截图路径用）。
	 *
	 * <p>与 {@link #remeshFor} 的区别：这里不比较两包差异，直接按当前表重排一遍，
	 * 并把重建状态标记成“未完成”，供 {@link ScreenshotCapture} 等待。
	 *
	 * @param batched 是否按「截图性能调度」的预算分批投递。
	 *                <b>只有截图取景那一轮传 true</b>：那时玩家正看着进度条，分批能让前台平稳；
	 *                其余（F7 主副互换、截图后的材质还原）一律一次性投完，不受该设置影响。
	 * @return 实际排队的区块段数量；0 表示这条路走不通（没装 Sodium / 不在世界里 / 排队失败）
	 */
	private static int scheduleTerrainRebuild(String reason, boolean batched) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || !FabricLoader.getInstance().isModLoaded("sodium")) {
			return 0;
		}
		try {
			int scheduled = SodiumRemesh.prepare(client, batched);
			if (scheduled > 0) {
				lastRemeshNanos = System.nanoTime();
				remeshBusySeen = false;
				remeshSettled = false;
				// 立刻投出第一批：否则“先忙后空”的判定会先看到空队列。
				SodiumRemesh.submitBatch(batched ? remeshBatchBudget() : Integer.MAX_VALUE);
				if (batched) {
					ShaderFlash.LOGGER.info("[ShaderFlash] {}：已把 {} 个区块段按「{}」分批排队重建",
						reason, scheduled, DuoConfig.get().policyName());
				} else {
					ShaderFlash.LOGGER.info("[ShaderFlash] {}：已把 {} 个区块段排队后台重建", reason, scheduled);
				}
			}
			return Math.max(0, scheduled);
		} catch (Throwable error) {
			ShaderFlash.LOGGER.warn("[ShaderFlash] {}：排队重建失败", reason, error);
			return 0;
		}
	}

	// ------------------------------------------------------------------
	// 副管线的离屏渲染与截图取景
	// ------------------------------------------------------------------

	/**
	 * 挂在 {@code GameRenderer.renderWorld} 的入口：把此刻 PoseStack 顶层的姿态记下来。
	 *
	 * <p>必须在这里记——方法跑起来之后它会就地改写这个栈（相机旋转），
	 * 到尾部就取不回“入口时的姿态”了。
	 */
	public static void captureEntryPose(MatrixStack matrices) {
		try {
			MatrixStack.Entry entry = matrices.peek();
			entryPose = new Matrix4f(entry.getPositionMatrix());
			entryNormal = new Matrix3f(entry.getNormalMatrix());
		} catch (Throwable error) {
			// 取不到就退回旧行为（尾部直接用调用方的栈），至少不崩
			entryPose = null;
			entryNormal = null;
		}
	}

	/**
	 * 挂在 {@code GameRenderer.renderWorld} 的尾部：<b>新管线建好之后补渲染一次</b>（输出进离屏目标）。
	 *
	 * <p>0.2.2 起不再有“每帧 / 每 N 帧”的周期渲染：副管线平时一帧都不画，
	 * 只在「副管线截图」时按需渲染。这里这一次不是可有可无的 —— 刚构造好的管线第一遍渲染时，
	 * Iris 的 composite/final 翻转状态还没跑起来，由此会有两个后果：
	 * ① 按 F7 切过去的第一帧可能是黑的；② 截图取景本来就要求“这条管线至少渲染过一整帧”。
	 * 渲染一次就够，之后让它安静待着。
	 *
	 * <p>0.2.5 起它是一个<b>可回退的调试开关</b>（{@code initialWarmupRender}）：关掉就省掉这一遍，
	 * 副管线会“零帧就绪”（预编译刚结束时更顺），但要承担上面两个后果。出事把开关打回去即可。
	 */
	public static void onWorldRenderTail(GameRenderer renderer, float tickDelta, long limitTime, MatrixStack matrices) {
		if (ghostPassActive || ghostBroken) {
			return;
		}
		DuoConfig config = DuoConfig.get();
		if (!config.enabled) {
			return;
		}
		PackSlot ghost = secondary;
		if (ghost == null || ghost.renderedOnce) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) {
			return;
		}
		if (!config.initialWarmupRender) {
			// 调试分支：不补这一帧。副管线就这样“零帧待命”，
			// 直到第一次取景（截图会自己渲染预热帧）或按 F7 切过去才真正画第一帧。
			if (!ghost.initialRenderSkipped) {
				ghost.initialRenderSkipped = true;
				ShaderFlash.LOGGER.info("[ShaderFlash] 副管线 {}：已按调试开关跳过初始化渲染"
					+ "（第一次切换/取景时才真正画第一帧）", ghost.name);
			}
			return;
		}
		Framebuffer warmupTarget = acquireOffscreen(client.getFramebuffer().textureWidth,
			client.getFramebuffer().textureHeight);
		if (warmupTarget != null) {
			long start = System.nanoTime();
			renderSecondaryPass(renderer, tickDelta, limitTime, matrices, ghost, warmupTarget, null, true, false);
			ShaderFlash.LOGGER.info("[ShaderFlash] 副管线 {}：初始化渲染完成（{} ms）",
				ghost.name, (System.nanoTime() - start) / 1_000_000L);
		}
	}

	/**
	 * 「副管线截图」的取景：在<b>一帧的最开头</b>（{@code GameRenderer.renderWorld} 的 HEAD）
	 * 用副管线渲染一遍世界，紧接着读像素存盘。本帧随后的正常渲染会把它整个覆盖掉，
	 * 所以玩家一帧都看不到。
	 *
	 * <h2>画到哪块缓冲</h2>
	 * <ul>
	 *   <li><b>分辨率跟随窗口</b>（默认）：直接画进主帧缓冲 —— 与“按 F7 切过去”看到的
	 *       是同一套路径，这是最稳的一条；</li>
	 *   <li><b>自定义分辨率</b>：画进<b>副管线自己的离屏目标</b>（尺寸 = 目标分辨率），
	 *       Iris 判断“当前是不是主缓冲”用的是 {@code MinecraftClient.getFramebuffer()}
	 *       （见 Iris 的 {@code MixinRenderTarget}），而这个调用在离屏渲染期间被本模组接管，
	 *       所以 Iris 的 begin/composite/final 会照着这块离屏目标走 —— 与画进主帧缓冲语义相同，
	 *       只是分辨率不同。<b>真实主帧缓冲自始至终保持窗口尺寸，一个像素都不动。</b></li>
	 * </ul>
	 *
	 * <p>自定义分辨率那一遍里，原版那些“按窗口尺寸取尺寸”的东西也要跟着走：除了让
	 * {@code Window} 上报目标尺寸，还要用 {@code WorldRenderer.onResized} 把原版的两个后期处理
	 * 缓冲（实体描边 / 观察者透明）调到同一尺寸 —— 它们会在 pass 里设置“自己缓冲尺寸”的视口，
	 * 而原版渲染完并不还视口，这正是 0.2.2 高清图上“手部按窗口尺寸落位”的根因。
	 *
	 * <p>历史：0.1.8/0.1.9 直接读常驻离屏 FBO 时拿到过“只有天空底色 / 没走完 final”的画面。
	 * 根因是当时没有在渲染前先换绑目标（本帧的 {@code clear} 会擦掉/错位），
	 * 现在 {@link #renderSecondaryPass} 里那一步 {@code target.beginWrite(true)} 就是那个修复。
	 */
	public static void captureSecondaryFrame(GameRenderer renderer, float tickDelta, long limitTime,
											 MatrixStack matrices) {
		if (ghostPassActive || !ScreenshotCapture.isPending()) {
			return;
		}
		PackSlot ghost = secondary;
		MinecraftClient client = MinecraftClient.getInstance();
		if (ghost == null || client.world == null || client.player == null) {
			// 副管线在等待期间没了（被关掉/释放）：取消本次截图并还原材质表，别一直挂着。
			ScreenshotCapture.shootNow(null, false);
			return;
		}
		if (!ghost.name.equals(captureName)) {
			// 等待期间玩家自己切了主副：这次的取景对象已经变了，直接取消，免得拍错包。
			ShaderFlash.LOGGER.warn("[ShaderFlash] 副管线截图：等待期间副管线变成了 {}，本次取消", ghost.name);
			ScreenshotCapture.shootNow(null, false);
			return;
		}
		if (!ScreenshotCapture.shouldShootNow()) {
			// 还没到取景时机（地形还在重建 / 刚稳定还要再等 500 ms）：这一帧不渲染副管线。
			return;
		}
		// 目标分辨率（见 CaptureResolution）：两个输入都留空 = 跟随窗口，渲染进主帧缓冲；
		// 否则渲染进**副管线自己的**离屏目标（主帧缓冲全程不动）。
		int windowWidth = client.getWindow().getFramebufferWidth();
		int windowHeight = client.getWindow().getFramebufferHeight();
		DuoConfig config = DuoConfig.get();
		int[] targetSize = CaptureResolution.resolve(windowWidth, windowHeight,
			config.screenshotWidth(), config.screenshotHeight());
		boolean wantsCustom = targetSize[0] != windowWidth || targetSize[1] != windowHeight;
		Framebuffer offscreen = null;
		if (wantsCustom) {
			if (CaptureResolution.beyondWarningBudget(targetSize[0], targetSize[1])) {
				// 只是警告：玩家有权渲染这么大，失败/极慢由他自己承担（见 docs/08）。
				ShaderFlash.LOGGER.warn("[ShaderFlash] 副渲染截图：目标 {}×{}（约 {} MP）超过建议值，"
						+ "可能因显存不足失败或非常慢",
					targetSize[0], targetSize[1], CaptureResolution.megapixels(targetSize[0], targetSize[1]));
			}
			offscreen = acquireOffscreen(targetSize[0], targetSize[1]);
			if (offscreen == null) {
				ShaderFlash.LOGGER.warn("[ShaderFlash] 副管线截图：无法创建 {}×{} 的离屏目标，本次按窗口分辨率拍摄",
					targetSize[0], targetSize[1]);
			}
		}
		// 建不出离屏目标就退回窗口分辨率（渲染进主帧缓冲），别把这一次截图弄丢。
		boolean custom = offscreen != null;
		int[] passSize = custom ? targetSize : null;
		// 到了取景时机，**先连续渲染若干帧再按快门**：
		//   * 至少 1 帧是必须的（Iris 的 composite/final 翻转状态要跑起来）；
		//   * 玩家实测：MakeUp 这类包刚加载时整屏极暗，要连续渲染 10~20 帧、
		//     等它自己那套“人眼适应”把亮度抬起来，画面才可用——只渲染 1 帧就会拍到那张
		//     “概率性偏黑”的画面。这是光影包的显示策略，与本模组无关（见 docs/03、docs/17）。
		// 预热必须紧贴快门，所以放在 shouldShootNow() 之后（等地形重建期间不渲染，
		// 免得适应状态在等待中又掉下去）。
		// 预热必须与快门用同一个目标（同一分辨率）：Iris 在尺寸变化时会重建渲染目标，
		// 重建会清掉光影包自己累积的“人眼适应”历史，那样又被拍成偏黑的画面。
		int warmupRequired = Math.max(1, config.screenshotWarmupFrames);
		if (!ghost.renderedOnce || ghost.warmupFrames < warmupRequired) {
			// 预热帧（自定义分辨率时）走离屏目标：尺寸与快门那一遍完全一致，
			// 但不动主帧缓冲（主画面在这几十帧里照常出画），也**不画手部** ——
			// 手部对光影包的时域适应没有意义，留着只会多一份开销。
			renderSecondaryPass(renderer, tickDelta, limitTime, matrices, ghost, offscreen,
				passSize, !custom, false);
			ghost.warmupFrames++;
			return;
		}
		// 快门帧：同样画进副管线自己的离屏目标（与预热同一块、同一尺寸，光影包的时域历史不受影响）。
		// 读像素在这一遍内部完成（shoot 参数），读完再由调用方收尾。
		boolean rendered = renderSecondaryPass(renderer, tickDelta, limitTime, matrices, ghost, offscreen,
			passSize, true, true);
		if (!rendered) {
			// 这一遍没能渲染（管线被换掉 / 注入点失效 / 抛异常）：取消本次截图，别让它一直挂着。
			ScreenshotCapture.shootNow(null, false);
		}
	}

	/**
	 * 用副管线整帧渲染一遍世界。
	 *
	 * @param offscreen 非 null = 输出到这块离屏目标（副管线自己的目标，尺寸可以大于窗口）；
	 *                  null = 输出到主帧缓冲（「跟随窗口」的截图取景走这条，与旧版行为一致）
	 * @param targetSize 这一遍要渲染成多大；null = 用目标当前尺寸。
	 *                   <b>只会调整离屏目标自己的尺寸</b>，绝不碰真实主帧缓冲。
	 * @param drawHand 这一遍要不要画手部。预热帧不画（手部对光影包的时域适应没有意义，
	 *                 而它一旦被画错位置（见 {@link ViewportGuard}）就会留在历史里变成鬼影）。
	 * @param shoot 渲染成功后要不要立刻按快门（读像素存盘）。<b>必须在这一遍内部做</b>：
	 *              高清截图会临时放大主帧缓冲，读像素要发生在尺寸被还原之前。
	 * @return 这一遍是否真的渲染完成
	 */
	private static boolean renderSecondaryPass(GameRenderer renderer, float tickDelta, long limitTime,
											   MatrixStack matrices, PackSlot ghost, Framebuffer offscreen,
											   int[] targetSize, boolean drawHand, boolean shoot) {
		MinecraftClient client = MinecraftClient.getInstance();
		PipelineManager manager = Iris.getPipelineManager();
		WorldRenderingPipeline primary = manager.getPipelineNullable();
		if (primary == null || primary == ghost.pipeline || primary instanceof VanillaRenderingPipeline) {
			return false;
		}
		NamespacedId dimension = Iris.getCurrentDimension();
		if (dimension == null || !ghost.dimension.equals(dimension)) {
			// 换维度了：本帧跳过，交给 tick 里的重建逻辑。
			return false;
		}

		PipelineManagerAccessor accessor;
		WorldRendererAccessor worldRenderer;
		try {
			accessor = (PipelineManagerAccessor) manager;
			worldRenderer = (WorldRendererAccessor) client.worldRenderer;
		} catch (ClassCastException error) {
			// mixin 没生效——继续画只会把副管线的画面糊到屏幕上，必须停。
			disableGhost(ModTexts.str(ModTexts.ERROR_INJECTION_MISSING, error.getMessage()));
			return false;
		}

		Framebuffer realTarget = client.getFramebuffer();
		boolean offscreenPass = offscreen != null;
		Framebuffer target = offscreenPass ? offscreen : realTarget;
		int wantedWidth = targetSize != null ? targetSize[0] : target.textureWidth;
		int wantedHeight = targetSize != null ? targetSize[1] : target.textureHeight;

		// 尺寸处理：**只动副管线自己的离屏目标**。
		// 主帧缓冲自始至终保持窗口尺寸（0.2.4 起刻意如此：临时放大主帧缓冲会牵动全局状态，
		// 兼容性风险太大）。为了让原版那些“按窗口尺寸”的东西在这一遍里也按目标尺寸走，
		// 需要做两件事：
		//   ① Window 在这一次渲染里上报目标尺寸（原版的视口与投影都取自它）；
		//   ② 原版那两个后期处理缓冲（实体描边 / 观察者透明）也调到目标尺寸 —— 它们按窗口尺寸
		//      建缓冲、在 pass 里设置“自己缓冲尺寸”的视口，而渲染完并不还视口
		//      （WorldRenderer 里 entityOutlinePostProcessor.render(...) 之后只 beginWrite(false)），
		//      这正是高清图上“手部按窗口尺寸落位”的来源。
		Window window = client.getWindow();
		int savedWindowWidth = window.getFramebufferWidth();
		int savedWindowHeight = window.getFramebufferHeight();
		int savedTargetWidth = target.textureWidth;
		int savedTargetHeight = target.textureHeight;
		boolean resizeTarget = offscreenPass
			&& (savedTargetWidth != wantedWidth || savedTargetHeight != wantedHeight);
		boolean resizeWindow = savedWindowWidth != wantedWidth || savedWindowHeight != wantedHeight;
		// 只有“按快门”那一遍需要连原版的后期处理缓冲一起调尺寸：那两块缓冲会在 pass 里
		// 设置“自己缓冲尺寸”的视口而且不还，正好发生在手部之前。预热那一遍不画手部，
		// 末尾画的东西也不进光影包的历史，所以不必付这份重新分配的代价。
		boolean matchVanillaBuffers = shoot && resizeWindow;

		// 预热帧不画手部：需要临时把 GameRenderer 的手部开关关掉（拿不到 accessor 就算了）。
		boolean previousRenderHand = true;
		boolean handToggleAvailable = false;
		if (!drawHand) {
			try {
				GameRendererAccessor handAccessor = (GameRendererAccessor) renderer;
				previousRenderHand = handAccessor.shaderflash$getRenderHand();
				handToggleAvailable = true;
			} catch (ClassCastException ignored) {
				handToggleAvailable = false;
			}
		}

		Map<NamespacedId, WorldRenderingPipeline> pipelines = accessor.shaderflash$getPipelinesPerDimension();
		WorldRenderingPipeline previousEntry = pipelines.get(dimension);
		boolean previousCulling = client.chunkCullingEnabled;
		Frustum previousFrustum = worldRenderer.shaderflash$getFrustum();

		// Iris 的这几个**全局静态**状态决定“顶点缓冲要不要按扩展格式建立 / 绑定”，
		// 以及“当前正在画的是哪个实体”。副渲染那一遍会用另一条管线把它们改成自己的值，
		// 正常路径下会被对称改回来；但只要中间有任何异常路径（例如某个 endBatch 抛错），
		// 就会留下“数据是扩展格式、绑定时按普通格式”的错配——
		// 表现正是实体表面出现杂色/花斑。
		//
		// 这里显式保存/还原一层，保证副渲染那一遍绝不把状态泄漏给主画面。
		boolean previousRenderLevel = ImmediateState.isRenderingLevel;
		boolean previousExtendedFormat = ImmediateState.renderWithExtendedVertexFormat;
		int previousEntityId = CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
		int previousBlockEntityId = CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity();
		int previousItemId = CapturedRenderingState.INSTANCE.getCurrentRenderedItem();
		// 手部渲染标志与「深度/颜色写入锁」同样会决定实体的绘制结果：
		// 前者若残留为 true，实体渲染类型会被 Iris 当成手部（HAND_* 程序）去画；
		// 后者（DepthColorStorage）一旦被锁住，colorMask/depthMask 全为 false，
		// 之后所有绘制都不会写出任何像素——表现就是“实体画了但看不见”。
		boolean previousHandActive = handRendererActive();
		boolean previousDepthColorLocked = DepthColorStorage.isDepthColorLocked();

		ghostPassActive = true;
		boolean rendered = false;
		long start = System.nanoTime();
		try {
			if (resizeTarget) {
				// 只调整副管线自己的离屏目标，主帧缓冲一个像素都不动。
				target.resize(wantedWidth, wantedHeight, true);
			}
			if (resizeWindow) {
				// 让原版在这一遍里按目标尺寸工作（它到处都从这里取帧缓冲尺寸）。
				window.setFramebufferWidth(wantedWidth);
				window.setFramebufferHeight(wantedHeight);
			}
			if (matchVanillaBuffers) {
				// 让原版的后期处理缓冲也按目标尺寸准备：它们的 pass 会设置一个
				// “自己缓冲尺寸”的视口，而原版渲染完并不还视口 —— 这是高清图上
				// “手部按窗口尺寸落位”的根因。
				client.worldRenderer.onResized(wantedWidth, wantedHeight);
			}
			if (!drawHand && handToggleAvailable) {
				renderer.setRenderHand(false);
			}
			// 视口护栏：这一遍里若有谁把视口设回窗口尺寸，改写成这一遍真正的目标尺寸。
			ViewportGuard.arm(wantedWidth, wantedHeight, savedWindowWidth, savedWindowHeight);
			pipelines.put(dimension, ghost.pipeline);
			accessor.shaderflash$setPipeline(ghost.pipeline);
			client.chunkCullingEnabled = !ghost.pipeline.shouldDisableOcclusionCulling();
			if (offscreenPass) {
				GhostFramebuffer.arm();
			}

			// 关键一步：先把目标 FBO 绑上。
			//
			// 原版世界渲染在真正画东西之前会调用一次 RenderSystem.clear(...)，而它清的是
			// “当前绑定”的帧缓冲；Iris 绑定自己的渲染目标是发生在这次 clear 之后
			// （iris$beginLevelRender 注入在 clear 之后）。上一遍主渲染结束时绑定的正是主帧缓冲
			// 的颜色附件，所以如果不先换绑，这一遍的 clear 会把刚画好的主画面擦成背景色 ——
			// 表现就是“屏幕上只剩天空底色”，节流模式下则变成画面与纯色高频闪烁。
			target.beginWrite(true);

			// 同一帧、同一相机、同一 tickDelta 再渲染一遍；区别只有管线与输出目标。
			//
			// PoseStack 不能用调用方那一个：renderWorld 已经就地改写了它（相机旋转），
			// 直接复用会把相机旋转叠加两次（见 CHANGELOG 0.1.1 ①）。
			// 也不能在入口 push 一层再在尾部 pop：原版 LevelRenderer.renderLevel 中段会调
			// checkPoseStack()，要求栈里只有 1 帧，push 会让它直接抛
			// "Pose stack not empty" 崩游戏（0.1.1 初版就是这么坏的）。
			// 正确做法：另建一个内容等于“入口状态”的栈，原版那个栈一个字都不动。
			renderer.renderWorld(tickDelta, limitTime, ghostMatrices(matrices));

			if (offscreenPass && !GhostFramebuffer.wasConsumed() && !mixinVerified) {
				// getFramebuffer() 没有被拦下来，说明这一遍其实是画在屏幕上的。
				disableGhost(ModTexts.raw(ModTexts.ERROR_REDIRECT_FAILED));
			} else {
				if (offscreenPass) {
					mixinVerified = true;
				}
				rendered = true;
				// 记下“这条管线已经真的渲染过”。截图取景要求它至少渲染过一次：
				// 刚建好的管线第一遍渲染时，composite/final 那一套翻转状态还没跑起来，
				// 抓到的帧可能是纯黑的（见 ScreenshotCapture 的说明）。
				ghost.renderedOnce = true;
				if (shoot) {
					// 关键：趁尺寸还没还原、这一遍的像素还在这块目标里，先把它们读走。
					ScreenshotCapture.shootNow(target, true);
				}
			}
		} catch (Throwable error) {
			Throwable cause = error instanceof CompletionException && error.getCause() != null
				? error.getCause() : error;
			ShaderFlash.LOGGER.error("[ShaderFlash] 副管线渲染失败（{}）", offscreenPass ? "离屏那一边" : "截图取景", cause);
			if (offscreenPass) {
				// 离屏那一遍失败要停用整个功能；直接画进主帧缓冲的取景失败只是一次截图没拍成。
				disableGhost(ModTexts.str(ModTexts.ERROR_RENDER_FAILED, cause.getClass().getSimpleName()));
			}
		} finally {
			ViewportGuard.disarm();
			if (offscreenPass) {
				GhostFramebuffer.disarm();
			}
			if (!drawHand && handToggleAvailable) {
				renderer.setRenderHand(previousRenderHand);
			}
			if (resizeWindow) {
				// 先把原版的后期处理缓冲还原回窗口尺寸，再把 Window 上报的尺寸还原：
				// 后面这一帧的正常渲染、HUD、以及 MinecraftClient 最后把主帧缓冲贴到窗口，
				// 用的都是原来的尺寸。主帧缓冲本身在这一遍里从未被改动。
				if (matchVanillaBuffers) {
					client.worldRenderer.onResized(savedWindowWidth, savedWindowHeight);
				}
				window.setFramebufferWidth(savedWindowWidth);
				window.setFramebufferHeight(savedWindowHeight);
			}
			ghostPassActive = false;
			if (previousEntry == null) {
				pipelines.remove(dimension);
			} else {
				pipelines.put(dimension, previousEntry);
			}
			accessor.shaderflash$setPipeline(primary);
			// 把绘制目标交还给主帧缓冲：副渲染那一遍结束时绑定的是它自己的离屏目标，
			// 不还回去的话 HUD 等后续绘制会画进那块离屏目标（HUD 会“消失”）。
			// 此时管线已经换回主管线，所以 Iris 的“主缓冲是否绑定”状态也会跟着正确刷新。
			realTarget.beginWrite(true);
			client.chunkCullingEnabled = previousCulling;
			worldRenderer.shaderflash$setFrustum(previousFrustum);
			// 防御性清理：万一副渲染那一遍的阴影阶段异常退出，这个全局标志会把主渲染的
			// 区块可见性列表切到“阴影用”的那一份，表现就是地形整片消失。
			ShadowRenderer.ACTIVE = false;
			// 同上：把 Iris 的即时状态原样交还给主画面。
			ImmediateState.isRenderingLevel = previousRenderLevel;
			ImmediateState.renderWithExtendedVertexFormat = previousExtendedFormat;
			CapturedRenderingState.INSTANCE.setCurrentEntity(previousEntityId);
			CapturedRenderingState.INSTANCE.setCurrentBlockEntity(previousBlockEntityId);
			CapturedRenderingState.INSTANCE.setCurrentRenderedItem(previousItemId);
			// 这两个只在“副渲染那一遍把它弄脏了”时才恢复，健康路径下是空操作。
			if (!previousDepthColorLocked && DepthColorStorage.isDepthColorLocked()) {
				DepthColorStorage.unlockDepthColor();
				ShaderFlash.LOGGER.warn("[ShaderFlash] 副渲染那一遍把「深度/颜色写入锁」留在了锁定状态，已解锁"
					+ "（否则之后画的所有东西都不会写出像素）");
			}
			if (previousHandActive != handRendererActive()) {
				setHandRendererActive(previousHandActive);
				ShaderFlash.LOGGER.warn("[ShaderFlash] 副渲染那一遍把「手部渲染」标志留在了 {}，已还原为 {}",
					!previousHandActive, previousHandActive);
			}
			lastGhostNanos = System.nanoTime() - start;
		}
		if (shoot && ViewportGuard.rewrites() > 0) {
			// 这条日志是“护栏有没有命中”的证据：命中说明确实有代码把视口设回了窗口尺寸
			// （高清截图手部/实体错位就是这么来的），护栏已经把它改写成目标尺寸。
			ShaderFlash.LOGGER.info("[ShaderFlash] 副渲染那一遍：视口被设回窗口尺寸 {} 次，已改写为 {}×{}",
				ViewportGuard.rewrites(), wantedWidth, wantedHeight);
		}
		return rendered;
	}

	private static boolean handRendererActive() {
		try {
			return ((dev.shaderflash.mixin.HandRendererAccessor) HandRenderer.INSTANCE).shaderflash$getActive();
		} catch (Throwable ignored) {
			return false; // accessor 没生效时不影响渲染
		}
	}

	private static void setHandRendererActive(boolean active) {
		try {
			((dev.shaderflash.mixin.HandRendererAccessor) HandRenderer.INSTANCE).shaderflash$setActive(active);
		} catch (Throwable ignored) {
			// 同上
		}
	}

	/**
	 * 把 Iris 的<b>全局材质设置</b>恢复成“主管线那一份”。
	 *
	 * <h2>为什么不能直接还原“构造前的快照”</h2>
	 * 世界刚加载 / Iris 刚重载时，「进世界自动预编译」会在<b>主管线还没构造出来</b>的那几个 tick 里跑：
	 * {@link #ensureSecondary} 只要求 Iris 已经读到光影包（{@code Iris.getCurrentPack()}），
	 * 并不要求管线就绪。那一刻 {@code WorldRenderingSettings} 里可能还是默认值 ——
	 * 方块材质表为 {@code null}、实体/物品 ID 表为 {@code null}、
	 * {@code useExtendedVertexFormat = false}。照快照还原就会把这些默认值写回去，
	 * 而 Iris 只在“新建管线”时设置这几个值（{@code beginLevelRendering} 里那句只在
	 * {@code !initializedBlockIds} 时执行，而副管线是提前标记成已初始化的），
	 * 于是主管线之后一直用错误的值渲染：实体顶点按<b>原版格式</b>写出、着色器却是
	 * <b>光影包的</b>（按扩展格式读属性）→ 实体渲染不出来。
	 *
	 * <p>这也正好解释了玩家实测的规律：进世界自动预编译的那条管线有问题，
	 * 游戏中用 F8 手动重建的就没事 —— 那时全局值一定是主管线的，快照/还原等于空操作。
	 *
	 * <p>所以这里改成按<b>主管线的包</b>重放一遍：无论构造先后，值都与主管线一致。
	 * 拿不到主管线包时才退回快照（此时快照至少不会比默认值更糟）。
	 */
	private static void restorePrimarySettings(ShaderPack primaryPack, NamespacedId dimension,
											   WorldSettings fallback) {
		WorldRenderingSettings target = WorldRenderingSettings.INSTANCE;
		if (primaryPack == null) {
			fallback.restore();
			return;
		}
		WorldSettings.applyFor(primaryPack, Iris.getPipelineManager().getPipelineNullable(), dimension, false);
		// 值与主管线一致时不该留下“需要重载”的信号（副管线构造过程会把它置上）。
		target.clearReloadRequired();
		if (ShaderFlash.LOGGER.isDebugEnabled()) {
			ShaderFlash.LOGGER.debug("[ShaderFlash] 预编译后全局材质设置：扩展顶点格式={}，方块材质表={}，实体ID表={}",
				target.shouldUseExtendedVertexFormat(), target.getBlockStateIds() != null,
				target.getEntityIds() != null);
		}
	}

	/**
	 * 安全网：只要当前管线是<b>光影管线</b>，全局的「扩展顶点格式」就必须是 {@code true}。
	 *
	 * <p>这是 Iris 自己的不变量 —— {@code IrisRenderingPipeline} 的构造函数设成 true，
	 * 只有 {@code VanillaRenderingPipeline}（没装/没开光影）才设成 false。
	 * 一旦它被谁写坏，实体顶点会按<b>原版</b>格式写出、却被光影包的着色器按<b>扩展</b>格式读取，
	 * 表现就是“实体渲染不出来”（顶点属性全部错位）。
	 *
	 * <p>副管线预编译会在构造期间改写这些全局值，历史上还出现过“按过期快照还原”把默认值写回去的
	 * 路径（见 {@link #restorePrimarySettings}）。这里每 tick 校正一次，代价只是一个 boolean 比较；
	 * 真的命中时会打一条 WARN，方便判断还有没有别的路径在写坏它。
	 */
	private static void keepExtendedVertexFormat() {
		try {
			if (WorldRenderingSettings.INSTANCE.shouldUseExtendedVertexFormat()) {
				return;
			}
			if (!(Iris.getPipelineManager().getPipelineNullable() instanceof ShaderRenderingPipeline)) {
				return; // 没有光影包：这个值本来就该是 false
			}
			WorldRenderingSettings.INSTANCE.setUseExtendedVertexFormat(true);
			WorldRenderingSettings.INSTANCE.clearReloadRequired();
			if (!extendedFormatWarningLogged) {
				extendedFormatWarningLogged = true;
				ShaderFlash.LOGGER.warn("[ShaderFlash] 全局「扩展顶点格式」被写成了 false（当前是光影管线）——"
					+ "已就地校正回 true；不校正的话实体会按原版顶点格式绘制、渲染不出来");
			}
		} catch (Throwable ignored) {
			// 保险性质的检查，出问题也不该影响渲染
		}
	}

	/** 自检用：这条管线的程序都链接上了吗。 */
	private static String healthText(WorldRenderingPipeline pipeline) {
		if (pipeline == null) {
			return ModTexts.raw(ModTexts.VALUE_NONE_SHORT);
		}
		try {
			List<String> unlinked = PipelineHealth.findUnlinkedPrograms(pipeline);
			return unlinked.isEmpty() ? ModTexts.raw(ModTexts.HEALTH_ALL_LINKED)
				: ModTexts.str(ModTexts.HEALTH_UNLINKED, PipelineHealth.describe(unlinked));
		} catch (Throwable error) {
			return ModTexts.raw(ModTexts.HEALTH_UNKNOWN);
		}
	}

	/**
	 * 取（必要时创建 / 调整尺寸）离屏渲染目标。失败时返回 null —— 调用方要么跳过这一遍，
	 * 要么退回主帧缓冲。0.2.2 起它同时服务于“新管线建好后的首帧预热”与“高清副渲染截图”。
	 */
	private static Framebuffer acquireOffscreen(int width, int height) {
		try {
			return GhostFramebuffer.acquire(width, height);
		} catch (Throwable error) {
			ShaderFlash.LOGGER.warn("[ShaderFlash] 无法创建 {}×{} 的离屏渲染目标", width, height, error);
			return null;
		}
	}

	/**
	 * 给副管线那一遍用的 PoseStack：内容等于 {@code renderWorld} <b>入口时</b>的姿态，
	 * 而且是全新的对象（栈里只有 1 帧，满足原版 {@code checkPoseStack()} 的要求）。
	 *
	 * <p>拿不到快照时退回调用方那个栈——画面会退化成“相机旋转叠加两次”，
	 * 但不会崩，也不会影响主画面。
	 */
	private static MatrixStack ghostMatrices(MatrixStack fallback) {
		if (entryPose == null) {
			return fallback;
		}
		MatrixStack stack = new MatrixStack();
		stack.peek().getPositionMatrix().set(entryPose);
		if (entryNormal != null) {
			stack.peek().getNormalMatrix().set(entryNormal);
		}
		return stack;
	}

	private static void disableGhost(String reason) {
		ghostBroken = true;
		lastError = reason;
		ShaderFlash.LOGGER.error("[ShaderFlash] 已停用副管线渲染：{}", reason);
		feedback(ModTexts.text(ModTexts.MESSAGE_GHOST_DISABLED, reason));
	}

	public static void resetGhostFailure() {
		ghostBroken = false;
	}

	public static boolean isGhostBroken() {
		return ghostBroken;
	}

	public static String lastRenderSnapshot() {
		return lastRenderSnapshot == null ? ModTexts.raw(ModTexts.STATUS_NEVER_RENDERED) : lastRenderSnapshot;
	}

	/** 由 {@code WorldRendererMixin} 在渲染中途调用：此刻的标志才是“真正在渲染时”的值。 */
	public static void captureRenderState() {
		try {
			lastRenderSnapshot = describePipeline(Iris.getPipelineManager().getPipelineNullable())
				+ "\n  " + ModTexts.str(ModTexts.DIAG_SNAPSHOT_IMMEDIATE,
					ImmediateState.isRenderingLevel,
					ImmediateState.renderWithExtendedVertexFormat,
					CapturedRenderingState.INSTANCE.getCurrentRenderedEntity(),
					CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity(),
					CapturedRenderingState.INSTANCE.getCurrentRenderedItem())
				// 实体“画了但看不见”的两个常见元凶：深度/颜色写入被锁（colorMask/depthMask 全 false）、
				// 手部标志残留（实体渲染类型会被 Iris 当成 HAND_*）。
				+ "\n  " + ModTexts.str(ModTexts.DIAG_SNAPSHOT_ENTITIES,
					DepthColorStorage.isDepthColorLocked(),
					handRendererActive(),
					ShadowRenderer.ACTIVE,
					WorldRenderingSettings.INSTANCE.shouldUseExtendedVertexFormat())
				// 程序没链接上 = 用它画的东西什么都不输出（玩家实测：手部与实体消失）。
				+ "\n  " + ModTexts.str(ModTexts.DIAG_SNAPSHOT_PROGRAMS,
					healthText(Iris.getPipelineManager().getPipelineNullable()),
					secondary == null ? ModTexts.raw(ModTexts.VALUE_NONE_SHORT) : healthText(secondary.pipeline));
		} catch (Throwable ignored) {
			// 采样失败不影响渲染
		}
	}

	private static String describePipeline(WorldRenderingPipeline pipeline) {
		StringBuilder builder = new StringBuilder();
		builder.append(pipeline == null
			? ModTexts.raw(ModTexts.VALUE_NONE_SHORT) : pipeline.getClass().getSimpleName());
		if (pipeline instanceof ShaderRenderingPipeline shaderPipeline) {
			builder.append(ModTexts.str(ModTexts.DIAG_SHADER_OVERRIDE, shaderPipeline.shouldOverrideShaders()));
			ShaderMap map = shaderPipeline.getShaderMap();
			ShaderKey[] probe = {ShaderKey.TERRAIN_SOLID, ShaderKey.ENTITIES_SOLID,
				ShaderKey.SKY_BASIC, ShaderKey.CLOUDS};
			for (ShaderKey key : probe) {
				var shader = map.getShader(key);
				builder.append("，").append(key).append('=')
					.append(shader == null ? ModTexts.raw(ModTexts.VALUE_NONE_SHORT) : shader.getClass().getSimpleName());
			}
		}
		return builder.toString();
	}

	// ------------------------------------------------------------------
	// 状态展示
	// ------------------------------------------------------------------

	public static String statusSummary() {
		StringBuilder builder = new StringBuilder();
		String primaryName = ShaderFlashSupport.available() ? String.valueOf(Iris.getCurrentPackName()) : "-";
		builder.append(ModTexts.text(ModTexts.STATUS_PRIMARY, primaryName).getString());
		builder.append('\n');
		PrewarmJob job = prewarm;
		if (job != null) {
			builder.append(ModTexts.text(ModTexts.STATUS_PREWARMING,
				job.name, ModTexts.text(job.phase), Math.round(job.progress * 100.0F)).getString());
			if (swapWhenReady) {
				builder.append('\n').append(ModTexts.str(ModTexts.STATUS_SWAP_PENDING));
			}
		} else if (secondary == null) {
			builder.append(ModTexts.str(ModTexts.STATUS_SECONDARY_NONE));
		} else {
			builder.append(ModTexts.text(ModTexts.STATUS_SECONDARY,
				secondary.name, secondary.buildMillis).getString());
			builder.append('\n');
			builder.append(ModTexts.text(ModTexts.STATUS_RESOLUTION, screenshotResolutionText()).getString());
			builder.append('\n');
			builder.append(ModTexts.text(ModTexts.STATUS_INITIAL_WARMUP,
				ModTexts.str(DuoConfig.get().initialWarmupRender
					? ModTexts.VALUE_ON : ModTexts.VALUE_OFF)).getString());
			if (!DuoConfig.get().initialWarmupRender && !secondary.renderedOnce) {
				builder.append(ModTexts.str(ModTexts.STATUS_INITIAL_WARMUP_SKIPPED));
			}
			long ghostMicros = lastGhostMicros();
			if (ghostMicros >= 0) {
				builder.append('\n');
				builder.append(ModTexts.text(ModTexts.STATUS_COST, ghostMicros / 1000.0D).getString());
			}
			if (lastSwapMillis >= 0) {
				builder.append('\n');
				builder.append(ModTexts.text(ModTexts.STATUS_SWAP, lastSwapMillis).getString());
			}
		}
		if (captureActive) {
			builder.append('\n').append(ModTexts.text(ModTexts.STATUS_CAPTURING, captureName).getString());
		}
		if (!lastError.isEmpty()) {
			builder.append('\n').append(ModTexts.text(ModTexts.STATUS_ERROR, lastError).getString());
		}
		return builder.toString();
	}

	/** 是否有预编译正在进行（设置界面用它决定要不要显示“取消”按钮）。 */
	public static boolean isPrewarming() {
		return prewarm != null;
	}

	/**
	 * 副管线是否已经真的渲染过至少一整帧（截图取景的前置条件，见 {@link ScreenshotCapture}）。
	 *
	 * <p>刚构造好的管线第一遍渲染时，Iris 的 composite/final 缓冲翻转状态还没跑起来，
	 * 那一帧有可能是纯黑 —— 玩家实测遇到过一次。取景那一遍会让它渲染起来，
	 * 所以第一次取景只当预热。
	 */
	public static boolean secondaryHasRendered() {
		PackSlot slot = secondary;
		return slot != null && slot.renderedOnce;
	}

	/**
	 * 「副渲染截图分辨率」的文案：`跟随窗口（1920×1080）` 或 `3840×2160（长边 3840）`。
	 * 设置界面、HUD 与日志共用它，避免三处各写一套换算。
	 */
	public static String screenshotResolutionText() {
		return screenshotResolutionText(DuoConfig.get().screenshotWidth(), DuoConfig.get().screenshotHeight());
	}

	/** 同上，但用指定的宽/高算（设置界面要实时显示“这样填会拍成多大”）。 */
	public static String screenshotResolutionText(int wantWidth, int wantHeight) {
		int[] window = windowFramebufferSize();
		int[] size = CaptureResolution.resolve(window[0], window[1], wantWidth, wantHeight);
		if (DuoConfig.clampScreenshotSize(wantWidth) <= 0 && DuoConfig.clampScreenshotSize(wantHeight) <= 0) {
			return ModTexts.text(ModTexts.VALUE_FOLLOW_WINDOW, size[0], size[1]).getString();
		}
		return ModTexts.text(ModTexts.VALUE_RESOLUTION, size[0], size[1]).getString();
	}

	/** 当前窗口的帧缓冲尺寸；拿不到时返回 {@code {0, 0}}。 */
	public static int[] windowFramebufferSize() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.getWindow() != null) {
			return new int[]{
				client.getWindow().getFramebufferWidth(),
				client.getWindow().getFramebufferHeight()
			};
		}
		return new int[]{0, 0};
	}

	/** 按当前配置算出来的目标尺寸（设置界面与 HUD 共用）。 */
	public static int[] screenshotTargetSize() {
		int[] window = windowFramebufferSize();
		return CaptureResolution.resolve(window[0], window[1],
			DuoConfig.get().screenshotWidth(), DuoConfig.get().screenshotHeight());
	}

	public static void renderHud(DrawContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.textRenderer == null) {
			return;
		}
		String[] lines = statusSummary().split("\n");
		int y = 4;
		for (String line : lines) {
			String text = (y == 4 ? ModTexts.raw(ModTexts.TAG) : "") + line;
			context.drawTextWithShadow(client.textRenderer, text, 4, y, 0xFFE0FFE0);
			y += 10;
		}
		// 「副管线截图」进度：区块重建 已做/总数 + 百分比 + 进度条（+ 预热帧数），
		// 方便玩家估算什么时候会真的按快门。
		if (ScreenshotCapture.isPending()) {
			y += 4;
			y = drawCaptureProgress(context, client, y);
		}
	}

	/** 画截图进度块，返回下一行的 y。 */
	private static int drawCaptureProgress(DrawContext context, MinecraftClient client, int y) {
		String pack = ScreenshotCapture.pendingPackName();
		int total = SodiumRemesh.totalSections();
		float progress = SodiumRemesh.progress();
		boolean terrainSettled = isTerrainSettled();
		int warmupRequired = Math.max(0, DuoConfig.get().screenshotWarmupFrames);
		int warmup = secondary == null ? 0 : secondary.warmupFrames;

		String phase;
		if (!terrainSettled) {
			phase = ModTexts.str(ModTexts.HUD_CAPTURE_TERRAIN);
		} else if (warmup < warmupRequired) {
			phase = ModTexts.str(ModTexts.HUD_CAPTURE_WARMUP);
		} else {
			phase = ModTexts.str(ModTexts.HUD_CAPTURE_READY);
		}

		context.drawTextWithShadow(client.textRenderer,
			ModTexts.raw(ModTexts.TAG) + ModTexts.text(ModTexts.HUD_CAPTURE_TITLE, pack, phase).getString(),
			4, y, 0xFFFFD37F);
		y += 10;

		if (total > 0 && progress >= 0.0F) {
			int done = Math.round(progress * total);
			context.drawTextWithShadow(client.textRenderer,
				ModTexts.text(ModTexts.HUD_CAPTURE_CHUNKS, done, total, Math.round(progress * 100.0F)).getString(),
				10, y, 0xFFE0FFE0);
			y += 10;
			y = drawProgressBar(context, client, y, progress);
		} else {
			context.drawTextWithShadow(client.textRenderer,
				ModTexts.str(ModTexts.HUD_CAPTURE_UNKNOWN), 10, y, 0xFFE0FFE0);
			y += 10;
		}

		context.drawTextWithShadow(client.textRenderer,
			ModTexts.text(ModTexts.HUD_CAPTURE_FRAMES, warmup, warmupRequired,
				ScreenshotCapture.waitedMillis()).getString(),
			10, y, 0xFFBFD9BF);
		y += 10;

		// 目标分辨率：这一张会以多大像素存盘（高清档位下不等于窗口分辨率）。
		context.drawTextWithShadow(client.textRenderer,
			ModTexts.text(ModTexts.HUD_CAPTURE_RESOLUTION, screenshotResolutionText()).getString(),
			10, y, 0xFFBFD9BF);
		return y + 10;
	}

	/** 一条简单的进度条（宽 120）。 */
	private static int drawProgressBar(DrawContext context, MinecraftClient client, int y, float progress) {
		int x = 10;
		int width = 120;
		int height = 4;
		int filled = Math.max(0, Math.min(width, Math.round(progress * width)));
		context.fill(x, y, x + width, y + height, 0x80000000);        // 底
		context.fill(x, y, x + filled, y + height, 0xFF8FE08F);       // 进度
		return y + height + 6;
	}

	public static void feedback(Text message) {
		if (!DuoConfig.get().chatFeedback) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.inGameHud == null) {
			return;
		}
		client.inGameHud.getChatHud().addMessage(
			Text.literal(ModTexts.raw(ModTexts.TAG)).formatted(Formatting.AQUA).append(message));
	}

	public static PackSlot secondarySlot() {
		return secondary;
	}
}
