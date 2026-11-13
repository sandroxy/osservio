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

/** Pages and transient surfaces share navigation, accessibility and host-dismissal ownership. */
final class InspectorScreen implements DialogInterface {
    enum Presentation { PAGE, SHEET, CONFIRMATION }
    interface Content { void bindScreen(InspectorScreen screen); }
    interface BackInterceptor { boolean consume(); }
    private BackInterceptor backInterceptor;
    void setBackInterceptor(BackInterceptor interceptor) { backInterceptor = interceptor; }
    final FrameLayout view;
    private final Presentation presentation;
    private final InspectorNavigation navigation;
    private final InspectorToolbar bar;
    private final LinearLayout card;
    private final View content;
    private final ImageButton minimize;
    private final ImageButton back;
    private final TextView heading;
    void title(InspectorAppearance.Value<String> value) { InspectorAppearance.text(heading, value); }
    void title(int resource) { InspectorAppearance.text(heading, resource); }
    private InspectorSheetHandle handle;
    private Dialog fallback;
    private boolean showing;
    private boolean cancelable = true;
    private OnDismissListener dismissed;
    private OnShowListener appeared;
    private OnCancelListener cancelled;

    InspectorScreen(Context context, String title, View content, Presentation presentation) {
        this.presentation = presentation; this.content = content; navigation = InspectorNavigation.from(context);
        view = new InspectorKeyboardDismissal.Frame(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (presentation == Presentation.SHEET) {
                    int bottomInset = getPaddingBottom();
                    int available = View.MeasureSpec.getSize(heightSpec) - getPaddingTop() - bottomInset;
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) card.getLayoutParams();
                    params.topMargin = Math.min(InspectorTheme.dp(context, 56), Math.max(0, available / 12));
                    // Extend the surface beneath system insets; keep the content above them.
                    params.bottomMargin = -bottomInset;
                    card.setPadding(0, 0, 0, bottomInset);
                } else if (presentation == Presentation.CONFIRMATION) {
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) card.getLayoutParams();
                    int margin = InspectorTheme.dp(context, 24);
                    int available = View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight() - margin * 2;
                    params.width = Math.min(InspectorTheme.dp(context, 360), Math.max(0, available));
                    params.setMargins(margin, margin, margin, margin);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL); card.setClipToOutline(true);
        InspectorAppearance.style(view, () -> view.setBackgroundColor(isTransient() ? 0x44000000 : InspectorTheme.canvas(context)));
        InspectorAppearance.style(card, () -> {
            boolean confirmation = presentation == Presentation.CONFIRMATION;
            android.graphics.drawable.GradientDrawable background = InspectorTheme.shape(context,
                confirmation ? InspectorTheme.surface(context) : InspectorTheme.canvas(context), confirmation ? 28 : 0, false);
            if (presentation == Presentation.SHEET) {
                float radius = InspectorTheme.dp(context, 24);
                background.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
            }
            card.setBackground(background);
        });
        view.addView(card, new FrameLayout.LayoutParams(-1, -1, presentation == Presentation.CONFIRMATION ? Gravity.CENTER : Gravity.BOTTOM));
        if (isTransient()) { view.setClickable(true); card.setClickable(true); }
        if (presentation == Presentation.CONFIRMATION) {
            bar = null; back = null; heading = null; minimize = null;
            card.addView(content, new LinearLayout.LayoutParams(-1, -2));
            compact(); return;
        }
        boolean panel = presentation == Presentation.SHEET;
        if (panel) {
            view.setClipToPadding(false);
            view.setOnClickListener(ignored -> cancel()); card.setClickable(true);
            handle = new InspectorSheetHandle(context, card, this::cancel);
            card.addView(handle, new LinearLayout.LayoutParams(-1, InspectorTheme.dp(context, 40)));
        }
        bar = new InspectorToolbar(context);
        back = InspectorWidgets.icon(context, panel ? R.drawable.osservio_close : R.drawable.osservio_back,
            panel ? R.string.osservio_archive_cancel : R.string.osservio_ui_back, this::cancel);
        back.setVisibility(panel ? View.GONE : View.VISIBLE); bar.leading.addView(back);
        heading = bar.title; heading.setText(title);
        minimize = InspectorWidgets.icon(context, R.drawable.osservio_minimize, R.string.osservio_ui_minimize,
            () -> { if (navigation != null) navigation.minimize(); });
        bar.pageActions.exit(minimize); updateMinimize();
        card.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        card.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    void bindContent() { if (content instanceof Content) ((Content) content).bindScreen(this); }
    void compact() {
        FrameLayout.LayoutParams cardLayout = (FrameLayout.LayoutParams) card.getLayoutParams();
        cardLayout.height = -2; card.setLayoutParams(cardLayout);
        content.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    }
    void animateEntry() {
        long duration = InspectorFloatingEntry.motionDuration(view.getContext());
        if (duration == 0) return;
        card.setAlpha(0); card.setTranslationY(InspectorTheme.dp(view.getContext(), presentation == Presentation.SHEET ? 24 : presentation == Presentation.PAGE ? 8 : 0));
        card.animate().alpha(1).translationY(0).setDuration(duration).start();
    }
    Button action(int label, Runnable operation) {
        Button action = InspectorWidgets.button(view.getContext(), label, operation);
        if (presentation == Presentation.SHEET) InspectorWidgets.sheetAction(action, true);
        bar.pageActions.add(action);
        return action;
    }
    Button leadingAction(int label, Runnable operation) {
        back.setVisibility(View.GONE);
        Button action = InspectorWidgets.button(view.getContext(), label, operation);
        if (presentation == Presentation.SHEET) InspectorWidgets.sheetAction(action, false);
        bar.leading.addView(action, 0, new LinearLayout.LayoutParams(-2, -2));
        return action;
    }
    boolean isTransient() { return presentation != Presentation.PAGE; }
    boolean coversTabs() { return isTransient(); }
    ImageButton iconAction(int icon, int label, Runnable operation) {
        ImageButton action = InspectorWidgets.icon(view.getContext(), icon, label, operation);
        addAction(action); return action;
    }
    void addAction(View action) {
        bar.pageActions.add(action);
    }
    void refreshActions() { bar.pageActions.render(); }
    void updateMinimize() {
        if (minimize == null) return;
        minimize.setVisibility(!isTransient() && navigation != null && navigation.canMinimize() ? View.VISIBLE : View.GONE); bar.pageActions.render();
    }
    void show() {
        if (showing) return;
        if (navigation != null) { navigation.push(this); return; }
        Context context = view.getContext();
        fallback = new Dialog(context, InspectorPreferences.dark(context) ? R.style.OsservioScreenDark : R.style.OsservioScreenLight) {
            @SuppressWarnings("deprecation")
            @Override public void onBackPressed() { InspectorScreen.this.cancel(); }
        };
        fallback.setContentView(view); fallback.setCancelable(cancelable);
        fallback.setCanceledOnTouchOutside(false);
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
        discard();
    }
    /** Host-driven dismissal skips draft prompts but still notifies cancellation owners. */
    void discard() {
        if (cancelled != null) cancelled.onCancel(this);
        dismiss();
    }
    void setCancelable(boolean value) { cancelable = value; if (handle != null) handle.setEnabled(value); if (back != null) back.setEnabled(value); if (fallback != null) fallback.setCancelable(value); }
    void setOnDismissListener(OnDismissListener listener) { dismissed = listener; }
    void setOnShowListener(OnShowListener listener) { appeared = listener; }
    void setOnCancelListener(OnCancelListener listener) { cancelled = listener; }
    void didShow() { showing = true; if (appeared != null) appeared.onShow(this); }
    void didDismiss() {
        card.animate().cancel(); card.setAlpha(1); card.setTranslationY(0);
        if (!showing) return; showing = false; if (dismissed != null) dismissed.onDismiss(this);
    }
}
