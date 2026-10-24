package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * fade_rows: the rows fade out as they scroll up under the clock, instead of being cut off at the grid's top
 * edge. It erases the top of the grid's own picture, so whatever is behind Home (a wallpaper, the sky) shows
 * through; the grid draws into a hardware layer for that ({@link #attach}), or the erasing would punch through
 * the whole window. Android's own requiresFadingEdge draws its fade in the wrong place with this grid's
 * clipChildren=false, so it is not used. The fade grows in over the first pixels of scrolling, so the dock at
 * rest is untouched.
 *
 * <p>fade_rows_bottom: the rows at the screen's bottom edge fade part of the way into the wallpaper too. That fade
 * never reaches up into the dock row (its tiles and names), so the dock stays whole while the rows under it fade.
 */
final class TopFade extends RecyclerView.ItemDecoration {

    /** How much of the rows the bottom fade takes away at the very edge: a soft fade, not a cut. */
    private static final int BOTTOM_ALPHA = 0xB3;

    private final float length, bottomLength, dockGap;
    /** The dock's column count, so the bottom fade can stay below the dock row; 0 without a dock row. */
    private final int dockColumns;
    private final Paint paint = new Paint(), bottomPaint = new Paint();
    private float shaderHeight = -1f, bottomTop = -1f, bottomEdge = -1f;

    private TopFade(float length, float bottomLength, int dockColumns, float dockGap) {
        this.length = length;
        this.bottomLength = bottomLength;
        this.dockColumns = dockColumns;
        this.dockGap = dockGap;
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        bottomPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    static void attach(RecyclerView grid, float length, float bottomLength, int dockColumns, float dockGap) {
        grid.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        grid.addItemDecoration(new TopFade(length, bottomLength, dockColumns, dockGap));
    }

    /** How tall the fade is after scrolling {@code scrolled} pixels: nothing at rest, then up to {@code length}. */
    static float height(int scrolled, float length) {
        return Math.max(0f, Math.min(length, scrolled));
    }

    @Override
    public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        float fade = height(parent.computeVerticalScrollOffset(), length);
        if (fade > 0f) {
            if (fade != shaderHeight) {
                paint.setShader(new LinearGradient(0f, 0f, 0f, fade, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
                shaderHeight = fade;
            }
            c.drawRect(0f, 0f, parent.getWidth(), fade, paint);
        }
        if (bottomLength > 0f) drawBottom(c, parent);
    }

    private void drawBottom(Canvas c, RecyclerView parent) {
        float edge = parent.getHeight();
        float top = edge - bottomLength;
        // Keep clear of the dock row while it is on screen: the fade starts under its lowest tile or name.
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position >= 0 && position < dockColumns) top = Math.max(top, child.getBottom() + child.getTranslationY() + dockGap);
        }
        if (top >= edge) return;
        if (top != bottomTop || edge != bottomEdge) {
            bottomPaint.setShader(new LinearGradient(0f, top, 0f, edge, 0x00000000, BOTTOM_ALPHA << 24,
                    Shader.TileMode.CLAMP));
            bottomTop = top;
            bottomEdge = edge;
        }
        c.drawRect(0f, top, parent.getWidth(), edge, bottomPaint);
    }
}
