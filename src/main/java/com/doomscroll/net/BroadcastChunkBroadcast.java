package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sunucu -> izleyici istemciler: yayin parcasinin bir dilimi. */
public record BroadcastChunkBroadcast(BlockPos pos, int seq, int piece, int pieces, boolean init, byte[] data) implements CustomPacketPayload {
	public static final Type<BroadcastChunkBroadcast> TYPE = new Type<>(Doomscroll.id("bc_chunk_bc"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, BroadcastChunkBroadcast> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, BroadcastChunkBroadcast::pos,
			ByteBufCodecs.VAR_INT, BroadcastChunkBroadcast::seq,
			ByteBufCodecs.VAR_INT, BroadcastChunkBroadcast::piece,
			ByteBufCodecs.VAR_INT, BroadcastChunkBroadcast::pieces,
			ByteBufCodecs.BOOL, BroadcastChunkBroadcast::init,
			ByteBufCodecs.byteArray(BroadcastChunkPayload.MAX_PIECE + 64), BroadcastChunkBroadcast::data,
			BroadcastChunkBroadcast::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
