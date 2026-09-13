package dev.shaderflash;

import dev.shaderflash.mixin.ShaderProgramAccessor;
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderMap;
import net.minecraft.client.gl.ShaderProgram;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.util.ArrayList;
import java.util.List;

/**
 * 管线健康检查：<b>这条管线里的着色器程序都链接成功了吗？</b>
 *
 * <h2>为什么要查这个</h2>
 * 玩家实测（Intel Iris Xe，iterationT 3.2.0）在日志里留下了直接证据：
 *
 * <pre>
 * OpenGL debug message: id=1, source=SHADER COMPILER ... 'SHADER_ID_LINK error has been generated.
 *   GLSL link failed for program 261, "": The shader uses varying iris_entityInfo, but previous shader
 *   does not write to it. ... Out of resource error.'
 * [Render thread/WARN]: Error encountered when linking program containing VS sky_textured and FS sky_textured.
 * OpenGL debug message: id=1282, source=API ... 'glGetUniformLocation- program 261, "" is not linked'
 * </pre>
 *
 * 结论：<b>驱动资源不足时，一条管线的部分程序会链接失败，而 Iris / 原版只打一条 WARN 就继续用</b>。
 * 用未链接的程序去画，结果是“画了但什么都不输出”——玩家看到的就是
 * <b>手部与实体消失</b>（地形走 Sodium 的另一套程序，所以照常显示）。
 *
 * <p>这也解释了玩家实测里那个奇怪规律：同一个主副组合会 ✓✗✓✗ 交替——
 * 因为“坏”的是<b>某一个管线实例</b>（预编译出来的那条），主副一交换，坏实例在不在主位上就变了；
 * 按 R 让 Iris 重新编译，链接成功的新实例又会变好。
 *
 * <h2>本类做什么</h2>
 * 遍历管线的 {@link ShaderMap}，对每个非 null 程序查 {@code GL_LINK_STATUS}，
 * 返回未链接程序的 {@link ShaderKey} 名。只在“构造完成时”和“切换之前”各查一次，开销可忽略。
 */
public final class PipelineHealth {
	private PipelineHealth() {
	}

	/** 未链接的程序名；空列表表示这条管线是健康的。 */
	public static List<String> findUnlinkedPrograms(WorldRenderingPipeline pipeline) {
		List<String> unlinked = new ArrayList<>();
		if (!(pipeline instanceof ShaderRenderingPipeline shaderPipeline)) {
			return unlinked;
		}
		ShaderMap map;
		try {
			map = shaderPipeline.getShaderMap();
		} catch (Throwable error) {
			return unlinked;
		}
		for (ShaderKey key : ShaderKey.values()) {
			ShaderProgram shader;
			try {
				shader = map.getShader(key);
			} catch (Throwable error) {
				continue;
			}
			// 没有阴影贴图时阴影 key 就是 null，这是 Iris 的正常设计，不算问题。
			if (shader == null) {
				continue;
			}
			int glRef;
			try {
				glRef = ((ShaderProgramAccessor) shader).shaderflash$getGlRef();
			} catch (Throwable error) {
				continue; // accessor 没生效就不下结论
			}
			if (glRef <= 0) {
				unlinked.add(key.name());
				continue;
			}
			try {
				if (GL20C.glGetProgrami(glRef, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
					unlinked.add(key.name());
				}
			} catch (Throwable error) {
				// 查询失败（上下文异常等）不下结论，避免误判
			}
		}
		return unlinked;
	}

	public static boolean isHealthy(WorldRenderingPipeline pipeline) {
		return findUnlinkedPrograms(pipeline).isEmpty();
	}

	/** 给日志/聊天框用的一句话说明。 */
	public static String describe(List<String> unlinked) {
		if (unlinked.size() <= 6) {
			return String.join("、", unlinked);
		}
		return String.join("、", unlinked.subList(0, 6)) + " 等 " + unlinked.size() + " 个";
	}
}
