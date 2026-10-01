package mtgcraft.minecraft.mtgcard.mixin;

import com.spider.mtgcard.deckbox.DeckboxBlockEntity;
import mtgcraft.minecraft.mtgcard.Custody;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** All pinned store/database/control/menu/automation lookups use this shared accessor. */
@Mixin(Level.class)
public abstract class WorldAccessGuard {
    @Inject(method = "getBlockEntity", at = @At("RETURN"), cancellable = true)
    private void lookup(BlockPos pos, CallbackInfoReturnable<BlockEntity> result) {
        var world = (Level) (Object) this;
        if (!world.isClientSide() && result.getReturnValue() instanceof DeckboxBlockEntity box && Custody.locked(world.getServer(), box.getStorageId())) result.setReturnValue(null);
    }
}
