package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A muted landscape video behind Home or the screensaver, from the theme folder's videos/.
 *
 * Theme keys (config.json): "video_wallpaper" (Home) and "screensaver_video" (ClockDream), both false by
 * default; "video_dim" 0..90 for a black shade; "video_rotation_minutes" (default 60) for how long one
 * video stays before another is picked. One video loops; Home and the screensaver share the current pick
 * (SharedPreferences "video_rotation"), so the screensaver shows the same scene. The player is released
 * whenever the layer stops, so the hardware decoder is free the moment an app opens.
 */
final class VideoLayer implements TextureView.SurfaceTextureListener {
    private static final String TAG = "TvHome.Video";
    private static final List<String> EXTENSIONS = Arrays.asList(".mp4", ".webm", ".mkv", ".m4v");
    private static final long MAX_BYTES = 200L * 1024 * 1024;
    private static final String PREFS = "video_rotation";

    private final TextureView texture;
    private final List<File> files;
    private final long interval;
    private final SharedPreferences prefs;
    private final Random random = new Random();
    private final Runnable rotate = this::rotateNow;
    private MediaPlayer player;
    private Surface surface;
    private boolean active;
    private int failures;
    /** video_moods (TimeOfDayBackgrounds): the videos that fit each time of day; empty uses all of them. */
    private java.util.Map<String, java.util.Set<String>> moods = java.util.Collections.emptyMap();

    /** Adds the layer at {@code index} of {@code root} when {@code key} is on and videos exist; else null. */
    static VideoLayer attach(Context ctx, FrameLayout root, int index, String key) {
        JSONObject json = Config.overlay(ctx);
        if (json == null || !json.optBoolean(key, false)) return null;
        List<File> files = videos(ctx);
        if (files.isEmpty()) return null;
        int dim = Math.max(0, Math.min(90, json.optInt("video_dim", json.optInt("wallpaper_dim", 0))));
        int minutes = Math.max(1, Math.min(1440, json.optInt("video_rotation_minutes", 60)));
        VideoLayer layer = new VideoLayer(ctx, root, index, files, dim, minutes * 60_000L);
        layer.moods = TimeOfDayBackgrounds.read(json.optJSONObject("video_moods"));
        return layer;
    }

    static List<File> videos(Context ctx) {
        File dir = Config.dir(ctx);
        File[] found = dir == null ? null : new File(dir, "videos").listFiles(f -> {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            int dot = n.lastIndexOf('.');
            return f.isFile() && f.length() > 0 && f.length() <= MAX_BYTES
                    && dot > 0 && EXTENSIONS.contains(n.substring(dot));
        });
        List<File> list = new ArrayList<>();
        if (found != null) Collections.addAll(list, found);
        Collections.sort(list);
        return list;
    }

    private VideoLayer(Context ctx, FrameLayout root, int index, List<File> files, int dim, long interval) {
        this.files = files;
        this.interval = interval;
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        texture = new TextureView(ctx);
        texture.setAlpha(0f);   // faded in on the first rendered frame, so no black flash
        texture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        texture.setSurfaceTextureListener(this);
        int at = Math.max(0, Math.min(index, root.getChildCount()));
        root.addView(texture, at, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (dim > 0) {
            View shade = new View(ctx);
            shade.setBackgroundColor((dim * 255 / 100) << 24);
            shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(shade, at + 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    void start() {
        active = true;
        play();
    }

    /** Releases the decoder; Home calls this in onPause so apps get it straight away. */
    void stop() {
        active = false;
        texture.removeCallbacks(rotate);
        release();
        texture.animate().cancel();
        texture.setAlpha(0f);
    }

    void close() {
        stop();
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        play();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        if (player != null) fit(player.getVideoWidth(), player.getVideoHeight());
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    /** The video for now: the saved one until its hour is up, then a different random one. */
    private File current() {
        String name = prefs.getString("file", "");
        long changed = prefs.getLong("changed", 0);
        long now = System.currentTimeMillis();
        File saved = null;
        List<File> pool = TimeOfDayBackgrounds.forNow(files, moods);
        for (File f : pool) if (f.getName().equals(name)) saved = f;
        if (saved != null && now - changed >= 0 && now - changed < interval) return saved;
        File next = pool.get(random.nextInt(pool.size()));
        if (pool.size() > 1) while (next.getName().equals(name)) next = pool.get(random.nextInt(pool.size()));
        prefs.edit().putString("file", next.getName()).putLong("changed", now).apply();
        return next;
    }

    private void play() {
        if (!active || surface == null || player != null || files.isEmpty()) return;
        File video = current();
        long left = interval - (System.currentTimeMillis() - prefs.getLong("changed", 0));
        texture.removeCallbacks(rotate);
        texture.postDelayed(rotate, Math.max(1_000L, left));
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(video.getPath());
            mp.setSurface(surface);
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            mp.setOnVideoSizeChangedListener((p, w, h) -> fit(w, h));
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    failures = 0;
                    texture.animate().alpha(1f).setDuration(900).start();
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "Cannot play " + video.getName() + " (" + what + "/" + extra + ")");
                skip(video);
                return true;
            });
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Cannot open " + video.getName(), e);
            skip(video);
        }
    }

    /** Drops a file that will not play and moves on to another. */
    private void skip(File bad) {
        files.remove(bad);
        prefs.edit().putLong("changed", 0).apply();
        release();
        if (++failures < 5) play();
    }

    /** The hour is up while Home is on screen: fade out, then the next video. */
    private void rotateNow() {
        if (!active) return;
        prefs.edit().putLong("changed", 0).apply();
        texture.animate().alpha(0f).setDuration(800).withEndAction(() -> {
            release();
            play();
        }).start();
    }

    /** Centre-crop: scale the video to cover the view, like the image wallpaper. */
    private void fit(int vw, int vh) {
        int w = texture.getWidth(), h = texture.getHeight();
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
        float scale = Math.max((float) w / vw, (float) h / vh);
        Matrix m = new Matrix();
        m.setScale(vw * scale / w, vh * scale / h, w / 2f, h / 2f);
        texture.setTransform(m);
    }

    private void release() {
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try {
                mp.reset();
            } catch (Exception ignored) {
            }
            mp.release();
        }
    }
}
