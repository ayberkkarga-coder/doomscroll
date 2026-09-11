package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Istemci -> sunucu: "bu ekrani bildiriyorum". Sunucu ekranin sahibini ve adresini
 * kendisi okur (istemcinin yolladigi metne guvenmez), denetim kaydina yazar ve
 * cevrimici yoneticilere bildirir.
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
