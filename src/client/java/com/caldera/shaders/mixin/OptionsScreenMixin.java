package com.caldera.shaders.mixin;

import com.caldera.shaders.screen.ShadersScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin({OptionsScreen.class})
public abstract class OptionsScreenMixin extends Screen {
   protected OptionsScreenMixin(Component title) {
      super(title);
   }

   @ModifyArg(
      method = {"init"},
      at = {@At(
   value = "INVOKE",
   target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"
)},
      index = 0
   )
   private LayoutElement caldera$addShadersButton(LayoutElement contents) {
      if (contents instanceof GridLayout grid) {
         grid.addChild(Button.builder(Component.translatable("caldera.screen.options_button"), (button) -> this.minecraft.setScreenAndShow(new ShadersScreen((OptionsScreen)(Object)this))).width(310).build(), 5, 0, 1, 2);
      }

      return contents;
   }
}
