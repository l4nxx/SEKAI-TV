package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every app that can be launched on this TV, as a grid of white cards: the chevron under Home's dock
 * opens it. Both entry points are listed (LEANBACK_LAUNCHER and LAUNCHER), so apps without a TV entry
 * still show up, unlike on Home. Back, or the top-right button, returns to Home.
 */
public class AllAppsActivity extends Activity {
    private final List<Entry> apps = new ArrayList<>();
    private RecyclerView grid;
    private View empty;
    private View root;
    private View content;
    private Adapter adapter;
    /** The page's flat navy, the colour the dock bar turns into as it grows (the wallpaper's shade). */
    private static final int SHEET_END_COLOR = 0xFF0A0F22;

    private static final class Entry {
        final String label;
        final ComponentName component;
        final ResolveInfo info;
        /** The app declares LEANBACK_LAUNCHER: open it with that category so a running app resumes. */
        final boolean tv;

        Entry(String label, ComponentName component, ResolveInfo info, boolean tv) {
            this.label = label;
            this.component = component;
            this.info = info;
            this.tv = tv;
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_apps);
        root = findViewById(R.id.allAppsRoot);
        content = findViewById(R.id.allAppsContent);
        grid = findViewById(R.id.allAppsGrid);
        empty = findViewById(R.id.allAppsEmpty);
        morphFromDock();
        // The same column count as Home's grid, so the list matches the dock's rhythm.
        int columns = Math.max(3, Math.min(7, Config.load(this).columns));
        grid.setLayoutManager(new GridLayoutManager(this, columns));
        grid.setItemAnimator(null);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        findViewById(R.id.btnAllAppsClose).setOnClickListener(v -> finish());
        load();
    }

    /**
     * The opening: the page's window already grows from the dock's size (res/anim/all_apps_in.xml).
     * Here the sheet it paints starts as the dock's white rounded bar and turns into the navy page,
     * while the title and the grid fade in only once the bar has most of its height.
     */
    private void morphFromDock() {
        final float density = getResources().getDisplayMetrics().density;
        final float radius = 26 * density;
        final GradientDrawable sheet = new GradientDrawable();
        sheet.setColor(Color.WHITE);
        sheet.setCornerRadius(radius);
        root.setBackground(sheet);
        content.setAlpha(0f);
        final ArgbEvaluator colors = new ArgbEvaluator();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(340).setInterpolator(new DecelerateInterpolator(1.4f));
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            sheet.setColor((int) colors.evaluate(t, Color.WHITE, SHEET_END_COLOR));
            sheet.setCornerRadius(Math.max(0f, (1f - t) * radius));
        });
        animator.start();
        content.animate().alpha(1f).setStartDelay(190).setDuration(150).start();
    }

    /** Closing slides the page back down to the dock (res/anim/all_apps_out.xml). */
    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.all_apps_out);
    }

    /** Reads the catalogue off the UI thread: opening another app's resources is not free on this TV. */
    private void load() {
        final PackageManager pm = getPackageManager();
        final String self = getPackageName();
        new Thread(() -> {
            Map<String, Entry> unique = new LinkedHashMap<>();
            Set<String> tv = new HashSet<>();
            // Leanback first: when an app declares both, its TV entry is the one kept.
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo info : pm.queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                    if (info.activityInfo == null || !info.activityInfo.exported) continue;
                    String pkg = info.activityInfo.packageName;
                    if (pkg.equals(self)) continue;
                    if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) tv.add(pkg);
                    if (unique.containsKey(pkg)) continue;
                    String label = Cards.cleanLabel(String.valueOf(info.loadLabel(pm)), pkg);
                    unique.put(pkg, new Entry(label,
                            new ComponentName(pkg, info.activityInfo.name), info, tv.contains(pkg)));
                }
            }
            List<Entry> found = new ArrayList<>(unique.values());
            Collator collator = Collator.getInstance(Locale.getDefault());
            found.sort((a, b) -> collator.compare(a.label, b.label));
            runOnUiThread(() -> show(found));
        }, "all-apps").start();
    }

    private void show(List<Entry> found) {
        if (isFinishing() || isDestroyed()) return;
        apps.clear();
        apps.addAll(found);
        adapter.notifyDataSetChanged();
        empty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
        if (apps.isEmpty()) {
            findViewById(R.id.btnAllAppsClose).requestFocus();
            return;
        }
        // Focus the first card once the grid has laid it out; the views appear a frame after the data.
        grid.post(new Runnable() {
            int frames;

            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(0);
                if (holder != null) holder.itemView.requestFocus();
                else if (++frames < 10) grid.post(this);
            }
        });
    }

    private void open(Entry entry) {
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(entry.component);
        if (entry.tv) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.cant_open, entry.label), Toast.LENGTH_SHORT).show();
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_all_app, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Entry entry = apps.get(position);
            holder.name.setText(entry.label);
            Drawable icon;
            try {
                icon = entry.info.loadIcon(getPackageManager());
            } catch (RuntimeException unavailable) {
                icon = null;
            }
            holder.icon.setImageDrawable(icon != null ? icon : getPackageManager().getDefaultActivityIcon());
            holder.card.setContentDescription(entry.label);
            holder.card.setOnClickListener(v -> open(entry));
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView icon;
        final TextView name;

        Holder(View view) {
            super(view);
            card = view.findViewById(R.id.card);
            icon = view.findViewById(R.id.icon);
            name = view.findViewById(R.id.name);
        }
    }
}
