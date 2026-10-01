package mtgcraft.minecraft.mtgcard;

import com.mojang.authlib.GameProfile;
import com.spider.mtgcard.api.DeckboxStorage;
import com.spider.mtgcard.util.StackData;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import mtgcraft.protocol.Json;
import static mtgcraft.protocol.Protocol.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;

/** Three independent Minecraft JVMs, with a surviving external engine and native clients. */
final class LifecycleProbe {
    static void run(MinecraftServer server) throws Exception {
        var mode = System.getProperty("mtgcraft.lifecycle"); var directory = server.getWorldPath(LevelResource.ROOT);
        var custody = Custody.current();
        if (mode.equals("seed")) {
            custody.proveAdmissionForProbe();
            Compatibility compatibility; try (var input = Matches.class.getResourceAsStream("/mtgcraft-compatibility.json")) { compatibility = Json.read(input.readAllBytes(), Compatibility.class); }
            for (int i = 0; i < 2; i++) {
                var owner = new Player(server, UUID.randomUUID(), "Lifecycle" + i); var backing = UUID.randomUUID();
                var card = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("mtgcard:card")), 40);
                var metadata = new CompoundTag(); metadata.putString("set", "m10"); metadata.putString("collector_number", "230"); metadata.putString("name", "Plains"); metadata.putString("layout", "normal");
                var custom = new CompoundTag(); custom.put("mtg_meta", metadata); custom.putString("mtg_uid", UUID.randomUUID().toString()); card.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
                var items = NonNullList.withSize(102, ItemStack.EMPTY); items.set(0, card);
                DeckboxStorage.save(server, owner.registryAccess(), backing, "mtgcard:oak_deckbox", "fixture", items);
                var box = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("mtgcard:oak_deckbox"))); var data = new CompoundTag(); data.putString(DeckboxStorage.ITEM_ID_KEY, backing.toString()); data.putInt("mtgcard_deckbox_tint", 0x336699); box.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                owner.setItemInHand(InteractionHand.MAIN_HAND, box);
                var source = MtgcardAdapter.readHeld(owner);
                var request = MatchRequest.create(custody.worldId(), UUID.randomUUID(), List.of(new Seat(UUID.randomUUID(), Role.HUMAN, owner.getUUID()), new Seat(UUID.randomUUID(), Role.AI, null)), DeckSnapshot.create(compatibility, source.entries()), "mad-plains-v1");
                custody.reserve(owner, source, request); custody.intend(owner.getUUID(), request.matchId(), Custody.Pending.START);
                assert owner.getMainHandItem().isEmpty() && custody.require(request.matchId()).phase() == Custody.Phase.RESERVED;
                Files.write(directory.resolve("request-" + i + ".json"), Json.bytes(request));
                var manifest = new CompoundTag(); manifest.putString("Backing", backing.toString()); manifest.putString("CardUID", custom.getString("mtg_uid").orElseThrow()); Custody.write(directory.resolve("physical-" + i + ".dat"), manifest);
            }
            System.out.println("PASS real lifecycle seed: two authoritative physical snapshots reserved before any remote creation"); server.halt(false); return;
        }
        if (mode.equals("unsupported")) { unsupported(server); return; }
        var requests = List.of(Json.read(Files.readAllBytes(directory.resolve("request-0.json")), MatchRequest.class), Json.read(Files.readAllBytes(directory.resolve("request-1.json")), MatchRequest.class));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            boolean completed;
            @Override public void onEndTick(MinecraftServer running) {
                try {
                    if (System.nanoTime() > deadline) throw new AssertionError("Real lifecycle reconciliation deadline");
                    assert custody.records().size() == 2;
                    for (var request : requests) assert custody.require(request.matchId()).request().equals(request);
                    if (mode.equals("active")) {
                        for (var request : requests) {
                            var record = custody.require(request.matchId()); if (record.observed() == null || record.observed().state() != State.ACTIVE) return;
                            assert record.phase() == Custody.Phase.RESERVED && Custody.locked(server, record.backingId());
                        }
                        Files.writeString(directory.resolve("mc-active"), "same matches, snapshots, seats and incarnation");
                        System.out.println("PASS fresh Minecraft JVM: two surviving real XMage games reattached without another table or snapshot");
                        Runtime.getRuntime().halt(88); // No shutdown hooks; both native clients and the service continue.
                    } else if (mode.equals("finish")) {
                        var first = custody.require(requests.getFirst().matchId());
                        if (!completed) {
                            if (first.observed() == null || first.observed().state() != State.COMPLETED) return;
                            assert first.observed().result().outcomes().getFirst().outcome() == Outcome.LOSS && first.observed().result().outcomes().getLast().outcome() == Outcome.WIN;
                            claim(server, custody, requests.getFirst(), directory.resolve("physical-0.dat"));
                            Files.write(directory.resolve("completed.json"), Json.bytes(first.observed()));
                            Files.writeString(directory.resolve("mc-completed"), "original returned once"); completed = true;
                        }
                        var second = custody.require(requests.getLast().matchId());
                        if (second.observed() == null || second.observed().state() != State.INTERRUPTED) {
                            assert second.phase() == Custody.Phase.RESERVED && Custody.locked(server, second.backingId()); return;
                        }
                        assert second.observed().result().outcomes().stream().allMatch(o -> o.outcome() == Outcome.NONE);
                        claim(server, custody, requests.getLast(), directory.resolve("physical-1.dat"));
                        var reloaded = new Custody(server);
                        assert reloaded.require(requests.getFirst().matchId()).observed().equals(first.observed());
                        assert reloaded.require(requests.getLast().matchId()).observed().equals(second.observed());
                        Files.write(directory.resolve("interrupted.json"), Json.bytes(second.observed()));
                        System.out.println("PASS real engine lifecycle: offline native concession persisted once; original box/card UID/tint returned once; unreachable engine retained custody; exclusive-process restart interrupted the second match with no winner");
                        server.getPlayerList().getPlayersByUUID().clear(); server.halt(false);
                    } else throw new AssertionError("Unknown lifecycle mode");
                } catch (Throwable failure) { failure.printStackTrace(); server.getPlayerList().getPlayersByUUID().clear(); server.halt(false); }
            }
        });
    }
    private static void claim(MinecraftServer server, Custody custody, MatchRequest request, Path manifestFile) throws Exception {
        var record = custody.require(request.matchId()); assert record.phase() == Custody.Phase.RETURN_PENDING;
        assert custody.claim(request.seats().getFirst().playerId(), request.matchId()) == Custody.Phase.RETURN_PENDING;
        var owner = new Player(server, request.seats().getFirst().playerId(), "ReturnOwner"); server.getPlayerList().getPlayersByUUID().put(owner.getUUID(), owner);
        for (int attempt = 0; attempt < 2; attempt++) server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mtgcraft claim " + request.matchId());
        assert custody.require(request.matchId()).phase() == Custody.Phase.RETURNED;
        var box = owner.getInventory().getItem(0); var manifest = Custody.read(manifestFile);
        assert box.getCount() == 1 && owner.getInventory().countItem(box.getItem()) == 1;
        assert StackData.readCustom(box).getString(DeckboxStorage.ITEM_ID_KEY).orElseThrow().equals(manifest.getString("Backing").orElseThrow());
        assert StackData.readCustom(box).getInt("mtgcard_deckbox_tint").orElseThrow() == 0x336699;
        var storage = DeckboxStorage.load(server, owner.registryAccess(), record.backingId()).orElseThrow();
        assert storage.items().get(0).getCount() == 40 && StackData.readCustom(storage.items().get(0)).getString("mtg_uid").orElseThrow().equals(manifest.getString("CardUID").orElseThrow());
        server.getPlayerList().getPlayersByUUID().remove(owner.getUUID());
    }
    private static void unsupported(MinecraftServer server) throws Exception {
        var owner = new Player(server, UUID.randomUUID(), "Unsupported"); var backing = UUID.randomUUID();
        var card = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("mtgcard:card")), 40);
        var metadata = new CompoundTag(); metadata.putString("set", "unh"); metadata.putString("collector_number", "30"); metadata.putString("name", "Cheatyface"); metadata.putString("layout", "normal"); metadata.putString("id", "ab857e73-ac20-4013-bc34-ed675de84c7c"); metadata.putString("oracle_id", "d6202ce1-8dbc-4aa5-8d62-5fe4005fa3d9");
        var custom = new CompoundTag(); custom.put("mtg_meta", metadata); custom.putString("mtg_uid", UUID.randomUUID().toString()); card.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        var items = NonNullList.withSize(102, ItemStack.EMPTY); items.set(0, card);
        DeckboxStorage.save(server, owner.registryAccess(), backing, "mtgcard:oak_deckbox", "fixture", items);
        var box = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("mtgcard:oak_deckbox"))); var data = new CompoundTag(); data.putString(DeckboxStorage.ITEM_ID_KEY, backing.toString()); box.set(DataComponents.CUSTOM_DATA, CustomData.of(data)); owner.setItemInHand(InteractionHand.MAIN_HAND, box);
        var fingerprint = MtgcardAdapter.readHeld(owner).fingerprint(); var count = Custody.current().records().size();
        server.getPlayerList().getPlayersByUUID().put(owner.getUUID(), owner);
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mtgcraft submit");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        ServerTickEvents.END_SERVER_TICK.register(running -> {
            try {
                if (owner.messages.stream().noneMatch(m -> m.contains("No approved engine implementation") && m.contains("Cheatyface"))) {
                    if (System.nanoTime() > deadline) throw new AssertionError("Unsupported-card validation deadline: " + owner.messages); return;
                }
                assert Custody.current().records().size() == count && !Custody.locked(server, backing);
                assert owner.getMainHandItem().getCount() == 1 && MtgcardAdapter.readHeld(owner).fingerprint().equals(fingerprint);
                System.out.println("PASS real unsupported command: No approved engine implementation — Cheatyface; original held box unchanged, no match or reservation");
                server.getPlayerList().getPlayersByUUID().clear(); server.halt(false);
            } catch (Throwable failure) { failure.printStackTrace(); server.getPlayerList().getPlayersByUUID().clear(); server.halt(false); }
        });
    }
    private static final class Player extends ServerPlayer {
        final List<String> messages = new ArrayList<>();
        Player(MinecraftServer server, UUID id, String name) { super(server, server.overworld(), new GameProfile(id, name), ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(net.minecraft.network.chat.Component message) { assert level().getServer().isSameThread(); messages.add(message.getString()); }
    }
}
