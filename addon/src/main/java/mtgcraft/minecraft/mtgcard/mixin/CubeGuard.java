package mtgcraft.minecraft.mtgcard.mixin;

import com.spider.mtgcard.deckbox.SulfurCubeDeckboxCards;
import mtgcraft.minecraft.mtgcard.Custody;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SulfurCubeDeckboxCards.class)
public abstract class CubeGuard {
    @Inject(method = "dropRandomCard", at = @At("HEAD"), cancellable = true)
    private static void eject(SulfurCube cube, ServerLevel level, CallbackInfo result) {
        if (Custody.locked(level.getServer(), cube.getItemBySlot(EquipmentSlot.BODY))) result.cancel();
    }
}
