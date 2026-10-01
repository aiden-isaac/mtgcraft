import java.util.UUID;
import mage.cards.decks.*;
import mage.game.match.MatchOptions;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.players.PlayerType;
import mage.players.net.UserData;
import mage.remote.*;
import mage.server.AuthorizedUserRepository;
import mage.utils.MageVersion;

/** Uses stock SessionImpl, not the Swing UI. Run only in the disposable server directory. */
public class NativeClientProbe implements MageClient {
    public MageVersion getVersion() { return new MageVersion(mage.server.Main.class); }
    public void connected(String message) { }
    public void disconnected(boolean reconnect, boolean keepSession) { }
    public void showMessage(String message) { System.out.println(message); }
    public void showError(String message) { throw new IllegalStateException(message); }
    public void onNewConnection() { }
    public void onCallback(ClientCallback callback) { }

    public static void main(String[] args) {
        try {
            if (args.length == 0) run();
            else managed(java.nio.file.Paths.get(args[0]));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void managed(java.nio.file.Path accessPath) throws Exception {
        var access = new java.util.Properties();
        try (var input = java.nio.file.Files.newInputStream(accessPath)) { access.load(input); }
        var connection = new Connection();
        connection.setHost("127.0.0.1");
        connection.setPort(17171);
        connection.setProxyType(Connection.ProxyType.NONE);
        connection.setUsername(access.getProperty("username"));
        connection.setPassword(access.getProperty("password"));
        connection.setUserIdStr(UUID.randomUUID().toString());
        connection.setUserData(UserData.getDefaultUserDataView());
        var room = UUID.fromString(access.getProperty("room"));
        var table = UUID.fromString(access.getProperty("table"));
        var player = UUID.fromString(access.getProperty("player"));
        var first = new SessionImpl(new NativeClientProbe());
        var second = new SessionImpl(new NativeClientProbe());
        try {
            assert first.connectStart(connection) : first.getLastError();
            assert !first.isTableOwner(room, table);
            assert first.getTable(room, table).orElseThrow().getSeats().size() == 2;
            assert first.getTable(room, table).orElseThrow().getSeats().stream()
                    .anyMatch(s -> player.equals(s.getPlayerId()) && access.getProperty("username").equals(s.getPlayerName()));
            String previousSession = first.getSessionId();
            first.connectStop(false, true);
            second.setRestoreSessionId(previousSession);
            assert second.connectStart(connection) : second.getLastError();
            assert !second.isTableOwner(room, table);
            assert second.getTable(room, table).orElseThrow().getSeats().stream()
                    .anyMatch(s -> player.equals(s.getPlayerId()) && access.getProperty("username").equals(s.getPlayerName()));
            var replacement = new DeckCardLists();
            replacement.getCards().add(new DeckCardInfo("Island", "234", "M10", 60));
            assert !second.submitDeck(table, replacement);
            assert second.leaveTable(room, table);
            assert second.joinTable(room, table, access.getProperty("username"), PlayerType.HUMAN, 1, replacement, "");
            assert second.startMatch(room, table);
            System.out.println("PASS native protocol: authenticated non-owner attaches/reconnects to precreated human seat");
            System.out.println("CONFIRMED: direct deckSubmit rejected, but leave/rejoin accepts a replacement deck");
            // Keep table until the server verifies its contents; disposable server exits afterward.
        } finally {
            first.connectStop(false, true);
            second.connectStop(false, true);
        }
    }

    private static void run() throws Exception {
        String username = "spike_" + UUID.randomUUID().toString().substring(0, 8);
        String password = UUID.randomUUID().toString();
        var users = AuthorizedUserRepository.getInstance();
        users.add(username, password, username + "@example.invalid"); // Local DB only; no email.
        assert users.getByName(username).doCredentialsMatch(username, password);
        var connection = new Connection();
        connection.setHost("127.0.0.1");
        connection.setPort(17171);
        connection.setProxyType(Connection.ProxyType.NONE);
        connection.setUsername(username);
        connection.setPassword(password);
        connection.setUserIdStr(UUID.randomUUID().toString());
        connection.setUserData(UserData.getDefaultUserDataView());
        var first = new SessionImpl(new NativeClientProbe());
        var second = new SessionImpl(new NativeClientProbe());
        try {
            assert first.connectStart(connection) : first.getLastError();
            var room = first.getMainRoomId();
            var options = new MatchOptions("native-seat-spike", "Two Player Duel", false);
            options.setDeckType("Constructed - Freeform");
            options.setWinsNeeded(1);
            options.setQuitRatio(100);
            options.getPlayerTypes().add(PlayerType.HUMAN);
            options.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
            var table = first.createTable(room, options);
            assert table != null;
            var deck = new DeckCardLists();
            deck.getCards().add(new DeckCardInfo("Plains", "230", "M10", 60));
            assert first.joinTable(room, table.getTableId(), username, PlayerType.HUMAN, 1, deck, "");
            assert first.joinTable(room, table.getTableId(), "spike-ai", PlayerType.COMPUTER_MAD, 1, deck, "");
            String oldSession = first.getSessionId();
            first.connectStop(false, true);
            second.setRestoreSessionId(oldSession);
            assert second.connectStart(connection) : second.getLastError();
            assert second.isTableOwner(room, table.getTableId());
            assert second.getTable(room, table.getTableId()).orElseThrow().getSeats().size() == 2;
            System.out.println("PASS native session: authenticated account reconnects and retains its prepared human/AI table");
            System.out.println("NOT PROVEN: Swing client UI, managed seat security, active-game reconnect or deck locking");
            second.removeTable(room, table.getTableId());
        } finally {
            first.connectStop(false, false);
            second.connectStop(false, false);
            users.remove(username);
        }
    }
}
