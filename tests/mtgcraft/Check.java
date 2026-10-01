package mtgcraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mtgcraft.protocol.*;
import mtgcraft.service.Catalog;
import mtgcraft.service.ControlServer;
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
        if (args.length == 1 && args[0].equals("history")) {
            var directory = java.nio.file.Path.of(java.nio.file.Files.readString(java.nio.file.Path.of(".cache/engine-check/history-location")).strip());
            var expected = Json.read(java.nio.file.Files.readAllBytes(directory.resolve("expected-result.json")), Status.class);
            var record = Json.read(java.nio.file.Files.readAllBytes(directory.resolve("match-" + expected.matchId() + ".json")), mtgcraft.service.ManagedMatches.Record.class);
            assert expected.equals(record.status()) && record.password() == null;
            System.out.println("PASS exact structured result read from a fresh JVM without XMage runtime classes");
            return;
        }
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
        http();
        state();
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
    private static void http() throws Exception {
        var catalog = new Catalog(java.nio.file.Path.of("service/catalog/manifest.json"), p -> true);
        var snapshot = DeckSnapshot.create(catalog.compatibility(), List.of(new Entry(catalog.manifest().printings().get(0).card(Finish.NONFOIL), 1, Section.MAIN)));
        var request = MatchRequest.create(UUID.randomUUID(), UUID.randomUUID(), List.of(
                new Seat(UUID.randomUUID(), Role.HUMAN, UUID.randomUUID()), new Seat(UUID.randomUUID(), Role.AI, null)), snapshot, "mad-v1");
        assert Json.read(Json.bytes(request), MatchRequest.class).equals(request);
        var wire = new String(Json.bytes(request), java.nio.charset.StandardCharsets.UTF_8);
        for (var invalid : List.of(wire.replace("\"protocolVersion\":1", "\"protocolVersion\":1,\"protocolVersion\":1"),
                wire.replace("\"protocolVersion\":1", "\"protocolVersion\":\"1\""),
                wire.replace("\"protocolVersion\":1", "\"protocolVersion\":1.5"),
                wire.replace("\"protocolVersion\":1", "\"protocolVersion\":2"),
                wire.replace("\"protocolVersion\":1", "\"unknown\":1"), "null", wire + " {}")) {
            rejected(() -> Json.read(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8), MatchRequest.class));
        }
        rejected(() -> Json.read(new byte[]{(byte) 0xc0, (byte) 0xaf}, MatchRequest.class));
        rejected(() -> Json.read(new byte[Json.MAX_BYTES + 1], MatchRequest.class));
        var incarnation = UUID.randomUUID();
        var status = new Status(VERSION, request.worldId(), request.matchId(), request.requestDigest(), snapshot.digest(), incarnation,
                request.seats(), State.PREPARED, 1, null);
        var engine = new MagicEngine() {
            public Health health() { return new Health(VERSION, request.worldId(), incarnation, catalog.compatibility(), true, 2); }
            public Validation validateDeck(ValidationRequest r) { return catalog.resolve(r.snapshot()).validation(); }
            public Status prepareMatch(MatchRequest r) { assert r.equals(request); return status; }
            public Status getStatus(UUID id) { assert id.equals(request.matchId()); return status; }
            public Status startMatch(UUID id, Operation r) { assert r.requestDigest().equals(request.requestDigest()); return status; }
            public Status cancelMatch(UUID id, Operation r) { assert r.requestDigest().equals(request.requestDigest()); return status; }
            public SeatAccess getSeatAccess(UUID id, OwnerRequest r) {
                if (!r.playerId().equals(request.seats().getFirst().playerId())) throw new ControlServer.Fault(403, "NOT_OWNER", "Seat owner mismatch", false);
                return new SeatAccess(VERSION, request.worldId(), id, request.seats().getFirst(), "127.0.0.1", 17171, "fixture", "private-fixture-only");
            }
        };
        var loopback = java.net.InetAddress.getByName("127.0.0.1");
        var token = "fixture_" + "0".repeat(32);
        try (var server = new ControlServer(loopback, 0, request.worldId(), token, null, engine)) {
            var base = java.net.URI.create("http://127.0.0.1:" + server.port());
            try (var client = new ControlClient(base, request.worldId(), token, java.time.Duration.ofSeconds(3))) {
                assert client.health().join().ready();
                assert client.validateDeck(snapshot).join().valid();
                assert client.prepareMatch(request).join().equals(status);
                assert client.getStatus(request.matchId()).join().equals(status);
                assert client.startMatch(request.matchId(), request.requestDigest()).join().equals(status);
                assert client.cancelMatch(request.matchId(), request.requestDigest()).join().equals(status);
                var access = client.getSeatAccess(request.matchId(), request.seats().getFirst().playerId()).join();
                assert !access.toString().contains(access.password());
                assertRejectedFuture(client.getSeatAccess(request.matchId(), UUID.randomUUID()), 403);
                assertRejectedFuture(client.prepareMatch(MatchRequest.create(UUID.randomUUID(), request.matchId(), request.seats(), snapshot, "mad-v1")), 403);
            }
            try (var wrongWorld = new ControlClient(base, UUID.randomUUID(), token, java.time.Duration.ofSeconds(3));
                 var wrongToken = new ControlClient(base, request.worldId(), "x".repeat(32), java.time.Duration.ofSeconds(3));
                 var raw = java.net.http.HttpClient.newHttpClient()) {
                assertRejectedFuture(wrongWorld.health(), 403); assertRejectedFuture(wrongToken.health(), 401);
                var noAuth = raw.send(java.net.http.HttpRequest.newBuilder(base.resolve("/v1/health")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                assert noAuth.statusCode() == 401;
                var oversized = raw.send(java.net.http.HttpRequest.newBuilder(base.resolve("/v1/decks/validate"))
                        .header("Authorization", "Bearer " + token).header("X-Mtgcraft-World", request.worldId().toString())
                        .header("X-Mtgcraft-Version", "1").header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[Json.MAX_BYTES + 1])).build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                assert oversized.statusCode() == 413;
            }
        }
        rejected(() -> {
            try { new ControlServer(java.net.InetAddress.getByName("0.0.0.0"), 0, request.worldId(), token, null, engine); }
            catch (java.io.IOException unexpected) { throw new AssertionError(unexpected); }
        });
        rejected(() -> {
            try { new ControlClient(java.net.URI.create("http://192.0.2.1"), request.worldId(), token, java.time.Duration.ofSeconds(3)); }
            catch (java.io.IOException unexpected) { throw new AssertionError(unexpected); }
        });
        System.out.println("PASS strict JSON, all HTTP operations, credentials/world/owner checks, payload bounds and TLS requirement");
    }
    private static void assertRejectedFuture(java.util.concurrent.CompletableFuture<?> future, int code) {
        try { future.join(); }
        catch (java.util.concurrent.CompletionException e) {
            assert e.getCause() instanceof ControlClient.Rejected;
            assert ((ControlClient.Rejected) e.getCause()).status == code;
            return;
        }
        throw new AssertionError("expected HTTP rejection");
    }
    private static void state() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(".cache"), "state-check-");
        UUID first;
        try (var state = new mtgcraft.service.ServiceState(directory)) {
            first = state.incarnation(); assert state.previous() == null;
            try {
                new mtgcraft.service.ServiceState(directory);
                throw new AssertionError("overlapping service was allowed");
            } catch (java.io.IOException expected) { }
            state.write("record.json", "durable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assert java.nio.file.Files.readString(directory.resolve("record.json")).equals("durable");
        }
        try (var replacement = new mtgcraft.service.ServiceState(directory)) {
            assert replacement.previous().equals(first) && !replacement.incarnation().equals(first);
        }
        try (var paths = java.nio.file.Files.list(directory)) { for (var p : paths.toList()) java.nio.file.Files.delete(p); }
        java.nio.file.Files.delete(directory);
        System.out.println("PASS durable incarnation, exclusive process lock and atomic acknowledged writes");
    }
}
