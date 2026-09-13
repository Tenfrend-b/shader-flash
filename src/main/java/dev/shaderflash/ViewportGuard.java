package dev.shaderflash;

/**
 * 副渲染那一遍的<b>视口护栏</b>。
 *
 * <h2>为什么需要它</h2>
 * 「副渲染截图」的高清档位会在一块比窗口更大的目标里渲染（见 {@code CaptureResolution}）。
 * 实测发现：这一遍里手部（以及被它挡住的实体）是按<b>窗口尺寸的视口</b>画出来的 ——
 * 于是高清图上手部只有窗口那么大、还挤在左下角，实体则被地形挡住看不见。
 * 也就是说，渲染途中某处把视口设回了窗口分辨率。
 *
 * <p>原版的视口来自两个地方：{@code RenderSystem.viewport(...)} 与
 * {@code Framebuffer.bindWrite(true)}；两者最终都走 {@code GlStateManager._viewport(...)}
 * （见 {@code ViewportGuardMixin}）。这里只做一件事：<b>在副渲染那一遍里，
 * 如果有谁把视口设成“窗口尺寸”，就把它改写成这一遍真正的目标尺寸</b>。
 * 其它尺寸（Iris 的 composite viewportScale、阴影贴图分辨率、1×1 的取中心深度）一律不动。
 *
 * <p>改写次数由 {@link #rewrites()} 报出来，日志里能看到有没有命中 —— 这是个能自证的护栏。
 */
public final class ViewportGuard {
	private static boolean armed;
	private static int targetWidth = 1;
	private static int targetHeight = 1;
	private static int staleWidth = 1;
	private static int staleHeight = 1;
	private static boolean rewriting;
	private static int rewrites;

	private ViewportGuard() {
	}

	/**
	 * 开始一次副渲染。
	 *
	 * @param targetWidth  这一遍真正的目标宽度
	 * @param targetHeight 这一遍真正的目标高度
	 * @param windowWidth  当前（没被这一遍放大的）窗口宽度 —— 视口等于它就会被改写
	 * @param windowHeight 同上
	 */
	public static void arm(int targetWidth, int targetHeight, int windowWidth, int windowHeight) {
		ViewportGuard.targetWidth = Math.max(1, targetWidth);
		ViewportGuard.targetHeight = Math.max(1, targetHeight);
		ViewportGuard.staleWidth = Math.max(1, windowWidth);
		ViewportGuard.staleHeight = Math.max(1, windowHeight);
		rewriting = false;
		rewrites = 0;
		armed = true;
	}

	public static void disarm() {
		armed = false;
		rewriting = false;
	}

	/** 由 mixin 调用：这一笔是不是需要改写成目标尺寸。 */
	public static boolean shouldRewrite(int width, int height) {
		if (!armed || rewriting) {
			return false;
		}
		if (width == targetWidth && height == targetHeight) {
			return false; // 已经是目标尺寸
		}
		return width == staleWidth && height == staleHeight;
	}

	public static int targetWidth() {
		return targetWidth;
	}

	public static int targetHeight() {
		return targetHeight;
	}

	/** 进入“正在改写”状态，避免改写调用自己又被拦一次。 */
	public static void beginRewrite() {
		rewriting = true;
		rewrites++;
	}

	public static void endRewrite() {
		rewriting = false;
	}

	/** 这一遍里改写了几次（0 = 没人把视口设回窗口尺寸）。 */
	public static int rewrites() {
		return rewrites;
	}
}
