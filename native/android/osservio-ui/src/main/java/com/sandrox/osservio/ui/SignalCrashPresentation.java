package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.AppleSignalCrash;
import java.math.BigInteger;

final class SignalCrashPresentation {
    private SignalCrashPresentation() { }
    static String address(String decimal) { return "0x" + new BigInteger(decimal).toString(16); }
    static String count(Context context, int count, boolean limited) {
        if (count == 0) return context.getString(R.string.osservio_runtime_unavailable);
        return count + (limited ? " · " + context.getString(R.string.osservio_crash_limit_reached) : "");
    }
    static String image(Context context, AppleSignalCrash.Image image) {
        String unavailable = context.getString(R.string.osservio_runtime_unavailable);
        return context.getString(R.string.osservio_crash_base_address) + ": " + address(image.baseAddress) + "\n"
            + context.getString(R.string.osservio_crash_image_size) + ": " + (image.size.equals("0") ? unavailable : image.size + " B") + "\n"
            + context.getString(R.string.osservio_crash_cpu_type) + ": " + (image.cpuType == null ? unavailable : image.cpuType) + "\n"
            + context.getString(R.string.osservio_crash_cpu_subtype) + ": " + (image.cpuSubtype == null ? unavailable : image.cpuSubtype);
    }
}
