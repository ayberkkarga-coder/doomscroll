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
 * Fine-tuning of a playing browser sound's OpenAL source: the reference distance.
 * Minecraft's linear attenuation starts at 0 blocks (not even full volume right next to the screen);
 * with a reference distance of a few blocks the sound stays constant in front of the screen, then falls off.
 * When Sound Physics is present it sets up its own model and this is left alone.
 */
public final class SoundTuning {
	private SoundTuning() {}

	/** Returns false when the source does not exist yet (retry on the next tick). */
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
				Doomscroll.LOGGER.warn("could not set the sound reference distance", t);
			}
		});
		return true;
	}
}
