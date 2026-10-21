package com.alex.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Current weather from Open-Meteo (no key). The place is either the town set
 * in config.xml or a guess from the public IP; both are looked up once and
 * cached by the caller.
 */
final class Weather {

    static class Place {
        String name;
        double lat, lon;
    }

    static class Now {
        int temp;
        int code;
        boolean day;
    }

    private Weather() {
    }

    static Place locate(String town) throws Exception {
        Place p = new Place();
        if (town != null && !town.trim().isEmpty()) {
            // "Ronda, ES": an optional two-letter country code narrows the search
            String name = town.trim(), country = "";
            int comma = name.lastIndexOf(',');
            if (comma > 0 && name.substring(comma + 1).trim().length() == 2) {
                country = "&countryCode=" + name.substring(comma + 1).trim().toUpperCase(Locale.ROOT);
                name = name.substring(0, comma).trim();
            }
            JSONObject o = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=es" + country
                    + "&name=" + URLEncoder.encode(name, "UTF-8"));
            JSONArray r = o.getJSONArray("results");
            JSONObject first = r.getJSONObject(0);
            p.name = first.getString("name");
            p.lat = first.getDouble("latitude");
            p.lon = first.getDouble("longitude");
        } else {
            JSONObject o = get("https://ipapi.co/json/");
            p.name = o.getString("city");
            p.lat = o.getDouble("latitude");
            p.lon = o.getDouble("longitude");
        }
        return p;
    }

    static Now current(Place p) throws Exception {
        JSONObject o = get(String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                        + "&current=temperature_2m,weather_code,is_day&timezone=auto", p.lat, p.lon));
        JSONObject c = o.getJSONObject("current");
        Now n = new Now();
        n.temp = (int) Math.round(c.getDouble("temperature_2m"));
        n.code = c.getInt("weather_code");
        n.day = c.optInt("is_day", 1) == 1;
        return n;
    }

    /** WMO weather code to a short Spanish description. */
    static String describe(int code) {
        if (code == 0) return "Despejado";
        if (code <= 2) return "Poco nuboso";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Niebla";
        if (code >= 51 && code <= 57) return "Llovizna";
        if (code >= 61 && code <= 67) return "Lluvia";
        if (code >= 71 && code <= 77) return "Nieve";
        if (code >= 80 && code <= 82) return "Chubascos";
        if (code == 85 || code == 86) return "Chubascos de nieve";
        if (code >= 95) return "Tormenta";
        return "";
    }

    static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_w_clear : R.drawable.ic_w_night;
        if (code <= 2) return day ? R.drawable.ic_w_partly : R.drawable.ic_w_night;
        if (code == 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_w_snow;
        if (code >= 95) return R.drawable.ic_w_storm;
        return R.drawable.ic_w_rain;
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "tvhome-launcher");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            return new JSONObject(buf.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }
}
