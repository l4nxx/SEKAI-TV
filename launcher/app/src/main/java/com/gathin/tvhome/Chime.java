package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/**
 * The two menu sounds, synthesised into res/raw: a quiet tick on every press and a two-note chime
 * when a reward is claimed. Loaded once; the pool is kept for the process' life.
 */
final class Chime {
    private static SoundPool pool;
    private static int clickId;
    private static int chimeId;

    private Chime() {}

    private static void load(Context context) {
        if (pool != null) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build();
        clickId = pool.load(context, R.raw.click, 1);
        chimeId = pool.load(context, R.raw.chime, 1);
    }

    static void click(Context context) {
        play(context, false);
    }

    static void confirm(Context context) {
        play(context, true);
    }

    private static void play(Context context, boolean reward) {
        try {
            load(context);
            int id = reward ? chimeId : clickId;
            if (id != 0) pool.play(id, reward ? 0.5f : 0.3f, reward ? 0.5f : 0.3f, 1, 0, 1f);
        } catch (RuntimeException ignored) {
            // No audio on this build of Android: the screen works without it.
        }
    }
}
