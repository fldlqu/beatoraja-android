package bms.player.beatoraja;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;

import bms.player.beatoraja.input.PollingInput;
import bms.player.beatoraja.ir.IRConnectionManager;
import bms.player.beatoraja.video.FfmpegVideo;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.ScreenUtils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;


/**
 * Debug-only test bridge for headless/on-device verification.
 *
 * <p>Lets a host drive the game without adb input injection:</p>
 * <pre>
 * am broadcast -a bms.player.beatoraja.android.CAPTURE
 * am broadcast -a bms.player.beatoraja.android.KEY --ei key 19 --ez down true
 * am broadcast -a bms.player.beatoraja.android.TAP --ef x 1200 --ef y 700
 * </pre>
 * Commands are queued here and executed on the GL thread ({@link #process()}).
 */
public final class DebugBridge extends BroadcastReceiver {

    public static final String ACTION_CAPTURE = "bms.player.beatoraja.CAPTURE";
    public static final String ACTION_KEY = "bms.player.beatoraja.KEY";
    public static final String ACTION_TAP = "bms.player.beatoraja.TAP";
    public static final String ACTION_QUIT = "bms.player.beatoraja.QUIT";
    public static final String ACTION_SCAN = "bms.player.beatoraja.SCAN";
    public static final String ACTION_DUMP = "bms.player.beatoraja.DUMP";
    /** Diagnostics: probe a video file with the bundled FFmpeg (used while validating .mpg playback). */
    public static final String ACTION_FFMPEG = "bms.player.beatoraja.FFMPEG";

    private static final String TAG = "beatoraja";
    private static final ConcurrentLinkedQueue<Runnable> COMMANDS = new ConcurrentLinkedQueue<>();
    private static volatile Path root;
    private static volatile boolean captureRequested;
    private static volatile Runnable scanSongsAction;
    private static volatile Context appContext;
    private static volatile MainController mainController;

    private DebugBridge() {
    }

    public static void install(Context context, Path dataRoot) {
        root = dataRoot;
        appContext = context.getApplicationContext();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CAPTURE);
        filter.addAction(ACTION_KEY);
        filter.addAction(ACTION_TAP);
        filter.addAction(ACTION_QUIT);
        filter.addAction(ACTION_SCAN);
        filter.addAction(ACTION_DUMP);
        filter.addAction(ACTION_FFMPEG);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(new DebugBridge(), filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(new DebugBridge(), filter);
        }
        Log.i(TAG, "debug bridge installed");
    }

    /** Lets the launcher expose the running controller to the debug bridge (diagnostics only). */
    public static void setMainController(MainController main) {
        mainController = main;
    }

    /** Lets the launcher expose "rescan song database" to the debug bridge. */
    public static void setScanSongsAction(Runnable action) {
        scanSongsAction = action;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case ACTION_CAPTURE -> { captureRequested = true; Log.i(TAG, "capture requested"); }
            case ACTION_KEY -> {
                final int key = intent.getIntExtra("key", -1);
                final boolean down = intent.getBooleanExtra("down", true);
                COMMANDS.add(() -> {
                    // Dispatch through the game's multiplexer so Stage/list/custom skin processors see it too.
                    InputProcessor processor = Gdx.input.getInputProcessor();
                    if (processor == null) processor = PollingInput.get();
                    if (processor != null && key >= 0) {
                        if (down) processor.keyDown(key); else processor.keyUp(key);
                    }
                    Logger.getGlobal().info("debug key " + (down ? "down " : "up ") + key
                            + " via " + (processor == null ? "null" : processor.getClass().getSimpleName()));
                });
            }
            case ACTION_TAP -> {
                final float x = intent.getFloatExtra("x", 0f);
                final float y = intent.getFloatExtra("y", 0f);
                COMMANDS.add(() -> {
                    InputProcessor processor = Gdx.input.getInputProcessor();
                    if (processor == null) processor = PollingInput.get();
                    if (processor != null) {
                        processor.touchDown((int) x, (int) y, 0, 0);
                        processor.touchUp((int) x, (int) y, 0, 0);
                    }
                });
            }
            case ACTION_DUMP -> COMMANDS.add(DebugBridge::dumpState);
            case ACTION_FFMPEG -> {
                final String path = intent.getStringExtra("path");
                COMMANDS.add(() -> {
                    final Context ctx = appContext;
                    FfmpegVideo.Info info = FfmpegVideo.probe(ctx, path == null ? null : Path.of(path));
                    Log.i(TAG, "FFMPEG_AVAILABLE " + FfmpegVideo.isAvailable(ctx) + " info=" + info);
                    if (info == null) return;
                    try {
                        final Process process = FfmpegVideo.startDecoder(ctx,
                                Path.of(path), info.width(), info.height());
                        final java.io.InputStream in = process.getInputStream();
                        final int frameSize = info.width() * info.height() * 4;
                        final byte[] frame = new byte[frameSize];
                        int read = 0;
                        while (read < frameSize) {
                            final int n = in.read(frame, read, frameSize - read);
                            if (n < 0) break;
                            read += n;
                        }
                        Log.i(TAG, "FFMPEG_FRAME bytes=" + read + " head=" + Integer.toHexString(frame[0] & 255)
                                + "," + Integer.toHexString(frame[1] & 255) + "," + Integer.toHexString(frame[2] & 255));
                        process.destroy();
                    } catch (Throwable e) {
                        Log.w(TAG, "FFMPEG_DECODE_FAILED " + e);
                    }
                });
            }
            case ACTION_SCAN -> COMMANDS.add(() -> {
                Runnable scan = scanSongsAction;
                if (scan != null) scan.run();
            });
            case ACTION_QUIT -> COMMANDS.add(() -> Gdx.app.exit());
            default -> { }
        }
    }

    private static void dumpState() {
        try {
            final MainController main = mainController;
            if (main == null) {
                Logger.getGlobal().info("debug dump: main controller not ready");
                return;
            }
            final StringBuilder line = new StringBuilder("debug dump state=")
                    .append(main.getCurrentState() == null ? "null" : main.getCurrentState().type)
                    .append(" pollingInput=").append(PollingInput.get() == null ? "null" : "present");
            final bms.player.beatoraja.input.BMSPlayerInputProcessor input = main.getInputProcessor();
            if (input != null) {
                line.append(" startPressed=").append(input.startPressed())
                        .append(" selectPressed=").append(input.isSelectPressed());
                line.append(" keys[0]=").append(input.getKeyState(0))
                        .append(" keys[1]=").append(input.getKeyState(1));
                final var rook = input.getKeyBoardInputProcesseor();
                if (rook != null) line.append(" effectiveKeys=").append(java.util.Arrays.toString(
                        java.util.Arrays.copyOf(rook.getEffectiveKeysForDiagnostics(),
                                Math.min(10, rook.getEffectiveKeysForDiagnostics().length))));
            }
            if (main.getSongDatabase() != null) {
                line.append(" songCount=").append(main.getSongDatabase().getSongCountForDiagnostics());
            }
            if (main.getMusicSelector() != null) {
                final var manager = main.getMusicSelector().getBarManager();
                line.append(" bars=").append(manager.getSelected() == null ? "null" : manager.getSelected().getTitle())
                        .append(" folders=").append(manager.getDirectory().size);
            }
            line.append(" ir=").append(IRConnectionManager.describeAvailableConnections());
            if (input != null) line.append(" windowInputActive=").append(input.isWindowInputActive());
            if (PollingInput.get() != null) {
                line.append(" pollingPressed[44]=").append(PollingInput.get().isKeyPressed(44))
                        .append(" pollingPressed[66]=").append(PollingInput.get().isKeyPressed(66))
                        .append(" pollingWindowActive=").append(PollingInput.get().isWindowInputActive())
                        .append(" queuePeek44=").append(PollingInput.get().getKeyboardEvents().peek(44))
                        .append(" queuePeek66=").append(PollingInput.get().getKeyboardEvents().peek(66));
            }
            line.append(" inputThreads=");
            Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().toLowerCase().contains("input") || t.getName().toLowerCase().contains("poll"))
                    .forEach(t -> line.append(t.getName()).append(':').append(t.getState()).append(','));
            line.append(" declaredState=").append(main.getCurrentState() == null ? "?" : main.getCurrentState().type);
            Logger.getGlobal().info(line.toString());
        } catch (RuntimeException e) {
            Logger.getGlobal().log(java.util.logging.Level.WARNING, "debug dump failed", e);
        }
    }

    /** Executes queued debug commands and captures a clean framebuffer when requested. */
    public static void process() {
        Runnable command;
        while ((command = COMMANDS.poll()) != null) {
            try {
                command.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "debug command failed", e);
            }
        }
        if (captureRequested) {
            captureRequested = false;
            capture();
        }
    }

    private static void capture() {
        final Path dataRoot = root;
        if (dataRoot == null) return;
        try {
            int width = Gdx.graphics.getBackBufferWidth();
            int height = Gdx.graphics.getBackBufferHeight();
            Pixmap pixmap = ScreenUtils.getFrameBufferPixmap(0, 0, width, height);
            Pixmap flipped = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = pixmap.getPixel(x, height - 1 - y);
                    flipped.drawPixel(x, y, pixel);
                }
            }
            pixmap.dispose();
            File dir = dataRoot.resolve("screenshots").toFile();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);
            File out = new File(dir, "capture_" + System.currentTimeMillis() + ".png");
            PixmapIO.writePNG(Gdx.files.absolute(out.getAbsolutePath()), flipped);
            flipped.dispose();
            Log.i(TAG, "CAPTURE_SAVED " + out.getAbsolutePath());
        } catch (RuntimeException | java.io.IOException e) {
            Log.e(TAG, "capture failed", e);
        }
    }
}
