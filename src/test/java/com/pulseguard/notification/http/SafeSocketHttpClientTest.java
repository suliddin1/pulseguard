package com.pulseguard.notification.http;

import com.pulseguard.notification.security.DestinationPolicy;
import com.pulseguard.notification.security.HostResolver;
import com.pulseguard.notification.security.PermanentDeliveryException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeSocketHttpClientTest {

    private HttpServer server;
    private int port;
    private SafeSocketHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        // Allow unsafe so test can reach 127.0.0.1
        DestinationPolicy policy = new DestinationPolicy(
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")},
                true,
                Set.of()
        );

        client = new SafeSocketHttpClient(
                policy,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                1024,
                null
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("post sends body and headers and returns status code")
    void postSendsBodyAndReceivesStatus() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> customHeader = new AtomicReference<>();

        server.createContext("/webhook", exchange -> {
            customHeader.set(exchange.getRequestHeaders().getFirst("X-Custom-Header"));
            try (InputStream in = exchange.getRequestBody()) {
                receivedBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();

        URI uri = URI.create("http://127.0.0.1:" + port + "/webhook");
        int status = client.post(uri, Map.of("X-Custom-Header", "PulseGuardValue"), "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(status).isEqualTo(202);
        assertThat(receivedBody.get()).isEqualTo("{\"hello\":\"world\"}");
        assertThat(customHeader.get()).isEqualTo("PulseGuardValue");
    }

    @Test
    @DisplayName("post does NOT follow redirects (returns 3xx status code directly)")
    void postDoesNotFollowRedirects() throws Exception {
        AtomicBoolean secondServerCalled = new AtomicBoolean(false);

        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            secondServerCalled.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        URI uri = URI.create("http://127.0.0.1:" + port + "/redirect");
        int status = client.post(uri, Map.of(), "body".getBytes(StandardCharsets.UTF_8));

        assertThat(status).isEqualTo(302);
        assertThat(secondServerCalled.get()).isFalse();
    }

    @Test
    @DisplayName("post rejects payloads exceeding max configured byte size")
    void postRejectsOversizedPayloads() {
        URI uri = URI.create("http://127.0.0.1:" + port + "/webhook");
        byte[] largeBody = new byte[2048]; // max is 1024

        assertThatThrownBy(() -> client.post(uri, Map.of(), largeBody))
                .isInstanceOf(PermanentDeliveryException.class)
                .hasMessageContaining("exceeds the configured size limit");
    }
}
