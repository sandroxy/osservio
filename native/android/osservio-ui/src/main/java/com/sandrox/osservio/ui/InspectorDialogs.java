package com.sandrox.osservio.ui;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextView;

/** Native pages, editing panels and short actions share one presentation owner. */
final class InspectorDialogs {
    private InspectorDialogs() { }
    static InspectorScreen screen(Context context, String title, View content) {
        InspectorScreen screen = new InspectorScreen(context, title, content, false); screen.bindContent(); return screen;
    }
    static InspectorScreen screen(Context context, int title, View content) {
        InspectorScreen screen = new InspectorScreen(context, context.getString(title), content, false); screen.title(title); screen.bindContent(); return screen;
    }
    static InspectorScreen panel(Context context, int title, View content) {
        return new InspectorScreen(context, context.getString(title), content, true);
    }
    static InspectorScreen message(Context context, int title, String message) {
        InspectorScreen screen = panel(context, title, messageView(context, message)); screen.compact(); screen.show(); return screen;
    }
    static InspectorScreen confirm(Context context, int title, String message, int confirm,
                                   android.content.DialogInterface.OnClickListener action) {
        android.widget.LinearLayout content = new android.widget.LinearLayout(context); content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.addView(messageText(context, message));
        android.widget.ScrollView scroll = new android.widget.ScrollView(context); scroll.addView(content);
        InspectorScreen screen = panel(context, title, scroll);
        screen.compact();
        android.widget.Button button = InspectorWidgets.button(context, confirm,
            () -> { screen.dismiss(); action.onClick(screen, android.content.DialogInterface.BUTTON_POSITIVE); });
        InspectorAppearance.style(button, () -> {
            button.setTextColor(InspectorTheme.color(context, R.color.osservio_error));
            InspectorTheme.surface(button, false, 24);
        });
        android.widget.LinearLayout.LayoutParams layout = new android.widget.LinearLayout.LayoutParams(-1, -2);
        int inset = InspectorTheme.dp(context, 16); layout.setMargins(inset, 0, inset, inset);
        content.addView(button, layout);
        screen.show(); return screen;
    }
    static InspectorScreen menu(Context context, int title, String[] labels, android.content.DialogInterface.OnClickListener chosen) {
        return choices(context, title, labels, -1, true, chosen);
    }
    static InspectorScreen choices(Context context, int title, String[] labels, int selected, boolean panel,
                                   android.content.DialogInterface.OnClickListener chosen) {
        android.widget.ListView list = new android.widget.ListView(context);
        int inset = InspectorTheme.dp(context, 16); list.setPadding(inset, InspectorTheme.dp(context, 8), inset, inset);
        list.setClipToPadding(false); list.setDivider(null); list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent); list.setVerticalScrollBarEnabled(false);
        list.setAdapter(new android.widget.ArrayAdapter<String>(context, 0, labels) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                android.widget.CheckedTextView row = recycled instanceof android.widget.CheckedTextView
                    ? (android.widget.CheckedTextView) recycled : InspectorWidgets.choice(context);
                row.setText(labels[position]); row.setChecked(position == selected);
                InspectorWidgets.groupedRow(row, position == 0, position + 1 == labels.length);
                return row;
            }
        });
        if (selected >= 0) { list.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE); list.setItemChecked(selected, true); }
        InspectorScreen screen = new InspectorScreen(context, context.getString(title), list, panel);
        if (panel) screen.compact();
        list.setOnItemClickListener((parent, view, index, id) -> { screen.dismiss(); chosen.onClick(screen, index); });
        screen.show(); return screen;
    }
    private static View messageView(Context context, String message) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(messageText(context, message)); return scroll;
    }
    private static TextView messageText(Context context, String message) {
        TextView text = InspectorTheme.text(context, 15, false); text.setText(message); text.setTextIsSelectable(true);
        int inset = InspectorTheme.dp(context, 20); text.setPadding(inset, inset, inset, inset); return text;
    }
    @SuppressWarnings("deprecation") // Window APIs differ across supported API 23–36 hosts.
    static void configureWindow(Window window, boolean dark) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } else {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (!dark && Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }
    @SuppressWarnings("deprecation") // Legacy insets keep API 23–29 supported.
    static void applyInsets(View root, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
        } else root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
    }
}
