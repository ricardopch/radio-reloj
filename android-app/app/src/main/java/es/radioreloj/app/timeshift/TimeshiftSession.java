package es.radioreloj.app.timeshift;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Graba la emisora sintonizada en segundo plano (hasta {@code maxSeconds}) para poder pausar y
 * retroceder. Prueba las URLs de la emisora en orden y se reconecta sola si se corta.
 */
public final class TimeshiftSession {

    public interface Listener {
        /** Ya hay audio suficiente para empezar a reproducir. */
        void onReady();

        /** Título de la canción o programa (si la emisora lo envía). */
        void onTitle(String title);

        /** Ninguna URL tiene un formato grabable: reproducir solo en directo. */
        void onUnsupported(String reason);

        /** No se ha podido conectar con ninguna URL. */
        void onFailed(String reason);
    }

    private static final int READY_SEGMENTS = 2;

    private final List<String> urls;
    private final SegmentStore store;
    private final Listener listener;
    private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
    private volatile boolean running;
    private volatile boolean ready;
    private Thread thread;

    public TimeshiftSession(List<String> urls, File dir, double maxSeconds, Listener listener) {
        this.urls = new ArrayList<>(urls);
        this.store = new SegmentStore(dir, maxSeconds);
        this.listener = listener;
    }

    public SegmentStore store() {
        return store;
    }

    public boolean isReady() {
        return ready;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "radio-reloj-grabador");
        thread.setDaemon(true);
        thread.start();
    }

    /** Para la grabación y borra lo grabado. */
    public synchronized void stop() {
        running = false;
        HttpURLConnection c = connection.get();
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
        if (thread != null) thread.interrupt();
        store.clear();
    }

    private void run() {
        int index = 0;
        int failuresWithoutData = 0;
        long backoff = 2000;
        Set<Integer> unsupported = new HashSet<>();
        String lastError = "";

        ProgressiveRecorder.Callbacks pCallbacks = new ProgressiveRecorder.Callbacks() {
            @Override
            public boolean running() {
                return running;
            }

            @Override
            public void onTitle(String title) {
                if (running) listener.onTitle(title);
            }

            @Override
            public void onSegmentAdded() {
                checkReady();
            }
        };
        HlsRecorder.Callbacks hCallbacks = new HlsRecorder.Callbacks() {
            @Override
            public boolean running() {
                return running;
            }

            @Override
            public void onSegmentAdded() {
                checkReady();
            }
        };

        while (running) {
            String url = urls.get(index);
            long before = store.totalAdded();
            try {
                try {
                    if (looksLikeHls(url)) new HlsRecorder(store, hCallbacks, connection).record(url);
                    else new ProgressiveRecorder(store, pCallbacks, connection).record(url);
                } catch (Http.IsHlsException e) {
                    new HlsRecorder(store, hCallbacks, connection).record(e.url);
                }
                lastError = "La emisión se ha cortado";
            } catch (Http.UnsupportedStreamException e) {
                log("No se puede grabar " + url + ": " + e.getMessage());
                unsupported.add(index);
                lastError = e.getMessage();
                if (unsupported.size() >= urls.size()) {
                    if (running) listener.onUnsupported(e.getMessage());
                    return;
                }
            } catch (IOException | RuntimeException e) {
                if (!running) return;
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                log("Error grabando " + url + ": " + lastError);
            }
            if (!running) return;

            boolean gotData = store.totalAdded() > before;
            store.markDiscontinuity();
            if (gotData) {
                failuresWithoutData = 0;
                backoff = 2000;
            } else {
                failuresWithoutData++;
                index = nextUsable(index, unsupported);
                if (!ready && failuresWithoutData >= 2 * urls.size()) {
                    listener.onFailed(lastError);
                    return;
                }
            }
            try {
                // Mientras no ha empezado a sonar se reintenta rápido; después, cada vez más espaciado.
                Thread.sleep(ready ? backoff : 1500);
            } catch (InterruptedException e) {
                return;
            }
            if (ready) backoff = Math.min(backoff * 2, 30_000);
        }
    }

    private int nextUsable(int index, Set<Integer> unsupported) {
        for (int i = 1; i <= urls.size(); i++) {
            int candidate = (index + i) % urls.size();
            if (!unsupported.contains(candidate)) return candidate;
        }
        return index;
    }

    private void checkReady() {
        if (!ready && running && store.size() >= READY_SEGMENTS) {
            ready = true;
            listener.onReady();
        }
    }

    static boolean looksLikeHls(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        return u.endsWith(".m3u8");
    }

    private static void log(String message) {
        System.out.println("RadioReloj grabador: " + message);
    }
}
