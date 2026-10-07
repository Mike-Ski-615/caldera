package com.caldera.shaders.screen;

import com.caldera.shaders.graph.NativePackRuntime;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个光影包的选项设置界面。
 * <p>
 * 这是 0.5.1 独有的能力，0.3.1 没有任何对应物。它按 0.3.1 的观感重做过：文案全部走翻译键、
 * 沿用 {@link ShadersScreen} 那套面板配色与它的面板底色画法。
 * <p>
 * 它原先继承一个 {@code ShaderScreenBase}——那个基类只有一个子类、没提供任何抽象，只是把八个颜色
 * 常量与四个方法藏到了另一个文件里；而颜色在那一侧写作十进制、在 {@link ShadersScreen} 那一侧写作
 * 十六进制，同一个值两处写法（已逐个核对过：完全相等）。合并之后调色板只有一处来源。
 * <p>
 * {@code status} 是 {@link Component} 而不是 {@code String}：{@code Component.translatable(...)}
 * 的 {@code getString()} 返回的是**翻译键本身**，真正的解析发生在渲染时，所以状态文案必须以组件
 * 形式一路带到 {@code graphics.text}。
 */
final class NativePackSettingsScreen extends Screen {
	private final Screen lastScreen;
	private final String packId;
	private PackSettingsModel model;
	private Component status;
	private boolean advanced;
	private boolean error;
	private int page;
	private int pages;
	private int x;
	private int panelWidth;
	private int rows;
	private int columns;
	private final List<String> visible = new ArrayList<>();

	NativePackSettingsScreen(ShadersScreen parent, String packId) {
		super(Component.translatable("caldera.settings.title"));
		this.lastScreen = parent;
		this.packId = packId;

		try {
			this.model = new PackSettingsModel(NativePackRuntime.settings(packId));
		} catch (Exception failure) {
			this.status = Component.translatable("caldera.settings.status.load_failed", String.valueOf(failure.getMessage()));
			this.error = true;
		}
	}

	@Override
	protected void init() {
		this.clearWidgets();
		this.visible.clear();
		this.panelWidth = Math.min(720, this.width - 24);
		this.x = (this.width - this.panelWidth) / 2;
		this.columns = this.panelWidth >= 560 ? 2 : 1;
		this.rows = Math.max(1, (this.height - 156) / 36);
		List<String> keys = this.model == null ? List.of() : this.model.keys(this.advanced);
		int count = this.rows * this.columns;
		this.pages = Math.max(1, (keys.size() + count - 1) / count);
		this.page = Math.min(this.page, this.pages - 1);
		this.tab(Component.translatable("caldera.settings.tab.basic"), false, this.x);
		this.tab(Component.translatable("caldera.settings.tab.advanced"), true, this.x + this.panelWidth / 2 + 3);
		int cell = (this.panelWidth - 12 * (this.columns - 1)) / this.columns;

		for(int i = this.page * count; i < Math.min(keys.size(), (this.page + 1) * count); ++i) {
			String key = (String)keys.get(i);
			this.visible.add(key);
			int local = i - this.page * count;
			int left = this.x + local % this.columns * (cell + 12);
			int top = 76 + local / this.columns * 36;
			Component label = Component.translatable("caldera.settings.option", this.model.name(key), this.model.value(key, this.advanced));
			Button button = Button.builder(label, (b) -> {
				this.model.change(key, this.advanced);
				this.status = null;
				this.error = false;
				this.init();
			}).bounds(left, top, cell, 20).tooltip(Tooltip.create(Component.literal(this.model.help(key)))).build();
			button.active = this.model.available(key);
			this.addRenderableWidget(button);
		}

		if (this.pages > 1) {
			((Button)this.addRenderableWidget(Button.builder(Component.translatable("caldera.settings.previous"), (b) -> {
				--this.page;
				this.init();
			}).bounds(this.x, this.height - 76, 80, 20).build())).active = this.page > 0;
			((Button)this.addRenderableWidget(Button.builder(Component.translatable("caldera.settings.next"), (b) -> {
				++this.page;
				this.init();
			}).bounds(this.x + this.panelWidth - 80, this.height - 76, 80, 20).build())).active = this.page < this.pages - 1;
		}

		int third = (this.panelWidth - 12) / 3;
		((Button)this.addRenderableWidget(Button.builder(Component.translatable("caldera.settings.reset"), (b) -> {
			this.model.reset();
			this.status = Component.translatable("caldera.settings.status.defaults");
			this.error = false;
			this.init();
		}).bounds(this.x, this.height - 30, third, 20).tooltip(Tooltip.create(Component.translatable("caldera.settings.reset.tooltip"))).build())).active = this.model != null;
		((Button)this.addRenderableWidget(Button.builder(Component.translatable("caldera.settings.apply"), (b) -> {
			try {
				NativePackRuntime.applyOptions(this.packId, this.model.values);
				this.model.applied();
				this.status = Component.translatable("caldera.settings.status.saved");
				this.error = false;
			} catch (Exception failure) {
				this.status = Component.translatable("caldera.settings.status.apply_failed", String.valueOf(failure.getMessage()));
				this.error = true;
			}

			this.init();
		}).bounds(this.x + third + 6, this.height - 30, third, 20).build())).active = this.model != null && this.model.dirty();
		this.addRenderableWidget(Button.builder(
				Component.translatable(this.model != null && this.model.dirty() ? "caldera.settings.cancel" : "caldera.settings.done"),
				(b) -> this.onClose()).bounds(this.x + 2 * (third + 6), this.height - 30, third, 20)
				.tooltip(Tooltip.create(Component.translatable("caldera.settings.close.tooltip"))).build());
	}

	private void tab(Component label, boolean target, int left) {
		((Button)this.addRenderableWidget(Button.builder(label, (b) -> {
			this.advanced = target;
			this.page = 0;
			this.init();
		}).bounds(left, 46, this.panelWidth / 2 - 3, 20).build())).active = this.advanced != target;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		this.fillPanel(graphics, this.x - 8, 8, this.panelWidth + 16, this.height - 12);
		graphics.text(this.font, this.title, this.x, 18, ShadersScreen.TEXT_PRIMARY);
		Component subtitle = Component.translatable(this.advanced ? "caldera.settings.subtitle.advanced" : "caldera.settings.subtitle.basic");
		graphics.textWithWordWrap(this.font, subtitle, this.x, 32, this.panelWidth, ShadersScreen.TEXT_SECONDARY);
		int cell = (this.panelWidth - 12 * (this.columns - 1)) / this.columns;

		for(int i = 0; i < this.visible.size(); ++i) {
			String key = (String)this.visible.get(i);
			int left = this.x + i % this.columns * (cell + 12);
			int top = 76 + i / this.columns * 36;
			Component detail = !this.model.available(key)
					? Component.translatable("caldera.settings.detail.enable_water")
					: this.advanced
							? Component.literal(this.model.group(key))
							: Component.literal(this.model.help(key).split("\\.")[0]);
			// 选项按钮下方那行只有一行的高度，所以按宽度切出第一行，而不是让它换行压到下一格。
			this.drawFirstLine(graphics, detail, left + 2, top + 23, cell - 4, ShadersScreen.TEXT_MUTED);
		}

		if (this.pages > 1) {
			graphics.text(this.font, Component.literal((this.page + 1) + " / " + this.pages), this.x + this.panelWidth / 2 - 12, this.height - 70, ShadersScreen.TEXT_SECONDARY);
		}

		Component message = this.status != null
				? this.status
				: Component.translatable(this.model != null && this.model.dirty() ? "caldera.settings.status.unsaved" : "caldera.settings.status.hint");
		this.drawFirstLine(graphics, message, this.x, this.height - 45, this.panelWidth, this.error ? ShadersScreen.STATUS_ERROR : ShadersScreen.TEXT_SECONDARY);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/** 按宽度截断到一行再画。{@code getString()} 不能用于翻译键，所以必须走 {@code split}。 */
	private void drawFirstLine(GuiGraphicsExtractor graphics, Component text, int x, int y, int width, int color) {
		List<FormattedCharSequence> lines = this.font.split(text, width);
		if (!lines.isEmpty()) {
			graphics.text(this.font, lines.getFirst(), x, y, color);
		}
	}

	/**
	 * 这一屏的面板底色。与列表屏共用同一组常量（见 {@link ShadersScreen}），所以两处的观感不会
	 * 各自漂移。
	 */
	private void fillPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		graphics.fill(x, y, x + width, y + height, ShadersScreen.PANEL_BACKGROUND);
		graphics.outline(x, y, width, height, ShadersScreen.PANEL_BORDER);
		graphics.fill(x + 1, y + 1, x + width - 1, y + 5, ShadersScreen.PANEL_ACCENT);
	}

	/**
	 * 只在自己没有世界背景时画模糊：有世界时那一层由游戏的关卡渲染提供，再画一次会压暗画面。
	 * <p>
	 * 这是原先那个基类唯一真正"共用"的东西——两个屏幕都要它，所以合并之后这份行为留在设置屏这一侧，
	 * 而 {@link ShadersScreen} 有它自己的同名实现（两者的判断一字不差）。
	 */
	@Override
	public void extractBackground(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, float partialTick) {
		if (this.minecraft == null || this.minecraft.level == null) {
			super.extractBackground(guiGraphicsExtractor, mouseX, mouseY, partialTick);
		}
	}

	/** 光影设置不该让单人游戏暂停。 */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(this.lastScreen);
		}
	}
}
