package com.gathin.tvmusic;

import android.content.Context;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The library: every audio file under the app's own external files folder, read from disk with no
 * permission and no network. That folder is
 * {@code /sdcard/Android/data/com.gathin.tvmusic/files/music}, and its sub-folders are walked three
 * levels deep, so a playlist can live in an album folder.
 */
final class Library {
    static final String FOLDER = "music";
    private static final String[] EXTENSIONS = {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav", ".mp4"};
    private static final int MAX_DEPTH = 3;

    /** The folder the music goes in; null when external storage is unavailable. */
    static File dir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        return base == null ? null : new File(base, FOLDER);
    }

    /** The path the empty state and the README show. */
    static String displayPath(Context ctx) {
        File dir = dir(ctx);
        return dir == null ? FOLDER : dir.getAbsolutePath();
    }

    /** Everything playable, sorted by title, in the TV's language. */
    static List<Track> scan(Context ctx) {
        List<Track> tracks = new ArrayList<>();
        File dir = dir(ctx);
        if (dir != null) walk(dir, 1, tracks);
        final Collator collator = Collator.getInstance(Locale.FRANCE);
        try {
            Collections.sort(tracks, (a, b) -> collator.compare(a.title, b.title));
        } catch (RuntimeException ignored) {
            // A broken collator must not leave the library empty; the folder order will do.
        }
        return tracks;
    }

    private static void walk(File dir, int depth, List<Track> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                if (depth < MAX_DEPTH) walk(entry, depth + 1, out);
                continue;
            }
            if (!playable(entry.getName())) continue;
            Track track = read(entry);
            if (track != null) out.add(track);
        }
    }

    private static boolean playable(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    /** Tags through the platform reader; the file name stands in for a missing title. */
    private static Track read(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String fallback = dot > 0 ? name.substring(0, dot) : name;
        MediaMetadataRetriever tags = new MediaMetadataRetriever();
        String title = null;
        String artist = null;
        long duration = 0;
        try {
            tags.setDataSource(file.getAbsolutePath());
            title = tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String length = tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (length != null) duration = Long.parseLong(length.trim());
        } catch (Exception ignored) {
            // A file the TV cannot read is still listed: MediaPlayer reports the real error when it
            // is played, and the row keeps its place instead of vanishing between scans.
        } finally {
            try {
                tags.release();
            } catch (Exception ignored) {
            }
        }
        title = clean(title);
        if (title == null) title = fallback;
        artist = clean(artist);
        return new Track(file, title, artist, Math.max(0, duration));
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.replace('\n', ' ').replace('\r', ' ').trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
