package dev.shaderflash;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * 只做一件事的选包页：给窗口太窄、放不下左侧列表的设置界面用。
 *
 * <p>选中的包会立刻写进配置并开始后台预编译（不阻塞游戏）。
 */
public final class PackSelectScreen extends Screen {
	private final Screen parent;
	private final Consumer<String> onPick;
	private PackListWidget list;

	public PackSelectScreen(Screen parent, Consumer<String> onPick) {
		super(ModTexts.text(ModTexts.SCREEN_PICK_TITLE));
		this.parent = parent;
		this.onPick = onPick;
	}

	@Override
	protected void init() {
		int listWidth = Math.max(80, Math.min(300, this.width - 40));
		int listHeight = Math.max(40, this.height - 90);
		this.list = new PackListWidget(this.client, listWidth, listHeight, 32, 20, name -> {
			onPick.accept(name);
			close();
		});
		this.list.refresh(DuoConfig.get().secondaryPack);
		addDrawableChild(this.list);
		this.list.setX((this.width - listWidth) / 2);
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
			.dimensions(this.width / 2 - 60, this.height - 28, 120, 20).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
			ModTexts.text(ModTexts.SCREEN_PACK_HINT), this.width / 2, 22, 0xFFA0A0A0);
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}
}
