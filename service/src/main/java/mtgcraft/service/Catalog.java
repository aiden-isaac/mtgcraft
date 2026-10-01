package mtgcraft.service;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import mtgcraft.protocol.Canonical;
import mtgcraft.protocol.Protocol.Error;
import static mtgcraft.protocol.Protocol.*;

/** Engine printing identifiers are private to this service adapter. */
public final class Catalog {
    public record EnginePrinting(String set, String collectorNumber, String name) {
        public EnginePrinting { text(set); text(collectorNumber); text(name); }
    }
    public record Printing(String cardKey, UUID scryfallId, UUID oracleId, String set,
                           String collectorNumber, String layout, List<String> faces,
                           String label, String source, EnginePrinting engine, List<EnginePrinting> equivalents) {
        public Printing {
            text(cardKey); text(set); text(collectorNumber); text(layout); text(label); text(source);
            faces = List.copyOf(faces); equivalents = List.copyOf(equivalents);
            if (engine == null && !equivalents.isEmpty()) throw new IllegalArgumentException("unsupported printing has equivalents");
        }
        public CardIdentity card(Finish finish) {
            return new CardIdentity(cardKey, scryfallId, oracleId, set, collectorNumber, finish, layout, faces, label);
        }
    }
    public record Manifest(int protocolVersion, String sourceCatalog, String engineBuild,
                           String catalogDigest, String format, String formatVersion, List<Printing> printings) {
        public Manifest {
            version(protocolVersion); text(sourceCatalog); text(engineBuild); digest(catalogDigest);
            text(format); text(formatVersion); printings = List.copyOf(printings);
        }
    }
    public record Resolved(Entry entry, EnginePrinting printing) {}
    public record Resolution(Validation validation, List<Resolved> cards) {
        public Resolution { cards = List.copyOf(cards); }
    }
    private final Manifest manifest;
    private final Compatibility compatibility;
    private final Predicate<EnginePrinting> installed;
    public Catalog(Path path, Predicate<EnginePrinting> installed) throws IOException {
        this(Files.readAllBytes(path), installed);
    }
    public Catalog(byte[] bytes, Predicate<EnginePrinting> installed) {
        manifest = new Gson().fromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Manifest.class);
        compatibility = new Compatibility(VERSION, manifest.format(), manifest.formatVersion(),
                Canonical.sha256(bytes), manifest.engineBuild(), manifest.catalogDigest());
        this.installed = java.util.Objects.requireNonNull(installed);
        // Equivalent tuples must reference another approved printing of the same Oracle/layout/faces.
        for (var p : manifest.printings()) for (var equivalent : p.equivalents()) {
            if (p.oracleId() == null || manifest.printings().stream().noneMatch(other -> equivalent.equals(other.engine())
                    && p.oracleId().equals(other.oracleId()) && p.layout().equals(other.layout()) && p.faces().equals(other.faces())))
                throw new IllegalArgumentException("unapproved equivalent printing");
        }
    }
    public Compatibility compatibility() { return compatibility; }
    public Manifest manifest() { return manifest; }
    private static Error error(String code, String message, int index) {
        return new Error(code, message, false, index < 0 ? List.of() : List.of(index));
    }
    public Resolution resolve(DeckSnapshot snapshot) {
        var errors = new ArrayList<Error>();
        var resolved = new ArrayList<Resolved>();
        if (!compatibility.equals(snapshot.compatibility())) {
            errors.add(error("VERSION_MISMATCH", "Service and snapshot compatibility differ", -1));
            return new Resolution(new Validation(VERSION, false, snapshot.digest(), errors), List.of());
        }
        for (int i = 0; i < snapshot.entries().size(); i++) {
            var entry = snapshot.entries().get(i);
            var card = entry.card();
            if (entry.section() != Section.MAIN) {
                errors.add(error("UNSUPPORTED_SECTION", "Only a main deck is supported", i));
            }
            // Consider every supplied authoritative field; never pick the first matching identifier.
            var candidates = manifest.printings().stream().filter(p -> p.cardKey().equals(card.cardKey())
                    || card.scryfallId() != null && card.scryfallId().equals(p.scryfallId())
                    || p.set().equals(card.set()) && p.collectorNumber().equals(card.collectorNumber())).toList();
            if (candidates.isEmpty()) { errors.add(error("UNMAPPED_CARD", "Printing is absent from the pinned manifest", i)); continue; }
            var matches = candidates.stream().filter(p -> p.cardKey().equals(card.cardKey())
                    && (card.scryfallId() == null || card.scryfallId().equals(p.scryfallId()))
                    && (card.oracleId() == null || card.oracleId().equals(p.oracleId()))
                    && p.set().equals(card.set()) && p.collectorNumber().equals(card.collectorNumber())
                    && p.layout().equals(card.layout()) && p.faces().equals(card.faces())).toList();
            if (matches.isEmpty()) { errors.add(error("IDENTITY_CONFLICT", "Identifiers or face/layout fields contradict the manifest", i)); continue; }
            if (matches.size() != 1 || candidates.size() != 1) { errors.add(error("AMBIGUOUS_CARD", "Multiple manifest records identify this printing", i)); continue; }
            var printing = matches.getFirst();
            if (printing.engine() == null) { errors.add(error("UNSUPPORTED_CARD", "No approved engine implementation", i)); continue; }
            EnginePrinting engine = null;
            if (installed.test(printing.engine())) engine = printing.engine();
            else {
                var equivalents = printing.equivalents().stream().filter(installed).distinct().toList();
                if (equivalents.size() > 1) { errors.add(error("AMBIGUOUS_CARD", "Multiple installed equivalent printings", i)); continue; }
                if (equivalents.size() == 1) engine = equivalents.getFirst();
            }
            if (engine == null) { errors.add(error("MISSING_ENGINE_CARD", "Approved printing is absent from the installed engine catalog", i)); continue; }
            resolved.add(new Resolved(entry, engine));
        }
        var validation = new Validation(VERSION, errors.isEmpty(), snapshot.digest(), errors);
        return new Resolution(validation, validation.valid() ? resolved : List.of());
    }
}
