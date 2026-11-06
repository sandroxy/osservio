package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.AndroidTombstone;
import com.sandrox.osservio.core.SystemDiagnostic;

final class TombstonePresentation {
    private TombstonePresentation() { }
    static String state(Context context, SystemDiagnostic.TombstoneState state) {
        int label;
        switch (state) {
            case NOT_REQUESTED: label = R.string.osservio_tombstone_not_requested; break;
            case NOT_APPLICABLE: label = R.string.osservio_tombstone_not_applicable; break;
            case UNSUPPORTED: label = R.string.osservio_tombstone_unsupported; break;
            case UNAVAILABLE: label = R.string.osservio_tombstone_unavailable; break;
            case INVALID: label = R.string.osservio_tombstone_invalid; break;
            case TOO_LARGE: label = R.string.osservio_tombstone_too_large; break;
            case LIMIT_REACHED: label = R.string.osservio_tombstone_limit_reached; break;
            case AVAILABLE: label = R.string.osservio_tombstone_available; break;
            default: throw new IllegalStateException("Unknown tombstone state");
        }
        return context.getString(label);
    }
    static String architecture(int code) {
        String[] known = {"ARM32", "ARM64", "X86", "X86_64", "RISCV64", "NONE"};
        return code < known.length ? known[code] : "#" + code;
    }
    static String frame(Context context, AndroidTombstone.Frame frame) {
        return context.getString(R.string.osservio_tombstone_pc) + ": " + SignalCrashPresentation.address(frame.programCounter) + "\n"
            + context.getString(R.string.osservio_tombstone_relative_pc) + ": " + SignalCrashPresentation.address(frame.relativeProgramCounter) + "\n"
            + context.getString(R.string.osservio_tombstone_build_id) + ": " + (frame.buildID == null ? context.getString(R.string.osservio_runtime_unavailable) : frame.buildID);
    }
}
