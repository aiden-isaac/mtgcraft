package mtgcraft.minecraft.mtgcard;

import com.mojang.authlib.GameProfile;
import com.spider.mtgcard.api.DeckboxStorage;
import com.spider.mtgcard.util.StackData;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import mtgcraft.protocol.*;
import mtgcraft.protocol.Protocol.Error;
import static mtgcraft.protocol.Protocol.*;
import mtgcraft.service.ControlServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Real server commands and HTTP; the engine fixture implements no Magic rules. */
final class CommandProbe {
    static void run(MinecraftServer server, ItemStack card) throws Exception {
        var engine = new Fixture(Custody.current().worldId());
        try (var input = Matches.class.getResourceAsStream("/mtgcraft-compatibility.json")) { engine.compatibility = Json.read(input.readAllBytes(), Compatibility.class); }
        var http = new ControlServer(InetAddress.getLoopbackAddress(), 0, engine.world, "x".repeat(48), null, engine);
        var config = FabricLoader.getInstance().getConfigDir(); Files.createDirectories(config);
        Files.writeString(config.resolve("probe-token"), "x".repeat(48)); Files.setPosixFilePermissions(config.resolve("probe-token"), PosixFilePermissions.fromString("rw-------"));
        Files.writeString(config.resolve("mtgcraft.properties"), "endpoint=http://127.0.0.1:" + http.port() + "\ntokenFile=probe-token\nadmissionEnabled=true\n");
        Matches.configure(server, Custody.current());
        var owner = new Player(server, "CommandOwner"); var stranger = new Player(server, "CommandStranger");
        server.getPlayerList().getPlayersByUUID().put(owner.getUUID(), owner); server.getPlayerList().getPlayersByUUID().put(stranger.getUUID(), stranger);
        held(server, owner, card); command(server, owner, "submit"); command(server, owner, "submit");
        assert owner.messages.stream().anyMatch(m -> m.contains("already pending"));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            int step; int baseline; UUID match; UUID backing;
            private boolean said(String text) { return owner.messages.stream().skip(baseline).anyMatch(m -> m.contains(text)); }
            @Override public void onEndTick(MinecraftServer running) {
                try {
                    if (System.nanoTime() > deadline) throw new AssertionError("Command probe deadline at step " + step);
                    switch (step) {
                        case 0 -> {
                            if (!said("Use /mtgcraft access")) return;
                            var request = engine.requests.values().iterator().next(); match = request.matchId(); backing = Custody.current().requireOwner(owner.getUUID(), match).backingId();
                            assert owner.getMainHandItem().isEmpty() && request.snapshot().entries().getFirst().quantity() == 40 && Custody.locked(server, backing);
                            assert engine.requests.size() == 1;
                            baseline = owner.messages.size(); command(server, owner, "access " + match); command(server, stranger, "access " + match); step++;
                        }
                        case 1 -> {
                            if (!said("private-fixture-password")) return;
                            assert stranger.messages.stream().noneMatch(m -> m.contains("private-fixture-password"));
                            assert stranger.messages.stream().anyMatch(m -> m.contains("another player"));
                            var unsupported = card.copy(); var metadata = StackData.readCustom(unsupported); metadata.getCompound("mtg_meta").orElseThrow().putString("name", "Cheatyface"); unsupported.set(DataComponents.CUSTOM_DATA, CustomData.of(metadata));
                            held(server, owner, unsupported); baseline = owner.messages.size(); command(server, owner, "submit"); step++;
                        }
                        case 2 -> {
                            if (!said("Unsupported fixture card")) return;
                            assert !owner.getMainHandItem().isEmpty() && engine.requests.size() == 1;
                            held(server, owner, card); engine.gate = new CountDownLatch(1); engine.entered = false;
                            baseline = owner.messages.size(); command(server, owner, "submit"); step++;
                        }
                        case 3 -> {
                            if (!engine.entered) return;
                            var data = StackData.readCustom(owner.getMainHandItem()); data.putString("in_flight_edit", "changed"); owner.getMainHandItem().set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                            engine.gate.countDown(); step++;
                        }
                        case 4 -> {
                            if (!said("changed during validation")) return;
                            assert !owner.getMainHandItem().isEmpty() && engine.requests.size() == 1;
                            held(server, owner, card); engine.gate = new CountDownLatch(1); engine.entered = false;
                            baseline = owner.messages.size(); command(server, owner, "submit"); step++;
                        }
                        case 5 -> {
                            if (!engine.entered) return;
                            server.getPlayerList().getPlayersByUUID().remove(owner.getUUID()); engine.gate.countDown(); step++;
                        }
                        case 6 -> {
                            if (Matches.hasPending(owner.getUUID())) return;
                            assert !owner.getMainHandItem().isEmpty() && engine.requests.size() == 1;
                            assert Custody.current().requireOwner(owner.getUUID(), match).phase() == Custody.Phase.RESERVED;
                            server.getPlayerList().getPlayersByUUID().put(owner.getUUID(), owner);
                            baseline = owner.messages.size(); command(server, owner, "cancel " + match); step++;
                        }
                        case 7 -> {
                            if (!said("CANCELLED")) return;
                            assert Custody.locked(server, backing) && Custody.current().requireOwner(owner.getUUID(), match).phase() == Custody.Phase.RETURN_PENDING;
                            assert Custody.current().requireOwner(owner.getUUID(), match).observed().state() == State.CANCELLED;
                            held(server, owner, card); engine.lose = "create"; baseline = owner.messages.size(); command(server, owner, "submit"); step++;
                        }
                        case 8 -> {
                            if (engine.requests.size() != 2 || Matches.hasPending(owner.getUUID())) return;
                            var recovered = new Custody(server); Custody.install(recovered); Matches.stop(); Matches.configure(server, recovered);
                            match = engine.requests.keySet().stream().filter(id -> !id.equals(engine.firstMatch)).findFirst().orElseThrow();
                            assert recovered.requireOwner(owner.getUUID(), match).pending() == Custody.Pending.CREATE;
                            step++;
                        }
                        case 9 -> {
                            var record = Custody.current().requireOwner(owner.getUUID(), match);
                            if (record.observed() == null) return;
                            assert engine.requests.size() == 2 && record.pending() == Custody.Pending.NONE;
                            engine.lose = "start"; command(server, owner, "start " + match); step++;
                        }
                        case 10 -> {
                            var record = Custody.current().requireOwner(owner.getUUID(), match);
                            if (record.observed().state() != State.ACTIVE) return;
                            assert record.startRequested() && record.pending() == Custody.Pending.NONE;
                            engine.lose = "cancel"; command(server, owner, "cancel " + match); step++;
                        }
                        case 11 -> {
                            var record = Custody.current().requireOwner(owner.getUUID(), match);
                            if (record.observed().state() != State.CANCELLED) return;
                            assert record.pending() == Custody.Pending.NONE && record.phase() == Custody.Phase.RETURN_PENDING;
                            assert engine.requests.size() == 2;
                            System.out.println("PASS stable retries: lost create/start/cancel acknowledgements and reloaded Minecraft tracking reuse the same IDs/digests");
                            System.out.println("PASS commands/async HTTP: server-only snapshots, bounded duplicate submission, private owner credentials, unsupported/stale rejection and offline validation abort; verified terminal return intent is durable");
                            http.close(); server.getPlayerList().getPlayersByUUID().clear(); server.halt(false); step++;
                        }
                    }
                } catch (Throwable failure) { failure.printStackTrace(); engine.gate = null; http.close(); server.getPlayerList().getPlayersByUUID().clear(); server.halt(false); }
            }
        });
    }
    private static void command(MinecraftServer server, Player player, String command) { server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "mtgcraft " + command); }
    private static void held(MinecraftServer server, Player player, ItemStack card) {
        var id = UUID.randomUUID(); var items = NonNullList.withSize(102, ItemStack.EMPTY); items.set(0, card.copy());
        DeckboxStorage.save(server, player.registryAccess(), id, "mtgcard:oak_deckbox", "fixture", items);
        var box = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("mtgcard:oak_deckbox"))); var data = new net.minecraft.nbt.CompoundTag(); data.putString(DeckboxStorage.ITEM_ID_KEY, id.toString()); box.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        player.setItemInHand(InteractionHand.MAIN_HAND, box);
    }
    private static final class Player extends ServerPlayer {
        final List<String> messages = new ArrayList<>();
        Player(MinecraftServer server, String name) { super(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(Component message) { assert level().getServer().isSameThread(); messages.add(message.getString()); }
    }
    private static final class Fixture implements MagicEngine {
        final UUID world; final UUID incarnation = UUID.randomUUID(); Compatibility compatibility;
        final Map<UUID, MatchRequest> requests = new ConcurrentHashMap<>(); final Map<UUID, Status> statuses = new ConcurrentHashMap<>();
        UUID firstMatch; volatile String lose;
        private void lost(String operation) { if (operation.equals(lose)) { lose = null; throw new ControlServer.Fault(503, "ACK_LOST", "Fixture acknowledgement lost", true); } }
        volatile CountDownLatch gate; volatile boolean entered; volatile int validationFinished;
        Fixture(UUID world) { this.world = world; }
        @Override public Health health() { return new Health(1, world, incarnation, compatibility, true, 2); }
        @Override public Validation validateDeck(ValidationRequest request) {
            var latch = gate;
            if (latch != null) { entered = true; try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("fixture gate timeout"); } catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); } }
            validationFinished++;
            var bad = request.snapshot().entries().stream().anyMatch(e -> e.card().label().equals("Cheatyface"));
            return new Validation(1, !bad, request.snapshot().digest(), bad ? List.of(new Error("UNSUPPORTED", "Unsupported fixture card", false, List.of(0))) : List.of());
        }
        @Override public Status prepareMatch(MatchRequest request) { if (firstMatch == null) firstMatch = request.matchId(); requests.putIfAbsent(request.matchId(), request); var value = statuses.computeIfAbsent(request.matchId(), id -> status(request, State.PREPARED, null)); lost("create"); return value; }
        private Status status(MatchRequest request, State state, Result result) { return new Status(1, world, request.matchId(), request.requestDigest(), request.snapshot().digest(), incarnation, request.seats(), state, result == null ? (state == State.ACTIVE ? 2 : 1) : result.revision(), result); }
        @Override public Status startMatch(UUID match, Operation operation) { assert operation.requestDigest().equals(requests.get(match).requestDigest()); var value = statuses.compute(match, (id, old) -> status(requests.get(id), State.ACTIVE, null)); lost("start"); return value; }
        @Override public Status getStatus(UUID match) { var value = statuses.get(match); if (value == null) throw new ControlServer.Fault(404, "NOT_FOUND", "Match does not exist", false); return value; }
        @Override public Status cancelMatch(UUID match, Operation operation) {
            var request = requests.get(match); var result = new Result(UUID.randomUUID(), world, match, request.snapshot().digest(), incarnation, State.CANCELLED, 3, request.seats().stream().map(s -> new SeatOutcome(s, Outcome.NONE)).toList());
            var value = statuses.compute(match, (id, old) -> terminal(old.state()) ? old : status(request, State.CANCELLED, result)); lost("cancel"); return value;
        }
        @Override public SeatAccess getSeatAccess(UUID match, OwnerRequest owner) {
            var seat = requests.get(match).seats().stream().filter(s -> s.role() == Role.HUMAN).findFirst().orElseThrow();
            if (!seat.playerId().equals(owner.playerId())) throw new ControlServer.Fault(403, "OWNER", "Wrong owner", false);
            return new SeatAccess(1, world, match, seat, "127.0.0.1", 17171, "fixture-seat", "private-fixture-password");
        }
    }
}
