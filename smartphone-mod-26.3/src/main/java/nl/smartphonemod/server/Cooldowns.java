package nl.smartphonemod.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Simple per-player rate limiting so a modified client cannot flood the database. */
public final class Cooldowns {
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    private Cooldowns() {}

    /** Returns true (and records the use) if at least {@code millis} passed since the last use of {@code action}. */
    public static boolean tryUse(UUID player, String action, long millis) {
        long now = System.currentTimeMillis();
        String key = player + "|" + action;
        Long last = LAST.get(key);
        if (last != null && now - last < millis) return false;
        LAST.put(key, now);
        return true;
    }

    static void forget(UUID player) {
        String prefix = player + "|";
        LAST.keySet().removeIf(k -> k.startsWith(prefix));
    }

    static void clear() {
        LAST.clear();
    }
}
