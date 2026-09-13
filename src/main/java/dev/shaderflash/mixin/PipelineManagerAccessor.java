package dev.shaderflash.mixin;

import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 打开 {@link PipelineManager} 的私有字段：当前管线、按维度缓存的管线表、
 * 以及用于让 Sodium 重建地形着色器的版本计数器。
 *
 * <p>这三样是“偷换管线”的全部机关：Iris 的渲染流程每一步都从
 * {@code getPipelineNullable()} / {@code preparePipeline()} 取管线，所以把字段换掉，
 * 整条渲染链就跟着换了。
 */
@Mixin(value = PipelineManager.class, remap = false)
public interface PipelineManagerAccessor {
	@Accessor("pipeline")
	WorldRenderingPipeline shaderflash$getPipeline();

	@Accessor("pipeline")
	void shaderflash$setPipeline(WorldRenderingPipeline pipeline);

	@Accessor("pipelinesPerDimension")
	Map<NamespacedId, WorldRenderingPipeline> shaderflash$getPipelinesPerDimension();

	@Accessor("versionCounterForSodiumShaderReload")
	int shaderflash$getVersionCounter();

	@Accessor("versionCounterForSodiumShaderReload")
	void shaderflash$setVersionCounter(int value);
}
