package dev.shaderflash.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.compat.sodium.impl.block_context.BlockContextHolder;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让区块构建永远使用<b>当前</b>的方块 ID 表，而不是 {@code ChunkBuildBuffers} 构造时捕获的那一份。
 *
 * <h2>问题</h2>
 * Iris 的 Sodium 兼容层在 {@code ChunkBuildBuffers} 的构造函数里把
 * {@code WorldRenderingSettings.INSTANCE.getBlockStateIds()} 存进一个 {@code BlockContextHolder}：
 *
 * <pre>
 * &#64;Inject(method = "&lt;init&gt;", at = &#64;At("RETURN"))
 * private void iris$onConstruct(ChunkVertexType vertexType, CallbackInfo ci) {
 *     Object2IntMap&lt;BlockState&gt; blockStateIds = WorldRenderingSettings.INSTANCE.getBlockStateIds();
 *     this.contextHolder = new BlockContextHolder(blockStateIds);   // ← 捕获当时的表
 * }
 * </pre>
 *
 * 而 {@code ChunkBuildBuffers} 的生命周期是<b>整个 RenderSectionManager</b>（也就是整个世界会话）：
 * 它由 {@code RenderSectionManager} 构造函数里的 {@code new ChunkBuilder(...)} 创建，
 * 之后所有区块重建（包括本模组的“逐区块后台重建”）都会一直用这份捕获的表。
 *
 * <p>后果：切换主副光影后，即使我们更新了全局方块 ID 表并排队重建区块，
 * 重建出来的顶点里写的仍然是<b>上一条管线</b>的材质 ID。最直观的表现就是
 * “副管线预编译过、切为主管线后植被不再摇动”——光影包的摇动判定是
 * {@code material_mask = mc_Entity.x - 10000}，ID 不对就永远匹配不上，
 * 而且除非整个世界重载（重建 RenderSectionManager）否则永远不会恢复。
 *
 * <h2>做法</h2>
 * 只改一处取值：把 {@code BlockContextHolder.set(...)} 里读的那张表换成“当前”的表。
 * 这样 Iris 自己的行为（切换光影包走整世界重载）完全不变，而本模组的逐区块重建也能拿到正确的 ID。
 *
 * <p>这个 mixin 由 {@code DuoMixinPlugin} 条件加载：没装 Sodium、或这个 Iris 版本没有
 * Sodium 兼容层时直接跳过。
 */
@Mixin(value = BlockContextHolder.class, remap = false)
public class BlockContextHolderMixin {
	@Redirect(
		method = "set",
		at = @At(
			value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/objects/Object2IntMap;getOrDefault(Ljava/lang/Object;I)I",
			remap = false),
		remap = false)
	private int shaderflash$useCurrentBlockStateIds(Object2IntMap<BlockState> captured, Object state, int fallback) {
		Object2IntMap<BlockState> current = WorldRenderingSettings.INSTANCE.getBlockStateIds();
		// 没拿到当前表时退回捕获的那份，行为与 Iris 原版一致。
		return (current != null ? current : captured).getOrDefault(state, fallback);
	}
}
