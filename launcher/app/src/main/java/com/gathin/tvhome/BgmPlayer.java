package com.gathin.tvhome;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * Background music on Home: bgm.mp3 in the theme folder, looping quietly while Home is resumed. The
 * theme and credits windows are dialogs over the same activity, so the music carries on over them;
 * leaving Home pauses it, and coming back starts it again. No file (or an unreadable one) means a
 * silent Home, never a crash.
 *
 * <p>An app that takes the audio focus and then sits there doing nothing (an errored player with no
 * network, say) would otherwise silence Home for good: the loss is permanent, nothing hands the
 * focus back, and Home only asks again the next time it is opened. So one loss is retried a few
 * times while Home stays on screen; a second loss means the other app really wants to be heard and
 * Home stays quiet until it is opened again.
 */
final class BgmPlayer {
    /** In getExternalFilesDir(null), next to config.json and wallpaper.jpg. */
    static final String FILE_NAME = "bgm.mp3";

    /** A focus retry, while Home is still on screen and an app took the focus and let it go. */
    private static final long FOCUS_RETRY_MS = 4000;
    private static final int FOCUS_RETRIES = 3;

    private final Activity activity;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Focus losses since Home was last opened; the second one keeps Home quiet. */
    private int losses;
    private int retries;
    /** Relative to the TV's own volume; the theme window's 20/40/60 %. */
    private float volume = 0.4f;
    private MediaPlayer player;
    private boolean prepared;
    private boolean resumed;
    private boolean hasFocus;
    private boolean released;

    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            hasFocus = false;
            pauseOnly();
            // The first loss of a Home visit is usually a player taking the focus and then failing
            // to play anything; ask again shortly. The second one is an app that means it.
            if (resumed && ++losses == 1) retryFocusAfterDelay();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            hasFocus = true;
            if (resumed) startOnly();
        }
    };

    private final Runnable retryFocus = () -> {
        if (released || !resumed || hasFocus) return;
        if (retries++ >= FOCUS_RETRIES) return;
        if (grabFocus()) {
            startOnly();
        } else {
            retryFocusAfterDelay();
        }
    };

    BgmPlayer(Activity activity) {
        this.activity = activity;
        this.audio = activity.getSystemService(AudioManager.class);
    }

    /** Home is on screen (a dialog over it included). */
    void start() {
        if (released) return;
        resumed = true;
        losses = 0;
        retries = 0;
        if (player == null) open();
        if (player != null) play();
    }

    /** Home went behind another app, or the screen saver took over. */
    void pause() {
        resumed = false;
        pauseOnly();
        handler.removeCallbacks(retryFocus);
        if (audio != null && hasFocus) {
            audio.abandonAudioFocus(focus);
            hasFocus = false;
        }
    }

    /** The theme window's music row, applied at once without restarting Home. */
    void setVolume(float level) {
        volume = Math.max(0f, Math.min(1f, level));
        if (player != null && prepared) {
            try {
                player.setVolume(volume, volume);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    void release() {
        if (released) return;
        pause();
        released = true;
        handler.removeCallbacks(retryFocus);
        if (player != null) {
            player.release();
            player = null;
        }
        prepared = false;
    }

    /** Opens the theme folder's track; done once, and retried on the next resume while it is missing. */
    private void open() {
        File file = new File(Config.dir(activity), FILE_NAME);
        if (!file.isFile() || file.length() == 0) return;
        MediaPlayer created = new MediaPlayer();
        try {
            created.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            created.setDataSource(file.getAbsolutePath());
            created.setLooping(true);
            created.setVolume(volume, volume);
            created.setOnPreparedListener(mp -> {
                prepared = true;
                if (resumed) play();
            });
            created.setOnErrorListener((mp, what, extra) -> {
                pauseOnly();
                return true;
            });
            player = created;
            created.prepareAsync();
        } catch (Exception e) {
            created.release();
            player = null;
        }
    }

    private void play() {
        if (!resumed || !prepared || player == null) return;
        if (!hasFocus) {
            if (!grabFocus()) {
                if (retries++ < FOCUS_RETRIES) retryFocusAfterDelay();
                return;
            }
        }
        startOnly();
    }

    /** Takes the audio focus, remembering whether it was ours. */
    private boolean grabFocus() {
        hasFocus = audio != null && audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasFocus;
    }

    /** One more try, while Home is still on screen. */
    private void retryFocusAfterDelay() {
        handler.removeCallbacks(retryFocus);
        if (resumed && !released) handler.postDelayed(retryFocus, FOCUS_RETRY_MS);
    }

    private void startOnly() {
        if (player == null || !prepared) return;
        try {
            if (!player.isPlaying()) player.start();
        } catch (IllegalStateException ignored) {
            // An error callback has already silenced it; Home just carries on without music.
        }
    }

    private void pauseOnly() {
        if (player == null || !prepared) return;
        try {
            if (player.isPlaying()) player.pause();
        } catch (IllegalStateException ignored) {
        }
    }
}
