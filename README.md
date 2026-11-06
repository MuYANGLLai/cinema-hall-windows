# cinema-hall · Windows 端源码

两个人（以及最多六个人）同步看一部本地影片的影厅程序 —— **Windows 桌面端的对应源码**。

本仓库是分发包 `cinema-hall-windows-<版本>.zip` 的**对应源码**。包里的 `SOURCE.txt` 指向的就是这里。

## 这个仓库包含什么、不包含什么

| | |
|---|---|
| ✅ 包含 | `desktopApp/`（窗口、影厅页、播放器、自检入口）、`desktop/{rtc,media,library}`（WebRTC 链路、libVLC 播放、片库扫描）、`core/{session,network,protocol,model,rtc,media,library}`（协议、信令、同步引擎、权限判定） |
| ❌ 不包含 | Android 客户端（`app/`、`feature/`、`core/*-android`）与 Node.js 信令服务（`server/`）。它们是**另外两个可独立分发的作品**，构建本包不需要它们 |

全部模块都是纯 Kotlin/JVM，**不需要 Android SDK**。

## 构建

要求：JDK 17 或更高（本仓库不钉 `org.gradle.java.home`，用你的 `JAVA_HOME`）。

```bash
# 只编译
./gradlew :desktopApp:compileKotlin

# 跑单元测试
./gradlew test

# 出可分发的目录（jpackage app-image）
./gradlew :desktopApp:packageRelease
```

`packageRelease` 产出的目录在 `desktopApp/build/dist/cinema-hall/`。
出完整 zip 还需要一份 VLC 运行时（见下），仓库里**不含**它。

## 运行前提

- **需要 VLC 3.0.21**。程序通过 vlcj 在进程内调用 libVLC；官方分发包里随包带一份 `vlc/` 目录，本仓库不包含它（体积与源码义务都不适合放源码仓库）。
  自己构建时，把 VLC 的 Windows 运行时放到程序目录下的 `vlc/`，或安装系统级 VLC。
- 需要一个信令服务器。程序默认连作者自建的那一台，也可以在设置里改成你自己的（协议见 `core/protocol` 与 `core/network`）。

## 许可

本程序整体按 **GNU General Public License v3.0** 分发，全文见 [`LICENSE`](LICENSE)。

之所以是 GPLv3：播放层用了 `vlcj`（GPL-3.0），并且随包分发 VLC（GPL-2.0-or-later）。

第三方组件、各自的版本与许可证、以及它们在包里的分发形态，见
[`docs/第三方组件清单.md`](docs/第三方组件清单.md)；随包合规文件在 `desktopApp/compliance/`。

## 相关文档

- [`docs/Windows使用说明.md`](docs/Windows使用说明.md) —— 给用户的操作说明
- [`docs/第三方组件清单.md`](docs/第三方组件清单.md) —— 第三方组件与许可证
- [`docs/Windows-W7-开源前核对.md`](docs/Windows-W7-开源前核对.md) —— `vlc/` 371 个二进制与 2325 个源文件的逐文件许可核对（证据表在 `docs/evidence/`）
