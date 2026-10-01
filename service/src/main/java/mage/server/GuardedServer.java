package mage.server;

import java.util.UUID;
import mage.MageException;
import mage.cards.decks.DeckCardLists;
import mage.constants.ManaType;
import mage.constants.PlayerAction;
import mage.game.match.MatchOptions;
import mage.game.tournament.TournamentOptions;
import mage.players.PlayerType;
import mage.utils.MageVersion;
import mage.view.TableView;
import mtgcraft.service.ManagedMatches;

/** Dedicated managed deployment: table mutations come only from the control service. */
public final class GuardedServer extends MageServerImpl {
    private final MainManagerFactory managers;
    private final ManagedMatches service;
    public GuardedServer(MainManagerFactory managers, ManagedMatches service) {
        super(managers, UUID.randomUUID().toString(), false, false); this.managers = managers; this.service = service;
    }
    @Override public boolean authRegister(String session, String name, String password, String email) { return false; }
    @Override public boolean authSendTokenToEmail(String session, String email) { return false; }
    @Override public boolean authResetPassword(String session, String email, String token, String password) { return false; }
    @Override public boolean connectAdmin(String password, String session, MageVersion version) { return false; }
    @Override public boolean connectUser(String name, String password, String session, String restore, MageVersion version, String userId) throws MageException {
        return service.seatAccountAllowed(name) && super.connectUser(name, password, session, restore, version, userId);
    }
    @Override public TableView roomCreateTable(String session, UUID room, MatchOptions options) { return null; }
    @Override public TableView roomCreateTournament(String session, UUID room, TournamentOptions options) { return null; }
    @Override public boolean roomJoinTable(String session, UUID room, UUID table, String name, PlayerType type, int skill, DeckCardLists deck, String password) { return false; }
    @Override public boolean roomJoinTournament(String session, UUID room, UUID table, String name, PlayerType type, int skill, DeckCardLists deck, String password) { return false; }
    @Override public boolean roomLeaveTableOrTournament(String session, UUID room, UUID table) { return false; }
    @Override public boolean deckSubmit(String session, UUID table, DeckCardLists deck) { return false; }
    @Override public void deckSave(String session, UUID table, DeckCardLists deck) throws MageException { throw denied(); }
    @Override public boolean matchStart(String session, UUID room, UUID table) { return false; }
    @Override public boolean tournamentStart(String session, UUID room, UUID table) { return false; }
    @Override public void tableSwapSeats(String session, UUID room, UUID table, int first, int second) throws MageException { throw denied(); }
    @Override public void tableRemove(String session, UUID room, UUID table) throws MageException { throw denied(); }
    @Override public void cheatShow(UUID game, String session, UUID player) throws MageException { throw denied(); }
    private static MageException denied() { return new MageException("Managed table mutation denied"); }
    private void checkGame(UUID game, String session) throws MageException {
        var user = managers.sessionManager().getSession(session).map(Session::getUserId).orElse(null);
        if (user == null || !service.gameAllowed(user, game)) throw new MageException("Managed seat access denied");
    }
    @Override public void gameJoin(UUID game, String session) throws MageException { checkGame(game, session); super.gameJoin(game, session); }
    @Override public void matchQuit(UUID game, String session) throws MageException { checkGame(game, session); super.matchQuit(game, session); }
    @Override public void sendPlayerUUID(UUID game, String session, UUID value) throws MageException { checkGame(game, session); super.sendPlayerUUID(game, session, value); }
    @Override public void sendPlayerString(UUID game, String session, String value) throws MageException { checkGame(game, session); super.sendPlayerString(game, session, value); }
    @Override public void sendPlayerBoolean(UUID game, String session, Boolean value) throws MageException { checkGame(game, session); super.sendPlayerBoolean(game, session, value); }
    @Override public void sendPlayerInteger(UUID game, String session, Integer value) throws MageException { checkGame(game, session); super.sendPlayerInteger(game, session, value); }
    @Override public void sendPlayerManaType(UUID game, UUID player, String session, ManaType value) throws MageException { checkGame(game, session); super.sendPlayerManaType(game, player, session, value); }
    @Override public void sendPlayerAction(PlayerAction action, UUID game, String session, Object value) throws MageException {
        checkGame(game, session);
        if (action.name().contains("ROLLBACK")) throw new MageException("Managed rollback is disabled");
        super.sendPlayerAction(action, game, session, value);
    }
}
