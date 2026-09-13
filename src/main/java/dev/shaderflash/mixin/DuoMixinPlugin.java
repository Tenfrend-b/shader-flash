package dev.shaderflash.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 条件加载：没有 Sodium、或这个 Iris 版本没带 Sodium 兼容层时，直接跳过对应 mixin，
 * 免得在不相关的环境里报一堆注入失败。
 *
 * <p>这里只做资源查找，不加载类——插件是在 Mixin 配置解析阶段跑的，那会儿去碰 Iris 的
 * 静态初始化很容易炸。
 */
public final class DuoMixinPlugin implements IMixinConfigPlugin {
	private static final String SODIUM_MIXIN = "SodiumTerrainProgramsMixin";
	private static final String BLOCK_ID_MIXIN = "BlockContextHolderMixin";
	private static final String IRIS_SODIUM_OVERRIDES =
		"net/irisshaders/iris/compat/sodium/impl/shader_overrides/IrisChunkProgramOverrides.class";
	private static final String IRIS_BLOCK_CONTEXT =
		"net/irisshaders/iris/compat/sodium/impl/block_context/BlockContextHolder.class";

	@Override
	public void onLoad(String mixinPackage) {
		// 无需初始化
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(SODIUM_MIXIN) || mixinClassName.endsWith(BLOCK_ID_MIXIN)) {
			if (!FabricLoader.getInstance().isModLoaded("sodium")) {
				return false;
			}
			return DuoMixinPlugin.class.getClassLoader().getResource(IRIS_SODIUM_OVERRIDES) != null;
		}
		return true;
	}

	/**
	 * 供运行时判断：这个环境里能不能让“逐区块后台重建”取到正确的方块 ID 表。
	 *
	 * <p>取不到时（没装 Sodium / Iris 没有 Sodium 兼容层）调用方应该退回整世界重载 ——
	 * 只有重建 RenderSectionManager 才能刷新 Iris 捕获的那份 ID 表。
	 */
	public static boolean canRefreshChunkBlockIds() {
		return FabricLoader.getInstance().isModLoaded("sodium")
			&& DuoMixinPlugin.class.getClassLoader().getResource(IRIS_BLOCK_CONTEXT) != null
			&& DuoMixinPlugin.class.getClassLoader().getResource(IRIS_SODIUM_OVERRIDES) != null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
		// 不做跨配置目标合并
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		// 无
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		// 无
	}
}
