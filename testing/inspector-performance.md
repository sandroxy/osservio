# Inspecting UI performance

The native inspector emits fixed, content-free profiling markers. iOS uses
Points of Interest in the `com.sandrox.osservio` subsystem; Android uses `Trace`
sections visible in Android Studio System Trace or Perfetto when app tracing is
enabled by the host/device. These markers do not start tracing, retain samples,
add a timer or collect diagnostic content. No record IDs, query text, URLs,
account details or payloads are included.

| Marker | Measured work |
| --- | --- |
| `Osservio.Inspector.open` / `.collapse` | Synchronous session presentation work; excludes completion of the animation. |
| `Osservio.Inspector.tab` | Selection callback point, not end-to-end tab rendering duration. |
| `Osservio.Log.refresh` / `Osservio.Network.refresh` | Visible polling, snapshot acquisition and synchronous filtering/scheduling. |
| `Osservio.Log.apply` / `Osservio.Network.apply` | Applying the current rows and feedback, excluding later system layout/drawing. |
| `Osservio.Record.search` | Bounded background record/content search; excludes queue waiting. |
| `Osservio.JSON.parse` / `Osservio.Form.parse` | Bounded background parsing; excludes queue waiting and UI delivery. |

iOS skips interval setup when signposts are disabled. Android 10+ checks whether
tracing is enabled before beginning a section; on API 23–28 the platform's
`beginSection` is itself inactive without tracing. Each interval ends in the
same synchronous operation, including cancellation and early returns. The
markers use system profiling APIs, not Osservio's logging or breadcrumb paths.

## Reproducible review

Use an isolated synthetic consumer scenario; never delete a user's history to
prepare a measurement. Record the source commit, artifact hash, device, OS,
build mode, history budgets and traffic rate. Measure cold presentation once,
then repeated restore, tab switches, scrolling and filtering with full bounded
histories. Include JSON/form content and cross-connection message search.

Correlate these SDK intervals with main-thread scheduling, frame deadlines,
layout/drawing, CPU and process memory in the system profiler. Their duration
alone is not frame latency. Compare off, baseline, detailed capture, and visible
inspection separately. Pause on an older record, enter details, hide the
inspector and background the host; hidden viewers must not keep polling.

Use the [sustained capture workload](capture-load.md) for capture/store bounds
and preliminary process measurements. It does not render UI. Shared runners,
simulators and short runs do not establish release-device latency, energy or
leak guarantees. Keep physical-device review separate from automated correctness.

Change incremental querying or list update algorithms only when a trace locates
a material cost. Any replacement must preserve stable reading positions,
filter generations, immutable detail reads, bounded indices and inactive-view
suspension; reducing a trace duration is not sufficient if these regress.
