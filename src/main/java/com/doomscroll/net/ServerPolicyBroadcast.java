package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Server -> client (on login and on config change): the URL policy.
 *
 * <p>The server already checks the shared URL, but redirects happen on the client first:
 * if a shortened URL leads to a blocked site, the page loads on the client for a moment.
 * Handing the policy to the client as well lets every browser apply the same rule in place
 * on every URL change, so the page never opens. A modified client may ignore this;
 * the server-side check still applies.
 */
public record ServerPolicyBroadcast(List<String> blocked, List<String> allowed, int flags, int urlCooldownMs)
		implements CustomPacketPayload {

	public static final int LOCKDOWN = 1;
	public static final int ALLOW_PRIVATE = 1 << 1;
	public static final int REQUIRE_CONSENT = 1 << 2;
	public static final int MUTE_OTHERS = 1 << 3;
	public static final int SHOW_DOMAIN = 1 << 4;
	public static final int SEPARATE_COOKIES = 1 << 5;

	private static final int MAX_DOMAINS = 512;

	public static final Type<ServerPolicyBroadcast> TYPE = new Type<>(Doomscroll.id("server_policy"));
	public static final StreamCodec<io.netty.buffer.ByteBuf, ServerPolicyBroadcast> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_DOMAINS)), ServerPolicyBroadcast::blocked,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_DOMAINS)), ServerPolicyBroadcast::allowed,
			ByteBufCodecs.VAR_INT, ServerPolicyBroadcast::flags,
			ByteBufCodecs.VAR_INT, ServerPolicyBroadcast::urlCooldownMs,
			ServerPolicyBroadcast::new
	);

	public boolean has(int flag) {
		return (flags & flag) != 0;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
