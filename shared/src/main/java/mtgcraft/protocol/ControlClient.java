package mtgcraft.protocol;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import static mtgcraft.protocol.Protocol.*;

/** Asynchronous control client; timeouts are unknown outcomes, never engine death. */
public final class ControlClient implements AutoCloseable {
    public static final class Rejected extends RuntimeException {
        public final int status;
        public final Failure failure;
        public Rejected(int status, Failure failure) { super(failure.error().code()); this.status = status; this.failure = failure; }
    }
    private final HttpClient client;
    private final URI base;
    private final UUID world;
    private final String token;
    private final Duration timeout;
    public ControlClient(URI base, UUID world, String token, Duration timeout) throws java.net.UnknownHostException {
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !List.of("", "/").contains(base.getPath()) || !List.of("http", "https").contains(base.getScheme()))
            throw new IllegalArgumentException("invalid control URL");
        for (var address : InetAddress.getAllByName(base.getHost()))
            if (!address.isLoopbackAddress() && !base.getScheme().equals("https")) throw new IllegalArgumentException("TLS required");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("invalid timeout");
        if (!token.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("invalid credential");
        this.base = base; this.world = java.util.Objects.requireNonNull(world); this.token = token; this.timeout = timeout;
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private <T> CompletableFuture<T> call(String method, String path, Object body, Class<T> type) {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                .header("Authorization", "Bearer " + token).header("X-Mtgcraft-World", world.toString())
                .header("X-Mtgcraft-Version", "1").header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body))).build();
        return client.sendAsync(request, response -> new LimitedBody()).thenApply(response -> {
            if (!response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
                throw new IllegalArgumentException("non-JSON service response");
            if (response.statusCode() != 200) throw new Rejected(response.statusCode(), Json.read(response.body(), Failure.class));
            return Json.read(response.body(), type);
        });
    }
    public CompletableFuture<Health> health() { return call("GET", "/v1/health", null, Health.class); }
    public CompletableFuture<Validation> validateDeck(DeckSnapshot snapshot) {
        return call("POST", "/v1/decks/validate", new ValidationRequest(VERSION, world, snapshot), Validation.class);
    }
    public CompletableFuture<Status> prepareMatch(MatchRequest request) { return call("PUT", "/v1/matches/" + request.matchId(), request, Status.class); }
    public CompletableFuture<Status> getStatus(UUID match) { return call("GET", "/v1/matches/" + match, null, Status.class); }
    public CompletableFuture<Status> startMatch(UUID match, String digest) {
        return call("POST", "/v1/matches/" + match + "/start", new Operation(VERSION, world, digest), Status.class);
    }
    public CompletableFuture<Status> cancelMatch(UUID match, String digest) {
        return call("POST", "/v1/matches/" + match + "/cancel", new Operation(VERSION, world, digest), Status.class);
    }
    public CompletableFuture<SeatAccess> getSeatAccess(UUID match, UUID player) {
        return call("POST", "/v1/matches/" + match + "/seat-access", new OwnerRequest(VERSION, world, player), SeatAccess.class);
    }
    @Override public void close() { client.close(); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > Json.MAX_BYTES) {
                    subscription.cancel(); result.completeExceptionally(new IllegalArgumentException("response too large")); return;
                }
                var data = new byte[buffer.remaining()]; buffer.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
