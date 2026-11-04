package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Writes the theme overlay (config.json) from the TV: whole theme presets, or a few keys such as a layout. */
final class ConfigWriter {
    private ConfigWriter() {}

    static final String[] LAYOUT_NAMES = {"Dock + apps", "Cinematic", "Single row", "Compact grid", "All apps grid"};

    static JSONObject layout(int i) {
        switch (i) {
            case 1: return keys("dock_row", true, "single_row", false, "cinematic_home", true, "columns", 4, "rows_below_dock", 0);
            case 2: return keys("dock_row", true, "single_row", true, "cinematic_home", false, "columns", 4, "rows_below_dock", 0);
            case 3: return keys("dock_row", true, "single_row", false, "cinematic_home", false, "columns", 6, "rows_below_dock", 1);
            case 4: return keys("dock_row", false, "single_row", false, "cinematic_home", false, "columns", 5, "rows_below_dock", 0);
            default: return keys("dock_row", true, "single_row", false, "cinematic_home", false, "columns", 4, "rows_below_dock", 0);
        }
    }

    /** "dock-apps", "all-apps-grid": file-name form of a layout name, for its preview picture. */
    static String slug(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    static JSONObject keys(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** Theme presets pushed from the PC into themes/*.json, sorted by file name. */
    static List<File> themes(Context ctx) {
        File dir = Config.dir(ctx);
        File[] found = dir == null ? null : new File(dir, "themes").listFiles(
                f -> f.isFile() && f.getName().endsWith(".json") && f.length() <= 64 * 1024);
        List<File> list = new ArrayList<>();
        if (found != null) Collections.addAll(list, found);
        Collections.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    /** "02-cinematic-video.json" -> "Cinematic video". */
    static String themeTitle(String fileName) {
        String n = fileName.replaceFirst("\\.json$", "").replaceFirst("^\\d+[-_ ]*", "").replace('-', ' ').replace('_', ' ');
        return n.isEmpty() ? fileName : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** previews/theme-<file stem>.jpg or previews/layout-<slug>.jpg, or null. */
    static File preview(Context ctx, String name) {
        File dir = Config.dir(ctx);
        if (dir == null) return null;
        File f = new File(new File(dir, "previews"), name + ".jpg");
        return f.isFile() ? f : null;
    }

    /** Copies a preset over config.json; Config sees the new file and drops the on-TV overrides. */
    static boolean applyTheme(Context ctx, File preset) {
        File dir = Config.dir(ctx);
        if (dir == null) return false;
        File tmp = new File(dir, Config.FILE_NAME + ".tmp");
        try (InputStream in = new FileInputStream(preset); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        return replace(dir, tmp);
    }

    /** A theme preset holds only look-and-layout keys: merge them, so background, glow and dock choices stay. */
    static boolean applyThemeKeys(Context ctx, File preset) {
        try (InputStream in = new FileInputStream(preset)) {
            byte[] buf = new byte[(int) Math.min(preset.length(), 64 * 1024)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return patch(ctx, new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return false;
        }
    }

    private static final String CUSTOM_BACKUP = "custom-backup.json";

    /** Keeps the owner's own mix (Design "Custom") before a design preset is laid over it. */
    static void saveCustom(Context ctx) {
        File dir = Config.dir(ctx);
        JSONObject json = Config.overlay(ctx);
        if (dir == null || json == null) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, CUSTOM_BACKUP)), StandardCharsets.UTF_8)) {
            w.write(json.toString(2));
        } catch (Exception ignored) {
        }
    }

    /** Design back to "Custom": config.json becomes exactly the mix saveCustom kept; false when there is none. */
    static boolean restoreCustom(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return false;
        File backup = new File(dir, CUSTOM_BACKUP);
        if (!backup.isFile()) return false;
        try {
            File tmp = new File(dir, Config.FILE_NAME + ".tmp");
            try (InputStream in = new FileInputStream(backup);
                 java.io.OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
            return replace(dir, tmp);
        } catch (Exception e) {
            return false;
        }
    }

    /** Writes {@code keys} into the active config.json (creating it if needed), keeping every other key. */
    static boolean patch(Context ctx, JSONObject keys) {
        File dir = Config.dir(ctx);
        if (dir == null) return false;
        try {
            JSONObject json = Config.overlay(ctx);
            if (json == null) json = new JSONObject();
            for (Iterator<String> it = keys.keys(); it.hasNext(); ) {
                String k = it.next();
                json.put(k, keys.get(k));
            }
            File tmp = new File(dir, Config.FILE_NAME + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(json.toString(2));
            }
            return replace(dir, tmp);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean replace(File dir, File tmp) {
        File target = new File(dir, Config.FILE_NAME);
        return tmp.renameTo(target) || (target.delete() && tmp.renameTo(target));
    }
}
