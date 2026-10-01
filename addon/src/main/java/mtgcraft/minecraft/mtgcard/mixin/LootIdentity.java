package mtgcraft.minecraft.mtgcard.mixin;

import com.spider.mtgcard.api.DeckboxStorage;
import com.spider.mtgcard.deckbox.DeckboxBlockEntity;
import com.spider.mtgcard.util.StackData;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserve the existing record on existing normal loot; never manufacture another drop. */
@Mixin(BlockBehaviour.class)
public abstract class LootIdentity {
    @Inject(method = "getDrops", at = @At("RETURN"))
    private void identity(BlockState state, LootParams.Builder context, CallbackInfoReturnable<List<ItemStack>> result) {
        if (!(context.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof DeckboxBlockEntity box) || box.getStorageId() == null) return;
        for (var drop : result.getReturnValue()) if (drop.is(state.getBlock().asItem())) {
            var custom = StackData.readCustom(drop);
            custom.putString(DeckboxStorage.ITEM_ID_KEY, box.getStorageId().toString());
            custom.putInt("mtgcard_deckbox_tint", box.getRgbTint());
            drop.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        }
    }
}
