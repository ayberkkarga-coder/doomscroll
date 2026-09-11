package com.doomscroll;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.Item.TooltipContext;
import java.util.function.Consumer;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** Tablet: sag tik ile elde tarayici acar (client tarafi). Ekran blogundan bagimsiz kendi tarayicisi vardir. */
public class TabletItem extends Item {
	public TabletItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) {
			Doomscroll.tabletOpener.run();
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		out.accept(Component.translatable("item.doomscroll.tablet.tooltip").withStyle(ChatFormatting.GRAY));
		out.accept(Component.translatable("item.doomscroll.tablet.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
	}

	/** Sunucu: tablet kimin envanterindeyse sahibi o olsun (baskasinin tabletini cizerken ona bakilir). */
	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
		if (entity instanceof Player p) {
			UUID cur = stack.get(Doomscroll.TABLET_OWNER);
			if (!p.getUUID().equals(cur) && !Doomscroll.DEBUG_FAKE_OWNER.equals(cur)) {
				stack.set(Doomscroll.TABLET_OWNER, p.getUUID());
			}
		}
	}
}
