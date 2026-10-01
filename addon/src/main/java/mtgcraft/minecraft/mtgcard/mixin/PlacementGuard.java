package mtgcraft.minecraft.mtgcard.mixin;

import mtgcraft.minecraft.mtgcard.Custody;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class PlacementGuard {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void place(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> result) {
        if (!context.getLevel().isClientSide() && Custody.locked(context.getLevel().getServer(), context.getItemInHand())) result.setReturnValue(InteractionResult.FAIL);
    }
}
