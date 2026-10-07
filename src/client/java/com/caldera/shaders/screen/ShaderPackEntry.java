package com.caldera.shaders.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

/**
 * 光影列表里的一行。
 * <p>
 * 直接持有 {@link ShadersScreen}，而不是绕一层中间接口。此前那个 {@code Host} 接口**有且只有一个
 * 实现者**，换不来任何可替换性，却逼界面把四个方法声明成 {@code public}；而其中 `onEntryClicked`
 * 的参数类型（本类）是包私有的，于是"公开的东西用了不公开的类型"这条警告怎么也消不掉。
 * 两者同包，直接调用即可，那些方法也就能老老实实待在包私有。
 */
final class ShaderPackEntry extends ObjectSelectionList.Entry<ShaderPackEntry> {
	/**
	 * 鼠标左键的键码。
	 * Minecraft 26.3 把窗口/输入后端从 GLFW 换成了 SDL，而 SDL 的鼠标键码是 1 起始的
	 * （左键 1、中键 2、右键 3），不再是 GLFW 的 0 起始。这里如果误用 0，真实鼠标左键
	 * 会被判定为"非左键"而全部忽略——界面看起来就是点击毫无反应。
	 */
	private static final int SDL_BUTTON_LEFT = 1;

	private static final int BACKGROUND_SELECTED = 0xF0243344;
	private static final int BACKGROUND_HOVERED = 0xCC1A2531;
	private static final int BACKGROUND_IDLE = 0xA0141C25;
	private static final int BACKGROUND_DISABLED = 0x60141C25;
	private static final int BORDER_HOVERED = 0xFF6485A4;
	private static final int BORDER_IDLE = 0xFF3A4A5C;
	private static final int BORDER_DISABLED = 0xFF2A3644;
	private static final int BADGE_BACKGROUND = 0xCC203041;
	private static final int BADGE_LIVE_BACKGROUND = 0xCC173523;
	private static final int NAME_INSET = 10;
	private static final int NAME_OFFSET_Y = 7;
	private static final int TYPE_OFFSET_Y = 22;
	private static final int BADGE_INSET = 8;
	private static final int BADGE_PADDING = 12;
	private static final int BADGE_TEXT_INSET = 6;
	private static final int BADGE_OFFSET_Y = 8;
	private static final int BADGE_HEIGHT = 14;
	private static final int BADGE_TEXT_OFFSET_Y = 11;

	private final ShadersScreen screen;
	private final String packId;
	private final Component name;
	private final Component type;

	ShaderPackEntry(ShadersScreen screen, String packId, Component name, Component type) {
		this.screen = screen;
		this.packId = packId;
		this.name = name;
		this.type = type;
	}

	String packId() {
		return this.packId;
	}

	@Override
	public @NonNull Component getNarration() {
		return Component.translatable("caldera.screen.narration", this.name, this.type);
	}

	@Override
	public void extractContent(@NonNull GuiGraphicsExtractor extractor, int mouseX, int mouseY, boolean hovered, float partialTick) {
		boolean selected = this.screen.isPending(this.packId);
		boolean live = this.screen.isLive(this.packId);
		// 关闭光影时整列变暗且不响应悬停：列表的"不可点"必须看得出来，否则用户会以为界面卡了。
		boolean selectable = this.screen.selectable();
		boolean highlighted = selectable && hovered;
		int backgroundColor = selected ? BACKGROUND_SELECTED : highlighted ? BACKGROUND_HOVERED : BACKGROUND_IDLE;
		int borderColor = selected ? ShadersScreen.PANEL_ACCENT : highlighted ? BORDER_HOVERED : BORDER_IDLE;
		int nameColor = ShadersScreen.TEXT_PRIMARY;
		if (!selectable) {
			backgroundColor = BACKGROUND_DISABLED;
			borderColor = BORDER_DISABLED;
			nameColor = ShadersScreen.TEXT_SECONDARY;
		}
		extractor.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), backgroundColor);
		extractor.outline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), borderColor);

		Font font = this.screen.font();
		extractor.text(font, this.name, this.getContentX() + NAME_INSET, this.getContentY() + NAME_OFFSET_Y, nameColor, true);
		extractor.text(font, this.type, this.getContentX() + NAME_INSET, this.getContentY() + TYPE_OFFSET_Y, ShadersScreen.TEXT_SECONDARY);

		Component badge = null;
		int badgeColor = nameColor;
		int badgeBackground = BADGE_BACKGROUND;
		if (live) {
			// 「生效中」只会出现在开启状态；关闭时 isLive 必然为 false，所以这个徽章自然消失。
			badge = Component.translatable("caldera.screen.badge_live");
			badgeColor = ShadersScreen.STATUS_SUCCESS;
			badgeBackground = BADGE_LIVE_BACKGROUND;
		} else if (selected) {
			// 「已选中」在关闭时仍然保留：关闭不改变选择，用户需要看到再开启会回到哪个包。
			badge = Component.translatable("caldera.screen.badge_pending");
		}
		if (badge == null) {
			return;
		}

		int badgeWidth = font.width(badge) + BADGE_PADDING;
		int badgeX = this.getContentRight() - badgeWidth - BADGE_INSET;
		extractor.fill(badgeX, this.getContentY() + BADGE_OFFSET_Y, badgeX + badgeWidth, this.getContentY() + BADGE_OFFSET_Y + BADGE_HEIGHT, badgeBackground);
		extractor.text(font, badge, badgeX + BADGE_TEXT_INSET, this.getContentY() + BADGE_TEXT_OFFSET_Y, badgeColor);
	}

	@Override
	public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubleClick) {
		// 关闭光影时列表不可点。这里必须显式挡掉，不能只靠视觉：变暗只是提示，
		// 真正决定"点了有没有反应"的是这里。
		if (!this.screen.selectable() || event.button() != SDL_BUTTON_LEFT) {
			return false;
		}
		this.screen.onEntryClicked(this);
		return true;
	}
}
