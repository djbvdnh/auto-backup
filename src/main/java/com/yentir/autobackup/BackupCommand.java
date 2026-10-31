package com.yentir.autobackup;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /backup now     立即备份
 * /backup status  查看下次备份时间和上次结果
 * /backup reload  重新读取配置文件
 */
public final class BackupCommand {
	private BackupCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, BackupManager manager) {
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
				})));
	}

	private static String formatDuration(long millis) {
		long totalSeconds = millis / 1000;
		return (totalSeconds / 60) + " 分 " + (totalSeconds % 60) + " 秒";
	}
}
