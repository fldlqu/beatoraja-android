package bms.player.beatoraja.video;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal bridge to the bundled FFmpeg executable.
 *
 * <p>Android's platform players do not decode MPEG-1 program streams ({@code .mpg}, common for old
 * BGA movies), so a small stripped Android/arm64 FFmpeg build is shipped as a native executable and
 * used only for those files. Frames are decoded in a child process and streamed back as raw BGRA,
 * which is the byte order the libGDX pixmap/texture upload path consumes.</p>
 */
public final class FfmpegVideo {

    private static final Logger LOG = Logger.getGlobal();
    private static final String BINARY_NAME = "libffmpeg.so";
    private static final Pattern VIDEO_LINE = Pattern.compile(
            "Video:\\s*([^,\\n]+),.*?,\\s*(\\d+)\\s*x\\s*(\\d+)[^,\\n]*,.*?,\\s*([0-9.]+)\\s+fps",
            Pattern.DOTALL);

    private FfmpegVideo() {
    }

    public record Info(int width, int height, float fps) {
    }

    private static File binary(Context context) {
        // The bundled executable is built against Android API 28; older devices keep MediaPlayer only.
        if (Build.VERSION.SDK_INT < 28) return null;
        if (context == null || context.getApplicationInfo() == null) return null;
        final String dir = context.getApplicationInfo().nativeLibraryDir;
        if (dir == null) return null;
        final File file = new File(dir, BINARY_NAME);
        return file.isFile() && file.canExecute() ? file : null;
    }

    public static boolean isAvailable(Context context) {
        return binary(context) != null;
    }

    private static String binaryPath(Context context) {
        final File file = binary(context);
        return file == null ? null : file.getAbsolutePath();
    }

    /** Reads resolution and frame rate from FFmpeg's stream header output. */
    public static Info probe(Context context, Path path) {
        final String binary = binaryPath(context);
        if (binary == null || path == null) return null;
        final List<String> command = List.of(binary, "-hide_banner", "-nostdin", "-i",
                path.toAbsolutePath().toString());
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final String output = readAll(process.getInputStream());
            process.waitFor(5, TimeUnit.SECONDS);
            final Matcher matcher = VIDEO_LINE.matcher(output);
            if (!matcher.find()) {
                LOG.warning("FFmpeg: cannot parse video info for " + path + ": " + firstLine(output));
                return null;
            }
            final int width = Integer.parseInt(matcher.group(2));
            final int height = Integer.parseInt(matcher.group(3));
            float fps = 30f;
            try {
                fps = Float.parseFloat(matcher.group(4));
            } catch (NumberFormatException ignored) {
            }
            if (width <= 0 || height <= 0) return null;
            return new Info(width, height, fps > 1f && fps < 121f ? fps : 30f);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warning("FFmpeg: probe failed for " + path + " (" + e + ")");
            return null;
        }
    }

    /** Starts decoding to raw RGBA frames on stdout. The caller owns the process. */
    public static Process startDecoder(Context context, Path path, int width, int height) throws IOException {
        return startDecoder(context, path, width, height, 0d);
    }

    /** Starts decoding from {@code startSeconds} to raw RGBA frames on stdout. */
    public static Process startDecoder(Context context, Path path, int width, int height, double startSeconds)
            throws IOException {
        final String binary = binaryPath(context);
        if (binary == null) throw new IOException("bundled FFmpeg executable is unavailable");
        final List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("-v");
        command.add("error");
        command.add("-nostdin");
        if (startSeconds > 0.05d) {
            command.add("-ss");
            command.add(String.format(java.util.Locale.ROOT, "%.3f", startSeconds));
        }
        command.add("-i");
        command.add(path.toAbsolutePath().toString());
        command.add("-an");
        command.add("-f");
        command.add("rawvideo");
        command.add("-pix_fmt");
        command.add("bgra");
        command.add("-s");
        command.add(width + "x" + height);
        command.add("-");
        final Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        // Drain stderr so a decoder warning can never fill the pipe and block playback.
        final Thread drain = new Thread(() -> {
            try (InputStream error = process.getErrorStream()) {
                final byte[] buffer = new byte[4096];
                while (error.read(buffer) >= 0) {
                    // Discard; only errors are requested via -v error.
                }
            } catch (IOException ignored) {
            }
        }, "ffmpeg-stderr");
        drain.setDaemon(true);
        drain.start();
        return process;
    }

    /** Frame size as sent by {@link #startDecoder}. Keep even dimensions for common encoders. */
    public static int even(int value) {
        return Math.max(2, value - (value % 2));
    }

    private static String readAll(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static String firstLine(String value) {
        final int newline = value.indexOf('\n');
        return (newline < 0 ? value : value.substring(0, newline)).trim();
    }
}
