import com.spider.mtgcard.api.DeckboxRemovalCallbackRegistry;
import com.spider.mtgcard.content.pack.cache.CardNBTUtil;
import com.spider.mtgcard.content.pack.cache.ScryfallModels;
import com.spider.mtgcard.util.StackData;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import com.google.gson.*;

/** Narrow upstream API experiment, not a Minecraft integration or custody test. */
public class MtgcardProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("record")) {
            var record = net.minecraft.nbt.NbtIo.readCompressed(Path.of(args[1]), net.minecraft.nbt.NbtAccounter.defaultQuota());
            assert record.getInt("Version").orElseThrow() == 1;
            assert record.getString("Id").orElseThrow().equals("11111111-1111-4111-8111-111111111111");
            var items = record.getList("Items").orElseThrow();
            assert items.size() == Integer.parseInt(args[2]);
            if (!items.isEmpty()) {
                var stackTag = items.getCompound(0).orElseThrow().getCompound("Stack").orElseThrow();
                assert stackTag.getString("id").orElseThrow().equals("mtgcard:card");
                assert stackTag.toString().contains("435589bb-27c6-4a6d-9d63-394d5092b9d8");
            }
            System.out.println("PASS live backing record: " + args[1] + " slots=" + items.size());
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var card = new ScryfallModels.Card();
        // Synthetic IDs test metadata round-tripping, not real catalog resolution.
        card.id = UUID.randomUUID().toString();
        card.oracleId = UUID.randomUUID().toString();
        card.name = "Probe card";
        card.set = "test";
        card.collectorNumber = "1";
        card.layout = "normal";
        UUID instance = UUID.randomUUID();
        // This isolated metadata probe has no world/datapack component bootstrap.
        Items.PAPER.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        ItemStack stack = new ItemStack(Items.PAPER);
        CardNBTUtil.populateCardNbt(stack, card, instance, true);
        var data = StackData.readCustom(stack);
        var meta = data.getCompound("mtg_meta").orElseThrow();
        assert meta.getString("scryfall_id").orElseThrow().equals(card.id);
        assert meta.getString("oracle_id").orElseThrow().equals(card.oracleId);
        assert data.getString("mtg_uid").orElseThrow().equals(instance.toString());
        assert data.getBoolean("mtg_foil").orElseThrow();
        assert !card.id.equals(card.oracleId);
        System.out.println("PASS metadata: printing, Oracle, instance and finish remain distinct");

        boolean[] reached = {false};
        DeckboxRemovalCallbackRegistry.register((level, pos, box) -> {
            throw new IllegalStateException("attempted veto");
        });
        DeckboxRemovalCallbackRegistry.register((level, pos, box) -> reached[0] = true);
        DeckboxRemovalCallbackRegistry.fire(null, null, null);
        assert reached[0] : "callbacks changed: re-evaluate the custody hook";
        System.out.println("PASS observation: removal callback cannot veto; exceptions are swallowed");
        System.out.println("NOT PROVEN: live deckbox escrow, backing aliases, menus, automation, crash recovery");
        if (args.length == 2) {
            JsonArray exported = new JsonArray();
            for (var element : JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray()) {
                var fixture = element.getAsJsonObject();
                for (boolean foil : new boolean[]{false, true}) {
                    var rec = new ScryfallModels.Card();
                    rec.id = fixture.get("id").getAsString();
                    rec.oracleId = fixture.get("oracle_id").getAsString();
                    rec.name = fixture.get("name").getAsString();
                    rec.set = fixture.get("set").getAsString();
                    rec.collectorNumber = fixture.get("collector_number").getAsString();
                    rec.layout = fixture.get("layout").getAsString();
                    rec.faces = new ArrayList<>();
                    for (var name : fixture.getAsJsonArray("faces")) {
                        var face = new ScryfallModels.Card.Face();
                        face.name = name.getAsString();
                        rec.faces.add(face);
                    }
                    var physical = new ItemStack(Items.PAPER);
                    CardNBTUtil.populateCardNbt(physical, rec, UUID.randomUUID(), foil);
                    var root = StackData.readCustom(physical);
                    var stored = root.getCompound("mtg_meta").orElseThrow();
                    var row = new JsonObject();
                    for (String key : new String[]{"scryfall_id", "oracle_id", "set", "collector_number", "name", "layout"}) {
                        row.addProperty(key, stored.getString(key).orElseThrow());
                    }
                    row.addProperty("foil", root.getBoolean("mtg_foil").orElseThrow());
                    row.addProperty("instance", root.getString("mtg_uid").orElseThrow());
                    var faces = new JsonArray();
                    stored.getList("card_faces").ifPresent(list -> {
                        for (int i = 0; i < list.size(); i++) {
                            faces.add(list.getCompound(i).orElseThrow().getString("name").orElseThrow());
                        }
                    });
                    row.add("faces", faces);
                    exported.add(row);
                }
            }
            Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(exported));
            System.out.println("PASS exported " + exported.size() + " metadata projections for XMage probe");
        }
    }
}
