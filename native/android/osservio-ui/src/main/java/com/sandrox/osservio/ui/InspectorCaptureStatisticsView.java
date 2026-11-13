package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.CaptureStatistics;

/** Fixed snapshot of recorder operations; no timer or event subscription. */
final class InspectorCaptureStatisticsView {
    private InspectorCaptureStatisticsView() { }
    static InspectorDetailPage create(Context context, CaptureStatistics statistics) {
        CaptureStatistics.Snapshot snapshot = statistics.snapshot();
        InspectorDetailPage page = new InspectorDetailPage(context);
        page.section(""); page.field(R.string.osservio_stats_window, () -> RuntimePresentation.date(context, snapshot.startedAtMillis));
        page.note(R.string.osservio_stats_hint);
        if (snapshot.isIncomplete) page.note(R.string.osservio_stats_incomplete);
        int[] sources = {R.string.osservio_stats_logs, R.string.osservio_stats_http, R.string.osservio_stats_web_socket, R.string.osservio_stats_breadcrumbs};
        int[] outcomes = {R.string.osservio_stats_accepted, R.string.osservio_stats_inactive, R.string.osservio_stats_busy,
            R.string.osservio_stats_rate_limited, R.string.osservio_stats_active_limit, R.string.osservio_stats_oversized,
            R.string.osservio_stats_protected_capacity, R.string.osservio_stats_invalid, R.string.osservio_stats_missing,
            R.string.osservio_stats_stale, R.string.osservio_stats_sink_failure, R.string.osservio_stats_sequence_exhausted};
        boolean any = false;
        for (CaptureStatistics.Source source : CaptureStatistics.Source.values()) {
            boolean section = false;
            for (CaptureStatistics.Outcome outcome : CaptureStatistics.Outcome.values()) {
                long count = snapshot.count(source, outcome);
                if (count == 0) continue;
                if (!section) { page.section(sources[source.ordinal()]); section = true; any = true; }
                page.field(outcomes[outcome.ordinal()], count);
            }
        }
        if (!any) { page.section(""); page.value(() -> context.getString(R.string.osservio_stats_empty)); }
        return page;
    }
}
