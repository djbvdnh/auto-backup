# Auto Backup

一个 Minecraft **Fabric 26.3** 服务端 mod，定时自动备份服务器存档。

## 功能

- 按设定的间隔自动把存档打包成 zip（默认每 30 分钟）
- 备份前自动保存世界，备份期间暂停自动保存，保证备份完整
- 在后台线程压缩，不卡服务器
- 自动删除最旧的备份，只保留最近 N 个（默认 10 个）
- 上次备份后没有玩家在线时跳过备份，避免空服产生大量重复备份
- 关服时如果正在备份，会等备份完成再关闭
- 游戏内一条命令回档，回档前自动备份当前世界，可以随时撤销

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
| `/backup list` | 列出所有备份（从新到旧）和大小 |
| `/backup restore <备份名>` | 选择要回档的备份，支持 Tab 补全 |
| `/backup confirm` | 30 秒内确认回档，只有发起者能确认 |

## 回档

回档只能在**专用服务器**上使用，并且需要启动脚本配合重启。

1. `/backup restore <备份名>`，再在 30 秒内输入 `/backup confirm`
2. 全服 10 秒倒计时，然后踢出所有玩家、关闭服务器
3. 服务器完全关闭后，先把当前世界备份为 `世界名_时间_before-restore.zip`（回档后后悔了，可以再回档到它）
4. 把所选备份解压到临时文件夹，检查无误后替换世界；出错时原世界保持不变
5. 在服务器根目录创建 `.auto-backup-restart`，然后退出

### 启动脚本

启动脚本发现 `.auto-backup-restart` 后，会删除它并重启服务器。正常 `/stop` 不会创建这个文件，所以不会被误重启。

下面两个脚本复制即用：放在服务器根目录，改好开头的配置，以后都用它来启动服务器。

| 服务器怎么关闭的 | 脚本的反应 |
|---|---|
| 回档 | 3 秒后自动重启 |
| 正常 `/stop` | 退出 |
| 崩溃 | `AUTO_RESTART` 为 `true` 时 10 秒后重启，否则退出 |

#### Linux / macOS：`start.sh`

```bash
#!/usr/bin/env bash

# ===== 按你的服务器修改 =====
JAVA="java"                      # Java 25 的路径，已加入 PATH 就不用改
JAR="fabric-server-launch.jar"   # 服务端 jar 的文件名
MEMORY="4G"                      # 最大内存
AUTO_RESTART=false               # 崩溃后是否自动重启
# ============================

cd "$(dirname "$0")" || exit 1

RESTORE_FLAG=".auto-backup-restart"
rm -f "$RESTORE_FLAG"

while true; do
    "$JAVA" -Xmx"$MEMORY" -jar "$JAR" nogui
    EXIT_CODE=$?

    if [ -f "$RESTORE_FLAG" ]; then
        rm -f "$RESTORE_FLAG"
        echo "[回档] 回档完成，3 秒后重启服务器..."
        sleep 3 || break
        continue
    fi

    if [ "$EXIT_CODE" -eq 0 ]; then
        echo "服务器已关闭。"
        break
    fi

    if [ "$AUTO_RESTART" = true ]; then
        echo "服务器异常退出（代码 $EXIT_CODE），10 秒后重启，按 Ctrl+C 取消..."
        sleep 10 || break
    else
        echo "服务器异常退出（代码 $EXIT_CODE）。"
        break
    fi
done
```

第一次使用前执行 `chmod +x start.sh`，之后用 `./start.sh` 启动。

#### Windows：`start.bat`

```bat
@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem ===== 按你的服务器修改 =====
rem Java 25 的路径，已加入 PATH 就不用改
set "JAVA=java"
rem 服务端 jar 的文件名
set "JAR=fabric-server-launch.jar"
rem 最大内存
set "MEMORY=4G"
rem 崩溃后是否自动重启
set "AUTO_RESTART=false"
rem ============================

set "RESTORE_FLAG=.auto-backup-restart"
if exist "%RESTORE_FLAG%" del /f /q "%RESTORE_FLAG%"

:loop
"%JAVA%" -Xmx%MEMORY% -jar "%JAR%" nogui
set "EXIT_CODE=%ERRORLEVEL%"

if exist "%RESTORE_FLAG%" (
    del /f /q "%RESTORE_FLAG%"
    echo [回档] 回档完成，3 秒后重启服务器...
    timeout /t 3 /nobreak >nul
    goto loop
)

if "%EXIT_CODE%"=="0" (
    echo 服务器已关闭。
    goto end
)

if /i "%AUTO_RESTART%"=="true" (
    echo 服务器异常退出（代码 %EXIT_CODE%），10 秒后重启，按 Ctrl+C 取消...
    timeout /t 10 >nul
    goto loop
)

echo 服务器异常退出（代码 %EXIT_CODE%）。

:end
pause
```

双击运行即可。用记事本保存时，编码选 **UTF-8**，不要选“带 BOM 的 UTF-8”，否则脚本会报错或者中文显示成乱码。

已经有自己的启动脚本？只要照着上面 `RESTORE_FLAG` 那一段，在 Java 退出后检查 `.auto-backup-restart` 就行。

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
