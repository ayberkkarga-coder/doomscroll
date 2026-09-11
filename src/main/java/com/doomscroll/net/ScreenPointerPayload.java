package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Istemci -> sunucu: ekrana bakan oyuncunun imlec konumu (panel orani 0..1). u/v < 0 = imlec ekrandan cikti. */
public record ScreenPointerPayload(BlockPos pos, float u, float v) implements CustomPacketPayload {
	public static final Type<ScreenPointerPayload> TYPE = new Type<>(Doomscroll.id("screen_pointer"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, ScreenPointerPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ScreenPointerPayload::pos,
			ByteBufCodecs.FLOAT, ScreenPointerPayload::u,
			ByteBufCodecs.FLOAT, ScreenPointerPayload::v,
			ScreenPointerPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
