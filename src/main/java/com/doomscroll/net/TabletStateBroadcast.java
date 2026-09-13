package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Server -> client: this player's tablet is at this URL, portrait/landscape, at this device volume (0 = nobody hears it). */
public record TabletStateBroadcast(UUID player, String url, boolean portrait, float volume) implements CustomPacketPayload {
	public static final Type<TabletStateBroadcast> TYPE = new Type<>(Doomscroll.id("tablet_state_broadcast"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, TabletStateBroadcast> CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, TabletStateBroadcast::player,
			ByteBufCodecs.stringUtf8(2048), TabletStateBroadcast::url,
			ByteBufCodecs.BOOL, TabletStateBroadcast::portrait,
			ByteBufCodecs.FLOAT, TabletStateBroadcast::volume,
			TabletStateBroadcast::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
