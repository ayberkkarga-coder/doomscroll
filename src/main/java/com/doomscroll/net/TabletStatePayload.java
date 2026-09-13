package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: my tablet is at this URL, portrait/landscape, at this device volume (0..1, the level everyone hears). */
public record TabletStatePayload(String url, boolean portrait, float volume) implements CustomPacketPayload {
	public static final Type<TabletStatePayload> TYPE = new Type<>(Doomscroll.id("tablet_state"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, TabletStatePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(2048), TabletStatePayload::url,
			ByteBufCodecs.BOOL, TabletStatePayload::portrait,
			ByteBufCodecs.FLOAT, TabletStatePayload::volume,
			TabletStatePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
