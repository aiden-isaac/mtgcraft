package mtgcraft.minecraft.mtgcard;

import com.spider.mtgcard.api.DeckboxStorage;
import com.spider.mtgcard.deckbox.DeckboxBlockEntity;
import com.spider.mtgcard.registry.ModBlocks;
import com.spider.mtgcard.item.ModItemTags;
import com.spider.mtgcard.util.StackData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import mtgcraft.protocol.Canonical;
import static mtgcraft.protocol.Protocol.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** All MTGCard layouts stay here. Extraction does not authorize physical admission. */
public final class MtgcardAdapter {
    public record Source(UUID owner, UUID backingId, String fingerprint, List<Entry> entries, ItemStack original) {
        public Source { entries = List.copyOf(entries); original = original.copy(); }
        @Override public ItemStack original() { return original.copy(); }
    }
    public static Source readHeld(ServerPlayer player) throws IOException {
        var server = player.level().getServer();
        if (!server.isSameThread()) throw new IllegalArgumentException("Read the held source on the server thread");
        var box = player.getMainHandItem();
        if (!ModBlocks.isDeckbox(box) || box.getCount() != 1) throw new IllegalArgumentException("Hold one MTGCard deckbox in your main hand");
        var data = StackData.readCustom(box);
        var backing = uuid(data.getString(DeckboxStorage.ITEM_ID_KEY).orElseThrow(() -> new IllegalArgumentException("Deckbox has no backing ID")));
        if (box.has(DataComponents.CONTAINER) || data.contains("BlockEntityTag")) throw new IllegalArgumentException("Legacy or embedded container data is unsupported");
        var path = DeckboxStorage.path(server, backing);
        if (!Files.isRegularFile(path) || Files.size(path) > 2 * 1024 * 1024) throw new IllegalArgumentException("Backing record is missing or too large");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(2 * 1024 * 1024 + 1); }
        if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Backing record is too large");
        var record = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(2L * 1024 * 1024));
        var item = (net.minecraft.world.item.BlockItem) box.getItem();
        if (!record.getString("Type").orElseThrow().equals(BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString()))
            throw new IllegalArgumentException("Deckbox type and backing record disagree");
        var entries = extract(record, backing, player.registryAccess());
        var encodedBox = ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, player.registryAccess()), box).getOrThrow();
        var buffer = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(buffer)) {
            output.writeInt(bytes.length); output.write(bytes);
            NbtIo.write((CompoundTag) encodedBox, output);
        }
        return new Source(player.getUUID(), backing, Canonical.sha256(buffer.toByteArray()), entries, box);
    }
    public static void recheck(ServerPlayer player, Source source) throws IOException {
        var now = readHeld(player);
        if (!now.owner().equals(source.owner()) || !now.backingId().equals(source.backingId()) || !now.fingerprint().equals(source.fingerprint()))
            throw new IllegalArgumentException("Deckbox changed during validation; submit it again");
    }
    static List<Entry> extract(CompoundTag root, UUID backing, HolderLookup.Provider registries) {
        if (root.getInt("Version").orElse(-1) != DeckboxStorage.FORMAT_VERSION
                || !uuid(root.getString("Id").orElseThrow()).equals(backing)
                || root.getInt("SlotCount").orElse(-1) != DeckboxBlockEntity.INVENTORY_SIZE)
            throw new IllegalArgumentException("Invalid backing record version, identity or layout");
        var slots = root.getList("Items").orElseThrow(() -> new IllegalArgumentException("Record has no item list"));
        var entries = new ArrayList<Entry>(); var seen = new HashSet<Integer>();
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.getCompound(i).orElseThrow(() -> new IllegalArgumentException("Malformed stored slot"));
            int index = slot.getInt("Slot").orElse(-1);
            if (index < 0 || index >= DeckboxBlockEntity.INVENTORY_SIZE || !seen.add(index)) throw new IllegalArgumentException("Invalid or repeated stored slot");
            if (index >= DeckboxBlockEntity.MAIN_SLOTS) throw new IllegalArgumentException("Empty all side/commander slots before submitting");
            var tag = slot.getCompound("Stack").orElseThrow(() -> new IllegalArgumentException("Stored stack is missing"));
            var stack = ItemStack.CODEC.parse(ops, tag).getOrThrow(IllegalArgumentException::new); // Never silently discard undecodable slots.
            if (stack.isEmpty() || !stack.is(ModItemTags.TCG_CARD)) throw new IllegalArgumentException("Deckbox contains an empty or non-card entry");
            entries.add(entry(StackData.readCustom(stack), stack.getCount()));
        }
        return Canonical.entries(entries);
    }
    static Entry entry(CompoundTag root, int quantity) {
        var metadata = root.getCompound("mtg_meta").orElseThrow(() -> new IllegalArgumentException("Card has no MTG metadata"));
        var scryfall = optionalUuid(metadata, "scryfall_id"); var legacy = optionalUuid(metadata, "id");
        if (scryfall != null && legacy != null && !scryfall.equals(legacy)) throw new IllegalArgumentException("Conflicting printing IDs");
        if (scryfall == null) scryfall = legacy;
        var oracle = optionalUuid(metadata, "oracle_id");
        var set = required(metadata, "set").toLowerCase(Locale.ROOT); var number = required(metadata, "collector_number");
        var layout = required(metadata, "layout"); var label = required(metadata, "name");
        var faces = new ArrayList<String>();
        if (metadata.contains("card_faces")) {
            var list = metadata.getList("card_faces").orElseThrow(() -> new IllegalArgumentException("Malformed faces"));
            for (int i = 0; i < list.size(); i++) faces.add(required(list.getCompound(i).orElseThrow(), "name"));
        }
        if (root.contains("mtg_foil") && root.getBoolean("mtg_foil").isEmpty()) throw new IllegalArgumentException("Invalid finish");
        var finish = root.getBoolean("mtg_foil").orElse(false) ? Finish.FOIL : Finish.NONFOIL;
        return new Entry(new CardIdentity(scryfall == null ? set + "/" + number : scryfall.toString(), scryfall, oracle,
                set, number, finish, layout, faces, label), quantity, Section.MAIN);
    }
    private static String required(CompoundTag tag, String field) {
        var value = tag.getString(field).orElseThrow(() -> new IllegalArgumentException("Missing card field: " + field));
        text(value); return value;
    }
    private static UUID optionalUuid(CompoundTag tag, String field) {
        if (!tag.contains(field)) return null;
        var value = tag.getString(field).orElseThrow(() -> new IllegalArgumentException("Invalid card ID"));
        return value.isBlank() ? null : uuid(value);
    }
    private static UUID uuid(String value) {
        var uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) throw new IllegalArgumentException("Noncanonical UUID");
        return uuid;
    }
}
