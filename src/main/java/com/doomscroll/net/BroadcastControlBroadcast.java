package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sunucu -> yayinci istemci: yeni izleyici geldi, kaydediciyi yeniden baslat (anahtar kare + baslangic parcasi). */
public record BroadcastControlBroadcast(BlockPos pos, int action) implements CustomPacketPayload {
	public static final int RESTART = 0;

	public static final Type<BroadcastControlBroadcast> TYPE = new Type<>(Doomscroll.id("bc_control_bc"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, BroadcastControlBroadcast> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, BroadcastControlBroadcast::pos,
			ByteBufCodecs.VAR_INT, BroadcastControlBroadcast::action,
			BroadcastControlBroadcast::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
