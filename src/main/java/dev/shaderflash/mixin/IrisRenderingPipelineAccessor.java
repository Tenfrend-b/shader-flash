package dev.shaderflash.mixin;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

/**
 * 拿到 {@link IrisRenderingPipeline} 的两个私有状态。
 *
 * <p>{@code initializedBlockIds}：Iris 在第一次 {@code beginLevelRendering()} 时会用<b>当前包</b>
 * 的材质表去覆盖全局设置，并顺手重建整个世界的区块网格。副管线是后加载的第二条管线，
 * 如果让它照做，就等于每加载一次副包都全区重建一次——那正是我们想省掉的开销。
 * 我们提前把它标记成已初始化，副管线那一遍就完全不碰全局状态。
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public interface IrisRenderingPipelineAccessor {
	@Accessor("initializedBlockIds")
	void shaderflash$setInitializedBlockIds(boolean value);

	@Accessor("initializedBlockIds")
	boolean shaderflash$getInitializedBlockIds();

	@Accessor("pack")
	ShaderPack shaderflash$getPack();

	/**
	 * Iris 的“阴影渲染目标”是<b>懒创建</b>的：{@code shadow.enabled} 显式为 true 时构造期就建好，
	 * 否则要等某个非 Sodium 版程序在编译时绑定阴影采样器才会顺手建出来。
	 * Sodium 的地形程序是懒编译的，Iris 假设“非 Sodium 版先编译过”，这个假设在部分光影包上不成立
	 * （MakeUp：{@code shadow.enabled} 没写，但源码里 {@code SHADOW_CASTING} 默认打开），
	 * 结果是 Iris 自己在 {@code createTerrainSamplers} 里 NPE。我们用它补建。
	 */
	@Accessor("shadowTargetsSupplier")
	Supplier<ShadowRenderTargets> shaderflash$getShadowTargetsSupplier();
}
