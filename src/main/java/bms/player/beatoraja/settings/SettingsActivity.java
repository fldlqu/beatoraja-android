package bms.player.beatoraja.settings;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import bms.model.Mode;
import bms.player.beatoraja.AudioConfig;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.IRConfig;
import bms.player.beatoraja.Launcher;
import bms.player.beatoraja.PlayConfig;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.R;
import bms.player.beatoraja.Resolution;

/**
 * Start screen: the Android-native replacement for the desktop launcher.
 *
 * <p>Edits the regular system/player configuration files with the game's own config classes, offers a
 * directory chooser for BMS roots and starts the game activity, which returns here when the player
 * quits. The screen itself is built from Material 3 components.</p>
 */
public final class SettingsActivity extends AppCompatActivity {

    private static final int REQUEST_FOLDER = 100;
    private static final String[] GAUGE_NAMES = {
            "ASSIST EASY", "EASY", "NORMAL", "HARD", "EX-HARD", "HAZARD", "CLASS", "EX-CLASS", "EX-HARD CLASS"
    };
    private static final String[] LN_NAMES = {"LN", "CN", "HCN"};
    private static final List<Mode> MODES = List.of(Mode.BEAT_7K, Mode.BEAT_5K, Mode.BEAT_10K,
            Mode.BEAT_14K, Mode.POPN_9K, Mode.KEYBOARD_24K, Mode.KEYBOARD_24K_DOUBLE);

    private SettingsStore store;
    private Config config;
    private PlayerConfig player;
    private Mode mode = Mode.BEAT_7K;

    private LinearLayout content;
    private ScrollView scroll;
    private View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveTask = this::saveNow;
    private boolean firstStart;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        store = new SettingsStore(this);
        firstStart = !Files.isRegularFile(store.dataRoot().resolve("config_sys.json"));
        config = store.loadConfig();
        player = store.loadPlayer(config);
        setContentView(R.layout.activity_settings);
        initializeShell();
        render();
    }

    private void initializeShell() {
        root = findViewById(R.id.settings_root);
        scroll = findViewById(R.id.settings_scroll);
        content = findViewById(R.id.settings_content);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        final MaterialToolbar toolbar = findViewById(R.id.settings_toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        toolbar.setOnMenuItemClickListener(item -> false);
        final MaterialButton start = findViewById(R.id.settings_start);
        start.setOnClickListener(view -> startGame(false, false));
        final MaterialButton scan = findViewById(R.id.settings_scan);
        scan.setOnClickListener(view -> startGame(true, false));
        final MaterialButton rebuild = findViewById(R.id.settings_rebuild);
        rebuild.setOnClickListener(view -> confirmRebuild());
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        loading = true;
        final int scrollY = scroll.getScrollY();
        content.removeAllViews();
        if (firstStart) addFirstStartCard();
        addLibrarySection();
        addPlayerSection();
        addPlaySection();
        addAudioSection();
        addDisplaySection();
        addIrSection();
        addResourceSection();
        addAboutSection();
        loading = false;
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private void addFirstStartCard() {
        final MaterialCardView card = UiKit.card(this);
        final LinearLayout body = UiKit.cardContent(card);
        final TextView title = UiKit.text(this, "欢迎使用 beatoraja", 20f, UiKit.onSurface(this), true);
        body.addView(title);
        body.addView(UiKit.hint(this, "首次启动会自动创建默认配置。先选择谱面文件夹，再进入游戏；"
                + "首次进入游戏会在后台扫描曲库。"));
        final MaterialButton choose = UiKit.button(this, "选择谱面文件夹", UiKit.ButtonStyle.FILLED);
        choose.setOnClickListener(view -> chooseFolder("选择 BMS 根目录",
                config.getBmsroot().length > 0 ? config.getBmsroot()[0] : appDataSongDirectory()));
        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, UiKit.dp(this, 14), 0, 0);
        actions.addView(choose);
        body.addView(actions);
        content.addView(card);
    }

    private void addLibrarySection() {
        content.addView(UiKit.section(this, "曲库"));
        final MaterialCardView card = UiKit.card(this);
        final LinearLayout body = UiKit.cardContent(card);
        body.addView(UiKit.text(this, "谱面文件夹", 16f, UiKit.onSurface(this), true));
        final ChipGroup roots = UiKit.chipGroup(this);
        final String[] configured = config.getBmsroot();
        for (String path : configured) {
            final Chip chip = UiKit.chip(this, path);
            chip.setOnCloseIconClickListener(view -> removeRoot(path));
            roots.addView(chip);
        }
        if (configured.length == 0) {
            roots.addView(UiKit.hint(this, "尚未配置谱面目录"));
        }
        body.addView(roots);
        final MaterialButton add = UiKit.button(this, "添加谱面文件夹", UiKit.ButtonStyle.OUTLINED);
        add.setOnClickListener(view -> chooseFolder("添加 BMS 根目录", appDataSongDirectory()));
        final LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setPadding(0, UiKit.dp(this, 10), 0, 0);
        addRow.addView(add);
        body.addView(addRow);
        body.addView(UiKit.hint(this, songCountText()));
        body.addView(UiKit.switchRow(this, "启动时自动更新曲库", config.isUpdatesong(),
                "每次进入选曲界面前增量扫描谱面目录", (button, checked) -> {
                    config.setUpdatesong(checked);
                    markDirty();
                }));
        body.addView(UiKit.switchRow(this, "扫描压缩包内的谱面", config.isScanSongArchives(), null,
                (button, checked) -> {
                    config.setScanSongArchives(checked);
                    markDirty();
                }));
        body.addView(UiKit.switchRow(this, "使用曲目详细信息库", config.isUseSongInfo(), null,
                (button, checked) -> {
                    config.setUseSongInfo(checked);
                    markDirty();
                }));
        final MaterialButton update = UiKit.button(this, "立即更新曲库", UiKit.ButtonStyle.TONAL);
        update.setOnClickListener(view -> startGame(true, false));
        final MaterialButton rebuild = UiKit.button(this, "重建曲库", UiKit.ButtonStyle.OUTLINED);
        rebuild.setTextColor(UiKit.error(this));
        rebuild.setOnClickListener(view -> confirmRebuild());
        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);
        actions.addView(update, weighted(1));
        final View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(this, 8), 1));
        actions.addView(spacer);
        actions.addView(rebuild, weighted(1));
        body.addView(actions);
        content.addView(card);
    }

    private void removeRoot(String path) {
        final String[] roots = config.getBmsroot();
        if (roots.length <= 1) {
            UiKit.snack(root, "至少保留一个谱面目录");
            return;
        }
        final List<String> updated = new ArrayList<>(Arrays.asList(roots));
        updated.remove(path);
        config.setBmsroot(updated.toArray(new String[0]));
        saveNow();
        render();
    }

    private void addPlayerSection() {
        content.addView(UiKit.section(this, "玩家"));
        final MaterialCardView card = UiKit.card(this);
        final LinearLayout body = UiKit.cardContent(card);
        final List<String> playerIds = new ArrayList<>(Arrays.asList(store.playerIds(config)));
        if (playerIds.isEmpty()) playerIds.add(config.getPlayername());
        final UiKit.Dropdown players = UiKit.dropdown(this);
        players.bind(playerIds, Math.max(0, playerIds.indexOf(config.getPlayername())), index -> {
            if (loading) return;
            final String id = playerIds.get(index);
            if (id.equals(config.getPlayername())) return;
            saveNow();
            config.setPlayername(id);
            player = store.loadPlayer(config);
            markDirty();
            render();
        });
        body.addView(UiKit.dropdownRow(this, "档案", players));
        final MaterialButton addPlayer = UiKit.button(this, "新建玩家档案", UiKit.ButtonStyle.OUTLINED);
        addPlayer.setOnClickListener(view -> promptNewPlayer());
        final LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
        addRow.addView(addPlayer);
        body.addView(addRow);
        body.addView(UiKit.editRow(this, "玩家名", player.getName(), "显示名称",
                InputType.TYPE_CLASS_TEXT, value -> {
                    player.setName(value);
                    markDirty();
                }));
        body.addView(UiKit.switchRow(this, "1P/2P 独立设置", player.isIndependentPlaySides(),
                "分别保存两侧的按键与游玩设置", (button, checked) -> {
                    player.setIndependentPlaySides(checked);
                    markDirty();
                }));
        content.addView(card);
    }

    private void addPlaySection() {
        content.addView(UiKit.section(this, "游玩"));
        final MaterialCardView card = UiKit.card(this);
        final LinearLayout body = UiKit.cardContent(card);
        body.addView(UiKit.hint(this, "这里提供常用参数；按键与更细的皮肤选项可在游戏内设置界面调整。"));
        final List<String> modeNames = MODES.stream().map(m -> m.name().replace('_', ' ')).toList();
        final UiKit.Dropdown modeDropdown = UiKit.dropdown(this);
        modeDropdown.bind(modeNames, MODES.indexOf(mode), index -> {
            if (loading) return;
            mode = MODES.get(index);
            render();
        });
        body.addView(UiKit.dropdownRow(this, "模式", modeDropdown));

        final List<String> gauges = Arrays.asList(GAUGE_NAMES);
        final UiKit.Dropdown gauge = UiKit.dropdown(this);
        gauge.bind(gauges, Math.max(0, Math.min(GAUGE_NAMES.length - 1, player.getGauge())), index -> {
            player.setGauge(index);
            markDirty();
        });
        body.addView(UiKit.dropdownRow(this, "默认计量条", gauge));

        final List<String> lns = Arrays.asList(LN_NAMES);
        final UiKit.Dropdown ln = UiKit.dropdown(this);
        ln.bind(lns, Math.max(0, Math.min(2, player.getLnmode())), index -> {
            player.setLnmode(index);
            markDirty();
        });
        body.addView(UiKit.dropdownRow(this, "长条类型", ln));

        body.addView(UiKit.editRow(this, "判定偏移 (ms)", String.valueOf(player.getJudgetiming()),
                "负数提前 / 正数延后", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED,
                value -> {
                    try {
                        player.setJudgetiming(Integer.parseInt(value.trim()));
                        markDirty();
                    } catch (NumberFormatException ignored) {
                    }
                }));

        final PlayConfig play = playConfig();
        if (play != null) {
            final List<String> judges = Arrays.stream(bms.player.beatoraja.play.JudgeAlgorithm.values())
                    .map(Enum::name).toList();
            final UiKit.Dropdown judge = UiKit.dropdown(this);
            judge.bind(judges, Math.max(0, judges.indexOf(play.getJudgetype())), index -> {
                play.setJudgetype(judges.get(index));
                markDirty();
            });
            body.addView(UiKit.dropdownRow(this, "判定算法", judge));
            body.addView(UiKit.editRow(this, "HI-SPEED", formatFloat(play.getHispeed()), "例如 1.0",
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, value -> {
                        try {
                            play.setHispeed(Math.max(0.1f, Math.min(20f, Float.parseFloat(value))));
                            markDirty();
                        } catch (NumberFormatException ignored) {
                        }
                    }));
            body.addView(UiKit.switchRow(this, "Lane Cover", play.isEnablelanecover(), null,
                    (button, checked) -> {
                        play.setEnablelanecover(checked);
                        markDirty();
                    }));
            body.addView(UiKit.switchRow(this, "Lift", play.isEnablelift(), null,
                    (button, checked) -> {
                        play.setEnablelift(checked);
                        markDirty();
                    }));
            body.addView(UiKit.switchRow(this, "Hidden", play.isEnablehidden(), null,
                    (button, checked) -> {
                        play.setEnablehidden(checked);
                        markDirty();
                    }));
            body.addView(UiKit.switchRow(this, "HI-SPEED FIX 自动调整", play.isEnableHispeedAutoAdjust(), null,
                    (button, checked) -> {
                        play.setHispeedAutoAdjust(checked);
                        markDirty();
                    }));
        }
        content.addView(card);
    }

    private void addAudioSection() {
        content.addView(UiKit.section(this, "音频"));
        final MaterialCardView card = UiKit.card(this);
        final LinearLayout body = UiKit.cardContent(card);
        final AudioConfig audio = config.getAudioConfig();

        final List<String> drivers = Arrays.stream(AudioConfig.DriverType.values()).map(Enum::name).toList();
        final UiKit.Dropdown driver = UiKit.dropdown(this);
        driver.bind(drivers, Math.max(0, drivers.indexOf(audio.getDriver().name())), index -> {
            audio.setDriver(AudioConfig.DriverType.valueOf(drivers.get(index)));
            markDirty();
        });
        body.addView(UiKit.dropdownRow(this, "输出驱动", driver));

        final List<String> buffers = List.of("256", "512", "1024", "2048", "4096", "8192");
        final UiKit.Dropdown buffer = UiKit.dropdown(this);
        buffer.bind(buffers, Math.max(0, buffers.indexOf(String.valueOf(audio.getDeviceBufferSize()))),
                index -> {
                    audio.setDeviceBufferSize(Integer.parseInt(buffers.get(index)));
                    markDirty();
                });
        body.addView(UiKit.dropdownRow(this, "缓冲大小 (samples)", buffer));

        final List<String> sourceOptions = List.of("64", "128", "256", "512");
        final UiKit.Dropdown sources = UiKit.dropdown(this);
        sources.bind(sourceOptions,
                Math.max(0, sourceOptions.indexOf(String.valueOf(audio.getDeviceSimultaneousSources()))),
                index -> {
                    audio.setDeviceSimultaneousSources(Integer.parseInt(sourceOptions.get(index)));
                    markDirty();
                });
        body.addView(UiKit.dropdownRow(this, "同时发声数", sources));

        final List<String> rates = List.of("自动", "44100", "48000", "96000");
        final UiKit.Dropdown rate = UiKit.dropdown(this);
        rate.bind(rates, Math.max(0, rates.indexOf(audio.getSampleRate() == 0 ? "自动"
                : String.valueOf(audio.getSampleRate()))), index -> {
            audio.setSampleRate(index == 0 ? 0 : Integer.parseInt(rates.get(index)));
            markDirty();
        });
        body.addView(UiKit.dropdownRow(this, "采样率", rate));

        body.addView(volumeRow("系统音量", audio.getSystemvolume(), audio::setSystemvolume));
        body.addView(volumeRow("按键音音量", audio.getKeyvolume(), audio::setKeyvolume));
        body.addView(volumeRow("BGM 音量", audio.getBgvolume(), audio::setBgvolume));
        content.addView(card);
    }

    private View volumeRow(String title, float current, Consumer<Float> setter) {
        final int initial = Math.round(current * 100f);
        return UiKit.seekRow(this, title, initial, "%", progress -> {
            setter.accept(progress / 100f);
            markDirty();
        });
    }

    private void addDisplaySection() {
        content.addView(UiKit.section(this, "显示与性能"));
        final MaterialCardView card = UiKit.card(this);
        final LinearLayout body = UiKit.cardContent(card);

        final List<String> resolutions = Arrays.stream(Resolution.values())
                .map(r -> r.name() + "  " + r.width + "×" + r.height).toList();
        final String selectedResolution = config.getResolution().name() + "  "
                + config.getResolution().width + "×" + config.getResolution().height;
        final UiKit.Dropdown resolution = UiKit.dropdown(this);
        resolution.bind(resolutions, Math.max(0, resolutions.indexOf(selectedResolution)), index -> {
            config.setResolution(Resolution.values()[index]);
            markDirty();
        });
        body.addView(UiKit.dropdownRow(this, "皮肤坐标系分辨率", resolution));

        body.addView(UiKit.switchRow(this, "垂直同步 (VSync)", config.isVsync(), null,
                (button, checked) -> {
                    config.setVsync(checked);
                    markDirty();
                }));

        final List<String> fpsOptions = List.of("60", "120", "144", "240");
        final UiKit.Dropdown maxFps = UiKit.dropdown(this);
        maxFps.bind(fpsOptions, Math.max(0, fpsOptions.indexOf(String.valueOf(config.getMaxFramePerSecond()))),
                index -> {
                    config.setMaxFramePerSecond(Integer.parseInt(fpsOptions.get(index)));
                    markDirty();
                });
        body.addView(UiKit.dropdownRow(this, "最大 FPS", maxFps));

        final List<String> skips = List.of("0", "1", "2", "3", "4");
        final UiKit.Dropdown frameskip = UiKit.dropdown(this);
        frameskip.bind(skips, Math.max(0, skips.indexOf(String.valueOf(config.getFrameskip()))), index -> {
            config.setFrameskip(Integer.parseInt(skips.get(index)));
            markDirty();
        });
        body.addView(UiKit.dropdownRow(this, "帧跳过", frameskip));

        body.addView(UiKit.switchRow(this, "Note 位置平滑", config.isNotePositionSmoothing(), null,
                (button, checked) -> {
                    config.setNotePositionSmoothing(checked);
                    markDirty();
                }));
        content.addView(card);
    }

    private void addIrSection() {
        content.addView(UiKit.section(this, "IR"));
        final MaterialCardView card = UiKit.card(this);
        final LinearLayout body = UiKit.cardContent(card);
        body.addView(UiKit.hint(this, "IR 插件随游戏一起加载；各 IR 的 API 与使用受其自身条款约束，"
                + "这里只负责账号配置。"));
        final List<SettingsStore.IrPlugin> plugins = store.irPlugins();
        if (plugins.isEmpty()) {
            body.addView(UiKit.hint(this, "尚未发现 IR 插件：可在构建前用 scripts/prepare-ir.sh 从官方包提取，"
                    + "或把允许使用的插件放入数据目录 ir/。"));
        } else {
            final StringBuilder names = new StringBuilder("已加载插件：");
            for (SettingsStore.IrPlugin plugin : plugins) {
                if (names.length() > 6) names.append("、");
                names.append(plugin.name());
            }
            body.addView(UiKit.hint(this, names.toString()));
        }
        final IRConfig[] irconfigs = player.getIrconfig();
        if (irconfigs != null) {
            for (IRConfig irconfig : irconfigs) {
                if (irconfig == null) continue;
                final LinearLayout row = UiKit.row(this);
                row.addView(UiKit.label(this, irconfig.getIrname()
                        + (irconfig.getUserid().isEmpty() ? "  (未配置账号)" : "  " + mask(irconfig.getUserid()))));
                final MaterialButton edit = UiKit.button(this, "编辑", UiKit.ButtonStyle.TEXT);
                edit.setOnClickListener(view -> showIrDialog(irconfig));
                row.addView(edit);
                body.addView(row);
                UiKit.divider(this, body);
            }
        }
        final MaterialButton add = UiKit.button(this, "添加 IR 账号", UiKit.ButtonStyle.OUTLINED);
        add.setOnClickListener(view -> showIrDialog(null));
        final LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setPadding(0, UiKit.dp(this, 8), 0, 0);
        addRow.addView(add);
        body.addView(addRow);
        content.addView(card);
    }

    private void addResourceSection() {
        content.addView(UiKit.section(this, "资源"));
        final MaterialCardView card = UiKit.card(this);
        final LinearLayout body = UiKit.cardContent(card);
        body.addView(UiKit.editRow(this, "BGM 目录", config.getBgmpath(), "相对数据目录或绝对路径",
                InputType.TYPE_CLASS_TEXT, value -> {
                    config.setBgmpath(value);
                    markDirty();
                }));
        body.addView(UiKit.editRow(this, "效果音目录", config.getSoundpath(), "相对数据目录或绝对路径",
                InputType.TYPE_CLASS_TEXT, value -> {
                    config.setSoundpath(value);
                    markDirty();
                }));
        body.addView(UiKit.editRow(this, "Table 目录", config.getTablepath(), "相对数据目录或绝对路径",
                InputType.TYPE_CLASS_TEXT, value -> {
                    config.setTablepath(value);
                    markDirty();
                }));
        body.addView(UiKit.editRow(this, "皮肤目录", config.getSkinpath(), "相对数据目录或绝对路径",
                InputType.TYPE_CLASS_TEXT, value -> {
                    config.setSkinpath(value);
                    markDirty();
                }));
        final MaterialButton folder = UiKit.button(this, "选择 BGM 目录", UiKit.ButtonStyle.OUTLINED);
        folder.setOnClickListener(view -> chooseFolder("选择 BGM 目录", config.getBgmpath()));
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, UiKit.dp(this, 6), 0, 0);
        row.addView(folder);
        body.addView(row);
        content.addView(card);
    }

    private void addAboutSection() {
        content.addView(UiKit.section(this, "其他"));
        final MaterialCardView card = UiKit.card(this);
        final LinearLayout body = UiKit.cardContent(card);
        body.addView(UiKit.switchRow(this, "IPFS 自动下载", config.isEnableIpfs(), null,
                (button, checked) -> {
                    config.setEnableIpfs(checked);
                    markDirty();
                    render();
                }));
        if (config.isEnableIpfs()) {
            body.addView(UiKit.editRow(this, "IPFS URL", config.getIpfsUrl(), null,
                    InputType.TYPE_CLASS_TEXT, value -> {
                        config.setIpfsUrl(value);
                        markDirty();
                    }));
        }
        final LinearLayout dataRow = UiKit.row(this);
        dataRow.addView(UiKit.label(this, "数据目录：" + store.dataRoot()));
        final MaterialButton copyData = UiKit.button(this, "复制", UiKit.ButtonStyle.TEXT);
        copyData.setOnClickListener(view -> {
            store.copyToClipboard("data root", store.dataRoot().toString());
            UiKit.snack(root, "已复制数据目录路径");
        });
        dataRow.addView(copyData);
        body.addView(dataRow);
        final LinearLayout logRow = UiKit.row(this);
        logRow.addView(UiKit.label(this, "日志：logs/beatoraja.log.0"));
        final MaterialButton copyLog = UiKit.button(this, "复制", UiKit.ButtonStyle.TEXT);
        copyLog.setOnClickListener(view -> {
            store.copyToClipboard("log path", store.dataRoot().resolve("logs/beatoraja.log.0").toString());
            UiKit.snack(root, "已复制日志路径");
        });
        logRow.addView(copyLog);
        body.addView(logRow);
        body.addView(UiKit.hint(this, "版本：" + packageVersion()));
        final MaterialButton reset = UiKit.button(this, "重置系统配置（保留曲库）", UiKit.ButtonStyle.OUTLINED);
        reset.setTextColor(UiKit.error(this));
        reset.setOnClickListener(view -> confirmReset());
        final LinearLayout rawRow = new LinearLayout(this);
        rawRow.setOrientation(LinearLayout.HORIZONTAL);
        rawRow.setPadding(0, UiKit.dp(this, 8), 0, 0);
        rawRow.addView(reset);
        body.addView(rawRow);
        content.addView(card);
    }

    // ------------------------------------------------------------------ actions

    private void saveNow() {
        handler.removeCallbacks(saveTask);
        try {
            store.saveConfig(config);
            store.savePlayer(config, player);
        } catch (RuntimeException e) {
            UiKit.snack(root, "保存配置失败：" + e.getMessage());
        }
    }

    private void markDirty() {
        handler.removeCallbacks(saveTask);
        handler.postDelayed(saveTask, 400);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store == null) return;
        if (firstStart && Files.isRegularFile(store.dataRoot().resolve("config_sys.json"))) {
            firstStart = false;
        }
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    private void startGame(boolean scan, boolean rebuild) {
        saveNow();
        if (rebuild) store.rebuildSongDatabase();
        final Intent intent = new Intent(this, Launcher.class);
        if (scan) intent.putExtra(Launcher.EXTRA_SCAN, true);
        if (rebuild) intent.putExtra(Launcher.EXTRA_REBUILD, true);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            UiKit.snack(root, "无法启动游戏：" + e.getMessage());
        }
    }

    private void confirmRebuild() {
        UiKit.dialog(this)
                .setTitle("重建曲库")
                .setMessage("将删除 songdata.db 并重新扫描所有谱面目录，谱面很多时需要较长时间。继续吗？")
                .setPositiveButton("重建", (dialog, which) -> startGame(false, true))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmReset() {
        UiKit.dialog(this)
                .setTitle("重置系统配置")
                .setMessage("将删除 config_sys.json 与当前玩家配置并恢复默认值，曲库与成绩保留。继续吗？")
                .setPositiveButton("重置", (dialog, which) -> {
                    try {
                        Files.deleteIfExists(store.dataRoot().resolve("config_sys.json"));
                        Files.deleteIfExists(store.dataRoot().resolve(config.getPlayerpath())
                                .resolve(config.getPlayername()).resolve("config_player.json"));
                    } catch (Exception e) {
                        UiKit.snack(root, "重置失败：" + e.getMessage());
                    }
                    config = store.loadConfig();
                    player = store.loadPlayer(config);
                    render();
                    UiKit.snack(root, "已恢复默认配置");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseFolder(String title, String startPath) {
        startActivityForResult(FolderPickerActivity.intent(this, title, startPath), REQUEST_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || data == null) return;
        final String path = data.getStringExtra(FolderPickerActivity.EXTRA_RESULT);
        if (path == null) return;
        final List<String> roots = new ArrayList<>(Arrays.asList(config.getBmsroot()));
        if (!roots.contains(path)) roots.add(path);
        config.setBmsroot(roots.toArray(new String[0]));
        saveNow();
        render();
        UiKit.snack(root, "已添加：" + path);
    }

    private void promptNewPlayer() {
        final TextInputEditText input = new TextInputEditText(this);
        input.setHint("玩家 ID（字母/数字）");
        input.setSingleLine(true);
        final LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(UiKit.dp(this, 20), 0, UiKit.dp(this, 20), 0);
        wrapper.addView(input);
        UiKit.dialog(this)
                .setTitle("新建玩家档案")
                .setView(wrapper)
                .setPositiveButton("创建", (dialog, which) -> {
                    final String id = input.getText() == null ? "" : input.getText().toString().trim();
                    if (id.isEmpty() || !id.matches("[A-Za-z0-9_-]{1,32}")) {
                        UiKit.snack(root, "玩家 ID 只能包含字母、数字、下划线或短横线");
                        return;
                    }
                    PlayerConfig.create(config.getPlayerpath(), id);
                    config.setPlayername(id);
                    player = store.loadPlayer(config);
                    saveNow();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showIrDialog(IRConfig existing) {
        final List<String> names = new ArrayList<>();
        for (SettingsStore.IrPlugin plugin : store.irPlugins()) names.add(plugin.name());
        if (names.isEmpty()) names.add("mocha");
        final List<String> sendModes = List.of("总是发送", "完成时发送", "更新成绩时发送");
        final int nameIndex = existing != null && names.contains(existing.getIrname())
                ? names.indexOf(existing.getIrname()) : 0;
        final int sendIndex = existing != null ? Math.max(0, Math.min(2, existing.getIrsend())) : 0;

        final UiKit.Dropdown name = UiKit.dropdown(this);
        name.bind(names, nameIndex, index -> {
        });
        final TextInputEditText user = new TextInputEditText(this);
        user.setHint("User ID");
        user.setSingleLine(true);
        user.setText(existing != null ? existing.getUserid() : "");
        final TextInputEditText password = new TextInputEditText(this);
        password.setHint("Password");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(existing != null ? existing.getPassword() : "");
        final UiKit.Dropdown send = UiKit.dropdown(this);
        send.bind(sendModes, sendIndex, index -> {
        });
        final MaterialSwitch importScore = new MaterialSwitch(this);
        importScore.setText("导入 IR 成绩");
        importScore.setTextColor(UiKit.onSurface(this));
        importScore.setChecked(existing != null && existing.isImportscore());
        final MaterialSwitch importRival = new MaterialSwitch(this);
        importRival.setText("导入 Rival");
        importRival.setTextColor(UiKit.onSurface(this));
        importRival.setChecked(existing == null || existing.isImportrival());

        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 8), UiKit.dp(this, 20), 0);
        layout.addView(UiKit.row(this, "IR", name.view()));
        layout.addView(wrapField(user));
        layout.addView(wrapField(password));
        layout.addView(UiKit.row(this, "发送时机", send.view()));
        layout.addView(importScore);
        layout.addView(importRival);

        UiKit.dialog(this)
                .setTitle(existing == null ? "添加 IR 账号" : "编辑 IR 账号")
                .setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    IRConfig target = existing;
                    final String selectedName = names.get(name.selectedIndex());
                    final IRConfig[] current = player.getIrconfig();
                    final List<IRConfig> list = new ArrayList<>(current == null ? List.of() : Arrays.asList(current));
                    if (target == null) {
                        for (IRConfig entry : list) {
                            if (selectedName.equals(entry.getIrname())) {
                                target = entry;
                                break;
                            }
                        }
                    }
                    if (target == null) {
                        target = new IRConfig();
                        target.setIrname(selectedName);
                        list.add(target);
                    }
                    target.setUserid(user.getText() == null ? "" : user.getText().toString().trim());
                    target.setPassword(password.getText() == null ? "" : password.getText().toString());
                    target.setIrsend(send.selectedIndex());
                    target.setImportscore(importScore.isChecked());
                    target.setImportrival(importRival.isChecked());
                    player.setIrconfig(list.toArray(new IRConfig[0]));
                    saveNow();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private View wrapField(View field) {
        final LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 4));
        wrapper.addView(field, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    // ------------------------------------------------------------------ helpers

    private PlayConfig playConfig() {
        try {
            final bms.player.beatoraja.PlayModeConfig modeConfig = player.getPlayConfig(mode);
            return modeConfig != null ? modeConfig.getPlayconfig() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String songCountText() {
        final int count = store.songCount();
        return count == 0 ? "曲库为空（首次进入游戏时会自动扫描）" : "曲库：" + count + " 首谱面";
    }

    private String appDataSongDirectory() {
        return store.dataRoot().resolve("song").toString();
    }

    private static String mask(String value) {
        if (value.length() <= 2) return "**";
        return value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }

    private static String formatFloat(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) return String.valueOf(Math.round(value));
        return String.valueOf(value);
    }

    private String packageVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private LinearLayout.LayoutParams weighted(int weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }
}
