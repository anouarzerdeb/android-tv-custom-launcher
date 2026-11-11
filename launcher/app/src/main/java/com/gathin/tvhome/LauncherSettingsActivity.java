package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    private TextView themeChoice;
    private TextView layoutChoice, screensaverChoice, weatherChoice;
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // As on Home: no text field here, so keep the keyboard service off the window; Gboard otherwise sends
        // stray DPAD_RIGHT presses that cycle whichever row has focus.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        setContentView(R.layout.activity_launcher_settings);
        // A sidebar down the right edge; Home stays visible (and live) in the rest of the screen.
        getWindow().setLayout(Math.round(440 * getResources().getDisplayMetrics().density),
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        getWindow().setGravity(android.view.Gravity.END);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = findViewById(clockIds[i]);
            clockChoices[i].setOnClickListener(v -> {
                if (value.equals(clockFormat)) return;
                clockFormat = value;
                choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
        }
        themeChoice = findViewById(R.id.themeChoice);
        themeChoice.setOnClickListener(v -> showThemePicker());
        layoutChoice = findViewById(R.id.layoutChoice);
        layoutChoice.setOnClickListener(v -> showLayoutPicker());
        screensaverChoice = findViewById(R.id.screensaverChoice);
        screensaverChoice.setOnClickListener(v -> showScreensaverPicker());
        weatherChoice = findViewById(R.id.weatherChoice);
        weatherChoice.setOnClickListener(v -> showWeatherDialog());
        TextView tools = findViewById(R.id.toolsChoice);
        tools.setText(R.string.tools_state);
        tools.setOnClickListener(v -> showTools());
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY).remove(OpenOnStart.KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            clockFormat = current.clockFormat;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v ->
                open(new Intent(this, HomeSettingsActivity.class)));
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        // Owner redesign: sections, preview cards for theme/layout/screensaver, text-only rows hidden.
        View firstCard = CustomizePanel.apply(this, choices, this::changed);
        if (firstCard != null) firstCard.requestFocus();
        else if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ---- Layout, screensaver, weather and tools (owner additions) ---------------------------------------------

    // Home stays live (video, weather, stats) only while this sidebar is actually on screen. An app or TV setting
    // opened from here stops the sidebar without destroying it, so Home must let go then, not at onDestroy.
    @Override
    protected void onStart() {
        super.onStart();
        MainActivity.sidebarShown();
    }

    @Override
    protected void onStop() {
        MainActivity.sidebarClosed();
        super.onStop();
    }

    private static final String LAYOUT_KEY = "layout_choice", SCREENSAVER_KEY = "screensaver_choice";

    /** Writes {@code keys} into the active config.json (creating it if needed), keeping every other key. */
    private boolean patchConfig(org.json.JSONObject keys) {
        File dir = Config.dir(this);
        if (dir == null) return false;
        try {
            org.json.JSONObject json = Config.overlay(this);
            if (json == null) json = new org.json.JSONObject();
            for (java.util.Iterator<String> it = keys.keys(); it.hasNext(); ) {
                String k = it.next();
                json.put(k, keys.get(k));
            }
            File tmp = new File(dir, Config.FILE_NAME + ".tmp");
            try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(json.toString(2));
            }
            File target = new File(dir, Config.FILE_NAME);
            return tmp.renameTo(target) || (target.delete() && tmp.renameTo(target));
        } catch (Exception e) {
            return false;
        }
    }

    private static org.json.JSONObject keys(Object... kv) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (org.json.JSONException ignored) {
        }
        return o;
    }

    private void showLayoutPicker() {
        String[] names = {"Dock + apps", "Cinematic", "Single row", "Compact grid", "All apps grid"};
        org.json.JSONObject[] values = {
                keys("dock_row", true, "single_row", false, "cinematic_home", false, "columns", 4, "rows_below_dock", 0),
                keys("dock_row", true, "single_row", false, "cinematic_home", true, "columns", 4, "rows_below_dock", 0),
                keys("dock_row", true, "single_row", true, "cinematic_home", false, "columns", 4, "rows_below_dock", 0),
                keys("dock_row", true, "single_row", false, "cinematic_home", false, "columns", 6, "rows_below_dock", 1),
                keys("dock_row", false, "single_row", false, "cinematic_home", false, "columns", 5, "rows_below_dock", 0)};
        pick(R.string.layout_label, names, LAYOUT_KEY, which -> patchConfig(values[which]));
    }

    private void showScreensaverPicker() {
        String[] names = {"Live video + clock", "Photos + clock", "Clock on black", "Other (TV screensaver settings)"};
        org.json.JSONObject[] values = {
                keys("screensaver_video", true, "clock_wallpaper", true, "clock_glow", true),
                keys("screensaver_video", false, "clock_wallpaper", true, "clock_glow", true),
                keys("screensaver_video", false, "clock_wallpaper", false, "clock_glow", true)};
        pick(R.string.screensaver_label, names, SCREENSAVER_KEY, which -> {
            if (which == 3) {   // World Clock and other installed screensavers are chosen in the TV's own list
                open(new Intent(Settings.ACTION_DREAM_SETTINGS));
                return true;
            }
            return patchConfig(values[which]);
        });
    }

    private interface Choice {
        boolean apply(int which);
    }

    private void pick(int title, String[] names, String key, Choice choice) {
        int checked = java.util.Arrays.asList(names).indexOf(choices.getString(key, ""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (choice.apply(which)) {
                        choices.edit().putString(key, names[which]).commit();
                        changed();
                        refresh();
                    } else {
                        Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showWeatherDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.weather_hint);
        input.setText(Weather.city(this));
        new AlertDialog.Builder(this)
                .setTitle(R.string.weather_label)
                .setView(input)
                .setPositiveButton(android.R.string.search_go, (d, w) -> searchCity(input.getText().toString()))
                .setNeutralButton(R.string.weather_off, (d, w) -> {
                    Weather.clear(this);
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void searchCity(String query) {
        if (query.trim().isEmpty()) return;
        Toast.makeText(this, R.string.weather_searching, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Weather.Place> found;
            try {
                found = Weather.search(query);
            } catch (Exception e) {
                found = new java.util.ArrayList<>();
            }
            List<Weather.Place> places = found;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (places.isEmpty()) {
                    Toast.makeText(this, R.string.weather_none_found, Toast.LENGTH_LONG).show();
                    return;
                }
                String[] labels = new String[places.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = places.get(i).label;
                new AlertDialog.Builder(this)
                        .setTitle(R.string.weather_label)
                        .setItems(labels, (d, which) -> {
                            Weather.set(this, places.get(which));
                            changed();
                            refresh();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }, "weather-search").start();
    }

    /** Add-on apps reached from Settings instead of cards on Home. */
    private void showTools() {
        String[][] known = Tools.KNOWN;
        List<String> names = new java.util.ArrayList<>();
        List<Intent> intents = new java.util.ArrayList<>();
        android.content.pm.PackageManager pm = getPackageManager();
        for (String[] k : known) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(k[0]);
            if (i == null) i = pm.getLaunchIntentForPackage(k[0]);
            if (i != null) {
                names.add(k[1]);
                intents.add(i);
            }
        }
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.tools_none, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.tools_label)
                .setItems(names.toArray(new String[0]), (d, which) -> open(intents.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Theme presets pushed from the PC into themes/*.json. Picking one copies it over config.json, which
     *  Config notices (new file stamp) and drops the on-TV column/motion/wallpaper choices, like a theme push. */
    private static final String THEME_KEY = "theme_preset";

    private List<File> themePresets() {
        File dir = Config.dir(this);
        File[] found = dir == null ? null : new File(dir, "themes").listFiles(
                f -> f.isFile() && f.getName().endsWith(".json") && f.length() <= 64 * 1024);
        List<File> list = new java.util.ArrayList<>();
        if (found != null) java.util.Collections.addAll(list, found);
        java.util.Collections.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    private static String themeTitle(File f) {
        // "02-cinematic-home.json" -> "Cinematic home"
        String n = f.getName().replaceFirst("\\.json$", "").replaceFirst("^\\d+[-_ ]*", "").replace('-', ' ').replace('_', ' ');
        return n.isEmpty() ? f.getName() : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private void showThemePicker() {
        List<File> presets = themePresets();
        if (presets.isEmpty()) {
            Toast.makeText(this, R.string.theme_missing, Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[presets.size()];
        String current = choices.getString(THEME_KEY, null);
        int checked = -1;
        for (int i = 0; i < presets.size(); i++) {
            labels[i] = themeTitle(presets.get(i));
            if (presets.get(i).getName().equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.theme_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (applyTheme(presets.get(which))) {
                        choices.edit().putString(THEME_KEY, presets.get(which).getName()).commit();
                        Toast.makeText(this, R.string.theme_applied, Toast.LENGTH_SHORT).show();
                        changed();
                        finish();   // Home recreates with the new theme
                    } else {
                        Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean applyTheme(File preset) {
        File dir = Config.dir(this);
        if (dir == null) return false;
        File target = new File(dir, Config.FILE_NAME);
        File tmp = new File(dir, Config.FILE_NAME + ".tmp");
        try (java.io.InputStream in = new java.io.FileInputStream(preset);
             java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (java.io.IOException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(target) || (target.delete() && tmp.renameTo(target));
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).commit();
        MainActivity.rebuildLive();   // Home behind the sidebar shows the change right away
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        for (int i = 0; i < clockChoices.length; i++) {
            clockChoices[i].setSelected(CLOCK_FORMATS[i].equals(clockFormat));
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String themeCurrent = choices.getString(THEME_KEY, null);
        themeChoice.setText(getString(R.string.theme_state, themeCurrent == null
                ? getString(R.string.theme_none) : themeTitle(new File(themeCurrent))));
        layoutChoice.setText(getString(R.string.layout_state, choices.getString(LAYOUT_KEY, getString(R.string.theme_none))));
        screensaverChoice.setText(getString(R.string.screensaver_state,
                choices.getString(SCREENSAVER_KEY, getString(R.string.theme_none))));
        String city = Weather.city(this);
        weatherChoice.setText(getString(R.string.weather_state, city.isEmpty() ? getString(R.string.weather_off) : city));
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CLOCK_FORMAT_KEY) || choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
