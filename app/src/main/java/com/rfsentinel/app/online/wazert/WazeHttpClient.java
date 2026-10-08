package com.rfsentinel.app.online.wazert;

// Replaces the upstream OkHttp-based client of the same name from
// highway-radar-sabre-plus (MIT). Same contract on HttpURLConnection, so the
// app takes no OkHttp dependency. See LICENSE in this directory.

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RF Sentinel replacement for the SABRE Plus OkHttp-based {@code WazeHttpClient}.
 * Same contract — binary/octet-stream POSTs, per-session cookies (login sets
 * {@code Waze-Session-Affinity}, every later command must carry it), three
 * attempts on transport failure — on {@link HttpURLConnection}, so the app takes
 * no OkHttp dependency. Only POST is kept; the tile-server GET is not part of the
 * alert fetch path.
 */
class WazeHttpClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    /** Must exceed the /command long-poll (x-waze-wait-timeout: 10500 ms). */
    private static final int READ_TIMEOUT_MS = 20_000;

    /** Session cookies by name. */
    private final Map<String, String> cookies = new LinkedHashMap<>();

    /** Drop all cookies (login clears them before re-authenticating). */
    void clearCookies() { synchronized (cookies) { cookies.clear(); } }

    static final class HttpResult {
        final int code;
        final byte[] body;
        HttpResult(int code, byte[] body) { this.code = code; this.body = body; }
    }

    HttpResult post(String url, byte[] body, Map<String, String> headers) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return postOnce(url, body, headers);
            } catch (IOException e) {
                last = e;
                if (attempt == 3) break;   // no point sleeping after the final attempt
                try { Thread.sleep(400L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private HttpResult postOnce(String url, byte[] body, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "binary/octet-stream");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            String cookieHeader = cookieHeader();
            if (cookieHeader != null) c.setRequestProperty("Cookie", cookieHeader);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
            int code = c.getResponseCode();
            rememberCookies(c.getHeaderFields());
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] bytes = in == null ? new byte[0] : readAll(in);
            return new HttpResult(code, bytes);
        } finally {
            c.disconnect();
        }
    }

    private String cookieHeader() {
        synchronized (cookies) {
            if (cookies.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }
    }

    /** Keep the name=value pair of every Set-Cookie (attributes are irrelevant: one host, one path). */
    private void rememberCookies(Map<String, List<String>> responseHeaders) {
        if (responseHeaders == null) return;
        for (Map.Entry<String, List<String>> h : responseHeaders.entrySet()) {
            if (h.getKey() == null || !h.getKey().equalsIgnoreCase("Set-Cookie") || h.getValue() == null) continue;
            for (String raw : h.getValue()) {
                if (raw == null) continue;
                int semi = raw.indexOf(';');
                String pair = (semi >= 0 ? raw.substring(0, semi) : raw).trim();
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                synchronized (cookies) { cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim()); }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
