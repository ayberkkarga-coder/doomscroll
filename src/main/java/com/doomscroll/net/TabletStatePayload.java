package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Istemci -> sunucu: benim tabletim su adreste, dik/yatay, cihaz sesi kac (0..1, herkesin duyacagi seviye). */
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
