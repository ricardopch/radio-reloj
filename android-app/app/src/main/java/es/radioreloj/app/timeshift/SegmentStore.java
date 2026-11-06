package es.radioreloj.app.timeshift;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Guarda en disco los últimos N segundos de la emisora en trozos (segmentos) y genera una
 * lista HLS "en directo" con ellos. ExoPlayer reproduce esa lista desde 127.0.0.1, y como
 * contiene todo lo grabado, se puede pausar y retroceder dentro de ella.
 *
 * Java puro (sin clases de Android) para poder probarlo en el PC.
 */
public final class SegmentStore {

    public static final class Segment {
        public final long seq;
        public final String name;
        public final double durationSec;
        public final long discontinuitySeq;
        public final String mapName;
        public final long wallClockMs;
        final File file;

        Segment(long seq, String name, double durationSec, long discontinuitySeq, String mapName,
                long wallClockMs, File file) {
            this.seq = seq;
            this.name = name;
            this.durationSec = durationSec;
            this.discontinuitySeq = discontinuitySeq;
            this.mapName = mapName;
            this.wallClockMs = wallClockMs;
            this.file = file;
        }
    }

    private final File dir;
    private final double maxSeconds;
    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private long nextSeq = 0;
    private long discontinuitySeq = 0;
    private boolean discontinuityPending = false;
    private double totalSeconds = 0;
    private long totalBytes = 0;
    private String currentMap;
    private int mapCounter = 0;

    public SegmentStore(File dir, double maxSeconds) {
        this.dir = dir;
        this.maxSeconds = maxSeconds;
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("No se pudo crear " + dir);
        }
    }

    /** El siguiente segmento no continúa al anterior (corte de conexión, cambio de formato…). */
    public synchronized void markDiscontinuity() {
        if (!segments.isEmpty()) discontinuityPending = true;
    }

    /** Segmento de inicialización (solo para HLS con fMP4). */
    public synchronized void setMap(byte[] data, String extension) throws IOException {
        String name = "map" + (mapCounter++) + "." + extension;
        write(new File(dir, name), data);
        if (currentMap != null) markDiscontinuity();
        currentMap = name;
    }

    public synchronized Segment add(byte[] data, String extension, double durationSec, long wallClockMs)
            throws IOException {
        long seq = nextSeq++;
        String name = "s" + seq + "." + extension;
        File f = new File(dir, name);
        write(f, data);
        if (discontinuityPending) {
            discontinuitySeq++;
            discontinuityPending = false;
        }
        Segment s = new Segment(seq, name, durationSec, discontinuitySeq, currentMap, wallClockMs, f);
        segments.addLast(s);
        totalSeconds += durationSec;
        totalBytes += data.length;
        trim();
        return s;
    }

    private void trim() {
        boolean removedWithMap = false;
        while (segments.size() > 1 && totalSeconds - segments.peekFirst().durationSec >= maxSeconds) {
            Segment old = segments.removeFirst();
            totalSeconds -= old.durationSec;
            totalBytes -= old.file.length();
            //noinspection ResultOfMethodCallIgnored
            old.file.delete();
            if (old.mapName != null) removedWithMap = true;
        }
        if (!removedWithMap) return;
        // Borrar segmentos de inicialización que ya no usa nadie
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("map") && !isMapInUse(n) && !n.equals(currentMap)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private boolean isMapInUse(String map) {
        for (Segment s : segments) if (map.equals(s.mapName)) return true;
        return false;
    }

    public synchronized int size() {
        return segments.size();
    }

    /** Número total de segmentos añadidos desde el principio (aunque ya se hayan borrado). */
    public synchronized long totalAdded() {
        return nextSeq;
    }

    public synchronized double recordedSeconds() {
        return totalSeconds;
    }

    public synchronized long recordedBytes() {
        return totalBytes;
    }

    /** Duración del trozo más largo (el reproductor empieza unos 3 trozos antes del final). */
    public synchronized double maxSegmentSeconds() {
        double max = 0;
        for (Segment s : segments) max = Math.max(max, s.durationSec);
        return max;
    }

    public synchronized long lastSegmentWallClockMs() {
        return segments.isEmpty() ? 0 : segments.peekLast().wallClockMs;
    }

    /** Lista HLS con todo lo grabado. */
    public synchronized String playlist() {
        StringBuilder sb = new StringBuilder(256 + segments.size() * 64);
        double maxDur = 1;
        for (Segment s : segments) maxDur = Math.max(maxDur, s.durationSec);
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:6\n");
        sb.append("#EXT-X-TARGETDURATION:").append((long) Math.ceil(maxDur)).append('\n');
        if (segments.isEmpty()) {
            sb.append("#EXT-X-MEDIA-SEQUENCE:0\n");
            return sb.toString();
        }
        Segment first = segments.peekFirst();
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(first.seq).append('\n');
        sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(first.discontinuitySeq).append('\n');
        Segment prev = null;
        for (Segment s : segments) {
            boolean disc = prev != null && s.discontinuitySeq != prev.discontinuitySeq;
            if (disc) sb.append("#EXT-X-DISCONTINUITY\n");
            if (s.mapName != null && (prev == null || disc || !s.mapName.equals(prev.mapName))) {
                sb.append("#EXT-X-MAP:URI=\"").append(s.mapName).append("\"\n");
            }
            if (prev == null || disc) {
                sb.append("#EXT-X-PROGRAM-DATE-TIME:").append(isoTime(s.wallClockMs)).append('\n');
            }
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.durationSec)).append(",\n");
            sb.append(s.name).append('\n');
            prev = s;
        }
        return sb.toString();
    }

    /** Archivo de un segmento o de un "map" que siga existiendo; null si no. */
    public synchronized File fileFor(String name) {
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        for (Segment s : segments) if (s.name.equals(name)) return s.file;
        if (name.equals(currentMap) || isMapInUse(name)) return new File(dir, name);
        return null;
    }

    /** Borra todo lo grabado. */
    public synchronized void clear() {
        segments.clear();
        totalSeconds = 0;
        totalBytes = 0;
        deleteRecursively(dir);
    }

    static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static String isoTime(long ms) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(ms));
    }
}
