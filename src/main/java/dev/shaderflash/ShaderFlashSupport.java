package dev.shaderflash;

import net.fabricmc.loader.api.FabricLoader;

/** 一些与 Iris / 装载器交互的杂项。 */
public final class ShaderFlashSupport {
	private static Boolean available;

	private ShaderFlashSupport() {
	}

	public static boolean available() {
		if (available == null) {
			boolean loaded = FabricLoader.getInstance().isModLoaded("iris");
			if (loaded) {
				try {
					Class.forName("net.irisshaders.iris.Iris");
				} catch (Throwable error) {
					loaded = false;
				}
			}
			available = loaded;
		}
		return available;
	}
}
