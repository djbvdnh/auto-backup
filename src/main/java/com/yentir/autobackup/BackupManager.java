package com.yentir.autobackup;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 备份的核心逻辑。
 * 除了 zipWorld / prune 在后台线程运行，其余方法都只在服务器主线程调用。
 */
public class BackupManager {
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final DateTimeFormatter CHAT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

	// 只有一个线程的线程池：同一时间最多只做一次备份
	private final ExecutorService executor = Executors.newSingleThreadExecutor(daemonThread("AutoBackup"));

	// 定时器线程：每秒让主线程检查一次是否该备份了。
	// 不能用 Fabric 的 tick 事件：没人在线 60 秒后服务器会暂停（pause-when-empty-seconds），
	// 暂停期间 tick 事件全都不触发，但主线程仍然会执行 server.execute 交给它的任务
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemonThread("AutoBackup-Timer"));
	private ScheduledFuture<?> timerTask;

	// 被我们暂停了自动保存的维度，备份结束后要恢复
	private final List<ServerLevel> pausedLevels = new ArrayList<>();

	private BackupConfig config;
	private long nextBackupAt;
	private Future<?> currentBackup;
	private String lastResult = "本次启动后还没有备份过";
	// 上次备份之后有没有玩家在线过；没人玩过存档就不会变，不需要再备份
	private boolean playersOnlineSinceLastBackup;

	public BackupManager(BackupConfig config) {
		this.config = config;
	}

	public void setConfig(BackupConfig config) {
		this.config = config;
		scheduleNext();
	}

	public void scheduleNext() {
		nextBackupAt = System.currentTimeMillis() + config.intervalMinutes * 60_000L;
	}

	public boolean isRunning() {
		return currentBackup != null;
	}

	public long millisUntilNext() {
		return Math.max(0, nextBackupAt - System.currentTimeMillis());
	}

	public String getLastResult() {
		return lastResult;
	}

	public void onServerStarted(MinecraftServer server) {
		scheduleNext();
		timerTask = timer.scheduleAtFixedRate(() -> server.execute(() -> tick(server)), 1, 1, TimeUnit.SECONDS);
	}

	// 每秒在主线程里调用一次，只做几次简单判断，开销很小
	private void tick(MinecraftServer server) {
		if (server.getPlayerList().getPlayerCount() > 0) {
			playersOnlineSinceLastBackup = true;
		}
		if (isRunning() || System.currentTimeMillis() < nextBackupAt) {
			return;
		}
		if (!playersOnlineSinceLastBackup) {
			scheduleNext();
			AutoBackup.LOGGER.info("上次备份后没有玩家在线，存档没有变化，跳过本次自动备份");
			return;
		}
		start(server);
	}

	/**
	 * 开始一次备份。返回 false 表示没能开始（正在备份中，或配置有问题）。
	 */
	public boolean start(MinecraftServer server) {
		if (isRunning()) {
			return false;
		}
		scheduleNext();

		Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		Path backupRoot = server.getServerDirectory().resolve(config.backupDir).toAbsolutePath().normalize();
		if (backupRoot.startsWith(worldDir)) {
			// 备份目录在存档里面的话，会把备份自己也打包进去，越备份越大
			AutoBackup.LOGGER.error("备份目录不能放在存档目录里面：{}", backupRoot);
			return false;
		}
		String worldName = worldDir.getFileName().toString();
		Path target = backupRoot.resolve(worldName + "_" + LocalDateTime.now().format(FILE_TIME) + ".zip");
		// 重新开始记录；之后只要有玩家在线，tick() 会再把它设回 true
		playersOnlineSinceLastBackup = false;

		announce(server, "开始备份存档……", ChatFormatting.YELLOW);

		// 第 1 步：把内存里的区块、玩家数据全部写到硬盘（相当于 /save-all flush）
		server.saveEverything(true, true, true);

		// 第 2 步：暂停自动保存（相当于 /save-off），防止压缩过程中文件被改写
		for (ServerLevel level : server.getAllLevels()) {
			if (!level.noSave) {
				level.noSave = true;
				pausedLevels.add(level);
			}
		}

		// 第 3 步：在后台线程压缩，服务器继续正常运行不会卡顿
		int maxBackups = config.maxBackups;
		long startTime = System.currentTimeMillis();
		currentBackup = executor.submit(() -> {
			try {
				long size = zipWorld(worldDir, target);
				prune(backupRoot, worldName, maxBackups);
				double seconds = (System.currentTimeMillis() - startTime) / 1000.0;
				String message = String.format("备份完成：%s（%s，耗时 %.1f 秒）",
						target.getFileName(), formatSize(size), seconds);
				// 回到服务器主线程去收尾，游戏对象只能在主线程里操作
				server.execute(() -> finish(server, message, ChatFormatting.GREEN));
			} catch (Exception e) {
				AutoBackup.LOGGER.error("备份失败", e);
				server.execute(() -> finish(server, "备份失败：" + e.getMessage(), ChatFormatting.RED));
			}
		});
		return true;
	}

	// 第 4 步：恢复自动保存（相当于 /save-on），并通知结果
	private void finish(MinecraftServer server, String message, ChatFormatting color) {
		resumeSaving();
		currentBackup = null;
		lastResult = LocalDateTime.now().format(CHAT_TIME) + " " + message;
		announce(server, message, color);
	}

	private void resumeSaving() {
		for (ServerLevel level : pausedLevels) {
			level.noSave = false;
		}
		pausedLevels.clear();
	}

	/**
	 * 服务器关闭前调用：如果正在备份，等它压缩完再让服务器做最后一次保存，
	 * 否则服务器写文件和我们读文件同时进行，备份可能损坏。
	 */
	public void onServerStopping() {
		if (timerTask != null) {
			timerTask.cancel(false);
			timerTask = null;
		}
		if (currentBackup == null) {
			return;
		}
		AutoBackup.LOGGER.info("服务器正在关闭，等待当前备份完成……");
		try {
			currentBackup.get(10, TimeUnit.MINUTES);
		} catch (Exception e) {
			AutoBackup.LOGGER.error("等待备份完成时出错", e);
		}
		// 主线程正在关闭，前面用 server.execute 排队的收尾任务不会再执行，所以这里手动恢复
		resumeSaving();
		currentBackup = null;
	}

	private void announce(MinecraftServer server, String message, ChatFormatting color) {
		AutoBackup.LOGGER.info(message);
		if (config.announce) {
			// 前缀金色，正文按消息类型着色：进行中黄色、成功绿色、失败红色
			Component text = Component.literal("[自动备份] ").withStyle(ChatFormatting.GOLD)
					.append(Component.literal(message).withStyle(color));
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				player.sendSystemMessage(text);
			}
		}
	}

	// ---------- 以下两个方法在后台线程运行，只做文件操作，不碰任何游戏对象 ----------

	private static long zipWorld(Path worldDir, Path target) throws IOException {
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

	// 只保留最新的 maxBackups 个备份。文件名里的时间格式按字母排序就是按时间排序
	private static void prune(Path backupRoot, String worldName, int maxBackups) throws IOException {
		if (maxBackups <= 0) {
			return;
		}
		List<Path> backups;
		try (Stream<Path> files = Files.list(backupRoot)) {
			backups = files
					.filter(path -> {
						String name = path.getFileName().toString();
						return name.startsWith(worldName + "_") && name.endsWith(".zip");
					})
					.sorted(Comparator.reverseOrder())
					.toList();
		}
		for (int i = maxBackups; i < backups.size(); i++) {
			Files.delete(backups.get(i));
			AutoBackup.LOGGER.info("已删除旧备份：{}", backups.get(i).getFileName());
		}
	}

	// 守护线程不会阻止 Java 进程退出
	private static ThreadFactory daemonThread(String name) {
		return runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		};
	}

	private static String formatSize(long bytes) {
		if (bytes < 1024 * 1024) {
			return String.format("%.1f KB", bytes / 1024.0);
		}
		if (bytes < 1024L * 1024 * 1024) {
			return String.format("%.1f MB", bytes / (1024.0 * 1024));
		}
		return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
	}
}
