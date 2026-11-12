package com.aerosync.ingestion;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TEST ONLY. A local HTTP server (JDK built-in, loopback, random port) that
 * plays an external API: each path answers with the responses queued for it,
 * in order, repeating the last one. Tests therefore never contact OpenSky or
 * OpenWeatherMap.
 */
public final class StubHttpServer implements AutoCloseable {

    /** One canned answer. */
    public record Reply(int status, String body, Map<String, String> headers, long delayMs) {

        public static Reply of(int status, String body) {
            return new Reply(status, body, Map.of(), 0);
        }

        public Reply header(String name, String value) {
            Map<String, String> copy = new HashMap<>(headers);
            copy.put(name, value);
            return new Reply(status, body, copy, delayMs);
        }

        public Reply delayed(long millis) {
            return new Reply(status, body, headers, millis);
        }
    }

    /** One request the server received. */
    public record Received(String method, String path, String query, String authorization, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "stub-http-server");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Deque<Reply>> replies = new HashMap<>();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestMethod(), path, exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            Reply reply;
            synchronized (replies) {
                Deque<Reply> queue = replies.get(path);
                if (queue == null || queue.isEmpty()) {
                    reply = Reply.of(404, "");
                } else {
                    reply = queue.size() > 1 ? queue.poll() : queue.peek();
                }
            }
            try {
                if (reply.delayMs() > 0) {
                    Thread.sleep(reply.delayMs());
                }
                reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The client gave up (timeout tests).
            } finally {
                exchange.close();
            }
        });
        // One thread per request, so a deliberately slow answer does not hold up the next request.
        server.setExecutor(executor);
        server.start();
    }

    /** Queues answers for a path; the last one keeps being returned. */
    public StubHttpServer on(String path, Reply... answers) {
        synchronized (replies) {
            Deque<Reply> queue = replies.computeIfAbsent(path, key -> new ArrayDeque<>());
            queue.clear();
            for (Reply answer : answers) {
                queue.add(answer);
            }
        }
        return this;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Received> received() {
        return new ArrayList<>(received);
    }

    public List<Received> received(String path) {
        return received().stream().filter(request -> request.path().equals(path)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
