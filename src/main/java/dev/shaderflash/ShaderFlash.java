package dev.shaderflash;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入口点：注册按键、tick 回调、HUD，并把配置界面的打开方式留给 Mod Menu。
 */
@Environment(EnvType.CLIENT)
public final class ShaderFlash implements ClientModInitializer {
	public static final String MOD_ID = "shaderflash";
	public static final Logger LOGGER = LoggerFactory.getLogger("Shader Flash");
	private static final String KEY_CATEGORY = "key.categories.shaderflash";

	private static KeyBinding swapKey;
	private static KeyBinding toggleKey;
	private static KeyBinding screenKey;

	@Override
	public void onInitializeClient() {
		DuoConfig.get();
		// 让「原版」光影包在 Iris 的选包界面里始终有一条：它就是一个放在 shaderpacks 下的目录包。
		if (DuoConfig.get().vanillaPackEntry) {
			VanillaPack.ensure();
		}

		swapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.shaderflash.swap", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7, KEY_CATEGORY));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.shaderflash.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, KEY_CATEGORY));
		screenKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.shaderflash.screen", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, KEY_CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			handleKeybinds(client);
			DuoManager.onClientTick(client);
		});

		HudRenderCallback.EVENT.register((context, tickDelta) -> {
			DuoConfig config = DuoConfig.get();
			if (config.hudStatus) {
				DuoManager.renderHud(context);
			}
		});

		LOGGER.info("[ShaderFlash] 已加载；Iris={} ModMenu={} Sodium={}",
			FabricLoader.getInstance().isModLoaded("iris"),
			FabricLoader.getInstance().isModLoaded("modmenu"),
			FabricLoader.getInstance().isModLoaded("sodium"));
	}

	private static void handleKeybinds(net.minecraft.client.MinecraftClient client) {
		while (swapKey.wasPressed()) {
			DuoManager.swapPrimary();
		}
		while (toggleKey.wasPressed()) {
			DuoConfig config = DuoConfig.get();
			config.enabled = !config.enabled;
			config.save();
			DuoManager.onEnabledChanged();
			DuoManager.feedback(ModTexts.text(config.enabled
				? ModTexts.MESSAGE_ENABLED : ModTexts.MESSAGE_DISABLED));
		}
		while (screenKey.wasPressed()) {
			client.setScreen(new DuoConfigScreen(client.currentScreen));
		}
	}
}
