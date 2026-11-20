package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorFeedUpdateTest {
    @Test public void liveCountsKeepFilterControlsAndTheirCurrentQuery() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            assertEquals(context.getString(R.string.osservio_ui_path_exists, "/id"), InspectorFilterOptions.jsonCondition(context, "/id", ""));
            assertEquals("/id = 42", InspectorFilterOptions.jsonCondition(context, "/id", "42"));
            InspectorFilterSheet.Target[] editing = {null};
            InspectorFeedHeader header = new InspectorFeedHeader(context, R.string.osservio_search, "", text -> { }, () -> { }, target -> editing[0] = target, () -> { }, true);
            InspectorQuery query = new InspectorQuery(); query.select(InspectorQuery.Field.LEVEL, "ERROR"); query.text = "first";
            InspectorFeedHeader.Groups groups = () -> InspectorFilterOptions.logs(context, Collections.emptyList());
            header.setQuery(query, groups, 1, 10);
            Button original = token(header, false); assertNotNull(original);
            header.setQuery(query, () -> { fail("Count-only updates must not rebuild filter candidates"); return Collections.emptyList(); }, 2, 20);
            assertSame(original, token(header, false));
            assertEquals(context.getString(R.string.osservio_ui_result_count, 2, 20), header.results.getText().toString());
            query.text = "latest"; header.setQuery(query, groups, 1, 20);
            InspectorQuery[] applied = {null}; header.onQueryChange = value -> applied[0] = value;
            token(header, false).performClick();
            assertEquals(InspectorQuery.Field.LEVEL, editing[0].field); assertNull(applied[0]);
            token(header, true).performClick(); assertNotNull(applied[0]);
            assertEquals("latest", applied[0].text); assertEquals(0, applied[0].activeCount());
        });
    }
    private static Button token(View view, boolean remove) {
        if (view instanceof Button && view.getContentDescription() != null && view.getContentDescription().toString().contains("ERROR") && "×".contentEquals(((Button) view).getText()) == remove) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = token(((ViewGroup) view).getChildAt(i), remove); if (found != null) return found;
        }
        return null;
    }
}
