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
 * KicomAvRestClient provides methods to interact with the KicomAV antivirus REST API.
 * It supports pinging the service and scanning files for malware.
 * <p>
 * Usage:
 * <ul>
 *   <li>Instantiate with the base URL and timeout settings.</li>
 *   <li>Use {@link #ping()} to check service availability.</li>
 *   <li>Use {@link #scan(InputStream, String)} to scan files for viruses.</li>
 * </ul>
 * <p>
 * The client handles multipart file uploads and parses various response formats,
 * including plain text and JSON. It throws {@link KicomAvException} on errors.
 *
 * <p>
 * Example:
 * <pre>
 *   KicomAvRestClient client = new KicomAvRestClient("http://localhost:8080", 5000, 10000);
 *   client.ping();
 *   KicomAvScanResult result = client.scan(new FileInputStream("file.bin"), "file.bin");
 * </pre>
 *
 * @author cparedesr
 * @since 1.0
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

    public KicomAvScanResult scan(InputStream data, String filename) {
        if (data == null) throw new IllegalArgumentException("data no puede ser null");
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
                writeAscii(out, "--" + boundary + "\r\n");
                writeAscii(out, "Content-Disposition: form-data; name=\"file\"; filename=\""
                        + escapeQuotes(safeName) + "\"\r\n");
                writeAscii(out, "Content-Type: application/octet-stream\r\n\r\n");

                byte[] buffer = new byte[16 * 1024];
                int read;
                long total = 0;
                while ((read = data.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    total += read;
                }

                writeAscii(out, "\r\n");
                writeAscii(out, "--" + boundary + "--\r\n");
                out.flush();

                LOG.debug("[KicomAV] enviado /scan/file ({} bytes) filename={}", total, safeName);
            }

            int code = con.getResponseCode();
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

    private KicomAvScanResult parseScanResponse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return KicomAvScanResult.clean();
        }

        String b = body.trim();
        String lower = b.toLowerCase();
        if (lower.contains("found")) {
            String sig = b.replaceFirst("(?i)^.*?:\\s*", "")
                    .replaceFirst("(?i)\\s+FOUND\\s*$", "");
            return KicomAvScanResult.infected(sig);
        }

        if (lower.contains(" ok") || lower.equals("ok") || lower.contains("clean") || lower.contains("no virus")) {
            return KicomAvScanResult.clean();
        }

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