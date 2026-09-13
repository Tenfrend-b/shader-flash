package dev.shaderflash;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把副光影包“铺”成一份本地可读的目录副本。
 *
 * <h2>为什么不用原包直接加载</h2>
 * <ul>
 *   <li>zip 包要用 zipfs 打开，而 Iris 自己的 zipfs 会在重载时被关掉，副管线是常驻的；</li>
 *   <li>主副是同一个包时，同一个 zip 不允许被打开两次。</li>
 * </ul>
 *
 * <p>副本放在 {@code config/shaderflash/packcache/<包名>/}，用“来源大小 + 修改时间”做戳记，
 * 只有来源变了才重新解包。玩家的 shaderpacks 目录始终不动。
 *
 * <p>这里<b>不做任何包内容改写</b>。历史上曾有过两条改写（GLSL 4.x 保留字改名、
 * 实体属性重塑），都已删除：前者救不回语义已经丢失的老着色器代码，后者基于一个被推翻的
 * 判断。详见 CHANGELOG。
 *
 * <p>本类只做纯文件 I/O，不碰 GL、不碰 Iris 全局状态，因此可以安全地在后台线程调用。
 */
public final class PackPreparer {
	/** 处理流程变了就把它加一，旧缓存会自动重建（v4：删除保留字改名后的缓存格式）。 */
	private static final String FORMAT_VERSION = "v4";

	private PackPreparer() {
	}

	/**
	 * @param name   包名（用于缓存目录名）
	 * @param source shaderpacks 里的原始 zip 或目录
	 */
	public static Result prepare(String name, Path source) throws IOException {
		Path cacheRoot = FabricLoader.getInstance().getConfigDir()
			.resolve("shaderflash").resolve("packcache");
		Files.createDirectories(cacheRoot);

		String safe = sanitize(name);
		Path packDir = cacheRoot.resolve(safe);
		Path stampFile = cacheRoot.resolve(safe + ".stamp");
		String stamp = String.join("|",
			FORMAT_VERSION,
			"size=" + Files.size(source),
			"mtime=" + Files.getLastModifiedTime(source).toMillis(),
			"src=" + source.getFileName());

		if (Files.isDirectory(packDir) && Files.isRegularFile(stampFile)
			&& stamp.equals(Files.readString(stampFile, StandardCharsets.UTF_8).trim())) {
			Path root = shaderRoot(packDir);
			if (root != null) {
				return new Result(root, packDir, true);
			}
		}

		deleteTree(packDir);
		Files.createDirectories(packDir);
		if (Files.isDirectory(source)) {
			copyTree(source, packDir);
		} else {
			extractZip(source, packDir);
		}

		Path root = shaderRoot(packDir);
		if (root == null) {
			throw new IOException(ModTexts.str(ModTexts.ERROR_PACK_NO_SHADERS, name));
		}
		Files.writeString(stampFile, stamp + "\n", StandardCharsets.UTF_8);
		return new Result(root, packDir, false);
	}

	/** Iris 传入 ShaderPack 的根目录是包内的 {@code shaders/} 目录（目录包与 zip 包都一样）。 */
	private static Path shaderRoot(Path packDir) {
		Path shaders = packDir.resolve("shaders");
		if (Files.isDirectory(shaders)) {
			return shaders;
		}
		return Files.isDirectory(packDir) ? packDir : null;
	}

	private static String sanitize(String name) {
		StringBuilder builder = new StringBuilder(name.length());
		for (char character : name.toCharArray()) {
			if (Character.isLetterOrDigit(character) || character == '-' || character == '_' || character == '.') {
				builder.append(character);
			} else {
				builder.append('_');
			}
		}
		return builder.toString().toLowerCase(Locale.ROOT);
	}

	private static void extractZip(Path zipPath, Path target) throws IOException {
		try (ZipFile zip = new ZipFile(zipPath.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				Path destination = target.resolve(entry.getName()).normalize();
				if (!destination.startsWith(target)) {
					continue; // 防御 zip slip
				}
				if (entry.isDirectory()) {
					Files.createDirectories(destination);
					continue;
				}
				Files.createDirectories(destination.getParent());
				try (InputStream in = zip.getInputStream(entry);
					 OutputStream out = Files.newOutputStream(destination)) {
					in.transferTo(out);
				}
			}
		}
	}

	private static void copyTree(Path from, Path to) throws IOException {
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(to.resolve(from.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, to.resolve(from.relativize(file).toString()),
					StandardCopyOption.REPLACE_EXISTING);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void deleteTree(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		Files.walkFileTree(path, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
				Files.deleteIfExists(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	public record Result(Path root, Path packDirectory, boolean fromCache) {
	}
}
