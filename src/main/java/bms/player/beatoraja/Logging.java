package bms.player.beatoraja;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import android.util.Log;

/** Routes java.util.logging (the logging API used by the game core) to the system log and a log file. */
public final class Logging {

    private static final String TAG = "beatoraja";
    private static final int MAX_CHUNK = 3500;

    private Logging() {
    }

    public static void install(java.nio.file.Path logDirectory) {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
            handler.close();
        }
        root.addHandler(new LogcatHandler());
        try {
            java.nio.file.Files.createDirectories(logDirectory);
            java.util.logging.FileHandler file = new java.util.logging.FileHandler(
                    logDirectory.resolve("beatoraja.log").toString(), 4 * 1024 * 1024, 2, true);
            file.setEncoding("UTF-8");
            file.setFormatter(new java.util.logging.SimpleFormatter());
            file.setLevel(Level.ALL);
            root.addHandler(file);
            Log.i(TAG, "file logging enabled: " + logDirectory.resolve("beatoraja.log"));
        } catch (java.io.IOException | RuntimeException e) {
            Log.e(TAG, "file logging unavailable", e);
        }
        root.setLevel(Level.INFO);
        // Game classes use Logger.getGlobal(); keep it attached to the configured root handlers.
        Logger.getGlobal().setUseParentHandlers(true);
    }

    private static final class LogcatHandler extends Handler {
        @Override
        public void publish(LogRecord record) {
            if (record == null) return;
            Level level = record.getLevel();
            int priority = level.intValue() >= Level.SEVERE.intValue() ? Log.ERROR
                    : level.intValue() >= Level.WARNING.intValue() ? Log.WARN
                    : level.intValue() >= Level.INFO.intValue() ? Log.INFO : Log.DEBUG;
            String message = record.getMessage();
            if (record.getParameters() != null && message != null) {
                try {
                    message = java.text.MessageFormat.format(message, record.getParameters());
                } catch (RuntimeException ignored) {
                    // Keep the raw message when it is not a MessageFormat pattern.
                }
            }
            String line = message == null ? "" : message;
            Throwable thrown = record.getThrown();
            for (int start = 0; start < line.length(); start += MAX_CHUNK) {
                Log.println(priority, TAG, line.substring(start, Math.min(line.length(), start + MAX_CHUNK)));
            }
            if (thrown != null) {
                Log.println(Log.ERROR, TAG, Log.getStackTraceString(thrown));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
