package com.caldera.shaders.graph;

import com.mojang.logging.LogUtils;
import com.caldera.shaders.runtime.ShaderRuntime;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;

final class WaterReflectionSmoke {
   private static int phase = -1;
   private static int ticks;
   private static boolean pending;
   private static boolean finished;

   static void tick(Minecraft client) {
      if (!pending && !finished && client.level != null && client.gui.overlay() == null && !ShaderRuntime.resourceReloading()) {
         try {
            if (NativePackRuntime.failure() != null) {
               throw new IllegalStateException(NativePackRuntime.failure());
            }

            if (phase < 0 && NativePackRuntime.renderedFrames() >= 120L) {
               setup(client);
               return;
            }

            if (phase >= 0 && client.player != null) {
               client.player.setPos(216.0F, 100.0F, 657.0F);
               client.player.setYRot(180.0F);
               client.player.setXRot(8.0F);
               client.player.setDeltaMovement(0.0F, 0.0F, 0.0F);
            }

            if (phase < 0 || ++ticks < 140) {
               return;
            }

            pending = true;
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), (image) -> {
               try {

                   try {
                     String var10000;
                     switch (phase) {
                        case 0 -> var10000 = "on";
                        case 1 -> var10000 = "off";
                        case 2 -> var10000 = "waves";
                        case 3 -> var10000 = "no-clouds";
                        default -> var10000 = "reload";
                     }

                     String name = var10000;
                     image.writeToFile(Path.of("../build/reports/smoke/caldera-water-reflections-" + name + ".png"));
                     LogUtils.getLogger().info("CALDERA_WATER_REFLECTION_STEP {} frames={}", name, NativePackRuntime.renderedFrames());
                     ticks = 0;
                     pending = false;
                     switch (phase++) {
                        case 0:
                           options(0, 0, 2);
                           break;
                        case 1:
                           options(3, 1, 2);
                           break;
                        case 2:
                           options(2, 0, 0);
                           break;
                        case 3:
                           options(1, 0, 2);
                           pending = true;
                           ShaderRuntime.applyConfig(ShaderRuntime.config(), true).whenComplete((ignored, errorx) -> {
                              if (errorx != null) {
                                 fail(client, errorx);
                              } else {
                                 pending = false;
                              }

                           });
                           break;
                        default:
                           finished = true;
                           LogUtils.getLogger().info("CALDERA_WATER_REFLECTION_SMOKE_PASS");
                           client.stop();
                     }
                  } catch (Throwable var6) {
                       try {
                          image.close();
                       } catch (Throwable x2) {
                          var6.addSuppressed(x2);
                       }

                       throw var6;
                  }

                   image.close();
               } catch (Throwable error) {
                  fail(client, error);
               }

            });
         } catch (Throwable error) {
            fail(client, error);
         }

      }
   }

   private static void options(int quality, int waves, int clouds) throws Exception {
      NativePackRuntime.applyOptions(ShaderRuntime.config().selectedPackId(), Map.of("WATER_REFLECTION_QUALITY", (double)quality, "WATER_WAVES", (double)waves, "CLOUD_QUALITY", (double)clouds));
   }

   private static void setup(Minecraft client) {
      IntegratedServer server = client.getSingleplayerServer();
      if (server != null && server.getWorldData().getLevelName().equals("Caldera QA")) {
         pending = true;
         server.execute(() -> {
            try {
               CommandSourceStack source = server.createCommandSourceStack();

               for(String command : new String[]{"gamerule minecraft:advance_time false", "time set 6000", "weather clear", "fill 198 94 618 234 104 662 minecraft:air", "fill 198 105 618 234 115 662 minecraft:air", "fill 200 94 620 232 98 660 minecraft:stone", "fill 201 95 621 231 98 659 minecraft:water", "fill 204 99 624 228 99 629 minecraft:smooth_quartz", "fill 205 100 625 208 111 628 minecraft:red_concrete", "fill 224 100 625 227 109 628 minecraft:blue_concrete", "fill 213 106 626 219 108 627 minecraft:yellow_concrete", "summon minecraft:pig 216 100 628 {NoAI:1b,NoGravity:1b,Invulnerable:1b}", "gamemode spectator @p", "tp @p 216 100 657 180 8"}) {
                  server.getCommands().performPrefixedCommand(source, command);
               }

               client.execute(() -> {
                  try {
                     options(2, 0, 2);
                     phase = 0;
                     ticks = 0;
                     pending = false;
                  } catch (Exception error) {
                     fail(client, error);
                  }

               });
            } catch (Throwable error) {
               client.execute(() -> fail(client, error));
            }

         });
      } else {
         throw new IllegalStateException("Requires disposable Caldera QA world");
      }
   }

   private static void fail(Minecraft client, Throwable error) {
      finished = true;
      SmokeFailure.fail(client, "CALDERA_WATER_REFLECTION_SMOKE_FAIL", error);
   }
}
