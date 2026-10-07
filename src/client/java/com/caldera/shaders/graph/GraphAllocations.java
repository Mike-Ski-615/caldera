package com.caldera.shaders.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record GraphAllocations(Map<String, Integer> resourceSlots, List<PackGraph.Resource> slots) {
   public static GraphAllocations plan(PackGraph graph) {
      List<PackGraph.Pass> passes = graph.schedule();
      Map<String, Integer> lastRead = new HashMap<>();

      for(int i = 0; i < passes.size(); ++i) {
         for(String output : ((PackGraph.Pass)passes.get(i)).writes()) {
            lastRead.putIfAbsent(output, i);
         }

         for(String input : ((PackGraph.Pass)passes.get(i)).reads().values()) {
            if (!input.startsWith("$") && !PackGraph.previous(input)) {
               lastRead.put(input, i);
            }
         }
      }

      lastRead.put(graph.present(), passes.size());
      Map<String, Integer> assignments = new LinkedHashMap<>();
      List<PackGraph.Resource> slots = new ArrayList<>();
      List<Integer> ends = new ArrayList<>();

      for(String scene : graph.sceneTargets()) {
         assignments.put(scene, slots.size());
         slots.add((PackGraph.Resource)graph.resources().get(scene));
         ends.add(Integer.MAX_VALUE);
      }

      for(int i = 0; i < passes.size(); ++i) {
         for(String output : ((PackGraph.Pass)passes.get(i)).writes()) {
            PackGraph.Resource resource = (PackGraph.Resource)graph.resources().get(output);
            int slot = -1;
            if (!resource.history()) {
               for(int s = 0; s < slots.size(); ++s) {
                  if (((PackGraph.Resource)slots.get(s)).equals(resource) && (Integer)ends.get(s) < i) {
                     slot = s;
                     break;
                  }
               }
            }

            if (slot < 0) {
               slot = slots.size();
               slots.add(resource);
               ends.add(-1);
            }

            ends.set(slot, resource.history() ? Integer.MAX_VALUE : (Integer)lastRead.get(output));
            assignments.put(output, slot);
         }
      }

      return new GraphAllocations(Collections.unmodifiableMap(assignments), List.copyOf(slots));
   }
}
