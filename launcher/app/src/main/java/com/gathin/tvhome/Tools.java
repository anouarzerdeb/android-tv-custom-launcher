package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The helper apps that live in Customize > Tools instead of on Home or in the App library: package, name and what
 * opening it is for. The theme lists the same packages in hidden_apps and library_excluded_apps.
 */
final class Tools {
    static final String[][] KNOWN = {
            {"io.github.sumyabazar1.weather", "Weather", "Forecast"},
            {"io.github.luyzfernando08.sleeptimer", "Sleep timer", "Turn off later"},
            {"io.github.savioronezero.worldclock", "World clock", "Other time zones"},
            {"io.github.maxhayim.headphones", "Headphones", "Audio output"},
            {"com.gathin.btconnect", "Bluetooth audio", "Speakers, headphones"},
            {"io.github.maxhayim.quiethours", "Quiet hours", "Night volume"},
            {"io.github.maxhayim.brightness", "Brightness", "Night mode"},
            {"io.github.luyzfernando08.wakeapp", "Open on wake", "App after power on"},
            {"io.github.maxhayim.remoteaccess", "Remote access", "Network debugging"},
            {"org.localsend.localsend_app", "LocalSend", "Receive files"},
            {"io.github.sds100.keymapper", "Key Mapper", "Remote buttons"},
            {"com.neilturner.aerialviews", "Aerial Views", "Screensaver settings"}};

    private Tools() {
    }

    /** One installed tool: its row text and how to open it. */
    static final class Tool {
        final String pkg, name, hint;
        final Intent open;

        Tool(String pkg, String name, String hint, Intent open) {
            this.pkg = pkg;
            this.name = name;
            this.hint = hint;
            this.open = open;
        }
    }

    /** The tools that are installed and can be opened, in KNOWN's order. */
    static List<Tool> installed(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<Tool> out = new ArrayList<>();
        for (String[] k : KNOWN) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(k[0]);
            if (i == null) i = pm.getLaunchIntentForPackage(k[0]);
            if (i != null) out.add(new Tool(k[0], k[1], k[2], i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }
        return out;
    }
}
