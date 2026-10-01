package mtgcraft;

import java.nio.file.Path;
import java.util.List;
import mage.cards.decks.*;
import mage.cards.repository.*;
import mage.deck.Freeform;
import mtgcraft.service.EngineDecks;
import static mtgcraft.protocol.Protocol.*;

/** Real pinned engine validation, no native client or physical custody. */
public final class EngineCheck {
    public static void main(String[] args) throws Exception {
        org.apache.log4j.BasicConfigurator.configure();
        org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.WARN);
        RepositoryUtil.bootstrapLocalDb(); CardScanner.scan();
        DeckValidatorFactory.instance.addDeckType("Constructed - Freeform", Freeform.class);
        var catalog = EngineDecks.catalog(Path.of(args[0]));
        var decks = new EngineDecks(catalog);
        var rows = catalog.manifest().printings();
        var plains = rows.getLast().card(Finish.NONFOIL);
        for (int copies : new int[]{40, 60, 100}) {
            var snapshot = DeckSnapshot.create(catalog.compatibility(), List.of(new Entry(plains, copies, Section.MAIN)));
            var prepared = decks.prepare(snapshot);
            assert prepared.validation().valid();
            var installed = Deck.load(prepared.list(), false, false);
            assert installed.getCards().size() == copies;
            decks.verify(snapshot, installed);
            installed.getCards().remove(installed.getCards().iterator().next());
            Check.rejected(() -> decks.verify(snapshot, installed));
        }
        var small = decks.prepare(DeckSnapshot.create(catalog.compatibility(), List.of(new Entry(plains, 39, Section.MAIN))));
        assert !small.validation().valid() && small.validation().errors().getFirst().code().equals("FORMAT_INVALID");
        for (int index : new int[]{0, 1, 2, 3}) {
            var snapshot = DeckSnapshot.create(catalog.compatibility(), List.of(new Entry(plains, 56, Section.MAIN),
                    new Entry(rows.get(index).card(Finish.FOIL), 4, Section.MAIN)));
            var prepared = decks.prepare(snapshot);
            assert prepared.validation().valid() : prepared.validation();
            decks.verify(snapshot, Deck.load(prepared.list(), false, false));
        }
        var unsupported = decks.prepare(DeckSnapshot.create(catalog.compatibility(), List.of(new Entry(plains, 59, Section.MAIN),
                new Entry(rows.get(4).card(Finish.NONFOIL), 1, Section.MAIN))));
        assert !unsupported.validation().valid() && unsupported.list() == null;
        System.out.println("PASS real engine exact deck/quantities/faces, foil/reprints, format validation, >99-copy row handling and unsupported rejection");
        if (args.length == 2) {
            managed(catalog, Path.of(args[1]));
            System.exit(0); // Upstream manager executors belong to this disposable test JVM.
        }
    }
    private static void managed(mtgcraft.service.Catalog catalog, Path config) throws Exception {
        mage.server.game.GameFactory.instance.addGameType("Two Player Duel", new mage.game.TwoPlayerDuelType(), mage.game.TwoPlayerMatch.class);
        mage.server.game.PlayerFactory.instance.addPlayerType("Human", mage.player.human.HumanPlayer.class);
        mage.server.game.PlayerFactory.instance.addPlayerType("Computer - mad", mage.player.ai.ComputerPlayerControllableProxy.class);
        var managers = new mage.server.MainManagerFactory(new mage.server.util.ConfigWrapper(mage.server.util.ConfigFactory.loadFromFile(config.toString())));
        var directory = java.nio.file.Files.createTempDirectory("managed-check-");
        var world = java.util.UUID.randomUUID();
        var snapshot = DeckSnapshot.create(catalog.compatibility(), List.of(new Entry(catalog.manifest().printings().getLast().card(Finish.NONFOIL), 60, Section.MAIN)));
        var seats = List.of(new Seat(java.util.UUID.randomUUID(), Role.HUMAN, java.util.UUID.randomUUID()), new Seat(java.util.UUID.randomUUID(), Role.AI, null));
        var request = MatchRequest.create(world, java.util.UUID.randomUUID(), seats, snapshot, mtgcraft.service.ManagedMatches.AI_PROFILE);
        Status original;
        try (var state = new mtgcraft.service.ServiceState(directory)) {
            var service = new mtgcraft.service.ManagedMatches(state, world, 2, catalog, managers);
            original = service.prepareMatch(request);
            assert original.state() == State.PREPARED;
            assert service.prepareMatch(request).equals(original);
            assert managers.tableManager().getTables().size() == 1;
            var changed = MatchRequest.create(world, request.matchId(), List.of(new Seat(java.util.UUID.randomUUID(), Role.HUMAN, seats.getFirst().playerId()), seats.getLast()), snapshot, request.aiProfile());
            expectFault(() -> service.prepareMatch(changed), "REQUEST_CONFLICT");
            expectFault(() -> service.startMatch(request.matchId(), new Operation(VERSION, world, request.requestDigest())), "SEAT_NOT_READY");
            var second = MatchRequest.create(world, java.util.UUID.randomUUID(), List.of(new Seat(java.util.UUID.randomUUID(), Role.HUMAN, java.util.UUID.randomUUID()), new Seat(java.util.UUID.randomUUID(), Role.AI, null)), snapshot, request.aiProfile());
            service.prepareMatch(second);
            var third = MatchRequest.create(world, java.util.UUID.randomUUID(), seats, snapshot, request.aiProfile());
            expectFault(() -> service.prepareMatch(third), "CAPACITY");
            assert managers.tableManager().getTables().size() == 2;
            // Inject a creation intent with no acknowledged table association.
            var pending = MatchRequest.create(world, java.util.UUID.randomUUID(), seats, snapshot, request.aiProfile());
            var record = new mtgcraft.service.ManagedMatches.Record(pending, state.incarnation(), true, null, null, null, null, null, null, null, null);
            state.write("match-" + pending.matchId() + ".json", mtgcraft.protocol.Json.bytes(record));
            for (var table : managers.tableManager().getTables()) managers.tableManager().removeTable(table.getId());
            managers.userManager().removeUser(managers.userManager().getUserByName("service_owner").orElseThrow().getId());
        }
        try (var state = new mtgcraft.service.ServiceState(directory)) {
            var replacement = new mtgcraft.service.ManagedMatches(state, world, 2, catalog, managers);
            var interrupted = replacement.getStatus(request.matchId());
            assert interrupted.state() == State.INTERRUPTED;
            assert interrupted.serviceIncarnation().equals(original.serviceIncarnation());
            assert replacement.prepareMatch(request).equals(interrupted);
            assert interrupted.result().outcomes().stream().allMatch(o -> o.outcome() == Outcome.NONE);
            assert managers.tableManager().getTables().isEmpty();
        }
        System.out.println("PASS managed create retries/conflicts/capacity, seat-readiness gate, crash-intent fencing and interruption tombstones");
    }
    private static void expectFault(Runnable call, String code) {
        try { call.run(); } catch (mtgcraft.service.ControlServer.Fault fault) { assert fault.error.code().equals(code); return; }
        throw new AssertionError("expected " + code);
    }
}
