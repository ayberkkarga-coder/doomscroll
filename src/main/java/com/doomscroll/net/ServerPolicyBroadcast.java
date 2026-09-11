package com.doomscroll.net;

import com.doomscroll.Doomscroll;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Sunucu -> istemci (girise ve ayar degisikligine): adres politikasi.
 *
 * <p>Sunucu zaten paylasilan adresi denetliyor, ama yonlendirmeler once istemcide olur:
 * kisaltilmis bir adres engelli bir siteye giderse sayfa istemcide bir an yuklenir.
 * Politikayi istemciye de verince her tarayici her adres degisikliginde ayni kurali
 * yerinde uygular ve sayfa hic acilmaz. Degistirilmis istemci bunu yok sayabilir;
 * sunucu tarafindaki denetim yine de gecerlidir.
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
