# Doomscroll

*[English](README.md) · **Türkçe***

Minecraft içinde arkadaşlarınla **birlikte** Reels / Shorts / TikTok / YouTube / film izle. Dünyaya dev ekranlar kur, elinde tablet taşı, kumandayla yönet, sinema salonu yap. Şakaydı, gerçek oldu.

- Minecraft **26.2** · Fabric · Java 25
- Her komutun bir de İngilizce adı var, ikisi karışık kullanılabilir: `/ds ekranlar` = `/ds screens`, `/ds sira temizle` = `/ds queue clear`. Arayüz dili Minecraft'ın dil ayarını izler.
- Gerçek Chromium (CEF 126, H.264/AAC kodekli) — kardeş proje [`mcef-codec`](../mcef-codec) sağlar
- Konumsal ses (panelin sana en yakın noktasından gelir, Sound Physics Remastered ile uyumlu), shader'larda ışık yayan ekran, ekrandaki görüntüye göre renklenen oda (ambilight)

## İçindekiler
1. [Kurulum](#kurulum)
2. [Eşyalar](#eşyalar)
3. [Ekranı kullanmak](#ekranı-kullanmak)
4. [Birlikte izleme](#birlikte-izleme-senkron)
5. [Sinema salonu özellikleri](#sinema-salonu-özellikleri)
6. [Reklam engelleme ve SponsorBlock](#reklam-engelleme-ve-sponsorblock)
7. [Komutlar](#komutlar-ds)
8. [İstemci ayarları](#istemci-ayarları--configdoomscrolljson)
9. [Sunucu yönetici ayarları ve komutları](#sunucu-yönetici-ayarları--configdoomscroll-serverjson)
10. [Moderasyon ve güvenlik](#moderasyon-ve-güvenlik)
11. [Performans](#performans)
11. [Güvenlik notları](#güvenlik-notları)
12. [Geliştirme](#geliştirme)

## Kurulum
1. `fabric-api`, `mcef-codec-0.1.0.jar` ve `doomscroll-0.1.0.jar` dosyalarını `mods/` klasörüne koy.
2. İlk açılışta mcef-codec Chromium ikililerini indirir (~136 MB, SHA-256 doğrulamalı) → `config/mcef-codec/`.
3. Sunucuda oynayacaksan her oyuncuda iki jar da olmalı (sunucuya da `doomscroll` gerekir; tarayıcı yalnızca istemcide çalışır). Modu olmayan biri ekranı boş görür.
4. İlk girişte tek seferlik karşılama mesajı çıkar; `/ds yardim` tüm komutları listeler.

## Eşyalar
Hepsi çalışma masasında yapılır (ekran: cam levha + redstone + demir → 2 adet; kumanda: taş düğme / redstone / demir; tablet: cam levha × 6 + demir, redstone, demir). Yaratıcı envanterde İşlevsel Bloklar sekmesinde.

| Eşya | Ne yapar |
|---|---|
| **Ekran** (`doomscroll:screen`) | Yan yana / üst üste dizince tek panel olur (sağ-alt blok "anchor"), yerleştirince panel boyutu yazılır. Eğilerek (Shift) yere ya da tavana koyunca yer / tavan ekranı. Açıkken ışık yayar. Çerçevesiz. |
| **Kumanda** (`doomscroll:remote`) | Ekrana sağ tık: o ekrana bağlanır. Havaya sağ tık: panel. Shift + sağ tık: bağı keser. Bağlı değilken hiçbir ekranı yönetmez. |
| **Tablet** (`doomscroll:tablet`) | Sağ tık: kendi tarayıcısı. Elde tutulunca 3D görünür, R ile dik/yatay. Ana eldeyken fare tekerleği tablete gider (TikTok/Shorts/Reels: video değiştir, diğer sayfalar: kaydır); eğilerek (Shift) çevirince hotbar değişir. Başkalarının tabletinde onların sayfası görünür. |

## Ekranı kullanmak
- **Bak-tıkla:** el boşken (ya da kumanda/tablet tutarken) ekrana bak: crosshair imleç olur. Sol tık tıklar, tekerlek kaydırır (Shorts/Reels'te bir tık = bir video), sağ tık klavyeyi ekrana bağlar (ESC bırakır), Shift + sağ tık oynat/durdur. Elinde blok varken tıklarsan ipucu çıkar (bloklar ekranın yanına konabilsin diye tıklar oyuna gider).
- **Yazı alanları:** görünür bir yazı alanına tıklayınca klavye otomatik ekrana bağlanır; Enter'a basıp sayfa değişince ya da odak gidince kendiliğinden bırakılır. Sitenin gizlice odakladığı alanlar klavyeni almaz.
- **Kumanda paneli:** kompakt, kabartmalı koyu kumanda gövdesi (vanilla tuş hissi: 1 px kontur, ışık/gölge kenar), yeşil durum ekranı (LCD), üç sekme (KUMANDA / AYARLAR / DİĞER), piksel simgeli tuşlar ve ses kaydırıcısı.
  - **KUMANDA:** güç, sessiz + ses, Shorts/Reels/TikTok, kanal (◀ ad ▶), geri/ileri/yenile/ana/sinema, adres çubuğu + git (ok) + **+** (sıraya ekle), "Ekranı tablete yansıt" (ekrandaki sayfa, YouTube'da kaldığı saniyeden, tablette açılır; tablet → ekran yönü tabletin **Yansıt** tuşunda).
  - **AYARLAR:** otomatik geçiş, film senkronu, reklam engelle, altyazı (HUD), YouTube giriş modu, kilit, çözünürlük, kare hızı, ses gecikmesi.
  - **DİĞER:** SponsorBlock, paylaşımlı işaretçi, redstone, yayın, ekran ışığı, yumuşak ışık, ışık menzili, sıra → **sıra listesi** sayfası (başlıklarıyla; oynat / sil / temizle, tekerlekle kaydır).
  - Ekran (LCD) satırları: durum · sahip · ses, sayfa başlığı + süre (+N sıradaki), kontrol/kilit.
- **Yeni bir ekrana bakınca** 1 sn sonra "▶ başlık · süre · kontrol" bildirimi çıkar.
- **Sinema modu:** önce sitenin kendi tam ekranı denenir (sayfa tek kullanımlık bir kapak koyar, mod oraya tıklatır; böylece tarayıcının istediği "kullanıcı hareketi" sağlanır), olmazsa en büyük oynatıcı CSS ile ekrana sabitlenir (`/ds sinema`, kumanda ve tablette Sinema). Yeni pencere açmaya çalışan bağlantılar aynı sitedeyse aynı ekranda açılır, yabancı popup'lar (reklam) engellenir.
- **Tablet araç çubuğu:** geri/ileri/yenile, Ana, Sinema, **★ yer imi** (açık sayfayı ekle/çıkar), **≡ menü** (yer imleri + geçmiş paneli: tıkla aç, ✕ sil, çöp kutusu geçmişi temizler), adres, **Yansıt** (sayfayı baktığın / en yakın ekrana yolla), **Sıraya** (sayfayı ekranın video sırasına ekle). Yer imleri ve son 40 sayfa `config/doomscroll-tablet.json` dosyasında.

## Birlikte izleme (senkron)
- Ekranın adresi sunucuda saklanır; herkesin istemcisi aynı sayfayı kendi tarayıcısında açar (CinemaMod modeli). Dünyadan çıkıp girince kaldığı yerden devam eder.
- **Kontrolcü:** her ekranı o an bir kişi sürer. Bilerek yapılan her eylem (kumanda, tıklama, tekerlek, adres) kontrolü alır; yalnızca kontrolcünün tarayıcısı adres yayar ve otomatik geçiş yapar. Kontrolcü uzaklaşır/çıkar/sessiz kalırsa (varsayılan 45 sn, sunucu ayarı) kontrol boşa düşer.
- **Kilit:** ekranı koyan kişi kilitlerse yalnızca o (ve op'lar) kontrol edebilir.
- **Konum senkronu:** uzun videolarda (≥30 sn) izleyiciler kontrolcünün saniyesine hizalanır (en fazla 2,5 sn fark), duraklatma eşlenir. Kısa döngülü videolarda devre dışı. Film sitelerinde oynatıcı iframe içinde olsa da çalışır.
- **Video sırası:** `/ds sira ekle <adres>` (ya da kumandadaki **+**, tabletteki **Sıraya**) ile kuyruğa video eklenir; oynayan video bitince sıradaki otomatik açılır ve herkes aynı adrese geçer. `/ds sira` listeler, `/ds sira atla` hemen geçer, `/ds sira sil <no>`, `/ds sira temizle`. Reklam/önizleme videolarının bitişi kuyruğu tetiklemez. Sıra oturumluktur.
- **Paylaşımlı işaretçi:** ekrana bakan oyuncunun crosshair'i, aynı ekrana bakan diğerlerine renkli bir nokta olarak görünür ("şuraya bak"). Sunucu saniyede en fazla ~16 konum yayınlar, 48 blok menzil. `/ds isaretci` ya da Diğer sekmesi.
- **Yansıt:** tabletteki sayfayı baktığın / en yakın ekrana yolla.
- **HUD altyazısı:** videoda altyazı açıksa (YouTube CC, HTML5 altyazı parçaları, oynatıcı altyazı katmanı, iframe içindeki oynatıcılar dahil) metin oyunun içinde envanter çubuğunun üstünde görünür; ekrana bakman gerekmez. Yalnızca ekranda gerçekten görünen satırlar alınır.
- Her oyuncu kendi CEF profilinde (çerezlerinde) izler: sadece adres ve konum paylaşılır, hesap paylaşılmaz. Herkes aynı sayfayı görür ama piksel piksel aynı görüntüyü değil (giriş durumu, reklamlar, öneriler kişiseldir; film sitesinde "dublaj/altyazı" seçimini herkes kendi yapar).

## Ana menüler
- Yeni yerleştirilen ekran ve tablet, siteye değil **ana menüye** açılır (`doomscroll://home/screen`, `doomscroll://home/tablet`; kumanda ve tabletteki "Ana" tuşu da buraya döner).
- **Ekran menüsü** (Smart TV launcher): üstte marka, arama (adres değilse Google) ve saat; büyük **hero** alanı (sırada video varsa küçük resmi ve başlığıyla "Şimdi oynat", yoksa günün selamı ve Shorts daveti); yatay raylar: Uygulamalar (YouTube, Shorts, Reels, TikTok, Twitch, Kick), Kanallar (+ Kanal ekle, ✕), Sırada (YouTube küçük resimli kartlar; tıkla oynat, ✕ çıkar). Üzerine gelince TV odak halkası.
- **Tablet menüsü** (iPad ana ekranı): duvar kâğıdı, durum çubuğu (saat, tarih), arama hapı, yuvarlak köşeli uygulama simgeleri, **Geçmiş** widget'ı ("Temizle"), yer imleri ve kanallar web-klip simgesi olarak (üzerine gelince ✕ ile silinir), altta dock (YouTube, Shorts, Reels, TikTok, Twitch). Dik modda 4 sütun.
- Her iki menüde **+ Ekle** simgesi: ad ve adres girince tablete yer imi, ekrana kanal olarak eklenir (üzerine gelince ✕ ile silinir). Yıldız ve `/ds kanal ekle` de aynı listeleri besler.
- Sayfa mod içinden üretilir (`assets/doomscroll/home/home.html` + `HomePages`), internete gitmez; sunucu adres kuralları `doomscroll://` için hep izinli.

## Yere ve tavana ekran
- Normal yerleştirme duvar ekranıdır (sana bakar). **Eğilerek (Shift)** bir bloğun üst yüzüne koyarsan **yer ekranı**, alt yüzüne koyarsan **tavan ekranı** olur.
- Resmin üst kenarı: yer ekranında baktığın yön (masa gibi, uzak kenar üst); tavan ekranında baktığın yönün tersi (sırt üstü yatıp bakana doğru). Ters gelirse kırıp diğer yöne bakarak koy.
- Bitişik bir ekranın düzlemine koyduğun blok onun yönünü alır: paneli uzatırken nereye baktığın önemli değil, eğilmene de gerek yok.
- Yer ekranı tavanı ve duvarları, tavan ekranı zemini aydınlatır; bak-tıkla, işaretçi ve ses aynı şekilde çalışır.

## Yayın modu (herkes aynı görüntüyü görsün)
Normalde herkes aynı adresi kendi tarayıcısında açar (sıfır ek maliyet). Film siteleri gibi kişiye göre değişen sayfalarda ya da "tam olarak benim gördüğümü görsünler" istediğinde **yayın modu**: `/ds yayin ac` (ya da kumanda → Diğer → Yayın). Senin ekranındaki video bir canvas'a küçültülerek çizilir, videonun ses iziyle birlikte Chromium'un kendi kodlayıcısıyla (VP8 + Opus, WebM) 500 ms'lik parçalara kodlanır; parçalar sunucu üzerinden aynı ekrana bakan herkese dağıtılır. İzleyicilerin ekranı küçük bir alıcı sayfası açar (MediaSource) ve canlı uca yakın oynatır (~1-2 sn gecikme). Yeni izleyici gelince kodlayıcı yeniden başlar (anahtar kare), yayıncı çıkınca ya da `/ds yayin kapat` deyince herkes sunucudaki adrese geri döner.
- Maliyet yayıncıda (kodlama, ~Discord ekran paylaşımı kadar) ve sunucuda (izleyici başına ~150 KB/s). Kalite ön ayarı `/ds yayin kalite dusuk|normal|yuksek` (640×360/20 fps/700 kbps · 960×540/24/1200 · 1280×720/30/2500; yayın sürerken de değişir) ya da `config/doomscroll.json`: `broadcastWidth`, `broadcastFps`, `broadcastKbps`. Sunucu yayıncı başına saniyede 40 dilimden fazlasını atar. Sunucu tarafında `broadcast` ile kapatılabilir.
- Sınır: Chromium, CORS'suz doğrudan MP4 kaynaklarını güvenlik gereği siyah yakalar; YouTube ve HLS/MSE kullanan oynatıcılar (çoğu film sitesi) sorunsuz.

## Sinema salonu özellikleri
- **Ekran ışığı (ambilight):** ekranın önündeki duvar, zemin ve tavan yüzeyleri ekranın o bölgesindeki renkle aydınlanır; uzaklaştıkça tüm ekranın ortalamasıyla karışır. Panel bir alan ışığı gibi davranır: yüzeyler kenarla aynı hizada olsa da ışık alır. Tamamen istemci tarafındadır, shader paketleriyle de çalışır (ekranla aynı "emissive" çizim yolu). Yüzey listesi arka planda 2 sn'de bir hesaplanır, görüş çizgisi kontrolüyle duvar arkasına ışık sızmaz; renkler her karede tarayıcının 8×5 renk haritasından alınır, ton korunarak parlaklık yükseltilir. Ayar: Ayarlar → "Ekran ışığı" (kapalı / az / normal / çok) ve "Yumuşak ışık" (açık: komşu yüzeyler arasında kesintisiz geçiş, kapalı: blok blok mozaik); `/ds isik kapat|az|normal|cok|yumusak|menzil <2-24>`; Diğer → ışık menzili. `/ds isik` teşhis bilgisi verir (yama sayısı, ekran ortalama rengi).
- **Redstone kontrolü:** ekran sahibi `/ds redstone` (ya da Diğer → Redstone) ile açar: panelin herhangi bir bloğuna gelen sinyalin yükselen kenarı ekranı açar/kapatır. Tek levhayla ışıkları söndürüp ekranı açmak için.
- **Blok ışığı:** açık ekran 12 seviye ışık yayar (sunucu ayarı `screenLightLevel`).

## Reklam engelleme ve SponsorBlock
- **Filtre listeleri (gerçek engelleyici mantığı):** [EasyList](https://easylist.to) ve [AdGuard Türkçe filtresi](https://filters.adtidy.org/extension/ublock/filters/13.txt) tam kural diliyle (alan + yol kalıpları, `$third-party`, tür, `domain=`, `$popup`, `@@` istisnalar, `$generichide`, `@@$document`) istek düzeyinde uygulanır; [StevenBlack hosts](https://github.com/StevenBlack/hosts) alan adı listesi eklenir. Listelerin `##` kozmetik gizleme kuralları (site özel + genel) her sayfaya ve iframe'e stil olarak enjekte edilir. `config/mcef-codec/adblock/`, 7 günde bir yenilenir. Video CDN'leri izin listesindedir; sayfanın kendini reklam/bahis sitesine yönlendirmesi engellenir, adres çubuğundan yazılan adres engellenmez.
- **Sayfa içi temizleyici (film siteleri):** standart boyutlu (300×250, 728×90...) reklam iframe'leri gizlenir; videonun üstündeki tıklama kapanları kaldırılır, "Reklamı geç / Skip ad / Atla" düğmesi otomatik basılır. Oynatıcı katmanları (ytp, vjs, jw, plyr...) dokunulmaz; YouTube, Instagram, TikTok, Twitch gibi büyük sitelerde bu temizleyici hiç çalışmaz.
- **Popup'lar:** hiçbir yeni pencere kendiliğinden açılmaz; "Popup engellendi: alan adı" bildirimi çıkar, gerçekten açmak istersen `/ds popup`.
- **YouTube reklamları** aynı alan adından geldiği için listeyle engellenemez; sayfa içi atlayıcı reklam başlayınca sonuna sarar ve "Atla"ya basar.
- **SponsorBlock:** YouTube videolarındaki sponsor, kendi reklamı, "abone ol" hatırlatması, intro, outro ve önizleme bölümleri [SponsorBlock](https://sponsor.ajay.app) topluluk verisiyle otomatik atlanır ("⏩ Sponsor bölümü atlandı"). Ekranda ve tablette çalışır. Diğer → SponsorBlock ya da `/ds sponsor`.
- Instagram/TikTok akış içi reklamlar sıradan içerik gibi geldiği için engellenmez.
- Aç/kapat: Ayarlar → **Reklam engelle** ya da `/ds reklam ac|kapat`; `/ds reklam` yüklü kural sayısını ve engellenen istek sayısını gösterir; log'da `[reklam] engellendi: alan/yol` (ilk 20).

## Komutlar (`/ds`)
| Komut | |
|---|---|
| `/ds yardim` | tüm liste |
| `/ds ekranlar` | yakındaki ekranlar: uzaklık, boyut, sahip, kontrol, kilit, sayfa başlığı |
| `/ds go <adres>` · `shorts` · `reels` · `tiktok` | ekrana aç |
| `/ds kontrol al\|birak\|kilit` | kontrol / kilit |
| `/ds sira [ekle <adres>\|liste\|sil <no>\|temizle\|atla]` | video sırası |
| `/ds kanal liste\|ekle "Ad" [adres]\|sil Ad` | kumanda kanalları (adres yoksa açık sayfa) |
| `/ds senkron` · `/ds auto` | konum senkronu / otomatik geçiş |
| `/ds sinema` · `/ds pause` | tam ekran / oynat-durdur |
| `/ds isik kapat\|az\|normal\|cok\|yumusak\|menzil <blok>` | ekran ışığı |
| `/ds yayin [ac\|kapat]` | yayın modu (senin görüntün herkese) |
| `/ds sponsor` · `/ds isaretci` · `/ds redstone` | SponsorBlock / paylaşımlı işaretçi / bu ekranda redstone (sahibi) |
| `/ds res 720p\|1080p\|1440p\|GxY` · `/ds audiorate <hz>` | çözünürlük / ses örnekleme hızı |
| `/ds fps <10-60>` · `/ds perf` | tarayıcı kare hızı / performans ölçümü |
| `/ds boost <0.5-6>` | tarayıcı ses kazancı (varsayılan 2.5; tepeler yumuşak sınırlanır) |
| `/ds gecikme dusuk\|normal\|yuksek` | ses gecikmesi profili: düşük ~150 ms, normal ~180 ms, yüksek ~320 ms |
| `/ds reklam [ac\|kapat]` · `/ds popup` | reklam engelleyici / son engellenen popup'ı aç |
| `/ds altyazi` | HUD altyazısını göster/gizle |
| `/ds ytgiris` | Google girişi için Firefox kimliği |
| `/ds rapor [not]` | baktığın ekranı yöneticilere bildir |
| `/ds remote` · `/ds tablet` · `/ds debug` | panel / tablet / durum |
| `/ds play <reels/tiktok linki>` | (eski) yt-dlp + ffmpeg ile indirip yerel oynatıcıda oynatır |

## İstemci ayarları — `config/doomscroll.json`
| Alan | Varsayılan | |
|---|---|---|
| `screenWidth`, `screenHeight` | 1280×720 | ekran tarayıcısı çözünürlüğü (akış kalitesini de belirler) |
| `audioSampleRate` | 0 | 0 = otomatik ölç; ses ince/kalınsa 44100 ya da 48000 |
| `browserFps` | 60 | bakılan ekranın boyama kare hızı; görüş dışındakiler otomatik 10'a, uzaktakiler yarıya düşer |
| `audioBoost` | 2.5 | tarayıcı sesine dijital kazanç |
| `audioLatency` | normal | `dusuk` / `normal` / `yuksek` |
| `adBlock` | true | reklam engelleyici |
| `sponsorBlock` | true | SponsorBlock |
| `subtitles` | true | HUD altyazısı (24 blok içindeki en yakın ekran) |
| `pointer` | true | paylaşımlı işaretçi (gönder + göster) |
| `screenVolume`, `screenMuted` | 0.8 | ekranların kişisel sesi (kumandadaki kaydırıcı) |
| `tabletVolume`, `tabletMuted` | 0.8 | elindeki tabletin kendi sesi (tablet araç çubuğundaki hoparlör) |
| `remoteTabletVolume` | 0.8 | başkalarının tabletinden duyulan ses (0 = duyma) |
| `othersScreenVolume` | 1.0 | senin koymadığın ekranların sesi (sunucu bunu 0'dan başlatabilir) |
| `separateScreenCookies` | true | ekranlar tabletin kalıcı profilinden ayrı, bellekteki bir Chromium bağlamında çalışır |

Ses üç kademeli: **cihazın sesi** (tabletin kaydırıcısı, herkes o seviyeden duyar; ekranınki blokta durur, kumanda → Diğer → "Ekranın sesi"), üstüne **senin kaydırıcın**, en üstte Minecraft'ın "Bloklar" ayarı.
| `tabletSway` | 0.35 | tablet eldeyken yürüme sallanmasının kalan oranı (0 sabit, 1 vanilla) |
| `screenGlow` / `screenGlowRange` / `screenGlowSmooth` | 1.0 / 10 / true | ekran ışığı yoğunluğu (0 kapalı, 0.5 az, 1 normal, 1.8 çok), menzil, yumuşak geçiş |
| `autoScroll` | true | video bitince sonraki (Shorts/Reels/TikTok) |
| `syncPlayback` | true | kontrolcünün konumuna hizalan |
| `channels` | 6 kanal | `{ "name": "...", "url": "..." }` listesi |
| `userAgent` | Chrome/128 | tarayıcı kimliği |
| `tvLogin` | false | YouTube giriş modu |
| `welcomeShown` | false | karşılama mesajı gösterildi |

## Sunucu yönetici ayarları — `config/doomscroll-server.json`
Tek oyunculuda da aynı dosya (iç sunucu). Alanlar:

| Alan | Varsayılan | Açıklama |
|---|---|---|
| `blockedDomains` | `[]` | Engelli alan adları (alt alanlar dahil). Açılmaya çalışılınca "Bu adres sunucuda engelli" uyarısı, ekran eski adresinde kalır. |
| `allowedDomains` | `[]` | Boş değilse yalnızca bu alan adları açılabilir (beyaz liste). |
| `screenLightLevel` | `12` | Açık ekranın blok ışığı (0-15), yeniden başlatma ister. |
| `announce` / `announceRange` | `true` / `32` | "X ekranda site açtı" bildirimi ve menzili. |
| `redstoneControl` | `true` | Redstone ile açma/kapama izni. |
| `pointer` | `true` | Paylaşımlı işaretçi yayını. |
| `broadcast` | `true` | Yayın moduna izin. |
| `controlTimeoutSeconds` | `45` | Sessiz kalan kontrolcünün kontrolü kaybettiği süre. |
| `auditLog` | `true` | Açılan her adresi `config/doomscroll-audit.log` dosyasına yaz. |
| `lockdown` | `false` | Acil kapatma: bütün ekranlar karanlık ve açılamaz. `/doomscroll acil` ile değişir. |
| `allowPrivateNetwork` | `false` | Loopback ve yerel ağ adreslerine izin. Kapalı bırak: açıksa biri ekrana `192.168.1.1` koyup herkese kendi modem arayüzünü açtırabilir. |
| `requireConsent` | `false` | İzin listesinde olmayan sayfa, izleyici Göster diyene kadar çizilmez. O ana kadar siteye hiçbir istek gitmez. |
| `muteOthersByDefault` | `false` | Senin koymadığın ekranlar sende sessiz başlar. |
| `showDomain` | `true` | Ekrana bakınca gerçek alan adı HUD'da yazsın. |
| `maxPanelBlocks` | `0` | En büyük panel, blok olarak (0 = sınırsız). |
| `maxScreensPerPlayer` | `0` | Bir oyuncunun sahip olabileceği ekran bloğu (0 = sınırsız). |
| `urlCooldownMs` | `0` | Aynı oyuncunun iki adres değişikliği arasındaki en az süre (0 = beklemesiz). Halka açık sunucuda 1000-2000. |

**Yönetici komutları** (`/doomscroll ...`, gamemaster yetkisi): `/doomscroll` durum · `yenile` · `engelle <alan>` / `engelkaldir <alan>` · `izin <alan>` / `izinkaldir <alan>` · `isik <0-15>` · `duyuru ac|kapat` · `isaretci ac|kapat` · `redstone ac|kapat` · `kontrolsuresi <sn>` · `kayit [n]` · `denetim ac|kapat` · `acil ac|kapat` · `karart` · `ozelag ac|kapat` · `onay ac|kapat` · `sessiz ac|kapat` · `alanadi ac|kapat` · `panelsinir <n>` · `ekransinir <n>` · `bekleme <ms>` · `yayin ac|kapat`. Her komutun İngilizce adı da var (`reload`, `block`, `audit`, `emergency`, `blackout` ...). Değişiklikler dosyaya yazılır ve anında bütün istemcilere gider.

## Moderasyon ve güvenlik

Sunucu sahiplerinin sorunu genelde modun ne yaptığı değil, istemcinin yetkili olması ve olay olduktan sonra
ellerinde hiçbir şey kalmaması. Bunun için olan araçlar şunlar.

- **Denetim kaydı.** Açılan her adres, engellenen her deneme, her yayın ve açma/kapama `config/doomscroll-audit.log`
  dosyasına kim, ne zaman, hangi dünya, hangi blok olarak yazılır. `/doomscroll kayit [n]` son kayıtları sohbete
  döker. Adres hiçbir zaman biçim dizesi olarak kullanılmaz, yani içinde `%` olan bir adres logu kıramaz.
- **Acil kapatma.** `/doomscroll acil ac` yüklü bütün ekranları karartır ve hiçbirinin açılmasına izin vermez.
  `/doomscroll karart` kilitsiz, tek seferlik hâli. İkisi de anında etki eder, kimsenin yeniden girmesi gerekmez.
- **Yönlendirme denetimi.** Sunucu paylaşılan adresi denetler ama yönlendirme önce tarayıcıda olur. Alan adı
  politikası her istemciye girişte ve her değişiklikte gider; böylece her tarayıcı aynı kuralı her adres
  değişikliğinde uygular ve kısaltılmış bir bağlantı engelli siteye hiç ulaşamaz. Değiştirilmiş bir istemci bunu
  yok sayabilir, sunucudaki denetim yine geçerlidir.
- **Yerel ağ adresleri reddedilir.** Loopback, `10/8`, `172.16/12`, `192.168/16`, link-local (`169.254.169.254`
  bulut metadata dahil), operatör NAT, IPv6 benzersiz yerel ve link-local, `.local` ve noktasız iç ağ adları.
  Bu olmasa ekrandaki `192.168.1.1` her izleyicinin kendi modem arayüzünü açardı. Kendi LAN'ında denemek için
  `allowPrivateNetwork` ile geri açabilirsin.
- **İzinler.** fabric-permissions-api konuşan bir izin yöneticisi (LuckPerms gibi) kuruluysa
  `doomscroll.place`, `doomscroll.url`, `doomscroll.broadcast`, `doomscroll.bypass` ve `doomscroll.admin`
  düğümleri geçerli olur. Yoksa ilk üçü herkese açık, son ikisi oyun yöneticisine. Ek bağımlılık yok: API
  çalışma anında aranır, bulunamazsa atlanır.
- **İzleyici onayı.** `requireConsent` açıkken izin listesinde olmayan sayfa yerine alan adını ve ekranı koyan
  oyuncuyu yazan bir kart çıkar; Göster ve Ana menü düğmeleri var. Sen Göster demeden siteye hiçbir istek
  gitmez, yani ne şok içerik ne de IP'n oraya ulaşır. Seçimin o oturum boyunca alan adı başına hatırlanır.
- **Çerez ayrımı.** Ekranlar, tabletin kullandığı kalıcı profilden ayrı, bellekte duran ortak bir Chromium
  bağlamını paylaşır. Başkasının açtığı bir sayfa senin giriş yaptığın oturumla aynı bağlamda çalışmaz ve
  ekranlar için diske hiçbir şey yazılmaz. Ekrandaki YouTube girişinin yeniden başlatmadan sonra da kalmasını
  istersen istemci ayarındaki `separateScreenCookies` kapatılabilir. Ekran başına ayrı bağlam daha sıkı olurdu
  ama CEF her bağlama ayrı bir render süreci açıyor; çok ekranda bu kaldırılmaz.
- **Gerçek alan adı HUD'da.** Ekrana bakınca sayfa başlığının yanında gerçek alan adı yazar. Sayfa oyunun
  arayüzüne dokunamaz, yani sahte bir giriş sayfası nerede olduğunu gizleyemez.
- **Başkasının ekranı sessiz başlayabilir.** `muteOthersByDefault` açıkken senin koymadığın ekranlar sen
  kaydırıcıyı açana kadar sessizdir (kumanda → Diğer → Başkalarının ekranı).
- **Sınırlar.** `maxPanelBlocks`, `maxScreensPerPlayer` ve `urlCooldownMs` lag makinesi kurulmasını ve adres
  döndürmeyi engeller. Sınırı aşan yerleştirmede blok konmaz, eşya iade edilir.
- **Rapor.** `/ds rapor [not]` baktığın ekranı sahibi, adresi ve koordinatıyla çevrimiçi bütün yöneticilere
  yollar ve aynısını denetim kaydına yazar. Adres ve sahip sunucuda okunur, istemciden alınmaz.

Bir de not: izin listesi alt alan adlarını doğru kapsıyor. `example.com` engellendiğinde `www.example.com` ve
`ads.example.com` da engelli olur.

## Performans
- Ekran görüntüsü Chromium'dan "ekran dışı çizim" ile alınır: değişen bölgeler doğrudan GPU dokusuna yüklenir (kopya yok). Bakılmayan ekranlar 10 fps'e düşer, video ve ses sürer.
- Asıl yük Chromium'un kendi süreçlerinde (video çözme): aynı anda en fazla 3 ekran canlı (48 blok), tablet elde değilken 8 fps. Tam kare hızı yarıçapı panel boyuyla büyür (1×1 ekranda 24 blok, her ek blok 3 blok daha, 48 blokta durur); görüş dışındaki ekran 10 fps'e, uzaktaki yarıya düşer. YouTube kalitesi ekran çözünürlüğüne göre kendiliğinden sınırlanır (720p ekranda 720p; üstü ekranda görünmez, yalnızca işlemciyi yorar).
- Ekran ışığı yüzey taraması arka plan iş parçacığında (2 sn'de bir), çizimi kare başına birkaç bin küçük kare; ihmal edilebilir.
- Takılma varsa: `/ds perf` ile ölç, `/ds res 720p` (çözünürlük), `/ds fps 30` dene; shader kullanıyorsan ekran sayısını azalt.

## Güvenlik notları
- Gömülü tarayıcıya **ana Google/Instagram hesabını girme**; ikinci/atılabilir hesap + 2FA kullan. Bankacılık, e-posta gibi sayfaları hiç açma.
- Tüm tarayıcılar (ekranlar, tablet) aynı Chromium profilini paylaşır; profil `config/mcef-codec/` altındadır.
- İkililer resmi CinemaMod mirror'ından SHA-256 ile doğrulanarak iner; `--disable-web-security` kullanılmaz.
- Sunucular için: `blockedDomains` / `allowedDomains` ile içerik politikası, `announce` ile bildirimler, kilit ile ekran sahipliği.

## Geliştirme
- `JAVA_HOME` = Java 25 (Minecraft launcher'ın kendi JDK'sı olur). `./gradlew build` → `build/libs/doomscroll-0.1.0.jar`. mcef-codec önce derlenmeli (`../mcef-codec/build/libs/mcef-codec-0.1.0.jar` compileOnly bağımlılık).
- 26.x obfuscate edilmemiş: gerçek Mojang adlarıyla yazılır. İmza notları: `api-notes*.txt`.
- Mimari: `ScreenBrowsers` (ekran başına tarayıcı, kontrol, senkron, SponsorBlock, kalite) · `Browsers` (tablet + sayfa JS'leri: raporcu, temizleyici, sinema) · `DirectControl` (bak-tıkla, klavye, işaretçi) · `ScreenGlow` (ambilight) · `ScreenQueue` / `SponsorBlock` / `Pointers` · `RemoteScreen` / `TabletScreen` (GUI) · `Doomscroll` (sunucu: paketler, kontrol süresi, kilit, adres kuralı) · `ServerConfig` / `AdminCommands` · `ScreenMultiblock` (panel birleştirme, ışık, redstone).
- **Duman testi:** `./gradlew runClient` dev istemciyi `-Ddoomscroll.selftest=true` ile açar; `SelfTest` dünyaya girince oyuncunun yanına 3×2 panel kurar, YouTube açar ve sayfa raporu, başlık, SponsorBlock atlama, ekran ışığı, sıra (atla + video bitince), sunucu adres engeli, redstone, yönetici komutları ve işaretçi adımlarını sınar; sonucu `[selftest]` satırlarıyla loga yazar ve oyunu kapatır (`run/config/doomscroll-server.json` içinde `example.org` engelli olmalı).
- **Tasarım:** blok/eşya dokuları, GUI simgeleri (`textures/gui/sprites/icon/*.png`, 9×9) ve mod simgesi `tools/make_art.py` ile üretilir (piksel haritaları betiğin içinde; `pip install pillow`). Arayüz çizim dili `Ui.java` (kabartma panel/tuş, çukur alan, simge).
- Gece geliştirme raporları ve kararlar: [GECE_RAPORU.md](GECE_RAPORU.md).

## Lisans
MIT — tam metin [LICENSE](LICENSE) dosyasında, üçüncü taraf bildirimleriyle birlikte. Chromium'u sağlayan
kardeş kütüphane `mcef-codec` LGPL-2.1'dir (kendi `COPYING.LESSER` dosyasıyla dağıtılır).
