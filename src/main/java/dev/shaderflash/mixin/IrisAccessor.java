package dev.shaderflash.mixin;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link Iris} 内部用 {@code currentPack} / {@code currentPackName} 记录“玩家现在用的是哪个包”。
 *
 * <p>把副管线提升为主管线时，这两个字段必须一起改，否则 Iris 界面显示、光影设置读写、
 * 以及下一次重载走的都会是旧包，状态就裂开了。
 */
@Mixin(value = Iris.class, remap = false)
public interface IrisAccessor {
	@Accessor("currentPack")
	static ShaderPack shaderflash$getCurrentPack() {
		throw new AssertionError("mixin accessor");
	}

	@Accessor("currentPack")
	static void shaderflash$setCurrentPack(ShaderPack pack) {
		throw new AssertionError("mixin accessor");
	}

	@Accessor("currentPackName")
	static String shaderflash$getCurrentPackName() {
		throw new AssertionError("mixin accessor");
	}

	@Accessor("currentPackName")
	static void shaderflash$setCurrentPackName(String name) {
		throw new AssertionError("mixin accessor");
	}
}
