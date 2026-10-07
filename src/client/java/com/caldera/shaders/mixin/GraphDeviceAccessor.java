package com.caldera.shaders.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin({FrontendGpuDevice.class})
public interface GraphDeviceAccessor {
   @Accessor("backend")
   GpuDeviceBackend caldera$backend();
}
