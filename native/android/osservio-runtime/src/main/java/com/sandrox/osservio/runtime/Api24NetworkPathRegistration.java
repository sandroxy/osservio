package com.sandrox.osservio.runtime;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import androidx.annotation.DoNotInline;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import java.util.ArrayList;
import java.util.List;

/** Keeps default-network registration behind its actual OS availability boundary. */
@RequiresApi(24)
final class Api24NetworkPathRegistration {
    private Api24NetworkPathRegistration() { }
    // Consumer rules prevent this boundary from being inlined into API 23 code.
    @DoNotInline
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    static NetworkPathSignals.Registration open(Context context, NetworkPathSignals.Lease lease) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Network observation permission is absent");
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) throw new IllegalStateException("Connectivity service unavailable");
        // One explicit control-time snapshot; callbacks use their supplied capabilities to avoid query races.
        Network initialNetwork = manager.getActiveNetwork();
        NetworkCapabilities initialCapabilities = initialNetwork == null ? null : manager.getNetworkCapabilities(initialNetwork);
        String initial = initialNetwork == null ? "status=unavailable" : initialCapabilities == null ? "status=unknown" : description(initialCapabilities);
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            private Network current;
            @Override public void onAvailable(Network network) { current = network; }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (network.equals(current)) lease.record(description(capabilities), false);
            }
            @Override public void onLost(Network network) {
                if (network.equals(current)) { current = null; lease.record("status=unavailable", false); }
            }
        };
        manager.registerDefaultNetworkCallback(callback);
        lease.record(initial, true);
        return () -> manager.unregisterNetworkCallback(callback);
    }
    private static String description(NetworkCapabilities capabilities) {
        List<String> transports = new ArrayList<>(5);
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("wifi");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("cellular");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("ethernet");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.add("bluetooth");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("vpn");
        if (transports.isEmpty()) transports.add("other");
        return "status=available; interfaces=" + android.text.TextUtils.join(",", transports)
            + "; validated=" + capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            + "; metered=" + !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }
}
