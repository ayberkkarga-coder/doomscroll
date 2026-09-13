package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Server -> client: a screen's queued videos, in playback order.
 * Since {@code mine} depends on the recipient, the packet is built separately for each player.
 */
public record QueueBroadcast(BlockPos pos, List<Row> rows) implements CustomPacketPayload {

	/** One row: URL, title, who added it, vote count, whether I voted. */
	public record Row(String url, String title, String by, int votes, boolean mine) {
		public static final StreamCodec<io.netty.buffer.ByteBuf, Row> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(2048), Row::url,
				ByteBufCodecs.stringUtf8(256), Row::title,
				ByteBufCodecs.stringUtf8(64), Row::by,
				ByteBufCodecs.VAR_INT, Row::votes,
				ByteBufCodecs.BOOL, Row::mine,
				Row::new
		);
	}

	public static final Type<QueueBroadcast> TYPE = new Type<>(Doomscroll.id("queue_state"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, QueueBroadcast> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, QueueBroadcast::pos,
			Row.CODEC.apply(ByteBufCodecs.list(64)), QueueBroadcast::rows,
			QueueBroadcast::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
