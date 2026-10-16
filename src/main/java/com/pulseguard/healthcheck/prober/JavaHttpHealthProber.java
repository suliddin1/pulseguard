package com.pulseguard.healthcheck.prober;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class JavaHttpHealthProber implements HttpHealthProber {

    private static final Logger log = LoggerFactory.getLogger(JavaHttpHealthProber.class);
    private static final String USER_AGENT = "PulseGuard-HealthProber/1.0";
    private static final com.pulseguard.notification.security.Redactor REDACTOR =
            new com.pulseguard.notification.security.Redactor(java.util.List.of());

    private final HttpClient httpClient;

    public JavaHttpHealthProber() {
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(60))
                .build());
    }

    public JavaHttpHealthProber(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ProbeResult probe(String targetUrl, int timeoutMs) {
        int effectiveTimeout = Math.max(500, timeoutMs);
        long startTime = System.nanoTime();

        try {
            URI uri = URI.create(targetUrl);
            if (uri.getScheme() == null || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https"))) {
                long elapsedMs = calculateElapsedMs(startTime);
                return ProbeResult.failure(elapsedMs, null, "Invalid URL scheme: only HTTP and HTTPS are supported");
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(effectiveTimeout))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            log.debug("Probing target URL '{}' with timeout {}ms", REDACTOR.redact(targetUrl), effectiveTimeout);
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long elapsedMs = calculateElapsedMs(startTime);
            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                log.debug("Probe to '{}' SUCCEEDED with HTTP {} in {}ms", REDACTOR.redact(targetUrl), statusCode, elapsedMs);
                return ProbeResult.success(elapsedMs, statusCode);
            } else {
                log.warn("Probe to '{}' FAILED with HTTP {} in {}ms", REDACTOR.redact(targetUrl), statusCode, elapsedMs);
                return ProbeResult.failure(elapsedMs, statusCode, "HTTP check returned non-2xx status: " + statusCode);
            }

        } catch (HttpTimeoutException ex) {
            long elapsedMs = calculateElapsedMs(startTime);
            log.warn("Probe to '{}' TIMED OUT after {}ms: {}", REDACTOR.redact(targetUrl), elapsedMs, REDACTOR.redact(ex.getMessage()));
            return ProbeResult.timeout(elapsedMs, "HTTP probe timed out after " + effectiveTimeout + "ms");

        } catch (ConnectException ex) {
            long elapsedMs = calculateElapsedMs(startTime);
            log.warn("Probe to '{}' FAILED with connection refusal in {}ms: {}", REDACTOR.redact(targetUrl), elapsedMs, REDACTOR.redact(ex.getMessage()));
            return ProbeResult.failure(elapsedMs, null, "Connection refused: " + REDACTOR.redact(ex.getMessage()));

        } catch (UnknownHostException ex) {
            long elapsedMs = calculateElapsedMs(startTime);
            log.warn("Probe to '{}' FAILED with unknown host in {}ms: {}", REDACTOR.redact(targetUrl), elapsedMs, REDACTOR.redact(ex.getMessage()));
            return ProbeResult.failure(elapsedMs, null, "Unknown host: " + REDACTOR.redact(ex.getMessage()));

        } catch (IOException ex) {
            long elapsedMs = calculateElapsedMs(startTime);
            log.warn("Probe to '{}' FAILED with I/O error in {}ms: {}", REDACTOR.redact(targetUrl), elapsedMs, REDACTOR.redact(ex.getMessage()));
            return ProbeResult.failure(elapsedMs, null, "Network I/O error: " + REDACTOR.redact(ex.getMessage()));

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            long elapsedMs = calculateElapsedMs(startTime);
            log.warn("Probe to '{}' was interrupted in {}ms", REDACTOR.redact(targetUrl), elapsedMs);
            return ProbeResult.failure(elapsedMs, null, "Health check probe was interrupted");

        } catch (IllegalArgumentException ex) {
            long elapsedMs = calculateElapsedMs(startTime);
            log.warn("Probe to '{}' failed with invalid URL: {}", REDACTOR.redact(targetUrl), REDACTOR.redact(ex.getMessage()));
            return ProbeResult.failure(elapsedMs, null, "Malformed URL: " + REDACTOR.redact(ex.getMessage()));

        } catch (Exception ex) {
            long elapsedMs = calculateElapsedMs(startTime);
            log.error("Unexpected error probing '{}' in {}ms: {}", REDACTOR.redact(targetUrl), elapsedMs, REDACTOR.redact(ex.getMessage()), ex);
            return ProbeResult.failure(elapsedMs, null, "Unexpected probe error: " + REDACTOR.redact(ex.getMessage()));
        }
    }

    private long calculateElapsedMs(long startNanoTime) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanoTime));
    }
}
