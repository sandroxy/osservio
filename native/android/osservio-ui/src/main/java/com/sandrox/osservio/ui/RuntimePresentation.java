package com.sandrox.osservio.ui;

import android.content.Context;
import android.text.format.Formatter;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import java.text.DateFormat;
import java.util.Date;

final class RuntimePresentation {
    private RuntimePresentation() { }
    static String date(long millis) { return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date(millis)); }
    static String value(Context context, EnvironmentSnapshot.Value value, EnvironmentSnapshot.Key key) {
        String result;
        switch (value.fact.type) {
            case TEXT: result = value.fact.text; break;
            case NUMBER:
                result = key.name().endsWith("_BYTES")
                    ? Formatter.formatFileSize(context, value.fact.number) + " (" + value.fact.number + " B)"
                    : value.fact.number + (key == EnvironmentSnapshot.Key.BATTERY_PERCENT ? "%" : "");
                break;
            case FLAG: result = context.getString(value.fact.flag ? R.string.osservio_runtime_yes : R.string.osservio_runtime_no); break;
            case UNAVAILABLE: result = context.getString(R.string.osservio_runtime_unavailable); break;
            case UNSUPPORTED: result = context.getString(R.string.osservio_runtime_unsupported); break;
            default: throw new IllegalStateException("Unknown environment value");
        }
        return result + (value.isTruncated ? " · " + context.getString(R.string.osservio_truncated) : "");
    }
    static String environment(Context context, EnvironmentSnapshot snapshot) {
        StringBuilder text = new StringBuilder(context.getString(R.string.osservio_runtime_environment));
        text.append(" · ").append(snapshot.platform).append('\n').append(date(snapshot.timestampMillis));
        for (EnvironmentSnapshot.Key key : EnvironmentSnapshot.Key.values()) {
            text.append('\n').append(context.getString(keyLabel(key))).append(": ").append(value(context, snapshot.values.get(key), key));
        }
        return text.toString();
    }
    static String breadcrumb(Context context, BreadcrumbEvent event) {
        StringBuilder text = new StringBuilder(kind(context, event.kind));
        text.append('\n').append(date(event.timestampMillis)).append("\nID: ").append(event.id);
        field(text, context, R.string.osservio_crumb_session, event.sessionId);
        field(text, context, R.string.osservio_crumb_sequence, event.sequence);
        field(text, context, R.string.osservio_crumb_elapsed, event.sessionElapsedMillis == null
            ? context.getString(R.string.osservio_runtime_unavailable) : event.sessionElapsedMillis + " ms");
        field(text, context, R.string.osservio_crumb_label, event.label);
        field(text, context, R.string.osservio_crumb_retention, context.getString(event.retention == BreadcrumbEvent.Retention.RESTART_RELEVANT
            ? R.string.osservio_crumb_restart_relevant : R.string.osservio_crumb_memory_only));
        if (event.value != null) field(text, context, R.string.osservio_crumb_value, event.value);
        if (event.message != null) text.append("\n\n").append(event.message);
        if (event.isPrivate) text.append('\n').append(context.getString(R.string.osservio_crumb_private));
        if (event.isTruncated) text.append('\n').append(context.getString(R.string.osservio_truncated));
        return text.toString();
    }
    private static void field(StringBuilder text, Context context, int key, Object value) { text.append('\n').append(context.getString(key)).append(": ").append(value); }
    static int keyLabel(EnvironmentSnapshot.Key key) {
        switch (key) {
            case OS_VERSION: return R.string.osservio_env_os_version;
            case APP_IDENTIFIER: return R.string.osservio_env_app_identifier;
            case APP_VERSION: return R.string.osservio_env_app_version;
            case APP_BUILD: return R.string.osservio_env_app_build;
            case MANUFACTURER: return R.string.osservio_env_manufacturer;
            case DEVICE_MODEL: return R.string.osservio_env_device_model;
            case ARCHITECTURE: return R.string.osservio_env_architecture;
            case PHYSICAL_MEMORY_BYTES: return R.string.osservio_env_physical_memory_bytes;
            case PROCESS_RESIDENT_BYTES: return R.string.osservio_env_process_resident_bytes;
            case PROCESS_PROPORTIONAL_BYTES: return R.string.osservio_env_process_proportional_bytes;
            case PROCESS_FOOTPRINT_BYTES: return R.string.osservio_env_process_footprint_bytes;
            case RUNTIME_HEAP_BYTES: return R.string.osservio_env_runtime_heap_bytes;
            case RUNTIME_HEAP_LIMIT_BYTES: return R.string.osservio_env_runtime_heap_limit_bytes;
            case PROCESS_ID: return R.string.osservio_env_process_id;
            case BATTERY_PERCENT: return R.string.osservio_env_battery_percent;
            case LOW_POWER_MODE: return R.string.osservio_env_low_power_mode;
            case THERMAL_STATE: return R.string.osservio_env_thermal_state;
            default: throw new IllegalStateException("Unknown environment key");
        }
    }
    static String kind(Context context, BreadcrumbEvent.Kind kind) {
        int label;
        switch (kind) {
            case HOST_MARKER: label = R.string.osservio_crumb_kind_host_marker; break;
            case APP_ACTIVE: label = R.string.osservio_crumb_kind_app_active; break;
            case APP_INACTIVE: label = R.string.osservio_crumb_kind_app_inactive; break;
            case APP_FOREGROUND: label = R.string.osservio_crumb_kind_app_foreground; break;
            case APP_BACKGROUND: label = R.string.osservio_crumb_kind_app_background; break;
            case ACTIVITY_STARTED: label = R.string.osservio_crumb_kind_activity_started; break;
            case ACTIVITY_RESUMED: label = R.string.osservio_crumb_kind_activity_resumed; break;
            case ACTIVITY_PAUSED: label = R.string.osservio_crumb_kind_activity_paused; break;
            case ACTIVITY_STOPPED: label = R.string.osservio_crumb_kind_activity_stopped; break;
            case ACTIVITY_DESTROYED: label = R.string.osservio_crumb_kind_activity_destroyed; break;
            case MEMORY_WARNING: label = R.string.osservio_crumb_kind_memory_warning; break;
            case MEMORY_TRIM: label = R.string.osservio_crumb_kind_memory_trim; break;
            case THERMAL_STATE: label = R.string.osservio_crumb_kind_thermal_state; break;
            case RESPONSIVENESS: label = R.string.osservio_crumb_kind_responsiveness; break;
            default: throw new IllegalStateException("Unknown breadcrumb kind");
        }
        return context.getString(label);
    }
}
