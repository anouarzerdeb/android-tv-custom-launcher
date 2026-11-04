package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The owner's redesign of Launcher settings as a sidebar: sections (Appearance, Home screen, Clock and weather,
 * Screensaver, More), visual cards with Home previews for theme, layout and screensaver (OK applies at once,
 * Home rebuilds live behind the panel), and the text-only rows that a remote cannot use well hidden.
 * Works on the views of activity_launcher_settings.xml without removing any, so the activity keeps its bindings.
 */
final class SettingsRedesign {
    // Palette: dark slate glass, white focus ring, green for the applied choice.
    private static final int PANEL = 0xF20F172A, EDGE = 0x33FFFFFF, CARD = 0xFF1B2336, CARD_FOCUS = 0xFF273349;
    private static final int TEXT = 0xFFF8FAFC, MUTED = 0xFF94A3B8, ACCENT = 0xFF22C55E, RING = 0xFFFFFFFF;

    private final Activity activity;
    private final SharedPreferences choices;
    private final Runnable changed;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final float density;
    /** The applied theme card (or the first), focused when the panel opens. */
    private View focusCard;
    /** The sidebar itself; it fades almost away after a change so Home can be seen, until the next key. */
    private View panelView;
    private static SettingsRedesign active;
    private final Runnable comeBack = this::restore;

    private SettingsRedesign(Activity activity, SharedPreferences choices, Runnable changed) {
        this.activity = activity;
        this.choices = choices;
        this.changed = changed;
        density = activity.getResources().getDisplayMetrics().density;
    }

    /** Rebuilds the settings column; {@code changed} reports a saved change (Home rebuilds live).
     *  Returns the card to focus first. */
    static View apply(Activity a, SharedPreferences choices, Runnable changed) {
        SettingsRedesign r = new SettingsRedesign(a, choices, changed);
        r.build();
        return r.focusCard;
    }

    private void build() {
        View reorder = activity.findViewById(R.id.reorderChoice);
        LinearLayout column = (LinearLayout) reorder.getParent();
        View scroll = (View) column.getParent();
        panelView = scroll;
        active = this;
        scroll.setBackground(panel());
        column.setPadding(dp(40), dp(36), dp(40), dp(48));

        // Text-only rows that do not suit a remote, or that the cards replace.
        int[] hide = {R.id.themeChoice, R.id.layoutChoice, R.id.screensaverChoice, R.id.wallpaperChoice,
                R.id.animationsChoice, R.id.advancedChoice, R.id.openOnStartChoice, R.id.followThemeChoice,
                R.id.defaultHomeChoice};
        for (int id : hide) hideWithHint(column, activity.findViewById(id));

        // Pull the existing blocks out, in the order they should come back.
        View title = column.getChildAt(0);
        View columnsLabel = labelBefore(column, activity.findViewById(R.id.columns4));
        View columnsRow = (View) activity.findViewById(R.id.columns4).getParent();
        View rowsLabel = labelBefore(column, activity.findViewById(R.id.rows0));
        View rowsRow = (View) activity.findViewById(R.id.rows0).getParent();
        View dockLabel = labelBefore(column, activity.findViewById(R.id.dock100));
        View dockRow = (View) activity.findViewById(R.id.dock100).getParent();
        View clockLabel = labelBefore(column, activity.findViewById(R.id.clockSystem));
        View clockRow = (View) activity.findViewById(R.id.clockSystem).getParent();
        View weather = activity.findViewById(R.id.weatherChoice);
        View tools = activity.findViewById(R.id.toolsChoice);
        View tv = activity.findViewById(R.id.tvSettingsChoice);

        List<View> hidden = new ArrayList<>();
        for (int i = 0; i < column.getChildCount(); i++) {
            View c = column.getChildAt(i);
            if (c.getVisibility() == View.GONE) hidden.add(c);
        }
        column.removeAllViews();

        column.addView(title);
        TextView subtitle = text("Changes show on Home right away", 15, MUTED);
        subtitle.setPadding(0, dp(4), 0, 0);
        column.addView(subtitle);

        section(column, "Theme");
        column.addView(themeList());

        section(column, "Background");
        backgroundSection(column);

        section(column, "Home screen");
        add(column, columnsLabel, columnsRow, rowsLabel, rowsRow, dockLabel, dockRow);

        section(column, "Clock and weather");
        add(column, clockLabel, clockRow, weather);

        section(column, "Screensaver");
        column.addView(screensaverCards());

        section(column, "More");
        add(column, tools, reorder, tv);
        for (View v : hidden) column.addView(v);   // still bound by the activity, never shown

        styleRows(column);
    }

    // ---- cards ----------------------------------------------------------------------------------------------

    /** Every theme as a plain row; OK applies it, the applied one carries a check and a green ring. */
    private View themeList() {
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        String current = choices.getString(PreviewPickerActivity.THEME_KEY, "");
        for (File f : ConfigWriter.themes(activity)) {
            String title = ConfigWriter.themeTitle(f.getName());
            boolean applied = f.getName().equals(current);
            TextView row = text(applied ? title + "   \u2713" : title, 18, TEXT);
            row.setTag(title);
            row.setFocusable(true);
            row.setClickable(true);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), 0, dp(20), 0);
            row.setSelected(applied);
            row.setOnClickListener(v -> {
                if (!ConfigWriter.applyThemeKeys(activity, f)) return;
                choices.edit().putString(PreviewPickerActivity.THEME_KEY, f.getName()).commit();
                for (int i = 0; i < list.getChildCount(); i++) {
                    TextView r = (TextView) list.getChildAt(i);
                    r.setSelected(r == v);
                    r.setText(r == v ? r.getTag() + "   \u2713" : (String) r.getTag());
                }
                changed.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
            lp.topMargin = dp(8);
            list.addView(row, lp);
            if (focusCard == null || applied) focusCard = row;
        }
        return list;
    }

    // ---- background: type, change interval, app colour glow ----------------------------------------------------

    private void backgroundSection(LinearLayout column) {
        JSONObject cfg = Config.overlay(activity);
        if (cfg == null) cfg = new JSONObject();

        String[] types = {"Live video", "Photos", "Night sky", "Black"};
        JSONObject[] typeKeys = {
                ConfigWriter.keys("video_wallpaper", true, "wallpaper", true, "sky_intensity", 0,
                        "background_color", "#000000", "screensaver_video", true, "clock_wallpaper", true),
                ConfigWriter.keys("video_wallpaper", false, "wallpaper", true, "sky_intensity", 0,
                        "background_color", "#000000", "screensaver_video", false, "clock_wallpaper", true),
                ConfigWriter.keys("video_wallpaper", false, "wallpaper", false, "sky_intensity", 75,
                        "background_color", "#05070F", "screensaver_video", false, "clock_wallpaper", false),
                ConfigWriter.keys("video_wallpaper", false, "wallpaper", false, "sky_intensity", 0,
                        "background_color", "#000000", "screensaver_video", false, "clock_wallpaper", false)};
        int type = cfg.optBoolean("video_wallpaper", false) ? 0 : cfg.optBoolean("wallpaper", true) ? 1
                : cfg.optInt("sky_intensity", 0) > 0 ? 2 : 3;
        column.addView(caption("Show"));
        column.addView(segmented(types, typeKeys, type));

        String[] every = {"5 min", "15 min", "30 min", "1 hour", "3 hours"};
        int[] minutes = {5, 15, 30, 60, 180};
        JSONObject[] everyKeys = new JSONObject[every.length];
        int current = cfg.optInt("video_rotation_minutes", cfg.optInt("wallpaper_rotation_minutes", 60)), at = 3;
        for (int i = 0; i < every.length; i++) {
            everyKeys[i] = ConfigWriter.keys("video_rotation_minutes", minutes[i], "wallpaper_rotation_minutes", minutes[i]);
            if (minutes[i] == current) at = i;
        }
        column.addView(caption("Change every"));
        column.addView(segmented(every, everyKeys, at));

        String[] glow = {"On", "Off"};
        JSONObject[] glowKeys = {ConfigWriter.keys("ambient_glow", true, "app_glow", true),
                ConfigWriter.keys("ambient_glow", false, "app_glow", false, "glow_style", "radial")};
        column.addView(caption("App colour glow"));
        column.addView(segmented(glow, glowKeys, cfg.optBoolean("ambient_glow", true) ? 0 : 1));

        column.addView(caption("Animated weather icon"));
        column.addView(segmented(new String[]{"On", "Off"},
                new JSONObject[]{ConfigWriter.keys("weather_icon", true), ConfigWriter.keys("weather_icon", false)},
                cfg.optBoolean("weather_icon", true) ? 0 : 1));

        // ---- Style: launcher looks that had no on-TV control before
        section(column, "Style");
        String gs = cfg.optString("glass_style", "glass");
        column.addView(caption("Focus highlight"));
        column.addView(segmented(new String[]{"Glass", "Rim", "Halo"},
                new JSONObject[]{ConfigWriter.keys("glass_style", "glass"), ConfigWriter.keys("glass_style", "rim"),
                        ConfigWriter.keys("glass_style", "halo")},
                "rim".equals(gs) ? 1 : "halo".equals(gs) ? 2 : 0));
        column.addView(caption("App tiles"));
        column.addView(segmented(new String[]{"App banners", "Icon cards"},
                new JSONObject[]{ConfigWriter.keys("uniform_tiles", false), ConfigWriter.keys("uniform_tiles", true)},
                cfg.optBoolean("uniform_tiles", false) ? 1 : 0));
        column.addView(caption("App names"));
        column.addView(segmented(new String[]{"Focused only", "Always"},
                new JSONObject[]{ConfigWriter.keys("always_show_names", false), ConfigWriter.keys("always_show_names", true)},
                cfg.optBoolean("always_show_names", false) ? 1 : 0));
        column.addView(caption("Dock"));
        column.addView(segmented(new String[]{"Glass band", "Solid band", "No band"},
                new JSONObject[]{ConfigWriter.keys("dock_band", true, "dock_glass", true),
                        ConfigWriter.keys("dock_band", true, "dock_glass", false),
                        ConfigWriter.keys("dock_band", false, "dock_glass", false)},
                !cfg.optBoolean("dock_band", true) ? 2 : cfg.optBoolean("dock_glass", false) ? 0 : 1));
        column.addView(caption("Greeting"));
        column.addView(segmented(new String[]{"Show", "Hide"},
                new JSONObject[]{ConfigWriter.keys("show_greeting", true), ConfigWriter.keys("show_greeting", false)},
                cfg.optBoolean("show_greeting", true) ? 0 : 1));
        column.addView(caption("System stats (top right)"));
        column.addView(segmented(new String[]{"Show", "Hide"},
                new JSONObject[]{ConfigWriter.keys("show_stats", true), ConfigWriter.keys("show_stats", false)},
                cfg.optBoolean("show_stats", false) ? 0 : 1));
        column.addView(caption("Motion"));
        column.addView(segmented(new String[]{"Lively", "Calm"},
                new JSONObject[]{ConfigWriter.keys("focus_overshoot", true, "advanced_effects", true),
                        ConfigWriter.keys("focus_overshoot", false, "advanced_effects", false)},
                cfg.optBoolean("focus_overshoot", true) ? 0 : 1));
    }

    /** A row of equal choices; OK writes that choice's keys, Home rebuilds, the choice turns green. */
    private View segmented(String[] names, JSONObject[] keys, int selected) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            int index = i;
            TextView t = text(names[i], 16, TEXT);
            t.setGravity(Gravity.CENTER);
            t.setFocusable(true);
            t.setClickable(true);
            t.setSingleLine(true);
            t.setSelected(i == selected);
            t.setOnClickListener(v -> {
                if (!ConfigWriter.patch(activity, keys[index])) return;
                for (int j = 0; j < row.getChildCount(); j++) row.getChildAt(j).setSelected(j == index);
                changed.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
            lp.setMarginEnd(i < names.length - 1 ? dp(10) : 0);
            row.addView(t, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    /** After any change: let Home show through, then come back on the next key (or after 8 seconds). */
    static void fadeActive() {
        SettingsRedesign r = active;
        if (r == null || r.panelView == null) return;
        r.panelView.animate().alpha(0.04f).setDuration(220).start();
        r.panelView.removeCallbacks(r.comeBack);
        r.panelView.postDelayed(r.comeBack, 8000);
    }

    /** True when the panel was faded: the key only brings it back. */
    static boolean restoreIfFaded() {
        SettingsRedesign r = active;
        if (r == null || r.panelView == null || r.panelView.getAlpha() > 0.5f) return false;
        r.restore();
        return true;
    }

    private void restore() {
        panelView.removeCallbacks(comeBack);
        panelView.animate().alpha(1f).setDuration(180).start();
    }

    private View themeCards() {
        List<File> themes = ConfigWriter.themes(activity);
        String current = choices.getString(PreviewPickerActivity.THEME_KEY, "");
        LinearLayout row = cardRow();
        for (File f : themes) {
            String stem = f.getName().replaceFirst("\\.json$", "");
            boolean applied = f.getName().equals(current);
            View c = card(ConfigWriter.themeTitle(f.getName()), "theme-" + stem, null, applied, () -> {
                        if (!ConfigWriter.applyTheme(activity, f)) return false;
                        choices.edit().putString(PreviewPickerActivity.THEME_KEY, f.getName()).commit();
                        return true;
                    });
            row.addView(c);
            if (focusCard == null || applied) focusCard = c;
        }
        return scroller(row);
    }

    private View layoutCards() {
        String current = choices.getString(PreviewPickerActivity.LAYOUT_KEY, "");
        LinearLayout row = cardRow();
        for (int i = 0; i < ConfigWriter.LAYOUT_NAMES.length; i++) {
            String name = ConfigWriter.LAYOUT_NAMES[i];
            int index = i;
            row.addView(card(name, "layout-" + ConfigWriter.slug(name), null, name.equals(current), () -> {
                if (!ConfigWriter.patch(activity, ConfigWriter.layout(index))) return false;
                choices.edit().putString(PreviewPickerActivity.LAYOUT_KEY, name).commit();
                return true;
            }));
        }
        return scroller(row);
    }

    private View screensaverCards() {
        String key = "screensaver_choice";
        String current = choices.getString(key, "");
        String[] names = {"Live video", "Photos", "Clock only"};
        String[] previews = {"theme-01-living-landscapes", "theme-03-photo-landscapes", null};
        JSONObject[] values = {
                ConfigWriter.keys("screensaver_video", true, "clock_wallpaper", true, "clock_glow", true),
                ConfigWriter.keys("screensaver_video", false, "clock_wallpaper", true, "clock_glow", true),
                ConfigWriter.keys("screensaver_video", false, "clock_wallpaper", false, "clock_glow", true)};
        LinearLayout row = cardRow();
        for (int i = 0; i < names.length; i++) {
            int index = i;
            row.addView(card(names[i], previews[i], i == 2 ? "12:00" : null, names[i].equals(current), () -> {
                if (!ConfigWriter.patch(activity, values[index])) return false;
                choices.edit().putString(key, names[index]).commit();
                return true;
            }));
        }
        return scroller(row);
    }

    private interface Action {
        boolean run();
    }

    /** A focusable card: Home preview picture (or a stand-in), name, and a green ring when applied. */
    private View card(String name, String preview, String standIn, boolean applied, Action action) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(6), dp(6), dp(6), dp(10));
        card.setBackground(cardBackground());
        card.setSelected(applied);

        FrameLayout picture = new FrameLayout(activity);
        picture.setBackgroundColor(0xFF000000);
        picture.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        picture.setClipToOutline(true);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (standIn != null) {
            TextView t = text(standIn, 34, 0xE6FFFFFF);
            t.setGravity(Gravity.CENTER);
            picture.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        TextView check = text("✓", 16, 0xFF0F172A);
        check.setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ACCENT);
        check.setBackground(dot);
        check.setVisibility(applied ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.END);
        checkLp.setMargins(0, dp(8), dp(8), 0);
        picture.addView(check, checkLp);
        card.addView(picture, new LinearLayout.LayoutParams(dp(224), dp(126)));

        TextView label = text(name, 16, TEXT);
        label.setPadding(dp(4), dp(10), dp(4), 0);
        label.setSingleLine(true);
        card.addView(label);

        if (preview != null) loadPreview(image, preview);
        card.setOnFocusChangeListener((v, has) ->
                v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(150).start());
        card.setOnClickListener(v -> {
            if (!action.run()) return;
            ViewGroup row = (ViewGroup) v.getParent();
            for (int i = 0; i < row.getChildCount(); i++) {
                View other = row.getChildAt(i);
                other.setSelected(other == v);
                View c = ((ViewGroup) ((ViewGroup) other).getChildAt(0)).getChildAt(
                        ((ViewGroup) ((ViewGroup) other).getChildAt(0)).getChildCount() - 1);
                c.setVisibility(other == v ? View.VISIBLE : View.GONE);
            }
            changed.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), dp(16), dp(6));
        card.setLayoutParams(lp);
        return card;
    }

    private void loadPreview(ImageView image, String name) {
        File f = ConfigWriter.preview(activity, name);
        if (f == null) return;
        decoder.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;   // 1280x720 previews, shown at card size
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            image.post(() -> image.setImageBitmap(b));
        });
    }

    // ---- building blocks ------------------------------------------------------------------------------------

    private LinearLayout cardRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(6), dp(4), dp(24), dp(4));
        return row;
    }

    private View scroller(LinearLayout row) {
        HorizontalScrollView s = new HorizontalScrollView(activity);
        s.setHorizontalScrollBarEnabled(false);
        s.setClipToPadding(false);
        s.setClipChildren(false);
        s.addView(row);
        s.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private void section(LinearLayout column, String name) {
        TextView t = text(name.toUpperCase(java.util.Locale.ROOT), 13, MUTED);
        t.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(36);
        lp.bottomMargin = dp(4);
        View rule = new View(activity);
        rule.setBackgroundColor(EDGE);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        ruleLp.topMargin = dp(32);
        column.addView(rule, ruleLp);
        column.addView(t, lp);
    }

    private TextView caption(String s) {
        TextView t = text(s, 18, TEXT);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static void add(LinearLayout column, View... views) {
        for (View v : views) if (v != null) column.addView(v);
    }

    /** The label TextView right above a choice row, or null. */
    private static View labelBefore(LinearLayout column, View choice) {
        View row = (View) choice.getParent();
        int i = column.indexOfChild(row);
        return i > 0 ? column.getChildAt(i - 1) : null;
    }

    /** Hides a row and the explanatory text right under it (follow_theme_hint, home_settings_help...). */
    private static void hideWithHint(LinearLayout column, View row) {
        if (row == null) return;
        row.setVisibility(View.GONE);
        int i = column.indexOfChild(row);
        if (i >= 0 && i + 1 < column.getChildCount()) {
            View next = column.getChildAt(i + 1);
            if (next instanceof TextView && !next.isFocusable()) next.setVisibility(View.GONE);
        }
    }

    /** Rows and segmented choices: same focus ring and applied colour as the cards. */
    private void styleRows(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            if (v instanceof HorizontalScrollView) continue;
            if (v instanceof TextView && v.isFocusable()) {
                v.setBackground(rowBackground());
                ((TextView) v).setTextColor(TEXT);
            } else if (v instanceof ViewGroup) {
                styleRows((ViewGroup) v);
            }
        }
    }

    // ---- drawables ------------------------------------------------------------------------------------------

    private Drawable panel() {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(PANEL);
        GradientDrawable edge = new GradientDrawable();
        edge.setColor(EDGE);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{edge, fill});
        layers.setLayerInset(1, dp(1), 0, 0, 0);   // a 1dp light line down the left edge
        return layers;
    }

    private Drawable cardBackground() {
        return states(dp(16), CARD, CARD_FOCUS);
    }

    private Drawable rowBackground() {
        return states(dp(12), CARD, CARD_FOCUS);
    }

    /** Focused: lighter fill with a white ring. Applied: green ring. Otherwise a quiet card. */
    private Drawable states(int radius, int fill, int focusFill) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(radius, focusFill, RING, dp(3)));
        s.addState(new int[]{android.R.attr.state_selected}, shape(radius, fill, ACCENT, dp(2)));
        s.addState(new int[]{}, shape(radius, fill, Color.TRANSPARENT, 0));
        return s;
    }

    private GradientDrawable shape(int radius, int fill, int stroke, int width) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(fill);
        if (width > 0) g.setStroke(width, stroke);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
