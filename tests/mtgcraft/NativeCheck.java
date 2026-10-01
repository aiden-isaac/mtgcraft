package mtgcraft;

import java.nio.file.*;
import java.util.Properties;
import java.util.UUID;
import mage.cards.decks.*;
import mage.game.match.MatchOptions;
import mage.game.tournament.TournamentOptions;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.players.PlayerType;
import mage.players.net.UserData;
import mage.remote.*;
import mage.utils.MageVersion;

/** Stock native session in a separate JVM; credentials come only from a private file. */
public final class NativeCheck implements MageClient {
    private volatile boolean prompt;
    private volatile boolean gameOver;
    public MageVersion getVersion() { return new MageVersion(mage.server.Main.class); }
    public void connected(String message) { }
    public void disconnected(boolean reconnect, boolean keepSession) { }
    public void showMessage(String message) { }
    public void showError(String message) { } // The adversarial calls below deliberately reject.
    public void onNewConnection() { }
    public void onCallback(ClientCallback callback) {
        if (callback.getMethod().getType() == mage.interfaces.callback.ClientCallbackType.DIALOG) prompt = true;
        if (callback.getMethod() == mage.interfaces.callback.ClientCallbackMethod.GAME_OVER) gameOver = true;
    }
    public static void main(String[] args) {
        org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.OFF);
        try { run(Path.of(args[0])); System.exit(0); }
        catch (Throwable failure) { System.err.println("Native check failed: " + failure.getClass().getSimpleName()); System.exit(1); }
    }
    private static void run(Path path) throws Exception {
        var access = new Properties(); try (var input = Files.newInputStream(path)) { access.load(input); }
        var connection = new Connection(); connection.setHost("127.0.0.1"); connection.setPort(17171);
        connection.setProxyType(Connection.ProxyType.NONE); connection.setUsername(access.getProperty("username"));
        connection.setPassword(access.getProperty("password")); connection.setUserIdStr(UUID.randomUUID().toString());
        connection.setUserData(UserData.getDefaultUserDataView());
        var room = UUID.fromString(access.getProperty("room")); var table = UUID.fromString(access.getProperty("table"));
        var player = UUID.fromString(access.getProperty("player"));
        var secondClient = new NativeCheck();
        var first = new SessionImpl(new NativeCheck()); var second = new SessionImpl(secondClient);
        try {
            assert first.connectStart(connection);
            assert !first.isTableOwner(room, table);
            assert first.getTable(room, table).orElseThrow().getSeats().stream().anyMatch(s -> player.equals(s.getPlayerId()));
            String old = first.getSessionId(); first.connectStop(false, true);
            second.setRestoreSessionId(old); assert second.connectStart(connection);
            assert second.getTable(room, table).orElseThrow().getSeats().stream().anyMatch(s -> player.equals(s.getPlayerId()));
            var replacement = new DeckCardLists(); replacement.getCards().add(new DeckCardInfo("Island", "234", "M10", 60));
            assert !second.submitDeck(table, replacement); assert !second.updateDeck(table, replacement);
            assert !second.leaveTable(room, table);
            assert !second.joinTable(room, table, "replacement", PlayerType.HUMAN, 1, replacement, "");
            assert !second.joinTournamentTable(room, table, "replacement", PlayerType.HUMAN, 1, replacement, "");
            assert !second.startMatch(room, table); assert !second.startTournament(room, table);
            assert !second.swapSeats(room, table, 0, 1); assert !second.removeTable(room, table);
            assert second.createTable(room, new MatchOptions("replacement", "Two Player Duel", false)) == null;
            assert second.createTournamentTable(room, new TournamentOptions("replacement", "Two Player Duel", false)) == null;
            assert !second.cheatShow(UUID.randomUUID(), player);
            assert !second.joinTable(room, UUID.fromString(access.getProperty("otherTable")), "hijack", PlayerType.HUMAN, 1, replacement, "");
            assert second.getTable(room, table).orElseThrow().getTableState() == mage.constants.TableState.READY_TO_START;
            System.out.println("PASS guarded native attachment/reconnect and audited table/deck mutation rejection");
            if (access.getProperty("play", "false").equals("true")) {
                Files.writeString(Path.of(path + ".ready"), "ready");
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                java.util.List<UUID> games;
                do {
                    games = second.getTable(room, table).orElseThrow().getGames();
                    if (System.nanoTime() > deadline) throw new AssertionError("managed start did not create a game");
                    if (games.isEmpty()) Thread.sleep(20);
                } while (games.isEmpty());
                var game = games.getLast();
                assert second.joinGame(game);
                while (!secondClient.prompt) {
                    if (System.nanoTime() > deadline) throw new AssertionError("native human prompt did not arrive");
                    Thread.sleep(20);
                }
                assert second.sendPlayerAction(mage.constants.PlayerAction.CONCEDE, game, null);
                while (!secondClient.gameOver || !Files.exists(Path.of(path + ".done"))) {
                    if (System.nanoTime() > deadline) throw new AssertionError("native completion was not acknowledged");
                    Thread.sleep(20);
                }
                System.out.println("PASS authenticated native game join and match concession");
            }
        } finally { first.connectStop(false, true); second.connectStop(false, true); }
    }
}
