# Network integration coverage

Osservio observes selected host-owned networking APIs. It does not globally
intercept traffic. [Android integration](../native/android/README.md) and
[iOS integration](../native/ios/README.md) describe local source setup. Both
collectors submit observations through [the HTTP contract](http.md), including
its default body omission, finite capture lifetime, and bounded memory policy.

## OkHttp

Install `OsservioInterceptor` once as an application interceptor on the client
that sends the requests of interest. The adapter compiles and runs JVM tests
against OkHttp 5.5.1. Its Android artifact requires a host with compileSdk 37 or
later; the base Osservio libraries still compile with SDK 36. An API 36 consumer
uses OkHttp 5.4.0 only for offline loopback compatibility checks. This test
configuration is not a recommendation to downgrade a production client: 5.5.1
includes certificate-blocking protections described in the
[upstream changelog](https://github.com/lysine-dev/okhttp/blob/parent-5.5.1/CHANGELOG.md).
The host selects and maintains its supported runtime and toolchain. The
adapter does not bring an OkHttp runtime into the host or change TLS, cookies,
cache, proxies, timeouts, redirect policy, authentication, or request headers.

One record represents one application-level call. Automatic retries and
redirects are not separate wire exchanges. The initial URL and final response
URL are sanitized separately. Request metadata reflects the observation point,
before OkHttp's later automatic header processing. Header/body observations do
not measure packets or encrypted transfer size.

Request bytes are copied only as the host writes its body. The wrapper forwards
content length, content type, and one-shot/duplex flags; it never writes a body
just to inspect it. It queries request-body metadata to select capture behavior;
custom body implementations must keep those metadata methods side-effect free.
Repeated writes during authentication or transport retry
make the request body incomplete instead of concatenating several attempts.
Duplex request bodies pass through unchanged and are not inspected.

Response observation follows source reads initiated by the host. It does not
force reads or drain a body when the host closes it. Okio buffering may observe
more bytes than the application has consumed from its buffer; observed bytes
are not a count of bytes consumed by business logic. EOF, or close after the
known content length is observed, establishes response-body completion. An
earlier close is distinct from an empty response. Closing still reaches the
original source, and original read/write exceptions reach the host unchanged.

Stopping capture does not cancel the call. Cancellation evidence uses OkHttp's
call state and does **not** prove that a user requested cancellation. In
particular, a call-wide timeout can cancel a call before the interceptor sees
OkHttp's final timeout exception; it is recorded as cancellation with no claimed
cause. A directly observed socket timeout has an explicit timeout category.
Raw exception descriptions are never copied into HTTP records.

HTTP 101 upgrade bodies remain owned by OkHttp. The handshake can be recorded,
but WebSocket frames and other upgraded protocol data are not observed by this
adapter. Request replay, mocking, and traffic alteration are not provided.

## URLSession

`ObservedURLSession` wraps a supplied URLSession and exposes completion-handler
data tasks plus the native async data API on iOS 15 and later. It returns the
original task, initially suspended. The host owns resume, cancellation, delegate
behavior, configuration, and invalidation. Requests made directly on the
underlying session bypass observation.

The initial request's supplied `httpBody` Data can be inspected under policy.
This is a snapshot of the submitted buffer, not proof that all bytes were sent
or accepted. Input streams are never opened or consumed for inspection. Their
request content is reported incomplete. Redirects, authentication retries, and
delegate-supplied replacement bodies do not become separate body snapshots.

Response Data is observed when Foundation delivers the completion result. The
host receives the same complete Data, response, and error; the SDK stores only
its permitted bounded prefix. Ordinary URLSession HTTP decoding has already
occurred at that point. Custom URL protocols must preserve that data contract;
the SDK does not implement or alter their decoding behavior. Errors are mapped
from stable URL loading error codes without storing localized descriptions.
An unsupported non-HTTP result ends observation without inventing a network
failure for a successful host operation.

For completion-handler tasks, observation starts at task creation, so duration
can include time before the host resumes the task. Response headers are not
available in the inspector before completion through this integration. Native
streaming delegate data tasks, uploads, downloads, background-session recovery,
and WebSocket tasks are not intercepted. Those APIs remain usable on the host's
original session. No global swizzling, delegate proxy, or URLProtocol injection
is installed by the SDK.

## Evidence limits

The `source` field distinguishes host-supplied, OkHttp, and URLSession
observations. Do not compare their durations or body byte counts as though they
were identical measurements of on-wire traffic. HTTP data currently remains in
memory; it is not an incident archive and cannot survive termination.

Overload, contention, capture expiry, unsupported content, partial reads, and
storage eviction can leave gaps. A pending record means no terminal observation
was stored, not necessarily that a request is still running. The SDK does not
retry a host request to recover missing evidence. Inspectors and exporters must
preserve these distinctions.

Android integration tests use real OkHttp calls, loopback MockWebServer
exchanges, and instrumented sources. URLSession tests exercise Foundation with a
host-configured synthetic URLProtocol, including redirects and cancellation.
These checks establish specific API behavior on the development machine, not
physical-device coverage, production performance, or support for every network
stack.
