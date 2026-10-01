package mtgcraft.service;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLContext;
import mtgcraft.protocol.*;
import mtgcraft.protocol.Protocol.Error;
import static mtgcraft.protocol.Protocol.*;

/** JDK HTTP server. Engine handlers own durability and idempotency, never HTTP retries. */
public final class ControlServer implements AutoCloseable {
    public static final class Fault extends RuntimeException {
        public final int status;
        public final Error error;
        public Fault(int status, String code, String message, boolean retryable) {
            super(code); this.status = status; error = new Error(code, message, retryable, List.of());
        }
    }
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(16);
    private final MagicEngine engine;
    private final UUID world;
    private final byte[] credential;
    public ControlServer(InetAddress bind, int port, UUID world, String token, SSLContext tls, MagicEngine engine) throws IOException {
        if (!bind.isLoopbackAddress() && tls == null) throw new IllegalArgumentException("TLS required for non-loopback binding");
        if (!token.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("invalid credential");
        this.world = java.util.Objects.requireNonNull(world); this.engine = java.util.Objects.requireNonNull(engine);
        credential = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        // JDK transport deadlines bound slow headers/bodies and response writes.
        System.setProperty("sun.net.httpserver.maxReqTime", "10");
        System.setProperty("sun.net.httpserver.maxRspTime", "10");
        System.setProperty("sun.net.httpserver.timerMillis", "1000");
        var address = new InetSocketAddress(bind, port);
        if (tls == null) server = HttpServer.create(address, 16);
        else {
            var https = HttpsServer.create(address, 16);
            https.setHttpsConfigurator(new HttpsConfigurator(tls)); server = https;
        }
        server.setExecutor(workers); server.createContext("/", this::handle); server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        boolean admitted = capacity.tryAcquire();
        try (exchange) {
            if (!admitted) { respond(exchange, 503, new Failure(VERSION, new Error("CAPACITY", "Control request capacity reached", true, List.of()))); return; }
            try {
                var headers = exchange.getRequestHeaders();
                if (headers.getOrDefault("Authorization", List.of()).size() != 1 || !MessageDigest.isEqual(credential,
                        headers.getFirst("Authorization").getBytes(StandardCharsets.UTF_8))) throw new Fault(401, "UNAUTHORIZED", "Invalid service credential", false);
                if (headers.getOrDefault("X-Mtgcraft-World", List.of()).size() != 1 || !world.toString().equals(headers.getFirst("X-Mtgcraft-World")))
                    throw new Fault(403, "WRONG_WORLD", "World namespace mismatch", false);
                if (!"1".equals(headers.getFirst("X-Mtgcraft-Version"))) throw new Fault(426, "VERSION_MISMATCH", "Protocol version mismatch", false);
                var length = headers.getFirst("Content-Length");
                if (length != null && Long.parseLong(length) > Json.MAX_BYTES) throw new Fault(413, "PAYLOAD_TOO_LARGE", "Payload limit exceeded", false);
                var body = exchange.getRequestBody().readNBytes(Json.MAX_BYTES + 1);
                if (body.length > Json.MAX_BYTES) throw new Fault(413, "PAYLOAD_TOO_LARGE", "Payload limit exceeded", false);
                var method = exchange.getRequestMethod();
                if (!method.equals("GET") && !"application/json".equals(headers.getFirst("Content-Type")))
                    throw new Fault(415, "CONTENT_TYPE", "Expected application/json", false);
                if (method.equals("GET") && body.length != 0) throw new IllegalArgumentException("GET body");
                if (exchange.getRequestURI().getRawQuery() != null || exchange.getRequestURI().getRawPath().contains("%"))
                    throw new IllegalArgumentException("invalid request target");
                respond(exchange, 200, route(method, exchange.getRequestURI().getPath(), body));
            } catch (Fault fault) { respond(exchange, fault.status, new Failure(VERSION, fault.error)); }
            catch (IllegalArgumentException | NullPointerException invalid) {
                respond(exchange, 400, new Failure(VERSION, new Error("BAD_SCHEMA", "Invalid request schema or binding", false, List.of())));
            } catch (RuntimeException failure) {
                respond(exchange, 503, new Failure(VERSION, new Error("SERVICE_FAILURE", "Operation outcome unknown; reconcile with the same IDs", true, List.of())));
            }
        } finally { if (admitted) capacity.release(); }
    }
    private Object route(String method, String path, byte[] body) {
        if (method.equals("GET") && path.equals("/v1/health")) return engine.health();
        if (method.equals("POST") && path.equals("/v1/decks/validate")) {
            var request = Json.read(body, ValidationRequest.class); checkWorld(request.worldId()); return engine.validateDeck(request);
        }
        var parts = path.split("/", -1);
        if (parts.length < 4 || parts.length > 5 || !parts[1].equals("v1") || !parts[2].equals("matches"))
            throw new Fault(404, "NOT_FOUND", "Unknown operation", false);
        var match = UUID.fromString(parts[3]);
        if (!match.toString().equals(parts[3])) throw new IllegalArgumentException("invalid match UUID");
        if (parts.length == 4) {
            if (method.equals("GET")) return engine.getStatus(match);
            if (method.equals("PUT")) {
                var request = Json.read(body, MatchRequest.class); checkWorld(request.worldId());
                if (!match.equals(request.matchId())) throw new IllegalArgumentException("match mismatch");
                return engine.prepareMatch(request);
            }
        } else if (method.equals("POST")) {
            if (parts[4].equals("seat-access")) {
                var owner = Json.read(body, OwnerRequest.class); checkWorld(owner.worldId()); return engine.getSeatAccess(match, owner);
            }
            var operation = Json.read(body, Operation.class); checkWorld(operation.worldId());
            if (parts[4].equals("start")) return engine.startMatch(match, operation);
            if (parts[4].equals("cancel")) return engine.cancelMatch(match, operation);
        }
        throw new Fault(405, "METHOD_NOT_ALLOWED", "Unsupported operation/method", false);
    }
    private void checkWorld(UUID requested) { if (!world.equals(requested)) throw new Fault(403, "WRONG_WORLD", "World namespace mismatch", false); }
    private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
        var bytes = Json.bytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); workers.close(); }
}
