package dev.shaderflash;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * 设置界面。
 *
 * <h2>布局</h2>
 * <ul>
 *   <li>窗口够宽：左边是光影包列表，右边是<b>可滚动</b>的选项面板（见 {@link OptionsPanel}）；</li>
 *   <li>窗口很窄：列表放不下，改成一个“选包”按钮打开 {@link PackSelectScreen}，
 *       右边的选项面板仍然可以滚动 —— 任何窗口尺寸下都不会有控件被挤没。</li>
 * </ul>
 *
 * <h2>怎么加一个选项</h2>
 * 在 {@link #buildRows} 里加一行即可，行内控件由面板负责定位与事件转发：
 * <pre>{@code panel.add(OptionsPanel.split(toggle(...), toggle(...)));}</pre>
 */
public final class DuoConfigScreen extends Screen {
	/** 低于这个宽度就换成“选包按钮 + 单栏滚动面板”。 */
	private static final int TWO_PANE_MIN_WIDTH = 460;
	/** 截图取景前的预热帧数档位（MakeUp 这类包要 10~20 帧才把亮度抬起来）。 */
	private static final int[] WARMUP_FRAMES = {0, 4, 8, 10, 12, 16, 20, 30, 40, 60};

	private final Screen parent;
	private final List<Runnable> refreshers = new ArrayList<>();
	private OptionsPanel panel;
	private PackListWidget packList;

	public DuoConfigScreen(Screen parent) {
		super(ModTexts.text(ModTexts.SCREEN_TITLE));
		this.parent = parent;
	}

	@Override
	protected void init() {
		refreshers.clear();
		DuoConfig config = DuoConfig.get();
		int top = 30;
		int bottom = this.height - 30;
		int panelX;
		int panelWidth;

		if (this.width >= TWO_PANE_MIN_WIDTH) {
			int listWidth = MathHelper.clamp(this.width / 4, 120, 220);
			packList = new PackListWidget(this.client, listWidth, bottom - top, top, 20, this::onPackPicked);
			packList.refresh(config.secondaryPack);
			addDrawableChild(packList);
			panelX = listWidth + 12;
			panelWidth = this.width - panelX - 8;
		} else {
			packList = null;
			panelX = 8;
			panelWidth = this.width - 16;
		}

		panel = new OptionsPanel(panelX, top, panelWidth, Math.max(40, bottom - top));
		addDrawableChild(panel);
		buildRows(config);

		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
			.dimensions(this.width / 2 - 60, this.height - 26, 120, 20).build());
	}

	/** 一行排不下两个控件时，面板会自动改成上下两行。 */
	private void buildRows(DuoConfig config) {
		// ---- 状态 ----
		panel.add(OptionsPanel.header(() -> ModTexts.text(ModTexts.SECTION_STATUS)));
		panel.add(OptionsPanel.note(() -> Text.literal(DuoManager.statusSummary())));

		// ---- 副管线 ----
		panel.add(OptionsPanel.header(() -> ModTexts.text(ModTexts.SECTION_PIPELINE)));
		if (packList == null) {
			ButtonWidget pick = ButtonWidget.builder(packLabel(), button ->
					this.client.setScreen(new PackSelectScreen(this, this::onPackPicked)))
				.dimensions(0, 0, 150, 20).build();
			refreshers.add(() -> pick.setMessage(packLabel()));
			panel.add(OptionsPanel.button(pick));
		}

		panel.add(OptionsPanel.button(toggle(ModTexts.OPTION_ENABLED, () -> config.enabled, value -> {
			config.enabled = value;
			DuoManager.onEnabledChanged();
		})));

		panel.add(OptionsPanel.button(
			toggle(ModTexts.OPTION_KEEP_OLD, () -> config.keepOldPrimaryAsSecondary,
				value -> config.keepOldPrimaryAsSecondary = value)));

		// 「截图副管线性能调度」：五档，**只决定区块重建每 tick 投递多少段**。
		// 解析与编译必须在渲染线程且是原子的，没有调度余地，所以不在这项设置的作用域内；
		// F7 主副互换、截图后的材质还原也不受影响（它们都是一次性投完）。
		panel.add(OptionsPanel.button(ButtonWidget.builder(policyLabel(config), button -> {
			config.remeshPolicy = (config.remeshPolicy + 1) % DuoConfig.POLICY_COUNT;
			config.save();
			button.setMessage(policyLabel(config));
		}).dimensions(0, 0, 210, 20).build()));
		panel.add(OptionsPanel.note(() -> ModTexts.text(ModTexts.NOTE_REMESH_POLICY)));

		panel.add(OptionsPanel.button(
			toggle(ModTexts.OPTION_HUD, () -> config.hudStatus, value -> config.hudStatus = value)));

		panel.add(OptionsPanel.button(toggle(ModTexts.OPTION_VANILLA_PACK, () -> config.vanillaPackEntry,
			value -> {
				config.vanillaPackEntry = value;
				if (value) {
					VanillaPack.ensure();
				}
			})));
		panel.add(OptionsPanel.note(() -> ModTexts.text(ModTexts.NOTE_VANILLA)));

		ButtonWidget prewarmButton = ButtonWidget.builder(Text.empty(), button -> {
			if (DuoManager.isPrewarming()) {
				DuoManager.cancelPrewarm();
			} else {
				DuoManager.ensureSecondary(true);
			}
		}).dimensions(0, 0, 150, 20).build();
		refreshers.add(() -> prewarmButton.setMessage(ModTexts.text(DuoManager.isPrewarming()
			? ModTexts.BUTTON_CANCEL_PREWARM : ModTexts.BUTTON_PREWARM)));
		panel.add(OptionsPanel.split(
			prewarmButton,
			ButtonWidget.builder(ModTexts.text(ModTexts.BUTTON_SWAP), button -> DuoManager.swapPrimary())
				.dimensions(0, 0, 150, 20).build()));

		// 调试开关：新管线建好后是否补渲染一帧（关掉 = 零帧就绪，但第一次切换可能黑一帧）。
		panel.add(OptionsPanel.button(toggle(ModTexts.OPTION_INITIAL_WARMUP,
			() -> config.initialWarmupRender, value -> config.initialWarmupRender = value)));
		panel.add(OptionsPanel.note(() -> ModTexts.text(ModTexts.NOTE_INITIAL_WARMUP)));

		// ---- 切换 ----
		panel.add(OptionsPanel.header(() -> ModTexts.text(ModTexts.SECTION_SWITCH)));
		panel.add(OptionsPanel.split(
			toggle(ModTexts.OPTION_REBUILD_CHUNKS, () -> config.rebuildChunksOnSwitch,
				value -> config.rebuildChunksOnSwitch = value),
			toggle(ModTexts.OPTION_SEAMLESS_REMESH, () -> config.seamlessRemesh,
				value -> config.seamlessRemesh = value)));

		// ---- 截图 ----
		panel.add(OptionsPanel.header(() -> ModTexts.text(ModTexts.SECTION_SCREENSHOT)));
		panel.add(OptionsPanel.button(toggle(ModTexts.OPTION_SECONDARY_SCREENSHOT,
			() -> config.secondaryScreenshot, value -> config.secondaryScreenshot = value)));
		panel.add(OptionsPanel.button(toggle(ModTexts.OPTION_SCREENSHOT_JPG,
			() -> config.screenshotJpg, value -> config.screenshotJpg = value)));
		panel.add(OptionsPanel.note(() -> ModTexts.text(ModTexts.NOTE_SCREENSHOT_JPG)));
		panel.add(OptionsPanel.button(new StepButton(config.screenshotWarmupFrames,
			value -> ModTexts.text(ModTexts.OPTION_SCREENSHOT_WARMUP, value),
			value -> {
				config.screenshotWarmupFrames = value;
				config.save();
				return value;
			}, WARMUP_FRAMES)));
		// 无渲染帧阈值：唯一的“收尾”分支，做成可调项方便调试（0.2.1 起）。
		panel.add(OptionsPanel.button(new StepButton(config.noFrameTimeoutTicks(),
			value -> ModTexts.text(ModTexts.OPTION_NO_FRAME_TIMEOUT,
				DuoConfig.noFrameTimeoutName(value)),
			value -> {
				config.noFrameTimeoutTicks = value;
				config.save();
				return value;
			}, DuoConfig.NO_FRAME_TIMEOUT_STEPS)));
		panel.add(OptionsPanel.note(() -> ModTexts.text(ModTexts.NOTE_NO_FRAME_TIMEOUT)));

		// 高清截图：两个文本框分别填宽与高，两个按钮按窗口宽高比互相补全。
		// 两个框都留空 = 跟随窗口分辨率（默认，渲染进主帧缓冲）。
		TextFieldWidget widthField = new TextFieldWidget(this.textRenderer, 0, 0, 150, 20,
			ModTexts.text(ModTexts.OPTION_SCREENSHOT_WIDTH));
		TextFieldWidget heightField = new TextFieldWidget(this.textRenderer, 0, 0, 150, 20,
			ModTexts.text(ModTexts.OPTION_SCREENSHOT_HEIGHT));
		configureSizeField(widthField, config.screenshotWidth(), value -> {
			config.screenshotWidth = value;
			config.save();
		});
		configureSizeField(heightField, config.screenshotHeight(), value -> {
			config.screenshotHeight = value;
			config.save();
		});
		panel.add(OptionsPanel.split(widthField, heightField));
		panel.add(OptionsPanel.split(
			ButtonWidget.builder(ModTexts.text(ModTexts.BUTTON_FILL_HEIGHT), button -> {
				int width = DuoConfig.clampScreenshotSize(parseSize(widthField.getText()));
				if (width <= 0) {
					return;
				}
				int[] window = DuoManager.windowFramebufferSize();
				int height = CaptureResolution.fillHeight(window[0], window[1], width);
				heightField.setText(String.valueOf(height));
				config.screenshotHeight = height;
				config.save();
			}).dimensions(0, 0, 150, 20).build(),
			ButtonWidget.builder(ModTexts.text(ModTexts.BUTTON_FILL_WIDTH), button -> {
				int height = DuoConfig.clampScreenshotSize(parseSize(heightField.getText()));
				if (height <= 0) {
					return;
				}
				int[] window = DuoManager.windowFramebufferSize();
				int width = CaptureResolution.fillWidth(window[0], window[1], height);
				widthField.setText(String.valueOf(width));
				config.screenshotWidth = width;
				config.save();
			}).dimensions(0, 0, 150, 20).build()));
		panel.add(OptionsPanel.note(() -> {
			String base = ModTexts.text(ModTexts.NOTE_SCREENSHOT_RESOLUTION,
				DuoManager.screenshotResolutionText()).getString();
			int[] size = DuoManager.screenshotTargetSize();
			if (!CaptureResolution.beyondWarningBudget(size[0], size[1])) {
				return Text.literal(base);
			}
			// 只警告，不限制：超量渲染是玩家的选择，风险也由玩家承担。
			return Text.literal(base + "\n" + ModTexts.text(ModTexts.NOTE_SCREENSHOT_RESOLUTION_WARN,
				size[0], size[1], CaptureResolution.megapixels(size[0], size[1])).getString());
		}));
		panel.add(OptionsPanel.note(() -> ModTexts.text(ModTexts.NOTE_SCREENSHOT)));

		// ---- 界面与提示 ----
		panel.add(OptionsPanel.header(() -> ModTexts.text(ModTexts.SECTION_INTERFACE)));
		panel.add(OptionsPanel.split(
			toggle(ModTexts.OPTION_CHAT, () -> config.chatFeedback, value -> config.chatFeedback = value),
			ButtonWidget.builder(ModTexts.text(ModTexts.BUTTON_DIAGNOSE), button -> Diagnostics.runAndReport())
				.dimensions(0, 0, 150, 20).build()));

		// ---- 诊断 ----
		panel.add(OptionsPanel.header(() -> ModTexts.text(ModTexts.SECTION_DIAGNOSTICS)));
		panel.add(OptionsPanel.button(
			ButtonWidget.builder(ModTexts.text(ModTexts.BUTTON_RESET_GHOST), button -> DuoManager.resetGhostFailure())
				.dimensions(0, 0, 150, 20).build()));
		panel.add(OptionsPanel.note(() -> ModTexts.text(ModTexts.NOTE_KEYS)));
	}

	private Text packLabel() {
		String name = DuoConfig.get().secondaryPack;
		return ModTexts.join(ModTexts.BUTTON_PICK_PACK,
			name == null || name.isBlank() ? ModTexts.text(ModTexts.VALUE_NONE) : Text.literal(name));
	}

	/** 预编译调度策略按钮的标签（五档）。 */
	private static Text policyLabel(DuoConfig config) {
		return Text.literal(ModTexts.str(ModTexts.OPTION_REMESH_POLICY,
			ModTexts.raw(ModTexts.policy(config.remeshPolicy))));
	}

	private void onPackPicked(String name) {
		DuoConfig config = DuoConfig.get();
		if (name.equals(config.secondaryPack)) {
			return;
		}
		config.secondaryPack = name;
		config.save();
		// 选了就顺手在后台编译，省得玩家再点一次；失败也不弹窗，状态行里会写原因。
		DuoManager.ensureSecondary(false);
	}

	/** 生成一个开关按钮：点一下翻转并写进配置，标签自动跟着变。 */
	private ButtonWidget toggle(ModTexts.LText label, java.util.function.BooleanSupplier reader,
								java.util.function.Consumer<Boolean> writer) {
		ToggleButton button = new ToggleButton(label, reader, writer);
		// 每帧对齐一次标签：用快捷键在界面外改了开关时，界面上的文字也不会滞后。
		refreshers.add(button::refresh);
		return button;
	}

	/** 开关按钮的实现：每次点击读取当前值、取反、写配置、刷新标签。 */
	private static final class ToggleButton extends ButtonWidget {
		private final ModTexts.LText label;
		private final java.util.function.BooleanSupplier reader;
		private final java.util.function.Consumer<Boolean> writer;

		ToggleButton(ModTexts.LText label, java.util.function.BooleanSupplier reader,
					 java.util.function.Consumer<Boolean> writer) {
			super(0, 0, 150, 20, toggleLabel(label, reader.getAsBoolean()), button -> { }, supplier -> supplier.get());
			this.label = label;
			this.reader = reader;
			this.writer = writer;
		}

		@Override
		public void onPress() {
			boolean value = !this.reader.getAsBoolean();
			this.writer.accept(value);
			DuoConfig.get().save();
			this.setMessage(toggleLabel(this.label, value));
		}

		void refresh() {
			this.setMessage(toggleLabel(this.label, this.reader.getAsBoolean()));
		}
	}

	/** 开关标签：「双管线：开」，其中开/关两个字保留自己的颜色。 */
	private static Text toggleLabel(ModTexts.LText label, boolean value) {
		return ModTexts.join(label, ModTexts.onOff(value));
	}

	/** 「副渲染截图分辨率」的文本框：只收数字，留空 = 0（跟随窗口），改动立刻写进配置。 */
	private static void configureSizeField(TextFieldWidget field, int initial,
										   java.util.function.IntConsumer onChange) {
		field.setMaxLength(5);
		field.setTextPredicate(text -> text.isEmpty() || text.matches("\\d{1,5}"));
		field.setPlaceholder(ModTexts.text(ModTexts.VALUE_FOLLOW_WINDOW_SHORT));
		field.setText(initial > 0 ? String.valueOf(initial) : "");
		field.setChangedListener(text -> onChange.accept(parseSize(text)));
	}

	/** 文本框里的数字；空/非法都当 0（= 跟随窗口）。 */
	private static int parseSize(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException error) {
			return 0;
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		for (Runnable refresher : refreshers) {
			refresher.run();
		}
		renderBackground(context, mouseX, mouseY, delta);
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);
	}

	@Override
	public void close() {
		DuoConfig.get().save();
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}

	/**
	 * 可左键/右键输入的数值按钮：左键 +1、右键 -1（在给定的候选值序列里走，到头就停）。
	 */
	private static final class StepButton extends ButtonWidget {
		private final int[] values;
		private final IntFunction<Text> label;
		private final IntUnaryOperator onChange;
		private int value;

		StepButton(int value, IntFunction<Text> label, IntUnaryOperator onChange, int[] values) {
			super(0, 0, 150, 20, label.apply(value), button -> { }, supplier -> supplier.get());
			this.value = value;
			this.label = label;
			this.onChange = onChange;
			this.values = values;
		}

		@Override
		public void onPress() {
			shift(1);
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button == 1 && this.active && this.visible && this.clicked(mouseX, mouseY)) {
				this.playDownSound(MinecraftClient.getInstance().getSoundManager());
				shift(-1);
				return true;
			}
			return super.mouseClicked(mouseX, mouseY, button);
		}

		private void shift(int direction) {
			int index = 0;
			for (int i = 0; i < this.values.length; i++) {
				if (this.values[i] == this.value) {
					index = i;
					break;
				}
			}
			int next = MathHelper.clamp(index + direction, 0, this.values.length - 1);
			this.value = this.onChange.applyAsInt(this.values[next]);
			this.setMessage(this.label.apply(this.value));
		}
	}
}
