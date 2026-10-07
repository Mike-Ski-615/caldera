package com.caldera.shaders.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import org.jspecify.annotations.NonNull;

/**
 * 光影包列表控件本身：行高、行宽、滚动条位置，以及"不要原版的列表背景与分隔线"
 * （我们要自己画行边框，原版那层背景会盖住面板）。
 */
final class ShaderPackList extends ObjectSelectionList<ShaderPackEntry> {
	private static final int ROW_HEIGHT = 42;
	private static final int ROW_WIDTH_INSET = 12;
	private static final int SCROLLBAR_INSET = 6;

	/**
	 * 列表此刻是否可选。关闭光影时置为 {@code false}。
	 * <p>
	 * 说明分工：真正决定"点了有没有反应"的是 {@link ShaderPackEntry#mouseClicked}，它会问界面的
	 * {@code selectable()}；这里声明的是"列表本身不接受选择"，主要是给方向键与初始焦点用的。
	 * 界面从不读回 {@code getSelected()}——选中态的唯一真值是 {@code pendingPackId}，
	 * 所以控件内部的选择状态是只写不读的，它变了也不会影响任何东西。
	 */
	private boolean selectable = true;

	ShaderPackList(Minecraft minecraft) {
		super(minecraft, 0, 0, 0, ROW_HEIGHT);
	}

	void setSelectable(boolean selectable) {
		this.selectable = selectable;
	}

	@Override
	public int getRowWidth() {
		return this.width - ROW_WIDTH_INSET;
	}

	@Override
	protected int scrollBarX() {
		return this.getX() + this.width - SCROLLBAR_INSET;
	}

	@Override
	protected boolean entriesCanBeSelected() {
		return this.selectable;
	}

	@Override
	protected void extractListBackground(@NonNull GuiGraphicsExtractor guiGraphicsExtractor) {
	}

	@Override
	protected void extractListSeparators(@NonNull GuiGraphicsExtractor guiGraphicsExtractor) {
	}
}
