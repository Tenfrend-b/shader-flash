package dev.shaderflash;

import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;

/**
 * 副管线的<b>离屏渲染目标</b>：那一遍渲染的最终像素全部落在这里，永远不会被提交到屏幕。
 *
 * <p>做法是临时让 {@code MinecraftClient.getFramebuffer()} 返回这个 FBO。原版世界渲染、
 * Iris 的 composite/final pass 以及“主缓冲是否绑定”的状态判断，全都通过这个方法取主目标，
 * 所以只要它在副管线那一遍里换掉，整条输出链就一起被改道了。
 *
 * <h2>谁在用</h2>
 * <ul>
 *   <li><b>新管线建好后的首帧预热</b>：尺寸 = 主帧缓冲（这样 Iris 不会为它重建另一套尺寸）；
 *   </li>
 *   <li><b>「副渲染截图」的高清档位</b>：尺寸 = 玩家选的目标分辨率，可以大于窗口。
 *       Iris 会按“当前主缓冲”（= 这块 FBO）的尺寸去重建这条管线自己的渲染目标，
 *       所以拿到的是真正的目标分辨率渲染，而不是把窗口画面放大；
 *       与此同时 {@code Window} 上报的帧缓冲尺寸也会临时改成目标尺寸（见
 *       {@code DuoManager.renderSecondaryPass}），否则原版的视口只会覆盖目标的一小块。</li>
 * </ul>
 *
 * <p>注意别在“每帧都换尺寸”的路径上用它：Iris 每次发现尺寸变化都会重建自己的渲染目标，
 * 那是实打实的显存分配。现在只有首帧预热与高清截图会碰它，且尺寸在整轮截图里保持不变。
 */
public final class GhostFramebuffer {
	private static Framebuffer target;
	private static boolean armed;
	private static boolean consumed;

	private GhostFramebuffer() {
	}

	/** 取得（必要时创建/调整）离屏目标。必须在渲染线程调用。 */
	public static Framebuffer acquire(int width, int height) {
		if (target == null) {
			target = new SimpleFramebuffer(width, height, true, false);
			target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
		} else if (target.textureWidth != width || target.textureHeight != height) {
			target.resize(width, height, false);
		}
		return target;
	}

	public static Framebuffer target() {
		return target;
	}

	/** 开始一次离屏渲染：从这里到 {@link #disarm()} 之间，主帧缓冲被改道。 */
	public static void arm() {
		armed = true;
		consumed = false;
	}

	public static void disarm() {
		armed = false;
	}

	/**
	 * 由 mixin 调用。返回非 null 表示这一帧的主帧缓冲要改道到离屏目标。
	 */
	public static Framebuffer override() {
		if (!armed) {
			return null;
		}
		consumed = true;
		return target;
	}

	/**
	 * 离屏渲染结束后检查 mixin 是否真的被调用过。
	 *
	 * <p>如果没被调用，说明注入点失效了（比如玩家换了别的 Iris 版本）——那一遍渲染其实画到了
	 * 屏幕上。这种情况必须立刻停手，否则画面会一闪一闪。
	 */
	public static boolean wasConsumed() {
		return consumed;
	}

	public static void destroy() {
		armed = false;
		consumed = false;
		if (target != null) {
			target.delete();
			target = null;
		}
	}
}
