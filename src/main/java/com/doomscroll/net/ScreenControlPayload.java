package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Istemci -> sunucu: ekran kontrolu al / birak / kilidi degistir. */
public record ScreenControlPayload(BlockPos pos, int action) implements CustomPacketPayload {
	public static final int TAKE = 0;
	public static final int RELEASE = 1;
	public static final int TOGGLE_LOCK = 2;
	/** Ekranin redstone ile acilip kapanmasini ac/kapat (sahibi/yonetici). */
	public static final int TOGGLE_REDSTONE = 3;
	/** Ekranin ortak sesini sirala: kapali -> kisik -> orta -> tam (kontrolu elinde tutan). */
	public static final int CYCLE_VOLUME = 4;

	public static final Type<ScreenControlPayload> TYPE = new Type<>(Doomscroll.id("screen_control"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, ScreenControlPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ScreenControlPayload::pos,
			ByteBufCodecs.VAR_INT, ScreenControlPayload::action,
			ScreenControlPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
