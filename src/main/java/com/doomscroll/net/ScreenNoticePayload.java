package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client: a short notice about a screen.
 * code=DENIED: the request was refused (lock); the client resets the screen back to the server's URL.
 */
public record ScreenNoticePayload(BlockPos pos, int code, Component text) implements CustomPacketPayload {
	public static final int INFO = 0;
	public static final int DENIED = 1;
	/** The URL is blocked by server rules: the client goes back to the server's URL. */
	public static final int BLOCKED = 2;

	public static final Type<ScreenNoticePayload> TYPE = new Type<>(Doomscroll.id("screen_notice"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, ScreenNoticePayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ScreenNoticePayload::pos,
			ByteBufCodecs.VAR_INT, ScreenNoticePayload::code,
			ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC, ScreenNoticePayload::text,
			ScreenNoticePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
