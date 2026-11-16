package com.sandrox.osservio.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorTabMotionTest {
    @Test public void enlargedLensStaysInsideWindowAtBothEdgesAndDuringOvershoot() {
        float left = -16, right = 374, width = 87.5f;
        for (float scale : new float[] {1, 1.3f, 1.65f}) {
            for (float position : new float[] {-30, 4, 100, 266.5f, 300}) {
                float constrained = InspectorTabGeometry.constrainLens(position, width, scale, left, right);
                float overflow = width * (scale - 1) / 2;
                assertTrue(constrained - overflow >= left - .001f);
                assertTrue(constrained + width + overflow <= right + .001f);
            }
            assertEquals(100, InspectorTabGeometry.constrainLens(100, width, scale, left, right), 0);
        }
    }
    @Test public void dragIsPreviewAndCancellationReturnsToCommittedPage() {
        InspectorTabMotion motion = new InspectorTabMotion(4); motion.sync(1, false, 62);
        motion.hold(62); motion.drag(2, 420); advance(motion, 30);
        assertEquals(1, motion.selected()); assertEquals(3, motion.destination()); assertTrue(motion.progress() > .9);
        motion.cancel(62); advance(motion, 240);
        assertEquals(1, motion.selected()); assertEquals(1, motion.position.value, .001); assertFalse(motion.running());
        assertEquals(0, motion.progress(), .001); assertEquals(0, motion.panel.value, .001);
    }
    @Test public void releaseAndExternalSelectionNeverCommitIntermediatePages() {
        InspectorTabMotion motion = new InspectorTabMotion(4); motion.sync(0, false, 62);
        motion.sync(3, true, 62); advance(motion, 4);
        assertEquals(3, motion.selected()); assertTrue(motion.position.value > 0 && motion.position.value < 3);
        motion.hold(62); motion.drag(-2, -420);
        motion.sync(2, true, 62);
        assertFalse(motion.held()); assertEquals(2, motion.position.value, 0); assertFalse(motion.running());
        motion.sync(0, false, 62); assertEquals(0, motion.position.value, 0);
    }
    @Test public void releaseToSameItemAndFastReversalSettleWithoutBackgroundFrames() {
        InspectorTabMotion motion = new InspectorTabMotion(4); motion.sync(3, false, 62);
        motion.settle(3, 62); advance(motion, 120); assertFalse(motion.running());
        motion.hold(62); motion.drag(-10, -1000); advance(motion, 5);
        assertEquals(0, motion.destination()); motion.drag(10, 1000); assertEquals(3, motion.destination());
        motion.settle(3, 62); advance(motion, 240);
        assertFalse(motion.running()); assertEquals(3, motion.position.value, .001);
        assertEquals(1, motion.scaleX.value, .001); assertEquals(1, motion.scaleY.value, .001);
    }
    @Test public void analyticSpringHasConsistentTimeAcrossRefreshRates() {
        for (double damping : new double[] {1, .6, .7, .5}) {
            InspectorSpring sixty = new InspectorSpring(0, damping, 250, .001);
            InspectorSpring oneTwenty = new InspectorSpring(0, damping, 250, .001);
            sixty.target = oneTwenty.target = 3;
            for (int i = 0; i < 12; i++) sixty.step(1.0 / 60);
            for (int i = 0; i < 24; i++) oneTwenty.step(1.0 / 120);
            assertEquals(sixty.value, oneTwenty.value, 1e-9); assertEquals(sixty.velocity, oneTwenty.velocity, 1e-9);
        }
    }
    private static void advance(InspectorTabMotion motion, int frames) { for (int i = 0; i < frames; i++) motion.step(1.0 / 60); }
}
