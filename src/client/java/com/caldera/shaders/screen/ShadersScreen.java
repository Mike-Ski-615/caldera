package com.caldera.shaders.screen;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.logging.LogUtils;
import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import com.caldera.shaders.runtime.ShaderRuntime;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 光影选择界面。
 * <p>
 * <b>状态模型。</b>只有一份真值——{@link ShaderRuntime#config()}。界面上唯一本地的东西是
 * {@code pendingPackId}，也就是"列表里点中、但还没按应用"的那个包。
 * <ul>
 *   <li><b>已生效</b>直接读配置：开不开、在用哪个包。</li>
 *   <li><b>草稿</b>只有"选中哪个包"这一件事。开关**不是**草稿——点「开启/关闭」立即生效，
 *       不需要再按一次应用。这样手上就永远不会挂着一个"待确认的开关"。</li>
 * </ul>
 * <p>
 * <b>按钮可用性由 {@link ShaderScreenState} 这个纯函数算出</b>：给它"开关 / 草稿 / 已生效 /
 * 我方重载中"，它给出七个按钮各自能不能点、列表能不能点。判断不在本类里，因为本类需要活的
 * {@code Minecraft} 才能构造、无法被单元测试触碰。
 * <p>
 * <b>忙碌态是有范围的。</b>只有**我们自己发起、尚未结束**的那次操作会让五个会读盘或触发资源重载的
 * 按钮（开启/关闭、应用、重载、设置、刷新）暂时锁住；「打开文件夹」「完成」与列表始终可用。
 * <p>
 * <b>重载边沿就是那个 future。</b>本界面不再监听游戏的资源重载——{@link ShaderRuntime#applyConfig}
 * 返回的 {@code CompletableFuture} 在重载结束时完成，成功与失败都在它上面，没有第三种结果，
 * 所以也没有"超时"这个状态需要界面去猜。
 * <p>
 * 与 0.3.1 原版的差异（迁移到 0.5.1 运行时的结果）：不再有"该包不含全屏 Pass"的警告——0.5.1 里
 * 能被扫描到的包**全都是**原生包（必须通过 {@code caldera.json} 解析），这个概念不存在；
 * "着色器不可用"改为读 {@link ShaderRuntime#rendererFailure()}。
 */
public final class ShadersScreen extends Screen {
	private static final Logger LOGGER = LogUtils.getLogger();

	// 面板配色。原先它们在一个抽象基类里，而那个基类只有一个子类、也没提供任何抽象，
	// 只是把代码藏到了另一个文件里，所以合并进来。条目类与它同包，直接引用。
	static final int PANEL_BACKGROUND = 0xD0141B24;
	static final int PANEL_BORDER = 0xFF516579;
	static final int PANEL_ACCENT = 0xFFB38A49;
	static final int TEXT_PRIMARY = 0xFFF6F8FB;
	static final int TEXT_SECONDARY = 0xFFB7C2CE;
	static final int STATUS_SUCCESS = 0xFFA6E3A1;
	static final int STATUS_ERROR = 0xFFF3A6A6;

	private static final Component TITLE = Component.translatable("caldera.screen.title");
	/** 光影开着、渲染器却报错时的提示。刻意带上补救动作。 */
	private static final Component UNAVAILABLE_WARNING = Component.translatable("caldera.screen.warning.unavailable");

	private final Screen lastScreen;

	private ShaderPackList packList;
	private Button toggleButton;
	private Button applyButton;
	private Button reloadButton;
	private Button settingsButton;
	private Button openFolderButton;
	private Button refreshButton;
	private Button doneButton;

	private boolean statusError;
	private Component status = Component.empty();
	private ShaderPackScanner.ScanResult scan = new ShaderPackScanner.ScanResult(List.of(), List.of());

	/** 列表里点中、但尚未应用的那个包。界面唯一的本地状态。 */
	private String pendingPackId;

	/** 我们自己发起、尚未结束的那次操作。只有它会让读盘/重载类按钮暂时锁住。 */
	private boolean busy;

	/**
	 * 界面是否已经关掉。
	 * <p>
	 * 发起的操作用 future 收尾，而那个回调**不会**因为界面关闭而取消。没有这个守卫的话，
	 * 点完应用马上关界面，旧界面的 {@code resyncUi()} 仍会在之后跑一遍——不会崩，但没有理由继续。
	 */
	private boolean closed;

	/** 每帧算一次的按钮/列表可用性；见 {@link ShaderScreenState}。 */
	private ShaderScreenState state = ShaderScreenState.of(
			new ShaderScreenState.Input(false, ShaderConfig.BUILTIN_PACK_ID, ShaderConfig.BUILTIN_PACK_ID, false));

	public ShadersScreen(Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
		this.refreshRuntimeState();
		this.syncPendingFromActive();
		this.resetStatus();
	}

	// ---------------------------------------------------------------- 界面骨架

	@Override
	protected void init() {
		this.clearWidgets();
		this.closed = false;
		// 只读扫描缓存，不读盘：原版在窗口尺寸变化时也会重新调用 init。
		this.refreshRuntimeState();

		this.packList = this.addRenderableWidget(new ShaderPackList(this.minecraft));
		this.toggleButton = this.addRenderableWidget(Button.builder(this.toggleLabel(), button -> this.toggleShaders()).build());
		this.applyButton = this.addRenderableWidget(Button.builder(Component.translatable("caldera.screen.button.apply"), button -> this.applyPending()).build());
		this.reloadButton = this.addRenderableWidget(Button.builder(Component.translatable("caldera.screen.button.reload"), button -> this.reloadActive()).build());
		this.settingsButton = this.addRenderableWidget(Button.builder(Component.translatable("caldera.screen.button.settings"), button -> this.openSettings()).build());
		this.openFolderButton = this.addRenderableWidget(Button.builder(Component.translatable("caldera.screen.button.open_folder"), button -> this.openShaderFolder()).build());
		this.refreshButton = this.addRenderableWidget(Button.builder(Component.translatable("caldera.screen.button.refresh"), button -> this.refreshFolder()).build());
		this.doneButton = this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).build());

		// 注意这里**不**重新同步 pending：窗口尺寸变化会重新 init，而用户在改窗口大小之前
		// 已经点选但还没应用的包不应该被悄悄丢掉。
		this.refreshPackEntries();
		this.repositionElements();
		this.refreshInteractivity();
		this.setInitialFocus(this.packList);
	}

	@Override
	protected void repositionElements() {
		if (this.packList == null) {
			return;
		}
		ShaderPanelLayout.Layout layout = this.panelLayout();
		this.applyRect(this.packList, layout.listBox());
		// 按动作取矩形，不按位置：布局那边少了哪个动作会大声失败，而不是把矩形错配给别的按钮。
		this.applyRect(this.toggleButton, layout.button(ShaderPanelLayout.Action.TOGGLE));
		this.applyRect(this.applyButton, layout.button(ShaderPanelLayout.Action.APPLY));
		this.applyRect(this.reloadButton, layout.button(ShaderPanelLayout.Action.RELOAD));
		this.applyRect(this.settingsButton, layout.button(ShaderPanelLayout.Action.SETTINGS));
		this.applyRect(this.openFolderButton, layout.button(ShaderPanelLayout.Action.OPEN_FOLDER));
		this.applyRect(this.refreshButton, layout.button(ShaderPanelLayout.Action.REFRESH));
		this.applyRect(this.doneButton, layout.button(ShaderPanelLayout.Action.DONE));
		this.updateButtonLabels(layout.compact());
	}

	private void applyRect(ShaderPackList list, ShaderPanelLayout.Rect rect) {
		list.updateSizeAndPosition(rect.width(), rect.height(), rect.x(), rect.y());
	}

	private void applyRect(Button button, ShaderPanelLayout.Rect rect) {
		button.setRectangle(rect.width(), rect.height(), rect.x(), rect.y());
	}

	private ShaderPanelLayout.Layout panelLayout() {
		return ShaderPanelLayout.compute(this.width, this.height);
	}

	// ---------------------------------------------------------------- 绘制

	@Override
	public void extractRenderState(@NonNull GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		// 每帧重算一次：条目问 selectable()、下面读 hasPendingPackChange() 时拿到的都是本帧的答案。
		this.refreshState();

		ShaderPanelLayout.Layout layout = this.panelLayout();
		this.fillPanel(extractor, layout.listPanel());
		if (!layout.compact()) {
			this.fillPanel(extractor, layout.sidePanel());
			extractor.text(this.font, Component.translatable("caldera.screen.side.title"), layout.sideTextX(), layout.sideTitleY(), TEXT_PRIMARY, true);
			extractor.text(this.font, this.appliedLine(), layout.sideTextX(), layout.appliedY(), TEXT_SECONDARY);
			extractor.text(
					this.font,
					Component.translatable("caldera.screen.side.selected", this.packLabelText(this.pendingPackId)),
					layout.sideTextX(),
					layout.selectedY(),
					state.hasPendingPackChange() ? PANEL_ACCENT : TEXT_SECONDARY);
			Component warning = this.sidePanelWarning();
			if (warning != null) {
				extractor.textWithWordWrap(this.font, warning, layout.sideTextX(), layout.warningY(), layout.sideTextWidth(), STATUS_ERROR);
			}
		}

		extractor.text(this.font, TITLE, layout.textX(), layout.titleY(), TEXT_PRIMARY, true);
		extractor.textWithWordWrap(this.font, this.listSubtitle(layout.compact()), layout.textX(), layout.subtitleY(), layout.textWidth(), TEXT_SECONDARY);
		// 紧凑布局没有侧栏，那句"着色器不可用"无处可去，只好顶掉这一行。它是**持续成立**的状态，
		// 而这一行本来是"刚才那次操作的结果"、属于瞬时信息，被顶掉可以接受——何况提示自带补救动作。
		boolean compactUnavailable = layout.compact() && this.shaderUnavailable();
		extractor.textWithWordWrap(
				this.font,
				compactUnavailable ? UNAVAILABLE_WARNING : this.status,
				layout.textX(),
				layout.statusY(),
				layout.textWidth(),
				(compactUnavailable || this.statusError) ? STATUS_ERROR : STATUS_SUCCESS);
		if (!layout.compact() && !this.unsupportedPacks().isEmpty()) {
			extractor.text(
					this.font,
					Component.translatable("caldera.screen.unsupported", this.unsupportedPacks().size()),
					layout.textX(),
					layout.unsupportedY(),
					STATUS_ERROR);
		}
		super.extractRenderState(extractor, mouseX, mouseY, partialTick);
	}

	private Component appliedLine() {
		if (!this.activeEnabled()) {
			return Component.translatable("caldera.screen.side.applied_off");
		}
		return Component.translatable("caldera.screen.side.applied", this.packLabelText(this.activePackId()));
	}

	private void fillPanel(GuiGraphicsExtractor extractor, ShaderPanelLayout.Rect panel) {
		extractor.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), PANEL_BACKGROUND);
		extractor.outline(panel.x(), panel.y(), panel.width(), panel.height(), PANEL_BORDER);
		extractor.fill(panel.x() + 1, panel.y() + 1, panel.right() - 1, panel.y() + 5, PANEL_ACCENT);
	}

	@Override
	public void extractBackground(@NonNull GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		// 在世界里打开时不画背景遮罩，让光影效果能直接看到。
		if (this.minecraft.level != null) {
			return;
		}
		super.extractBackground(extractor, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		this.closed = true;
		this.minecraft.setScreenAndShow(this.lastScreen);
	}

	// ---------------------------------------------------------------- 状态

	/** 重新读取运行时状态、重建列表条目、重排控件、刷新可用性。四处调用点收敛到这一个入口。 */
	private void resyncUi() {
		this.refreshRuntimeState();
		this.refreshPackEntries();
		this.repositionElements();
		this.refreshInteractivity();
	}

	/** 只读运行时**缓存的**扫描结果：窗口尺寸变化会重新 init，那时不该再读一遍盘。 */
	private void refreshRuntimeState() {
		this.scan = ShaderRuntime.scanResult();
	}

	private List<ShaderPackScanner.AvailableShaderPack> packs() {
		return this.scan.supportedPacks();
	}

	private List<ShaderPackScanner.UnsupportedShaderPack> unsupportedPacks() {
		return this.scan.unsupportedPacks();
	}

	/** "已生效"的唯一真值来源就是配置本身，界面不再存副本。 */
	private boolean activeEnabled() {
		return ShaderRuntime.config().enabled();
	}

	private String activePackId() {
		String selectedPackId = ShaderRuntime.config().selectedPackId();
		return ShaderPackScanner.isKnownPackId(selectedPackId, this.packs()) ? selectedPackId : ShaderConfig.BUILTIN_PACK_ID;
	}

	/** 把草稿收敛回已生效的那个包。「开启/关闭」与每次操作完成后都会走这里。 */
	private void syncPendingFromActive() {
		this.pendingPackId = this.activePackId();
	}

	/** 重新算出按钮与列表的可用性，并推给控件。判断本身在 {@link ShaderScreenState} 里。 */
	private void refreshInteractivity() {
		this.refreshState();

		this.toggleButton.active = state.isEnabled(ShaderPanelLayout.Action.TOGGLE);
		this.applyButton.active = state.isEnabled(ShaderPanelLayout.Action.APPLY);
		this.reloadButton.active = state.isEnabled(ShaderPanelLayout.Action.RELOAD);
		this.settingsButton.active = state.isEnabled(ShaderPanelLayout.Action.SETTINGS);
		this.openFolderButton.active = state.isEnabled(ShaderPanelLayout.Action.OPEN_FOLDER);
		this.refreshButton.active = state.isEnabled(ShaderPanelLayout.Action.REFRESH);
		this.doneButton.active = state.isEnabled(ShaderPanelLayout.Action.DONE);
		this.packList.setSelectable(state.listSelectable());
		this.updateButtonLabels(this.panelLayout().compact());
	}

	/** 只重算状态，不碰控件——构造期与绘制期都会用到。 */
	private void refreshState() {
		this.state = ShaderScreenState.of(new ShaderScreenState.Input(
				this.activeEnabled(), this.pendingPackId, this.activePackId(), this.busy));
	}

	private void refreshPackEntries() {
		List<ShaderPackEntry> entries = new ArrayList<>();
		ShaderPackEntry builtinEntry = new ShaderPackEntry(
				this,
				ShaderConfig.BUILTIN_PACK_ID,
				Component.translatable("caldera.screen.builtin_name"),
				Component.translatable("caldera.screen.builtin_type"));
		entries.add(builtinEntry);
		ShaderPackEntry selectedEntry = Objects.equals(this.pendingPackId, ShaderConfig.BUILTIN_PACK_ID) ? builtinEntry : null;
		for (ShaderPackScanner.AvailableShaderPack pack : this.packs()) {
			// 0.5.1 里能被扫描到的包全都是原生包（必须能解析 caldera.json），
			// 所以 0.3.1 那句"该包不含全屏 Pass，画面不会变化"在这里没有对应物。
			ShaderPackEntry entry = new ShaderPackEntry(
					this, pack.id(), Component.literal(pack.displayName()), Component.translatable("caldera.screen.external_type"));
			entries.add(entry);
			if (Objects.equals(this.pendingPackId, pack.id())) {
				selectedEntry = entry;
			}
		}
		this.packList.replaceEntries(entries);
		this.packList.setSelected(selectedEntry);
	}

	private Component listSubtitle(boolean compact) {
		// 关闭时列表不可点，原来那句"点选一个光影包"就成了做不到的指示，必须换掉。
		if (!this.activeEnabled()) {
			return Component.translatable(compact ? "caldera.screen.subtitle_off_compact" : "caldera.screen.subtitle_off");
		}
		if (compact) {
			return Component.translatable("caldera.screen.subtitle_compact");
		}
		if (this.packs().isEmpty()) {
			return Component.translatable("caldera.screen.subtitle_empty");
		}
		return Component.translatable("caldera.screen.subtitle");
	}

	/** 光影开着、但渲染器报错——"已开启，画面却没有效果"。 */
	private boolean shaderUnavailable() {
		return this.activeEnabled() && ShaderRuntime.rendererFailure() != null;
	}

	/** 侧栏那个警告位该写什么。 */
	private Component sidePanelWarning() {
		return this.shaderUnavailable() ? UNAVAILABLE_WARNING : null;
	}

	/** 草稿指向的那个包是否仍然存在于磁盘上。 */
	private boolean draftStillExists() {
		return ShaderConfig.BUILTIN_PACK_ID.equals(this.pendingPackId)
				|| this.findExternalPack(this.pendingPackId) != null;
	}

	private ShaderPackScanner.AvailableShaderPack findExternalPack(String packId) {
		for (ShaderPackScanner.AvailableShaderPack pack : this.packs()) {
			if (pack.id().equals(packId)) {
				return pack;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- 交互

	/** 点击列表条目：只改变草稿，不触发任何异步操作，也不受我方重载状态影响。 */
	private void selectPending(String packId) {
		this.pendingPackId = packId;
		this.statusError = false;
		// 必须**先**让状态机吸收这次点击，再读它的结论：建在旧草稿上的判断会把文案说反——
		// 选中一个不同的包却提示"已经是当前生效的光影包"。
		this.refreshInteractivity();
		if (!this.state.hasPendingPackChange()) {
			this.status = Component.translatable("caldera.screen.status.already_active", this.packLabelText(packId));
		} else {
			this.status = Component.translatable("caldera.screen.status.pending", this.packLabelText(packId));
		}
	}

	/**
	 * 开启或关闭光影——**立即生效**，不再需要按「应用」确认。
	 * <p>
	 * 关闭时把未应用的草稿一起收敛回已生效的包：关闭后列表不可点，挂着一个永远提交不了的草稿
	 * 只会让用户以为界面坏了。
	 */
	private void toggleShaders() {
		boolean nextEnabled = !this.activeEnabled();
		String packId = this.activePackId();
		this.pendingPackId = packId;

		ShaderConfig nextConfig = ShaderRuntime.config().withSelection(nextEnabled, packId);
		if (nextEnabled) {
			this.runOperation(
					Component.translatable("caldera.screen.status.enabling", this.packLabelText(packId)),
					Component.translatable("caldera.screen.failure.enable"),
					false,
					() -> ShaderRuntime.applyConfig(nextConfig, false),
					() -> Component.translatable("caldera.screen.status.enabled", this.packLabelText(this.activePackId())));
		} else {
			this.runOperation(
					Component.translatable("caldera.screen.status.disabling"),
					Component.translatable("caldera.screen.failure.disable"),
					false,
					() -> ShaderRuntime.applyConfig(nextConfig, false),
					() -> Component.translatable("caldera.screen.status.disabled"));
		}
	}

	/** 提交列表里的草稿：把选中的包变成已生效。开关状态不受影响。 */
	private void applyPending() {
		if (!this.state.hasPendingPackChange()) {
			return;
		}
		String packId = this.pendingPackId;
		ShaderConfig nextConfig = ShaderRuntime.config().withSelection(this.activeEnabled(), packId);
		this.runOperation(
				Component.translatable("caldera.screen.status.applying", this.packLabelText(packId)),
				Component.translatable("caldera.screen.failure.apply"),
				false,
				() -> ShaderRuntime.applyConfig(nextConfig, false),
				() -> this.activeEnabled()
						? Component.translatable("caldera.screen.status.applied", this.packLabelText(this.activePackId()))
						: Component.translatable("caldera.screen.status.disabled"));
	}

	/**
	 * 强制重建并重载当前已经生效的光影包。
	 * <p>
	 * 注意 {@code preserveDraft = true}：重载是关于**当前已生效那个包**的维护动作，
	 * 不该动用户在列表里还没应用的挑选。
	 */
	private void reloadActive() {
		String packId = this.activePackId();
		ShaderConfig nextConfig = ShaderRuntime.config().withSelection(this.activeEnabled(), packId);
		this.runOperation(
				Component.translatable("caldera.screen.status.reloading", this.packLabelText(packId)),
				Component.translatable("caldera.screen.failure.reload"),
				true,
				() -> ShaderRuntime.applyConfig(nextConfig, true),
				() -> Component.translatable("caldera.screen.status.reloaded", this.packLabelText(this.activePackId())));
	}

	/**
	 * 打开设置界面。
	 * <p>
	 * 它编辑的是**已生效**那个包的选项，所以关闭状态下也能进——那时改的是"下次开启时会用到的"那份设置。
	 */
	private void openSettings() {
		if (this.minecraft == null) {
			return;
		}
		this.minecraft.setScreenAndShow(new NativePackSettingsScreen(this, this.activePackId()));
	}

	/**
	 * 重新读盘，并让运行时把设置与磁盘重新对齐（选中的包没了就回退到内置包，必要时触发一次重载）。
	 * <p>
	 * 它是一次真正的异步操作，所以接进了与开启/应用/重载同一个忙碌态。
	 */
	private void refreshFolder() {
		String previouslySelected = ShaderRuntime.config().selectedPackId();
		this.runOperation(
				Component.translatable("caldera.screen.status.refreshing"),
				Component.translatable("caldera.screen.failure.refresh"),
				// 刷新不碰用户的草稿——除非那个草稿指向的包已经被删掉了（由收尾逻辑统一处理）。
				true,
				ShaderRuntime::refresh,
				() -> {
					if (!ShaderConfig.BUILTIN_PACK_ID.equals(previouslySelected)
							&& this.findExternalPack(previouslySelected) == null) {
						return Component.translatable("caldera.screen.status.refreshed_fallback", this.packLabelText(previouslySelected));
					}
					if (this.packs().isEmpty()) {
						return Component.translatable("caldera.screen.status.refreshed_empty");
					}
					return this.unsupportedPacks().isEmpty()
							? Component.translatable("caldera.screen.status.refreshed", this.packs().size())
							: Component.translatable("caldera.screen.status.refreshed_unsupported", this.packs().size(), this.unsupportedPacks().size());
				});
	}

	/**
	 * 所有会读盘或触发资源重载的按钮共用的路径：发起工作、锁住那几个按钮、结束后统一收尾。
	 * <p>
	 * 收敛到一处的原因：所谓"我方操作中"必须是**一个**标志。此前几处各自发起、各自收尾，
	 * 谁也没法准确说自己是不是还在忙。
	 */
	private void runOperation(
			Component inProgressText,
			Component failureText,
			boolean preserveDraft,
			Supplier<CompletableFuture<Void>> work,
			Supplier<Component> successStatus) {
		if (this.minecraft == null) {
			return;
		}
		this.statusError = false;
		this.status = inProgressText;
		this.busy = true;

		CompletableFuture<Void> completion = work.get();
		// 配置与开关的改动是**同步**的，所以这里立刻刷新一次，
		// 界面马上反映新的开关状态与"操作中"。
		this.refreshInteractivity();

		completion.whenComplete((ignored, throwable) -> this.minecraft.execute(() -> {
			if (this.closed) {
				return;
			}
			this.busy = false;
			// 草稿什么时候收敛：操作本身解决了它（开启/关闭提交掉、或运行时因包消失回退需要跟随纠正），
			// 或者**它指向的包已经不在磁盘上了**——后者是「刷新」的常见情形，留着它会挂出一个
			// 永远应用不成的选择。而「重载」两不沾，草稿原样保留：那正是"点了重载，选择莫名其妙没了"的成因。
			this.refreshRuntimeState();
			if (!preserveDraft || !this.draftStillExists()) {
				this.syncPendingFromActive();
			}
			this.resyncUi();

			// future 的失败就是唯一的失败通道：0.5.1 的重载边沿本身就是这个 future，
			// 没有"超时"这第三种结果需要单独判断。
			if (throwable != null) {
				this.statusError = true;
				this.status = failureText;
				return;
			}

			this.statusError = false;
			this.status = successStatus.get();
		}));
	}

	private void openShaderFolder() {
		Blaze3D.openPath(ShaderPackScanner.shaderPackDirectory());
		this.statusError = false;
		this.status = Component.translatable("caldera.screen.status.folder_opened");
	}

	private void resetStatus() {
		this.statusError = false;
		if (!this.activeEnabled()) {
			this.status = Component.translatable("caldera.screen.status.idle_off", this.packLabelText(this.activePackId()));
			return;
		}
		if (this.packs().isEmpty()) {
			this.status = Component.translatable(
					"caldera.screen.status.idle_builtin", Component.translatable("caldera.screen.builtin_name"));
			return;
		}
		this.status = Component.translatable("caldera.screen.status.idle", this.packLabelText(this.activePackId()));
	}

	private Component toggleLabel() {
		return Component.translatable(this.activeEnabled() ? "caldera.screen.button.toggle_off" : "caldera.screen.button.toggle_on");
	}

	private void updateButtonLabels(boolean compact) {
		this.toggleButton.setMessage(compact
				? Component.translatable(this.activeEnabled() ? "caldera.screen.button.toggle_off_compact" : "caldera.screen.button.toggle_on_compact")
				: this.toggleLabel());
		this.reloadButton.setMessage(Component.translatable(compact ? "caldera.screen.button.reload_compact" : "caldera.screen.button.reload"));
		this.openFolderButton.setMessage(Component.translatable(compact ? "caldera.screen.button.open_folder_compact" : "caldera.screen.button.open_folder"));
	}

	private String packLabelText(String packId) {
		if (ShaderConfig.BUILTIN_PACK_ID.equals(packId)) {
			return Component.translatable("caldera.screen.builtin_name").getString();
		}
		return packId.endsWith(".zip") ? packId.substring(0, packId.length() - 4) : packId;
	}

	// ---------------------------------------------------------------- 列表条目问的事

	// 下面四个是**包私有**：ShaderPackEntry 与 ShadersScreen 同包，没必要把它们摆到公开面上。
	// 尤其是 onEntryClicked —— 它的参数类型是包私有的，声明成 public 等于对外承诺了一个
	// 包外代码根本无法命名、因而也永远调不到的方法。

	/**
	 * 列表条目画字要用的字体。
	 * <p>
	 * 这里必须开一个口子：{@code Screen} 只有一个 {@code protected} 的 {@code font} **字段**，
	 * 并没有公开的取值方法，而 {@code ShaderPackEntry} 不在 {@code Screen} 的继承链上，读不到那个字段。
	 */
	Font font() {
		return this.font;
	}

	/** 这个包是不是列表里草稿选中的那个。 */
	boolean isPending(String packId) {
		return Objects.equals(packId, this.pendingPackId);
	}

	/** 这个包是不是当前生效的那个（关闭时一律为 false）。 */
	boolean isLive(String packId) {
		return this.activeEnabled() && Objects.equals(packId, this.activePackId());
	}

	/** 此刻列表是否可选。关闭光影时为 false：条目变暗且不响应点击。 */
	boolean selectable() {
		return this.state.listSelectable();
	}

	void onEntryClicked(ShaderPackEntry entry) {
		// 条目那边已经挡过一次；这里再挡一次是因为"点选"本身必须是关闭状态下不可能发生的事，
		// 而不只是"画面上看着不能点"。
		if (!this.state.listSelectable()) {
			return;
		}
		LOGGER.info(
				"Caldera 光影界面：点击了列表条目「{}」（当前生效={}，当前选中={}）",
				entry.packId(),
				this.activePackId(),
				this.pendingPackId);
		// 选择永远允许，不受我方重载状态影响：它是纯本地状态。
		this.packList.setSelected(entry);
		this.selectPending(entry.packId());
	}
}
