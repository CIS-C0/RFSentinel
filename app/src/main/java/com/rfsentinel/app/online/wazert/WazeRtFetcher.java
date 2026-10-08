package com.rfsentinel.app.online.wazert;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;
import com.rfsentinel.app.util.SecureStore;

import java.util.ArrayList;
import java.util.List;

/**
 * RF Sentinel's driver for the vendored Waze RT protocol layer: owns the anonymous
 * account, the session lifecycle, the alert cache and the rate limits, and hands
 * back just the POLICE alerts near a point.
 *
 * <p>This class is RF Sentinel's own (the classes it drives are vendored from
 * highway-radar-sabre-plus — see LICENSE in this directory). It is a slimmed
 * equivalent of that project's WazeProtocolSource: no Highway Radar plumbing, no
 * reporting, no tile decoding, no keepalive thread.
 *
 * <p><b>What talking to Waze costs the user.</b> The RT protocol is the one the
 * Waze app itself speaks. Using it means registering an anonymous account with
 * Waze (username/password minted by Waze, stored encrypted on-device) and sending
 * a position with each query. That position is first rounded to a 1 km grid
 * ({@link #snapToGrid}), so Waze only ever learns which ~1 km cell the phone is in
 * (never closer than about 500 m), and every command (login, handshake, query
 * boxes) uses the rounded point, not the real one. That is a real disclosure, so the backend is opt-in and
 * the Settings screen says so plainly before the user turns it on.
 *
 * <p><b>Rate limits.</b> Waze caps anonymous registrations per device per day, so
 * a minted account is persisted and reused, registrations are counted against
 * {@link WazeConstants#MAX_ACCOUNTS_PER_DAY} in a rolling 24 h window, and a
 * rejected account backs off exponentially (30 s → 10 min) instead of spinning the
 * register→login loop. A generic failure (5xx, network flap) backs off 15 s.
 *
 * <p>Every public method is synchronized: a poll can overlap a Settings action
 * ("forget account") and both touch the session and the cache.
 */
public final class WazeRtFetcher {

    private static final String TAG = "WazeRtFetcher";
    private static final String PREFS = "rfsentinel_wazert";
    private static final String KEY_ACCOUNT = "wazert_account";      // SecureStore (encrypted)
    private static final String KEY_REG_COUNT = "reg_count";         // plain prefs (not secret)
    private static final String KEY_REG_WINDOW = "reg_window_start";

    /** Never sweep a box tighter than this: the user is moving, and a 2 km box
     *  would leave nothing ahead of them by the next poll. Matches upstream. */
    private static final double MIN_QUERY_RADIUS_M = 8000.0;
    /** Progressively smaller boxes defeat the server's viewport-size thinning. */
    private static final int SHRINK_STEPS = 5;
    /** Waze is only told which grid cell of this size you are in. A grid, not random jitter: a phone
     *  that stays put sends the same value every time, so many polls cannot be averaged back to it. */
    private static final double GRID_M = 1000.0;
    /** A cell centre is at most this far (diagonally) from the true position, so every query is widened by it. */
    private static final double SNAP_MARGIN_M = 750.0;
    /** The smallest query box is never narrower than this (before the 0.75 shrink): it must still hold the true position. */
    private static final double MIN_INNER_BOX_M = 1000.0;
    private static final long QUERY_BUDGET_MS = 10_000L;
    /** A brand-new account's first command often draws an in-band 504 "Retry";
     *  it succeeds on the next attempt, so absorb it rather than lose a poll. */
    private static final int HANDSHAKE_ATTEMPTS = 3;

    private static final long BACKOFF_BASE_MS = 30_000L;
    private static final long BACKOFF_MAX_MS = 10 * 60_000L;
    private static final long GENERIC_FAIL_BACKOFF_MS = 15_000L;
    private static final long DAY_MS = 24 * 3600_000L;

    /** One Waze police report, reduced to what RF Sentinel scores. */
    public static final class PoliceAlert {
        public final String uuid;
        public final String subtype;    // e.g. POLICE_WITH_MOBILE_CAMERA; "" when unspecified
        public final double lat;
        public final double lon;
        public final long pubMillis;
        public final int thumbsUp;      // crowd corroboration; 0 when absent
        public final String type;       // Waze alert type name, e.g. POLICE, ACCIDENT, HAZARD
        public final String street;     // null when Waze gave none
        public final String city;       // null when Waze gave none

        public PoliceAlert(String uuid, String subtype, double lat, double lon, long pubMillis, int thumbsUp, String type) {
            this(uuid, subtype, lat, lon, pubMillis, thumbsUp, type, null, null);
        }

        public PoliceAlert(String uuid, String subtype, double lat, double lon, long pubMillis, int thumbsUp, String type,
                           String street, String city) {
            this.uuid = uuid;
            this.subtype = subtype;
            this.lat = lat;
            this.lon = lon;
            this.pubMillis = pubMillis;
            this.thumbsUp = thumbsUp;
            this.type = type;
            this.street = street;
            this.city = city;
        }
    }

    /** Bumped by {@link #forgetStoredAccount}: every running fetcher drops its session when it sees a new value. */
    private static final java.util.concurrent.atomic.AtomicInteger ACCOUNT_EPOCH = new java.util.concurrent.atomic.AtomicInteger();

    private final Context ctx;
    private final String region;
    private int seenEpoch = ACCOUNT_EPOCH.get();
    private final WazeAlertCache cache = new WazeAlertCache();

    private WazeSession session;
    private int consecutiveRejections = 0;
    private long backoffUntilMs = 0L;

    private final WazeHttpClient http;

    public WazeRtFetcher(Context context, String region) {
        this(context, region, new WazeHttpClient());
    }

    /** Test seam: the transport is supplied by the caller (a fake server). */
    WazeRtFetcher(Context context, String region, WazeHttpClient http) {
        this.ctx = context.getApplicationContext();
        this.region = region;
        this.http = http;
    }

    /**
     * Blocking: ensure a session, sweep the area, and return the cached POLICE
     * alerts. Throws on failure (the caller turns that into a status message);
     * the thrown message is safe to show, it never contains credentials.
     */
    public synchronized List<PoliceAlert> fetchAlertsNear(double lat, double lon, double radiusMeters, java.util.Set<String> types)
            throws Exception {
        Diagnostics.lastAttemptAt = System.currentTimeMillis();
        Diagnostics.host = WazeConstants.rtHost(region);
        try {
            List<PoliceAlert> out = fetchLocked(lat, lon, radiusMeters, types);
            Diagnostics.lastOkAt = System.currentTimeMillis();
            Diagnostics.lastError = "";
            Diagnostics.alertsInCache = cache.size();
            return out;
        } catch (Exception e) {
            Diagnostics.lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw e;
        }
    }

    /** What the status screen shows: the last attempt, and the rounded position that went out with it. */
    public static final class Diagnostics {
        private Diagnostics() {}
        public static volatile long lastAttemptAt;
        public static volatile long lastOkAt;
        public static volatile String lastError = "";
        public static volatile String host = "";
        public static volatile double sentLat = Double.NaN;
        public static volatile double sentLon = Double.NaN;
        public static volatile int alertsInCache;
    }

    /** True once an anonymous account is stored (Settings > Waze status). */
    public static boolean hasStoredAccount(Context context) {
        String blob = SecureStore.INSTANCE.get(context.getApplicationContext(), KEY_ACCOUNT);
        return blob != null && !blob.isEmpty();
    }

    /** Anonymous accounts registered in the last 24 hours, and the most Waze allows per day. */
    public static int accountsMadeToday(Context context) {
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long start = p.getLong(KEY_REG_WINDOW, 0L);
        if (start == 0L || System.currentTimeMillis() - start >= DAY_MS) return 0;
        return p.getInt(KEY_REG_COUNT, 0);
    }

    public static int dailyAccountLimit() { return WazeConstants.MAX_ACCOUNTS_PER_DAY; }

    private List<PoliceAlert> fetchLocked(double lat, double lon, double radiusMeters, java.util.Set<String> types)
            throws Exception {
        int epoch = ACCOUNT_EPOCH.get();
        if (epoch != seenEpoch) {   // the account was forgotten in Settings: start over with a fresh one
            seenEpoch = epoch;
            session = null;
            cache.clear();
            consecutiveRejections = 0;
            backoffUntilMs = 0L;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < backoffUntilMs) {
            throw new WazeExceptions.WazeOperationException(
                    "backing off " + ((backoffUntilMs - now) / 1000) + "s after a rejected session");
        }
        try {
            double[] at = snapToGrid(lat, lon);
            Diagnostics.sentLat = at[0];
            Diagnostics.sentLon = at[1];
            queryArea(at[0], at[1], Math.max(radiusMeters + SNAP_MARGIN_M, MIN_QUERY_RADIUS_M));
            consecutiveRejections = 0;
            backoffUntilMs = 0L;
            return filter(cache.snapshot(), types);
        } catch (WazeExceptions.AccountRejectedException e) {
            // Waze disowned this account (they get purged). Drop it so the next
            // attempt mints a fresh one, and back off so a persistent rejection
            // cannot burn the daily registration cap in seconds.
            forgetAccount();
            session = null;
            consecutiveRejections++;
            setBackoff();
            throw e;
        } catch (WazeExceptions.SessionExpiredException e) {
            session = null;   // credentials are still good, just re-login next poll
            throw e;
        } catch (Exception e) {
            if (backoffUntilMs <= SystemClock.elapsedRealtime()) {
                backoffUntilMs = SystemClock.elapsedRealtime() + GENERIC_FAIL_BACKOFF_MS;
            }
            throw e;
        }
    }

    /** Forget the session (not the account) so the next fetch logs in again. */
    public synchronized void reset() {
        session = null;
        cache.clear();
    }

    // ── privacy: what position Waze gets ─────────────────────────────────────

    /**
     * Rounds a position to the centre of its ~1 km grid cell: {@code [lat, lon]}. The cell is the
     * same for every point inside it, so Waze cannot tell where in the cell the phone is, and the
     * rounding error is at most about 500 m per axis.
     */
    static double[] snapToGrid(double lat, double lon) {
        double latStep = GRID_M / WazeConstants.M_PER_DEG_LAT;
        double snappedLat = Math.round(lat / latStep) * latStep;
        // Longitude degrees shrink towards the poles; use the cell row's own latitude so the whole row shares one step.
        double lonStep = GRID_M / Math.max(WazeConstants.mPerDegLon(snappedLat), 1000.0);
        double snappedLon = Math.round(lon / lonStep) * lonStep;
        return new double[]{snappedLat, snappedLon};
    }

    /** How many shrinking boxes to query: stop before one gets narrower than {@link #MIN_INNER_BOX_M}. */
    static int boxSteps(double radiusM) {
        int steps = 1;
        while (steps < SHRINK_STEPS && radiusM / (1L << steps) >= MIN_INNER_BOX_M) steps++;
        return steps;
    }

    /** Settings "forget account": wipes the stored account and makes every running fetcher drop its session too. */
    public static void forgetStoredAccount(Context context) {
        SecureStore.INSTANCE.put(context.getApplicationContext(), KEY_ACCOUNT, "");   // empty clears the entry
        ACCOUNT_EPOCH.incrementAndGet();
    }

    // ── session + query ──────────────────────────────────────────────────────

    private void queryArea(double lat, double lon, double radiusMeters) throws Exception {
        if (session == null) session = restoreOrCreateSession();
        boolean wasUnregistered = session.getCredentials() == null;
        if (wasUnregistered && !canRegisterToday()) {
            throw new WazeExceptions.WazeOperationException(
                    "Waze's daily anonymous-account limit is reached — try again tomorrow");
        }

        long sessionBefore = session.currentServerSessionId();
        WazeProto.Batch handshake = prepareWithRetry(lat, lon);

        // Credentials exist now: persist immediately so a failure in the box loop
        // below cannot lose a freshly minted account and force a wasteful
        // re-register on the next poll.
        if (wasUnregistered) {
            noteRegistration();
            saveAccount(session.getCredentials(), session.getDevice());
            Log.i(TAG, "Registered a new anonymous Waze account");
        }

        // A new server session re-sends every active alert but sends no removal for
        // alerts that cleared while we were logged out, so a stale cache would show
        // ghosts. Defer the clear until the first box succeeds, so a failure right
        // after a re-login keeps serving the previous cache instead of blanking.
        boolean sessionChanged = session.currentServerSessionId() != sessionBefore;
        boolean cleared = false;

        double[][] boxes = GeoBoxes.shrinkingBoxes(lon, lat, radiusMeters, boxSteps(radiusMeters));
        long deadline = SystemClock.elapsedRealtime() + QUERY_BUDGET_MS;
        for (double[] box : boxes) {
            if (SystemClock.elapsedRealtime() >= deadline) break;   // best-effort, like the official client
            WazeProto.Batch batch = session.queryBox(GeoBoxes.shrink(box, 0.75));
            if (sessionChanged && !cleared) {
                cache.clear();
                cleared = true;
            }
            if (handshake != null) {
                // The handshake's own MapDisplayed box is a real viewport query and the
                // server will not send those alerts again this session, so merge it.
                cache.submit(new AlertQueryResult(
                        WazeRtCodec.parseAlerts(handshake), WazeRtCodec.parseRemovedAlertIds(handshake)));
                handshake = null;
            }
            cache.submit(new AlertQueryResult(
                    WazeRtCodec.parseAlerts(batch), WazeRtCodec.parseRemovedAlertIds(batch)));
        }
    }

    private WazeProto.Batch prepareWithRetry(double lat, double lon) throws Exception {
        WazeExceptions.WazeOperationException last = null;
        for (int attempt = 1; attempt <= HANDSHAKE_ATTEMPTS; attempt++) {
            try {
                return session.prepareForArea(lat, lon);
            } catch (WazeExceptions.WazeOperationException e) {
                last = e;
                Log.w(TAG, "Waze handshake attempt " + attempt + " failed: " + e.getMessage());
                if (attempt == HANDSHAKE_ATTEMPTS) break;
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    /** Only the alert types the user chose (police by default); the caller names them by Waze type. */
    private static List<PoliceAlert> filter(List<WazeAlert> all, java.util.Set<String> types) {
        List<PoliceAlert> out = new ArrayList<>();
        for (WazeAlert a : all) {
            if (a.type == null || !types.contains(a.type)) continue;
            if (a.uuid == null || a.uuid.isEmpty()) continue;
            out.add(new PoliceAlert(a.uuid, a.subtype == null ? "" : a.subtype,
                    a.lat, a.lon, a.pubMillis, a.nThumbsUp == null ? 0 : a.nThumbsUp, a.type, a.street, a.city));
        }
        return out;
    }

    // ── account persistence ──────────────────────────────────────────────────

    private WazeSession restoreOrCreateSession() {
        String blob = SecureStore.INSTANCE.get(ctx, KEY_ACCOUNT);
        if (blob == null || blob.isEmpty()) return new WazeSession(region, DeviceIdentity.random(), null, http);
        try {
            JSONObject o = new JSONObject(blob);
            WazeCredentials creds = new WazeCredentials(o.getString("community"), o.getString("secret"));
            DeviceIdentity device = new DeviceIdentity(
                    o.getString("manufacturer"), o.getString("model"), o.getString("os"),
                    o.getInt("w"), o.getInt("h"), o.getString("installation_id"));
            return new WazeSession(region, device, creds, http);
        } catch (Exception e) {
            Log.w(TAG, "Stored Waze account unreadable, registering a new one: " + e.getMessage());
            return new WazeSession(region, DeviceIdentity.random(), null, http);
        }
    }

    private void saveAccount(WazeCredentials c, DeviceIdentity d) {
        if (c == null || d == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("community", c.community);
            o.put("secret", c.secret);
            o.put("manufacturer", d.manufacturer);
            o.put("model", d.model);
            o.put("os", d.osVersion);
            o.put("w", d.screenW);
            o.put("h", d.screenH);
            o.put("installation_id", d.installationId);
            SecureStore.INSTANCE.put(ctx, KEY_ACCOUNT, o.toString());
        } catch (Exception e) {
            Log.w(TAG, "Could not persist the Waze account: " + e.getMessage());
        }
    }

    /** Wipe the stored anonymous account. Also the Settings "forget" action. */
    public synchronized void forgetAccount() {
        SecureStore.INSTANCE.put(ctx, KEY_ACCOUNT, "");   // empty clears the entry
        cache.clear();
        session = null;
    }

    public synchronized boolean hasAccount() {
        String blob = SecureStore.INSTANCE.get(ctx, KEY_ACCOUNT);
        return blob != null && !blob.isEmpty();
    }

    // ── registration cap + backoff ───────────────────────────────────────────

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean windowRolledOver() {
        long start = prefs().getLong(KEY_REG_WINDOW, 0L);
        return start == 0L || System.currentTimeMillis() - start >= DAY_MS;
    }

    private boolean canRegisterToday() {
        if (windowRolledOver()) return true;
        return prefs().getInt(KEY_REG_COUNT, 0) < WazeConstants.MAX_ACCOUNTS_PER_DAY;
    }

    private void noteRegistration() {
        SharedPreferences p = prefs();
        if (windowRolledOver()) {
            p.edit().putLong(KEY_REG_WINDOW, System.currentTimeMillis()).putInt(KEY_REG_COUNT, 1).apply();
        } else {
            p.edit().putInt(KEY_REG_COUNT, p.getInt(KEY_REG_COUNT, 0) + 1).apply();
        }
    }

    private void setBackoff() {
        int step = Math.min(Math.max(consecutiveRejections, 1), 5);
        long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * (1L << (step - 1)));
        backoffUntilMs = SystemClock.elapsedRealtime() + delay;
        Log.w(TAG, "Waze RT backing off " + (delay / 1000) + "s");
    }
}
