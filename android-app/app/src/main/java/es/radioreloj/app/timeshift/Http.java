package es.radioreloj.app.timeshift;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;

/** Conexiones HTTP con redirecciones (también de https a http, que usan algunas emisoras). */
final class Http {

    static final String USER_AGENT = "RadioReloj/1.1 (Android)";
    private static final int MAX_DOWNLOAD = 20 * 1024 * 1024;

    /** El formato del stream no se puede grabar (se reproducirá solo en directo). */
    static final class UnsupportedStreamException extends IOException {
        UnsupportedStreamException(String message) {
            super(message);
        }
    }

    /** La dirección resultó ser una lista HLS. */
    static final class IsHlsException extends IOException {
        final String url;

        IsHlsException(String url) {
            super("Es una lista HLS: " + url);
            this.url = url;
        }
    }

    private Http() {
    }

    static HttpURLConnection open(String url, boolean icyMetadata, AtomicReference<HttpURLConnection> holder)
            throws IOException {
        String current = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            if (icyMetadata) c.setRequestProperty("Icy-MetaData", "1");
            if (holder != null) holder.set(c);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new IOException("Redirección sin destino en " + current);
                current = new URL(new URL(current), location).toString();
                continue;
            }
            if (code != 200 && code != 206) {
                c.disconnect();
                throw new IOException("HTTP " + code + " en " + current);
            }
            return c;
        }
        throw new IOException("Demasiadas redirecciones: " + url);
    }

    static byte[] get(String url, AtomicReference<HttpURLConnection> holder) throws IOException {
        HttpURLConnection c = open(url, false, holder);
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    static String getText(String url, AtomicReference<HttpURLConnection> holder, String[] finalUrl)
            throws IOException {
        HttpURLConnection c = open(url, false, holder);
        try (InputStream in = c.getInputStream()) {
            if (finalUrl != null) finalUrl[0] = c.getURL().toString();
            return new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_DOWNLOAD) throw new IOException("Respuesta demasiado grande");
        }
        return out.toByteArray();
    }
}
