package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/** A native single-choice group with horizontal lens preview and an untruncated vertical fallback. */
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorSegmentControl extends RadioGroup {
    final InspectorSelectionMaterial material;
    private boolean restoreMaterial;
    InspectorSegmentControl(ViewGroup owner, View viewport, int count) {
        super(owner.getContext());
        InspectorNavigation navigation = InspectorNavigation.from(getContext());
        material = new InspectorSelectionMaterial(this, navigation == null ? null : navigation.materials, count, true,
            new InspectorSelectionMaterial.Content() {
                @Override public boolean ready() {
                    return getOrientation() == HORIZONTAL && navigation != null && navigation.materialVisible(InspectorSegmentControl.this);
                }
                @Override public void observe(Runnable changed) { }
                @Override public float navigationLensAspect() { return navigation == null ? 0 : navigation.navigationLensAspect; }
                @Override public void drawBackdrop(Canvas canvas) { canvas.drawColor(InspectorTheme.canvas(getContext())); }
                @Override public void drawLabel(Canvas canvas, int index) { ((Choice) getChildAt(index)).drawLabel(canvas); }
            });
        if (navigation != null) material.overflow = new InspectorSegmentOverflow(this, owner, viewport);
        setClipChildren(false); setClipToPadding(false);
        InspectorAppearance.content(this, material::refreshAppearance);
    }
    @Override public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        super.setOnCheckedChangeListener((group, id) -> {
            View selected = findViewById(id);
            if (selected != null && selected.getTag() instanceof Integer) material.syncSelection((Integer) selected.getTag());
            if (listener != null) listener.onCheckedChanged(group, id);
        });
        View selected = findViewById(getCheckedRadioButtonId());
        if (selected != null) material.syncSelection((Integer) selected.getTag());
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        boolean vertical = false;
        if (getChildCount() > 0 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
            int share = Math.max(0, available / getChildCount());
            for (int index = 0; index < getChildCount(); index++) {
                TextView choice = (TextView) getChildAt(index);
                float needed = android.text.Layout.getDesiredWidth(choice.getText(), choice.getPaint())
                    + choice.getCompoundPaddingLeft() + choice.getCompoundPaddingRight();
                if (needed > share) { vertical = true; break; }
            }
        }
        int orientation = vertical ? VERTICAL : HORIZONTAL;
        if (getOrientation() != orientation) {
            material.release(); setOrientation(orientation);
            for (int index = 0; index < getChildCount(); index++) {
                LayoutParams params = (LayoutParams) getChildAt(index).getLayoutParams();
                params.width = vertical ? LayoutParams.MATCH_PARENT : 0; params.weight = vertical ? 0 : 1;
                params.topMargin = vertical && index > 0 ? InspectorTheme.dp(getContext(), 2) : 0;
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    @Override public void draw(Canvas canvas) {
        if (restoreMaterial) { restoreMaterial = false; material.updateRegistration(); }
        if (!material.draw(canvas)) super.draw(canvas);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return material.intercept(event); }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Commit dispatches through the selected RadioButton, including keyboard/accessibility.
    @Override public boolean onTouchEvent(MotionEvent event) { return material.touch(event) || super.onTouchEvent(event); }
    @Override public void onDescendantInvalidated(View child, View target) {
        if (android.os.Build.VERSION.SDK_INT >= 26) super.onDescendantInvalidated(child, target);
        if (material != null) material.inkChanged();
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) material.geometryChangedByLayout();
        material.updateRegistration();
    }
    @Override public void onRtlPropertiesChanged(int direction) {
        super.onRtlPropertiesChanged(direction); if (material != null) material.directionChanged();
    }
    @Override public void onFinishTemporaryDetach() {
        super.onFinishTemporaryDetach();
        // ListView finishes temporary detach before putting the row back in its parent.
        // Rejoin on the first draw, with valid ancestry and no interim fallback frame.
        restoreMaterial = true; invalidate();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); restoreMaterial = false; material.updateRegistration(); }
    @Override protected void onDetachedFromWindow() { restoreMaterial = false; material.detach(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility); if (material != null) material.updateRegistration();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); if (material != null) material.updateRegistration();
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); material.focusChanged(focused); }
    @android.annotation.SuppressLint("ViewConstructor")
    static final class Choice extends RadioButton {
        Choice(Context context) { super(context); }
        @android.annotation.SuppressLint("WrongCall") // Capture this native label only; draw() would also capture its selected background.
        void drawLabel(Canvas canvas) { super.onDraw(canvas); }
    }
}
