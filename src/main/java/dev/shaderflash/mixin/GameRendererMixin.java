package dev.shaderflash.mixin;

import dev.shaderflash.DuoManager;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 一帧结束前的挂点：{@code GameRenderer.renderWorld(F J MatrixStack)} 的尾部。
 *
 * <p>选这个方法是因为它包含“世界 + 手部”的完整一帧内容，而 Iris 也在这里调用
 * {@code finalizeGameRendering()}（色彩空间转换）。我们在这个点再整帧渲染一次副管线，
 * 主画面已经画完，剩下来的就是交换管线、换帧缓冲、再画一遍。
 *
 * <p>{@code method_3188} 是 {@code renderWorld} 的 intermediary 名。
 *
 * <p><b>为什么要在 HEAD 采一次 PoseStack：</b>{@code renderWorld} 会<b>就地改写</b>调用方传进来的
 * 那个 {@code PoseStack} —— 它在方法中段对同一个栈做 {@code mulPose(相机 pitch)} 与
 * {@code mulPose(相机 yaw + 180)}，而且方法内部没有配对的 push/pop（调用方
 * {@code GameRenderer.render} 每帧传的是 {@code new PoseStack()}，用完即弃，所以原版不需要还原）。
 * 到了尾部，这个栈里已经含有相机旋转，副管线再拿它渲染一遍就会把旋转叠加两次。
 *
 * <p>修法不是 push/pop：原版 {@code LevelRenderer.renderLevel} 在中段会调 {@code checkPoseStack()}，
 * 要求栈里只有 1 帧（{@code PoseStack.clear()} 就是判断这个），<b>多出一帧会直接抛
 * {@code IllegalStateException: Pose stack not empty} 崩游戏</b>（0.1.1 初版的实际故障）。
 * 所以这里只在入口把姿态快照记下来，尾部另建一个内容相同的栈交给副管线那一遍，
 * 原版那个栈一个字都不动。
 *
 * <p><b>HEAD 还要做一件事：副管线截图的取景。</b>取景必须在一切绘制之前把副管线那一帧画进
 * 主帧缓冲（随后本帧的正常渲染会覆盖它，玩家看不到），并且必须在 {@code captureEntryPose}
 * 之后执行 —— 取景那一遍要用“入口时的姿态”。
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "method_3188", at = @At("HEAD"), remap = false, require = 1)
	private void shaderflash$capturePose(float tickDelta, long limitTime, MatrixStack matrices, CallbackInfo ci) {
		DuoManager.captureEntryPose(matrices);
		DuoManager.captureSecondaryFrame((GameRenderer) (Object) this, tickDelta, limitTime, matrices);
	}

	@Inject(method = "method_3188", at = @At("TAIL"), remap = false, require = 1)
	private void shaderflash$afterWorldRender(float tickDelta, long limitTime, MatrixStack matrices, CallbackInfo ci) {
		DuoManager.onWorldRenderTail((GameRenderer) (Object) this, tickDelta, limitTime, matrices);
	}
}
