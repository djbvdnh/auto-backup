package com.yentir.autobackup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置文件：config/auto-backup.json
 * Gson 会把这个类的字段和 JSON 里的键一一对应。
 */
public class BackupConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// 每隔多少分钟自动备份一次
	public int intervalMinutes = 30;
	// 最多保留多少个备份，超出后删除最旧的；0 表示不限制
	public int maxBackups = 10;
	// 备份存放目录，相对于服务器根目录
	public String backupDir = "backups";
	// 备份开始/结束时是否在聊天栏通知在线玩家
	public boolean announce = true;

	public static BackupConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(AutoBackup.MOD_ID + ".json");
		BackupConfig config = new BackupConfig();

		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				BackupConfig loaded = GSON.fromJson(reader, BackupConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException | JsonParseException e) {
				// 配置写错了就用默认值，但不覆盖原文件，方便玩家自己修正
				AutoBackup.LOGGER.error("读取配置文件失败，本次使用默认配置：{}", file, e);
				return config;
			}
		}

		// 防止填了不合理的数值
		config.intervalMinutes = Math.max(1, config.intervalMinutes);
		config.maxBackups = Math.max(0, config.maxBackups);
		if (config.backupDir == null || config.backupDir.isBlank()) {
			config.backupDir = "backups";
		}

		// 写回文件：第一次运行时会生成默认配置，以后新增的配置项也会自动补上
		config.save(file);
		return config;
	}

	private void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this));
		} catch (IOException e) {
			AutoBackup.LOGGER.error("保存配置文件失败：{}", file, e);
		}
	}
}
