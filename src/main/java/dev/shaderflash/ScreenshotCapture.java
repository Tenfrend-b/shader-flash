package dev.shaderflash;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.io.File;
import java.util.function.Consumer;

/**
 * 「副管线截图」：把副管线渲染的一帧存盘，对玩家不可见、也不打断游玩。
 *
 * <h2>像素从哪来</h2>
 * 按下 F2 后，{@code DuoManager.beginSecondaryCapture()} 先把材质表切到副包、把视距内的
 * 区块网格排队重建；等到条件满足，{@link #shouldShootNow()} 会在某一帧的<b>开头</b>答应一次取景：
 * {@code DuoManager.captureSecondaryFrame()} 用副管线把世界渲染进<b>主帧缓冲</b>，
 * 紧接着 {@link #shootNow(Framebuffer, boolean)} 用原版实现读像素存盘。
 * 本帧随后的正常渲染会把它整个覆盖掉，所以玩家一帧都看不到。
 *
 * <h2>取景条件（都不依赖“看像素”这类事后判断）</h2>
 * <ol>
 *   <li><b>副管线已经渲染过至少一整帧</b>：刚构造好的管线第一遍渲染时，Iris 的
 *       composite/final 缓冲翻转状态还没跑起来，那一帧有可能是纯黑（玩家实测过一次，
 *       日志里那次几乎是刚到最小等待就被放行）。取景那一遍本身会让它渲染起来，
 *       所以这一条等价于“第一次取景只当预热，第二次才真拍”。</li>
 *   <li><b>过了最小等待</b>（1.5 秒）：刚切换过光影时，区块重建任务要过一会儿才真正开始。</li>
 *   <li><b>等的地形重建走完一轮「忙 → 空」</b>，并且<b>稳定 {@value #SETTLE_STABLE_MILLIS} 毫秒</b>
 *       之后再拍 —— 队列刚空的那一瞬间可能只是视距内那一批刚建完，再等一小段更稳。</li>
 * </ol>
 *
 * <p><b>没有“超时回落”了（0.2 删除）</b>：以前等太久会“按当前画面直接拍”，
 * 拍到的往往是半成品地形。<b>现在有进度条了，等待本身就是可见、可解释的</b>，
 * 所以条件不满足就一直等，不再有“等不及就拍一张”的分支。
 * 唯一的例外是“连续若干 tick 一帧都没渲染出来”（窗口最小化/渲染卡死）——
 * 那不是回落，而是收尾，否则 F2 会永远卡在等待状态。这个阈值是**可调项**
 * （{@link DuoConfig#noFrameTimeoutTicks}，100 ~ 32767 tick，默认 100 ≈ 5 秒），
 * 调大后最小化/暂停也不会把等待打断，方便调试。
 *
 * <h2>为什么不用“拍完检查是不是黑图再重拍”</h2>
 * 那是事后补救：既解释不了成因，也给每次截图都加了读回+判定+重拍的复杂度。
 * 现在改成从取景时机上堵住已知的两种可能（新建管线的第一帧、地形刚重建完就抢拍），
 * 剩下的不确定性写在 {@code docs/03-限制与风险.md} 的已知问题里。
 *
 * <h2>截图里没有 HUD</h2>
 * 取景发生在 HUD 之前，所以文件里是纯世界画面（和旧版“切屏后拍主帧缓冲”不同）。
 */
public final class ScreenshotCapture {
	/** 先无条件等一小段：刚切换过光影时，区块重建任务要过一会儿才真正开始。 */
	private static final long SETTLE_MIN_MILLIS = 1_500L;
	/** 「地形重建已经稳定」需要保持这么久才允许取景。 */
	private static final long SETTLE_STABLE_MILLIS = 500L;

	private static boolean pending;
	/** 连续多少个 tick 没有渲染出任何一帧（最小化 / 暂停时用它兜底收尾）。 */
	private static int ticksWithoutFrame;
	private static long pendingSinceNanos;
	/** 地形稳定之后，最早允许取景的时间戳；0 表示还没观察到“稳定”。 */
	private static long shootNotBeforeNanos;
	private static File directory;
	private static Consumer<Text> messageReceiver;
	/** 请求截图时副管线的名字（用于消息与“等待期间换了包”的判定）。 */
	private static String pendingPackName = "";

	private ScreenshotCapture() {
	}

	/**
	 * 接管原版截图。由 {@code KeyboardMixin} 在 {@code ScreenshotRecorder.saveScreenshot} 处调用。
	 *
	 * @return true 表示已接管（原版的截图不用执行）；false 表示交回原版处理
	 */
	public static boolean intercept(File screenshotDirectory, Framebuffer framebuffer,
									Consumer<Text> receiver) {
		DuoConfig config = DuoConfig.get();
		if (!config.secondaryScreenshot || !config.enabled) {
			return false;
		}
		if (pending) {
			// 上一张还没拍完，忽略这次触发
			return true;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) {
			return false;
		}
		if (DuoManager.secondarySlot() == null) {
			DuoManager.feedback(ModTexts.text(DuoManager.isPrewarming()
				? ModTexts.MESSAGE_SCREENSHOT_PREWARMING
				: ModTexts.MESSAGE_SCREENSHOT_NO_SECONDARY));
			return false;
		}
		// 把材质表切到副包的、并让地形按它重建；这一步之后才开始等重建完成。
		if (!DuoManager.beginSecondaryCapture()) {
			DuoManager.feedback(ModTexts.text(ModTexts.MESSAGE_SCREENSHOT_FAILED));
			return false;
		}

		pending = true;
		ticksWithoutFrame = 0;
		shootNotBeforeNanos = 0L;
		pendingSinceNanos = System.nanoTime();
		directory = screenshotDirectory;
		messageReceiver = receiver;
		pendingPackName = DuoManager.secondaryName();
		ShaderFlash.LOGGER.info("[ShaderFlash] 副管线截图：已请求（等地形重建完成后在帧首取景，不切屏）");
		return true;
	}

	/** 截图流程是否在进行中（{@code DuoManager} 用它决定要不要做预热帧/取景）。 */
	public static boolean isPending() {
		return pending;
	}

	/** 本次取景对应的副包名（HUD 进度提示用）。 */
	public static String pendingPackName() {
		return pendingPackName;
	}

	/** 从按下 F2 到现在过了多久（毫秒）。 */
	public static long waitedMillis() {
		return pending ? (System.nanoTime() - pendingSinceNanos) / 1_000_000L : 0L;
	}

	/**
	 * 每帧<b>开头</b>由 {@code DuoManager.captureSecondaryFrame()} 询问：现在可以取景了吗？
	 *
	 * <p>条件见类注释；不满足就返回 false，下一帧再问。
	 */
	public static boolean shouldShootNow() {
		if (!pending) {
			return false;
		}
		ticksWithoutFrame = 0;
		long now = System.nanoTime();
		long waitedMillis = (now - pendingSinceNanos) / 1_000_000L;
		if (waitedMillis < SETTLE_MIN_MILLIS) {
			return false;
		}
		if (DuoManager.isRemeshSettled()) {
			if (shootNotBeforeNanos == 0L) {
				// 刚刚稳定：再等一小段，别在“视距内那一批刚建完”的瞬间抢拍。
				shootNotBeforeNanos = now + SETTLE_STABLE_MILLIS * 1_000_000L;
				return false;
			}
			return now >= shootNotBeforeNanos;
		}
		shootNotBeforeNanos = 0L;
		// 还没稳定：继续等（进度条会告诉玩家等到哪一步了）。
		// 0.2 起删除了“超时按当前画面拍摄”的回落分支。
		return false;
	}

	/**
	 * 取景那一帧的收尾：把主帧缓冲里副管线的那一帧存盘，然后还原主管线的材质表。
	 *
	 * @param target   主帧缓冲（此刻里面就是副管线刚画完的那一帧）
	 * @param rendered 那一遍是否真的渲染成功
	 */
	public static void shootNow(Framebuffer target, boolean rendered) {
		if (!pending) {
			return;
		}
		File targetDirectory = directory;
		Consumer<Text> receiver = messageReceiver;
		String packName = pendingPackName;
		long waitedMillis = (System.nanoTime() - pendingSinceNanos) / 1_000_000L;
		try {
			if (!rendered || target == null) {
				ShaderFlash.LOGGER.warn("[ShaderFlash] 副管线截图：副管线那一遍没渲染成功，本次取消");
				DuoManager.feedback(ModTexts.text(ModTexts.MESSAGE_SCREENSHOT_FAILED));
				return;
			}
			// 关键：直接读主帧缓冲 —— 像素走的是和“把副管线当主管线”完全相同的路径。
			// 默认存盘沿用原版实现（命名规则、写盘线程、聊天提示都与 F2 原生行为一致）；
			// 开了「JPG 模式」则换成有损压缩写入（见 JpegScreenshot）。
			boolean jpg = DuoConfig.get().screenshotJpg;
			if (jpg) {
				JpegScreenshot.save(target, targetDirectory, receiver);
			} else {
				ScreenshotRecorder.saveScreenshot(targetDirectory, target, receiver);
			}
			// 走到这里说明地形重建确实完成并稳定过了（0.2 起没有“超时回落”，不会拍到半成品）。
			ShaderFlash.LOGGER.info("[ShaderFlash] 副管线截图完成（{}）：{}×{}，耗时 {} ms（取景在帧首完成，不阻塞主画面）{}",
				packName, target.textureWidth, target.textureHeight, waitedMillis,
				jpg ? "，格式 JPG" : "");
			if (!jpg) {
				// JPG 那条路由 JpegScreenshot 自己发消息（带“点开文件”的链接）。
				DuoManager.feedback(ModTexts.text(ModTexts.MESSAGE_SCREENSHOT_DONE, packName,
					target.textureWidth, target.textureHeight));
			}
		} catch (Throwable error) {
			ShaderFlash.LOGGER.error("[ShaderFlash] 副管线截图失败", error);
			DuoManager.feedback(ModTexts.text(ModTexts.MESSAGE_SCREENSHOT_FAILED));
		} finally {
			finish();
		}
	}

	/**
	 * 每 tick 的收尾检查：<b>只有一种情况</b>会结束等待——
	 * 连续多个 tick 一帧都没有渲染出来（窗口最小化 / 渲染卡死），也就是根本不可能取到景。
	 *
	 * <p>这<b>不是</b>“超时回落”：0.2 起已经删掉“等太久就按当前画面拍”的分支，
	 * 只要还在出帧，就会一直等到地形重建稳定为止（进度条会显示到哪一步了）。
	 */
	public static void tick() {
		if (!pending) {
			return;
		}
		// 阈值是可调项（默认 100 tick）：调大便于调试，最小化/暂停时不会被提前打断。
		int limit = DuoConfig.get().noFrameTimeoutTicks();
		if (++ticksWithoutFrame > limit) {
			ShaderFlash.LOGGER.warn("[ShaderFlash] 副管线截图：连续 {} tick 没有任何渲染帧（阈值 {} tick），已取消",
				ticksWithoutFrame, limit);
			finish();
			DuoManager.feedback(ModTexts.text(ModTexts.MESSAGE_SCREENSHOT_FAILED));
		}
	}

	private static void finish() {
		// 无论成功失败都要把材质表还原回主管线的，否则玩家会一直在“副包材质”下游戏。
		DuoManager.endSecondaryCapture();
		pending = false;
		ticksWithoutFrame = 0;
		shootNotBeforeNanos = 0L;
		directory = null;
		messageReceiver = null;
		pendingPackName = "";
	}
}
