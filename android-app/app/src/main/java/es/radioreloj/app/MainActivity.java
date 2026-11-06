package es.radioreloj.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pantalla de la app: una radio de válvulas dibujada en HTML (assets/ui/radio.html).
 * La página llama a {@link Bridge} para sintonizar, pausar, etc., y recibe el estado cada segundo.
 * Editar emisoras y los ajustes se hacen con diálogos normales de Android.
 */
public class MainActivity extends Activity implements RadioService.Listener {

    private static final String PAGE = "https://appassets.androidplatform.net/assets/ui/radio.html";
    private static final int CABINET_COLOR = 0xFF2C160B;

    private WebView web;
    private boolean pageReady;
    private StationStore store;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            pushState();
            ticker.postDelayed(this, 1000);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = StationStore.get(this);

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(CABINET_COLOR);
        web = new WebView(this);
        web.setBackgroundColor(CABINET_COLOR);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setTextZoom(100); // el dibujo de la radio está pensado a tamaño fijo

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // la página no navega a ningún otro sitio
            }
        });
        web.addJavascriptInterface(new Bridge(), "RadioApp");
        web.loadUrl(PAGE);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        startRadioService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        RadioService.addListener(this);
        startRadioService();
        ticker.post(tick);
    }

    @Override
    protected void onPause() {
        RadioService.removeListener(this);
        ticker.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onRadioChanged() {
        pushState();
    }

    private void startRadioService() {
        try {
            startForegroundService(new Intent(this, RadioService.class));
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo iniciar la radio: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private interface ServiceAction {
        void run(RadioService s);
    }

    private void withService(ServiceAction action) {
        RadioService s = RadioService.get();
        if (s == null) {
            startRadioService();
            Toast.makeText(this, "Encendiendo la radio, prueba otra vez en un segundo", Toast.LENGTH_SHORT).show();
            return;
        }
        action.run(s);
        pushState();
    }

    // ---------- estado hacia la página ----------

    private void pushState() {
        if (!pageReady || web == null) return;
        try {
            RadioService s = RadioService.get();
            JSONObject o = new JSONObject();
            Station current = s != null ? s.getCurrent() : store.lastOrFirst();
            o.put("active", s != null && s.isActive());
            o.put("state", s != null ? s.getState() : "idle");
            o.put("stationId", current != null ? current.id : "");
            o.put("stationName", current != null ? current.name : "");
            o.put("title", s != null ? s.getTitle() : "");
            o.put("error", s != null ? s.getError() : "");
            o.put("behind", s != null ? s.behindSeconds() : 0);
            o.put("recorded", s != null ? s.recordedSeconds() : 0);
            o.put("canSeek", s != null && s.canSeek());
            o.put("volume", s != null ? s.getVolume() : 0);
            o.put("maxVolume", s != null ? s.getMaxVolume() : 15);
            o.put("serverOk", s != null && s.isServerRunning());
            PowerManager pm = getSystemService(PowerManager.class);
            o.put("batteryOk", pm == null || pm.isIgnoringBatteryOptimizations(getPackageName()));
            JSONArray arr = new JSONArray();
            for (Station st : store.all()) {
                JSONObject j = new JSONObject();
                j.put("id", st.id);
                j.put("name", st.name);
                arr.put(j);
            }
            o.put("stations", arr);
            web.evaluateJavascript("window.radioUpdate && window.radioUpdate(" + JSONObject.quote(o.toString()) + ")", null);
        } catch (Exception ignored) {
        }
    }

    /** Lo que la página puede pedir. Se llama desde otro hilo: todo pasa al hilo principal. */
    private final class Bridge {
        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> {
                pageReady = true;
                pushState();
            });
        }

        @JavascriptInterface
        public void play(String id) {
            runOnUiThread(() -> {
                Station st = store.byId(id);
                if (st != null) withService(s -> s.play(st));
            });
        }

        @JavascriptInterface
        public void toggle() {
            runOnUiThread(() -> withService(RadioService::toggle));
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> withService(RadioService::stopPlayback));
        }

        @JavascriptInterface
        public void seek(int seconds) {
            runOnUiThread(() -> withService(s -> s.seekBy(seconds)));
        }

        @JavascriptInterface
        public void live() {
            runOnUiThread(() -> withService(RadioService::goLive));
        }

        @JavascriptInterface
        public void setVolume(int level) {
            runOnUiThread(() -> withService(s -> s.setVolume(level)));
        }

        @JavascriptInterface
        public void openStations() {
            runOnUiThread(MainActivity.this::showStationsMenu);
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(MainActivity.this::showSettingsMenu);
        }

        @JavascriptInterface
        public void fixBattery() {
            runOnUiThread(MainActivity.this::askIgnoreBatteryOptimizations);
        }
    }

    // ---------- emisoras ----------

    private void showStationsMenu() {
        List<Station> all = store.all();
        List<String> items = new ArrayList<>();
        for (Station s : all) items.add(s.name);
        items.add("+ Añadir emisora");
        new AlertDialog.Builder(this)
                .setTitle("Emisoras (toca una para editarla)")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == all.size()) showEditDialog(null);
                    else showStationOptions(all.get(which));
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void showStationOptions(Station station) {
        String[] items = {"Escuchar", "Editar", "Subir en el dial", "Bajar en el dial", "Eliminar"};
        new AlertDialog.Builder(this)
                .setTitle(station.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            withService(s -> s.play(station));
                            break;
                        case 1:
                            showEditDialog(station);
                            break;
                        case 2:
                            store.move(station.id, -1);
                            pushState();
                            break;
                        case 3:
                            store.move(station.id, 1);
                            pushState();
                            break;
                        default:
                            new AlertDialog.Builder(this)
                                    .setMessage("¿Quitar " + station.name + " del dial?")
                                    .setPositiveButton("Eliminar", (d2, w2) -> {
                                        store.remove(station.id);
                                        pushState();
                                    })
                                    .setNegativeButton("Cancelar", null)
                                    .show();
                    }
                })
                .show();
    }

    private void showEditDialog(Station station) {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText name = new EditText(this);
        name.setHint("Nombre (como se verá en el dial)");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(name);

        EditText urls = new EditText(this);
        urls.setHint("Dirección del streaming (una por línea; se prueban en orden)");
        urls.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        urls.setMinLines(3);
        box.addView(urls);

        if (station != null) {
            name.setText(station.name);
            urls.setText(String.join("\n", station.urls));
        }

        new AlertDialog.Builder(this)
                .setTitle(station == null ? "Nueva emisora" : "Editar emisora")
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> {
                    String n = name.getText().toString().trim();
                    List<String> list = new ArrayList<>();
                    for (String part : urls.getText().toString().split("\\s+")) {
                        String u = part.trim();
                        if (u.startsWith("http://") || u.startsWith("https://")) list.add(u);
                    }
                    if (n.isEmpty() || list.isEmpty()) {
                        Toast.makeText(this, "Hace falta un nombre y al menos una dirección http(s)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (station == null) store.add(n, list);
                    else store.update(station.id, n, list);
                    pushState();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- ajustes ----------

    private void showSettingsMenu() {
        RadioService s = RadioService.get();
        boolean keep = store.keepForWatch();
        String watch = s != null && s.isServerRunning()
                ? "Conexión con el reloj: lista"
                : "Conexión con el reloj: no disponible ahora";
        String[] items = {
                keep ? "Al cerrar la app: seguir esperando al reloj (cambiar)"
                        : "Al cerrar la app: apagarlo todo (cambiar)",
                "Quitar la optimización de batería",
                "Restaurar la lista de emisoras de fábrica",
                watch
        };
        new AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            chooseCloseBehaviour();
                            break;
                        case 1:
                            askIgnoreBatteryOptimizations();
                            break;
                        case 2:
                            confirmReset();
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void chooseCloseBehaviour() {
        String[] options = {
                "Apagarlo todo: deja de sonar y desaparece la notificación. El reloj no podrá encender la radio hasta que abras la app.",
                "Parar la radio pero seguir esperando al reloj: deja de sonar, pero queda una notificación y el reloj puede volver a encenderla."
        };
        int checked = store.keepForWatch() ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Al cerrar la app")
                .setSingleChoiceItems(options, checked, (d, which) -> {
                    store.setKeepForWatch(which == 1);
                    d.dismiss();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setMessage("Se perderán las emisoras que hayas añadido o editado. ¿Seguir?")
                .setPositiveButton("Restaurar", (d, w) -> {
                    store.resetToDefaults();
                    pushState();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void askIgnoreBatteryOptimizations() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}
