package dev.shaderflash;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ContainerWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 可滚动的选项面板：一组「行」垂直排布，内容超出高度时可以滚动，永远不会把控件挤没。
 *
 * <h2>为什么不用原版的列表控件</h2>
 * 原版 {@code EntryListWidget} 的行高是固定的一个 {@code itemHeight}，而我们既有 20px 的按钮行，
 * 也有会自动折行的说明文字行（窗口越窄行越高）。所以这里自己算行高，
 * 只把「滚动、裁剪、鼠标转发、行内控件定位」这几件事做掉。
 *
 * <h2>怎么加一个选项</h2>
 * 设置界面里一行代码：
 * <pre>{@code
 * panel.add(OptionsPanel.split(buttonA, buttonB));
 * panel.add(OptionsPanel.note(() -> Text.translatable("...", value)));
 * }</pre>
 * 行内控件由面板统一定位与转发事件，调用方不用管坐标，也不用管窗口大小。
 */
public final class OptionsPanel extends ContainerWidget {
	/** 面板内边距。 */
	private static final int PAD = 6;
	/** 两行之间的间距。 */
	private static final int GAP = 4;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int ROW_HEIGHT = 20;
	private static final int LINE_HEIGHT = 10;
	/** 一行里并排放两个控件所需的最小宽度，比这窄就自动改成上下两行。 */
	public static final int SPLIT_MIN_WIDTH = 250;

	private final List<Item> items = new ArrayList<>();
	private final List<ClickableWidget> widgets = new ArrayList<>();
	private double scrollAmount;
	private int contentHeight;
	private boolean draggingScrollbar;

	public OptionsPanel(int x, int y, int width, int height) {
		super(x, y, width, height, Text.empty());
	}

	@Override
	public List<? extends Element> children() {
		return this.widgets;
	}

	/** 追加一行。 */
	public OptionsPanel add(Row row) {
		this.items.add(new Item(row));
		this.widgets.addAll(row.widgets());
		return this;
	}

	// ------------------------------------------------------------------
	// 行类型
	// ------------------------------------------------------------------

	/** 一行。行高按给定宽度计算，因此窗口变窄时可以自己变成“两行”。 */
	public abstract static class Row {
		abstract int height(TextRenderer font, int width);

		void layout(int x, int y, int width) {
		}

		abstract void render(DrawContext context, TextRenderer font, int mouseX, int mouseY, float delta);

		List<ClickableWidget> widgets() {
			return List.of();
		}
	}

	/** 分节标题 + 一条分隔线。 */
	public static Row header(Supplier<Text> title) {
		return new HeaderRow(title);
	}

	/** 说明文字，按宽度自动折行。 */
	public static Row note(Supplier<Text> text) {
		return new NoteRow(text);
	}

	private static final class NoteRow extends Row {
		private final Supplier<Text> text;
		private List<OrderedText> lines = List.of();
		private int x;
		private int y;

		NoteRow(Supplier<Text> text) {
			this.text = text;
		}

		@Override
		int height(TextRenderer font, int width) {
			// 内容会变（状态行就是），所以行高每帧按当前宽度重算。
			this.lines = font.wrapLines(this.text.get(), Math.max(20, width));
			return this.lines.size() * LINE_HEIGHT + 2;
		}

		@Override
		void layout(int x, int y, int width) {
			this.x = x;
			this.y = y;
		}

		@Override
		void render(DrawContext context, TextRenderer font, int mouseX, int mouseY, float delta) {
			int lineY = this.y + 1;
			for (OrderedText line : this.lines) {
				context.drawTextWithShadow(font, line, this.x, lineY, 0xFFA0A0A0);
				lineY += LINE_HEIGHT;
			}
		}
	}

	private static final class HeaderRow extends Row {
		private final Supplier<Text> title;
		private int x;
		private int y;
		private int width;

		HeaderRow(Supplier<Text> title) {
			this.title = title;
		}

		@Override
		int height(TextRenderer font, int width) {
			return ROW_HEIGHT;
		}

		@Override
		void layout(int x, int y, int width) {
			this.x = x;
			this.y = y;
			this.width = width;
		}

		@Override
		void render(DrawContext context, TextRenderer font, int mouseX, int mouseY, float delta) {
			context.drawTextWithShadow(font, this.title.get(), this.x, this.y + 4, 0xFFFFD24A);
			int lineY = this.y + ROW_HEIGHT - 4;
			context.fill(this.x, lineY, this.x + this.width, lineY + 1, 0x60FFD24A);
		}
	}

	/** 一整行的单个控件。 */
	public static Row button(ClickableWidget widget) {
		return new WidgetRow(List.of(widget));
	}

	/** 一行并排两个控件；宽度不够时自动变成上下两行。 */
	public static Row split(ClickableWidget left, ClickableWidget right) {
		return new SplitRow(left, right);
	}

	private static class WidgetRow extends Row {
		private final List<ClickableWidget> children;

		WidgetRow(List<ClickableWidget> children) {
			this.children = children;
		}

		@Override
		List<ClickableWidget> widgets() {
			return this.children;
		}

		@Override
		int height(TextRenderer font, int width) {
			int height = ROW_HEIGHT;
			for (ClickableWidget child : this.children) {
				height = Math.max(height, child.getHeight());
			}
			return height;
		}

		@Override
		void layout(int x, int y, int width) {
			this.place(x, y, width);
		}

		void place(int x, int y, int width) {
			ClickableWidget child = this.children.get(0);
			child.setX(x);
			child.setY(y);
			child.setWidth(width);
		}

		@Override
		void render(DrawContext context, TextRenderer font, int mouseX, int mouseY, float delta) {
			for (ClickableWidget child : this.children) {
				child.render(context, mouseX, mouseY, delta);
			}
		}
	}

	private static final class SplitRow extends WidgetRow {
		private final ClickableWidget left;
		private final ClickableWidget right;

		SplitRow(ClickableWidget left, ClickableWidget right) {
			super(List.of(left, right));
			this.left = left;
			this.right = right;
		}

		@Override
		int height(TextRenderer font, int width) {
			if (width < SPLIT_MIN_WIDTH) {
				return ROW_HEIGHT * 2 + GAP;
			}
			return ROW_HEIGHT;
		}

		@Override
		void place(int x, int y, int width) {
			if (width < SPLIT_MIN_WIDTH) {
				this.left.setX(x);
				this.left.setY(y);
				this.left.setWidth(width);
				this.right.setX(x);
				this.right.setY(y + ROW_HEIGHT + GAP);
				this.right.setWidth(width);
			} else {
				int half = (width - GAP) / 2;
				this.left.setX(x);
				this.left.setY(y);
				this.left.setWidth(half);
				this.right.setX(x + half + GAP);
				this.right.setY(y);
				this.right.setWidth(width - half - GAP);
			}
		}
	}

	// ------------------------------------------------------------------
	// 布局与渲染
	// ------------------------------------------------------------------

	private static final class Item {
		final Row row;
		int top;
		int height;

		Item(Row row) {
			this.row = row;
		}
	}

	private int viewHeight() {
		return Math.max(1, this.getHeight() - PAD * 2);
	}

	/** 重新计算每行的位置与高度。窗口大小变化、行内容变化都会自动跟上。 */
	private void layoutItems(TextRenderer font) {
		int width = this.getWidth() - PAD * 2 - (this.contentHeight > this.viewHeight() ? SCROLLBAR_WIDTH : 0);
		width = Math.max(40, width);
		int y = this.getY() + PAD - (int) this.scrollAmount;
		int top = y;
		for (Item item : this.items) {
			item.height = item.row.height(font, width);
			item.top = y;
			if (item.top + item.height < this.getY() || item.top > this.getY() + this.getHeight()) {
				// 滚出可视区：位置挪到屏幕外，免得它还能接住鼠标点击。
				item.row.layout(this.getX() + PAD, -10000, width);
			} else {
				item.row.layout(this.getX() + PAD, y, width);
			}
			y += item.height + GAP;
		}
		this.contentHeight = y - GAP - top;
		this.scrollAmount = MathHelper.clamp(this.scrollAmount, 0.0D, this.maxScroll());
	}

	private double maxScroll() {
		return Math.max(0.0D, this.contentHeight - this.viewHeight());
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		TextRenderer font = MinecraftClient.getInstance().textRenderer;
		this.layoutItems(font);
		int left = this.getX();
		int top = this.getY();
		int right = this.getX() + this.getWidth();
		int bottom = this.getY() + this.getHeight();

		context.fill(left, top, right, bottom, 0x50000000);
		context.enableScissor(left, top, right, bottom);
		for (Item item : this.items) {
			if (item.top + item.height >= top && item.top <= bottom) {
				item.row.render(context, font, mouseX, mouseY, delta);
			}
		}
		context.disableScissor();

		double max = this.maxScroll();
		if (max > 0.0D) {
			int trackX = right - SCROLLBAR_WIDTH - 1;
			int thumbHeight = MathHelper.clamp((int) ((double) this.getHeight() * this.getHeight() / this.contentHeight),
				16, this.getHeight() - 2);
			int thumbY = top + (int) ((this.getHeight() - thumbHeight) * (this.scrollAmount / max));
			context.fill(trackX, top, trackX + SCROLLBAR_WIDTH, bottom, 0x40000000);
			context.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0xFFA0A0A0);
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		// 面板本身不朗读；行内控件各自有朗读文本
	}

	// ------------------------------------------------------------------
	// 输入
	// ------------------------------------------------------------------

	private boolean overScrollbar(double mouseX) {
		return this.maxScroll() > 0.0D && mouseX >= this.getX() + this.getWidth() - SCROLLBAR_WIDTH - 1;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!this.active || !this.visible || !this.isMouseOver(mouseX, mouseY)) {
			return false;
		}
		if (button == 0 && this.overScrollbar(mouseX)) {
			this.draggingScrollbar = true;
			this.scrollToMouse(mouseY);
			return true;
		}
		// 行内控件的位置每帧都会重算；这里先算一次，保证第一帧点击也命中。
		this.layoutItems(MinecraftClient.getInstance().textRenderer);
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (this.draggingScrollbar && button == 0) {
			this.draggingScrollbar = false;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (this.draggingScrollbar && button == 0) {
			this.scrollToMouse(mouseY);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	/** 拖动滚动条：光标位置映射成滚动比例。 */
	private void scrollToMouse(double mouseY) {
		double fraction = (mouseY - this.getY()) / Math.max(1, this.getHeight());
		this.scrollAmount = MathHelper.clamp(fraction, 0.0D, 1.0D) * this.maxScroll();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (!this.isMouseOver(mouseX, mouseY) || this.maxScroll() <= 0.0D) {
			return false;
		}
		this.scrollAmount = MathHelper.clamp(this.scrollAmount - vertical * (LINE_HEIGHT + 8), 0.0D, this.maxScroll());
		return true;
	}
}
