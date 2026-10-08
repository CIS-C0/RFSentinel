package com.rfsentinel.app.online.wazert;

// Vendored unmodified from highway-radar-sabre-plus (MIT) except for the
// package declaration. See LICENSE in this directory.

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent uuid-keyed alert cache with soft-delete, matching the caching
 * behavior of the original wzsabre plugin (merge, soft-delete, expiry, filter).
 *
 * <p>WHY this exists: the Waze RT {@code /command} endpoint is session-stateful.
 * It returns each alert as an {@code AddAlertAction} only ONCE per session, then a
 * {@code "RmAlert,<uuid>"} removal line when the alert clears. The previous source
 * replaced its cache with every response, so after the first query the cache went
 * near-empty and alerts vanished from Highway Radar mid-drive. This class instead
 * accumulates adds and soft-deletes removals (kept {@link #SOFT_DELETE_MS} before
 * purge), so the merged view stays complete.
 */
final class WazeAlertCache {
    /** wzsabre uses PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS (5 minutes). */
    static final long SOFT_DELETE_MS = 5 * 60_000L;

    private final Map<String, WazeAlert> alertCache = new LinkedHashMap<>();
    private final Map<String, Long> softDeleted = new LinkedHashMap<>();

    /** Apply one query's deltas: upsert adds, soft-delete removals still cached. */
    synchronized void submit(AlertQueryResult result) {
        for (WazeAlert a : result.newAlerts) {
            if (a.uuid != null && a.uuid.length() != 0) {
                alertCache.put(a.uuid, a);
                softDeleted.remove(a.uuid);
            }
        }
        long now = System.currentTimeMillis();
        for (String id : result.removedIds) {
            if (alertCache.containsKey(id) && !softDeleted.containsKey(id)) {
                softDeleted.put(id, now);
            }
        }
    }

    private void purgeExpiredSoftDeletes() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> e : softDeleted.entrySet()) {
            if (now - e.getValue() >= SOFT_DELETE_MS) expired.add(e.getKey());
        }
        for (String id : expired) {
            alertCache.remove(id);
            softDeleted.remove(id);
        }
    }

    private boolean isSoftDeleteExpired(String uuid) {
        Long t = softDeleted.get(uuid);
        return t != null && System.currentTimeMillis() - t >= SOFT_DELETE_MS;
    }

    /**
     * Live view of the cache: purge expired soft-deletes, then return every cached
     * alert that is not currently soft-deleted.
     */
    synchronized List<WazeAlert> snapshot() {
        purgeExpiredSoftDeletes();
        List<WazeAlert> out = new ArrayList<>();
        for (WazeAlert a : alertCache.values()) {
            if (!(softDeleted.containsKey(a.uuid) && !isSoftDeleteExpired(a.uuid))) {
                out.add(a);
            }
        }
        return out;
    }

    synchronized void clear() {
        alertCache.clear();
        softDeleted.clear();
    }

    synchronized int size() {
        return alertCache.size();
    }
}
