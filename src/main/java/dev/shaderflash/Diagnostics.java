package dev.shaderflash;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderMap;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.minecraft.block.BlockState;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 兼容性自检：把两条管线之间那些“共享全局状态”的差异摊开给玩家看。
 *
 * <p>这些东西之所以重要，是因为 Minecraft 的区块顶点里保存的材质 ID、以及
 * “是否使用扩展顶点格式”等设置是<b>全区共享</b>的，不区分管线。两包在这些地方不一致时，
 * 副管线那一遍的材质判断会不准（好在不上屏），而真正切过去时 Iris 需要重建一次区块网格。
 */
public final class Diagnostics {
	private Diagnostics() {
	}

	public static void runAndReport() {
		DuoManager.feedback(ModTexts.text(ModTexts.DIAG_HEADER).formatted(Formatting.GOLD));
		// 先把“管线当前是否真的在接管着色器”这类运行期状态打出来 —— 排查“切过去以后像没开光影”
		// 时，这几个标志比看画面有用得多。
		for (String line : runtimeState()) {
			DuoManager.feedback(Text.literal(line));
			ShaderFlash.LOGGER.info("[ShaderFlash][自检] {}", line);
		}
		ShaderPack primary = Iris.getCurrentPack().orElse(null);
		if (primary == null) {
			DuoManager.feedback(ModTexts.text(ModTexts.DIAG_NO_PRIMARY));
			return;
		}
		PackSlot secondary = DuoManager.secondarySlot();
		if (secondary == null) {
			DuoManager.feedback(ModTexts.text(ModTexts.DIAG_NO_SECONDARY));
			return;
		}
		NamespacedId dimension = Iris.getCurrentDimension();
		if (!secondary.dimension.equals(dimension)) {
			DuoManager.feedback(ModTexts.text(ModTexts.DIAG_DIMENSION_CHANGED));
			return;
		}

		for (String line : compare(primary, secondary.pack, dimension)) {
			DuoManager.feedback(Text.literal(line));
		}
	}

	private static List<String> runtimeState() {
		List<String> lines = new ArrayList<>();
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		lines.add(ModTexts.str(ModTexts.DIAG_RUNTIME_PIPELINE,
			pipeline == null ? ModTexts.raw(ModTexts.VALUE_NONE_SHORT) : pipeline.getClass().getSimpleName(),
			Iris.getCurrentPackName()));
		lines.add(ModTexts.str(ModTexts.DIAG_RUNTIME_STATE, DuoManager.lastRenderSnapshot(),
			ShadowRenderer.ACTIVE));
		lines.add(ModTexts.raw(ModTexts.DIAG_FALLBACK_NOTE));
		PackSlot secondary = DuoManager.secondarySlot();
		lines.add(ModTexts.str(ModTexts.DIAG_SLOT,
			secondary == null ? ModTexts.raw(ModTexts.VALUE_EMPTY_SLOT) : secondary.name,
			DuoManager.isGhostBroken()));
		return lines;
	}

	private static List<String> compare(ShaderPack primary, ShaderPack secondary, NamespacedId dimension) {
		List<String> lines = new ArrayList<>();

		PackDirectives a = primary.getProgramSet(dimension).getPackDirectives();
		PackDirectives b = secondary.getProgramSet(dimension).getPackDirectives();

		boolean sameAo = Float.compare(a.getAmbientOcclusionLevel(), b.getAmbientOcclusionLevel()) == 0;
		lines.add(ModTexts.text(ModTexts.DIAG_AO,
			a.getAmbientOcclusionLevel(), b.getAmbientOcclusionLevel(),
			mark(sameAo)).getString());

		boolean sameVoxelize = a.shouldVoxelizeLightBlocks() == b.shouldVoxelizeLightBlocks();
		lines.add(ModTexts.text(ModTexts.DIAG_VOXELIZE,
			a.shouldVoxelizeLightBlocks(), b.shouldVoxelizeLightBlocks(), mark(sameVoxelize)).getString());

		boolean sameEntityDraws = a.shouldUseSeparateEntityDraws() == b.shouldUseSeparateEntityDraws();
		lines.add(ModTexts.text(ModTexts.DIAG_ENTITY_DRAWS,
			a.shouldUseSeparateEntityDraws(), b.shouldUseSeparateEntityDraws(), mark(sameEntityDraws)).getString());

		boolean sameShadow = a.getShadowDirectives().getResolution() == b.getShadowDirectives().getResolution();
		lines.add(ModTexts.text(ModTexts.DIAG_SHADOW,
			a.getShadowDirectives().getResolution(), b.getShadowDirectives().getResolution(),
			mark(sameShadow)).getString());

		boolean sameIds = sameBlockIds(primary, secondary);
		lines.add(ModTexts.text(ModTexts.DIAG_BLOCK_IDS, mark(sameIds)).getString());
		if (!sameIds) {
			lines.add(ModTexts.text(ModTexts.DIAG_BLOCK_IDS_NOTE).formatted(Formatting.YELLOW).getString());
		}
		lines.add(ModTexts.text(ModTexts.DIAG_PACK_PROGRAMS,
			has(secondary, dimension, ModTexts.LABEL_TERRAIN, "getGbuffersTerrain"),
			has(secondary, dimension, ModTexts.LABEL_ENTITIES, "getGbuffersEntities"),
			has(secondary, dimension, ModTexts.LABEL_WATER, "getGbuffersWater"),
			has(secondary, dimension, ModTexts.LABEL_SHADOW, "getShadow")).getString());
		if (VanillaPack.isVanilla(DuoManager.secondaryName())) {
			// 「原版」包按定义就不带任何程序：上面那一行全是「=无」是预期行为，别让玩家以为坏了。
			lines.add(ModTexts.text(ModTexts.DIAG_VANILLA).formatted(Formatting.AQUA).getString());
		}
		return lines;
	}

	/**
	 * 这个包**自己**有没有提供某个程序（没有的话 Iris 会用合成出来的、近似原版的 fallback —— 这
	 * 正是“看起来像没开光影”的常见原因）。
	 *
	 * <p>用反射调用：{@code ProgramSet} 的程序访问器在 Iris 1.7.1 与 1.7.2 之间有出入，反射可以
	 * 两边都兼容，而且这只是诊断信息，取不到也不该影响其它功能。
	 */
	private static String has(ShaderPack pack, NamespacedId dimension, ModTexts.LText label, String accessor) {
		boolean present = false;
		try {
			Object programSet = pack.getProgramSet(dimension);
			Object value = programSet.getClass().getMethod(accessor).invoke(programSet);
			if (value == null) {
				present = false;
			} else if (value instanceof java.util.Optional<?> optional) {
				present = optional.isPresent();
			} else if (value instanceof Object[] array) {
				present = array.length > 0;
			} else {
				present = true;
			}
		} catch (Throwable ignored) {
			// 版本差异，忽略
		}
		return ModTexts.str(present ? ModTexts.VALUE_HAS_PROGRAM : ModTexts.VALUE_NO_PROGRAM,
			ModTexts.raw(label));
	}

	private static boolean sameBlockIds(ShaderPack primary, ShaderPack secondary) {
		Object2IntMap<BlockState> a = BlockMaterialMapping.createBlockStateIdMap(
			primary.getIdMap().getBlockProperties());
		Object2IntMap<BlockState> b = BlockMaterialMapping.createBlockStateIdMap(
			secondary.getIdMap().getBlockProperties());
		return a.equals(b);
	}

	private static String mark(boolean same) {
		return ModTexts.raw(same ? ModTexts.VALUE_SAME : ModTexts.VALUE_DIFFERENT);
	}
}
