package com.caldera.shaders.screen;

import com.caldera.shaders.screen.ShaderPanelLayout.Action;
import com.caldera.shaders.screen.ShaderScreenState.Input;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShaderScreenState} 的单元测试——把那张 4×7 的状态表逐格钉死。
 * <p>
 * 这些断言此前无处可写：同样的判断曾经散在 {@code ShadersScreen.updateInteractivity()} 里，
 * 而那个类需要活的 {@code Minecraft} 才能构造。于是"关闭时列表还能点""重载期间连点开关会排队"
 * 这类问题只能在游戏里用眼睛发现。
 */
class ShaderScreenStateTest {
	private static final String BUILTIN = "__builtin__";
	private static final String PACK_X = "X.zip";
	private static final String PACK_Y = "Y.zip";

	/** 断言整行：「打开文件夹」与「完成」永远可用，所以只作为不变量一起检查。 */
	private static void assertRow(
			ShaderScreenState state,
			boolean toggle,
			boolean apply,
			boolean reload,
			boolean settings,
			boolean refresh,
			boolean listSelectable) {
		assertEquals(toggle, state.isEnabled(Action.TOGGLE), "开启/关闭");
		assertEquals(apply, state.isEnabled(Action.APPLY), "应用");
		assertEquals(reload, state.isEnabled(Action.RELOAD), "重载光影");
		assertEquals(settings, state.isEnabled(Action.SETTINGS), "设置");
		assertEquals(refresh, state.isEnabled(Action.REFRESH), "刷新");
		assertTrue(state.isEnabled(Action.OPEN_FOLDER), "打开文件夹应当永远可用");
		assertTrue(state.isEnabled(Action.DONE), "完成应当永远可用");
		assertEquals(listSelectable, state.listSelectable(), "列表可点");
	}

	// ------------------------------------------------------------ 三种稳定状态

	@Test
	void s1OffLocksTheListApplyAndReloadButKeepsRefresh() {
		ShaderScreenState state = ShaderScreenState.of(new Input(false, BUILTIN, BUILTIN, false));

		assertRow(state, true, false, false, true, true, false);
		assertFalse(state.hasPendingPackChange());
	}

	@Test
	void s2OnWithNoDraftOnlyOffersApplyAsDisabled() {
		ShaderScreenState state = ShaderScreenState.of(new Input(true, PACK_X, PACK_X, false));

		assertRow(state, true, false, true, true, true, true);
		assertFalse(state.hasPendingPackChange());
	}

	@Test
	void s3OnWithADraftOffersApply() {
		ShaderScreenState state = ShaderScreenState.of(new Input(true, PACK_Y, PACK_X, false));

		assertRow(state, true, true, true, true, true, true);
		assertTrue(state.hasPendingPackChange());
	}

	@Test
	void theBuiltinPackCountsAsAnOrdinaryChoice() {
		// 从外部包切回内置包，同样是一次需要「应用」的草稿改动。
		ShaderScreenState state = ShaderScreenState.of(new Input(true, BUILTIN, PACK_X, false));

		assertTrue(state.hasPendingPackChange());
		assertTrue(state.isEnabled(Action.APPLY));
	}

	// ------------------------------------------------------------ 叠加态：我方操作中

	/**
	 * 「刷新」与「设置」也在这张名单里：它们都要读盘（「刷新」还可能在"已生效的包没了"时触发一次重载），
	 * 所以和另外三个一样，不允许在操作进行中再叠一个。
	 */
	@Test
	void busyLocksExactlyTheFiveDiskOrReloadButtons() {
		ShaderScreenState busy = ShaderScreenState.of(new Input(true, PACK_Y, PACK_X, true));

		assertRow(busy, false, false, false, false, false, true);
	}

	@Test
	void busyAlsoLocksRefreshWhileShadersAreOff() {
		ShaderScreenState busy = ShaderScreenState.of(new Input(false, BUILTIN, BUILTIN, true));

		assertFalse(busy.isEnabled(Action.REFRESH), "操作进行中，刷新不该还能按");
		assertFalse(busy.isEnabled(Action.SETTINGS), "设置要读盘，操作进行中也不该能按");
		assertTrue(busy.isEnabled(Action.OPEN_FOLDER), "但打开文件夹不受影响");
		assertTrue(busy.isEnabled(Action.DONE), "完成也不受影响");
	}

	/**
	 * 「设置」不依赖开关：光影关着的时候照样可以先把这个包的选项调好。
	 * 它只读那个包的 {@code caldera.json}，与渲染器是否在跑无关。
	 */
	@Test
	void settingsStaysAvailableWhileShadersAreOff() {
		ShaderScreenState off = ShaderScreenState.of(new Input(false, BUILTIN, BUILTIN, false));

		assertTrue(off.isEnabled(Action.SETTINGS));
	}

	/**
	 * 操作进行中，列表**照样**可以点：草稿是纯本地状态，用户可以先攒好选择，等忙完了再按应用。
	 * 禁用它只会让界面在每次开关之后有几秒钟像死了一样。
	 */
	@Test
	void busyNeverChangesWhetherTheListIsSelectable() {
		for (boolean on : new boolean[] {true, false}) {
			ShaderScreenState idle = ShaderScreenState.of(new Input(on, PACK_X, PACK_X, false));
			ShaderScreenState busy = ShaderScreenState.of(new Input(on, PACK_X, PACK_X, true));
			assertEquals(
					idle.listSelectable(),
					busy.listSelectable(),
					"开关=" + on + " 时，忙不忙不该改变列表是否可点");
		}
	}

	// ------------------------------------------------------------ 防御性规则

	/**
	 * "关闭时应用必定禁用"是**规则本身**，不是靠别处维持的不变量。
	 * 即便有人构造出一个"已关闭但草稿与已生效不同"的输入（正常流程下不可能），应用也不该可用：
	 * 关闭状态下提交一个包选择没有意义，而列表此时本来就不可点。
	 */
	@Test
	void offDisablesApplyEvenIfADraftSomehowDiffers() {
		ShaderScreenState state = ShaderScreenState.of(new Input(false, PACK_Y, PACK_X, false));

		assertTrue(state.hasPendingPackChange(), "这个输入确实带着草稿改动");
		assertFalse(state.isEnabled(Action.APPLY), "但关闭时应用必须禁用");
	}

	// ------------------------------------------------------------ 完备性

	/**
	 * 每个动作都必须有可用性。往 {@link Action} 里加一个按钮却忘了在状态机里给它定规则，
	 * 会在这里立刻暴露。
	 */
	@Test
	void everyActionGetsAnAvailability() {
		ShaderScreenState state = ShaderScreenState.of(new Input(true, PACK_X, PACK_X, false));

		assertEquals(EnumSet.allOf(Action.class), state.allActions().keySet());
	}
}
