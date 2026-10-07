package com.caldera.shaders.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

abstract class ShaderScreenBase extends Screen {
   protected static final int PANEL_BACKGROUND = -803988700;
   protected static final int PANEL_BORDER = -11442823;
   protected static final int PANEL_ACCENT = -5010871;
   protected static final int TEXT_PRIMARY = -591621;
   protected static final int TEXT_SECONDARY = -4734258;
   protected static final int TEXT_MUTED = -7694426;
   protected static final int STATUS_SUCCESS = -5839967;
   protected static final int STATUS_ERROR = -809306;
   private final Screen lastScreen;

   protected ShaderScreenBase(Screen lastScreen, Component title) {
      super(title);
      this.lastScreen = lastScreen;
   }

   public void extractBackground(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, float partialTick) {
      if (this.minecraft == null || this.minecraft.level == null) {
         super.extractBackground(guiGraphicsExtractor, mouseX, mouseY, partialTick);
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void onClose() {
      if (this.minecraft != null) {
         this.minecraft.setScreenAndShow(this.lastScreen);
      }

   }

   protected final void fillPanel(GuiGraphicsExtractor guiGraphicsExtractor, int x, int y, int width, int height) {
      guiGraphicsExtractor.fill(x, y, x + width, y + height, -803988700);
      guiGraphicsExtractor.outline(x, y, width, height, -11442823);
      guiGraphicsExtractor.fill(x + 1, y + 1, x + width - 1, y + 5, -5010871);
   }

   protected final int wrappedHeight(Component text, int width) {
      return text != null && !text.getString().isBlank() ? this.font.wordWrapHeight(text, Math.max(1, width)) : 0;
   }
}
