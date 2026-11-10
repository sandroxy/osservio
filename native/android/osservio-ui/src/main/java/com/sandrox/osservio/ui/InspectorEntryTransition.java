package com.sandrox.osservio.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import java.util.ArrayList;
import java.util.List;

/** Native reveal clipping and alpha only: no screenshots, bitmap blur or per-frame Java work. */
final class InspectorEntryTransition {
    private AnimatorSet animator;
    private ViewGroup surface;
    private View entry;
    private View blocker;
    private ViewTreeObserver.OnPreDrawListener pending;
    private View.OnLayoutChangeListener geometry;
    private Runnable completed;
    private int generation;
    private boolean closing;
    boolean isClosing() { return closing && surface != null; }

    void run(ViewGroup target, Rect originOnScreen, boolean opening, View entry, Runnable completed) {
        cancel(); this.surface = target; this.entry = entry; this.completed = completed; closing = !opening;
        if (InspectorFloatingEntry.motionDuration(target.getContext()) == 0) { finish(); return; }
        final int ticket = generation;
        blocker = new View(target.getContext()); blocker.setClickable(true); blocker.setFocusable(true);
        blocker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        target.addView(blocker, new ViewGroup.LayoutParams(-1, -1)); blocker.requestFocus();
        target.setAlpha(opening ? 0 : 1);
        pending = () -> {
            if (target.getViewTreeObserver().isAlive()) target.getViewTreeObserver().removeOnPreDrawListener(pending);
            pending = null;
            if (ticket != generation || !target.isAttachedToWindow()) return true;
            start(originOnScreen, opening, ticket); return true;
        };
        target.getViewTreeObserver().addOnPreDrawListener(pending); target.invalidate();
    }
    private void start(Rect origin, boolean opening, int ticket) {
        ViewGroup target = surface;
        int[] location = new int[2]; target.getLocationOnScreen(location);
        int x = Math.max(0, Math.min(target.getWidth(), origin.centerX() - location[0]));
        int y = Math.max(0, Math.min(target.getHeight(), origin.centerY() - location[1]));
        float small = Math.max(1, origin.width() / 2f);
        float large = (float) Math.hypot(Math.max(x, target.getWidth() - x), Math.max(y, target.getHeight() - y));
        long duration = opening ? 320 : 260;
        List<Animator> tracks = new ArrayList<>();
        Animator reveal = ViewAnimationUtils.createCircularReveal(target, x, y, opening ? small : large, opening ? large : small);
        reveal.setDuration(duration); tracks.add(reveal);
        ObjectAnimator fade = ObjectAnimator.ofFloat(target, View.ALPHA, opening ? 0.12f : 1, opening ? 1 : 0);
        fade.setDuration(duration); tracks.add(fade);
        if (!opening && entry != null) {
            entry.setAlpha(0);
            ObjectAnimator restore = ObjectAnimator.ofFloat(entry, View.ALPHA, 0, 1);
            restore.setStartDelay(duration * 3 / 5); restore.setDuration(duration * 2 / 5); tracks.add(restore);
        }
        geometry = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) finish();
        };
        target.addOnLayoutChangeListener(geometry);
        animator = new AnimatorSet(); animator.playTogether(tracks);
        animator.setInterpolator(new PathInterpolator(0.2f, 0, 0.2f, 1));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { if (ticket == generation) finish(); }
        });
        animator.start();
    }
    private void finish() {
        Runnable result = completed;
        cancel();
        if (result != null) result.run();
    }
    void cancel() {
        generation++;
        if (surface != null) {
            if (pending != null && surface.getViewTreeObserver().isAlive()) surface.getViewTreeObserver().removeOnPreDrawListener(pending);
            if (geometry != null) surface.removeOnLayoutChangeListener(geometry);
        }
        pending = null; geometry = null;
        if (animator != null) { animator.removeAllListeners(); animator.cancel(); animator = null; }
        if (surface != null) { surface.setAlpha(1); if (blocker != null) surface.removeView(blocker); }
        if (entry != null) entry.setAlpha(1);
        blocker = null; surface = null; entry = null; completed = null; closing = false;
    }
}
