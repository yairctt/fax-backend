import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.Executors;

public class FaxServer {

    private static final int    PORT     = 8080;
    private static final String JAR_PATH = "./Fax.jar";

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.createContext("/ping",     new PingHandler());
        server.createContext("/compilar", new CompileHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Servidor FAX corriendo en http://localhost:" + PORT);
    }

    private static void addCORS(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        ex.getResponseHeaders().set("Access-Control-Max-Age",       "86400");
    }

    private static boolean handlePreflight(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            addCORS(ex);
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return true;
        }
        return false;
    }

    private static void sendText(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        addCORS(ex);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }

    // Nueva: envía JSON
    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        addCORS(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }

    // Escapa caracteres especiales para JSON sin depender de librerías
    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r\n", "\\n")
                .replace("\n",   "\\n")
                .replace("\r",   "\\n")
                .replace("\t",   "\\t");
    }

    // ── /ping ────────────────────────────────────────────────
    static class PingHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            sendText(ex, 200, "pong");
        }
    }

    // ── /compilar ────────────────────────────────────────────
    static class CompileHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Metodo no permitido. Usa POST.");
                return;
            }

            String codigo;
            try (InputStream is = ex.getRequestBody()) {
                codigo = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (codigo == null || codigo.isBlank()) {
                sendText(ex, 400, "El codigo esta vacio.");
                return;
            }

            Path tmp = null;
            try {
                // Usamos nombre fijo "fax_src" para que el .ci sea predecible
                Path dir = Files.createTempDirectory("fax_run_");
                tmp = dir.resolve("fax_src.fax");
                Files.writeString(tmp, codigo, StandardCharsets.UTF_8);

                CompileResult result = compilar(tmp, dir);

                // Construir JSON manualmente (sin librerías externas)
                String json = "{"
                    + "\"salida\":\""  + jsonEscape(result.salida) + "\","
                    + "\"ci\":\""      + jsonEscape(result.ci)     + "\","
                    + "\"exitoso\":"   + result.exitoso
                    + "}";

                sendJson(ex, 200, json);

            } catch (Exception e) {
                sendText(ex, 500, "Error del servidor: " + e.getMessage());
            } finally {
                // Limpiar directorio temporal
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                        Path ciFile = tmp.getParent().resolve("fax_src.ci");
                        Files.deleteIfExists(ciFile);
                        Files.deleteIfExists(tmp.getParent());
                    } catch (IOException ignored) {}
                }
            }
        }

        private CompileResult compilar(Path archivo, Path dir) throws Exception {
            if (!new File(JAR_PATH).exists()) {
                return new CompileResult(
                    "ERROR: No se encontro Fax.jar en " + new File(JAR_PATH).getAbsolutePath(),
                    "", false);
            }

            // Fax.jar lee de stdin y, si compila bien, escribe <nombre>.ci
            // en el directorio de trabajo → usamos dir como workdir
            ProcessBuilder pb = new ProcessBuilder("java", "-jar",
                new File(JAR_PATH).getAbsolutePath(), archivo.toString());
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);

            Process p = pb.start();
            String salida;
            try (InputStream is = p.getInputStream()) {
                salida = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            boolean ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                return new CompileResult("TIMEOUT: El compilador tardo mas de 10 segundos.", "", false);
            }

            boolean exitoso = salida.contains("✓") || salida.matches("(?s).*exitosa.*");

            // Intentar leer el .ci generado (mismo nombre base que el .fax)
            String ciContent = "";
            String baseName  = archivo.getFileName().toString().replaceFirst("\\.fax$", "");
            Path   ciPath    = dir.resolve(baseName + ".ci");
            if (Files.exists(ciPath)) {
                ciContent = Files.readString(ciPath, StandardCharsets.UTF_8);
            }

            return new CompileResult(
                salida.isBlank() ? "Sin salida (exit code: " + p.exitValue() + ")" : salida,
                ciContent,
                exitoso
            );
        }

        static class CompileResult {
            final String  salida;
            final String  ci;
            final boolean exitoso;
            CompileResult(String salida, String ci, boolean exitoso) {
                this.salida   = salida;
                this.ci       = ci;
                this.exitoso  = exitoso;
            }
        }
    }
}
