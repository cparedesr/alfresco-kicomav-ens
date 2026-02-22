package com.cparedesr.kicomav.ens;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests unitarios del cliente REST.
 *
 * ¿Por qué usamos HttpServer embebido?
 * - No dependemos de Docker / KicomAV real.
 * - Controlamos exactamente las respuestas para testear:
 *   - OK/CLEAN
 *   - FOUND
 *   - JSON
 *   - errores HTTP
 *
 * Este es un patrón muy usado para testear "HTTP clients".
 */
class KicomAvRestClientTest {

    private HttpServer server;
    private int port;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        // 0 => el SO elige un puerto libre
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void ping_ok_shouldPass() {
        server.createContext("/ping", ex -> respondText(ex, 200, "pong"));
        server.start();

        KicomAvRestClient client = new KicomAvRestClient(baseUrl, 2000, 2000);

        // Si no lanza excepción, el test pasa
        client.ping();
    }

    @Test
    void ping_http500_shouldThrow() {
        server.createContext("/ping", ex -> respondText(ex, 500, "boom"));
        server.start();

        KicomAvRestClient client = new KicomAvRestClient(baseUrl, 2000, 2000);

        assertThatThrownBy(client::ping)
                .isInstanceOf(KicomAvException.class)
                .hasMessageContaining("/ping");
    }

    @Test
    void scan_clean_okText_shouldReturnClean() {
        server.createContext("/ping", ex -> respondText(ex, 200, "pong"));
        server.createContext("/scan/file", ex -> {
            // Validamos que llega multipart (esto “prueba” que tu código está mandando form-data)
            Headers h = ex.getRequestHeaders();
            assertThat(h.getFirst("Content-Type")).contains("multipart/form-data");

            // Consumimos el body (en un test real puedes ignorarlo,
            // pero leerlo evita que queden bytes pendientes)
            drain(ex.getRequestBody());

            // Simulamos respuesta estilo clamd "OK"
            respondText(ex, 200, "stream: OK");
        });
        server.start();

        KicomAvRestClient client = new KicomAvRestClient(baseUrl, 2000, 5000);

        byte[] content = "hola".getBytes(StandardCharsets.UTF_8);
        KicomAvScanResult result = client.scan(new ByteArrayInputStream(content), "test.txt");

        assertThat(result.isInfected()).isFalse();
        assertThat(result.getSignature()).isNull();
    }

    @Test
    void scan_infected_foundText_shouldReturnInfectedWithSignature() {
        server.createContext("/ping", ex -> respondText(ex, 200, "pong"));
        server.createContext("/scan/file", ex -> {
            drain(ex.getRequestBody());
            respondText(ex, 200, "stream: Eicar-Test-Signature FOUND");
        });
        server.start();

        KicomAvRestClient client = new KicomAvRestClient(baseUrl, 2000, 5000);

        KicomAvScanResult result = client.scan(new ByteArrayInputStream(new byte[]{1,2,3}), "eicar.com");

        assertThat(result.isInfected()).isTrue();
        assertThat(result.getSignature()).contains("Eicar-Test-Signature");
    }

    @Test
    void scan_json_infected_shouldReturnInfected() {
        server.createContext("/ping", ex -> respondText(ex, 200, "pong"));
        server.createContext("/scan/file", ex -> {
            drain(ex.getRequestBody());
            respondText(ex, 200, "{\"infected\": true, \"signature\": \"Demo.Virus\"}");
        });
        server.start();

        KicomAvRestClient client = new KicomAvRestClient(baseUrl, 2000, 5000);

        KicomAvScanResult result = client.scan(new ByteArrayInputStream("x".getBytes()), "a.bin");

        assertThat(result.isInfected()).isTrue();
        assertThat(result.getSignature()).isEqualTo("Demo.Virus");
    }

    @Test
    void scan_http400_shouldThrow() {
        server.createContext("/ping", ex -> respondText(ex, 200, "pong"));
        server.createContext("/scan/file", ex -> {
            drain(ex.getRequestBody());
            respondText(ex, 400, "bad request");
        });
        server.start();

        KicomAvRestClient client = new KicomAvRestClient(baseUrl, 2000, 5000);

        assertThatThrownBy(() -> client.scan(new ByteArrayInputStream(new byte[]{9}), "x"))
                .isInstanceOf(KicomAvException.class)
                .hasMessageContaining("/scan/file");
    }

    // -----------------------
    // Helpers de test
    // -----------------------

    private static void respondText(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        while (in.read(buf) != -1) { /* consume */ }
        in.close();
    }
}