package com.yentir.autobackup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 用备份替换世界文件夹。只能在服务器完全关闭、存档被释放之后调用。
 * 不依赖任何 Minecraft 类，可以直接写单元测试。
 */
public final class WorldRestorer {
	private static final Logger LOGGER = LoggerFactory.getLogger(AutoBackup.MOD_ID);

	private WorldRestorer() {
	}

	/**
	 * 先解压到同级的 “世界名.restoring”，检查无误后再和原世界交换。
	 * 抛出异常时保证原世界不变，也不会留下临时文件夹。
	 */
	public static void restore(Path backupZip, Path worldDir) throws IOException {
		worldDir = worldDir.toAbsolutePath().normalize();
		Path restoring = sibling(worldDir, ".restoring");
		Path old = sibling(worldDir, ".old");

		// 上次回档在两次改名之间断电的话，原世界只剩 .old，这时绝不能删掉它
		if (!Files.exists(worldDir) && Files.exists(old)) {
			throw new IOException("世界文件夹不存在，但发现了 " + old.getFileName()
					+ "：上次回档可能没有完成，原世界还在里面，请手动检查后再回档");
		}

		// 上次回档如果中途断电，可能留下这两个文件夹
		deleteRecursively(restoring);
		deleteRecursively(old);

		try {
			extract(backupZip, restoring);
			if (!Files.isRegularFile(restoring.resolve("level.dat"))) {
				throw new IOException("备份里没有 level.dat，不是有效的存档：" + backupZip.getFileName());
			}
		} catch (IOException e) {
			deleteRecursively(restoring);
			throw e;
		} catch (RuntimeException e) {
			// 比如条目名里有 Windows 不允许的字符
			deleteRecursively(restoring);
			throw new IOException("解压备份失败：" + e.getMessage(), e);
		}

		// 交换：原世界 → .old，新世界 → 原位置。第二步失败就把原世界移回来
		if (Files.exists(worldDir)) {
			Files.move(worldDir, old);
		}
		try {
			Files.move(restoring, worldDir);
		} catch (IOException e) {
			if (Files.exists(old)) {
				Files.move(old, worldDir);
			}
			deleteRecursively(restoring);
			throw e;
		}

		try {
			deleteRecursively(old);
		} catch (IOException e) {
			// 回档已经成功，旧世界删不掉也不影响；下次回档前会再清理
			LOGGER.warn("删除旧世界文件夹失败：{}", old, e);
		}
	}

	/**
	 * 写入重启标记，让启动脚本重启服务器。世界不存在时不写：那样重启会生成一个全新的世界。
	 */
	public static boolean requestRestart(Path flagFile, Path worldDir) throws IOException {
		if (!Files.isRegularFile(worldDir.resolve("level.dat"))) {
			return false;
		}
		Files.writeString(flagFile, "");
		return true;
	}

	private static void extract(Path zipFile, Path targetDir) throws IOException {
		Files.createDirectories(targetDir);
		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName().replace('\\', '/');

				// 去掉第一级目录（备份时的世界文件夹名），所以根目录叫什么都可以
				int slash = name.indexOf('/');
				if (slash < 0 || slash == name.length() - 1) {
					continue;
				}
				Path out = targetDir.resolve(name.substring(slash + 1)).normalize();

				// 防止 “../” 之类的条目把文件写到临时文件夹外面（zip slip）
				if (!out.startsWith(targetDir)) {
					throw new IOException("备份里有不安全的路径，已拒绝：" + entry.getName());
				}

				if (entry.isDirectory()) {
					Files.createDirectories(out);
					continue;
				}
				Files.createDirectories(out.getParent());
				try (InputStream in = zip.getInputStream(entry)) {
					Files.copy(in, out);
				}
			}
		}
	}

	private static Path sibling(Path dir, String suffix) {
		return dir.resolveSibling(dir.getFileName() + suffix);
	}

	private static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			// 先删里面的文件，再删外面的文件夹
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}
}
