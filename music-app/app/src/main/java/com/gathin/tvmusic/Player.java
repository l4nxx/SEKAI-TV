package com.gathin.tvmusic;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plays the library from a local file. It takes the audio focus so the TV's other players yield,
 * keeps a playback order that honours shuffle and repeat, remembers the track and the position
 * across restarts, and reports back every half second. Nothing here reads the network.
 */
final class Player {
    interface Listener {
        void onTrackChanged(Track track);

        void onPlayingChanged(boolean playing);

        void onProgress(int positionMs, int durationMs);
    }

    enum Repeat {ALL, ONE, OFF}

    static final String PREFS = "music_player";
    private static final String LAST_PATH = "last_path";
    private static final String LAST_POSITION = "last_position";
    private static final String SHUFFLE = "shuffle";
    private static final String REPEAT = "repeat";
    private static final long TICK_MS = 500;

    private final Context context;
    private final AudioManager audio;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Playback order: the library, shuffled when shuffle is on. */
    private final List<Track> order = new ArrayList<>();

    private MediaPlayer player;
    private Track current;
    private int index = -1;
    private boolean prepared;
    /** The listener asked for sound (it is playing, or waiting for focus or a prepared file). */
    private boolean playing;
    private boolean hasFocus;
    private boolean released;
    private boolean shuffle;
    private Repeat repeat = Repeat.ALL;
    /** A position to jump to once the file is prepared (the one saved at the last exit). */
    private int pendingSeekMs;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            reportProgress();
            if (playing && prepared) handler.postDelayed(this, TICK_MS);
        }
    };

    /** Built in the constructor: it reports through {@code listener}, which must be set by then. */
    private final AudioManager.OnAudioFocusChangeListener focus;

    Player(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.audio = this.context.getSystemService(AudioManager.class);
        this.listener = listener;
        this.focus = change -> {
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                hasFocus = true;
                if (playing) start();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
                // Another player took the sound for good (it may be an app with nothing to play, but
                // the TV cannot tell): stop, and say so, so the play button shows a triangle again.
                hasFocus = false;
                playing = false;
                pauseOnly();
                listener.onPlayingChanged(false);
            } else {
                // A short interruption only pauses; the gain that follows resumes.
                hasFocus = false;
                pauseOnly();
            }
        };
        SharedPreferences prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        shuffle = prefs.getBoolean(SHUFFLE, false);
        repeat = parseRepeat(prefs.getString(REPEAT, Repeat.ALL.name()));
    }

    // --- queue -------------------------------------------------------------------------------

    /**
     * Loads the library. {@code keepPath} stays first (the track that is playing, or the one saved
     * at the last exit), the rest follows in library order or shuffled.
     */
    void setQueue(List<Track> tracks, String keepPath) {
        Track keep = null;
        if (keepPath != null) {
            for (Track track : tracks) {
                if (track.path().equals(keepPath)) {
                    keep = track;
                    break;
                }
            }
        }
        order.clear();
        order.addAll(tracks);
        if (keep != null) order.remove(keep);
        if (shuffle) Collections.shuffle(order);
        if (keep != null) order.add(0, keep);
        index = order.isEmpty() ? -1 : 0;
        current = index >= 0 ? order.get(index) : null;
        listener.onTrackChanged(current);
    }

    /** Plays this track of the queue, starting the file from its beginning. */
    void play(Track track) {
        int at = order.indexOf(track);
        if (at >= 0) playAt(at);
    }

    private void playAt(int at) {
        if (at < 0 || at >= order.size()) return;
        index = at;
        current = order.get(at);
        pendingSeekMs = 0;
        playing = true;
        listener.onTrackChanged(current);
        open(current);
    }

    void next() {
        skipForward();
    }

    void previous() {
        if (positionMs() > 3000) {
            seekTo(0);
            return;
        }
        if (order.isEmpty()) return;
        int at = index - 1;
        if (at < 0) at = repeat == Repeat.ALL ? order.size() - 1 : 0;
        playAt(at);
    }

    private void skipForward() {
        if (order.isEmpty()) return;
        int at = index + 1;
        if (at >= order.size()) {
            if (repeat == Repeat.ALL) {
                if (shuffle) Collections.shuffle(order);
                at = 0;
            } else {
                // The last track of a queue that does not loop: stop at its end, silently.
                playing = false;
                pauseOnly();
                seekTo(0);
                listener.onPlayingChanged(false);
                return;
            }
        }
        playAt(at);
    }

    // --- transport ---------------------------------------------------------------------------

    void toggle() {
        if (playing) pause();
        else play();
    }

    /** Starts (or resumes) the current track. */
    void play() {
        if (released) return;
        if (current == null) {
            if (order.isEmpty()) return;
            playAt(index >= 0 ? index : 0);
            return;
        }
        playing = true;
        if (player == null) {
            open(current);
            return;
        }
        if (prepared && ensureFocus()) {
            start();
            listener.onPlayingChanged(true);
        }
    }

    void pause() {
        if (released) return;
        playing = false;
        pauseOnly();
        save();
        listener.onPlayingChanged(false);
    }

    void seekTo(int ms) {
        int target = Math.max(0, ms);
        if (player != null && prepared) {
            try {
                player.seekTo(target);
            } catch (IllegalStateException ignored) {
            }
            reportProgress();
        } else {
            pendingSeekMs = target;
        }
    }

    void seekBy(int delta) {
        seekTo(positionMs() + delta);
    }

    /** The position to jump to when the saved track is opened (the last exit's). */
    void setStartPosition(int ms) {
        pendingSeekMs = Math.max(0, ms);
    }

    int positionMs() {
        if (player != null && prepared) {
            try {
                return player.getCurrentPosition();
            } catch (IllegalStateException ignored) {
            }
        }
        return pendingSeekMs;
    }

    int durationMs() {
        if (player != null && prepared) {
            try {
                return player.getDuration();
            } catch (IllegalStateException ignored) {
            }
        }
        return current == null ? 0 : (int) current.durationMs;
    }

    Track current() {
        return current;
    }

    boolean isPlaying() {
        return playing;
    }

    // --- modes -------------------------------------------------------------------------------

    boolean shuffle() {
        return shuffle;
    }

    void setShuffle(boolean value) {
        if (shuffle == value) return;
        shuffle = value;
        if (current == null) {
            if (shuffle) Collections.shuffle(order);
        } else {
            // The track on screen keeps playing; only what follows it is reordered.
            List<Track> rest = new ArrayList<>(order);
            rest.remove(current);
            order.clear();
            order.add(current);
            if (shuffle) Collections.shuffle(rest);
            order.addAll(rest);
            index = 0;
        }
        save();
    }

    Repeat repeat() {
        return repeat;
    }

    void setRepeat(Repeat value) {
        repeat = value;
        if (player != null) {
            try {
                player.setLooping(repeat == Repeat.ONE);
            } catch (IllegalStateException ignored) {
            }
        }
        save();
    }

    /** What the last session was playing, for the next start. */
    String lastPath() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_PATH, null);
    }

    int lastPosition() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(LAST_POSITION, 0);
    }

    void save() {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (current != null) editor.putString(LAST_PATH, current.path());
        editor.putInt(LAST_POSITION, positionMs());
        editor.putBoolean(SHUFFLE, shuffle);
        editor.putString(REPEAT, repeat.name());
        editor.apply();
    }

    void release() {
        if (released) return;
        playing = false;
        pauseOnly();
        released = true;
        releasePlayer();
        if (audio != null && hasFocus) audio.abandonAudioFocus(focus);
        hasFocus = false;
    }

    // --- internals ---------------------------------------------------------------------------

    private void open(Track track) {
        releasePlayer();
        MediaPlayer created = new MediaPlayer();
        try {
            created.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            created.setDataSource(track.path());
            created.setLooping(repeat == Repeat.ONE);
            created.setOnPreparedListener(mp -> {
                prepared = true;
                if (pendingSeekMs > 0) {
                    try {
                        mp.seekTo(pendingSeekMs);
                    } catch (IllegalStateException ignored) {
                    }
                    pendingSeekMs = 0;
                }
                if (playing && ensureFocus()) {
                    start();
                    listener.onPlayingChanged(true);
                }
            });
            created.setOnCompletionListener(mp -> skipForward());
            created.setOnErrorListener((mp, what, extra) -> {
                // A file the TV cannot decode: leave a still player rather than a spinning one.
                playing = false;
                prepared = false;
                listener.onPlayingChanged(false);
                return true;
            });
            player = created;
            created.prepareAsync();
        } catch (Exception error) {
            created.release();
            player = null;
            prepared = false;
            playing = false;
            listener.onPlayingChanged(false);
        }
    }

    private boolean ensureFocus() {
        if (hasFocus) return true;
        hasFocus = audio != null && audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasFocus;
    }

    private void start() {
        if (player == null || !prepared) return;
        try {
            if (!player.isPlaying()) player.start();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        } catch (IllegalStateException ignored) {
        }
    }

    private void pauseOnly() {
        handler.removeCallbacks(ticker);
        if (player == null || !prepared) return;
        try {
            if (player.isPlaying()) player.pause();
        } catch (IllegalStateException ignored) {
        }
    }

    private void releasePlayer() {
        handler.removeCallbacks(ticker);
        prepared = false;
        if (player == null) return;
        try {
            player.reset();
        } catch (IllegalStateException ignored) {
        }
        player.release();
        player = null;
    }

    private void reportProgress() {
        listener.onProgress(positionMs(), durationMs());
    }

    private static Repeat parseRepeat(String value) {
        if (value == null) return Repeat.ALL;
        try {
            return Repeat.valueOf(value);
        } catch (IllegalArgumentException error) {
            return Repeat.ALL;
        }
    }
}
