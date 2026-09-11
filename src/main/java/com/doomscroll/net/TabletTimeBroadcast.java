package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Sunucu -> istemci: su oyuncunun tabletindeki videonun konumu (izleyenler bu ana hizalanir). */
public record TabletTimeBroadcast(UUID player, float time, float duration, boolean paused) implements CustomPacketPayload {
	public static final Type<TabletTimeBroadcast> TYPE = new Type<>(Doomscroll.id("tablet_time_broadcast"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, TabletTimeBroadcast> CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, TabletTimeBroadcast::player,
			ByteBufCodecs.FLOAT, TabletTimeBroadcast::time,
			ByteBufCodecs.FLOAT, TabletTimeBroadcast::duration,
			ByteBufCodecs.BOOL, TabletTimeBroadcast::paused,
			TabletTimeBroadcast::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
