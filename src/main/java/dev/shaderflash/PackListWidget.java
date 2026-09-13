package dev.shaderflash;

import net.irisshaders.iris.Iris;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.function.Consumer;

/**
 * 光影包列表：点一下把它选成副包。
 *
 * <p>被设置界面与“窄窗口下的选包页”共用，所以做成独立控件；选中后做什么由调用方决定
 * （{@code onPick}）。
 */
public final class PackListWidget extends AlwaysSelectedEntryListWidget<PackListWidget.PackEntry> {
	private final Consumer<String> onPick;
	private String selectedName = "";
	private boolean buildingList;

	public PackListWidget(MinecraftClient client, int width, int height, int top, int itemHeight,
						  Consumer<String> onPick) {
		super(client, width, height, top, itemHeight);
		this.onPick = onPick;
	}

	/** 重新扫描 shaderpacks 目录并按 {@code selected} 选中当前副包。 */
	public void refresh(String selected) {
		buildingList = true;
		clearEntries();
		selectedName = selected == null ? "" : selected;
		List<String> packs = PackScanner.listPacks();
		String currentPrimary = Iris.getCurrentPackName();
		for (String name : packs) {
			PackEntry entry = new PackEntry(name, name.equals(currentPrimary),
				VanillaPack.isVanilla(name) && VanillaPack.isPristine());
			addEntry(entry);
			if (name.equals(selectedName)) {
				setSelected(entry);
			}
		}
		buildingList = false;
	}

	@Override
	public void setSelected(PackEntry entry) {
		super.setSelected(entry);
		if (entry == null || buildingList || entry.name.equals(selectedName)) {
			return;
		}
		selectedName = entry.name;
		onPick.accept(entry.name);
	}

	@Override
	public int getRowWidth() {
		return this.width - 12;
	}

	/** 列表里的一行：包名，★ 表示它现在是主管线。 */
	public final class PackEntry extends AlwaysSelectedEntryListWidget.Entry<PackEntry> {
		private final String name;
		private final boolean isPrimary;
		/** 这一行是不是“原版”且仍然不含任何程序（只有这种状态才是原生等效）。 */
		private final boolean vanillaPristine;

		private PackEntry(String name, boolean isPrimary, boolean vanillaPristine) {
			this.name = name;
			this.isPrimary = isPrimary;
			this.vanillaPristine = vanillaPristine;
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
						   int mouseX, int mouseY, boolean hovered, float tickDelta) {
			if (VanillaPack.isVanilla(name)) {
				// 「原版」不是普通光影包：标出来，免得玩家以为选错了。
				String mark = (isPrimary ? "★ " : "◆ ") + name;
				context.drawTextWithShadow(client.textRenderer,
					Text.literal(mark).formatted(Formatting.AQUA),
					x + 4, y + 6, 0xFFFFFF);
				if (vanillaPristine) {
					// 玩家往这个包里塞过自己的程序时不再这么标，免得误导。
					context.drawTextWithShadow(client.textRenderer,
						ModTexts.styled(ModTexts.PACK_VANILLA_SUFFIX, Formatting.DARK_GRAY),
						x + 4 + client.textRenderer.getWidth(mark) + 4, y + 6, 0x808080);
				}
				return;
			}
			String label = (isPrimary ? "★ " : "  ") + name;
			context.drawTextWithShadow(client.textRenderer, label, x + 4, y + 6, 0xFFFFFF);
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			PackListWidget.this.setSelected(this);
			return true;
		}

		@Override
		public Text getNarration() {
			return Text.literal(name);
		}
	}
}
