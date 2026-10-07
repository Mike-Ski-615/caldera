package com.caldera.shaders.screen;

import java.util.EnumMap;
import java.util.Map;

/**
 * 光影界面的**纯函数布局**：给定窗口尺寸，算出所有面板、列表与按钮的矩形。
 * <p>
 * 为什么值得单独抽出来：原先"状态行的位置"和"按钮的位置"由两段独立代码各自推导同一个公式
 * （列数、行数、动作区高度、行间距），任何一处改动都会让另一个错位；而错位在 2560×1346 这类
 * 大窗口下**根本看不出来**（走的是非紧凑分支），只在小窗口暴露。现在它们只有一个来源，
 * 而且这个来源是纯函数、可以直接单元测试。
 * <p>
 * <b>按钮按动作索引、而不是按位置。</b>此前六个矩形是记录里的六个字段，宽屏分支用无名参数逐个传入，
 * 紧凑分支用 {@code slots[0..5]} 按下标绑定，而"一共六个"是另一个常量——三处必须互相对齐，
 * 唯一的保障是注释。现在有了 {@link Action}：个数就是枚举常量个数，顺序就是声明顺序，
 * 调用方按名字取矩形，而测试可以遍历全部动作，于是<b>新增一个动作会自动落进几何测试</b>。
 * <p>
 * 记录里的坐标约定：{@code x}/{@code y} 是左上角，{@code width}/{@code height} 是尺寸。
 */
final class ShaderPanelLayout {
	static final int BUTTON_HEIGHT = 20;

	/** 界面上每一个按钮。声明顺序就是紧凑布局里按钮的排列顺序。 */
	enum Action {
		TOGGLE,
		APPLY,
		RELOAD,
		SETTINGS,
		OPEN_FOLDER,
		REFRESH,
		DONE
	}

	private static final int BUTTON_GAP = 6;
	private static final int COMPACT_BUTTON_GAP = 5;
	private static final int MAX_CONTENT_WIDTH = 980;
	private static final int PANEL_GAP = 14;
	private static final int LARGE_MARGIN = 18;
	private static final int SMALL_MARGIN = 6;
	private static final int COMPACT_MAX_WIDTH = 760;
	private static final int COMPACT_MAX_HEIGHT = 260;
	private static final int COMPACT_COLUMN_THRESHOLD = 320;
	private static final int TEXT_INSET = 16;
	private static final int TITLE_OFFSET = 14;
	private static final int SUBTITLE_OFFSET = 30;
	private static final int LIST_INSET = 12;
	private static final int WIDE_LIST_TOP = 58;
	private static final int WIDE_LIST_BOTTOM_INSET = 56;
	private static final int WIDE_STATUS_BOTTOM_INSET = 34;
	private static final int WIDE_UNSUPPORTED_BOTTOM_INSET = 48;
	private static final int SIDE_INNER_INSET = 14;
	private static final int SIDE_TITLE_OFFSET = 14;
	private static final int SIDE_APPLIED_OFFSET = 34;
	private static final int SIDE_SELECTED_OFFSET = 48;
	private static final int SIDE_WARNING_OFFSET = 64;
	private static final int SIDE_BUTTONS_TOP = 86;
	private static final int DONE_BOTTOM_INSET = 30;
	private static final int COMPACT_LIST_TOP = 48;
	private static final int COMPACT_ACTIONS_BOTTOM_INSET = 10;
	private static final int COMPACT_STATUS_GAP = 18;
	private static final int COMPACT_LIST_BOTTOM_GAP = 4;
	private static final int MIN_LIST_HEIGHT = 24;

	private ShaderPanelLayout() {
	}

	record Rect(int x, int y, int width, int height) {
		int right() {
			return this.x + this.width;
		}

		int bottom() {
			return this.y + this.height;
		}
	}

	/**
	 * 一次算完。紧凑布局下 {@code sidePanel} 与 {@code listPanel} 相同，且 {@code side*} 与
	 * {@code unsupportedY} 无意义（界面在紧凑模式下不画侧栏与被忽略条目那一行）。
	 */
	record Layout(
			boolean compact,
			Rect listPanel,
			Rect sidePanel,
			Rect listBox,
			Map<Action, Rect> buttons,
			int textX,
			int textWidth,
			int titleY,
			int subtitleY,
			int statusY,
			int unsupportedY,
			int sideTextX,
			int sideTextWidth,
			int sideTitleY,
			int appliedY,
			int selectedY,
			int warningY) {

		/** 按动作取矩形。漏算某个动作会**大声失败**，而不是安静地拿到一个越界坐标。 */
		Rect button(Action action) {
			Rect rect = this.buttons.get(action);
			if (rect == null) {
				throw new IllegalStateException("布局没有为动作 " + action + " 算出矩形");
			}
			return rect;
		}
	}

	static Layout compute(int screenWidth, int screenHeight) {
		int margin = screenWidth < 520 || screenHeight < 320 ? SMALL_MARGIN : LARGE_MARGIN;
		int contentWidth = Math.clamp(screenWidth - margin * 2, 1, MAX_CONTENT_WIDTH);
		int contentHeight = Math.max(1, screenHeight - margin * 2);
		int contentX = (screenWidth - contentWidth) / 2;
        boolean compact = contentWidth < COMPACT_MAX_WIDTH || contentHeight < COMPACT_MAX_HEIGHT;

		if (compact) {
			Rect panel = new Rect(contentX, margin, contentWidth, contentHeight);
			int textX = panel.x() + TEXT_INSET;
			int textWidth = panel.width() - TEXT_INSET * 2;
			return computeCompact(panel, textX, textWidth, panel.y() + TITLE_OFFSET, panel.y() + SUBTITLE_OFFSET);
		}

		// 宽屏是"两块面板并排占满内容区"：左边给列表，右边给状态与按钮，中间留一条缝。
		int sideWidth = Math.clamp(contentWidth / 3, 220, 260);
		int listWidth = contentWidth - sideWidth - PANEL_GAP;
		Rect listPanel = new Rect(contentX, margin, listWidth, contentHeight);
		Rect sidePanel = new Rect(listPanel.right() + PANEL_GAP, margin, sideWidth, contentHeight);
		int textX = listPanel.x() + TEXT_INSET;
		int textWidth = listPanel.width() - TEXT_INSET * 2;
		return computeWide(listPanel, sidePanel, textX, textWidth, listPanel.y() + TITLE_OFFSET, listPanel.y() + SUBTITLE_OFFSET);
	}

	private static Layout computeCompact(Rect panel, int textX, int textWidth, int titleY, int subtitleY) {
		int innerX = panel.x() + LIST_INSET;
		int innerWidth = Math.max(1, panel.width() - LIST_INSET * 2);

		Action[] actions = Action.values();
		int columns = innerWidth >= COMPACT_COLUMN_THRESHOLD ? 3 : 2;
		int rows = (actions.length + columns - 1) / columns;
		int actionsHeight = rows * BUTTON_HEIGHT + (rows - 1) * COMPACT_BUTTON_GAP;
		int actionsY = panel.bottom() - COMPACT_ACTIONS_BOTTOM_INSET - actionsHeight;
		int statusY = actionsY - COMPACT_STATUS_GAP;

		int listTop = panel.y() + COMPACT_LIST_TOP;
		int listBottom = statusY - COMPACT_LIST_BOTTOM_GAP;
		Rect listBox = new Rect(innerX, listTop, innerWidth, Math.max(MIN_LIST_HEIGHT, listBottom - listTop));

		int buttonWidth = Math.max(1, (innerWidth - (columns - 1) * COMPACT_BUTTON_GAP) / columns);
		Map<Action, Rect> buttons = new EnumMap<>(Action.class);
		for (int i = 0; i < actions.length; i++) {
			buttons.put(actions[i], new Rect(
					innerX + (i % columns) * (buttonWidth + COMPACT_BUTTON_GAP),
					actionsY + (i / columns) * (BUTTON_HEIGHT + COMPACT_BUTTON_GAP),
					buttonWidth,
					BUTTON_HEIGHT));
		}

		return new Layout(
				true,
				panel,
				panel,
				listBox,
				Map.copyOf(buttons),
				textX,
				textWidth,
				titleY,
				subtitleY,
				statusY,
				0,
				0,
				0,
				0,
				0,
				0,
				0);
	}

	private static Layout computeWide(Rect listPanel, Rect sidePanel, int textX, int textWidth, int titleY, int subtitleY) {
		int listTop = listPanel.y() + WIDE_LIST_TOP;
		int listBottom = listPanel.bottom() - WIDE_LIST_BOTTOM_INSET;
		Rect listBox = new Rect(
				listPanel.x() + LIST_INSET,
				listTop,
				listPanel.width() - LIST_INSET * 2,
				Math.max(MIN_LIST_HEIGHT, listBottom - listTop));

		int sideTextX = sidePanel.x() + SIDE_INNER_INSET;
		int sideTextWidth = sidePanel.width() - SIDE_INNER_INSET * 2;
		int buttonY = sidePanel.y() + SIDE_BUTTONS_TOP;
		int halfWidth = (sideTextWidth - BUTTON_GAP) / 2;

		Map<Action, Rect> buttons = new EnumMap<>(Action.class);
		buttons.put(Action.TOGGLE, new Rect(sideTextX, buttonY, sideTextWidth, BUTTON_HEIGHT));
		buttonY += BUTTON_HEIGHT + BUTTON_GAP;
		buttons.put(Action.APPLY, new Rect(sideTextX, buttonY, sideTextWidth, BUTTON_HEIGHT));
		buttonY += BUTTON_HEIGHT + BUTTON_GAP;
		buttons.put(Action.RELOAD, new Rect(sideTextX, buttonY, sideTextWidth, BUTTON_HEIGHT));
		buttonY += BUTTON_HEIGHT + BUTTON_GAP;
		buttons.put(Action.SETTINGS, new Rect(sideTextX, buttonY, sideTextWidth, BUTTON_HEIGHT));
		buttonY += BUTTON_HEIGHT + BUTTON_GAP;
		buttons.put(Action.OPEN_FOLDER, new Rect(sideTextX, buttonY, halfWidth, BUTTON_HEIGHT));
		buttons.put(Action.REFRESH, new Rect(sideTextX + halfWidth + BUTTON_GAP, buttonY, halfWidth, BUTTON_HEIGHT));
		buttons.put(Action.DONE, new Rect(sideTextX, sidePanel.bottom() - DONE_BOTTOM_INSET, sideTextWidth, BUTTON_HEIGHT));

		return new Layout(
				false,
				listPanel,
				sidePanel,
				listBox,
				Map.copyOf(buttons),
				textX,
				textWidth,
				titleY,
				subtitleY,
				listPanel.bottom() - WIDE_STATUS_BOTTOM_INSET,
				listPanel.bottom() - WIDE_UNSUPPORTED_BOTTOM_INSET,
				sideTextX,
				sideTextWidth,
				sidePanel.y() + SIDE_TITLE_OFFSET,
				sidePanel.y() + SIDE_APPLIED_OFFSET,
				sidePanel.y() + SIDE_SELECTED_OFFSET,
				sidePanel.y() + SIDE_WARNING_OFFSET);
	}
}
