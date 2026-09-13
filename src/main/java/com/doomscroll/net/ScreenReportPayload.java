package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: "I'm reporting this screen". The server reads the screen's owner and URL
 * itself (it does not trust the text sent by the client), writes to the audit log and
 * notifies the online admins.
 */
public record ScreenReportPayload(BlockPos pos, String note) implements CustomPacketPayload {
	public static final Type<ScreenReportPayload> TYPE = new Type<>(Doomscroll.id("screen_report"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, ScreenReportPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ScreenReportPayload::pos,
			ByteBufCodecs.stringUtf8(256), ScreenReportPayload::note,
			ScreenReportPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
