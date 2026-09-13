package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client (controller) -> server: position of the video on the screen. Also serves as an "I'm still here" signal.
 * time < 0: no video on the page (signal only).
 */
public record ScreenTimePayload(BlockPos pos, float time, float duration, boolean paused) implements CustomPacketPayload {
	public static final Type<ScreenTimePayload> TYPE = new Type<>(Doomscroll.id("screen_time"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, ScreenTimePayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ScreenTimePayload::pos,
			ByteBufCodecs.FLOAT, ScreenTimePayload::time,
			ByteBufCodecs.FLOAT, ScreenTimePayload::duration,
			ByteBufCodecs.BOOL, ScreenTimePayload::paused,
			ScreenTimePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
