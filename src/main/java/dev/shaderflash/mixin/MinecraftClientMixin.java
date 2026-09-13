package dev.shaderflash.mixin;

import dev.shaderflash.GhostFramebuffer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 副管线的离屏渲染期间（首帧预热 / 高清截图）把“主帧缓冲”换成模组自己的离屏目标。
 *
 * <p>两点说明：
 * <ol>
 *   <li>{@code MinecraftClient.class} 是字节码里的类常量，会被命名空间重映射自动处理；</li>
 *   <li>方法名写成 <b>intermediary</b> 名（{@code method_1522} = {@code getFramebuffer}）并显式
 *       {@code remap = false} —— 本工程用手工构建、没有 Mixin 注解处理器生成的 refmap，
 *       运行时只认 intermediary 名。</li>
 * </ol>
 *
 * <p>为什么拦这一个方法就够了：原版世界渲染、Iris 的 composite/final pass、
 * 以及 Iris 判断“主缓冲是否绑定”的逻辑，全都通过它取主目标。
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	@Inject(method = "method_1522", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
	private void shaderflash$useGhostFramebuffer(CallbackInfoReturnable<Framebuffer> cir) {
		Framebuffer ghost = GhostFramebuffer.override();
		if (ghost != null) {
			cir.setReturnValue(ghost);
		}
	}

}
