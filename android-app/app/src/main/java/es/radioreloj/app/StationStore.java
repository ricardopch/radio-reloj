package es.radioreloj.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Lista de emisoras guardada en el móvil (la de fábrica viene en assets/stations.json). */
public final class StationStore {
    private static final String TAG = "StationStore";
    private static final String PREFS = "radio_reloj";
    private static final String KEY_STATIONS = "stations_json";
    private static final String KEY_LAST = "last_station_id";
    private static final String KEY_KEEP_FOR_WATCH = "keep_for_watch";

    private static StationStore instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final List<Station> stations = new ArrayList<>();

    public static synchronized StationStore get(Context context) {
        if (instance == null) instance = new StationStore(context.getApplicationContext());
        return instance;
    }

    private StationStore(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_STATIONS, null);
        List<Station> loaded = saved != null ? parse(saved) : new ArrayList<>();
        if (loaded.isEmpty()) loaded = loadDefaults();
        stations.addAll(loaded);
    }

    public synchronized List<Station> all() {
        return new ArrayList<>(stations);
    }

    public synchronized Station byId(String id) {
        if (id == null) return null;
        for (Station s : stations) if (s.id.equals(id)) return s;
        return null;
    }

    public synchronized int indexOf(String id) {
        for (int i = 0; i < stations.size(); i++) if (stations.get(i).id.equals(id)) return i;
        return -1;
    }

    public synchronized Station lastOrFirst() {
        Station last = byId(prefs.getString(KEY_LAST, null));
        if (last != null) return last;
        return stations.isEmpty() ? null : stations.get(0);
    }

    public synchronized void setLast(String id) {
        prefs.edit().putString(KEY_LAST, id).apply();
    }

    /** true: al cerrar la app se para la radio pero se sigue esperando al reloj. */
    public synchronized boolean keepForWatch() {
        return prefs.getBoolean(KEY_KEEP_FOR_WATCH, false);
    }

    public synchronized void setKeepForWatch(boolean keep) {
        prefs.edit().putBoolean(KEY_KEEP_FOR_WATCH, keep).apply();
    }

    public synchronized void add(String name, List<String> urls) {
        String id = "u" + System.currentTimeMillis();
        stations.add(new Station(id, name, urls));
        save();
    }

    public synchronized void update(String id, String name, List<String> urls) {
        int i = indexOf(id);
        if (i < 0) return;
        stations.set(i, new Station(id, name, urls));
        save();
    }

    public synchronized void remove(String id) {
        int i = indexOf(id);
        if (i < 0) return;
        stations.remove(i);
        save();
    }

    public synchronized void move(String id, int delta) {
        int i = indexOf(id);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= stations.size()) return;
        Station s = stations.remove(i);
        stations.add(j, s);
        save();
    }

    public synchronized void resetToDefaults() {
        stations.clear();
        stations.addAll(loadDefaults());
        prefs.edit().remove(KEY_STATIONS).apply();
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Station s : stations) arr.put(s.toJson());
            prefs.edit().putString(KEY_STATIONS, arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "No se pudo guardar la lista", e);
        }
    }

    private List<Station> loadDefaults() {
        try (InputStream in = context.getAssets().open("stations.json")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            JSONObject root = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            return parse(root.getJSONArray("stations").toString());
        } catch (Exception e) {
            Log.e(TAG, "No se pudo leer la lista de fábrica", e);
            return new ArrayList<>();
        }
    }

    private static List<Station> parse(String json) {
        List<Station> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Station s = Station.fromJson(o);
                if (s != null) out.add(s);
            }
        } catch (Exception e) {
            Log.e(TAG, "Lista de emisoras dañada", e);
        }
        return out;
    }
}
