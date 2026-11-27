package com.yentir.autobackup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupFilesTest {
	@TempDir
	Path dir;

	private Path touch(String name) throws IOException {
		Path file = dir.resolve(name);
		Files.createDirectories(file.getParent());
		return Files.writeString(file, name);
	}

	private static List<String> names(List<Path> paths) {
		return paths.stream().map(path -> path.getFileName().toString()).toList();
	}

	@Test
	void listBackups_newestFirstAndOnlyThisWorld() throws IOException {
		touch("world_2026-01-01_00-00-00.zip");
		touch("world_2026-01-02_00-00-00.zip");
		touch("world_2026-01-02_00-00-00_before-restore.zip");
		touch("other_2026-01-03_00-00-00.zip");
		touch("world_x.txt");

		assertEquals(List.of(
				"world_2026-01-02_00-00-00_before-restore.zip",
				"world_2026-01-02_00-00-00.zip",
				"world_2026-01-01_00-00-00.zip"
		), names(BackupFiles.listBackups(dir, "world")));
	}

	@Test
	void listBackups_missingDirReturnsEmpty() throws IOException {
		assertEquals(Collections.emptyList(), BackupFiles.listBackups(dir.resolve("nope"), "world"));
	}

	// world_test 的备份也以 "world_" 开头，不能被当成 world 的备份
	@Test
	void listBackups_ignoresWorldWhoseNameStartsWithThisWorld() throws IOException {
		touch("world_2026-01-01_00-00-00.zip");
		touch("world_test_2026-01-02_00-00-00.zip");
		touch("world_test_2026-01-02_00-00-00_before-restore.zip");

		assertEquals(List.of("world_2026-01-01_00-00-00.zip"), names(BackupFiles.listBackups(dir, "world")));
	}

	@Test
	void prune_keepsBackupsOfWorldWhoseNameStartsWithThisWorld() throws IOException {
		touch("world_2026-01-01_00-00-00.zip");
		touch("world_test_2026-01-02_00-00-00.zip");

		BackupFiles.prune(dir, "world", 1);

		assertTrue(Files.exists(dir.resolve("world_2026-01-01_00-00-00.zip")));
		assertTrue(Files.exists(dir.resolve("world_test_2026-01-02_00-00-00.zip")));
	}

	// 世界名里的 . + ( ) 等字符要按原样匹配
	@Test
	void listBackups_worldNameWithSpecialCharacters() throws IOException {
		touch("my.world (1)+_2026-01-01_00-00-00.zip");
		touch("myXworld (1)+_2026-01-01_00-00-00.zip");

		assertEquals(List.of("my.world (1)+_2026-01-01_00-00-00.zip"),
				names(BackupFiles.listBackups(dir, "my.world (1)+")));
	}

	@Test
	void prune_keepsNewestN() throws IOException {
		touch("world_2026-01-01_00-00-00.zip");
		touch("world_2026-01-02_00-00-00.zip");
		touch("world_2026-01-03_00-00-00.zip");
		touch("other_2026-01-01_00-00-00.zip");

		BackupFiles.prune(dir, "world", 2);

		assertEquals(List.of("world_2026-01-03_00-00-00.zip", "world_2026-01-02_00-00-00.zip"),
				names(BackupFiles.listBackups(dir, "world")));
		assertTrue(Files.exists(dir.resolve("other_2026-01-01_00-00-00.zip")));
	}

	@Test
	void zipWorld_rootsEntriesAtFolderNameAndSkipsSessionLock() throws IOException {
		touch("world/level.dat");
		touch("world/region/r.0.0.mca");
		touch("world/session.lock");
		Path target = dir.resolve("backups/world_test.zip");

		BackupFiles.zipWorld(dir.resolve("world"), target);

		Set<String> entries = new HashSet<>();
		try (ZipFile zip = new ZipFile(target.toFile())) {
			zip.stream().forEach(entry -> entries.add(entry.getName()));
		}
		assertEquals(Set.of("world/level.dat", "world/region/r.0.0.mca"), entries);
	}
}
