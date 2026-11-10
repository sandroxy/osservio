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
    interface BackInterceptor { boolean consume(); }
    private BackInterceptor backInterceptor;
    void setBackInterceptor(BackInterceptor interceptor) { backInterceptor = interceptor; }
    final FrameLayout view;
    final boolean panel;
    private final InspectorNavigation navigation;
    private final LinearLayout bar;
    private final LinearLayout card;
    private final View content;
    private final ImageButton minimize;
    private final ImageButton back;
    private final TextView heading;
    void title(int resource) { InspectorAppearance.text(heading, resource); }
    private InspectorSheetHandle handle;
    private boolean modal;
    private Dialog fallback;
    private boolean showing;
    private boolean cancelable = true;
    private OnDismissListener dismissed;
    private OnShowListener appeared;
    private OnCancelListener cancelled;

    InspectorScreen(Context context, String title, View content, boolean panel) {
        this.panel = panel; this.content = content; navigation = InspectorNavigation.from(context);
        view = new InspectorKeyboardDismissal.Frame(context);
        view.setBackgroundColor(panel ? 0x44000000 : InspectorTheme.canvas(context));
        card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL); card.setClipToOutline(true);
        InspectorAppearance.style(view, () -> view.setBackgroundColor(panel ? 0x44000000 : InspectorTheme.canvas(context)));
        InspectorAppearance.style(card, () -> card.setBackground(InspectorTheme.shape(context, InspectorTheme.canvas(context), panel ? 24 : 0, false)));
        view.addView(card, new FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM));
        if (panel) {
            view.addOnLayoutChangeListener((root, l, t, r, b, ol, ot, or, ob) -> {
                int gap = Math.min(InspectorTheme.dp(context, 56), Math.max(0, (b - t) / 12));
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) card.getLayoutParams();
                if (params.topMargin != gap) { params.topMargin = gap; card.setLayoutParams(params); }
            });
            view.setOnClickListener(ignored -> cancel()); card.setClickable(true);
            handle = new InspectorSheetHandle(context, card, this::cancel);
            card.addView(handle, new LinearLayout.LayoutParams(-1, InspectorTheme.dp(context, 40)));
        }
        bar = new LinearLayout(context); bar.setGravity(Gravity.CENTER_VERTICAL);
        int inset = InspectorTheme.dp(context, 8); bar.setPadding(inset, inset, inset, inset);
        back = InspectorWidgets.icon(context, panel ? R.drawable.osservio_close : R.drawable.osservio_back,
            panel ? R.string.osservio_archive_cancel : R.string.osservio_ui_back, this::cancel);
        back.setVisibility(panel ? View.GONE : View.VISIBLE); bar.addView(back);
        heading = InspectorTheme.text(context, 19, true); heading.setText(title); heading.setMaxLines(2);
        heading.setPadding(inset, 0, inset, 0); bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        minimize = InspectorWidgets.icon(context, R.drawable.osservio_minimize, R.string.osservio_ui_minimize,
            () -> { if (navigation != null) navigation.minimize(); });
        bar.addView(minimize); updateMinimize();
        card.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        card.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    void compact() {
        FrameLayout.LayoutParams cardLayout = (FrameLayout.LayoutParams) card.getLayoutParams();
        cardLayout.height = -2; card.setLayoutParams(cardLayout);
        content.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    }
    void animateEntry() {
        long duration = InspectorFloatingEntry.motionDuration(view.getContext());
        if (duration == 0) return;
        card.setAlpha(0); card.setTranslationY(InspectorTheme.dp(view.getContext(), panel ? 24 : 8));
        card.animate().alpha(1).translationY(0).setDuration(duration).start();
    }
    Button action(int label, Runnable operation) {
        Button action = InspectorWidgets.button(view.getContext(), label, operation);
        if (view.getResources().getConfiguration().fontScale >= 1.5f) {
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
            layout.setMargins(InspectorTheme.dp(view.getContext(), 16), 0, InspectorTheme.dp(view.getContext(), 16), 0);
            card.addView(action, card.indexOfChild(bar) + 1, layout);
        } else bar.addView(action, bar.getChildCount() - 1, new LinearLayout.LayoutParams(-2, -2));
        return action;
    }
    void leadingAction(int label, Runnable operation) {
        back.setVisibility(View.GONE);
        Button action = InspectorWidgets.button(view.getContext(), label, operation);
        bar.addView(action, 0, new LinearLayout.LayoutParams(-2, -2));
        heading.setGravity(Gravity.CENTER);
    }
    boolean coversTabs() { return panel || modal; }
    void fullScreenTask() { modal = true; }
    void showCancel() { back.setVisibility(View.VISIBLE); }
    ImageButton iconAction(int icon, int label, Runnable operation) {
        ImageButton action = InspectorWidgets.icon(view.getContext(), icon, label, operation);
        bar.addView(action, bar.getChildCount() - 1); return action;
    }
    void updateMinimize() { minimize.setVisibility(!panel && navigation != null && navigation.canMinimize() ? View.VISIBLE : View.GONE); }
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
        if (!cancelable || (backInterceptor != null && backInterceptor.consume())) return;
        if (cancelled != null) cancelled.onCancel(this);
        dismiss();
    }
    void setCancelable(boolean value) { cancelable = value; if (handle != null) handle.setEnabled(value); back.setEnabled(value); if (fallback != null) fallback.setCancelable(value); }
    void setOnDismissListener(OnDismissListener listener) { dismissed = listener; }
    void setOnShowListener(OnShowListener listener) { appeared = listener; }
    void setOnCancelListener(OnCancelListener listener) { cancelled = listener; }
    void didShow() { showing = true; if (appeared != null) appeared.onShow(this); }
    void didDismiss() {
        card.animate().cancel(); card.setAlpha(1); card.setTranslationY(0);
        if (!showing) return; showing = false; if (dismissed != null) dismissed.onDismiss(this);
    }
}
