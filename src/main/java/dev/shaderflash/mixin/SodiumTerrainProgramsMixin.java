package dev.shaderflash.mixin;

import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkProgramOverrides;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkShaderInterface;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisTerrainPass;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 让 Sodium 的地形着色器也能在主副管线之间零成本切换。
 *
 * <h2>问题</h2>
 * 装了 Sodium 时，区块地形的着色器由 Iris 的 {@link IrisChunkProgramOverrides} 负责编译，
 * 它内部只有<b>一份</b> {@code programs} 表，而且只在“版本计数器变了”或“渲染器重建”时才重编。
 * 我们把当前管线换成副管线后，它会继续拿着主包编译出来的地形程序去画副管线的画面——
 * 结果就是每次切换都要重编一遍地形着色器。
 *
 * <h2>做法</h2>
 * 给每条管线各留一份编译结果：切换前把当前那份整体搬进缓存，切回来时再搬出来。
 * 两条管线各自的地形程序都常驻显存，切换时只有 {@code EnumMap} 的几个引用在动。
 *
 * <p>缓存里的程序不归 Iris 管（已经不在它的 {@code programs} 表里），所以由我们自己负责删除；
 * 反过来，Iris 调用 {@code deleteShaders()} 时只删它自己那份，不会重复删除。
 */
@Mixin(value = IrisChunkProgramOverrides.class, remap = false)
public class SodiumTerrainProgramsMixin {
	@Shadow
	@Final
	private EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> programs;

	@Shadow
	private boolean shadersCreated;

	@Unique
	private final Map<WorldRenderingPipeline, EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>>>
		shaderflash$programCache = new IdentityHashMap<>();

	/** 当前 {@code programs} 表里的程序属于哪条管线。 */
	@Unique
	private WorldRenderingPipeline shaderflash$programOwner;

	@Unique
	private int shaderflash$lastVersion = Integer.MIN_VALUE;

	@Inject(method = "getProgramOverride", at = @At("HEAD"), remap = false, require = 0)
	private void shaderflash$switchPrograms(TerrainRenderPass pass, ChunkVertexType vertexType,
										CallbackInfoReturnable<GlProgram<IrisChunkShaderInterface>> cir) {
		PipelineManager manager = Iris.getPipelineManager();

		int version = manager.getVersionCounterForSodiumShaderReload();
		if (version != shaderflash$lastVersion) {
			// 真正的重载/换维度：Iris 会自己删掉当前那份，缓存里的也该跟着作废。
			shaderflash$lastVersion = version;
			shaderflash$dropCache();
			shaderflash$programOwner = null;
		}

		WorldRenderingPipeline wanted = manager.getPipelineNullable();
		if (wanted == shaderflash$programOwner) {
			return;
		}

		if (shaderflash$programOwner != null && shadersCreated && !programs.isEmpty()) {
			shaderflash$programCache.put(shaderflash$programOwner,
				new EnumMap<>(programs));
			programs.clear();
			shadersCreated = false;
		}

		EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> cached = shaderflash$programCache.remove(wanted);
		shaderflash$programOwner = wanted;
		if (cached != null) {
			programs.clear();
			programs.putAll(cached);
			shadersCreated = true;
		} else {
			// 这条管线还没有缓存（例如刚预编译完、还没渲染过地形）：把当前这份连同 GL 对象一起清掉，
			// 让 Iris 紧接着按**当前管线**重建。少了这一步，切换后会继续用上一条管线的地形程序。
			for (GlProgram<?> program : programs.values()) {
				if (program != null) {
					program.delete();
				}
			}
			programs.clear();
			shadersCreated = false;
		}
	}

	@Inject(method = "deleteShaders", at = @At("HEAD"), remap = false, require = 0)
	private void shaderflash$onDeleteShaders(CallbackInfo ci) {
		shaderflash$dropCache();
		shaderflash$programOwner = null;
	}

	@Unique
	private void shaderflash$dropCache() {
		for (EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> map : shaderflash$programCache.values()) {
			for (GlProgram<?> program : map.values()) {
				if (program != null) {
					program.delete();
				}
			}
		}
		shaderflash$programCache.clear();
	}
}
