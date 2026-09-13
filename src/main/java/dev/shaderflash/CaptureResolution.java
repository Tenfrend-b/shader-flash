package dev.shaderflash;

/**
 * 「副渲染截图」的目标分辨率计算。
 *
 * <h2>两边怎么配合</h2>
 * 光影管线的投影矩阵、雾、天空、屏幕空间效果全都按<b>窗口宽高比</b>算，
 * 所以目标尺寸最好与窗口同比例，否则画面会被拉伸。
 * 界面上因此给了两个“按宽高比补全”的按钮：玩家只填一边，另一边按窗口比例算出来。
 * 当然也允许两边都自己填（懂行的人想拍宽幅/竖幅就用得上）。
 *
 * <p>尺寸取偶数（部分驱动/着色器对奇数尺寸的渲染目标不友好）。
 */
public final class CaptureResolution {
	/** 目标尺寸的下限（单边）。 */
	public static final int MIN_SIDE = 64;

	private CaptureResolution() {
	}

	/**
	 * 算出这一轮副渲染截图的目标尺寸。
	 *
	 * @param windowWidth  当前窗口的帧缓冲宽度
	 * @param windowHeight 当前窗口的帧缓冲高度
	 * @param wantWidth    {@link DuoConfig#screenshotWidth}：0 = 跟随窗口
	 * @param wantHeight   {@link DuoConfig#screenshotHeight}：0 = 跟随窗口
	 * @return {@code [width, height]}
	 */
	public static int[] resolve(int windowWidth, int windowHeight, int wantWidth, int wantHeight) {
		int baseWidth = Math.max(MIN_SIDE, windowWidth);
		int baseHeight = Math.max(MIN_SIDE, windowHeight);
		int width = DuoConfig.clampScreenshotSize(wantWidth);
		int height = DuoConfig.clampScreenshotSize(wantHeight);

		if (width <= 0 && height <= 0) {
			return new int[]{baseWidth, baseHeight};
		}
		if (width <= 0) {
			width = fillWidth(baseWidth, baseHeight, height);
		} else if (height <= 0) {
			height = fillHeight(baseWidth, baseHeight, width);
		}
		// 注意：这里**不做**像素总量裁剪。超预算只是可能失败/极慢，玩家有权这么干（见
		// DuoConfig.SCREENSHOT_PIXEL_WARN），界面上会给出警告。
		return new int[]{even(width), even(height)};
	}

	/** 目标尺寸是否就是窗口尺寸（这种情况渲染进主帧缓冲，与旧版行为完全一致）。 */
	public static boolean followsWindow(int windowWidth, int windowHeight, int wantWidth, int wantHeight) {
		int[] size = resolve(windowWidth, windowHeight, wantWidth, wantHeight);
		return size[0] == windowWidth && size[1] == windowHeight;
	}

	/** 按窗口宽高比、由宽度算出高度（界面按钮“补全高度”用的就是它）。 */
	public static int fillHeight(int windowWidth, int windowHeight, int width) {
		int baseWidth = Math.max(1, windowWidth);
		int baseHeight = Math.max(1, windowHeight);
		return DuoConfig.clampScreenshotSize(even((double) width * baseHeight / baseWidth));
	}

	/** 按窗口宽高比、由高度算出宽度（界面按钮“补全宽度”）。 */
	public static int fillWidth(int windowWidth, int windowHeight, int height) {
		int baseWidth = Math.max(1, windowWidth);
		int baseHeight = Math.max(1, windowHeight);
		return DuoConfig.clampScreenshotSize(even((double) height * baseWidth / baseHeight));
	}

	/** 尺寸是否超过“建议值”（只用于提示，不限制渲染）。 */
	public static boolean beyondWarningBudget(int width, int height) {
		return (long) width * (long) height > DuoConfig.SCREENSHOT_PIXEL_WARN;
	}

	/** 百万像素，给提示文案用。 */
	public static long megapixels(int width, int height) {
		return Math.round((long) width * (long) height / 1_000_000.0D);
	}

	private static int even(double value) {
		int rounded = (int) Math.round(value);
		rounded &= ~1; // 取偶数
		return Math.max(MIN_SIDE, rounded);
	}
}
