package com.sandrox.osservio.ui;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InspectorEntryTransitionTest {
    @Test public void firstHardwareFrameKeepsCircularClipInsteadOfShowingTheWholeWindow() throws Exception {
        String previous = shell("settings get global animator_duration_scale").trim();
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        Dialog[] dialog = new Dialog[1]; FrameLayout[] target = new FrameLayout[1];
        InspectorEntryTransition transition = new InspectorEntryTransition(); java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        int[] origin = new int[2], corner = new int[2];
        try {
            // Finish the Activity's own window-enter animation before measuring the independent reveal.
            long hostDeadline = SystemClock.uptimeMillis() + 5000; boolean hostVisible = false;
            while (!hostVisible && SystemClock.uptimeMillis() < hostDeadline) {
                Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                if (screenshot != null) {
                    int pixel = screenshot.getPixel(screenshot.getWidth() * 9 / 10, screenshot.getHeight() / 5);
                    hostVisible = Color.red(pixel) < 20 && Color.green(pixel) > 230; screenshot.recycle();
                }
                if (!hostVisible) Thread.sleep(25);
            }
            assertTrue("Hardware source window must be visible before opening", hostVisible);
            shell("settings put global animator_duration_scale 4");
            main(() -> {
                dialog[0] = new Dialog(host); target[0] = new FrameLayout(host); target[0].setBackgroundColor(Color.WHITE);
                dialog[0].setContentView(target[0]);
                dialog[0].getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog[0].getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                dialog[0].getWindow().setWindowAnimations(0); dialog[0].show(); dialog[0].getWindow().setLayout(-1, -1);
                android.util.DisplayMetrics metrics = host.getResources().getDisplayMetrics();
                origin[0] = metrics.widthPixels / 5; origin[1] = metrics.heightPixels * 3 / 5;
                corner[0] = metrics.widthPixels * 9 / 10; corner[1] = metrics.heightPixels / 5;
                transition.run(target[0], new Rect(origin[0] - 20, origin[1] - 20, origin[0] + 20, origin[1] + 20),
                    true, null, () -> completed.set(true));
            });
            boolean sawCircle = false, sawComplete = false; long deadline = SystemClock.uptimeMillis() + 6000;
            while (SystemClock.uptimeMillis() < deadline && !sawComplete) {
                Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                assertNotNull(screenshot);
                try {
                    int center = screenshot.getPixel(origin[0], origin[1]), outside = screenshot.getPixel(corner[0], corner[1]);
                    if (Color.red(center) > 245 && Color.red(outside) < 20 && Color.green(outside) > 230) sawCircle = true;
                    if (sawCircle && Color.red(outside) > 245) sawComplete = true;
                } finally { screenshot.recycle(); }
                Thread.sleep(20);
            }
            assertTrue("First attachment must show a partial circular reveal", sawCircle);
            assertTrue("Reveal must reach the full window", sawComplete);
            long completionDeadline = SystemClock.uptimeMillis() + 2000;
            while (!completed.get() && SystemClock.uptimeMillis() < completionDeadline) { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(20); }
            main(() -> { assertTrue(completed.get()); assertEquals(1, target[0].getAlpha(), 0); assertEquals(0, target[0].getChildCount()); });
        } finally {
            main(() -> { transition.cancel(); if (dialog[0] != null) dialog[0].dismiss(); host.finish(); });
            shell(previous.equals("null") ? "settings delete global animator_duration_scale" : "settings put global animator_duration_scale " + previous);
        }
    }
    @Test public void cancellationBeforeFirstDrawRemovesPendingWorkAndTouchBlocker() throws Exception {
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        try {
            main(() -> {
                FrameLayout target = new FrameLayout(host); ((android.view.ViewGroup) host.content.getParent()).addView(target);
                InspectorEntryTransition transition = new InspectorEntryTransition();
                transition.run(target, new Rect(0, 0, 48, 48), true, null, () -> fail("Canceled completion"));
                transition.cancel(); assertEquals(1, target.getAlpha(), 0); assertEquals(0, target.getChildCount());
                ((android.view.ViewGroup) target.getParent()).removeView(target);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        } finally { main(host::finish); }
    }
    private static String shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
