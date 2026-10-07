package com.caldera.shaders.screen;

import com.caldera.shaders.screen.ShaderPanelLayout.Action;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 光影界面的**纯函数状态机**：给定"开关 / 草稿 / 已生效 / 我方重载中"，算出每个按钮能不能点、
 * 列表能不能点。
 * <p>
 * <b>为什么单独抽出来：</b>这张表有 4 种状态 × 6 个按钮，还外加"列表可不可点"。留在
 * {@code ShadersScreen} 里的话，它是本轮所有界面设计里**唯一没有任何验证手段**的一块——
 * 界面需要活的 {@code Minecraft} 才能构造，单元测试根本碰不到。抽成纯函数之后，整张表可以在
 * 没有游戏的情况下被逐格钉死，做法与 {@link ShaderPanelLayout} 一致。
 * <p>
 * <b>状态表：</b>
 * <pre>
 *                    开启/关闭  应用  重载  设置  文件夹  刷新  完成   列表可点
 *  S1 关闭              ✅      ⛔    ⛔    ✅    ✅     ✅    ✅      ⛔
 *  S2 开启·无草稿改动    ✅      ⛔    ✅    ✅    ✅     ✅    ✅      ✅
 *  S3 开启·有草稿改动    ✅      ✅    ✅    ✅    ✅     ✅    ✅      ✅
 *  叠加：我方操作中       ⛔      ⛔    ⛔    ⛔    ✅     ⛔    ✅     不变
 * </pre>
 * 最后一行是叠加态：它只锁住**会读盘或发起资源重载**的五个按钮（开启/关闭、应用、重载、设置、刷新），
 * 「打开文件夹」「完成」与列表不受影响。
 * <p>
 * 这张表原本是 4 状态 × 6 按钮，随「设置」按钮一起扩到 7 个。之所以把「设置」也纳入这张表，
 * 是因为它的全部价值就在于**覆盖每一个按钮**——把它留在表外，它就会成为唯一一个可用性没人管住的控件。
 * <p>
 * {@code busy} 的语义是"我方操作中"，它同时覆盖后台扫描与资源重载两个阶段——从用户视角，
 * "我刚按了一下、界面在忙"就是一件事，拆成两个标志只会多出"扫描中且重载中"这种要额外定义的组合。
 */
final class ShaderScreenState {
	/** 界面此刻的四个输入。 */
	record Input(boolean shadersEnabled, String pendingPackId, String activePackId, boolean busy) {
	}

	private final EnumMap<Action, Boolean> actionEnabled;
	private final boolean listSelectable;
	private final boolean pendingPackChange;

	private ShaderScreenState(EnumMap<Action, Boolean> actionEnabled, boolean listSelectable, boolean pendingPackChange) {
		this.actionEnabled = actionEnabled;
		this.listSelectable = listSelectable;
		this.pendingPackChange = pendingPackChange;
	}

	static ShaderScreenState of(Input input) {
		boolean on = input.shadersEnabled();
		boolean busy = input.busy();
		boolean pendingChange = !Objects.equals(input.pendingPackId(), input.activePackId());

		EnumMap<Action, Boolean> enabled = new EnumMap<>(Action.class);
		// 「开启/关闭」「应用」「重载」都会发起一次资源重载，所以在我们自己那次重载未完成时必须锁住。
		// 只认"我方发起、尚未完成"，不认原版的 resourceReloading()——后者还包含启动阶段与
		// LoadingOverlay 约两秒的淡出，用它会让界面在无关重载时也无故变灰。
		enabled.put(Action.TOGGLE, !busy);
		// 「应用」提交的是列表里的草稿。关闭时列表不可点，草稿不可能与已生效不同；这里再把开关
		// 作为条件写死一次，是为了让"S1 的应用必定禁用"成为规则本身，而不是依赖别处维持的不变量。
		enabled.put(Action.APPLY, !busy && on && pendingChange);
		// 「重载」重载的是**已生效**的那个包；关闭时没有 Pass 存在，没有可重载的对象。
		enabled.put(Action.RELOAD, !busy && on);
		// 「刷新」现在也要读盘、并且可能在"包没了"时触发一次重载，所以它和上面三个是同一类：
		// 操作进行中不能再叠一个。
		enabled.put(Action.REFRESH, !busy);
		// 「设置」要读那个包的 caldera.json，也是读盘，所以同样受忙碌态约束。
		// 但它不依赖开关：关闭状态下照样可以先把这个包的选项调好。
		enabled.put(Action.SETTINGS, !busy);
		// 「打开文件夹」与「完成」不碰任何异步的东西，任何时候都可用。
		enabled.put(Action.OPEN_FOLDER, true);
		enabled.put(Action.DONE, true);

		// 列表可否点击只由开关决定：草稿是纯本地状态，我方重载进行中照样可以先把选择攒好。
		return new ShaderScreenState(enabled, on, pendingChange);
	}

	/** 某个动作此刻能不能点。漏算一个动作会**大声失败**，而不是安静地当成不可用。 */
	boolean isEnabled(Action action) {
		Boolean value = this.actionEnabled.get(action);
		if (value == null) {
			throw new IllegalStateException("状态机没有为动作 " + action + " 算出可用性");
		}
		return value;
	}

	/** 列表条目此刻能不能点。 */
	boolean listSelectable() {
		return this.listSelectable;
	}

	/** 草稿选中的包是否与已生效的不同——即「应用」是否有东西可提交。 */
	boolean hasPendingPackChange() {
		return this.pendingPackChange;
	}

	/** 供测试遍历全部动作；正式代码不该用这个。 */
	Map<Action, Boolean> allActions() {
		return Map.copyOf(this.actionEnabled);
	}
}
