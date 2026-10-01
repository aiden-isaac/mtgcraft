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
        try { run(args); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
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
        var directory = java.nio.file.Files.createTempDirectory(Path.of(".").toAbsolutePath(), "managed-check-");
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
            mage.server.ManagedTransport.start(managers, service);
            nativeGuard(service, managers, request, second, directory, false);
            original = service.getStatus(request.matchId());
            assert original.state() == State.COMPLETED;
            java.nio.file.Files.write(directory.resolve("expected-result.json"), mtgcraft.protocol.Json.bytes(original));
            java.nio.file.Files.writeString(Path.of("history-location"), directory.toString());
            var raceRequest = MatchRequest.create(world, java.util.UUID.randomUUID(), List.of(new Seat(java.util.UUID.randomUUID(), Role.HUMAN, java.util.UUID.randomUUID()),
                    new Seat(java.util.UUID.randomUUID(), Role.AI, null)), snapshot, request.aiProfile());
            service.prepareMatch(raceRequest);
            nativeGuard(service, managers, raceRequest, second, directory, true);
            var raced = service.getStatus(raceRequest.matchId());
            assert raced.state() == State.COMPLETED || raced.state() == State.CANCELLED;
            assert service.cancelMatch(raceRequest.matchId(), new Operation(VERSION, world, raceRequest.requestDigest())).equals(raced);
            var operation = new Operation(VERSION, world, second.requestDigest());
            var cancelled = service.cancelMatch(second.matchId(), operation);
            assert cancelled.state() == State.CANCELLED;
            assert service.cancelMatch(second.matchId(), operation).equals(cancelled);
            assert cancelled.result().outcomes().stream().allMatch(o -> o.outcome() == Outcome.NONE);
            // Inject a creation intent with no acknowledged table association.
            var pending = MatchRequest.create(world, java.util.UUID.randomUUID(), seats, snapshot, request.aiProfile());
            var record = new mtgcraft.service.ManagedMatches.Record(pending, state.incarnation(), true, null, null, null, null, null, null, null, null);
            state.write("match-" + pending.matchId() + ".json", mtgcraft.protocol.Json.bytes(record));
            for (var table : managers.tableManager().getTables()) managers.tableManager().removeTable(table.getId());
            managers.userManager().removeUser(managers.userManager().getUserByName("service_owner").orElseThrow().getId());
        }
        try (var state = new mtgcraft.service.ServiceState(directory)) {
            var replacement = new mtgcraft.service.ManagedMatches(state, world, 2, catalog, managers);
            var recovered = replacement.getStatus(request.matchId());
            assert recovered.equals(original);
            assert replacement.prepareMatch(request).equals(recovered);
            assert recovered.result().outcomes().getLast().outcome() == Outcome.WIN;
            assert managers.tableManager().getTables().isEmpty();
        }
        System.out.println("PASS managed create retries/conflicts/capacity, authenticated start, structured outcome/cancellation, cleanup and durable result tombstones");
    }
    private static void expectFault(Runnable call, String code) {
        try { call.run(); } catch (mtgcraft.service.ControlServer.Fault fault) { assert fault.error.code().equals(code); return; }
        throw new AssertionError("expected " + code);
    }
    private static void nativeGuard(mtgcraft.service.ManagedMatches service, mage.server.MainManagerFactory managers,
                                    MatchRequest request, MatchRequest other, Path directory, boolean race) throws Exception {
        var access = service.getSeatAccess(request.matchId(), new OwnerRequest(VERSION, request.worldId(), request.seats().getFirst().playerId()));
        expectFault(() -> service.getSeatAccess(request.matchId(), new OwnerRequest(VERSION, request.worldId(), java.util.UUID.randomUUID())), "NOT_OWNER");
        assert !service.seatAccountAllowed("unrelated");
        var table = managers.tableManager().getTables().stream().filter(t -> t.getName().endsWith(request.matchId().toString())).findFirst().orElseThrow();
        var otherTable = managers.tableManager().getTables().stream().filter(t -> t.getName().endsWith(other.matchId().toString())).findFirst().orElseThrow();
        var human = table.getMatch().getPlayers().stream().filter(p -> p.getPlayer().isHuman()).findFirst().orElseThrow();
        var guarded = new mage.server.GuardedServer(managers, service);
        for (var phase : List.of(mage.constants.TableState.WAITING, mage.constants.TableState.READY_TO_START, mage.constants.TableState.STARTING,
                mage.constants.TableState.SIDEBOARDING, mage.constants.TableState.CONSTRUCTING, mage.constants.TableState.DUELING)) {
            table.setState(phase);
            assert !guarded.deckSubmit("unrelated", table.getId(), new DeckCardLists());
            try { guarded.deckSave("unrelated", table.getId(), new DeckCardLists()); throw new AssertionError("native save accepted"); }
            catch (mage.MageException expected) { }
            assert !guarded.matchStart("unrelated", table.getRoomId(), table.getId());
            assert table.getState() == phase;
            assert human.getDeck().getCards().size() == 60 && human.getDeck().getCards().stream().allMatch(c -> c.getName().equals("Plains"));
        }
        table.setState(mage.constants.TableState.READY_TO_START);
        var properties = new java.util.Properties(); properties.setProperty("username", access.username()); properties.setProperty("password", access.password());
        properties.setProperty("room", table.getRoomId().toString()); properties.setProperty("table", table.getId().toString());
        properties.setProperty("player", human.getPlayer().getId().toString()); properties.setProperty("otherTable", otherTable.getId().toString());
        properties.setProperty("play", "true");
        properties.setProperty("timing", "true"); properties.setProperty("hold", "true");
        properties.setProperty("race", Boolean.toString(race));
        var path = directory.resolve("native-access.properties");
        java.nio.file.Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        try (var output = java.nio.file.Files.newOutputStream(path)) { properties.store(output, "Private disposable fixture"); }
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-ea", "-Xmx512m",
                "--add-opens=java.base/java.io=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), "mtgcraft.NativeCheck", path.toString()).inheritIO().start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (!java.nio.file.Files.exists(Path.of(path + ".ready"))) {
                if (!process.isAlive() || System.nanoTime() > deadline) throw new AssertionError("native seat did not become ready");
                Thread.sleep(20);
            }
            var operation = new Operation(VERSION, request.worldId(), request.requestDigest());
            assert service.startMatch(request.matchId(), operation).state() == State.ACTIVE;
            assert service.startMatch(request.matchId(), operation).state() == State.ACTIVE;
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
            while (!java.nio.file.Files.exists(Path.of(path + ".playing"))) {
                if (!process.isAlive() || System.nanoTime() > deadline) throw new AssertionError("Two AI turns were not observed");
                Thread.sleep(20);
            }
            var game = table.getMatch().getGame();
            var measuredAi = game.getPlayers().values().stream().filter(p -> !p.isHuman()).findFirst().orElseThrow().copy();
            measuredAi.getHand().clear(); game.getCards().stream().filter(c -> c.getOwnerId().equals(measuredAi.getId())).limit(7).forEach(c -> measuredAi.getHand().add(c.getId()));
            assert measuredAi.getHand().size() == 7;
            var decisions = new java.util.ArrayList<Double>();
            for (int sample = 0; sample < 10; sample++) {
                long began = System.nanoTime(); assert measuredAi.chooseMulligan(game); decisions.add((System.nanoTime() - began) / 1_000_000.0);
            }
            System.out.println("MEASURE ai_mulligan_decision_ms=" + decisions);
            java.nio.file.Files.writeString(Path.of(path + ".release"), "continue");
            if (race) {
                while (!java.nio.file.Files.exists(Path.of(path + ".conceding"))) {
                    if (!process.isAlive() || System.nanoTime() > deadline) {
                        for (var entry : Thread.getAllStackTraces().entrySet()) if (entry.getKey().getName().startsWith("XMAGE GAME"))
                            System.err.println(entry.getKey().getState() + " " + java.util.Arrays.toString(entry.getValue()));
                        throw new AssertionError("concession race did not become ready");
                    }
                    Thread.sleep(20);
                }
                var cancelled = java.util.concurrent.CompletableFuture.supplyAsync(() -> service.cancelMatch(request.matchId(), operation));
                cancelled.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            Status result;
            do {
                result = service.getStatus(request.matchId());
                if (System.nanoTime() > deadline || !process.isAlive()) {
                    System.err.println("native completion diagnostics: table=" + table.getState() + " gameEnded=" + table.getMatch().getGame().hasEnded());
                    throw new AssertionError("native outcome did not become durable");
                }
                if (!terminal(result.state()) || managers.tableManager().getController(table.getId()).isPresent()) Thread.sleep(20);
            } while (!terminal(result.state()) || managers.tableManager().getController(table.getId()).isPresent());
            java.nio.file.Files.writeString(Path.of(path + ".done"), "acknowledged");
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("native client timed out");
            assert process.exitValue() == 0 : "native client rejected expected behavior";
            assert race ? result.state() == State.COMPLETED || result.state() == State.CANCELLED
                    : result.state() == State.COMPLETED && result.result().outcomes().getLast().outcome() == Outcome.WIN;
            assert !service.seatAccountAllowed(access.username());
            assert !managers.tableManager().getController(table.getId()).isPresent();
            expectFault(() -> service.getSeatAccess(request.matchId(), new OwnerRequest(VERSION, request.worldId(), request.seats().getFirst().playerId())), "SEAT_UNAVAILABLE");
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
            java.nio.file.Files.deleteIfExists(path);
            for (var suffix : List.of(".ready", ".done", ".conceding", ".playing", ".release")) java.nio.file.Files.deleteIfExists(Path.of(path + suffix));
        }
    }
}
