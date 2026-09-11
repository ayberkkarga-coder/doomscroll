package com.doomscroll;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Hoparlor blogu: bagli oldugu ekranin sesini kendi konumundan duyurur.
 *
 * <p>Baglama: kumandayi bir ekrana bagla, sonra hoparlore sag tikla. Egilerek sag tik bagi keser.
 * Elin bosken sag tiklayinca neye bagli oldugunu soyler.
 */
public class SpeakerBlock extends BaseEntityBlock {
	public static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);

	public SpeakerBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SpeakerBlockEntity(pos, state);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof SpeakerBlockEntity be)) {
			return InteractionResult.SUCCESS;
		}
		if (player.isShiftKeyDown()) {
			be.setScreen(null);
			player.sendOverlayMessage(Component.translatable("message.doomscroll.speaker.unbound"));
			return InteractionResult.SUCCESS;
		}
		BlockPos screen = be.getScreen();
		player.sendOverlayMessage(screen == null
				? Component.translatable("message.doomscroll.speaker.unbound_hint")
				: Component.translatable("message.doomscroll.speaker.bound",
						screen.getX(), screen.getY(), screen.getZ()));
		return InteractionResult.SUCCESS;
	}
}
