package com.gathin.tvhome;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Carousel Home (theme key "carousel"): the apps on a loop. The selected card sits in the middle at full size,
 * one on each side shows smaller, and the rest wait out of sight; Left and Right turn the
 * loop (Home moves focus, then calls {@link #setCenter}). Every card is laid out once in the middle and only
 * moved and scaled after that, so turning the loop never lays the row out again. The App library pill, when
 * present, sits centred under the loop.
 */
final class CarouselLayoutManager extends RecyclerView.LayoutManager {
    interface Pill {
        boolean isPill(int position);
    }

    /** Offset from the middle, in card widths, and the scale and opacity at each step away from the middle. */
    private static final float[] SHIFT = {0f, 0.9f, 1.45f, 2.2f};
    private static final float[] SCALE = {1f, 0.74f, 0.56f, 0.45f};
    // Neighbours solid, so nothing shows through them; cards further out would be cut off at the edge, so they wait unseen
    private static final float[] ALPHA = {1f, 1f, 0f, 0f};

    private final float density;
    private final Pill pill;
    private int center;

    CarouselLayoutManager(float density, Pill pill) {
        this.density = density;
        this.pill = pill;
    }

    @Override
    public RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        detachAndScrapAttachedViews(recycler);
        int count = state.getItemCount();
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        for (int i = 0; i < count; i++) {
            View v = recycler.getViewForPosition(i);
            addView(v);
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST));
            int vw = v.getMeasuredWidth(), vh = v.getMeasuredHeight();
            int left = getPaddingLeft() + (w - vw) / 2;
            int top = pill != null && pill.isPill(i)
                    ? getPaddingTop() + h - vh - Math.round(8 * density)          // under the loop
                    : getPaddingTop() + (h - vh) / 2 - Math.round(10 * density);  // a little above the middle
            layoutDecorated(v, left, top, left + vw, top + vh);
        }
        place(false);
    }

    /** Turns the loop so {@code position} is in the middle. */
    void setCenter(int position, boolean animate) {
        if (pill != null && pill.isPill(position)) return;
        if (position == center) {
            place(false);
            return;
        }
        center = position;
        place(animate);
    }

    private void place(boolean animate) {
        List<Integer> ring = new ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) if (pill == null || !pill.isPill(i)) ring.add(i);
        int n = ring.size();
        if (n == 0) return;
        int middle = Math.max(0, ring.indexOf(center));
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            int pos = getPosition(v);
            if (pill != null && pill.isPill(pos)) continue;
            int off = ring.indexOf(pos) - middle;
            if (off > n / 2) off -= n;               // the shorter way round the loop
            if (off < -(n - 1) / 2) off += n;
            int a = Math.min(Math.abs(off), SHIFT.length - 1);
            float tx = Math.signum(off) * SHIFT[a] * v.getWidth();
            float s = SCALE[a], alpha = ALPHA[a];
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
            v.setTranslationZ(10f - a);              // the middle card draws on top
            if (animate) {
                // A card going round the back of the loop is invisible at both ends, so it simply jumps.
                if (v.getAlpha() == 0f && alpha == 0f) {
                    v.animate().cancel();
                    v.setTranslationX(tx);
                    v.setScaleX(s);
                    v.setScaleY(s);
                    continue;
                }
                v.animate().translationX(tx).scaleX(s).scaleY(s).alpha(alpha).setDuration(320)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            } else {
                v.animate().cancel();
                v.setTranslationX(tx);
                v.setScaleX(s);
                v.setScaleY(s);
                v.setAlpha(alpha);
            }
        }
    }
}
