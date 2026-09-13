package dev.shaderflash;

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

/**
 * 一条“已经活着”的光影管线：包对象 + 编译好的管线对象。
 *
 * <p>副管线就是靠这个对象长期驻留内存/显存，切换时只交换指针，不重新编译。
 */
public final class PackSlot {
	/** 给玩家看/日志用的包名（不带 .zip）。 */
	public final String name;
	/** shaderpacks 目录里的真实条目名（带 .zip），写回 Iris 配置时必须用这个。 */
	public final String fileName;
	public final ShaderPack pack;
	public final WorldRenderingPipeline pipeline;
	public final NamespacedId dimension;
	/** 这条管线从零编译花了多少毫秒（用于对比切换耗时）。 */
	public final long buildMillis;
	/**
	 * 这条管线是否已经真的渲染过至少一整帧。
	 *
	 * <p>截图取景前要求它为 true：刚构造好的管线第一遍渲染时，Iris 的 composite/final
	 * 缓冲翻转状态还没跑起来，那一帧有可能是纯黑（玩家实测过一次）。
	 */
	public volatile boolean renderedOnce;
	/** 调试开关（{@code initialWarmupRender=false}）下是否已经提示过“跳过初始化渲染”。 */
	public volatile boolean initialRenderSkipped;

	/**
	 * 本次「副管线截图」已经渲染了多少帧预热帧（见 {@code DuoConfig#screenshotWarmupFrames}）。
	 *
	 * <p>MakeUp 这类包刚加载时亮度极低，要连续渲染 10~20 帧让它自己的“人眼适应”抬起来，
	 * 画面才可用——这是光影包的显示策略，与本模组无关。
	 */
	public volatile int warmupFrames;

	/**
	 * 这条管线的程序有没有<b>没链接上</b>的（驱动资源不足时会这样，见 {@link PipelineHealth}）。
	 *
	 * <p>预编译完成时检查一次；为 true 时<b>不允许</b>被提升为主管线——
	 * 用未链接的程序绘制等于什么都不画，玩家看到的就是“手部与实体消失”。
	 */
	public volatile boolean unhealthyPrograms;

	public PackSlot(String name, String fileName, ShaderPack pack, WorldRenderingPipeline pipeline,
					NamespacedId dimension, long buildMillis) {
		this.name = name;
		this.fileName = fileName;
		this.pack = pack;
		this.pipeline = pipeline;
		this.dimension = dimension;
		this.buildMillis = buildMillis;
	}
}
