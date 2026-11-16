// Motion parameters derived from Kyant0/AndroidLiquidGlass, Copyright 2025 Kyant.
// Apache-2.0; see META-INF/osservio/backdrop-LICENSE.txt and backdrop-NOTICE.txt.
package com.sandrox.osservio.ui;

/** Visual preview only: the inspector owns the committed destination. */
final class InspectorTabMotion {
    final InspectorSpring position = new InspectorSpring(0, 1, 1000, .001);
    final InspectorSpring press = new InspectorSpring(0, 1, 1000, .001);
    final InspectorSpring scaleX = new InspectorSpring(1, .6, 250, .001);
    final InspectorSpring scaleY = new InspectorSpring(1, .7, 250, .001);
    final InspectorSpring speed = new InspectorSpring(0, .5, 300, .01);
    final InspectorSpring panel = new InspectorSpring(0, 1, 300, .5);
    final InspectorSpring glow = new InspectorSpring(0, .5, 300, .001);
    private int committed;
    private final int count;
    private boolean held, releasing;

    InspectorTabMotion(int count) { this.count = count; }
    int selected() { return committed; }
    boolean held() { return held; }
    int destination() { return Math.max(0, Math.min(count - 1, (int) Math.round(position.target))); }
    float progress() { return Math.max(0, Math.min(1, (float) press.value)); }

    void sync(int index, boolean animate, float heightDp) {
        committed = index;
        if (!animate || held) { stop(); return; }
        settle(index, heightDp);
    }
    void hold(float heightDp) {
        held = true; releasing = false;
        press(heightDp); glow.target = 1;
    }
    void drag(float fraction, float pixels) {
        if (!held) return;
        position.target = Math.max(0, Math.min(count - 1, position.target + fraction));
        panel.snap(panel.value + pixels);
    }
    void settle(int index, float heightDp) {
        held = false; position.target = index; panel.target = 0; glow.target = 0;
        press(heightDp); releasing = true;
    }
    void cancel(float heightDp) { settle(committed, heightDp); }
    private void press(float heightDp) {
        press.target = 1;
        double scale = (heightDp + 14) / (heightDp - 8);
        scaleX.target = scale; scaleY.target = scale; speed.target = 0;
    }
    void step(double seconds) {
        position.step(seconds);
        if (held) speed.target = position.velocity / Math.max(1, count - 1);
        speed.step(seconds); panel.step(seconds); glow.step(seconds);
        if (releasing && Math.abs(position.value - position.target) < (count - 1) * .025) {
            releasing = false; press.target = 0; scaleX.target = 1; scaleY.target = 1; speed.target = 0;
        }
        press.step(seconds); scaleX.step(seconds); scaleY.step(seconds);
    }
    boolean running() {
        return position.running() || press.running() || scaleX.running() || scaleY.running()
            || speed.running() || panel.running() || glow.running() || releasing;
    }
    void stop() {
        held = false; releasing = false; position.snap(committed); press.snap(0);
        scaleX.snap(1); scaleY.snap(1); speed.snap(0); panel.snap(0); glow.snap(0);
    }
}
