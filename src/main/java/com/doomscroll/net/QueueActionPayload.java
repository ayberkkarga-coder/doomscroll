package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: an action on the shared video queue.
 * The queue lives on the server; the client only requests, the server enforces the rules.
 */
public record QueueActionPayload(BlockPos pos, int action, String url, String title) implements CustomPacketPayload {
	public static final int ADD = 0;
	public static final int VOTE = 1;
	public static final int REMOVE = 2;
	public static final int CLEAR = 3;
	/** Open the next video (the one with the most votes). */
	public static final int NEXT = 4;
	/** Open a specific video right away. */
	public static final int PLAY = 5;
	/** Only request the current list (when the remote opens, when the page loads). */
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
