package mtgcraft.minecraft.mtgcard;

import com.spider.mtgcard.content.pack.cache.CardNBTUtil;
import com.spider.mtgcard.content.pack.cache.ScryfallModels;
import com.spider.mtgcard.util.StackData;
import com.spider.mtgcard.item.ModItemTags;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static mtgcraft.protocol.Protocol.*;

/** Narrow metadata checks; does not establish live custody or admission. */
public final class AdapterCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Items.PAPER.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        BuiltInRegistries.ITEM.freeze();
        BuiltInRegistries.ITEM.prepareTagReload(new TagLoader.LoadResult<>(BuiltInRegistries.ITEM.key(),
                Map.of(ModItemTags.TCG_CARD, List.of(Items.PAPER.builtInRegistryHolder())))).apply();
        var registries = HolderLookup.Provider.create(java.util.stream.Stream.of(BuiltInRegistries.ITEM));
        for (var layout : List.of("normal", "split", "transform")) {
            var record = new ScryfallModels.Card(); record.id = UUID.randomUUID().toString(); record.oracleId = UUID.randomUUID().toString();
            record.set = "m10"; record.collectorNumber = "146"; record.layout = layout; record.name = "Fixture";
            record.faces = new java.util.ArrayList<>();
            if (!layout.equals("normal")) for (var name : List.of("Front", "Back")) {
                var face = new ScryfallModels.Card.Face(); face.name = name; record.faces.add(face);
            }
            var stack = new ItemStack(Items.PAPER); CardNBTUtil.populateCardNbt(stack, record, UUID.randomUUID(), true);
            var metadata = StackData.readCustom(stack);
            var entry = MtgcardAdapter.entry(metadata, 4);
            assert entry.quantity() == 4 && entry.card().finish() == Finish.FOIL;
            assert entry.card().scryfallId().toString().equals(record.id) && entry.card().oracleId().toString().equals(record.oracleId);
            assert entry.card().faces().size() == (layout.equals("normal") ? 0 : 2);
            var conflict = metadata.copy(); conflict.getCompound("mtg_meta").orElseThrow().putString("id", UUID.randomUUID().toString());
            reject(() -> MtgcardAdapter.entry(conflict, 1));
            reject(() -> MtgcardAdapter.entry(metadata, 0));
            var backing = UUID.randomUUID();
            var root = new CompoundTag(); root.putInt("Version", 1); root.putString("Id", backing.toString()); root.putInt("SlotCount", 102);
            var slots = new ListTag(); var slot = new CompoundTag(); slot.putInt("Slot", 0);
            slot.put("Stack", ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), stack).getOrThrow());
            slots.add(slot); root.put("Items", slots);
            assert MtgcardAdapter.extract(root, backing, registries).equals(List.of(MtgcardAdapter.entry(metadata, 1)));
            reject(() -> MtgcardAdapter.extract(root, UUID.randomUUID(), registries));
            slots.add(slot.copy()); reject(() -> MtgcardAdapter.extract(root, backing, registries)); slots.removeLast();
            slot.putInt("Slot", 99); reject(() -> MtgcardAdapter.extract(root, backing, registries)); slot.putInt("Slot", 0);
            slot.put("Stack", new CompoundTag()); reject(() -> MtgcardAdapter.extract(root, backing, registries));
        }
        reject(() -> MtgcardAdapter.entry(new CompoundTag(), 1));
        System.out.println("PASS MTGCard metadata and backing conversion, faces/finish/quantity, conflicting IDs, duplicate slots, auxiliary slots and malformed stacks");
    }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected rejection");
    }
}
