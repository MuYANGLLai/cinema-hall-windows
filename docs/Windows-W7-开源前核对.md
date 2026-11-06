# Windows W7 · 开源前核对

本文件回答两件事：

- **A 部分**：`vlc/` 里每个二进制的许可证是什么 —— 路径 A（接受 GPLv3、Windows 端整仓开源）能不能成立。**逐文件，不抽样。**
- **B 部分**：开源前，Win 客户端打包产物里哪些文件**不能公开**，哪些必须补进去。

> 前置结论（已拍板）：Q1 = **A**，分发是确定事实（给朋友装 / 放到网上）。所以本文件不再讨论 B/C 路径的代价，只解决「A 到底成不成立」和「开源前要清理什么」。

---

## 0. 结论

**路径 A 成立** —— 针对我们实际分发的 **Windows 包**。

1. 随包 **371 个 PE**：**0 个** `v2-only`。其中 332 个自称 `LGPL-2.1-or-later`、34 个自称 `GPL-2.0-or-later`、5 个二进制内没有授权自述（已逐个由源码定案，结论仍落在 or-later 一侧）。**全部 or-later ⇒ 与 GPLv3 兼容**，整包可按 GPLv3 分发。
2. 源码层（2325 个源文件）：**3 个**文件写的是 v2-only 式措辞、**2 个**没写版本号，但这 5 个**都不编译进 Windows 包**（证据：对应插件 DLL 不在包里）。所以「分发的这个包」里没有任何 v2-only 代码。
3. **一处必须处理的上游缺口**：`axvlc.dll` / `npvlc.dll` 的对应源码**不在 VLC 的任何源码包里**（发布 tar.xz 没有、git tag 也没有，它们来自另一个项目 `npapi-vlc`）。处置：**从发布包剔除这两个 DLL** —— 本来也用不到（IE/Firefox 的嵌入插件），顺带减 2.39 MB。
4. **B 部分修掉一个真缺陷**：`stageRelease` 会把开发机的 `data/logs/app.log` 搬进 `dist/cinema-hall/`，里面有本机绝对路径、房号、本轮自检参数。`releaseZip` 的 `exclude` 只挡住了 zip，中间产物已经漏了。已在 `desktopApp/build.gradle.kts:86` 补 `exclude("data/**")`，复跑验证通过。

> ⚠️ **边界**：以上判决只覆盖 **Windows 包**。将来若出 Linux / macOS 构建，A **必须重判** —— 见 A5：`modules/access/v4l2/linux/v4l2-common.h`（Linux 专有）与 `modules/control/unimotion.c`（macOS 专有）的措辞就是 v2-only。

---

## A. `vlc/` 逐文件许可核对

### A1. 判据：为什么不是看 `COPYING.txt`

`vlc/COPYING.txt`（18431 B）是 **GPLv2 的许可证全文**，它只说明「这份 VLC 按 GPLv2 的条款授权」，**不说明每个二进制实际按哪个版本**。真正有约束力的是每个二进制/源文件自己那句授权声明（`either version N of the License, or (at your option) any later version`）。所以判据是：

| 轮次 | 对象 | 数量 | 抓什么 |
|---|---|---|---|
| 1 | `vlc/` 下全部 PE 文件 | **371**（365 个插件 DLL + `libvlc.dll`/`libvlccore.dll`/`axvlc.dll`/`npvlc.dll` + `vlc.exe`/`vlc-cache-gen.exe`） | 二进制字符串里的 `Lesser/General Public License, version …` 窗口 |
| 2 | VLC 3.0.21 源码树全部 `.c/.h/.cpp/.hpp/.cc/.m/.mm` | **2325** | 文件头 45 行内的授权声明句 |

**第 2 轮比第 1 轮强，不能省**：注释不进二进制 —— 一个文件若只在源码头里写了 `version 2`（v2-only），第 1 轮**根本看不见**。两轮结论一致才算数。

逐文件结果落成可复核的表（无抽样，全量）：

- `docs/evidence/vlc-3.0.21-pe-license.tsv` —— 371 行，`相对路径 ⇥ 字节数 ⇥ 判定`
- `docs/evidence/vlc-3.0.21-src-license.tsv` —— 2325 行，同格式

源码来源（两份都实际取到并解包过，不是「查到的说法」）：

| 来源 | 体积 / 条目 | 用途 |
|---|---|---|
| `download.videolan.org/pub/videolan/vlc/3.0.21/vlc-3.0.21.tar.xz` | 25.6 MB / 5019 条目 | 第 2 轮扫描的主依据 |
| `codeload.github.com/videolan/vlc/tar.gz/refs/tags/3.0.21` | 32.5 MB / 4895 条目 | 交叉验证「发布包少了东西」（A7） |

### A2. 二进制层：371 个 PE 的结果

| 判定 | 个数 | 对路径 A 的含义 |
|---|---|---|
| `LGPL-2.1-or-later` | 332 | 可链接进专有软件；也可整体升到 GPLv3 |
| `GPL-2.0-or-later` | 34 | 不能专有链接；因是 or-later，可升到 GPLv3 |
| `GPL-2.0-only` | **0** | 若有任何一个，整包只能停在 GPLv2 ⇒ 路径 A 直接否掉 |
| 二进制内无授权自述 | 5 | 见 A3，逐个由源码定案 |

**34 个 `GPL-2.0-or-later` 全量清单**（这些是本包里"最严"的一批，路径 A 下随整包一起按 GPLv3 发布）：

| # | 文件 | # | 文件 |
|---|---|---|---|
| 1 | `plugins\access\libaccess_realrtsp_plugin.dll` | 18 | `plugins\demux\libmpc_plugin.dll` |
| 2 | `plugins\access\libdshow_plugin.dll` | 19 | `plugins\demux\libreal_plugin.dll` |
| 3 | `plugins\audio_filter\libdolby_surround_decoder_plugin.dll` | 20 | `plugins\gui\libqt_plugin.dll` |
| 4 | `plugins\audio_filter\libheadphone_channel_mixer_plugin.dll` | 21 | `plugins\gui\libskins2_plugin.dll` |
| 5 | `plugins\audio_filter\libmono_plugin.dll` | 22 | `plugins\logger\libfile_logger_plugin.dll` |
| 6 | `plugins\codec\libt140_plugin.dll` | 23 | `plugins\lua\liblua_plugin.dll` |
| 7 | `plugins\codec\libx264_plugin.dll` | 24 | `plugins\misc\libaudioscrobbler_plugin.dll` |
| 8 | `plugins\codec\libx26410b_plugin.dll` | 25 | `plugins\misc\libexport_plugin.dll` |
| 9 | `plugins\codec\libx265_plugin.dll` | 26 | `plugins\misc\liblogger_plugin.dll` |
| 10 | `plugins\control\libdummy_plugin.dll` | 27 | `plugins\misc\libstats_plugin.dll` |
| 11 | `plugins\control\libgestures_plugin.dll` | 28 | `plugins\misc\libvod_rtsp_plugin.dll` |
| 12 | `plugins\control\libhotkeys_plugin.dll` | 29 | `plugins\services_discovery\libmediadirs_plugin.dll` |
| 13 | `plugins\control\libnetsync_plugin.dll` | 30 | `plugins\services_discovery\libpodcast_plugin.dll` |
| 14 | `plugins\control\libntservice_plugin.dll` | 31 | `plugins\services_discovery\libsap_plugin.dll` |
| 15 | `plugins\control\liboldrc_plugin.dll` | 32 | `plugins\stream_out\libstream_out_cycle_plugin.dll` |
| 16 | `plugins\control\libwin_hotkeys_plugin.dll` | 33 | `plugins\stream_out\libstream_out_rtp_plugin.dll` |
| 17 | `plugins\control\libwin_msg_plugin.dll` | 34 | `plugins\video_filter\librotate_plugin.dll` |

其余 332 个（含 `libvlccore.dll`）逐行在证据表里，判定均为 `LGPL-2.1-or-later`。

### A3. 五个「二进制内无自述」文件的定案

二进制里不写授权是构建配置问题，不代表没授权 —— 但**必须由源码定案，不能推测**。逐个查到对应源文件并引用原文：

| 文件 | 对应源码（3.0.21） | 头部原文（节选） | 判定 |
|---|---|---|---|
| `libvlc.dll` | `src/libvlc.c` | `GNU Lesser General Public License … either version 2.1 of the License, or (at your option) any later version` | **LGPL-2.1-or-later** |
| `vlc.exe` | `bin/winvlc.c` + `bin/vlc.c`（Windows 入口 + 主程序） | `GNU General Public License … either version 2 of the License, or (at your option) any later version` | **GPL-2.0-or-later** |
| `vlc-cache-gen.exe` | `bin/cachegen.c` | 同上（GPL v2 or later） | **GPL-2.0-or-later** |
| `axvlc.dll` | **不在 VLC 源码树**：`npapi-vlc` 项目 `activex/plugin.cpp` | `GNU General Public License … either version 2 of the License, or (at your option) any later version` | **GPL-2.0-or-later**（见 A7） |
| `npvlc.dll` | **不在 VLC 源码树**：`npapi-vlc` 项目（`common/win32_fullscreen.cpp` 为 LGPL-2.1-or-later，`activex/*` 为 GPL-2.0-or-later） | 同上两族 | **or-later**（见 A7） |

补充事实（从 PE 版本资源读的，用来确认"这是哪一版"）：

| 文件 | FileVersion | LegalCopyright |
|---|---|---|
| `libvlc.dll` / `vlc.exe` / `vlc-cache-gen.exe` | 3.0.21 | Copyright © 1996-2024 VideoLAN and VLC Authors |
| `axvlc.dll` / `npvlc.dll` | **3.0.3** | Copyright © 2002-2019 VideoLAN and VLC Authors |

`axvlc`/`npvlc` 的版本号停在 **3.0.3**，没有随 3.0.21 重编 —— 这也印证了它们不是这个源码树的产物。

### A4. 源码层：2325 个文件的结果

脚本按「头部那句声明的字面形态」分 8 类，最后人工把前 5 类并入 or-later：

| 脚本判定 | 个数 | 人工定案 |
|---|---|---|
| `LGPL-2.1-or-later` | 1518 | or-later |
| `GPL-2-or-later` | 671 | or-later |
| `LGPL-2-or-later` | 16 | or-later（含 `modules/packetizer/dirac.c`：原文 `version 2 of the License (at your option) any later version`，漏了个 `or`，语义仍是 or-later） |
| `GPL-3-or-later` | 2 | or-later（与 GPLv3 同向，无冲突） |
| `GPL-2-NO-CLAUSE` | 8 | or-later —— 原文漏了 `either`：`GNU General Public License … ; version 2 of the license, or (at your option) any later version`（全是 `test/libvlc/*`，测试代码，不进包） |
| `LGPL-UNVERSIONED` | 2 | **v2-only 式措辞**（`modules/control/unimotion.c/h`，A5） |
| `GPL-UNVERSIONED` | 3 | 1 个 **v2-only 式措辞** + 2 个无版本号（A5） |
| 无 GPL 头 | 105 | 宽松许可（A6） |

**最终：or-later 2215 个 · v2-only 式措辞 3 个 · 无版本号 2 个 · 无 GPL 头 105 个 = 2325 个**（1518+671+16+2+8=2215；2215+3+2+105=2325 ✓）。

> 口径提示：这里的「or-later」是**源码文件自己的授权声明**，不是我们对整个 VLC 的再许可。VLC 作为整体由 `COPYING.txt`（GPLv2）约束；因为每个文件都给了 or-later 选择权，我们才可以选 GPLv3 作为出站许可。

### A5. 那 5 个「不是 or-later」的文件，为什么不影响判决

关键不是「它写了什么」，而是「它有没有被编译进我们分发的包」。逐个验证：

| 文件 | 原文 | 性质 | 是否进 Windows 包 | 判据 |
|---|---|---|---|---|
| `modules\access\v4l2\linux\v4l2-common.h` | `GNU General Public License version 2 as published by the Free Software Foundation.` | **GPL-2.0-only**（Linux 内核派生头） | **否** | V4L2 是 Linux ioctl 接口；`vlc/plugins/` 下**没有** `libv4l2_plugin.dll` |
| `modules\control\unimotion.c` | `GNU Lesser General Public License version 2.1 as published by the Free Software Foundation.` | **LGPL-2.1-only**（Apple Sudden Motion Sensor） | **否** | 只有 macOS 用 IOKit；`vlc/plugins/` 下**没有** `libunimotion_plugin.dll` |
| `modules\control\unimotion.h` | 同上 | 同上 | **否** | 同上 |
| `modules\codec\wmafixed\wmafixed.c` | `All files in this archive are subject to the GNU General Public License. See the file COPYING …` | 无版本号（Rockbox 派生） | **否** | `vlc/plugins/codec/` 下**没有** `libwma_fixed_plugin.dll`（WMA 走 `libavcodec_plugin.dll`） |
| `modules\codec\wmafixed\wmafixed.h` | 同上 | 同上 | **否** | 同上 |

**验证方法**：直接枚举 `vlc/plugins/**/*.dll` 全部 365 个文件名，按 `v4l|unimotion|motion|wma` 匹配 —— 只命中 `libmotionblur_plugin.dll`、`libmotiondetect_plugin.dll`（无关的视频滤镜）。另外 `modules/codec/Makefile.am:92-103` 显示 `libwma_fixed_plugin` 由 `$(LTLIBwma_fixed)` 条件控制，本包的构建配置没打开它。

> 所以：**Windows 包里 0 个 v2-only**。但这三处是真实存在的「一票否决」材料 —— 一旦做 Linux/macOS 构建，或一旦打开 `wma_fixed` 插件，A 的判决就要重来。这句话要写进仓库的 `AGENTS.md` / 构建说明，别只放在这里。

### A6. 105 个「无 GPL 头」的源文件是什么

| 类别 | 个数 | 典型 |
|---|---|---|
| 宽松许可（BSD 式） | 32 | `compat/lfind.c`（Todd C. Miller，`Permission to use, copy, modify, and distribute …`） |
| 宽松许可（MIT 式） | 12 | — |
| 公有领域 | 9 | — |
| WTFPL 等 | 余下 | `doc/libvlc/*`（示例程序，`Under WTFPL`） |
| 空文件 / 生成物 | 少量 | `compat/dummy.c`（`Intentionally left empty`）、`contrib/src/gpg-error/lock-obj-pub.posix.h`（生成物） |

**没有一个 copyleft 许可**（无 MPL / EPL / CDDL / Apache-1.0 / OpenSSL）。宽松许可与 GPLv3 **兼容**，不构成阻塞。`doc/` 下的示例程序本来也不进包。

### A7. 上游缺口：`axvlc.dll` / `npvlc.dll` 的源码不在 VLC 任何源码包里

这条是本次核对唯一的硬发现，必须单列：

1. `vlc-3.0.21.tar.xz`（5019 个条目）顶层只有 `autotools bin compat contrib doc extras include lib m4 modules po share src test` —— **没有 `activex/`，没有 `npapi/`**。
2. git tag `3.0.21`（4895 个条目）同样没有；全树只有 1 处相关文件：`extras/package/win32/msi/axvlc.wxs`（注册表安装脚本，不是源码）。
3. `extras/package/win32/package.mak:88-89` 说明这两个 DLL 是**从另一个项目拷进来的**：

```make
cp "$(top_builddir)/npapi-vlc/installed/lib/axvlc.dll" "$(win32_destdir)/"
cp "$(top_builddir)/npapi-vlc/installed/lib/npvlc.dll" "$(win32_destdir)/"
```

4. 上游项目是 `code.videolan.org/videolan/npapi-vlc`（"Plugin-specific code to run VLC in web browsers, currently NPAPI and ActiveX"），`COPYING` 是 GPLv2 全文，源码文件头是 GPL-2.0-or-later / LGPL-2.1-or-later 混合。

**这意味着什么**：如果我们把这两个 DLL 随包分发，「提供对应源码」这件事**没法用 VLC 的源码包完成** —— 得额外归档 `npapi-vlc` 的源码，而它的构建还依赖 Mozilla/ActiveX 的旧 SDK。

**处置（已写进 B3）**：**从发布包剔除**。理由两条，任一条都足够：

- 我们的播放是 vlcj 走 JNA 在**进程内**调 `libvlc.dll`，从不加载浏览器插件；IE 已退役，NPAPI 早已被所有现代浏览器移除。
- 留着它只会给合规材料增加一个「必须额外归档另一份源码」的负担。

剔除后，5 个「无自述」文件降到 3 个，且这 3 个的定案都能用 VLC 自己的源码包覆盖。

### A8. 判决与三个前置条件

**路径 A 成立**（Windows 包），成立的前提是这三件事都要做到：

1. **整仓开源**：`cinema-hall` 全部源码（含 `desktop/**`、`desktopApp/**`）按 GPLv3 发布；因为我们用 Apache-2.0 的 webrtc-java + GPLv2-or-later 的 VLC，出站许可只有 GPLv3 这一条路（Apache-2.0 与 GPLv2 不兼容）。
2. **合规材料齐**：`COPYING`/`LICENSE` 原件保留（`vlc/COPYING.txt` **不许删**）+ `THIRD-PARTY-NOTICES`（82 个依赖按许可证分组）+ webrtc-java 的 Apache-2.0 与 WebRTC BSD-3 声明（jar 里没有，得自己写）+ FFmpeg 的 LGPL 说明（Q3 未定，见 §7）。
3. **不许出现 v2-only**：见 A5，Windows 包当前满足；**任何新增平台/新增插件都要重跑本文件的 A1 扫描**。

---

## B. 开源前清理清单（Win 打包产物）

### B0. 打包链路（先说清楚"产物"指什么）

```
:desktopApp:createDistributable   → build/compose/binaries/main/app/cinema-hall/   （app/ data/ runtime/ exe ico）
:desktopApp:stageRelease          → build/dist/cinema-hall/                        （+ vlc/ + 使用说明.md）
:desktopApp:releaseZip            → build/dist/cinema-hall-windows-0.1.0.zip        （单顶层目录 cinema-hall/）
```

`vlc/` 来自 `desktopApp/build.gradle.kts:76` 的 `cinema.vlcDir`（默认 `D:/AI/minimax/_spike/third_party/vlc-3.0.21`）。它**不在 git 里**（180.6 MB），只在这条打包链上被拷进产物。

### B1. 绝对不能公开的文件

| 载体 | 路径 | 里面有什么（本机实测） | 处置 |
|---|---|---|---|
| **开发机数据** | `desktopApp/build/compose/binaries/main/app/cinema-hall/data/` | `logs/app.log` 90 行：含 `D:\AI\minimax\cinema-hall-L3\desktopApp\build\...` 绝对路径、`roomId=ABCDEF`、`sessionTest=a`、`signal=ws://127.0.0.1:8080`、`rtcPort=39321` | 已在打包链排除（B2）；**发布前再确认一次** |
| 同上 | `desktopApp/build/dist/cinema-hall/data/` | 同上（`Sync` 搬过来的） | 同上 |
| 仓库工作区数据 | `desktopApp/data/`（`library.json` / `settings.json` / `logs/` / 单实例锁） | 本机影片库路径、信令地址与昵称、日志 | 已在 `.gitignore:46` 忽略；**不要为了"让别人能跑"把它删掉规则** |
| 本机环境 | `local.properties` | `sdk.dir=C:/Users/LLai/AppData/Local/Android/Sdk` | 已在 `.gitignore:11` 忽略 |
| 构建缓存 | `desktopApp/build/**`（除要发布的 zip） | Gradle 缓存里有绝对路径；`dist/cinema-hall/` 含 B2 所说的日志 | 已在 `.gitignore:3` 忽略；**发布只发 zip，不发 build 目录** |
| JVM 崩溃日志 | `hs_err_pid*.log` / `replay_pid*.log` / `*.hprof` | 带完整命令行、classpath（含绝对路径） | 当前仓库里**没有**（已查，build 外为 0）；`.gitignore` 里也**没有**这两条规则 → 建议补 |
| 第三方二进制 | `D:\AI\minimax\_spike\third_party\vlc-3.0.21`（180.6 MB） | VLC 发行包 | **不要为了"让别人能打包"塞进仓库**；用 `cinema.vlcDir` 指过去，并在使用说明里写清依赖 |
| 签名材料 | `*.keystore` / `*.jks` / `*.pepk` / `server/.env*` | 密钥 | `.gitignore:14-16 / 30-33` 已覆盖（这一条是既有规则，本次只复核，未改） |

`config.example.env` 是模板，**不是**秘密，可以公开。

### B2. 已修：`stageRelease` 会把开发机日志搬进产物

**缺陷**（本机复现）：`stageRelease` 是 `Sync`，`from(appImageRoot)` 没排 `data/`，所以只要在 `compose/binaries/.../cinema-hall/` 里跑过一次 exe，`data/logs/app.log` 就会被同步到 `dist/cinema-hall/data/logs/app.log`。`releaseZip` 的 `exclude("**/data/**")` 只挡 zip —— 直接压 `dist/cinema-hall/`、或者手工把那个目录拷给别人，日志就跟着走了。

**修复**（`desktopApp/build.gradle.kts:86`）：

```kotlin
from(appImageRoot) {
    // 在源目录里跑过一次 exe 之后，`data/logs/app.log` 就会留在这里，而 `Sync`
    // 会把它原样搬进 `dist/cinema-hall/` …排除必须写在**这一步**。
    exclude("data/**")
}
```

**复跑验证**：

| 时点 | `dist/cinema-hall/` 顶层 | `data/` |
|---|---|---|
| 修复前 | `app/ data/ runtime/ vlc/ 使用说明.md exe ico` | **存在**，`data/logs/app.log` 8662 B |
| 修复后 | `app/ runtime/ vlc/ 使用说明.md exe ico` | **不存在** ✅ |

### B3. 建议从包里剔掉的（体积 + 合规，实测体积）

现包 `vlc/` = **591 个文件 / 180.6 MB**。逐项实测：

| 项 | 体积 | 处置 | 理由 |
|---|---|---|---|
| `locale/`（104 个 `.mo`，全语言） | **39.94 MB** | **只留 `zh_CN` + `zh_TW` + `en_GB`**（0.96 MB）⇒ 省 **38.98 MB** | 界面是中文；libVLC 的报错文案按系统语言取，留三份够 |
| `plugins/`（365 个 DLL） | 132.37 MB | 保留 | 解码/输出能力全在这 |
| `axvlc.dll` + `npvlc.dll` | **2.39 MB** | ✅ **已删**（`stageRelease` 里 `exclude("axvlc.dll","npvlc.dll")`，见 B6） | IE/Firefox 嵌入插件，我们用不到；且对应源码不在 VLC 源码包（A7） |
| `msi/`（6 个文件，含 3 个 `.wxs` + 一个注册 `axvlc.dll` 的 CLSID 脚本） | 0.23 MB | ✅ **已删**（`exclude("msi/**")`；留着会让「包里有 axvlc 字样」看着像没剔干净） | WiX 安装脚本，zip 交付用不上 |
| `vlc.exe` | 0.95 MB | 可选删 | 我们从不启动 VLC 的 UI（进程内用 `libvlc.dll`）。删前跑一次 §8.3 截图门禁 |
| `skins/`（6 个文件） | 0.50 MB | 随 `vlc.exe` 一起删 | 只有 `vlc.exe` 的 skins2 界面用 |
| `vlc-cache-gen.exe` | 0.14 MB | **保留** | 插件缓存生成，排障时需要 |
| `lua/`（95 个文件） | 0.78 MB | 保留 | 播放列表解析（`.m3u`/`.xspf`）与元数据抓取 |
| `COPYING.txt` / `AUTHORS.txt` / `THANKS.txt` / `README.txt` | 0.05 MB | **必须保留** | 许可与署名，删了就是违规 |
| `libvlccore.dll` / `libvlc.dll` | 2.87 MB | 保留 | 本体 |
| `hrtfs/` | 0.09 MB | 保留 | 耳机环绕的头相关传输函数，体积可忽略 |

**预计**：180.6 → **139.0 MB**（保守，只删 locale 多余部分 + axvlc/npvlc + msi）；若再删 `vlc.exe`/`skins` → **137.6 MB**。

### B4. 必须补进包里的合规文件

| 文件 | 放哪 | 内容 | 源 | 状态 |
|---|---|---|---|---|
| `LICENSE` | 包根 | 我们自己的 GPLv3 全文（35149 B，gnu.org 官方文本，含 `END OF TERMS AND CONDITIONS`） | `desktopApp/compliance/LICENSE` | ✅ 已加 |
| `NOTICE-third-party.txt` | 包根 | 43116 B：组件↔许可证对照 + **Apache-2.0 / BSD-3(WebRTC) / MIT(SLF4J) / LGPL-2.1** 四份正文 | `desktopApp/compliance/NOTICE-third-party.txt` | ✅ 已加 |
| `SOURCE.txt` | 包根 | 仓库地址 + 本包提交 `54f2f4c…` + 如实写明「该提交尚未推送到公开 main」+ 各组件上游源码地址 + FFmpeg §6 说明 | `desktopApp/compliance/SOURCE.txt` | ✅ 已加 |
| `第三方组件清单.md` | 包根 | 直接取 `docs/第三方组件清单.md`（单一副本，避免漂移） | `docs/` | ✅ 已加 |
| `vlc/COPYING.txt` | `vlc/`（原位） | VLC 的 GPLv2 全文 | VLC 官方包 | ✅ 在包里，**不许删** |

> 四份文件的**源**都在 `desktopApp/compliance/`（`第三方组件清单.md` 除外，它从 `docs/` 取），由 `stageRelease` 铺到包根 —— 所以 `dist/cinema-hall/` 本身也是可直接拷人的完整包，不必依赖 zip。

### B5. 发布前逐条自检

| # | 命令 | 通过判据 |
|---|---|---|
| 1 | `git status --short` | 只有你本轮**故意**改的文件；`local.properties` / `desktopApp/data/` / `build/` 不出现 |
| 2 | `Test-Path desktopApp\build\dist\cinema-hall\data` | `False` |
| 3 | `Test-Path desktopApp\build\dist\cinema-hall\vlc\COPYING.txt` | `True` |
| 4 | 解压 zip 后 `Get-ChildItem` | 顶层**只有一个** `cinema-hall\`（exe / app / runtime / vlc 都在它里面） |
| 5 | 解压后 `Test-Path .\cinema-hall\data` | `False`（`data/` 只在首次启动时生成） |
| 6 | 解压后 `Test-Path .\cinema-hall\vlc\axvlc.dll` | `False`（B3 执行到位） |
| 7 | 解压后跑 §8.1 / §8.2 / §8.3 | 三条 `exit=0`，读数达判据 |
| 8 | 包内 `LICENSE` / `THIRD-PARTY-NOTICES.md` | 都存在且非空 |

---

### B6. 发布包自检表（2026-11-06 实跑，出包 commit `54f2f4c`）

> 任务名说明：仓库里**没有** `packageRelease` 这个任务，实际出包链路是
> `:desktopApp:createDistributable → :desktopApp:stageRelease → :desktopApp:releaseZip`。
> 下表用的是 `releaseZip`。

**产物**

| 项 | 值 |
|---|---|
| 路径 | `desktopApp/build/dist/cinema-hall-windows-0.1.0.zip` |
| 体积 | **171,957,837 B（164.0 MB）** |
| sha256 | `fa1364bbd695d60bdea4e004c4829403fc3dfbea26a5b538ac1f61ba80ddf2b1` |
| zip 条目 | 1295 |
| 顶层目录 | 只有 `cinema-hall/` |
| 出包 commit | `54f2f4cc760f2837d17c15e91efd651c611e73c8` |

**逐项自检**

| # | 检查项 | 本次实际输出 | 判据 |
|---|---|---|---|
| 1 | 四份合规文件在包根 | `第三方组件清单.md 8962 B`<br>`LICENSE 35149 B`<br>`NOTICE-third-party.txt 43116 B`<br>`SOURCE.txt 2524 B` | 四个都在 |
| 2 | `axvlc.dll` / `npvlc.dll` 不在 | `0` | 0 |
| 3 | `msi/` 不在 | `0` | 0 |
| 4 | `data/` 不在 | `0` | 0 |
| 5 | `*.log` 不在 | `0` | 0 |
| 6 | 只有一个顶层目录 | `cinema-hall` | 单个 |
| 7 | `vlc/COPYING.txt` 仍在 | `18431` 字节 | > 0 |
| 8 | 解压后能启动 | `exit=0`；日志 `libVLC 已加载：…\cinema-hall\vlc` | 退出码 0 且有该行 |
| 9 | 重跑一次仍一致 | 体积与 sha256 逐字节相同（`fa1364bb…`） | 同哈希 |

对应命令（`$z` = 上面那个 zip 的路径；**整段粘贴即可**，第 1–7 项一次跑完）：

```powershell
$z='desktopApp\build\dist\cinema-hall-windows-0.1.0.zip'
$zip=[IO.Compression.ZipFile]::OpenRead($z); $n=$zip.Entries|%{$_.FullName}
$want=@('cinema-hall/LICENSE','cinema-hall/SOURCE.txt','cinema-hall/NOTICE-third-party.txt','cinema-hall/第三方组件清单.md')
$want | % { $e=$zip.Entries|?{$_.FullName -eq $_}; '{0}  {1} B' -f $_,$e.Length }   # 1
($n|?{$_ -match 'axvlc|npvlc'}).Count                  # 2 -> 0
($n|?{$_ -match '(^|/)msi/'}).Count                    # 3 -> 0
($n|?{$_ -match '(^|/)data/'}).Count                   # 4 -> 0
($n|?{$_ -match '\.log$'}).Count                       # 5 -> 0
($n|%{($_ -split '/')[0]}|Sort-Object -Unique) -join ','   # 6 -> cinema-hall
$zip.GetEntry('cinema-hall/vlc/COPYING.txt').Length     # 7 -> 18431
$zip.Dispose()
```

第 8 项：解压 zip 到任意目录，`Start-Process .\cinema-hall\cinema-hall.exe -ArgumentList '--exit-after=10' -Wait`，
然后看 `cinema-hall\data\logs\app.log` 里有没有 `libVLC 已加载`。
第 9 项：删掉 zip 后重跑 `.\gradlew.bat :desktopApp:releaseZip`，再 `Get-FileHash` 比 sha256。

> 第 8 项是在**最终这一版 zip**（`fa1364bb…`）上重跑确认的：解压 → `--exit-after=10` → `exit=0`，日志出现
> `libVLC 已加载：…\cinema-hall\vlc`，且包内 `第三方组件清单.md` 为 8962 B（与仓库一致）。

第 8 项的意义：`axvlc`/`npvlc` 剔掉之后播放内核仍能加载，这四份合规文件和 `msi/` 的删除都没有破坏运行。



## C. 你该做的步骤

### C1. 自己复核「371 个 PE 里没有 v2-only」（约 1 分钟）

**前置**：`D:\AI\minimax\_spike\third_party\vlc-3.0.21` 存在；已在 `cinema-hall-L3` 目录。

**操作**（整段粘贴到 PowerShell）：

```powershell
$t = Get-Content .\docs\evidence\vlc-3.0.21-pe-license.tsv -Encoding utf8
$t.Count
$t | ForEach-Object { ($_ -split "`t")[2] } | Group-Object | Sort-Object Count -Descending |
  Select-Object Count,Name | Format-Table -AutoSize
"v2-only 行数 = " + ($t | Where-Object { ($_ -split "`t")[2] -eq 'GPL-2.0-only' }).Count
```

**期望结果**：`371`；表里三行 `332 LGPL-2.1-or-later` / `34 GPL-2.0-or-later` / `5 NO-SELF-STATEMENT`；`v2-only 行数 = 0`。

**不符期望怎么办**：若 `v2-only 行数` 不是 0，把命中的那几行原文贴给我，**不要自行决定换方案** —— 这时候路径 A 要重判。

### C2. 复核那 5 个无自述文件的定案（约 2 分钟）

**前置**：同 C1；且 `D:\AI\minimax\_tmp\vlc-src\vlc-3.0.21` 已解包（没有的话按 C2 末尾的命令重下）。

**操作**：

```powershell
$src='D:\AI\minimax\_tmp\vlc-src\vlc-3.0.21'
foreach ($f in @('src\libvlc.c','bin\winvlc.c','bin\cachegen.c')) {
  "==== $f"
  (Get-Content "$src\$f" -TotalCount 20 | Select-String 'License|version 2') | ForEach-Object { $_.Line.Trim() } }
```

**期望结果**：

- `src\libvlc.c` → `GNU Lesser General Public License … either version 2.1 of the License, or`
- `bin\winvlc.c` 与 `bin\cachegen.c` → `GNU General Public License … either version 2 of the License, or`

三条都必须出现 `or`（后面跟 `(at your option) any later version`）—— 出现 `version 2 of the License.`（句号结尾）才是仅 v2。

**不符期望怎么办**：先确认解包目录存在；没有就重下：

```powershell
$dst='D:\AI\minimax\_tmp\vlc-src'; New-Item -ItemType Directory -Force $dst | Out-Null
curl.exe -L --noproxy '*' -o "$dst\vlc-3.0.21.tar.xz" 'https://download.videolan.org/pub/videolan/vlc/3.0.21/vlc-3.0.21.tar.xz'
cd $dst; tar.exe -xJf vlc-3.0.21.tar.xz
```

（实测 12.9 s / 25.6 MB；若 `tar.exe` 报不认识 xz，说明系统是 Win10 1809 以前，改在别处解包。）

### C3. 复核「那 5 个不进包的文件确实没进包」（约 30 秒）

**前置**：同 C1。

**操作**：

```powershell
$vlc='D:\AI\minimax\_spike\third_party\vlc-3.0.21'
Get-ChildItem "$vlc\plugins" -Recurse -File -Filter '*.dll' |
  Where-Object { $_.Name -match 'v4l|unimotion|wma_fixed' } |
  ForEach-Object { $_.Name }
"命中数 = " + (Get-ChildItem "$vlc\plugins" -Recurse -File -Filter '*.dll' |
  Where-Object { $_.Name -match 'v4l|unimotion|wma_fixed' }).Count
```

**期望结果**：没有任何文件名输出；`命中数 = 0`。

**不符期望怎么办**：若真出现了 `libv4l2_plugin.dll` / `libunimotion_plugin.dll` / `libwma_fixed_plugin.dll`，说明这个 VLC 包是别的构建配置 —— 立刻停下，把文件名给我，路径 A 要重判。

### C4. 复核 `data/` 不再进产物（约 1 分钟）

**前置**：`$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.1'`；在 `cinema-hall-L3`。

**操作**：

```powershell
.\gradlew.bat :desktopApp:stageRelease --console=plain -q
"exit=$LASTEXITCODE"
Test-Path .\desktopApp\build\dist\cinema-hall\data
Get-ChildItem .\desktopApp\build\dist\cinema-hall | Select-Object Name
```

**期望结果**：`exit=0`；`False`；顶层是 `app runtime vlc 使用说明.md cinema-hall.exe cinema-hall.ico`（**没有 `data`**）。

**不符期望怎么办**：若 `Test-Path` 为 `True`，检查 `desktopApp/build.gradle.kts` 里 `from(appImageRoot)` 后面有没有那段 `exclude("data/**")`（本轮加的，见 B2）。若被别人的改动覆盖掉了，直接告诉我，别自己重写打包逻辑。

### C5. 还剩两件要你定（第 1 件已经执行完）

1. ~~把 `axvlc.dll` / `npvlc.dll` 从发布包剔除~~ —— ✅ **2026-11-06 已执行**：`stageRelease` 里 `exclude("axvlc.dll","npvlc.dll")`，连 `msi/`（含注册 `axvlc` 的 WiX 脚本）一起剔了；实测见 B6 第 2、3 项。
2. **确认仓库公开范围**：整仓（含 `server/**`、`app/**`、`core/**`、`feature/**` 全部 Android 代码）一起公开，还是只公开 Windows 端目录？GPLv3 要求"对应源码"完整可获取，但 Android 端是**另一个可独立分发的作品**，可以单独许可。这条影响"整仓开源"到底开多大。
3. **Q3（FFmpeg 是否静态链进 `jwebrtc.dll`）** 谁承担 —— 若静态链接，LGPL-2.1 §6 要求提供可重链接的目标文件。`SOURCE.txt` 现按「提供源码 + 重建说明 + 承诺代为索取」写（见 `docs/第三方组件清单.md` §4）；若要**商业分发**，这条仍建议过一次法务。

> **2026-11-06 更新（源码去向已定并落地）**：开源范围按你的决定收窄为「只公开 Windows 端 + 它依赖的 `core`」，
> 因此**不用**原仓库（那里 `main` 上是 Android 代码），而是单独建一个公开仓库：
>
> - 仓库：`https://github.com/MuYANGLLai/cinema-hall-windows`
> - 分支 `windows-public` → 远端 `main`；tag `windows-0.1.0`；对应提交 `171d274`（**根提交**，不带 Android 历史）
> - 内容：`desktopApp`、`desktop/{rtc,media,library}`、`core/{session,network,protocol,model,rtc,media,library}`，共 187 个文件
> - 已验证**可独立构建**：干净 worktree + JDK 17 + 不装 Android SDK，`:desktopApp:compileKotlin` 成功，
>   `test` 631 例 0 失败 0 错误
> - 为公开而改的三处：`settings.gradle.kts` 只 include 11 个模块；根 `build.gradle.kts` 去掉 Android 插件别名；
>   `gradle.properties` 删掉 `org.gradle.java.home` 的本机路径
> - `SOURCE.txt` 已改成指向该仓库 + tag（不再写未推送的提交号）
>
> 仍待完成的一步只有「把这条分支推上去」：本机到 `github.com:443` 当时不通（直连与代理均失败），
> 已挂 30 秒一轮的重试。**在推送成功之前不要把这个包发给别人** —— 那样源码义务只履行了一半。

---

## 附：本文件引用的证据

| 证据 | 位置 | 行数 / 体积 |
|---|---|---|
| 二进制逐文件许可 | `docs/evidence/vlc-3.0.21-pe-license.tsv` | 371 行 |
| 源码逐文件许可 | `docs/evidence/vlc-3.0.21-src-license.tsv` | 2325 行 |
| VLC 源码包（发布版） | `download.videolan.org/pub/videolan/vlc/3.0.21/vlc-3.0.21.tar.xz` | 25.6 MB / 5019 条目 |
| VLC git 树（交叉验证） | `codeload.github.com/videolan/vlc/tar.gz/refs/tags/3.0.21` | 32.5 MB / 4895 条目 |
| npapi-vlc 上游 | `code.videolan.org/videolan/npapi-vlc`（`COPYING` / `activex/plugin.cpp`） | — |
| 打包链路 | `desktopApp/build.gradle.kts` 的 `stageRelease`（第 83 行起）/ `releaseZip`（第 120 行起） | — |
| 修复点 | 同文件：`exclude("data/**")` 第 95 行、`exclude("axvlc.dll","npvlc.dll")` 第 104 行、`exclude("msi/**")` 第 107 行、`from(complianceDir)` 第 110 行 | — |
| 合规文件源 | `desktopApp/compliance/{LICENSE,SOURCE.txt,NOTICE-third-party.txt}` + `docs/第三方组件清单.md` | 35149 / 2524 / 43116 B |
