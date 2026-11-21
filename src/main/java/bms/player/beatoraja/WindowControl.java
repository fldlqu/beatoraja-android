package bms.player.beatoraja;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

/**
 * Window state and cursor control used by the game state machine.
 *
 * <p>The window is owned by the operating system and is always full screen; the methods only expose
 * focus/hover state and keep the state machine API stable.</p>
 */
public final class WindowControl {

    public void create(Config config) {
    }

    public boolean isSwitching() {
        return false;
    }

    public boolean toggle() {
        return true;
    }

    public boolean isWindowFocused() {
        return Gdx.app != null && Gdx.graphics != null;
    }

    public boolean isMouseHovered() {
        return true;
    }

    public void setCursorCatched(boolean catched) {
    }

    public void describeSkinProjection(SpriteBatch batch, int width, int height) {
    }
}
