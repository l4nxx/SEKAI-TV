package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The small window behind the header's Theme tab: the language the launcher shows itself in. Every
 * language the launcher ships a translation for is listed by its own name, the TV's language first;
 * a pick is saved in the launcher's preferences and overrides the theme's locale until the next one.
 */
final class ThemeSettingsDialog extends Dialog {
    /** Languages with a values-* translation in the APK, plus "" for the TV's own. */
    private static final String[] TAGS = {
            "", "en", "fr", "ar", "sr-Latn", "bn", "ca", "cs", "da", "de", "es", "fi", "in",
            "it", "iw", "ko", "nb", "nl", "pl", "pt-BR", "pt-PT", "ru", "sv", "th", "tr",
            "uk", "uz", "vi", "zh-CN", "zh-TW"
    };

    private static final int COLUMNS = 3;
    /** Music levels offered under the language grid, in percent of the TV volume; 0 is Off. */
    private static final int[] MUSIC_LEVELS = {0, 20, 40, 60};

    private final Activity activity;
    private final SharedPreferences choices;
    /** The tag in force now: the on-TV pick, or the theme's when none was made. */
    private final String current;
    private final int density;
    private int musicLevel;
    private final TextView[] musicChoices = new TextView[MUSIC_LEVELS.length];

    ThemeSettingsDialog(Activity activity) {
        super(activity);
        this.activity = activity;
        choices = activity.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE);
        String saved = choices.getString(LauncherLocale.CHOICE_KEY, null);
        String theme = Config.load(activity).locale;
        current = saved != null ? saved : (LauncherLocale.accepts(theme) ? theme.trim() : "");
        density = Math.round(activity.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_theme_settings);
        Window window = getWindow();
        if (window != null) {
            // A full-screen, transparent window around the card: the wallpaper stays visible behind
            // the dim, and the dialog owns the D-pad and Back without resizing to a tiny window.
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.62f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        setCanceledOnTouchOutside(true);

        TextView currentName = findViewById(R.id.themeCurrent);
        currentName.setText(current.isEmpty()
                ? activity.getString(R.string.theme_language_system) + " · " + capitalize(tvLocale().getDisplayName(tvLocale()))
                : label(current));

        LinearLayout host = findViewById(R.id.themeList);
        fill(host);

        TextView closeButton = findViewById(R.id.themeClose);
        closeButton.setOnClickListener(v -> dismiss());
        findViewById(R.id.btnCredits).setOnClickListener(v -> new CreditsDialog(activity).show());
        setUpMusic();

        setOnShowListener(d -> {
            View selected = host.findViewWithTag(current);
            (selected != null ? selected : closeButton).requestFocus();
        });
    }

    /** The music row: Arrêt, 20 %, 40 %, 60 %, the saved one rimmed in teal. */
    private void setUpMusic() {
        musicLevel = choices.getInt("bgm_volume", 40);
        int[] ids = {R.id.musicOff, R.id.music20, R.id.music40, R.id.music60};
        for (int i = 0; i < ids.length; i++) {
            final int level = MUSIC_LEVELS[i];
            TextView chip = findViewById(ids[i]);
            musicChoices[i] = chip;
            chip.setActivated(musicLevel == level);
            chip.setOnClickListener(v -> chooseMusic(level));
        }
    }

    private void chooseMusic(int level) {
        if (level == musicLevel) return;
        musicLevel = level;
        choices.edit().putInt("bgm_volume", level).apply();
        for (int i = 0; i < MUSIC_LEVELS.length; i++) {
            musicChoices[i].setActivated(MUSIC_LEVELS[i] == musicLevel);
        }
        // No recreate: the player is behind this window, so the new level is heard immediately.
        if (activity instanceof MainActivity) ((MainActivity) activity).applyBgmLevel();
    }

    /** Builds the three-column grid; the last row is padded so its chips keep the columns' width. */
    private void fill(LinearLayout host) {
        List<String> tags = new ArrayList<>();
        Collections.addAll(tags, TAGS);
        Collator collator = Collator.getInstance(Locale.getDefault());
        // The TV's language stays first, everything else by the name shown, in the reader's order.
        tags.sort((a, b) -> a.isEmpty() ? -1 : b.isEmpty() ? 1 : collator.compare(label(a), label(b)));

        LinearLayout row = null;
        int inRow = 0;
        for (String tag : tags) {
            if (row == null || inRow == COLUMNS) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (host.getChildCount() > 0) rlp.topMargin = dp(6);
                row.setLayoutParams(rlp);
                host.addView(row);
                inRow = 0;
            }
            row.addView(chip(tag), chipParams());
            inRow++;
        }
        if (row != null) {
            while (inRow < COLUMNS) {
                View spacer = new View(activity);
                spacer.setLayoutParams(chipParams());
                row.addView(spacer);
                inRow++;
            }
        }
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMarginEnd(dp(6));
        return lp;
    }

    private TextView chip(String tag) {
        boolean selected = tag.equals(current);
        TextView chip = new TextView(activity);
        chip.setTag(tag);
        chip.setText((selected ? "✓  " : "") + label(tag));
        chip.setTextSize(14);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(8), 0);
        chip.setBackgroundResource(R.drawable.theme_lang_bg);
        chip.setTextColor(activity.getColorStateList(R.drawable.theme_lang_text));
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setSelected(selected);
        chip.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        chip.setOnClickListener(v -> choose(tag));
        return chip;
    }

    /** The name a language goes by in itself; the TV's row says so in the launcher's language. */
    private String label(String tag) {
        if (tag.isEmpty()) return activity.getString(R.string.theme_language_system);
        Locale locale = Locale.forLanguageTag(tag);
        String name = locale.getDisplayName(locale);
        return capitalize(name.isEmpty() ? tag : name);
    }

    /** The TV's own language, not the one this window is drawn in. */
    private static Locale tvLocale() {
        android.content.res.Configuration system = android.content.res.Resources.getSystem().getConfiguration();
        return system.getLocales().get(0);
    }

    private static String capitalize(String text) {
        return text == null || text.isEmpty() ? text
                : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private void choose(String tag) {
        if (tag.equals(current)) {
            dismiss();
            return;
        }
        choices.edit().putString(LauncherLocale.CHOICE_KEY, tag).apply();
        dismiss();
        // attachBaseContext runs again, so the new language needs a fresh activity.
        activity.recreate();
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
