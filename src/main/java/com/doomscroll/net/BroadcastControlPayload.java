package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Istemci -> sunucu: yayin baslat/durdur, yayina abone ol/ayril. */
public record BroadcastControlPayload(BlockPos pos, int action) implements CustomPacketPayload {
	public static final int START = 0;
	public static final int STOP = 1;
	public static final int SUBSCRIBE = 2;
	public static final int UNSUBSCRIBE = 3;

	public static final Type<BroadcastControlPayload> TYPE = new Type<>(Doomscroll.id("bc_control"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, BroadcastControlPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, BroadcastControlPayload::pos,
			ByteBufCodecs.VAR_INT, BroadcastControlPayload::action,
			BroadcastControlPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
