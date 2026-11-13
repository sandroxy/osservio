package com.sandrox.osservio.okhttp;

import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.MemoryHTTPStore;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HTTPMetricsTest {
    @Test public void redirectsKeepRepeatedPhasesAndHostCallbacksWithoutChangingBodies() throws Exception {
        MemoryHTTPStore history = new MemoryHTTPStore(); CaptureController capture = new CaptureController(); capture.startDetailed();
        HTTPRecorder recorder = new HTTPRecorder(history, capture, new HTTPPrivacyPolicy());
        AtomicInteger starts = new AtomicInteger(), headers = new AtomicInteger(), ends = new AtomicInteger();
        OsservioEventListener listener = new OsservioEventListener(capture, call -> new EventListener() {
            @Override public void callStart(Call value) { starts.incrementAndGet(); }
            @Override public void responseHeadersEnd(Call value, Response response) { headers.incrementAndGet(); }
            @Override public void callEnd(Call value) { ends.incrementAndGet(); }
        });
        OkHttpClient client = new OkHttpClient.Builder().eventListenerFactory(listener).addInterceptor(new OsservioInterceptor(recorder, listener))
            .callTimeout(3, TimeUnit.SECONDS).build();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "/final").build());
            server.enqueue(new MockResponse.Builder().body("original response").build());
            try (Response response = client.newCall(new Request.Builder().url(server.url("/start")).build()).execute()) {
                assertEquals("original response", response.body().string());
            }
            assertEquals(1, starts.get()); assertEquals(2, headers.get()); assertEquals(1, ends.get());
            HTTPEvent event = history.snapshot().events.get(0); assertEquals(2, event.revision); assertEquals(HTTPEvent.State.COMPLETED, event.state);
            assertNotNull(event.metrics); assertEquals("http/1.1", event.metrics.protocolName); assertEquals(HTTPMetrics.Cache.NETWORK, event.metrics.cache);
            assertEquals(2, event.metrics.phases.stream().filter(v -> v.phase == HTTPMetrics.Phase.REQUEST_HEADERS).count());
            assertTrue(event.metrics.phases.stream().anyMatch(v -> v.phase == HTTPMetrics.Phase.WAITING && v.endMillis != null));
            assertNull(event.metrics.connectionReused); // No inferred reuse from absent connection callbacks.
        } finally { client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll(); }
    }
    @Test public void boundedConcurrentAttemptsAndMissingEndsStayDistinct() {
        HTTPMetricsBuffer buffer = new HTTPMetricsBuffer(); assertTrue(buffer.activate());
        Object first = new Object(), second = new Object();
        buffer.start(HTTPMetrics.Phase.CONNECT, first); buffer.start(HTTPMetrics.Phase.CONNECT, second);
        buffer.end(HTTPMetrics.Phase.CONNECT, second);
        HTTPMetrics metrics = buffer.snapshot(null, HTTPMetrics.Cache.UNKNOWN);
        assertEquals(2, metrics.phases.size()); assertNull(metrics.phases.get(0).endMillis); assertNotNull(metrics.phases.get(1).endMillis);
        assertFalse(metrics.limited);
        assertTrue(buffer.activate());
        for (int i = 0; i < 1000; i++) { buffer.start(HTTPMetrics.Phase.DNS, null); buffer.end(HTTPMetrics.Phase.DNS, null); }
        metrics = buffer.snapshot(null, HTTPMetrics.Cache.UNKNOWN); assertEquals(32, metrics.phases.size()); assertTrue(metrics.limited);
        assertThrows(IllegalArgumentException.class, () -> new HTTPMetrics.Timing(HTTPMetrics.Phase.DNS, 2, 1L));
        assertThrows(IllegalArgumentException.class, () -> new HTTPMetrics(Collections.emptyList(), "h2\n", HTTPMetrics.Cache.UNKNOWN, null, false));
    }
    @Test public void stoppingOrClearingDoesNotRecreateTheHTTPRecord() {
        MemoryHTTPStore history = new MemoryHTTPStore(); CaptureController capture = new CaptureController(); capture.startDetailed();
        HTTPRecorder recorder = new HTTPRecorder(history, capture, new HTTPPrivacyPolicy());
        HTTPRecorder.Token token = recorder.begin("GET", "https://example.com", Collections.emptyList(), HTTPEvent.Source.HOST).token;
        history.clear();
        assertEquals(HTTPRecorder.Result.MISSING, recorder.finish(token, HTTPEvent.State.FAILED, HTTPEvent.Failure.CONNECTION, true, false,
            new HTTPMetrics(Collections.emptyList(), null, HTTPMetrics.Cache.UNKNOWN, null, false)));
        assertTrue(history.snapshot().events.isEmpty());
    }
}
