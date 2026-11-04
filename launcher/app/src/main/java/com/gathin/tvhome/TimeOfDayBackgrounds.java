package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Theme key "time_of_day_backgrounds": the photos and videos sorted by how bright they are, the brightest third for
 * the morning, the middle third for the evening and the darkest for the night. Written as wallpaper_moods (which
 * WallpaperPlayer already follows) and video_moods (VideoLayer), so nobody has to group the files by hand.
 */
final class TimeOfDayBackgrounds {
    private static final String TAG = "TvHome.TimeOfDay";

    private TimeOfDayBackgrounds() {
    }

    private interface Luma {
        double of(File f);
    }

    /** Sorts both collections off the main thread, writes the groups to config.json, then runs {@code done}. */
    static void build(Context ctx, Runnable done) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            JSONObject keys = new JSONObject();
            try {
                File dir = Config.dir(app);
                List<File> photos = new ArrayList<>();
                File[] found = dir == null ? null : new File(dir, "wallpapers").listFiles(
                        f -> f.isFile() && ThemeFiles.collectionImage(f.getName()));
                if (found != null) Collections.addAll(photos, found);
                keys.put("wallpaper_moods", groups(photos, TimeOfDayBackgrounds::photoLuma));
                keys.put("video_moods", groups(VideoLayer.videos(app), TimeOfDayBackgrounds::videoLuma));
                keys.put("time_of_day_backgrounds", true);
            } catch (Exception e) {
                Log.w(TAG, "Cannot sort the backgrounds", e);
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                if (keys.length() > 0) ConfigWriter.patch(app, keys);
                if (done != null) done.run();
            });
        }, "time-of-day-backgrounds").start();
    }

    /** The keys that turn it off: no groups, so both rotations use every file again. */
    static JSONObject off() {
        return ConfigWriter.keys("time_of_day_backgrounds", false,
                "wallpaper_moods", new JSONObject(), "video_moods", new JSONObject());
    }

    /** The files of {@code all} that fit the time of day now, per video_moods; all of them without groups. */
    static List<File> forNow(List<File> all, Map<String, Set<String>> groups) {
        if (groups.isEmpty()) return all;
        java.time.LocalTime now = java.time.LocalTime.now();
        String mood = WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(),
                WallpaperMood.DEFAULT_SUNRISE, WallpaperMood.DEFAULT_SUNSET);
        return WallpaperMood.select(all, groups, mood);
    }

    /** video_moods as name groups, as Config reads wallpaper_moods. */
    static Map<String, Set<String>> read(JSONObject o) {
        Map<String, Set<String>> groups = new java.util.HashMap<>();
        if (o == null) return groups;
        for (String mood : WallpaperMood.ALL) {
            JSONArray names = o.optJSONArray(mood);
            if (names == null || names.length() == 0) continue;
            Set<String> group = new java.util.HashSet<>();
            for (int i = 0; i < names.length(); i++) group.add(names.optString(i, ""));
            groups.put(mood, group);
        }
        return groups;
    }

    private static JSONObject groups(List<File> files, Luma luma) throws Exception {
        List<Object[]> scored = new ArrayList<>();
        for (File f : files) {
            double l = luma.of(f);
            if (l >= 0) scored.add(new Object[]{f.getName(), l});
        }
        JSONObject out = new JSONObject();
        if (scored.size() < 3) return out;   // too few to split: every file at every hour
        Collections.sort(scored, (a, b) -> Double.compare((double) b[1], (double) a[1]));   // brightest first
        int third = scored.size() / 3;
        JSONArray morning = new JSONArray(), evening = new JSONArray(), night = new JSONArray();
        for (int i = 0; i < scored.size(); i++) {
            (i < third ? morning : i < scored.size() - third ? evening : night).put(scored.get(i)[0]);
        }
        out.put(WallpaperMood.MORNING, morning);
        out.put(WallpaperMood.EVENING, evening);
        out.put(WallpaperMood.NIGHT, night);
        return out;
    }

    private static double photoLuma(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return -1;
        o.inJustDecodeBounds = false;
        o.inSampleSize = Math.max(1, Integer.highestOneBit(o.outWidth / 64));
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return luma(BitmapFactory.decodeFile(f.getPath(), o));
    }

    private static double videoLuma(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            Bitmap frame = r.getFrameAtTime(3_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return -1;
            Bitmap small = Bitmap.createScaledBitmap(frame, 64, 36, true);
            if (small != frame) frame.recycle();
            return luma(small);
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** Mean perceived brightness, 0 to 1; recycles the bitmap. */
    private static double luma(Bitmap b) {
        if (b == null) return -1;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        double sum = 0;
        for (int p : px) sum += 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255);
        return px.length == 0 ? -1 : sum / px.length / 255.0;
    }
}
