import mage.cards.decks.*;
import mage.cards.repository.*;
import mage.constants.TableState;
import mage.deck.Freeform;
import mage.game.*;
import mage.game.match.*;
import mage.player.ai.ComputerPlayerControllableProxy;
import mage.player.human.HumanPlayer;
import mage.players.PlayerType;
import mage.server.*;
import mage.server.game.GameFactory;
import mage.server.game.PlayerFactory;
import mage.server.util.ConfigFactory;
import mage.server.util.ConfigWrapper;
import org.apache.log4j.*;

/** Calls real upstream managers; optional stock native-session child process, no UI. */
public class XmageProbe {
    public static void main(String[] args) {
        try {
            if (Boolean.getBoolean("probe.historyOnly")) {
                ResultProbe.verifyHistory();
                System.exit(0);
            }
            run(args);
            // Upstream managers own scheduled executors; this disposable JVM ends here.
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        BasicConfigurator.configure();
        Logger.getRootLogger().setLevel(Level.WARN);
        long start = System.nanoTime();
        RepositoryUtil.bootstrapLocalDb();
        CardScanner.scan();
        System.out.println("catalog_ready_ms=" + (System.nanoTime() - start) / 1_000_000);
        if (args.length == 3) MappingProbe.run(args[1], args[2]);
        GameFactory.instance.addGameType("Two Player Duel", new TwoPlayerDuelType(), TwoPlayerMatch.class);
        DeckValidatorFactory.instance.addDeckType("Constructed - Freeform", Freeform.class);
        PlayerFactory.instance.addPlayerType("Human", HumanPlayer.class);
        PlayerFactory.instance.addPlayerType("Computer - mad", ComputerPlayerControllableProxy.class);
        var managers = new MainManagerFactory(new ConfigWrapper(ConfigFactory.loadFromFile(args[0])));
        boolean nativeProbe = Boolean.getBoolean("probe.native");
        if (nativeProbe && (!managers.configSettings().isAuthenticationActivated()
                || !managers.configSettings().getServerAddress().equals("127.0.0.1"))) {
            throw new IllegalArgumentException("native probe requires authenticated loopback config");
        }
        var owner = managers.userManager().createUser("spike-owner", "127.0.0.1", null).orElseThrow();
        String username = "spike_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String password = java.util.UUID.randomUUID().toString();
        User human = owner;
        if (nativeProbe) {
            var users = AuthorizedUserRepository.getInstance();
            users.add(username, password, username + "@example.invalid");
            human = managers.userManager().createUser(username, "127.0.0.1", users.getByName(username)).orElseThrow();
        }
        var options = new MatchOptions("integration-spike", "Two Player Duel", false);
        options.setDeckType("Constructed - Freeform");
        options.setWinsNeeded(1);
        options.setQuitRatio(100);
        options.getPlayerTypes().add(PlayerType.HUMAN);
        options.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
        var room = managers.gamesRoomManager().getMainRoomId();
        var view = managers.gamesRoomManager().getRoom(room).orElseThrow().createTable(owner.getId(), options);
        var table = managers.tableManager().getTable(view.getTableId());
        var deck = new DeckCardLists();
        // Exact catalog printing; basic-land deck isolates table admission from AI quality.
        CardInfo plains = CardRepository.instance.findCard("M10", "230");
        assert plains != null && plains.getName().equals("Plains");
        deck.getCards().add(new DeckCardInfo(plains.getName(), "230", "M10", 60));
        assert managers.tableManager().joinTable(human.getId(), table.getId(), human.getName(), PlayerType.HUMAN, 1, deck, "");
        assert managers.tableManager().joinTable(owner.getId(), table.getId(), "spike-ai", PlayerType.COMPUTER_MAD, 1, deck, "");
        var match = managers.tableManager().getMatch(table.getId()).orElseThrow();
        assert match.getPlayers().size() == 2;
        assert match.getPlayers().stream().filter(p -> p.getPlayer().isHuman()).count() == 1;
        for (var player : match.getPlayers()) {
            assert player.getDeck().getCards().size() == 60;
            assert player.getDeck().getCards().stream().allMatch(c -> c.getName().equals("Plains") && c.getExpansionSetCode().equals("M10"));
        }
        assert table.getState() == TableState.READY_TO_START : table.getState();
        assert !managers.tableManager().submitDeck(human.getId(), table.getId(), new DeckCardLists());
        System.out.println("PASS table: registered human + MAD AI; exact 60-card decks; native submitDeck rejected in READY_TO_START");
        System.out.println("NOT PROVEN: authenticated client attachment, gameplay, comprehensive deck enforcement, durable result binding");
        if (nativeProbe) {
            NativeTransport.start(managers);
            var access = new java.util.Properties();
            access.setProperty("username", username);
            access.setProperty("password", password);
            access.setProperty("room", room.toString());
            access.setProperty("table", table.getId().toString());
            access.setProperty("player", match.getPlayers().stream().filter(p -> p.getPlayer().isHuman())
                    .findFirst().orElseThrow().getPlayer().getId().toString());
            var accessPath = java.nio.file.Paths.get("seat.properties");
            try {
                java.nio.file.Files.createFile(accessPath, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
                try (var output = java.nio.file.Files.newOutputStream(accessPath)) { access.store(output, "Disposable local probe only"); }
                // ponytail: two local JVMs and a private credential file; no service protocol in this spike.
                var client = new ProcessBuilder(
                        java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                        "-ea", "-Xmx512m", "--add-opens=java.base/java.io=ALL-UNNAMED",
                        "-cp", System.getProperty("java.class.path"), "NativeClientProbe", accessPath.toString())
                        .inheritIO().start();
                try {
                    if (!client.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("native probe client timed out");
                    }
                    assert client.exitValue() == 0 : "native client probe failed";
                } finally {
                    if (client.isAlive()) client.destroyForcibly().waitFor();
                }
                var submitted = match.getPlayers().stream().filter(p -> p.getPlayer().isHuman() && !p.hasQuit()).findFirst().orElseThrow().getDeck();
                assert submitted.getCards().size() == 60;
                assert submitted.getCards().stream().allMatch(c -> c.getName().equals("Island"));
                System.out.println("CONFIRMED DECK-LOCK GAP: native leave/rejoin replaced precreated Plains deck with Islands");
                assert table.getState() == TableState.STARTING : table.getState();
                System.out.println("CONFIRMED START GAP: non-owner matchStart moved table to STARTING");
            } finally {
                java.nio.file.Files.deleteIfExists(accessPath);
                AuthorizedUserRepository.getInstance().remove(username);
            }
        }
        if (Boolean.getBoolean("probe.result")) {
            ResultProbe.run(managers, table);
            return;
        }
        managers.tableManager().removeTable(table.getId());
        assert !managers.tableManager().getController(table.getId()).isPresent();
        System.out.println("PASS cleanup: prepared table controller removed");
    }
}
