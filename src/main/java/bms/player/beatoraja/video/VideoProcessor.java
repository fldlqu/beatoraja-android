package bms.player.beatoraja.video;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.logging.Logger;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;

import com.badlogic.gdx.backends.android.AndroidApplication;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;

import bms.player.beatoraja.song.SongResource;

/**
 * Movie playback for BGA and skin movies.
 *
 * <p>{@link MediaPlayer} decodes the file into an external OES {@link SurfaceTexture}; for every new
 * frame this processor copies the GPU frame into a normal RGBA {@link Texture} through a small
 * framebuffer pass. The rest of the game keeps consuming an ordinary Texture, so no SpriteBatch
 * shader changes are needed. The copy runs on the GL thread inside {@link #getFrame(long)} and
 * restores the previous framebuffer and viewport, which makes it safe to call while a skin batches
 * sprites.</p>
 *
 * <p>Limitations: video audio is muted (BMS keysounds carry the music), playback is real time without
 * frame-accurate seeking, and the copy target is capped at 1280x720 RGBA to bound memory usage.</p>
 */
public final class VideoProcessor {

    private static final Logger LOG = Logger.getGlobal();
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GL_VIEWPORT = 0x0BA2;
    private static final int MAX_WIDTH = 1280;
    private static final int MAX_HEIGHT = 720;

    private static final String VERTEX_SHADER =
            "attribute vec4 a_position;\n"
            + "attribute vec2 a_texCoord;\n"
            + "uniform mat4 u_stMatrix;\n"
            + "varying vec2 v_texCoord;\n"
            + "void main() {\n"
            + "  gl_Position = a_position;\n"
            + "  v_texCoord = (u_stMatrix * vec4(a_texCoord, 0.0, 1.0)).xy;\n"
            + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 v_texCoord;\n"
            + "uniform samplerExternalOES u_texture;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(u_texture, v_texCoord);\n"
            + "}\n";

    private final Object lock = new Object();
    private final Matrix4 transform = new Matrix4();
    private final float[] transformValues = new float[16];
    private final IntBuffer intScratch = BufferUtils.newIntBuffer(16);

    private SongResource pendingResource;
    private MediaPlayer player;
    private FfmpegVideoPlayer nativePlayer;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private String movieName = "";

    private int oesTextureId;
    private int framebuffer;
    private int program;
    private int stMatrixLocation = -1;
    private int textureLocation = -1;
    private int positionLocation = -1;
    private int texCoordLocation = -1;
    private Texture targetTexture;
    private FloatBuffer quadVertices;
    private FloatBuffer quadTexCoords;
    private final FloatBuffer matrixBuffer = BufferUtils.newFloatBuffer(16);

    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile boolean prepared;
    private volatile boolean startWhenPrepared;
    private volatile boolean frameAvailable;
    private volatile boolean playing;
    private volatile boolean looping = true;
    private volatile boolean disposed;
    private long requestedTimeMicros;
    private boolean firstFrameRendered;

    public VideoProcessor() {
    }

    public void create(SongResource resource) {
        if (resource == null) return;
        synchronized (lock) {
            if (disposed || pendingResource != null || player != null) return;
            pendingResource = resource;
            movieName = resource.displayPath();
        }
        // GL objects (OES texture, FBO, shader) must be created on the GL thread; callers create video
        // processors from skin/BGA loading which is not always the render thread. The first getFrame()
        // call (always on the render thread) completes the setup.
    }

    /** Creates GL objects and the MediaPlayer on the render thread. */
    private void ensurePlayer() {
        synchronized (lock) {
            if (disposed || player != null || pendingResource == null) return;
            final SongResource resource = pendingResource;
            pendingResource = null;
            try {
                final java.nio.file.Path path = resolvePath(resource);
                if (path == null) {
                    LOG.warning("Video: resource cannot be materialized: " + movieName);
                    return;
                }
                if (isMpegProgramStream(path) && startNativePlayer(path)) return;
                ensureGlResources();
                if (program == 0) {
                    LOG.warning("Video: GL setup failed, video disabled: " + movieName);
                    return;
                }
                startPlayer(path);
            } catch (Throwable e) {
                LOG.warning("Video: cannot open " + movieName + " (" + e + ")");
                releasePlayer();
            }
        }
    }

    private java.nio.file.Path resolvePath(SongResource resource) {
        return resource.localPath().orElseGet(() -> {
            try {
                return resource.materialize();
            } catch (IOException e) {
                return null;
            }
        });
    }

    private boolean startNativePlayer(java.nio.file.Path path) {
        final Context context = Gdx.app instanceof AndroidApplication application
                ? application.getContext() : null;
        if (context == null || !FfmpegVideo.isAvailable(context)) {
            LOG.warning("Video: bundled FFmpeg unavailable for " + movieName);
            return false;
        }
        final FfmpegVideo.Info info = FfmpegVideo.probe(context, path);
        if (info == null) {
            LOG.warning("Video: FFmpeg cannot read " + movieName);
            return false;
        }
        try {
            nativePlayer = new FfmpegVideoPlayer(context, path, info);
            prepared = true;
            videoWidth = nativePlayer.width();
            videoHeight = nativePlayer.height();
            if (startWhenPrepared) {
                startWhenPrepared = false;
                nativePlayer.play(looping, requestedTimeMicros);
                playing = true;
            }
            LOG.info("Video ready (FFmpeg): " + movieName);
            return true;
        } catch (Throwable e) {
            LOG.warning("Video: FFmpeg player failed for " + movieName + " (" + e + ")");
            if (nativePlayer != null) {
                nativePlayer.dispose();
                nativePlayer = null;
            }
            return false;
        }
    }

    private static boolean isMpegProgramStream(java.nio.file.Path path) {
        final String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".mpg") || name.endsWith(".mpeg") || name.endsWith(".ps")
                || name.endsWith(".vob") || name.endsWith(".m1v") || name.endsWith(".mpv");
    }

    private void startPlayer(java.nio.file.Path path) throws IOException {
        player = new MediaPlayer();
        player.setVolume(0f, 0f);
        surfaceTexture = new SurfaceTexture(oesTextureId);
        surfaceTexture.setOnFrameAvailableListener(st -> frameAvailable = true);
        surface = new Surface(surfaceTexture);
        player.setSurface(surface);
        player.setDataSource(path.toAbsolutePath().toString());
        player.setOnVideoSizeChangedListener((mp, width, height) -> {
            videoWidth = width;
            videoHeight = height;
            if (surfaceTexture != null && width > 0 && height > 0) {
                surfaceTexture.setDefaultBufferSize(width, height);
            }
            LOG.info("Video size: " + movieName + " " + width + "x" + height);
        });
        player.setOnPreparedListener(mp -> {
            synchronized (lock) {
                if (disposed) return;
                prepared = true;
                try {
                    mp.setLooping(looping);
                } catch (IllegalStateException ignored) {
                }
                if (startWhenPrepared) {
                    startWhenPrepared = false;
                    try {
                        mp.start();
                        playing = true;
                    } catch (IllegalStateException ignored) {
                    }
                }
                LOG.info("Video ready: " + movieName);
            }
        });
        player.setOnErrorListener((mp, what, extra) -> {
            LOG.warning("Video error: " + movieName + " what=" + what + " extra=" + extra);
            return true;
        });
        player.prepareAsync();
    }

    private void ensureGlResources() {
        if (program != 0) return;
        final GL20 gl = Gdx.gl;
        program = createProgram(gl);
        if (program == 0) {
            LOG.warning("Video: external-texture shader unavailable, video disabled");
            return;
        }
        stMatrixLocation = gl.glGetUniformLocation(program, "u_stMatrix");
        textureLocation = gl.glGetUniformLocation(program, "u_texture");
        positionLocation = gl.glGetAttribLocation(program, "a_position");
        texCoordLocation = gl.glGetAttribLocation(program, "a_texCoord");
        if (stMatrixLocation < 0 || textureLocation < 0 || positionLocation < 0 || texCoordLocation < 0) {
            LOG.warning("Video: shader attribute lookup failed (" + stMatrixLocation + "," + textureLocation
                    + "," + positionLocation + "," + texCoordLocation + ")");
            gl.glDeleteProgram(program);
            program = 0;
            return;
        }
        intScratch.clear();
        gl.glGenTextures(1, intScratch);
        oesTextureId = intScratch.get(0);
        gl.glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        gl.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_LINEAR);
        gl.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_LINEAR);
        gl.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE);
        gl.glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
        intScratch.clear();
        gl.glGenFramebuffers(1, intScratch);
        framebuffer = intScratch.get(0);

        quadVertices = BufferUtils.newFloatBuffer(8);
        quadVertices.put(new float[] {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
        quadVertices.flip();
        quadTexCoords = BufferUtils.newFloatBuffer(8);
        quadTexCoords.put(new float[] {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f});
        quadTexCoords.flip();
        LOG.info("Video: GL resources ready");
    }

    /** Compiles and links the OES sampling program with full driver logs. Returns 0 on failure. */
    private int createProgram(GL20 gl) {
        final int vertex = compileShader(gl, GL20.GL_VERTEX_SHADER, VERTEX_SHADER, "vertex");
        if (vertex == 0) return 0;
        final int fragment = compileShader(gl, GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER, "fragment");
        if (fragment == 0) {
            gl.glDeleteShader(vertex);
            return 0;
        }
        final int program = gl.glCreateProgram();
        gl.glAttachShader(program, vertex);
        gl.glAttachShader(program, fragment);
        gl.glLinkProgram(program);
        intScratch.clear();
        gl.glGetProgramiv(program, GL20.GL_LINK_STATUS, intScratch);
        final boolean linked = intScratch.get(0) != 0;
        if (!linked) {
            LOG.warning("Video: program link failed: " + gl.glGetProgramInfoLog(program));
            gl.glDeleteProgram(program);
        }
        gl.glDeleteShader(vertex);
        gl.glDeleteShader(fragment);
        return linked ? program : 0;
    }

    private int compileShader(GL20 gl, int type, String source, String name) {
        final int shader = gl.glCreateShader(type);
        gl.glShaderSource(shader, source);
        gl.glCompileShader(shader);
        intScratch.clear();
        gl.glGetShaderiv(shader, GL20.GL_COMPILE_STATUS, intScratch);
        if (intScratch.get(0) == 0) {
            LOG.warning("Video: " + name + " shader compile failed: " + gl.glGetShaderInfoLog(shader));
            gl.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    public void setFrameskip(int frameskip) {
        // MediaPlayer renders in real time; frame skipping is not applicable.
    }

    public Texture getFrame(long time) {
        requestedTimeMicros = time;
        if (disposed) return null;
        if (player == null && nativePlayer == null && pendingResource != null) {
            ensurePlayer();
        }
        if (nativePlayer != null) {
            return nativePlayer.getTexture();
        }
        if (player == null || !prepared || program == 0) return null;
        if (frameAvailable) {
            frameAvailable = false;
            try {
                renderPendingFrame();
            } catch (Throwable e) {
                LOG.warning("Video frame render failed: " + movieName + " (" + e + ")");
            }
        }
        return firstFrameRendered ? targetTexture : null;
    }

    private void renderPendingFrame() {
        if (surfaceTexture == null) return;
        final GL20 gl = Gdx.gl;
        if (targetTexture == null) {
            if (videoWidth <= 0 || videoHeight <= 0) {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(transformValues);
                return;
            }
            final float scale = Math.min(1f,
                    Math.min((float) MAX_WIDTH / videoWidth, (float) MAX_HEIGHT / videoHeight));
            final int width = Math.max(2, Math.round(videoWidth * scale));
            final int height = Math.max(2, Math.round(videoHeight * scale));
            targetTexture = new Texture(width, height, Pixmap.Format.RGBA8888);
            targetTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            intScratch.clear();
            gl.glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            gl.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL20.GL_TEXTURE_2D,
                    targetTexture.getTextureObjectHandle(), 0);
            final int status = gl.glCheckFramebufferStatus(GL_FRAMEBUFFER);
            gl.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (status != GL20.GL_FRAMEBUFFER_COMPLETE) {
                LOG.warning("Video: framebuffer incomplete (0x" + Integer.toHexString(status) + ")");
                targetTexture.dispose();
                targetTexture = null;
                return;
            }
            LOG.info("Video target texture: " + width + "x" + height + " for " + movieName);
        }

        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(transformValues);

        intScratch.clear();
        gl.glGetIntegerv(GL_FRAMEBUFFER_BINDING, intScratch);
        final int previousFramebuffer = intScratch.get(0);
        intScratch.clear();
        gl.glGetIntegerv(GL_VIEWPORT, intScratch);
        final int previousX = intScratch.get(0);
        final int previousY = intScratch.get(1);
        final int previousWidth = intScratch.get(2);
        final int previousHeight = intScratch.get(3);

        gl.glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        gl.glViewport(0, 0, targetTexture.getWidth(), targetTexture.getHeight());
        gl.glDisable(GL20.GL_BLEND);
        gl.glUseProgram(program);
        matrixBuffer.clear();
        matrixBuffer.put(transformValues);
        matrixBuffer.flip();
        gl.glUniformMatrix4fv(stMatrixLocation, 1, false, matrixBuffer);
        gl.glUniform1i(textureLocation, 0);
        gl.glActiveTexture(GL20.GL_TEXTURE0);
        gl.glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        gl.glEnableVertexAttribArray(positionLocation);
        gl.glVertexAttribPointer(positionLocation, 2, GL20.GL_FLOAT, false, 0, quadVertices);
        gl.glEnableVertexAttribArray(texCoordLocation);
        gl.glVertexAttribPointer(texCoordLocation, 2, GL20.GL_FLOAT, false, 0, quadTexCoords);
        gl.glDrawArrays(GL20.GL_TRIANGLE_STRIP, 0, 4);
        gl.glDisableVertexAttribArray(positionLocation);
        gl.glDisableVertexAttribArray(texCoordLocation);
        gl.glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
        gl.glUseProgram(0);
        gl.glBindFramebuffer(GL_FRAMEBUFFER, previousFramebuffer);
        gl.glViewport(previousX, previousY, previousWidth, previousHeight);
        firstFrameRendered = true;
    }

    public void play(long time, boolean loop) {
        requestedTimeMicros = time;
        if (disposed) return;
        synchronized (lock) {
            looping = loop;
            if (nativePlayer != null) {
                nativePlayer.play(loop, time);
                playing = true;
                return;
            }
            if (player == null || !prepared) {
                startWhenPrepared = true;
                return;
            }
            try {
                player.setLooping(loop);
                if (!playing) {
                    final int duration = player.getDuration();
                    final int target = (int) Math.max(0, Math.min(Integer.MAX_VALUE, time / 1000));
                    int position = 0;
                    try {
                        position = player.getCurrentPosition();
                    } catch (IllegalStateException ignored) {
                    }
                    if (duration <= 0 || target < position - 500 || target > position + 2000
                            || position >= duration - 100) {
                        player.seekTo(target);
                    }
                    player.start();
                    playing = true;
                }
            } catch (IllegalStateException e) {
                LOG.warning("Video play failed: " + movieName + " (" + e.getMessage() + ")");
            }
        }
    }

    public void stop() {
        if (disposed) return;
        synchronized (lock) {
            if (nativePlayer != null) {
                nativePlayer.pause();
                playing = false;
                return;
            }
            if (player != null) {
                try {
                    if (playing) player.pause();
                } catch (IllegalStateException ignored) {
                }
            }
            playing = false;
        }
    }

    public long getRetainedMovieBytes() {
        final Texture texture = targetTexture;
        return texture == null ? 0 : (long) texture.getWidth() * texture.getHeight() * 4;
    }

    public void dispose() {
        final int oes;
        final int fbo;
        final Texture texture;
        final int appliedProgram;
        synchronized (lock) {
            if (disposed) return;
            disposed = true;
            playing = false;
            startWhenPrepared = false;
            pendingResource = null;
            releasePlayer();
            oes = oesTextureId;
            fbo = framebuffer;
            texture = targetTexture;
            appliedProgram = program;
            oesTextureId = 0;
            framebuffer = 0;
            targetTexture = null;
            program = 0;
        }
        final Runnable cleanup = () -> {
            if (texture != null) texture.dispose();
            if (appliedProgram != 0) Gdx.gl.glDeleteProgram(appliedProgram);
            if (Gdx.gl != null && Gdx.app != null) {
                if (oes != 0) {
                    intScratch.clear();
                    intScratch.put(0, oes);
                    Gdx.gl.glDeleteTextures(1, intScratch);
                }
                if (fbo != 0) {
                    intScratch.clear();
                    intScratch.put(0, fbo);
                    Gdx.gl.glDeleteFramebuffers(1, intScratch);
                }
            }
        };
        if (Gdx.app != null) {
            Gdx.app.postRunnable(cleanup);
        } else {
            cleanup.run();
        }
    }

    private void releasePlayer() {
        if (nativePlayer != null) {
            nativePlayer.dispose();
            nativePlayer = null;
        }
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
            player = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        prepared = false;
        frameAvailable = false;
        firstFrameRendered = false;
    }
}
