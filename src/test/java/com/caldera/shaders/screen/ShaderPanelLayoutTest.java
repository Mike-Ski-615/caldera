package com.caldera.shaders.screen;

import com.caldera.shaders.screen.ShaderPanelLayout.Action;
import com.caldera.shaders.screen.ShaderPanelLayout.Layout;
import com.caldera.shaders.screen.ShaderPanelLayout.Rect;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShaderPanelLayout} 的单元测试。
 * <p>
 * 这里钉的正是"状态行位置"与"按钮位置"曾经各自推演同一个公式所导致的错位：那些不变量在大窗口下
 * 走非紧凑分支、看不出来，只在小窗口暴露，而小窗口恰恰不是日常使用的窗口尺寸。
 * <p>
 * 按钮相关的断言**遍历全部动作**，而不是写死六个名字：布局改用动作索引之后，新增一个动作会自动
 * 落进这些几何检查里，不需要有人记得回来补测试。
 */
class ShaderPanelLayoutTest {
	private static final int WIDE_WIDTH = 1280;
	private static final int WIDE_HEIGHT = 720;
	private static final int COMPACT_WIDTH = 400;
	private static final int COMPACT_HEIGHT = 240;

	private static final Set<Action> ALL_ACTIONS = EnumSet.allOf(Action.class);

	private static Rect button(Layout layout, Action action) {
		return layout.button(action);
	}

	@Test
	void wideLayoutPutsTheSidePanelRightOfTheMainPanelAndAlignsTheirEdges() {
		Layout layout = ShaderPanelLayout.compute(WIDE_WIDTH, WIDE_HEIGHT);
		assertFalse(layout.compact());
		assertTrue(layout.side().panel().x() > layout.listPanel().right(), "侧栏应在列表面板右侧且不重叠");
		assertTrue(layout.side().panel().x() - layout.listPanel().right() < 40, "两块面板之间的间隙应当是常数级的窄缝");
		assertEquals(layout.listPanel().y(), layout.side().panel().y(), "两个面板应当上对齐");
		assertEquals(layout.listPanel().height(), layout.side().panel().height(), "两个面板应当等高");
	}

	@Test
	void wideLayoutStacksListUnsupportedLineAndStatusWithoutOverlap() {
		Layout layout = ShaderPanelLayout.compute(WIDE_WIDTH, WIDE_HEIGHT);
		assertTrue(layout.listBox().bottom() <= layout.side().unsupportedY(), "被忽略条目那一行必须在列表下方");
		assertTrue(layout.side().unsupportedY() < layout.statusY(), "状态行必须在被忽略条目那一行下方");
		assertTrue(layout.statusY() <= layout.listPanel().bottom(), "状态行必须在面板内");
	}

	@Test
	void wideLayoutStacksSideButtonsInOrderWithoutOverlap() {
		Layout layout = ShaderPanelLayout.compute(WIDE_WIDTH, WIDE_HEIGHT);
		assertTrue(button(layout, Action.TOGGLE).bottom() <= button(layout, Action.APPLY).y(), "开启/关闭按钮在应用按钮之上");
		assertTrue(button(layout, Action.APPLY).bottom() <= button(layout, Action.RELOAD).y(), "应用按钮在重载按钮之上");
		assertEquals(button(layout, Action.OPEN_FOLDER).y(), button(layout, Action.REFRESH).y(), "打开文件夹与刷新同一行");
		assertTrue(button(layout, Action.OPEN_FOLDER).right() <= button(layout, Action.REFRESH).x(), "同一行的两个按钮不重叠");
		assertTrue(button(layout, Action.DONE).bottom() <= layout.side().panel().bottom(), "完成按钮必须在侧栏内");

		for (Rect rect : layout.buttons().values()) {
			assertTrue(rect.x() >= layout.side().panel().x(), "按钮必须在侧栏内(左)");
			assertTrue(rect.right() <= layout.side().panel().right(), "按钮必须在侧栏内(右)");
		}
	}

	@Test
	void compactLayoutHasNoSideGeometryAtAll() {
		Layout layout = ShaderPanelLayout.compute(COMPACT_WIDTH, COMPACT_HEIGHT);
		assertTrue(layout.compact());

		// 原先这里断言 sidePanel() == listPanel()。现在紧凑布局**没有**侧栏几何：
		// 读到 0 以为有意义的错写不出来，代价是必须问一次。
		assertThrows(IllegalStateException.class, layout::side);
	}

	@Test
	void compactLayoutKeepsStatusAboveTheButtonGridAndBelowTheList() {
		Layout layout = ShaderPanelLayout.compute(COMPACT_WIDTH, COMPACT_HEIGHT);
		assertTrue(layout.listBox().bottom() <= layout.statusY(), "列表必须在状态行之上");
		assertTrue(layout.statusY() <= button(layout, Action.TOGGLE).y(), "状态行必须在按钮区之上");

		for (Rect rect : layout.buttons().values()) {
			assertTrue(rect.x() >= layout.listPanel().x(), "按钮必须在面板内(左)");
			assertTrue(rect.right() <= layout.listPanel().right(), "按钮必须在面板内(右)");
			assertTrue(rect.y() >= layout.listPanel().y(), "按钮必须在面板内(上)");
			assertTrue(rect.bottom() <= layout.listPanel().bottom(), "按钮必须在面板内(下)");
		}
	}

	@Test
	void compactLayoutWrapsEveryActionIntoRowsWhenThePanelIsNarrow() {
		// 宽度 400：每行三个（七个动作 -> 三行）。
		Layout threeColumns = ShaderPanelLayout.compute(COMPACT_WIDTH, COMPACT_HEIGHT);
		assertEquals(button(threeColumns, Action.TOGGLE).y(), button(threeColumns, Action.APPLY).y(), "前两个按钮同一行");
		assertEquals(button(threeColumns, Action.APPLY).y(), button(threeColumns, Action.RELOAD).y(), "前三个按钮同一行");
		assertTrue(button(threeColumns, Action.SETTINGS).y() > button(threeColumns, Action.RELOAD).y(), "第四个按钮换到下一行");
		assertEquals(button(threeColumns, Action.TOGGLE).x(), button(threeColumns, Action.SETTINGS).x(), "换行后回到第一列");
		assertEquals(button(threeColumns, Action.SETTINGS).y(), button(threeColumns, Action.OPEN_FOLDER).y(), "第二行三个");
		assertTrue(button(threeColumns, Action.DONE).y() > button(threeColumns, Action.REFRESH).y(), "第七个按钮落在第三行");

		// 宽度 300：每行只放得下两个（七个动作 -> 四行）。
		Layout twoColumns = ShaderPanelLayout.compute(300, COMPACT_HEIGHT);
		assertEquals(button(twoColumns, Action.TOGGLE).y(), button(twoColumns, Action.APPLY).y(), "第一行两个");
		assertEquals(button(twoColumns, Action.RELOAD).y(), button(twoColumns, Action.SETTINGS).y(), "第二行两个");
		assertEquals(button(twoColumns, Action.OPEN_FOLDER).y(), button(twoColumns, Action.REFRESH).y(), "第三行两个");
		assertTrue(button(twoColumns, Action.DONE).y() > button(twoColumns, Action.REFRESH).y(), "第七个按钮单独一行");
		assertEquals(button(twoColumns, Action.TOGGLE).x(), button(twoColumns, Action.RELOAD).x(), "第一列左对齐");
		assertEquals(button(twoColumns, Action.APPLY).x(), button(twoColumns, Action.SETTINGS).x(), "第二列左对齐");
	}

	/**
	 * 每个动作都必须拿到矩形，两种布局都要。
	 * <p>
	 * 这一条是"新增动作自动被覆盖"的入口：它遍历 {@link Action} 的全部常量，所以往枚举里加一个动作
	 * 而忘了在某条分支里给它排位置，会在这里、以及上面那几条遍历式断言里立刻暴露。
	 */
	@Test
	void everyActionGetsARectInBothLayouts() {
		assertEquals(ALL_ACTIONS, ShaderPanelLayout.compute(WIDE_WIDTH, WIDE_HEIGHT).buttons().keySet());
		assertEquals(ALL_ACTIONS, ShaderPanelLayout.compute(COMPACT_WIDTH, COMPACT_HEIGHT).buttons().keySet());
		assertEquals(ALL_ACTIONS, ShaderPanelLayout.compute(300, COMPACT_HEIGHT).buttons().keySet());
	}

	@Test
	void contentWidthIsCappedOnVeryWideWindows() {
		Layout layout = ShaderPanelLayout.compute(4000, 1400);
		assertTrue(layout.listPanel().width() <= 980, "内容宽度应当有上限，避免超宽窗口上元素被拉得过散");
		assertTrue(layout.listPanel().x() > 0, "超宽窗口下内容应当居中");
	}

	@Test
	void absurdlySmallWindowsStillProducePositiveSizes() {
		Layout layout = ShaderPanelLayout.compute(100, 40);
		assertTrue(layout.listPanel().width() >= 1);
		assertTrue(layout.listPanel().height() >= 1);
		assertTrue(layout.listBox().width() >= 1);
		assertTrue(layout.listBox().height() >= 24, "列表有一个最小高度，避免退化成不可点的一行");
		for (Rect rect : layout.buttons().values()) {
			assertTrue(rect.width() >= 1, "按钮宽度不能为负");
		}
	}
}
