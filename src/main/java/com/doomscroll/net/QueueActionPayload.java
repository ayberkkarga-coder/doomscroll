package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Istemci -> sunucu: paylasilan video sirasinda bir islem.
 * Sira sunucuda durur; istemci yalnizca ister, kurali sunucu uygular.
 */
public record QueueActionPayload(BlockPos pos, int action, String url, String title) implements CustomPacketPayload {
	public static final int ADD = 0;
	public static final int VOTE = 1;
	public static final int REMOVE = 2;
	public static final int CLEAR = 3;
	/** Siradaki (en cok oy alan) videoyu ac. */
	public static final int NEXT = 4;
	/** Belirli bir videoyu hemen ac. */
	public static final int PLAY = 5;
	/** Sadece guncel listeyi iste (kumanda acilinca, sayfa yuklenince). */
	public static final int REFRESH = 6;

	public static final Type<QueueActionPayload> TYPE = new Type<>(Doomscroll.id("queue_action"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, QueueActionPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, QueueActionPayload::pos,
			ByteBufCodecs.VAR_INT, QueueActionPayload::action,
			ByteBufCodecs.stringUtf8(2048), QueueActionPayload::url,
			ByteBufCodecs.stringUtf8(256), QueueActionPayload::title,
			QueueActionPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
