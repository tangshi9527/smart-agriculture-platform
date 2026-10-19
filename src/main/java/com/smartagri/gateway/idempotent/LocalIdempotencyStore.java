package com.smartagri.gateway.idempotent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存幂等存储（单机版，亦为 Redis 不可用时的兜底）。
 */
public class LocalIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

    @Override
    public String putIfAbsent(String key, String status, long ttlSeconds) {
        Entry existing = map.get(key);
        if (existing != null && !existing.isExpired()) {
            return existing.status;
        }
        Entry prev = map.putIfAbsent(key, new Entry(status, null,
                System.currentTimeMillis() + ttlSeconds * 1000L));
        return prev == null ? null : prev.status;
    }

    @Override
    public void setCompleted(String key, String body, long ttlSeconds) {
        map.put(key, new Entry(STATUS_COMPLETED, body,
                System.currentTimeMillis() + ttlSeconds * 1000L));
    }

    @Override
    public String getCompletedBody(String key) {
        Entry e = map.get(key);
        if (e == null || e.isExpired()) {
            return null;
        }
        return STATUS_COMPLETED.equals(e.status) ? e.body : null;
    }

    @Override
    public void clear(String key) {
        map.remove(key);
    }

    private static final class Entry {
        final String status;
        final String body;
        final long expireAt;

        Entry(String status, String body, long expireAt) {
            this.status = status;
            this.body = body;
            this.expireAt = expireAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
