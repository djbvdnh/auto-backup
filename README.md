# Auto Backup

一个 Minecraft **Fabric 26.3** 服务端 mod，定时自动备份服务器存档。

## 功能

- 按设定的间隔自动把存档打包成 zip（默认每 30 分钟）
- 备份前自动保存世界，备份期间暂停自动保存，保证备份完整
- 在后台线程压缩，不卡服务器
- 自动删除最旧的备份，只保留最近 N 个（默认 10 个）
- 上次备份后没有玩家在线时跳过备份，避免空服产生大量重复备份
- 关服时如果正在备份，会等备份完成再关闭

## 安装

1. 服务器安装 [Fabric Loader](https://fabricmc.net/use/server/) 和 [Fabric API](https://modrinth.com/mod/fabric-api)
2. 把 `auto-backup-x.x.x.jar` 放进服务器的 `mods` 文件夹
3. 启动服务器

纯服务端 mod，玩家客户端不需要安装。

## 命令

需要 OP 4 级权限或在控制台执行。

| 命令 | 说明 |
|---|---|
| `/backup now` | 立即备份 |
| `/backup status` | 查看下次备份时间和上次备份结果 |
| `/backup reload` | 重新读取配置文件 |

## 配置

第一次启动后生成 `config/auto-backup.json`：

```json
{
  "intervalMinutes": 30,
  "maxBackups": 10,
  "backupDir": "backups",
  "announce": true
}
```

| 配置项 | 说明 |
|---|---|
| `intervalMinutes` | 每隔多少分钟备份一次（最小 1） |
| `maxBackups` | 最多保留多少个备份，`0` 表示不限制 |
| `backupDir` | 备份存放目录，相对于服务器根目录 |
| `announce` | 是否在聊天栏通知在线玩家 |

修改后执行 `/backup reload` 即可生效。

## 从源码构建

需要 JDK 25。

```bash
./gradlew build
```

生成的 jar 在 `build/libs/`。

## 许可证

[MIT](LICENSE)
