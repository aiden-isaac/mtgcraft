import java.util.concurrent.*;
import mage.constants.TableState;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.Table;
import mage.server.MainManagerFactory;
import mage.server.game.GameWorker;
import mage.server.record.TableRecordRepository;

/** Engine-driven concession and upstream history check, with no native client. */
class ResultProbe {
    static void run(MainManagerFactory managers, Table table) throws Exception {
        var match = table.getMatch();
        var human = match.getPlayers().stream().filter(p -> p.getPlayer().isHuman()).findFirst().orElseThrow();
        var ai = match.getPlayers().stream().filter(p -> !p.getPlayer().isHuman()).findFirst().orElseThrow();
        match.startMatch();
        mage.game.result.ResultProtos.TableProto expected = null;
        boolean probeDraw = Boolean.getBoolean("probe.draw");
        for (boolean draw : probeDraw ? new boolean[]{true, false} : new boolean[]{false}) {
            match.startGame();
            table.initGame();
            Game game = match.getGame();
            game.setGameOptions(new GameOptions());
            if (draw) {
                var injected = new java.util.concurrent.atomic.AtomicBoolean();
                game.addPlayerQueryEventListener(event -> {
                    if (game.getState().getPlayersInRange(human.getPlayer().getId(), game).size() == 2
                            && injected.compareAndSet(false, true)) {
                        // Rules effects run on the game worker; setDraw is not a network command.
                        game.setDraw(human.getPlayer().getId());
                    }
                });
            }
            var finished = new CompletableFuture<mage.game.result.ResultProtos.TableProto>();
            var worker = new Thread(() -> {
                new GameWorker(game, ai.getPlayer().getId(), ignoredText -> {
                    match.endGame();
                    assert match.getDraws() == (probeDraw ? 1 : 0);
                    assert match.hasEnded() == !draw;
                    assert ai.isMatchWinner() == !draw;
                    assert ai.getWins() == (draw ? 0 : 1);
                    assert human.getWins() == 0;
                    var snapshot = table.toProto();
                    if (!draw) table.closeTable();
                    finished.complete(snapshot);
                }).call();
                if (!finished.isDone()) finished.completeExceptionally(new IllegalStateException("worker ended without verified result"));
            });
            worker.start();
            if (!draw) {
                // ponytail: inspect the isolated worker's wait; native client prompts are a separate spike.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (worker.getState() != Thread.State.WAITING && worker.getState() != Thread.State.TIMED_WAITING) {
                    if (System.nanoTime() > deadline || !worker.isAlive()) throw new IllegalStateException("human prompt did not become pending");
                    Thread.sleep(10);
                }
                game.concede(human.getPlayer().getId());
            }
            expected = finished.get(15, TimeUnit.SECONDS);
            worker.join(2000);
            assert !worker.isAlive();
        }
        assert expected != null;
        java.nio.file.Files.write(java.nio.file.Paths.get("expected-table.pb"), expected.toByteArray());
        assert table.getState() == TableState.FINISHED;
        managers.tableManager().removeTable(table.getId());
        assert !managers.tableManager().getController(table.getId()).isPresent();
        verifyHistory();
        System.out.println("PASS result: concession -> structured AI win; history survives controller cleanup; draws=" + expected.getMatch().getDraws());
        System.out.println("NOT PROVEN: stable project result/seat binding, persistence acknowledgement, native-client callback path; draw injection remains experimental");
    }

    static void verifyHistory() throws Exception {
        var expected = mage.game.result.ResultProtos.TableProto.parseFrom(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("expected-table.pb")));
        assert expected.getMatch().getGames() == expected.getMatch().getDraws() + 1;
        assert TableRecordRepository.instance.getAfter(expected.getEndTimeMs() - 1).stream()
                .anyMatch(record -> expected.equals(record.getProto()));
        System.out.println("PASS stored history: exact structured snapshot retrieved from upstream SQLite");
    }
}
