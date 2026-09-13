package dev.shaderflash.mixin;

import dev.shaderflash.ShaderFlash;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.FallbackShader;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 实体着色器安全网：**绝不让原版着色器去画「扩展顶点格式」的实体顶点**。
 *
 * <h2>为什么必须有这一层</h2>
 * Iris 的顶点写出与着色器选择是<b>两处独立判断</b>（都在 Iris 1.7.2 的真实字节码里核对过）：
 *
 * <ul>
 *   <li><b>写出的顶点是什么格式</b>：Sodium 兼容层的
 *       {@code MixinModelVertex}/${@code MixinEntityRenderDispatcher} 用
 *       {@code IrisApi.isShaderPackInUse() && ImmediateState.renderWithExtendedVertexFormat}
 *       决定按 {@code EntityVertex}（扩展格式）还是原版 @{@code ModelVertex} 写；</li>
 *   <li><b>用哪个着色器画</b>：{@code MixinGameRenderer} 的 {@code getRendertypeEntity*Shader}
 *       注入只在 {@code shouldOverrideShaders() == isRenderingWorld && isMainBound}
 *       为真时才把返回的着色器换成当前管线的 {@code ShaderMap}；否则<b>保留原版着色器</b>。</li>
 * </ul>
 *
 * 正常情况下两者一致。但 {@code shouldOverrideShaders()} 依赖两个<b>运行时</b>标志，
 * 而 {@code isShaderPackInUse()} 只看“当前管线是不是 IrisRenderingPipeline”：
 * 一旦出现“装了光影、顶点按扩展格式写、但这一帧 {@code shouldOverrideShaders()} 为假”
 * （阴影阶段、手部、方块实体、或者任何让 {@code isMainBound}/{@code isRenderingWorld}
 * 落到 false 的路径，包括双管线切换与幽灵渲染带来的额外一帧），
 * 原版着色器就会按 {@code DefaultVertexFormat.NEW_ENTITY} 的偏移去读扩展格式的顶点：
 * <b>位置/UV/法线全部错位，实体要么糊成一团，要么直接画到屏幕外——玩家看到的就是“实体没了”。</b>
 *
 * <h2>这一层做什么</h2>
 * 在那些 getter 的返回处兜底：只要满足
 * 「装了光影」+「顶点确实是扩展格式」+「Iris 这次没接管（返回的不是它自己的着色器）」+
 * 「不在阴影/手部/方块实体这些自有分支里」，就把返回值换成当前管线的实体着色器。
 * 健康状态下这段代码不会改变任何东西（Iris 早已返回它自己的着色器），
 * 只有在状态错配时才会生效——并在日志里留一行证据。
 *
 * <p>方法名用 intermediary 名 + {@code remap = false}（本工程没有 refmap），
 * {@code require = 0} 保证换了 Iris 版本也不会因此崩游戏。
 */
@Mixin(value = GameRenderer.class, remap = false)
public class EntityShaderGuardMixin {
	private static long lastLogNanos;

	@Inject(method = "method_34502", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntitySolid(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_SOLID_DIFFUSE, cir);
	}

	@Inject(method = "method_34503", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntityCutout(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_CUTOUT_DIFFUSE, cir);
	}

	@Inject(method = "method_34504", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntityCutoutNoCull(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_CUTOUT_DIFFUSE, cir);
	}

	@Inject(method = "method_34505", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntityCutoutNoCullZOffset(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_CUTOUT_DIFFUSE, cir);
	}

	@Inject(method = "method_34509", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntitySmoothCutout(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_CUTOUT_DIFFUSE, cir);
	}

	@Inject(method = "method_34511", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntityDecal(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_CUTOUT_DIFFUSE, cir);
	}

	@Inject(method = "method_34506", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardItemEntityTranslucentCull(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_TRANSLUCENT, cir);
	}

	@Inject(method = "method_34507", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntityTranslucentCull(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_TRANSLUCENT, cir);
	}

	@Inject(method = "method_34508", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntityTranslucent(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_TRANSLUCENT, cir);
	}

	@Inject(method = "method_34512", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntityNoOutline(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_TRANSLUCENT, cir);
	}

	@Inject(method = "method_42595", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntityTranslucentEmissive(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_EYES_TRANS, cir);
	}

	@Inject(method = "method_34513", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntityShadow(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_CUTOUT, cir);
	}

	@Inject(method = "method_34514", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void shaderflash$guardEntityAlpha(CallbackInfoReturnable<ShaderProgram> cir) {
		shaderflash$guard(ShaderKey.ENTITIES_ALPHA, cir);
	}

	private static void shaderflash$guard(ShaderKey key, CallbackInfoReturnable<ShaderProgram> cir) {
		ShaderProgram returned = cir.getReturnValue();
		// Iris 已经接管（返回它自己的程序）→ 不插手。
		if (returned instanceof ExtendedShader || returned instanceof FallbackShader) {
			return;
		}
		// 没开光影 → 原版着色器正是对的。
		if (!IrisApi.getInstance().isShaderPackInUse()) {
			return;
		}
		// 顶点到底是不是扩展格式：只有「配置文件允许 + 运行时确实在用」时才可能错配。
		if (!WorldRenderingSettings.INSTANCE.shouldUseExtendedVertexFormat()
			|| !ImmediateState.renderWithExtendedVertexFormat) {
			return;
		}
		// 阴影 / 手部 有自己的一套程序（Iris 在那里返回的是它自己的着色器，走不到这里；这里是双保险）。
		if (ShadowRenderer.ACTIVE || HandRenderer.INSTANCE.isActive()) {
			return;
		}

		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		if (!(pipeline instanceof ShaderRenderingPipeline shaderPipeline)) {
			return;
		}
		// 方块实体的渲染类型也会走到这些 getter，Iris 有 BLOCK_ENTITY 分支，别抢。
		if (pipeline.getPhase() == WorldRenderingPhase.BLOCK_ENTITIES) {
			return;
		}

		ShaderProgram replacement = shaderPipeline.getShaderMap().getShader(key);
		if (replacement == null || replacement == returned) {
			return;
		}

		long now = System.nanoTime();
		if (now - lastLogNanos > 5_000_000_000L) {
			lastLogNanos = now;
			ShaderFlash.LOGGER.warn("[ShaderFlash] 实体着色器安全网生效：这一帧 Iris 没有接管着色器"
					+ "（shouldOverrideShaders={}、isRenderingWorld/isMainBound 见自检），"
					+ "但顶点是扩展格式 —— 已改用当前管线的 {}，避免实体按错位的顶点布局绘制（表现为实体消失/糊成一团）。",
				shaderPipeline.shouldOverrideShaders(), key);
		}
		cir.setReturnValue(replacement);
	}
}
