package com.sandrox.osservio.okhttp;

import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.MemoryHTTPStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.*;
import okio.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OsservioInterceptorTest {
    private static final MediaType TEXT = MediaType.get("text/plain; charset=utf-8");
    private MemoryHTTPStore store;
    private CaptureController controller;
    private HTTPRecorder recorder;
    private final List<OkHttpClient> clients = new ArrayList<>();

    @Before public void setUp() {
        store = new MemoryHTTPStore();
        controller = new CaptureController();
        controller.startDetailed();
        recorder = new HTTPRecorder(store, controller, new HTTPPrivacyPolicy(true, Collections.emptySet(), HTTPPrivacyPolicy.BodyPolicy.PUBLIC_UTF8, 8));
    }
    @After public void closeClients() {
        for (OkHttpClient client : clients) { client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll(); }
    }
    private OkHttpClient client(OkHttpClient.Builder builder) {
        OkHttpClient client = builder.callTimeout(3, TimeUnit.SECONDS).build(); clients.add(client); return client;
    }
    private OkHttpClient.Builder builder() { return new OkHttpClient.Builder().addInterceptor(new OsservioInterceptor(recorder)); }
    private static Response reply(Request request, ResponseBody body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", "text/plain; charset=utf-8").body(body).build();
    }
    private static ResponseBody body(Source source, long length) {
        BufferedSource buffered = Okio.buffer(source);
        return new ResponseBody() {
            @Override public MediaType contentType() { return TEXT; }
            @Override public long contentLength() { return length; }
            @Override public BufferedSource source() { return buffered; }
        };
    }
    private HTTPEvent onlyEvent() { assertEquals(1, store.snapshot().events.size()); return store.snapshot().events.get(0); }

    @Test public void postKeepsWireBytesAndWritesOneShotBodyExactlyOnce() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        RequestBody requestBody = new RequestBody() {
            @Override public MediaType contentType() { return TEXT; }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) throws IOException { writes.incrementAndGet(); sink.writeUtf8("request-one-shot-body"); }
        };
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().addHeader("Content-Type", "text/plain").body("response-from-server").build());
            OkHttpClient client = client(builder().addInterceptor(chain -> { assertTrue(chain.request().body().isOneShot()); return chain.proceed(chain.request()); }));
            Request request = new Request.Builder().url(server.url("/post?token=synthetic"))
                .header("Authorization", "Bearer synthetic-only").post(requestBody).build();
            try (Response response = client.newCall(request).execute()) {
                assertEquals(HTTPEvent.State.RECEIVING, onlyEvent().state);
                assertEquals("response-from-server", response.body().string());
            }
            RecordedRequest received = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(received);
            assertEquals("request-one-shot-body", received.getBody().utf8());
            assertEquals("Bearer synthetic-only", received.getHeaders().get("Authorization"));
            assertEquals(1, writes.get());
            HTTPEvent event = onlyEvent();
            assertEquals(HTTPEvent.Source.OKHTTP, event.source);
            assertEquals(HTTPEvent.State.COMPLETED, event.state);
            assertTrue(event.url.endsWith("/post?[redacted]"));
            assertEquals(HTTPEvent.BodyState.TRUNCATED, event.requestBody.state);
            assertEquals("request-", store.body(event.requestBody.blobId).body.text);
            assertEquals("response", store.body(event.responseBody.blobId).body.text);
            assertTrue(event.requestHeaders.stream().anyMatch(header -> header.name.equals("authorization") && header.value.equals("[redacted]")));
        }
    }

    @Test public void responseIsNotReadOrClosedUntilHostActs() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        Source source = new ForwardingSource(new Buffer().writeUtf8("body")) {
            @Override public long read(Buffer sink, long count) throws IOException { reads.incrementAndGet(); return super.read(sink, count); }
            @Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
        };
        OkHttpClient client = client(builder().addInterceptor(chain -> reply(chain.request(), body(source, 4))));
        Response response = client.newCall(new Request.Builder().url("https://example.com").build()).execute();
        assertEquals(0, reads.get()); assertEquals(0, closes.get());
        assertEquals(HTTPEvent.State.RECEIVING, onlyEvent().state);
        response.close(); response.close();
        assertEquals(0, reads.get()); assertEquals(1, closes.get());
        assertEquals(HTTPEvent.State.CLOSED_EARLY, onlyEvent().state);
        assertEquals(HTTPEvent.BodyState.INCOMPLETE, onlyEvent().responseBody.state);
    }

    @Test public void sourceIOExceptionIdentityIsPreserved() throws Exception {
        IOException expected = new IOException("synthetic private detail");
        Source source = new ForwardingSource(new Buffer()) {
            @Override public long read(Buffer sink, long count) throws IOException { throw expected; }
        };
        OkHttpClient client = client(builder().addInterceptor(chain -> reply(chain.request(), body(source, -1))));
        try (Response response = client.newCall(new Request.Builder().url("https://example.com").build()).execute()) {
            assertSame(expected, assertThrows(IOException.class, () -> response.body().string()));
        }
        assertEquals(HTTPEvent.State.FAILED, onlyEvent().state);
        assertEquals(HTTPEvent.Failure.TRANSPORT, onlyEvent().failure);
    }

    @Test public void hostRuntimeExceptionsAreNeverRetriedIncludingWhenCaptureIsOff() {
        for (boolean enabled : new boolean[] {false, true}) {
            if (enabled) controller.startDetailed(); else controller.stopAll();
            AtomicInteger calls = new AtomicInteger();
            IllegalStateException expected = new IllegalStateException("host failure");
            OkHttpClient client = client(builder().addInterceptor(chain -> { calls.incrementAndGet(); throw expected; }));
            assertSame(expected, assertThrows(IllegalStateException.class, () -> client.newCall(new Request.Builder().url("https://example.com").build()).execute()));
            assertEquals(1, calls.get());
        }
        assertEquals(HTTPEvent.State.FAILED, onlyEvent().state);
        assertEquals(HTTPEvent.Failure.UNKNOWN, onlyEvent().failure);
    }

    @Test public void diagnosticFailurePreservesTheOriginalResponse() throws Exception {
        HTTPRecorder failing = new HTTPRecorder(update -> { throw new IllegalStateException("diagnostic failure"); }, controller, new HTTPPrivacyPolicy());
        ResponseBody original = body(new Buffer().writeUtf8("untouched"), 9);
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient client = client(new OkHttpClient.Builder().addInterceptor(new OsservioInterceptor(failing))
            .addInterceptor(chain -> { calls.incrementAndGet(); return reply(chain.request(), original); }));
        try (Response response = client.newCall(new Request.Builder().url("https://example.com").build()).execute()) {
            assertSame(original, response.body());
            assertEquals("untouched", response.body().string());
        }
        assertEquals(1, calls.get());
    }

    @Test public void redirectsRemainOneLogicalObservationWithSanitizedFinalURL() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", server.url("/final?secret=value")).build());
            server.enqueue(new MockResponse.Builder().addHeader("Content-Type", "text/plain").body("done").build());
            OkHttpClient client = client(builder());
            try (Response response = client.newCall(new Request.Builder().url(server.url("/start")).build()).execute()) {
                assertEquals("done", response.body().string());
            }
            assertEquals(2, server.getRequestCount());
            assertTrue(onlyEvent().url.endsWith("/start"));
            assertTrue(onlyEvent().responseURL.endsWith("/final?[redacted]"));
        }
    }

    @Test public void authenticationRetryDoesNotConcatenateRequestBodies() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        RequestBody requestBody = new RequestBody() {
            @Override public MediaType contentType() { return TEXT; }
            @Override public void writeTo(BufferedSink sink) throws IOException { writes.incrementAndGet(); sink.writeUtf8("repeated-body"); }
        };
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(401).build());
            server.enqueue(new MockResponse.Builder().addHeader("Content-Type", "text/plain").body("ok").build());
            OkHttpClient client = client(builder().authenticator((route, response) -> response.request().newBuilder().header("Authorization", "synthetic").build()));
            try (Response response = client.newCall(new Request.Builder().url(server.url("/retry")).post(requestBody).build()).execute()) {
                assertEquals("ok", response.body().string());
            }
            assertEquals(2, writes.get());
            assertEquals("repeated-body", server.takeRequest(1, TimeUnit.SECONDS).getBody().utf8());
            assertEquals("repeated-body", server.takeRequest(1, TimeUnit.SECONDS).getBody().utf8());
            assertEquals(HTTPEvent.BodyState.INCOMPLETE, onlyEvent().requestBody.state);
            assertNull(onlyEvent().requestBody.blobId);
        }
    }

    @Test public void stoppingCaptureDoesNotCancelOrConsumeTheHostResponse() throws Exception {
        OkHttpClient client = client(builder().addInterceptor(chain -> reply(chain.request(), body(new Buffer().writeUtf8("still-readable"), 14))));
        Call call = client.newCall(new Request.Builder().url("https://example.com").build());
        try (Response response = call.execute()) {
            assertTrue(controller.stopDetailed());
            controller.startDetailed();
            assertEquals("still-readable", response.body().string());
        }
        assertFalse(call.isCanceled());
        assertEquals(HTTPEvent.State.CAPTURE_ENDED, onlyEvent().state);
        assertNull(onlyEvent().responseBody.blobId);
    }

    @Test public void duplexRequestBodyIsPassedThroughUnchanged() throws Exception {
        RequestBody duplex = new RequestBody() {
            @Override public MediaType contentType() { return TEXT; }
            @Override public boolean isDuplex() { return true; }
            @Override public void writeTo(BufferedSink sink) { fail("Inspector must not write a duplex body"); }
        };
        OkHttpClient client = client(builder().addInterceptor(chain -> { assertSame(duplex, chain.request().body()); return reply(chain.request(), body(new Buffer().writeUtf8("ok"), 2)); }));
        try (Response response = client.newCall(new Request.Builder().url("https://example.com").post(duplex).build()).execute()) {
            assertEquals("ok", response.body().string());
        }
        assertEquals(HTTPEvent.BodyState.INCOMPLETE, onlyEvent().requestBody.state);
    }

    @Test public void cancellingTheCallPreservesItsReadFailure() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().addHeader("Content-Type", "text/plain").body("late").bodyDelay(200, TimeUnit.MILLISECONDS).build());
            OkHttpClient client = client(builder());
            Call call = client.newCall(new Request.Builder().url(server.url("/cancel")).build());
            try (Response response = call.execute()) {
                call.cancel();
                assertThrows(IOException.class, () -> response.body().string());
            }
            assertEquals(HTTPEvent.State.CANCELLED, onlyEvent().state);
        }
    }

    @Test public void callTimeoutPreservesHostErrorWithoutInferringCancellationCause() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().headersDelay(200, TimeUnit.MILLISECONDS).body("late").build());
            OkHttpClient client = client(builder());
            Call call = client.newCall(new Request.Builder().url(server.url("/timeout")).build());
            call.timeout().timeout(50, TimeUnit.MILLISECONDS);
            assertThrows(java.io.InterruptedIOException.class, call::execute);
            assertTrue(call.isCanceled());
            assertEquals(HTTPEvent.State.CANCELLED, onlyEvent().state);
            assertNull(onlyEvent().failure);
        }
    }

    @Test public void socketReadTimeoutHasAnExplicitTimeoutCategory() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().addHeader("Content-Type", "text/plain").body("late").bodyDelay(200, TimeUnit.MILLISECONDS).build());
            OkHttpClient client = client(builder().readTimeout(50, TimeUnit.MILLISECONDS));
            try (Response response = client.newCall(new Request.Builder().url(server.url("/read-timeout")).build()).execute()) {
                assertThrows(java.net.SocketTimeoutException.class, () -> response.body().string());
            }
            assertEquals(HTTPEvent.State.FAILED, onlyEvent().state);
            assertEquals(HTTPEvent.Failure.TIMEOUT, onlyEvent().failure);
        }
    }
}
