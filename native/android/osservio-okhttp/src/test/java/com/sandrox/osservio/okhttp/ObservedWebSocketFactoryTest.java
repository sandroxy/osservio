package com.sandrox.osservio.okhttp;

import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.MemoryWebSocketStore;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.*;
import okio.ByteString;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ObservedWebSocketFactoryTest {
    private final MemoryWebSocketStore history = new MemoryWebSocketStore();
    private final CaptureController capture = new CaptureController();
    private WebSocketRecorder recorder;
    private final Request request = new Request.Builder().url("https://example.com/socket?token=secret")
        .header("Authorization", "Bearer secret").build();
    private final FakeSocket original = new FakeSocket();
    private final AtomicReference<WebSocketListener> listener = new AtomicReference<>();
    @Before public void start() {
        capture.startDetailed();
        recorder = new WebSocketRecorder(history, capture, new WebSocketPrivacyPolicy(true, Collections.emptySet(), true, 4));
    }
    private final class FakeSocket implements WebSocket {
        int sends;
        int cancels;
        int closes;
        boolean accepts = true;
        RuntimeException exception;
        String reason;
        @Override public Request request() { return request; }
        @Override public long queueSize() { return 11; }
        @Override public boolean send(String text) { sends++; if (exception != null) throw exception; return accepts; }
        @Override public boolean send(ByteString bytes) { sends++; return accepts; }
        @Override public boolean close(int code, String reason) { closes++; this.reason = reason; return accepts; }
        @Override public void cancel() { cancels++; }
    }
    private WebSocket socket(WebSocketListener host) {
        return new ObservedWebSocketFactory((value, callback) -> {
            assertSame(request, value); listener.set(callback); return original;
        }, recorder).newWebSocket(request, host);
    }
    private Response response(int status) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
            .header("Set-Cookie", "private-cookie").build();
    }
    @Test public void forwardsCallbackIdentityArgumentsAndCloseReasonWithoutRecordingIt() {
        AtomicReference<WebSocket> hostSocket = new AtomicReference<>();
        AtomicReference<Object> argument = new AtomicReference<>();
        WebSocket socket = socket(new WebSocketListener() {
            @Override public void onOpen(WebSocket value, Response response) { hostSocket.set(value); argument.set(response); }
            @Override public void onMessage(WebSocket value, String text) { hostSocket.set(value); argument.set(text); }
            @Override public void onMessage(WebSocket value, ByteString bytes) { hostSocket.set(value); argument.set(bytes); }
            @Override public void onClosing(WebSocket value, int code, String reason) { hostSocket.set(value); argument.set(reason); }
            @Override public void onFailure(WebSocket value, Throwable failure, Response response) { hostSocket.set(value); argument.set(failure); }
        });
        Response open = response(101); listener.get().onOpen(original, open);
        assertSame(socket, hostSocket.get()); assertSame(open, argument.get());
        String text = "abcdef"; listener.get().onMessage(original, text); assertSame(text, argument.get());
        ByteString binary = ByteString.encodeUtf8("binary-secret"); listener.get().onMessage(original, binary); assertSame(binary, argument.get());
        String reason = "private-close-reason";
        assertTrue(socket.close(1000, reason)); assertSame(reason, original.reason);
        listener.get().onClosing(original, 1000, reason); assertSame(reason, argument.get());
        IOException error = new IOException("private-error"); listener.get().onFailure(original, error, null); assertSame(error, argument.get());
        assertSame(request, socket.request()); assertEquals(11, socket.queueSize());
        assertEquals(1, original.closes); assertEquals(0, recorder.status().activeConnections);
        assertEquals(WebSocketEvent.Kind.CLOSE_REQUESTED, history.snapshot().events.get(4).kind);
        assertEquals("[redacted]", history.snapshot().events.get(1).headers.get(0).value);
        assertEquals("abcd", history.payload(history.snapshot().events.get(2).payload.blobId).payload.text);
        assertEquals(WebSocketEvent.PayloadState.BINARY, history.snapshot().events.get(3).payload.state);
    }
    @Test public void factoryExceptionIsNotRetriedOrInventedAsTransportFailure() {
        AtomicInteger calls = new AtomicInteger(); RuntimeException failure = new IllegalStateException("host failure");
        ObservedWebSocketFactory factory = new ObservedWebSocketFactory((value, callback) -> { calls.incrementAndGet(); throw failure; }, recorder);
        try { factory.newWebSocket(request, new WebSocketListener() {}); fail(); } catch (RuntimeException actual) { assertSame(failure, actual); }
        assertEquals(1, calls.get()); assertEquals(WebSocketEvent.Kind.OBSERVATION_ENDED, history.snapshot().events.get(1).kind);
        capture.stopAll();
        try { factory.newWebSocket(request, new WebSocketListener() {}); fail(); } catch (RuntimeException actual) { assertSame(failure, actual); }
        assertEquals(2, calls.get());
    }
    @Test public void diagnosticFailureCannotSuppressHostReturnOrListenerException() {
        recorder = new WebSocketRecorder((event, payload) -> { throw new IllegalStateException("broken sink"); }, capture, new WebSocketPrivacyPolicy());
        RuntimeException hostFailure = new IllegalArgumentException("host listener");
        WebSocket socket = socket(new WebSocketListener() {
            @Override public void onMessage(WebSocket value, String text) { throw hostFailure; }
        });
        assertTrue(socket.send("still-sent")); assertEquals(1, original.sends);
        try { listener.get().onMessage(original, "message"); fail(); } catch (RuntimeException actual) { assertSame(hostFailure, actual); }
        socket.cancel(); assertEquals(1, original.cancels);
    }
    @Test public void liveSocketAttachesAfterEnablingAndAcrossCaptureSessions() {
        capture.stopAll(); WebSocket socket = socket(new WebSocketListener() {});
        assertTrue(socket.send("off")); assertTrue(history.snapshot().events.isEmpty());
        capture.startDetailed(); assertTrue(socket.send("on"));
        WebSocketEvent first = history.snapshot().events.get(0);
        assertEquals(WebSocketEvent.Kind.ATTACHED, first.kind);
        capture.stopDetailed(); assertTrue(socket.send("off again")); assertEquals(2, history.snapshot().events.size());
        capture.startDetailed(); listener.get().onMessage(original, "incoming");
        WebSocketEvent later = history.snapshot().events.get(2);
        assertEquals(WebSocketEvent.Kind.ATTACHED, later.kind); assertEquals(first.connectionId, later.connectionId);
        assertNotEquals(first.sessionId, later.sessionId); assertEquals(0, original.cancels);
    }
    @Test public void preservesRejectedSendAndOriginalExceptions() {
        WebSocket socket = socket(new WebSocketListener() {}); original.accepts = false;
        assertFalse(socket.send("rejected"));
        assertEquals(WebSocketEvent.Outcome.REJECTED, history.snapshot().events.get(1).outcome);
        RuntimeException failure = new IllegalArgumentException("original send"); original.exception = failure;
        try { socket.send("invalid"); fail(); } catch (RuntimeException actual) { assertSame(failure, actual); }
        assertEquals(2, original.sends); assertEquals(2, history.snapshot().events.size());
        assertFalse(socket.close(1000, "reason")); assertEquals(2, history.snapshot().events.size());
    }
    @Test public void observesRealBidirectionalMessagesAndGracefulClose() throws Exception {
        CountDownLatch received = new CountDownLatch(1); CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        OkHttpClient client = new OkHttpClient(); WebSocket[] socket = new WebSocket[1];
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket value, Response response) { value.send("server-message"); }
                @Override public void onMessage(WebSocket value, String message) { if (message.equals("client-message")) received.countDown(); }
                @Override public void onClosing(WebSocket value, int code, String reason) { value.close(code, reason); }
            }).build());
            socket[0] = new ObservedWebSocketFactory(client, recorder).newWebSocket(new Request.Builder().url(server.url("/socket")).build(),
                new WebSocketListener() {
                    @Override public void onOpen(WebSocket value, Response response) { value.send("client-message"); }
                    @Override public void onMessage(WebSocket value, String message) { if (message.equals("server-message")) value.close(1000, "private-close"); }
                    @Override public void onClosed(WebSocket value, int code, String reason) { closed.countDown(); }
                    @Override public void onFailure(WebSocket value, Throwable error, Response response) { failure.set(error); closed.countDown(); }
                });
            assertTrue(received.await(5, TimeUnit.SECONDS)); assertTrue(closed.await(5, TimeUnit.SECONDS)); assertNull(failure.get());
            WebSocketSnapshot snapshot = history.snapshot();
            assertTrue(snapshot.events.stream().allMatch(event -> event.source == WebSocketEvent.Source.OKHTTP));
            assertTrue(snapshot.events.stream().anyMatch(event -> event.outcome == WebSocketEvent.Outcome.QUEUED));
            assertTrue(snapshot.events.stream().anyMatch(event -> event.outcome == WebSocketEvent.Outcome.RECEIVED));
            assertEquals(WebSocketEvent.Kind.CLOSED, snapshot.events.get(snapshot.events.size() - 1).kind);
        } finally {
            if (socket[0] != null) socket[0].cancel();
            client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll();
        }
    }
}
