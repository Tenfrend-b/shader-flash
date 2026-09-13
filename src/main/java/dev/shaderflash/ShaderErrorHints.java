package dev.shaderflash;

import java.util.Locale;

/**
 * 把 Iris/驱动抛出来的着色器编译错误翻译成一句人话。
 *
 * <p>这些报错给的行号是转译、去注释之后的行号，往往对不上原始文件，单看
 * ERROR: 0:58: 'sample' : syntax error 基本没法排查，所以这里给个方向。
 */
public final class ShaderErrorHints {
	private ShaderErrorHints() {
	}

	public static String hintFor(String message) {
		if (message == null) {
			return null;
		}
		String lower = message.toLowerCase(Locale.ROOT);
		if (lower.contains("syntax error")) {
			return "提示：Iris 1.7.1+ 会把光影包转成 #version 410 core 再交给驱动，"
				+ "老包里像 sample / subroutine 这类变量名在 410 里是关键字。"
				+ "本模组默认会把它们改名（设置里的“兼容 GLSL 4.x 保留字”），"
				+ "如果关掉了这个选项，重新打开后再重建一次。";
		}
		if (lower.contains("not supported") || lower.contains("unsupported")) {
			return "提示：该光影包要求更高的 GLSL 版本，可能超出当前显卡驱动支持的范围。";
		}
		if (lower.contains("out of memory") || lower.contains("memory")) {
			return "提示：显存或内存不足。可以先把渲染模式改成“仅预编译”，或换更轻的副光影包。";
		}
		if (lower.contains("shader storage") || lower.contains("ssbo") || lower.contains("compute")) {
			return "提示：该光影包用到了计算着色器或 SSBO，需要显卡支持 OpenGL 4.3 及以上。";
		}
		return null;
	}
}
