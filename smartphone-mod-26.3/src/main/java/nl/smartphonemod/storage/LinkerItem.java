package nl.smartphonemod.storage;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The linker ("Koppeltool"). Right-click a hub to select it, right-click chests/barrels to link or
 * unlink them, sneak + right-click two corners to link every container in a box. The selection is
 * stored on the item itself (custom data), so the tooltip can show it.
 */
public class LinkerItem extends Item {
    public LinkerItem(Properties properties) {
        super(properties);
    }

    /** The hub this linker is set to. */
    public record Selection(BlockPos hub, ResourceKey<Level> dimension, String name, UUID by) {}

    public static Selection selection(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (tag.getLong("HubPos").isEmpty()) return null;
        Identifier dim = Identifier.tryParse(tag.getStringOr("HubDim", ""));
        if (dim == null) return null;
        UUID by = nl.smartphonemod.server.PhoneDatabase.uuid(tag.getStringOr("By", ""));
        return new Selection(BlockPos.of(tag.getLongOr("HubPos", 0)), ResourceKey.create(Registries.DIMENSION, dim),
                tag.getStringOr("HubName", ""), by);
    }

    public static void select(ItemStack stack, StorageHubBlockEntity hub, ServerPlayer by) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putLong("HubPos", hub.getBlockPos().asLong());
            tag.putString("HubDim", hub.getLevel().dimension().identifier().toString());
            tag.putString("HubName", hub.displayName());
            tag.putString("By", by.getUUID().toString());
            tag.remove("Corner");
            tag.remove("CornerDim");
        });
    }

    public static void clear(ItemStack stack) {
        stack.remove(DataComponents.CUSTOM_DATA);
    }

    /** First corner of an area selection, or null. */
    public static BlockPos corner(ItemStack stack, ResourceKey<Level> dimension) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (tag.getLong("Corner").isEmpty()) return null;
        if (!dimension.identifier().toString().equals(tag.getStringOr("CornerDim", ""))) return null;
        return BlockPos.of(tag.getLongOr("Corner", 0));
    }

    public static void setCorner(ItemStack stack, BlockPos pos, ResourceKey<Level> dimension) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (pos == null) {
                tag.remove("Corner");
                tag.remove("CornerDim");
            } else {
                tag.putLong("Corner", pos.asLong());
                tag.putString("CornerDim", dimension.identifier().toString());
            }
        });
    }

    /** Sneak + right-click in the air forgets the selected hub. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown() || selection(stack) == null) return InteractionResult.PASS;
        if (!level.isClientSide()) {
            clear(stack);
            player.sendOverlayMessage(Component.translatable("smartphone.linker.cleared").withStyle(ChatFormatting.YELLOW));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return selection(stack) != null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        Selection sel = selection(stack);
        if (sel == null) {
            builder.accept(Component.translatable("smartphone.linker.tip.none").withStyle(ChatFormatting.GRAY));
        } else {
            BlockPos p = sel.hub();
            builder.accept(Component.translatable("smartphone.linker.tip.hub", sel.name(), p.getX(), p.getY(), p.getZ())
                    .withStyle(ChatFormatting.AQUA));
        }
        builder.accept(Component.translatable("smartphone.linker.tip.1").withStyle(ChatFormatting.DARK_GRAY));
        builder.accept(Component.translatable("smartphone.linker.tip.2").withStyle(ChatFormatting.DARK_GRAY));
        builder.accept(Component.translatable("smartphone.linker.tip.3").withStyle(ChatFormatting.DARK_GRAY));
    }
}
