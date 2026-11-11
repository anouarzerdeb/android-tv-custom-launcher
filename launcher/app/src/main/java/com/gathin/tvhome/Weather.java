package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Current weather for one city, shown on Home's quiet line under the clock. Open-Meteo (no account, no key):
 * geocoding once when the city is chosen in Launcher settings, then the current temperature and condition at
 * most every 30 minutes, and only while Home is on screen (the stats reader calls {@link #line}). Off until a
 * city is chosen. Network calls run on the caller's thread, never the main thread.
 */
final class Weather {
    static final String NAME = "weather_name", LAT = "weather_lat", LON = "weather_lon";
    private static final long FRESH_MS = 30 * 60_000L, RETRY_MS = 60_000L;
    private static final String LAST = "weather_last";
    /** Weather turned off on the TV: the city in config.json is not brought back. */
    private static final String OFF = "weather_off";

    private static String cached = "";
    private static long fetchedAt, failedAt;

    private Weather() {}

    static boolean enabled(Context ctx) {
        fromConfig(ctx);
        return !prefs(ctx).getString(NAME, "").isEmpty();
    }

    /**
     * Theme keys "weather_city", "weather_lat" and "weather_lon": a city set in config.json, so it survives a
     * reinstall (the on-TV choice lives in this app's own storage). Used only while none is chosen on the TV.
     */
    private static void fromConfig(Context ctx) {
        if (!prefs(ctx).getString(NAME, "").isEmpty() || prefs(ctx).getBoolean(OFF, false)) return;
        org.json.JSONObject cfg = Config.overlay(ctx);
        if (cfg == null) return;
        String name = cfg.optString("weather_city", ""), lat = cfg.optString("weather_lat", ""),
                lon = cfg.optString("weather_lon", "");
        if (name.isEmpty() || lat.isEmpty() || lon.isEmpty()) return;
        prefs(ctx).edit().putString(NAME, name).putString(LAT, lat).putString(LON, lon).apply();
    }

    static String city(Context ctx) {
        return prefs(ctx).getString(NAME, "");
    }

    static void set(Context ctx, Place p) {
        prefs(ctx).edit().putString(NAME, p.name).putString(LAT, p.lat).putString(LON, p.lon).remove(OFF).apply();
        cached = "";
        fetchedAt = failedAt = 0;
    }

    static void clear(Context ctx) {
        prefs(ctx).edit().remove(NAME).remove(LAT).remove(LON).putBoolean(OFF, true).apply();   // config's city stays out
        cached = "";
    }

    /** "Berlin  12° Cloudy", refreshed when older than 30 minutes; the last good line while offline. */
    static synchronized String line(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String name = p.getString(NAME, "");
        if (name.isEmpty()) return "";
        // After a restart (or while offline) show the last good reading instead of nothing.
        if (cached.isEmpty()) cached = p.getString(LAST, "");
        long now = System.currentTimeMillis();
        boolean stale = now - fetchedAt > FRESH_MS;
        if (stale && now - failedAt > RETRY_MS) {
            try {
                JSONObject cur = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude="
                        + p.getString(LAT, "") + "&longitude=" + p.getString(LON, "")
                        + "&current=temperature_2m,weather_code&timezone=auto")).getJSONObject("current");
                long t = Math.round(cur.getDouble("temperature_2m"));
                cached = name + "  " + t + "° " + condition(cur.optInt("weather_code", -1));
                fetchedAt = now;
                p.edit().putString(LAST, cached).apply();
            } catch (Exception e) {
                android.util.Log.w("TvHome.Weather", "Open-Meteo failed, trying MET Norway: " + e);
                try {
                    cached = name + "  " + metNorway(p.getString(LAT, ""), p.getString(LON, ""));
                    fetchedAt = now;
                    p.edit().putString(LAST, cached).apply();
                } catch (Exception e2) {
                    failedAt = now;
                    android.util.Log.w("TvHome.Weather", "MET Norway failed too: " + e2);
                }
            }
        }
        return cached;
    }

    static final class Place {
        final String name, label, lat, lon;

        Place(String name, String label, String lat, String lon) {
            this.name = name;
            this.label = label;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Up to 8 matches for a typed city name. */
    static List<Place> search(String query) throws Exception {
        String lang = Locale.getDefault().getLanguage();
        JSONObject json = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&language="
                + lang + "&name=" + URLEncoder.encode(query.trim(), "UTF-8")));
        List<Place> out = new ArrayList<>();
        JSONArray results = json.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String name = r.optString("name");
            StringBuilder label = new StringBuilder(name);
            if (!r.optString("admin1").isEmpty()) label.append(", ").append(r.optString("admin1"));
            if (!r.optString("country").isEmpty()) label.append(", ").append(r.optString("country"));
            out.add(new Place(name, label.toString(),
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")),
                    String.format(Locale.US, "%.4f", r.getDouble("longitude"))));
        }
        return out;
    }

    /** Backup source: MET Norway's free forecast API (asks for an identifying User-Agent, no key). */
    private static String metNorway(String lat, String lon) throws Exception {
        JSONObject first = new JSONObject(get("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat="
                + lat + "&lon=" + lon)).getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0)
                .getJSONObject("data");
        long t = Math.round(first.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature"));
        JSONObject next = first.optJSONObject("next_1_hours");
        String sym = next == null ? "" : next.getJSONObject("summary").optString("symbol_code");
        return t + "° " + symbol(sym);
    }

    private static String symbol(String s) {
        if (s.startsWith("clearsky")) return "Clear";
        if (s.startsWith("fair") || s.startsWith("partlycloudy")) return "Partly cloudy";
        if (s.startsWith("cloudy")) return "Cloudy";
        if (s.startsWith("fog")) return "Fog";
        if (s.contains("thunder")) return "Thunderstorm";
        if (s.contains("snow")) return "Snow";
        if (s.contains("sleet")) return "Sleet";
        if (s.contains("rain")) return "Rain";
        return "";
    }

    /** WMO weather codes as one short word. */
    private static String condition(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "tvhome-launcher/1.0 (github.com/Gathin23/tv-vibehack)");
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new java.io.IOException("HTTP " + code + " from " + new URL(url).getHost());
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
