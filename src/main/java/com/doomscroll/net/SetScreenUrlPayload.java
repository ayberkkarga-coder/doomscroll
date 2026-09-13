package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: this screen's URL.
 * explicit=true: the player navigated deliberately (remote, tablet, address bar) -> takes control, the URL goes to everyone.
 * explicit=false: their browser changed on its own (automatic transition, redirect) -> only propagated if they hold control.
 */
public record SetScreenUrlPayload(BlockPos pos, String url, boolean explicit) implements CustomPacketPayload {
	public static final Type<SetScreenUrlPayload> TYPE = new Type<>(Doomscroll.id("set_screen_url"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, SetScreenUrlPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, SetScreenUrlPayload::pos,
			ByteBufCodecs.stringUtf8(2048), SetScreenUrlPayload::url,
			ByteBufCodecs.BOOL, SetScreenUrlPayload::explicit,
			SetScreenUrlPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
