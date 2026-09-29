package dev.crossbridge.velocity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class VelocitySecurity {
    private static final class SourceState {
        long windowStart;
        int count;
        int invalid;
        long lockedUntil;
    }

    private final Map<String, SourceState> states = new ConcurrentHashMap<>();
    private final int maxMessagesPerSecond;
    private final int invalidThreshold;
    private final long lockoutMillis;

    VelocitySecurity(int maxMessagesPerSecond, int invalidThreshold, long lockoutSeconds) {
        this.maxMessagesPerSecond = Math.max(1, maxMessagesPerSecond);
        this.invalidThreshold = Math.max(1, invalidThreshold);
        this.lockoutMillis = Math.max(1L, lockoutSeconds) * 1000L;
    }

    boolean allowed(String source) {
        SourceState state = states.computeIfAbsent(source.toLowerCase(), ignored -> new SourceState());
        long now = System.currentTimeMillis();
        if (now < state.lockedUntil) return false;
        if (now - state.windowStart >= 1000L) {
            state.windowStart = now;
            state.count = 0;
        }
        return ++state.count <= maxMessagesPerSecond;
    }

    boolean invalid(String source) {
        SourceState state = states.computeIfAbsent(source.toLowerCase(), ignored -> new SourceState());
        state.invalid++;
        if (state.invalid >= invalidThreshold) {
            state.lockedUntil = System.currentTimeMillis() + lockoutMillis;
            state.invalid = 0;
            state.count = 0;
            state.windowStart = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    void valid(String source) {
        SourceState state = states.get(source.toLowerCase());
        if (state != null) state.invalid = 0;
    }

    int trackedSources() { return states.size(); }
}
