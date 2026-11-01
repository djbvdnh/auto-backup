package com.yentir.autobackup;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 备份文件的读写操作。不依赖任何 Minecraft 类，可以在后台线程运行，也可以直接写单元测试。
 */
public final class BackupFiles {
	// 不用 AutoBackup.LOGGER：那样会加载 mod 入口类，单元测试里没有 Fabric 环境
	private static final Logger LOGGER = LoggerFactory.getLogger(AutoBackup.MOD_ID);

	private BackupFiles() {
	}

	/**
	 * 把世界文件夹打包成 zip，zip 里的路径以世界文件夹名开头。返回 zip 文件大小。
	 */
	public static long zipWorld(Path worldDir, Path target) throws IOException {
		Files.createDirectories(target.getParent());
		// 先写到 .tmp 文件，全部写完再改名，这样中途失败也不会留下一个损坏的 .zip
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		String rootName = worldDir.getFileName().toString();

		try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
			Files.walkFileTree(worldDir, new SimpleFileVisitor<>() {
				@Override
				public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
					// session.lock 被服务器独占锁住，读不了，也不需要备份
					if (file.getFileName().toString().equals("session.lock")) {
						return FileVisitResult.CONTINUE;
					}
					// zip 里的路径统一用 /，在 Windows 上也一样
					String entryName = rootName + "/" + worldDir.relativize(file).toString().replace('\\', '/');
					ZipEntry entry = new ZipEntry(entryName);
					entry.setLastModifiedTime(attrs.lastModifiedTime());
					zip.putNextEntry(entry);
					Files.copy(file, zip);
					zip.closeEntry();
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			Files.deleteIfExists(temp);
			throw e;
		}

		Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
		return Files.size(target);
	}

	/**
	 * 列出某个世界的所有备份，从新到旧。文件名里的时间格式按字母倒序就是按时间从新到旧。
	 */
	public static List<Path> listBackups(Path backupRoot, String worldName) throws IOException {
		if (!Files.isDirectory(backupRoot)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(backupRoot)) {
			return files
					.filter(path -> {
						String name = path.getFileName().toString();
						return name.startsWith(worldName + "_") && name.endsWith(".zip");
					})
					.sorted(Comparator.reverseOrder())
					.toList();
		}
	}

	// 只保留最新的 maxBackups 个备份；0 表示不限制
	public static void prune(Path backupRoot, String worldName, int maxBackups) throws IOException {
		if (maxBackups <= 0) {
			return;
		}
		List<Path> backups = listBackups(backupRoot, worldName);
		for (int i = maxBackups; i < backups.size(); i++) {
			Files.delete(backups.get(i));
			LOGGER.info("已删除旧备份：{}", backups.get(i).getFileName());
		}
	}

	public static String formatSize(long bytes) {
		if (bytes < 1024 * 1024) {
			return String.format("%.1f KB", bytes / 1024.0);
		}
		if (bytes < 1024L * 1024 * 1024) {
			return String.format("%.1f MB", bytes / (1024.0 * 1024));
		}
		return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
	}
}
