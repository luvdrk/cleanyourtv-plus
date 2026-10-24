package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The now playing card (now_playing): while a music app such as Spotify plays or is paused, its artwork, title,
 * artist and album from the app's media session, the song's progress and time left, previous, play/pause, next and
 * Open in the app. It sits in the shelf above the dock, in place of the focused app's name, whichever tile is
 * focused. Up from the dock moves onto play/pause, Down goes back to the tile and Up again goes on to the top bar.
 * Reading another app's session needs notification access for {@link NowPlayingListener}; without it the card
 * simply never shows.
 */
final class NowPlaying {
    private final Activity activity;
    private final View card;
    private final ImageView art, icon, prev, toggle, next;
    private final TextView kicker, title, artist, elapsed, left, open;
    private final View progressRow;
    private final ProgressBar progress;
    /** Moves the progress on while the song plays. */
    private final Runnable progressTick = this::tick;
    /** When the TV went quiet while the session says playing; 0 while music is coming out (see silent()). */
    private long silentSince;
    /** The state the card last drew (play or pause icon), so the tick redraws it when silence changes it. */
    private int renderedState = PlaybackState.STATE_NONE;
    /**
     * The card's own song clock (showProgress): the position at anchorTime, and whether it runs from there. Kept for
     * the next Home like {@link #remembered}: after HDMI the app's last report predates the switch, so the clock as
     * Home left it is the better guess of where the song stopped.
     */
    private static long anchorPosition, anchorTime;
    private static boolean anchorPlaying;
    private static CharSequence anchorTrack;
    private final MediaSessionManager sessions;
    private final ComponentName listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaController controller;
    /**
     * The followed session, kept for the next Home: the Live TV app makes Android create Home afresh when you come
     * back from HDMI, and the new card starts from this. Built on the application context, so it holds no Home.
     */
    private static MediaController remembered;
    /**
     * The followed session is no longer active (Live TV or another app took over, or the app went quiet) but still
     * exists: the card keeps its last song, shown as paused, and play resumes it. Android lists only active
     * sessions, and Spotify leaves its state at playing when it is pushed out, so this is decided here.
     */
    private boolean inactive;
    private boolean started;
    /** The shelf above the dock is showing (focus on the dock or the top bar), so the card may show there. */
    private boolean shelf;
    /** Where the card's bottom goes: just above the dock, from MainActivity.showHero. */
    private float bottom = -1;
    /** The card's content is filled for the followed session (it has a title and plays or is paused). */
    private boolean ready;
    /** The artwork shows a real cover (ALBUM_ART or ART) of the followed app, not its icon. */
    private boolean hasCover;
    /** Puts focus back on the tile the card belongs to (Down, or the card going away while focused). */
    private Runnable onLeave;
    /** Told when the card comes and goes, so the shelf hides the app's name behind it. */
    private Consumer<Boolean> onShown;
    /**
     * Apps whose sessions are never followed: the TV's Live TV and input players. Skyworth's Live TV opens a session at
     * every input switch, and following it lost the music app's song when it closed.
     */
    private final Set<String> ignored = new HashSet<>(Arrays.asList(
            "com.smartdevice.livetv", "com.android.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter",
            "com.mediatek.tvinput"));

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            // Stopped: another app may still be playing or paused, so look again.
            if (showable(stateOf(controller))) render();
            else pick(null);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            render();
        }

        @Override
        public void onSessionDestroyed() {
            follow(null);   // gone for good, not just inactive
            pick(null);
        }
    };

    /** Focus moved: the card follows it in and out of its buttons. */
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusChanged;

    NowPlaying(Activity activity, View card) {
        this.activity = activity;
        this.card = card;
        focusChanged = (oldFocus, newFocus) -> updateVisibility();
        art = card.findViewById(R.id.nowPlayingArt);
        icon = card.findViewById(R.id.nowPlayingIcon);
        kicker = card.findViewById(R.id.nowPlayingKicker);
        title = card.findViewById(R.id.nowPlayingTitle);
        artist = card.findViewById(R.id.nowPlayingArtist);
        progressRow = card.findViewById(R.id.nowPlayingProgressRow);
        progress = card.findViewById(R.id.nowPlayingProgress);
        elapsed = card.findViewById(R.id.nowPlayingElapsed);
        left = card.findViewById(R.id.nowPlayingLeft);
        prev = card.findViewById(R.id.nowPlayingPrev);
        toggle = card.findViewById(R.id.nowPlayingToggle);
        next = card.findViewById(R.id.nowPlayingNext);
        open = card.findViewById(R.id.nowPlayingOpen);
        sessions = activity.getSystemService(MediaSessionManager.class);
        listener = new ComponentName(activity, NowPlayingListener.class);
        float radius = 12 * activity.getResources().getDisplayMetrics().density;
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        art.setClipToOutline(true);
        // The card's height changes with its lines (no album, no duration): keep its bottom on the dock.
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place());

        prev.setOnClickListener(v -> {
            if (needsKeys()) mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            else if (controller != null) controller.getTransportControls().skipToPrevious();
        });
        next.setOnClickListener(v -> {
            if (needsKeys()) mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            else if (controller != null) controller.getTransportControls().skipToNext();
        });
        toggle.setOnClickListener(v -> {
            if (controller == null) return;
            if (isPlaying(effectiveState())) {
                controller.getTransportControls().pause();
            } else if (needsKeys()) {
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
            } else {
                controller.getTransportControls().play();
            }
        });
        open.setOnClickListener(v -> {
            if (controller == null) return;
            PackageManager pm = activity.getPackageManager();
            String pkg = controller.getPackageName();
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (onLeave != null) onLeave.run();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                // On to the top bar above, as Up from the dock does; Inputs if the search finds nothing there.
                View above = v.focusSearch(View.FOCUS_UP);
                if (above == null || inside(above, card)) above = activity.findViewById(R.id.btnInputs);
                if (above != null) above.requestFocus();
                return true;
            }
            // The row's ends: nothing beside the buttons to move to, so stay on them.
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v == prev
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == open;
        };
        prev.setOnKeyListener(keys);
        toggle.setOnKeyListener(keys);
        next.setOnKeyListener(keys);
        open.setOnKeyListener(keys);
    }

    /**
     * Pushed out by Live TV, the app's session is inactive and Android's media-button target is gone (Live TV took it
     * and let it go), so a key sent through the system reaches nobody, and Spotify's media-button receiver takes the
     * key without resuming. What does resume it is the key delivered to its session, as the system delivers the
     * remote's media keys to the media-button session: send it there.
     */
    private void mediaKey(int keyCode) {
        if (controller == null) return;
        controller.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        controller.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    void setOnLeave(Runnable onLeave) {
        this.onLeave = onLeave;
    }

    void setOnShown(Consumer<Boolean> onShown) {
        this.onShown = onShown;
    }

    /** The configured Live TV app (live_tv_package), ignored like the built-in list. */
    void ignore(String pkg) {
        if (pkg != null && !pkg.isEmpty()) ignored.add(pkg);
    }

    /** The shelf shows above the dock, whose top bar line is {@code bottom} in the root's coordinates. */
    void showShelf(float bottom) {
        this.bottom = bottom;
        shelf = true;
        place();
        updateVisibility();
    }

    /** Focus went below the dock, where the shelf (and so the card) gives way to the grid. */
    void hideShelf() {
        shelf = false;
        updateVisibility();
    }

    private void place() {
        if (bottom >= 0 && card.getHeight() > 0) card.setY(bottom - card.getHeight());
    }

    /** Up from the dock: moves onto play/pause when the card is showing. */
    boolean focusControls() {
        if (card.getVisibility() != View.VISIBLE) return false;
        return toggle.requestFocus();
    }

    /** Home is visible: follow the sessions until {@link #stop()}. */
    void start() {
        if (started || sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler);
        } catch (SecurityException e) {
            card.setVisibility(View.GONE);   // no notification access
            return;
        }
        started = true;
        card.getViewTreeObserver().addOnGlobalFocusChangeListener(focusChanged);
        // The song from before Home went away (Live TV, another app): listen to it again while we look.
        if (controller == null) controller = remembered;
        if (controller != null) controller.registerCallback(callback, handler);
        pick(null);
    }

    /**
     * Home left the screen: nothing of ours keeps listening behind another app. The followed session is kept, so
     * coming back from HDMI or an app still shows its last song when Android no longer lists it.
     */
    void stop() {
        if (!started) return;
        started = false;
        sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        card.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusChanged);
        if (controller != null) controller.unregisterCallback(callback);
        ready = false;
        handler.removeCallbacks(progressTick);
        // Stop the clock where Home leaves the song: if Live TV takes over next, that is about where it paused.
        if (anchorPlaying) {
            long now = SystemClock.elapsedRealtime();
            anchorPosition += now - anchorTime;
            anchorTime = now;
            anchorPlaying = false;
        }
        updateVisibility();
    }

    /**
     * Follows the session that is playing, or else one that is paused; a null list means ask again. When neither is
     * listed and the followed session is missing from the list, it went inactive: keep it, shown as paused.
     */
    private void pick(List<MediaController> list) {
        if (!started) return;
        if (list == null) {
            try {
                list = sessions.getActiveSessions(listener);
            } catch (SecurityException e) {
                list = Collections.emptyList();
            }
        }
        MediaController best = null;
        for (MediaController c : list) {
            // The TV's own player for HDMI and the tuner reports "playing" when you switch input: not music.
            if (ignored.contains(c.getPackageName())) continue;
            int s = stateOf(c);
            if (isPlaying(s)) {
                best = c;
                break;
            }
            if (s == PlaybackState.STATE_PAUSED && best == null) best = c;
        }
        if (best == null && controller == null) {
            // Nothing remembered (the launcher was restarted): an app's media notification still names its session.
            for (MediaSession.Token token : NowPlayingListener.mediaSessions()) {
                MediaController c = new MediaController(activity.getApplicationContext(), token);
                if (text(c.getMetadata(), MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE) != null) {
                    follow(c);
                    break;
                }
            }
        }
        if (best != null) {
            inactive = false;
            follow(best);
        } else if (controller != null && !listed(list, controller)) {
            inactive = true;
        } else {
            inactive = false;
            follow(null);
        }
        render();
    }

    private static boolean listed(List<MediaController> list, MediaController c) {
        for (MediaController other : list) {
            if (other.getSessionToken().equals(c.getSessionToken())) return true;
        }
        return false;
    }

    /**
     * The followed session's state, counted as paused when it is inactive or when it says playing while the TV has
     * played no music for a moment: after HDMI, Spotify reports playing (with fresh times) while it is silent.
     */
    private int effectiveState() {
        int s = stateOf(controller);
        if (!showable(s)) return s;
        if (inactive || isPlaying(s) && silent()) return PlaybackState.STATE_PAUSED;
        return s;
    }

    /** The session says playing but no music has come out of the TV for 1.5 seconds (a song just starting is not yet). */
    private boolean silent() {
        AudioManager audio = activity.getSystemService(AudioManager.class);
        if (audio == null || audio.isMusicActive()) {
            silentSince = 0;
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (silentSince == 0) silentSince = now;
        return now - silentSince > 1500;
    }

    /** Commands go as media keys to the session when it would ignore them: inactive, or "playing" while silent. */
    private boolean needsKeys() {
        return inactive || isPlaying(stateOf(controller)) && !isPlaying(effectiveState());
    }

    private void follow(MediaController next) {
        if (controller != null && next != null && controller.getSessionToken().equals(next.getSessionToken())) return;
        if (controller != null) controller.unregisterCallback(callback);
        boolean otherApp = controller == null || next == null || !controller.getPackageName().equals(next.getPackageName());
        controller = next == null ? null : new MediaController(activity.getApplicationContext(), next.getSessionToken());
        remembered = controller;
        if (controller != null) controller.registerCallback(callback, handler);
        // A different app's card must not start with the last app's artwork.
        if (otherApp) {
            art.setImageDrawable(null);
            hasCover = false;
        }
    }

    /** The progress tick: redraws the whole card when playing or silence flipped the state, else only the progress. */
    private void tick() {
        if (effectiveState() != renderedState) render();
        else showProgress();
    }

    private void render() {
        int s = effectiveState();
        renderedState = s;
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        CharSequence name = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        ready = showable(s) && name != null;
        if (ready) {
            String pkg = controller.getPackageName();
            CharSequence app = label(pkg);
            title.setText(name);
            CharSequence by = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            CharSequence album = text(m, MediaMetadata.METADATA_KEY_ALBUM);
            CharSequence line = by != null && album != null ? activity.getString(R.string.now_playing_by, by, album)
                    : by != null ? by : album;
            artist.setText(line);
            artist.setVisibility(line == null ? View.GONE : View.VISIBLE);
            // Spotify re-sends its metadata many times a second while a song plays, with the 640px cover present in
            // some updates and missing in others, and its 160px DISPLAY_ICON (the Spotify logo) in all of them. So
            // only the real cover counts, and the last one stays up through the gaps and across a song change
            // until the next cover arrives; DISPLAY_ICON, then the app's icon, only fill a card that has none yet.
            Bitmap cover = bitmap(m, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART);
            if (cover != null) {
                art.setImageBitmap(cover);
                hasCover = true;
            } else if (!hasCover && art.getDrawable() == null) {
                Bitmap display = bitmap(m, MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (display != null) art.setImageBitmap(display);
                else art.setImageDrawable(appIcon(pkg));
            }
            if (icon.getDrawable() == null || !pkg.equals(icon.getTag())) {
                icon.setImageDrawable(appIcon(pkg));
                icon.setTag(pkg);
            }
            kicker.setText(activity.getString(R.string.now_playing_kicker, app));
            open.setText(activity.getString(R.string.now_playing_open, app));
            toggle.setImageResource(isPlaying(s) ? R.drawable.ic_np_pause : R.drawable.ic_np_play);
        }
        updateVisibility();
        showProgress();
    }

    /**
     * How far into the song and the time left, from the session's last reported position moved on by the time
     * since; while playing, comes back just after the next whole second. Gone without a duration.
     */
    private void showProgress() {
        handler.removeCallbacks(progressTick);
        if (card.getVisibility() != View.VISIBLE || controller == null) return;
        MediaMetadata m = controller.getMetadata();
        PlaybackState p = controller.getPlaybackState();
        long duration = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration <= 0 || p == null || p.getPosition() < 0) {
            progressRow.setVisibility(View.GONE);
            return;
        }
        boolean playing = isPlaying(effectiveState());
        long now = SystemClock.elapsedRealtime();
        long reported = p.getPosition();
        if (playing && p.getLastPositionUpdateTime() > 0) {
            reported += (long) ((now - p.getLastPositionUpdateTime()) * p.getPlaybackSpeed());
        }
        // Spotify sends many updates a second whose positions differ by a little: run our own clock from one anchor,
        // and move the anchor only on a real change (pause, another song, a seek, or a drift past 2 seconds).
        CharSequence track = title.getText();
        long estimate = anchorPosition + (anchorPlaying ? now - anchorTime : 0);
        boolean sameTrack = anchorTime != 0 && TextUtils.equals(track, anchorTrack);
        if (inactive && sameTrack) {
            // Pushed out by Live TV, the app still reports where it was before the switch: hold the clock where it
            // was instead (stopped where Home left it, or now if the song went quiet while Home was showing).
            if (anchorPlaying) {
                anchorPosition = estimate;
                anchorTime = now;
                anchorPlaying = false;
            }
        } else if (!sameTrack || playing != anchorPlaying || Math.abs(reported - estimate) > 2000) {
            anchorPosition = reported;
            anchorTime = now;
            anchorPlaying = playing;
            anchorTrack = track;
            estimate = reported;
        }
        long position = Math.max(0, Math.min(estimate, duration));
        progress.setProgress((int) (position * progress.getMax() / duration));
        String shown = time(position);
        String remaining = activity.getString(R.string.now_playing_left, time(duration - position));
        if (!shown.contentEquals(elapsed.getText())) elapsed.setText(shown);
        if (!remaining.contentEquals(left.getText())) left.setText(remaining);
        progressRow.setVisibility(View.VISIBLE);
        if (playing) handler.postDelayed(progressTick, 200);   // the bar glides; the times change once a second
        // Says playing but silent: look again each second, to switch back as soon as the music comes out.
        else if (!inactive && isPlaying(stateOf(controller))) handler.postDelayed(progressTick, 1000);
    }

    /** 3:07, or 1:02:07 from an hour up. */
    private static String time(long ms) {
        long total = ms / 1000, h = total / 3600, min = total / 60 % 60, sec = total % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, min, sec)
                : String.format(java.util.Locale.ROOT, "%d:%02d", min, sec);
    }

    private void updateVisibility() {
        boolean focusInCard = card.hasFocus();
        boolean show = started && ready && controller != null && (shelf || focusInCard);
        if (show == (card.getVisibility() == View.VISIBLE)) return;
        if (show) {
            card.setAlpha(0f);
            card.setVisibility(View.VISIBLE);
            card.animate().alpha(1f).setDuration(160).start();
            place();
            showProgress();
        } else {
            card.animate().cancel();
            handler.removeCallbacks(progressTick);
            card.setVisibility(View.GONE);
            // The session ended while a button had focus: give focus back to the tile.
            if (focusInCard && onLeave != null) onLeave.run();
        }
        if (onShown != null) onShown.accept(show);
    }

    private android.graphics.drawable.Drawable appIcon(String pkg) {
        try {
            return activity.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static boolean inside(View view, View ancestor) {
        for (ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return view == ancestor;
    }

    private static boolean isPlaying(int state) {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
    }

    private static boolean showable(int state) {
        return isPlaying(state) || state == PlaybackState.STATE_PAUSED;
    }

    private static int stateOf(MediaController c) {
        PlaybackState p = c == null ? null : c.getPlaybackState();
        return p == null ? PlaybackState.STATE_NONE : p.getState();
    }

    private static CharSequence text(MediaMetadata m, String... keys) {
        if (m == null) return null;
        for (String key : keys) {
            CharSequence value = m.getText(key);
            if (!TextUtils.isEmpty(value)) return value;
        }
        return null;
    }

    private static Bitmap bitmap(MediaMetadata m, String... keys) {
        if (m == null) return null;
        for (String key : keys) {
            Bitmap value = m.getBitmap(key);
            if (value != null) return value;
        }
        return null;
    }

    private CharSequence label(String pkg) {
        PackageManager pm = activity.getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info);
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
