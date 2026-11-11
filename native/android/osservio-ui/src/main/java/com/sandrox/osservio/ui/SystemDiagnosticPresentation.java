package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.AppleSignalCrash;
import com.sandrox.osservio.core.AndroidTombstone;
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
    static java.util.List<Field> fields(Context context, SystemDiagnostic diagnostic) {
        java.util.List<Field> text = new java.util.ArrayList<>();
        int source;
        switch (diagnostic.source) {
            case ANDROID_EXIT_INFO: source = R.string.osservio_diagnostic_source_android_exit_info; break;
            case METRIC_KIT: source = R.string.osservio_diagnostic_source_metric_kit; break;
            case APPLE_SIGNAL: source = R.string.osservio_diagnostic_source_apple_signal; break;
            default: throw new IllegalStateException("Unknown diagnostic source");
        }
        field(text, context, R.string.osservio_diagnostic_source, context.getString(source));
        field(text, context, R.string.osservio_diagnostic_kind, kind(context, diagnostic.kind));
        SystemDiagnostic.AndroidExit exit = diagnostic.androidExit;
        if (exit != null) {
            field(text, context, R.string.osservio_diagnostic_exit_time, RuntimePresentation.date(context, diagnostic.intervalStartMillis));
            field(text, context, R.string.osservio_diagnostic_reason, exitReason(context, exit.reasonCode));
            field(text, context, R.string.osservio_diagnostic_status, Integer.toString(exit.status));
            field(text, context, R.string.osservio_env_process_id, Integer.toString(exit.processID));
            field(text, context, R.string.osservio_diagnostic_importance, Integer.toString(exit.importance));
            field(text, context, R.string.osservio_diagnostic_pss, exit.pssKiB == null ? null : exit.pssKiB + " KiB");
            field(text, context, R.string.osservio_diagnostic_rss, exit.rssKiB == null ? null : exit.rssKiB + " KiB");
            field(text, context, R.string.osservio_tombstone_state, TombstonePresentation.state(context, exit.tombstoneState));
            AndroidTombstone trace = exit.tombstone;
            if (trace != null) {
                field(text, context, R.string.osservio_env_architecture, TombstonePresentation.architecture(trace.architectureCode));
                field(text, context, R.string.osservio_diagnostic_signal, Integer.toString(trace.signalNumber));
                field(text, context, R.string.osservio_crash_code, Integer.toString(trace.signalCode));
                field(text, context, R.string.osservio_tombstone_thread_id, Integer.toString(trace.threadID));
                field(text, context, R.string.osservio_crash_fault_address, trace.faultAddress == null ? null : SignalCrashPresentation.address(trace.faultAddress));
                field(text, context, R.string.osservio_crash_frame_count, SignalCrashPresentation.count(context, trace.frames.size(), trace.frameLimitReached()));
            }
        }
        SystemDiagnostic.MetricKit metric = diagnostic.metricKit;
        if (metric != null) {
            field(text, context, R.string.osservio_diagnostic_interval_start, RuntimePresentation.date(context, diagnostic.intervalStartMillis));
            field(text, context, R.string.osservio_diagnostic_interval_end, RuntimePresentation.date(context, diagnostic.intervalEndMillis));
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
        AppleSignalCrash signal = diagnostic.appleSignal;
        if (signal != null) {
            field(text, context, R.string.osservio_crash_time, RuntimePresentation.date(context, signal.timestampMillis));
            field(text, context, R.string.osservio_crash_report_id, signal.reportID);
            field(text, context, R.string.osservio_diagnostic_signal, signal.signal.wireName);
            field(text, context, R.string.osservio_crash_code, signal.code);
            field(text, context, R.string.osservio_crash_fault_address, SignalCrashPresentation.address(signal.faultAddress));
            field(text, context, R.string.osservio_crash_thread, signal.threadNumber == null ? null : signal.threadNumber.toString());
            field(text, context, R.string.osservio_crash_frame_count, SignalCrashPresentation.count(context, signal.frames.size(), signal.frameLimitReached()));
            field(text, context, R.string.osservio_crash_image_count, SignalCrashPresentation.count(context, signal.images.size(), signal.imageLimitReached()));
            field(text, context, R.string.osservio_crash_image_cache, context.getString(signal.imageCacheIncomplete
                ? R.string.osservio_crash_image_cache_excluded : R.string.osservio_crash_image_cache_no_exclusion));
            for (SystemDiagnostic.MetadataKey key : SystemDiagnostic.MetadataKey.values()) {
                SystemDiagnostic.Text value = signal.metadata.get(key); field(text, context, metadataLabel(key), value == null ? null : value.value);
            }
        }
        return text;
    }
    static final class Field {
        final int label; final String value;
        Field(int label, String value) { this.label = label; this.value = value; }
        boolean primary() { return label == R.string.osservio_diagnostic_source || label == R.string.osservio_diagnostic_kind || label == R.string.osservio_diagnostic_exit_time
            || label == R.string.osservio_diagnostic_reason || label == R.string.osservio_diagnostic_signal || label == R.string.osservio_diagnostic_interval_start
            || label == R.string.osservio_diagnostic_interval_end || label == R.string.osservio_diagnostic_hang_duration || label == R.string.osservio_crash_time || label == R.string.osservio_tombstone_state; }
    }
    static String exitReason(Context context, int code) {
        int[] keys = {R.string.osservio_ui_exit_reason_0, R.string.osservio_ui_exit_reason_1, R.string.osservio_ui_exit_reason_2, R.string.osservio_ui_exit_reason_3, R.string.osservio_ui_exit_reason_4, R.string.osservio_ui_exit_reason_5, R.string.osservio_ui_exit_reason_6, R.string.osservio_ui_exit_reason_7, R.string.osservio_ui_exit_reason_8, R.string.osservio_ui_exit_reason_9, R.string.osservio_ui_exit_reason_10, R.string.osservio_ui_exit_reason_11, R.string.osservio_ui_exit_reason_12, R.string.osservio_ui_exit_reason_13, R.string.osservio_ui_exit_reason_14, R.string.osservio_ui_exit_reason_15, R.string.osservio_ui_exit_reason_16};
        return code >= 0 && code < keys.length ? context.getString(keys[code]) + " (" + code + ")" : Integer.toString(code);
    }
    private static void field(java.util.List<Field> fields, Context context, int label, String value) {
        fields.add(new Field(label, value == null ? context.getString(R.string.osservio_runtime_unavailable) : value));
    }
    static String hint(Context context, SystemDiagnostic diagnostic) {
        switch (diagnostic.source) {
            case ANDROID_EXIT_INFO: return context.getString(R.string.osservio_diagnostic_android_hint);
            case METRIC_KIT: return context.getString(R.string.osservio_diagnostic_metric_hint);
            case APPLE_SIGNAL: return context.getString(R.string.osservio_crash_hint);
            default: throw new IllegalStateException("Unknown diagnostic source");
        }
    }
    static String summary(Context context, SystemDiagnostic diagnostic) {
        StringBuilder text = new StringBuilder();
        for (Field field : fields(context, diagnostic)) {
            if (text.length() != 0) text.append('\n');
            text.append(context.getString(field.label)).append(": ").append(field.value);
        }
        return text.append("\n\n").append(hint(context, diagnostic)).toString();
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
