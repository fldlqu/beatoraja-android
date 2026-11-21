package bms.player.beatoraja;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.logging.Logger;

import bms.player.beatoraja.AudioConfig;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.Resolution;
import bms.player.beatoraja.AppPaths;

/**
 * First-run configuration tuned for a fullscreen, touch-first device.
 *
 * <p>Defaults are only applied when there is no {@code config_sys.json} yet; afterwards the regular
 * config file is authoritative.</p>
 */
public final class Defaults {

    /** FullHD destination resolution: most modern skins (default and ModernChic) are authored for it. */
    private static final Resolution DEFAULT_RESOLUTION = Resolution.FULLHD;

    private Defaults() {
    }

    public static void apply(Config config, boolean firstRun) {
        AudioConfig audio = config.getAudioConfig();
        if (firstRun) {
            config.setResolution(DEFAULT_RESOLUTION);
            config.setVsync(true);
            config.setMaxFramePerSecond(120);
            config.setEnableIpfs(false);
            config.setBmsroot(new String[] {"song"});
            config.setSongpath(Config.SONGPATH_DEFAULT);
            config.setSonginfopath(Config.SONGINFOPATH_DEFAULT);
            config.setPlayerpath(Config.PLAYERPATH_DEFAULT);
            config.setSkinpath(Config.SKINPATH_DEFAULT);
            config.setTablepath(Config.TABLEPATH_DEFAULT);
            config.setSystemfontpath("font/VL-Gothic-Regular.ttf");
            config.setMessagefontpath("font/VL-Gothic-Regular.ttf");
            // The AudioTrack backend is the low-latency path for the custom keysound mixer.
            audio.setDriver(AudioConfig.DriverType.AudioDevice);
            audio.setDeviceBufferSize(1024);
            audio.setDeviceSimultaneousSources(256);
            audio.setSampleRate(0);
        }
        ensureDirectory(config.getBmsroot().length > 0 ? AppPaths.path(config.getBmsroot()[0]) : AppPaths.path("song"));
        ensureDirectory(AppPaths.path(config.getPlayerpath()));
        ensureDirectory(AppPaths.path(config.getSkinpath()));
        ensureDirectory(AppPaths.path(config.getBgmpath()));
        ensureDirectory(AppPaths.path(config.getSoundpath()));
        adoptAvailableAudioSets(config);
    }

    private static final String[] AUDIO_EXTENSIONS = {".wav", ".ogg", ".mp3", ".flac"};

    /**
     * Falls back to an audio set that is actually present on disk.
     *
     * <p>There is no external launcher to pick a BGM/SE directory, so configured paths may point at
     * missing folders (or at an empty directory while the selected skin ships a usable set, e.g.
     * {@code skin/ModernChic/Sound}). The first folder containing the marker file wins.</p>
     */
    private static void adoptAvailableAudioSets(Config config) {
        if (!containsAudioSet(AppPaths.path(config.getSoundpath()), "clear")) {
            Path candidate = findAudioSet("clear");
            if (candidate != null) {
                config.setSoundpath(configPortablePath(candidate));
                Logger.getGlobal().info("adopted sound set " + candidate);
            }
        }
        if (!containsAudioSet(AppPaths.path(config.getBgmpath()), "select")) {
            Path candidate = findAudioSet("select");
            if (candidate != null) {
                config.setBgmpath(configPortablePath(candidate));
                Logger.getGlobal().info("adopted BGM set " + candidate);
            }
        }
    }

    private static Path findAudioSet(String marker) {
        final Path root = AppPaths.normalizedRoot();
        final List<Path> candidates = new ArrayList<>();
        candidates.add(root.resolve(marker.equals("select") ? "bgm" : "sound"));
        candidates.add(root.resolve("sound"));
        candidates.add(root.resolve("bgm"));
        try (Stream<Path> skins = Files.isDirectory(root.resolve("skin")) ? Files.list(root.resolve("skin")) : Stream.empty()) {
            skins.filter(Files::isDirectory).sorted().forEach(skinDir -> {
                candidates.add(skinDir.resolve("Sound"));
                candidates.add(skinDir.resolve("sound"));
                candidates.add(skinDir.resolve("BGM"));
                candidates.add(skinDir.resolve("bgm"));
            });
        } catch (IOException | RuntimeException ignored) {
            // fall through to the defaults
        }
        for (Path candidate : candidates) {
            if (containsAudioSet(candidate, marker)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean containsAudioSet(Path directory, String marker) {
        if (directory == null || !Files.isDirectory(directory)) return false;
        try (Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(file -> {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                for (String extension : AUDIO_EXTENSIONS) {
                    if (name.equals(marker + extension)) return true;
                }
                return false;
            });
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    /** Stores paths below the game data root relative, so reinstalling/moving the data root keeps working. */
    private static String configPortablePath(Path path) {
        final Path root = AppPaths.normalizedRoot();
        final Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString() : absolute.toString();
    }

    private static void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (RuntimeException | java.io.IOException e) {
            // Directory creation failures surface later through the regular logging path.
        }
    }
}
