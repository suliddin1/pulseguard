package com.pulseguard.notification.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    @Test
    @DisplayName("redact masks configured secrets, URLs, and bearer tokens")
    void redactMasksSecretsUrlsAndTokens() {
        String secretUrl = "https://hooks.slack.com/services/T00/B00/SECRET123";
        String hmacSecret = "super-secret-hmac-key";
        Redactor redactor = new Redactor(List.of(secretUrl, hmacSecret));

        String input = "Webhook call to " + secretUrl + " with key " + hmacSecret
                + " and Authorization: Bearer eyJhbGciOiJIUzI1NiJ9 and external url https://evil.com/leak";

        String redacted = redactor.redact(input);

        assertThat(redacted).doesNotContain("SECRET123");
        assertThat(redacted).doesNotContain("super-secret-hmac-key");
        assertThat(redacted).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(redacted).doesNotContain("https://evil.com/leak");
        assertThat(redacted).contains("[redacted]");
        assertThat(redacted).contains("[redacted-url]");
        assertThat(redacted).contains("Bearer [redacted]");
    }

    @Test
    @DisplayName("describe maps network and TLS exceptions to safe vocabulary strings")
    void describeMapsExceptionsToSafeVocabulary() {
        Redactor redactor = new Redactor(List.of());

        assertThat(redactor.describe(new SocketTimeoutException("connect to 10.0.0.1:443 timed out")))
                .isEqualTo("timeout");

        assertThat(redactor.describe(new ConnectException("connection refused to 192.168.1.1:80")))
                .isEqualTo("connection_failed");

        assertThat(redactor.describe(new UnknownHostException("internal-service.cluster.local")))
                .isEqualTo("unknown_host");

        javax.net.ssl.SSLHandshakeException certError = new javax.net.ssl.SSLHandshakeException(
                "PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path");
        certError.initCause(new CertificateException("Untrusted root"));
        assertThat(redactor.describe(certError)).isEqualTo("tls_certificate_error");

        assertThat(redactor.describe(new javax.net.ssl.SSLException("Connection reset during handshake")))
                .isEqualTo("tls_error");

        assertThat(redactor.describe(new DestinationNotAllowedException("destination resolves to a non-public address")))
                .isEqualTo("destination resolves to a non-public address");
    }
}
