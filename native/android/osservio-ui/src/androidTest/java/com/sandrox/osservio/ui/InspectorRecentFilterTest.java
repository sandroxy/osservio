package com.sandrox.osservio.ui;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InspectorRecentFilterTest {
    private static void ui(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private static List<InspectorQuery> items(InspectorRecentFilters store) throws InterruptedException {
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<List<InspectorQuery>> result = new AtomicReference<>();
        ui(() -> store.load(rows -> { result.set(rows); loaded.countDown(); }));
        assertTrue(loaded.await(5, TimeUnit.SECONDS));
        return result.get();
    }
    @Test public void historyIsBoundedAndIsolatedAcrossViewersOwnersAndFeeds() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        InspectorRecentFilters store = new InspectorRecentFilters("logs"), relaunched = new InspectorRecentFilters("logs");
        InspectorRecentFilters network = new InspectorRecentFilters("network");
        InspectorQuery query = new InspectorQuery(); query.text = "private search";
        try {
            ui(() -> { store.update(query, false); });
            assertTrue(items(store).isEmpty());
            ui(() -> {
                for (int n = 0; n <= 10; n++) {
                    query.filterRecord(InspectorQuery.Field.CATEGORY, "category-" + n, false); store.update(query, false);
                }
            });
            List<InspectorQuery> anonymous = items(store);
            assertEquals(10, anonymous.size());
            assertTrue(anonymous.get(0).values.get(InspectorQuery.Field.CATEGORY).contains("category-10"));
            ui(() -> store.owner(context, alice));
            assertTrue(items(store).isEmpty());
            ui(() -> {
                for (String value : Arrays.asList("A", "B", "A")) {
                    query.filterRecord(InspectorQuery.Field.CATEGORY, value, false); store.update(query, false);
                }
                relaunched.owner(context, alice); network.owner(context, alice); store.owner(context, bob);
            });
            List<InspectorQuery> restored = items(relaunched);
            assertEquals(2, restored.size());
            assertTrue(restored.get(0).values.get(InspectorQuery.Field.CATEGORY).contains("A"));
            assertTrue(restored.get(1).values.get(InspectorQuery.Field.CATEGORY).contains("B"));
            assertTrue(restored.get(0).text.isEmpty());
            assertTrue(items(store).isEmpty()); assertTrue(items(network).isEmpty());
            ui(() -> {
                relaunched.update(query, true);
                query.filterRecord(InspectorQuery.Field.CATEGORY, new String(new char[4097]).replace((char) 0, 'x'), false);
                relaunched.update(query, false);
                store.owner(context, alice);
            });
            assertEquals(1, items(store).size());
            assertTrue(items(store).get(0).values.get(InspectorQuery.Field.CATEGORY).contains("B"));
        } finally {
            new android.util.AtomicFile(new File(new File(context.getNoBackupFilesDir(), "osservio-recent-filters"), alice + "-logs.json")).delete();
        }
    }
}
