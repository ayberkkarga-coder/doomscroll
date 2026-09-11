package com.doomscroll;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

/**
 * Sunucu yonetici komutlari (/doomscroll ...): config/doomscroll-server.json'u oyun icinden duzenler.
 * Yalnizca oyun yoneticileri (gamemaster ve ustu).
 *
 * Her alt komut hem Turkce hem Ingilizce adla kayitlidir (yenile / reload gibi); mesajlar dil dosyasindan
 * cevrilebilir Component olarak gider, yani her yoneticinin istemcisi kendi diliyle gosterir.
 *
 * Ayar degisen her komut politikayi istemcilere yeniden yollar: beyaz liste degisikligi icin
 * kimsenin yeniden girmesi gerekmez.
 */
public final class AdminCommands {
	private AdminCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
			LiteralArgumentBuilder<CommandSourceStack> root =
					Commands.literal("doomscroll").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
							.executes(c -> reply(c, summary()));

			for (String n : new String[]{"yenile", "reload"}) {
				root.then(Commands.literal(n).executes(c -> {
					ServerConfig.load();
					Doomscroll.CONTROL_TIMEOUT_MS = ServerConfig.get().controlTimeoutSeconds * 1000L;
					Doomscroll.broadcastPolicy(c.getSource().getServer());
					return reply(c, Component.translatable("command.doomscroll.admin.reloaded", summary()));
				}));
			}
			for (String n : new String[]{"yardim", "help"}) {
				root.then(Commands.literal(n).executes(AdminCommands::help));
			}
			for (String n : new String[]{"liste", "list"}) {
				root.then(Commands.literal(n).executes(c -> reply(c, summary())));
			}
			for (String n : new String[]{"engelle", "block"}) {
				root.then(domain(n, AdminCommands::block));
			}
			for (String n : new String[]{"engelkaldir", "unblock"}) {
				root.then(domain(n, AdminCommands::unblock));
			}
			for (String n : new String[]{"izin", "allow"}) {
				root.then(domain(n, AdminCommands::allow));
			}
			for (String n : new String[]{"izinkaldir", "unallow"}) {
				root.then(domain(n, AdminCommands::unallow));
			}
			for (String n : new String[]{"isik", "light"}) {
				root.then(number(n, "seviye", 0, 15, (c, v) -> {
					ServerConfig.get().screenLightLevel = v;
					return Component.translatable("command.doomscroll.admin.light", v);
				}));
			}
			for (String n : new String[]{"duyuru", "announce"}) {
				root.then(toggle(n, v -> {
					ServerConfig.get().announce = v;
					return Component.translatable("command.doomscroll.admin.announce", onOff(v));
				}));
			}
			for (String n : new String[]{"isaretci", "pointer"}) {
				root.then(toggle(n, v -> {
					ServerConfig.get().pointer = v;
					return Component.translatable("command.doomscroll.admin.pointer", onOff(v));
				}));
			}
			root.then(toggle("redstone", v -> {
				ServerConfig.get().redstoneControl = v;
				return Component.translatable("command.doomscroll.admin.redstone", onOff(v));
			}));
			for (String n : new String[]{"kontrolsuresi", "controltime"}) {
				root.then(number(n, "saniye", 5, 3600, (c, v) -> {
					ServerConfig.get().controlTimeoutSeconds = v;
					Doomscroll.CONTROL_TIMEOUT_MS = v * 1000L;
					return Component.translatable("command.doomscroll.admin.control_time", v);
				}));
			}

			// ---------- denetim ve guvenlik ----------

			for (String n : new String[]{"kayit", "audit"}) {
				root.then(Commands.literal(n)
						.executes(c -> audit(c, 15))
						.then(Commands.argument("adet", IntegerArgumentType.integer(1, 200))
								.executes(c -> audit(c, IntegerArgumentType.getInteger(c, "adet")))));
			}
			for (String n : new String[]{"denetim", "auditlog"}) {
				root.then(toggle(n, v -> {
					ServerConfig.get().auditLog = v;
					return Component.translatable("command.doomscroll.admin.audit_log", onOff(v));
				}));
			}
			for (String n : new String[]{"acil", "emergency"}) {
				root.then(toggle(n, v -> {
					ServerConfig.get().lockdown = v;
					int off = v ? Doomscroll.blackout() : 0;
					return v
							? Component.translatable("command.doomscroll.admin.lockdown_on", off)
							: Component.translatable("command.doomscroll.admin.lockdown_off");
				}));
			}
			for (String n : new String[]{"karart", "blackout"}) {
				root.then(Commands.literal(n).executes(c -> {
					int off = Doomscroll.blackout();
					AuditLog.record(c.getSource().getPlayer(), AuditLog.ADMIN, "blackout " + off);
					return reply(c, Component.translatable("command.doomscroll.admin.blackout", off));
				}));
			}
			for (String n : new String[]{"ozelag", "privatenet"}) {
				root.then(toggle(n, v -> {
					ServerConfig.get().allowPrivateNetwork = v;
					return Component.translatable("command.doomscroll.admin.private_net", onOff(v));
				}));
			}
			for (String n : new String[]{"onay", "consent"}) {
				root.then(toggle(n, v -> {
					ServerConfig.get().requireConsent = v;
					return Component.translatable("command.doomscroll.admin.consent", onOff(v));
				}));
			}
			for (String n : new String[]{"sessiz", "muteothers"}) {
				root.then(toggle(n, v -> {
					ServerConfig.get().muteOthersByDefault = v;
					return Component.translatable("command.doomscroll.admin.mute_others", onOff(v));
				}));
			}
			for (String n : new String[]{"cerez", "cookies"}) {
				root.then(toggle(n, v -> {
					ServerConfig.get().separateScreenCookies = v;
					return Component.translatable("command.doomscroll.admin.cookies", onOff(v));
				}));
			}
			for (String n : new String[]{"alanadi", "showdomain"}) {
				root.then(toggle(n, v -> {
					ServerConfig.get().showDomain = v;
					return Component.translatable("command.doomscroll.admin.show_domain", onOff(v));
				}));
			}
			for (String n : new String[]{"panelsinir", "maxpanel"}) {
				root.then(number(n, "blok", 0, 4096, (c, v) -> {
					ServerConfig.get().maxPanelBlocks = v;
					return Component.translatable("command.doomscroll.admin.max_panel", label(v));
				}));
			}
			for (String n : new String[]{"ekransinir", "maxscreens"}) {
				root.then(number(n, "blok", 0, 4096, (c, v) -> {
					ServerConfig.get().maxScreensPerPlayer = v;
					return Component.translatable("command.doomscroll.admin.max_screens", label(v));
				}));
			}
			for (String n : new String[]{"bekleme", "cooldown"}) {
				root.then(number(n, "ms", 0, 60000, (c, v) -> {
					ServerConfig.get().urlCooldownMs = v;
					return Component.translatable("command.doomscroll.admin.cooldown", v);
				}));
			}
			for (String n : new String[]{"yayin", "broadcast"}) {
				root.then(toggle(n, v -> {
					ServerConfig.get().broadcast = v;
					return Component.translatable("command.doomscroll.admin.broadcast", onOff(v));
				}));
			}
			dispatcher.register(root);
		});
	}

	/**
	 * /doomscroll yardim: her ayarin ne yaptigini ve su anki degerini tek satirda gosterir.
	 * Ozet (/doomscroll) yalnizca degerleri yazar; burasi "bu ne ise yariyor" sorusunun yeri.
	 */
	private static int help(CommandContext<CommandSourceStack> c) {
		ServerConfig s = ServerConfig.get();
		CommandSourceStack src = c.getSource();
		src.sendSuccess(() -> Component.translatable("command.doomscroll.admin.help.title"), false);

		head(src, "safety");
		line(src, "lockdown", onOff(s.lockdown));
		line(src, "blackout", null);
		line(src, "block", count(s.blockedDomains.size()));
		line(src, "allow", count(s.allowedDomains.size()));
		line(src, "private_net", onOff(s.allowPrivateNetwork));
		line(src, "consent", onOff(s.requireConsent));
		line(src, "cookies", onOff(s.separateScreenCookies));
		line(src, "show_domain", onOff(s.showDomain));

		head(src, "audit");
		line(src, "audit", null);
		line(src, "audit_log", onOff(s.auditLog));

		head(src, "limits");
		line(src, "max_panel", label(s.maxPanelBlocks));
		line(src, "max_screens", label(s.maxScreensPerPlayer));
		line(src, "cooldown", Component.literal(s.urlCooldownMs + " ms"));
		line(src, "control_time", Component.literal(s.controlTimeoutSeconds + " s"));

		head(src, "features");
		line(src, "broadcast", onOff(s.broadcast));
		line(src, "pointer", onOff(s.pointer));
		line(src, "redstone", onOff(s.redstoneControl));
		line(src, "announce", onOff(s.announce));
		line(src, "light", Component.literal(String.valueOf(s.screenLightLevel)));
		line(src, "reload", null);

		src.sendSuccess(() -> Component.translatable("command.doomscroll.admin.help.foot",
				AuditLog.file().toString()), false);
		src.sendSuccess(() -> Component.translatable("command.doomscroll.admin.help.perms"), false);
		return 1;
	}

	private static void head(CommandSourceStack src, String key) {
		src.sendSuccess(() -> Component.translatable("command.doomscroll.admin.help.head." + key), false);
	}

	/** "  /doomscroll <komut>  aciklama  -> deger" satiri. */
	private static void line(CommandSourceStack src, String key, @org.jetbrains.annotations.Nullable Component value) {
		// Komut adi da dil dosyasindan gelir: Ingilizce oynayan yonetici Ingilizce adi gorur.
		String cmd = Component.translatable("command.doomscroll.admin.help.cmd." + key).getString();
		net.minecraft.network.chat.MutableComponent m = Component.literal("  \u00a7e/doomscroll " + cmd + "\u00a7r  ")
				.append(Component.translatable("command.doomscroll.admin.help." + key));
		if (value != null) {
			m = m.append(Component.literal("  \u00a78\u2192 \u00a7f")).append(value);
		}
		final Component out = m;
		src.sendSuccess(() -> out, false);
	}

	private static Component count(int n) {
		return Component.translatable("command.doomscroll.admin.help.count", n);
	}

	// ---------- alt komut kaliplari ----------

	private static LiteralArgumentBuilder<CommandSourceStack> domain(String name, ToIntBiFunction<CommandContext<CommandSourceStack>, String> fn) {
		return Commands.literal(name).then(Commands.argument("alan", StringArgumentType.word())
				.executes(c -> fn.applyAsInt(c, clean(StringArgumentType.getString(c, "alan")))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> toggle(String name, Function<Boolean, Component> fn) {
		return Commands.literal(name).then(Commands.argument("durum", StringArgumentType.word()).executes(c -> {
			Component msg = fn.apply(on(StringArgumentType.getString(c, "durum")));
			return apply(c, msg);
		}));
	}

	private interface IntSetting {
		Component apply(CommandContext<CommandSourceStack> c, int value);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> number(String name, String arg, int min, int max, IntSetting fn) {
		return Commands.literal(name).then(Commands.argument(arg, IntegerArgumentType.integer(min, max)).executes(c -> {
			Component msg = fn.apply(c, IntegerArgumentType.getInteger(c, arg));
			return apply(c, msg);
		}));
	}

	/** Ayari kaydeder, politikayi istemcilere yollar, denetim kaydina yazar ve yaniti gonderir. */
	private static int apply(CommandContext<CommandSourceStack> c, Component msg) {
		ServerConfig.save();
		Doomscroll.broadcastPolicy(c.getSource().getServer());
		AuditLog.record(c.getSource().getPlayer(), AuditLog.ADMIN, msg.getString());
		return reply(c, msg);
	}

	// ---------- eylemler ----------

	private static int audit(CommandContext<CommandSourceStack> c, int n) {
		List<AuditLog.Entry> rows = AuditLog.recent(n);
		if (rows.isEmpty()) {
			return reply(c, Component.translatable("command.doomscroll.admin.audit_empty"));
		}
		c.getSource().sendSuccess(() -> Component.translatable("command.doomscroll.admin.audit_head",
				rows.size(), AuditLog.file().toString()), false);
		for (AuditLog.Entry e : rows) {
			// Kayit satirlari oyuncu metni icerir: asla bicim dizesi olarak kullanma.
			c.getSource().sendSuccess(() -> Component.literal("§7" + e.line()), false);
		}
		return rows.size();
	}

	private static int block(CommandContext<CommandSourceStack> c, String d) {
		if (d.isEmpty()) return fail(c, Component.translatable("command.doomscroll.admin.bad_domain"));
		List<String> l = ServerConfig.get().blockedDomains;
		if (!l.contains(d)) l.add(d);
		return apply(c, Component.translatable("command.doomscroll.admin.blocked", d));
	}

	private static int unblock(CommandContext<CommandSourceStack> c, String d) {
		boolean ok = ServerConfig.get().blockedDomains.remove(d);
		return ok ? apply(c, Component.translatable("command.doomscroll.admin.unblocked", d))
				: fail(c, Component.translatable("command.doomscroll.admin.not_in_list", d));
	}

	private static int allow(CommandContext<CommandSourceStack> c, String d) {
		if (d.isEmpty()) return fail(c, Component.translatable("command.doomscroll.admin.bad_domain"));
		List<String> l = ServerConfig.get().allowedDomains;
		if (!l.contains(d)) l.add(d);
		return apply(c, Component.translatable("command.doomscroll.admin.allowed", d));
	}

	private static int unallow(CommandContext<CommandSourceStack> c, String d) {
		boolean ok = ServerConfig.get().allowedDomains.remove(d);
		return ok ? apply(c, Component.translatable("command.doomscroll.admin.unallowed", d))
				: fail(c, Component.translatable("command.doomscroll.admin.not_in_list", d));
	}

	// ---------- yardimcilar ----------

	/** "ac/on/true/1" ve Turkce karsiliklari acik sayilir. */
	private static boolean on(String s) {
		String v = s.toLowerCase(Locale.ROOT);
		return v.equals("ac") || v.equals("aç") || v.equals("on") || v.equals("true") || v.equals("1")
				|| v.equals("acik") || v.equals("açık");
	}

	private static Component onOff(boolean v) {
		return Component.translatable(v ? "gui.doomscroll.enabled" : "gui.doomscroll.disabled");
	}

	/** 0 = sinirsiz. */
	private static Component label(int v) {
		return v <= 0 ? Component.translatable("command.doomscroll.admin.unlimited") : Component.literal(String.valueOf(v));
	}

	private static String clean(String d) {
		String s = d.trim().toLowerCase(Locale.ROOT);
		if (s.startsWith("http://")) s = s.substring(7);
		if (s.startsWith("https://")) s = s.substring(8);
		int slash = s.indexOf('/');
		if (slash >= 0) s = s.substring(0, slash);
		if (s.startsWith("www.")) s = s.substring(4);
		return s.matches("[a-z0-9.-]+") ? s : "";
	}

	private static Component summary() {
		ServerConfig c = ServerConfig.get();
		Component blocked = c.blockedDomains.isEmpty() ? Component.literal("-") : Component.literal(String.join(", ", c.blockedDomains));
		Component allowed = c.allowedDomains.isEmpty()
				? Component.translatable("command.doomscroll.admin.all")
				: Component.literal(String.join(", ", c.allowedDomains));
		return Component.translatable("command.doomscroll.admin.summary",
				blocked, allowed, c.screenLightLevel, onOff(c.announce), c.announceRange,
				onOff(c.redstoneControl), onOff(c.pointer), c.controlTimeoutSeconds)
				.copy().append(Component.translatable("command.doomscroll.admin.summary2",
						onOff(c.lockdown), onOff(c.auditLog), onOff(c.allowPrivateNetwork), onOff(c.requireConsent),
						onOff(c.muteOthersByDefault), onOff(c.showDomain), onOff(c.separateScreenCookies),
						label(c.maxPanelBlocks), label(c.maxScreensPerPlayer), c.urlCooldownMs));
	}

	private static int reply(CommandContext<CommandSourceStack> c, Component text) {
		c.getSource().sendSuccess(() -> text, true);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> c, Component text) {
		c.getSource().sendFailure(text);
		return 0;
	}
}
