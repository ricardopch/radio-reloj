package es.radioreloj.app.timeshift;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Graba una emisora que se emite en HLS copiando sus segmentos a medida que aparecen. */
final class HlsRecorder {

    interface Callbacks {
        boolean running();

        void onSegmentAdded();
    }

    static final class Segment {
        long seq;
        String uri;
        double duration;
        boolean discontinuity;
        String mapUri;
    }

    static final class MediaPlaylist {
        long mediaSequence;
        double targetDuration = 6;
        boolean endList;
        final List<Segment> segments = new ArrayList<>();
    }

    private static final Set<String> KNOWN_EXTENSIONS = new HashSet<>(Arrays.asList(
            "ts", "aac", "mp3", "m4s", "mp4", "m4a", "ac3", "ec3", "cmfa", "cmfv"));
    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern CODECS = Pattern.compile("CODECS=\"([^\"]*)\"");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");
    private static final Pattern METHOD = Pattern.compile("METHOD=([A-Z0-9-]+)");

    private final SegmentStore store;
    private final Callbacks callbacks;
    private final AtomicReference<HttpURLConnection> connection;

    HlsRecorder(SegmentStore store, Callbacks callbacks, AtomicReference<HttpURLConnection> connection) {
        this.store = store;
        this.callbacks = callbacks;
        this.connection = connection;
    }

    void record(String url) throws IOException {
        String[] finalUrl = new String[1];
        String text = Http.getText(url, connection, finalUrl);
        String mediaUrl = finalUrl[0];
        if (!text.trim().startsWith("#EXTM3U")) throw new Http.UnsupportedStreamException("No es una lista HLS");
        if (text.contains("#EXT-X-STREAM-INF")) {
            mediaUrl = chooseVariant(text, mediaUrl);
            text = Http.getText(mediaUrl, connection, finalUrl);
            mediaUrl = finalUrl[0];
        }

        long lastSeq = -1;
        String lastMap = null;
        while (callbacks.running()) {
            MediaPlaylist pl = parseMedia(text, mediaUrl);
            List<Segment> todo = new ArrayList<>();
            if (!pl.segments.isEmpty() && lastSeq >= 0
                    && pl.segments.get(pl.segments.size() - 1).seq < lastSeq - 3) {
                // La emisora ha reiniciado la numeración: empezar de nuevo
                store.markDiscontinuity();
                lastSeq = -1;
            }
            if (lastSeq < 0) {
                int from = Math.max(0, pl.segments.size() - 3);
                todo.addAll(pl.segments.subList(from, pl.segments.size()));
            } else {
                for (Segment s : pl.segments) if (s.seq > lastSeq) todo.add(s);
                if (!todo.isEmpty() && todo.get(0).seq > lastSeq + 1) store.markDiscontinuity();
            }

            double pending = 0;
            for (Segment s : todo) pending += s.duration;
            long now = System.currentTimeMillis();
            for (Segment s : todo) {
                if (!callbacks.running()) return;
                if (s.mapUri != null && !s.mapUri.equals(lastMap)) {
                    store.setMap(Http.get(s.mapUri, connection), extension(s.mapUri, "mp4"));
                    lastMap = s.mapUri;
                }
                if (s.discontinuity && lastSeq >= 0) store.markDiscontinuity();
                byte[] data = Http.get(s.uri, connection);
                long wallClock = now - Math.round(pending * 1000);
                pending -= s.duration;
                store.add(data, extension(s.uri, "ts"), s.duration, wallClock);
                lastSeq = s.seq;
                callbacks.onSegmentAdded();
            }
            if (pl.endList) return;

            long wait = Math.max(1000, Math.min(5000, Math.round(pl.targetDuration * 500)));
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
            if (!callbacks.running()) return;
            text = Http.getText(mediaUrl, connection, finalUrl);
        }
    }

    /** Elige la variante: la de mejor calidad entre las que son solo audio; si no hay, la más ligera. */
    static String chooseVariant(String master, String baseUrl) throws IOException {
        String[] lines = master.split("\r?\n");
        String bestAudio = null;
        long bestAudioBw = -1;
        String lightest = null;
        long lightestBw = Long.MAX_VALUE;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (next.isEmpty() || next.startsWith("#")) continue;
                uri = next;
                break;
            }
            if (uri == null) continue;
            long bw = 0;
            Matcher m = BANDWIDTH.matcher(line);
            if (m.find()) bw = Long.parseLong(m.group(1));
            Matcher c = CODECS.matcher(line);
            String codecs = c.find() ? c.group(1).toLowerCase(Locale.ROOT) : null;
            boolean audioOnly = !line.contains("RESOLUTION=") && codecs != null
                    && !codecs.contains("avc") && !codecs.contains("hvc") && !codecs.contains("hev")
                    && !codecs.contains("vp0") && !codecs.contains("av01");
            String abs = resolve(baseUrl, uri);
            if (audioOnly && bw > bestAudioBw) {
                bestAudioBw = bw;
                bestAudio = abs;
            }
            if (bw < lightestBw) {
                lightestBw = bw;
                lightest = abs;
            }
        }
        if (bestAudio != null) return bestAudio;
        if (lightest != null) return lightest;
        throw new Http.UnsupportedStreamException("Lista HLS sin variantes");
    }

    static MediaPlaylist parseMedia(String text, String baseUrl) throws IOException {
        MediaPlaylist pl = new MediaPlaylist();
        long seq = 0;
        double pendingDuration = -1;
        boolean pendingDiscontinuity = false;
        String currentMap = null;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                seq = Long.parseLong(line.substring(22).trim());
                pl.mediaSequence = seq;
            } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                pl.targetDuration = Double.parseDouble(line.substring(22).trim());
            } else if (line.startsWith("#EXTINF:")) {
                String v = line.substring(8);
                int comma = v.indexOf(',');
                pendingDuration = Double.parseDouble((comma >= 0 ? v.substring(0, comma) : v).trim());
            } else if (line.equals("#EXT-X-DISCONTINUITY")) {
                pendingDiscontinuity = true;
            } else if (line.startsWith("#EXT-X-MAP:")) {
                if (line.contains("BYTERANGE=")) throw new Http.UnsupportedStreamException("HLS con BYTERANGE");
                Matcher m = URI_ATTR.matcher(line);
                if (m.find()) currentMap = resolve(baseUrl, m.group(1));
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Matcher m = METHOD.matcher(line);
                if (m.find() && !"NONE".equals(m.group(1))) {
                    throw new Http.UnsupportedStreamException("Emisión cifrada");
                }
            } else if (line.startsWith("#EXT-X-BYTERANGE")) {
                throw new Http.UnsupportedStreamException("HLS con BYTERANGE");
            } else if (line.equals("#EXT-X-ENDLIST")) {
                pl.endList = true;
            } else if (!line.startsWith("#")) {
                Segment s = new Segment();
                s.seq = seq++;
                s.uri = resolve(baseUrl, line);
                s.duration = pendingDuration > 0 ? pendingDuration : pl.targetDuration;
                s.discontinuity = pendingDiscontinuity;
                s.mapUri = currentMap;
                pl.segments.add(s);
                pendingDuration = -1;
                pendingDiscontinuity = false;
            }
        }
        return pl;
    }

    static String extension(String url, String fallback) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot > slash && dot >= 0) {
            String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (KNOWN_EXTENSIONS.contains(ext)) return ext;
        }
        return fallback;
    }

    static String resolve(String base, String ref) {
        try {
            return URI.create(base).resolve(ref.replace(" ", "%20")).toString();
        } catch (IllegalArgumentException e) {
            return ref;
        }
    }
}
