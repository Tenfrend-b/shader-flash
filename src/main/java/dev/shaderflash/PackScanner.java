package dev.shaderflash;

import net.irisshaders.iris.Iris;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 光影包目录扫描 & 选项读取。
 *
 * <p>选项文件（{@code shaderpacks/<包名>.txt}）里存着玩家在光影设置界面里改过的选项。
 * 副管线必须读同一份选项，否则两条管线的画面参数会不一样。
 */
public final class PackScanner {
	private PackScanner() {
	}

	/** shaderpacks 目录下所有可用的包名（目录名 / 去掉 .zip 的文件名）。 */
	public static List<String> listPacks() {
		List<String> result = new ArrayList<>();
		// 「原版」是模组维护的目录型光影包：先确保它在，再列出来。
		if (DuoConfig.get().vanillaPackEntry) {
			VanillaPack.ensure();
		}
		Path directory = Iris.getShaderpacksDirectory();
		if (directory == null || !Files.isDirectory(directory)) {
			return result;
		}
		try (Stream<Path> stream = Files.list(directory)) {
			stream.forEach(entry -> {
				String fileName = entry.getFileName().toString();
				if (Files.isDirectory(entry)) {
					if (!fileName.startsWith(".")) {
						result.add(fileName);
					}
				} else if (fileName.toLowerCase().endsWith(".zip")) {
					result.add(fileName.substring(0, fileName.length() - 4));
				}
			});
		} catch (IOException error) {
			ShaderFlash.LOGGER.warn("[ShaderFlash] 无法列出 shaderpacks 目录", error);
		}
		result.sort(Comparator.naturalOrder());
		if (result.remove(VanillaPack.NAME)) {
			// 让「原版」排在最前面：它是“当前就是原生渲染”的那一项，最常被拿来做对照。
			result.add(0, VanillaPack.NAME);
		}
		return result;
	}

	/** 解析成实际存在的路径：先找同名目录，再找 .zip。找不到返回 null。 */
	public static Path resolvePackPath(String name) {
		Path directory = Iris.getShaderpacksDirectory();
		if (directory == null || name == null || name.isBlank()) {
			return null;
		}
		Path direct = directory.resolve(name);
		if (Files.exists(direct)) {
			return direct;
		}
		Path zip = directory.resolve(name + ".zip");
		if (Files.exists(zip)) {
			return zip;
		}
		return null;
	}

	/**
	 * 读取某个包的选项覆盖。Iris 自己只会在“切换到这个包”时读，这里照做一遍。
	 *
	 * <p>选项文件名有讲究：Iris 用的是<b>完整的包名</b>加 {@code .txt}，
	 * 所以 zip 包的选项文件其实是 {@code BSL_v8.2.07.1.zip.txt}，而不是去掉扩展名的
	 * {@code BSL_v8.2.07.1.txt}。两种都试一遍，免得副管线漏掉玩家改过的参数。
	 *
	 * <p>注意：这里<b>不</b>合并 {@code Iris.getShaderPackOptionQueue()}。那个队列属于
	 * 主光影包（玩家刚在光影设置里改动、还没保存的值），套到副包头上会张冠李戴。
	 */
	public static Map<String, String> readOptions(String name) {
		Map<String, String> options = new LinkedHashMap<>();
		Path directory = Iris.getShaderpacksDirectory();
		if (directory != null) {
			Path configFile = directory.resolve(name + ".zip.txt");
			if (!Files.isRegularFile(configFile)) {
				configFile = directory.resolve(name + ".txt");
			}
			if (Files.isRegularFile(configFile)) {
				readInto(configFile, options);
			}
		}
		return options;
	}

	private static void readInto(Path configFile, Map<String, String> options) {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
			properties.load(reader);
			for (String key : properties.stringPropertyNames()) {
				options.put(key, properties.getProperty(key));
			}
		} catch (IOException error) {
			ShaderFlash.LOGGER.warn("[ShaderFlash] 读取光影选项 {} 失败", configFile, error);
		}
	}
}
