package com.sandrox.osservio.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class RuntimeEnvironmentTest {
    @Test public void buildFactsDescribeInstalledApplicationAndDeclareOlderPlatformLimits() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<EnvironmentSnapshot> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try (RuntimeEnvironment provider = new RuntimeEnvironment(context)) {
            provider.read((snapshot, error) -> { result.set(snapshot); done.countDown(); });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            EnvironmentSnapshot snapshot = result.get(); assertNotNull(snapshot);
            ApplicationInfo application = context.getApplicationInfo();
            assertEquals(Long.valueOf(application.targetSdkVersion), snapshot.values.get(EnvironmentSnapshot.Key.ANDROID_TARGET_SDK).fact.number);
            assertEquals(Boolean.valueOf((application.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0), snapshot.values.get(EnvironmentSnapshot.Key.ANDROID_DEBUGGABLE).fact.flag);
            EnvironmentSnapshot.Fact minimum = snapshot.values.get(EnvironmentSnapshot.Key.ANDROID_MIN_SDK).fact;
            if (Build.VERSION.SDK_INT >= 24) assertEquals(Long.valueOf(application.minSdkVersion), minimum.number);
            else assertEquals(EnvironmentSnapshot.Fact.Type.UNSUPPORTED, minimum.type);
        }
    }
}
