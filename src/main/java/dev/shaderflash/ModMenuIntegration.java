package dev.shaderflash;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 集成：在模组列表里给出齿轮按钮，直接打开设置界面。
 *
 * <p>这个类只由 Mod Menu 自己加载（"modmenu" 入口点），没装 Mod Menu 时不会被碰，
 * 模组本体照常工作，按 F8 一样能打开界面。
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return DuoConfigScreen::new;
	}
}
