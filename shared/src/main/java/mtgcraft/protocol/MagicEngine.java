package mtgcraft.protocol;

import java.util.UUID;
import static mtgcraft.protocol.Protocol.*;

/** The small service port described by the change; no gameplay decisions cross it. */
public interface MagicEngine {
    Health health();
    Validation validateDeck(ValidationRequest request);
    Status prepareMatch(MatchRequest request);
    Status startMatch(UUID matchId, Operation request);
    Status getStatus(UUID matchId);
    Status cancelMatch(UUID matchId, Operation request);
    SeatAccess getSeatAccess(UUID matchId, OwnerRequest owner);
}
