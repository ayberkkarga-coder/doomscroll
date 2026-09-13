package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.client.mixin.ChannelAccessor;
import com.doomscroll.client.mixin.SoundEngineAccessor;
import com.doomscroll.client.mixin.SoundManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Sound Physics Remastered bridge (soft dependency, via reflection).
 * SPR computes walls/underwater/reverb once when a sound starts; since our sound streams for hours,
 * we make it recompute periodically, the way Simple Voice Chat does:
 * setLastSoundCategoryAndName -> onPlayReverb -> onPlaySound (with the OpenAL source id).
 */
public final class SoundPhysicsBridge {
	private static final boolean AVAILABLE;
	private static MethodHandle setCategory;
	private static MethodHandle onPlayReverb;
	private static MethodHandle onPlaySound;

	static {
		boolean ok = false;
		try {
			Class<?> sp = Class.forName("com.sonicether.soundphysics.SoundPhysics");
			MethodHandles.Lookup l = MethodHandles.publicLookup();
			setCategory = l.findStatic(sp, "setLastSoundCategoryAndName", MethodType.methodType(void.class, SoundSource.class, Identifier.class));
			onPlayReverb = l.findStatic(sp, "onPlayReverb", MethodType.methodType(void.class, double.class, double.class, double.class, int.class));
			onPlaySound = l.findStatic(sp, "onPlaySound", MethodType.methodType(void.class, double.class, double.class, double.class, int.class));
			ok = true;
			Doomscroll.LOGGER.info("Sound Physics Remastered found: periodic environment computation enabled for browser audio");
		} catch (Throwable t) {
			Doomscroll.LOGGER.info("Sound Physics Remastered not present ({}), browser audio is plain positional", t.getClass().getSimpleName());
		}
		AVAILABLE = ok;
	}

	private SoundPhysicsBridge() {}

	public static boolean available() {
		return AVAILABLE;
	}

	/** Makes SPR recompute the environment (walls, water, reverb) for the OpenAL source of a playing sound. */
	public static void refresh(SoundInstance instance, Vec3 pos, Identifier name) {
		if (!AVAILABLE || pos == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		var engine = ((SoundManagerAccessor) mc.getSoundManager()).doomscroll$soundEngine();
		ChannelAccess.ChannelHandle handle = ((SoundEngineAccessor) engine).doomscroll$instanceToChannel().get(instance);
		if (handle == null) {
			return;
		}
		final SoundSource src = instance.getSource();
		// Run on the sound engine thread (OpenAL calls are made there)
		handle.execute(channel -> {
			try {
				int source = ((ChannelAccessor) channel).doomscroll$source();
				setCategory.invoke(src, name);
				onPlayReverb.invoke(pos.x, pos.y, pos.z, source);
				onPlaySound.invoke(pos.x, pos.y, pos.z, source);
			} catch (Throwable t) {
				Doomscroll.LOGGER.warn("Sound Physics call failed", t);
			}
		});
	}
}
