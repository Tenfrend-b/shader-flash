package dev.shaderflash;

import dev.shaderflash.mixin.IrisAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;

import java.lang.reflect.Field;

/**
 * 写 Iris 内部那两个“当前是哪个包”的私有静态字段。
 *
 * <p>正常情况下走 mixin 生成的 accessor；万一 accessor 没被应用（例如换了装载器版本，
 * 静态字段 accessor 不被支持），退回反射 —— 这一步失败本身不致命，但会让主副互换后的
 * 状态不一致，所以两条路都留着。
 */
public final class IrisState {
	private static Field packField;
	private static Field nameField;

	private IrisState() {
	}

	public static void setCurrentPack(ShaderPack pack) {
		try {
			IrisAccessor.shaderflash$setCurrentPack(pack);
			return;
		} catch (Throwable ignored) {
			// 落到反射
		}
		Field field = packField != null ? packField : (packField = findField("currentPack"));
		if (field != null) {
			try {
				field.set(null, pack);
			} catch (Throwable error) {
				ShaderFlash.LOGGER.warn("[ShaderFlash] 无法写入 Iris.currentPack", error);
			}
		}
	}

	public static void setCurrentPackName(String name) {
		try {
			IrisAccessor.shaderflash$setCurrentPackName(name);
			return;
		} catch (Throwable ignored) {
			// 落到反射
		}
		Field field = nameField != null ? nameField : (nameField = findField("currentPackName"));
		if (field != null) {
			try {
				field.set(null, name);
			} catch (Throwable error) {
				ShaderFlash.LOGGER.warn("[ShaderFlash] 无法写入 Iris.currentPackName", error);
			}
		}
	}

	private static Field findField(String name) {
		try {
			Field field = Iris.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (Throwable error) {
			ShaderFlash.LOGGER.warn("[ShaderFlash] 找不到 Iris.{}", name, error);
			return null;
		}
	}
}
