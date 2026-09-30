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
            run();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
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
