package es.radioreloj.app;

import android.Manifest;
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
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Pantalla de la app: estado, controles de prueba y edición de la lista de emisoras. */
public class MainActivity extends Activity implements RadioService.Listener {

    private TextView txtServer;
    private TextView txtBattery;
    private TextView txtStation;
    private TextView txtState;
    private TextView txtTitle;
    private TextView txtInfo;
    private Button btnToggle;
    private String listSignature = "";
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ticker.postDelayed(this, 1000);
        }
    };
    private ArrayAdapter<String> adapter;
    private final List<Station> shown = new ArrayList<>();
    private StationStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = StationStore.get(this);

        txtServer = findViewById(R.id.txtServer);
        txtBattery = findViewById(R.id.txtBattery);
        txtStation = findViewById(R.id.txtStation);
        txtState = findViewById(R.id.txtState);
        txtTitle = findViewById(R.id.txtTitle);
        txtInfo = findViewById(R.id.txtInfo);
        btnToggle = findViewById(R.id.btnToggle);

        findViewById(R.id.btnPrev).setOnClickListener(v -> withService(s -> s.skip(-1)));
        findViewById(R.id.btnNext).setOnClickListener(v -> withService(s -> s.skip(1)));
        btnToggle.setOnClickListener(v -> withService(RadioService::toggle));
        findViewById(R.id.btnVolDown).setOnClickListener(v -> withService(s -> s.changeVolume(false)));
        findViewById(R.id.btnVolUp).setOnClickListener(v -> withService(s -> s.changeVolume(true)));
        findViewById(R.id.btnStop).setOnClickListener(v -> withService(RadioService::stopPlayback));
        findViewById(R.id.btnBack5m).setOnClickListener(v -> withService(s -> s.seekBy(-300)));
        findViewById(R.id.btnBack30).setOnClickListener(v -> withService(s -> s.seekBy(-30)));
        findViewById(R.id.btnFwd30).setOnClickListener(v -> withService(s -> s.seekBy(30)));
        findViewById(R.id.btnLive).setOnClickListener(v -> withService(RadioService::goLive));
        findViewById(R.id.btnMore).setOnClickListener(v -> showMoreMenu());
        txtBattery.setOnClickListener(v -> askIgnoreBatteryOptimizations());

        ListView list = findViewById(R.id.listStations);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Station s = shown.get(position);
            withService(svc -> svc.play(s));
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showStationOptions(shown.get(position));
            return true;
        });

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
        ticker.post(tick); // el retraso y lo grabado cambian cada segundo
    }

    @Override
    protected void onPause() {
        RadioService.removeListener(this);
        ticker.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onRadioChanged() {
        refresh();
    }

    private void startRadioService() {
        try {
            startForegroundService(new Intent(this, RadioService.class));
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo iniciar el servicio: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private interface ServiceAction {
        void run(RadioService s);
    }

    private void withService(ServiceAction action) {
        RadioService s = RadioService.get();
        if (s == null) {
            startRadioService();
            Toast.makeText(this, "Iniciando el servicio, prueba otra vez en un segundo", Toast.LENGTH_SHORT).show();
            return;
        }
        action.run(s);
    }

    // ---------- pintar la pantalla ----------

    private void refresh() {
        RadioService s = RadioService.get();

        if (s == null) {
            txtServer.setText("Servicio detenido");
        } else if (s.isServerRunning()) {
            txtServer.setText("Conexión con el reloj: lista (127.0.0.1:" + RadioService.PORT + ")");
        } else {
            String err = s.getServerError();
            txtServer.setText(err.isEmpty() ? "Abriendo conexión con el reloj…" : err);
        }

        PowerManager pm = getSystemService(PowerManager.class);
        boolean unrestricted = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        txtBattery.setVisibility(unrestricted ? View.GONE : View.VISIBLE);

        Station current = s != null ? s.getCurrent() : store.lastOrFirst();
        String state = s != null ? s.getState() : "idle";
        int behind = s != null ? s.behindSeconds() : 0;
        txtStation.setText(current != null ? current.name : "Sin emisora");
        switch (state) {
            case "playing":
                txtState.setText(behind > 0
                        ? "En diferido · " + RadioService.formatDuration(behind) + " de retraso"
                        : "En directo");
                break;
            case "paused":
                txtState.setText("En pausa · " + RadioService.formatDuration(behind) + " de retraso");
                break;
            case "buffering":
                txtState.setText("Conectando con la emisora…");
                break;
            case "error":
                txtState.setText(s.getError());
                break;
            default:
                txtState.setText("Parada");
        }

        String info = "";
        if (s != null) {
            String volume = "volumen " + s.getVolume() + "/" + s.getMaxVolume();
            if (s.isRecording()) {
                info = "Grabado: " + RadioService.formatDuration(s.recordedSeconds()) + " (máximo 2 horas) · " + volume;
            } else if (s.isActive()) {
                info = "Esta emisora no permite pausar ni retroceder · " + volume;
            } else {
                info = volume;
            }
        }
        txtInfo.setText(info);

        String title = s != null ? s.getTitle() : "";
        txtTitle.setText(title);
        txtTitle.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);
        boolean active = s != null && s.isActive();
        btnToggle.setText(!active ? "Sonar" : "paused".equals(state) ? "Seguir" : "Pausa");
        boolean canSeek = s != null && s.canSeek();
        findViewById(R.id.btnBack5m).setEnabled(canSeek);
        findViewById(R.id.btnBack30).setEnabled(canSeek);
        findViewById(R.id.btnFwd30).setEnabled(canSeek && behind > 0);
        findViewById(R.id.btnLive).setEnabled(canSeek && (behind > 0 || "paused".equals(state)));

        // La lista solo se repinta si ha cambiado algo
        List<Station> all = store.all();
        StringBuilder sig = new StringBuilder(active && current != null ? current.id : "-");
        for (Station st : all) sig.append('|').append(st.id).append('=').append(st.name);
        if (sig.toString().equals(listSignature)) return;
        listSignature = sig.toString();
        shown.clear();
        shown.addAll(all);
        List<String> names = new ArrayList<>();
        for (Station st : shown) {
            boolean isCurrent = active && current != null && st.id.equals(current.id);
            names.add((isCurrent ? "▶  " : "") + st.name);
        }
        adapter.clear();
        adapter.addAll(names);
        adapter.notifyDataSetChanged();
    }

    // ---------- menús y diálogos ----------

    private void showMoreMenu() {
        String[] items = {
                "Añadir emisora",
                "Restaurar la lista de fábrica",
                "Quitar la optimización de batería",
                "Detener el servicio y salir"
        };
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            showEditDialog(null);
                            break;
                        case 1:
                            confirmReset();
                            break;
                        case 2:
                            askIgnoreBatteryOptimizations();
                            break;
                        default:
                            stopService(new Intent(this, RadioService.class));
                            finishAndRemoveTask();
                    }
                })
                .show();
    }

    private void showStationOptions(Station station) {
        String[] items = {"Editar", "Subir", "Bajar", "Eliminar"};
        new AlertDialog.Builder(this)
                .setTitle(station.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            showEditDialog(station);
                            break;
                        case 1:
                            store.move(station.id, -1);
                            refresh();
                            break;
                        case 2:
                            store.move(station.id, 1);
                            refresh();
                            break;
                        default:
                            new AlertDialog.Builder(this)
                                    .setMessage("¿Eliminar " + station.name + " de la lista?")
                                    .setPositiveButton("Eliminar", (d2, w2) -> {
                                        store.remove(station.id);
                                        refresh();
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
        name.setHint("Nombre (como se verá en el reloj)");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(name);

        EditText urls = new EditText(this);
        urls.setHint("URL del streaming (una por línea; se prueban en orden)");
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
                    for (String line : urls.getText().toString().split("\\s+")) {
                        String u = line.trim();
                        if (u.startsWith("http://") || u.startsWith("https://")) list.add(u);
                    }
                    if (n.isEmpty() || list.isEmpty()) {
                        Toast.makeText(this, "Hace falta un nombre y al menos una URL http(s)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (station == null) store.add(n, list);
                    else store.update(station.id, n, list);
                    refresh();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setMessage("Se perderán las emisoras que hayas añadido o editado. ¿Seguir?")
                .setPositiveButton("Restaurar", (d, w) -> {
                    store.resetToDefaults();
                    refresh();
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
