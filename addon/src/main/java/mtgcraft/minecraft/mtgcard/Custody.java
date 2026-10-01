package mtgcraft.minecraft.mtgcard;

import com.spider.mtgcard.api.DeckboxStorage;
import com.spider.mtgcard.deckbox.DeckboxBlockEntity;
import com.spider.mtgcard.registry.ModBlocks;
import com.spider.mtgcard.util.StackData;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import mtgcraft.protocol.Json;
import static mtgcraft.protocol.Protocol.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;

/** Server-thread custody journal. No remote match may be prepared until RESERVED. */
public final class Custody {
    public enum Phase { INTENT, RESERVED, RETURN_PENDING, CLAIMING, RETURNED, QUARANTINED }
    public record Reservation(MatchRequest request, UUID backingId, String sourceFingerprint, CompoundTag original, Phase phase, Status observed) {
        public Reservation {
            Objects.requireNonNull(request); Objects.requireNonNull(backingId); digest(sourceFingerprint); original = original.copy(); Objects.requireNonNull(phase);
            if (observed != null) Matches.verify(request, observed);
        }
        @Override public CompoundTag original() { return original.copy(); }
    }
    public static final String MARKER = "mtgcraft_escrow";
    private static volatile Custody current;
    private final MinecraftServer server;
    private final Path directory;
    private final UUID worldId;
    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private final Set<UUID> reservedBacking = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> resultOwners = new HashMap<>();
    private final Set<DeckboxBlockEntity> loaded = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean admissionProven; // Never enabled by production startup before the live custody acceptance probe.
    private java.util.function.Consumer<String> fault = ignored -> {};

    public Custody(MinecraftServer server) throws IOException {
        this.server = server;
        directory = server.getWorldPath(LevelResource.ROOT).resolve("mtgcraft"); Files.createDirectories(directory);
        var world = directory.resolve("world.dat");
        if (!Files.exists(world)) { var tag = new CompoundTag(); tag.putString("World", UUID.randomUUID().toString()); write(world, tag); }
        worldId = UUID.fromString(read(world).getString("World").orElseThrow());
        try (var files = Files.newDirectoryStream(directory, "escrow-*.dat")) {
            for (var file : files) {
                var tag = read(file);
                if (tag.getInt("Version").orElse(-1) != 1) throw new IOException("Unsupported custody journal version");
                var request = Json.read(tag.getString("Request").orElseThrow().getBytes(java.nio.charset.StandardCharsets.UTF_8), MatchRequest.class);
                if (!request.worldId().equals(worldId) || !file.getFileName().toString().equals("escrow-" + request.matchId() + ".dat")) throw new IOException("Custody journal binding mismatch");
                var record = new Reservation(request, UUID.fromString(tag.getString("Backing").orElseThrow()), tag.getString("Fingerprint").orElseThrow(),
                        tag.getCompound("Original").orElseThrow(), Phase.valueOf(tag.getString("Phase").orElseThrow()),
                        tag.getString("Status").map(json -> Json.read(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), Status.class)).orElse(null));
                uniqueResult(record);
                if (record.phase() != Phase.RETURNED && !reservedBacking.add(record.backingId())) throw new IOException("Repeated backing reservation");
                reservations.put(request.matchId(), record);
                if (record.phase() == Phase.INTENT || record.phase() == Phase.CLAIMING) put(phase(record, Phase.QUARANTINED));
            }
        }
    }
    public static void install(Custody custody) { current = Objects.requireNonNull(custody); }
    public static Custody current() { return Objects.requireNonNull(current, "Custody journal is unavailable"); }
    public UUID worldId() { return worldId; }
    public Collection<Reservation> records() { onThread(); return List.copyOf(reservations.values()); }
    public void track(DeckboxBlockEntity box, boolean adding) {
        onThread(); if (adding) { loaded.add(box); if (locked(server, box.getStorageId())) box.getItem(0); } else loaded.remove(box);
    }
    public static boolean locked(MinecraftServer server, UUID id) {
        if (id == null) return false;
        var custody = current;
        return custody == null || custody.server != server || custody.reservedBacking.contains(id);
    }
    public static boolean locked(MinecraftServer server, ItemStack stack) {
        if (!ModBlocks.isDeckbox(stack)) return false;
        var raw = StackData.readCustom(stack).getString(DeckboxStorage.ITEM_ID_KEY).orElse("");
        try { return !raw.isBlank() && locked(server, UUID.fromString(raw)); } catch (IllegalArgumentException invalid) { return true; }
    }
    public Reservation reserve(ServerPlayer player, MtgcardAdapter.Source source, MatchRequest request) throws IOException {
        onThread();
        if (!admissionProven) throw new IllegalArgumentException("Physical admission is disabled pending live custody verification");
        if (!request.worldId().equals(worldId) || !request.seats().stream().anyMatch(s -> s.role() == Role.HUMAN && player.getUUID().equals(s.playerId()))
                || !source.owner().equals(player.getUUID()) || !request.snapshot().entries().equals(source.entries())) throw new IllegalArgumentException("Admission binding mismatch");
        if (reservations.containsKey(request.matchId()) || locked(server, source.backingId())) throw new IllegalArgumentException("Deckbox or match is already reserved");
        if (loaded.stream().anyMatch(box -> !box.isRemoved() && source.backingId().equals(box.getStorageId()))) throw new IllegalArgumentException("A placed alias of this deckbox is loaded; remove it first");
        for (var viewer : server.getPlayerList().getPlayers()) {
            var menu = viewer.containerMenu;
            if (menu instanceof com.spider.mtgcard.deckbox.DeckboxScreenHandler || menu instanceof com.spider.mtgcard.db.CardDatabaseScreenHandler
                    || menu instanceof com.spider.mtgcard.deckcontrol.DeckControlScreenHandler) viewer.closeContainer();
        }
        var original = (CompoundTag) ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, player.registryAccess()), source.original()).getOrThrow(IllegalArgumentException::new);
        var record = new Reservation(request, source.backingId(), source.fingerprint(), original, Phase.INTENT, null);
        synchronized (DeckboxStorage.class) { // Reuse upstream's storage mutex: no async API write can slip between recheck and protection.
            MtgcardAdapter.recheck(player, source);
            fault.accept("before_intent");
            put(record); // Durable intent also activates every backing and alias guard.
            fault.accept("after_intent");
        }
        try {
            var held = player.getMainHandItem(); var custom = StackData.readCustom(held);
            custom.putString(MARKER, request.matchId().toString()); held.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
            savePlayer(player);
            fault.accept("after_marked");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            savePlayer(player); // Acknowledged atomic player write, rather than vanilla's swallowed save errors.
            fault.accept("after_removed");
            record = new Reservation(request, source.backingId(), source.fingerprint(), original, Phase.RESERVED, null);
            put(record); fault.accept("after_reserved"); return record;
        } catch (IOException | RuntimeException failure) {
            // Keep intent/guards even if the quarantine write itself fails. Never invent a replacement box.
            put(new Reservation(request, source.backingId(), source.fingerprint(), original, Phase.QUARANTINED, null)); throw failure;
        }
    }
    void proveAdmissionForProbe() { onThread(); admissionProven = true; }
    void enableVerifiedAdmission() { onThread(); admissionProven = true; }
    void injectCrashForProbe(java.util.function.Consumer<String> fault) { onThread(); this.fault = fault; }
    Status persistStatus(UUID match, Status incoming) throws IOException {
        onThread(); var record = require(match); Matches.verify(record.request(), incoming);
        var previous = record.observed();
        if (previous != null) {
            if (!previous.serviceIncarnation().equals(incoming.serviceIncarnation())) throw new IllegalArgumentException("Conflicting service incarnation");
            if (terminal(previous.state())) {
                if (!previous.equals(incoming)) throw new IllegalArgumentException("Conflicting terminal status");
                return previous;
            }
            if (incoming.revision() < previous.revision()) return previous;
            if (incoming.revision() == previous.revision()) {
                if (!incoming.equals(previous)) throw new IllegalArgumentException("Conflicting status revision");
                return previous;
            }
            if (previous.state() == State.ACTIVE && incoming.state() == State.PREPARED) throw new IllegalArgumentException("Regressing match status");
        }
        put(new Reservation(record.request(), record.backingId(), record.sourceFingerprint(), record.original(), record.phase(), incoming));
        return incoming;
    }
    // Only the lifecycle coordinator may call this, after committing a verified terminal result.
    void returnPending(UUID match) throws IOException {
        onThread(); var record = require(match);
        if (record.phase() == Phase.RETURN_PENDING || record.phase() == Phase.RETURNED) return;
        if (record.phase() != Phase.RESERVED) throw new IllegalArgumentException("Escrow cannot be released in this phase");
        fault.accept("before_return_pending");
        put(phase(record, Phase.RETURN_PENDING));
        fault.accept("after_return_pending");
    }
    public Phase claim(UUID owner, UUID match) throws IOException {
        onThread(); var record = requireOwner(owner, match);
        var player = server.getPlayerList().getPlayer(owner);
        if (player == null) return record.phase(); // Keep the durable claim; never drop an offline owner's box.
        return claim(player, match);
    }
    Phase claim(ServerPlayer player, UUID match) throws IOException {
        onThread(); var record = requireOwner(player.getUUID(), match);
        if (record.phase() == Phase.RETURNED) return Phase.RETURNED;
        if (record.phase() != Phase.RETURN_PENDING) throw new IllegalArgumentException("Deckbox is not ready to claim");
        int slot = player.getInventory().getFreeSlot();
        if (slot < 0) return Phase.RETURN_PENDING;
        var box = ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, player.registryAccess()), record.original()).getOrThrow(IllegalArgumentException::new);
        var custom = StackData.readCustom(box); custom.putString(MARKER, match.toString()); box.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        fault.accept("before_claim");
        put(phase(record, Phase.CLAIMING)); // Crash anywhere in delivery quarantines; no automatic second item.
        fault.accept("after_claim_intent");
        try {
            player.getInventory().setItem(slot, box);
            fault.accept("after_claim_insert");
            savePlayer(player);
            fault.accept("after_claim_saved");
            put(phase(record, Phase.RETURNED)); // Acknowledged delivery and durable tombstone precede unlock.
            fault.accept("after_returned");
            return Phase.RETURNED;
        } catch (IOException | RuntimeException failure) {
            put(phase(record, Phase.QUARANTINED)); throw failure;
        }
    }
    public Reservation requireOwner(UUID owner, UUID match) {
        onThread(); var record = require(match);
        if (record.request().seats().stream().noneMatch(s -> s.role() == Role.HUMAN && owner.equals(s.playerId()))) throw new IllegalArgumentException("This deckbox belongs to another player");
        return record;
    }
    private Reservation require(UUID match) {
        var record = reservations.get(match);
        if (record == null) throw new IllegalArgumentException("Unknown match");
        return record;
    }
    private static Reservation phase(Reservation record, Phase phase) { return new Reservation(record.request(), record.backingId(), record.sourceFingerprint(), record.original(), phase, record.observed()); }
    private void uniqueResult(Reservation record) throws IOException {
        if (record.observed() == null || record.observed().result() == null) return;
        var result = record.observed().result().resultId(); var match = record.request().matchId();
        var previous = resultOwners.putIfAbsent(result, match);
        if (previous != null && !previous.equals(match)) throw new IOException("Result ID belongs to another match");
    }
    private void onThread() { if (!server.isSameThread()) throw new IllegalStateException("Custody requires the Minecraft server thread"); }
    private void put(Reservation record) throws IOException {
        var tag = new CompoundTag(); tag.putInt("Version", 1); tag.putString("Request", new String(Json.bytes(record.request()), java.nio.charset.StandardCharsets.UTF_8));
        tag.putString("Backing", record.backingId().toString()); tag.putString("Fingerprint", record.sourceFingerprint()); tag.put("Original", record.original()); tag.putString("Phase", record.phase().name());
        if (record.observed() != null) tag.putString("Status", new String(Json.bytes(record.observed()), java.nio.charset.StandardCharsets.UTF_8));
        uniqueResult(record);
        reservations.put(record.request().matchId(), record);
        if (record.phase() != Phase.RETURNED) reservedBacking.add(record.backingId());
        write(directory.resolve("escrow-" + record.request().matchId() + ".dat"), tag);
        if (record.phase() == Phase.RETURNED) reservedBacking.remove(record.backingId());
    }
    static void savePlayer(ServerPlayer player) throws IOException {
        var problems = new ProblemReporter.Collector(); var output = TagValueOutput.createWithContext(problems, player.registryAccess());
        player.saveWithoutId(output);
        if (!problems.isEmpty()) throw new IOException("Player data did not encode completely");
        write(player.level().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getUUID() + ".dat"), output.buildResult());
    }
    static CompoundTag read(Path file) throws IOException { return NbtIo.readCompressed(file, NbtAccounter.create(4L * 1024 * 1024)); }
    static void write(Path file, CompoundTag tag) throws IOException {
        Files.createDirectories(file.getParent()); var temporary = Files.createTempFile(file.getParent(), "custody-", ".tmp");
        try {
            NbtIo.writeCompressed(tag, temporary);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var channel = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { channel.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
