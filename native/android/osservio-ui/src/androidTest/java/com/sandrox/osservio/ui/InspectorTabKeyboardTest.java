package com.sandrox.osservio.ui;

import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Keyboard behavior is shared by the native fallback and optical navigation. */
@RunWith(AndroidJUnit4.class)
public final class InspectorTabKeyboardTest {
    @Test public void keyboardActivationKeepsFocusForFollowingTabNavigation() throws Exception {
        InspectorNavigationMaterialActivity host = InspectorWindowTestSupport.start(InspectorNavigationMaterialActivity.class);
        try {
            await(() -> host.bar().isAttachedToWindow() && host.bar().getWidth() > 0);
            Thread.sleep(500);
            for (boolean enhanced : new boolean[] {false, true}) {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB);
                main(() -> {
                    InspectorTabBar bar = host.bar(); bar.setMaterialVisible(enhanced);
                    assertTrue(bar.getChildAt(0).requestFocus());
                });
                for (int i = 0; i < 4; i++) {
                    final int index = i;
                    if (i > 0) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
                    main(() -> assertTrue("Arrow navigation must reach the adjacent tab", host.bar().getChildAt(index).hasFocus()));
                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER);
                    main(() -> {
                        assertTrue(host.bar().getChildAt(index).isSelected());
                        assertTrue("Page changes must preserve the triggering tab's focus", host.bar().getChildAt(index).hasFocus());
                    });
                }
            }
        } finally { finish(host); }
    }
    private static void finish(InspectorNavigationMaterialActivity host) {
        main(host::finish); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] ready = {false};
            main(() -> { try { ready[0] = condition.call(); } catch (Exception error) { throw new AssertionError(error); } });
            if (ready[0]) return;
            Thread.sleep(25);
        }
        fail("Navigation did not become ready");
    }
}
