package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Server -> nearby clients: a player's pointer on the screen (panel fraction 0..1; u/v < 0 = gone). */
public record ScreenPointerBroadcast(BlockPos pos, UUID player, String name, float u, float v) implements CustomPacketPayload {
	public static final Type<ScreenPointerBroadcast> TYPE = new Type<>(Doomscroll.id("screen_pointer_bc"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, ScreenPointerBroadcast> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ScreenPointerBroadcast::pos,
			UUIDUtil.STREAM_CODEC, ScreenPointerBroadcast::player,
			ByteBufCodecs.stringUtf8(64), ScreenPointerBroadcast::name,
			ByteBufCodecs.FLOAT, ScreenPointerBroadcast::u,
			ByteBufCodecs.FLOAT, ScreenPointerBroadcast::v,
			ScreenPointerBroadcast::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
