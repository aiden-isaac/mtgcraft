package mtgcraft.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import static mtgcraft.protocol.Protocol.*;

/** Versioned, length-prefixed UTF-8 encoding; independent of JSON property order. */
public final class Canonical {
    private interface Encoder { void write(DataOutputStream output) throws IOException; }
    private static byte[] encode(Encoder encoder) {
        try {
            var buffer = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(buffer)) { encoder.write(output); }
            return buffer.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void string(DataOutputStream output, Object value) throws IOException {
        if (value == null) { output.writeInt(-1); return; }
        var bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }
    private static byte[] card(CardIdentity card) {
        return encode(o -> {
            string(o, card.cardKey()); string(o, card.scryfallId()); string(o, card.oracleId());
            string(o, card.set()); string(o, card.collectorNumber()); string(o, card.finish());
            string(o, card.layout()); o.writeInt(card.faces().size());
            for (var face : card.faces()) string(o, face);
            string(o, card.label());
        });
    }
    public static List<Entry> entries(List<Entry> entries) {
        if (entries == null || entries.isEmpty() || entries.size() > MAX_ENTRIES)
            throw new IllegalArgumentException("invalid deck size");
        if (entries.stream().mapToLong(Entry::quantity).sum() > MAX_CARDS)
            throw new IllegalArgumentException("too many cards");
        var grouped = new TreeMap<String, Entry>();
        for (var entry : entries) {
            var key = entry.section() + ":" + HexFormat.of().formatHex(card(entry.card()));
            var prior = grouped.get(key);
            grouped.put(key, prior == null ? entry : new Entry(entry.card(), prior.quantity() + entry.quantity(), entry.section()));
        }
        return List.copyOf(grouped.values());
    }
    public static String snapshotDigest(Compatibility compatibility, List<Entry> entries) {
        return sha256(encode(o -> {
            string(o, "mtgcraft.snapshot.v1"); o.writeInt(compatibility.protocolVersion());
            string(o, compatibility.format()); string(o, compatibility.formatVersion());
            string(o, compatibility.mappingDigest()); string(o, compatibility.engineBuild()); string(o, compatibility.catalogDigest());
            var canonical = entries(entries); o.writeInt(canonical.size());
            for (var entry : canonical) {
                string(o, entry.section()); o.write(card(entry.card())); o.writeInt(entry.quantity());
            }
        }));
    }
    public static String requestDigest(UUID world, UUID match, List<Seat> seats, DeckSnapshot snapshot, String ai) {
        return sha256(encode(o -> {
            string(o, "mtgcraft.request.v1"); o.writeInt(VERSION); string(o, world); string(o, match);
            for (var seat : seats(seats)) { string(o, seat.seatId()); string(o, seat.role()); string(o, seat.playerId()); }
            string(o, snapshot.digest()); string(o, ai);
        }));
    }
}
