import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import mage.cards.SplitCard;
import mage.cards.decks.*;
import mage.cards.repository.CardRepository;

/** Fixture-only exact lookup experiment. This is not the production resolver. */
class MappingProbe {
    static void run(String fixturePath, String projectionPath) throws Exception {
        var fixtures = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(fixturePath)), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
        var rows = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(projectionPath)), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
        assert rows.size() == fixtures.size() * 2;
        Set<String> instances = new HashSet<>();
        Map<String, String> printingClasses = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i).getAsJsonObject();
            var fixture = fixtures.get(i / 2).getAsJsonObject();
            assert row.get("scryfall_id").equals(fixture.get("id"));
            for (String key : new String[]{"oracle_id", "set", "collector_number", "name", "layout", "faces"}) {
                assert row.get(key).equals(fixture.get(key)) : key;
            }
            assert row.get("foil").getAsBoolean() == (i % 2 == 1);
            assert instances.add(row.get("instance").getAsString());
            String set = row.get("set").getAsString().toUpperCase(Locale.ROOT);
            String number = row.get("collector_number").getAsString();
            var info = CardRepository.instance.findCard(set, number);
            if (set.equals("UNH") && number.equals("30")) {
                assert info == null : "upstream now supports fixture; choose another negative case";
                System.out.println("PASS unsupported: Cheatyface UNH/30; no fallback");
                continue;
            }
            assert info != null : set + "/" + number;
            var card = info.createCard();
            assert card != null;
            String layout = row.get("layout").getAsString();
            if (layout.equals("split")) {
                assert card instanceof SplitCard;
                var split = (SplitCard) card;
                assert split.getLeftHalfCard().getName().equals("Fire");
                assert split.getRightHalfCard().getName().equals("Ice");
            } else if (layout.equals("transform")) {
                assert card.isTransformable();
                assert card.getName().equals("Delver of Secrets");
                assert card.getSecondCardFace().getName().equals("Insectile Aberration");
            } else {
                assert card.getName().equals(row.get("name").getAsString());
            }
            var list = new DeckCardLists();
            list.getCards().add(new DeckCardInfo(info.getName(), number, set));
            assert Deck.load(list, false, false).getCards().size() == 1;
            String key = row.get("scryfall_id").getAsString();
            String previous = printingClasses.put(key, card.getClass().getName());
            if (previous != null) assert previous.equals(card.getClass().getName());
            System.out.println("PASS mapping: " + set + "/" + number + " foil=" + row.get("foil") + " -> " + card.getClass().getName());
        }
        assert fixtures.get(0).getAsJsonObject().get("oracle_id").equals(fixtures.get(1).getAsJsonObject().get("oracle_id"));
        assert !fixtures.get(0).getAsJsonObject().get("id").equals(fixtures.get(1).getAsJsonObject().get("id"));
        assert printingClasses.get(fixtures.get(0).getAsJsonObject().get("id").getAsString()).equals(printingClasses.get(fixtures.get(1).getAsJsonObject().get("id").getAsString()));
        System.out.println("PASS reprint/finish: distinct printings, same Oracle and gameplay implementation");
    }
}
