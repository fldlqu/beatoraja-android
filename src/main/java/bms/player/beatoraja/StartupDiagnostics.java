package bms.player.beatoraja;

import java.util.logging.Logger;

/**
 * Opt-in startup timings with fixed labels; no request, response or account data.
 *
 * <p>The trace is disabled unless the {@code beatoraja.startupDiagnostics} system property is set.
 * Measurements only record timing labels and outcomes, so they can be enabled for a single run
 * without leaking user data.</p>
 */
public final class StartupDiagnostics {
    private static final String PROPERTY = "beatoraja.startupDiagnostics";
    private static boolean firstFrame, inputReady;

    public enum Step {
        SYSTEM_FONT, INPUT_DEVICES, SELECT_BAR_INIT, SELECT_BAR_BUILD, SELECT_SKIN_LOAD,
        IR_CHART_CHECK, IR_CHART_DB_LOOKUP, IR_LOGIN, SELECT_FIRST_FRAME, SELECT_INPUT_READY
    }

    public enum Outcome { OK, FAILED, TIMEOUT, INTERRUPTED, SKIPPED }

    private StartupDiagnostics() {
    }

    static void selectionFrame(boolean unlocked) {
        if ((firstFrame && (inputReady || !unlocked)) || !enabled()) return;
        if (!firstFrame) {
            firstFrame = true;
            milestone(Step.SELECT_FIRST_FRAME);
        }
        if (unlocked && !inputReady) {
            inputReady = true;
            milestone(Step.SELECT_INPUT_READY);
        }
    }

    static boolean enabled() { return Boolean.getBoolean(PROPERTY); }

    private static void milestone(Step step) { log(step, null, -1); }

    public static Span measure(Step step) { return enabled() ? new Span(step) : Span.DISABLED; }
    public static Span measure(Step step, boolean include) { return include ? measure(step) : Span.DISABLED; }

    private static void log(Step step, Outcome outcome, long durationMicros) {
        Logger.getGlobal().info("Startup diagnostics: step=" + step
                + " pid=" + android.os.Process.myPid()
                + (outcome == null ? "" : " outcome=" + outcome + " durationUs=" + durationMicros));
    }

    public static final class Span implements AutoCloseable {
        private static final Span DISABLED = new Span(null);
        private final Step step;
        private final long startNanos;
        private Outcome outcome = Outcome.FAILED;

        private Span(Step step) {
            this.step = step;
            startNanos = step == null ? 0 : System.nanoTime();
        }

        public void outcome(Outcome result) { if (step != null) outcome = result; }
        @Override public void close() {
            if (step != null) log(step, outcome, (System.nanoTime() - startNanos) / 1000);
        }
    }
}
