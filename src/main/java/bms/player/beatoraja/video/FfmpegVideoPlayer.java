package bms.player.beatoraja.video;

import android.content.Context;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Software playback of MPEG-1 program streams through the bundled FFmpeg executable.
 *
 * <p>The decoder runs in a child process and writes raw RGBA frames to stdout. A worker thread paces
 * those frames to the stream frame rate and hands the newest one to the GL thread, which uploads it
 * into a regular texture. Audio is intentionally not decoded: BMS keysounds carry the music.</p>
 */
final class FfmpegVideoPlayer {

    private static final Logger LOG = Logger.getGlobal();

    private final Context context;
    private final Path path;
    private static final int MAX_WIDTH = 1280;
    private static final int MAX_HEIGHT = 720;

    private final int width;
    private final int height;
    private final float fps;
    private final int frameSize;

    private final Object lock = new Object();
    private Process process;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean disposed;
    private volatile boolean looping = true;
    private volatile long startMicros;
    private volatile long playStartNanos;

    private byte[] pendingFrame;
    private long frameVersion;
    private long consumedVersion = -1;
    private byte[] spareFrame;

    private Texture texture;
    private Pixmap pixmap;

    FfmpegVideoPlayer(Context context, Path path, FfmpegVideo.Info info) throws IOException {
        this.context = context;
        this.path = path;
        this.fps = info.fps();
        final float scale = Math.min(1f, Math.min((float) MAX_WIDTH / info.width(),
                (float) MAX_HEIGHT / info.height()));
        this.width = FfmpegVideo.even(Math.max(2, Math.round(info.width() * scale)));
        this.height = FfmpegVideo.even(Math.max(2, Math.round(info.height() * scale)));
        this.frameSize = width * height * 4;
        LOG.info("Video: FFmpeg decoder for " + path + " (" + info.width() + "x" + info.height()
                + " -> " + width + "x" + height + " @" + fps + "fps)");
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    synchronized void play(boolean loop) {
        play(loop, 0L);
    }

    synchronized void play(boolean loop, long startMicros) {
        if (disposed) return;
        looping = loop;
        this.startMicros = startMicros;
        if (running) return;
        playStartNanos = System.nanoTime();
        running = true;
        worker = new Thread(this::decodeLoop, "ffmpeg-video");
        worker.setDaemon(true);
        worker.start();
    }

    void pause() {
        final Process current;
        final Thread thread;
        synchronized (this) {
            running = false;
            current = process;
            process = null;
            thread = worker;
            worker = null;
        }
        if (current != null) current.destroy();
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Called on the GL thread from the render loop. */
    Texture getTexture() {
        if (disposed) return texture;
        final long version;
        final byte[] frame;
        synchronized (lock) {
            version = frameVersion;
            frame = pendingFrame;
        }
        if (frame == null) return texture;
        if (version == consumedVersion && texture != null) return texture;
        if (texture == null) {
            texture = new Texture(width, height, Pixmap.Format.RGBA8888);
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            LOG.info("Video: FFmpeg texture " + width + "x" + height + " for " + path);
        }
        if (pixmap != null) {
            pixmap.getPixels().clear();
            pixmap.getPixels().put(frame);
            pixmap.getPixels().position(0);
            texture.draw(pixmap, 0, 0);
        }
        consumedVersion = version;
        return texture;
    }

    void dispose() {
        disposed = true;
        pause();
        final Texture current = texture;
        texture = null;
        if (current != null && com.badlogic.gdx.Gdx.app != null) {
            com.badlogic.gdx.Gdx.app.postRunnable(current::dispose);
        }
        final Pixmap currentPixmap = pixmap;
        pixmap = null;
        if (currentPixmap != null) currentPixmap.dispose();
    }

    private void decodeLoop() {
        boolean firstProcess = true;
        long frameIndex = 0;
        while (running && !disposed) {
            final Process current;
            try {
                final double startSeconds = firstProcess ? startMicros / 1_000_000d : 0d;
                if (!firstProcess) {
                    // A looped movie restarts its timeline together with the new decoder process.
                    playStartNanos = System.nanoTime();
                    frameIndex = 0;
                }
                firstProcess = false;
                current = FfmpegVideo.startDecoder(context, path, width, height, startSeconds);
            } catch (IOException e) {
                LOG.warning("Video: FFmpeg start failed for " + path + " (" + e + ")");
                break;
            }
            synchronized (this) {
                if (!running || disposed) {
                    current.destroy();
                    break;
                }
                process = current;
            }
            try {
                final InputStream in = current.getInputStream();
                final long frameInterval = Math.max(1L, (long) (1_000_000_000L / fps));
                while (running && !disposed) {
                    final byte[] buffer;
                    synchronized (lock) {
                        buffer = spareFrame;
                        spareFrame = null;
                    }
                    final byte[] frame = buffer != null && buffer.length == frameSize
                            ? buffer : new byte[frameSize];
                    if (!readFully(in, frame)) break;
                    final long now = System.nanoTime();
                    // Frames decoded during process startup are dropped so the first displayed frame
                    // corresponds to the wall-clock position of the play request, keeping video in
                    // step with the already-running music.
                    final long desired = (long) ((now - playStartNanos) * fps / 1_000_000_000d);
                    if (frameIndex < desired) {
                        frameIndex++;
                        continue;
                    }
                    final long target = playStartNanos + (long) ((frameIndex + 1) * 1_000_000_000d / fps);
                    final long delay = target - System.nanoTime();
                    if (delay > 0) {
                        try {
                            Thread.sleep(Math.max(1, delay / 1_000_000L));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    synchronized (lock) {
                        if (pendingFrame != null && pendingFrame.length == frameSize) spareFrame = pendingFrame;
                        pendingFrame = frame;
                        frameVersion++;
                    }
                    frameIndex++;
                }
            } catch (IOException e) {
                if (!disposed) LOG.warning("Video: FFmpeg read failed for " + path + " (" + e + ")");
            } finally {
                current.destroy();
                synchronized (this) {
                    if (process == current) process = null;
                }
            }
            if (!looping || disposed) break;
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] frame) throws IOException {
        int read = 0;
        while (read < frame.length) {
            final int n = in.read(frame, read, frame.length - read);
            if (n < 0) return false;
            read += n;
        }
        return true;
    }
}
