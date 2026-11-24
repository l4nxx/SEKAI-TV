package com.gathin.tvhome;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * The profile page: the owner's level and title, the month's event, the daily login bonus, three
 * daily missions, the card collection, the favourite character and the titles. Every row is a
 * focusable view, so the whole page works with the remote; rewards are claimed with OK.
 */
public final class ProfileActivity extends Activity {
    private static final int[] BONUS_IDS = {
            R.id.bonus1, R.id.bonus2, R.id.bonus3, R.id.bonus4, R.id.bonus5, R.id.bonus6, R.id.bonus7};
    private static final int[] MISSION_IDS = {R.id.missionApps, R.id.missionMusic, R.id.missionProfile};
    private static final int[] CARD_IDS = {
            R.id.card0, R.id.card1, R.id.card2, R.id.card3,
            R.id.card4, R.id.card5, R.id.card6, R.id.card7};
    private static final int[] CHARACTER_IDS = {R.id.char0, R.id.char1, R.id.char2, R.id.char3, R.id.char4};
    private static final int[] TITLE_IDS = {R.id.title0, R.id.title1, R.id.title2, R.id.title3, R.id.title4};

    private Player player;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_profile);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        player = Player.load(this);
        player.rollover(this);

        findViewById(R.id.btnProfileClose).setOnClickListener(v -> {
            Chime.click(this);
            finish();
        });
        findViewById(R.id.bonusRow).setOnClickListener(v -> {
            int before = player.level();
            int gained = player.claimBonus(this);
            if (gained == 0) {
                Chime.click(this);
                Toast.makeText(this, R.string.profile_bonus_done, Toast.LENGTH_SHORT).show();
                return;
            }
            Chime.confirm(this);
            refresh();
            announceLevel(before, player.level());
        });
        for (int i = 0; i < MISSION_IDS.length; i++) {
            final int index = i;
            findViewById(MISSION_IDS[i]).setOnClickListener(v -> {
                int before = player.level();
                int gained = player.claimMission(this, index);
                if (gained == 0) {
                    Chime.click(this);
                    return;
                }
                Chime.confirm(this);
                refresh();
                announceLevel(before, player.level());
            });
        }
        for (int i = 0; i < CARD_IDS.length; i++) {
            final int index = i;
            findViewById(CARD_IDS[i]).setOnClickListener(v -> {
                Chime.click(this);
                Toast.makeText(this, player.cardUnlocked(index)
                                ? getString(R.string.profile_card_toast, Player.CARDS[index], index + 1)
                                : getString(R.string.profile_locked_toast, Player.CARD_LEVELS[index]),
                        Toast.LENGTH_SHORT).show();
            });
        }
        for (int i = 0; i < CHARACTER_IDS.length; i++) {
            final int index = i;
            findViewById(CHARACTER_IDS[i]).setOnClickListener(v -> {
                Chime.confirm(this);
                player.favourite = index;
                player.save(this);
                refresh();
            });
        }
        for (int i = 0; i < TITLE_IDS.length; i++) {
            final int index = i;
            findViewById(TITLE_IDS[i]).setOnClickListener(v -> {
                if (!player.titleUnlocked(index)) {
                    Chime.click(this);
                    Toast.makeText(this, getString(R.string.profile_locked_toast, Player.TITLE_LEVELS[index]),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                Chime.confirm(this);
                player.title = index;
                player.save(this);
                refresh();
            });
        }

        if (Config.load(this).reducedMotion) findViewById(R.id.sparkView).setVisibility(View.GONE);

        bindEvent();
        refresh();
        showLoading();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void announceLevel(int before, int after) {
        if (after > before) {
            Toast.makeText(this, getString(R.string.profile_level_up, after), Toast.LENGTH_LONG).show();
        }
    }

    /** The page is behind the themed loading screen for a moment, then both cross-fade. */
    private void showLoading() {
        final View loading = findViewById(R.id.profileLoading);
        final View content = findViewById(R.id.profileContent);
        content.setAlpha(0f);
        loading.postDelayed(() -> {
            loading.animate().alpha(0f).setDuration(280)
                    .withEndAction(() -> loading.setVisibility(View.GONE)).start();
            content.animate().alpha(1f).setDuration(320).start();
            findViewById(R.id.bonusRow).requestFocus();
        }, 750);
    }

    /** The month's event: a local, offline calendar entry, new during its first week. */
    private void bindEvent() {
        Calendar now = Calendar.getInstance();
        String month = new SimpleDateFormat("MMMM", Locale.getDefault()).format(now.getTime());
        int last = now.getActualMaximum(Calendar.DAY_OF_MONTH);
        ((TextView) findViewById(R.id.eventName)).setText(getString(R.string.profile_event_name, month));
        ((TextView) findViewById(R.id.eventWindow)).setText(getString(R.string.profile_event_window, last, month));
        findViewById(R.id.eventNew).setVisibility(
                now.get(Calendar.DAY_OF_MONTH) <= 7 ? View.VISIBLE : View.GONE);
    }

    private String missionText(int index) {
        String label;
        if (index == 0) label = getString(R.string.profile_mission_apps, Player.MISSION_APPS);
        else if (index == 1) label = getString(R.string.profile_mission_music);
        else label = getString(R.string.profile_mission_profile);
        if (player.missionClaimed(index)) {
            return label + "  ·  " + getString(R.string.profile_mission_claimed);
        }
        if (player.missionDone(index)) {
            return label + "  ·  " + getString(R.string.profile_mission_ready, Player.MISSION_XP[index]);
        }
        int have;
        int goal;
        if (index == 0) {
            have = player.apps;
            goal = Player.MISSION_APPS;
        } else if (index == 1) {
            have = player.music;
            goal = 1;
        } else {
            have = player.visits;
            goal = 1;
        }
        return label + "  ·  " + getString(R.string.profile_mission_progress, Math.min(have, goal), goal);
    }

    private void refresh() {
        int level = player.level();
        TextView avatar = findViewById(R.id.profileAvatar);
        avatar.setText(Player.CHARACTERS[player.favourite].substring(0, 1));
        avatar.setBackgroundTintList(ColorStateList.valueOf(Player.CHARACTER_COLORS[player.favourite]));
        ((TextView) findViewById(R.id.profileName)).setText(Player.CHARACTERS[player.favourite]);
        ((TextView) findViewById(R.id.profileTitle)).setText(Player.TITLES[player.title]);
        ((TextView) findViewById(R.id.profileLevel)).setText(getString(R.string.profile_level, level));
        ((ProgressBar) findViewById(R.id.profileXp)).setProgress(player.progress());
        ((TextView) findViewById(R.id.profileXpText)).setText(
                getString(R.string.profile_xp, player.progress(), Player.XP_PER_LEVEL));

        for (int i = 0; i < BONUS_IDS.length; i++) {
            findViewById(BONUS_IDS[i]).setSelected(i < player.streak);
        }
        TextView bonus = findViewById(R.id.bonusRow);
        bonus.setText(player.bonusAvailable()
                ? getString(R.string.profile_bonus_claim, Player.BONUS_XP)
                : getString(R.string.profile_bonus_done) + "  ·  "
                        + getString(R.string.profile_bonus_streak, Math.max(1, player.streak)));

        for (int i = 0; i < MISSION_IDS.length; i++) {
            ((TextView) findViewById(MISSION_IDS[i])).setText(missionText(i));
        }

        for (int i = 0; i < CARD_IDS.length; i++) {
            ViewGroup body = (ViewGroup) ((ViewGroup) findViewById(CARD_IDS[i])).getChildAt(0);
            View art = body.getChildAt(0);
            TextView name = (TextView) body.getChildAt(1);
            boolean unlocked = player.cardUnlocked(i);
            art.setBackgroundColor(Player.CARD_COLORS[i]);
            art.setAlpha(unlocked ? 1f : 0.35f);
            name.setText(unlocked ? Player.CARDS[i]
                    : getString(R.string.profile_locked, Player.CARD_LEVELS[i]));
            name.setAlpha(unlocked ? 1f : 0.55f);
        }

        for (int i = 0; i < CHARACTER_IDS.length; i++) {
            TextView chip = findViewById(CHARACTER_IDS[i]);
            chip.setText(Player.CHARACTERS[i]);
            chip.setSelected(i == player.favourite);
        }
        for (int i = 0; i < TITLE_IDS.length; i++) {
            TextView chip = findViewById(TITLE_IDS[i]);
            boolean unlocked = player.titleUnlocked(i);
            chip.setText(unlocked ? Player.TITLES[i]
                    : getString(R.string.profile_locked, Player.TITLE_LEVELS[i]));
            chip.setSelected(i == player.title);
            chip.setAlpha(unlocked ? 1f : 0.5f);
        }
    }
}
