package mtgcraft.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.UUID;
import static java.nio.file.StandardOpenOption.*;

/** One engine process per durable state directory; replacement starts only after fencing. */
public final class ServiceState implements AutoCloseable {
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private final UUID incarnation;
    private final UUID previous;
    public ServiceState(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath(); Files.createDirectories(this.directory);
        channel = FileChannel.open(this.directory.resolve("service.lock"), CREATE, WRITE);
        FileLock acquired;
        try { acquired = channel.tryLock(); }
        catch (java.nio.channels.OverlappingFileLockException active) { acquired = null; }
        if (acquired == null) { channel.close(); throw new IOException("service state is already locked"); }
        lock = acquired;
        try {
            var path = this.directory.resolve("incarnation");
            previous = Files.exists(path) ? UUID.fromString(Files.readString(path).strip()) : null;
            incarnation = UUID.randomUUID();
            write("incarnation", (incarnation + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) { lock.release(); channel.close(); throw failure; }
    }
    public UUID incarnation() { return incarnation; }
    public UUID previous() { return previous; }
    public void bindWorld(UUID world) throws IOException {
        var path = directory.resolve("world");
        if (Files.exists(path)) {
            if (!Files.readString(path).strip().equals(world.toString())) throw new IOException("state belongs to another world");
        } else write("world", (world + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public java.util.List<byte[]> matches() throws IOException {
        var records = new java.util.ArrayList<byte[]>();
        try (var paths = Files.list(directory)) {
            for (var path : paths.filter(p -> p.getFileName().toString().matches("match-[0-9a-f-]{36}\\.json")).toList())
                records.add(Files.readAllBytes(path));
        }
        return records;
    }
    public void write(String name, byte[] bytes) throws IOException {
        if (!name.matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("invalid record name");
        var pending = Files.createTempFile(directory, "pending-", ".tmp");
        try {
            Files.setPosixFilePermissions(pending, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            try (var output = FileChannel.open(pending, WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(pending, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var parent = FileChannel.open(directory, READ)) { parent.force(true); }
        } finally { Files.deleteIfExists(pending); }
    }
    @Override public void close() throws IOException { lock.release(); channel.close(); }
}
