package mage.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import mage.cards.repository.*;
import mage.cards.decks.DeckValidatorFactory;
import mage.deck.Freeform;
import mage.game.*;
import mage.player.ai.ComputerPlayerControllableProxy;
import mage.player.human.HumanPlayer;
import mage.server.game.GameFactory;
import mage.server.game.PlayerFactory;
import mage.server.util.ConfigFactory;
import mage.server.util.ConfigWrapper;
import mtgcraft.protocol.*;
import mtgcraft.service.*;
import static mtgcraft.protocol.Protocol.*;

/** Pinned service bootstrap; source package gives access to the verified transport seam. */
public final class ServiceMain {
    public static void main(String[] args) {
        try { run(Path.of(args[0]).toAbsolutePath()); }
        catch (Exception failure) { System.err.println("Service startup failed: " + failure.getClass().getSimpleName()); System.exit(1); }
    }
    private static Path path(Path config, Properties properties, String key) {
        return config.getParent().resolve(properties.getProperty(key)).normalize();
    }
    private static void run(Path config) throws Exception {
        var properties = new Properties();
        try (var input = Files.newInputStream(config)) { properties.load(input); }
        var world = UUID.fromString(properties.getProperty("worldId"));
        var limit = Integer.parseInt(properties.getProperty("activeMatchLimit", "2"));
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("invalid match capacity");
        var tokenPath = path(config, properties, "tokenFile");
        var permissions = Files.getPosixFilePermissions(tokenPath);
        if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
            throw new IllegalArgumentException("credential file must be private");
        var token = Files.readString(tokenPath).strip();
        var state = new ServiceState(Path.of("."));
        long started = System.nanoTime();
        org.apache.log4j.BasicConfigurator.configure();
        org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.WARN);
        RepositoryUtil.bootstrapLocalDb(); CardScanner.scan();
        // Pin the actual sets artifact, not only upstream's version counters.
        var setsJar = Path.of(System.getProperty("mtgcraft.setsJar"));
        var catalog = EngineDecks.catalog(path(config, properties, "mappingFile"));
        if (!catalog.compatibility().catalogDigest().equals(Canonical.sha256(Files.readAllBytes(setsJar))))
            throw new IllegalArgumentException("installed catalog artifact mismatch");
        GameFactory.instance.addGameType("Two Player Duel", new TwoPlayerDuelType(), TwoPlayerMatch.class);
        DeckValidatorFactory.instance.addDeckType("Constructed - Freeform", Freeform.class);
        PlayerFactory.instance.addPlayerType("Human", HumanPlayer.class);
        PlayerFactory.instance.addPlayerType("Computer - mad", ComputerPlayerControllableProxy.class);
        var managers = new MainManagerFactory(new ConfigWrapper(ConfigFactory.loadFromFile(path(config, properties, "nativeConfig").toString())));
        if (!managers.configSettings().isAuthenticationActivated() || !managers.configSettings().getServerAddress().equals("127.0.0.1") || Main.isTestMode())
            throw new IllegalArgumentException("native service requires authenticated loopback, test mode off");
        var service = new ManagedMatches(state, world, limit, catalog, managers);
        ManagedTransport.start(managers);
        var http = new ControlServer(java.net.InetAddress.getByName(properties.getProperty("bind", "127.0.0.1")),
                Integer.parseInt(properties.getProperty("port", "17180")), world, token, null, service);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.close();
            // Release the process lock only on actual process exit, after engine threads die.
        }));
        System.out.println("control_listening startup_ms=" + (System.nanoTime() - started) / 1_000_000
                + " incarnation=" + state.incarnation() + " active_match_limit=" + limit);
        Thread.currentThread().join();
    }
}
