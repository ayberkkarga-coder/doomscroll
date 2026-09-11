package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Yayinci istemci -> sunucu: WebM parcasinin bir dilimi (sunucuya giden paket siniri 32 KB). */
public record BroadcastChunkPayload(BlockPos pos, int seq, int piece, int pieces, boolean init, byte[] data) implements CustomPacketPayload {
	public static final int MAX_PIECE = 30000;
	public static final Type<BroadcastChunkPayload> TYPE = new Type<>(Doomscroll.id("bc_chunk"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, BroadcastChunkPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, BroadcastChunkPayload::pos,
			ByteBufCodecs.VAR_INT, BroadcastChunkPayload::seq,
			ByteBufCodecs.VAR_INT, BroadcastChunkPayload::piece,
			ByteBufCodecs.VAR_INT, BroadcastChunkPayload::pieces,
			ByteBufCodecs.BOOL, BroadcastChunkPayload::init,
			ByteBufCodecs.byteArray(MAX_PIECE + 64), BroadcastChunkPayload::data,
			BroadcastChunkPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
