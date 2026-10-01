package mtgcraft.minecraft.mtgcard;

import com.spider.mtgcard.deckbox.DeckboxBlockEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class Integration implements ModInitializer {
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try { Custody.install(new Custody(server)); } catch (Exception invalid) { throw new IllegalStateException("Cannot load custody; stopping without admissions", invalid); }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((box, world) -> { if (box instanceof DeckboxBlockEntity deckbox) Custody.current().track(deckbox, true); });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((box, world) -> { if (box instanceof DeckboxBlockEntity deckbox) Custody.current().track(deckbox, false); });
    }
}
