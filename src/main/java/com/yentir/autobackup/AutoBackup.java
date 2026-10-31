package com.yentir.autobackup;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoBackup implements ModInitializer {
	public static final String MOD_ID = "auto-backup";

	// 用 mod id 当日志名字，控制台里就能看出是哪个 mod 打印的
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 服务器加载 mod 时最先执行这里。这里只“注册事件”，真正的工作在事件触发时才做
		BackupManager manager = new BackupManager(BackupConfig.load());

		// 服务器启动完成：开始计时，启动定时器
		ServerLifecycleEvents.SERVER_STARTED.register(manager::onServerStarted);

		// 服务器关闭前：等待正在进行的备份完成
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> manager.onServerStopping());

		// 注册 /backup 命令
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
				BackupCommand.register(dispatcher, manager));

		LOGGER.info("自动备份已加载");
	}
}
