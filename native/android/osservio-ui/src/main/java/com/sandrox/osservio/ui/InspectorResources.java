package com.sandrox.osservio.ui;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.SparseArray;

/** View-scoped strings backed by a base-package table, independent of language splits. */
final class InspectorResources extends Resources {
    private final SparseArray<CharSequence> translations = new SparseArray<>();

    // A Resources subclass is necessary for platform widgets' setText(resourceId).
    // Its configuration is copied from the scoped context, never updated globally.
    @SuppressWarnings("deprecation")
    InspectorResources(Resources source, boolean english) {
        super(source.getAssets(), source.getDisplayMetrics(), source.getConfiguration());
        if (!english) {
            TypedArray pairs = source.obtainTypedArray(R.array.osservio_chinese_translations);
            try {
                for (int i = 0; i < pairs.length(); i += 2) translations.put(pairs.getResourceId(i, 0), pairs.getText(i + 1));
            } finally { pairs.recycle(); }
        }
    }
    @Override public CharSequence getText(int id) {
        CharSequence translated = translations.get(id);
        return translated == null ? super.getText(id) : translated;
    }
    @Override public CharSequence getText(int id, CharSequence fallback) {
        CharSequence translated = translations.get(id);
        return translated == null ? super.getText(id, fallback) : translated;
    }
}
