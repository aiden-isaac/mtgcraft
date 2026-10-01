package mtgcraft.protocol;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Project-owned wire records. Physical references and engine types stay in adapters. */
public final class Protocol {
    public static final int VERSION = 1;
    public static final int MAX_ENTRIES = 512;
    public static final int MAX_CARDS = 1000;
    public enum Finish { NONFOIL, FOIL }
    public enum Section { MAIN, SIDEBOARD, COMMANDER }
    public enum Role { HUMAN, AI }
    public enum State { PREPARED, ACTIVE, COMPLETED, CANCELLED, INTERRUPTED, FAILED }
    public enum Outcome { WIN, LOSS, DRAW, NONE }

    public record CardIdentity(String cardKey, UUID scryfallId, UUID oracleId,
                               String set, String collectorNumber, Finish finish,
                               String layout, List<String> faces, String label) {
        public CardIdentity {
            text(cardKey); text(set); text(collectorNumber); text(layout); text(label);
            if (!set.matches("[a-z0-9]{2,12}")) throw new IllegalArgumentException("invalid set");
            Objects.requireNonNull(finish);
            faces = List.copyOf(faces);
            if (faces.size() > 4) throw new IllegalArgumentException("too many faces");
            faces.forEach(Protocol::text);
        }
    }
    public record Entry(CardIdentity card, int quantity, Section section) {
        public Entry {
            Objects.requireNonNull(card); Objects.requireNonNull(section);
            if (quantity < 1 || quantity > MAX_CARDS) throw new IllegalArgumentException("invalid quantity");
        }
    }
    public record Compatibility(int protocolVersion, String format, String formatVersion,
                                String mappingDigest, String engineBuild, String catalogDigest) {
        public Compatibility {
            version(protocolVersion); text(format); text(formatVersion);
            digest(mappingDigest); text(engineBuild); digest(catalogDigest);
        }
    }
    public record DeckSnapshot(Compatibility compatibility, List<Entry> entries, String digest) {
        public DeckSnapshot {
            Objects.requireNonNull(compatibility);
            entries = Canonical.entries(entries);
            Protocol.digest(digest);
            if (!Canonical.snapshotDigest(compatibility, entries).equals(digest))
                throw new IllegalArgumentException("snapshot digest mismatch");
        }
        public static DeckSnapshot create(Compatibility compatibility, List<Entry> entries) {
            var canonical = Canonical.entries(entries);
            return new DeckSnapshot(compatibility, canonical, Canonical.snapshotDigest(compatibility, canonical));
        }
    }
    public record Seat(UUID seatId, Role role, UUID playerId) {
        public Seat {
            Objects.requireNonNull(seatId); Objects.requireNonNull(role);
            if ((role == Role.HUMAN) != (playerId != null)) throw new IllegalArgumentException("invalid seat binding");
        }
    }
    public record MatchRequest(int protocolVersion, UUID worldId, UUID matchId,
                               List<Seat> seats, DeckSnapshot snapshot, String aiProfile, String requestDigest) {
        public MatchRequest {
            version(protocolVersion); Objects.requireNonNull(worldId); Objects.requireNonNull(matchId);
            seats = Protocol.seats(seats); Objects.requireNonNull(snapshot); text(aiProfile); digest(requestDigest);
            if (!Canonical.requestDigest(worldId, matchId, seats, snapshot, aiProfile).equals(requestDigest))
                throw new IllegalArgumentException("request digest mismatch");
        }
        public static MatchRequest create(UUID world, UUID match, List<Seat> seats, DeckSnapshot snapshot, String ai) {
            return new MatchRequest(VERSION, world, match, seats, snapshot, ai,
                    Canonical.requestDigest(world, match, seats, snapshot, ai));
        }
    }
    public record ValidationRequest(int protocolVersion, UUID worldId, DeckSnapshot snapshot) {
        public ValidationRequest { version(protocolVersion); Objects.requireNonNull(worldId); Objects.requireNonNull(snapshot); }
    }
    public record Error(String code, String message, boolean retryable, List<Integer> entries) {
        public Error {
            text(code); text(message); entries = List.copyOf(entries);
            if (entries.stream().anyMatch(i -> i < 0 || i >= MAX_ENTRIES)) throw new IllegalArgumentException("invalid entry index");
        }
    }
    public record Validation(int protocolVersion, boolean valid, String snapshotDigest, List<Error> errors) {
        public Validation {
            version(protocolVersion); digest(snapshotDigest); errors = List.copyOf(errors);
            if (valid != errors.isEmpty()) throw new IllegalArgumentException("invalid validation response");
        }
    }
    public record SeatOutcome(Seat seat, Outcome outcome) {
        public SeatOutcome { Objects.requireNonNull(seat); Objects.requireNonNull(outcome); }
    }
    public record Result(UUID resultId, UUID worldId, UUID matchId, String snapshotDigest,
                         UUID serviceIncarnation, State disposition, long revision, List<SeatOutcome> outcomes) {
        public Result {
            Objects.requireNonNull(resultId); Objects.requireNonNull(worldId); Objects.requireNonNull(matchId);
            digest(snapshotDigest); Objects.requireNonNull(serviceIncarnation); Objects.requireNonNull(disposition);
            if (!terminal(disposition) || revision < 1) throw new IllegalArgumentException("invalid terminal record");
            outcomes = List.copyOf(outcomes);
            seats(outcomes.stream().map(SeatOutcome::seat).toList());
            long wins = outcomes.stream().filter(o -> o.outcome() == Outcome.WIN).count();
            long losses = outcomes.stream().filter(o -> o.outcome() == Outcome.LOSS).count();
            long draws = outcomes.stream().filter(o -> o.outcome() == Outcome.DRAW).count();
            if (disposition == State.COMPLETED ? !((wins == 1 && losses == 1) || draws == 2)
                    : outcomes.stream().anyMatch(o -> o.outcome() != Outcome.NONE))
                throw new IllegalArgumentException("invalid terminal outcomes");
        }
    }
    public record Status(int protocolVersion, UUID worldId, UUID matchId, String requestDigest,
                         String snapshotDigest, UUID serviceIncarnation, List<Seat> seats,
                         State state, long revision, Result result) {
        public Status {
            version(protocolVersion); Objects.requireNonNull(worldId); Objects.requireNonNull(matchId);
            digest(requestDigest); digest(snapshotDigest); Objects.requireNonNull(serviceIncarnation);
            seats = Protocol.seats(seats); Objects.requireNonNull(state);
            if (revision < 1 || terminal(state) != (result != null)) throw new IllegalArgumentException("invalid status");
            if (result != null && (!worldId.equals(result.worldId()) || !matchId.equals(result.matchId())
                    || !snapshotDigest.equals(result.snapshotDigest()) || !serviceIncarnation.equals(result.serviceIncarnation())
                    || state != result.disposition() || revision != result.revision()
                    || !seats.equals(Protocol.seats(result.outcomes().stream().map(SeatOutcome::seat).toList()))))
                throw new IllegalArgumentException("result binding mismatch");
        }
    }
    public record OwnerRequest(int protocolVersion, UUID worldId, UUID playerId) {
        public OwnerRequest { version(protocolVersion); Objects.requireNonNull(worldId); Objects.requireNonNull(playerId); }
    }
    public record Operation(int protocolVersion, UUID worldId, String requestDigest) {
        public Operation { version(protocolVersion); Objects.requireNonNull(worldId); digest(requestDigest); }
    }
    public record SeatAccess(int protocolVersion, UUID worldId, UUID matchId, Seat seat,
                             String host, int port, String username, String password) {
        public SeatAccess {
            version(protocolVersion); Objects.requireNonNull(worldId); Objects.requireNonNull(matchId);
            Objects.requireNonNull(seat); text(host); text(username); text(password);
            if (seat.role() != Role.HUMAN || port < 1 || port > 65535) throw new IllegalArgumentException("invalid seat access");
        }
        @Override public String toString() { return "SeatAccess[matchId=" + matchId + ", credentials=REDACTED]"; }
    }
    public record Health(int protocolVersion, UUID worldId, UUID serviceIncarnation,
                         Compatibility compatibility, boolean ready, int activeMatchLimit) {
        public Health {
            version(protocolVersion); Objects.requireNonNull(worldId); Objects.requireNonNull(serviceIncarnation);
            Objects.requireNonNull(compatibility);
            if (activeMatchLimit < 1 || activeMatchLimit > 16) throw new IllegalArgumentException("invalid capacity");
        }
    }
    public record Failure(int protocolVersion, Error error) {
        public Failure { version(protocolVersion); Objects.requireNonNull(error); }
    }
    public static boolean terminal(State state) { return state != State.PREPARED && state != State.ACTIVE; }
    public static List<Seat> seats(List<Seat> seats) {
        var sorted = seats.stream().sorted(java.util.Comparator.comparing(Seat::role)).toList();
        if (sorted.size() != 2 || sorted.get(0).role() != Role.HUMAN || sorted.get(1).role() != Role.AI
                || sorted.get(0).seatId().equals(sorted.get(1).seatId())) throw new IllegalArgumentException("expected distinct human/AI seats");
        return sorted;
    }
    public static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid text");
    }
    public static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid SHA-256 digest");
    }
    public static void version(int value) {
        if (value != VERSION) throw new IllegalArgumentException("unsupported protocol version");
    }
}
