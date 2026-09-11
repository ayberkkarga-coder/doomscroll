package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Istemci -> sunucu: kendi tabletimdeki videonun konumu (saniyede bir).
 * Baskalarinin istemcisi ayni sayfayi kendi acar; bu konum sayesinde ayni ana hizalanir.
 * time &lt; 0: sayfada video yok.
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
