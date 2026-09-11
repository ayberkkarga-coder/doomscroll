package com.doomscroll;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * Kumanda.
 * - Ekrana sag tik: kumanda o ekrana baglanir (REMOTE_TARGET bileseni, sunucu tarafinda).
 * - Havaya sag tik: yonetim paneli (client). Bagli ekran varsa onu yonetir.
 * - Egilerek (shift) sag tik: baglantiyi keser.
 */
public class RemoteItem extends Item {
	public RemoteItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext ctx) {
		Level level = ctx.getLevel();
		BlockPos pos = ctx.getClickedPos();
		// Hoparlore sag tik: kumandanin bagli oldugu ekrana baglar
		if (level.getBlockEntity(pos) instanceof SpeakerBlockEntity sp) {
			if (!level.isClientSide()) {
				Player p = ctx.getPlayer();
				BlockPos target = ctx.getItemInHand().get(Doomscroll.REMOTE_TARGET);
				if (p != null && p.isShiftKeyDown()) {
					sp.setScreen(null);
					p.sendOverlayMessage(Component.translatable("message.doomscroll.speaker.unbound"));
				} else if (target == null) {
					if (p != null) {
						p.sendOverlayMessage(Component.translatable("message.doomscroll.speaker.need_remote"));
					}
				} else {
					sp.setScreen(target);
					if (p != null) {
						p.sendOverlayMessage(Component.translatable("message.doomscroll.speaker.bound",
								target.getX(), target.getY(), target.getZ()));
					}
				}
			}
			return InteractionResult.SUCCESS;
		}
		if (!(level.getBlockEntity(pos) instanceof ScreenBlockEntity be)) {
			return InteractionResult.PASS; // ekran degil: normal davranis (use -> panel)
		}
		if (!level.isClientSide()) {
			BlockPos anchor = be.getAnchor();
			ItemStack stack = ctx.getItemInHand();
			stack.set(Doomscroll.REMOTE_TARGET, anchor);
			Player p = ctx.getPlayer();
			if (p != null) {
				p.sendOverlayMessage(be.getOwnerName().isEmpty()
						? Component.translatable("message.doomscroll.remote.bound", anchor.getX(), anchor.getY(), anchor.getZ())
						: Component.translatable("message.doomscroll.remote.bound_owner", anchor.getX(), anchor.getY(), anchor.getZ(), be.getOwnerName()));
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (player.isShiftKeyDown() && stack.has(Doomscroll.REMOTE_TARGET)) {
			if (!level.isClientSide()) {
				stack.remove(Doomscroll.REMOTE_TARGET);
				player.sendOverlayMessage(Component.translatable("message.doomscroll.remote.unbound"));
			}
			return InteractionResult.SUCCESS;
		}
		if (level.isClientSide()) {
			Doomscroll.remoteOpener.run();
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		BlockPos p = stack.get(Doomscroll.REMOTE_TARGET);
		if (p == null) {
			out.accept(Component.translatable("item.doomscroll.remote.tooltip.unbound").withStyle(ChatFormatting.GRAY));
			out.accept(Component.translatable("item.doomscroll.remote.tooltip.unbound_hint").withStyle(ChatFormatting.DARK_GRAY));
		} else {
			out.accept(Component.translatable("item.doomscroll.remote.tooltip.bound", p.getX(), p.getY(), p.getZ()).withStyle(ChatFormatting.AQUA));
			out.accept(Component.translatable("item.doomscroll.remote.tooltip.unbind_hint").withStyle(ChatFormatting.DARK_GRAY));
		}
	}
}
