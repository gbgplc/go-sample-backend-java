package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.utils.HTTPClient;
import com.gbg.gocore.utils.SpeakeasyHTTPClient;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * Recovers the fields {@code journey/state/fetch}'s real wire response
 * carries that {@code GetJourneyStateResponseBody} has no field for.
 *
 * <p>Confirmed live (2026-09-18, against the Meridian Health journey): the
 * SDK's response body for this operation always deserializes with an empty
 * {@code data} map, even on a decided instance whose {@code context} is
 * populated — because {@code com.gbg.gocore.utils.JSON}'s shared
 * {@code ObjectMapper} is configured with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES = false}: the wire JSON's top-level
 * {@code steps} and {@code result} keys (the ones the old raw-HTTP
 * {@code GoStateResponse} record declared and read directly) have no home in
 * the generated {@code GetJourneyStateResponseBody} class, so Jackson
 * silently discards them rather than erroring or falling through to
 * {@code data}. This was the actual cause of every decision reading as
 * "Referred" regardless of the real outcome — {@code mapDecision} was never
 * wrong, it was correctly defaulting on data that never arrived.
 *
 * <p>This wraps the SDK's own {@link SpeakeasyHTTPClient} rather than
 * replacing it — every request still goes out through the SDK (same URL
 * construction, headers, auth, hooks), and every response the SDK itself
 * still deserializes normally. Only for a {@code journey/state/fetch}
 * request does this class additionally buffer the raw bytes before handing
 * an equivalent, still-fully-readable response back to the SDK, so
 * {@link GoSdkClient} can separately parse the same bytes with a plain
 * {@code ObjectMapper} afterwards and read {@code steps}/{@code result}
 * directly — the same fields the raw-HTTP client already knew how to find.
 *
 * <p>One {@code Go} client (and so one instance of this class) is rebuilt
 * per token rotation (see {@code GoSdkClient.currentGoClient}), and each
 * Spring MVC request runs on its own container thread, so a
 * {@link ThreadLocal} keyed only by "the most recent state-fetch response
 * on this thread" is sufficient — there is never more than one in-flight
 * state-fetch per thread to confuse it with.
 */
final class RawStateBodyCapturingHttpClient implements HTTPClient {

    private final HTTPClient delegate = new SpeakeasyHTTPClient();
    private final ThreadLocal<byte[]> lastStateFetchBody = new ThreadLocal<>();

    @Override
    public HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException, URISyntaxException {
        HttpResponse<InputStream> response = delegate.send(request);
        if (!request.uri().getPath().endsWith("/journey/state/fetch")) {
            return response;
        }
        byte[] bytes = response.body().readAllBytes();
        lastStateFetchBody.set(bytes);
        return new BufferedBodyResponse(response, bytes);
    }

    /** The bytes captured by the most recent {@code journey/state/fetch} call on this thread, if any. Clears on read. */
    Optional<byte[]> takeLastStateFetchBody() {
        byte[] bytes = lastStateFetchBody.get();
        lastStateFetchBody.remove();
        return Optional.ofNullable(bytes);
    }

    @Override
    public void enableDebugLogging(boolean enabled) {
        delegate.enableDebugLogging(enabled);
    }

    @Override
    public boolean isDebugLoggingEnabled() {
        return delegate.isDebugLoggingEnabled();
    }

    /** Same response, replayable body — everything else delegates unchanged. */
    private record BufferedBodyResponse(HttpResponse<InputStream> original, byte[] bytes)
            implements HttpResponse<InputStream> {

        @Override
        public int statusCode() {
            return original.statusCode();
        }

        @Override
        public HttpRequest request() {
            return original.request();
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return original.previousResponse();
        }

        @Override
        public HttpHeaders headers() {
            return original.headers();
        }

        @Override
        public InputStream body() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return original.sslSession();
        }

        @Override
        public URI uri() {
            return original.uri();
        }

        @Override
        public HttpClient.Version version() {
            return original.version();
        }
    }
}
