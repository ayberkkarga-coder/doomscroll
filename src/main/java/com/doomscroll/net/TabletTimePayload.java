package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: position of the video on my own tablet (once per second).
 * Other players' clients open the same page themselves; this position lets them align to the same moment.
 * time &lt; 0: no video on the page.
 */
public record TabletTimePayload(float time, float duration, boolean paused) implements CustomPacketPayload {
	public static final Type<TabletTimePayload> TYPE = new Type<>(Doomscroll.id("tablet_time"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, TabletTimePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.FLOAT, TabletTimePayload::time,
			ByteBufCodecs.FLOAT, TabletTimePayload::duration,
			ByteBufCodecs.BOOL, TabletTimePayload::paused,
			TabletTimePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
