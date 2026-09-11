package com.doomscroll;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

/** Hoparlor blogunun esyasi: ne ise yaradigini ve nasil baglandigini soyler. */
public class SpeakerItem extends BlockItem {
	public SpeakerItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		super.appendHoverText(stack, context, display, out, flag);
		out.accept(Component.translatable("block.doomscroll.speaker.tooltip").withStyle(ChatFormatting.GRAY));
		out.accept(Component.translatable("block.doomscroll.speaker.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
	}
}
