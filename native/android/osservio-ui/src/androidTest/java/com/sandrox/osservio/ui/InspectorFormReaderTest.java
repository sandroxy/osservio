package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InspectorFormReaderTest {
    @Test public void orderedFieldsReuseRowsAndOpenCompleteDecodedValues() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            InspectorFormView view = new InspectorFormView(context);
            InspectorFormDocument document = parse("tag=one&tag=%E4%BD%A0%E5%A5%BD&empty=&flag"); view.display(document); layout(view);
            assertEquals(4, view.getAdapter().getCount());
            InspectorWidgets.ValueRow row = (InspectorWidgets.ValueRow) view.getAdapter().getView(1, null, view);
            assertEquals("tag", row.title.getText().toString()); assertEquals("你好", row.value.getText().toString());
            InspectorFormDocument.Field[] selected = new InspectorFormDocument.Field[1]; view.open = field -> selected[0] = field;
            view.performItemClick(row, 1, 1); assertSame(document.fields.get(1), selected[0]);
            assertEquals("tag=%E4%BD%A0%E5%A5%BD", selected[0].original);
            assertNotEquals(InspectorFormView.value(context, document.fields.get(2)), InspectorFormView.value(context, document.fields.get(3)));
            StringBuilder many = new StringBuilder(); for (int i = 0; i < 256; i++) many.append("item=").append(i).append('&');
            view.display(parse(many.toString())); layout(view);
            assertEquals(256, view.getAdapter().getCount()); assertTrue(view.getChildCount() > 0 && view.getChildCount() < 40);
            view.setSelection(230); layout(view); assertTrue(view.getChildCount() < 40);
        });
    }
    private static InspectorFormDocument parse(String source) {
        try { return InspectorFormDocument.parse(source, () -> false); }
        catch (InspectorFormDocument.ParseFailure failure) { throw new AssertionError(failure); }
    }
    private static void layout(View view) {
        int width = InspectorTheme.dp(view.getContext(), 320), height = InspectorTheme.dp(view.getContext(), 500);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
}
