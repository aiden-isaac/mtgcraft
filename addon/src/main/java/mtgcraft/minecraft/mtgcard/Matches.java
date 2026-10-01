package mtgcraft.minecraft.mtgcard;

import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import mtgcraft.protocol.ControlClient;
import mtgcraft.protocol.Json;
import static mtgcraft.protocol.Protocol.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import static net.minecraft.commands.Commands.*;

/** Player commands carry only IDs. Decks and result data always come from the server. */
public final class Matches implements AutoCloseable {
    private static Matches current;
    private final MinecraftServer server;
    private final Custody custody;
    private final ControlClient client;
    private final Compatibility expected;
    private final boolean admissions;
    private final Set<UUID> busy = new HashSet<>();
    public Matches(MinecraftServer server, Custody custody, ControlClient client, Compatibility expected, boolean admissions) {
        this.server = server; this.custody = custody; this.client = client; this.expected = expected; this.admissions = admissions;
    }
    public static void configure(MinecraftServer server, Custody custody) throws Exception {
        var config = FabricLoader.getInstance().getConfigDir().resolve("mtgcraft.properties");
        current = null;
        if (!Files.isRegularFile(config)) return;
        var properties = new Properties(); try (var input = Files.newInputStream(config)) { properties.load(input); }
        var tokenFile = config.toAbsolutePath().getParent().resolve(properties.getProperty("tokenFile")).normalize();
        if (Files.getPosixFilePermissions(tokenFile).stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) throw new IllegalArgumentException("Control token must be private");
        Compatibility expected;
        try (var input = Matches.class.getResourceAsStream("/mtgcraft-compatibility.json")) { expected = Json.read(Objects.requireNonNull(input).readAllBytes(), Compatibility.class); }
        var enabled = properties.getProperty("admissionEnabled", "false");
        if (!Set.of("true", "false").contains(enabled)) throw new IllegalArgumentException("Invalid admission flag");
        var client = new ControlClient(URI.create(properties.getProperty("endpoint")), custody.worldId(), Files.readString(tokenFile).strip(), Duration.ofSeconds(10));
        current = new Matches(server, custody, client, expected, Boolean.parseBoolean(enabled));
        if (current.admissions) custody.enableVerifiedAdmission();
    }
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            var root = literal("mtgcraft").requires(source -> source.getPlayer() != null)
                    .executes(context -> { tell(context.getSource().getPlayerOrException(), "/mtgcraft world | submit | status/access/start/cancel/claim <match UUID>"); return 1; });
            root.then(literal("world").executes(context -> { tell(context.getSource().getPlayerOrException(), "World ID: " + Custody.current().worldId()); return 1; }));
            root.then(literal("submit").executes(context -> { var player = context.getSource().getPlayerOrException(); try { configured().submit(player); } catch (Exception invalid) { tell(player, invalid.getMessage()); } return 1; }));
            for (var action : List.of("status", "access", "start", "cancel", "claim")) root.then(literal(action).then(argument("match", UuidArgument.uuid()).executes(context -> {
                var player = context.getSource().getPlayerOrException(); var match = UuidArgument.getUuid(context, "match");
                try {
                    if (action.equals("claim")) tell(player, "Deckbox return: " + Custody.current().claim(player.getUUID(), match));
                    else configured().action(player, match, action);
                } catch (Exception invalid) { tell(player, invalid.getMessage()); }
                return 1;
            })));
            dispatcher.register(root);
        });
    }
    private static Matches configured() { if (current == null) throw new IllegalArgumentException("Match service is not configured; ask the server operator"); return current; }
    static boolean hasPending(UUID owner) { return current != null && current.busy.contains(owner); }
    private static void tell(ServerPlayer player, String message) { player.sendSystemMessage(Component.literal(message == null ? "Request failed" : message)); }
    private void tell(UUID owner, String message) { var player = server.getPlayerList().getPlayer(owner); if (player != null) tell(player, message); }
    private void begin(UUID owner) {
        if (!server.isSameThread()) throw new IllegalStateException("Commands require the server thread");
        if (busy.size() >= 16 || !busy.add(owner)) throw new IllegalArgumentException("A request is already pending; wait and retry");
    }
    private <T> void observe(UUID owner, Supplier<CompletableFuture<T>> request, Consumer<T> success) {
        begin(owner);
        CompletableFuture<T> future;
        try { future = request.get(); } catch (RuntimeException failure) { busy.remove(owner); throw failure; }
        future.whenComplete((value, failure) -> server.execute(() -> {
            busy.remove(owner);
            try {
                if (failure == null) success.accept(value);
                else {
                    var cause = failure; while (cause.getCause() != null) cause = cause.getCause();
                    tell(owner, cause instanceof ControlClient.Rejected rejected ? rejected.failure.error().message()
                            : "Service status is unknown. Any reserved deckbox stays in custody; retry status.");
                }
            } catch (Exception invalid) { tell(owner, "Request could not be applied: " + invalid.getMessage()); }
        }));
    }
    public void submit(ServerPlayer player) throws Exception {
        if (!admissions) throw new IllegalArgumentException("New matches are disabled while custody acceptance is incomplete");
        var source = MtgcardAdapter.readHeld(player); var owner = player.getUUID();
        observe(owner, client::health, health -> {
            if (!health.worldId().equals(custody.worldId()) || !health.ready() || !health.compatibility().equals(expected)) throw new IllegalArgumentException("Service compatibility/readiness mismatch");
            var online = server.getPlayerList().getPlayer(owner); if (online == null) return;
            var snapshot = DeckSnapshot.create(expected, source.entries());
            observe(owner, () -> client.validateDeck(snapshot), validation -> {
                if (!validation.snapshotDigest().equals(snapshot.digest())) throw new IllegalArgumentException("Validation digest mismatch");
                if (!validation.valid()) {
                    for (var error : validation.errors()) {
                        var labels = error.entries().stream().map(i -> snapshot.entries().get(i).card().label()).distinct().toList();
                        tell(owner, error.message() + (labels.isEmpty() ? "" : " — " + String.join(", ", labels)));
                    }
                    return;
                }
                var heldOwner = server.getPlayerList().getPlayer(owner); if (heldOwner == null) return;
                var request = MatchRequest.create(custody.worldId(), UUID.randomUUID(), List.of(new Seat(UUID.randomUUID(), Role.HUMAN, owner), new Seat(UUID.randomUUID(), Role.AI, null)), snapshot, "mad-plains-v1");
                try { custody.reserve(heldOwner, source, request); } catch (Exception invalid) { throw new IllegalArgumentException(invalid.getMessage()); }
                tell(owner, "Deckbox reserved for match " + request.matchId());
                observe(owner, () -> client.prepareMatch(request), status -> { verify(request, status); tell(owner, "Match " + request.matchId() + ": " + status.state() + ". Use /mtgcraft access " + request.matchId() + " for your XMage login."); });
            });
        });
    }
    public void action(ServerPlayer player, UUID match, String action) {
        var record = custody.requireOwner(player.getUUID(), match); var owner = player.getUUID();
        if (action.equals("access")) {
            observe(owner, () -> client.getSeatAccess(match, owner), access -> {
                if (!access.worldId().equals(custody.worldId()) || !access.matchId().equals(match) || !record.request().seats().contains(access.seat()) || !owner.equals(access.seat().playerId())) throw new IllegalArgumentException("Seat access binding mismatch");
                tell(owner, "XMage " + access.host() + ":" + access.port() + " — username " + access.username() + " — password " + access.password() + ". Join your table, then /mtgcraft start " + match);
            }); return;
        }
        observe(owner, () -> switch (action) {
            case "start" -> client.startMatch(match, record.request().requestDigest());
            case "cancel" -> client.cancelMatch(match, record.request().requestDigest());
            default -> client.getStatus(match);
        }, status -> { verify(record.request(), status); tell(owner, "Match " + match + ": " + status.state() + "; deckbox " + record.phase()); });
    }
    static void verify(MatchRequest request, Status status) {
        if (!request.worldId().equals(status.worldId()) || !request.matchId().equals(status.matchId()) || !request.requestDigest().equals(status.requestDigest())
                || !request.snapshot().digest().equals(status.snapshotDigest()) || !request.seats().equals(status.seats())) throw new IllegalArgumentException("Service status binding mismatch");
    }
    public static void stop() { var matches = current; current = null; if (matches != null) Thread.startVirtualThread(matches::close); }
    @Override public void close() { client.close(); }
}
