package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;

/** One bounded, in-process navigation hierarchy. Hiding its window preserves page ownership. */
// Workspace ownership is required; this private container is never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorNavigation extends FrameLayout {
    private final List<InspectorScreen> screens = new ArrayList<>();
    private final View workspace;
    private Runnable minimize;
    private boolean disposing;

    InspectorNavigation(Context context, View workspace) {
        super(context); this.workspace = workspace;
        addView(workspace, new LayoutParams(-1, -1));
    }
    void setMinimize(Runnable action) {
        minimize = action;
        for (InspectorScreen screen : screens) screen.updateMinimize();
    }
    boolean canMinimize() { return minimize != null; }
    void minimize() { if (minimize != null) minimize.run(); }
    void push(InspectorScreen screen) {
        if (disposing || screens.contains(screen)) return;
        InspectorWidgets.hideKeyboard(this);
        screens.add(screen); addView(screen.view, new LayoutParams(-1, -1));
        reconcile(); screen.didShow();
    }
    void remove(InspectorScreen screen) {
        int index = screens.indexOf(screen);
        if (index < 0) return;
        InspectorWidgets.hideKeyboard(this);
        // Removing an owning page also releases its descendants, never orphaning a draft.
        while (screens.size() > index) {
            InspectorScreen removed = screens.remove(screens.size() - 1);
            removeView(removed.view); removed.didDismiss();
        }
        reconcile();
    }
    boolean back() {
        if (screens.isEmpty()) return false;
        screens.get(screens.size() - 1).cancel(); return true;
    }
    void dispose() {
        disposing = true;
        if (!screens.isEmpty()) remove(screens.get(0));
        removeAllViews(); minimize = null;
    }
    private void reconcile() {
        int first = screens.size() - 1;
        while (first >= 0 && screens.get(first).panel) first--;
        workspace.setVisibility(first < 0 ? VISIBLE : GONE);
        for (int i = 0; i < screens.size(); i++) screens.get(i).view.setVisibility(i >= first ? VISIBLE : GONE);
        // Covered pages remain attached for state, but are excluded from accessibility traversal.
        workspace.setImportantForAccessibility(screens.isEmpty() ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (int i = 0; i < screens.size(); i++) screens.get(i).view.setImportantForAccessibility(
            i == screens.size() - 1 ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    static InspectorNavigation from(Context context) {
        for (int i = 0; i < 16 && context instanceof ContextWrapper; i++) {
            if (context instanceof OwnerContext) return ((OwnerContext) context).navigation;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break; context = base;
        }
        return null;
    }
    static boolean owns(Context context) { return from(context) != null; }
    static final class OwnerContext extends ContextWrapper {
        InspectorNavigation navigation;
        OwnerContext(Context base) { super(base); }
    }
}
