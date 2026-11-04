package com.gathin.tvhome;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Mosaic Home (theme key "mosaic"): six columns, the first tile two columns wide and two rows tall, four tiles
 * of two columns beside it, then one two-column tile with four single ones, then rows of six single tiles. The
 * App library pill, when present, takes a full row at the end. Every child is laid out (Home holds a few dozen
 * tiles at most), so the platform's geometric focus search moves between tiles of different sizes naturally.
 */
final class MosaicLayoutManager extends RecyclerView.LayoutManager {
    static final int COLS = 6;
    /** Tiles 0-9 fill the first three rows (the big tile's pattern), which fit one screen. */
    static final int FIRST_SCREEN = 10;
    /** Theme key "mosaic_style": "twin" puts two big cards side by side on top, then rows of six (set by Home). */
    static boolean TWIN;

    /** The tiles of the first screen: the big card's three rows, or the two big cards and one row of six. */
    static int firstScreen() {
        return TWIN ? 8 : FIRST_SCREEN;
    }

    /** A big card: its logo and title are drawn larger. */
    static boolean isBig(int position) {
        return TWIN ? position < 2 : position == 0;
    }

    interface FullRow {
        boolean isFullRow(int position);
    }

    private final float density;
    private final FullRow fullRow;
    /** Run after each layout: Home re-places the focus highlight over the (possibly resized) focused tile. */
    Runnable afterLayout;
    private int scrollY;
    /** Where a smooth scroll that is still running will stop; -1 when none is. */
    private int pendingScrollY = -1;
    private int contentHeight;

    MosaicLayoutManager(float density, FullRow fullRow) {
        this.density = density;
        this.fullRow = fullRow;
    }

    static int gap(float density) {
        return Math.round(12 * density);   // mosaic gutter, same on every side
    }

    /** Width of one column for a grid of this inner width. */
    static int unit(int innerWidth, float density) {
        return unit(innerWidth, 0, density);
    }

    /** Square cells: as wide as six fit across, but no taller than three rows fit in the visible height. */
    static int unit(int innerWidth, int innerHeight, float density) {
        int byWidth = (innerWidth - (COLS - 1) * gap(density)) / COLS;
        if (innerHeight <= 0) return byWidth;
        return Math.min(byWidth, (innerHeight - 2 * gap(density)) / 3);
    }

    /** Height of one row: a single tile is a little wider than tall, like the picture's small tiles. */
    static int rowHeight(int innerWidth, float density) {
        return unit(innerWidth, density);
    }

    /** {col, row, colSpan, rowSpan} of a tile in the pattern. */
    static int[] cell(int position) {
        if (TWIN) {
            if (position < 2) return new int[]{position * 3, 0, 3, 2};
            int t = position - 2;
            return new int[]{t % COLS, 2 + t / COLS, 1, 1};
        }
        switch (position) {
            case 0: return new int[]{0, 0, 2, 2};
            case 1: return new int[]{2, 0, 2, 1};
            case 2: return new int[]{4, 0, 2, 1};
            case 3: return new int[]{2, 1, 2, 1};
            case 4: return new int[]{4, 1, 2, 1};
            case 5: return new int[]{0, 2, 2, 1};
            case 6: case 7: case 8: case 9: return new int[]{position - 4, 2, 1, 1};
            default:
                int k = position - 10;
                return new int[]{k % COLS, 3 + k / COLS, 1, 1};
        }
    }

    /** The tile's rectangle inside the grid's content area (no padding, no scroll). */
    static Rect rect(int position, int innerWidth, float density) {
        return rect(position, innerWidth, 0, density);
    }

    /** Row height: three rows fit the visible height, but a cell never gets taller than it is wide. */
    static int rowHeight(int innerWidth, int innerHeight, float density) {
        int w = unit(innerWidth, density);
        if (innerHeight <= 0) return w;
        return Math.min(w, (innerHeight - 2 * gap(density)) / 3);
    }

    static Rect rect(int position, int innerWidth, int innerHeight, float density) {
        int[] c = cell(position);
        int u = unit(innerWidth, density), h = rowHeight(innerWidth, innerHeight, density), g = gap(density);
        int left = c[0] * (u + g), top = c[1] * (h + g);
        return new Rect(left, top, left + c[2] * u + (c[2] - 1) * g, top + c[3] * h + (c[3] - 1) * g);
    }

    @Override
    public RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public boolean canScrollVertically() {
        return true;
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        detachAndScrapAttachedViews(recycler);
        int count = state.getItemCount();
        if (count == 0) {
            contentHeight = 0;
            return;
        }
        int inner = getWidth() - getPaddingLeft() - getPaddingRight();
        int innerH = getHeight() - getPaddingTop() - getPaddingBottom();
        int rowH = rowHeight(inner, innerH, density), g = gap(density);
        int bottom = 0;
        for (int i = 0; i < count; i++) {
            Rect r;
            if (fullRow != null && fullRow.isFullRow(i)) {
                int top = bottom + g;
                r = new Rect(0, top, inner, top + Math.round(64 * density));
            } else {
                r = rect(i, inner, innerH, density);
            }
            bottom = Math.max(bottom, r.bottom);
            View v = recycler.getViewForPosition(i);
            addView(v);
            v.measure(View.MeasureSpec.makeMeasureSpec(r.width(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(r.height(), View.MeasureSpec.EXACTLY));
            // Square cells may leave room at the sides: centre the mosaic
            int used = inner;   // columns fill the full width now
            int xOff = fullRow != null && fullRow.isFullRow(i) ? 0 : Math.max(0, (inner - used) / 2);
            int left = getPaddingLeft() + xOff + r.left, top = getPaddingTop() + r.top - scrollY;
            layoutDecorated(v, left, top, left + r.width(), top + r.height());
        }
        contentHeight = bottom + Math.round(rowH * 0.25f);
        int max = maxScroll();
        if (scrollY > max) {
            offsetChildrenVertical(scrollY - max);
            scrollY = max;
        }
    }

    @Override
    public void onLayoutCompleted(RecyclerView.State state) {
        super.onLayoutCompleted(state);
        if (afterLayout != null && getChildCount() > 0) getChildAt(0).post(afterLayout);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (getHeight() - getPaddingTop() - getPaddingBottom()));
    }

    @Override
    public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
        int target = Math.max(0, Math.min(scrollY + dy, maxScroll()));
        int delta = target - scrollY;
        if (delta == 0) return 0;
        scrollY = target;
        offsetChildrenVertical(-delta);
        return delta;
    }

    @Override
    public void scrollToPosition(int position) {
        scrollY = 0;
        requestLayout();
    }

    /** Bring a focused tile fully on screen, nothing more. */
    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                 @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
        // From where the tile will be once a scroll already on its way ends, so a second request for the same
        // focus move (RecyclerView's own, then Home's) does not restart the scroll and overshoot it.
        boolean settling = !immediate && pendingScrollY >= 0
                && parent.getScrollState() == RecyclerView.SCROLL_STATE_SETTLING;
        int ahead = settling ? pendingScrollY - scrollY : 0;
        int top = child.getTop() - ahead, bottom = child.getBottom() - ahead;
        int minTop = getPaddingTop(), maxBottom = getHeight() - getPaddingBottom();
        int dy = top < minTop ? top - minTop : bottom > maxBottom ? bottom - maxBottom : 0;
        // The first screen (the big tile's three rows) always shows from the top: coming back up from the extra
        // rows puts the mosaic back where it started instead of leaving it half scrolled under the clock.
        int pos = getPosition(child);
        boolean firstScreen = pos >= 0 && pos < firstScreen() && (fullRow == null || !fullRow.isFullRow(pos));
        if (firstScreen) dy = -(settling ? pendingScrollY : scrollY);
        if (dy == 0) return settling;
        int target = Math.max(0, Math.min((settling ? pendingScrollY : scrollY) + dy, maxScroll()));
        if (target == scrollY) return false;
        if (immediate) {
            pendingScrollY = -1;
            parent.scrollBy(0, target - scrollY);
        } else {
            pendingScrollY = target;
            parent.smoothScrollBy(0, target - scrollY, new android.view.animation.DecelerateInterpolator(1.6f));
        }
        return true;
    }

    @Override
    public int computeVerticalScrollOffset(@NonNull RecyclerView.State state) {
        return scrollY;
    }

    @Override
    public int computeVerticalScrollRange(@NonNull RecyclerView.State state) {
        return contentHeight;
    }

    @Override
    public int computeVerticalScrollExtent(@NonNull RecyclerView.State state) {
        return getHeight() - getPaddingTop() - getPaddingBottom();
    }
}
