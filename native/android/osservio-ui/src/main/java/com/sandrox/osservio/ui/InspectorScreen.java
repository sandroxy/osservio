package com.sandrox.osservio.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A page or editing panel in the inspector. Standalone viewers use a native dialog container. */
final class InspectorScreen implements DialogInterface {
    final FrameLayout view;
    final boolean panel;
    private final InspectorNavigation navigation;
    private final LinearLayout bar;
    private final ImageButton minimize;
    private Dialog fallback;
    private boolean showing;
    private boolean cancelable = true;
    private OnDismissListener dismissed;
    private OnShowListener appeared;
    private OnCancelListener cancelled;

    InspectorScreen(Context context, String title, View content, boolean panel) {
        this.panel = panel; navigation = InspectorNavigation.from(context);
        view = new FrameLayout(context);
        view.setBackgroundColor(panel ? 0x44000000 : InspectorTheme.canvas(context));
        LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(InspectorTheme.shape(context, InspectorTheme.canvas(context), panel ? 24 : 0, false));
        view.addView(card, new FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM));
        if (panel) {
            view.addOnLayoutChangeListener((root, l, t, r, b, ol, ot, or, ob) -> {
                int gap = Math.min(InspectorTheme.dp(context, 56), Math.max(0, (b - t) / 12));
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) card.getLayoutParams();
                if (params.topMargin != gap) { params.topMargin = gap; card.setLayoutParams(params); }
            });
            view.setOnClickListener(ignored -> cancel()); card.setClickable(true);
        }
        bar = new LinearLayout(context); bar.setGravity(Gravity.CENTER_VERTICAL);
        int inset = InspectorTheme.dp(context, 8); bar.setPadding(inset, inset, inset, inset);
        bar.addView(InspectorWidgets.icon(context, panel ? R.drawable.osservio_close : R.drawable.osservio_back,
            panel ? R.string.osservio_archive_cancel : R.string.osservio_ui_back, this::cancel));
        TextView heading = InspectorTheme.text(context, 19, true); heading.setText(title); heading.setMaxLines(2);
        heading.setPadding(inset, 0, inset, 0); bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        minimize = InspectorWidgets.icon(context, R.drawable.osservio_minimize, R.string.osservio_ui_minimize,
            () -> { if (navigation != null) navigation.minimize(); });
        bar.addView(minimize); updateMinimize();
        card.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        card.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    Button action(int label, Runnable operation) {
        Button action = InspectorWidgets.button(view.getContext(), label, operation);
        bar.addView(action, bar.getChildCount() - 1, new LinearLayout.LayoutParams(-2, -2)); return action;
    }
    void updateMinimize() { minimize.setVisibility(navigation != null && navigation.canMinimize() ? View.VISIBLE : View.GONE); }
    void show() {
        if (showing) return;
        if (navigation != null) { navigation.push(this); return; }
        Context context = view.getContext();
        fallback = new Dialog(context, InspectorPreferences.dark(context) ? R.style.OsservioScreenDark : R.style.OsservioScreenLight);
        fallback.setContentView(view); fallback.setCancelable(cancelable);
        fallback.setOnCancelListener(ignored -> { if (cancelled != null) cancelled.onCancel(this); });
        fallback.setOnDismissListener(ignored -> didDismiss());
        fallback.show();
        if (fallback.getWindow() != null) {
            fallback.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            fallback.getWindow().setLayout(-1, -1);
            InspectorDialogs.configureWindow(fallback.getWindow(), InspectorPreferences.dark(context));
            view.setOnApplyWindowInsetsListener((root, insets) -> { InspectorDialogs.applyInsets(root, insets); return insets; });
            view.requestApplyInsets();
        }
        didShow();
    }
    boolean isShowing() { return showing; }
    @Override public void dismiss() {
        if (navigation != null) navigation.remove(this);
        else if (fallback != null) { fallback.dismiss(); fallback = null; }
    }
    @Override public void cancel() {
        if (!cancelable) return;
        if (cancelled != null) cancelled.onCancel(this);
        dismiss();
    }
    void setCancelable(boolean value) { cancelable = value; if (fallback != null) fallback.setCancelable(value); }
    void setOnDismissListener(OnDismissListener listener) { dismissed = listener; }
    void setOnShowListener(OnShowListener listener) { appeared = listener; }
    void setOnCancelListener(OnCancelListener listener) { cancelled = listener; }
    void didShow() { showing = true; if (appeared != null) appeared.onShow(this); }
    void didDismiss() { if (!showing) return; showing = false; if (dismissed != null) dismissed.onDismiss(this); }
}
