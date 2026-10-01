package mtgcraft.minecraft.mtgcard;

import com.mojang.authlib.GameProfile;
import com.spider.mtgcard.api.DeckboxStorage;
import com.spider.mtgcard.util.StackData;
import java.nio.charset.StandardCharsets;
import java.util.*;
import mtgcraft.protocol.Json;
import static mtgcraft.protocol.Protocol.*;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;

/** Each exercise JVM is halted without shutdown hooks; recovery runs in a second JVM. */
final class CrashProbe {
    static void exercise(MinecraftServer server) throws Exception {
        var point = System.getProperty("mtgcraft.fault"); var custody = Custody.current(); custody.proveAdmissionForProbe();
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "CrashOwner"), ClientInformation.createDefault());
        var backing = UUID.randomUUID(); var items = NonNullList.withSize(102, ItemStack.EMPTY);
        var card = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("mtgcard:card")), 40);
        var metadata = new CompoundTag(); metadata.putString("set", "m10"); metadata.putString("collector_number", "230"); metadata.putString("name", "Plains"); metadata.putString("layout", "normal");
        var custom = new CompoundTag(); custom.put("mtg_meta", metadata); custom.putString("mtg_uid", UUID.randomUUID().toString()); card.set(DataComponents.CUSTOM_DATA, CustomData.of(custom)); items.set(0, card);
        DeckboxStorage.save(server, player.registryAccess(), backing, "mtgcard:oak_deckbox", "fixture", items);
        var box = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("mtgcard:oak_deckbox"))); var data = new CompoundTag(); data.putString(DeckboxStorage.ITEM_ID_KEY, backing.toString()); box.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        player.setItemInHand(InteractionHand.MAIN_HAND, box); Custody.savePlayer(player);
        var source = MtgcardAdapter.readHeld(player);
        Compatibility compatibility; try (var input = Matches.class.getResourceAsStream("/mtgcraft-compatibility.json")) { compatibility = Json.read(input.readAllBytes(), Compatibility.class); }
        var request = MatchRequest.create(custody.worldId(), UUID.randomUUID(), List.of(new Seat(UUID.randomUUID(), Role.HUMAN, player.getUUID()), new Seat(UUID.randomUUID(), Role.AI, null)), DeckSnapshot.create(compatibility, source.entries()), "mad-plains-v1");
        var manifest = new CompoundTag(); manifest.putString("Point", point); manifest.putString("Owner", player.getUUID().toString()); manifest.putString("Backing", backing.toString());
        manifest.putString("Request", new String(Json.bytes(request), StandardCharsets.UTF_8)); manifest.putString("CardUID", custom.getString("mtg_uid").orElseThrow());
        Custody.write(server.getWorldPath(LevelResource.ROOT).resolve("probe.dat"), manifest);
        custody.injectCrashForProbe(actual -> {
            if (actual.equals(point)) { System.out.println("CRASH_INJECTED=" + point); Runtime.getRuntime().halt(88); }
        });
        custody.reserve(player, source, request); custody.intend(player.getUUID(), request.matchId(), Custody.Pending.CANCEL);
        var incarnation = UUID.randomUUID(); var result = new Result(UUID.randomUUID(), request.worldId(), request.matchId(), request.snapshot().digest(), incarnation, State.CANCELLED, 1, request.seats().stream().map(seat -> new SeatOutcome(seat, Outcome.NONE)).toList());
        custody.persistStatus(request.matchId(), new Status(1, request.worldId(), request.matchId(), request.requestDigest(), request.snapshot().digest(), incarnation, request.seats(), State.CANCELLED, 1, result));
        custody.claim(player, request.matchId());
        throw new AssertionError("Requested fault point was never reached: " + point);
    }
    static void recover(MinecraftServer server) throws Exception {
        var manifest = Custody.read(server.getWorldPath(LevelResource.ROOT).resolve("probe.dat")); var point = manifest.getString("Point").orElseThrow();
        var owner = UUID.fromString(manifest.getString("Owner").orElseThrow()); var backing = UUID.fromString(manifest.getString("Backing").orElseThrow());
        var request = Json.read(manifest.getString("Request").orElseThrow().getBytes(StandardCharsets.UTF_8), MatchRequest.class); var custody = Custody.current();
        assert custody.worldId().equals(request.worldId());
        var player = Custody.read(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat"));
        var ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess()); int boxes = 0;
        var inventory = player.getList("Inventory").orElseThrow();
        for (int i = 0; i < inventory.size(); i++) {
            var stack = ItemStack.CODEC.parse(ops, inventory.getCompound(i).orElseThrow()).getOrThrow();
            if (StackData.readCustom(stack).getString(DeckboxStorage.ITEM_ID_KEY).orElse("").equals(backing.toString())) boxes += stack.getCount();
        }
        int expectedBoxes = Set.of("before_intent", "after_intent", "after_marked", "after_claim_saved", "after_returned").contains(point) ? 1 : 0;
        assert boxes == expectedBoxes : point + " persisted item count " + boxes;
        if (point.equals("before_intent")) { assert custody.records().isEmpty() && !Custody.locked(server, backing); }
        else {
            var expected = switch (point) {
                case "after_reserved", "before_return_pending" -> Custody.Phase.RESERVED;
                case "after_return_pending", "before_claim" -> Custody.Phase.RETURN_PENDING;
                case "after_returned" -> Custody.Phase.RETURNED;
                default -> Custody.Phase.QUARANTINED;
            };
            var record = custody.requireOwner(owner, request.matchId()); assert record.request().equals(request) && record.phase() == expected;
            var original = ItemStack.CODEC.parse(ops, record.original()).getOrThrow(); assert original.getCount() == 1;
            assert StackData.readCustom(original).getString(DeckboxStorage.ITEM_ID_KEY).orElseThrow().equals(backing.toString());
            assert Custody.locked(server, backing) == (expected != Custody.Phase.RETURNED);
            assert custody.claim(owner, request.matchId()) == expected; // Offline status cannot deliver a replacement.
            assert custody.records().size() == 1;
        }
        var raw = Custody.read(DeckboxStorage.path(server, backing)); var stack = raw.getList("Items").orElseThrow().getCompound(0).orElseThrow().getCompound("Stack").orElseThrow();
        var physical = ItemStack.CODEC.parse(ops, stack).getOrThrow();
        assert physical.getCount() == 40 && StackData.readCustom(physical).getString("mtg_uid").orElseThrow().equals(manifest.getString("CardUID").orElseThrow());
        System.out.println("PASS fresh-JVM crash boundary " + point + ": original item/record preserved, ambiguity fenced, no replacement issued");
    }
}
