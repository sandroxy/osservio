package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.sandrox.osservio.core.AppleSignalCrash;
import com.sandrox.osservio.core.AndroidTombstone;

/** Immutable address evidence. Only visible rows are formatted until an explicit copy. */
@SuppressLint("ViewConstructor")
final class SignalCrashView extends LinearLayout implements InspectorScreen.Content {
    private final AppleSignalCrash crash;
    private final AndroidTombstone tombstone;
    private final boolean images;
    private final LinearLayout controls;
    private final android.widget.ImageButton copy;
    private final TextView title;
    SignalCrashView(Context context, AppleSignalCrash crash, boolean images, Runnable goBack) {
        this(context, crash, null, images, goBack);
    }
    SignalCrashView(Context context, AndroidTombstone tombstone, Runnable goBack) {
        this(context, null, tombstone, false, goBack);
    }
    private SignalCrashView(Context context, AppleSignalCrash crash, AndroidTombstone tombstone, boolean images, Runnable goBack) {
        super(context); this.crash = crash; this.tombstone = tombstone; this.images = images; setOrientation(VERTICAL);
        controls = new LinearLayout(context);
        android.widget.ImageButton back = InspectorWidgets.icon(context, R.drawable.osservio_back, R.string.osservio_ui_back, () -> { }); back.setOnClickListener(view -> goBack.run());
        copy = InspectorWidgets.icon(context, R.drawable.osservio_copy, R.string.osservio_crash_copy_all, () -> { }); copy.setOnClickListener(view -> {
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < count(); index++) { if (index != 0) text.append("\n\n"); text.append(copyText(index)); }
            copy(text.toString());
        });
        controls.addView(back); View spacer = new View(context); controls.addView(spacer, new LayoutParams(0, 1, 1)); controls.addView(copy); addView(controls);
        title = text(); InspectorAppearance.text(title, images ? R.string.osservio_crash_images : R.string.osservio_crash_frames);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        addView(title);
        ListView list = new InspectorScroll.List(context); list.setPadding(dp(16), dp(20), dp(16), dp(16)); list.setClipToPadding(false); list.setDivider(null); list.setDividerHeight(0);
        TextView hint = InspectorWidgets.caption(context); hint.setPadding(dp(16), dp(12), dp(16), dp(16)); InspectorAppearance.text(hint, () -> context.getString(R.string.osservio_crash_browser_hint,
            context.getString(tombstone != null ? R.string.osservio_tombstone_frames_hint : images ? R.string.osservio_crash_images_hint : R.string.osservio_crash_frames_hint), context.getString(R.string.osservio_crash_copy_hint)));
        list.addFooterView(hint, null, false); Rows rows = new Rows(); list.setAdapter(rows); InspectorAppearance.content(this, rows::notifyDataSetChanged);
        list.setOnItemClickListener((parent, view, position, id) -> { if (position < count()) copy(copyText(position)); });
        addView(list, new LayoutParams(-1, 0, 1));
    }
    @Override public void bindScreen(InspectorScreen screen) {
        screen.title(images ? R.string.osservio_crash_images : R.string.osservio_crash_frames); screen.addAction(copy); controls.setVisibility(GONE); title.setVisibility(GONE);
    }
    private int count() { return tombstone != null ? tombstone.frames.size() : images ? crash.images.size() : crash.frames.size(); }
    String copyText(int index) {
        if (tombstone != null) return "#" + index + "\n" + TombstonePresentation.frame(getContext(), tombstone.frames.get(index));
        if (!images) return "#" + index + "\n" + SignalCrashPresentation.address(crash.frames.get(index));
        AppleSignalCrash.Image image = crash.images.get(index);
        return "#" + index + " · " + (image.uuid == null ? getContext().getString(R.string.osservio_runtime_unavailable) : image.uuid)
            + "\n" + SignalCrashPresentation.image(getContext(), image);
    }
    private void copy(String value) {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", value));
            Toast.makeText(getContext(), R.string.osservio_crash_copied, Toast.LENGTH_SHORT).show();
        }
    }
    private TextView text() {
        TextView result = InspectorTheme.text(getContext(), 16, false);
        result.setPadding(dp(12), dp(12), dp(12), dp(12)); result.setMinHeight(dp(48)); return result;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return count(); }
        @Override public Object getItem(int position) { return tombstone != null ? tombstone.frames.get(position) : images ? crash.images.get(position) : crash.frames.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.ValueRow row = recycled instanceof InspectorWidgets.ValueRow ? (InspectorWidgets.ValueRow) recycled : new InspectorWidgets.ValueRow(getContext());
            String text = copyText(position); int newline = text.indexOf('\n');
            row.bind(newline < 0 ? text : text.substring(0, newline), newline < 0 ? "" : text.substring(newline + 1), false);
            row.value.setTypeface(android.graphics.Typeface.MONOSPACE); InspectorWidgets.groupedRow(row, position == 0, position == count() - 1); return row;
        }
    }
}
