package mtgcraft.minecraft.mtgcard.mixin;

import com.spider.mtgcard.api.DeckboxStorage;
import com.spider.mtgcard.deckbox.DeckboxBlockEntity;
import java.util.UUID;
import mtgcraft.minecraft.mtgcard.Custody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(DeckboxBlockEntity.class)
public abstract class CacheGuard extends RandomizableContainerBlockEntity {
    @Shadow private UUID deckboxId;
    @Shadow private NonNullList<ItemStack> inventory;
    @Unique private boolean mtgcraft$reload;
    protected CacheGuard(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    @Unique private boolean mtgcraft$blocked() {
        if (level == null || level.isClientSide()) return false;
        if (Custody.locked(level.getServer(), deckboxId)) { mtgcraft$reload = true; return true; }
        if (mtgcraft$reload) {
            var record = DeckboxStorage.load(level.getServer(), level.registryAccess(), deckboxId).orElseThrow(() -> new IllegalStateException("Protected alias backing disappeared"));
            inventory = NonNullList.withSize(DeckboxBlockEntity.INVENTORY_SIZE, ItemStack.EMPTY);
            for (int i = 0; i < inventory.size(); i++) inventory.set(i, record.items().get(i).copy());
            mtgcraft$reload = false;
        }
        return false;
    }
    @Inject(method = "getItems", at = @At("HEAD"), cancellable = true)
    private void items(CallbackInfoReturnable<NonNullList<ItemStack>> result) {
        if (mtgcraft$blocked()) result.setReturnValue(NonNullList.withSize(DeckboxBlockEntity.INVENTORY_SIZE, ItemStack.EMPTY));
    }
    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
    private void item(int slot, ItemStack stack, CallbackInfo result) { if (mtgcraft$blocked()) result.cancel(); }
    @Inject(method = "setItems", at = @At("HEAD"), cancellable = true)
    private void items(NonNullList<ItemStack> items, CallbackInfo result) { if (mtgcraft$blocked()) result.cancel(); }
    @Inject(method = "persistExternalRecord", at = @At("HEAD"), cancellable = true)
    private void persist(CallbackInfo result) { if (mtgcraft$blocked()) result.cancel(); }
    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void valid(Player player, CallbackInfoReturnable<Boolean> result) { if (mtgcraft$blocked()) result.setReturnValue(false); }
    @Inject(method = "loadStorageId", at = @At("HEAD"), cancellable = true)
    private void load(UUID id, CallbackInfo result) {
        if (level != null && !level.isClientSide() && Custody.locked(level.getServer(), id)) { deckboxId = id; mtgcraft$reload = true; result.cancel(); }
    }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return !mtgcraft$blocked() && super.canPlaceItem(slot, stack); }
    @Override public boolean canTakeItem(Container target, int slot, ItemStack stack) { return !mtgcraft$blocked() && super.canTakeItem(target, slot, stack); }
}
