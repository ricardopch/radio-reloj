package es.radioreloj.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import es.radioreloj.app.timeshift.TimeshiftSession;

/**
 * Servicio en primer plano que:
 *  - graba la emisora sintonizada (últimas 2 horas) y la reproduce desde esa grabación,
 *    para poder pausar, retroceder y volver al directo,
 *  - si el formato de una emisora no se puede grabar, la reproduce en directo sin más,
 *  - escucha las órdenes del reloj en http://127.0.0.1:8765,
 *  - publica una sesión multimedia, así el control de música nativo del reloj también la ve.
 */
public class RadioService extends Service {

    public static final int PORT = 8765;
    public static final String APP_VERSION = "1.1";
    public static final double TIMESHIFT_SECONDS = 2 * 60 * 60;

    public static final String ACTION_TOGGLE = "es.radioreloj.app.action.TOGGLE";
    public static final String ACTION_STOP = "es.radioreloj.app.action.STOP";
    public static final String ACTION_NEXT = "es.radioreloj.app.action.NEXT";
    public static final String ACTION_PREV = "es.radioreloj.app.action.PREV";
    public static final String ACTION_BACK30 = "es.radioreloj.app.action.BACK30";
    public static final String ACTION_PLAY = "es.radioreloj.app.action.PLAY";
    public static final String EXTRA_STATION_ID = "station_id";

    private static final String TAG = "RadioService";
    private static final String CHANNEL_ID = "radio";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_DIRECT_ROUNDS = 3;
    private static final int MAX_PLAYER_RETRIES = 8;
    /** Retraso por debajo del cual se considera "en directo". */
    private static final int LIVE_THRESHOLD_SECONDS = 5;

    public interface Listener {
        void onRadioChanged();
    }

    private static RadioService instance;
    private static final List<Listener> listeners = new ArrayList<>();

    private Handler main;
    private StationStore store;
    private AudioManager audio;
    private ExoPlayer player;
    private MediaSession session;
    private CommandServer server;
    private PowerManager.WakeLock recordingLock;

    private Station current;
    private boolean active;           // hay una emisora sintonizada (sonando o en pausa)
    private boolean directMode;       // reproduciendo sin grabar (formato no grabable)
    private boolean playerStarted;    // el reproductor ya tiene la grabación cargada
    private volatile TimeshiftSession recording;
    private volatile String recordingKey = "";
    private int playerRetries;
    private int urlIndex;
    private int directRound;
    private String liveTitle = "";
    private String playerTitle = "";
    private String error = "";

    private final Runnable directRetry = this::startDirectUrl;
    private final Runnable autoStop = () -> {
        Log.i(TAG, "Dos horas en pausa: se apaga la grabación");
        stopPlayback();
    };

    // ---------- acceso desde la pantalla de la app ----------

    public static RadioService get() {
        return instance;
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---------- ciclo de vida ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        main = new Handler(Looper.getMainLooper());
        store = StationStore.get(this);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        recordingLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioReloj:grabacion");
        recordingLock.setReferenceCounted(false);
        deleteRecordings(); // restos de una ejecución anterior
        createChannel();
        createSession();
        createPlayer();
        current = store.lastOrFirst();
        startForegroundSafely();

        server = new CommandServer(PORT, this::handleFromServerThread);
        server.start();
        main.postDelayed(this::notifyChanged, 500);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafely();
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_TOGGLE.equals(action)) toggle();
        else if (ACTION_STOP.equals(action)) stopPlayback();
        else if (ACTION_NEXT.equals(action)) skip(1);
        else if (ACTION_PREV.equals(action)) skip(-1);
        else if (ACTION_BACK30.equals(action)) seekBy(-30);
        else if (ACTION_PLAY.equals(action)) {
            Station s = store.byId(intent.getStringExtra(EXTRA_STATION_ID));
            if (s != null) play(s);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        main.removeCallbacksAndMessages(null);
        stopRecording();
        if (player != null) player.release();
        if (session != null) session.release();
        if (recordingLock.isHeld()) recordingLock.release();
        instance = null;
        for (Listener l : new ArrayList<>(listeners)) l.onRadioChanged();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- reproductor ----------

    private void createPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("RadioReloj/" + APP_VERSION + " (Android)")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000);
        DefaultDataSource.Factory dataSource = new DefaultDataSource.Factory(this, http);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSource))
                .setAudioAttributes(attrs, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    directRound = 0;
                    playerRetries = 0;
                    error = "";
                }
                notifyChanged();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && active) {
                    // Un directo no debería "terminar": se ha cortado.
                    onPlayerFailed(null);
                    return;
                }
                notifyChanged();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (!active) return;
                if (!playWhenReady && directMode
                        && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                        || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                    // Sin grabación no se puede pausar: se apaga.
                    stopPlayback();
                    return;
                }
                // En pausa la grabación sigue; si pasan 2 horas así, se apaga sola.
                main.removeCallbacks(autoStop);
                if (!playWhenReady && !directMode) main.postDelayed(autoStop, (long) (TIMESHIFT_SECONDS * 1000));
                notifyChanged();
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                Log.w(TAG, "Error de reproducción: " + e.getErrorCodeName(), e);
                onPlayerFailed(e);
            }

            @Override
            public void onMediaMetadataChanged(androidx.media3.common.MediaMetadata md) {
                String t = md.title != null ? md.title.toString().trim() : "";
                String a = md.artist != null ? md.artist.toString().trim() : "";
                if (!a.isEmpty() && !t.isEmpty() && !t.contains(a)) t = a + " - " + t;
                playerTitle = t;
                notifyChanged();
            }
        });
    }

    /** Sintoniza una emisora: empieza a grabarla y, en cuanto hay audio, a sonar. */
    public void play(Station station) {
        if (station == null) return;
        stopInternal();
        current = station;
        store.setLast(station.id);
        active = true;
        error = "";
        if (!recordingLock.isHeld()) recordingLock.acquire();

        String key = Long.toString(System.currentTimeMillis(), 36);
        File dir = new File(new File(getFilesDir(), "timeshift"), key);
        final TimeshiftSession[] self = new TimeshiftSession[1];
        TimeshiftSession rec = new TimeshiftSession(station.urls, dir, TIMESHIFT_SECONDS,
                new TimeshiftSession.Listener() {
                    @Override
                    public void onReady() {
                        main.post(() -> {
                            if (recording == self[0]) startRecordedPlayback();
                        });
                    }

                    @Override
                    public void onTitle(String title) {
                        main.post(() -> {
                            if (recording == self[0]) {
                                liveTitle = title;
                                notifyChanged();
                            }
                        });
                    }

                    @Override
                    public void onUnsupported(String reason) {
                        main.post(() -> {
                            if (recording == self[0]) {
                                Log.i(TAG, "Sin grabación para " + station.name + ": " + reason);
                                stopRecording();
                                startDirect();
                            }
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        main.post(() -> {
                            if (recording == self[0]) fail("No se pudo conectar con " + station.name);
                        });
                    }
                });
        self[0] = rec;
        recordingKey = key;
        recording = rec;
        rec.start();
        notifyChanged();
    }

    private String recordingUrl() {
        return "http://127.0.0.1:" + PORT + "/ts/" + recordingKey + "/index.m3u8";
    }

    private void startRecordedPlayback() {
        if (!active || directMode) return;
        MediaItem item = new MediaItem.Builder()
                .setUri(recordingUrl())
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setMinPlaybackSpeed(1f)
                        .setMaxPlaybackSpeed(1f)
                        .build())
                .build();
        player.setMediaItem(item);
        player.prepare();
        player.play();
        playerStarted = true;
        notifyChanged();
    }

    private void startDirect() {
        if (!active || current == null) return;
        directMode = true;
        urlIndex = 0;
        directRound = 0;
        startDirectUrl();
    }

    private void startDirectUrl() {
        if (!active || !directMode || current == null) return;
        String url = current.urls.get(urlIndex);
        Log.i(TAG, "Directo sin grabación: " + current.name + " -> " + url);
        player.stop();
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        playerStarted = true;
        notifyChanged();
    }

    private void onPlayerFailed(PlaybackException e) {
        if (!active || current == null) return;
        if (directMode) {
            main.removeCallbacks(directRetry);
            urlIndex++;
            if (urlIndex < current.urls.size()) {
                main.postDelayed(directRetry, 500);
            } else if (directRound + 1 < MAX_DIRECT_ROUNDS) {
                directRound++;
                urlIndex = 0;
                main.postDelayed(directRetry, 3000L * directRound);
            } else {
                fail("No se pudo conectar con " + current.name);
            }
            notifyChanged();
            return;
        }
        // Reproduciendo la grabación: el grabador se reconecta solo; aquí solo se recarga la lista.
        if (e != null && e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        if (++playerRetries > MAX_PLAYER_RETRIES) {
            fail("Se ha perdido la conexión con " + current.name);
            return;
        }
        main.postDelayed(() -> {
            if (active && !directMode && playerStarted) player.prepare();
        }, 2000);
        notifyChanged();
    }

    private void fail(String message) {
        stopInternal();
        error = message;
        notifyChanged();
    }

    /** Pausa: el sonido se para pero la emisora se sigue grabando. */
    public void pause() {
        if (!active) return;
        if (directMode || !playerStarted) {
            stopPlayback();
            return;
        }
        player.pause();
        notifyChanged();
    }

    public void resume() {
        if (!active) {
            play(current != null ? current : store.lastOrFirst());
            return;
        }
        if (playerStarted) player.play();
        notifyChanged();
    }

    public void toggle() {
        if (!active) resume();
        else if (isPaused()) resume();
        else pause();
    }

    /** Apaga del todo: deja de grabar y borra lo grabado. */
    public void stopPlayback() {
        stopInternal();
        error = "";
        notifyChanged();
    }

    private void stopInternal() {
        main.removeCallbacks(directRetry);
        main.removeCallbacks(autoStop);
        active = false;
        directMode = false;
        playerStarted = false;
        playerRetries = 0;
        liveTitle = "";
        playerTitle = "";
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        stopRecording();
        if (recordingLock.isHeld()) recordingLock.release();
    }

    private void stopRecording() {
        TimeshiftSession rec = recording;
        recording = null;
        recordingKey = "";
        if (rec != null) new Thread(rec::stop, "radio-reloj-borrado").start();
    }

    /** Borra grabaciones que hayan quedado de una ejecución anterior (sin bloquear la pantalla). */
    private void deleteRecordings() {
        File root = new File(getFilesDir(), "timeshift");
        File trash = new File(getFilesDir(), "timeshift-borrar-" + System.currentTimeMillis());
        if (root.exists() && !root.renameTo(trash)) trash = root;
        File[] old = getFilesDir().listFiles((dir, name) -> name.startsWith("timeshift-borrar-"));
        List<File> toDelete = new ArrayList<>();
        if (old != null) for (File f : old) toDelete.add(f);
        if (trash != root) toDelete.add(trash);
        new Thread(() -> {
            for (File f : toDelete) deleteRecursively(f);
        }, "radio-reloj-limpieza").start();
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public void skip(int delta) {
        List<Station> list = store.all();
        if (list.isEmpty()) return;
        int i = current != null ? store.indexOf(current.id) : -1;
        int next = i < 0 ? 0 : ((i + delta) % list.size() + list.size()) % list.size();
        play(list.get(next));
    }

    /** Mueve la reproducción: negativo = atrás, positivo = adelante (sin pasar del directo). */
    public void seekBy(int seconds) {
        if (!canSeek()) return;
        long target = player.getCurrentPosition() + seconds * 1000L;
        if (seconds > 0 && behindSeconds() - seconds <= LIVE_THRESHOLD_SECONDS) {
            goLive();
            return;
        }
        player.seekTo(Math.max(0, target));
        notifyChanged();
    }

    public void goLive() {
        if (!canSeek()) return;
        player.seekToDefaultPosition();
        player.play();
        notifyChanged();
    }

    public void changeVolume(boolean up) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        notifyChanged();
    }

    // ---------- estado ----------

    /** idle | buffering | playing | paused | error */
    public String getState() {
        if (!active) return error.isEmpty() ? "idle" : "error";
        if (!playerStarted) return "buffering";
        if (!player.getPlayWhenReady()) return "paused";
        return player.isPlaying() ? "playing" : "buffering";
    }

    public boolean isPaused() {
        return "paused".equals(getState());
    }

    public boolean isActive() {
        return active;
    }

    public boolean canSeek() {
        return active && !directMode && playerStarted && player.isCurrentMediaItemSeekable();
    }

    /** Segundos de retraso respecto al directo (0 = en directo). */
    public int behindSeconds() {
        TimeshiftSession rec = recording;
        if (!canSeek() || rec == null) return 0;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET) return 0;
        long liveEdgeMs = Math.round(3 * rec.store().maxSegmentSeconds() * 1000);
        long behind = duration - player.getCurrentPosition() - liveEdgeMs;
        int s = (int) Math.max(0, behind / 1000);
        return s <= LIVE_THRESHOLD_SECONDS ? 0 : s;
    }

    /** Segundos grabados disponibles para retroceder. */
    public int recordedSeconds() {
        TimeshiftSession rec = recording;
        return rec == null || directMode ? 0 : (int) rec.store().recordedSeconds();
    }

    public boolean isRecording() {
        return active && !directMode;
    }

    public Station getCurrent() {
        return current;
    }

    /** Lo que está sonando. El título "en vivo" solo se muestra si se escucha el directo. */
    public String getTitle() {
        if (!playerTitle.isEmpty()) return playerTitle;
        return behindSeconds() == 0 ? liveTitle : "";
    }

    public String getError() {
        return error;
    }

    public int getVolume() {
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    public int getMaxVolume() {
        return audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    public boolean isServerRunning() {
        return server != null && server.isRunning();
    }

    public String getServerError() {
        return server != null ? server.getLastError() : "";
    }

    public static String formatDuration(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    private void notifyChanged() {
        updateSession();
        updateNotification();
        for (Listener l : new ArrayList<>(listeners)) l.onRadioChanged();
    }

    // ---------- servidor local: órdenes del reloj y grabación ----------

    /** Llamado desde los hilos del servidor. */
    private CommandServer.Response handleFromServerThread(String path, Map<String, String> query) throws Exception {
        if (path.startsWith("/ts/")) return serveRecording(path.substring(4));
        FutureTask<String> task = new FutureTask<>(() -> handleApi(path, query).toString());
        main.post(task);
        return CommandServer.Response.json(task.get(5, TimeUnit.SECONDS));
    }

    private CommandServer.Response serveRecording(String rest) {
        TimeshiftSession rec = recording;
        int slash = rest.indexOf('/');
        if (rec == null || slash < 0 || !rest.substring(0, slash).equals(recordingKey)) {
            return CommandServer.Response.notFound();
        }
        String name = rest.substring(slash + 1);
        if (name.equals("index.m3u8")) {
            return CommandServer.Response.text(rec.store().playlist(), "application/vnd.apple.mpegurl");
        }
        File f = rec.store().fileFor(name);
        if (f == null) return CommandServer.Response.notFound();
        String type = name.endsWith(".aac") ? "audio/aac"
                : name.endsWith(".mp3") ? "audio/mpeg"
                : name.endsWith(".ts") ? "video/mp2t"
                : "audio/mp4";
        return CommandServer.Response.file(f, type);
    }

    private JSONObject handleApi(String path, Map<String, String> q) throws JSONException {
        switch (path) {
            case "/api/ping": {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("app", "RadioReloj");
                o.put("version", APP_VERSION);
                return o;
            }
            case "/api/stations": {
                JSONArray arr = new JSONArray();
                for (Station s : store.all()) {
                    JSONObject o = new JSONObject();
                    o.put("id", s.id);
                    o.put("name", s.name);
                    arr.put(o);
                }
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("stations", arr);
                return o;
            }
            case "/api/status":
                return statusJson();
            case "/api/play": {
                String id = q.get("id");
                Station s = id != null && !id.isEmpty() ? store.byId(id) : (current != null ? current : store.lastOrFirst());
                if (s == null) return errorJson("Emisora no encontrada");
                if (active && current != null && current.id.equals(s.id)) resume(); // ya sintonizada
                else play(s);
                return statusJson();
            }
            case "/api/pause":
                pause();
                return statusJson();
            case "/api/resume":
                resume();
                return statusJson();
            case "/api/toggle":
                toggle();
                return statusJson();
            case "/api/stop":
                stopPlayback();
                return statusJson();
            case "/api/next":
                skip(1);
                return statusJson();
            case "/api/prev":
                skip(-1);
                return statusJson();
            case "/api/seek": {
                int by;
                try {
                    by = Integer.parseInt(q.get("by"));
                } catch (Exception e) {
                    return errorJson("Falta 'by' (segundos)");
                }
                seekBy(by);
                return statusJson();
            }
            case "/api/live":
                goLive();
                return statusJson();
            case "/api/volume":
                changeVolume(!"down".equals(q.get("dir")));
                return statusJson();
            default:
                return errorJson("Ruta desconocida: " + path);
        }
    }

    private JSONObject statusJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("state", getState());
        o.put("stationId", current != null ? current.id : "");
        o.put("stationName", current != null ? current.name : "");
        o.put("title", getTitle());
        o.put("error", error);
        o.put("volume", getVolume());
        o.put("maxVolume", getMaxVolume());
        o.put("canSeek", canSeek());
        o.put("behind", behindSeconds());
        o.put("recorded", recordedSeconds());
        return o;
    }

    private static JSONObject errorJson(String message) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("error", message);
        return o;
    }

    // ---------- sesión multimedia y notificación ----------

    private void createSession() {
        session = new MediaSession(this, "RadioReloj");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                resume();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                stopPlayback();
            }

            @Override
            public void onSkipToNext() {
                skip(1);
            }

            @Override
            public void onSkipToPrevious() {
                skip(-1);
            }

            @Override
            public void onRewind() {
                seekBy(-30);
            }

            @Override
            public void onFastForward() {
                seekBy(30);
            }
        });
        Intent open = new Intent(this, MainActivity.class);
        session.setSessionActivity(PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        session.setActive(true);
    }

    private void updateSession() {
        if (session == null) return;
        String state = getState();
        int pbState;
        switch (state) {
            case "playing":
                pbState = PlaybackState.STATE_PLAYING;
                break;
            case "buffering":
                pbState = PlaybackState.STATE_BUFFERING;
                break;
            case "error":
                pbState = PlaybackState.STATE_ERROR;
                break;
            default:
                pbState = PlaybackState.STATE_PAUSED;
        }
        PlaybackState.Builder pb = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD)
                .setState(pbState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f);
        if (pbState == PlaybackState.STATE_ERROR) pb.setErrorMessage(error);
        session.setPlaybackState(pb.build());

        String stationName = current != null ? current.name : getString(R.string.app_name);
        String title = getTitle();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title.isEmpty() ? stationName : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, stationName)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, getString(R.string.app_name))
                .build());
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Radio",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Reproducción y estado de la conexión con el reloj");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent i = new Intent(this, RadioService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action action(int icon, String label, String intentAction, int requestCode) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label,
                serviceIntent(intentAction, requestCode)).build();
    }

    private Notification buildNotification() {
        String state = getState();
        String stationName = current != null ? current.name : getString(R.string.app_name);
        int behind = behindSeconds();
        String text;
        switch (state) {
            case "playing":
                text = behind > 0 ? "En diferido (" + formatDuration(behind) + " de retraso)"
                        : (getTitle().isEmpty() ? "En directo" : getTitle());
                break;
            case "paused":
                text = "En pausa · se sigue grabando";
                break;
            case "buffering":
                text = "Conectando…";
                break;
            case "error":
                text = error;
                break;
            default:
                text = "Parada · esperando órdenes del reloj";
        }

        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        boolean playingNow = "playing".equals(state) || "buffering".equals(state);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(stationName)
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(R.drawable.ic_prev, "Anterior", ACTION_PREV, 1))
                .addAction(action(R.drawable.ic_rewind, "−30 s", ACTION_BACK30, 2))
                .addAction(action(playingNow ? R.drawable.ic_pause : R.drawable.ic_play,
                        playingNow ? "Pausa" : "Sonar", ACTION_TOGGLE, 3))
                .addAction(action(R.drawable.ic_next, "Siguiente", ACTION_NEXT, 4))
                .addAction(action(R.drawable.ic_close, "Apagar", ACTION_STOP, 5))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(1, 2, 3))
                .build();
    }

    private void startForegroundSafely() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            // Android puede negarse si el sistema intenta reiniciar el servicio en segundo plano.
            Log.w(TAG, "No se pudo pasar a primer plano; abre la app para reactivarlo", e);
            stopSelf();
        }
    }

    private void updateNotification() {
        if (session == null) return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }
}
