package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Istemci -> sunucu: su ekrani ac/kapat. */
public record SetScreenPowerPayload(BlockPos pos, boolean on) implements CustomPacketPayload {
	public static final Type<SetScreenPowerPayload> TYPE = new Type<>(Doomscroll.id("set_screen_power"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, SetScreenPowerPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, SetScreenPowerPayload::pos,
			ByteBufCodecs.BOOL, SetScreenPowerPayload::on,
			SetScreenPowerPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
