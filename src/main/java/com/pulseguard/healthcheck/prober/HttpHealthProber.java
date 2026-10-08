package com.pulseguard.healthcheck.prober;

public interface HttpHealthProber {

    /**
     * Executes an HTTP GET health check against the given target URL.
     *
     * @param targetUrl the HTTP or HTTPS endpoint to probe
     * @param timeoutMs maximum time in milliseconds to wait for a response
     * @return a {@link ProbeResult} capturing status, latency, HTTP code, and any errors
     */
    ProbeResult probe(String targetUrl, int timeoutMs);
}
