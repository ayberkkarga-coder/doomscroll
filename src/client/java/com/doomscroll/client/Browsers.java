package com.doomscroll.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.doomscroll.cef.api.CefService;
import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;

/**
 * Tek global tarayici (MVP): tum ekranlar ayni tarayiciyi gosterir.
 */
public final class Browsers {
	public static final String URL_SHORTS = "https://www.youtube.com/shorts";
	public static final String URL_REELS = "https://www.instagram.com/reels/";
	public static final String URL_TIKTOK = "https://www.tiktok.com/";
	/** Ana menuler: ekran (doomscroll://home/screen) ve tablet (doomscroll://home/tablet); HomePages uretir. */
	public static final String HOME_URL = HomePages.SCREEN;
	public static final String TABLET_HOME_URL = HomePages.TABLET;
	public static final String URL_YT_LOGIN = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/";

	/** Ekran tarayicisinin acilis sayfasi: o ekranin ana menusu (sira listesi ekrana ait). */
	public static String homeUrlFor(@Nullable BlockPos anchor) {
		return HomePages.screenUrl(anchor);
	}

	/** Senkron: tarayici henuz yokken sunucudan gelen ekran adresi; tarayici dogrudan bununla acilir (ana sayfa yarisi olmasin). */
	private static String initialScreenUrl = "";
	public static void setInitialScreenUrl(String url) { initialScreenUrl = url == null ? "" : url; }

	/** Ekran tarayicisi cozunurlugu (config/doomscroll.json). */
	public static int screenWidth() { return DoomscrollConfig.get().screenWidth; }
	public static int screenHeight() { return DoomscrollConfig.get().screenHeight; }

	/** Ayar degisince calisan tarayiciyi aninda yeniden boyutlandirir ve kaydeder. */
	public static void applyScreenResolution() {
		DoomscrollConfig.save();
		ScreenBrowsers.resizeAll();
		ScreenBrowsers.refreshQuality(); // YouTube kalite siniri cozunurlukten turer
	}

	private static final double FADE_START = 6.0;
	private static final double FADE_END = 30.0;

	@Nullable
	private static CefBrowserView browser;
	@Nullable
	private static GpuSampler sampler;
	@Nullable
	private static Vec3 lastScreenPos;
	private static float lastAppliedVolume = -1f;


	private Browsers() {}

	private static long lastInitNoticeNanos = 0L;

	@Nullable
	public static CefBrowserView getOrCreate() {
		return ScreenBrowsers.activeBrowser(); // ekran tarayicilari renderer'da acilir; burada aktif olan doner
	}

	// ---- tablet: ekrandan bagimsiz ikinci tarayici ----

	/** Yatay: WD pad yuzeyi orani (27.65:14 ~ 1.98). Dik (telefon): 9:16. */
	private static boolean tabletPortrait = false;
	public static boolean isTabletPortrait() { return tabletPortrait; }
	public static int tabletWidth() { return tabletPortrait ? 720 : 1280; }
	public static int tabletHeight() { return tabletPortrait ? 1280 : 644; }

	/** R tusu: dik/yatay. Tarayici varsa aninda yeniden boyutlandirilir. */
	public static void setTabletPortrait(boolean portrait) {
		tabletPortrait = portrait;
		if (tablet != null) {
			tablet.resize(tabletWidth(), tabletHeight());
		}
	}
	@Nullable
	private static CefBrowserView tablet;

	@Nullable
	public static CefBrowserView getOrCreateTablet() {
		if (tablet == null) {
			var init = CefService.initialize();
			if (!init.isDone()) {
				return null;
			}
			try {
				// Tablet senin: kalici profil (girislerin kalsin).
				tablet = init.getFuture().join().createBrowser(TABLET_HOME_URL, false, false);
				tablet.resize(tabletWidth(), tabletHeight());
				final CefBrowserView tb = tablet;
				tb.setMessageListener(msg -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
					if (msg.contains("fsArmed") && tablet == tb) {
						armedClick(tb);
						return;
					}
					if (msg.contains("\"popup\"")) {
						tabletLastPopup = popupUrl(msg);
						popupNotice(tabletLastPopup);
						return;
					}
					onTabletMessage(msg);
				}));
			} catch (Exception e) {
				com.doomscroll.Doomscroll.LOGGER.error("tablet tarayicisi olusturulamadi", e);
				return null;
			}
		}
		return tablet;
	}

	/** Tablet tarayicisinda son engellenen popup adresi (/ds popup ile acilir). */
	public static String tabletLastPopup = "";
	private static long lastPopupNoticeMs = 0L;

	static String popupUrl(String msg) {
		try {
			return com.google.gson.JsonParser.parseString(msg).getAsJsonObject().get("popup").getAsString();
		} catch (Exception e) {
			return "";
		}
	}

	/** Engellenen popup bildirimi (10 sn'de en fazla bir). */
	static void popupNotice(String url) {
		long now = System.currentTimeMillis();
		if (url.isEmpty() || now - lastPopupNoticeMs < 10_000L) {
			return;
		}
		lastPopupNoticeMs = now;
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.player != null) {
			String host = url.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
			mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.doomscroll.popup_blocked", host));
		}
	}

	@Nullable
	public static CefBrowserView getTabletIfPresent() {
		return tablet;
	}

	public static void closeTablet() {
		if (tablet != null) {
			try {
				tabletJs("document.querySelectorAll('video,audio').forEach(function(m){ m.pause(); m.src = ''; });");
				tablet.getCefBrowser().stopLoad();
				tablet.getCefBrowser().loadURL("about:blank");
			} catch (Exception ignored) {
			}
			try {
				tablet.close();
			} catch (Exception ignored) {
			}
			tablet = null;
		}
	}

	/**
	 * Tablet cebe girince ses kesilsin, cikinca devam etsin. Sayfanin kendi ses ayarina (YouTube kaydiricisi,
	 * sessiz tusu) dokunulmaz: tablet kisisel, kullanicinin sayfada kistigi ses kisik kalmali.
	 */
	public static void tabletVisible(boolean visible) {
		if (tablet == null) {
			return;
		}
		tablet.setFrameRate(visible ? DoomscrollConfig.get().browserFps : 8);
		if (visible) {
			tabletJs("document.querySelectorAll('video,audio').forEach(function(m){ if (m.paused) { m.play().catch(function(){}); } });");
		} else if (!Broadcast.testTabletViewer) { // duman testinde tablet yayin alicisi: durdurma
			tabletJs("document.querySelectorAll('video,audio').forEach(function(m){ m.pause(); });");
		}
	}

	public static void tabletNavigate(String input) {
		String url = input.trim();
		if (url.isEmpty()) {
			return;
		}
		CefBrowserView b = getOrCreateTablet();
		if (b == null) {
			return;
		}
		if (!url.contains("://")) {
			url = url.contains(".") && !url.contains(" ") ? "https://" + url : searchUrl(url);
		}
		b.getCefBrowser().loadURL(url);
	}

	public static void goBackTablet() {
		CefBrowserView b = tablet;
		if (b != null && b.getCefBrowser().canGoBack()) {
			b.getCefBrowser().goBack();
		}
	}

	public static void goForwardTablet() {
		CefBrowserView b = tablet;
		if (b != null && b.getCefBrowser().canGoForward()) {
			b.getCefBrowser().goForward();
		}
	}

	public static void reloadTablet() {
		CefBrowserView b = tablet;
		if (b != null) {
			b.getCefBrowser().reload();
		}
	}

	private static String tabletReportUrl = "";
	private static double tabletTime = -1;
	private static double tabletDuration = 0;
	private static boolean tabletPaused = true;
	private static long tabletTimeStampMs = 0L;

	/** Tabletteki videonun konumu (sn), rapor yoksa -1. */
	public static double tabletTime() {
		return tabletTime;
	}

	public static double tabletDuration() { return tabletDuration; }
	public static boolean tabletPaused() { return tabletPaused; }

	/** Konum, son rapordan bu yana gecen sureyle duzeltilmis; rapor bayatsa -1. */
	public static double tabletNow() {
		if (tabletDuration <= 0 || tabletTime < 0 || System.currentTimeMillis() - tabletTimeStampMs > 3000L) {
			return -1;
		}
		return tabletPaused ? tabletTime : tabletTime + (System.currentTimeMillis() - tabletTimeStampMs) / 1000.0;
	}
	private static String tabletTitle = "";
	private static String tabletSbId = "";

	/** Tablet sayfa raporu (sure/adres/baslik, SponsorBlock atlama). Altyazi ve odak mesajlari tablette kullanilmaz. */
	private static void onTabletMessage(String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			if (o.has("home")) {
				HomePages.onTabletCommand(o); // ana menu komutu (yer imi cikar, gecmisi temizle)
				return;
			}
			if (o.has("sb")) {
				ScreenBrowsers.sponsorSkipped(o.get("sb").getAsInt());
				return;
			}
			if (o.has("bcinfo")) {
				Broadcast.onTabletReady();
				return;
			}
			if (o.has("bct")) {
				tabletTime = o.get("bct").getAsDouble();
				return;
			}
			if (!o.has("t")) {
				return;
			}
			tabletTime = o.get("t").getAsDouble();
			tabletDuration = o.has("d") ? o.get("d").getAsDouble() : 0;
			tabletPaused = o.has("p") && o.get("p").getAsInt() == 1;
			tabletTimeStampMs = System.currentTimeMillis();
			if (o.has("ti")) {
				tabletTitle = o.get("ti").getAsString();
			}
			String u = o.has("u") ? o.get("u").getAsString() : "";
			if (!u.equals(tabletReportUrl)) {
				tabletReportUrl = u;
				tabletUrlChanged();
				TabletBookmarks.noteVisit(u, tabletTitle()); // gecmis
			} else if (o.has("ti")) {
				TabletBookmarks.updateTitle(u, tabletTitle()); // baslik sonradan geldi (SPA)
			}
			if (o.has("ti") && !tabletTitle().isBlank()) {
				PageTitles.remember(u, tabletTitle());
			}
		} catch (Exception ignored) {
		}
	}

	private static void tabletUrlChanged() {
		String id = SponsorBlock.videoId(tabletReportUrl);
		if (id == null) {
			tabletSbId = "";
			return;
		}
		if (!DoomscrollConfig.get().sponsorBlock || id.equals(tabletSbId)) {
			return;
		}
		tabletSbId = id;
		SponsorBlock.request(id, segs -> {
			if (tablet != null && id.equals(SponsorBlock.videoId(tabletReportUrl))) {
				tabletJs(SponsorBlock.injectJs(id, segs));
			}
		});
	}

	/** Tabletteki sayfanin basligi (" - YouTube" eki atilmis) ya da "". */
	public static String tabletTitle() {
		String t = tabletTitle == null ? "" : tabletTitle.trim();
		int dash = t.lastIndexOf(" - ");
		return dash > 8 ? t.substring(0, dash) : t;
	}

	public static String tabletUrl() {
		CefBrowserView b = tablet;
		if (b == null) {
			return "";
		}
		String u = b.getCefBrowser().getURL();
		return u == null ? "" : u;
	}

	private static void tabletJs(String code) {
		CefBrowserView b = tablet;
		if (b != null) {
			b.getCefBrowser().executeJavaScript(code, b.getCefBrowser().getURL(), 0);
		}
	}

	public static boolean isIdlePaused() { return !ScreenBrowsers.activeOn(); }
	@Nullable public static Vec3 lastScreenPos() { return ScreenBrowsers.activeCenter(); }

	/** Shorts / Reels / TikTok gibi dikey kisa video sayfasi mi? (tekerlek -> ok tusu, otomatik gecis) */
	public static boolean isShortFormPage(String url) {
		if (url == null) return false;
		String u = url.toLowerCase();
		return u.contains("youtube.com/shorts") || u.contains("instagram.com/reels") || u.contains("instagram.com/reel/") || u.contains("tiktok.com");
	}

	/**
	 * Sayfaya idempotent izleyici kurar: video bitince ya da basa sarinca (Shorts loop) console'a
	 * "__DS_NEXT__" yazar; mcef-codec bunu yakalayip sayfaya guvenilir bir ArrowDown tusu gonderir.
	 */
	/**
	 * Sayfa ici gezinme: sitenin kendi sonraki/onceki dugmesine tiklar (YouTube Shorts, TikTok, Instagram),
	 * dugme yoksa klavye olayi gonderir. Tarayiciya tus basmaktan cok daha guvenilir.
	 */
	private static final String NAV_JS =
			"if(!window.__dsNext){"
			+ "window.__dsClick=function(sel){var b=document.querySelector(sel);if(b){b.click();return true;}return false;};"
			+ "window.__dsKey=function(k,c){var ev=new KeyboardEvent('keydown',{key:k,code:k,keyCode:c,which:c,bubbles:true,cancelable:true});"
			+ "(document.activeElement||document.body).dispatchEvent(ev);document.dispatchEvent(ev);};"
			+ "window.__dsScrollable=function(el){while(el&&el!==document.body){var st=getComputedStyle(el);if(/(auto|scroll)/.test(st.overflowY)&&el.scrollHeight>el.clientHeight+10)return el;el=el.parentElement;}return document.scrollingElement||document.documentElement;};"
			+ "window.__dsScrollVideo=function(dir){var v=document.querySelector('video');var c=window.__dsScrollable(v);var h=v?Math.max(200,v.getBoundingClientRect().height):c.clientHeight;c.scrollBy({top:dir*h,left:0,behavior:'smooth'});};"
			+ "window.__dsNext=function(){if(location.hostname.indexOf('instagram.com')>=0){window.__dsScrollVideo(1);return;}if(window.__dsClick('#navigation-button-down button, button[aria-label=\"Next video\"], [data-e2e=\"arrow-right\"], button[aria-label=\"Next\"]'))return;window.__dsKey('ArrowDown',40);};"
			+ "window.__dsPrev=function(){if(location.hostname.indexOf('instagram.com')>=0){window.__dsScrollVideo(-1);return;}if(window.__dsClick('#navigation-button-up button, button[aria-label=\"Previous video\"], [data-e2e=\"arrow-left\"], button[aria-label=\"Previous\"]'))return;window.__dsKey('ArrowUp',38);};"
			+ "}";

	private static final String AUTO_NEXT_JS = NAV_JS +
			"(function(){if(window.__dsAuto)return;window.__dsAuto=true;window.__dsLastNext=0;"
			+ "function next(){var t=Date.now();if(t-window.__dsLastNext<2000)return;window.__dsLastNext=t;console.log('__DS_NEXT__');window.__dsNext();}"
			+ "setInterval(function(){document.querySelectorAll('video').forEach(function(v){if(v.__ds)return;v.__ds={last:0,src:v.currentSrc};"
			+ "v.addEventListener('timeupdate',function(){"
			+ "if(v.currentSrc!==v.__ds.src){v.__ds.src=v.currentSrc;v.__ds.last=0;return;}"
			+ "var d=v.duration;if(d>1&&v.__ds.last>d-0.7&&v.currentTime<0.5&&!v.paused){v.__ds.last=0;next();}else{v.__ds.last=v.currentTime;}});"
			+ "v.addEventListener('ended',next);});},1000);})();";

	/**
	 * Sinema modu (ac/kapat). Once sitenin kendi tam ekrani denenir: Fullscreen API kullanici hareketi ister,
	 * bu yuzden sayfa sol ust koseye tek kullanimlik gorunmez bir kapak koyar, Java oraya sentetik bir tik
	 * gonderir (__DS__ fsArmed mesaji), kapagin tiklama isleyicisi requestFullscreen cagirir. Olmazsa
	 * (izin yok, reddedildi, 2,5 sn icinde tik gelmedi) en buyuk oynaticiyi CSS ile ekrana sabitleyen eski yol.
	 * Kapatirken tam ekrandan cikilir ve CSS geri alinir.
	 */
	private static final String CINEMA_JS =
			"(function(){"
			+ "var off=function(){if(window.__dsCinema){var c=window.__dsCinema;c.el.style.cssText=c.css;document.documentElement.style.overflow=c.ov;document.body.style.overflow=c.bov;if(c.inner){c.inner.style.cssText=c.innerCss;}window.__dsCinema=null;}};"
			+ "var ov0=document.getElementById('__dsFsOv');if(ov0)ov0.remove();"
			+ "if(document.fullscreenElement){try{document.exitFullscreen();}catch(e){}off();return 'off';}"
			+ "if(window.__dsCinema){off();return 'off';}"
			+ "var VW=innerWidth,VH=innerHeight,VA=VW*VH;"
			+ "function score(e){var r=e.getBoundingClientRect();var A=r.width*r.height;if(A<40000||r.width<160)return -99;var sc=0;"
			+ "if(A>0.93*VA)sc-=3;var ar=r.width/Math.max(1,r.height);if(ar>1.2&&ar<2.5)sc+=1;"
			+ "var cx=r.left+r.width/2,cy=r.top+r.height/2;if(Math.abs(cx-VW/2)<VW*0.25&&Math.abs(cy-VH/2)<VH*0.35)sc+=1;"
			+ "var st=getComputedStyle(e);if(st.pointerEvents==='none'||st.visibility==='hidden'||st.opacity==='0')sc-=3;"
			+ "if(e.tagName==='VIDEO'){if(!e.paused&&e.currentTime>0)sc+=3;if(e.duration>60)sc+=1;if(e.loop&&e.muted)sc-=3;if(!e.src&&!e.currentSrc&&!e.querySelector('source'))sc-=2;}"
			+ "else{var src=(e.src||'').toLowerCase();if(/player|embed|video|stream|play|vid|sibnet|ok[.]ru|dailymotion|vk[.]com/.test(src))sc+=2;if(/bet|casino|(^|[^a-z])ads?([^a-z]|$)|adserv|banner|promo|sponsor|track|pixel|analytics|doubleclick|recaptcha|hcaptcha/.test(src))sc-=5;if(!src)sc-=1;}"
			+ "return sc+Math.min(2,A/VA*2);}"
			+ "var cands=[].slice.call(document.querySelectorAll('video,iframe')).map(function(e){return {e:e,s:score(e)};}).filter(function(c){return c.s>-5;}).sort(function(a,b){return b.s-a.s;});"
			+ "if(!cands.length)return 'none';var el=cands[0].e;var r=el.getBoundingClientRect();"
			+ "var p=el;while(p.parentElement&&p.parentElement!==document.body){var pr=p.parentElement.getBoundingClientRect();if(pr.width*pr.height>r.width*r.height*1.35)break;p=p.parentElement;}"
			+ "var pin=function(){if(window.__dsCinema||document.fullscreenElement)return;"
			+ "window.__dsCinema={el:p,css:p.style.cssText,ov:document.documentElement.style.overflow,bov:document.body.style.overflow,inner:el!==p?el:null,innerCss:el!==p?el.style.cssText:''};"
			+ "p.style.cssText+=';position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;z-index:2147483647!important;background:#000!important;';"
			+ "if(el!==p){el.style.width='100%';el.style.height='100%';el.style.objectFit='contain';}"
			+ "document.documentElement.style.overflow='hidden';document.body.style.overflow='hidden';};"
			+ "var fs=p.requestFullscreen||p.webkitRequestFullscreen;"
			+ "if(!fs||!document.fullscreenEnabled){pin();return 'css';}"
			+ "var ov=document.createElement('div');ov.id='__dsFsOv';ov.style.cssText='position:fixed;left:0;top:0;width:12px;height:12px;z-index:2147483647;background:transparent;';"
			+ "var fired=false;var go=function(ev){if(fired)return;fired=true;ev.stopPropagation();ev.preventDefault();ov.remove();"
			+ "try{var pr=fs.call(p,{navigationUI:'hide'});if(pr&&pr.catch)pr.catch(function(){pin();});}catch(e){pin();}"
			+ "setTimeout(function(){if(!document.fullscreenElement)pin();},700);};"
			+ "ov.addEventListener('mousedown',function(ev){ev.stopPropagation();ev.preventDefault();});"
			+ "ov.addEventListener('mouseup',go);ov.addEventListener('click',go);"
			+ "document.documentElement.appendChild(ov);"
			+ "setTimeout(function(){if(document.getElementById('__dsFsOv')){ov.remove();if(!fired){fired=true;pin();}}},2500);"
			+ "console.log('__DS__{\"fsArmed\":1}');"
			+ "return 'armed';})();";

	/** Sayfa tam ekran icin kullanici hareketi bekliyor: sol ust kosedeki tek kullanimlik kapaga tiklat. */
	public static void armedClick(@Nullable CefBrowserView b) {
		if (b == null) {
			return;
		}
		var info = new net.minecraft.client.input.MouseButtonInfo(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		b.onMouseMoved(6, 6);
		b.onMouseClicked(new net.minecraft.client.input.MouseButtonEvent(6, 6, info), false);
		b.onMouseReleased(new net.minecraft.client.input.MouseButtonEvent(6, 6, info));
	}

	/**
	 * Sayfa raporcusu: saniyede bir en buyuk/oynayan videonun konumunu console uzerinden Java'ya bildirir
	 * (__DS__ kanali). Ayrica izleyici hizalama yardimcilari (__dsSeek, __dsSetPaused). Idempotent.
	 */
	private static final String REPORT_JS =
			"if(!window.__dsRep){window.__dsRep=1;window.__dsFr=Math.floor(Math.random()*1e9);"
			+ "window.__dsVid=function(){var best=null,bs=-1;[].slice.call(document.querySelectorAll('video')).forEach(function(v){if(!(v.duration>0))return;var r=v.getBoundingClientRect();var sc=r.width*r.height+(v.paused?0:1e7);if(sc>bs){bs=sc;best=v;}});return best;};"
			+ "window.__dsSeek=function(t){var v=window.__dsVid();if(v){try{v.currentTime=t;}catch(e){}}};"
			+ "window.__dsSetPaused=function(p){var v=window.__dsVid();if(!v)return;if(p){v.pause();}else{v.play().catch(function(){});}};"
			+ "setInterval(function(){"
			+ "if(window.__dsAdSkip!==false&&window.__dsAdClean)window.__dsAdClean();"
			+ "if(window.__dsFocusSend)window.__dsFocusSend();"
			+ "if(window.__dsAdSkip!==false&&location.hostname.indexOf('youtube.com')>=0){var pl=document.querySelector('.html5-video-player');if(pl&&pl.classList.contains('ad-showing')){var av=pl.querySelector('video');if(av&&isFinite(av.duration)&&av.duration>0){av.currentTime=av.duration;}var sb=document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern');if(sb)sb.click();var oc=document.querySelector('.ytp-ad-overlay-close-button');if(oc)oc.click();}}"
			+ "var v=window.__dsVid();if(!v)return;var d=v.duration;if(!v.__dsEnd){v.__dsEnd=1;v.addEventListener('ended',function(){var ad=document.querySelector('.html5-video-player.ad-showing')?1:0;console.log('__DS__{\"ended\":1,\"fr\":'+window.__dsFr+',\"d\":'+Math.round(v.duration||0)+',\"ad\":'+ad+'}');});}"
			+ "console.log('__DS__'+JSON.stringify({t:Math.round(v.currentTime*10)/10,d:isFinite(d)?Math.round(d*10)/10:0,p:v.paused?1:0,u:location.href,fr:window.__dsFr,ti:(document.title||'').slice(0,100)}));},1000);"
			+ "window.__dsFsFix=function(f){if(!f||f.dataset.dsFs)return;f.dataset.dsFs='1';if(!f.hasAttribute('allowfullscreen'))f.setAttribute('allowfullscreen','');var a=f.getAttribute('allow')||'';if(!/fullscreen/.test(a))f.setAttribute('allow',(a?a+'; ':'')+'fullscreen');};"
			+ "[].slice.call(document.querySelectorAll('iframe')).forEach(window.__dsFsFix);"
			+ "try{new MutationObserver(function(ms){ms.forEach(function(m){[].slice.call(m.addedNodes).forEach(function(n){if(n.tagName==='IFRAME')window.__dsFsFix(n);else if(n.querySelectorAll)[].slice.call(n.querySelectorAll('iframe')).forEach(window.__dsFsFix);});});}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}"
			+ "window.__dsSub=function(){var t='';var segs=document.querySelectorAll('.ytp-caption-segment');if(segs.length){"
			// YouTube: yalnizca gercekten gorunen satirlar (rollup penceresinde kaymis/henuz girmemis satirlar, gizli pencereler haric)
			+ "for(var i=0;i<segs.length;i++){var sg=segs[i];var r=sg.getBoundingClientRect();if(r.width<1||r.height<1)continue;"
			// segmentten pencereye kadar tum atalar gorunur olmali (solarak giren satirlar: atada opacity 0)
			+ "var w=sg.closest('.caption-window');var ok=true;var el=sg;while(el){var st=getComputedStyle(el);if(st.display==='none'||st.visibility==='hidden'||parseFloat(st.opacity)<0.05){ok=false;break;}if(el===w)break;el=el.parentElement;}if(!ok)continue;"
			+ "if(w){var wr=w.getBoundingClientRect();if(r.top<wr.top-2||r.bottom>wr.bottom+2)continue;}"
			// gercekten ekranda mi: segmentin ortasindaki eleman segmentin kendisi (ya da satiri) olmali (kirpilmis/kaymis satirlar elenir)
			+ "var cx=Math.min(innerWidth-1,Math.max(0,r.left+r.width/2)),cy=Math.min(innerHeight-1,Math.max(0,r.top+r.height/2));var hit=document.elementFromPoint(cx,cy);"
			+ "if(!hit||!(sg.contains(hit)||hit===sg.parentElement||(sg.parentElement&&hit===sg.parentElement.parentElement)))continue;"
			+ "t+=(t?' ':'')+sg.textContent;}return t.trim();}"
			+ "if(location.hostname.indexOf('youtube.com')>=0)return '';"
			+ "var v=window.__dsVid();if(!v)return '';"
			+ "if(v.textTracks){for(var i=0;i<v.textTracks.length;i++){var tr=v.textTracks[i];if(tr.mode==='disabled'||(tr.kind!=='subtitles'&&tr.kind!=='captions'))continue;var cs=tr.activeCues;if(cs&&cs.length){for(var j=0;j<cs.length;j++){t+=(t?' ':'')+(cs[j].text||'');}if(t)return t.replace(/<[^>]+>/g,'').trim();}}}"
			+ "var vr=v.getBoundingClientRect();var va=vr.width*vr.height;var els=document.querySelectorAll('[class*=caption],[class*=subtitle],[class*=Caption],[class*=Subtitle],[id*=caption],[id*=subtitle],[class*=text-track],[class*=-cue],[class*=_cue],[class*=Cue],[class*=altyaz],[id*=altyaz]');"
			+ "for(var k=0;k<els.length;k++){var e=els[k];if(e.tagName==='VIDEO'||e.tagName==='TRACK'||e.tagName==='BUTTON'||e.contains(v))continue;var r=e.getBoundingClientRect();if(r.width<40||r.height<8||r.width*r.height>va*0.6)continue;if(r.right<vr.left||r.left>vr.right||r.bottom<vr.top||r.top>vr.bottom)continue;"
			+ "var cid=(e.getAttribute('class')||'')+' '+(e.id||'');if(/control|chrome|toolbar|menu|setting|progress|time|button|icon|title|header|thumb|badge/i.test(cid))continue;"
			+ "var st=getComputedStyle(e);if(st.visibility==='hidden'||st.display==='none'||st.opacity==='0')continue;var s=(e.innerText||'').trim();if(!s||s.length>300||s.indexOf('\\n\\n')>=0)continue;if(/^[\\d:\\s\\/.,\\-]+$/.test(s))continue;return s;}return '';};"
			+ "window.__dsLastSub='';window.__dsLastSubAt=0;"
			+ "window.__dsSubTick=function(){try{var s=window.__dsSub();if(s.length>300)s=s.slice(0,300);var now=Date.now();"
			+ "if(s!==window.__dsLastSub||(s&&now-window.__dsLastSubAt>2500)){window.__dsLastSub=s;window.__dsLastSubAt=now;console.log('__DS__'+JSON.stringify({s:s}));}"
			+ "var v=window.__dsVid();if(v&&v.textTracks&&!v.__dsCue){v.__dsCue=1;for(var i=0;i<v.textTracks.length;i++){v.textTracks[i].addEventListener('cuechange',window.__dsSubTick);}}"
			+ "var cw=document.querySelector('.ytp-caption-window-container');if(cw&&!cw.__dsObs){cw.__dsObs=1;new MutationObserver(window.__dsSubTick).observe(cw,{childList:true,subtree:true,characterData:true});}"
			+ "}catch(e){}};setInterval(window.__dsSubTick,100);"
			// Yazi alani odagi: yalnizca gorunur bir alana yakin zamanda (1.5 sn) tiklanmissa 1; odak surdukce 1 kalir.
			// (Sitenin kendi kendine odakladigi gizli alanlar klavyeyi kapmasin; ESC'den sonra tik yeniden kapmasin.)
			// SponsorBlock: Java'nin enjekte ettigi bolum listesi (window.__dsSegs={id,segs}); bolum icindeysek sonuna atla
			+ "setInterval(function(){try{var S=window.__dsSegs;if(!S||!S.segs||!S.segs.length)return;if(location.href.indexOf(S.id)<0)return;var v=window.__dsVid();if(!v||v.paused)return;var t=v.currentTime;for(var i=0;i<S.segs.length;i++){var g=S.segs[i];if(t>=g[0]&&t<g[1]-0.3){v.currentTime=g[1];console.log('__DS__{\"sb\":'+Math.round(g[1]-g[0])+'}');break;}}}catch(e){}},250);"
			+ "if(!window.__dsFocusHook){window.__dsFocusHook=1;window.__dsEdit=function(){var el=document.activeElement;if(!el)return 0;var tg=el.tagName;var ed=(tg==='TEXTAREA'||el.isContentEditable||(tg==='INPUT'&&!/^(button|submit|checkbox|radio|range|file|color|image|reset|hidden)$/i.test(el.type||'')));if(!ed)return 0;"
			+ "var r=el.getBoundingClientRect();if(r.width<4||r.height<4||r.bottom<0||r.right<0||r.top>innerHeight||r.left>innerWidth)return 0;if(window.__dsLastFocus===1)return 1;"
			+ "var cp=window.__dsClickPt;if(!cp||Date.now()-cp[2]>1500)return 0;if(cp[0]<r.left-6||cp[0]>r.right+6||cp[1]<r.top-6||cp[1]>r.bottom+6)return 0;return 1;};"
			// Surukle-birak: crosshair kayinca gorsel/link suruklemesi baslamasin (tiklari yutan surukleme modu)
			+ "document.addEventListener('dragstart',function(e){e.preventDefault();},true);"
			+ "window.__dsFocusSend=function(){var f=window.__dsEdit();if(f!==window.__dsLastFocus){window.__dsLastFocus=f;console.log('__DS__{\"focus\":'+f+'}');}};"
			+ "document.addEventListener('focusin',window.__dsFocusSend,true);document.addEventListener('focusout',function(){setTimeout(window.__dsFocusSend,60);},true);}"
			+ "}";

	public static String reporterJs() {
		// Atlayici bayragi guard disinda: ayar degisince sayfa yenilenmeden uygulanir
		return REPORT_JS + "window.__dsAdSkip=" + DoomscrollConfig.get().adBlock + ";";
	}

	/**
	 * Sayfa ici reklam temizleyici. Her cerceveye (oynatici iframe'i dahil) ayri enjekte edilir, kendi
	 * 1 sn'lik dongusunu kurar (idempotent). Kurallar: bahis/casino sitesine giden ya da "deneme bonusu /
	 * bahis / casino" iceren link ve gorseller; standart reklam boyutundaki, video olmayan iframe'ler;
	 * videonun ustundeki tiklama kapani katmanlari (link/onclick tasiyan ya da bos+z-index'li). Oynatici
	 * sinif adlari (ytp, vjs, jw, plyr, artplayer...) korunur. window.__dsAdSkip=false ile durur.
	 */
	private static final String AD_CLEAN_JS =
			"if(!window.__dsVid){window.__dsVid=function(){var best=null,bs=-1;[].slice.call(document.querySelectorAll('video')).forEach(function(v){if(!(v.duration>0))return;var r=v.getBoundingClientRect();var sc=r.width*r.height+(v.paused?0:1e7);if(sc>bs){bs=sc;best=v;}});return best;};}"
			+ "window.__dsAdClean=function(){try{if(window.__dsAdSkip===false)return;"
			+ "var BETH=/bahis|casino|kumar|jackpot|rulet|slotin|betorspin|betist|betine|baywin|meritbet|jetbahis|marsbahis|sekabet|matbet|holiganbet|grandpashabet|casinomaxi|mobilbahis|bets10|betboo|superbetin|tipobet|restbet|piabet|betpas|onwin|hilbet|kralbet|betturkey|vdcasino|betnano|betwoon|betcio|sahabet|elexbet|imajbet|milanobet|betsmove|1xbet|betwinner|melbet|22bet|mostbet|pin-?up|1win|parimatch|freespin|deneme-?bonus|\\d+bet\\b|bet\\d+/i;"
			+ "var BETT=/deneme bonusu|ilk yat[ıi]r[ıi]m|yat[ıi]r[ıi]m bonus|bonusu|bahis|casino|kumar|freespin|free spin|spor bahis/i;"
			+ "var SAFE=/(^|\\.)(youtube|youtu|google|facebook|twitter|x|instagram|tiktok|reddit|wikipedia|netflix|imdb|twitch|kick|vimeo|dailymotion)\\.[a-z]+$/;if(SAFE.test(location.hostname.toLowerCase()))return;"
			+ "var ADPATH=/reklam|banner|sponsor|\\/go\\/|redirect|\\/click|out\\?|\\/ad\\/|\\/ads\\/|\\/r\\/|\\/link\\/|affiliate|promo/i;"
			+ "var SKIPC=/skip|atla|geç|gec\\b|kapat|close/i;"
			+ "function site(h){var p=h.split('.');return p.length>=2?p[p.length-2]+'.'+p[p.length-1]:h;}"
			+ "var mySite=site(location.hostname.toLowerCase());"
			+ "function hasSkip(e){return SKIPC.test((e.innerText||'').slice(0,200))||!!e.querySelector('[class*=skip],[class*=Skip],[id*=skip],[class*=close],[class*=kapat]');}"
			+ "function hide(e){if(!e||e.__dsHid)return;e.__dsHid=1;e.style.setProperty('display','none','important');var p=e.parentElement;if(p&&p!==document.body&&p.children.length===1&&!(p.innerText||'').trim()){p.__dsHid=1;p.style.setProperty('display','none','important');}}"
			// linkler
			+ "var links=document.querySelectorAll('a[href]');for(var i=0;i<links.length;i++){var a=links[i];if(a.__dsHid)continue;if(a.querySelector('video,iframe'))continue;var h='';try{h=new URL(a.href,location.href).hostname.toLowerCase();}catch(e){}"
			+ "var extSite=h&&site(h)!==mySite;var imgs=a.querySelectorAll('img');var alt='';for(var j=0;j<imgs.length;j++){alt+=' '+(imgs[j].alt||'')+' '+(imgs[j].src||'');}"
			+ "var txt=(a.innerText||'').trim();var imgOnly=imgs.length>0&&!txt;var rel=(a.getAttribute('rel')||'').toLowerCase();"
			+ "if((extSite&&BETH.test(h)&&!SAFE.test(h))||BETT.test(txt+' '+(a.title||''))||(imgs.length&&BETH.test(alt))||(imgOnly&&extSite&&!SAFE.test(h))||(imgOnly&&ADPATH.test(a.href))||(imgOnly&&/sponsored|nofollow/.test(rel)&&extSite)){hide(a);}}"
			// gorseller
			+ "var ims=document.querySelectorAll('img');for(var k=0;k<ims.length;k++){var im=ims[k];if(im.__dsHid)continue;var sv=(im.src||'')+' '+(im.alt||'');if(BETH.test(sv)||BETT.test(im.alt||'')||ADPATH.test(im.src||'')){hide(im.closest('a')||im);}}"
			// reklam/sponsor sinifli kutular (icinde link ya da gorsel varsa, video yoksa)
			+ "var boxes=document.querySelectorAll('[class*=reklam],[id*=reklam],[class*=sponsor],[id*=sponsor],[class*=adsbox],[class*=ad-banner],[class*=banner-ad],[class*=adsbygoogle],ins.adsbygoogle');"
			+ "for(var b=0;b<boxes.length;b++){var bx=boxes[b];if(bx.__dsHid||bx.querySelector('video')||bx.tagName==='BODY'||bx.tagName==='HTML')continue;if(bx.querySelector('a,img,iframe')||bx.tagName==='INS'){hide(bx);}}"
			// video ve reklam gec dugmesi
			+ "var v=window.__dsVid();var vr=v?v.getBoundingClientRect():null;"
			+ "var sizes={'300x250':1,'336x280':1,'728x90':1,'970x90':1,'970x250':1,'160x600':1,'300x600':1,'320x50':1,'320x100':1,'468x60':1,'250x250':1,'200x200':1,'120x600':1,'300x50':1,'980x120':1,'930x180':1};"
			+ "var ifr=document.querySelectorAll('iframe');for(var i=0;i<ifr.length;i++){var f=ifr[i];if(f.__dsHid)continue;var r=f.getBoundingClientRect();var key=Math.round(r.width)+'x'+Math.round(r.height);var src=(f.src||'').toLowerCase();"
			+ "var vid=/youtube|youtu\\.be|vimeo|dailymotion|twitch|player|embed|video|stream|ok\\.ru|vk\\.com|sibnet|mail\\.ru|drive\\.google|hls|m3u8|kick\\.com/.test(src);"
			+ "if((sizes[key]&&!vid)||(BETH.test(src)&&!vid)){hide(f);}}"
			// videoyu orten kapan: gec dugmesi iceriyorsa gizleme, tiklanamaz yap (dugme tiklanabilir kalir)
			+ "if(vr&&vr.width>200){var all=document.querySelectorAll('a,div,span');for(var k=0;k<all.length;k++){var e=all[k];if(e.__dsHid||e.__dsPe||e.id==='__dsFsOv')continue;if(e.querySelector&&e.querySelector('video'))continue;"
			+ "var st=getComputedStyle(e);if(st.position!=='absolute'&&st.position!=='fixed')continue;if(st.display==='none'||st.visibility==='hidden'||st.pointerEvents==='none')continue;"
			+ "var er=e.getBoundingClientRect();if(er.width<vr.width*0.6||er.height<vr.height*0.6)continue;"
			+ "var ov=Math.max(0,Math.min(er.right,vr.right)-Math.max(er.left,vr.left))*Math.max(0,Math.min(er.bottom,vr.bottom)-Math.max(er.top,vr.top));if(ov<vr.width*vr.height*0.5)continue;"
			+ "var cls=(e.className||'')+' '+(e.id||'');if(/player|control|ytp|vjs|jw-|plyr|poster|overlay-play|artplayer|dplayer|progress|volume/i.test(cls))continue;"
			+ "var isLink=e.tagName==='A'||e.getAttribute('onclick')||e.querySelector('a[target=_blank]');var empty=!(e.innerText||'').trim()&&!e.querySelector('img,svg,button,input,video,iframe');var z=parseInt(st.zIndex)||0;"
			+ "if(!(isLink||(empty&&z>0)))continue;"
			+ "if(hasSkip(e)){e.__dsPe=1;e.style.setProperty('pointer-events','none','important');var ks=e.querySelectorAll('button,a,div,span');for(var q=0;q<ks.length;q++){var kk=ks[q];if(SKIPC.test((kk.innerText||'').slice(0,60))||/skip|close|kapat/i.test((kk.className||'')+' '+(kk.id||''))){kk.style.setProperty('pointer-events','auto','important');}}}"
			+ "else{hide(e);}}}"
			// reklam gec / skip / atla dugmesine otomatik bas
			+ "var sk=document.querySelectorAll('button,a,div,span');var now=Date.now();for(var i2=0;i2<sk.length;i2++){var e2=sk[i2];var t2=(e2.innerText||'').trim();if(t2.length>40||e2.children.length>3)continue;var c2=(e2.className||'')+' '+(e2.id||'');"
			+ "var byText=/^(reklam[ıi]?\\s*)?(geç|gec|atla|skip)(\\s*(ads?|reklam[ıi]?|reklamlar[ıi]?|video))?\\s*[»›>→]*$/i.test(t2)||/^skip\\s*ads?\\s*[»›>→]*$/i.test(t2);var byCls=/skip[-_]?(ad|button|btn)|ima-skip|vast-skip|videoAdUiSkip|ad-?skip|skip-?ad|reklam[-_]?gec|reklami?-?gec/i.test(c2);"
			+ "if(!(byText||byCls))continue;var r2=e2.getBoundingClientRect();if(r2.width<8||r2.height<8||r2.width>innerWidth*0.5)continue;if(!vr||r2.right<vr.left-40||r2.left>vr.right+40||r2.bottom<vr.top-40||r2.top>vr.bottom+40)continue;var s2=getComputedStyle(e2);if(s2.display==='none'||s2.visibility==='hidden')continue;"
			+ "if(!e2.__dsClk||now-e2.__dsClk>800){e2.__dsClk=now;try{e2.click();}catch(x){}}}"
			+ "}catch(e){}};"
			+ "if(!window.__dsCleanTimer){window.__dsCleanTimer=setInterval(window.__dsAdClean,1000);window.__dsAdClean();}";

	/** Her cerceveye enjekte edilecek temizleyici + ayar bayragi. */
	public static String adCleanJs() {
		return AD_CLEAN_JS + "window.__dsAdSkip=" + DoomscrollConfig.get().adBlock + ";";
	}

	/** Tablet: tum cercevelere (oynatici iframe'i dahil) reklam temizleyiciyi kur. */
	public static void tabletAdClean() {
		CefBrowserView b = tablet;
		if (b == null || !DoomscrollConfig.get().adBlock) {
			return;
		}
		jsAllFrames(b, adCleanJs());
		b.applyCosmetics();
	}

	/** Kodu tarayicinin tum cercevelerinde (ana + iframe'ler) calistirir. */
	public static void jsAllFrames(CefBrowserView b, String code) {
		var cb = b.getCefBrowser();
		try {
			for (String id : cb.getFrameIdentifiers()) {
				var f = cb.getFrameByIdentifier(id);
				if (f != null) {
					f.executeJavaScript(code, f.getURL(), 0);
				}
			}
		} catch (Exception e) {
			cb.executeJavaScript(code, cb.getURL(), 0);
		}
	}

	public static void toggleCinema() {
		runJs(CINEMA_JS);
	}

	public static void toggleTabletCinema() {
		tabletJs(CINEMA_JS);
	}

	/** Her ~2 sn cagrilir: otomatik gecis acik ve sayfa kisa video sayfasiysa izleyiciyi kurar (idempotent). */
	public static void tickAutoNext() {
		if (tablet != null) {
			jsAllFrames(tablet, reporterJs()); // sure/baslik raporu + SponsorBlock (idempotent)
		}
		if (!DoomscrollConfig.get().autoScroll) return;
		if (tablet != null && isShortFormPage(tabletUrl())) tabletJs(AUTO_NEXT_JS);
		tabletAdClean();
	}

	/** ScreenBrowsers ekran basina kullanir. */
	public static String autoNextJs() {
		return AUTO_NEXT_JS;
	}

	/** /ds debug icin durum ozeti. */
	public static String debugInfo() {
		var init = CefService.initialize();
		return "mcef=" + init.getStage() + " %" + (int) init.getPercentage() + " " + ScreenBrowsers.debugInfo()
				+ "\n  tablet=" + (tablet != null) + " ekranSes=" + getUserVolume() + (isMuted() ? "(sessiz)" : "") + " tabletSes=" + getTabletVolume() + (isTabletMuted() ? "(sessiz)" : "");
	}

	@Nullable
	public static CefBrowserView getIfPresent() {
		return ScreenBrowsers.activeBrowser();
	}

	public static void close() {
		ScreenBrowsers.closeAll();
	}

	/** Hic islemeden dogrudan yukler (yerel oynatici sayfasi icin). */
	public static void navigateRaw(String url) {
		CefBrowserView b = getOrCreate();
		if (b != null) {
			b.getCefBrowser().loadURL(url);
		}
	}

	/** Kullanici girdisini adrese cevirir (arama metni -> arama sayfasi); gecersizse "". */
	public static String normalize(String input) {
		return normalizeUrl(input);
	}

	public static void navigate(String input) {
		String url = normalizeUrl(input);
		if (url.isEmpty()) {
			return;
		}
		BlockPos anchor = ScreenBrowsers.activeAnchor();
		if (anchor == null) {
			return;
		}
		// Sunucu-oncelikli: adres sunucuya gider (kontrol alinir), herkesin ekrani - bizimki dahil - oradan yuklenir
		ScreenBrowsers.requestNavigate(anchor, url);
	}

	/** Kullanici metnini adrese cevirir: "://" yoksa alan adi gibiyse https://, degilse arama. */
	public static String normalizeUrl(String input) {
		String url = input == null ? "" : input.trim();
		if (url.isEmpty()) {
			return "";
		}
		if (!url.contains("://")) {
			url = url.contains(".") && !url.contains(" ") ? "https://" + url : searchUrl(url);
		}
		return url;
	}

	public static String searchUrl(String query) {
		return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query.trim(), java.nio.charset.StandardCharsets.UTF_8);
	}

	/** Metni her zaman arama olarak acar (URL gibi gorunse bile). */
	public static void search(String query) {
		if (query == null || query.isBlank()) {
			return;
		}
		BlockPos anchor = ScreenBrowsers.activeAnchor();
		if (anchor != null) {
			ScreenBrowsers.requestNavigate(anchor, searchUrl(query));
		}
	}

	public static void goBack() {
		ScreenBrowsers.noteExplicitActive();
		CefBrowserView b = getIfPresent();
		if (b != null && b.getCefBrowser().canGoBack()) {
			b.getCefBrowser().goBack();
		}
	}

	public static void goForward() {
		ScreenBrowsers.noteExplicitActive();
		CefBrowserView b = getIfPresent();
		if (b != null && b.getCefBrowser().canGoForward()) {
			b.getCefBrowser().goForward();
		}
	}

	public static void reload() {
		ScreenBrowsers.noteExplicitActive();
		CefBrowserView b = getIfPresent();
		if (b != null) {
			b.getCefBrowser().reload();
		}
	}

	public static String currentUrl() {
		CefBrowserView b = getIfPresent();
		if (b == null) {
			return "";
		}
		String u = b.getCefBrowser().getURL();
		return u == null ? "" : u;
	}

	/** Kisa video sayfalarinda sonraki/onceki video (guvenilir ok tusu). */
	public static void nextVideo() {
		ScreenBrowsers.noteExplicitActive();
		runJs(NAV_JS + "window.__dsNext();");
	}

	public static void prevVideo() {
		ScreenBrowsers.noteExplicitActive();
		runJs(NAV_JS + "window.__dsPrev();");
	}

	public static void tabletNextVideo() {
		tabletJs(NAV_JS + "window.__dsNext();");
	}

	public static void tabletPrevVideo() {
		tabletJs(NAV_JS + "window.__dsPrev();");
	}

	public static void togglePlayback() {
		ScreenBrowsers.noteExplicitActive();
		runJs("document.querySelectorAll('video,audio').forEach(function(m){ if (m.paused) { m.play(); } else { m.pause(); } });");
	}

	// ---- ses: ekranlar (kumanda) ve elindeki tablet ayri; ikisi de config'e yazilir ----

	/** Bu oturumdaki etkin deger; -1 = henuz sunucu politikasina gore belirlenmedi. */
	private static float othersEffective = -1f;

	/** Senin koymadigin ekranlarin sesi. Sunucu "sessiz basla" diyorsa oturum 0'dan baslar. */
	public static float getOthersScreenVolume() {
		if (othersEffective < 0f) {
			othersEffective = ServerPolicy.muteOthers() ? 0f : DoomscrollConfig.get().othersScreenVolume;
		}
		return othersEffective;
	}

	/** kapali -> kisik -> orta -> tam -> kapali */
	public static void cycleOthersScreenVolume() {
		float v = getOthersScreenVolume();
		float next = v <= 0.01f ? 0.35f : v <= 0.4f ? 0.7f : v <= 0.75f ? 1f : 0f;
		othersEffective = next;
		DoomscrollConfig.get().othersScreenVolume = next;
		DoomscrollConfig.save();
	}

	public static String othersScreenLabel() {
		float v = getOthersScreenVolume();
		return Lang.tr(v <= 0.01f ? "gui.doomscroll.volume.off"
				: v <= 0.4f ? "gui.doomscroll.volume.low"
				: v <= 0.75f ? "gui.doomscroll.volume.mid" : "gui.doomscroll.volume.full");
	}

	/** Sunucu politikasi degisti: etkin deger yeniden hesaplansin. */
	public static void onPolicyChanged() {
		othersEffective = -1f;
	}

	public static float getUserVolume() { return DoomscrollConfig.get().screenVolume; }
	public static boolean isMuted() { return DoomscrollConfig.get().screenMuted; }

	public static void setUserVolume(float v) {
		DoomscrollConfig cfg = DoomscrollConfig.get();
		float n = Math.max(0f, Math.min(1f, v));
		if (n == cfg.screenVolume) {
			return;
		}
		cfg.screenVolume = n;
		DoomscrollConfig.save();
		lastAppliedVolume = -1f;
	}

	public static void volumeStep(float delta) {
		setUserVolume(Math.round((getUserVolume() + delta) * 10f) / 10f);
	}

	public static void toggleMute() {
		DoomscrollConfig cfg = DoomscrollConfig.get();
		cfg.screenMuted = !cfg.screenMuted;
		DoomscrollConfig.save();
		lastAppliedVolume = -1f;
	}

	/**
	 * Elindeki tabletin sesi: ekranlardan bagimsiz kendi seviyesi (tablet arac cubugundaki hoparlor tusu).
	 * Baskalarinin tableti dunyadan duyulan bir ses oldugu icin ekran seviyesinden calar.
	 */
	public static float getTabletVolume() { return DoomscrollConfig.get().tabletVolume; }
	public static boolean isTabletMuted() { return DoomscrollConfig.get().tabletMuted; }

	public static void setTabletVolume(float v) {
		DoomscrollConfig cfg = DoomscrollConfig.get();
		float n = Math.max(0f, Math.min(1f, v));
		if (n == cfg.tabletVolume) {
			return;
		}
		cfg.tabletVolume = n;
		DoomscrollConfig.save();
	}

	/** Baskalarinin tabletinden duyulan ses (0 = kapali). Kumanda -> Ayarlar -> "Başkalarının tableti". */
	public static float getRemoteTabletVolume() { return DoomscrollConfig.get().remoteTabletVolume; }

	public static String remoteTabletLabel() {
		float v = getRemoteTabletVolume();
		return v <= 0.01f ? "KAPALI" : v <= 0.5f ? "KISIK" : "NORMAL";
	}

	/** kapali -> kisik -> normal -> kapali */
	public static void cycleRemoteTabletVolume() {
		DoomscrollConfig cfg = DoomscrollConfig.get();
		cfg.remoteTabletVolume = cfg.remoteTabletVolume <= 0.01f ? 0.35f : cfg.remoteTabletVolume <= 0.5f ? 0.8f : 0f;
		DoomscrollConfig.save();
	}

	public static void toggleTabletMute() {
		DoomscrollConfig cfg = DoomscrollConfig.get();
		cfg.tabletMuted = !cfg.tabletMuted;
		DoomscrollConfig.save();
	}

	private static long lastScreenSeenNanos = 0L;
	private static boolean idlePaused = false;
	private static final long IDLE_PAUSE_NANOS = 2_000_000_000L;
	private static final long IDLE_CLOSE_NANOS = 15_000_000_000L;

	/** Tablet/GUI acikken: idle sayaclarini sifirla, tarayici kapanmasin. */
	public static void keepAlive() {
	}

	/** Renderer her karede acik bir ekran cizince cagirir. */
	public static void noteScreen(BlockPos pos) {
	}

	/** Renderer: kapali bir ekran cizildi. Baska acik ekran yoksa aninda durdur + sessize al. */
	public static void noteScreenOff(BlockPos pos) {
	}

	/** Uzaklik * kullanici sesi (* sessiz). Seyrek cagrilir. Ekran yoksa durdurur, uzun sure yoksa kapatir. */
	public static void updateVolume(Vec3 playerPos) {
	}

	private static void runJs(String code) {
		CefBrowserView b = getIfPresent();
		if (b != null) {
			b.getCefBrowser().executeJavaScript(code, b.getCefBrowser().getURL(), 0);
		}
	}

	public static GpuSampler sampler() {
		if (sampler == null) {
			sampler = RenderSystem.getDevice().createSampler(
					AddressMode.CLAMP_TO_EDGE,
					AddressMode.CLAMP_TO_EDGE,
					FilterMode.LINEAR,
					FilterMode.LINEAR,
					1,
					OptionalDouble.empty()
			);
		}
		return sampler;
	}

	public static float initProgress() {
		return CefService.initialize().getPercentage();
	}
}
