package com.doomscroll.client;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.resources.Identifier;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * The in-world source of a browser's sound. Screen: positional (anchor centre, attenuates with distance);
 * tablet: relative to the listener, the same everywhere.
 */
public final class BrowserSoundInstance extends AbstractTickableSoundInstance {
	@Nullable
	private final Supplier<Vec3> position; // null -> relative
	private final Supplier<Float> volumeSupplier;
	private final Supplier<Boolean> alive;
	@Nullable
	private final Identifier customSound; // remote tablet: sounds/remote/<uuid>.ogg

	public BrowserSoundInstance(SoundEvent event, @Nullable Supplier<Vec3> position,
								Supplier<Float> volumeSupplier, Supplier<Boolean> alive) {
		this(event, position, volumeSupplier, alive, null);
	}

	public BrowserSoundInstance(SoundEvent event, @Nullable Supplier<Vec3> position,
								Supplier<Float> volumeSupplier, Supplier<Boolean> alive, @Nullable Identifier customSound) {
		// BLOCKS: Sound Physics does not process the RECORDS (jukebox) category by default
		super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
		this.position = position;
		this.volumeSupplier = volumeSupplier;
		this.alive = alive;
		this.customSound = customSound;
		this.looping = false;
		this.delay = 0;
		this.volume = volumeSupplier.get();
		if (position == null) {
			this.relative = true;
			this.attenuation = Attenuation.NONE;
			this.x = 0;
			this.y = 0;
			this.z = 0;
		} else {
			this.relative = false;
			this.attenuation = Attenuation.LINEAR;
			Vec3 p = position.get();
			if (p != null) {
				this.x = p.x;
				this.y = p.y;
				this.z = p.z;
			}
		}
	}

	@Override
	public void tick() {
		if (!alive.get()) {
			stop();
			return;
		}
		this.volume = volumeSupplier.get();
		if (position != null) {
			Vec3 p = position.get();
			if (p != null) {
				this.x = p.x;
				this.y = p.y;
				this.z = p.z;
			}
		}
	}

	/** For remote tablets the sound file path is per player: SoundBufferLibraryMixin finds the right browser from this path. */
	@Override
	public Sound getSound() {
		if (customSound != null) {
			return new Sound(customSound, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1, Sound.Type.FILE, true, false, 16);
		}
		return super.getSound();
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}
}
