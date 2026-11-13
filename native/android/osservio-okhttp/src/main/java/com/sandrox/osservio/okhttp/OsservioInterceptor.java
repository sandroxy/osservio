package com.sandrox.osservio.okhttp;

import com.sandrox.osservio.core.CaptureController;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.HTTPHeader;
import com.sandrox.osservio.core.HTTPMetrics;
import com.sandrox.osservio.core.HTTPRecorder;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingSink;
import okio.ForwardingSource;
import okio.Okio;

/** Install once with addInterceptor, on a host-owned OkHttp client. No global hooks or client reconfiguration. */
public final class OsservioInterceptor implements Interceptor {
    private final HTTPRecorder recorder;
    private final OsservioEventListener metrics;

    public OsservioInterceptor(HTTPRecorder recorder) { this(recorder, null); }
    public OsservioInterceptor(HTTPRecorder recorder, OsservioEventListener metrics) {
        this.recorder = Objects.requireNonNull(recorder, "recorder"); this.metrics = metrics;
        if (metrics != null && metrics.controller != recorder.captureController) throw new IllegalArgumentException("Mismatched capture controller");
    }

    @Override public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        Prepared prepared;
        try { prepared = prepare(original, chain.call()); }
        catch (RuntimeException diagnosticFailure) { prepared = null; }
        // Host execution must remain outside diagnostic exception handling: it must never be retried here.
        if (prepared == null) return chain.proceed(original);
        Trace trace = prepared.trace;

        final Response response;
        try { response = chain.proceed(prepared.request); }
        catch (IOException | RuntimeException hostFailure) { trace.failed(hostFailure); throw hostFailure; }

        try {
            trace.protocol = response.protocol().toString();
            trace.cache = response.cacheResponse() != null ? response.networkResponse() != null ? HTTPMetrics.Cache.VALIDATED : HTTPMetrics.Cache.LOCAL
                : response.networkResponse() != null ? HTTPMetrics.Cache.NETWORK : HTTPMetrics.Cache.UNKNOWN;
            HTTPRecorder.Result result = recorder.receiveResponse(trace.token, response.code(), headers(response.headers()), false, response.request().url().toString());
            if (result != HTTPRecorder.Result.RECORDED) {
                trace.finish(HTTPEvent.State.CLOSED_EARLY, null, false);
                return response;
            }
            // Protocol upgrade bodies are owned by OkHttp's WebSocket/upgrade implementation.
            if (response.code() == 101) { trace.finish(HTTPEvent.State.COMPLETED, null, false); return response; }
            ResponseBody body = response.body();
            return response.newBuilder().body(responseBody(body, trace)).build();
        } catch (RuntimeException diagnosticFailure) {
            trace.finish(HTTPEvent.State.CLOSED_EARLY, null, false);
            return response;
        }
    }

    private final class Prepared {
        final Trace trace;
        final Request request;
        Prepared(Trace trace, Request request) { this.trace = trace; this.request = request; }
    }

    private Prepared prepare(Request original, Call call) {
        CaptureController.Status status = recorder.captureController.status();
        if (status == null || status.mode != CaptureController.Mode.DETAILED) return null;
        List<HTTPHeader> headers = headers(original.headers());
        RequestBody body = original.body();
        boolean duplex = body != null && body.isDuplex();
        if (body != null && !hasHeader(headers, "content-type")) {
            MediaType type = body.contentType();
            if (type != null) headers.add(0, new HTTPHeader("Content-Type", type.toString()));
        }
        HTTPRecorder.BeginResult begin = recorder.begin(original.method(), original.url().toString(), headers, HTTPEvent.Source.OKHTTP);
        if (begin.token == null) return null;
        Trace trace = new Trace(begin.token, call, body == null, metrics == null ? null : metrics.attach(call));
        // Duplex streams have independent lifetimes. Never wrap or attempt to replay their request body.
        Request observed = body == null || duplex ? original : original.newBuilder().method(original.method(), requestBody(body, trace)).build();
        return new Prepared(trace, observed);
    }

    private RequestBody requestBody(RequestBody delegate, Trace trace) {
        return new RequestBody() {
            @Override public MediaType contentType() { return delegate.contentType(); }
            @Override public long contentLength() throws IOException { return delegate.contentLength(); }
            @Override public boolean isDuplex() { return delegate.isDuplex(); }
            @Override public boolean isOneShot() { return delegate.isOneShot(); }
            @Override public void writeTo(BufferedSink sink) throws IOException {
                int attempt = trace.requestWrites.incrementAndGet();
                trace.requestComplete = false;
                if (attempt != 1) {
                    // Do not concatenate retried bodies into a misleading single payload.
                    trace.requestGap = true;
                    delegate.writeTo(sink);
                    return;
                }
                BufferedSink observing = Okio.buffer(new ForwardingSink(sink) {
                    @Override public void write(Buffer source, long byteCount) throws IOException {
                        trace.observe(HTTPRecorder.Direction.REQUEST, source, 0, byteCount);
                        super.write(source, byteCount);
                    }
                });
                delegate.writeTo(observing);
                observing.emit();
                trace.requestComplete = !trace.requestGap;
            }
        };
    }

    private ResponseBody responseBody(ResponseBody delegate, Trace trace) {
        long expectedBytes = delegate.contentLength();
        BufferedSource source = Okio.buffer(new ForwardingSource(delegate.source()) {
            private long received;
            private boolean eof;

            @Override public long read(Buffer sink, long byteCount) throws IOException {
                long offset = sink.size();
                final long count;
                try { count = super.read(sink, byteCount); }
                catch (IOException | RuntimeException hostFailure) { trace.failed(hostFailure); throw hostFailure; }
                if (count > 0) {
                    received = count > Long.MAX_VALUE - received ? Long.MAX_VALUE : received + count;
                    trace.observe(HTTPRecorder.Direction.RESPONSE, sink, offset, count);
                } else if (count == -1) {
                    eof = true;
                    trace.finish(HTTPEvent.State.COMPLETED, null, true);
                }
                return count;
            }

            @Override public void close() throws IOException {
                try { super.close(); }
                catch (IOException | RuntimeException hostFailure) { trace.failed(hostFailure); throw hostFailure; }
                boolean complete = eof || (expectedBytes >= 0 && received == expectedBytes);
                trace.finish(complete ? HTTPEvent.State.COMPLETED : HTTPEvent.State.CLOSED_EARLY, null, complete);
            }
        });
        return new ResponseBody() {
            @Override public MediaType contentType() { return delegate.contentType(); }
            @Override public long contentLength() { return delegate.contentLength(); }
            @Override public BufferedSource source() { return source; }
        };
    }

    private final class Trace {
        final HTTPRecorder.Token token;
        final Call call;
        final HTTPMetricsBuffer metrics;
        volatile String protocol;
        volatile HTTPMetrics.Cache cache = HTTPMetrics.Cache.UNKNOWN;
        final AtomicInteger requestWrites = new AtomicInteger();
        final AtomicBoolean terminal = new AtomicBoolean();
        volatile boolean requestComplete;
        volatile boolean requestGap;
        volatile boolean responseGap;
        Trace(HTTPRecorder.Token token, Call call, boolean requestComplete, HTTPMetricsBuffer metrics) {
            this.token = token; this.call = call; this.requestComplete = requestComplete; this.metrics = metrics;
        }
        void observe(HTTPRecorder.Direction direction, Buffer buffer, long offset, long count) {
            try {
                HTTPRecorder.Result result = recorder.observeBody(token, direction, count, requested -> {
                    Buffer copy = new Buffer();
                    buffer.copyTo(copy, offset, requested);
                    return copy.readByteArray();
                });
                if (result != HTTPRecorder.Result.RECORDED) gap(direction);
            } catch (RuntimeException diagnosticFailure) { gap(direction); }
        }
        private void gap(HTTPRecorder.Direction direction) {
            if (direction == HTTPRecorder.Direction.REQUEST) requestGap = true; else responseGap = true;
        }
        void finish(HTTPEvent.State state, HTTPEvent.Failure failure, boolean responseComplete) {
            if (!terminal.compareAndSet(false, true)) return;
            try {
                HTTPMetrics observed = metrics != null ? metrics.snapshot(protocol, cache) : protocol == null ? null
                    : new HTTPMetrics(java.util.Collections.emptyList(), protocol, cache, null, false);
                recorder.finish(token, state, failure, requestComplete && !requestGap, responseComplete && !responseGap, observed);
            }
            catch (RuntimeException diagnosticFailure) { /* Diagnostics never replace a host result or exception. */ }
        }
        void failed(Exception failure) {
            if (failure instanceof SocketTimeoutException) { finish(HTTPEvent.State.FAILED, HTTPEvent.Failure.TIMEOUT, false); return; }
            // isCanceled also covers OkHttp's call-wide timeout. It does not establish who cancelled the call.
            try {
                if (call.isCanceled()) { finish(HTTPEvent.State.CANCELLED, null, false); return; }
            } catch (RuntimeException diagnosticFailure) {
                // An unavailable cancellation hint must not replace the original host failure.
            }
            HTTPEvent.Failure category = failure instanceof SSLException ? HTTPEvent.Failure.TLS
                : failure instanceof ConnectException || failure instanceof UnknownHostException ? HTTPEvent.Failure.CONNECTION
                : failure instanceof IOException ? HTTPEvent.Failure.TRANSPORT : HTTPEvent.Failure.UNKNOWN;
            finish(HTTPEvent.State.FAILED, category, false);
        }
    }

    private static List<HTTPHeader> headers(Headers headers) {
        List<HTTPHeader> result = new ArrayList<>();
        // The extra entry lets Core report that the input exceeded its processing limit.
        for (int i = 0; i < Math.min(headers.size(), 33); i++) result.add(new HTTPHeader(headers.name(i), headers.value(i)));
        return result;
    }
    private static boolean hasHeader(List<HTTPHeader> headers, String name) {
        for (HTTPHeader header : headers) if (name.equalsIgnoreCase(header.name)) return true;
        return false;
    }
}
