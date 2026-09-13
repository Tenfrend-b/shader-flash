package dev.shaderflash;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组配置。字段直接对应 {@code config/shaderflash.json}，方便手工编辑。
 */
public final class DuoConfig {
	/**
	 * 「截图副管线」的性能调度策略：**只影响区块重建的投递节奏**（每 tick 投多少区块段）。
	 *
	 * <p>为什么不影响解析/编译：那两步必须在渲染线程上执行，而且是**原子**的
	 * （Iris 解析期会做 GL 查询，编译直接在管线构造函数里调 GL），
	 * 没有任何“调度”余地——把它写进策略只会名不副实（0.1.17 试过，0.2 回退）。
	 *
	 * <p>而区块重建是**可以**分片投递的：一次性把十几万个区块段全排进 Sodium 队列，
	 * 那一帧的 CPU 尖峰 + 随后跑满构建线程，前台能明显感到掉帧；按预算分批投递就平稳得多
	 * （旧网格仍然渲染到新网格建好，无可见卸载）。
	 */
	public static final int POLICY_SEAMLESS = 0;   // 最平滑：每 tick 300 段
	public static final int POLICY_LOW = 1;        // 平滑：每 tick 700 段
	public static final int POLICY_MEDIUM = 2;     // 均衡：每 tick 1500 段（默认）
	public static final int POLICY_HIGH = 3;       // 快速：每 tick 4000 段
	public static final int POLICY_REALTIME = 4;   // 最快：一次性全部投完
	public static final int POLICY_COUNT = 5;

	private static final String[] POLICY_KEYS = {"smoothest", "smooth", "balanced", "fast", "fastest"};

	/**
	 * 「连续多少 tick 一帧都没渲染出来就取消本次截图」的可选区间（0.2.1 起可调，便于调试）。
	 *
	 * <p>这不是“超时回落”（那个在 0.2 已删除）：只有当窗口最小化 / 渲染卡死、根本不可能取到景时，
	 * 才用它收尾，否则按 F2 会永远停在等待状态、之后再也截图不了。
	 * 下限 100 tick（5 秒），上限 32767 tick（≈27 分钟，等于“调试时基本不会触发”）。
	 */
	public static final int NO_FRAME_TIMEOUT_MIN = 100;
	public static final int NO_FRAME_TIMEOUT_MAX = 32767;

	/** 设置界面里无渲染帧阈值可走的档位（覆盖 100 ~ 32767 tick）。 */
	public static final int[] NO_FRAME_TIMEOUT_STEPS =
		{100, 200, 400, 600, 1000, 2000, 4000, 8000, 12000, 16384, 24000, 32767};

	/**
	 * 「副渲染截图」的目标分辨率（见 {@link #screenshotWidth} / {@link #screenshotHeight}）。
	 *
	 * <p>两个值都留空（0）时跟随窗口分辨率 —— 这时渲染进主帧缓冲，与旧版行为完全一致。
	 * 玩家只填一边时，另一边按窗口宽高比推出（界面里也有两个按钮做同一件事）。
	 */
	public static final int SCREENSHOT_SIZE_MIN = 64;
	/** 单边上限：再大也超过 GL 的 {@code GL_MAX_TEXTURE_SIZE} 常见值（16384），建不出目标。 */
	public static final int SCREENSHOT_SIZE_MAX = 16384;
	/**
	 * 建议的像素总量上限（≈33M，等于 7680×4320）。
	 *
	 * <p><b>这只是警告值，不是硬限制</b>：超过它照样按玩家填的尺寸渲染，只是很可能因为显存不足
	 * 失败或者慢到无法接受 —— 界面上会提示，由玩家自己决定。
	 */
	public static final long SCREENSHOT_PIXEL_WARN = 7680L * 4320L;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static DuoConfig instance;

	/** 总开关。默认关闭：这东西会实打实地吃掉一倍以上的 GPU 开销，必须玩家主动打开。 */
	public boolean enabled = false;

	/** 副光影包名（shaderpacks 目录下的目录名或去掉 .zip 的文件名）。 */
	public String secondaryPack = "";

	/** 切换主/副管线后，把原来的主管线留作新的副管线（双向切换都能零编译）。 */
	public boolean keepOldPrimaryAsSecondary = true;

	/** 在聊天框里报告预编译/切换/自检结果。 */
	public boolean chatFeedback = true;

	/** 在 HUD 左上角显示主/副管线状态。 */
	public boolean hudStatus = false;

	/**
	 * <b>调试开关</b>：新管线建好后是否补渲染一帧（“初始化渲染”）。
	 *
	 * <p>默认开 = 一直以来的行为。关掉可以省掉那一整遍世界渲染（预编译刚结束时更顺、响应更快），
	 * 代价是这条管线在被真正用到之前<b>一帧都没画过</b>，由此可能：
	 * ① 按 F7 切过去的第一帧是黑的（Iris 的 composite/final 翻转状态还没跑起来）；
	 * ② 第一次切换时现编译 Sodium 的地形程序，多一次卡顿。
	 * 出事就把这个开关打开（回退到旧行为），不需要换版本。
	 */
	public boolean initialWarmupRender = true;

	/** 切换主副时，如果两包在“会被烘焙进区块网格”的设置上不同，是否重做网格。 */
	public boolean rebuildChunksOnSwitch = true;

	/**
	 * 需要重做网格时，是否用 Sodium 的“逐区块排队重建”（旧网格会渲染到新网格建好，玩家看不到卸载）。
	 * 关掉则退回 Iris 那套整体重建（会有可见的区块卸载/重载）。
	 */
	public boolean seamlessRemesh = true;

	/**
	 * “副管线截图”：开启后 F2 会用副管线渲染一帧并把它存成截图（原版 F2 由本模组接管）。
	 */
	public boolean secondaryScreenshot = false;

	/**
	 * 副管线截图是否保存成 JPG（压缩）而不是 PNG（无损）。
	 *
	 * <p>按质量 85% 编码，文件明显更小；代价是有损压缩（边缘会出现压缩痕迹）。
	 * 只影响本模组接管的 F2 截图，不影响原版截图。
	 */
	public boolean screenshotJpg = false;

	/**
	 * 预编译副管线时，把“解包 + 解析光影包”放到后台线程，避免整段工作在渲染线程上串行跑完。
	 *
	 * <p>注意：着色器编译本身依赖 GL 上下文，只能在渲染线程上执行，详见
	 * {@link DuoManager} 的说明，这里不包含那一段。
	 *
	 * <p>0.2 起这里只管“解包放工作线程”这一件事；旧配置里它是 false 时，
	 * 解包也在渲染线程同步做。
	 */
	public boolean backgroundPrewarm = true;

	/**
	 * 「截图副管线性能调度」策略，见 {@code POLICY_*}：只决定区块重建**每 tick 投递多少段**。
	 *
	 * <p>默认「均衡」；越靠平滑端，前台越稳，但地形替换得越慢（总工作量不变）。
	 */
	public int remeshPolicy = POLICY_MEDIUM;

	/**
	 * 「副管线截图」取景前，先让副管线完整渲染多少帧再按快门。
	 *
	 * <p>玩家实测：MakeUp 这类包刚加载时画面亮度极低，要连续渲染 10~20 帧、
	 * 等它自己那套“人眼适应”把亮度拉起来，画面才可用——这是<b>光影包自己的显示策略</b>，
	 * 与本模组无关（见 docs/03）。所以取景前先渲染若干帧，而不是只渲染 1 帧。
	 */
	public int screenshotWarmupFrames = 20;

	/**
	 * 「副管线截图」等待期间，连续多少个 tick 一帧都没渲染出来就取消本次截图（收尾保护）。
	 *
	 * <p>可选区间见 {@link #NO_FRAME_TIMEOUT_MIN} ~ {@link #NO_FRAME_TIMEOUT_MAX}；
	 * 调大便于调试（窗口最小化/暂停时不会被提前取消），调小便于快速验证收尾分支。
	 */
	public int noFrameTimeoutTicks = NO_FRAME_TIMEOUT_MIN;

	/** 「副渲染截图」的目标宽度：0 = 跟随窗口。 */
	public int screenshotWidth = 0;

	/** 「副渲染截图」的目标高度：0 = 跟随窗口。 */
	public int screenshotHeight = 0;

	/**
	 * 在 shaderpacks 目录里维护一个叫「原版」的目录型光影包（见 {@link VanillaPack}）。
	 *
	 * <p>它不提供任何程序，Iris 会为它合成与原生等价的 fallback 着色器，
	 * 于是「原版」可以作为一条完整管线参与双管线的切换 / 截图 / 自检。
	 * 关掉就不再生成（也不会删掉已经生成的）。
	 */
	public boolean vanillaPackEntry = true;

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("shaderflash.json");
	}

	public static synchronized DuoConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static DuoConfig load() {
		Path file = path();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				DuoConfig loaded = GSON.fromJson(reader, DuoConfig.class);
				if (loaded != null) {
					loaded.migrateOldSettings();
					return loaded;
				}
			} catch (Exception error) {
				ShaderFlash.LOGGER.warn("[ShaderFlash] 读取配置失败，改用默认值", error);
			}
		}
		DuoConfig fresh = new DuoConfig();
		fresh.save();
		return fresh;
	}

	public synchronized void save() {
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException error) {
			ShaderFlash.LOGGER.warn("[ShaderFlash] 保存配置失败", error);
		}
	}

	/** 策略的翻译键后缀（用于界面与日志）。 */
	public static String policyKey(int policy) {
		if (policy < 0 || policy >= POLICY_COUNT) {
			policy = POLICY_MEDIUM;
		}
		return POLICY_KEYS[policy];
	}

	public String policyName() {
		return ModTexts.str(ModTexts.policy(remeshPolicy));
	}

	/** 无渲染帧阈值（已夹到 {@link #NO_FRAME_TIMEOUT_MIN} ~ {@link #NO_FRAME_TIMEOUT_MAX}）。 */
	public int noFrameTimeoutTicks() {
		return clampNoFrameTimeout(noFrameTimeoutTicks);
	}

	/** 把任意数值夹到合法区间（配置文件被手工改成越界值时也安全）。 */
	public static int clampNoFrameTimeout(int ticks) {
		return Math.max(NO_FRAME_TIMEOUT_MIN, Math.min(NO_FRAME_TIMEOUT_MAX, ticks));
	}

	/** 界面与日志用的阈值文案：`100 tick（约 5.0 秒）`。 */
	public static String noFrameTimeoutName(int ticks) {
		int clamped = clampNoFrameTimeout(ticks);
		double seconds = clamped / 20.0;
		String human = seconds < 60.0
			? ModTexts.str(seconds < 10.0 ? ModTexts.VALUE_SECONDS_FRACTION : ModTexts.VALUE_SECONDS, seconds)
			: ModTexts.str(ModTexts.VALUE_MINUTES, seconds / 60.0);
		return ModTexts.str(ModTexts.VALUE_TICKS_APPROX, clamped, human);
	}

	/** 目标宽（已夹到合法区间；0 表示“跟随窗口”，原样保留）。 */
	public int screenshotWidth() {
		return clampScreenshotSize(screenshotWidth);
	}

	/** 目标高（同上）。 */
	public int screenshotHeight() {
		return clampScreenshotSize(screenshotHeight);
	}

	/** 把任意数值夹到合法区间；0 表示“跟随窗口”，原样保留。 */
	public static int clampScreenshotSize(int value) {
		if (value <= 0) {
			return 0;
		}
		return Math.max(SCREENSHOT_SIZE_MIN, Math.min(SCREENSHOT_SIZE_MAX, value));
	}

	/** 旧配置迁移：0.1.17 的 `prewarmPolicy` 语义已经改变，忽略即可（这里只留个钩子）。 */
	public void migrateOldSettings() {
		// 0.1.17 的字段名是 prewarmPolicy；0.2 改成 remeshPolicy 后旧值不再读入，
		// 直接落到默认「均衡」，无需迁移。
		// 0.2.2 删掉了 mode / intervalFrames / skipWhenScreenOpen（只保留“编译好但不周期渲染”
		// 这一种行为），旧配置里留着这几个字段也无害：Gson 读进来就被忽略，下次保存时自动消失。
	}
}
