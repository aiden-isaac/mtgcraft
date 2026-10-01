package mtgcraft.minecraft.mtgcard.mixin;

import com.spider.mtgcard.api.DeckboxStorage;
import java.util.Optional;
import java.util.UUID;
import mtgcraft.minecraft.mtgcard.Custody;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(DeckboxStorage.class)
public abstract class StorageGuard {
    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private static void read(MinecraftServer server, HolderLookup.Provider registries, UUID id, CallbackInfoReturnable<Optional<DeckboxStorage.Record>> result) {
        if (Custody.locked(server, id)) result.setReturnValue(Optional.empty());
    }
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private static void write(MinecraftServer server, HolderLookup.Provider registries, UUID id, String type, String name, NonNullList<ItemStack> items, CallbackInfo result) {
        if (Custody.locked(server, id)) result.cancel();
    }
}
