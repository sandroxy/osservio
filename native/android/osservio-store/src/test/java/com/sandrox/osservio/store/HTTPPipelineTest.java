package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HTTPPipelineTest {
    private static final List<HTTPHeader> TEXT = Collections.singletonList(new HTTPHeader("Content-Type", "application/json; charset=utf-8"));
    private static final List<HTTPHeader> EMPTY = Collections.emptyList();
    private static final class Clock implements LogClock {
        long now = 100;
        @Override public long monotonicMillis() { return now; }
        @Override public long wallTimeMillis() { return 1_700_000_000_000L; }
    }
    private static HTTPPrivacyPolicy publicText(int limit) {
        return new HTTPPrivacyPolicy(false, Collections.emptySet(), HTTPPrivacyPolicy.BodyPolicy.PUBLIC_UTF8, limit);
    }
    private static HTTPRecorder recorder(MemoryHTTPStore store, HTTPPrivacyPolicy policy) {
        CaptureController controller = new CaptureController(false, new Clock());
        controller.startDetailed();
        return new HTTPRecorder(store, controller, policy);
    }
    private static HTTPRecorder.Token response(HTTPRecorder recorder, String body) {
        HTTPRecorder.Token token = recorder.begin("GET", "https://example.com/api", EMPTY).token;
        assertNotNull(token);
        assertEquals(HTTPRecorder.Result.RECORDED, recorder.receiveResponse(token, 200, TEXT, false));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        assertEquals(HTTPRecorder.Result.RECORDED, recorder.observeBody(token, HTTPRecorder.Direction.RESPONSE, bytes.length, count -> Arrays.copyOf(bytes, count)));
        assertEquals(HTTPRecorder.Result.RECORDED, recorder.finish(token, HTTPEvent.State.COMPLETED, null, true, true));
        return token;
    }
    private static List<String> fixture(String name) throws Exception {
        return Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), name), StandardCharsets.UTF_8);
    }

    @Test public void sharedURLPrivacyFixtures() throws Exception {
        for (String line : fixture("http-url-cases.tsv")) {
            String[] row = line.split("\t", -1);
            assertEquals(4, row.length);
            MemoryHTTPStore store = new MemoryHTTPStore();
            HTTPRecorder capture = recorder(store, new HTTPPrivacyPolicy(Boolean.parseBoolean(row[1]), Collections.emptySet(), HTTPPrivacyPolicy.BodyPolicy.OMIT, 16384));
            assertEquals(row[0], HTTPRecorder.Result.RECORDED, capture.begin("GET", row[2], EMPTY).result);
            assertEquals(row[0], row[3], store.snapshot().events.get(0).url);
        }
    }

    @Test public void defaultPrivacyDoesNotCopyBodiesAndSanitizesBeforeStorage() {
        MemoryHTTPStore store = new MemoryHTTPStore();
        HTTPRecorder capture = recorder(store, new HTTPPrivacyPolicy());
        HTTPRecorder.Token token = capture.begin("post", "https://user:secret@example.com/private?token=secret", Arrays.asList(
            new HTTPHeader("Authorization", "Bearer secret"), new HTTPHeader("Content-Type", "application/json; boundary=secret"),
            new HTTPHeader("Content-Length", "123"), new HTTPHeader("secret-name", "secret"))).token;
        assertNotNull(token);
        assertEquals(HTTPRecorder.Result.RECORDED, capture.observeBody(token, HTTPRecorder.Direction.REQUEST, 123, count -> { fail("Omitted body was read"); return null; }));
        assertEquals(HTTPRecorder.Result.RECORDED, capture.finish(token, HTTPEvent.State.FAILED, HTTPEvent.Failure.CONNECTION, false, false));
        HTTPEvent event = store.snapshot().events.get(0);
        assertEquals("POST", event.method);
        assertEquals(3, event.requestHeaders.size());
        assertEquals("[redacted]", event.requestHeaders.get(0).value);
        assertEquals("application/json", event.requestHeaders.get(1).value);
        assertEquals("123", event.requestHeaders.get(2).value);
        assertTrue(event.headersLimited);
        assertEquals(HTTPEvent.BodyState.OMITTED, event.requestBody.state);
        assertEquals(123, event.requestBody.observedBytes);
        assertEquals(0, store.snapshot().retainedBodyBytes);
        assertThrows(IllegalArgumentException.class, () -> new HTTPPrivacyPolicy(false, Collections.singleton("Authorization"), HTTPPrivacyPolicy.BodyPolicy.OMIT, 16384));
    }

    @Test public void sharedBodyUTF8AndFormatFixtures() throws Exception {
        for (String line : fixture("http-body-cases.tsv")) {
            String[] row = line.split("\t", -1);
            assertEquals(6, row.length);
            MemoryHTTPStore store = new MemoryHTTPStore();
            HTTPRecorder capture = recorder(store, publicText(Integer.parseInt(row[1])));
            HTTPRecorder.Token token = capture.begin("GET", "https://example.com", EMPTY).token;
            byte[] bytes = Base64.getDecoder().decode(row[2]);
            assertEquals(HTTPRecorder.Result.RECORDED, capture.receiveResponse(token, 200, Collections.singletonList(new HTTPHeader("Content-Type", row[3])), false));
            assertEquals(HTTPRecorder.Result.RECORDED, capture.observeBody(token, HTTPRecorder.Direction.RESPONSE, bytes.length, count -> Arrays.copyOf(bytes, count)));
            assertEquals(HTTPRecorder.Result.RECORDED, capture.finish(token, HTTPEvent.State.COMPLETED, null, true, true));
            HTTPEvent event = store.snapshot().events.get(0);
            String state = event.responseBody.state == HTTPEvent.BodyState.INVALID_TEXT ? "invalidText" : event.responseBody.state.name().toLowerCase(java.util.Locale.ROOT);
            assertEquals(row[0], row[4], state);
            if (event.responseBody.blobId != null) {
                HTTPHistory.BodyLookup lookup = store.body(event.responseBody.blobId);
                assertEquals(row[0], HTTPHistory.BodyLookup.State.AVAILABLE, lookup.state);
                assertEquals(row[0], row[5].equals("~") ? "" : row[5], Base64.getEncoder().encodeToString(lookup.body.text.getBytes(StandardCharsets.UTF_8)));
            } else assertEquals(row[0], "-", row[5]);
        }
    }

    @Test public void clearAndEventEvictionRejectLateResponses() {
        MemoryHTTPStore store = new MemoryHTTPStore(1, 262144, 524288);
        HTTPRecorder capture = recorder(store, new HTTPPrivacyPolicy());
        HTTPRecorder.Token first = capture.begin("GET", "https://example.com/first", EMPTY).token;
        HTTPSnapshot before = store.snapshot();
        assertNotNull(capture.begin("GET", "https://example.com/second", EMPTY).token);
        assertEquals(HTTPRecorder.Result.MISSING, capture.receiveResponse(first, 200, TEXT, false));
        assertEquals(HTTPRecorder.Result.MISSING, capture.finish(first, HTTPEvent.State.COMPLETED, null, true, true));
        assertEquals(1, before.events.size());
        assertEquals(HTTPEvent.State.PENDING, before.events.get(0).state);
        HTTPRecorder.Token second = capture.begin("GET", "https://example.com/third", EMPTY).token;
        store.clear();
        assertEquals(HTTPRecorder.Result.MISSING, capture.receiveResponse(second, 200, TEXT, false));
        assertEquals(HTTPRecorder.Result.MISSING, capture.finish(second, HTTPEvent.State.COMPLETED, null, true, true));
        assertEquals(0, store.snapshot().events.size());
    }

    @Test public void bodyEvictionPreservesMetadataAndSnapshot() {
        MemoryHTTPStore store = new MemoryHTTPStore(200, 262144, 3);
        HTTPRecorder capture = recorder(store, publicText(16384));
        HTTPRecorder.Token first = response(capture, "abc");
        HTTPSnapshot before = store.snapshot();
        HTTPRecorder.Token second = response(capture, "xyz");
        assertEquals(2, store.snapshot().events.size());
        assertEquals(3, store.snapshot().retainedBodyBytes);
        assertEquals(1, store.snapshot().evictedBodies);
        assertEquals(HTTPHistory.BodyLookup.State.UNAVAILABLE, store.body(first.id + ".response").state);
        assertEquals("xyz", store.body(second.id + ".response").body.text);
        assertEquals(HTTPEvent.BodyState.AVAILABLE, before.events.get(0).responseBody.state);
    }

    @Test public void sessionStopReleasesObservationsAndRejectsStaleTokens() {
        MemoryHTTPStore store = new MemoryHTTPStore();
        CaptureController controller = new CaptureController(true, new Clock());
        controller.startDetailed();
        HTTPRecorder capture = new HTTPRecorder(store, controller, publicText(16384), 1, 50, 120000);
        HTTPRecorder.Token token = capture.begin("POST", "https://example.com", TEXT).token;
        assertEquals(HTTPRecorder.Result.RECORDED, capture.observeBody(token, HTTPRecorder.Direction.REQUEST, 3, count -> "abc".getBytes(StandardCharsets.UTF_8)));
        assertTrue(controller.stopDetailed());
        assertEquals(HTTPEvent.State.CAPTURE_ENDED, store.snapshot().events.get(0).state);
        assertNull(store.snapshot().events.get(0).durationMillis);
        assertEquals(0, store.snapshot().retainedBodyBytes);
        controller.startDetailed();
        assertEquals(HTTPRecorder.Result.STALE, capture.receiveResponse(token, 200, TEXT, false));
        assertNotNull(capture.begin("GET", "https://example.com", EMPTY).token);
    }

    @Test public void admissionLimitsAndLazyTimeout() {
        MemoryHTTPStore store = new MemoryHTTPStore();
        Clock clock = new Clock();
        CaptureController controller = new CaptureController(false, clock);
        controller.startDetailed();
        HTTPRecorder capture = new HTTPRecorder(store, controller, new HTTPPrivacyPolicy(), 1, 2, 10);
        HTTPRecorder.Token first = capture.begin("GET", "https://example.com", EMPTY).token;
        assertEquals(HTTPRecorder.Result.ACTIVE_LIMIT, capture.begin("GET", "https://example.com", EMPTY).result);
        assertEquals(HTTPRecorder.Result.RATE_LIMITED, capture.begin("GET", "https://example.com", EMPTY).result);
        clock.now += 1000;
        assertEquals(HTTPRecorder.Result.RECORDED, capture.maintenance());
        assertEquals(HTTPEvent.State.CAPTURE_ENDED, store.snapshot().events.get(0).state);
        assertEquals(HTTPRecorder.Result.STALE, capture.finish(first, HTTPEvent.State.FAILED, HTTPEvent.Failure.TIMEOUT, true, true));
        assertNotNull(capture.begin("GET", "https://example.com", EMPTY).token);
    }

    @Test public void invalidOrMissingChunksNeverBecomeCompleteBodies() {
        MemoryHTTPStore store = new MemoryHTTPStore();
        HTTPRecorder capture = recorder(store, publicText(16384));
        HTTPRecorder.Token token = capture.begin("GET", "https://example.com", EMPTY).token;
        assertEquals(HTTPRecorder.Result.INVALID, capture.finish(token, HTTPEvent.State.COMPLETED, null, true, true));
        assertEquals(HTTPRecorder.Result.INVALID, capture.receiveResponse(token, 600, TEXT, false));
        assertEquals(HTTPRecorder.Result.RECORDED, capture.receiveResponse(token, 200, TEXT, false));
        assertEquals(HTTPRecorder.Result.INVALID, capture.observeBody(token, HTTPRecorder.Direction.RESPONSE, 4, count -> new byte[2]));
        assertEquals(HTTPRecorder.Result.RECORDED, capture.finish(token, HTTPEvent.State.COMPLETED, null, true, true));
        assertEquals(HTTPEvent.BodyState.INCOMPLETE, store.snapshot().events.get(0).responseBody.state);
        assertNull(store.snapshot().events.get(0).responseBody.blobId);
        assertEquals(HTTPRecorder.Result.STALE, capture.finish(token, HTTPEvent.State.COMPLETED, null, true, true));
    }

    @Test public void publicHeadersRemainBoundedAndCompressionRequiresDecodedBytes() {
        MemoryHTTPStore store = new MemoryHTTPStore();
        HTTPPrivacyPolicy policy = new HTTPPrivacyPolicy(false, Collections.singleton("X-Trace"), HTTPPrivacyPolicy.BodyPolicy.PUBLIC_UTF8, 16384);
        HTTPRecorder capture = recorder(store, policy);
        HTTPRecorder.Token token = capture.begin("GET", "https://example.com", Collections.singletonList(new HTTPHeader("X-Trace", "😀".repeat(200)))).token;
        assertEquals(512, store.snapshot().events.get(0).requestHeaders.get(0).value.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(HTTPRecorder.Result.RECORDED, capture.receiveResponse(token, 200, Arrays.asList(TEXT.get(0), new HTTPHeader("Content-Encoding", "gzip")), false));
        assertEquals(HTTPRecorder.Result.RECORDED, capture.observeBody(token, HTTPRecorder.Direction.RESPONSE, 4, count -> { fail("Encoded body must be omitted"); return null; }));
        assertEquals(HTTPRecorder.Result.RECORDED, capture.finish(token, HTTPEvent.State.COMPLETED, null, true, true));
        assertEquals(HTTPEvent.BodyState.UNSUPPORTED, store.snapshot().events.get(0).responseBody.state);
    }

    @Test public void oversizedMetadataPreservesHistory() {
        MemoryHTTPStore store = new MemoryHTTPStore(200, 100, 524288);
        HTTPRecorder capture = recorder(store, new HTTPPrivacyPolicy(true, Collections.emptySet(), HTTPPrivacyPolicy.BodyPolicy.OMIT, 16384));
        assertEquals(HTTPRecorder.Result.RECORDED, capture.begin("GET", "https://example.com/a", EMPTY).result);
        assertEquals(HTTPRecorder.Result.TOO_LARGE, capture.begin("GET", "https://example.com/" + "x".repeat(150), EMPTY).result);
        assertEquals(1, store.snapshot().events.size());
        assertEquals(0, store.snapshot().evictedEvents);
    }

    @Test public void reentrantAndConcurrentAdmissionsAreBusy() throws Exception {
        CaptureController controller = new CaptureController(false, new Clock());
        controller.startDetailed();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<HTTPRecorder.Result> result = new AtomicReference<>();
        HTTPRecorder capture = new HTTPRecorder(update -> {
            assertNull(controller.status());
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return HTTPSink.WriteResult.STORED;
        }, controller, new HTTPPrivacyPolicy());
        Thread worker = new Thread(() -> result.set(capture.begin("GET", "https://example.com", EMPTY).result));
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(HTTPRecorder.Result.BUSY, capture.begin("GET", "https://example.com", EMPTY).result);
            assertFalse(controller.stopDetailed());
        } finally { release.countDown(); worker.join(2000); }
        assertFalse(worker.isAlive());
        assertEquals(HTTPRecorder.Result.RECORDED, result.get());
    }

    @Test public void sinkFailuresAndInvalidBodyOrderingAreExplicit() {
        CaptureController controller = new CaptureController(false, new Clock());
        controller.startDetailed();
        HTTPRecorder failing = new HTTPRecorder(update -> { throw new IllegalStateException("synthetic"); }, controller, new HTTPPrivacyPolicy());
        assertEquals(HTTPRecorder.Result.SINK_FAILURE, failing.begin("GET", "https://example.com", EMPTY).result);
        MemoryHTTPStore store = new MemoryHTTPStore();
        HTTPRecorder capture = new HTTPRecorder(store, controller, publicText(16384));
        HTTPRecorder.Token token = capture.begin("GET", "https://example.com", EMPTY).token;
        assertEquals(HTTPRecorder.Result.INVALID, capture.observeBody(token, HTTPRecorder.Direction.RESPONSE, 2, count -> { fail("Out-of-order copy"); return null; }));
        assertEquals(HTTPRecorder.Result.RECORDED, capture.receiveResponse(token, 200, TEXT, false));
        assertEquals(HTTPRecorder.Result.RECORDED, capture.finish(token, HTTPEvent.State.COMPLETED, null, true, true));
        assertEquals(HTTPEvent.BodyState.INCOMPLETE, store.snapshot().events.get(0).responseBody.state);
    }

    @Test public void growingMetadataEvictsOthersWithoutRemovingUpdatedRecord() {
        MemoryHTTPStore store = new MemoryHTTPStore(200, 150, 524288);
        HTTPRecorder capture = recorder(store, new HTTPPrivacyPolicy(false, Collections.singleton("X-Trace"), HTTPPrivacyPolicy.BodyPolicy.OMIT, 16384));
        HTTPRecorder.Token first = capture.begin("GET", "https://example.com", EMPTY).token;
        capture.begin("GET", "https://example.com", EMPTY);
        assertEquals(HTTPRecorder.Result.RECORDED, capture.receiveResponse(first, 200, Collections.singletonList(new HTTPHeader("X-Trace", "a".repeat(80))), false));
        assertEquals(1, store.snapshot().events.size());
        assertEquals(first.id, store.snapshot().events.get(0).id);
        assertTrue(store.snapshot().retainedTextBytes <= 150);
    }
}
