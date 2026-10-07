package com.caldera.shaders.pack;

import java.util.List;
import net.minecraft.client.Minecraft;

public final class LegacyPackMigration {
   private static final String OLD_ID = "file/caldera_shaderpack_active";

   private LegacyPackMigration() {
   }

   static List<String> withoutLegacy(List<String> ids) {
      return ids.stream().filter((id) -> !"file/caldera_shaderpack_active".equals(id)).toList();
   }

   public static boolean detach(Minecraft minecraft) {
      List<String> selected = withoutLegacy(minecraft.options.resourcePacks);
      List<String> incompatible = withoutLegacy(minecraft.options.incompatibleResourcePacks);
      List<String> repository = List.copyOf(minecraft.getResourcePackRepository().getSelectedIds());
      boolean changed = !selected.equals(minecraft.options.resourcePacks) || !incompatible.equals(minecraft.options.incompatibleResourcePacks) || !repository.equals(withoutLegacy(repository));
      if (changed) {
         minecraft.options.resourcePacks.clear();
         minecraft.options.resourcePacks.addAll(selected);
         minecraft.options.incompatibleResourcePacks.clear();
         minecraft.options.incompatibleResourcePacks.addAll(incompatible);
         minecraft.getResourcePackRepository().setSelected(withoutLegacy(repository));
      }

      return changed;
   }
}
