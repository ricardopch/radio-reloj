package es.radioreloj.app.timeshift;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Graba un stream continuo MP3 o AAC (Icecast/Shoutcast) cortándolo en trozos de ~3 segundos
 * que empiezan siempre en una trama completa. Quita los metadatos ICY intercalados y avisa
 * del título que esté sonando.
 */
final class ProgressiveRecorder {

    interface Callbacks {
        boolean running();

        void onTitle(String title);

        void onSegmentAdded();
    }

    static final double SEGMENT_SECONDS = 3.0;

    private final SegmentStore store;
    private final Callbacks callbacks;
    private final AtomicReference<HttpURLConnection> connection;

    private long pts90k = 0;
    private int format = 0;
    private final ByteArrayOutputStream segment = new ByteArrayOutputStream(64 * 1024);
    private long segmentSamples = 0;
    private int segmentRate = 0;
    private long segmentStartWallClock = 0;

    ProgressiveRecorder(SegmentStore store, Callbacks callbacks, AtomicReference<HttpURLConnection> connection) {
        this.store = store;
        this.callbacks = callbacks;
        this.connection = connection;
    }

    /** Graba hasta que se pare la sesión o se corte el stream (en ese caso lanza o vuelve). */
    void record(String url) throws IOException {
        HttpURLConnection c = Http.open(url, true, connection);
        try {
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
            String finalUrl = c.getURL().toString();
            if (type.contains("mpegurl")) throw new Http.IsHlsException(finalUrl);
            if (type.contains("ogg") || type.contains("opus") || type.contains("flac")
                    || type.contains("audio/mp4") || type.contains("video/") || type.contains("scpls")) {
                throw new Http.UnsupportedStreamException("Formato no grabable: " + type);
            }
            int metaint = 0;
            try {
                String h = c.getHeaderField("icy-metaint");
                if (h != null) metaint = Integer.parseInt(h.trim());
            } catch (NumberFormatException ignored) {
            }
            InputStream in = new BufferedInputStream(c.getInputStream(), 32 * 1024);
            if (metaint > 0) in = new IcyStripper(in, metaint, callbacks);
            readFrames(in, finalUrl);
        } finally {
            c.disconnect();
        }
    }

    private void readFrames(InputStream in, String url) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int pos = 0;
        int limit = 0;
        boolean synced = false;
        long skipped = 0;
        boolean checkedPlaylist = false;

        // Se vuelven a leer tramas hasta que se pare la sesión o termine el stream
        while (callbacks.running()) {
            // asegurar al menos 10 bytes
            if (limit - pos < 10) {
                if (pos > 0) {
                    System.arraycopy(buf, pos, buf, 0, limit - pos);
                    limit -= pos;
                    pos = 0;
                }
                int r = in.read(buf, limit, buf.length - limit);
                if (r < 0) break;
                limit += r;
                continue;
            }
            if (!checkedPlaylist) {
                checkedPlaylist = true;
                String head = new String(buf, pos, Math.min(7, limit - pos), StandardCharsets.ISO_8859_1);
                if (head.startsWith("#EXTM3U")) throw new Http.IsHlsException(url);
            }
            AudioFrames.Frame f = AudioFrames.parse(buf, pos, limit - pos);
            if (f == null || (format != 0 && f.format != format)) {
                pos++;
                synced = false;
                if (++skipped > 256 * 1024 && format == 0) {
                    throw new Http.UnsupportedStreamException("No es MP3 ni AAC");
                }
                continue;
            }
            // Tener la trama completa y la cabecera de la siguiente
            int need = f.length + 4;
            if (limit - pos < need) {
                if (pos > 0) {
                    System.arraycopy(buf, pos, buf, 0, limit - pos);
                    limit -= pos;
                    pos = 0;
                }
                int r = in.read(buf, limit, buf.length - limit);
                if (r < 0) break;
                limit += r;
                continue;
            }
            if (!synced) {
                AudioFrames.Frame next = AudioFrames.parse(buf, pos + f.length, limit - pos - f.length);
                if (next == null || next.format != f.format) {
                    pos++;
                    continue;
                }
                synced = true;
                if (format == 0) format = f.format;
            }
            if (segmentRate != 0 && f.sampleRate != segmentRate) flush();
            if (segment.size() == 0) segmentStartWallClock = System.currentTimeMillis();
            segment.write(buf, pos, f.length);
            pos += f.length;
            segmentSamples += f.samples;
            segmentRate = f.sampleRate;
            if (segmentSamples >= SEGMENT_SECONDS * segmentRate) flush();
        }
        flush();
    }

    private void flush() throws IOException {
        if (segment.size() == 0 || segmentRate == 0) return;
        byte[] id3 = AudioFrames.id3Timestamp(pts90k);
        byte[] audio = segment.toByteArray();
        byte[] data = new byte[id3.length + audio.length];
        System.arraycopy(id3, 0, data, 0, id3.length);
        System.arraycopy(audio, 0, data, id3.length, audio.length);
        double duration = segmentSamples / (double) segmentRate;
        store.add(data, format == AudioFrames.FORMAT_ADTS ? "aac" : "mp3", duration, segmentStartWallClock);
        pts90k += Math.round(segmentSamples * 90000.0 / segmentRate);
        segment.reset();
        segmentSamples = 0;
        callbacks.onSegmentAdded();
    }

    /** Quita los bloques de metadatos ICY que el servidor intercala cada "metaint" bytes. */
    static final class IcyStripper extends FilterInputStream {
        private final int metaint;
        private final Callbacks callbacks;
        private int untilMetadata;
        private String lastTitle = "";

        IcyStripper(InputStream in, int metaint, Callbacks callbacks) {
            super(in);
            this.metaint = metaint;
            this.callbacks = callbacks;
            this.untilMetadata = metaint;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (untilMetadata == 0) {
                if (!readMetadata()) return -1;
                untilMetadata = metaint;
            }
            int n = in.read(b, off, Math.min(len, untilMetadata));
            if (n > 0) untilMetadata -= n;
            return n;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        private boolean readMetadata() throws IOException {
            int lengthByte = in.read();
            if (lengthByte < 0) return false;
            int length = lengthByte * 16;
            if (length == 0) return true;
            byte[] md = new byte[length];
            int got = 0;
            while (got < length) {
                int r = in.read(md, got, length - got);
                if (r < 0) return false;
                got += r;
            }
            String title = parseTitle(md);
            if (title != null && !title.equals(lastTitle)) {
                lastTitle = title;
                callbacks.onTitle(title);
            }
            return true;
        }

        static String parseTitle(byte[] md) {
            String s = new String(md, StandardCharsets.UTF_8);
            if (s.indexOf('�') >= 0) s = new String(md, StandardCharsets.ISO_8859_1);
            int i = s.indexOf("StreamTitle='");
            if (i < 0) return null;
            i += "StreamTitle='".length();
            int j = s.indexOf("';", i);
            if (j < 0) j = s.lastIndexOf('\'');
            if (j < i) return null;
            return s.substring(i, j).trim();
        }
    }
}
