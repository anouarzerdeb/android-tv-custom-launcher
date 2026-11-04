package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import app.rive.runtime.kotlin.RiveAnimationView;
import app.rive.runtime.kotlin.core.Alignment;
import app.rive.runtime.kotlin.core.Fit;
import app.rive.runtime.kotlin.core.Loop;
import app.rive.runtime.kotlin.core.Rive;

/**
 * Animated weather icon left of the weather line (res/raw/weather_icons.riv: artboards Sun, Cloud, Rain, Snow).
 * Theme key "weather_icon" (default true) turns it off. Anything Rive cannot do on this TV leaves the plain text.
 */
final class WeatherIcon {
    private final RiveAnimationView view;
    private String artboard = "";

    private WeatherIcon(RiveAnimationView view) {
        this.view = view;
    }

    /** Puts the icon before {@code line} (a child of a RelativeLayout); null when off or Rive is unavailable. */
    static WeatherIcon attach(Context ctx, TextView line) {
        org.json.JSONObject cfg = Config.overlay(ctx);
        if (cfg != null && !cfg.optBoolean("weather_icon", true)) return null;
        if (!(line.getParent() instanceof RelativeLayout)) return null;
        try {
            Rive.INSTANCE.init(ctx.getApplicationContext(), app.rive.runtime.kotlin.core.RendererType.Rive);
            RiveAnimationView v = new RiveAnimationView(ctx, null);
            v.setId(View.generateViewId());
            v.setFocusable(false);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int size = Math.round(34 * ctx.getResources().getDisplayMetrics().density);
            RelativeLayout parent = (RelativeLayout) line.getParent();
            RelativeLayout.LayoutParams lineLp = (RelativeLayout.LayoutParams) line.getLayoutParams();
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(size, size);
            for (int rule : new int[]{RelativeLayout.BELOW, RelativeLayout.ALIGN_START}) {
                int anchor = lineLp.getRules()[rule];
                if (anchor != 0) lp.addRule(rule, anchor);
            }
            lp.setMarginStart(-Math.round(4 * ctx.getResources().getDisplayMetrics().density));
            parent.addView(v, lp);
            // The text moves right of the icon and sits on its middle.
            lineLp.removeRule(RelativeLayout.ALIGN_START);
            lineLp.addRule(RelativeLayout.END_OF, v.getId());
            lineLp.addRule(RelativeLayout.ALIGN_TOP, v.getId());
            lineLp.addRule(RelativeLayout.ALIGN_BOTTOM, v.getId());
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            line.setLayoutParams(lineLp);
            v.setVisibility(View.GONE);
            return new WeatherIcon(v);
        } catch (Throwable t) {   // UnsatisfiedLinkError on an unusual ABI, etc.: keep Home working
            Log.w("TvHome.WeatherIcon", "Rive unavailable", t);
            return null;
        }
    }

    /** Shows the artboard for the condition in a weather line such as "Berlin  9° Cloudy". */
    void show(String weatherLine) {
        String next = artboardFor(weatherLine);
        if (next == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        if (next.equals(artboard)) return;
        artboard = next;
        try {
            view.setRiveResource(R.raw.weather_icons, next, null, "State Machine 1", true,
                    Fit.CONTAIN, Alignment.CENTER, Loop.AUTO);
        } catch (Throwable t) {
            Log.w("TvHome.WeatherIcon", "Cannot show " + next, t);
            view.setVisibility(View.GONE);
        }
    }

    private static String artboardFor(String line) {
        if (line == null || line.isEmpty()) return null;
        String s = line.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("snow") || s.contains("sleet")) return "Snow";
        if (s.contains("rain") || s.contains("drizzle") || s.contains("thunder")) return "Rain";
        if (s.contains("clear")) return "Sun";
        return "Cloud";   // cloudy, partly cloudy, fog, unknown
    }

    void pause() {
        view.pause();
    }

    void resume() {
        if (view.getVisibility() == View.VISIBLE && !artboard.isEmpty()) view.play(Loop.AUTO, app.rive.runtime.kotlin.core.Direction.AUTO, true);
    }

    static void detach(WeatherIcon icon) {
        if (icon == null) return;
        ViewGroup p = (ViewGroup) icon.view.getParent();
        if (p != null) p.removeView(icon.view);
    }
}
