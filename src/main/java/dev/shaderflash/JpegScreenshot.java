package dev.shaderflash;

import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * 「JPG 模式」的截图写入：把帧缓冲的内容编码成 JPEG 存盘。
 *
 * <h2>和原版 PNG 路径的关系</h2>
 * 像素来源完全相同 —— 都走 {@link ScreenshotRecorder#takeScreenshot(Framebuffer)}，
 * 也就是“把当前帧缓冲的颜色附件读成一张 {@code NativeImage}”。
 * 区别只在编码：原版用自带的无损 PNG 写入器，这里换成 JDK 的 JPEG 编码器（质量 85%）
 * （中间借 PNG 做一次无损中转，理由见 {@link #toBufferedImage}）。
 *
 * <h2>为什么在自己的线程上做</h2>
 * 编码与写盘都放到原版的 IO 工作线程（{@link Util#getIoWorkerExecutor()}），
 * 渲染线程只负责“把像素读出来”这一步 —— 与 vanilla 的行为一致，
 * 所以 8000×4000 这种大图也不会卡住游戏。
 */
public final class JpegScreenshot {
	/** JPEG 质量（0~1）。0.85 是“看不出明显损失、体积又小很多”的常用取值。 */
	private static final float QUALITY = 0.85F;

	private JpegScreenshot() {
	}

	/**
	 * 读取帧缓冲并异步存成 JPG。必须在渲染线程调用（像素读取要走 GL）。
	 *
	 * @param screenshotDirectory 原版 screenshots 目录的父目录（与 vanilla 的入参一致）
	 * @param framebuffer         要保存的帧缓冲（主帧缓冲，或高清截图用的离屏目标）
	 * @param receiver            聊天框消息接收器（可以为 null）
	 */
	public static void save(Framebuffer framebuffer, File screenshotDirectory, Consumer<Text> receiver) {
		NativeImage image = ScreenshotRecorder.takeScreenshot(framebuffer);
		File directory = new File(screenshotDirectory, "screenshots");
		//noinspection ResultOfMethodCallIgnored
		directory.mkdirs();
		File file = new File(directory, nextFileName(directory));

		Util.getIoWorkerExecutor().execute(() -> {
			try {
				BufferedImage rgb = toBufferedImage(image);
				writeJpeg(rgb, file);
				if (receiver != null) {
					// 与原版截图消息一致：文件名带下划线，点一下用系统默认程序打开这张图
					// （vanilla 的 ScreenshotRecorder.method_1664 就是这么做的）。
					MutableText name = Text.literal(file.getName()).formatted(Formatting.UNDERLINE)
						.styled(style -> style.withClickEvent(
							new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath())));
					receiver.accept(ModTexts.join(ModTexts.MESSAGE_SCREENSHOT_JPG_DONE, name));
				}
				ShaderFlash.LOGGER.info("[ShaderFlash] 已保存 JPG 截图：{}（{}×{}）",
					file.getName(), image.getWidth(), image.getHeight());
			} catch (Throwable error) {
				ShaderFlash.LOGGER.error("[ShaderFlash] 写 JPG 截图失败：{}", file.getAbsolutePath(), error);
				if (receiver != null) {
					receiver.accept(ModTexts.text(ModTexts.MESSAGE_SCREENSHOT_FAILED));
				}
			} finally {
				image.close();
			}
		});
	}

	/**
	 * {@code NativeImage} → {@code BufferedImage}（RGB）。
	 *
	 * <p><b>注意：{@link NativeImage#getBytes()} 不是原始像素</b> —— 1.20.4 的实现是
	 * 「{@code ByteArrayOutputStream} + {@code write(WritableByteChannel)}」，
	 * 也就是<b>把图像编码成 PNG</b> 之后返回那些字节（所以同一尺寸两次调用的长度都不一样）。
	 * 早期版本按“原始 RGBA 像素”去索引它，索引必然越界（玩家实测日志：
	 * {@code ArrayIndexOutOfBoundsException: Index 43332033 out of bounds for length 43332033}）。
	 *
	 * <p>所以这里反过来用：拿 PNG 字节交给 {@code ImageIO} 解码成 {@code BufferedImage}，
	 * 再统一成 {@code TYPE_INT_RGB}（JPEG 不支持 alpha，带 alpha 的先合成到不透明底）。
	 * 顺带的好处是完全不用猜通道顺序。
	 */
	private static BufferedImage toBufferedImage(NativeImage image) throws IOException {
		BufferedImage decoded;
		try (ByteArrayInputStream input = new ByteArrayInputStream(image.getBytes())) {
			decoded = ImageIO.read(input);
		}
		if (decoded == null) {
			throw new IOException("cannot decode the PNG produced by NativeImage");
		}
		if (decoded.getType() == BufferedImage.TYPE_INT_RGB) {
			return decoded;
		}
		BufferedImage rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = rgb.createGraphics();
		try {
			graphics.drawImage(decoded, 0, 0, null);
		} finally {
			graphics.dispose();
		}
		return rgb;
	}

	private static void writeJpeg(BufferedImage image, File file) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
		if (!writers.hasNext()) {
			throw new IOException("no JPEG encoder available");
		}
		ImageWriter writer = writers.next();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(file)) {
			writer.setOutput(output);
			ImageWriteParam param = writer.getDefaultWriteParam();
			if (param.canWriteCompressed()) {
				param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(QUALITY);
			}
			writer.write(null, new IIOImage(image, null, null), param);
		} finally {
			writer.dispose();
		}
	}

	/** 与 vanilla 同样的命名规则（{@code yyyy-MM-dd_HH.mm.ss[_n].jpg}）。 */
	private static String nextFileName(File directory) {
		String base = Util.getFormattedCurrentTime();
		int index = 1;
		while (true) {
			String suffix = index == 1 ? "" : "_" + index;
			String name = base + suffix + ".jpg";
			if (!new File(directory, name).exists()) {
				return name;
			}
			index++;
		}
	}
}
