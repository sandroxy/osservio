package com.sandrox.osservio.okhttp;

import com.sandrox.osservio.core.CaptureController;
import com.sandrox.osservio.core.HTTPMetrics;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.HttpUrl;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/** Optional factory paired with OsservioInterceptor. The supplied host factory keeps every callback. */
public final class OsservioEventListener implements EventListener.Factory {
    private static final ThreadLocal<Object> TLS_THREAD = new ThreadLocal<Object>() {
        @Override protected Object initialValue() { return new Object(); }
    };
    final CaptureController controller;
    private final EventListener.Factory host;
    private final ReentrantLock lock = new ReentrantLock();
    private final WeakHashMap<Call, HTTPMetricsBuffer> calls = new WeakHashMap<>();
    public OsservioEventListener(CaptureController controller) { this(controller, call -> EventListener.NONE); }
    public OsservioEventListener(CaptureController controller, EventListener.Factory host) {
        this.controller = Objects.requireNonNull(controller, "controller"); this.host = Objects.requireNonNull(host, "host");
    }
    @Override public EventListener create(Call call) {
        EventListener target = host.create(call);
        CaptureController.Status status = controller.status();
        if (status == null || status.mode != CaptureController.Mode.DETAILED || !lock.tryLock()) return target;
        final HTTPMetricsBuffer buffer;
        try {
            if (calls.size() >= 64) return target;
            buffer = new HTTPMetricsBuffer(); calls.put(call, buffer);
        } finally { lock.unlock(); }
        return new Listener(target, buffer);
    }
    HTTPMetricsBuffer attach(Call call) {
        if (!lock.tryLock()) return null;
        try { HTTPMetricsBuffer value = calls.get(call); return value != null && value.activate() ? value : null; }
        finally { lock.unlock(); }
    }
    private void remove(Call call) {
        if (!lock.tryLock()) return;
        try { calls.remove(call); } finally { lock.unlock(); }
    }
    private final class Listener extends EventListener {
        final EventListener target;
        final HTTPMetricsBuffer buffer;
        Listener(EventListener target, HTTPMetricsBuffer buffer) { this.target = target; this.buffer = buffer; }
        @Override public void callStart(Call call) {
            target.callStart(call);
        }
        @Override public void dispatcherQueueStart(Call call, Dispatcher dispatcher) {
            target.dispatcherQueueStart(call, dispatcher);
        }
        @Override public void dispatcherQueueEnd(Call call, Dispatcher dispatcher) {
            target.dispatcherQueueEnd(call, dispatcher);
        }
        @Override public void proxySelectStart(Call call, HttpUrl url) {
            target.proxySelectStart(call, url);
        }
        @Override public void proxySelectEnd(Call call, HttpUrl url, List<Proxy> proxies) {
            target.proxySelectEnd(call, url, proxies);
        }
        @Override public void dnsStart(Call call, String domainName) {
            try { buffer.start(HTTPMetrics.Phase.DNS, domainName); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.dnsStart(call, domainName);
        }
        @Override public void dnsEnd(Call call, String domainName, List<InetAddress> inetAddressList) {
            try { buffer.end(HTTPMetrics.Phase.DNS, domainName); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.dnsEnd(call, domainName, inetAddressList);
        }
        @Override public void connectStart(Call call, InetSocketAddress inetSocketAddress, Proxy proxy) {
            try { buffer.start(HTTPMetrics.Phase.CONNECT, Arrays.asList(inetSocketAddress, proxy)); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.connectStart(call, inetSocketAddress, proxy);
        }
        @Override public void secureConnectStart(Call call) {
            try { buffer.start(HTTPMetrics.Phase.TLS, TLS_THREAD.get()); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.secureConnectStart(call);
        }
        @Override public void secureConnectEnd(Call call, Handshake handshake) {
            try { buffer.end(HTTPMetrics.Phase.TLS, TLS_THREAD.get()); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.secureConnectEnd(call, handshake);
        }
        @Override public void connectEnd(Call call, InetSocketAddress inetSocketAddress, Proxy proxy, Protocol protocol) {
            try { buffer.end(HTTPMetrics.Phase.CONNECT, Arrays.asList(inetSocketAddress, proxy)); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.connectEnd(call, inetSocketAddress, proxy, protocol);
        }
        @Override public void connectFailed(Call call, InetSocketAddress inetSocketAddress, Proxy proxy, Protocol protocol, IOException ioe) {
            try { buffer.end(HTTPMetrics.Phase.CONNECT, Arrays.asList(inetSocketAddress, proxy)); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.connectFailed(call, inetSocketAddress, proxy, protocol, ioe);
        }
        @Override public void connectionAcquired(Call call, Connection connection) {
            target.connectionAcquired(call, connection);
        }
        @Override public void connectionReleased(Call call, Connection connection) {
            target.connectionReleased(call, connection);
        }
        @Override public void requestHeadersStart(Call call) {
            try { buffer.start(HTTPMetrics.Phase.REQUEST_HEADERS, null); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.requestHeadersStart(call);
        }
        @Override public void requestHeadersEnd(Call call, Request request) {
            try { buffer.end(HTTPMetrics.Phase.REQUEST_HEADERS, null); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.requestHeadersEnd(call, request);
        }
        @Override public void requestBodyStart(Call call) {
            try { buffer.start(HTTPMetrics.Phase.REQUEST_BODY, null); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.requestBodyStart(call);
        }
        @Override public void requestBodyEnd(Call call, long byteCount) {
            try { buffer.end(HTTPMetrics.Phase.REQUEST_BODY, null); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.requestBodyEnd(call, byteCount);
        }
        @Override public void requestFailed(Call call, IOException ioe) {
            target.requestFailed(call, ioe);
        }
        @Override public void responseHeadersStart(Call call) {
            try { buffer.start(HTTPMetrics.Phase.RESPONSE_HEADERS, null); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.responseHeadersStart(call);
        }
        @Override public void responseHeadersEnd(Call call, Response response) {
            try { buffer.end(HTTPMetrics.Phase.RESPONSE_HEADERS, null); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.responseHeadersEnd(call, response);
        }
        @Override public void responseBodyStart(Call call) {
            try { buffer.start(HTTPMetrics.Phase.RESPONSE_BODY, null); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.responseBodyStart(call);
        }
        @Override public void responseBodyEnd(Call call, long byteCount) {
            try { buffer.end(HTTPMetrics.Phase.RESPONSE_BODY, null); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.responseBodyEnd(call, byteCount);
        }
        @Override public void responseFailed(Call call, IOException ioe) {
            target.responseFailed(call, ioe);
        }
        @Override public void callEnd(Call call) {
            try { remove(call); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.callEnd(call);
        }
        @Override public void callFailed(Call call, IOException ioe) {
            try { remove(call); } catch (RuntimeException ignored) { /* Observation cannot change host callbacks. */ }
            target.callFailed(call, ioe);
        }
        @Override public void canceled(Call call) {
            target.canceled(call);
        }
        @Override public void satisfactionFailure(Call call, Response response) {
            target.satisfactionFailure(call, response);
        }
        @Override public void cacheHit(Call call, Response response) {
            target.cacheHit(call, response);
        }
        @Override public void cacheMiss(Call call) {
            target.cacheMiss(call);
        }
        @Override public void cacheConditionalHit(Call call, Response cachedResponse) {
            target.cacheConditionalHit(call, cachedResponse);
        }
        @Override public void retryDecision(Call call, IOException exception, boolean retry) {
            target.retryDecision(call, exception, retry);
        }
        @Override public void followUpDecision(Call call, Response networkResponse, Request nextRequest) {
            target.followUpDecision(call, networkResponse, nextRequest);
        }
    }
}
