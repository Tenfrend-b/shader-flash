package dev.shaderflash.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code GameRenderer.renderHand} 开关（intermediary {@code field_3992}）。
 *
 * <p>用途：「副渲染截图」的<b>预热帧</b>不画手部 —— 手部对光影包的时域适应没有意义，
 * 而它在那一遍里一旦被画错位置（见 {@code ViewportGuard}），就会留在光影包的历史缓冲里
 * 变成鬼影。原版自己的分块高清截图也是这么做的（{@code renderWithZoom} 里关掉手部）。
 *
 * <p>字段名用 intermediary 并 {@code remap = false}：本工程没有 refmap，运行时只认中间名。
 */
@Mixin(value = GameRenderer.class, remap = false)
public interface GameRendererAccessor {
	@Accessor("field_3992")
	boolean shaderflash$getRenderHand();

	@Accessor("field_3992")
	void shaderflash$setRenderHand(boolean renderHand);
}
