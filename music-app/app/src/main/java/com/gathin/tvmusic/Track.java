package com.gathin.tvmusic;

import java.io.File;

/** One local audio file: where it is, what its tags say, and how long it runs. */
final class Track {
    final File file;
    /** Tag title, or the file name without its extension. */
    final String title;
    /** Tag artist, or null when the file carries none. */
    final String artist;
    final long durationMs;

    Track(File file, String title, String artist, long durationMs) {
        this.file = file;
        this.title = title;
        this.artist = artist;
        this.durationMs = durationMs;
    }

    String path() {
        return file.getAbsolutePath();
    }
}
