package com.gathin.tvhome;

import android.service.dreams.DreamService;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow (ClockScene).
 * With the theme key screensaver_video and clips in videos/, a muted landscape video plays underneath.
 */
public class ClockDream extends DreamService {

    private ClockScene scene;
    /** Landscape video under the clock (theme key screensaver_video); null when off or no videos. */
    private VideoLayer video;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        ClockFormat.apply(getWindow().getDecorView(), Config.load(this).clockFormat);
        scene = new ClockScene(getWindow().getDecorView());
        scene.useHomeWallpaper(this);
        // Index 1: above the ambient/wallpaper image, below the drifting clock.
        View ambient = findViewById(R.id.dreamAmbient);
        if (ambient != null && ambient.getParent() instanceof FrameLayout) {
            video = VideoLayer.attach(this, (FrameLayout) ambient.getParent(), 1, "screensaver_video");
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
        if (video != null) video.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        if (video != null) video.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        if (video != null) video.close();
        super.onDetachedFromWindow();
    }
}
