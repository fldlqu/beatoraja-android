package bms.player.beatoraja.input;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Consumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Application;
import com.badlogic.gdx.backends.android.AndroidApplication;

import bms.player.beatoraja.input.keyboard.KeyboardEventQueue;
import bms.player.beatoraja.input.mouse.MouseMotionQueue;

/**
 * Process-wide input source polled by {@link BMSPlayerInputProcessor}.
 *
 * <p>libGDX cannot replace {@code Gdx.input}, so this class is a normal {@link InputProcessor}
 * registered at the front of the game's {@code InputMultiplexer}. It turns hardware-keyboard
 * callbacks into the per-key FIFO the input polling thread drains, and publishes a thread-safe
 * keyboard/pointer snapshot once per render frame ({@link #update()}); the 1 kHz input-polling
 * thread only ever reads the snapshot arrays, never libGDX input.</p>
 *
 * <p>Touch pointer 0 is exposed as the mouse (position, buttons) so the existing skin/Stage UI
 * keeps working. Touch-to-lane/gameplay mapping is not implemented yet; mouse scratch can be fed
 * from pointer-0 drag deltas when the player enables it.</p>
 */
public final class PollingInput implements InputProcessor {

    private static final int KEY_COUNT = 256;
    private static final int BUTTON_COUNT = 5;

    private static volatile PollingInput current;

    /** The installed input, or null before {@link #install(Application)}. */
    public static PollingInput get() {
        return current;
    }

    /** Replaces the installed input reference; null clears it. */
    public static void set(PollingInput input) {
        current = input;
    }

    private final Thread owner = Thread.currentThread();
    private final LifecycleListener lifecycle = new LifecycleListener() {
        @Override public void pause() { setWindowActive(false); }
        @Override public void resume() { setWindowActive(true); }
        @Override public void dispose() { release(); }
    };
    /** Runs once per libGDX render frame on the GL thread so publication needs no render-loop wiring. */
    private final Runnable framePump = new Runnable() {
        @Override public void run() {
            if (released) return;
            update();
            if (Gdx.app != null) Gdx.app.postRunnable(this);
        }
    };

    /** Raw state changed by InputProcessor callbacks (GL thread). */
    private final AtomicIntegerArray keys = new AtomicIntegerArray(KEY_COUNT);
    private final AtomicIntegerArray buttons = new AtomicIntegerArray(BUTTON_COUNT);
    /** Snapshot published to the polling thread once per frame. */
    private final AtomicIntegerArray publishedKeys = new AtomicIntegerArray(KEY_COUNT);
    private final AtomicIntegerArray publishedButtons = new AtomicIntegerArray(BUTTON_COUNT);

    private final KeyboardEventQueue keyboardEvents = new KeyboardEventQueue();
    private final MouseMotionQueue scratch = new MouseMotionQueue();
    private final boolean[] suppressedKeys = new boolean[KEY_COUNT];

    private volatile int x, y;
    private volatile boolean windowInputActive = true;
    private volatile boolean overlayActive;
    private volatile boolean scratchEnabled;
    private volatile Consumer<Boolean> activityListener;

    // pointer 0 is the mouse; last position is used for mouse-scratch deltas
    private int pointerX, pointerY;
    private int lastScratchX, lastScratchY;
    private boolean overflowReported;
    private boolean released;

    private PollingInput() {
    }

    /**
     * Creates the process input, installs it as {@code Gdx.input}'s input processor, registers it
     * through {@link #set(PollingInput)} and forwards application pause/resume to
     * {@link #setWindowActive(boolean)}. The game's {@code MainController} multiplexes this
     * instance through {@code PollingInput.get()}.
     *
     * @param app the running application, or null when the caller handles lifecycle
     * @return the installed instance
     */
    public static PollingInput install(Application app) {
        PollingInput input = new PollingInput();
        PollingInput previous = current;
        current = input;
        if (previous != null && previous != input) previous.release();
        if (Gdx.input != null) Gdx.input.setInputProcessor(input);
        if (app instanceof AndroidApplication activity) activity.addLifecycleListener(input.lifecycle);
        input.startFramePump();
        return input;
    }

    // ------------------------------------------------------------------ frame publish

    /**
     * Publishes the per-frame keyboard/mouse snapshot. {@link #install(Application)} already
     * drives this once per render frame through {@code Gdx.app.postRunnable}; game code may call it
     * explicitly instead, in which case the automatic publisher is redundant but harmless.
     */
    public void update() {
        if (released) return;
        final boolean focused = windowInputActive && !overlayActive;
        for (int i = 0; i < KEY_COUNT; i++) {
            if (keys.get(i) == 0) suppressedKeys[i] = false;
            boolean pressed = focused && !suppressedKeys[i] && keys.get(i) != 0;
            publishedKeys.set(i, pressed ? 1 : 0);
        }
        keyboardEvents.publish(focused, publishedKeys);
        for (int i = 0; i < BUTTON_COUNT; i++)
            publishedButtons.set(i, focused && buttons.get(i) != 0 ? 1 : 0);
        if (keyboardEvents.isOverflowed() && !overflowReported) {
            overflowReported = true;
            java.util.logging.Logger.getGlobal().severe(
                    "Keyboard event queue overflow; stopping to avoid lost releases or invalid scores");
            if (Gdx.app != null) Gdx.app.exit();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Drives window activity from Activity pause ({@code false}) / resume ({@code true}). */
    public void setWindowActive(boolean active) {
        boolean changed;
        synchronized (this) {
            changed = windowInputActive != active;
            windowInputActive = active;
            if (!active) {
                // No key-up events arrive while paused: drop the held state and clear stale edges.
                for (int i = 0; i < KEY_COUNT; i++) {
                    suppressedKeys[i] = keys.get(i) != 0;
                    keys.set(i, 0);
                    publishedKeys.set(i, 0);
                }
                for (int i = 0; i < BUTTON_COUNT; i++) {
                    buttons.set(i, 0);
                    publishedButtons.set(i, 0);
                }
                keyboardEvents.reset(publishedKeys);
                resetMouseScratchDeltas();
            }
        }
        if (changed) {
            Consumer<Boolean> listener = activityListener;
            if (listener != null) listener.accept(active);
        }
    }

    private void startFramePump() {
        if (Gdx.app != null) Gdx.app.postRunnable(framePump);
    }

    /** Drops the process registration and held state; safe to call more than once. */
    public void release() {
        synchronized (this) {
            if (released) return;
            released = true;
        }
        setWindowActive(false);
        if (current == this) set(null);
    }

    public boolean isWindowInputActive() {
        return windowInputActive;
    }

    public synchronized void setActivityListener(Consumer<Boolean> listener) {
        activityListener = listener;
    }

    public synchronized void clearActivityListener(Consumer<Boolean> listener) {
        if (activityListener == listener) activityListener = null;
    }

    // ------------------------------------------------------------------ keyboard

    private void keyboardEdge(int key, boolean down, long time) {
        if (key < 0 || key >= KEY_COUNT) return;
        if (!down) suppressedKeys[key] = false;
        if (windowInputActive && !overlayActive && !suppressedKeys[key])
            keyboardEvents.offer(key, down, time);
    }

    public void configureKeyboardEvents(int[] assignments) {
        keyboardEvents.configure(assignments, publishedKeys);
    }

    public void resetKeyboardEvents() {
        keyboardEvents.reset(publishedKeys);
    }

    /** Drops held-key state, e.g. after rebinding inputs. */
    public void resetBindingInput() {
        for (int i = 0; i < KEY_COUNT; i++) {
            suppressedKeys[i] = keys.get(i) != 0;
            publishedKeys.set(i, 0);
        }
        for (int i = 0; i < BUTTON_COUNT; i++) publishedButtons.set(i, 0);
        resetMouseScratchDeltas();
    }

    public void setOverlayActive(boolean overlay) {
        overlayActive = overlay;
        resetBindingInput();
    }

    public void suppressHeldConversionKeys() {
        // The system keyboard/IME owns conversion keys; nothing to suppress here.
    }

    public String keyName(int key) {
        return Input.Keys.toString(key);
    }

    public KeyboardEventQueue getKeyboardEvents() {
        return keyboardEvents;
    }

    // ------------------------------------------------------------------ mouse / touch

    public void setMouseScratchEnabled(boolean enabled) {
        scratchEnabled = enabled;
        resetMouseScratchDeltas();
    }

    public void invalidateMouseScratchCenter() {
        // No cursor warping: pointer drags feed the scratch deltas directly.
    }

    public void resetMouseScratchDeltas() {
        scratch.clear();
    }

    public void consumeMouseScratchDeltas(MouseMotionQueue.Batch batch) {
        scratch.drain(batch);
    }

    public boolean isMouseScratchBatchCurrent(MouseMotionQueue.Batch batch) {
        return scratch.isCurrent(batch);
    }

    public long consumeMouseScratchDelta() {
        return scratch.drain();
    }

    public boolean requiresMouseScratchCapture() {
        return false;
    }

    /** Thread-safe pointer snapshot for render-thread consumers. */
    public int getX() { return Thread.currentThread() == owner ? pointerX : x; }

    /** Thread-safe pointer snapshot for render-thread consumers. */
    public int getY() { return Thread.currentThread() == owner ? pointerY : y; }

    public boolean isButtonPressed(int button) {
        return button >= 0 && button < BUTTON_COUNT && buttons.get(button) != 0;
    }

    /** Physical key state from the raw snapshot. */
    public boolean isKeyPressed(int key) {
        return key >= 0 && key < KEY_COUNT && keys.get(key) != 0;
    }

    // ------------------------------------------------------------------ InputProcessor

    @Override public boolean keyDown(int keycode) {
        if (keycode < 0 || keycode >= KEY_COUNT) return false;
        keys.set(keycode, 1);
        keyboardEdge(keycode, true, System.nanoTime());
        return false;
    }

    @Override public boolean keyUp(int keycode) {
        if (keycode < 0 || keycode >= KEY_COUNT) return false;
        keys.set(keycode, 0);
        keyboardEdge(keycode, false, System.nanoTime());
        return false;
    }

    @Override public boolean keyTyped(char character) {
        // Scene2D TextField / music-select search consume characters downstream.
        return false;
    }

    @Override public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (pointer == 0) {
            pointerX = x = screenX;
            pointerY = y = screenY;
            lastScratchX = screenX;
            lastScratchY = screenY;
            if (button >= 0 && button < BUTTON_COUNT) buttons.set(button, 1);
        }
        return false;
    }

    @Override public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (pointer == 0) {
            pointerX = x = screenX;
            pointerY = y = screenY;
            for (int i = 0; i < BUTTON_COUNT; i++) buttons.set(i, 0);
            if (button >= 0 && button < BUTTON_COUNT) buttons.set(button, 0);
        }
        return false;
    }

    @Override public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
        if (pointer == 0) {
            for (int i = 0; i < BUTTON_COUNT; i++) buttons.set(i, 0);
        }
        return false;
    }

    @Override public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (pointer == 0) {
            if (scratchEnabled) {
                int dx = screenX - lastScratchX;
                int dy = screenY - lastScratchY;
                if (dx != 0 || dy != 0) scratch.add(dx, dy);
            }
            lastScratchX = screenX;
            lastScratchY = screenY;
            pointerX = x = screenX;
            pointerY = y = screenY;
        }
        return false;
    }

    @Override public boolean mouseMoved(int screenX, int screenY) {
        pointerX = x = screenX;
        pointerY = y = screenY;
        return false;
    }

    @Override public boolean scrolled(float amountX, float amountY) {
        return false;
    }

    // ------------------------------------------------------------------ diagnostics

    public InputDiagnostics getDiagnostics() {
        return null;
    }

    /** Called from the input thread every poll; no frame pacing to advance here. */
    public void workerPoll(long nanoTime) {
    }
}
