package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import java.text.DateFormat;
import java.util.Date;

final class RuntimePresentation {
    private RuntimePresentation() { }
    static String message(Context context, String value, boolean isPrivate) {
        return isPrivate ? context.getString(R.string.osservio_ui_private_content) : value;
    }
    static String date(Context context, long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM,
            InspectorPreferences.english(context) ? java.util.Locale.ENGLISH : java.util.Locale.SIMPLIFIED_CHINESE).format(new Date(millis));
    }
    static String value(Context context, EnvironmentSnapshot.Value value, EnvironmentSnapshot.Key key) { return value(context, value, key, false); }
    static String value(Context context, EnvironmentSnapshot.Value value, EnvironmentSnapshot.Key key, boolean compact) {
        String result;
        switch (value.fact.type) {
            case TEXT: result = value.fact.text;
                if (compact && key == EnvironmentSnapshot.Key.THERMAL_STATE) result = thermal(context, result);
                break;
            case NUMBER:
                result = key.name().endsWith("_BYTES")
                    ? InspectorValuePresentation.bytes(value.fact.number) + (compact ? "" : " (" + value.fact.number + " B)")
                    : value.fact.number + (key == EnvironmentSnapshot.Key.BATTERY_PERCENT ? "%" : "");
                break;
            case FLAG: result = context.getString(value.fact.flag ? R.string.osservio_runtime_yes : R.string.osservio_runtime_no); break;
            case UNAVAILABLE: result = context.getString(R.string.osservio_runtime_unavailable); break;
            case UNSUPPORTED: result = context.getString(R.string.osservio_runtime_unsupported); break;
            default: throw new IllegalStateException("Unknown environment value");
        }
        return result + (value.isTruncated ? " · " + context.getString(R.string.osservio_truncated) : "");
    }
    private static String thermal(Context context, String value) {
        int label;
        switch (value) {
            case "nominal": label = R.string.osservio_ui_thermal_nominal; break;
            case "fair": label = R.string.osservio_ui_thermal_fair; break;
            case "serious": label = R.string.osservio_ui_thermal_serious; break;
            case "critical": label = R.string.osservio_ui_thermal_critical; break;
            case "none": label = R.string.osservio_ui_thermal_none; break;
            case "light": label = R.string.osservio_ui_thermal_light; break;
            case "moderate": label = R.string.osservio_ui_thermal_moderate; break;
            case "severe": label = R.string.osservio_ui_thermal_severe; break;
            case "emergency": label = R.string.osservio_ui_thermal_emergency; break;
            case "shutdown": label = R.string.osservio_ui_thermal_shutdown; break;
            default: return value;
        }
        return context.getString(label) + " (" + value + ")";
    }
    static String environment(Context context, EnvironmentSnapshot snapshot) {
        StringBuilder text = new StringBuilder(context.getString(R.string.osservio_runtime_environment));
        text.append(" · ").append(snapshot.platform).append('\n').append(date(context, snapshot.timestampMillis));
        for (EnvironmentSnapshot.Key key : EnvironmentSnapshot.Key.values()) {
            text.append('\n').append(context.getString(keyLabel(key))).append(": ").append(value(context, snapshot.values.get(key), key));
        }
        return text.toString();
    }
    static String breadcrumbTitle(Context context, BreadcrumbEvent event) { return event.kind == BreadcrumbEvent.Kind.HOST_MARKER ? event.label : kind(context, event.kind); }
    static String breadcrumbSubtitle(Context context, BreadcrumbEvent event) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (event.message != null) parts.add(event.message.isEmpty() ? context.getString(R.string.osservio_ui_content_empty) : message(context, InspectorRecordPresentation.compact(event.message, 180), event.isPrivate));
        if (event.kind.name().startsWith("ACTIVITY_")) parts.add(event.label);
        if (event.sessionElapsedMillis != null) parts.add(context.getString(R.string.osservio_ui_since_start, event.sessionElapsedMillis));
        return android.text.TextUtils.join(" · ", parts);
    }
    static String breadcrumb(Context context, BreadcrumbEvent event) {
        StringBuilder text = new StringBuilder(kind(context, event.kind));
        text.append('\n').append(date(context, event.timestampMillis)).append("\nID: ").append(event.id);
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
