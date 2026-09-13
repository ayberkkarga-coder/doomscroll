package com.doomscroll;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

/** Item form of the screen block: placement hints (merging, sneak for floor/ceiling). */
public class ScreenItem extends BlockItem {
	public ScreenItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		super.appendHoverText(stack, context, display, out, flag);
		out.accept(Component.translatable("item.doomscroll.screen.tooltip.merge").withStyle(ChatFormatting.GRAY));
		out.accept(Component.translatable("item.doomscroll.screen.tooltip.floor").withStyle(ChatFormatting.DARK_GRAY));
	}
}
