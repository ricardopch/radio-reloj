package es.radioreloj.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP mínimo que solo escucha en 127.0.0.1 (no es accesible desde fuera del móvil).
 * Sirve dos cosas:
 *  - las órdenes del reloj (GET /api/play?id=ser → JSON),
 *  - la grabación de la emisora como lista HLS (/ts/...), que reproduce el propio reproductor.
 * No usa clases de Android para poder probarlo con Java normal.
 */
public final class CommandServer {

    public interface Handler {
        Response handle(String path, Map<String, String> query) throws Exception;
    }

    public static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        final File file;

        private Response(int status, String contentType, byte[] body, File file) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.file = file;
        }

        public static Response json(String json) {
            return new Response(200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), null);
        }

        public static Response text(String text, String contentType) {
            return new Response(200, contentType, text.getBytes(StandardCharsets.UTF_8), null);
        }

        public static Response file(File file, String contentType) {
            return new Response(200, contentType, null, file);
        }

        public static Response notFound() {
            return new Response(404, "application/json; charset=utf-8",
                    errorJson("No encontrado").getBytes(StandardCharsets.UTF_8), null);
        }

        static Response error(int status, String message) {
            return new Response(status, "application/json; charset=utf-8",
                    errorJson(message).getBytes(StandardCharsets.UTF_8), null);
        }
    }

    private final int port;
    private final Handler handler;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private volatile String lastError = "";
    private Thread acceptThread;

    public CommandServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public String getLastError() {
        return lastError;
    }

    public int getPort() {
        return port;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "radio-reloj-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        ServerSocket s = serverSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 16);
                serverSocket = ss;
                lastError = "";
                while (running) {
                    Socket client = ss.accept();
                    workers.execute(() -> serve(client));
                }
            } catch (IOException e) {
                if (!running) return;
                lastError = "No se pudo abrir el puerto " + port + ": " + e.getMessage();
                serverSocket = null;
                try {
                    Thread.sleep(3000); // reintento (por si el puerto estaba ocupado un momento)
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void serve(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(10_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            // Saltar cabeceras
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // nada
            }

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String target = parts.length > 1 ? parts[1] : "/";
            boolean head = "HEAD".equals(method);

            Response response;
            if (!"GET".equals(method) && !"POST".equals(method) && !head) {
                response = Response.error(405, "Método no permitido");
            } else {
                String path = target;
                String queryString = "";
                int q = target.indexOf('?');
                if (q >= 0) {
                    path = target.substring(0, q);
                    queryString = target.substring(q + 1);
                }
                try {
                    response = handler.handle(path, parseQuery(queryString));
                    if (response == null) response = Response.notFound();
                } catch (Exception e) {
                    response = Response.error(500, "Error interno: " + e.getMessage());
                }
            }
            writeResponse(s.getOutputStream(), response, head);
        } catch (IOException ignored) {
            // cliente cerrado o lento: no hay nada que hacer
        }
    }

    static Map<String, String> parseQuery(String qs) {
        Map<String, String> out = new HashMap<>();
        if (qs == null || qs.isEmpty()) return out;
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void writeResponse(OutputStream out, Response r, boolean headOnly) throws IOException {
        InputStream fileIn = null;
        long length;
        if (r.file != null) {
            try {
                fileIn = new FileInputStream(r.file);
                length = r.file.length();
            } catch (IOException e) {
                // El trozo se acaba de borrar (fuera de la ventana de 2 horas)
                r = Response.notFound();
                length = r.body.length;
            }
        } else {
            length = r.body.length;
        }
        String reason = r.status == 200 ? "OK" : r.status == 404 ? "Not Found"
                : r.status == 405 ? "Method Not Allowed" : "Internal Server Error";
        String head = "HTTP/1.1 " + r.status + " " + reason + "\r\n"
                + "Content-Type: " + r.contentType + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        if (!headOnly) {
            if (fileIn != null) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = fileIn.read(buf)) > 0) out.write(buf, 0, n);
            } else {
                out.write(r.body);
            }
        }
        if (fileIn != null) fileIn.close();
        out.flush();
    }

    static String errorJson(String message) {
        String safe = String.valueOf(message)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
        return "{\"ok\":false,\"error\":\"" + safe + "\"}";
    }
}
