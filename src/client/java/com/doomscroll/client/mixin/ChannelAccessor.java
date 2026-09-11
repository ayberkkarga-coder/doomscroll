package com.doomscroll.client.mixin;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Channel.class)
public interface ChannelAccessor {
	/** OpenAL kaynak kimligi (Sound Physics API'sine verilir). */
	@Accessor("source")
	int doomscroll$source();
}
