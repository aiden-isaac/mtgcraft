package mage.server;

import mage.game.Table;
import java.util.function.BiConsumer;

/** Pinned cleanup seams call this before discarding managed engine state. */
public final class ManagedHooks {
    private static volatile BiConsumer<Table, Boolean> observer = (table, removing) -> { };
    public static void install(BiConsumer<Table, Boolean> handler) { observer = java.util.Objects.requireNonNull(handler); }
    public static void beforeCleanup(Table table, boolean removing) { observer.accept(table, removing); }
}
