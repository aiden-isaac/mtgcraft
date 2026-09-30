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

/** Calls real upstream managers in a disposable process; no transport or UI. */
public class XmageProbe {
    public static void main(String[] args) {
        try {
            run(args[0]);
            // Upstream managers own scheduled executors; this disposable JVM ends here.
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String configPath) throws Exception {
        BasicConfigurator.configure();
        Logger.getRootLogger().setLevel(Level.WARN);
        long start = System.nanoTime();
        RepositoryUtil.bootstrapLocalDb();
        CardScanner.scan();
        System.out.println("catalog_ready_ms=" + (System.nanoTime() - start) / 1_000_000);
        GameFactory.instance.addGameType("Two Player Duel", new TwoPlayerDuelType(), TwoPlayerMatch.class);
        DeckValidatorFactory.instance.addDeckType("Constructed - Freeform", Freeform.class);
        PlayerFactory.instance.addPlayerType("Human", HumanPlayer.class);
        PlayerFactory.instance.addPlayerType("Computer - mad", ComputerPlayerControllableProxy.class);
        var managers = new MainManagerFactory(new ConfigWrapper(ConfigFactory.loadFromFile(configPath)));
        var owner = managers.userManager().createUser("spike-human", "127.0.0.1", null).orElseThrow();
        var options = new MatchOptions("integration-spike", "Two Player Duel", false);
        options.setDeckType("Constructed - Freeform");
        options.setWinsNeeded(1);
        options.setQuitRatio(100);
        options.getPlayerTypes().add(PlayerType.HUMAN);
        options.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
        var room = managers.gamesRoomManager().getMainRoomId();
        var table = managers.tableManager().createTable(room, owner.getId(), options);
        var deck = new DeckCardLists();
        // Exact catalog printing; basic-land deck isolates table admission from AI quality.
        CardInfo plains = CardRepository.instance.findCard("M10", "230");
        assert plains != null && plains.getName().equals("Plains");
        deck.getCards().add(new DeckCardInfo(plains.getName(), "230", "M10", 60));
        assert managers.tableManager().joinTable(owner.getId(), table.getId(), "spike-human", PlayerType.HUMAN, 1, deck, "");
        assert managers.tableManager().joinTable(owner.getId(), table.getId(), "spike-ai", PlayerType.COMPUTER_MAD, 1, deck, "");
        var match = managers.tableManager().getMatch(table.getId()).orElseThrow();
        assert match.getPlayers().size() == 2;
        assert match.getPlayers().stream().filter(p -> p.getPlayer().isHuman()).count() == 1;
        for (var player : match.getPlayers()) {
            assert player.getDeck().getCards().size() == 60;
            assert player.getDeck().getCards().stream().allMatch(c -> c.getName().equals("Plains") && c.getExpansionSetCode().equals("M10"));
        }
        assert table.getState() == TableState.READY_TO_START : table.getState();
        assert !managers.tableManager().submitDeck(owner.getId(), table.getId(), new DeckCardLists());
        System.out.println("PASS table: registered human + MAD AI; exact 60-card decks; native submitDeck rejected in READY_TO_START");
        System.out.println("NOT PROVEN: authenticated client attachment, gameplay, comprehensive deck enforcement, durable result binding");
        managers.tableManager().removeTable(table.getId());
        assert !managers.tableManager().getController(table.getId()).isPresent();
        System.out.println("PASS cleanup: prepared table controller removed");
    }
}
