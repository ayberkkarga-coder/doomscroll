package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Istemci (kontrolcu) -> sunucu: ekrandaki videonun konumu. Ayni zamanda "hala buradayim" sinyali.
 * time < 0: sayfada video yok (yalnizca sinyal).
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
