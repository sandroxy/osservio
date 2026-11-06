package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.SystemDiagnostic;

/** Formats only the immutable saved source facts, without querying the current environment. */
final class SystemDiagnosticPresentation {
    private SystemDiagnosticPresentation() { }
    static String kind(Context context, SystemDiagnostic.Kind kind) {
        int label;
        switch (kind) {
            case MANAGED_CRASH: label = R.string.osservio_diagnostic_kind_managed_crash; break;
            case NATIVE_CRASH: label = R.string.osservio_diagnostic_kind_native_crash; break;
            case ANR: label = R.string.osservio_diagnostic_kind_anr; break;
            case LOW_MEMORY_TERMINATION: label = R.string.osservio_diagnostic_kind_low_memory_termination; break;
            case SIGNAL_TERMINATION: label = R.string.osservio_diagnostic_kind_signal_termination; break;
            case OTHER_EXIT: label = R.string.osservio_diagnostic_kind_other_exit; break;
            case CRASH: label = R.string.osservio_diagnostic_kind_crash; break;
            case HANG: label = R.string.osservio_diagnostic_kind_hang; break;
            default: throw new IllegalStateException("Unknown system diagnostic kind");
        }
        return context.getString(label);
    }
    static String summary(Context context, SystemDiagnostic diagnostic) {
        StringBuilder text = new StringBuilder();
        field(text, context, R.string.osservio_diagnostic_source, context.getString(diagnostic.source == SystemDiagnostic.Source.ANDROID_EXIT_INFO
            ? R.string.osservio_diagnostic_source_android_exit_info : R.string.osservio_diagnostic_source_metric_kit));
        field(text, context, R.string.osservio_diagnostic_kind, kind(context, diagnostic.kind));
        SystemDiagnostic.AndroidExit exit = diagnostic.androidExit;
        if (exit != null) {
            field(text, context, R.string.osservio_diagnostic_exit_time, RuntimePresentation.date(diagnostic.intervalStartMillis));
            field(text, context, R.string.osservio_diagnostic_reason, Integer.toString(exit.reasonCode));
            field(text, context, R.string.osservio_diagnostic_status, Integer.toString(exit.status));
            field(text, context, R.string.osservio_env_process_id, Integer.toString(exit.processID));
            field(text, context, R.string.osservio_diagnostic_importance, Integer.toString(exit.importance));
            field(text, context, R.string.osservio_diagnostic_pss, exit.pssKiB == null ? null : exit.pssKiB + " KiB");
            field(text, context, R.string.osservio_diagnostic_rss, exit.rssKiB == null ? null : exit.rssKiB + " KiB");
        }
        SystemDiagnostic.MetricKit metric = diagnostic.metricKit;
        if (metric != null) {
            field(text, context, R.string.osservio_diagnostic_interval_start, RuntimePresentation.date(diagnostic.intervalStartMillis));
            field(text, context, R.string.osservio_diagnostic_interval_end, RuntimePresentation.date(diagnostic.intervalEndMillis));
            for (SystemDiagnostic.MetadataKey key : SystemDiagnostic.MetadataKey.values()) {
                SystemDiagnostic.Text value = metric.metadata.get(key);
                field(text, context, metadataLabel(key), value == null ? null
                    : value.value + (value.isTruncated ? " · " + context.getString(R.string.osservio_truncated) : ""));
            }
            if (diagnostic.kind == SystemDiagnostic.Kind.CRASH) {
                field(text, context, R.string.osservio_diagnostic_signal, metric.signal == null ? null : metric.signal.toString());
                field(text, context, R.string.osservio_diagnostic_exception_type, metric.exceptionType == null ? null : metric.exceptionType.toString());
                field(text, context, R.string.osservio_diagnostic_exception_code, metric.exceptionCode);
            } else {
                field(text, context, R.string.osservio_diagnostic_hang_duration, metric.hangDurationMillis == null ? null : metric.hangDurationMillis + " ms");
            }
        }
        return text.append("\n\n").append(context.getString(diagnostic.source == SystemDiagnostic.Source.ANDROID_EXIT_INFO
            ? R.string.osservio_diagnostic_android_hint : R.string.osservio_diagnostic_metric_hint)).toString();
    }
    private static void field(StringBuilder text, Context context, int label, String value) {
        if (text.length() != 0) text.append('\n');
        text.append(context.getString(label)).append(": ").append(value == null ? context.getString(R.string.osservio_runtime_unavailable) : value);
    }
    private static int metadataLabel(SystemDiagnostic.MetadataKey key) {
        switch (key) {
            case APP_VERSION: return R.string.osservio_env_app_version;
            case APP_BUILD: return R.string.osservio_env_app_build;
            case OS_VERSION: return R.string.osservio_env_os_version;
            case DEVICE_MODEL: return R.string.osservio_env_device_model;
            default: throw new IllegalStateException("Unknown diagnostic metadata key");
        }
    }
}
