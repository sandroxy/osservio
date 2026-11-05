package com.sandrox.osservio.okhttp;

import com.sandrox.osservio.core.CaptureController;
import com.sandrox.osservio.core.HTTPHeader;
import com.sandrox.osservio.core.WebSocketEvent;
import com.sandrox.osservio.core.WebSocketRecorder;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.SSLException;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Explicitly wraps a host factory. Owns no transport, replay, ping, reconnect, or message queue. */
public final class ObservedWebSocketFactory implements WebSocket.Factory {
    private final WebSocket.Factory factory;
    private final WebSocketRecorder recorder;
    public ObservedWebSocketFactory(WebSocket.Factory factory, WebSocketRecorder recorder) {
        this.factory = Objects.requireNonNull(factory, "factory"); this.recorder = Objects.requireNonNull(recorder, "recorder");
    }
    @Override public WebSocket newWebSocket(Request request, WebSocketListener listener) {
        Objects.requireNonNull(request, "request"); Objects.requireNonNull(listener, "listener");
        Socket socket = new Socket(recorder);
        socket.connecting(request);
        // Keep the host call outside diagnostic catch blocks. A host exception must never cause a retry.
        try { socket.bind(factory.newWebSocket(request, socket.listener(listener))); }
        catch (RuntimeException failure) {
            socket.observe(true, token -> recorder.lifecycle(token, WebSocketEvent.Kind.OBSERVATION_ENDED,
                Collections.emptyList(), null, null, null));
            throw failure;
        }
        return socket;
    }
    private interface Observation { void record(WebSocketRecorder.Token token); }
    private static final class Socket implements WebSocket {
        private final WebSocketRecorder recorder;
        private final AtomicReference<WebSocket> original = new AtomicReference<>();
        private final UUID connectionId = UUID.randomUUID();
        private final ReentrantLock lock = new ReentrantLock();
        private WebSocketRecorder.Token token;
        private volatile boolean ended;
        Socket(WebSocketRecorder recorder) { this.recorder = recorder; }
        void bind(WebSocket socket) { original.compareAndSet(null, socket); }
        @Override public Request request() { return original.get().request(); }
        @Override public long queueSize() { return original.get().queueSize(); }

        void connecting(Request request) {
            try {
                CaptureController.Status status = recorder.captureController.status();
                if (status != null && status.mode == CaptureController.Mode.DETAILED) {
                    token = recorder.begin(request.url().toString(), headers(request.headers()), WebSocketEvent.Source.OKHTTP,
                        false, connectionId).token;
                }
            } catch (RuntimeException diagnosticFailure) { token = null; }
        }
        void observe(boolean terminal, Observation observation) {
            if (ended) return;
            if (lock.isHeldByCurrentThread() || !lock.tryLock()) { if (terminal) ended = true; return; }
            try {
                if (ended) return;
                CaptureController.Status status = recorder.captureController.status();
                if (status == null) return;
                if (status.mode != CaptureController.Mode.DETAILED) { token = null; return; }
                if (token == null || !token.sessionId.equals(status.sessionId)) {
                    Request request = request();
                    token = recorder.begin(request.url().toString(), headers(request.headers()), WebSocketEvent.Source.OKHTTP,
                        true, connectionId).token;
                }
                if (token != null) observation.record(token);
            } catch (RuntimeException diagnosticFailure) {
                // Observation cannot change transport results or suppress host callbacks.
            } finally {
                if (terminal) ended = true;
                lock.unlock();
            }
        }
        @Override public boolean send(String text) {
            boolean accepted = original.get().send(text);
            observe(false, token -> recorder.text(token, WebSocketEvent.Direction.OUTGOING,
                accepted ? WebSocketEvent.Outcome.QUEUED : WebSocketEvent.Outcome.REJECTED, text));
            return accepted;
        }
        @Override public boolean send(ByteString bytes) {
            boolean accepted = original.get().send(bytes);
            observe(false, token -> recorder.binary(token, WebSocketEvent.Direction.OUTGOING,
                accepted ? WebSocketEvent.Outcome.QUEUED : WebSocketEvent.Outcome.REJECTED, bytes.size()));
            return accepted;
        }
        @Override public boolean close(int code, String reason) {
            boolean accepted = original.get().close(code, reason);
            if (accepted) observe(false, token -> recorder.lifecycle(token, WebSocketEvent.Kind.CLOSE_REQUESTED,
                Collections.emptyList(), null, code, null));
            return accepted;
        }
        @Override public void cancel() {
            original.get().cancel();
            observe(true, token -> recorder.lifecycle(token, WebSocketEvent.Kind.CANCEL_REQUESTED,
                Collections.emptyList(), null, null, null));
        }
        WebSocketListener listener(WebSocketListener host) {
            return new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) {
                    bind(socket);
                    observe(false, token -> recorder.lifecycle(token, WebSocketEvent.Kind.OPENED, headers(response.headers()), response.code(), null, null));
                    host.onOpen(Socket.this, response);
                }
                @Override public void onMessage(WebSocket socket, String text) {
                    bind(socket);
                    observe(false, token -> recorder.text(token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, text));
                    host.onMessage(Socket.this, text);
                }
                @Override public void onMessage(WebSocket socket, ByteString bytes) {
                    bind(socket);
                    observe(false, token -> recorder.binary(token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, bytes.size()));
                    host.onMessage(Socket.this, bytes);
                }
                @Override public void onClosing(WebSocket socket, int code, String reason) {
                    bind(socket);
                    observe(false, token -> recorder.lifecycle(token, WebSocketEvent.Kind.CLOSING, Collections.emptyList(), null, code, null));
                    host.onClosing(Socket.this, code, reason);
                }
                @Override public void onClosed(WebSocket socket, int code, String reason) {
                    bind(socket);
                    observe(true, token -> recorder.lifecycle(token, WebSocketEvent.Kind.CLOSED, Collections.emptyList(), null, code, null));
                    host.onClosed(Socket.this, code, reason);
                }
                @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                    bind(socket);
                    observe(true, token -> recorder.lifecycle(token, WebSocketEvent.Kind.FAILED,
                        response == null ? Collections.emptyList() : headers(response.headers()), response == null ? null : response.code(), null, failure(error)));
                    host.onFailure(Socket.this, error, response);
                }
            };
        }
    }
    private static List<HTTPHeader> headers(Headers headers) {
        List<HTTPHeader> result = new ArrayList<>();
        for (int i = 0; i < Math.min(33, headers.size()); i++) result.add(new HTTPHeader(headers.name(i), headers.value(i)));
        return result;
    }
    private static WebSocketEvent.Failure failure(Throwable error) {
        if (error instanceof SocketTimeoutException) return WebSocketEvent.Failure.TIMEOUT;
        if (error instanceof SSLException) return WebSocketEvent.Failure.TLS;
        if (error instanceof ConnectException || error instanceof UnknownHostException) return WebSocketEvent.Failure.CONNECTION;
        return error instanceof IOException ? WebSocketEvent.Failure.TRANSPORT : WebSocketEvent.Failure.UNKNOWN;
    }
}
