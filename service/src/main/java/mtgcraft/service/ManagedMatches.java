package mtgcraft.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import mage.constants.TableState;
import mage.game.match.MatchOptions;
import mage.players.PlayerType;
import mage.server.*;
import mtgcraft.protocol.*;
import static mtgcraft.protocol.Protocol.*;

/** Durable managed creation. Native policy/result hooks must be ready before admission. */
public final class ManagedMatches implements MagicEngine {
    public static final String AI_PROFILE = "mad-plains-v1";
    public record Record(MatchRequest request, UUID incarnation, boolean creating, Status status,
                         UUID roomId, UUID tableId, UUID humanUserId, UUID humanPlayerId, UUID aiPlayerId,
                         String username, String password) {
        public Record {
            java.util.Objects.requireNonNull(request); java.util.Objects.requireNonNull(incarnation);
            if (creating != (status == null)) throw new IllegalArgumentException("invalid creation phase");
            if (status != null && (!request.worldId().equals(status.worldId()) || !request.matchId().equals(status.matchId())
                    || !request.requestDigest().equals(status.requestDigest()) || !request.snapshot().digest().equals(status.snapshotDigest())
                    || !incarnation.equals(status.serviceIncarnation()) || !request.seats().equals(status.seats())))
                throw new IllegalArgumentException("journal binding mismatch");
        }
        @Override public String toString() { return "ManagedRecord[match=" + request.matchId() + ", credentials=REDACTED]"; }
    }
    private final ServiceState state;
    private final UUID world;
    private final int limit;
    private final Catalog catalog;
    private final EngineDecks decks;
    private final MainManagerFactory managers;
    private final User owner;
    private final HashMap<UUID, Record> records = new HashMap<>();
    public ManagedMatches(ServiceState state, UUID world, int limit, Catalog catalog, MainManagerFactory managers) throws IOException {
        this.state = state; this.world = world; this.limit = limit; this.catalog = catalog; this.managers = managers;
        decks = new EngineDecks(catalog); state.bindWorld(world);
        owner = managers.userManager().createUser("service_owner", "127.0.0.1", null).orElseThrow();
        for (var bytes : state.matches()) {
            var record = Json.read(bytes, Record.class);
            if (!world.equals(record.request().worldId()) || records.put(record.request().matchId(), record) != null)
                throw new IOException("invalid durable match binding");
            // The exclusive state lock proves all tables of the old process are fenced.
            if (record.creating() || !terminal(record.status().state()))
                save(terminalRecord(record, State.INTERRUPTED));
            revokeAccount(record);
        }
    }
    private static ControlServer.Fault fault(int status, String code, String message, boolean retryable) {
        return new ControlServer.Fault(status, code, message, retryable);
    }
    private void checkWorld(UUID requested) { if (!world.equals(requested)) throw fault(403, "WRONG_WORLD", "World namespace mismatch", false); }
    private Record record(UUID match) {
        var record = records.get(match);
        if (record == null) throw fault(404, "NOT_FOUND", "Match does not exist", false);
        return record;
    }
    private void save(Record record) {
        try { state.write("match-" + record.request().matchId() + ".json", Json.bytes(record)); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        records.put(record.request().matchId(), record);
    }
    private String marker(MatchRequest request) { return "mtgcraft:" + world + ":" + request.matchId(); }
    private Status status(Record record, State disposition, long revision, Result result) {
        var request = record.request();
        return new Status(VERSION, world, request.matchId(), request.requestDigest(), request.snapshot().digest(),
                record.incarnation(), request.seats(), disposition, revision, result);
    }
    private Record withStatus(Record record, Status status) {
        return new Record(record.request(), record.incarnation(), false, status, record.roomId(), record.tableId(),
                record.humanUserId(), record.humanPlayerId(), record.aiPlayerId(), record.username(), record.password());
    }
    private Record terminalRecord(Record record, State disposition) {
        long revision = record.status() == null ? 1 : record.status().revision() + 1;
        var request = record.request();
        var result = new Result(UUID.randomUUID(), world, request.matchId(), request.snapshot().digest(), record.incarnation(), disposition,
                revision, request.seats().stream().map(seat -> new SeatOutcome(seat, Outcome.NONE)).toList());
        return withStatus(record, status(record, disposition, revision, result));
    }
    private void revokeAccount(Record record) {
        if (record.username() != null) AuthorizedUserRepository.getInstance().remove(record.username());
    }
    private DeckSnapshot aiSnapshot() {
        var printing = catalog.manifest().printings().stream().filter(p -> p.cardKey().equals("m10/230")).findFirst().orElseThrow();
        return DeckSnapshot.create(catalog.compatibility(), List.of(new Entry(printing.card(Finish.NONFOIL), 60, Section.MAIN)));
    }
    public Health health() { return new Health(VERSION, world, state.incarnation(), catalog.compatibility(), false, limit); }
    public Validation validateDeck(ValidationRequest request) { checkWorld(request.worldId()); return decks.prepare(request.snapshot()).validation(); }
    // ponytail: one service lock at <=16 matches; per-match locks if measured contention matters.
    public synchronized Status prepareMatch(MatchRequest request) {
        checkWorld(request.worldId());
        var existing = records.get(request.matchId());
        if (existing != null) {
            if (!existing.request().requestDigest().equals(request.requestDigest())) throw fault(409, "REQUEST_CONFLICT", "Match ID is bound to a different request", false);
            return getStatus(request.matchId());
        }
        if (!request.aiProfile().equals(AI_PROFILE)) throw fault(400, "AI_PROFILE_MISMATCH", "Unsupported pinned AI profile", false);
        var prepared = decks.prepare(request.snapshot());
        if (!prepared.validation().valid()) throw fault(422, "DECK_INVALID", "Validate the deck before preparing a match", false);
        if (records.values().stream().filter(r -> r.creating() || !terminal(r.status().state())).count() >= limit)
            throw fault(503, "CAPACITY", "Active match limit reached", true);
        var username = "m_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var password = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new java.security.SecureRandom().generateSeed(32));
        var record = new Record(request, state.incarnation(), true, null, managers.gamesRoomManager().getMainRoomId(),
                null, null, null, null, username, password);
        save(record); // Commit intent before account, table or seat creation.
        try {
            var users = AuthorizedUserRepository.getInstance();
            if (users.getByName(username) != null) throw new IllegalStateException("account collision");
            users.add(username, password, username + "@example.invalid");
            var authorized = users.getByName(username);
            if (authorized == null || !authorized.doCredentialsMatch(username, password)) throw new IllegalStateException("account persistence failed");
            var human = managers.userManager().createUser(username, "127.0.0.1", authorized).orElseThrow();
            var options = new MatchOptions(marker(request), "Two Player Duel", false);
            options.setDeckType("Constructed - Freeform"); options.setWinsNeeded(1); options.setQuitRatio(100);
            options.setRollbackTurnsAllowed(false);
            options.getPlayerTypes().add(PlayerType.HUMAN); options.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
            var view = managers.gamesRoomManager().getRoom(record.roomId()).orElseThrow().createTable(owner.getId(), options);
            var table = managers.tableManager().getTable(view.getTableId());
            record = new Record(request, state.incarnation(), true, null, record.roomId(), table.getId(), human.getId(), null, null, username, password);
            save(record); // Associate table before installing either seat.
            var ai = decks.prepare(aiSnapshot());
            if (!ai.validation().valid() || !managers.tableManager().joinTable(human.getId(), table.getId(), username, PlayerType.HUMAN, 1, prepared.list(), "")
                    || !managers.tableManager().joinTable(owner.getId(), table.getId(), "managed_ai", PlayerType.COMPUTER_MAD, 1, ai.list(), ""))
                throw new IllegalStateException("seat preparation failed");
            var match = table.getMatch();
            var humanSeat = match.getPlayers().stream().filter(p -> p.getPlayer().isHuman()).findFirst().orElseThrow();
            var aiSeat = match.getPlayers().stream().filter(p -> !p.getPlayer().isHuman()).findFirst().orElseThrow();
            decks.verify(request.snapshot(), humanSeat.getDeck()); decks.verify(aiSnapshot(), aiSeat.getDeck());
            if (table.getState() != TableState.READY_TO_START) throw new IllegalStateException("table not prepared");
            record = new Record(request, state.incarnation(), false, status(record, State.PREPARED, 1, null), record.roomId(), table.getId(),
                    human.getId(), humanSeat.getPlayer().getId(), aiSeat.getPlayer().getId(), username, password);
            save(record); return record.status();
        } catch (Exception unknown) {
            // Do not create another table on retry. Reconcile/fence the durable marker first.
            throw fault(503, "CREATION_UNKNOWN", "Preparation outcome unknown; query the same match ID", true);
        }
    }
    public synchronized Status getStatus(UUID match) {
        var record = record(match);
        if (record.creating()) {
            var terminal = terminalRecord(record, State.FAILED);
            save(terminal); // Persist the fence/tombstone before removing ambiguous tables.
            for (var table : managers.tableManager().getTables()) if (table.getName().equals(marker(record.request()))) managers.tableManager().removeTable(table.getId());
            revokeAccount(record); return terminal.status();
        }
        return record.status();
    }
    public synchronized Status startMatch(UUID matchId, Operation operation) {
        checkWorld(operation.worldId());
        var record = record(matchId);
        if (!record.request().requestDigest().equals(operation.requestDigest())) throw fault(409, "REQUEST_CONFLICT", "Request digest mismatch", false);
        if (record.creating()) return getStatus(matchId);
        if (terminal(record.status().state())) return record.status();
        var table = managers.tableManager().getTable(record.tableId());
        var match = table.getMatch();
        if (match.hasStarted()) return record.status();
        var human = managers.userManager().getUser(record.humanUserId()).orElseThrow();
        if (!human.isConnected()) throw fault(409, "SEAT_NOT_READY", "Authenticate the native human seat before starting", true);
        decks.verify(record.request().snapshot(), match.getPlayer(record.humanPlayerId()).getDeck());
        decks.verify(aiSnapshot(), match.getPlayer(record.aiPlayerId()).getDeck());
        if (record.status().state() != State.ACTIVE) {
            record = withStatus(record, status(record, State.ACTIVE, record.status().revision() + 1, null)); save(record);
        }
        var controller = managers.tableManager().getController(table.getId()).orElseThrow();
        if (table.getState() == TableState.READY_TO_START && !controller.changeTableStateToStarting()) throw fault(503, "START_UNKNOWN", "Start outcome unknown", true);
        managers.tableManager().startMatch(owner.getId(), record.roomId(), record.tableId());
        return records.get(matchId).status();
    }
    public Status cancelMatch(UUID match, Operation request) { throw fault(503, "NOT_READY", "Terminal lifecycle is not ready", true); }
    public SeatAccess getSeatAccess(UUID match, OwnerRequest request) { throw fault(503, "NOT_READY", "Managed native policy is not ready", true); }
}
