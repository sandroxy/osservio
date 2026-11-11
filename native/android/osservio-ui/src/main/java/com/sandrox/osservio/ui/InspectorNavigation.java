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
    private final List<List<InspectorScreen>> stacks = new ArrayList<>();
    private final View rootPage;
    private final FrameLayout modalContainer;
    private final View workspace;
    private Runnable minimize;
    private Runnable changed;
    private int selection;
    private boolean disposing;

    InspectorNavigation(Context context, View rootPage, FrameLayout modalContainer, View workspace) {
        super(context); this.rootPage = rootPage; this.modalContainer = modalContainer; this.workspace = workspace;
        for (int i = 0; i < 4; i++) stacks.add(new ArrayList<>());
        addView(rootPage, new LayoutParams(-1, -1));
    }
    void setChanged(Runnable action) { changed = action; }
    void select(int tab) { selection = tab; reconcile(); }
    boolean isAtRoot() { return stacks.get(selection).isEmpty(); }
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
        stack.get(stack.size() - 1).cancel(); return true;
    }
    void refreshAppearance(Context themed) {
        java.util.Set<OwnerContext> contexts = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (List<InspectorScreen> stack : stacks) for (InspectorScreen screen : stack) {
            OwnerContext owner = owner(screen.view.getContext());
            if (owner != null && owner.tab >= 0 && contexts.add(owner)) owner.setBaseContext(themed);
        }
        for (List<InspectorScreen> stack : stacks) for (InspectorScreen screen : stack) InspectorAppearance.refresh(screen.view);
    }
    void discardPanels() {
        for (List<InspectorScreen> stack : stacks) {
            // Host-driven hiding discards transient UI, even when user dismissal needs confirmation.
            for (InspectorScreen screen : new ArrayList<>(stack)) if (screen.panel) { screen.discard(); break; }
        }
    }
    void dispose() {
        disposing = true;
        for (List<InspectorScreen> stack : stacks) if (!stack.isEmpty()) remove(stack.get(0));
        removeAllViews(); minimize = null; changed = null;
    }
    private void reconcile() {
        List<InspectorScreen> active = stacks.get(selection);
        int first = active.size() - 1;
        while (first >= 0 && active.get(first).panel) first--;
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
