package mtgcraft.minecraft.mtgcard;

import com.mojang.authlib.GameProfile;
import com.spider.mtgcard.api.DeckboxStorage;
import com.spider.mtgcard.deckbox.DeckboxBlockEntity;
import com.spider.mtgcard.util.StackData;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import static mtgcraft.protocol.Protocol.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Disposable headless server fixture. Never packaged into the production addon. */
public final class CustodyProbe implements ModInitializer {
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                if (System.getProperty("mtgcraft.fault") != null) { CrashProbe.exercise(server); return; }
                if (Boolean.getBoolean("mtgcraft.recover")) { CrashProbe.recover(server); server.halt(false); return; }
                var level = server.overworld(); var player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "CustodyProbe"), ClientInformation.createDefault());
                var pos = new BlockPos(0, 100, 0); var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse("mtgcard:oak_deckbox"));
                level.setBlock(pos, block.defaultBlockState(), 3);
                var placed = (DeckboxBlockEntity) level.getBlockEntity(pos); assert placed != null;
                var backing = placed.ensureStorageId();
                placed.setRgbTint(0x123456);
                var card = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("mtgcard:card")), 40);
                var meta = new net.minecraft.nbt.CompoundTag(); meta.putString("set", "m10"); meta.putString("collector_number", "230"); meta.putString("layout", "normal"); meta.putString("name", "Plains");
                var custom = new net.minecraft.nbt.CompoundTag(); custom.put("mtg_meta", meta); custom.putString("mtg_uid", UUID.randomUUID().toString()); card.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
                placed.setItem(0, card); placed.persistExternalRecord();
                var drops = Block.getDrops(placed.getBlockState(), level, pos, placed, player, new ItemStack(Items.DIAMOND_PICKAXE));
                assert drops.size() == 1 : "Normal block loot did not yield one deckbox";
                var box = drops.getFirst();
                boolean normalLoot = StackData.readCustom(box).getString(DeckboxStorage.ITEM_ID_KEY).orElse("").equals(backing.toString());
                System.out.println("NORMAL_LOOT_BOUND=" + normalLoot + " BLOCK_BACKING_PRESENT=" + placed.saveWithFullMetadata(level.registryAccess()).contains("DeckboxId"));
                if (!normalLoot) throw new AssertionError("CUSTODY_GATE_BLOCKED: normal survival loot lost the backing UUID");
                assert StackData.readCustom(box).getInt("mtgcard_deckbox_tint").orElse(-1) == 0x123456;
                player.setItemInHand(InteractionHand.MAIN_HAND, box);
                var custody = Custody.current(); custody.proveAdmissionForProbe();
                var source = MtgcardAdapter.readHeld(player);
                var compatibility = new Compatibility(1, "freeform", "1", "0".repeat(64), "fixture", "0".repeat(64));
                var request = MatchRequest.create(custody.worldId(), UUID.randomUUID(), List.of(new Seat(UUID.randomUUID(), Role.HUMAN, player.getUUID()), new Seat(UUID.randomUUID(), Role.AI, null)), DeckSnapshot.create(compatibility, source.entries()), "mad-plains-v1");
                var foreign = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "ForeignSource"), ClientInformation.createDefault());
                foreign.setItemInHand(InteractionHand.MAIN_HAND, box.copy());
                var foreignRequest = MatchRequest.create(custody.worldId(), UUID.randomUUID(), List.of(new Seat(UUID.randomUUID(), Role.HUMAN, foreign.getUUID()), new Seat(UUID.randomUUID(), Role.AI, null)), request.snapshot(), request.aiProfile());
                reject(() -> custody.reserve(foreign, source, foreignRequest));
                assert !foreign.getMainHandItem().isEmpty() && custody.records().isEmpty();
                box.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.EMPTY);
                reject(() -> MtgcardAdapter.readHeld(player)); box.remove(DataComponents.CONTAINER);
                custody.track(placed, true);
                reject(() -> custody.reserve(player, source, request)); // A held clone is not a safe source while the original is placed.
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                player.setPos(0.5, 100, 1.5);
                assert player.gameMode.destroyBlock(pos) : "Survival block destruction failed";
                custody.track(placed, false);
                var entities = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(3));
                assert entities.size() == 1 : "Survival breaking did not create exactly one original box";
                box = entities.getFirst().getItem(); entities.getFirst().discard();
                player.setItemInHand(InteractionHand.MAIN_HAND, box);
                var physical = MtgcardAdapter.readHeld(player);
                assert physical.entries().equals(source.entries()) && physical.backingId().equals(backing);
                var reservation = custody.reserve(player, physical, request);
                assert reservation.phase() == Custody.Phase.RESERVED && player.getMainHandItem().isEmpty();
                assert Custody.locked(server, backing) && DeckboxStorage.load(server, level.registryAccess(), backing).isEmpty();
                var backingBytes = Files.readAllBytes(DeckboxStorage.path(server, backing));
                var empty = net.minecraft.core.NonNullList.withSize(102, ItemStack.EMPTY);
                DeckboxStorage.save(server, level.registryAccess(), backing, "mtgcard:oak_deckbox", "alias", empty);
                assert java.util.Arrays.equals(backingBytes, Files.readAllBytes(DeckboxStorage.path(server, backing)));
                var target = pos.offset(2, 0, 0); var alias = box.copy();
                var result = ((BlockItem) alias.getItem()).place(new DirectionalPlaceContext(level, target, Direction.DOWN, alias, Direction.UP));
                assert result == net.minecraft.world.InteractionResult.FAIL && alias.getCount() == 1 && level.getBlockState(target).isAir();
                // An operator/unloaded-chunk alias can still appear; its cached contents must also be inaccessible.
                level.setBlock(target, block.defaultBlockState(), 3);
                var cached = (DeckboxBlockEntity) level.getBlockEntity(target); cached.setItem(0, card.copyWithCount(1)); cached.loadStorageId(backing); custody.track(cached, true);
                assert level.getBlockEntity(target) == null : "Reserved alias remained available to store/database/control/hopper lookups";
                assert net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, target) == null;
                assert cached.getItem(0).isEmpty() && cached.removeStack(0).isEmpty();
                assert !cached.canPlaceItem(0, card) && !cached.canTakeItem(cached, 0, card) && !cached.stillValid(player);
                cached.setItem(0, card.copyWithCount(1)); cached.persistExternalRecord();
                assert java.util.Arrays.equals(backingBytes, Files.readAllBytes(DeckboxStorage.path(server, backing)));
                var cube = (net.minecraft.world.entity.monster.cubemob.SulfurCube) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:sulfur_cube")).create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                var swallowed = alias.copy(); var embedded = net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(card.copy())); swallowed.set(DataComponents.CONTAINER, embedded);
                cube.setItemSlot(net.minecraft.world.entity.EquipmentSlot.BODY, swallowed);
                int beforeDrops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(target).inflate(10)).size();
                com.spider.mtgcard.deckbox.SulfurCubeDeckboxCards.dropRandomCard(cube, level);
                assert cube.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.BODY).get(DataComponents.CONTAINER).equals(embedded);
                assert level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(target).inflate(10)).size() == beforeDrops;
                assert com.spider.mtgcard.api.SleeveApplication.apply(server, level.registryAccess(), alias, null) == 0;
                assert java.util.Arrays.equals(backingBytes, Files.readAllBytes(DeckboxStorage.path(server, backing)));
                var again = new Custody(server); Custody.install(again);
                assert again.records().size() == 1 && again.records().iterator().next().request().equals(request) && Custody.locked(server, backing);
                var incarnation = UUID.randomUUID();
                var prepared = new Status(1, request.worldId(), request.matchId(), request.requestDigest(), request.snapshot().digest(), incarnation, request.seats(), State.PREPARED, 1, null);
                var active = new Status(1, request.worldId(), request.matchId(), request.requestDigest(), request.snapshot().digest(), incarnation, request.seats(), State.ACTIVE, 2, null);
                again.persistStatus(request.matchId(), prepared); again.intend(player.getUUID(), request.matchId(), Custody.Pending.START); again.persistStatus(request.matchId(), active);
                assert again.persistStatus(request.matchId(), prepared).equals(active) : "Delayed prepared status regressed active state";
                var resultRecord = new Result(UUID.randomUUID(), request.worldId(), request.matchId(), request.snapshot().digest(), incarnation, State.CANCELLED, 3, request.seats().stream().map(seat -> new SeatOutcome(seat, Outcome.NONE)).toList());
                var terminal = new Status(1, request.worldId(), request.matchId(), request.requestDigest(), request.snapshot().digest(), incarnation, request.seats(), State.CANCELLED, 3, resultRecord);
                again.intend(player.getUUID(), request.matchId(), Custody.Pending.CANCEL);
                assert again.persistStatus(request.matchId(), terminal).equals(terminal) && again.persistStatus(request.matchId(), terminal).equals(terminal);
                assert again.requireOwner(player.getUUID(), request.matchId()).phase() == Custody.Phase.RETURN_PENDING : "Terminal result did not atomically record its return intent";
                assert again.persistStatus(request.matchId(), prepared).equals(terminal);
                var heldFile = Custody.read(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(player.getUUID() + ".dat"));
                assert heldFile.getList("Inventory").orElseThrow().isEmpty() : "Escrow removal was not persisted";
                assert again.claim(player.getUUID(), request.matchId()) == Custody.Phase.RETURN_PENDING : "Offline claim delivered an item";
                var stranger = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "Stranger"), ClientInformation.createDefault());
                reject(() -> again.claim(stranger, request.matchId()));
                for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
                assert again.claim(player, request.matchId()) == Custody.Phase.RETURN_PENDING && Custody.locked(server, backing);
                player.getInventory().setItem(0, ItemStack.EMPTY);
                assert again.claim(player, request.matchId()) == Custody.Phase.RETURNED && !Custody.locked(server, backing);
                var returned = player.getInventory().getItem(0);
                assert returned.getCount() == 1 && StackData.readCustom(returned).getString(DeckboxStorage.ITEM_ID_KEY).orElseThrow().equals(backing.toString());
                assert StackData.readCustom(returned).getInt("mtgcard_deckbox_tint").orElseThrow() == 0x123456;
                assert again.claim(player, request.matchId()) == Custody.Phase.RETURNED && player.getInventory().countItem(returned.getItem()) == 1;
                assert cached.getItem(0).getCount() == 40 : "Unlock reused stale alias cache instead of authoritative backing";
                var afterReturn = new Custody(server); Custody.install(afterReturn);
                assert afterReturn.requireOwner(player.getUUID(), request.matchId()).phase() == Custody.Phase.RETURNED;
                assert afterReturn.requireOwner(player.getUUID(), request.matchId()).observed().equals(terminal);
                assert afterReturn.claim(player, request.matchId()) == Custody.Phase.RETURNED && player.getInventory().countItem(returned.getItem()) == 1;
                var collision = MatchRequest.create(request.worldId(), UUID.randomUUID(), request.seats(), request.snapshot(), request.aiProfile());
                var collisionResult = new Result(resultRecord.resultId(), collision.worldId(), collision.matchId(), collision.snapshot().digest(), incarnation, State.CANCELLED, 3, collision.seats().stream().map(seat -> new SeatOutcome(seat, Outcome.NONE)).toList());
                var collisionStatus = new Status(1, collision.worldId(), collision.matchId(), collision.requestDigest(), collision.snapshot().digest(), incarnation, collision.seats(), State.CANCELLED, 3, collisionResult);
                var journalDirectory = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mtgcraft");
                var collisionFile = journalDirectory.resolve("escrow-" + collision.matchId() + ".dat");
                var collisionTag = Custody.read(journalDirectory.resolve("escrow-" + request.matchId() + ".dat"));
                collisionTag.putString("Request", new String(mtgcraft.protocol.Json.bytes(collision), java.nio.charset.StandardCharsets.UTF_8));
                collisionTag.putString("Status", new String(mtgcraft.protocol.Json.bytes(collisionStatus), java.nio.charset.StandardCharsets.UTF_8));
                Custody.write(collisionFile, collisionTag);
                try { new Custody(server); throw new AssertionError("Duplicate result identity was accepted"); }
                catch (java.io.IOException expected) { assert expected.getMessage().contains("Result ID"); }
                Files.delete(collisionFile);
                var placement = ((BlockItem) returned.getItem()).place(new DirectionalPlaceContext(level, pos.offset(4, 0, 0), Direction.DOWN, returned, Direction.UP));
                assert placement != net.minecraft.world.InteractionResult.FAIL;
                var restored = (DeckboxBlockEntity) level.getBlockEntity(pos.offset(4, 0, 0));
                assert restored.getStorageId().equals(backing) && restored.getItem(0).getCount() == 40;
                var wrongWorld = new Status(1, UUID.randomUUID(), request.matchId(), request.requestDigest(), request.snapshot().digest(), incarnation, request.seats(), State.PREPARED, 1, null);
                reject(() -> afterReturn.persistStatus(request.matchId(), wrongWorld));
                assert afterReturn.requireOwner(player.getUUID(), request.matchId()).phase() == Custody.Phase.QUARANTINED;
                assert afterReturn.requireOwner(player.getUUID(), request.matchId()).observed().equals(terminal);
                // This fixture now owns the returned physical record; restore its acknowledged tombstone before the independent delivery-crash case.
                var restoredJournal = Custody.read(journalDirectory.resolve("escrow-" + request.matchId() + ".dat")); restoredJournal.putString("Phase", "RETURNED"); Custody.write(journalDirectory.resolve("escrow-" + request.matchId() + ".dat"), restoredJournal);
                // Inject a crash at the persisted delivery intent, independently of whether an item reached player data.
                var uncertain = MatchRequest.create(request.worldId(), UUID.randomUUID(), request.seats(), request.snapshot(), request.aiProfile());
                var root = Custody.read(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mtgcraft/escrow-" + request.matchId() + ".dat"));
                root.putString("Request", new String(mtgcraft.protocol.Json.bytes(uncertain), java.nio.charset.StandardCharsets.UTF_8)); root.putString("Phase", "CLAIMING");
                root.remove("Status");
                Custody.write(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mtgcraft/escrow-" + uncertain.matchId() + ".dat"), root);
                var recovered = new Custody(server); Custody.install(recovered);
                assert recovered.requireOwner(player.getUUID(), uncertain.matchId()).phase() == Custody.Phase.QUARANTINED && Custody.locked(server, backing);
                reject(() -> recovered.claim(player, uncertain.matchId()));
                System.out.println("PASS live custody: survival breaking keeps one original box/UUID/tint, placed rejection, durable physical transfer, storage guards, cached alias guards, placement rejection and journal reload");
                System.out.println("PASS return claims: offline/full pending, owner binding, one original item, durable tombstone, re-placement, fresh alias cache and crash quarantine");
                System.out.println("PASS world journal: immutable request/result bindings, monotonic observations, terminal replay/reload and independent return phase");
                CommandProbe.run(server, card);
            } catch (Throwable failure) { failure.printStackTrace(); server.halt(false); }
        });
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void reject(Checked action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected rejection");
    }
}
