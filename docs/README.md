# beatoraja Android 版说明

本仓库是 [beatoraja](https://github.com/exch-bms2/beatoraja) 的 **Android 专用**版本：整个工程就是一个
Android application 模块（根目录即 app 模块），已移除桌面端启动器/后端（LWJGL3、JavaFX、PortAudio、
FFmpeg/JavaCV、Discord RPC、OBS）以及为跨平台而存在的抽象层。

## 架构

- `src/main/java`：完整游戏代码（核心玩法、皮肤引擎、BMS 解析、选曲/结果界面、Lua 皮肤）。
- 关键类（均为唯一实现，不再有平台抽象）：
  - `settings.SettingsActivity`：应用入口（启动器）。管理配置、曲库、玩家、IR、资源并启动游戏。
  - `settings.FolderPickerActivity`：谱面/资源目录选择器（含存储权限引导）。
  - `Launcher`：游戏 Activity，配置数据目录、首次解包资源、启动 `MainController`。
  - `AppPaths`：所有游戏数据路径相对数据根目录解析。
  - `WindowControl`：窗口/焦点/光标状态（全屏固定窗口的薄封装）。
  - `DisplayInfo`：屏幕刷新率。
  - `input.PollingInput`：libGDX 输入事件到游戏输入模型的桥（同时是 `InputProcessor`）。
  - `video.VideoProcessor`：`MediaPlayer` + `SurfaceTexture`（外部 OES 纹理）→ FBO 拷贝为普通 Texture，
    供 BGA 与皮肤背景视频使用。
  - `database.SqliteDatabase` / `SqliteOptions`：把游戏内的 JDBC 调用映射到 `android.database.sqlite`。
- 依赖裁剪：仅保留 gdx core/backend/freetype、gdx-controllers、JLayer、JOrbis、LuaJ、BMS 解析器、
  FLAC、junrar、commons-compress、AndroidX AppCompat/Material；ABI 仅 arm64-v8a。
  另外在 `jniLibs/arm64-v8a/libffmpeg.so` 内置一个精简 Android/arm64 FFmpeg 可执行文件，仅用于
  MPEG-PS 视频回退解码（LGPL，见 `third_party/ffmpeg/`）。
- `src/main/assets/game`：随 APK 打包的默认皮肤/字体/音效、测试曲、占位 BGM/SE，首次启动解包到数据目录；
  `src/main/assets/glsl`：着色器（直接从 assets 读取）。

## 构建与运行

```bash
./scripts/build.sh     # assembleDebug → build/outputs/apk/debug/beatoraja-debug.apk
./scripts/run.sh       # 安装 + 启动 + 截图 + 拉日志（adb 或本机 root su）
./scripts/push-skin.sh ModernChic
./scripts/select-skin.sh ModernChic
```

要求：JDK 17+、Android SDK 36、Gradle 9.6+（有 wrapper）、arm64-v8a 设备（Android 8.0 / API 26+）。
在 Termux（arm64 设备本机构建）上脚本会自动使用 Termux 的 aapt2（AGP 自带的 aapt2 是 x86_64 Linux 二进制）。

> 安装后从应用图标进入的是 **设置/启动界面**；`scripts/run.sh` 为自动化测试而直接启动游戏 Activity。

## 启动器与设置界面

Android 端用一个原生设置界面替代桌面启动器，进入游戏前可完成常用配置（启动游戏时按需扫描曲库）。
界面使用 Material 3 组件（卡片、开关、Chip、滑块、下拉选择/对话框、底部操作栏）并新增了自适应应用图标。
界面截图：[screenshots/settings.png](screenshots/settings.png)。

- **曲库**：BMS 文件夹列表（可增删）、通过目录选择器选择应用数据或外部存储中的谱面目录、
  启动时自动更新、扫描压缩包、曲目详细信息库、立即更新、重建曲库并进入、曲库谱面数。
- **玩家**：多玩家档案切换/新建、玩家名、1P/2P 独立设置。
- **游玩**：模式、默认计量条、长条类型、判定偏移、判定算法、HI-SPEED、Lane Cover/Lift/Hidden、
  HI-SPEED FIX 自动调整。更细的皮肤/按键设置仍在游戏内 CONFIG / SKIN CONFIG 界面。
- **音频**：输出驱动、缓冲大小、同时发声数、采样率、系统/按键/BGM 音量。
- **显示与性能**：皮肤坐标系分辨率、VSync、最大 FPS、帧跳过、Note 位置平滑。
- **IR**：已加载的 IR 插件列表、账号（用户名/密码）、发送模式、导入成绩/Rival；账号直接写入玩家配置。
- **资源**：BGM/效果音/Table/皮肤目录。
- **其他**：IPFS 开关与地址、数据目录/日志路径复制、版本、重置系统配置（保留曲库）。

> 设置界面按 Material 3 风格实现：顶部工具栏、tonal 卡片、Chip 目录标签、MaterialSwitch、Slider
> 与单选对话框；修改自动保存，无需“应用”按钮。

说明：

- 谱面存放在外部存储时，目录选择器需要“所有文件访问权限”（系统设置中授权）；未授权时只能浏览应用目录。
- 设置改动自动保存；从游戏返回（Back/退出）会回到设置界面并刷新曲库数量与 IR 插件列表。
- 首次启动会自动创建默认系统/玩家配置；首次进入游戏会自动扫描一次曲库。

## 发行打包（APK + 皮肤）

```bash
./scripts/package.sh                 # 默认打包 APK + skin/ModernChic
./scripts/package.sh --skin skin/Other --out /storage/emulated/0/Download/other.zip
```

默认输出 `/storage/emulated/0/Download/beatoraja-android-ModernChic.zip`，包含：

- `beatoraja-android.apk`（debug，arm64-v8a，Android 8.0+）
- `skin/ModernChic/`（完整皮肤与音效）
- `安装说明.md`（APK 安装、皮肤复制目标目录与 SKIN CONFIG 选择步骤）

同时生成同名 `.sha256` 校验文件。当前 ModernChic 包约 784MB（媒体文件以存储方式打包，不做重复压缩）。
皮肤目录需位于仓库的 `skin/` 下；第三方皮肤按各自授权决定是否分发。

> 另一种做法是把皮肤内置进 APK 的 assets、首次启动时解包（单文件 APK，但体积会增加到近 800MB），
> 当前默认采用“APK + 皮肤目录”的 ZIP 发行方式。

## 数据目录

`/storage/emulated/0/Android/data/bms.player.beatoraja/files/beatoraja/`

| 路径 | 内容 |
| --- | --- |
| `song/` | BMS 曲目根目录（可用 `bmsroot` 追加） |
| `skin/default/` | 默认皮肤（首次启动解包） |
| `bgm/`、`sound/` | 选曲 BGM / 系统效果音资源集（首次启动解包的是合成占位音） |
| `player/player1/` | 玩家配置、皮肤设置 |
| `songdata.db`、`songinfo.db` | 曲库数据库；首次启动自动扫描，也可在设置界面更新/重建 |
| `logs/beatoraja.log.0` | 运行日志（每次启动追加） |

替换自己的 BGM/效果音：覆盖 `bgm/`、`sound/` 下同名文件即可（BGM 需 `select`/`decide` 基础名；
系统 SE 需 `clear`、`fail`、`f-open`、`o-change` 等 `SystemSoundManager.SoundType` 中的基础名；
支持 wav/ogg/mp3/flac）。程序会自动采用包含对应文件的目录；ModernChic 的 `Sound/` 目录也可作为
效果音集使用。

## 调试桥（Debug Bridge）

仅调试用途，通过广播驱动无头设备：

```bash
am broadcast -a bms.player.beatoraja.CAPTURE
am broadcast -a bms.player.beatoraja.KEY --ei key 54 --ez down true
am broadcast -a bms.player.beatoraja.TAP --ef x 1200 --ef y 700
am broadcast -a bms.player.beatoraja.SCAN
am broadcast -a bms.player.beatoraja.DUMP
am broadcast -a bms.player.beatoraja.QUIT
```

## 功能验证（真机）

已在本机（arm64 / Android 16）逐项验证：

| 功能 | 验证方式 | 结果 |
| --- | --- | --- |
| 冷启动 → 默认皮肤选曲 | 截图 `screenshots/default_select.png` / `fresh_install_select.png` | ✅ |
| 曲库扫描与 SQLite 数据库 | 测试曲入库、选曲可进入 | ✅ |
| 游玩（WAV 键盘音、判定、长条） | 截图 `screenshots/default_play.png` | ✅ |
| ModernChic 皮肤（1914 个 Lua 皮肤对象） | 选曲/决定/游玩/结果截图 `screenshots/modernchic_*.png` | ✅ |
| BGA / 皮肤 mp4 视频 | 测试曲 BGA 与 ModernChic 动态背景截图 `screenshots/android_video_play.png`、`screenshots/modernchic_video_select.png` | ✅ |
| 选曲 BGM / 系统菜单 SE / 皮肤 Lua 音效 | 日志 `SE/BGM loaded : ...`（`bgm/select.ogg`、`sound/o-change.ogg`、`skin/ModernChic/Root/sounds/open.ogg`） | ✅ |
| 键盘/手柄输入注入 | Debug bridge KEY/TAP | ✅ |
| 配置持久化 | 重启后皮肤/设置保持 | ✅ |
| 结果界面 | 打中至少 1 个音符后正常进入（0 判定时不进结果是上游原有行为） | ✅ |
| 原生设置/启动界面 | 首次启动默认配置、曲库目录增删、更新/重建曲库、玩家/IR/音频/显示设置、进入游戏与返回；截图 [screenshots/settings.png](screenshots/settings.png) | ✅ |
| 外部存储曲库 | 目录选择器添加 `/storage/emulated/0/BMSExternal` 后重建曲库，扫描到 2 首谱面，选曲界面显示 `BMSExternal` 目录 | ✅ |
| MPEG-1 BGA 视频 | `[LeaF]Armageddon/_bga.mpg`（mpeg1video 256×256 30fps）自动走 FFmpeg 解码；游戏内截图可见 BGA 画面，帧纹理持续更新；用纯红测试视频验证红蓝通道正确，播放起点按 play 时间对齐 | ✅ |

## IR（Internet Ranking）

IR 通过 IR 官方提供的插件实现。本移植只做“让官方插件能在 Android 上加载”的移植便利：
不实现、不修改、不逆向分析任何 IR 客户端或 API，也不提供 IR API 调试/探测接口。

Android 无法直接加载 JVM 插件 jar，因此构建时用 D8 把 `ir/*.jar` 转为 DEX 并在游戏进程内加载：

1. 准备插件（只需一次；`ir/` 已 gitignore，不提交第三方二进制）：

   ```bash
   # 只提取官方包内置的 IR 插件（Mocha-Repository）
   ./scripts/prepare-ir.sh /path/to/beatoraja0.8.9-full.zip   # 或 beatoraja.jar

   # 如自行确认过某第三方 IR 插件的使用条款，可显式加入发行包 ir/ 下的插件：
   ./scripts/prepare-ir.sh --include-third-party /path/to/beatoraja0.8.9-full.zip

   ./scripts/build.sh
   ```

   构建任务 `dexIrPlugins` 会把 `ir/` 下的插件转成 DEX 并打包进 `assets/ir/`，启动时解包到数据目录
   `ir/` 由 `IRConnectionManager` 通过 `DexClassLoader` 加载。

2. 配置账号：在设置界面的 **IR** 区添加/编辑账号即可（发送模式、导入成绩/Rival 同页设置）。
   自动化场景仍可使用脚本：

   ```bash
   ./scripts/set-ir.sh mocha <user-id> <password> [always|complete|update]
   ```

   进入游戏后日志会出现 `IR plugin loaded : mocha (mocha-ir.jar)`；若账号/网络正常即可登录，
   IR 表、Rival、成绩上传等按原有逻辑工作。

3. 注意事项：
   - 各 IR 对其 API、客户端版本、数据提交都有各自的使用条款和限制，请遵守对应 IR 的规定。
   - 某些 IR 的服务端只接受其官方客户端；如本移植构建被拒绝，请使用该 IR 的官方客户端，
     本项目不包含绕过或修改 IR 服务端行为的功能。
   - 没有 JVM 子进程隔离，IR 插件在游戏进程内运行（代码中以 `TODO(unimplemented)` 标注）。
   - 插件二进制不在仓库中，需要按第 1 步从官方包生成；第三方插件是否可用、是否允许打包
     请以对应 IR 的条款为准。

## 明确未实现（代码中以 `TODO(unimplemented)` 标注）

Android 上应当可以做、但本移植尚未实现的功能：

| 功能 | 现状 | 代码位置 |
| --- | --- | --- |
| MIDI 输入 | 不创建 MIDI 设备，MIDI 绑定 UI 移除 | `input/BMSPlayerInputProcessor` 等处的 `TODO(unimplemented): MIDI input...` |
| WSOLA 变速（练习/快进） | 保留未变速 PCM | `audio/AbstractAudioDriver`、`audio/GdxSoundDriver` |
| 隔离 IR Worker（JVM 子进程） | IR 插件在游戏进程内运行（无隔离） | `ir/IRConnectionManager` |
| 全屏/窗口切换 | 固定全屏，无切换 | `MainController` 中的 `TODO(unimplemented)` |
| 文件管理器打开文件/复制图片 | 仅支持复制文本、打开 URL | `external/SystemActions` |
| 视频音轨 | 有意静音（音乐由 BMS 键盘音提供） | `video/VideoProcessor` |
| 触摸打歌（把屏幕区域映射到键位） | 触摸目前用于皮肤 UI/鼠标；打歌需键盘或手柄 | 见“已知限制” |
| Table / Course / Folder / Song 编辑器 | 设置界面未提供；桌面启动器的这些编辑窗口未移植 | — |
| LR2 成绩导入入口 | `external/ScoreDataImporter` 保留但无 UI 入口 | — |
| 版本检查/自动更新 | 无；APK 需手动更新 | — |
| 完整 Play Option | 设置界面仅提供常用项；其余沿用游戏内/皮肤选项或 JSON | `PlayerConfig`/`PlayConfig` |

有意移除的桌面功能（不属于 Android 目标）：OBS、Discord RPC、Webhook、JavaFX 启动器、PortAudio/ASIO、
FFmpeg/JavaCV 视频解码、多显示器控制、桌面 IME 桥、SDL/JInput 控制器后端选择。
桌面启动器的常用配置/曲库管理/玩家/IR 能力已由 Android 原生设置界面覆盖；编辑器类窗口与版本更新未移植。

## 已知限制

- 输出分辨率按配置的 skin 坐标系（默认 FullHD）拉伸到屏幕（当前 2392×1080），无 letterbox。
- 视频为实时播放、无逐帧精确 seek；拷帧目标上限 1280×720 RGBA 以控制内存。
- MP4/WebM 等由系统 MediaPlayer 解码；`.mpg/.mpeg/.ps/.vob` 等 MPEG-PS 由内置 FFmpeg 子进程解码，
  启动时有一次进程启动延迟，音频有意静音（BMS 按键音提供音乐），需要 Android 9（API 28）及以上；
  Android 8 仍使用 MediaPlayer（无法播放 MPEG-1）。
- 内置 FFmpeg 为 LGPL 构建（`--disable-gpl --disable-nonfree`），来源与许可见 `third_party/ffmpeg/`。
- 触屏选曲依赖皮肤鼠标区域；还没有触屏专用打歌映射。
- 设置界面是竖屏原生界面；游戏为横屏 Activity，退出游戏返回设置。
- 选择外部存储中的谱面目录需要授予“所有文件访问权限”（Android 系统限制）。
- 仅提供 arm64-v8a ABI。
