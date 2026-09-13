package dev.shaderflash.mixin;

import dev.shaderflash.DuoManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在世界渲染**进行中**采一次管线状态。
 *
 * <p>为什么不能在世界渲染的尾部采：{@code shouldOverrideShaders()} = {@code isRenderingWorld && isMainBound}，
 * 而 Iris 在 {@code finalizeLevelRendering()} 里就把 {@code isRenderingWorld} 置回 false 了 ——
 * 在尾部或按键回调里读永远是 false，看不出真实情况。
 *
 * <p>这里挂在 {@code renderSky} 调用处：此刻已经过了 {@code beginLevelRendering()}（各种标志都为真），
 * 又还没到收尾，正好是“真正在画世界”的时刻。
 *
 * <p>{@code method_3257} 是 {@code WorldRenderer.renderSky} 的 intermediary 名。
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
	@Inject(method = "method_3257", at = @At("HEAD"), remap = false, require = 0)
	private void shaderflash$sampleRenderState(MatrixStack matrices, Matrix4f projectionMatrix, float tickDelta,
										   Camera camera, boolean thickFog, Runnable fogCallback, CallbackInfo ci) {
		DuoManager.captureRenderState();
	}
}
