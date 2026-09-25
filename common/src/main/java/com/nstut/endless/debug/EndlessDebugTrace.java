package com.nstut.endless.debug;

import com.nstut.endless.config.EndlessConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Debug-only tracing for sparse high-Y state/render synchronization. */
public final class EndlessDebugTrace {
    private static final String PREFIX = "ENDLESS_DEBUG ";
    private static final Path LOG_FILE = Path.of("logs", "endless-debug.log");
    private static final Map<String, String> LAST_STATE = new ConcurrentHashMap<>();
    private static boolean logInitialized;

    private EndlessDebugTrace() {}

    public static boolean enabled() {
        return EndlessConfig.getInstance().isDebug();
    }

    public static boolean highY(int y) {
        return y < EndlessConfig.DENSE_MIN_BUILD_HEIGHT || y >= EndlessConfig.DENSE_MAX_BUILD_HEIGHT;
    }

    public static void log(String event, String details) {
        if (!enabled()) return;
        emit(PREFIX + event + " " + details);
    }

    /** Emit only when the value for a stable diagnostic key changes. */
    public static void state(String key, String event, String details) {
        if (!enabled()) return;
        String previous = LAST_STATE.put(key, details);
        if (!details.equals(previous)) {
            emit(PREFIX + event + " " + details);
        }
    }

    public static void reset() {
        LAST_STATE.clear();
    }

    private static synchronized void emit(String line) {
        System.out.println(line);
        try {
            if (!logInitialized) {
                Files.createDirectories(LOG_FILE.getParent());
                Files.writeString(LOG_FILE, "# Endless debug trace " + Instant.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                logInitialized = true;
            }
            Files.writeString(LOG_FILE, Instant.now() + " " + line + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Endless: failed to write debug trace: " + e.getMessage());
        }
    }
}
