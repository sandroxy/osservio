package com.sandrox.osservio.store;

import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicitly requested, synthetic in-memory load. Measurements are not device performance gates. */
@RunWith(AndroidJUnit4.class)
public final class CaptureLoadTest {
    private static final int TEXT_LIMIT = 65536;
    private static final String TEXT = new String(new char[8192]).replace('\0', 'x');
    private static final byte[] BODY = TEXT.getBytes(StandardCharsets.UTF_8);
    private static final List<HTTPHeader> HEADERS = Arrays.asList(
        new HTTPHeader("Authorization", "Bearer " + String.join("", Collections.nCopies(1200, "x"))), new HTTPHeader("Content-Type", "text/plain"));
    private static final long[] LATENCY_UPPER_US = {10, 50, 100, 500, 1000, 5000, 20000};

    @Test public void sustainedCaptureAndQueryMeasurements() throws Exception {
        String argument = InstrumentationRegistry.getArguments().getString("osservio.loadSeconds");
        Assume.assumeTrue("Set osservio.loadSeconds explicitly for resource measurements", argument != null);
        int seconds = Integer.parseInt(argument);
        assertTrue("Duration must be 5...900 seconds per mode", seconds >= 5 && seconds <= 900);
        for (String mode : new String[] {"off", "baseline", "detailed", "queried"}) measure(mode, seconds);
    }

    private void measure(String mode, int seconds) throws Exception {
        LogDetails context = LogDetails.publicValues(new LogValue[] {LogValue.integer(9007199254740993L), LogValue.bool(false)},
            new LogField[] {new LogField("operation", LogValue.string("load"))}, new LogError("SyntheticError", null, null, null, "Fixture.run\nFixture.tick"), null);
        CaptureController capture = new CaptureController();
        MemoryLogStore logs = new MemoryLogStore(200, TEXT_LIMIT);
        MemoryBreadcrumbStore crumbs = new MemoryBreadcrumbStore(200, TEXT_LIMIT);
        MemoryHTTPStore requests = new MemoryHTTPStore(100, TEXT_LIMIT, 32768);
        MemoryWebSocketStore sockets = new MemoryWebSocketStore(200, TEXT_LIMIT, 16384);
        LogRecorder log = new LogRecorder(logs, capture);
        BreadcrumbRecorder breadcrumb = new BreadcrumbRecorder(crumbs, capture);
        HTTPRecorder http = new HTTPRecorder(requests, capture,
            new HTTPPrivacyPolicy(true, null, HTTPPrivacyPolicy.BodyPolicy.PUBLIC_UTF8, 1024));
        WebSocketRecorder ws = new WebSocketRecorder(sockets, capture,
            new WebSocketPrivacyPolicy(true, null, true, 1024));
        if (!mode.equals("off")) assertTrue(capture.setBaselineEnabled(true));
        if (mode.equals("detailed") || mode.equals("queried")) capture.startDetailed(1000L * (seconds + 10));
        Map<String, Long> outcomes = new LinkedHashMap<>();
        long[] latency = new long[LATENCY_UPPER_US.length + 1];
        JSONArray memory = new JSONArray();
        long started = SystemClock.elapsedRealtimeNanos(), cpu = Process.getElapsedCpuTime();
        long deadline = started + seconds * 1_000_000_000L, nextQuery = started, nextSample = started;
        long ticks = 0, maximumNs = 0, queries = 0;
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
            // A fixed offered load: five mixed observations, then 10 ms idle. No catch-up burst.
            for (int index = 0; index < 5; index++) {
                long before = SystemClock.elapsedRealtimeNanos();
                count(outcomes, "log", log.record(ticks % 50 == 0 ? LogLevel.ERROR : LogLevel.INFO, "load",
                    ticks % 2 == 0 ? LogMessage.publicText(TEXT) : LogMessage.privateText("synthetic-secret"), ticks % 50 == 0 ? context : null));
                count(outcomes, "breadcrumb", breadcrumb.record(BreadcrumbEvent.Kind.HOST_MARKER, "load.tick"));
                HTTPRecorder.BeginResult request = http.begin("POST", "https://example.com/private?secret=value", HEADERS);
                count(outcomes, "http.begin", request.result);
                if (request.token != null) {
                    count(outcomes, "http.response", http.receiveResponse(request.token, 200, HEADERS, false));
                    count(outcomes, "http.body", http.observeBody(request.token, HTTPRecorder.Direction.RESPONSE,
                        BODY.length, limit -> Arrays.copyOf(BODY, limit)));
                    count(outcomes, "http.finish", http.finish(request.token, HTTPEvent.State.COMPLETED, null, true, true));
                }
                WebSocketRecorder.BeginResult connection = ws.begin("wss://example.com/private?secret=value", HEADERS, WebSocketEvent.Source.HOST);
                count(outcomes, "ws.begin", connection.result);
                if (connection.token != null) {
                    count(outcomes, "ws.text", ws.text(connection.token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, TEXT));
                    count(outcomes, "ws.close", ws.lifecycle(connection.token, WebSocketEvent.Kind.CLOSED, Collections.emptyList(), null, 1000, null));
                }
                long elapsed = SystemClock.elapsedRealtimeNanos() - before;
                int bucket = 0;
                while (bucket < LATENCY_UPPER_US.length && elapsed > LATENCY_UPPER_US[bucket] * 1000) bucket++;
                latency[bucket]++; maximumNs = Math.max(maximumNs, elapsed); ticks++;
            }
            long now = SystemClock.elapsedRealtimeNanos();
            if (mode.equals("queried") && now >= nextQuery) {
                checkBounds(logs, crumbs, requests, sockets); queries++;
                nextQuery = now + 250_000_000L;
            }
            if (now >= nextSample) {
                Debug.MemoryInfo info = new Debug.MemoryInfo(); Debug.getMemoryInfo(info);
                Runtime runtime = Runtime.getRuntime();
                memory.put(new JSONObject().put("elapsedMs", (now - started) / 1_000_000)
                    .put("pssKiB", info.getTotalPss()).put("javaHeapBytes", runtime.totalMemory() - runtime.freeMemory())
                    .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize()));
                nextSample = now + 1_000_000_000L;
            }
            Thread.sleep(10);
        }
        checkBounds(logs, crumbs, requests, sockets);
        assertEquals(0, ws.status().activeConnections);
        for (HTTPEvent event : requests.snapshot().events) {
            assertEquals(HTTPEvent.State.COMPLETED, event.state);
            assertEquals("https://example.com/private?secret=value", event.url);
            assertEquals(HEADERS.get(0).value, event.requestHeaders.get(0).value);
        }
        if (mode.equals("off") || mode.equals("baseline")) {
            assertTrue(logs.snapshot().events.isEmpty()); assertTrue(requests.snapshot().events.isEmpty()); assertTrue(sockets.snapshot().events.isEmpty());
        } else {
            assertFalse(logs.snapshot().events.isEmpty()); assertFalse(requests.snapshot().events.isEmpty()); assertFalse(sockets.snapshot().events.isEmpty());
            assertTrue(logs.snapshot().evictedCount > 0); assertTrue(sockets.snapshot().evictedEvents > 0);
        }
        assertEquals(mode.equals("off"), crumbs.snapshot().events.isEmpty());
        for (LogEvent event : logs.snapshot().events) if (event.isPrivate) assertEquals("[private]", event.message);
        JSONObject result = new JSONObject().put("mode", mode).put("seconds", seconds).put("ticks", ticks)
            .put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000)
            .put("processCpuMs", Process.getElapsedCpuTime() - cpu).put("queryCount", queries)
            .put("tickLatencyUpperUs", new JSONArray(LATENCY_UPPER_US)).put("tickLatencyCounts", new JSONArray(latency))
            .put("tickMaximumUs", maximumNs / 1000).put("outcomes", new JSONObject(outcomes));
        // One compact line per sample avoids Android logcat's per-entry truncation.
        Log.i("OsservioLoad", result.toString());
        for (int index = 0; index < memory.length(); index++) Log.i("OsservioLoad", memory.getJSONObject(index).put("mode", mode).toString());
        assertTrue(capture.stopAll());
    }

    private static void count(Map<String, Long> outcomes, String operation, Enum<?> result) {
        String key = operation + "." + result.name();
        outcomes.put(key, outcomes.getOrDefault(key, 0L) + 1);
        assertFalse(key, result.name().equals("INVALID") || result.name().equals("SINK_FAILURE") || result.name().equals("CONNECTION_LIMIT"));
    }
    private static void checkBounds(MemoryLogStore logs, MemoryBreadcrumbStore crumbs, MemoryHTTPStore requests, MemoryWebSocketStore sockets) {
        LogSnapshot log = logs.snapshot(); BreadcrumbSnapshot breadcrumb = crumbs.snapshot();
        HTTPSnapshot http = requests.snapshot(); WebSocketSnapshot ws = sockets.snapshot();
        assertNotNull(log); assertNotNull(breadcrumb); assertNotNull(http); assertNotNull(ws);
        assertTrue(log.retainedDetailsBytes <= 262144);
        assertTrue(log.events.size() <= 200 && log.retainedTextBytes <= TEXT_LIMIT);
        assertTrue(breadcrumb.events.size() <= 200 && breadcrumb.retainedTextBytes <= TEXT_LIMIT);
        assertTrue(http.events.size() <= 100 && http.retainedTextBytes <= TEXT_LIMIT && http.retainedBodyBytes <= 32768);
        assertTrue(ws.events.size() <= 200 && ws.retainedTextBytes <= TEXT_LIMIT && ws.retainedPayloadBytes <= 16384);
    }
}
