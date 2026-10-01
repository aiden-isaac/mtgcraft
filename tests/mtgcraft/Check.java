package mtgcraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mtgcraft.protocol.*;
import mtgcraft.service.Catalog;
import static mtgcraft.protocol.Protocol.*;

/** Run with bash scripts/check.sh; assertions must be enabled. */
public final class Check {
    public static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected rejection");
    }
    public static void main(String[] args) throws Exception {
        boolean assertions = false; assert assertions = true;
        if (!assertions) throw new IllegalStateException("enable assertions");
        var card = new CardIdentity("m10/146", null, null, "m10", "146", Finish.NONFOIL, "normal", List.of(), "Lightning Bolt");
        var compatibility = new Compatibility(VERSION, "freeform", "1", "0".repeat(64), "fixture", "1".repeat(64));
        var input = new ArrayList<>(List.of(new Entry(card, 2, Section.MAIN), new Entry(card, 1, Section.MAIN)));
        var snapshot = DeckSnapshot.create(compatibility, input);
        input.clear();
        assert snapshot.entries().size() == 1 && snapshot.entries().getFirst().quantity() == 3;
        assert snapshot.equals(DeckSnapshot.create(compatibility, List.of(new Entry(card, 3, Section.MAIN))));
        rejected(() -> new DeckSnapshot(compatibility, snapshot.entries(), "f".repeat(64)));
        var seats = List.of(new Seat(UUID.randomUUID(), Role.HUMAN, UUID.randomUUID()), new Seat(UUID.randomUUID(), Role.AI, null));
        var request = MatchRequest.create(UUID.randomUUID(), UUID.randomUUID(), seats, snapshot, "mad-v1");
        assert request.equals(MatchRequest.create(request.worldId(), request.matchId(), seats.reversed(), snapshot, "mad-v1"));
        rejected(() -> new MatchRequest(VERSION, request.worldId(), request.matchId(), seats, snapshot, "other-ai", request.requestDigest()));
        rejected(() -> new Entry(card, 0, Section.MAIN));
        System.out.println("PASS immutable snapshots, quantities, canonical encoding and request binding");
        mapping();
    }
    private static void mapping() throws Exception {
        var path = java.nio.file.Path.of("service/catalog/manifest.json");
        var catalog = new Catalog(path, printing -> true);
        var c = catalog.compatibility();
        var printings = catalog.manifest().printings();
        for (var p : printings) {
            for (var finish : Finish.values()) {
                var deck = DeckSnapshot.create(c, List.of(new Entry(p.card(finish), 1, Section.MAIN)));
                var original = deck.digest();
                var resolution = catalog.resolve(deck);
                assert resolution.validation().valid() == (p.engine() != null);
                if (p.engine() != null) assert resolution.cards().size() == 1;
                assert deck.digest().equals(original);
            }
        }
        var bolt = printings.get(0);
        var reprint = printings.get(1);
        var reprintDeck = DeckSnapshot.create(c, List.of(new Entry(reprint.card(Finish.FOIL), 4, Section.MAIN)));
        assert catalog.resolve(reprintDeck).cards().getFirst().printing().equals(reprint.engine());
        var fallback = new Catalog(path, printing -> !printing.equals(reprint.engine()));
        assert fallback.resolve(reprintDeck).cards().getFirst().printing().equals(bolt.engine());
        var missing = new Catalog(path, printing -> false);
        assert missing.resolve(reprintDeck).validation().errors().getFirst().code().equals("MISSING_ENGINE_CARD");
        var contradictory = new CardIdentity(bolt.cardKey(), reprint.scryfallId(), bolt.oracleId(), bolt.set(),
                bolt.collectorNumber(), Finish.NONFOIL, bolt.layout(), bolt.faces(), bolt.label());
        var errors = catalog.resolve(DeckSnapshot.create(c, List.of(new Entry(contradictory, 1, Section.MAIN),
                new Entry(printings.get(4).card(Finish.NONFOIL), 1, Section.MAIN)))).validation().errors();
        assert errors.size() == 2 && errors.stream().anyMatch(e -> e.code().equals("IDENTITY_CONFLICT"));
        var wrongFaces = new CardIdentity(printings.get(2).cardKey(), printings.get(2).scryfallId(), printings.get(2).oracleId(),
                "apc", "128", Finish.NONFOIL, "split", List.of("Fire"), "Fire");
        assert !catalog.resolve(DeckSnapshot.create(c, List.of(new Entry(wrongFaces, 1, Section.MAIN)))).validation().valid();
        var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
        var rows = json.getAsJsonArray("printings"); rows.add(rows.get(0).deepCopy());
        var ambiguous = new Catalog(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), printing -> true);
        assert ambiguous.resolve(DeckSnapshot.create(ambiguous.compatibility(), List.of(new Entry(bolt.card(Finish.NONFOIL), 1, Section.MAIN))))
                .validation().errors().getFirst().code().equals("AMBIGUOUS_CARD");
        assert !catalog.resolve(snapshotWithWrongCompatibility(c, bolt.card(Finish.NONFOIL))).validation().valid();
        System.out.println("PASS pinned normal/reprint/finish/split/transform mapping, conflicts, ambiguity, missing cards and compatibility");
    }
    private static DeckSnapshot snapshotWithWrongCompatibility(Compatibility c, CardIdentity card) {
        return DeckSnapshot.create(new Compatibility(VERSION, c.format(), c.formatVersion(), "f".repeat(64), c.engineBuild(), c.catalogDigest()),
                List.of(new Entry(card, 1, Section.MAIN)));
    }
}
