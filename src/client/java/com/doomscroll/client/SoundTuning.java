package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.client.mixin.ChannelAccessor;
import com.doomscroll.client.mixin.SoundEngineAccessor;
import com.doomscroll.client.mixin.SoundManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import org.lwjgl.openal.AL10;

/**
 * Calan bir tarayici sesinin OpenAL kaynagina ince ayar: referans mesafesi.
 * Minecraft'in dogrusal azalmasi 0 bloktan baslar (ekranin dibinde bile tam ses degil);
 * referansi birkac blok yapinca ekranin onunde ses sabit kalir, sonra azalir.
 * Sound Physics varsa o kendi modelini kurar, buraya dokunulmaz.
 */
public final class SoundTuning {
	private SoundTuning() {}

	/** Kaynak henuz yoksa false doner (bir sonraki tick'te yeniden dene). */
	public static boolean applyReferenceDistance(SoundInstance instance, float blocks) {
		Minecraft mc = Minecraft.getInstance();
		var engine = ((SoundManagerAccessor) mc.getSoundManager()).doomscroll$soundEngine();
		ChannelAccess.ChannelHandle handle = ((SoundEngineAccessor) engine).doomscroll$instanceToChannel().get(instance);
		if (handle == null) {
			return false;
		}
		handle.execute(channel -> {
			try {
				int source = ((ChannelAccessor) channel).doomscroll$source();
				AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, blocks);
			} catch (Throwable t) {
				Doomscroll.LOGGER.warn("ses referans mesafesi ayarlanamadi", t);
			}
		});
		return true;
	}
}
