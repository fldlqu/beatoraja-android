package bms.player.beatoraja.input.text;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.TextInputListener;

/**
 * Text-input transport between a skin editor and the system text dialog.
 *
 * <p>Focus opens the system text dialog through {@link Gdx#input}; the confirmed text is delivered
 * to the owning {@link TextInputSession} as ordered session events, so callers keep reading the
 * editor state from the session. Inline IME composition is not implemented: there is no preedit
 * stream and the dialog delivers the whole document once when confirmed.</p>
 */
public final class TextInputBridge {

    /** Commit / cancel identities carried by the session event stream. */
    public enum Command { SUBMIT, CANCEL }

    /** Session event sink; may be called from the system dialog callback thread. */
    public interface Sink {
        /** Full committed document, not an insertion delta; excludes preedit text. */
        void committed(long session, TextInputSession.EditorState state);
        /** Preedit replaces the current selection visually, without modifying committed text. */
        void preedit(long session, String text, int caret);
        void cancelled(long session);
        /** Consumed key identity must survive composition-end/commit ordering. */
        void command(long session, Command command, boolean consumedByIme);
        /** Sent only after finish has resolved composition and delivered final committed text. */
        void finished(long session);
    }

    private static final String DIALOG_TITLE = "Input";
    private static final String DIALOG_HINT = "";

    private long session;
    private Sink sink;

    /** True when a text dialog can be shown; false only before libGDX input exists (tooling). */
    public boolean isAvailable() {
        return Gdx.input != null && Gdx.app != null;
    }

    public void begin(long session, TextInputSession.EditorState initial, Sink sink) {
        if (!isAvailable()) return;
        this.session = session;
        this.sink = sink;
        // TODO(unimplemented): inline IME composition is not implemented.
        Gdx.app.postRunnable(() -> {
            if (this.session != session) return;
            Gdx.input.getTextInput(new TextInputListener() {
                @Override public void input(String text) {
                    sink.committed(session, new TextInputSession.EditorState(text, text.length(), text.length()));
                    sink.command(session, Command.SUBMIT, false);
                }
                @Override public void canceled() {
                    sink.cancelled(session);
                    sink.command(session, Command.SUBMIT, false);
                }
            }, DIALOG_TITLE, initial.text(), DIALOG_HINT);
        });
    }

    /** No live editor synchronization: the dialog owns the text until it is confirmed. */
    public void update(long session, TextInputSession.EditorState state) {
    }

    /** No caret transport: the dialog is a separate window. */
    public void caret(long session, double x, double y, double width, double height,
            double clientWidth, double clientHeight) {
    }

    /** No owner-thread work is needed; dialog callbacks feed the session directly. */
    public void poll(long session) {
    }

    /** TODO(unimplemented): inline IME composition is not implemented. */
    public void confirmComposition(long session) {
    }

    /** TODO(unimplemented): inline IME composition is not implemented. */
    public void cancelComposition(long session) {
    }

    /** Resolves the finish transaction started by {@link TextInputSession#requestFinish()}. */
    public void finish(long session) {
        if (session == this.session) {
            Sink current = sink;
            if (current != null) current.finished(session);
        }
    }

    /** Detaches session callbacks; late dialog callbacks are ignored by the session id check. */
    public void close(long session) {
        if (session == this.session) {
            this.session = 0;
            sink = null;
        }
    }
}
