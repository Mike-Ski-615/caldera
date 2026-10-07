package com.caldera.shaders.graph;

import com.mojang.logging.LogUtils;
import com.caldera.shaders.render.sky.CustomCelestials;
import com.caldera.shaders.runtime.ShaderRuntime;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector3f;

final class NativeEnvironmentSmoke {
   private static int phase = -1;
   private static int ticks;
   private static boolean pending;
   private static boolean finished;
   private static boolean reloaded;
   private static final Vector3f sun = new Vector3f();
   private static final Vector3f moon = new Vector3f();
   private static final String[] NAMES = new String[]{"day", "sunset", "night", "rain", "underwater", "sun", "moon", "cave"};
   private static final int[] TIMES = new int[]{6000, 12000, 18000, 6000, 6000, 12000, 18000, 18000};

   static void observe(LevelRenderState state) {
      if (state != null) {
         CustomCelestials.setCelestialDirection(state.skyRenderState.sunAngle, sun);
         CustomCelestials.setCelestialDirection(state.skyRenderState.moonAngle, moon);
      }

   }

   static void tick(Minecraft client) {
      if (!pending && !finished && client.level != null && client.gui.overlay() == null && !ShaderRuntime.resourceReloading()) {
         try {
            if (NativePackRuntime.failure() != null) {
               throw new IllegalStateException(NativePackRuntime.failure());
            }

            if (phase < 0 && NativePackRuntime.renderedFrames() >= 180L) {
               setup(client, 0);
               return;
            }

            if (phase < 0) {
               return;
            }

            ++ticks;
            if ((phase == 5 || phase == 6) && ticks == 30) {
               Vector3f direction = phase == 5 ? sun : moon;
                if (client.player != null) {
                    client.player.setYRot((float)Math.toDegrees(Math.atan2(-direction.x, direction.z)));
                }
                if (client.player != null) {
                    client.player.setXRot((float)(-Math.toDegrees(Math.asin(direction.y))));
                }
            }

            if (ticks < (phase == 3 ? 300 : 100)) {
               return;
            }

            if (phase == 4 && client.gameRenderer.mainCamera().getFluidInCamera() != FogType.WATER) {
               throw new IllegalStateException("Underwater test camera is not in water");
            }

            pending = true;
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), (image) -> {
               try {

                   try {
                     image.writeToFile(Path.of("../build/reports/smoke/caldera-environment-" + NAMES[phase] + ".png"));
                     LogUtils.getLogger().info("CALDERA_ENVIRONMENT_SCENE_PASS {} frames={}", NAMES[phase], NativePackRuntime.renderedFrames());
                     pending = false;
                     if (phase == NAMES.length - 1) {
                        if (!reloaded) {
                           reloaded = true;
                           pending = true;
                           ShaderRuntime.applyConfig(ShaderRuntime.config(), true).whenComplete((ignored, errorx) -> {
                              if (errorx != null) {
                                 fail(client, errorx);
                              } else {
                                 ticks = 0;
                                 pending = false;
                              }

                           });
                        } else {
                           finished = true;
                           LogUtils.getLogger().info("CALDERA_ENVIRONMENT_SMOKE_PASS");
                           client.stop();
                        }
                     } else {
                        setup(client, phase + 1);
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

   private static void setup(Minecraft client, int next) {
      IntegratedServer server = client.getSingleplayerServer();
      if (server != null && server.getWorldData().getLevelName().equals("Caldera QA")) {
         pending = true;
         server.execute(() -> {
            try {
               CommandSourceStack source = server.createCommandSourceStack();
               server.getCommands().performPrefixedCommand(source, "gamerule minecraft:advance_time false");
               Commands var10000 = server.getCommands();
               int var10002 = TIMES[next];
               var10000.performPrefixedCommand(source, "time set " + var10002);
               server.getCommands().performPrefixedCommand(source, next == 3 ? "weather rain" : "weather clear");
               server.getCommands().performPrefixedCommand(source, "gamemode spectator @p");
               if (next == 7) {
                  for(String command : new String[]{"fill 180 79 630 190 85 640 minecraft:stone", "fill 181 80 631 189 84 639 minecraft:air", "setblock 183 80 633 minecraft:torch"}) {
                     server.getCommands().performPrefixedCommand(source, command);
                  }
               }

               String var14;
               switch (next) {
                  case 4 -> var14 = "170 57 650 135 0";
                  case 5 -> var14 = "204 103 643 45 -8";
                  case 6 -> var14 = "204 103 643 45 -70";
                  case 7 -> var14 = "185 81 638 180 8";
                  default -> var14 = "204 103 643 135 -12";
               }

               String position = var14;
               if (next == 4) {
                  for(String command : new String[]{"fill 210 94 640 222 102 652 minecraft:stone", "fill 211 95 641 221 102 651 minecraft:water", "fill 211 94 641 221 94 651 minecraft:sand"}) {
                     server.getCommands().performPrefixedCommand(source, command);
                  }

                  position = "216 97 649 180 -10";
               }

               server.getCommands().performPrefixedCommand(source, "tp @p " + position);
               client.execute(() -> {
                  try {
                     NativePackRuntime.applyOptions(ShaderRuntime.config().selectedPackId(), Map.of("CLOUD_QUALITY", next != 5 && next != 6 ? (double)2.0F : (double)0.0F));
                  } catch (Exception error) {
                     fail(client, error);
                     return;
                  }

                  phase = next;
                  ticks = 0;
                  pending = false;
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
      LogUtils.getLogger().error("CALDERA_ENVIRONMENT_SMOKE_FAIL", error);
      client.stop();
   }
}
