# Doomscroll

***English** · [Türkçe](README.tr.md)*

Watch Reels / Shorts / TikTok / YouTube / films **together** inside Minecraft. Build huge screens in the world, carry a tablet, drive everything with a remote, turn a room into a cinema. It started as a joke and became real.

- Minecraft **26.2** · Fabric · Java 25
- A real Chromium (CEF 126 with H.264/AAC codecs), supplied by the sibling project [`mcef-codec`](https://github.com/menntdev/mcef-codec)
- Positional audio (it comes from the nearest point of the panel, works with Sound Physics Remastered), a screen that emits light under shaders, and a room that takes on the colours of what is playing (ambilight)

## Contents
1. [Install](#install)
2. [Items](#items)
3. [Using a screen](#using-a-screen)
4. [Watching together](#watching-together-sync)
5. [Home menus](#home-menus)
6. [Floor and ceiling screens](#floor-and-ceiling-screens)
7. [Broadcast mode](#broadcast-mode-everyone-sees-your-view)
8. [Cinema-room features](#cinema-room-features)
9. [Ad blocking](#ad-blocking)
10. [Commands](#commands-ds)
11. [Client settings](#client-settings--configdoomscrolljson)
12. [Server admin settings and commands](#server-admin-settings--configdoomscroll-serverjson)
13. [Moderation and safety](#moderation-and-safety)
14. [Performance](#performance)
15. [Safety notes](#safety-notes)
16. [Development](#development)

> **Language.** The mod ships in English and Turkish and follows your Minecraft language setting. Most commands have both an English and a Turkish name, and the two can be mixed: `/ds screens` and `/ds ekranlar` are the same command, as are `/ds queue clear` and `/ds sira temizle`. Names that read the same in both languages (`go`, `fps`, `boost`, `pause`, `remote`, `tablet`, ...) have just one.

## Install
1. Put `fabric-api`, `mcef-codec-0.1.0.jar` and `doomscroll-0.1.0.jar` in your `mods/` folder.
2. On first launch mcef-codec downloads the Chromium binaries (a few hundred MB, SHA-256 verified) into `config/mcef-codec/libraries/`.
3. On a server both jars go in the server's `mods/` folder as well as every player's: `doomscroll` declares `mcef-codec` as a dependency, so Fabric looks for it on both sides. The browser itself only ever runs on the client - on a server mcef-codec loads and does nothing. Anyone without the mod sees a blank screen.
4. A one-time welcome message appears on your first join. `/ds help` lists every command.

## Items
Everything is crafted at a crafting table (screen: glass pane + redstone + iron → 2; remote: stone button / redstone / iron; tablet: 6 glass panes + iron, redstone, iron). They are in the Functional Blocks tab in creative.

| Item | What it does |
|---|---|
| **Screen** (`doomscroll:screen`) | Place them side by side or stacked and they become one panel (the bottom-right block is the anchor); the panel size is reported when you place one. Sneak-place on a floor or ceiling for a floor / ceiling screen. It emits light while on. No frame. |
| **Screen Remote** (`doomscroll:remote`) | Right-click a screen to pair with it. Right-click the air for the panel. Sneak + right-click to unpair. While unpaired it drives nothing. |
| **Tablet** (`doomscroll:tablet`) | Right-click for its own browser. It renders in 3D while held; R switches portrait/landscape. While it is in your main hand the scroll wheel goes to the tablet (TikTok/Shorts/Reels: next video, other pages: scroll); sneak-scroll still changes your hotbar. Other people's tablets show their page. |

## Using a screen
- **Look and click:** with an empty hand (or while holding the remote or tablet), look at a screen and your crosshair becomes a cursor. Left click clicks, the wheel scrolls (on Shorts/Reels one tick is one video), right click binds your keyboard to the screen (ESC releases it), sneak + right click plays/pauses. If you are holding a block you get a hint instead, so the click goes to the game and you can build next to the screen.
- **Text fields:** clicking a visible text field binds your keyboard automatically, and it is released when you press Enter, the page changes, or focus is lost. Fields a site focuses behind your back never steal your keyboard.
- **The remote panel:** a compact embossed dark remote body (vanilla button feel: a 1 px outline with light and shadow edges), a green LCD status display, three tabs (REMOTE / SETTINGS / MORE), pixel-icon buttons and a volume slider.
  - **REMOTE:** power, mute + volume, Shorts/Reels/TikTok, channel (◀ name ▶), back/forward/reload/home/cinema, address bar + go (arrow) + **+** (add to queue), "Send screen to tablet" (the page on the screen opens on the tablet, on YouTube from the same second; the tablet → screen direction is the tablet's **Cast** button).
  - **SETTINGS:** auto-advance, playback sync, ad blocker, captions (HUD), YouTube sign-in mode, lock, resolution, frame rate, audio delay.
  - **MORE:** shared pointer, redstone, broadcast, screen light, smooth light, light range, queue → the **queue list** page (with titles; play / remove / clear, scrollable).
  - LCD rows: state · owner · volume, page title + duration (+N queued), control/lock.
- **Looking at a new screen** shows a "▶ title · duration · control" notice after one second.
- **Cinema mode:** the site's own fullscreen is tried first (the page drops a single-use cover and the mod clicks it, which supplies the "user gesture" the browser wants); if that fails the largest player is pinned to the screen with CSS (`/ds cinema`, and Cinema on the remote and tablet). Links that try to open a new window open on the same screen if they are on the same site; foreign popups (ads) are blocked.
- **Tablet toolbar:** back/forward/reload, Home, Cinema, **★ bookmark** (add/remove the open page), **≡ menu** (bookmarks + history panel: tap to open, ✕ to delete, the bin clears history), address, **Cast** (send the page to the screen you are looking at, or the nearest one), **Queue** (add the page to that screen's video queue). Bookmarks and the last 40 pages live in `config/doomscroll-tablet.json`.

## Watching together (sync)
- A screen's address is stored on the server; every client opens the same page in its own browser (the CinemaMod model). Leave the world and come back and it picks up where it was.
- **Controller:** one person drives each screen at a time. Any deliberate action (remote, click, wheel, address) takes control; only the controller's browser publishes addresses and auto-advances. If the controller walks away, leaves, or goes quiet (45 s by default, a server setting) control is released.
- **Lock:** if the person who placed the screen locks it, only they (and operators) can control it.
- **Playback sync:** on long videos (≥30 s) viewers line up with the controller's position (2.5 s tolerance) and pausing is mirrored. It is off for short looping videos. It works on film sites even when the player is inside an iframe.
- **Video queue, with voting:** the queue lives on the server, so everyone sees the same list and anyone can add to it. `/ds queue add <url>`, the **+** on the remote, or **Queue** on the tablet. When the current video ends, the one with the **most votes** opens next and everyone moves to the same address. Tap the number on a card to vote or take your vote back; adding something counts as your vote for it, and adding a URL that is already queued just votes for it. `/ds queue` lists with vote counts, `/ds queue vote <n>`, `/ds queue skip`, `/ds queue remove <n>` (your own entries, or any if you own the screen), `/ds queue clear` (screen owner). Up to 50 entries, session only.
- **Shared pointer:** the crosshair of a player looking at a screen shows up as a coloured dot for everyone else looking at the same screen ("look here"). The server publishes at most ~16 positions per second, within 48 blocks. `/ds pointer` or the More tab.
- **Cast:** send the tablet's page to the screen you are looking at, or the nearest one.
- **HUD captions:** if the video has captions on (YouTube CC, HTML5 caption tracks, a player's caption layer, players inside iframes), the text appears above your hotbar in the game. You do not have to look at the screen. Only lines actually visible on the screen are picked up.
- Everyone watches in their own CEF profile and cookies: only the address and position are shared, never an account. Everyone sees the same page but not a pixel-identical image (sign-in state, ads and recommendations are personal; on a film site each person picks their own dub or subtitle track).

## Home menus
- A newly placed screen and the tablet open the **home menu**, not a site (`doomscroll://home/screen`, `doomscroll://home/tablet`; the Home button on the remote and tablet also comes back here).
- **Help on both menus.** The bottom of the screen menu carries a **How to use it** rail, and the tablet has a help widget next to History: look-and-click, the keyboard, scrolling, the remote, the queue and voting, cinema mode, R for portrait, Cast and Queue. Players of comparable mods repeatedly ask how to scroll or how to get back to the home page; this is where they will look.
- **Screen menu** (a smart-TV launcher): brand, search (anything that is not an address goes to Google) and a clock across the top; a large **hero** area (if something is queued, its thumbnail and title with "Play now"; otherwise a greeting for the time of day and an invitation to Shorts); horizontal rails: Apps (YouTube, Shorts, Reels, TikTok, Twitch, Kick), Channels (+ Add a channel, ✕), Up next (cards with YouTube thumbnails; click to play, ✕ to remove). A TV focus ring on hover.
- **Tablet menu** (an iPad home screen): wallpaper, status bar (clock, date), a search pill, rounded app icons, a **History** widget ("Clear"), bookmarks and channels as web-clip icons (hover for ✕), and a dock at the bottom (YouTube, Shorts, Reels, TikTok, Twitch). Five columns in portrait.
- Both menus have a **+ Add** icon: give it a name and an address and it becomes a tablet bookmark or a screen channel (hover for ✕). The star and `/ds channel add` feed the same lists.
- The pages are generated inside the mod (`assets/doomscroll/home/home.html` + `HomePages`) and never touch the internet; server address rules always allow `doomscroll://`.

## Floor and ceiling screens
- A normal placement is a wall screen facing you. **Sneak** and place on the top face of a block for a **floor screen**, on the bottom face for a **ceiling screen**.
- The top edge of the picture: on a floor screen it is the direction you are facing (like a table, with the far edge on top); on a ceiling screen it is the opposite (as if you were lying on your back looking up). If it comes out upside down, break it and place it facing the other way.
- A block placed in the plane of an adjacent screen inherits its orientation, so extending a panel does not depend on where you look and does not need sneaking.
- A floor screen lights the ceiling and walls, a ceiling screen lights the floor. Look-and-click, the pointer and audio all work the same.

## Broadcast mode (everyone sees your view)
Normally everyone opens the same address in their own browser, at zero extra cost. When a page differs per person, as on film sites, or when you want people to see exactly what you see, use **broadcast mode**: `/ds broadcast on` (or remote → More → Broadcast). The video on your screen is drawn scaled down onto a canvas and encoded with the video's own audio track by Chromium's own encoder (VP8 + Opus, WebM) into 500 ms chunks; the chunks go through the server to everyone looking at that screen. Viewers' screens open a small receiver page (MediaSource) and play near the live edge (~1-2 s behind). When a new viewer arrives the encoder restarts for a keyframe; when the host leaves or runs `/ds broadcast off` everyone returns to the address on the server.
- The cost falls on the host (encoding, about like a Discord screen share) and the server (~150 KB/s per viewer). Quality presets: `/ds broadcast quality low|normal|high` (640×360/20 fps/700 kbps · 960×540/24/1200 · 1280×720/30/2500, changeable mid-broadcast) or `config/doomscroll.json`: `broadcastWidth`, `broadcastFps`, `broadcastKbps`. The server drops anything over 40 chunks per second per host. Server owners can turn it off with `broadcast`.
- Limit: Chromium captures direct MP4 sources without CORS as black for security reasons; YouTube and players using HLS/MSE (most film sites) are fine.

## Cinema-room features
- **Screen light (ambilight):** walls, floor and ceiling in front of the screen are lit by the colour of the nearest part of the screen, blending into the whole screen's average as you move away. The panel behaves like an area light, so surfaces flush with its edge still catch light. It is entirely client-side and works with shader packs (the same emissive draw path as the screen). The surface list is recomputed on a background thread every 2 s with a line-of-sight check so light does not leak through walls; colours come from the browser's 8×5 colour map every frame, brightened while keeping their hue. Settings → "Screen light" (off / low / normal / high) and "Smooth light" (on: a continuous gradient across neighbouring surfaces, off: a block-by-block mosaic); `/ds light off|low|normal|high|smooth|range <2-24>`; More → light range. `/ds light` prints diagnostics (patch count, average screen colour).
- **Redstone control:** the screen's owner enables it with `/ds redstone` (or More → Redstone): a rising edge on any block of the panel toggles the screen. One lever to kill the lights and start the film.
- **Block light:** a screen that is on emits light level 12 (server setting `screenLightLevel`).

## Ad blocking
- **Filter lists (real blocker logic):** [EasyList](https://easylist.to) and the [AdGuard Turkish filter](https://filters.adtidy.org/extension/ublock/filters/13.txt) are applied at request level with the full rule syntax (domain and path patterns, `$third-party`, type, `domain=`, `$popup`, `@@` exceptions, `$generichide`, `@@$document`), plus the [StevenBlack hosts](https://github.com/StevenBlack/hosts) domain list. The lists' `##` cosmetic rules (site-specific and generic) are injected as styles into every page and iframe. Cached in `config/mcef-codec/adblock/` and refreshed every 7 days. Video CDNs are allowlisted; a page redirecting itself to an ad or gambling site is blocked, while an address you type yourself never is.
- **In-page cleaner (film sites):** standard-size ad iframes (300×250, 728×90 …) are hidden, click traps over the video are removed, and a "Skip ad" button is pressed automatically. Player layers (ytp, vjs, jw, plyr …) are left alone, and the cleaner does not run at all on large sites such as YouTube, Instagram, TikTok and Twitch.
- **Popups:** no new window opens by itself; you get a "Popup blocked: domain" notice, and `/ds popup` opens it if you really want it.
- **YouTube ads are left alone.** They are served from the same domain as the video, so filter lists cannot reach them, and the mod does not skip them either — you see them exactly as you would in a browser.
- In-feed ads on Instagram and TikTok arrive as ordinary content and are not blocked.
- Toggle: Settings → **Ad blocker** or `/ds adblock on|off`; `/ds adblock` reports the loaded rule count and how many requests were blocked; the log has `[reklam] engellendi: domain/path` for the first 20.

## Commands (`/ds`)
| Command | |
|---|---|
| `/ds help` | the full list |
| `/ds screens` | nearby screens: distance, size, owner, control, lock, page title |
| `/ds go <url>` · `shorts` · `reels` · `tiktok` | open on the screen |
| `/ds control take\|release\|lock` | take / release control, toggle the lock |
| `/ds queue [add <url>\|list\|remove <n>\|clear\|skip]` | video queue |
| `/ds channel list\|add "Name" [url]\|remove Name` | remote channels (without a url it uses the open page) |
| `/ds sync` · `/ds auto` | playback sync / auto-advance |
| `/ds cinema` · `/ds pause` | fullscreen / play-pause |
| `/ds light off\|low\|normal\|high\|smooth\|range <blocks>` | screen light |
| `/ds broadcast [on\|off]` | broadcast mode (your view to everyone) |
| `/ds pointer` · `/ds redstone` | shared pointer / redstone on this screen (owner) |
| `/ds res 720p\|1080p\|1440p\|WxH` · `/ds audiorate <hz>` | resolution / audio sample rate |
| `/ds fps <10-60>` · `/ds perf` | browser frame rate / performance report |
| `/ds boost <0.5-6>` | browser audio gain (default 2.5; peaks are soft-limited) |
| `/ds latency low\|normal\|high` | audio delay profile: low ~150 ms, normal ~180 ms, high ~320 ms |
| `/ds adblock [on\|off]` · `/ds popup` | ad blocker / open the last blocked popup |
| `/ds captions` | show or hide HUD captions |
| `/ds ytlogin` | YouTube sign-in mode. **Use a throwaway account.** See the warning below. |
| `/ds report [note]` | report the screen you are looking at to the admins |
| `/ds remote` · `/ds tablet` · `/ds debug` | panel / tablet / status |

## Client settings — `config/doomscroll.json`
| Field | Default | |
|---|---|---|
| `screenWidth`, `screenHeight` | 1280×720 | screen browser resolution (it also caps stream quality) |
| `audioSampleRate` | 0 | 0 = auto-detect; set 44100 or 48000 if audio sounds pitched |
| `browserFps` | 60 | paint rate of the screen you are looking at; off-screen ones drop to 10, distant ones to half (never below 20 fps, and never above the cap you set) |
| `audioBoost` | 2.5 | digital gain on browser audio |
| `audioLatency` | normal | `dusuk` / `normal` / `yuksek` |
| `adBlock` | true | ad blocker |
| `subtitles` | true | HUD captions (nearest screen within 24 blocks) |
| `pointer` | true | shared pointer (send and show) |
| `screenVolume` / `screenMuted` | 0.8 / false | your personal volume for screens (the slider on the remote) |
| `tabletVolume` / `tabletMuted` | 0.8 / false | the volume of the tablet in your hand (the speaker in its toolbar) |
| `remoteTabletVolume` | 0.8 | how loud other people's tablets are for you (0 = never hear them) |
| `othersScreenVolume` | 1.0 | how loud screens you did not place are (the server can make this start at 0) |
| `separateScreenCookies` | false | screens use an in-memory Chromium context, separate from the tablet's persistent profile. A sign-in made on a screen is then lost when you close the game, so it is off by default; the server can force it on |
| `tabletSway` | 0.35 | how much of the vanilla walking sway the held tablet keeps (0 still, 1 vanilla) |
| `screenGlow` / `screenGlowRange` / `screenGlowSmooth` | 1.0 / 10 / true | screen light strength (0 off, 0.5 low, 1 normal, 1.8 high), range, smooth blending |
| `autoScroll` | true | next video when one ends (Shorts/Reels/TikTok) |
| `syncPlayback` | true | line up with the controller's position |
| `channels` | 6 channels | a list of `{ "name": "...", "url": "..." }` |
| `userAgent` | Chrome/128 | browser identity |
| `tvLogin` | false | YouTube sign-in mode |
| `welcomeShown` | false | the welcome message has been shown |
| `broadcastWidth` / `broadcastFps` / `broadcastKbps` | 960 / 24 / 1200 | broadcast encoder settings (`/ds broadcast quality` writes these) |

Volume has three stages: the **device's own volume** (the tablet's slider, which everyone hears it at; a screen's lives on the block, remote → More → "Screen volume"), then **your own slider**, and on top of that Minecraft's "Blocks" setting.

## Server admin settings — `config/doomscroll-server.json`
Singleplayer uses the same file through its internal server. Two things sit next to it:

- `doomscroll-server.txt` is regenerated on every start and explains every field in English and Turkish, with three ready-made setups to copy.
- `/doomscroll help` in game lists the same settings with their current values and one line each on what they do, in the admin's own language.

The JSON is rewritten on every start too, so a mod update adds its new fields with their defaults instead of leaving you to add them by hand.

| Field | Default | Description |
|---|---|---|
| `blockedDomains` | `[]` | Blocked domains, subdomains included. Trying to open one shows "This address is blocked on this server" and the screen stays where it was. |
| `allowedDomains` | `[]` | If not empty, only these domains can be opened (allowlist). |
| `screenLightLevel` | `12` | Block light of a screen that is on (0-15). Needs a restart. |
| `announce` / `announceRange` | `true` / `32` | The "X opened site on the screen" notice and its range. |
| `redstoneControl` | `true` | Whether redstone may toggle screens. |
| `pointer` | `true` | Shared pointer relay. |
| `broadcast` | `true` | Whether broadcast mode is allowed. |
| `controlTimeoutSeconds` | `45` | How long a quiet controller keeps control. |
| `auditLog` | `true` | Write every address opened to `config/doomscroll-audit.log`. |
| `lockdown` | `false` | Emergency shutdown: every screen is dark and none can be turned on. Toggled with `/doomscroll emergency`. |
| `allowPrivateNetwork` | `false` | Allow loopback and LAN addresses. Leave it off: otherwise a player can put `192.168.1.1` on a screen and make everyone open their own router page. |
| `requireConsent` | `false` | A page outside the allowlist is not drawn until the viewer taps Show. Nothing is requested from the site before that. |
| `muteOthersByDefault` | `false` | Screens you did not place start muted for you. |
| `showDomain` | `true` | Show the real domain on the HUD when you look at a screen. |
| `separateScreenCookies` | `false` | Force screens onto an in-memory cookie context, overriding each player's own setting. |
| `maxPanelBlocks` | `0` | Largest panel in blocks (0 = unlimited). |
| `maxScreensPerPlayer` | `0` | Screen blocks one player may have (0 = unlimited). |
| `urlCooldownMs` | `0` | Minimum gap between one player's address changes (0 = none). Set 1000-2000 on a public server. |

**Admin commands** (`/doomscroll ...`, gamemaster permission). Every subcommand has a Turkish and an English name: `/doomscroll` status · `yenile` / `reload` · `engelle` / `block` `<domain>` · `engelkaldir` / `unblock` · `izin` / `allow` · `izinkaldir` / `unallow` · `isik` / `light` `<0-15>` · `liste` / `list` · `duyuru` / `announce` `on|off` · `isaretci` / `pointer` `on|off` · `redstone` `on|off` · `kontrolsuresi` / `controltime` `<seconds>` · `kayit` / `audit` `[n]` · `denetim` / `auditlog` `on|off` · `acil` / `emergency` `on|off` · `karart` / `blackout` · `ozelag` / `privatenet` `on|off` · `onay` / `consent` `on|off` · `sessiz` / `muteothers` `on|off` · `alanadi` / `showdomain` `on|off` · `cerez` / `cookies` `on|off` · `panelsinir` / `maxpanel` `<n>` · `ekransinir` / `maxscreens` `<n>` · `bekleme` / `cooldown` `<ms>` · `yayin` / `broadcast` `on|off`. Changes are written to the file and pushed to every client at once.

## Moderation and safety

Server owners generally do not distrust what a mod like this does. They distrust that the client is
authoritative and that nothing is available after an incident. These are the tools for that.

- **Audit log.** Every address opened, every blocked attempt, every broadcast and power change is written to
  `config/doomscroll-audit.log` with who, when, which world and which block. `/doomscroll audit [n]` shows the
  last entries in chat. Addresses are never used as a format string, so a `%` in a URL cannot break the log,
  and `§` formatting codes are stripped so nobody can hide or forge a line in that output. Writing happens on
  a background thread, and the file rotates to `doomscroll-audit.log.1` at 8 MB.
- **The tablet obeys the same rules as screens.** A page on somebody's tablet is visible to everyone around
  them, so the address goes through the same gate: the block and allow lists, the private-network rule,
  emergency shutdown and the `doomscroll.url` permission. A refused address simply shows nothing on other
  people's view of that tablet, and the attempt is logged.
- **Emergency shutdown.** `/doomscroll emergency on` blacks out every loaded screen and stops any of them from
  being turned on again. `/doomscroll blackout` is the one-shot version without the lock. Both take effect
  immediately; nobody has to rejoin.
- **Redirect enforcement.** The server checks the shared address, but redirects happen in the browser first.
  The domain policy is sent to every client on join and after every change, so each browser applies the same
  rule at every navigation and a shortened link to a blocked site never finishes loading. A modified client can
  ignore this; the server-side check still stands.
- **Private network addresses are refused.** Loopback, `10/8`, `172.16/12`, `192.168/16`, link-local (including
  cloud metadata at `169.254.169.254`), carrier NAT, IPv6 unique-local and link-local, `.local` and dotless
  intranet names. Without this, a screen showing `192.168.1.1` makes every viewer's client open their own
  router page. Turn it back on with `allowPrivateNetwork` only on a LAN you control.
- **Permissions.** If a permission manager is installed (anything speaking fabric-permissions-api, such as
  LuckPerms) the nodes `doomscroll.place`, `doomscroll.url`, `doomscroll.broadcast`, `doomscroll.bypass` and
  `doomscroll.admin` are honoured. Without one, the first three are open to everyone and the last two need
  gamemaster. No extra dependency: the API is looked up at runtime and skipped if absent.
- **Viewer consent.** With `requireConsent` on, a page outside the allowlist is replaced by a card naming the
  domain and the player who placed the screen, with Show and Home menu. Until you choose Show, the site gets no
  request at all, so neither shock content nor your IP reaches it. Your choice is remembered per domain for the
  session.
- **Separate cookies.** Screens can share an in-memory Chromium context that is separate from the persistent
  profile your tablet uses, so a page somebody else opened never runs in the same context as your own signed-in
  session, and nothing is written to disk for screens. It is **off by default**, because a sign-in made on a
  screen then disappears when you close the game. Each player can turn on `separateScreenCookies` in their
  client config, and a server can force it for everyone with the same field in the server config. Per-screen
  contexts would be stricter, but CEF gives every context its own render process, which is unaffordable with
  many screens.
- **The real domain on the HUD.** Looking at a screen shows its real domain next to the page title. A page
  cannot touch the game's HUD, so a fake sign-in page cannot hide where it actually is.
- **Others' screens can start muted.** With `muteOthersByDefault` on, screens you did not place are silent
  until you raise the slider (remote → More → Others' screens).
- **Limits.** `maxPanelBlocks`, `maxScreensPerPlayer` and `urlCooldownMs` stop somebody building a lag machine
  or cycling addresses. Going over the limit refunds the block instead of placing it.
- **Report.** `/ds report [note]` sends the screen you are looking at to every online admin, with its owner,
  its address and its coordinates, and writes the same to the audit log. The address and owner are read on the
  server, never taken from the client.

One thing worth knowing: both lists match subdomains the same way. Blocking `example.com` also blocks
`www.example.com` and `ads.example.com`.

## Performance
- The picture comes out of Chromium by off-screen rendering: changed regions are uploaded straight to a GPU texture, with no copy. Screens nobody is looking at drop to 10 fps while video and audio keep running.
- The real load is inside Chromium's own processes (video decoding): at most 3 screens are live at once (48 blocks), and the tablet runs at 8 fps when it is not in your hand. The full-frame-rate radius grows with the panel (24 blocks for a 1×1 screen, 3 blocks more per extra block, capped at 48); an off-screen panel drops to 10 fps and a distant one to half. YouTube quality is capped to the screen resolution automatically (720p on a 720p screen; anything above is invisible on the screen and only burns CPU).
- The screen-light surface scan runs on a background thread every 2 s, and drawing it is a few thousand small quads per frame. Negligible.
- If it stutters: measure with `/ds perf`, then try `/ds res 720p` and `/ds fps 30`. With shaders, use fewer screens.

## Safety notes
- Do **not** sign into your main Google or Instagram account in the embedded browser. Use a second, throwaway account with 2FA. Never open banking or email pages on it.
- **YouTube sign-in mode, and its risk.** `/ds ytlogin` opens Google's sign-in page on the screen and, while it
  is on, presents the browser as Firefox. Google blocks sign-in from embedded browsers deliberately, and this
  gets past that block. Google may treat it as a suspicious sign-in and lock the account. Never use your main
  account here. Use a throwaway one, turn on 2FA, and remember that on a server every player signs in to their
  own browser, so nobody inherits your session.
- Every browser (all screens and the tablet) shares one Chromium profile, under `config/mcef-codec/`.
- Binaries are downloaded from the official CinemaMod mirror and verified with SHA-256. `--disable-web-security` is never used.
- For servers: `blockedDomains` / `allowedDomains` give you a content policy, `announce` gives you notices, and the lock gives screens an owner.

## Development
- `JAVA_HOME` must be a Java 25 (the Minecraft launcher's own JDK works). `./gradlew build` → `build/libs/doomscroll-0.1.0.jar`. Build mcef-codec first; `../mcef-codec/build/libs/mcef-codec-0.1.0.jar` is a compileOnly dependency.
- 26.x is unobfuscated, so the code uses real Mojang names. The signatures the mod relies on are listed in the commit that introduced them.
- Architecture: `ScreenBrowsers` (a browser per screen, control, sync, quality) · `Browsers` (tablet and page scripts: reporter, cleaner, cinema) · `DirectControl` (look-and-click, keyboard, pointer) · `ScreenGlow` (ambilight) · `ScreenQueue` / `Pointers` · `RemoteScreen` / `TabletScreen` (GUI) · `Doomscroll` (server: packets, control timeout, lock, address policy) · `ServerConfig` / `AdminCommands` · `ScreenMultiblock` (panel merging, light, redstone).
- **Text:** no user-facing string is hard-coded. Java uses `Component.translatable` or, where a plain `String` is needed, `Lang.tr(key, …)`; the home pages use `{{key}}` (HTML) and `{{js:key}}` (inside a JS string), resolved by `HomePages.translate()`. `en_us.json` and `tr_tr.json` must always hold the same set of keys.
- **Smoke test:** `./gradlew runClient` starts the dev client with `-Ddoomscroll.selftest=true`; on joining the world `SelfTest` builds a 3×2 panel next to the player, opens YouTube and exercises the page reporter, titles, screen light, the queue (skip and end-of-video), a server address block, redstone, admin commands and the pointer, writing `[selftest]` lines to the log before closing the game. `run/config/doomscroll-server.json` must have `example.org` blocked.
- **Art:** block and item textures, GUI icons (`textures/gui/sprites/icon/*.png`, 9×9) and the mod icon are generated by `tools/make_art.py` (the pixel maps are in the script; `pip install pillow`). The interface draw language lives in `Ui.java` (embossed panels and buttons, sunken fields, icons).

## Bug reports
Something broken, or a site that will not play? Open an issue: https://github.com/menntdev/doomscroll/issues
Tell me your Minecraft and mod versions, whether it happens in singleplayer or on a server, and the
address of the page. The log under `.minecraft/logs/latest.log` usually has the answer in it.

## License
**All rights reserved** — see [LICENSE](LICENSE). The source is published so you can read it and see for yourself what a mod that embeds a browser actually does; it is not open source.

You may use it, put it in a modpack (with credit and a link) and change it for yourself. You may not re-upload it, distribute a fork's build, or sell it. Ask in an [issue](https://github.com/menntdev/doomscroll/issues) and permission is usually given.

Third-party components keep their own terms, listed in [NOTICE](NOTICE): the sibling library `mcef-codec` (LGPL-2.1, a separate jar), the Jersey 10 typeface (SIL OFL 1.1) and the held-tablet pose from WebDisplays (public domain).
