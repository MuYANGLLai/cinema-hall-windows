# cinema-hall Windows 端使用说明

本文只讲**怎么用**。为什么这么做、放弃了哪些路线，见 `docs/Windows端技术方案.md`；
Phase 0 的实测底稿见 `docs/Windows-Phase0.md`。

当前版本：`0.1.0`（W1–W6 落地）。**还没有线上影厅**（RTC 在 W7），现在是一个
「本地播放器 + 本地影片库」的桌面应用。

---

## 1. 交付形态

一个 zip，解压即用。解压后目录长这样：

```text
cinema-hall\
  cinema-hall.exe        ← 双击这个
  vlc\                   ← libvlc.dll / libvlccore.dll / plugins\（随包分发）
  使用说明.md            ← 本文件
  data\                  ← 首次启动时自动创建，**不随 zip 分发**
```

`data\` 是用户数据，不在发布包里：里面是影片库索引、设置、日志、单实例锁。
发布包带一个别人的 `data\` 会让你「带着别人的影片库首次启动」，所以打包脚本
显式排除了它（`desktopApp/build.gradle.kts` 的 `releaseZip` → `exclude("**/data/**")`）。

**不需要**在机器上安装 VLC：`vlc\` 跟 exe 同级，程序只认这个位置。

---

## 2. 第一次用（解压后照着做）

1. 解压 zip 到一个**可写**目录。可以是中文名、带空格的路径（例如
   `D:\我的 影片\cinema-hall`）—— 这一条是门禁项，已实测。
2. 双击 `cinema-hall.exe`。窗口标题 `cinema-hall · Windows`。
   首次启动会在 exe 同级创建 `data\`，此时还没有影片。
3. 左侧点 `影片库` → 在「根目录」区点 `选择目录…`，选一个存电影的目录
   （或用输入框粘贴路径后点 `添加`）。可以有多个根目录，程序会**递归**扫。
4. 点 `开始扫描`。列表会先**秒出**（快扫只读文件名与大小），时长在后台补；
   右上角进度显示 `解析时长 123/500`，全部完成后变成 `完成：500 部`。
5. 回 `首页`，在「最近可播」里点一部片，或直接点 `打开文件` 选单个视频。
   控制条在画面**下方**（不是浮在画面上）：`播放` / 暂停 / 进度条 / 音量 / 字幕。
6. 想退出：关窗口会直接退出程序；托盘图标（`cinema-hall`）右键有 `显示主窗口` /
   `退出`。

> 为什么控制条在下面：Compose 的 `SwingPanel` 里塞的是 AWT 重量级 Canvas，
> 任何 Compose 控件叠在它上面都**看不见**。这是 Phase 0 的结论，不是审美选择。

---

## 3. 快捷键

| 按键 | 作用 |
| --- | --- |
| `空格` | 播放 / 暂停 |
| `←` / `→` | 后退 / 前进 10 秒 |
| `↑` / `↓` | 音量 +5% / −5% |
| `Ctrl+O` | 打开文件对话框 |

快捷键走窗口级预览（`onPreviewKeyEvent`），所以不用先点画面才有焦点。

---

## 4. 界面里有什么

- `首页`：`打开文件`、最近可播列表、当前片子的播放器。
- `影片库`：根目录管理（`添加` / `移除`）、`开始扫描` / `取消`、影片列表
  （按 `电影` / `剧集` / `短片` 分类，判据复用 Android 端的 80/30 分钟边界）。
- `设置`：`日间模式`、`默认音量`、`播放时阻止系统休眠`。
- `诊断`：`程序目录`、`数据目录`、是否找到 libVLC、`扫描阶段`、跳过统计、
  `打开数据目录` / `打开日志目录`、`退出`。

`诊断` 页是排障的第一站：`可播放` 那一行是 `否：<原因>` 的话，问题在 libVLC
（见第 7 节），不在影片库。

---

## 5. 数据与日志

都在 exe 同级的 `data\` 里（开发态是仓库里的 `desktopApp\data\`）：

| 文件 | 内容 |
| --- | --- |
| `data\library.json` | 根目录列表 + 时长缓存（按「大小 + 修改时间」建键） |
| `data\settings.json` | 主题、音量、防休眠、上次播放、窗口尺寸 |
| `data\logs\app.log` | 运行日志（含每次时长探测失败的具体原因） |
| `data\app-default.lock` | 单实例锁；`--profile=b` 会另建 `app-b.lock` |

想从零开始：**退出程序**后删掉 `data\library.json`（只删这一个文件也会保留设置）。
`settings.json` 坏掉不会让程序打不开，会回落到默认值。

日志里的这条是正常的，不是故障：

```text
direct3d11 vout display error: SetThumbNailClip failed: 0x800706f4
```

每成功探测一部片的时长，探测播放器会建一次视频输出，D3D 就报一次这个错。
它**不计入** `failed`，也不影响播放。

播放相关的诊断（`[vlc] surface attach…`、`[vlc] event playing`、`[vlc] event
lengthChanged=…`）也都写在 `data\logs\app.log` 里。打包后的 exe 是 GUI 程序、
没有控制台，所以**排障一律看日志文件**，不要指望屏幕上能看到输出。

---

## 6. 命令行参数（排障 / 脚本用）

正常使用不需要这些参数。

```text
--audio-helper         采集子进程（W7 用；本轮只预留分支，不参与产品流程）
--profile=b            同一台机器上的第二个实例（单实例锁按 profile 分）
--open=<path>          启动即选片
--no-autoplay          选片但不自动播放
--exit-after=<sec>     跑 N 秒后自动退出
--screenshot=<path>    退出前抓一张窗口截图
--add-root=<dir>       添加一个影片根目录（写进 library.json）
--scan                 添加后立刻扫描，打印一行统计并退出
--measure-root=<dir>   用临时库（data\measure.json）扫一遍该目录并打印统计
```

例：不开界面量一遍扫描性能

```powershell
.\cinema-hall.exe --measure-root="D:\我的 影片"
```

打印：

```text
[measure] root=... phase=DONE found=500 probed=500 failed=0 fast=59ms probe=39645ms total=39739ms skippedExt=0 skippedHidden=0 skippedSystem=0
```

- `fast` = 快扫（列片、拿文件名与大小）耗时，列表可交互的时间点；
- `probe` = 后台补时长耗时；命中缓存时为 `0ms`；
- `failed` = 时长解析失败数，**每一片的失败原因都在 `data\logs\app.log` 里**。

`--audio-helper` 现在单独跑起来是「打开麦克风、往 stdout 写裸 PCM、父进程 stdin
EOF 就自杀」。**这不是功能**，只是打包脚本要验证「同一个 exe 能自举出采集进程」
这条分支存在。W7 接 RTC 时才会真正用它。

---

## 7. 本机实测读数（2026-11，Windows 11 / 32 核）

语料：500 个 939 KB 的视频文件，分在 10 个目录里（`cat0/` … `cat9/`，各 50 个）。

| 场景 | 读数 |
| --- | --- |
| 冷缓存扫描 500 部（开发态） | `found=500 probed=500 failed=0 fast=59ms probe=39645ms total=39739ms` |
| 冷缓存扫描 500 部（**打包态 zip 解压后**） | `found=500 probed=500 failed=0 fast=59ms probe=41264ms total=41361ms` |
| 热缓存重扫 500 部 | `found=500 probed=500 failed=0 fast=60ms probe=0ms total=92ms` |
| 4 个样本片（含 1 个坏文件） | `found=4 probed=4 failed=1 fast=6ms probe=596ms` |
| 起播到出画 | `[vlc] surface attach after 200ms: ok size=1196x665`，`event playing` |
| 播放 15 秒后的状态 | `playing=true position=15151 duration=52209 ready=true` |
| 同机两个实例（打包态，中文+空格路径） | A `18:00:25.800 event playing` → B `18:00:30.809 event playing` → A 退出时 `playing=true position=20151`（**重叠 15 秒都在播**） |

坏文件那一条不是 bug：那个 `sample-5s.mp4` 只有 `mdat` 没有 `moov` 原子，
libVLC 报 `avcodec demux error: Could not open`，日志里写明「播放器进入 STOPPED
（文件打不开）」。**一个坏文件不会拖慢整轮扫描**：探测在 400 ms 内认输。

时长探测是**一次性**成本：第一次扫 500 部约 40 秒（后台进行，列表已经能点），
之后重扫是 92 ms（全部命中缓存）。换机器、换盘、文件名改了不影响缓存命中，
只有「大小或修改时间变了」才会重探。

---

## 8. 常见问题

**双击没反应 / 窗口一闪就没了**
看 `data\logs\app.log` 最后几行。最常见的两个原因：exe 所在目录**不可写**
（比如在 `C:\Program Files` 下解压），或 `vlc\` 目录被删了。

**`诊断` 页显示 `可播放：否：未找到 libVLC…`**
确认 `vlc\libvlc.dll` 与 `cinema-hall.exe` 是**同级**（不是 `vlc\vlc-3.0.21\`）。
也可以设环境变量 `CINEMA_VLC_DIR=<vlc 目录>` 指过去。

**启动后提示「已有实例在运行」**
单实例是按 profile 的锁（`data\app-<profile>.lock`）。要同机开两个窗口，
第二个用 `cinema-hall.exe --profile=b`。上一个实例异常退出留下的锁不会卡住启动。

**有几部片没有时长**
去 `data\logs\app.log` 搜 `时长探测失败`，后面写着原因。缺 `moov` 的残缺文件、
正在下载的半截文件、掉线的网络盘都会出现在这里；这些片子仍能列出来，只是没有时长。

**第一次扫描很慢**
40 秒 / 500 部是冷缓存的一次性成本，列表在 59 ms 时就已经能列片了。如果几百部片
要几分钟，多半是机械盘随机读或网络盘 —— 时长解析对每部片都要读一次文件头。

**播放时机器不休眠，但我想要它休眠**
`设置` 里关掉 `播放时阻止系统休眠`。这是按 W3 要求默认开启的。

**片子放完以后，进度条回到 0:00、按钮变回 `播放`**
这是现在的行为：片尾触发 libVLC 的 `stopped`，播放器把状态复位，再点 `播放` 从头放。
v1 没有「已看完/重播」这类结束态 UI（影片库里的「已看完」标记是另一件事）。

**没有托盘图标**
部分会话（远程桌面、无桌面 shell）里 `SystemTray` 不可用，程序会跳过安装并在
日志里写 `本机不支持系统托盘，跳过`。不影响其它功能。

---

## 9. 自己打包

在仓库里（需要 JDK 17、Gradle wrapper）：

```powershell
cd D:\AI\minimax\cinema-hall-L3
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.1'
.\gradlew.bat :desktopApp:releaseZip --console=plain
```

产物：`desktopApp\build\dist\cinema-hall-windows-0.1.0.zip`。

- 中间产物在 `desktopApp\build\dist\cinema-hall\`（`stageRelease` 的 Sync 目录）；
- libVLC 来源用 `-Pcinema.vlcDir=<目录>` 指定，默认
  `D:/AI/minimax/_spike/third_party/vlc-3.0.21`；
- zip 里**不含** `data\`，且 `isPreserveFileTimestamps=false` /
  `isReproducibleFileOrder=true`，同样输入打出来的包可复现；
- zip 里只有**一个**顶层目录 `cinema-hall\`（`into("cinema-hall")`）。少了这一层，
  解压时 exe 与 `vlc\` 会摊进你当前所在目录。

⚠️ **别往 jpackage 的元数据里放非 ASCII**。`desktopApp/build.gradle.kts` 里
`description` 故意写成英文：Compose 插件把参数写成 UTF-8 的 `@args.txt`，Windows 上的
jpackage 按**系统代码页**读它，本机（GBK）一遇到中文就崩，报错只有一句
`Input length = 1`。换到开了「Beta: Use Unicode UTF-8」的机器又能过 —— 这种「看机器
脸色」的失败最难查，所以宁可让 Windows 属性页显示英文。真需要中文元数据时，在跑
Gradle 前设 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8` 可以让它过，但这等于给打包加了一条
隐式前提，不推荐。

开发态直接跑（不用打包）：

```powershell
.\gradlew.bat :desktopApp:run --console=plain --args="--open=D:\片\某片.mp4"
```

开发态的数据目录是 `desktopApp\data\`，libVLC 按
「`vlc\` → `third_party/vlc-3.0.21` → `CINEMA_VLC_DIR`」的顺序找。

---

## 10. 这一轮**没有**做的事

- 线上影厅 / RTC / 加房 / 同步播放（W7）；
- `--audio-helper` 的产品化接线（只留分支，设计见
  `docs/Windows-音频独立进程-设计.md`）；
- 真正的安装包（`.msi` / `.exe` 安装器）——现在是 `app-image` + zip，解压即用；
- 自动更新、多语言、海报抓取。
