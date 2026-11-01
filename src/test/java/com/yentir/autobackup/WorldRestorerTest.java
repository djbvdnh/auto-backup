package com.yentir.autobackup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldRestorerTest {
	@TempDir
	Path dir;

	private Path world;

	@BeforeEach
	void createOldWorld() throws IOException {
		world = dir.resolve("world");
		write(world.resolve("level.dat"), "old");
		write(world.resolve("marker.txt"), "marker");
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	// 用真正的备份逻辑打包一个“新世界”
	private Path backupOf(String folderName) throws IOException {
		Path source = dir.resolve("source").resolve(folderName);
		write(source.resolve("level.dat"), "new");
		write(source.resolve("region/r.0.0.mca"), "region");
		Path zip = dir.resolve("backups/" + folderName + "_test.zip");
		BackupFiles.zipWorld(source, zip);
		return zip;
	}

	// 手写 zip，用来构造恶意或不完整的压缩包
	private Path handmadeZip(String... entryNames) throws IOException {
		Path zip = dir.resolve("handmade.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (String name : entryNames) {
				out.putNextEntry(new ZipEntry(name));
				out.write(name.getBytes());
				out.closeEntry();
			}
		}
		return zip;
	}

	private void assertOldWorldUntouched() throws IOException {
		assertEquals("old", Files.readString(world.resolve("level.dat")));
		assertTrue(Files.exists(world.resolve("marker.txt")));
		assertNoTempDirs();
	}

	private void assertNoTempDirs() {
		assertFalse(Files.exists(dir.resolve("world.restoring")));
		assertFalse(Files.exists(dir.resolve("world.old")));
	}

	@Test
	void restore_replacesWorld() throws IOException {
		WorldRestorer.restore(backupOf("world"), world);

		assertEquals("new", Files.readString(world.resolve("level.dat")));
		assertTrue(Files.exists(world.resolve("region/r.0.0.mca")));
		assertFalse(Files.exists(world.resolve("marker.txt")));
		assertNoTempDirs();
	}

	@Test
	void restore_acceptsDifferentRootFolderName() throws IOException {
		WorldRestorer.restore(backupOf("myworld"), world);

		assertEquals("new", Files.readString(world.resolve("level.dat")));
		assertTrue(Files.exists(world.resolve("region/r.0.0.mca")));
		assertNoTempDirs();
	}

	@Test
	void restore_rejectsZipSlip() throws IOException {
		Path zip = handmadeZip("world/level.dat", "world/../../evil.txt");

		IOException e = assertThrows(IOException.class, () -> WorldRestorer.restore(zip, world));
		assertTrue(e.getMessage().contains("不安全的路径"), e.getMessage());

		assertFalse(Files.exists(dir.getParent().resolve("evil.txt")));
		assertFalse(Files.exists(dir.resolve("evil.txt")));
		assertOldWorldUntouched();
	}

	@Test
	void restore_rejectsMissingLevelDat() throws IOException {
		Path zip = handmadeZip("world/region/r.0.0.mca");

		IOException e = assertThrows(IOException.class, () -> WorldRestorer.restore(zip, world));
		assertTrue(e.getMessage().contains("level.dat"), e.getMessage());

		assertOldWorldUntouched();
	}

	@Test
	void restore_rejectsCorruptZip() throws IOException {
		Path zip = dir.resolve("corrupt.zip");
		byte[] junk = new byte[4096];
		new Random(42).nextBytes(junk);
		try (OutputStream out = Files.newOutputStream(zip)) {
			out.write(junk);
		}

		assertThrows(IOException.class, () -> WorldRestorer.restore(zip, world));

		assertOldWorldUntouched();
	}

	// 上次回档在两次改名之间断电：world 不见了，原世界只剩 world.old，不能把它当垃圾删掉
	@Test
	void restore_refusesWhenWorldMissingButOldExists() throws IOException {
		Files.move(world, dir.resolve("world.old"));
		Path backup = backupOf("world");

		IOException e = assertThrows(IOException.class, () -> WorldRestorer.restore(backup, world));
		assertTrue(e.getMessage().contains("world.old"), e.getMessage());

		assertEquals("old", Files.readString(dir.resolve("world.old/level.dat")));
		assertFalse(Files.exists(world));
	}

	@Test
	void requestRestart_writesFlagWhenWorldPresent() throws IOException {
		Path flag = dir.resolve(".auto-backup-restart");

		assertTrue(WorldRestorer.requestRestart(flag, world));
		assertTrue(Files.exists(flag));
	}

	// 没有世界就重启，服务器会生成一个全新的世界
	@Test
	void requestRestart_refusesWhenWorldMissing() throws IOException {
		Path flag = dir.resolve(".auto-backup-restart");
		Files.move(world, dir.resolve("world.old"));

		assertFalse(WorldRestorer.requestRestart(flag, world));
		assertFalse(Files.exists(flag));
	}

	@Test
	void restore_cleansStaleTempDirs() throws IOException {
		write(dir.resolve("world.restoring/junk.txt"), "junk");
		write(dir.resolve("world.old/junk.txt"), "junk");

		WorldRestorer.restore(backupOf("world"), world);

		assertEquals("new", Files.readString(world.resolve("level.dat")));
		assertFalse(Files.exists(world.resolve("junk.txt")));
		assertNoTempDirs();
	}
}
