package com.gathin.tvmusic;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole app on one screen: the teal header from Home, the EN LECTURE card on the left, the
 * library on the right. Everything is drawn from local files; there is no network code at all, and
 * the app holds no permission, so it works with the Wi-Fi off.
 */
public final class MusicActivity extends Activity implements Player.Listener {
    private final List<Track> tracks = new ArrayList<>();
    private Player player;
    private TrackAdapter adapter;
    private RecyclerView list;
    private LinearLayoutManager layout;
    private TextView subtitle;
    private TextView libraryCount;
    private TextView nowTitle;
    private TextView nowArtist;
    private TextView nowPosition;
    private TextView nowDuration;
    private TextView shuffleChip;
    private TextView repeatChip;
    private LinearLayout emptyState;
    private ImageView playPause;
    private SeekBar progress;
    private boolean wasPlaying;

    /** MODE NUIT: the black sheet is up and the window brightness is at its floor. */
    private boolean night;
    private View nightOverlay;
    private TextView nightHint;
    private final Handler nightHandler = new Handler(Looper.getMainLooper());
    /** After a few seconds the hint goes and the panel goes fully dark. */
    private final Runnable darken = () -> {
        if (!night) return;
        setBrightness(0f);
        nightHint.setAlpha(0f);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music);
        bindWallpaper();

        subtitle = findViewById(R.id.headerSubtitle);
        subtitle.setAllCaps(true);
        libraryCount = findViewById(R.id.libraryCount);
        nowTitle = findViewById(R.id.nowTitle);
        nowArtist = findViewById(R.id.nowArtist);
        nowPosition = findViewById(R.id.nowPosition);
        nowDuration = findViewById(R.id.nowDuration);
        shuffleChip = findViewById(R.id.shuffle);
        repeatChip = findViewById(R.id.repeat);
        emptyState = findViewById(R.id.emptyState);
        playPause = findViewById(R.id.playPause);
        progress = findViewById(R.id.progress);
        nightOverlay = findViewById(R.id.nightOverlay);
        nightHint = findViewById(R.id.nightHint);
        nightOverlay.setOnClickListener(view -> exitNight());
        ((TextView) findViewById(R.id.emptyBody))
                .setText(getString(R.string.empty_body, Library.displayPath(this)));

        list = findViewById(R.id.trackList);
        layout = new LinearLayoutManager(this);
        list.setLayoutManager(layout);
        adapter = new TrackAdapter(tracks, player_pick());
        list.setAdapter(adapter);

        player = new Player(this, this);

        findViewById(R.id.refresh).setOnClickListener(view -> rescan());
        findViewById(R.id.quit).setOnClickListener(view -> finish());
        findViewById(R.id.previous).setOnClickListener(view -> player.previous());
        findViewById(R.id.next).setOnClickListener(view -> player.next());
        playPause.setOnClickListener(view -> player.toggle());
        findViewById(R.id.backTen).setOnClickListener(view -> player.seekBy(-10000));
        findViewById(R.id.forwardTen).setOnClickListener(view -> player.seekBy(10000));
        findViewById(R.id.night).setOnClickListener(view -> enterNight());
        shuffleChip.setOnClickListener(view -> {
            player.setShuffle(!player.shuffle());
            showModes();
        });
        repeatChip.setOnClickListener(view -> {
            player.setRepeat(nextRepeat(player.repeat()));
            showModes();
        });

        showModes();
        rescan();
        // A track and a position left at the last exit: come back on that spot, paused.
        Track current = player.current();
        if (current != null && current.path().equals(player.lastPath())) {
            player.setStartPosition(player.lastPosition());
            onProgress(player.positionMs(), player.durationMs());
        }
        // « Musique » launched with --ez night true opens straight onto the black screen.
        if (getIntent() != null && getIntent().getBooleanExtra("night", false)) enterNight();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("night", false)) enterNight();
    }

    // --- MODE NUIT ---------------------------------------------------------------------------

    /**
     * A black screen with the brightness at its floor, so the panel does not light the room while a
     * track plays. The music keeps going, and the screen is kept awake so it cannot sleep and cut
     * the sound; any key lifts it.
     */
    private void enterNight() {
        if (night || isFinishing()) return;
        night = true;
        nightOverlay.setVisibility(View.VISIBLE);
        nightOverlay.bringToFront();
        // The sheet takes the focus, so the next press is not swallowed by the button that was
        // focused underneath.
        nightOverlay.requestFocus();
        nightHint.setAlpha(1f);
        setBrightness(0.25f);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        nightHandler.removeCallbacks(darken);
        nightHandler.postDelayed(darken, 5000);
    }

    private void exitNight() {
        if (!night) return;
        night = false;
        nightHandler.removeCallbacks(darken);
        nightOverlay.setVisibility(View.GONE);
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getCurrentFocus() == null) playPause.requestFocus();
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = value;
        getWindow().setAttributes(params);
    }

    private TrackAdapter.Pick player_pick() {
        return track -> player.play(track);
    }

    /** The launcher's wallpaper, copied here by the install script; the gradient shows without it. */
    private void bindWallpaper() {
        File file = new File(getExternalFilesDir(null), "wallpaper.jpg");
        if (!file.isFile() || file.length() == 0) return;
        try {
            BitmapDrawable art = new BitmapDrawable(getResources(), BitmapFactory.decodeFile(file.getAbsolutePath()));
            if (art.getBitmap() == null) return;
            ImageView view = findViewById(R.id.wallpaper);
            view.setImageDrawable(art);
            view.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {
            // A wallpaper the TV cannot decode is not worth failing over.
        }
    }

    /** Reads the library folder again and keeps whatever was playing in place. */
    private void rescan() {
        String keep = player.current() != null ? player.current().path() : player.lastPath();
        tracks.clear();
        tracks.addAll(Library.scan(this));
        player.setQueue(tracks, keep);
        adapter.setPlaying(player.current() == null ? null : player.current().path());
        adapter.notifyDataSetChanged();
        updateCount();
        boolean empty = tracks.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        // Something must hold the focus, or the remote's arrows do nothing: the play button first,
        // then the first row once the list has been laid out.
        playPause.requestFocus();
        if (!empty) {
            list.post(() -> {
                View first = layout.findViewByPosition(0);
                if (first != null) first.requestFocus();
            });
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && getCurrentFocus() == null) playPause.requestFocus();
    }

    private void updateCount() {
        String count = tracks.isEmpty() ? getString(R.string.header_tracks_zero)
                : getString(tracks.size() == 1 ? R.string.header_track : R.string.header_tracks, tracks.size());
        subtitle.setText(getString(R.string.header_subtitle, count));
        libraryCount.setText(count);
    }

    private void showModes() {
        shuffleChip.setActivated(player.shuffle());
        Player.Repeat repeat = player.repeat();
        repeatChip.setText(repeat == Player.Repeat.ALL ? R.string.repeat_all
                : repeat == Player.Repeat.ONE ? R.string.repeat_one : R.string.repeat_off);
        repeatChip.setActivated(repeat != Player.Repeat.OFF);
    }

    private static Player.Repeat nextRepeat(Player.Repeat repeat) {
        if (repeat == Player.Repeat.ALL) return Player.Repeat.ONE;
        return repeat == Player.Repeat.ONE ? Player.Repeat.OFF : Player.Repeat.ALL;
    }

    // --- lifecycle: no background service, so leaving the app pauses it -----------------------

    @Override
    protected void onPause() {
        super.onPause();
        wasPlaying = player != null && player.isPlaying();
        if (player != null) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null && wasPlaying) player.play();
        // Coming back to the app in the night: the window's brightness is ours to set again.
        if (night) {
            setBrightness(0f);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) player.release();
    }

    // --- the remote --------------------------------------------------------------------------

    /**
     * In the night, the first key press has to lift the black sheet before any button underneath
     * could take it: this runs before the focused view, so nothing else sees that key. The media
     * keys are let through, so playback can still be driven from the remote in the dark.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (night && event.getAction() == KeyEvent.ACTION_DOWN && !isMediaKey(event.getKeyCode())) {
            exitNight();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isMediaKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                player.toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                player.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                player.pause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                player.next();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                player.previous();
                return true;
            default:
                // The media keys keep working in the dark; anything else brings the screen back.
                if (night) {
                    exitNight();
                    return true;
                }
                return super.onKeyDown(keyCode, event);
        }
    }

    // --- Player.Listener ---------------------------------------------------------------------

    @Override
    public void onTrackChanged(Track track) {
        if (track == null) {
            nowTitle.setText(R.string.nothing_playing);
            nowArtist.setText("");
            nowPosition.setText(clock(0));
            nowDuration.setText(clock(0));
            progress.setProgress(0);
            adapter.setPlaying(null);
            return;
        }
        nowTitle.setText(track.title);
        nowArtist.setText(track.artist != null ? track.artist : getString(R.string.unknown_artist));
        nowDuration.setText(clock(track.durationMs));
        if (track.durationMs > 0) progress.setMax((int) track.durationMs);
        adapter.setPlaying(track.path());
        scrollToPlaying(track.path());
    }

    @Override
    public void onPlayingChanged(boolean playing) {
        playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playPause.setContentDescription(getString(playing ? R.string.pause_cd : R.string.play_cd));
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
        if (durationMs > 0) progress.setMax(durationMs);
        progress.setProgress(Math.max(0, positionMs));
        nowPosition.setText(clock(positionMs));
        if (durationMs > 0) nowDuration.setText(clock(durationMs));
    }

    private void scrollToPlaying(String path) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).path().equals(path)) {
                layout.scrollToPositionWithOffset(i, 0);
                return;
            }
        }
    }

    /** 3:07, as on the card. */
    static String clock(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.FRANCE, "%d:%02d", seconds / 60, seconds % 60);
    }
}
