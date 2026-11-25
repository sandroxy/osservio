package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;

/** Independent tab stacks share one presentation owner. Temporary panels cover the tab bar. */
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorNavigation extends FrameLayout {
    final InspectorGlassSession materials;
    InspectorIncidentRecording incidentRecording;
    private final List<List<InspectorScreen>> stacks = new ArrayList<>();
    private final View rootPage;
    private final FrameLayout modalContainer;
    private final View workspace;
    private InspectorToolbar rootToolbar;
    private int measuredToolbarHeight = -1;
    private Runnable minimize;
    private Runnable changed;
    private int selection;
    private boolean disposing;
    private int bottomInset;
    float navigationLensAspect;
    private Runnable materialInvalidation;
    void setMaterialInvalidation(Runnable callback) { materialInvalidation = callback; }
    @Override public void onDescendantInvalidated(View child, View target) {
        if (android.os.Build.VERSION.SDK_INT >= 26) super.onDescendantInvalidated(child, target);
        if (materialInvalidation != null) materialInvalidation.run();
    }
    private final android.graphics.Rect contentBounds = new android.graphics.Rect();

    void setBottomInset(int value) {
        if (bottomInset == value) return;
        bottomInset = value; requestLayout();
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int height = sheetToolbarHeight(getContext());
        if (measuredToolbarHeight == height) return;
        measuredToolbarHeight = height;
        // The modal sibling can otherwise reuse its previous measurement when only
        // this page's toolbar changes size. Its parent measures it after this workspace.
        for (InspectorScreen screen : stacks.get(selection)) if (screen.coversTabs()) screen.view.forceLayout();
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        insetContent(this);
    }
    private void insetContent(View child) {
        if (child.getVisibility() == GONE) return;
        if (child instanceof android.widget.AbsListView || child instanceof android.widget.ScrollView) {
            int[] padding = (int[]) child.getTag(R.id.osservio_scroll_padding);
            if (padding == null) {
                padding = new int[] {child.getPaddingLeft(), child.getPaddingTop(), child.getPaddingRight(), child.getPaddingBottom()};
                child.setTag(R.id.osservio_scroll_padding, padding);
            }
            contentBounds.set(child.getScrollX(), child.getScrollY(), child.getScrollX() + child.getWidth(), child.getScrollY() + child.getHeight());
            offsetDescendantRectToMyCoords(child, contentBounds);
            int inset = contentBounds.bottom >= getHeight() - InspectorTheme.dp(getContext(), 20) ? bottomInset : 0;
            if (child.getPaddingBottom() != padding[3] + inset) child.setPadding(padding[0], padding[1], padding[2], padding[3] + inset);
            ((android.view.ViewGroup) child).setClipToPadding(false);
            return; // A scroll container owns its content; never inset individual rows.
        }
        Object margin = child.getTag(R.id.osservio_floating_margin);
        if (margin instanceof Integer && child.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
            if (params.bottomMargin != (Integer) margin + bottomInset) {
                params.bottomMargin = (Integer) margin + bottomInset; child.setLayoutParams(params);
            }
        }
        if (child instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) child;
            for (int i = 0; i < group.getChildCount(); i++) insetContent(group.getChildAt(i));
        }
    }

    InspectorNavigation(Context context, View rootPage, FrameLayout modalContainer, View workspace, InspectorGlassSession materials) {
        super(context); this.materials = materials; this.rootPage = rootPage; this.modalContainer = modalContainer; this.workspace = workspace;
        for (int i = 0; i < 4; i++) stacks.add(new ArrayList<>());
        addView(rootPage, new LayoutParams(-1, -1));
    }
    void setChanged(Runnable action) { changed = action; }
    void setRootToolbar(InspectorToolbar toolbar) { rootToolbar = toolbar; }
    int sheetToolbarHeight(Context context) {
        int tab = tab(context);
        List<InspectorScreen> active = stacks.get(tab < 0 ? selection : tab);
        for (int index = active.size() - 1; index >= 0; index--) {
            InspectorScreen screen = active.get(index);
            if (!screen.isTransient()) return screen.toolbarHeight();
        }
        return rootToolbar == null ? 0 : rootToolbar.getMeasuredHeight();
    }
    void select(int tab) { selection = tab; reconcile(); }
    boolean isAtRoot() { return stacks.get(selection).isEmpty(); }
    boolean coversNavigation() {
        List<InspectorScreen> active = stacks.get(selection);
        return !active.isEmpty() && active.get(active.size() - 1).coversTabs();
    }
    boolean materialVisible(View view) {
        if (!view.isShown()) return false;
        for (View current = view; current != null; current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            if (current.getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return false;
        }
        return true;
    }
    void setMinimize(Runnable action) {
        minimize = action;
        for (List<InspectorScreen> stack : stacks) for (InspectorScreen screen : stack) screen.updateMinimize();
    }
    boolean canMinimize() { return minimize != null; }
    void minimize() { if (minimize != null) minimize.run(); }
    void push(InspectorScreen screen) {
        int tab = tab(screen.view.getContext());
        List<InspectorScreen> stack = stacks.get(tab < 0 ? selection : tab);
        if (disposing || stack.contains(screen)) return;
        InspectorWidgets.hideKeyboard(this);
        stack.add(screen);
        (screen.coversTabs() ? modalContainer : this).addView(screen.view, new LayoutParams(-1, -1));
        reconcile(); screen.didShow(); screen.animateEntry();
    }
    void remove(InspectorScreen screen) {
        for (List<InspectorScreen> stack : stacks) {
            int index = stack.indexOf(screen);
            if (index < 0) continue;
            InspectorWidgets.hideKeyboard(this);
            while (stack.size() > index) {
                InspectorScreen removed = stack.remove(stack.size() - 1);
                (removed.coversTabs() ? modalContainer : this).removeView(removed.view); removed.didDismiss();
            }
            reconcile(); return;
        }
    }
    boolean back() {
        List<InspectorScreen> stack = stacks.get(selection);
        if (stack.isEmpty()) return false;
        stack.get(stack.size() - 1).navigateBack(); return true;
    }
    void refreshAppearance(Context themed) { refreshAppearance(themed, null); }
    void refreshAppearance(Context themed, InspectorLayoutConfiguration layout) {
        java.util.Set<OwnerContext> contexts = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (List<InspectorScreen> stack : stacks) for (InspectorScreen screen : stack) {
            OwnerContext owner = owner(screen.view.getContext());
            if (owner != null && owner.tab >= 0 && contexts.add(owner)) owner.setBaseContext(themed);
        }
        for (List<InspectorScreen> stack : stacks) for (InspectorScreen screen : stack) {
            if (layout != null) layout.apply(screen.view);
            InspectorAppearance.refresh(screen.view);
        }
    }
    void discardPanels() {
        for (List<InspectorScreen> stack : stacks) {
            // Host-driven hiding discards transient UI, even when user dismissal needs confirmation.
            for (InspectorScreen screen : new ArrayList<>(stack)) if (screen.isTransient()) { screen.discard(); break; }
        }
    }
    void dispose() {
        disposing = true;
        for (List<InspectorScreen> stack : stacks) if (!stack.isEmpty()) remove(stack.get(0));
        removeAllViews(); rootToolbar = null; minimize = null; changed = null;
    }
    private void reconcile() {
        List<InspectorScreen> active = stacks.get(selection);
        int first = active.size() - 1;
        while (first >= 0 && active.get(first).isTransient()) first--;
        boolean modal = !active.isEmpty() && active.get(active.size() - 1).coversTabs();
        rootPage.setVisibility(first < 0 ? VISIBLE : GONE);
        workspace.setImportantForAccessibility(modal ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        rootPage.setImportantForAccessibility(active.isEmpty() ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (int tab = 0; tab < stacks.size(); tab++) {
            List<InspectorScreen> stack = stacks.get(tab);
            for (int i = 0; i < stack.size(); i++) {
                InspectorScreen screen = stack.get(i);
                screen.view.setVisibility(tab == selection && i >= first ? VISIBLE : GONE);
                screen.view.setImportantForAccessibility(tab == selection && i == active.size() - 1
                    ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
        }
        if (changed != null) changed.run();
        materials.refresh();
    }
    private static OwnerContext owner(Context context) {
        for (int i = 0; i < 16 && context instanceof ContextWrapper; i++) {
            if (context instanceof OwnerContext) return (OwnerContext) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break; context = base;
        }
        return null;
    }
    static InspectorNavigation from(Context context) { OwnerContext owner = owner(context); return owner == null ? null : owner.navigation; }
    private static int tab(Context context) { OwnerContext owner = owner(context); return owner == null ? -1 : owner.tab; }
    static boolean owns(Context context) { return from(context) != null; }
    static final class OwnerContext extends android.content.MutableContextWrapper {
        InspectorNavigation navigation;
        final int tab;
        OwnerContext(Context base) { this(base, null, -1); }
        OwnerContext(Context base, InspectorNavigation navigation, int tab) { super(base); this.navigation = navigation; this.tab = tab; }
    }
}
