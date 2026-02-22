package com.cparedesr.kicomav.ens;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente REST para KicomAV (k2d).
 *
 * Endpoints esperados:
 *  - GET  /ping
 *  - POST /scan/file   (multipart/form-data, campo "file")
 *
 * Cambios respecto a tu versión:
 *  - Parser MUCHO más tolerante: acepta respuestas limpias tipo "OK", "CLEAN", JSON infected=false, JSON status=infected, etc.
 *  - Maneja body vacío como "clean" (algunos servicios devuelven 204 o 200 sin body).
 *  - Mensajes de error más claros (incluyen URL).
 */
public class KicomAvRestClient {

    private static final Logger LOG = LoggerFactory.getLogger(KicomAvRestClient.class);

    private final String baseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public KicomAvRestClient(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl no puede ser null/blank");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public void ping() {
        HttpURLConnection con = null;
        try {
            URL url = new URL(baseUrl + "/ping");
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(connectTimeoutMs);
            con.setReadTimeout(readTimeoutMs);

            int code = con.getResponseCode();
            String body = readAll(code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream());
            String norm = body == null ? "" : body.trim().toLowerCase();

            // “pong” o “ok” son típicos
            if (code < 200 || code >= 300 || !(norm.contains("pong") || norm.contains("ok"))) {
                throw new KicomAvException("KicomAV /ping falló. url=" + url + " HTTP=" + code + " body=" + body);
            }

            LOG.debug("[KicomAV] ping ok: {}", body != null ? body.trim() : "(empty)");
        } catch (IOException e) {
            throw new KicomAvException("Error conectando con KicomAV /ping. baseUrl=" + baseUrl, e);
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /**
     * Escanea un stream por REST.
     * IMPORTANTE: consume el InputStream completo.
     */
    public KicomAvScanResult scan(InputStream data, String filename) {
        if (data == null) throw new IllegalArgumentException("data no puede ser null");

        // Si quieres ping siempre, déjalo. Si KicomAV está caído, esto fallará rápido.
        ping();

        final String boundary = "----AlfrescoKicomAV" + System.currentTimeMillis();
        HttpURLConnection con = null;

        try {
            URL url = new URL(baseUrl + "/scan/file");
            con = (HttpURLConnection) url.openConnection();

            con.setRequestMethod("POST");
            con.setConnectTimeout(connectTimeoutMs);
            con.setReadTimeout(readTimeoutMs);
            con.setDoOutput(true);

            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            con.setChunkedStreamingMode(16 * 1024);

            String safeName = (filename == null || filename.isBlank()) ? "upload.bin" : filename;

            try (OutputStream out = new BufferedOutputStream(con.getOutputStream())) {
                // Parte “file”
                writeAscii(out, "--" + boundary + "\r\n");
                writeAscii(out, "Content-Disposition: form-data; name=\"file\"; filename=\""
                        + escapeQuotes(safeName) + "\"\r\n");
                writeAscii(out, "Content-Type: application/octet-stream\r\n\r\n");

                // Copiamos bytes del fichero
                byte[] buffer = new byte[16 * 1024];
                int read;
                long total = 0;
                while ((read = data.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    total += read;
                }

                // Fin multipart
                writeAscii(out, "\r\n");
                writeAscii(out, "--" + boundary + "--\r\n");
                out.flush();

                LOG.debug("[KicomAV] enviado /scan/file ({} bytes) filename={}", total, safeName);
            }

            int code = con.getResponseCode();

            // Algunos servidores devuelven 204 sin body
            InputStream responseStream = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
            String body = readAll(responseStream);

            LOG.debug("[KicomAV] respuesta HTTP={} body={}", code, body);

            if (code < 200 || code >= 300) {
                throw new KicomAvException("KicomAV /scan/file falló. url=" + url + " HTTP=" + code + " body=" + body);
            }

            return parseScanResponse(body);

        } catch (IOException e) {
            throw new KicomAvException("Error escaneando con KicomAV. baseUrl=" + baseUrl, e);
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /**
     * Parser tolerante:
     *  - clamd-like: "stream: OK" / "stream: <sig> FOUND"
     *  - texto: "OK", "CLEAN"
     *  - JSON: {"infected": false} / {"infected": true, "signature": "..."}
     *  - JSON k2d: {"status":"infected|clean|ok|error", "malware":"..."}
     *
     * Si no reconoce, lanza excepción -> el behaviour decidirá (fail-open o fail-closed).
     */
    private KicomAvScanResult parseScanResponse(String body) {
        // Si body viene vacío (null o ""), lo tratamos como CLEAN para no tumbar uploads
        // (algunos servicios responden 204/200 vacío cuando está limpio)
        if (body == null || body.trim().isEmpty()) {
            return KicomAvScanResult.clean();
        }

        String b = body.trim();
        String lower = b.toLowerCase();

        // 1) Si aparece FOUND => infectado (clamd-style o texto)
        //    (tolerante a mayúsculas/minúsculas)
        if (lower.contains("found")) {
            // Intento extraer firma: "stream: Eicar... FOUND"
            String sig = b.replaceFirst("(?i)^.*?:\\s*", "")
                    .replaceFirst("(?i)\\s+FOUND\\s*$", "");
            return KicomAvScanResult.infected(sig);
        }

        // 2) Indicadores de limpio
        //    “ok”, “clean”, “no virus”, etc.
        if (lower.contains(" ok") || lower.equals("ok") || lower.contains("clean") || lower.contains("no virus")) {
            return KicomAvScanResult.clean();
        }

        // 2.5) JSON estilo k2d: {"status":"infected|clean|ok|error", "malware":"..."}
        if (lower.contains("\"status\"")) {
            String status = extractJsonString(b, "status");
            if (status != null) {
                String st = status.trim().toLowerCase();

                if (st.contains("infect")) {
                    String sig = extractJsonString(b, "malware");
                    if (sig == null) sig = extractJsonString(b, "signature");
                    if (sig == null) sig = extractJsonString(b, "sig");
                    return KicomAvScanResult.infected(sig);
                }

                if (st.equals("clean") || st.equals("ok")) {
                    return KicomAvScanResult.clean();
                }

                if (st.equals("error")) {
                    String err = extractJsonString(b, "error");
                    throw new KicomAvException("KicomAV devolvió status=error: " + err + " body=" + b);
                }
            }
        }

        // 3) JSON infected=true/false (tolerante)
        //    - infected: true  => infectado
        //    - infected: false => limpio
        //    Acepta true/false y también 1/0.
        if (lower.contains("\"infected\"")) {
            if (lower.matches("(?s).*\"infected\"\\s*:\\s*(true|1).*")) {
                String sig = extractJsonString(b, "signature");
                if (sig == null) sig = extractJsonString(b, "sig");
                return KicomAvScanResult.infected(sig);
            }
            if (lower.matches("(?s).*\"infected\"\\s*:\\s*(false|0).*")) {
                return KicomAvScanResult.clean();
            }
        }

        // 4) JSON "result": "...":
        //    result=clean/ok => limpio, result=infected => infectado
        if (lower.contains("\"result\"")) {
            String result = extractJsonString(b, "result");
            if (result != null) {
                String r = result.trim().toLowerCase();
                if (r.contains("clean") || r.equals("ok")) return KicomAvScanResult.clean();
                if (r.contains("infect")) {
                    String sig = extractJsonString(b, "signature");
                    return KicomAvScanResult.infected(sig);
                }
            }
        }

        // Si llegamos aquí, no sabemos interpretar. Eso estaba provocando que TODO falle si KicomAV devuelve
        // un formato distinto al esperado. Ahora solo fallará si realmente es irreconocible.
        throw new KicomAvException("Respuesta inesperada de KicomAV /scan/file: " + b);
    }

    private static void writeAscii(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String escapeQuotes(String s) {
        return s.replace("\"", "\\\"");
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String extractJsonString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}