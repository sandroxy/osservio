package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Theme selection updates SDK-owned preferences without replacing retained readers. */
final class InspectorThemeView extends ScrollView implements InspectorScreen.Content {
    InspectorThemeView(Context context) {
        super(context);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, 0, inset, inset); addView(content);
        android.widget.TextView modeTitle = InspectorWidgets.sectionLabel(context);
        InspectorAppearance.text(modeTitle, R.string.osservio_ui_display_mode); content.addView(modeTitle);
        LinearLayout modes = InspectorWidgets.group(context); content.addView(modes);
        for (boolean dark : new boolean[] {false, true}) {
            CheckedTextView row = InspectorWidgets.choice(context);
            InspectorAppearance.text(row, dark ? R.string.osservio_ui_dark : R.string.osservio_ui_light);
            InspectorAppearance.content(row, () -> row.setChecked(InspectorPreferences.dark(context) == dark));
            row.setChecked(InspectorPreferences.dark(context) == dark);
            row.setOnClickListener(ignored -> InspectorPreferences.setDark(context, dark));
            InspectorWidgets.addGrouped(modes, row);
        }
        android.widget.TextView paletteTitle = InspectorWidgets.sectionLabel(context);
        InspectorAppearance.text(paletteTitle, R.string.osservio_ui_palette); content.addView(paletteTitle);
        PaletteGrid grid = new PaletteGrid(context); content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        android.widget.TextView hint = InspectorWidgets.caption(context);
        InspectorAppearance.text(hint, R.string.osservio_ui_preferences_hint); content.addView(hint);
    }
    @Override public void bindScreen(InspectorScreen screen) { screen.title(R.string.osservio_ui_appearance); }

    private static final class PaletteGrid extends ViewGroup {
        private int columns = 5;
        PaletteGrid(Context context) {
            super(context); int inset = InspectorTheme.dp(context, 12); setPadding(inset, inset, inset, inset);
            InspectorAppearance.style(this, () -> InspectorTheme.surface(this, false, 20));
            for (InspectorPalette palette : InspectorPalette.values()) addView(new Swatch(context, palette));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            columns = Math.max(1, Math.min(5, available / InspectorTheme.dp(getContext(), 56)));
            int size = InspectorTheme.dp(getContext(), 48), cell = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(cell, cell);
            int height = ((getChildCount() + columns - 1) / columns) * InspectorTheme.dp(getContext(), 56) + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int step = (getWidth() - getPaddingLeft() - getPaddingRight()) / columns;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); int x = getPaddingLeft() + i % columns * step + (step - child.getMeasuredWidth()) / 2;
                int y = getPaddingTop() + i / columns * InspectorTheme.dp(getContext(), 56) + InspectorTheme.dp(getContext(), 4);
                child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
            }
        }
    }
    private static final class Swatch extends View {
        private final InspectorPalette palette;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();
        Swatch(Context context, InspectorPalette palette) {
            super(context); this.palette = palette; setFocusable(true);
            setOnClickListener(ignored -> InspectorPreferences.setPalette(context, palette));
            InspectorAppearance.style(this, () -> {
                setContentDescription(context.getString(palette.label));
                setSelected(InspectorPreferences.palette(context) == palette);
                InspectorTheme.feedback(this, 24); invalidate();
            });
        }
        @Override public CharSequence getAccessibilityClassName() { return android.widget.Button.class.getName(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float unit = getResources().getDisplayMetrics().density, x = getWidth() / 2f, y = getHeight() / 2f;
            boolean dark = InspectorPreferences.dark(getContext());
            paint.setStyle(Paint.Style.FILL); paint.setColor(dark ? palette.dark : palette.light);
            canvas.drawCircle(x, y, 15 * unit, paint);
            if (isSelected()) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * unit); canvas.drawCircle(x, y, 19 * unit, paint);
                paint.setColor(dark ? InspectorTheme.canvas(getContext()) : android.graphics.Color.WHITE);
                paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
                check.reset(); check.moveTo(x - 6 * unit, y); check.lineTo(x - 1.5f * unit, y + 4 * unit); check.lineTo(x + 7 * unit, y - 5 * unit);
                canvas.drawPath(check, paint);
            }
        }
    }
}
