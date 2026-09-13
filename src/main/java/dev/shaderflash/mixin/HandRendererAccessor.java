package dev.shaderflash.mixin;

import net.irisshaders.iris.pathways.HandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 打开 {@code HandRenderer.ACTIVE}。
 *
 * <p>这个标志直接决定<b>实体渲染类型用哪套程序</b>：Iris 的 {@code MixinGameRenderer} 在
 * 实体渲染类型的 getter 里写着 {@code else if (HandRenderer.INSTANCE.isActive()) → HAND_*}，
 * 也就是说它只要残留为 true，主画面里的实体会被当成“手”去渲染（程序、绑定目标都变了）。
 *
 * <p>幽灵渲染会完整跑一遍世界渲染（包括手部），所以我们把它和别的全局标志一样
 * 在那一遍前后保存/还原：任何一条异常路径都不该把它留给主画面。
 */
@Mixin(value = HandRenderer.class, remap = false)
public interface HandRendererAccessor {
	@Accessor("ACTIVE")
	boolean shaderflash$getActive();

	@Accessor("ACTIVE")
	void shaderflash$setActive(boolean active);
}
