package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.doomscroll.cef.api.CefService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.doomscroll.net.TabletStateBroadcast;
import com.doomscroll.net.TabletStatePayload;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class DoomscrollClient implements ClientModInitializer {
	private static int tickCounter = 0;
	/** Sohbet komutuyla acilacak ekran: ChatScreen komuttan sonra kendini kapatirken bizim ekrani da kapatiyor; bir tick sonra acilir. */
	@org.jetbrains.annotations.Nullable
	private static java.util.function.Supplier<net.minecraft.client.gui.screens.Screen> pendingScreen;

	public static void openLater(java.util.function.Supplier<net.minecraft.client.gui.screens.Screen> screen) {
		pendingScreen = screen;
	}
	private static final int HELP_LINES = 20;

	/** Yardim metni dil dosyasindan satir satir kurulur: command.doomscroll.help.0 .. help.19 */
	private static Component help() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < HELP_LINES; i++) {
			if (i > 0) sb.append('\n');
			sb.append(Lang.tr("command.doomscroll.help." + i));
		}
		return Component.literal(sb.toString());
	}

	private static boolean portraitKeyWasDown = false;
	private static boolean tabletWasHeld = false;

	/**
	 * Google/YouTube giris modunu acar/kapatir. Yeniden baslatma YOK: istek basliklarindaki kimlik aninda
	 * Firefox olur ve ekran Google giris sayfasina gider; kapatinca kimlik geri alinir ve YouTube acilir.
	 */
	public static boolean toggleTvLogin() {
		boolean on = !DoomscrollConfig.get().tvLogin;
		DoomscrollConfig.get().tvLogin = on;
		DoomscrollConfig.save();
		applyLoginMode(on);
		Browsers.navigateRaw(on ? Browsers.URL_YT_LOGIN : "https://www.youtube.com/");
		return on;
	}

	/** Istek basligi kimligi: giris modunda Firefox, normalde kapali (Chromium kendi kimligi). */
	public static void applyLoginMode(boolean on) {
		com.doomscroll.cef.api.CefLaunchOptions.headerUserAgentOverride = on ? DoomscrollConfig.TV_USER_AGENT : null;
	}

	public static String tvLoginMessage(boolean on) {
		return on
				? Lang.tr("command.doomscroll.tv_login.on")
				: Lang.tr("command.doomscroll.tv_login.off");
	}
	private static int tabletStateTick = 0;
	private static String lastTabletUrlSent = "";
	private static boolean lastTabletPortraitSent = false;
	private static float lastTabletVolumeSent = -1f;

	/** Kendi tablet durumumu (adres, dik, cihaz sesi) saniyede bir sunucuya bildir. */
	private static void tickTabletState(Minecraft client) {
		if (++tabletStateTick % 20 != 0 || client.player == null || Browsers.getTabletIfPresent() == null) {
			return;
		}
		// Konum: her saniye yollanir (izleyenler ayni ana hizalansin)
		double dur = Browsers.tabletDuration();
		double t = Browsers.tabletNow();
		if (dur > 0 && t >= 0) {
			ClientPlayNetworking.send(new com.doomscroll.net.TabletTimePayload((float) t, (float) dur, Browsers.tabletPaused()));
		}

		String u = Browsers.tabletUrl();
		boolean p = Browsers.isTabletPortrait();
		float vol = Browsers.isTabletMuted() ? 0f : Browsers.getTabletVolume();
		if (u.equals(lastTabletUrlSent) && p == lastTabletPortraitSent && vol == lastTabletVolumeSent) {
			return;
		}
		lastTabletUrlSent = u;
		lastTabletPortraitSent = p;
		lastTabletVolumeSent = vol;
		ClientPlayNetworking.send(new TabletStatePayload(u, p, vol));
	}
	private static int autoTick = 0;

	/** Tablet elde mi? Cebe girince sesi durdur, cikinca devam. */
	private static void tickTabletHeld(Minecraft client) {
		boolean held = client.player != null
				&& (client.player.getMainHandItem().getItem() == Doomscroll.TABLET_ITEM
				|| client.player.getOffhandItem().getItem() == Doomscroll.TABLET_ITEM);
		if (held != tabletWasHeld) {
			tabletWasHeld = held;
			Browsers.tabletVisible(held);
			BrowserAudio.setTabletHeld(held);
		}
	}

	/** Tablet eldeyken R: dik/yatay mod. Klavye ekrana bagliyken ve GUI acikken calismaz. */
	private static void tickPortraitKey(Minecraft client) {
		boolean down = com.mojang.blaze3d.platform.InputConstants.isKeyDown(client.getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_R);
		boolean pressed = down && !portraitKeyWasDown;
		portraitKeyWasDown = down;
		if (!pressed || client.player == null || client.gui.screen() != null || DirectControl.isKeyboardCaptured()) {
			return;
		}
		boolean holding = client.player.getMainHandItem().getItem() == Doomscroll.TABLET_ITEM
				|| client.player.getOffhandItem().getItem() == Doomscroll.TABLET_ITEM;
		if (!holding) {
			return;
		}
		Browsers.setTabletPortrait(!Browsers.isTabletPortrait());
		client.player.sendOverlayMessage(Component.translatable(Browsers.isTabletPortrait()
				? "message.doomscroll.tablet.portrait" : "message.doomscroll.tablet.landscape"));
	}

	@Override
	public void onInitializeClient() {
		DoomscrollConfig.load();
		com.doomscroll.cef.api.CefAudioDefaults.sampleRate = DoomscrollConfig.get().audioSampleRate;
		com.doomscroll.cef.api.CefLaunchOptions.frameRate = DoomscrollConfig.get().browserFps;
		com.doomscroll.cef.api.CefLaunchOptions.localPages = HomePages::html; // doomscroll://home ana menuler
		com.doomscroll.cef.api.CefAudioDefaults.targetBacklogMs = DoomscrollConfig.get().audioTargetBacklogMs();
		com.doomscroll.cef.api.CefLaunchOptions.adBlock = DoomscrollConfig.get().adBlock;
		com.doomscroll.cef.api.CefLaunchOptions.userAgent = DoomscrollConfig.get().userAgent == null ? "" : DoomscrollConfig.get().userAgent;
		applyLoginMode(DoomscrollConfig.get().tvLogin); // giris modu acik kaldiysa baslik kimligi de acik baslar
		CefService.initialize();

		BlockEntityRendererRegistry.register(Doomscroll.SCREEN_BE_TYPE, ScreenBlockEntityRenderer::new);
		SubtitleHud.register();

		// Elde tutulan tablet: ozel item renderer'i kaydet (tarayiciyi item yuzeyine cizer)
		com.doomscroll.client.mixin.SpecialModelRenderersAccessor.doomscroll$idMapper()
				.put(TabletSpecialRenderer.ID, TabletSpecialRenderer.Unbaked.MAP_CODEC);

		Doomscroll.screenOpener = pos -> {};
		Doomscroll.playbackToggler = Browsers::togglePlayback;
		Doomscroll.remoteOpener = () -> Minecraft.getInstance().setScreenAndShow(new RemoteScreen());
		Doomscroll.tabletOpener = () -> Minecraft.getInstance().setScreenAndShow(new TabletScreen());

		// Ekran blogu kirilinca: baska canli ekran yoksa tarayiciyi aninda kapat
		Doomscroll.screenRemoved = pos -> {
			ScreenTracker.forget(pos);
			ScreenBrowsers.removed(pos);
			var lvl = Minecraft.getInstance().level;
			boolean others = lvl != null && ScreenTracker.hasOtherLive(pos, lvl);
			Doomscroll.LOGGER.info("ekran kaldirildi {} — baska canli ekran: {}", pos, others);
			if (!others) {
				Browsers.close();
				DirectControl.reset();
			}
		};

		// Onceki oturumdan kalan gecici videolar
		LinkParty.wipeCache();

		if (SelfTest.enabled()) {
			SelfTest.register();
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (client.player != null && !DoomscrollConfig.get().welcomeShown) {
				DoomscrollConfig.get().welcomeShown = true;
				DoomscrollConfig.save();
				client.player.sendSystemMessage(Component.translatable("message.doomscroll.welcome"));
			}
			if (DoomscrollConfig.get().tvLogin && client.player != null) {
				client.player.sendSystemMessage(Component.translatable("message.doomscroll.tv_login_notice"));
			}
		});
		// Kontrolcunun video konumu (izleyiciler hizalanir)
		ClientPlayNetworking.registerGlobalReceiver(com.doomscroll.net.ScreenTimeBroadcast.TYPE, (payload, ctx) -> ctx.client().execute(() ->
				ScreenBrowsers.applyRemoteTime(payload.pos(), payload.time(), payload.duration(), payload.paused())));
		// Sunucu adres politikasi (girise ve her yonetici degisikligine gelir)
		ClientPlayNetworking.registerGlobalReceiver(com.doomscroll.net.ServerPolicyBroadcast.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
			ServerPolicy.apply(payload);
			Browsers.onPolicyChanged();
		}));
		// Sunucu bildirimi (kilit reddi vb.)
		ClientPlayNetworking.registerGlobalReceiver(com.doomscroll.net.ScreenNoticePayload.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
			if (ctx.client().player != null && !payload.text().getString().isEmpty()) {
				ctx.client().player.sendOverlayMessage(payload.text());
			}
			if (payload.code() == com.doomscroll.net.ScreenNoticePayload.DENIED || payload.code() == com.doomscroll.net.ScreenNoticePayload.BLOCKED) {
				ScreenBrowsers.noteDenied(payload.pos());
				ScreenBrowsers.forceResync(payload.pos());
			}
		}));
		// Yayin parcalari ve yayinci komutlari
		ClientPlayNetworking.registerGlobalReceiver(com.doomscroll.net.BroadcastChunkBroadcast.TYPE, (payload, ctx) -> ctx.client().execute(() -> Broadcast.onChunk(payload)));
		ClientPlayNetworking.registerGlobalReceiver(com.doomscroll.net.BroadcastControlBroadcast.TYPE, (payload, ctx) -> ctx.client().execute(() -> Broadcast.onControl(payload)));
		// Baskalarinin isaretcisi (ekrandaki imlec)
		ClientPlayNetworking.registerGlobalReceiver(com.doomscroll.net.ScreenPointerBroadcast.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
			if (ctx.client().player == null) {
				return;
			}
			if (payload.player().equals(ctx.client().player.getUUID())) {
				Pointers.noteEcho();
			} else {
				Pointers.apply(payload);
			}
		}));
		// Baskalarinin tablet durumu
		ClientPlayNetworking.registerGlobalReceiver(TabletStateBroadcast.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
			if (ctx.client().player == null || payload.player().equals(ctx.client().player.getUUID())) {
				return;
			}
			RemoteTablets.applyState(payload.player(), payload.url(), payload.portrait(), payload.volume());
		}));
		ClientPlayNetworking.registerGlobalReceiver(com.doomscroll.net.TabletTimeBroadcast.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
			if (ctx.client().player == null || payload.player().equals(ctx.client().player.getUUID())) {
				return;
			}
			RemoteTablets.applyTime(payload.player(), payload.time(), payload.duration(), payload.paused());
		}));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ServerPolicy.reset();
			Browsers.onPolicyChanged();
			Pointers.clear();
			ScreenQueue.clearAll();
			Broadcast.reset();
			RemoteTablets.clear();
			ScreenSync.clear();
			BrowserAudio.stopAll();
			Browsers.close();
			Browsers.closeTablet();
			DirectControl.reset();
			LinkParty.wipeCache();
		});
		// Oyun kapanirken tarayicilari MCEF'ten once kapat (kapanis takilmasini azaltir) ve videolari sil
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			Browsers.close();
			Browsers.closeTablet();
			LinkParty.wipeCache();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (pendingScreen != null && client.gui.screen() == null && client.player != null) {
				var sup = pendingScreen;
				pendingScreen = null;
				client.setScreenAndShow(sup.get());
			}
			DirectControl.tick(client);
			tickPortraitKey(client);
			tickTabletHeld(client);
			BrowserAudio.tick(client);
			RemoteTablets.tick(client);
			ScreenSync.tick(client);
			tickTabletState(client);
			if (++autoTick % 40 == 0) {
				Browsers.tickAutoNext();
			}
			if (client.player != null && ++tickCounter % 10 == 0) {
				Browsers.updateVolume(client.player.position());
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> aliases(dispatcher.register(
				ClientCommands.literal("ds")
						.executes(c -> {
							c.getSource().sendFeedback(help());
							return 1;
						})
						.then(ClientCommands.literal("yardim").executes(c -> {
							c.getSource().sendFeedback(help());
							return 1;
						}))
						.then(ClientCommands.literal("res").then(ClientCommands.argument("deger", StringArgumentType.greedyString()).executes(c -> {
							String v = StringArgumentType.getString(c, "deger");
							if (!DoomscrollConfig.get().setResolution(v)) {
								c.getSource().sendError(Component.translatable("command.doomscroll.res_invalid"));
								return 0;
							}
							Browsers.applyScreenResolution();
							c.getSource().sendFeedback(Component.translatable("command.doomscroll.res_set", DoomscrollConfig.get().resolutionLabel()));
							return 1;
						})))
						.then(ClientCommands.literal("auto").executes(c -> {
							DoomscrollConfig.get().autoScroll = !DoomscrollConfig.get().autoScroll;
							DoomscrollConfig.save();
							c.getSource().sendFeedback(Component.translatable("command.doomscroll.auto_next", onOff(DoomscrollConfig.get().autoScroll)));
							return 1;
						}))
						.then(ClientCommands.literal("faketablet").then(ClientCommands.argument("url", StringArgumentType.greedyString()).executes(c -> {
							String u = StringArgumentType.getString(c, "url").trim();
							RemoteTablets.DEBUG_FAKE.add(RemoteTablets.FAKE_UUID);
							RemoteTablets.applyState(RemoteTablets.FAKE_UUID, u, false, 1f);
							c.getSource().sendFeedback(Component.translatable("command.doomscroll.fake_tablet", RemoteTablets.FAKE_UUID, u));
							return 1;
						})))
						.then(ClientCommands.literal("ekranlar").executes(c -> {
							c.getSource().sendFeedback(Component.literal(screenList()));
							return 1;
						}))
						.then(ClientCommands.literal("kontrol")
								.then(ClientCommands.literal("al").executes(c -> control(c.getSource(), com.doomscroll.net.ScreenControlPayload.TAKE)))
								.then(ClientCommands.literal("birak").executes(c -> control(c.getSource(), com.doomscroll.net.ScreenControlPayload.RELEASE)))
								.then(ClientCommands.literal("kilit").executes(c -> control(c.getSource(), com.doomscroll.net.ScreenControlPayload.TOGGLE_LOCK))))
						.then(ClientCommands.literal("senkron").executes(c -> {
							DoomscrollConfig.get().syncPlayback = !DoomscrollConfig.get().syncPlayback;
							DoomscrollConfig.save();
							c.getSource().sendFeedback(Component.translatable("command.doomscroll.sync", onOff(DoomscrollConfig.get().syncPlayback)));
							return 1;
						}))
						.then(ClientCommands.literal("kanal")
								.then(ClientCommands.literal("liste").executes(c -> {
									StringBuilder sb = new StringBuilder("[doomscroll] Kanallar:");
									for (Channels.Channel ch : Channels.list()) sb.append("\n  ").append(ch.name()).append("  ").append(ch.url());
									c.getSource().sendFeedback(Component.literal(sb.toString()));
									return 1;
								}))
								.then(ClientCommands.literal("ekle").then(ClientCommands.argument("ad", StringArgumentType.string()).executes(c -> {
									String ad = StringArgumentType.getString(c, "ad");
									String url = Browsers.currentUrl();
									if (url.isEmpty() || !Channels.add(ad, url)) {
										c.getSource().sendError(Component.translatable("command.doomscroll.channel.no_page"));
										return 0;
									}
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.channel.added_current", ad, url));
									return 1;
								}).then(ClientCommands.argument("url", StringArgumentType.greedyString()).executes(c -> {
									String ad = StringArgumentType.getString(c, "ad");
									String url = StringArgumentType.getString(c, "url").trim();
									if (!Channels.add(ad, url)) {
										c.getSource().sendError(Component.translatable("command.doomscroll.invalid_url"));
										return 0;
									}
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.channel.added", ad, url));
									return 1;
								}))))
								.then(ClientCommands.literal("sil").then(ClientCommands.argument("ad", StringArgumentType.greedyString()).executes(c -> {
									String ad = StringArgumentType.getString(c, "ad").trim();
									if (!Channels.remove(ad)) {
										c.getSource().sendError(Component.translatable("command.doomscroll.channel.not_found", ad));
										return 0;
									}
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.channel.removed", ad));
									return 1;
								}))))
						.then(ClientCommands.literal("fps").then(ClientCommands.argument("deger", com.mojang.brigadier.arguments.IntegerArgumentType.integer(10, 60)).executes(c -> {
							int f = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "deger");
							DoomscrollConfig.get().browserFps = f;
							DoomscrollConfig.save();
							com.doomscroll.cef.api.CefLaunchOptions.frameRate = f;
							c.getSource().sendFeedback(Component.translatable("command.doomscroll.fps_set", f));
							return 1;
						})))
						.then(ClientCommands.literal("boost").then(ClientCommands.argument("kazanc", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.5f, 6f)).executes(c -> {
							float g = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(c, "kazanc");
							DoomscrollConfig.get().audioBoost = g;
							DoomscrollConfig.save();
							c.getSource().sendFeedback(Component.translatable("command.doomscroll.boost_set", String.format(java.util.Locale.ROOT, "%.1f", g)));
							return 1;
						})))
						.then(ClientCommands.literal("gecikme").then(ClientCommands.argument("profil", StringArgumentType.word()).executes(c -> {
							String p0 = StringArgumentType.getString(c, "profil").toLowerCase(java.util.Locale.ROOT)
									.replace('\u00fc', 'u').replace('\u015f', 's').replace('\u0131', 'i');
							String p = switch (p0) {
								case "low" -> "dusuk";
								case "high" -> "yuksek";
								default -> p0;
							};
							if (!java.util.Set.of("dusuk", "normal", "yuksek").contains(p)) {
								c.getSource().sendError(Component.translatable("command.doomscroll.latency_usage"));
								return 0;
							}
							DoomscrollConfig.get().audioLatency = p;
							DoomscrollConfig.save();
							com.doomscroll.cef.api.CefAudioDefaults.targetBacklogMs = DoomscrollConfig.get().audioTargetBacklogMs();
							ScreenBrowsers.restartSounds();
							BrowserAudio.stopAll();
							c.getSource().sendFeedback(Component.translatable("command.doomscroll.latency_set", p,
									DoomscrollConfig.get().audioChunkMs(), DoomscrollConfig.get().audioTargetBacklogMs()));
							return 1;
						})))
						.then(ClientCommands.literal("reklam")
								.executes(c -> {
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.adblock_status", adBlockStatus()));
									return 1;
								})
								.then(ClientCommands.literal("ac").executes(c -> setAdBlock(c.getSource(), true)))
								.then(ClientCommands.literal("kapat").executes(c -> setAdBlock(c.getSource(), false))))
						.then(ClientCommands.literal("sira")
								.executes(c -> {
									c.getSource().sendFeedback(Component.literal(queueList()));
									return 1;
								})
								.then(ClientCommands.literal("liste").executes(c -> {
									c.getSource().sendFeedback(Component.literal(queueList()));
									return 1;
								}))
								.then(ClientCommands.literal("ekle").then(ClientCommands.argument("url", StringArgumentType.greedyString()).executes(c -> {
									net.minecraft.core.BlockPos a = ScreenBrowsers.activeAnchor();
									if (a == null) {
										c.getSource().sendError(Component.translatable("command.doomscroll.no_screen_near_short"));
										return 0;
									}
									if (!ScreenQueue.add(a, StringArgumentType.getString(c, "url").trim())) {
										c.getSource().sendError(Component.translatable("command.doomscroll.queue.invalid"));
										return 0;
									}
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.queue.added", ScreenQueue.size(a)));
									return 1;
								})))
								.then(ClientCommands.literal("sil").then(ClientCommands.argument("no", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 50)).executes(c -> {
									boolean ok = ScreenQueue.remove(ScreenBrowsers.activeAnchor(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "no"));
									c.getSource().sendFeedback(Component.translatable(ok ? "command.doomscroll.queue.removed" : "command.doomscroll.queue.no_such"));
									return ok ? 1 : 0;
								})))
								.then(ClientCommands.literal("temizle").executes(c -> {
									ScreenQueue.clear(ScreenBrowsers.activeAnchor());
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.queue.cleared"));
									return 1;
								}))
								.then(ClientCommands.literal("atla").executes(c -> {
									boolean ok = ScreenQueue.next(ScreenBrowsers.activeAnchor());
									c.getSource().sendFeedback(Component.translatable(ok ? "command.doomscroll.queue.skipping" : "command.doomscroll.queue.is_empty"));
									return ok ? 1 : 0;
								})))
						.then(ClientCommands.literal("yayin")
								.executes(c -> {
									ScreenBrowsers.Screen s = ScreenBrowsers.get(ScreenBrowsers.activeAnchor());
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.broadcast.status", Broadcast.label(s), Broadcast.debugInfo()));
									return 1;
								})
								.then(ClientCommands.literal("ac").executes(c -> {
									net.minecraft.core.BlockPos a = ScreenBrowsers.activeAnchor();
									if (a == null) {
										c.getSource().sendError(Component.translatable("command.doomscroll.no_screen_near_short"));
										return 0;
									}
									Broadcast.requestStart(a);
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.broadcast.requested", DoomscrollConfig.get().broadcastWidth, DoomscrollConfig.get().broadcastKbps));
									return 1;
								}))
								.then(ClientCommands.literal("kapat").executes(c -> {
									Broadcast.requestStop(ScreenBrowsers.activeAnchor());
									return 1;
								}))
								.then(ClientCommands.literal("kalite").then(ClientCommands.argument("seviye", StringArgumentType.word()).executes(c -> {
									String v = StringArgumentType.getString(c, "seviye");
									if (!Broadcast.applyPreset(v)) {
										c.getSource().sendError(Component.translatable("command.doomscroll.quality_usage"));
										return 0;
									}
									DoomscrollConfig cfg = DoomscrollConfig.get();
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.broadcast.quality_set", Broadcast.presetLabel(), cfg.broadcastWidth, cfg.broadcastWidth * 9 / 16, cfg.broadcastFps, cfg.broadcastKbps));
									return 1;
								}))))
						.then(ClientCommands.literal("sponsor").executes(c -> {
							DoomscrollConfig cfg = DoomscrollConfig.get();
							cfg.sponsorBlock = !cfg.sponsorBlock;
							DoomscrollConfig.save();
							ScreenBrowsers.refreshSponsorBlock();
							c.getSource().sendFeedback(Component.translatable("command.doomscroll.sponsorblock_status", onOff(cfg.sponsorBlock), SponsorBlock.skippedTotal()));
							return 1;
						}))
						.then(ClientCommands.literal("isaretci").executes(c -> {
							DoomscrollConfig cfg = DoomscrollConfig.get();
							cfg.pointer = !cfg.pointer;
							DoomscrollConfig.save();
							if (!cfg.pointer) Pointers.clear();
							c.getSource().sendFeedback(Component.translatable("command.doomscroll.pointer_status", onOff(cfg.pointer)));
							return 1;
						}))
						.then(ClientCommands.literal("redstone").executes(c -> {
							net.minecraft.core.BlockPos a = ScreenBrowsers.activeAnchor();
							if (a == null) {
								c.getSource().sendError(Component.translatable("command.doomscroll.no_screen_near_short"));
								return 0;
							}
							ScreenBrowsers.sendControl(a, com.doomscroll.net.ScreenControlPayload.TOGGLE_REDSTONE);
							return 1;
						}))
						.then(ClientCommands.literal("altyazi").executes(c -> {
							DoomscrollConfig.get().subtitles = !DoomscrollConfig.get().subtitles;
							DoomscrollConfig.save();
							c.getSource().sendFeedback(Component.translatable("command.doomscroll.subtitles_status", onOff(DoomscrollConfig.get().subtitles)));
							return 1;
						}))
						.then(ClientCommands.literal("isik")
								.executes(c -> {
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.glow_status", DoomscrollConfig.get().glowLabel(),
									Component.translatable(DoomscrollConfig.get().screenGlowSmooth
											? "command.doomscroll.glow_smooth_suffix" : "command.doomscroll.glow_blocky_suffix")));
									c.getSource().sendFeedback(Component.literal("  " + ScreenGlow.info(ScreenBrowsers.activeAnchor())));
									return 1;
								})
								.then(ClientCommands.literal("kapat").executes(c -> setGlow(c.getSource(), 0f)))
								.then(ClientCommands.literal("az").executes(c -> setGlow(c.getSource(), 0.5f)))
								.then(ClientCommands.literal("normal").executes(c -> setGlow(c.getSource(), 1.0f)))
								.then(ClientCommands.literal("cok").executes(c -> setGlow(c.getSource(), 1.8f)))
								.then(ClientCommands.literal("menzil").then(ClientCommands.argument("blok", com.mojang.brigadier.arguments.IntegerArgumentType.integer(2, 24)).executes(c -> {
									int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "blok");
									DoomscrollConfig.get().screenGlowRange = n;
									DoomscrollConfig.save();
									ScreenGlow.clear();
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.glow_range_set", n));
									return 1;
								})))
								.then(ClientCommands.literal("yumusak").executes(c -> {
									DoomscrollConfig cfg = DoomscrollConfig.get();
									cfg.screenGlowSmooth = !cfg.screenGlowSmooth;
									DoomscrollConfig.save();
									c.getSource().sendFeedback(Component.translatable("command.doomscroll.glow_smooth_status", onOff(cfg.screenGlowSmooth)));
									return 1;
								})))
						.then(ClientCommands.literal("popup").executes(c -> {
							String u = ScreenBrowsers.lastPopup();
							net.minecraft.core.BlockPos anchor = ScreenBrowsers.activeAnchor();
							if (!u.isEmpty() && anchor != null) {
								ScreenBrowsers.requestNavigate(anchor, u);
								c.getSource().sendFeedback(Component.translatable("command.doomscroll.popup_opening", u));
								return 1;
							}
							if (!Browsers.tabletLastPopup.isEmpty() && Browsers.getTabletIfPresent() != null) {
								Browsers.tabletNavigate(Browsers.tabletLastPopup);
								c.getSource().sendFeedback(Component.translatable("command.doomscroll.popup_opening_tablet", Browsers.tabletLastPopup));
								return 1;
							}
							c.getSource().sendError(Component.translatable("command.doomscroll.popup_none"));
							return 0;
						}))
						.then(ClientCommands.literal("rapor")
								.executes(c -> report(c.getSource(), ""))
								.then(ClientCommands.argument("not", StringArgumentType.greedyString())
										.executes(c -> report(c.getSource(), StringArgumentType.getString(c, "not")))))
						.then(ClientCommands.literal("perf").executes(c -> {
							c.getSource().sendFeedback(Component.literal(perfReport()));
							return 1;
						}))
						.then(ClientCommands.literal("sinema").executes(c -> {
							Browsers.toggleCinema();
							return 1;
						}))
						.then(ClientCommands.literal("ytgiris").executes(c -> {
							boolean on = toggleTvLogin();
							c.getSource().sendFeedback(Component.literal(tvLoginMessage(on)));
							return 1;
						}))
						.then(ClientCommands.literal("audiorate").then(ClientCommands.argument("hz", com.mojang.brigadier.arguments.IntegerArgumentType.integer(8000, 192000)).executes(c -> {
							int hz = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "hz");
							com.doomscroll.cef.api.CefAudioDefaults.sampleRate = hz;
							DoomscrollConfig.get().audioSampleRate = hz;
							DoomscrollConfig.save();
							c.getSource().sendFeedback(Component.translatable("command.doomscroll.audiorate_set", hz));
							return 1;
						})))
						.then(ClientCommands.literal("shorts").executes(c -> go(Browsers.URL_SHORTS)))
						.then(ClientCommands.literal("reels").executes(c -> go(Browsers.URL_REELS)))
						.then(ClientCommands.literal("tiktok").executes(c -> go(Browsers.URL_TIKTOK)))
						.then(ClientCommands.literal("go")
								.then(ClientCommands.argument("url", StringArgumentType.greedyString())
										.executes(c -> go(StringArgumentType.getString(c, "url")))))
						.then(ClientCommands.literal("play")
								.then(ClientCommands.argument("url", StringArgumentType.greedyString())
										.executes(c -> {
											LinkParty.play(StringArgumentType.getString(c, "url"));
											return 1;
										})))
						.then(ClientCommands.literal("debug").executes(c -> {
							Minecraft mc = Minecraft.getInstance();
							if (mc.player != null) {
								mc.player.sendSystemMessage(Component.literal("[doomscroll] " + Browsers.debugInfo()));
								mc.player.sendSystemMessage(Component.literal("[doomscroll] hit=" + DirectControl.currentHit()
										+ " enYakin=" + ScreenTracker.nearestAnchor(mc.player.position(), true)));
							}
							return 1;
						}))
						.then(ClientCommands.literal("clear").executes(c -> {
							LinkParty.clear();
							return 1;
						}))
						.then(ClientCommands.literal("pause").executes(c -> {
							Browsers.togglePlayback();
							return 1;
						}))
						.then(ClientCommands.literal("remote").executes(c -> {
							openLater(RemoteScreen::new);
							return 1;
						}))
						.then(ClientCommands.literal("tablet").executes(c -> {
							openLater(TabletScreen::new);
							return 1;
						}))
						.then(ClientCommands.literal("gui").executes(c -> {
							openLater(BrowserScreen::new);
							return 1;
						}))
		)));

		Doomscroll.LOGGER.info("doomscroll client hazir");
	}

	/**
	 * Komut agacina Ingilizce takma adlar ekler: /ds screens = /ds ekranlar.
	 * Takma dugum, kaynak dugumun komutunu ve cocuklarini paylasir (kopya yok).
	 */
	private static com.mojang.brigadier.tree.LiteralCommandNode<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> aliases(
			com.mojang.brigadier.tree.LiteralCommandNode<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> root) {
		// Once derin dugumler: ust dugum kopyalanirken cocuklarini oldugu gibi paylasir.
		alias(root, "kontrol", "al", "take");
		alias(root, "kontrol", "birak", "release");
		alias(root, "kontrol", "kilit", "lock");
		alias(root, "kanal", "liste", "list");
		alias(root, "kanal", "ekle", "add");
		alias(root, "kanal", "sil", "remove");
		alias(root, "sira", "ekle", "add");
		alias(root, "sira", "liste", "list");
		alias(root, "sira", "sil", "remove");
		alias(root, "sira", "temizle", "clear");
		alias(root, "sira", "atla", "skip");
		alias(root, "yayin", "ac", "on");
		alias(root, "yayin", "kapat", "off");
		alias(root, "yayin", "kalite", "quality");
		alias(root, "isik", "kapat", "off");
		alias(root, "isik", "az", "low");
		alias(root, "isik", "cok", "high");
		alias(root, "isik", "yumusak", "smooth");
		alias(root, "isik", "menzil", "range");
		alias(root, "reklam", "ac", "on");
		alias(root, "reklam", "kapat", "off");

		alias(root, "yardim", "help");
		alias(root, "ekranlar", "screens");
		alias(root, "kontrol", "control");
		alias(root, "kanal", "channel");
		alias(root, "senkron", "sync");
		alias(root, "sinema", "cinema");
		alias(root, "sira", "queue");
		alias(root, "yayin", "broadcast");
		alias(root, "isik", "light");
		alias(root, "reklam", "adblock");
		alias(root, "isaretci", "pointer");
		alias(root, "altyazi", "captions");
		alias(root, "gecikme", "latency");
		alias(root, "ytgiris", "ytlogin");
		alias(root, "rapor", "report");
		return root;
	}

	private static void alias(com.mojang.brigadier.tree.CommandNode<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> root, String parent, String from, String to) {
		com.mojang.brigadier.tree.CommandNode<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> p = root.getChild(parent);
		if (p != null) {
			alias(p, from, to);
		}
	}

	private static void alias(com.mojang.brigadier.tree.CommandNode<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> parent, String from, String to) {
		com.mojang.brigadier.tree.CommandNode<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> src = parent.getChild(from);
		if (src == null || parent.getChild(to) != null) {
			Doomscroll.LOGGER.warn("komut takma adi atlandi: {} -> {}", from, to);
			return;
		}
		com.mojang.brigadier.tree.LiteralCommandNode<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> node =
				com.mojang.brigadier.builder.LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal(to)
						.requires(src.getRequirement())
						.executes(src.getCommand())
						.build();
		for (com.mojang.brigadier.tree.CommandNode<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> child : src.getChildren()) {
			node.addChild(child);
		}
		parent.addChild(node);
	}

	/** /ds kontrol al|birak|kilit: bakilan / kumandanin sectigi / en yakin ekran icin. */
	private static int control(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src, int action) {
		net.minecraft.core.BlockPos anchor = ScreenBrowsers.activeAnchor();
		if (anchor == null) {
			src.sendError(Component.translatable("command.doomscroll.no_screen"));
			return 0;
		}
		ScreenBrowsers.sendControl(anchor, action);
		src.sendFeedback(Component.literal(switch (action) {
			case com.doomscroll.net.ScreenControlPayload.TAKE -> Lang.tr("command.doomscroll.control.take", anchor.toShortString());
			case com.doomscroll.net.ScreenControlPayload.RELEASE -> Lang.tr("command.doomscroll.control.release", anchor.toShortString());
			default -> Lang.tr("command.doomscroll.control.lock", anchor.toShortString());
		}));
		return 1;
	}

	/** Komut geri bildirimlerinde acik/kapali. */
	private static Component onOff(boolean v) {
		return Component.translatable(v ? "gui.doomscroll.enabled" : "gui.doomscroll.disabled");
	}

	/** /ds rapor: baktigin ekrani yoneticilere bildir. Adres ve sahip sunucuda okunur. */
	private static int report(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src, String note) {
		net.minecraft.core.BlockPos anchor = ScreenBrowsers.activeAnchor();
		if (anchor == null) {
			src.sendError(Component.translatable("command.doomscroll.no_screen"));
			return 0;
		}
		ClientPlayNetworking.send(new com.doomscroll.net.ScreenReportPayload(anchor,
				note.length() > 200 ? note.substring(0, 200) : note));
		return 1;
	}

	private static String adBlockStatus() {
		var init = CefService.initialize();
		String cef = init.isDone() ? init.getFuture().join().adBlockInfo() : Lang.tr("command.doomscroll.chromium_not_ready");
		return cef + Lang.tr("command.doomscroll.yt_skipper",
				Lang.tr(DoomscrollConfig.get().adBlock ? "gui.doomscroll.enabled" : "gui.doomscroll.disabled"));
	}

	private static int setGlow(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src, float v) {
		DoomscrollConfig.get().screenGlow = v;
		DoomscrollConfig.save();
		ScreenGlow.clear();
		src.sendFeedback(Component.translatable("command.doomscroll.glow_set", DoomscrollConfig.glowLabelOf(v)));
		return 1;
	}

	private static int setAdBlock(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src, boolean on) {
		DoomscrollConfig.get().adBlock = on;
		DoomscrollConfig.save();
		com.doomscroll.cef.api.CefLaunchOptions.adBlock = on;
		src.sendFeedback(Component.translatable("command.doomscroll.adblock_toggled",
				Component.translatable(on ? "command.doomscroll.turned_on" : "command.doomscroll.turned_off"), adBlockStatus()));
		return 1;
	}

	/** /ds perf: CEF pompa maliyeti + tarayici basina boyama/yukleme istatistigi (son cagridan bu yana). */
	private static String perfReport() {
		StringBuilder sb = new StringBuilder(Lang.tr("command.doomscroll.perf.title"));
		var init = CefService.initialize();
		if (!init.isDone()) {
			return sb.append(' ').append(Lang.tr("command.doomscroll.chromium_not_ready")).toString();
		}
		sb.append("\n  ").append(init.getFuture().join().perfInfo());
		for (ScreenBrowsers.Screen s : ScreenBrowsers.liveScreens()) {
			sb.append("\n  ").append(Lang.tr("command.doomscroll.perf.screen")).append(' ').append(s.pos.toShortString()).append(": ").append(s.browser.perfInfo())
					.append(Lang.tr("command.doomscroll.perf.audio_delay")).append(s.browser.audioBacklogMs() + 4 * DoomscrollConfig.get().audioChunkMs() + 50).append(" ms");
		}
		var t = Browsers.getTabletIfPresent();
		if (t != null) {
			sb.append("\n  tablet: ").append(t.perfInfo());
		}
		sb.append("\n  ").append(Lang.tr("command.doomscroll.perf.adblock")).append(": ").append(adBlockStatus());
		sb.append("\n  ").append(Lang.tr("command.doomscroll.perf.settings")).append(": ").append(DoomscrollConfig.get().browserFps).append(" fps, ").append(DoomscrollConfig.get().resolutionLabel())
				.append(" — /ds fps <10-60>, /ds res 720p|1080p");
		return sb.toString();
	}

	/** /ds ekranlar: yakindaki ekranlar (uzaklik, durum, sahip, kontrol, site). */
	private static String queueList() {
		net.minecraft.core.BlockPos a = ScreenBrowsers.activeAnchor();
		java.util.List<String> q = ScreenQueue.list(a);
		if (a == null) return Lang.tr("command.doomscroll.no_screen_near");
		if (q.isEmpty()) return Lang.tr("command.doomscroll.queue.empty_hint");
		StringBuilder sb = new StringBuilder(Lang.tr("command.doomscroll.queue.title", q.size()));
		for (int i = 0; i < q.size(); i++) sb.append("\n  ").append(i + 1).append(". ").append(q.get(i));
		return sb.toString();
	}

	private static String screenList() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return "";
		}
		java.util.List<String> rows = new java.util.ArrayList<>();
		for (ScreenTracker.ScreenInfo info : ScreenTracker.all()) {
			if (!(mc.level.getBlockEntity(info.pos()) instanceof com.doomscroll.ScreenBlockEntity be) || !be.isAnchor()) {
				continue;
			}
			double d = Math.sqrt(mc.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(info.pos())));
			ScreenBrowsers.Screen s = ScreenBrowsers.get(info.pos());
			String url = s != null ? s.currentUrl() : be.getUrl();
			String ctl = s != null ? s.controllerLabel() : (be.getController() == null ? Lang.tr("gui.doomscroll.none") : be.getControllerName());
			String extra = (be.getBroadcaster() != null ? " 📡" + be.getBroadcasterName() : "") + (Pointers.at(info.pos()).isEmpty() ? "" : " 👀" + Pointers.at(info.pos()).size());
			rows.add(String.format(java.util.Locale.ROOT, "%5.0fm  %s  %dx%d  %s  " + Lang.tr("command.doomscroll.list.owner") + ":%s  "
							+ Lang.tr("command.doomscroll.list.control") + ":%s%s%s  %s",
					d, info.pos().toShortString(), be.getWidth(), be.getHeight(), Lang.tr(be.isOn() ? "command.doomscroll.list.on" : "command.doomscroll.list.off"),
					be.getOwnerName().isEmpty() ? "-" : be.getOwnerName(), ctl, be.isLocked() ? " (" + Lang.tr("command.doomscroll.list.locked") + ")" : "", extra,
					ScreenBrowsers.pageTitle(info.pos()).isEmpty() ? Channels.nameFor(url, RemoteScreen.siteName(url)) : RemoteScreen.shortName(ScreenBrowsers.pageTitle(info.pos()), 40)));
		}
		if (rows.isEmpty()) {
			return Lang.tr("command.doomscroll.list.none");
		}
		java.util.Collections.sort(rows);
		return Lang.tr("command.doomscroll.list.title") + "\n" + String.join("\n", rows);
	}

	private static int go(String url) {
		if (ScreenBrowsers.activeAnchor() == null) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.translatable("command.doomscroll.no_screen"));
			}
			return 0;
		}
		Browsers.navigate(url);
		return 1;
	}
}
