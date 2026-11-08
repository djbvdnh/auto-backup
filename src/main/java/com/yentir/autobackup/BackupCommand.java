package com.yentir.autobackup;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * /backup now     立即备份
 * /backup status  查看下次备份时间和上次结果
 * /backup reload  重新读取配置文件
 * /backup on      开启自动备份
 * /backup off     停止自动备份
 * /backup list    列出所有备份
 * /backup restore <备份名>  选择要回档的备份
 * /backup confirm 确认回档
 */
public final class BackupCommand {
	// 备份名以世界名开头，世界名可以是中文或带空格，word() 只接受英文字母数字，所以用 greedyString()。
	// 安全性不靠参数类型：RestoreManager 只接受备份列表里存在的文件名
	static final StringArgumentType BACKUP_NAME = StringArgumentType.greedyString();

	private BackupCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, BackupManager manager,
								RestoreManager restore) {
		dispatcher.register(Commands.literal("backup")
				// 和原版 /save-all 一样，只有最高权限（OP 4 级 / 控制台）能用
				.requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
				.then(Commands.literal("now").executes(context -> {
					CommandSourceStack source = context.getSource();
					if (manager.isRunning()) {
						source.sendFailure(Component.literal("已经有一个备份正在进行中"));
						return 0;
					}
					if (!manager.start(source.getServer())) {
						source.sendFailure(Component.literal("无法开始备份，请查看服务器日志"));
						return 0;
					}
					return 1;
				}))
				.then(Commands.literal("status").executes(context -> {
					String state = manager.isRunning()
							? "正在备份中……"
							: !manager.isEnabled()
							? "自动备份已停止"
							: "下次自动备份：" + formatDuration(manager.millisUntilNext()) + "后";
					Component message = Component.literal(state).withStyle(ChatFormatting.AQUA)
							.append(Component.literal("\n上次结果：" + manager.getLastResult()).withStyle(ChatFormatting.GRAY));
					context.getSource().sendSuccess(() -> message, false);
					return 1;
				}))
				.then(Commands.literal("reload").executes(context -> {
					BackupConfig config = BackupConfig.load();
					manager.setConfig(config);
					context.getSource().sendSuccess(() -> Component.literal(
							"配置已重新加载，每 " + config.intervalMinutes + " 分钟备份一次").withStyle(ChatFormatting.GREEN), true);
					return 1;
				}))
				.then(Commands.literal("on").executes(context -> setEnabled(context.getSource(), manager, true)))
				.then(Commands.literal("off").executes(context -> setEnabled(context.getSource(), manager, false)))
				.then(Commands.literal("list").executes(context -> {
					List<Path> list = restore.listBackups(context.getSource().getServer());
					if (list.isEmpty()) {
						context.getSource().sendSuccess(() -> Component.literal("还没有任何备份").withStyle(ChatFormatting.GRAY), false);
						return 0;
					}
					MutableComponent message = Component.literal("共 " + list.size() + " 个备份（从新到旧）：")
							.withStyle(ChatFormatting.GOLD);
					for (Path path : list) {
						message.append(Component.literal("\n" + path.getFileName()).withStyle(ChatFormatting.WHITE))
								.append(Component.literal("  " + sizeOf(path)).withStyle(ChatFormatting.GRAY));
					}
					context.getSource().sendSuccess(() -> message, false);
					return list.size();
				}))
				.then(Commands.literal("restore")
						.then(Commands.argument("name", BACKUP_NAME)
								// Tab 补全：列出备份文件名
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(
										restore.listBackups(context.getSource().getServer()).stream()
												.map(path -> path.getFileName().toString()), builder))
								.executes(context -> restore.request(context.getSource(),
										StringArgumentType.getString(context, "name")))))
				.then(Commands.literal("confirm").executes(context -> restore.confirm(context.getSource()))));
	}

	private static int setEnabled(CommandSourceStack source, BackupManager manager, boolean enabled) {
		if (manager.isEnabled() == enabled) {
			source.sendFailure(Component.literal(enabled ? "自动备份已经是开启状态" : "自动备份已经是停止状态"));
			return 0;
		}
		manager.setEnabled(enabled);
		String message = enabled
				? "自动备份已开启，下次备份在 " + formatDuration(manager.millisUntilNext()) + "后"
				: "自动备份已停止，/backup now 仍然可以手动备份";
		source.sendSuccess(() -> Component.literal(message)
				.withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
		return 1;
	}

	private static String sizeOf(Path path) {
		try {
			return BackupFiles.formatSize(Files.size(path));
		} catch (IOException e) {
			return "?";
		}
	}

	private static String formatDuration(long millis) {
		long totalSeconds = millis / 1000;
		return (totalSeconds / 60) + " 分 " + (totalSeconds % 60) + " 秒";
	}
}
