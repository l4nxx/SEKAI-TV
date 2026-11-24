package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Locale;

/**
 * The owner's game-like profile: experience and level, the daily login bonus, three daily missions,
 * the decorative card collection, the favourite character and the chosen title.
 *
 * Everything is local: no network, no account, no analytics. The state lives in the launcher's own
 * preferences, so it survives an app update and a theme push.
 */
final class Player {
    static final String PREFS = "player";

    /** The five muses of the theme, drawn as pastel initials rather than artwork. */
    static final String[] CHARACTERS = {"AKARI", "MIZU", "KAEDE", "RIN", "SORA"};
    static final int[] CHARACTER_COLORS = {
            0xFF00CCBB, 0xFF7F6FF2, 0xFFF49BC1, 0xFFFF8A5B, 0xFF4AA3DF};

    /** Titles, and the level each one needs. */
    static final String[] TITLES = {"DEBUTANT", "MELOMANE", "STAR CHASER", "NIGHT OWL", "VIRTUOSO"};
    static final int[] TITLE_LEVELS = {1, 3, 5, 8, 12};

    /** Decorative cards, the level that unlocks each, and the colour of its art band. */
    static final String[] CARDS = {"AURORA", "ECHO", "HORIZON", "NEON", "RAIN", "SERENADE", "STORM", "ZENITH"};
    static final int[] CARD_LEVELS = {1, 2, 3, 4, 6, 8, 10, 14};
    static final int[] CARD_COLORS = {
            0xFFBDE8F5, 0xFFD9C9FF, 0xFFC9D6FF, 0xFFFFC0D9,
            0xFFBFE3D0, 0xFFF6D7A7, 0xFFC2C7F2, 0xFF9FD8FF};

    static final int XP_PER_LEVEL = 100;
    static final int BONUS_XP = 30;
    static final int MISSION_APPS = 3;
    static final int[] MISSION_XP = {60, 50, 30};

    int xp;
    int dayKey;
    int favourite;
    int title;
    int streak;
    int bonusDay;
    int apps;
    int music;
    int visits;
    boolean appsClaimed;
    boolean musicClaimed;
    boolean visitClaimed;

    private Player() {}

    static Player load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Player player = new Player();
        player.xp = p.getInt("xp", 0);
        player.dayKey = p.getInt("day", 0);
        player.favourite = p.getInt("favourite", 0);
        player.title = p.getInt("title", 0);
        player.streak = p.getInt("streak", 0);
        player.bonusDay = p.getInt("bonus_day", 0);
        player.apps = p.getInt("apps", 0);
        player.music = p.getInt("music", 0);
        player.visits = p.getInt("visits", 0);
        player.appsClaimed = p.getBoolean("apps_claimed", false);
        player.musicClaimed = p.getBoolean("music_claimed", false);
        player.visitClaimed = p.getBoolean("visit_claimed", false);
        player.rollover(context);
        return player;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("xp", xp)
                .putInt("day", dayKey)
                .putInt("favourite", favourite)
                .putInt("title", title)
                .putInt("streak", streak)
                .putInt("bonus_day", bonusDay)
                .putInt("apps", apps)
                .putInt("music", music)
                .putInt("visits", visits)
                .putBoolean("apps_claimed", appsClaimed)
                .putBoolean("music_claimed", musicClaimed)
                .putBoolean("visit_claimed", visitClaimed)
                .apply();
    }

    /** A new day: the missions start over, the login bonus and the level stay. */
    void rollover(Context context) {
        int today = today();
        if (dayKey == today) return;
        dayKey = today;
        apps = 0;
        music = 0;
        visits = 0;
        appsClaimed = false;
        musicClaimed = false;
        visitClaimed = false;
        save(context);
    }

    int level() {
        return xp / XP_PER_LEVEL + 1;
    }

    /** Experience inside the current level, 0 to XP_PER_LEVEL - 1. */
    int progress() {
        return xp % XP_PER_LEVEL;
    }

    boolean bonusAvailable() {
        return bonusDay != today();
    }

    /** Claims today's bonus; returns the experience gained, or 0 when it was already claimed. */
    int claimBonus(Context context) {
        int today = today();
        if (bonusDay == today) return 0;
        streak = bonusDay == yesterday() ? (streak >= 7 ? 1 : streak + 1) : 1;
        bonusDay = today;
        xp += BONUS_XP;
        save(context);
        return BONUS_XP;
    }

    boolean missionDone(int index) {
        if (index == 0) return apps >= MISSION_APPS;
        if (index == 1) return music >= 1;
        return visits >= 1;
    }

    boolean missionClaimed(int index) {
        if (index == 0) return appsClaimed;
        if (index == 1) return musicClaimed;
        return visitClaimed;
    }

    /** Claims a finished mission; returns the experience gained, or 0. */
    int claimMission(Context context, int index) {
        if (!missionDone(index) || missionClaimed(index)) return 0;
        if (index == 0) appsClaimed = true;
        else if (index == 1) musicClaimed = true;
        else visitClaimed = true;
        xp += MISSION_XP[index];
        save(context);
        return MISSION_XP[index];
    }

    boolean anyToClaim() {
        for (int i = 0; i < 3; i++) {
            if (!missionClaimed(i) && missionDone(i)) return true;
        }
        return bonusAvailable();
    }

    boolean cardUnlocked(int index) {
        return level() >= CARD_LEVELS[index];
    }

    boolean titleUnlocked(int index) {
        return level() >= TITLE_LEVELS[index];
    }

    /** An app opened from Home: the "open apps" mission, and "Open Music" for the music app. */
    static void appOpened(Context context, String pkg) {
        if (pkg == null || pkg.equals(context.getPackageName())) return;
        Player player = load(context);
        player.apps++;
        if ("com.gathin.tvmusic".equals(pkg)) player.music = 1;
        player.save(context);
    }

    static void profileOpened(Context context) {
        Player player = load(context);
        player.visits = 1;
        player.save(context);
    }

    private static int today() {
        Calendar now = Calendar.getInstance(Locale.US);
        return now.get(Calendar.YEAR) * 10000 + (now.get(Calendar.MONTH) + 1) * 100
                + now.get(Calendar.DAY_OF_MONTH);
    }

    private static int yesterday() {
        Calendar now = Calendar.getInstance(Locale.US);
        now.add(Calendar.DAY_OF_MONTH, -1);
        return now.get(Calendar.YEAR) * 10000 + (now.get(Calendar.MONTH) + 1) * 100
                + now.get(Calendar.DAY_OF_MONTH);
    }
}
