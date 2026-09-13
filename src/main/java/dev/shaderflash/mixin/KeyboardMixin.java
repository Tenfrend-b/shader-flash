package dev.shaderflash.mixin;

import dev.shaderflash.ScreenshotCapture;
import net.minecraft.client.Keyboard;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.File;
import java.util.function.Consumer;

/**
 * 拦下原版 F2 的截图调用（“副管线截图”）。
 *
 * <p>原版路径：{@code Keyboard.onKey} → {@code ScreenshotRecorder.saveScreenshot(runDirectory,
 * getFramebuffer(), 消息消费者)}。这里换成我们的实现：先切到副管线、等那一帧画完再调用**同一个**原版
 * 方法存盘；如果功能没开、没有副管线或不在世界里，就原样调用原版方法，F2 行为完全不变。
 *
 * <p>{@code method_1466} = {@code Keyboard.onKey}；{@code class_318.method_1659} =
 * {@code ScreenshotRecorder.saveScreenshot(File, Framebuffer, Consumer)}。
 * 注意只管这个三参数重载：Ctrl+F2 的远景图与 Shift+F2 的超大截图走的是别的路径，保持原版行为。
 */
@Mixin(Keyboard.class)
public class KeyboardMixin {
	@Redirect(method = "method_1466", require = 0, remap = false,
		at = @At(value = "INVOKE", remap = false,
			target = "Lnet/minecraft/class_318;method_1659(Ljava/io/File;Lnet/minecraft/class_276;Ljava/util/function/Consumer;)V"))
	private void shaderflash$secondaryScreenshot(File gameDirectory, Framebuffer framebuffer,
											 Consumer<Text> messageReceiver) {
		if (!ScreenshotCapture.intercept(gameDirectory, framebuffer, messageReceiver)) {
			ScreenshotRecorder.saveScreenshot(gameDirectory, framebuffer, messageReceiver);
		}
	}
}
