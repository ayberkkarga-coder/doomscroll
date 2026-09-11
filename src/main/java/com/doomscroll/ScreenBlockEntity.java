package com.doomscroll;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Ekran blogu verisi. Cok-bloklu panelin yalnizca anchor'u anlamlidir (adres, guc, sahip, kontrol).
 * Kontrol (controller) diske yazilmaz; sunucu her acilista sifirdan verir.
 */
public class ScreenBlockEntity extends BlockEntity {
	// Coklu blok yerlesimi: anchor = dikdortgenin ana blogu (kendisi olabilir)
	private BlockPos anchor;
	private int width = 1;
	private int height = 1;
	private boolean on = true;
	/** Ekranda acik adres (senkron). */
	private String url = "";
	// Sahip: blogu koyan oyuncu (kapatma/kilit yetkisi)
	@Nullable
	private UUID owner;
	private String ownerName = "";
	// Kontrol: ekrani suren oyuncu; adres ve oynatma konumu onun tarayicisindan yayilir
	@Nullable
	private UUID controller;
	private String controllerName = "";
	/** Kilit: yalnizca sahibi (ve yoneticiler) kontrol edebilir. */
	private boolean locked = false;
	/** Ekranin ortak sesi (0..1): odadaki herkes bu seviyeden duyar; dinleyenin kendi kaydiricisi ustune biner. */
	private float volume = 1.0f;
	/** Yayinci: goruntusu herkese aktarilan oyuncu (oturumluk, diske yazilmaz). */
	@Nullable
	private UUID broadcaster;
	private String broadcasterName = "";
	/** Redstone: panelin herhangi bir bloguna gelen sinyalin yukselen kenari ekrani acar/kapatir. */
	private boolean redstone = false;
	/** Son bilinen sinyal durumu (yukselen kenar tespiti). */
	private boolean powered = false;

	public ScreenBlockEntity(BlockPos pos, BlockState state) {
		super(Doomscroll.SCREEN_BE_TYPE, pos, state);
		this.anchor = pos.immutable();
	}

	@Nullable
	public UUID getOwner() { return owner; }
	public String getOwnerName() { return ownerName; }

	/** Sahipsiz ekranda herkes sahip sayilir. */
	public boolean isOwner(UUID uuid) {
		return owner == null || owner.equals(uuid);
	}

	/** Sunucu: sahibi ayarla ve istemcilere yolla. */
	public void setOwner(UUID uuid, String name) {
		this.owner = uuid;
		this.ownerName = name == null ? "" : name;
		sync();
	}

	@Nullable
	public UUID getController() { return controller; }
	public String getControllerName() { return controllerName; }
	public boolean isLocked() { return locked; }
	public boolean isRedstone() { return redstone; }

	@Nullable
	public UUID getBroadcaster() { return broadcaster; }
	public String getBroadcasterName() { return broadcasterName; }

	public boolean isBroadcaster(@Nullable UUID uuid) {
		return uuid != null && uuid.equals(broadcaster);
	}

	/** Sunucu: yayinciyi ayarla/temizle, istemcilere yolla. */
	public void setBroadcaster(@Nullable UUID uuid, @Nullable String name) {
		boolean changed = (uuid == null) != (broadcaster == null) || (uuid != null && !uuid.equals(broadcaster));
		broadcaster = uuid;
		broadcasterName = uuid == null || name == null ? "" : name;
		if (changed) {
			sync();
		}
	}
	public boolean isPowered() { return powered; }

	/** Sunucu: redstone kontrolunu ac/kapat, istemcilere yolla. */
	public void setRedstone(boolean redstone) {
		if (this.redstone != redstone) {
			this.redstone = redstone;
			sync();
		}
	}

	/** Sunucu: sinyal durumunu kaydet (yalnizca diske; istemciyi ilgilendirmez). */
	public void setPowered(boolean powered) {
		if (this.powered != powered) {
			this.powered = powered;
			setChanged();
		}
	}

	public boolean isController(@Nullable UUID uuid) {
		return controller != null && controller.equals(uuid);
	}

	/** Kilitliyse yalnizca sahibi; degilse herkes. (Yonetici istisnasi sunucu tarafinda.) */
	public boolean canControl(UUID uuid) {
		return !locked || isOwner(uuid);
	}

	/** Sunucu: kontrolu ver/al ve istemcilere yolla. */
	public void setController(@Nullable UUID uuid, @Nullable String name) {
		String n = uuid == null || name == null ? "" : name;
		if (Objects.equals(uuid, controller) && n.equals(controllerName)) {
			return;
		}
		controller = uuid;
		controllerName = n;
		sync();
	}

	public float getVolume() { return volume; }

	/** Dil dosyasi anahtari (sunucu metni degil: alan istemci kendi diliyle cozer). */
	public static String volumeKey(float v) {
		return v <= 0.01f ? "gui.doomscroll.volume.off"
				: v <= 0.4f ? "gui.doomscroll.volume.low"
				: v <= 0.75f ? "gui.doomscroll.volume.mid" : "gui.doomscroll.volume.full";
	}

	/** Sunucu: ekranin ortak sesini ayarla ve istemcilere yolla. */
	public void setVolume(float v) {
		float n = Math.max(0f, Math.min(1f, v));
		if (n != volume) {
			volume = n;
			sync();
		}
	}

	/** Sunucu: kilidi ayarla ve istemcilere yolla. */
	public void setLocked(boolean locked) {
		if (this.locked != locked) {
			this.locked = locked;
			sync();
		}
	}

	public boolean isAnchor() {
		return anchor == null || anchor.equals(getBlockPos());
	}

	public BlockPos getAnchor() {
		return anchor == null ? getBlockPos() : anchor;
	}

	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public boolean isOn() { return on; }
	public String getUrl() { return url; }

	/** Sunucu: adresi degistir ve istemcilere yolla. */
	public void setUrl(String url) {
		String u = url == null ? "" : url;
		if (!this.url.equals(u)) {
			this.url = u;
			sync();
		}
	}

	/** Sunucu: yerlesimi gunceller ve istemcilere yollar. */
	public void setLayout(BlockPos anchor, int width, int height) {
		boolean changed = !anchor.equals(this.anchor) || width != this.width || height != this.height;
		this.anchor = anchor.immutable();
		this.width = width;
		this.height = height;
		if (changed) {
			sync();
		}
	}

	/** Sunucu: ac/kapat, istemcilere yolla; panelin tum bloklarinin isigini (LIT) esle. */
	public void setOn(boolean on) {
		if (this.on != on) {
			this.on = on;
			sync();
		}
		if (level != null && !level.isClientSide() && isAnchor()) {
			ScreenMultiblock.applyLit(level, getAnchor(), width, height, on);
		}
	}

	private boolean litChecked = false;

	/** Sunucu tick'i: yuklenince bir kez blok isigini (LIT) guc durumuna esitler (eski dunyalar icin). */
	public void serverTick() {
		if (litChecked || level == null) {
			return;
		}
		litChecked = true;
		if (isAnchor()) {
			BlockState st = getBlockState();
			if (st.hasProperty(ScreenBlock.LIT) && st.getValue(ScreenBlock.LIT) != on) {
				ScreenMultiblock.applyLit(level, getAnchor(), width, height, on);
			}
		}
	}

	private void sync() {
		setChanged();
		if (level != null && !level.isClientSide()) {
			BlockState s = getBlockState();
			level.sendBlockUpdated(getBlockPos(), s, s, 3);
		}
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		BlockPos a = getAnchor();
		output.putInt("ax", a.getX());
		output.putInt("ay", a.getY());
		output.putInt("az", a.getZ());
		output.putInt("w", width);
		output.putInt("h", height);
		output.putBoolean("on", on);
		output.putString("url", url);
		output.putString("owner", owner == null ? "" : owner.toString());
		output.putString("owner_name", ownerName);
		output.putBoolean("locked", locked);
		output.putFloat("volume", volume);
		output.putBoolean("redstone", redstone);
		output.putBoolean("powered", powered);
		// controller bilerek yazilmiyor: oturumluk bilgi (getUpdateTag ile yalnizca istemcilere gider)
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		BlockPos self = getBlockPos();
		anchor = new BlockPos(
				input.getIntOr("ax", self.getX()),
				input.getIntOr("ay", self.getY()),
				input.getIntOr("az", self.getZ())
		);
		width = Math.max(1, input.getIntOr("w", 1));
		height = Math.max(1, input.getIntOr("h", 1));
		on = input.getBooleanOr("on", true);
		url = input.getStringOr("url", "");
		owner = parseUuid(input.getStringOr("owner", ""));
		ownerName = input.getStringOr("owner_name", "");
		locked = input.getBooleanOr("locked", false);
		volume = Math.max(0f, Math.min(1f, input.getFloatOr("volume", 1.0f)));
		redstone = input.getBooleanOr("redstone", false);
		powered = input.getBooleanOr("powered", false);
		controller = parseUuid(input.getStringOr("controller", ""));
		controllerName = input.getStringOr("controller_name", "");
		broadcaster = parseUuid(input.getStringOr("broadcaster", ""));
		broadcasterName = input.getStringOr("broadcaster_name", "");
	}

	@Nullable
	private static UUID parseUuid(String s) {
		if (s == null || s.isEmpty()) {
			return null;
		}
		try {
			return UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Sunucuda yuklu ekran bloklari: acil kapatma ve sayi siniri bunlar uzerinden calisir. */
	private static final java.util.Set<ScreenBlockEntity> SERVER_LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * Yuklu (chunk'i acik) sunucu ekranlarinin kopyasi.
	 * Kaldirilmis ya da dunyasi gitmis girdiler burada ayiklanir: chunk bosaltmasi
	 * setRemoved cagirir ama tek oyunculuda dunya kapanisinda birkac girdi kalabiliyor.
	 */
	public static java.util.List<ScreenBlockEntity> liveOnServer() {
		SERVER_LIVE.removeIf(s -> s.isRemoved() || s.level == null);
		return java.util.List.copyOf(SERVER_LIVE);
	}

	@Override
	public void setLevel(net.minecraft.world.level.Level level) {
		super.setLevel(level);
		if (!level.isClientSide()) {
			SERVER_LIVE.add(this);
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		SERVER_LIVE.remove(this);
		if (level != null && level.isClientSide()) {
			Doomscroll.screenRemoved.accept(getBlockPos());
		}
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
		CompoundTag tag = saveWithoutMetadata(provider);
		tag.putString("controller", controller == null ? "" : controller.toString());
		tag.putString("controller_name", controllerName);
		tag.putString("broadcaster", broadcaster == null ? "" : broadcaster.toString());
		tag.putString("broadcaster_name", broadcasterName);
		return tag;
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
