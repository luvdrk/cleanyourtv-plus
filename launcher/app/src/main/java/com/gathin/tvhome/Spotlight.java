package com.gathin.tvhome;

import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The app spotlight (app_spotlight): in the shelf above the dock, the focused dock app's banner large with rounded
 * corners, its name and when it was last opened, laid out like the now playing card. It gives the shelf to that
 * card while music plays, and goes with the shelf when focus moves below the dock.
 */
final class Spotlight {
    private final View view;
    private final ImageView art;
    private final TextView name, meta;
    private final float density;
    private final boolean animate;
    /** The shelf is up (focus on the dock or the top bar). */
    private boolean shelf;
    /** The now playing card holds the shelf. */
    private boolean blocked;
    /** Where the spotlight's bottom goes: just above the dock, from MainActivity.showHero. */
    private float bottom = -1;
    private String shownPkg;

    Spotlight(View view, float density, boolean animate) {
        this.view = view;
        this.density = density;
        this.animate = animate;
        art = view.findViewById(R.id.spotlightArt);
        name = view.findViewById(R.id.spotlightName);
        meta = view.findViewById(R.id.spotlightMeta);
        float radius = 14 * density;
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        art.setClipToOutline(true);
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place());
    }

    /** A dock tile took focus: show its app, sliding the new one in when the app changed. */
    void show(String pkg, CharSequence label, Drawable artwork, CharSequence lastOpened, float bottom) {
        this.bottom = bottom;
        shelf = true;
        meta.setText(lastOpened);
        meta.setVisibility(lastOpened == null ? View.GONE : View.VISIBLE);
        if (pkg != null && !pkg.equals(shownPkg)) {
            shownPkg = pkg;
            // Its own copy: the tile keeps drawing the shared one at its own size and state.
            Drawable.ConstantState state = artwork == null ? null : artwork.getConstantState();
            art.setImageDrawable(state != null ? state.newDrawable(view.getResources()).mutate() : artwork);
            name.setText(label);
            if (animate && view.getVisibility() == View.VISIBLE) {
                view.animate().cancel();
                view.setAlpha(0.25f);
                view.setTranslationX(-14 * density);
                view.animate().alpha(1f).translationX(0f).setDuration(220).start();
            }
        }
        place();
        update();
    }

    /** Focus went below the dock (or into the app library): the shelf, and the spotlight with it, gives way. */
    void hideShelf() {
        shelf = false;
        update();
    }

    /** The now playing card came or went; it takes the shelf while it shows. */
    void setBlocked(boolean blocked) {
        this.blocked = blocked;
        update();
    }

    private void update() {
        boolean show = shelf && !blocked && shownPkg != null;
        if (show == (view.getVisibility() == View.VISIBLE)) return;
        view.animate().cancel();
        if (show) {
            view.setVisibility(View.VISIBLE);
            view.setTranslationX(0f);
            view.setAlpha(animate ? 0f : 1f);
            if (animate) view.animate().alpha(1f).setDuration(200).start();
            place();
        } else {
            view.setVisibility(View.GONE);
        }
    }

    private void place() {
        if (bottom >= 0 && view.getHeight() > 0) view.setY(bottom - view.getHeight());
    }
}
