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
					return reply(c, Component.translatable("command.doomscroll.admin.reloaded", summary()));
				}));
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
				root.then(Commands.literal(n).then(Commands.argument("seviye", IntegerArgumentType.integer(0, 15)).executes(c -> {
					ServerConfig.get().screenLightLevel = IntegerArgumentType.getInteger(c, "seviye");
					ServerConfig.save();
					return reply(c, Component.translatable("command.doomscroll.admin.light", ServerConfig.get().screenLightLevel));
				})));
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
				root.then(Commands.literal(n).then(Commands.argument("saniye", IntegerArgumentType.integer(5, 3600)).executes(c -> {
					ServerConfig.get().controlTimeoutSeconds = IntegerArgumentType.getInteger(c, "saniye");
					Doomscroll.CONTROL_TIMEOUT_MS = ServerConfig.get().controlTimeoutSeconds * 1000L;
					ServerConfig.save();
					return reply(c, Component.translatable("command.doomscroll.admin.control_time", ServerConfig.get().controlTimeoutSeconds));
				})));
			}
			dispatcher.register(root);
		});
	}

	// ---------- alt komut kaliplari ----------

	private static LiteralArgumentBuilder<CommandSourceStack> domain(String name, ToIntBiFunction<CommandContext<CommandSourceStack>, String> fn) {
		return Commands.literal(name).then(Commands.argument("alan", StringArgumentType.word())
				.executes(c -> fn.applyAsInt(c, clean(StringArgumentType.getString(c, "alan")))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> toggle(String name, Function<Boolean, Component> fn) {
		return Commands.literal(name).then(Commands.argument("durum", StringArgumentType.word()).executes(c -> {
			Component msg = fn.apply(on(StringArgumentType.getString(c, "durum")));
			ServerConfig.save();
			return reply(c, msg);
		}));
	}

	// ---------- eylemler ----------

	private static int block(CommandContext<CommandSourceStack> c, String d) {
		if (d.isEmpty()) return fail(c, Component.translatable("command.doomscroll.admin.bad_domain"));
		List<String> l = ServerConfig.get().blockedDomains;
		if (!l.contains(d)) l.add(d);
		ServerConfig.save();
		return reply(c, Component.translatable("command.doomscroll.admin.blocked", d));
	}

	private static int unblock(CommandContext<CommandSourceStack> c, String d) {
		boolean ok = ServerConfig.get().blockedDomains.remove(d);
		ServerConfig.save();
		return ok ? reply(c, Component.translatable("command.doomscroll.admin.unblocked", d))
				: fail(c, Component.translatable("command.doomscroll.admin.not_in_list", d));
	}

	private static int allow(CommandContext<CommandSourceStack> c, String d) {
		if (d.isEmpty()) return fail(c, Component.translatable("command.doomscroll.admin.bad_domain"));
		List<String> l = ServerConfig.get().allowedDomains;
		if (!l.contains(d)) l.add(d);
		ServerConfig.save();
		return reply(c, Component.translatable("command.doomscroll.admin.allowed", d));
	}

	private static int unallow(CommandContext<CommandSourceStack> c, String d) {
		boolean ok = ServerConfig.get().allowedDomains.remove(d);
		ServerConfig.save();
		return ok ? reply(c, Component.translatable("command.doomscroll.admin.unallowed", d))
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
				onOff(c.redstoneControl), onOff(c.pointer), c.controlTimeoutSeconds);
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
