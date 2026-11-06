package es.radioreloj.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Una emisora: nombre visible y una o varias URLs de streaming (se prueban en orden). */
public final class Station {
    public final String id;
    public final String name;
    public final List<String> urls;

    public Station(String id, String name, List<String> urls) {
        this.id = id;
        this.name = name;
        this.urls = Collections.unmodifiableList(new ArrayList<>(urls));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        JSONArray arr = new JSONArray();
        for (String u : urls) arr.put(u);
        o.put("urls", arr);
        return o;
    }

    public static Station fromJson(JSONObject o) {
        String id = o.optString("id", "").trim();
        String name = o.optString("name", "").trim();
        List<String> urls = new ArrayList<>();
        JSONArray arr = o.optJSONArray("urls");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "").trim();
                if (!u.isEmpty()) urls.add(u);
            }
        }
        if (id.isEmpty() || name.isEmpty() || urls.isEmpty()) return null;
        return new Station(id, name, urls);
    }
}
