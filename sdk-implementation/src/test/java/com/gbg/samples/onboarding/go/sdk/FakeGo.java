package com.gbg.samples.onboarding.go.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for Go's HTTP API, so a test can drive the real SDK, and
 * GoSdkClient or GoSdkAuthService above it, end to end. Replies are scripted
 * in order; each request is recorded. A request that doesn't match the next
 * reply's path gets a 599, which the test then fails on.
 */
final class FakeGo implements AutoCloseable {

    record Request(String path, String body) {
    }

    private record Reply(String path, int status, String json) {
    }

    private final HttpServer server;
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();

    FakeGo() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** {@code path} on this server, e.g. {@code url("/v2/captain")}. */
    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** The next request must end with {@code path}; it gets {@code status} and {@code json} (or no body if null). */
    FakeGo reply(String path, int status, String json) {
        replies.add(new Reply(path, status, json));
        return this;
    }

    /** The bodies of the requests to {@code path}, in order. */
    List<String> bodiesSentTo(String path) {
        return requests.stream().filter(r -> r.path().endsWith(path)).map(Request::body).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Reply reply = replies.poll();
        int status = reply != null && path.endsWith(reply.path()) ? reply.status() : 599;
        byte[] body = reply == null || reply.json() == null ? new byte[0] : reply.json().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
