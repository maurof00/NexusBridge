package dev.crossbridge.bungee;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class ProxySecurity {
    private final int maxPerSecond;
    private final int strikeLimit;
    private final long lockoutMillis;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> strikes = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();

    ProxySecurity(int maxPerSecond, int strikeLimit, long lockoutSeconds) {
        this.maxPerSecond = Math.max(1, maxPerSecond);
        this.strikeLimit = Math.max(1, strikeLimit);
        this.lockoutMillis = Math.max(1, lockoutSeconds) * 1000L;
    }

    boolean allowed(String source) {
        long now = System.currentTimeMillis();
        Long lock = lockedUntil.get(source);
        if (lock != null) {
            if (lock > now) return false;
            lockedUntil.remove(source, lock);
        }
        return buckets.computeIfAbsent(source, k -> new Bucket()).tryAcquire(now, maxPerSecond);
    }

    void valid(String source) {
        strikes.remove(source);
    }

    boolean invalid(String source) {
        int count = strikes.computeIfAbsent(source, k -> new AtomicInteger()).incrementAndGet();
        if (count >= strikeLimit) {
            lockedUntil.put(source, System.currentTimeMillis() + lockoutMillis);
            strikes.remove(source);
            return true;
        }
        return false;
    }

    long lockedRemaining(String source) {
        Long until = lockedUntil.get(source);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    int trackedSources() { return buckets.size(); }

    private static final class Bucket {
        private final AtomicLong window = new AtomicLong();
        private final AtomicInteger count = new AtomicInteger();

        synchronized boolean tryAcquire(long now, int max) {
            long start = window.get();
            if (start == 0 || now - start >= 1000) {
                window.set(now);
                count.set(0);
            }
            return count.incrementAndGet() <= max;
        }
    }
}
