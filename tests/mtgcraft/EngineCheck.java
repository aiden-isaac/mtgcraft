package mtgcraft;

import java.nio.file.Path;
import java.util.List;
import mage.cards.decks.*;
import mage.cards.repository.*;
import mage.deck.Freeform;
import mtgcraft.service.EngineDecks;
import static mtgcraft.protocol.Protocol.*;

/** Real pinned engine validation, no native client or physical custody. */
public final class EngineCheck {
    public static void main(String[] args) throws Exception {
        org.apache.log4j.BasicConfigurator.configure();
        org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.WARN);
        RepositoryUtil.bootstrapLocalDb(); CardScanner.scan();
        DeckValidatorFactory.instance.addDeckType("Constructed - Freeform", Freeform.class);
        var catalog = EngineDecks.catalog(Path.of(args[0]));
        var decks = new EngineDecks(catalog);
        var rows = catalog.manifest().printings();
        var plains = rows.getLast().card(Finish.NONFOIL);
        for (int copies : new int[]{40, 60, 100}) {
            var snapshot = DeckSnapshot.create(catalog.compatibility(), List.of(new Entry(plains, copies, Section.MAIN)));
            var prepared = decks.prepare(snapshot);
            assert prepared.validation().valid();
            var installed = Deck.load(prepared.list(), false, false);
            assert installed.getCards().size() == copies;
            decks.verify(snapshot, installed);
            installed.getCards().remove(installed.getCards().iterator().next());
            Check.rejected(() -> decks.verify(snapshot, installed));
        }
        var small = decks.prepare(DeckSnapshot.create(catalog.compatibility(), List.of(new Entry(plains, 39, Section.MAIN))));
        assert !small.validation().valid() && small.validation().errors().getFirst().code().equals("FORMAT_INVALID");
        for (int index : new int[]{0, 1, 2, 3}) {
            var snapshot = DeckSnapshot.create(catalog.compatibility(), List.of(new Entry(plains, 56, Section.MAIN),
                    new Entry(rows.get(index).card(Finish.FOIL), 4, Section.MAIN)));
            var prepared = decks.prepare(snapshot);
            assert prepared.validation().valid() : prepared.validation();
            decks.verify(snapshot, Deck.load(prepared.list(), false, false));
        }
        var unsupported = decks.prepare(DeckSnapshot.create(catalog.compatibility(), List.of(new Entry(plains, 59, Section.MAIN),
                new Entry(rows.get(4).card(Finish.NONFOIL), 1, Section.MAIN))));
        assert !unsupported.validation().valid() && unsupported.list() == null;
        System.out.println("PASS real engine exact deck/quantities/faces, foil/reprints, format validation, >99-copy row handling and unsupported rejection");
    }
}
