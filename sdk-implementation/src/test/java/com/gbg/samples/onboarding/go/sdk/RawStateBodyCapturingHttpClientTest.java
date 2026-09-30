package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.utils.HTTPClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Review finding 17: the raw state body is captured only up to a bound, and the SDK always gets it whole. */
class RawStateBodyCapturingHttpClientTest {

    private static final HttpRequest STATE_FETCH =
            HttpRequest.newBuilder(URI.create("https://go.test/v2/captain/journey/state/fetch")).build();

    @SuppressWarnings("unchecked")
    private static RawStateBodyCapturingHttpClient returning(byte[] body) throws Exception {
        HTTPClient delegate = mock(HTTPClient.class);
        HttpResponse<InputStream> response = (HttpResponse<InputStream>) mock(HttpResponse.class);
        when(response.body()).thenReturn(new ByteArrayInputStream(body));
        when(delegate.send(any())).thenReturn(response);
        return new RawStateBodyCapturingHttpClient(delegate);
    }

    @Test
    void aNormalStateBodyIsCapturedAndStillReadableByTheSdk() throws Exception {
        byte[] body = "{\"status\":\"InProgress\"}".getBytes();
        RawStateBodyCapturingHttpClient client = returning(body);

        HttpResponse<InputStream> response = client.send(STATE_FETCH);

        assertThat(response.body().readAllBytes()).isEqualTo(body);
        assertThat(client.takeLastStateFetchBody()).hasValue(body);
    }

    @Test
    void anOversizedStateBodyIsPassedThroughWholeButNotCaptured() throws Exception {
        byte[] body = new byte[RawStateBodyCapturingHttpClient.MAX_CAPTURED_BYTES + 10];
        body[body.length - 1] = 7;
        RawStateBodyCapturingHttpClient client = returning(body);

        HttpResponse<InputStream> response = client.send(STATE_FETCH);

        assertThat(client.takeLastStateFetchBody()).isEmpty();
        byte[] read = response.body().readAllBytes();
        assertThat(read).hasSize(body.length);
        assertThat(read[read.length - 1]).isEqualTo((byte) 7);
    }
}
