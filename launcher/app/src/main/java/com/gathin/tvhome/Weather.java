package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The weather card (weather_latitude, weather_longitude, weather_city): in the shelf above the dock while no music
 * plays, the place's temperature, sky and rain from Open-Meteo (free, no account), laid out like the now playing
 * card. Fetched when Home shows and every 30 minutes while it stays; the last answer is kept, so the card shows at
 * once and still has something to say while the network is down. The launcher's only use of the internet.
 */
final class Weather {
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final String PREFS = "weather", KEY_JSON = "json", KEY_AT = "at", KEY_PLACE = "place";

    private final Context context;
    private final View view;
    private final ImageView icon;
    private final TextView kicker, temperature, condition, details;
    private final double latitude, longitude;
    private final String city;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refresh;
    private boolean started, shelf, blocked, hasData, fetching;
    private float bottom = -1;

    Weather(Context context, View view, double latitude, double longitude, String city) {
        this.context = context.getApplicationContext();
        this.view = view;
        this.latitude = latitude;
        this.longitude = longitude;
        this.city = city;
        icon = view.findViewById(R.id.weatherIcon);
        kicker = view.findViewById(R.id.weatherKicker);
        temperature = view.findViewById(R.id.weatherTemp);
        condition = view.findViewById(R.id.weatherCondition);
        details = view.findViewById(R.id.weatherDetails);
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place());
        // The kept answer counts only for the place it was asked for: a theme with another city starts afresh.
        String cached = placeKey().equals(prefs().getString(KEY_PLACE, null)) ? prefs().getString(KEY_JSON, null) : null;
        if (cached != null) show(cached);
    }

    private String placeKey() {
        return String.format(Locale.ROOT, "%.4f,%.4f", latitude, longitude);
    }

    /** Home is visible: refresh now if the last answer is older than 30 minutes, then every 30 minutes. */
    void start() {
        started = true;
        long age = System.currentTimeMillis() - prefs().getLong(KEY_AT, 0);
        handler.removeCallbacks(refresh);
        if (age >= REFRESH_MS || !hasData) refresh();
        else handler.postDelayed(refresh, REFRESH_MS - age);
    }

    /** Home left the screen: no fetching behind another app. */
    void stop() {
        started = false;
        handler.removeCallbacks(refresh);
    }

    void showShelf(float bottom) {
        this.bottom = bottom;
        shelf = true;
        place();
        update();
    }

    void hideShelf() {
        shelf = false;
        update();
    }

    /** The now playing card came or went; it takes the shelf while it shows. */
    void setBlocked(boolean blocked) {
        this.blocked = blocked;
        update();
    }

    private void refresh() {
        handler.removeCallbacks(refresh);
        if (started) handler.postDelayed(refresh, REFRESH_MS);
        if (fetching) return;
        fetching = true;
        String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,apparent_temperature,weather_code,is_day,precipitation"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&hourly=precipitation_probability&forecast_hours=12&forecast_days=1&timezone=auto", latitude, longitude);
        network.execute(() -> {
            String body = get(url);
            handler.post(() -> {
                fetching = false;
                if (body == null || !show(body)) return;   // keep the last good answer
                prefs().edit().putString(KEY_JSON, body).putLong(KEY_AT, System.currentTimeMillis())
                        .putString(KEY_PLACE, placeKey()).apply();
            });
        });
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Fills the card from an Open-Meteo answer; false when it does not parse. */
    private boolean show(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject now = json.getJSONObject("current");
            JSONObject daily = json.getJSONObject("daily");
            int code = now.getInt("weather_code");
            boolean day = now.optInt("is_day", 1) == 1;
            boolean wet = now.optDouble("precipitation", 0) > 0 || isWet(code);
            icon.setImageResource(icon(code, day));
            kicker.setText(context.getString(R.string.weather_kicker, city));
            temperature.setText(degrees(now.getDouble("temperature_2m")));
            String sky = context.getString(label(code));
            condition.setText(context.getString(R.string.weather_condition, sky,
                    context.getString(R.string.weather_feels, degrees(now.getDouble("apparent_temperature")))));
            String range = context.getString(R.string.weather_range, degrees(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                    degrees(daily.getJSONArray("temperature_2m_min").getDouble(0)));
            details.setText(context.getString(R.string.weather_details, range, rainLine(json, wet,
                    daily.getJSONArray("precipitation_probability_max").optInt(0, -1))));
            hasData = true;
            update();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** "Raining now", else "Rain likely around 4 PM" from the next 12 hours, else "Rain 20% today". */
    private String rainLine(JSONObject json, boolean wet, int todayChance) {
        if (wet) return context.getString(R.string.weather_raining);
        JSONObject hourly = json.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.optJSONArray("time"), chances = hourly.optJSONArray("precipitation_probability");
            if (times != null && chances != null) {
                for (int i = 0; i < Math.min(times.length(), chances.length()); i++) {
                    if (chances.optInt(i, 0) >= 50) {
                        // The first hour is the current one: "around 3 PM" at 3:20 PM would read oddly.
                        return i == 0 ? context.getString(R.string.weather_rain_soon)
                                : context.getString(R.string.weather_rain_at, hour(times.optString(i, "")));
                    }
                }
            }
        }
        return todayChance >= 0 ? context.getString(R.string.weather_rain_today, todayChance) : "";
    }

    /** "2026-10-24T16:00" as "4 PM". */
    private static String hour(String iso) {
        int t = iso.indexOf('T');
        if (t < 0 || iso.length() < t + 3) return iso;
        int h = Integer.parseInt(iso.substring(t + 1, t + 3));
        return (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " AM" : " PM");
    }

    private static String degrees(double celsius) {
        return Math.round(celsius) + "°";
    }

    private static boolean isWet(int code) {
        return code >= 51 && code <= 67 || code >= 80 && code <= 82 || code >= 95;
    }

    /** WMO weather codes as Open-Meteo sends them. */
    private static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (code <= 2) return day ? R.drawable.ic_wx_partly : R.drawable.ic_wx_moon;
        if (code == 3) return R.drawable.ic_wx_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if (code >= 95) return R.drawable.ic_wx_storm;
        if (isWet(code)) return R.drawable.ic_wx_rain;
        return R.drawable.ic_wx_cloud;
    }

    private static int label(int code) {
        if (code == 0) return R.string.weather_clear;
        if (code <= 2) return R.string.weather_partly;
        if (code == 3) return R.string.weather_cloudy;
        if (code == 45 || code == 48) return R.string.weather_fog;
        if (code >= 51 && code <= 57) return R.string.weather_drizzle;
        if (code == 65 || code == 67 || code == 82) return R.string.weather_heavy_rain;
        if (code >= 61 && code <= 67) return R.string.weather_rain;
        if (code >= 80 && code <= 82) return R.string.weather_showers;
        if (code >= 95) return R.string.weather_storm;
        return R.string.weather_cloudy;
    }

    private void update() {
        boolean show = shelf && !blocked && hasData;
        if (show == (view.getVisibility() == View.VISIBLE)) return;
        view.animate().cancel();
        if (show) {
            view.setAlpha(0f);
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).setDuration(200).start();
            place();
        } else {
            view.setVisibility(View.GONE);
        }
    }

    private void place() {
        if (bottom >= 0 && view.getHeight() > 0) view.setY(bottom - view.getHeight());
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
