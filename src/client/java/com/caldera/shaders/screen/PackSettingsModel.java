package com.caldera.shaders.screen;

import com.caldera.shaders.graph.PackGraph;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

final class PackSettingsModel {
   static final List<String> BASIC = List.of("ANTIALIASING", "SHADOW_QUALITY", "CLOUD_QUALITY", "WATER_ENABLED", "WATER_REFLECTION_QUALITY", "SUN_SHAFTS", "ATMOSPHERE", "BLOOM_STRENGTH", "AMBIENT_OCCLUSION", "AUTO_EXPOSURE", "COLOR_GRADE", "WET_SURFACES", "MATERIAL_SHEEN", "EMISSIVE_ACCENTS", "HELD_LIGHTING");
   final PackGraph graph;
   final Map<String, Double> values = new TreeMap();
   private final Map<String, Double> saved = new TreeMap();
   private final Map<String, Double> enabledValues = new HashMap();

   PackSettingsModel(PackGraph graph) {
      this.graph = graph;
      this.values.putAll(graph.options());
      this.saved.putAll(this.values);
   }

   List<String> keys(boolean advanced) {
      if (advanced) {
         return this.graph.optionDefinitions().keySet().stream().sorted(Comparator.comparing(this::group).thenComparing(this::name)).toList();
      } else {
         Stream var10000 = BASIC.stream();
         Map var10001 = this.graph.optionDefinitions();
         Objects.requireNonNull(var10001);
         List<String> known = var10000.filter(var10001::containsKey).toList();
         return known.isEmpty() ? List.copyOf(this.graph.optionDefinitions().keySet()) : known;
      }
   }

   boolean dirty() {
      return !this.values.equals(this.saved);
   }

   void applied() {
      this.saved.clear();
      this.saved.putAll(this.values);
   }

   void reset() {
      this.graph.optionDefinitions().forEach((key, option) -> this.values.put(key, option.defaultValue()));
      this.enabledValues.clear();
   }

   boolean toggle(String key, boolean advanced) {
      List<Double> choices = ((PackGraph.Option)this.graph.optionDefinitions().get(key)).values();
      return choices.equals(List.of((double)0.0F, (double)1.0F));
   }

   void change(String key, boolean advanced) {
      PackGraph.Option option = (PackGraph.Option)this.graph.optionDefinitions().get(key);
      double current = (Double)this.values.get(key);
      if (this.toggle(key, advanced)) {
         if (current != (double)0.0F) {
            this.enabledValues.put(key, current);
            this.values.put(key, (double)0.0F);
         } else {
            this.values.put(key, (Double)this.enabledValues.getOrDefault(key, option.defaultValue() != (double)0.0F ? option.defaultValue() : (Double)option.values().stream().filter((v) -> v != (double)0.0F).findFirst().orElse((double)0.0F)));
         }
      } else {
         this.values.put(key, (Double)option.values().get((option.values().indexOf(current) + 1) % option.values().size()));
      }

   }

   String value(String key, boolean advanced) {
      double v = (Double)this.values.get(key);
      if (key.equals("COLOR_GRADE") && ((PackGraph.Option)this.graph.optionDefinitions().get(key)).values().equals(List.of((double)0.0F, (double)0.25F, (double)0.5F, (double)1.0F))) {
         return v == (double)0.0F ? "Off" : (v == (double)0.25F ? "Natural" : (v == (double)0.5F ? "Realistic" : "Vibrant"));
      } else if (this.toggle(key, advanced)) {
         return v == (double)0.0F ? "Off" : "On";
      } else if (key.endsWith("_QUALITY") && v >= (double)0.0F && v <= (double)4.0F && v == Math.rint(v)) {
         return (String)List.of("Off", "Low", "Medium", "High", "Ultra").get((int)v);
      } else {
         List<Double> choices = ((PackGraph.Option)this.graph.optionDefinitions().get(key)).values();
         if (!advanced && BASIC.contains(key) && choices.size() == 5 && (Double)choices.getFirst() == (double)0.0F) {
            return (String)List.of("Off", "Low", "Medium", "High", "Ultra").get(choices.indexOf(v));
         } else {
            return v != (double)0.0F || !BASIC.contains(key) && !key.equals("WATER_WAVES") && !key.equals("WATER_REFLECTION_STRENGTH") ? BigDecimal.valueOf(v).stripTrailingZeros().toPlainString() : "Off";
         }
      }
   }

   String name(String key) {
      String var10000;
      switch (key) {
         case "SHADOW_QUALITY" -> var10000 = "Shadows";
         case "CLOUD_QUALITY" -> var10000 = "Clouds";
         case "WATER_ENABLED" -> var10000 = "Enhanced water";
         case "WATER_REFLECTION_QUALITY" -> var10000 = "Water reflections";
         case "SUN_SHAFTS" -> var10000 = "Sun rays";
         case "ATMOSPHERE" -> var10000 = "Atmospheric fog";
         case "BLOOM_STRENGTH" -> var10000 = "Bloom";
         case "AMBIENT_OCCLUSION" -> var10000 = "Contact shadows";
         default -> var10000 = ((PackGraph.Option)this.graph.optionDefinitions().get(key)).label();
      }

      return var10000;
   }

   String group(String key) {
      if (key.startsWith("WATER")) {
         return "Water";
      } else if (!key.startsWith("CLOUD") && !key.startsWith("SUN") && !key.equals("ATMOSPHERE")) {
         return !key.startsWith("SHADOW") && !key.startsWith("LIGHT") && !key.equals("AMBIENT_OCCLUSION") ? "Color & effects" : "Lighting";
      } else {
         return "Sky & atmosphere";
      }
   }

   boolean available(String key) {
      return !key.startsWith("WATER_") || key.equals("WATER_ENABLED") || (Double)this.values.getOrDefault("WATER_ENABLED", (double)1.0F) != (double)0.0F;
   }

   String help(String key) {
      String var10000;
      switch (key) {
         case "ANTIALIASING" -> var10000 = "Smooths jagged edges with a lightweight spatial filter. No motion history or ghosting.";
         case "SHADOW_QUALITY" -> var10000 = "Shadows from terrain, players and creatures. Higher quality uses more GPU memory.";
         case "CLOUD_QUALITY" -> var10000 = "Moving volumetric clouds in the sky and water reflections.";
         case "WATER_ENABLED" -> var10000 = "Clear, tinted water with waves and reflections.";
         case "WATER_REFLECTION_QUALITY" -> var10000 = "Reflects the sky and visible scenery. Requires enhanced water.";
         case "SUN_SHAFTS" -> var10000 = "Soft beams of sunlight through clouds and shadows.";
         case "ATMOSPHERE" -> var10000 = "Patchy low-elevation mist and subtle horizon haze, stronger around dawn and in rain.";
         case "BLOOM_STRENGTH" -> var10000 = "A gentle glow around bright highlights.";
         case "HELD_LIGHTING" -> var10000 = "Light from either hand over 12 blocks, with terrain, grass, player and mob shadows.";
         case "AMBIENT_OCCLUSION" -> var10000 = "Adds depth to corners and nearby surfaces.";
         case "COLOR_GRADE" -> var10000 = "Off: no extra grading; Natural: neutral and soft; Realistic: warm highlights and cool shadows; Vibrant: richer colors and contrast.";
         case "AUTO_EXPOSURE" -> var10000 = "Gradually brightens dark areas. Sunlight briefly appears overexposed when leaving darkness, then settles. Exposure remains a manual brightness adjustment.";
         case "DISTANCE_COLOR" -> var10000 = "Cool distant terrain and warmer sun-facing horizons. Requires atmospheric fog.";
         case "WET_SURFACES" -> var10000 = "Darker, glossy exposed surfaces during rain. Exposure is estimated from skylight.";
         case "MATERIAL_SHEEN" -> var10000 = "Distinct highlights on supported metal, stone and wooden blocks.";
         case "EMISSIVE_ACCENTS" -> var10000 = "Bright accents on luminous blocks, plants and active redstone ore. Bloom adds their halo.";
         default -> var10000 = "Adjust " + this.name(key).toLowerCase(Locale.ROOT) + ". Click to cycle through available values.";
      }

      String description = var10000;
      return description + " Changes take effect when you press Apply.";
   }
}
