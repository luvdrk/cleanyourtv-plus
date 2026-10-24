package com.gathin.tvhome;

import android.app.Notification;
import android.media.session.MediaSession;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lets {@link NowPlaying} read other apps' media sessions: Android answers MediaSessionManager.getActiveSessions
 * for an app whose notification listener is enabled. It also hands over the session of an app's media
 * notification, which Spotify keeps up after Live TV pushes its session out of the active list. Nothing else of the
 * notifications is read. Enabled over adb with
 * {@code cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.NowPlayingListener}.
 */
public final class NowPlayingListener extends NotificationListenerService {
    private static volatile NowPlayingListener connected;

    @Override
    public void onListenerConnected() {
        connected = this;
    }

    @Override
    public void onListenerDisconnected() {
        connected = null;
    }

    /** The media sessions of the media notifications on screen; empty while the listener is not connected. */
    static List<MediaSession.Token> mediaSessions() {
        NowPlayingListener listener = connected;
        if (listener == null) return Collections.emptyList();
        StatusBarNotification[] all;
        try {
            all = listener.getActiveNotifications();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        if (all == null) return Collections.emptyList();
        List<MediaSession.Token> tokens = new ArrayList<>();
        for (StatusBarNotification sbn : all) {
            Parcelable token = sbn.getNotification().extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
            if (token instanceof MediaSession.Token) tokens.add((MediaSession.Token) token);
        }
        return tokens;
    }
}
