package com.sandrox.osservio.ui;

import android.content.Context;
import android.os.PowerManager;

/** The API-29 listener type never appears in the minSdk-23 session's signatures. */
@androidx.annotation.RequiresApi(29)
final class InspectorGlassThermal implements InspectorGlassSession.Thermal {
    private final PowerManager manager;
    private final PowerManager.OnThermalStatusChangedListener listener;
    private boolean active = true;
    private InspectorGlassThermal(PowerManager manager, InspectorGlassSession.ThermalChanged changed) {
        this.manager = manager;
        listener = status -> { if (active) changed.accept(status); };
        manager.addThermalStatusListener(listener);
    }
    static InspectorGlassSession.Thermal register(Context context, InspectorGlassSession.ThermalChanged changed) {
        PowerManager manager = context.getSystemService(PowerManager.class);
        if (manager == null) throw new IllegalStateException("Power service unavailable");
        changed.accept(manager.getCurrentThermalStatus());
        return new InspectorGlassThermal(manager, changed);
    }
    @Override public void close() { active = false; manager.removeThermalStatusListener(listener); }
}
