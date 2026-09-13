package dev.shaderflash.mixin;

import dev.shaderflash.DuoManager;
import net.irisshaders.iris.pipeline.PipelineManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris 每次切换光影包 / 换维度都会调用 {@code destroyPipeline()} 把自己所有管线销毁重建。
 * 副管线不在 Iris 的名册里，不会被它销毁，所以要在这里自己收拾干净（否则显存泄漏，
 * 而且旧包的着色器会和新世界状态对不上）。
 *
 * <p>{@code require = 0}：这只是个清理钩子，万一哪天方法名变了也不该让游戏崩。
 */
@Mixin(value = PipelineManager.class, remap = false)
public class PipelineManagerMixin {
	@Inject(method = "destroyPipeline", at = @At("TAIL"), remap = false, require = 0)
	private void shaderflash$onDestroyPipeline(CallbackInfo ci) {
		DuoManager.onIrisPipelinesDestroyed();
	}
}
