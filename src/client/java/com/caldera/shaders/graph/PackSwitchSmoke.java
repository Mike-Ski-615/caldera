package com.caldera.shaders.graph;

import com.mojang.logging.LogUtils;
import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.runtime.ShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;

final class PackSwitchSmoke {
   private static int phase;
   private static int ticks;
   private static boolean loading;
   private static boolean finished;

   static void tick(Minecraft client) {
      if (!finished && !loading && client.level != null && client.gui.overlay() == null && !ShaderRuntime.resourceReloading()) {
         ++ticks;
         if (phase == 0 && ticks == 1 && client.getSingleplayerServer() != null && client.getSingleplayerServer().getWorldData().getLevelName().equals("Caldera QA")) {
            IntegratedServer server = client.getSingleplayerServer();
            server.execute(() -> {
               CommandSourceStack source = server.createCommandSourceStack();
               server.getCommands().performPrefixedCommand(source, "tp @p 204 103 643 135 15");
               server.getCommands().performPrefixedCommand(source, "time set 6000");
               server.getCommands().performPrefixedCommand(source, "weather clear");
            });
         }

         if (NativePackRuntime.failure() != null && phase != 5 && phase != 6) {
            fail(client, new IllegalStateException(NativePackRuntime.failure()));
         } else {
            switch (phase) {
               case 0:
                  if (ticks >= 60) {
                     select(client, new ShaderConfig(true, "caldera-realistic"), 1);
                  }
                  break;
               case 1:
                  if (rendered()) {
                     log("first selection");
                     select(client, new ShaderConfig(false, "__builtin__"), 2);
                  }
                  break;
               case 2:
                  if (ticks >= 60) {
                     select(client, new ShaderConfig(true, "caldera-realistic"), 3);
                  }
                  break;
               case 3:
                  if (rendered()) {
                     log("reselection after disabling");
                     select(client, new ShaderConfig(true, "caldera-invalid"), 5);
                  }
               case 4:
               default:
                  break;
               case 5:
                  if (NativePackRuntime.failure() != null) {
                     phase = 6;
                     ticks = 0;
                  } else if (ticks > 300) {
                     fail(client, new IllegalStateException("Invalid scene input did not trigger rejection"));
                  }
                  break;
               case 6:
                  if (ticks >= 60) {
                     log("invalid shader recovered without crashing");
                     select(client, new ShaderConfig(true, "caldera-realistic"), 7);
                  }
                  break;
               case 7:
                  if (rendered()) {
                     log("selection after recovery");
                     finished = true;
                     LogUtils.getLogger().info("CALDERA_PACK_SWITCH_SMOKE_PASS");
                     client.stop();
                  }
            }

            if (ticks > 1200) {
               fail(client, new IllegalStateException("Timed out in switch phase " + phase));
            }

         }
      }
   }

   private static boolean rendered() {
      return NativePackRuntime.renderedFrames() >= 180L && NativePackRuntime.sceneReplacementCount() >= 3L;
   }

   private static void log(String step) {
      LogUtils.getLogger().info("CALDERA_PACK_SWITCH_STEP {}", step);
   }

   private static void select(Minecraft client, ShaderConfig config, int next) {
      loading = true;
      phase = next;
      ticks = 0;
      ShaderRuntime.applyConfig(config, true).whenComplete((ignored, failure) -> {
         loading = false;
         if (failure != null) {
            fail(client, failure);
         }

      });
   }

   private static void fail(Minecraft client, Throwable failure) {
      finished = true;
      LogUtils.getLogger().error("CALDERA_PACK_SWITCH_SMOKE_FAIL", failure);
      client.stop();
   }
}
