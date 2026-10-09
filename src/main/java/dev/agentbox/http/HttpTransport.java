package dev.agentbox.http;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public interface HttpTransport {

    Result exchange(String method, URI uri, String body, Duration timeout);

    default Result exchange(String method, URI uri, String body, Map<String, String> headers, Duration timeout) {
        return exchange(method, uri, body, timeout);
    }

    record Result(int status, String body) {}
}
