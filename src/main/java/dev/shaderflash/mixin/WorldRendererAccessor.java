package dev.shaderflash.mixin;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 视锥体字段。Iris 在管线声明“禁用视锥剔除”时会把这个世界渲染器的视锥体换成一个不剔除的版本，
 * 副管线那一遍结束后必须把主管线原来的视锥体放回去，否则主画面会跟着一起变成不剔除。
 *
 * <p>字段名用 intermediary（{@code field_27740} = {@code frustum}）并 {@code remap = false}：
 * 本工程没有 refmap，运行时只认 intermediary 名。
 */
@Mixin(value = WorldRenderer.class, remap = false)
public interface WorldRendererAccessor {
	@Accessor("field_27740")
	Frustum shaderflash$getFrustum();

	@Accessor("field_27740")
	void shaderflash$setFrustum(Frustum frustum);
}
