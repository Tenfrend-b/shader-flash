package dev.shaderflash.mixin;

import net.minecraft.client.gl.ShaderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 拿到 {@code ShaderProgram} 的 GL 程序号（Yarn 字段名 {@code glRef}，intermediary {@code field_29493}）。
 *
 * <p>用途只有一个：<b>检查一条管线的程序到底有没有链接成功</b>。
 * 玩家实测日志里出现过（Intel 核显）：
 *
 * <pre>
 * OpenGL debug message: SHADER COMPILER ... 'SHADER_ID_LINK error ... GLSL link failed for program 261, "":
 *     The shader uses varying iris_entityInfo, but previous shader does not write to it. ... Out of resource error.'
 * [WARN]: Error encountered when linking program containing VS sky_textured and FS sky_textured.
 * OpenGL debug message: GL error GL_INVALID_OPERATION ... glGetUniformLocation- program 261, "" is not linked
 * </pre>
 *
 * 也就是说：<b>预编译出来的那条副管线里可能有没链接上的程序</b>，而 Iris/原版只是打个 WARN 就继续——
 * 一旦这条管线被 F7 提升为主管线，用未链接程序画的那些东西（手部、实体、天空…）
 * 就会<b>什么都不输出</b>。有了这个程序号，本模组就能在“提升之前”自己查清楚并拒绝。
 */
@Mixin(value = ShaderProgram.class, remap = false)
public interface ShaderProgramAccessor {
	@Accessor("field_29493")
	int shaderflash$getGlRef();
}
