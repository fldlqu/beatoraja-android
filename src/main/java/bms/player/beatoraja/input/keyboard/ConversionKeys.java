package bms.player.beatoraja.input.keyboard;

import com.badlogic.gdx.Input.Keys;

/**
 * Shared JIS conversion-key aliases used by the key-config UI and shortcut validation.
 *
 * <p>libGDX has no key codes for the Japanese Muhenkan/Henkan keys. They get private aliases above
 * {@link Keys#MAX_KEYCODE}; the values are part of the persisted key bindings, so they must stay
 * stable.</p>
 */
public final class ConversionKeys {

    /** Muhenkan (無変換). */
    public static final int NONCONVERT = 244;
    /** Henkan (変換). */
    public static final int CONVERT = 245;

    private ConversionKeys() {
    }

    public static boolean isConversionKey(int key) {
        return key == NONCONVERT || key == CONVERT;
    }
}
