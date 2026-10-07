package com.caldera.shaders.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public final class ShaderConfig {
   public static final String BUILTIN_PACK_ID = "__builtin__";
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Gson GSON = (new GsonBuilder()).setPrettyPrinting().create();
   private static final String CONFIG_FILE_NAME = "caldera-shaders.json";
   private boolean enabled = true;
   private String selectedPackId = "__builtin__";

   public ShaderConfig() {
   }

   public ShaderConfig(boolean enabled, String selectedPackId) {
      this.enabled = enabled;
      this.selectedPackId = selectedPackId != null && !selectedPackId.isBlank() ? selectedPackId : "__builtin__";
   }

   public boolean enabled() {
      return this.enabled;
   }

   public String selectedPackId() {
      return this.selectedPackId;
   }

   public ShaderConfig withSelection(boolean enabled, String id) {
      return new ShaderConfig(enabled, id);
   }

   public static ShaderConfig load() {
      Path configPath = configPath();
      if (!Files.isRegularFile(configPath, new LinkOption[0])) {
         return new ShaderConfig();
      } else {
         try {
            Reader reader = Files.newBufferedReader(configPath);

            ShaderConfig var3;
            label57: {
               try {
                  ShaderConfig loaded = (ShaderConfig)GSON.fromJson(reader, ShaderConfig.class);
                  if (loaded != null) {
                     var3 = new ShaderConfig(loaded.enabled, loaded.selectedPackId);
                     break label57;
                  }
               } catch (Throwable var5) {
                   try {
                       reader.close();
                   } catch (Throwable var4) {
                       var5.addSuppressed(var4);
                   }

                   throw var5;
               }

                reader.close();

                return new ShaderConfig();
            }

             reader.close();

             return var3;
         } catch (Exception exception) {
            LOGGER.error("Failed to load Caldera shader settings from {}", configPath, exception);
            return new ShaderConfig();
         }
      }
   }

   public void save() {
      Path configPath = configPath();

      try {
         Files.createDirectories(configPath.getParent());
         Writer writer = Files.newBufferedWriter(configPath);

         try {
            GSON.toJson(this, writer);
         } catch (Throwable var6) {
             try {
                 writer.close();
             } catch (Throwable var5) {
                 var6.addSuppressed(var5);
             }

             throw var6;
         }

          writer.close();
      } catch (IOException exception) {
         LOGGER.error("Failed to save Caldera shader settings to {}", configPath, exception);
      }

   }

   private static Path configPath() {
      return FabricLoader.getInstance().getGameDir().resolve("config").resolve("caldera-shaders.json");
   }
}
