package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * "Make it yours": Launcher settings as one list of rows, each "Label .... ‹ Value ›". Left/Right (or OK) on a
 * row changes its value, writes it to config.json and Home rebuilds behind the panel a moment later, so the
 * change shows while the panel stays open. Every value lives in config.json (none in the on-TV preferences),
 * because a config write resets those preferences (Config.syncThemeChoices).
 */
final class CustomizePanel {
    private static final int PANEL = 0xF40E1320, ROW = 0x1FFFFFFF, ROW_FOCUS = 0xF2F1F4F8;
    private static final int TEXT = 0xFFF1F4F8, MUTED = 0xFF98A2B3, DARK = 0xFF0E1320, ACCENT = 0xFF34D399;
    private static final long APPLY_DELAY_MS = 350;

    private interface Write {
        JSONObject keys(int index);
    }

    private final Activity activity;
    private final SharedPreferences choices;
    private final Runnable changed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rebuild;
    private final float density;
    private JSONObject cfg;
    private View firstRow;
    private LinearLayout column;

    /** Tag on every view this panel adds, so a rebuild drops the old ones and can find a row by its label. */
    private static final class Own {
        final String label;

        Own(String label) {
            this.label = label;
        }
    }

    private CustomizePanel(Activity activity, SharedPreferences choices, Runnable changed) {
        this.activity = activity;
        this.choices = choices;
        this.changed = changed;
        this.rebuild = changed;
        density = activity.getResources().getDisplayMetrics().density;
    }

    /** Rebuilds the settings column; returns the row to focus first. */
    static View apply(Activity a, SharedPreferences choices, Runnable changed) {
        CustomizePanel p = new CustomizePanel(a, choices, changed);
        p.build();
        return p.firstRow;
    }

    /** After a change: rebuild the rows (some appear or go with the new choice) and keep focus on the same one. */
    private void refresh() {
        View f = activity.getCurrentFocus();
        String want = f != null && f.getTag() instanceof Own ? ((Own) f.getTag()).label : null;
        build();
        for (int i = 0; want != null && i < column.getChildCount(); i++) {
            View c = column.getChildAt(i);
            if (c.isFocusable() && c.getTag() instanceof Own && want.equals(((Own) c.getTag()).label)) {
                c.requestFocus();
                return;
            }
        }
        if (firstRow != null) firstRow.requestFocus();
    }

    private void build() {
        firstRow = null;
        cfg = Config.overlay(activity);
        if (cfg == null) cfg = new JSONObject();
        moveOnTvChoicesIntoConfig();

        View reorder = activity.findViewById(R.id.reorderChoice);
        LinearLayout column = (LinearLayout) reorder.getParent();
        this.column = column;
        View scroll = (View) column.getParent();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadii(new float[]{dp(22), dp(22), 0, 0, 0, 0, dp(22), dp(22)});
        scroll.setBackground(bg);
        column.setPadding(dp(26), dp(26), dp(26), dp(36));

        View weather = activity.findViewById(R.id.weatherChoice);
        View tools = activity.findViewById(R.id.toolsChoice);
        View tv = activity.findViewById(R.id.tvSettingsChoice);
        List<View> keep = new ArrayList<>();
        for (int i = 0; i < column.getChildCount(); i++) {
            View c = column.getChildAt(i);
            if (!(c.getTag() instanceof Own)) keep.add(c);   // rows of an earlier build are dropped
        }
        column.removeAllViews();

        TextView kicker = text("APPEARANCE", 12, MUTED);
        kicker.setLetterSpacing(0.16f);
        column.addView(kicker);
        TextView title = text("Make it yours", 30, TEXT);
        title.setPadding(0, dp(2), 0, dp(14));
        column.addView(title);

        // Design: the theme presets, merged so the rows below keep their values
        List<File> themes = ConfigWriter.themes(activity);
        String[] designs = new String[themes.size() + 1];
        designs[0] = "Custom";
        int design = 0;
        String current = choices.getString(PreviewPickerActivity.THEME_KEY, "");
        for (int i = 0; i < themes.size(); i++) {
            designs[i + 1] = ConfigWriter.themeTitle(themes.get(i).getName());
            if (themes.get(i).getName().equals(current)) design = i + 1;
        }
        column.addView(row("Design", designs, design, i -> {
            if (i == 0) {
                // Back to Custom: the owner's own mix exactly as it was before the first design was tried
                choices.edit().remove(PreviewPickerActivity.THEME_KEY).commit();
                ConfigWriter.restoreCustom(activity);
                return null;
            }
            // Leaving Custom: keep that mix, so trying designs can never lose it
            if (choices.getString(PreviewPickerActivity.THEME_KEY, "").isEmpty()) ConfigWriter.saveCustom(activity);
            File f = themes.get(i - 1);
            choices.edit().putString(PreviewPickerActivity.THEME_KEY, f.getName()).commit();
            ConfigWriter.applyThemeKeys(activity, f);
            return null;
        }));

        int bgType = cfg.optBoolean("video_wallpaper", false) ? 0 : cfg.optBoolean("wallpaper", true) ? 1
                : cfg.optInt("sky_intensity", 0) > 0 ? 2 : 3;
        column.addView(row("Background", new String[]{"Live video", "Photos", "Night sky", "Black"}, bgType, i -> {
            switch (i) {
                case 0: return ConfigWriter.keys("video_wallpaper", true, "wallpaper", true, "sky_intensity", 0,
                        "background_color", "#000000");
                case 1: return ConfigWriter.keys("video_wallpaper", false, "wallpaper", true, "sky_intensity", 0,
                        "background_color", "#000000");
                case 2: return ConfigWriter.keys("video_wallpaper", false, "wallpaper", false, "sky_intensity", 75,
                        "background_color", "#05070F");
                default: return ConfigWriter.keys("video_wallpaper", false, "wallpaper", false, "sky_intensity", 0,
                        "background_color", "#000000");
            }
        }));
        boolean pictures = bgType <= 1;   // video or photos: they rotate and can be dimmed
        int[] minutes = {5, 15, 30, 60, 180};
        if (pictures) column.addView(row("Change background", new String[]{"Every 5 min", "Every 15 min", "Every 30 min",
                        "Every hour", "Every 3 hours"},
                indexOf(minutes, cfg.optInt("video_rotation_minutes", cfg.optInt("wallpaper_rotation_minutes", 60)), 3),
                i -> ConfigWriter.keys("video_rotation_minutes", minutes[i], "wallpaper_rotation_minutes", minutes[i])));
        int[] dims = {0, 20, 35, 50};
        if (pictures) column.addView(row("Background dim", new String[]{"None", "Light", "Medium", "Strong"},
                nearest(dims, cfg.optInt("wallpaper_dim", 30)),
                i -> ConfigWriter.keys("wallpaper_dim", dims[i], "video_dim", dims[i])));
        if (pictures) column.addView(row("Background by time", new String[]{"Same all day", "Morning · evening · night"},
                cfg.optBoolean("time_of_day_backgrounds", false) ? 1 : 0, i -> {
                    if (i == 0) return TimeOfDayBackgrounds.off();
                    // Sorting by brightness reads every file: done in the background, then Home rebuilds
                    TimeOfDayBackgrounds.build(activity, () -> {
                        rebuild.run();
                        refresh();
                    });
                    return null;
                }));

        boolean mo = cfg.optBoolean("mosaic", false), sr = cfg.optBoolean("single_row", false);
        int lay = mo ? ("twin".equals(cfg.optString("mosaic_style", "")) ? 2 : 1)
                : sr ? (cfg.optBoolean("carousel", false) ? 4 : 6)
                : cfg.optBoolean("side_list", false) ? 5
                : cfg.optBoolean("cinematic_home", false) && cfg.optBoolean("dock_row", true) ? 3
                : !cfg.optBoolean("dock_row", true) ? 7 : 0;
        String[] layouts = {"Dock + grid", "Mosaic", "Two big cards", "Cinematic", "Carousel", "Side list",
                "Single row", "Grid"};
        column.addView(row("Layout", layouts, lay, CustomizePanel::layoutKeys));
        boolean mosaic = lay == 1 || lay == 2, dockGrid = lay == 0 || lay == 3, single = lay == 6;
        boolean carouselLay = lay == 4, sideListLay = lay == 5;
        // Layouts that show focus with Home's floating highlight, and those that draw a ring on the card itself
        boolean glassLay = lay == 0 || lay == 3 || lay == 6 || lay == 7;
        boolean ringLay = mosaic || carouselLay || sideListLay;
        if (mosaic) {
            List<String[]> apps = homeApps();
            String hero = cfg.optString("mosaic_hero", "");
            String[] names = new String[apps.size() + 1];
            names[0] = "First app";
            int at = 0;
            for (int i = 0; i < apps.size(); i++) {
                names[i + 1] = apps.get(i)[1];
                if (apps.get(i)[0].equals(hero)) at = i + 1;
            }
            column.addView(row("Big card", names, at,
                    i -> ConfigWriter.keys("mosaic_hero", i == 0 ? "" : apps.get(i - 1)[0])));
        }
        int[] cols = {4, 5, 6, 7};
        if (glassLay) column.addView(row("Apps per row", new String[]{"4", "5", "6", "7"}, indexOf(cols, cfg.optInt("columns", 4), 0),
                i -> ConfigWriter.keys("columns", cols[i])));
        if (dockGrid) column.addView(row("Rows under dock", new String[]{"0", "1", "2", "3"}, clamp(cfg.optInt("rows_below_dock", 0), 3),
                i -> ConfigWriter.keys("rows_below_dock", i)));
        int dock = !cfg.optBoolean("dock_band", true) ? 2 : cfg.optBoolean("dock_glass", false) ? 0 : 1;
        if (dockGrid || single) column.addView(row("Dock", new String[]{"Glass", "Solid", "Floating"}, dock, i ->
                i == 0 ? ConfigWriter.keys("dock_band", true, "dock_glass", true)
                        : i == 1 ? ConfigWriter.keys("dock_band", true, "dock_glass", false)
                        : ConfigWriter.keys("dock_band", false, "dock_glass", false)));
        if (glassLay || carouselLay) column.addView(row("Tiles", new String[]{"App banners", "Icon cards"}, cfg.optBoolean("uniform_tiles", false) ? 1 : 0,
                i -> ConfigWriter.keys("uniform_tiles", i == 1)));
        if (glassLay) column.addView(row("Labels", new String[]{"On focus", "Always"}, cfg.optBoolean("always_show_names", false) ? 1 : 0,
                i -> ConfigWriter.keys("always_show_names", i == 1)));
        String gs = cfg.optString("glass_style", "glass");
        if (glassLay) column.addView(row("Highlight", new String[]{"Glass", "Rim", "Halo"},
                "rim".equals(gs) ? 1 : "halo".equals(gs) ? 2 : 0,
                i -> ConfigWriter.keys("glass_style", i == 1 ? "rim" : i == 2 ? "halo" : "glass")));
        String fx = cfg.optString("focus_effect", "zoom");
        // Pulse and border-only work on the mosaic's own ring; the other layouts offer zoom and shine
        String[] fxKeys = ringLay ? new String[]{"zoom", "shine", "pulse", "border"} : new String[]{"zoom", "shine"};
        String[] fxNames = ringLay ? new String[]{"Gentle zoom", "Zoom + shine", "Zoom + pulse", "Border only"}
                : new String[]{"Gentle zoom", "Zoom + shine"};
        column.addView(row("Focus effect", fxNames,
                Math.max(0, java.util.Arrays.asList(fxKeys).indexOf(fx)),
                i -> ConfigWriter.keys("focus_effect", fxKeys[i])));
        if (ringLay) column.addView(row("Border colour", new String[]{"White", "App colour"},
                "app".equals(cfg.optString("border_color", "white")) ? 1 : 0,
                i -> ConfigWriter.keys("border_color", i == 1 ? "app" : "white")));
        String ent = cfg.optString("entrance", "off");
        column.addView(row("Opening animation", new String[]{"Off", "Fade up", "Pop in"},
                "fade".equals(ent) ? 1 : "pop".equals(ent) ? 2 : 0,
                i -> ConfigWriter.keys("entrance", i == 1 ? "fade" : i == 2 ? "pop" : "off")));
        column.addView(row("App colour glow", new String[]{"On", "Off"}, cfg.optBoolean("ambient_glow", true) ? 0 : 1,
                i -> i == 0 ? ConfigWriter.keys("ambient_glow", true, "app_glow", true)
                        : ConfigWriter.keys("ambient_glow", false, "app_glow", false)));

        String cf = cfg.optString("clock_format", "system");
        column.addView(row("Clock", new String[]{"24-hour", "12-hour", "System"},
                "24".equals(cf) ? 0 : "12".equals(cf) ? 1 : 2,
                i -> ConfigWriter.keys("clock_format", i == 0 ? "24" : i == 1 ? "12" : "system")));
        String cs = cfg.optString("clock_style", "thin");
        String[] csKeys = {"thin", "bold", "large", "seconds"};
        column.addView(row("Clock style", new String[]{"Thin", "Bold", "Large", "With seconds"},
                Math.max(0, java.util.Arrays.asList(csKeys).indexOf(cs)),
                i -> ConfigWriter.keys("clock_style", csKeys[i])));
        column.addView(row("Date", new String[]{"Beside the time", "Below the time"},
                cfg.optBoolean("date_below_clock", false) ? 1 : 0,
                i -> ConfigWriter.keys("date_below_clock", i == 1)));
        column.addView(row("Greeting", new String[]{"Show", "Hide"}, cfg.optBoolean("show_greeting", true) ? 0 : 1,
                i -> ConfigWriter.keys("show_greeting", i == 0)));
        column.addView(row("Weather icon", new String[]{"Animated", "Off"}, cfg.optBoolean("weather_icon", true) ? 0 : 1,
                i -> ConfigWriter.keys("weather_icon", i == 0)));
        column.addView(row("System stats", new String[]{"Show", "Hide"}, cfg.optBoolean("show_stats", false) ? 0 : 1,
                i -> ConfigWriter.keys("show_stats", i == 0)));
        boolean anim = cfg.optBoolean("animations", true);
        if (ringLay) {
            // The mosaic has no bounce or lift to tune: motion is on or off
            column.addView(row("Motion", new String[]{"On", "Off"}, anim ? 0 : 1,
                    i -> ConfigWriter.keys("animations", i == 0)));
        } else {
            column.addView(row("Motion", new String[]{"Lively", "Calm", "Off"},
                    !anim ? 2 : cfg.optBoolean("focus_overshoot", true) ? 0 : 1,
                    i -> i == 2 ? ConfigWriter.keys("animations", false)
                            : ConfigWriter.keys("animations", true, "focus_overshoot", i == 0, "advanced_effects", i == 0)));
        }
        screensaverRow(column);
        if (android.provider.Settings.System.canWrite(activity)) {
            // How long the TV waits before the screensaver comes on (the system's own timeout)
            int[] mins = {5, 10, 15, 30, 60};
            int now = android.provider.Settings.System.getInt(activity.getContentResolver(),
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 600000) / 60000;
            column.addView(row("Screensaver after", new String[]{"5 min", "10 min", "15 min", "30 min", "1 hour"},
                    nearest(mins, now), i -> {
                        try {
                            android.provider.Settings.System.putInt(activity.getContentResolver(),
                                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT, mins[i] * 60000);
                        } catch (Exception ignored) {
                        }
                        return null;
                    }));
        }

        // Tools: each helper app on its own row, opened from here (they are kept off Home and the App library)
        List<Tools.Tool> found = Tools.installed(activity);
        if (!found.isEmpty()) {
            TextView toolsTitle = text("TOOLS", 12, MUTED);
            toolsTitle.setLetterSpacing(0.16f);
            toolsTitle.setPadding(0, dp(22), 0, dp(4));
            column.addView(toolsTitle);
            for (Tools.Tool t : found) column.addView(toolRow(t));
        }

        TextView more = text("MORE", 12, MUTED);
        more.setLetterSpacing(0.16f);
        more.setPadding(0, dp(22), 0, dp(4));
        column.addView(more);
        for (View action : new View[]{weather, reorder, tv}) {
            if (action == null) continue;
            styleAction((TextView) action);
            column.addView(action);
            keep.remove(action);
        }
        TextView hint = text("← → change   ·   Back closes", 13, MUTED);
        hint.setPadding(0, dp(18), 0, 0);
        column.addView(hint);
        for (View v : keep) {              // still bound by the activity, never shown
            v.setVisibility(View.GONE);
            column.addView(v);
        }
    }

    private static final String AERIAL_DREAM = "com.neilturner.aerialviews/.ui.screensaver.DreamActivity";

    /**
     * Screensaver: the TV's own screensaver setting, so the choice really is what comes on. Aerial Views (when
     * installed) or this launcher's clock screen with live video, photos or just the clock. Needs
     * WRITE_SECURE_SETTINGS, which the install grants; without it the row says so instead.
     */
    private void screensaverRow(LinearLayout column) {
        android.content.ContentResolver cr = activity.getContentResolver();
        // Full class name: the app id (com.anouarzerdeb.tvhome) differs from the code's package
        String ours = new android.content.ComponentName(activity, ClockDream.class).flattenToString();
        boolean aerial;
        try {
            activity.getPackageManager().getPackageInfo("com.neilturner.aerialviews", 0);
            aerial = true;
        } catch (Exception e) {
            aerial = false;
        }
        boolean canSet = activity.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        String now = android.provider.Settings.Secure.getString(cr, "screensaver_components");
        boolean isOurs = now != null && now.startsWith(activity.getPackageName() + "/");
        int clock = cfg.optBoolean("screensaver_video", false) ? 0 : cfg.optBoolean("clock_wallpaper", false) ? 1 : 2;
        List<String> names = new ArrayList<>();
        if (aerial) names.add("Aerial Views");
        java.util.Collections.addAll(names, "Live video", "Photos", "Clock only");
        int first = aerial ? 1 : 0;
        int at = aerial && !isOurs ? 0 : first + clock;
        final boolean hasAerial = aerial;
        column.addView(row("Screensaver", names.toArray(new String[0]), at, i -> {
            if (!canSet) return null;
            try {
                android.provider.Settings.Secure.putString(cr, "screensaver_components",
                        hasAerial && i == 0 ? AERIAL_DREAM : ours);
                android.provider.Settings.Secure.putInt(cr, "screensaver_enabled", 1);
            } catch (SecurityException ignored) {
            }
            int c = i - first;
            if (c < 0) return null;
            return c == 0 ? ConfigWriter.keys("screensaver_video", true, "clock_wallpaper", true)
                    : c == 1 ? ConfigWriter.keys("screensaver_video", false, "clock_wallpaper", true)
                    : ConfigWriter.keys("screensaver_video", false, "clock_wallpaper", false);
        }));
    }

    /** Every layout sets all the keys that tell layouts apart, so switching never leaves one half on. */
    private static JSONObject layoutKeys(int i) {
        boolean mosaic = i == 1 || i == 2, single = i == 4 || i == 6;
        JSONObject k = ConfigWriter.keys("mosaic", mosaic, "mosaic_style", i == 2 ? "twin" : "classic",
                "single_row", single, "carousel", i == 4, "side_list", i == 5,
                "cinematic_home", i == 3, "dock_row", i != 5 && i != 7);
        try {
            if (mosaic || i == 5) {   // icon cards with names read best in these
                k.put("uniform_tiles", true);
                k.put("always_show_names", true);
            }
            if (i == 4) k.put("dock_band", false);   // carousel: the cards float, no band around the row
            if (i == 3) {             // cinematic: the art shows through a lighter scrim, no band behind the dock
                k.put("cinematic_scrim", 60);
                k.put("dock_band", false);
            }
        } catch (org.json.JSONException ignored) {
        }
        return k;
    }

    /** The apps on Home, as {package, label} sorted by label: TV apps and library-added ones, minus hidden ones. */
    private List<String[]> homeApps() {
        android.content.pm.PackageManager pm = activity.getPackageManager();
        java.util.Set<String> skip = new java.util.HashSet<>();
        skip.add(activity.getPackageName());
        for (String key : new String[]{"hidden_apps", "top_bar_apps"}) {
            org.json.JSONArray a = cfg.optJSONArray(key);
            for (int i = 0; a != null && i < a.length(); i++) skip.add(a.optString(i));
        }
        java.util.Set<String> added = LibraryApps.load(activity);
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String[]> out = new ArrayList<>();
        String[] categories = {android.content.Intent.CATEGORY_LEANBACK_LAUNCHER, android.content.Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(
                    new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                boolean tv = category.equals(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER);
                if (skip.contains(pkg) || (!tv && !added.contains(pkg)) || !seen.add(pkg)) continue;
                out.add(new String[]{pkg, String.valueOf(ri.loadLabel(pm))});
            }
        }
        java.util.Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    /** Older on-TV choices (columns, rows, dock opacity, clock) go into config.json, then out of the prefs. */
    private void moveOnTvChoicesIntoConfig() {
        JSONObject k = new JSONObject();
        try {
            if (choices.contains("columns")) k.put("columns", choices.getInt("columns", 4));
            if (choices.contains(Config.ROWS_BELOW_DOCK_KEY)) k.put("rows_below_dock", choices.getInt(Config.ROWS_BELOW_DOCK_KEY, 0));
            if (choices.contains(Config.CLOCK_FORMAT_KEY)) k.put("clock_format", choices.getString(Config.CLOCK_FORMAT_KEY, "system"));
        } catch (Exception ignored) {
        }
        if (k.length() == 0) return;
        ConfigWriter.patch(activity, k);
        choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove(Config.CLOCK_FORMAT_KEY).commit();
        cfg = Config.overlay(activity);
        if (cfg == null) cfg = new JSONObject();
    }

    // ---- one row: label left, value right, ‹ › while focused --------------------------------------------------

    private View row(String label, String[] values, int start, Write write) {
        final int[] index = {Math.max(0, Math.min(start, values.length - 1))};
        LinearLayout row = new LinearLayout(activity);
        row.setTag(new Own(label));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(18), 0, dp(12), 0);
        row.setBackground(rowBackground());
        TextView name = text(label, 17, TEXT);
        name.setSingleLine(true);
        TextView value = text("", 16, MUTED);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable paint = () -> {
            boolean f = row.hasFocus() || row.isFocused();
            name.setTextColor(f ? DARK : TEXT);
            value.setTextColor(f ? DARK : MUTED);
            value.setText(f ? "‹  " + values[index[0]] + "  ›" : values[index[0]]);
        };
        paint.run();
        row.setOnFocusChangeListener((v, has) -> paint.run());
        row.setOnKeyListener((v, code, e) -> {
            boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT;
            boolean right = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER;
            if (!left && !right) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            index[0] = (index[0] + (left ? values.length - 1 : 1)) % values.length;
            paint.run();
            JSONObject keys = write.keys(index[0]);
            if (keys != null) ConfigWriter.patch(activity, keys);
            if (keys != null && !"Design".equals(label)) {
                choices.edit().remove(PreviewPickerActivity.THEME_KEY).apply();   // now a custom mix
            }
            if (keys == null && !"Design".equals(label)) return true;   // a TV setting only: Home has nothing to redo
            handler.post(this::refresh);   // show/hide dependent rows
            handler.removeCallbacks(rebuild);
            handler.postDelayed(rebuild, APPLY_DELAY_MS);   // a quick run of presses rebuilds Home once
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        if (firstRow == null) firstRow = row;
        return row;
    }

    /** A tool's row: its name, what it is for, and OK (or Right) opens it. */
    private View toolRow(Tools.Tool tool) {
        LinearLayout row = new LinearLayout(activity);
        row.setTag(new Own("tool:" + tool.pkg));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(18), 0, dp(12), 0);
        row.setBackground(rowBackground());
        TextView name = text(tool.name, 17, TEXT);
        name.setSingleLine(true);
        TextView hint = text(tool.hint, 16, MUTED);
        hint.setSingleLine(true);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable paint = () -> {
            boolean f = row.isFocused();
            name.setTextColor(f ? DARK : TEXT);
            hint.setTextColor(f ? DARK : MUTED);
            hint.setText(f ? "Open  ›" : tool.hint);
        };
        paint.run();
        row.setOnFocusChangeListener((v, has) -> paint.run());
        Runnable open = () -> {
            try {
                activity.startActivity(tool.open);
            } catch (Exception e) {
                android.widget.Toast.makeText(activity, R.string.not_available, android.widget.Toast.LENGTH_SHORT).show();
            }
        };
        row.setOnClickListener(v -> open.run());
        row.setOnKeyListener((v, code, e) -> {
            if (code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) open.run();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void styleAction(TextView t) {
        t.setBackground(rowBackground());
        t.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{DARK, TEXT}));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        t.setVisibility(View.VISIBLE);
    }

    private Drawable rowBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(ROW_FOCUS));
        s.addState(new int[]{}, shape(ROW));
        return s;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(color);
        return g;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(activity);
        t.setTag(new Own(s));
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static int indexOf(int[] a, int v, int fallback) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return fallback;
    }

    private static int nearest(int[] a, int v) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (Math.abs(a[i] - v) < Math.abs(a[best] - v)) best = i;
        return best;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max));
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    @SuppressWarnings("unused")
    private static int accent() {
        return ACCENT;
    }
}
