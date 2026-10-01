package mtgcraft.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import mage.cards.Card;
import mage.cards.SplitCard;
import mage.cards.decks.*;
import mage.cards.repository.CardRepository;
import mtgcraft.protocol.Protocol.Error;
import static mtgcraft.protocol.Protocol.*;

/** The only conversion from project snapshots to engine deck objects. */
public final class EngineDecks {
    private final Catalog catalog;
    public record Prepared(Validation validation, DeckCardLists list) {}
    public EngineDecks(Catalog catalog) { this.catalog = catalog; }
    public static Catalog catalog(Path path) throws java.io.IOException {
        return new Catalog(path, printing -> {
            var info = CardRepository.instance.findCard(printing.set(), printing.collectorNumber());
            return info != null && info.getName().equals(printing.name()) && info.createCard() != null;
        });
    }
    public Prepared prepare(DeckSnapshot snapshot) {
        var resolved = catalog.resolve(snapshot);
        if (!resolved.validation().valid()) return new Prepared(resolved.validation(), null);
        var list = new DeckCardLists();
        for (var row : resolved.cards()) {
            var p = row.printing();
            // One row per copy follows XMage's convention and avoids its 99-copy row cap.
            for (int i = 0; i < row.entry().quantity(); i++) list.getCards().add(new DeckCardInfo(p.name(), p.collectorNumber(), p.set()));
        }
        Deck deck;
        try { deck = Deck.load(list, false, false); }
        catch (mage.game.GameException invalid) {
            return new Prepared(new Validation(VERSION, false, snapshot.digest(), List.of(
                    new Error("ENGINE_DECK_MISMATCH", "Engine could not install the approved deck", false, List.of()))), null);
        }
        var errors = new ArrayList<Error>();
        try { verify(snapshot, deck); }
        catch (IllegalArgumentException invalid) { errors.add(new Error("ENGINE_DECK_MISMATCH", "Installed deck differs from the approved snapshot", false, List.of())); }
        var validator = DeckValidatorFactory.instance.createDeckValidator("Constructed - Freeform");
        if (!validator.validate(deck)) for (var error : validator.getErrorsList())
            errors.add(new Error("FORMAT_INVALID", error.getMessage(), false, List.of()));
        var validation = new Validation(VERSION, errors.isEmpty(), snapshot.digest(), errors);
        return new Prepared(validation, validation.valid() ? list : null);
    }
    public void verify(DeckSnapshot snapshot, Deck installed) {
        var resolved = catalog.resolve(snapshot);
        if (!resolved.validation().valid() || !installed.getSideboard().isEmpty()) throw new IllegalArgumentException("unapproved deck");
        var expected = new TreeMap<Catalog.EnginePrinting, Integer>(java.util.Comparator.comparing(Catalog.EnginePrinting::set)
                .thenComparing(Catalog.EnginePrinting::collectorNumber).thenComparing(Catalog.EnginePrinting::name));
        var actual = new TreeMap<Catalog.EnginePrinting, Integer>(expected.comparator());
        var identities = new java.util.HashMap<Catalog.EnginePrinting, CardIdentity>();
        var classes = new java.util.HashMap<Catalog.EnginePrinting, Class<?>>();
        for (var row : resolved.cards()) {
            expected.merge(row.printing(), row.entry().quantity(), Integer::sum);
            var info = CardRepository.instance.findCard(row.printing().set(), row.printing().collectorNumber());
            var card = info.createCard();
            verifyFaces(row.entry().card(), card);
            identities.put(row.printing(), row.entry().card()); classes.put(row.printing(), card.getClass());
        }
        for (var card : installed.getCards()) {
            var key = new Catalog.EnginePrinting(card.getExpansionSetCode(), card.getCardNumber(), card.getName());
            actual.merge(key, 1, Integer::sum);
            if (!card.getClass().equals(classes.get(key))) throw new IllegalArgumentException("extra/wrong engine implementation");
            verifyFaces(identities.get(key), card);
        }
        if (!actual.equals(expected)) throw new IllegalArgumentException("installed quantities differ");
    }
    private static void verifyFaces(CardIdentity identity, Card card) {
        switch (identity.layout()) {
            case "normal" -> {
                if (!identity.faces().isEmpty() || card instanceof SplitCard || card.isTransformable()) throw new IllegalArgumentException("invalid normal layout");
            }
            case "split" -> {
                if (!(card instanceof SplitCard split) || !identity.faces().equals(List.of(split.getLeftHalfCard().getName(), split.getRightHalfCard().getName())))
                    throw new IllegalArgumentException("split faces differ");
            }
            case "transform" -> {
                if (!card.isTransformable() || !identity.faces().equals(List.of(card.getName(), card.getSecondCardFace().getName())))
                    throw new IllegalArgumentException("transform faces differ");
            }
            default -> throw new IllegalArgumentException("unsupported engine face layout");
        }
    }
}
