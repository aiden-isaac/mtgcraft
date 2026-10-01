package mtgcraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mtgcraft.protocol.*;
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
    }
}
