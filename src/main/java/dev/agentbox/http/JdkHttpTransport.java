package dev.agentbox.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class JdkHttpTransport implements HttpTransport {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public Result exchange(String method, URI uri, String body, Duration timeout) {
        return exchange(method, uri, body, Map.of(), timeout);
    }

    @Override
    public Result exchange(String method, URI uri, String body, Map<String, String> headers, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout);
        if (headers != null) {
            headers.forEach(request::header);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("content-type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Result(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted", exception));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
