package dev.shaderflash.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.shaderflash.ViewportGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 副渲染那一遍的视口护栏，见 {@link ViewportGuard}。
 *
 * <p>{@code _viewport} 不在混淆范围内（Mojang 的 blaze3d 用原名），所以这里写原名 +
 * {@code remap = false} —— 和本工程其它 mixin 一样，运行时只认中间名。
 * 拦截这一处就够了：{@code RenderSystem.viewport(...)} 与
 * {@code Framebuffer.bindWrite(true)} 最终都会调用它。
 *
 * <p>{@code require = 0}：万一以后的原版把方法改名/挪走，护栏失效但不影响其它功能
 * （日志里的“视口改写”次数会一直是 0，能看出来）。
 */
@Mixin(GlStateManager.class)
public class ViewportGuardMixin {
	@Inject(method = "_viewport", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$keepTargetViewport(int x, int y, int width, int height, CallbackInfo ci) {
		if (!ViewportGuard.shouldRewrite(width, height)) {
			return;
		}
		ViewportGuard.beginRewrite();
		try {
			GlStateManager._viewport(x, y, ViewportGuard.targetWidth(), ViewportGuard.targetHeight());
		} finally {
			ViewportGuard.endRewrite();
		}
		ci.cancel();
	}
}
