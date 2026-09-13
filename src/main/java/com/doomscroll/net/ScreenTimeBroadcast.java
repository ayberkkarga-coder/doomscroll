package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> viewers: the controller's video is at this position (viewers correct their drift). */
public record ScreenTimeBroadcast(BlockPos pos, float time, float duration, boolean paused) implements CustomPacketPayload {
	public static final Type<ScreenTimeBroadcast> TYPE = new Type<>(Doomscroll.id("screen_time_broadcast"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, ScreenTimeBroadcast> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ScreenTimeBroadcast::pos,
			ByteBufCodecs.FLOAT, ScreenTimeBroadcast::time,
			ByteBufCodecs.FLOAT, ScreenTimeBroadcast::duration,
			ByteBufCodecs.BOOL, ScreenTimeBroadcast::paused,
			ScreenTimeBroadcast::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
