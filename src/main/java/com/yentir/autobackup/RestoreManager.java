package com.yentir.autobackup;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 游戏内回档：/backup restore 选择备份 → /backup confirm 确认 → 倒计时 → 踢人关服
 * → 服务器完全关闭后替换世界 → 写入重启标记，由启动脚本重启服务器。
 * 除了定时器线程只负责“敲门”，所有方法都在服务器主线程运行。
 */
public class RestoreManager {
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final long CONFIRM_TIMEOUT_MILLIS = 30_000;
	private static final int COUNTDOWN_SECONDS = 10;
	// start.sh 看到这个文件就会重启服务器
	private static final String RESTART_FLAG = ".auto-backup-restart";

	private final BackupManager backups;

	// 和 BackupManager 一样不能用 tick 事件计时：没人在线时服务器会暂停
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "AutoBackup-Restore");
		thread.setDaemon(true);
		return thread;
	});

	// 等待确认的请求
	private String pendingRequester;
	private Path pendingBackup;
	private long pendingExpiresAt;

	// 已经确认、关服后要执行的回档
	private Path confirmedBackup;
	private ScheduledFuture<?> countdown;
	private int secondsLeft;

	public RestoreManager(BackupManager backups) {
		this.backups = backups;
	}

	public List<Path> listBackups(MinecraftServer server) {
		try {
			String worldName = backups.worldDir(server).getFileName().toString();
			return BackupFiles.listBackups(backups.backupRoot(server), worldName);
		} catch (IOException e) {
			AutoBackup.LOGGER.error("读取备份列表失败", e);
			return List.of();
		}
	}

	public int request(CommandSourceStack source, String backupName) {
		MinecraftServer server = source.getServer();
		if (!server.isDedicatedServer()) {
			source.sendFailure(Component.literal("回档只能在专用服务器上使用"));
			return 0;
		}
		if (confirmedBackup != null) {
			source.sendFailure(Component.literal("已经在准备回档了，服务器马上就会重启"));
			return 0;
		}
		// 只接受备份列表里的文件名，不接受路径，防止回档到备份目录以外的文件
		Optional<Path> backup = listBackups(server).stream()
				.filter(path -> path.getFileName().toString().equals(backupName))
				.findFirst();
		if (backup.isEmpty()) {
			source.sendFailure(Component.literal("找不到备份：" + backupName + "，可以用 /backup list 查看所有备份"));
			return 0;
		}

		pendingRequester = source.getTextName();
		pendingBackup = backup.get();
		pendingExpiresAt = System.currentTimeMillis() + CONFIRM_TIMEOUT_MILLIS;

		Component message = Component.literal("即将回档到 " + backupName + "，这会踢出所有玩家并重启服务器。")
				.withStyle(ChatFormatting.YELLOW)
				.append(Component.literal("\n" + CONFIRM_TIMEOUT_MILLIS / 1000 + " 秒内输入 /backup confirm 确认")
						.withStyle(ChatFormatting.GOLD));
		source.sendSuccess(() -> message, false);
		return 1;
	}

	public int confirm(CommandSourceStack source) {
		if (pendingBackup == null) {
			source.sendFailure(Component.literal("没有等待确认的回档，请先使用 /backup restore <备份名>"));
			return 0;
		}
		if (System.currentTimeMillis() > pendingExpiresAt) {
			clearPending();
			source.sendFailure(Component.literal("确认已超时，请重新执行 /backup restore"));
			return 0;
		}
		if (!source.getTextName().equals(pendingRequester)) {
			source.sendFailure(Component.literal("只有发起回档的 " + pendingRequester + " 才能确认"));
			return 0;
		}

		confirmedBackup = pendingBackup;
		clearPending();
		secondsLeft = COUNTDOWN_SECONDS;

		MinecraftServer server = source.getServer();
		source.sendSuccess(() -> Component.literal("已确认回档到 " + confirmedBackup.getFileName() + "，开始倒计时")
				.withStyle(ChatFormatting.GREEN), true);
		countdown = timer.scheduleAtFixedRate(() -> server.execute(() -> countdownTick(server)), 0, 1, TimeUnit.SECONDS);
		return 1;
	}

	private void clearPending() {
		pendingRequester = null;
		pendingBackup = null;
	}

	private void countdownTick(MinecraftServer server) {
		if (confirmedBackup == null || countdown == null) {
			return;
		}
		if (secondsLeft <= 0) {
			countdown.cancel(false);
			countdown = null;
			// 复制一份列表再踢人，踢人的过程中原列表会变化
			for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
				player.connection.disconnect(Component.literal("服务器正在回档，请稍后重新连接"));
			}
			AutoBackup.LOGGER.info("开始回档，正在关闭服务器……");
			server.halt(false);
			return;
		}
		if (secondsLeft == COUNTDOWN_SECONDS || secondsLeft <= 5) {
			String message = "服务器将在 " + secondsLeft + " 秒后回档到 " + confirmedBackup.getFileName() + " 并重启";
			AutoBackup.LOGGER.info(message);
			Component text = Component.literal("[自动备份] ").withStyle(ChatFormatting.GOLD)
					.append(Component.literal(message).withStyle(ChatFormatting.RED));
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				player.sendSystemMessage(text);
			}
		}
		secondsLeft--;
	}

	/**
	 * 服务器完全关闭后调用（SERVER_STOPPED）。这时存档已经释放，可以安全地替换文件。
	 */
	public void onServerStopped(MinecraftServer server) {
		if (countdown != null) {
			countdown.cancel(false);
			countdown = null;
		}
		if (confirmedBackup == null) {
			return;
		}
		Path backup = confirmedBackup;
		confirmedBackup = null;

		Path worldDir = backups.worldDir(server);
		String worldName = worldDir.getFileName().toString();
		Path safetyBackup = backups.backupRoot(server)
				.resolve(worldName + "_" + LocalDateTime.now().format(FILE_TIME) + "_before-restore.zip");

		// 第 1 步：先把当前世界备份一份，回档后后悔了还能恢复回来
		boolean safe;
		try {
			BackupFiles.zipWorld(worldDir, safetyBackup);
			AutoBackup.LOGGER.info("回档前的世界已备份到：{}", safetyBackup.getFileName());
			safe = true;
		} catch (IOException e) {
			AutoBackup.LOGGER.error("回档前备份当前世界失败，为了安全已取消回档，原世界保持不变", e);
			safe = false;
		}

		// 第 2 步：用选中的备份替换世界
		if (safe) {
			try {
				WorldRestorer.restore(backup, worldDir);
				AutoBackup.LOGGER.info("回档完成：世界已恢复到 {}", backup.getFileName());
			} catch (IOException e) {
				AutoBackup.LOGGER.error("回档失败", e);
			}
		}

		// 第 3 步：写入重启标记，让服务器重新启动，不要一直停着；世界不见了就不重启
		Path flag = server.getServerDirectory().resolve(RESTART_FLAG);
		try {
			if (WorldRestorer.requestRestart(flag, worldDir)) {
				AutoBackup.LOGGER.info("已写入重启标记 {}，等待启动脚本重启服务器", RESTART_FLAG);
			} else {
				AutoBackup.LOGGER.error("世界文件夹不见了！为了避免重启后生成一个全新的世界，没有写入重启标记。"
						+ "原世界可能在 {}.old 文件夹或 {} 里，请手动恢复后再启动服务器", worldName, safetyBackup.getFileName());
			}
		} catch (IOException e) {
			AutoBackup.LOGGER.error("写入重启标记失败，请手动启动服务器", e);
		}
	}
}
