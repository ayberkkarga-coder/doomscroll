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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class Doomscroll implements ModInitializer {
	public static final String MOD_ID = "doomscroll";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static ScreenBlock SCREEN_BLOCK;
	public static BlockItem SCREEN_ITEM;
	public static RemoteItem REMOTE_ITEM;
	public static SoundEvent SCREEN_SOUND;
	public static SoundEvent TABLET_SOUND;
	public static TabletItem TABLET_ITEM;
	public static BlockEntityType<ScreenBlockEntity> SCREEN_BE_TYPE;
	/** Tabletin su an kimin elinde oldugu (sunucu her tick gunceller; baskasinin tabletini cizerken kullanilir). */
	public static DataComponentType<UUID> TABLET_OWNER;
	/** Kumandanin bagli oldugu ekran (anchor). Ekrana sag tik ile ayarlanir. */
	public static DataComponentType<BlockPos> REMOTE_TARGET;
	/** Test: bu sahip UUID'si sunucu tarafinda ezilmez (zirh askisi ile tek kisilik senkron testi). */
	public static final UUID DEBUG_FAKE_OWNER = UUID.fromString("00000000-0000-4000-8000-00000000dead");
	/** Sunucu: oyuncu -> tablet durumu. */
	public static final Map<UUID, TabletStateBroadcast> TABLET_STATES = new ConcurrentHashMap<>();

	/** Acik ekranin isik seviyesi (0-15). */
	public static final int LIGHT_LEVEL = 12;
	/** Kontrolcu bu sureden uzun sessiz kalirsa (sinyal yok) kontrol dusar. */
	public static long CONTROL_TIMEOUT_MS = 45_000L;
	/** Kontrolcu ekrandan bu kadar uzaklasinca kontrol dusar. */
	public static final double CONTROL_RANGE = 64.0;
	/** Istemcilerin ekranla konusabildigi en uzak mesafe. */
	private static final double REACH = 96.0;

	/** Sunucu: hangi ekranin kontrolcusu en son ne zaman sinyal verdi (boyut + anchor). */
	public record ScreenKey(ResourceKey<Level> dim, BlockPos pos) {}
	public static final Map<ScreenKey, Long> CONTROL_ACTIVITY = new ConcurrentHashMap<>();
	/** Sunucu: yayin aboneleri (ekran -> izleyiciler) ve ekran -> yayinci. */
	public static final Map<ScreenKey, java.util.Set<UUID>> BROADCAST_SUBS = new ConcurrentHashMap<>();
	public static final Map<ScreenKey, UUID> BROADCASTERS = new ConcurrentHashMap<>();
	/** Sunucu: yayinci basina parca sayaci (saniye penceresi, kotuye kullanim siniri). */
	private static final Map<UUID, long[]> CHUNK_RATE = new ConcurrentHashMap<>();
	/** Sunucu: isaretci yayin hiz siniri (oyuncu -> son yayin ms). */
	public static final Map<UUID, Long> POINTER_LAST = new ConcurrentHashMap<>();

	// Client tarafinin doldurdugu kancalar
	public static Consumer<BlockPos> screenOpener = pos -> {};
	public static Runnable playbackToggler = () -> {};
	public static Runnable remoteOpener = () -> {};
	public static Runnable tabletOpener = () -> {};
	public static Consumer<BlockPos> screenRemoved = pos -> {};

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	// ---------- yardimcilar (sunucu) ----------

	static boolean isAdmin(ServerPlayer p) {
		return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/** Kilitli ekrani sahibi ve yoneticiler; digerlerini herkes kontrol edebilir. */
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

	/** Adresin okunabilir kisa hali (alan adi). */
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

	/** Ekrana bilerek yeni sayfa acildi: yakindaki digerlerine kisa bildirim (izleme partisi hissi). */
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

	static boolean validUrl(String url) {
		return url != null && url.length() <= 2048 && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("doomscroll://"));
	}

	/** Oyuncunun erisebildigi (yuklu, menzilde) ekranin anchor BE'si; yoksa null. */
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

	/** Kontrolu dusmus/uzaklasmis/cikmis kontrolculeri temizler (saniyede bir). */
	/** Yayinci cevrimdisi, baska boyutta ya da menzil disindaysa yayini bitir (izleyiciler sitenin adresine doner). */
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
			ServerPlayer h = server.getPlayerList().getPlayer(e.getValue());
			boolean gone = h == null || h.level() != level
					|| h.position().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH
					|| !a.isOn();
			if (gone) {
				LOGGER.info("[yayin] {} ekranindaki yayin bitti ({})", pos.toShortString(), a.getBroadcasterName());
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
				continue; // yuklenince bakilir
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
				LOGGER.info("[kontrol] {} ekranindaki kontrol dustu ({})", pos.toShortString(), a.getControllerName());
				a.setController(null, "");
				it.remove();
			}
		}
	}

	/** Oyuncu ciktiginda tuttugu tum kontrolleri birakir. */
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

		// Tarayici sesleri: sounds.json stream girdileri; gercek veri SoundBufferLibraryMixin ile canli PCM
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

		// Yaratici envanter: Islevsel Bloklar sekmesi
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

		// ---------- ag tipleri ----------
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
		PayloadTypeRegistry.serverboundPlay().register(com.doomscroll.net.ScreenPointerPayload.TYPE, com.doomscroll.net.ScreenPointerPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.doomscroll.net.BroadcastChunkPayload.TYPE, com.doomscroll.net.BroadcastChunkPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.doomscroll.net.BroadcastControlPayload.TYPE, com.doomscroll.net.BroadcastControlPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.doomscroll.net.BroadcastChunkBroadcast.TYPE, com.doomscroll.net.BroadcastChunkBroadcast.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.doomscroll.net.BroadcastControlBroadcast.TYPE, com.doomscroll.net.BroadcastControlBroadcast.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.doomscroll.net.ScreenPointerBroadcast.TYPE, com.doomscroll.net.ScreenPointerBroadcast.CODEC);

		// ---------- ekran adresi (kontrol modeli) ----------
		// explicit: oyuncu bilerek yonlendirdi -> kontrolu alir (kilit izin veriyorsa), adres herkese gider.
		// pasif: tarayicisi kendiliginden degisti -> yalnizca kontrolcu (ya da kontrol bostaysa ilk gelen) yayar.
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
			if (!ServerConfig.urlAllowed(url)) {
				ServerPlayNetworking.send(player, new ScreenNoticePayload(a.getBlockPos(), ScreenNoticePayload.BLOCKED,
						Component.translatable("message.doomscroll.url_blocked", siteOf(url))));
				return;
			}
			if (payload.explicit()) {
				if (!canControl(a, player)) {
					deny(player, a);
					return;
				}
				giveControl(player, a);
				if (!url.equals(a.getUrl())) {
					announce(player, a, url);
				}
				a.setUrl(url);
				return;
			}
			UUID c = a.getController();
			if (c == null) {
				if (!canControl(a, player)) {
					return; // kilitli ekranda izleyici sessizce izler
				}
				giveControl(player, a);
				a.setUrl(url);
			} else if (c.equals(player.getUUID())) {
				touch(player, a);
				a.setUrl(url);
			}
			// baskasinin surdugu ekrandaki pasif degisiklik yayilmaz
		}));

		// ---------- kontrol al / birak / kilit ----------
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

		// ---------- paylasimli isaretci (bakan oyuncunun imleci, yakindakilere) ----------
		ServerPlayNetworking.registerGlobalReceiver(com.doomscroll.net.ScreenPointerPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			if (!ServerConfig.get().pointer) {
				return;
			}
			ServerPlayer player = ctx.player();
			ScreenBlockEntity a = anchorNear(player, payload.pos(), REACH);
			if (a == null) {
				return;
			}
			long now = System.currentTimeMillis();
			Long last = POINTER_LAST.get(player.getUUID());
			if (last != null && now - last < 60L && payload.u() >= 0) {
				return; // sn'de en fazla ~16
			}
			POINTER_LAST.put(player.getUUID(), now);
			var b = new com.doomscroll.net.ScreenPointerBroadcast(a.getBlockPos(), player.getUUID(), player.getName().getString(), payload.u(), payload.v());
			boolean echo = Boolean.getBoolean("doomscroll.selftest"); // duman testi: gonderene de yansit
			for (ServerPlayer p : PlayerLookup.around(player.level(), Vec3.atCenterOf(a.getBlockPos()), 48.0)) {
				if (p != player || echo) {
					ServerPlayNetworking.send(p, b);
				}
			}
		}));

		// ---------- yayin: baslat / durdur / abone ----------
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
					giveControl(player, a);
					a.setBroadcaster(player.getUUID(), player.getName().getString());
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
					BROADCAST_SUBS.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(player.getUUID());
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

		// ---------- yayin parcalari (yayinci -> aboneler) ----------
		ServerPlayNetworking.registerGlobalReceiver(com.doomscroll.net.BroadcastChunkPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			ScreenKey key = new ScreenKey(player.level().dimension(), payload.pos().immutable());
			UUID host = BROADCASTERS.get(key);
			if (host == null || !host.equals(player.getUUID())) {
				return;
			}
			// hiz siniri: saniyede en fazla 40 dilim (~1.2 MB/s), fazlasi atilir
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

		// ---------- video konumu (kontrolcu -> izleyiciler) ----------
		ServerPlayNetworking.registerGlobalReceiver(ScreenTimePayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			ScreenBlockEntity a = anchorNear(player, payload.pos(), REACH);
			if (a == null || !a.isController(player.getUUID())) {
				return;
			}
			touch(player, a);
			if (!(payload.time() >= 0f) || !(payload.duration() > 0f)) {
				return; // yalnizca "buradayim" sinyali
			}
			ScreenTimeBroadcast b = new ScreenTimeBroadcast(a.getBlockPos(), payload.time(), payload.duration(), payload.paused());
			for (ServerPlayer p : PlayerLookup.around(player.level(), Vec3.atCenterOf(a.getBlockPos()), REACH)) {
				if (p != player) {
					ServerPlayNetworking.send(p, b);
				}
			}
		}));

		// ---------- kisisel tablet durumu (herkes baskasinin tabletinde onun izledigini gorsun) ----------
		ServerPlayNetworking.registerGlobalReceiver(TabletStatePayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			String url = payload.url();
			if (url.length() > 2048 || (!url.isEmpty() && !(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("doomscroll://")))) {
				return;
			}
			float vol = Math.max(0f, Math.min(1f, payload.volume()));
			TabletStateBroadcast b = new TabletStateBroadcast(ctx.player().getUUID(), url, payload.portrait(), vol);
			TABLET_STATES.put(b.player(), b);
			for (ServerPlayer p : PlayerLookup.all(ctx.server())) {
				ServerPlayNetworking.send(p, b);
			}
		}));
		// tablet konumu: izleyenler ayni ana hizalansin (anlik, saklanmaz)
		ServerPlayNetworking.registerGlobalReceiver(com.doomscroll.net.TabletTimePayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			com.doomscroll.net.TabletTimeBroadcast b = new com.doomscroll.net.TabletTimeBroadcast(
					ctx.player().getUUID(), payload.time(), Math.max(0f, payload.duration()), payload.paused());
			for (ServerPlayer p : PlayerLookup.all(ctx.server())) {
				if (p != ctx.player()) {
					ServerPlayNetworking.send(p, b);
				}
			}
		}));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			for (TabletStateBroadcast b : TABLET_STATES.values()) {
				ServerPlayNetworking.send(handler.player, b);
			}
		});
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
			// yayinci ciktiysa yayin biter; abonelikleri sil
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

		// ---------- kumandadan ac/kapat ----------
		ServerPlayNetworking.registerGlobalReceiver(SetScreenPowerPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			ScreenBlockEntity a = anchorNear(player, payload.pos(), CONTROL_RANGE);
			if (a == null) {
				return;
			}
			// Kapatmak sahibine (ve yoneticilere); acmak kilit yoksa herkese
			if (!payload.on() && !a.isOwner(player.getUUID()) && !isAdmin(player)) {
				player.sendOverlayMessage(a.getOwnerName().isEmpty()
						? Component.translatable("message.doomscroll.power.owner_only")
						: Component.translatable("message.doomscroll.power.owner_only_named", a.getOwnerName()));
				return;
			}
			if (payload.on() && !canControl(a, player)) {
				deny(player, a);
				return;
			}
			a.setOn(payload.on());
		}));

		// ---------- kontrol suresi ----------
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 0) {
				expireControllers(server);
				expireBroadcasts(server);
			}
		});

		LOGGER.info("doomscroll yuklendi - kaydirmaya hazir");
	}
}
