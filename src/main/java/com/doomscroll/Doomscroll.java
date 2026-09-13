package com.doomscroll;

import com.doomscroll.net.ScreenControlPayload;
import com.doomscroll.net.ScreenNoticePayload;
import com.doomscroll.net.ScreenTimeBroadcast;
import com.doomscroll.net.ScreenTimePayload;
import com.doomscroll.net.SetScreenPowerPayload;
import com.doomscroll.net.SetScreenUrlPayload;
import com.doomscroll.net.TabletStateBroadcast;
import com.doomscroll.net.TabletStatePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class Doomscroll implements ModInitializer {
	public static final String MOD_ID = "doomscroll";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** Last address change per player (for the cooldown). */
	private static final Map<UUID, Long> URL_COOLDOWN = new ConcurrentHashMap<>();
	/** Last report per player. */
	private static final Map<UUID, Long> REPORT_COOLDOWN = new ConcurrentHashMap<>();

	public static ScreenBlock SCREEN_BLOCK;
	public static BlockItem SCREEN_ITEM;
	public static RemoteItem REMOTE_ITEM;
	public static SoundEvent SCREEN_SOUND;
	public static SoundEvent TABLET_SOUND;
	public static TabletItem TABLET_ITEM;
	public static BlockEntityType<ScreenBlockEntity> SCREEN_BE_TYPE;
	/** Who is holding the tablet right now (the server updates it every tick; used when rendering someone else's tablet). */
	public static DataComponentType<UUID> TABLET_OWNER;
	/** The screen (anchor) the remote is bound to. Set by right-clicking a screen. */
	public static DataComponentType<BlockPos> REMOTE_TARGET;
	/** Test: this owner UUID is never overwritten server-side (one-person sync test with an armor stand). */
	public static final UUID DEBUG_FAKE_OWNER = UUID.fromString("00000000-0000-4000-8000-00000000dead");
	/** Server: player -> tablet state. */
	public static final Map<UUID, TabletStateBroadcast> TABLET_STATES = new ConcurrentHashMap<>();

	/** Light level of a screen that is on (0-15). */
	public static final int LIGHT_LEVEL = 12;
	/** If the controller stays silent (no signal) longer than this, control is dropped. */
	public static long CONTROL_TIMEOUT_MS = 45_000L;
	/** Control is dropped once the controller moves this far away from the screen. */
	public static final double CONTROL_RANGE = 64.0;
	/** Farthest distance from which clients may talk to a screen. */
	private static final double REACH = 96.0;

	/** Server: when each screen's controller last sent a signal (dimension + anchor). */
	public record ScreenKey(ResourceKey<Level> dim, BlockPos pos) {}
	public static final Map<ScreenKey, Long> CONTROL_ACTIVITY = new ConcurrentHashMap<>();
	/** Server: broadcast subscribers (screen -> viewers) and screen -> broadcaster. */
	public static final Map<ScreenKey, java.util.Set<UUID>> BROADCAST_SUBS = new ConcurrentHashMap<>();
	public static final Map<ScreenKey, UUID> BROADCASTERS = new ConcurrentHashMap<>();
	/** Server: per-broadcaster chunk counter (one-second window, abuse limit). */
	private static final Map<UUID, long[]> CHUNK_RATE = new ConcurrentHashMap<>();
	/** Server: pointer broadcast rate limit (player -> last broadcast ms). */
	public static final Map<UUID, Long> POINTER_LAST = new ConcurrentHashMap<>();
	/** Server: rate limit for tablet packets (player -> {last state ms, last position ms}). */
	private static final Map<UUID, long[]> TABLET_RATE = new ConcurrentHashMap<>();
	/** Server: rate limit for the controller's passive address changes (screen -> last ms). */
	private static final Map<ScreenKey, Long> PASSIVE_URL = new ConcurrentHashMap<>();

	// Hooks filled in by the client side
	public static Consumer<BlockPos> screenOpener = pos -> {};
	public static Runnable playbackToggler = () -> {};
	public static Runnable remoteOpener = () -> {};
	public static Runnable tabletOpener = () -> {};
	public static Consumer<BlockPos> screenRemoved = pos -> {};

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	// ---------- helpers (server) ----------

	static boolean isAdmin(ServerPlayer p) {
		return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/** A locked screen may be controlled by its owner and admins; any other screen by everyone. */
	static boolean canControl(ScreenBlockEntity a, ServerPlayer p) {
		return !a.isLocked() || a.isOwner(p.getUUID()) || isAdmin(p);
	}

	static ScreenKey keyOf(ServerLevel level, ScreenBlockEntity a) {
		return new ScreenKey(level.dimension(), a.getBlockPos().immutable());
	}

	static void touch(ServerPlayer p, ScreenBlockEntity a) {
		CONTROL_ACTIVITY.put(keyOf(p.level(), a), System.currentTimeMillis());
	}

	static void giveControl(ServerPlayer p, ScreenBlockEntity a) {
		a.setController(p.getUUID(), p.getName().getString());
		touch(p, a);
	}

	static void deny(ServerPlayer p, ScreenBlockEntity a) {
		Component who = a.getOwnerName().isEmpty()
				? Component.translatable("message.doomscroll.owner_word")
				: Component.literal(a.getOwnerName());
		ServerPlayNetworking.send(p, new ScreenNoticePayload(a.getBlockPos(), ScreenNoticePayload.DENIED,
				Component.translatable("message.doomscroll.locked_by", who)));
	}

	static void notice(ServerPlayer p, ScreenBlockEntity a, Component text) {
		ServerPlayNetworking.send(p, new ScreenNoticePayload(a.getBlockPos(), ScreenNoticePayload.INFO, text));
	}

	/** Short readable form of the address (the domain). */
	static String siteOf(String url) {
		try {
			String h = java.net.URI.create(url).getHost();
			if (h != null && !h.isEmpty()) {
				return h.startsWith("www.") ? h.substring(4) : h;
			}
		} catch (Exception ignored) {
		}
		return url.length() > 30 ? url.substring(0, 30) + "..." : url;
	}

	/** A new page was deliberately opened on the screen: brief notice to the others nearby (watch-party feel). */
	static void announce(ServerPlayer from, ScreenBlockEntity a, String url) {
		ServerConfig sc = ServerConfig.get();
		if (!sc.announce || sc.announceRange <= 0) {
			return;
		}
		Component msg = Component.translatable("message.doomscroll.screen.opened", from.getName().getString(), siteOf(url));
		for (ServerPlayer p : PlayerLookup.around(from.level(), Vec3.atCenterOf(a.getBlockPos()), sc.announceRange)) {
			if (p != from) {
				p.sendOverlayMessage(msg);
			}
		}
	}

	/** Opens a video from the queue on the screen: control passes to the opener, everyone switches to the same address. */
	private static void openFromQueue(ServerPlayer player, ScreenBlockEntity a, ServerQueue.Entry e) {
		giveControl(player, a);
		if (!e.url.equals(a.getUrl())) {
			announce(player, a, e.url);
			AuditLog.record(player, a, AuditLog.OPEN, e.url);
		}
		a.setUrl(e.url);
	}

	/** Sends the screen's queue to everyone nearby; the "did I vote" flag depends on the recipient. */
	static void sendQueue(ScreenBlockEntity a, ScreenKey key, net.minecraft.world.level.Level level) {
		java.util.List<ServerQueue.Entry> entries = ServerQueue.list(key);
		if (!(level instanceof ServerLevel sl)) {
			return;
		}
		for (ServerPlayer p : PlayerLookup.around(sl, Vec3.atCenterOf(a.getBlockPos()), CONTROL_RANGE)) {
			java.util.List<com.doomscroll.net.QueueBroadcast.Row> rows = new java.util.ArrayList<>(entries.size());
			for (ServerQueue.Entry e : entries) {
				rows.add(new com.doomscroll.net.QueueBroadcast.Row(
						e.url, e.title, e.byName, e.voteCount(), e.votes.contains(p.getUUID())));
			}
			ServerPlayNetworking.send(p, new com.doomscroll.net.QueueBroadcast(a.getBlockPos(), rows));
		}
	}

	/** Sends the policy to a client (on join and on a settings change). */
	static void sendPolicy(ServerPlayer p) {
		ServerPlayNetworking.send(p, policy());
	}

	/** Sends the policy to all players. */
	public static void broadcastPolicy(@Nullable MinecraftServer server) {
		if (server == null) {
			return;
		}
		com.doomscroll.net.ServerPolicyBroadcast msg = policy();
		for (ServerPlayer p : PlayerLookup.all(server)) {
			ServerPlayNetworking.send(p, msg);
		}
	}

	private static com.doomscroll.net.ServerPolicyBroadcast policy() {
		ServerConfig c = ServerConfig.get();
		int flags = 0;
		if (c.lockdown) flags |= com.doomscroll.net.ServerPolicyBroadcast.LOCKDOWN;
		if (c.allowPrivateNetwork) flags |= com.doomscroll.net.ServerPolicyBroadcast.ALLOW_PRIVATE;
		if (c.requireConsent) flags |= com.doomscroll.net.ServerPolicyBroadcast.REQUIRE_CONSENT;
		if (c.muteOthersByDefault) flags |= com.doomscroll.net.ServerPolicyBroadcast.MUTE_OTHERS;
		if (c.showDomain) flags |= com.doomscroll.net.ServerPolicyBroadcast.SHOW_DOMAIN;
		if (c.separateScreenCookies) flags |= com.doomscroll.net.ServerPolicyBroadcast.SEPARATE_COOKIES;
		return new com.doomscroll.net.ServerPolicyBroadcast(
				List.copyOf(c.blockedDomains), List.copyOf(c.allowedDomains), flags, c.urlCooldownMs);
	}

	/** Emergency shutdown: darkens every loaded screen. Returns the number of screens darkened. */
	public static int blackout() {
		int n = 0;
		for (ScreenBlockEntity s : ScreenBlockEntity.liveOnServer()) {
			if (s.isAnchor() && s.isOn()) {
				s.setOn(false);
				n++;
			}
		}
		return n;
	}

	/** Address-change cooldown (against spam and lag machines). */
	/** At least {@code gapMs} between tablet packets, per player. */
	private static boolean tabletFlood(ServerPlayer p, int slot, long gapMs) {
		long[] r = TABLET_RATE.computeIfAbsent(p.getUUID(), k -> new long[2]);
		long now = System.currentTimeMillis();
		if (now - r[slot] < gapMs) {
			return true;
		}
		r[slot] = now;
		return false;
	}

	/** The page's own redirects: at most two changes per second per screen get recorded. */
	private static boolean passiveFlood(ServerPlayer p, ScreenBlockEntity a) {
		ScreenKey k = keyOf(p.level(), a);
		long now = System.currentTimeMillis();
		Long last = PASSIVE_URL.get(k);
		if (last != null && now - last < 500L) {
			return true;
		}
		PASSIVE_URL.put(k, now);
		return false;
	}

	private static boolean onCooldown(ServerPlayer p) {
		int ms = ServerConfig.get().urlCooldownMs;
		if (ms <= 0 || Perms.has(p, Perms.BYPASS, Perms.GAMEMASTER)) {
			return false;
		}
		long now = System.currentTimeMillis();
		Long last = URL_COOLDOWN.get(p.getUUID());
		if (last != null && now - last < ms) {
			return true;
		}
		URL_COOLDOWN.put(p.getUUID(), now);
		return false;
	}

	/** Prevents report spam: once per 10 seconds per player. */
	private static boolean onReportCooldown(ServerPlayer p) {
		long now = System.currentTimeMillis();
		Long last = REPORT_COOLDOWN.get(p.getUUID());
		if (last != null && now - last < 10_000L) {
			return true;
		}
		REPORT_COOLDOWN.put(p.getUUID(), now);
		return false;
	}

	/** Message according to the denial reason. The address is never used as a format string. */
	static Component denyMessage(int deny, String url) {
		String site = siteOf(url);
		return switch (deny) {
			case ServerConfig.DENY_PRIVATE -> Component.translatable("message.doomscroll.url_private", site);
			case ServerConfig.DENY_LOCKDOWN -> Component.translatable("message.doomscroll.lockdown");
			case ServerConfig.DENY_NOT_ALLOWED -> Component.translatable("message.doomscroll.url_not_allowed", site);
			default -> Component.translatable("message.doomscroll.url_blocked", site);
		};
	}

	static boolean validUrl(String url) {
		return url != null && url.length() <= 2048 && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("doomscroll://"));
	}

	/** Anchor BE of a screen the player can reach (loaded, within range); null otherwise. */
	@Nullable
	static ScreenBlockEntity anchorNear(ServerPlayer player, BlockPos pos, double range) {
		if (player.position().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > range * range) {
			return null;
		}
		ServerLevel level = player.level();
		if (!level.isLoaded(pos)) {
			return null;
		}
		if (level.getBlockEntity(pos) instanceof ScreenBlockEntity sbe
				&& level.isLoaded(sbe.getAnchor())
				&& level.getBlockEntity(sbe.getAnchor()) instanceof ScreenBlockEntity a) {
			return a;
		}
		return null;
	}

	/** Clears controllers that timed out / moved away / left (once a second). */
	/** Ends the broadcast if the broadcaster is offline, in another dimension or out of range (viewers fall back to the site's address). */
	private static void expireBroadcasts(MinecraftServer server) {
		if (BROADCASTERS.isEmpty()) {
			return;
		}
		for (Map.Entry<ScreenKey, UUID> e : BROADCASTERS.entrySet()) {
			ServerLevel level = server.getLevel(e.getKey().dim());
			BlockPos pos = e.getKey().pos();
			if (level == null || !level.isLoaded(pos)) {
				continue;
			}
			if (!(level.getBlockEntity(pos) instanceof ScreenBlockEntity a) || !a.isAnchor()) {
				BROADCASTERS.remove(e.getKey());
				BROADCAST_SUBS.remove(e.getKey());
				continue;
			}
			java.util.Set<UUID> subs = BROADCAST_SUBS.get(e.getKey());
			if (subs != null) {
				subs.removeIf(id -> {
					ServerPlayer sp = server.getPlayerList().getPlayer(id);
					return sp == null || sp.level() != level
							|| sp.position().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH;
				});
			}
			ServerPlayer h = server.getPlayerList().getPlayer(e.getValue());
			boolean gone = h == null || h.level() != level
					|| h.position().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH
					|| !a.isOn();
			if (gone) {
				LOGGER.info("[broadcast] broadcast on screen {} ended ({})", pos.toShortString(), a.getBroadcasterName());
				a.setBroadcaster(null, "");
				BROADCASTERS.remove(e.getKey());
				BROADCAST_SUBS.remove(e.getKey());
			}
		}
	}

	private static void expireControllers(MinecraftServer server) {
		if (CONTROL_ACTIVITY.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<ScreenKey, Long>> it = CONTROL_ACTIVITY.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<ScreenKey, Long> e = it.next();
			ServerLevel level = server.getLevel(e.getKey().dim());
			BlockPos pos = e.getKey().pos();
			if (level == null) {
				it.remove();
				continue;
			}
			if (!level.isLoaded(pos)) {
				continue; // checked once it loads
			}
			if (!(level.getBlockEntity(pos) instanceof ScreenBlockEntity a) || !a.isAnchor() || a.getController() == null) {
				it.remove();
				continue;
			}
			ServerPlayer c = server.getPlayerList().getPlayer(a.getController());
			boolean gone = c == null
					|| c.level() != level
					|| c.position().distanceToSqr(Vec3.atCenterOf(pos)) > CONTROL_RANGE * CONTROL_RANGE
					|| now - e.getValue() > CONTROL_TIMEOUT_MS;
			if (gone) {
				LOGGER.info("[control] control of screen {} dropped ({})", pos.toShortString(), a.getControllerName());
				a.setController(null, "");
				it.remove();
			}
		}
	}

	/** Releases every control the player holds when they leave. */
	private static void dropControlsOf(MinecraftServer server, UUID player) {
		Iterator<Map.Entry<ScreenKey, Long>> it = CONTROL_ACTIVITY.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<ScreenKey, Long> e = it.next();
			ServerLevel level = server.getLevel(e.getKey().dim());
			BlockPos pos = e.getKey().pos();
			if (level == null || !level.isLoaded(pos)) {
				continue;
			}
			if (level.getBlockEntity(pos) instanceof ScreenBlockEntity a && a.isController(player)) {
				a.setController(null, "");
				it.remove();
			}
		}
	}

	@Override
	public void onInitialize() {
		ServerConfig.load();
		CONTROL_TIMEOUT_MS = ServerConfig.get().controlTimeoutSeconds * 1000L;
		AdminCommands.register();
		Identifier screenId = id("screen");
		Identifier remoteId = id("remote");

		SCREEN_BLOCK = Registry.register(
				BuiltInRegistries.BLOCK,
				screenId,
				new ScreenBlock(BlockBehaviour.Properties.of()
						.setId(ResourceKey.create(Registries.BLOCK, screenId))
						.strength(1.5f)
						.noOcclusion()
						.lightLevel(s -> s.getValue(ScreenBlock.LIT) ? ServerConfig.get().screenLightLevel : 0))
		);

		SCREEN_ITEM = Registry.register(
				BuiltInRegistries.ITEM,
				screenId,
				new ScreenItem(SCREEN_BLOCK, new Item.Properties()
						.setId(ResourceKey.create(Registries.ITEM, screenId))
						.useBlockDescriptionPrefix())
		);

		// Browser sounds: stream entries in sounds.json; the real data is live PCM via SoundBufferLibraryMixin
		SCREEN_SOUND = Registry.register(BuiltInRegistries.SOUND_EVENT, id("screen"),
				SoundEvent.createFixedRangeEvent(id("screen"), 48.0f));
		TABLET_SOUND = Registry.register(BuiltInRegistries.SOUND_EVENT, id("tablet"),
				SoundEvent.createVariableRangeEvent(id("tablet")));
		REMOTE_ITEM = Registry.register(
				BuiltInRegistries.ITEM,
				remoteId,
				new RemoteItem(new Item.Properties()
						.setId(ResourceKey.create(Registries.ITEM, remoteId))
						.stacksTo(1))
		);

		Identifier tabletId = id("tablet");
		TABLET_ITEM = Registry.register(
				BuiltInRegistries.ITEM,
				tabletId,
				new TabletItem(new Item.Properties()
						.setId(ResourceKey.create(Registries.ITEM, tabletId))
						.stacksTo(1))
		);

		SCREEN_BE_TYPE = Registry.register(
				BuiltInRegistries.BLOCK_ENTITY_TYPE,
				screenId,
				new BlockEntityType<>(ScreenBlockEntity::new, Set.of(SCREEN_BLOCK))
		);

		// Creative inventory: Functional Blocks tab
		ResourceKey<CreativeModeTab> functional = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("functional_blocks"));
		CreativeModeTabEvents.modifyOutputEvent(functional).register(out -> {
			out.accept(new ItemStack(SCREEN_ITEM), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
			out.accept(new ItemStack(REMOTE_ITEM), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
			out.accept(new ItemStack(TABLET_ITEM), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
		});

		TABLET_OWNER = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id("tablet_owner"),
				DataComponentType.<UUID>builder().persistent(UUIDUtil.STRING_CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build());

		REMOTE_TARGET = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id("remote_target"),
				DataComponentType.<BlockPos>builder().persistent(BlockPos.CODEC).networkSynchronized(BlockPos.STREAM_CODEC).build());

		// ---------- network types ----------
		PayloadTypeRegistry.serverboundPlay().register(SetScreenUrlPayload.TYPE, SetScreenUrlPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetScreenPowerPayload.TYPE, SetScreenPowerPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ScreenControlPayload.TYPE, ScreenControlPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ScreenTimePayload.TYPE, ScreenTimePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TabletStatePayload.TYPE, TabletStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TabletStateBroadcast.TYPE, TabletStateBroadcast.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.doomscroll.net.TabletTimePayload.TYPE, com.doomscroll.net.TabletTimePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.doomscroll.net.TabletTimeBroadcast.TYPE, com.doomscroll.net.TabletTimeBroadcast.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ScreenTimeBroadcast.TYPE, ScreenTimeBroadcast.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ScreenNoticePayload.TYPE, ScreenNoticePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.doomscroll.net.ServerPolicyBroadcast.TYPE, com.doomscroll.net.ServerPolicyBroadcast.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.doomscroll.net.ScreenReportPayload.TYPE, com.doomscroll.net.ScreenReportPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.doomscroll.net.QueueActionPayload.TYPE, com.doomscroll.net.QueueActionPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.doomscroll.net.QueueBroadcast.TYPE, com.doomscroll.net.QueueBroadcast.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.doomscroll.net.ScreenPointerPayload.TYPE, com.doomscroll.net.ScreenPointerPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.doomscroll.net.BroadcastChunkPayload.TYPE, com.doomscroll.net.BroadcastChunkPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.doomscroll.net.BroadcastControlPayload.TYPE, com.doomscroll.net.BroadcastControlPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.doomscroll.net.BroadcastChunkBroadcast.TYPE, com.doomscroll.net.BroadcastChunkBroadcast.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.doomscroll.net.BroadcastControlBroadcast.TYPE, com.doomscroll.net.BroadcastControlBroadcast.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.doomscroll.net.ScreenPointerBroadcast.TYPE, com.doomscroll.net.ScreenPointerBroadcast.CODEC);

		// ---------- screen address (control model) ----------
		// explicit: the player navigated deliberately -> takes control (if the lock allows), the address goes to everyone.
		// passive: their browser changed on its own -> only the controller (or, if control is free, the first to arrive) propagates it.
		ServerPlayNetworking.registerGlobalReceiver(SetScreenUrlPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			String url = payload.url();
			if (!validUrl(url)) {
				return;
			}
			ScreenBlockEntity a = anchorNear(player, payload.pos(), REACH);
			if (a == null) {
				return;
			}
			int deny = ServerConfig.check(url);
			if (deny != ServerConfig.OK && !Perms.has(player, Perms.BYPASS, Perms.GAMEMASTER)) {
				AuditLog.record(player, a, AuditLog.BLOCKED, url);
				ServerPlayNetworking.send(player, new ScreenNoticePayload(a.getBlockPos(), ScreenNoticePayload.BLOCKED,
						denyMessage(deny, url)));
				return;
			}
			if (!Perms.has(player, Perms.URL, Perms.EVERYONE)) {
				if (payload.explicit()) {
					notice(player, a, Component.translatable("message.doomscroll.no_permission"));
				}
				return;
			}
			if (payload.explicit()) {
				if (!canControl(a, player)) {
					deny(player, a); // permission first: a refused attempt must not consume the cooldown
					return;
				}
				if (onCooldown(player)) {
					notice(player, a, Component.translatable("message.doomscroll.too_fast"));
					return;
				}
				giveControl(player, a);
				if (!url.equals(a.getUrl())) {
					announce(player, a, url);
					AuditLog.record(player, a, AuditLog.OPEN, url);
				}
				a.setUrl(url);
				return;
			}
			UUID c = a.getController();
			if (c == null) {
				if (!canControl(a, player)) {
					return; // on a locked screen a viewer just watches silently
				}
				giveControl(player, a);
				if (!url.equals(a.getUrl())) {
					AuditLog.record(player, a, AuditLog.OPEN, url);
				}
				a.setUrl(url);
			} else if (c.equals(player.getUUID())) {
				touch(player, a);
				if (!url.equals(a.getUrl())) {
					if (passiveFlood(player, a)) {
						return; // the page's own redirects must not flood the server
					}
					AuditLog.record(player, a, AuditLog.OPEN, url);
				}
				a.setUrl(url);
			}
			// a passive change on a screen driven by someone else is not propagated
		}));

		// ---------- take / release control, lock ----------
		ServerPlayNetworking.registerGlobalReceiver(ScreenControlPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			ScreenBlockEntity a = anchorNear(player, payload.pos(), REACH);
			if (a == null) {
				return;
			}
			switch (payload.action()) {
				case ScreenControlPayload.TAKE -> {
					if (!canControl(a, player)) {
						deny(player, a);
						return;
					}
					giveControl(player, a);
				}
				case ScreenControlPayload.RELEASE -> {
					if (a.isController(player.getUUID())) {
						a.setController(null, "");
						CONTROL_ACTIVITY.remove(keyOf(player.level(), a));
						notice(player, a, Component.translatable("message.doomscroll.control.released"));
					}
				}
				case ScreenControlPayload.TOGGLE_LOCK -> {
					if (!a.isOwner(player.getUUID()) && !isAdmin(player)) {
						notice(player, a, a.getOwnerName().isEmpty()
								? Component.translatable("message.doomscroll.lock.owner_only")
								: Component.translatable("message.doomscroll.lock.owner_only_named", a.getOwnerName()));
						return;
					}
					boolean lock = !a.isLocked();
					a.setLocked(lock);
					if (lock) {
						UUID c = a.getController();
						if (c != null && !a.isOwner(c)) {
							ServerPlayer cp = ctx.server().getPlayerList().getPlayer(c);
							if (cp == null || !isAdmin(cp)) {
								a.setController(null, "");
								CONTROL_ACTIVITY.remove(keyOf(player.level(), a));
							}
						}
					}
					notice(player, a, Component.translatable(lock ? "message.doomscroll.lock.on" : "message.doomscroll.lock.off"));
				}
				case ScreenControlPayload.CYCLE_VOLUME -> {
					if (!canControl(a, player)) {
						deny(player, a);
						return;
					}
					float v = a.getVolume();
					float n = v <= 0.01f ? 0.35f : v <= 0.4f ? 0.7f : v <= 0.75f ? 1.0f : 0f;
					a.setVolume(n);
					notice(player, a, Component.translatable("message.doomscroll.screen_volume_set", Component.translatable(ScreenBlockEntity.volumeKey(n))));
				}
				case ScreenControlPayload.TOGGLE_REDSTONE -> {
					if (!ServerConfig.get().redstoneControl) {
						notice(player, a, Component.translatable("message.doomscroll.redstone.server_off"));
						return;
					}
					if (!a.isOwner(player.getUUID()) && !isAdmin(player)) {
						notice(player, a, Component.translatable("message.doomscroll.redstone.owner_only"));
						return;
					}
					boolean r = !a.isRedstone();
					a.setRedstone(r);
					notice(player, a, Component.translatable(r ? "message.doomscroll.redstone.on" : "message.doomscroll.redstone.off"));
				}
				default -> { }
			}
		}));

		// ---------- shared pointer (the looking player's cursor, sent to those nearby) ----------
		ServerPlayNetworking.registerGlobalReceiver(com.doomscroll.net.ScreenPointerPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			if (!ServerConfig.get().pointer) {
				return;
			}
			ServerPlayer player = ctx.player();
			ScreenBlockEntity a = anchorNear(player, payload.pos(), REACH);
			if (a == null) {
				return;
			}
			float pu = payload.u();
			float pv = payload.v();
			if (Float.isNaN(pu) || Float.isNaN(pv) || Float.isInfinite(pu) || Float.isInfinite(pv)) {
				return;
			}
			long now = System.currentTimeMillis();
			Long last = POINTER_LAST.get(player.getUUID());
			if (last != null && now - last < 60L) {
				return; // at most ~16 per second (the leave notice included)
			}
			POINTER_LAST.put(player.getUUID(), now);
			var b = new com.doomscroll.net.ScreenPointerBroadcast(a.getBlockPos(), player.getUUID(), player.getName().getString(), pu, pv);
			boolean echo = Boolean.getBoolean("doomscroll.selftest"); // smoke test: echo it back to the sender too
			for (ServerPlayer p : PlayerLookup.around(player.level(), Vec3.atCenterOf(a.getBlockPos()), 48.0)) {
				if (p != player || echo) {
					ServerPlayNetworking.send(p, b);
				}
			}
		}));

		// ---------- broadcast: start / stop / subscribe ----------
		ServerPlayNetworking.registerGlobalReceiver(com.doomscroll.net.BroadcastControlPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			ScreenBlockEntity a = anchorNear(player, payload.pos(), REACH);
			if (a == null) {
				return;
			}
			ScreenKey key = keyOf(player.level(), a);
			switch (payload.action()) {
				case com.doomscroll.net.BroadcastControlPayload.START -> {
					if (!ServerConfig.get().broadcast) {
						notice(player, a, Component.translatable("message.doomscroll.broadcast.server_off"));
						return;
					}
					if (!canControl(a, player)) {
						deny(player, a);
						return;
					}
					UUID cur = a.getBroadcaster();
					if (cur != null && !cur.equals(player.getUUID())) {
						notice(player, a, Component.translatable("message.doomscroll.broadcast.already", a.getBroadcasterName()));
						return;
					}
					if (!Perms.has(player, Perms.BROADCAST, Perms.EVERYONE)) {
						notice(player, a, Component.translatable("message.doomscroll.no_permission"));
						return;
					}
					giveControl(player, a);
					a.setBroadcaster(player.getUUID(), player.getName().getString());
					AuditLog.record(player, a, AuditLog.BROADCAST, a.getUrl());
					BROADCASTERS.put(key, player.getUUID());
					BROADCAST_SUBS.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
					Component msg = Component.translatable("message.doomscroll.broadcast.started", player.getName().getString());
					for (ServerPlayer p : PlayerLookup.around(player.level(), Vec3.atCenterOf(a.getBlockPos()), REACH)) {
						p.sendOverlayMessage(msg);
					}
				}
				case com.doomscroll.net.BroadcastControlPayload.STOP -> {
					if (a.getBroadcaster() == null) {
						return;
					}
					if (!a.isBroadcaster(player.getUUID()) && !a.isOwner(player.getUUID()) && !isAdmin(player)) {
						notice(player, a, Component.translatable("message.doomscroll.broadcast.stop_denied"));
						return;
					}
					a.setBroadcaster(null, "");
					BROADCASTERS.remove(key);
					BROADCAST_SUBS.remove(key);
					notice(player, a, Component.translatable("message.doomscroll.broadcast.stopped"));
				}
				case com.doomscroll.net.BroadcastControlPayload.SUBSCRIBE -> {
					UUID host = a.getBroadcaster();
					if (host == null) {
						return;
					}
					if (!BROADCAST_SUBS.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(player.getUUID())) {
						return; // was already subscribed: do not restart the broadcaster
					}
					ServerPlayer hp = ctx.server().getPlayerList().getPlayer(host);
					if (hp != null) {
						ServerPlayNetworking.send(hp, new com.doomscroll.net.BroadcastControlBroadcast(a.getBlockPos(), com.doomscroll.net.BroadcastControlBroadcast.RESTART));
					}
				}
				case com.doomscroll.net.BroadcastControlPayload.UNSUBSCRIBE -> {
					java.util.Set<UUID> subs = BROADCAST_SUBS.get(key);
					if (subs != null) {
						subs.remove(player.getUUID());
					}
				}
				default -> { }
			}
		}));

		// ---------- broadcast chunks (broadcaster -> subscribers) ----------
		ServerPlayNetworking.registerGlobalReceiver(com.doomscroll.net.BroadcastChunkPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			ScreenKey key = new ScreenKey(player.level().dimension(), payload.pos().immutable());
			UUID host = BROADCASTERS.get(key);
			if (host == null || !host.equals(player.getUUID())) {
				return;
			}
			// rate limit: at most 40 chunks per second (~1.2 MB/s), anything more is dropped
			long[] rate = CHUNK_RATE.computeIfAbsent(player.getUUID(), k -> new long[2]);
			long sec = System.currentTimeMillis() / 1000L;
			if (rate[0] != sec) {
				rate[0] = sec;
				rate[1] = 0;
			}
			if (++rate[1] > 40) {
				return;
			}
			java.util.Set<UUID> subs = BROADCAST_SUBS.get(key);
			boolean echo = Boolean.getBoolean("doomscroll.selftest");
			if ((subs == null || subs.isEmpty()) && !echo) {
				return;
			}
			var b = new com.doomscroll.net.BroadcastChunkBroadcast(payload.pos(), payload.seq(), payload.piece(), payload.pieces(), payload.init(), payload.data());
			if (subs != null) {
				for (UUID id : subs) {
					ServerPlayer p = ctx.server().getPlayerList().getPlayer(id);
					if (p != null && p != player) {
						ServerPlayNetworking.send(p, b);
					}
				}
			}
			if (echo) {
				ServerPlayNetworking.send(player, b);
			}
		}));

		// ---------- video position (controller -> viewers) ----------
		ServerPlayNetworking.registerGlobalReceiver(ScreenTimePayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			ScreenBlockEntity a = anchorNear(player, payload.pos(), REACH);
			if (a == null || !a.isController(player.getUUID())) {
				return;
			}
			touch(player, a);
			if (!(payload.time() >= 0f) || !(payload.duration() > 0f)) {
				return; // just an "I am here" signal
			}
			ScreenTimeBroadcast b = new ScreenTimeBroadcast(a.getBlockPos(), payload.time(), payload.duration(), payload.paused());
			for (ServerPlayer p : PlayerLookup.around(player.level(), Vec3.atCenterOf(a.getBlockPos()), REACH)) {
				if (p != player) {
					ServerPlayNetworking.send(p, b);
				}
			}
		}));

		// ---------- personal tablet state (so everyone sees what a player is watching on their tablet) ----------
		ServerPlayNetworking.registerGlobalReceiver(TabletStatePayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			if (player.hasDisconnected()) {
				return; // a packet arriving after the disconnect must not leave a permanent ghost
			}
			String url = payload.url();
			if (!url.isEmpty() && !validUrl(url)) {
				return;
			}
			if (tabletFlood(player, 0, 250L)) {
				return;
			}
			// Same rules as the screen: blocked/allowed domain, local network, emergency shutdown, permission node.
			if (!url.isEmpty()) {
				boolean bypass = Perms.has(player, Perms.BYPASS, Perms.GAMEMASTER);
				if (!bypass && !Perms.has(player, Perms.URL, Perms.EVERYONE)) {
					return;
				}
				int deny = ServerConfig.check(url);
				if (deny != ServerConfig.OK && !bypass) {
					AuditLog.record(player, null, AuditLog.BLOCKED, "tablet " + url);
					ServerPlayNetworking.send(player, new ScreenNoticePayload(player.blockPosition(),
							ScreenNoticePayload.BLOCKED, denyMessage(deny, url)));
					url = ""; // shows as empty on other players' tablets; their own page stays open
				}
			}
			float vol = payload.volume();
			vol = Float.isNaN(vol) ? 0f : Math.max(0f, Math.min(1f, vol));
			TabletStateBroadcast b = new TabletStateBroadcast(player.getUUID(), url, payload.portrait(), vol);
			TABLET_STATES.put(b.player(), b);
			for (ServerPlayer p : PlayerLookup.all(ctx.server())) {
				ServerPlayNetworking.send(p, b);
			}
		}));
		// tablet position: viewers align to the same moment (transient, not stored)
		ServerPlayNetworking.registerGlobalReceiver(com.doomscroll.net.TabletTimePayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			if (!(payload.time() >= 0f) || !(payload.duration() >= 0f)) {
				return; // NaN lands here too
			}
			if (tabletFlood(ctx.player(), 1, 200L)) {
				return;
			}
			com.doomscroll.net.TabletTimeBroadcast b = new com.doomscroll.net.TabletTimeBroadcast(
					ctx.player().getUUID(), payload.time(), payload.duration(), payload.paused());
			for (ServerPlayer p : PlayerLookup.all(ctx.server())) {
				if (p != ctx.player()) {
					ServerPlayNetworking.send(p, b);
				}
			}
		}));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			sendPolicy(handler.player);
			for (TabletStateBroadcast b : TABLET_STATES.values()) {
				ServerPlayNetworking.send(handler.player, b);
			}
		});

		// ---------- shared queue (with voting) ----------
		ServerPlayNetworking.registerGlobalReceiver(com.doomscroll.net.QueueActionPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			ScreenBlockEntity a = anchorNear(player, payload.pos(), CONTROL_RANGE);
			if (a == null) {
				return;
			}
			ScreenKey key = keyOf(player.level(), a);
			boolean admin = isAdmin(player);
			String url = payload.url();
			switch (payload.action()) {
				case com.doomscroll.net.QueueActionPayload.ADD -> {
					if (!validUrl(url)) {
						return;
					}
					if (!Perms.has(player, Perms.URL, Perms.EVERYONE)) {
						notice(player, a, Component.translatable("message.doomscroll.no_permission"));
						return;
					}
					int deny = ServerConfig.check(url);
					if (deny != ServerConfig.OK && !Perms.has(player, Perms.BYPASS, Perms.GAMEMASTER)) {
						AuditLog.record(player, a, AuditLog.BLOCKED, url);
						notice(player, a, denyMessage(deny, url));
						return;
					}
					if (!ServerQueue.add(key, url, payload.title(), player)) {
						notice(player, a, Component.translatable("message.doomscroll.queue.full", ServerQueue.MAX));
						return;
					}
					AuditLog.record(player, a, AuditLog.QUEUE, url);
				}
				case com.doomscroll.net.QueueActionPayload.VOTE -> ServerQueue.vote(key, url, player);
				case com.doomscroll.net.QueueActionPayload.REMOVE -> {
					if (!ServerQueue.remove(key, url, player, admin || a.isOwner(player.getUUID()))) {
						notice(player, a, Component.translatable("message.doomscroll.queue.not_yours"));
						return;
					}
				}
				case com.doomscroll.net.QueueActionPayload.CLEAR -> {
					if (!admin && !a.isOwner(player.getUUID())) {
						notice(player, a, Component.translatable("message.doomscroll.queue.not_yours"));
						return;
					}
					ServerQueue.clear(key);
				}
				case com.doomscroll.net.QueueActionPayload.NEXT -> {
					if (!canControl(a, player)) {
						deny(player, a);
						return;
					}
					ServerQueue.Entry e = ServerQueue.poll(key);
					if (e == null) {
						return;
					}
					openFromQueue(player, a, e);
				}
				case com.doomscroll.net.QueueActionPayload.PLAY -> {
					if (!canControl(a, player)) {
						deny(player, a);
						return;
					}
					ServerQueue.Entry e = ServerQueue.take(key, url);
					if (e == null) {
						return;
					}
					openFromQueue(player, a, e);
				}
				default -> {
					// REFRESH: the broadcast below already sends the current list
				}
			}
			sendQueue(a, key, player.level());
		}));

		// ---------- report ----------
		ServerPlayNetworking.registerGlobalReceiver(com.doomscroll.net.ScreenReportPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			ScreenBlockEntity a = anchorNear(player, payload.pos(), 64);
			if (a == null) {
				return;
			}
			if (onReportCooldown(player)) {
				notice(player, a, Component.translatable("message.doomscroll.too_fast"));
				return;
			}
			String owner = a.getOwnerName().isEmpty() ? "-" : a.getOwnerName();
			// Do not trust the note sent by the client: the address and the owner are read from the server.
			AuditLog.record(player, a, AuditLog.REPORT, "owner=" + owner + " url=" + a.getUrl() + " note=" + payload.note());
			notice(player, a, Component.translatable("message.doomscroll.report.sent"));
			BlockPos bp = a.getBlockPos();
			Component alert = Component.translatable("message.doomscroll.report.alert",
					player.getName().getString(), owner, siteOf(a.getUrl()),
					bp.getX() + " " + bp.getY() + " " + bp.getZ());
			for (ServerPlayer admin : PlayerLookup.all(ctx.server())) {
				if (Perms.has(admin, Perms.ADMIN, Perms.GAMEMASTER)) {
					admin.sendSystemMessage(alert);
				}
			}
		}));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.player.getUUID();
			if (TABLET_STATES.remove(id) != null) {
				TabletStateBroadcast gone = new TabletStateBroadcast(id, "", false, 0f);
				for (ServerPlayer p : PlayerLookup.all(server)) {
					ServerPlayNetworking.send(p, gone);
				}
			}
			dropControlsOf(server, id);
			POINTER_LAST.remove(id);
			CHUNK_RATE.remove(id);
			TABLET_RATE.remove(id);
			URL_COOLDOWN.remove(id);
			REPORT_COOLDOWN.remove(id);
			// if the broadcaster left, the broadcast ends; drop the subscriptions
			for (var e : BROADCASTERS.entrySet()) {
				if (id.equals(e.getValue())) {
					ServerLevel lvl = server.getLevel(e.getKey().dim());
					if (lvl != null && lvl.isLoaded(e.getKey().pos()) && lvl.getBlockEntity(e.getKey().pos()) instanceof ScreenBlockEntity sbe) {
						sbe.setBroadcaster(null, "");
					}
					BROADCASTERS.remove(e.getKey());
					BROADCAST_SUBS.remove(e.getKey());
				}
			}
			for (java.util.Set<UUID> subs : BROADCAST_SUBS.values()) {
				subs.remove(id);
			}
		});

		// ---------- power on/off from the remote ----------
		ServerPlayNetworking.registerGlobalReceiver(SetScreenPowerPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			ScreenBlockEntity a = anchorNear(player, payload.pos(), CONTROL_RANGE);
			if (a == null) {
				return;
			}
			// Turning off is for the owner (and admins); turning on is for everyone unless locked
			if (!payload.on() && !a.isOwner(player.getUUID()) && !isAdmin(player)) {
				player.sendOverlayMessage(a.getOwnerName().isEmpty()
						? Component.translatable("message.doomscroll.power.owner_only")
						: Component.translatable("message.doomscroll.power.owner_only_named", a.getOwnerName()));
				return;
			}
			if (payload.on() && ServerConfig.get().lockdown) {
				notice(player, a, Component.translatable("message.doomscroll.lockdown"));
				return;
			}
			if (payload.on() && !canControl(a, player)) {
				deny(player, a);
				return;
			}
			a.setOn(payload.on());
			AuditLog.record(player, a, AuditLog.POWER, payload.on() ? "on" : "off");
		}));

		// ---------- control timeout ----------
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 0) {
				expireControllers(server);
				expireBroadcasts(server);
			}
		});

		// Server-side tables are reset when the world closes; otherwise, in the next world, the
		// screen at the same coordinates would inherit the old queue and the old controller.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			URL_COOLDOWN.clear();
			REPORT_COOLDOWN.clear();
			CONTROL_ACTIVITY.clear();
			BROADCASTERS.clear();
			BROADCAST_SUBS.clear();
			CHUNK_RATE.clear();
			POINTER_LAST.clear();
			TABLET_RATE.clear();
			PASSIVE_URL.clear();
			TABLET_STATES.clear();
			ServerQueue.clearAll();
			ScreenBlockEntity.clearServerLive();
			AuditLog.flush();
		});

		LOGGER.info("doomscroll loaded - ready to scroll");
	}
}
