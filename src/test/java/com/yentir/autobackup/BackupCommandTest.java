package com.yentir.autobackup;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackupCommandTest {
	// 备份文件名以世界名开头，世界名可以是中文或带空格
	@Test
	void backupNameArgument_acceptsNonAsciiAndSpaces() throws CommandSyntaxException {
		String name = "我的 世界_2026-11-01_10-00-00.zip";
		assertEquals(name, BackupCommand.BACKUP_NAME.parse(new StringReader(name)));
	}
}
