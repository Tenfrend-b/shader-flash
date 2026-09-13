package dev.shaderflash;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;

/**
 * 本模组<b>所有玩家可见文本的唯一来源</b>。
 *
 * <h2>怎么改</h2>
 * 只改这个文件里的字符串即可：每一条都是 {@code new LText("中文", "English")}，
 * 中文在前、英文在后（英文留空字符串就是“照抄中文也没有”）。
 * 代码里全部通过下面的常量引用（写成 {@code ModTexts.XXX}），
 * 所以改名/删除会直接编译不过 —— 不会出现“键写错了但运行期才发现”。
 *
 * <h2>占位符</h2>
 * {@code %s} 是占位符（按出现顺序替换），{@code %%} 表示一个字面百分号。
 * 改动占位符个数会让日志里出现一条 WARN，并把原文照原样显示（不会崩游戏）。
 *
 * <h2>唯一的例外</h2>
 * 按键名称与按键分类（{@code key.shaderflash.*} / {@code key.categories.shaderflash}）
 * 仍然留在 {@code assets/shaderflash/lang/*.json} —— Minecraft 的按键系统按翻译键解析它们，
 * Java 常量喂不进去。
 */
public final class ModTexts {

	private ModTexts() {
	}

	/** 一段双语文本。 */
	public record LText(String zh, String en) {
	}

	// ------------------------------------------------------------------
	// 界面：标题与选包页
	// ------------------------------------------------------------------
	public static final LText SCREEN_TITLE = new LText(
		"闪光灯设置",
		"Shader Flash settings");
	public static final LText SCREEN_PICK_TITLE = new LText(
		"选择副光影包",
		"Choose the secondary shader pack");
	public static final LText SCREEN_PACK_HINT = new LText(
		"点击选择副光影包（★ = 当前主管线）",
		"Click to choose the secondary pack (star = current primary)");

	// ------------------------------------------------------------------
	// 设置界面：分节标题
	// ------------------------------------------------------------------
	public static final LText SECTION_STATUS = new LText(
		"状态",
		"Status");
	public static final LText SECTION_PIPELINE = new LText(
		"副管线",
		"Secondary pipeline");
	public static final LText SECTION_SWITCH = new LText(
		"主副切换",
		"Swapping");
	public static final LText SECTION_SCREENSHOT = new LText(
		"副管线截图",
		"Secondary screenshot");
	public static final LText SECTION_INTERFACE = new LText(
		"界面与提示",
		"Interface and feedback");
	public static final LText SECTION_DIAGNOSTICS = new LText(
		"诊断",
		"Diagnostics");

	// ------------------------------------------------------------------
	// 设置界面：选项标签（%s = 开/关或当前值）
	// ------------------------------------------------------------------
	public static final LText OPTION_ENABLED = new LText(
		"双管线：%s",
		"Dual pipeline: %s");
	public static final LText OPTION_KEEP_OLD = new LText(
		"保留旧主包为副包：%s",
		"Keep old primary as secondary: %s");
	public static final LText OPTION_CHAT = new LText(
		"聊天提示：%s",
		"Chat feedback: %s");
	public static final LText OPTION_HUD = new LText(
		"HUD 状态：%s",
		"HUD status: %s");
	public static final LText OPTION_BACKGROUND_PREWARM = new LText(
		"后台预编译：%s",
		"Background prewarm: %s");
	public static final LText OPTION_INITIAL_WARMUP = new LText(
		"新管线初始化渲染（调试）：%s",
		"Initialise new pipeline with one render (debug): %s");
	public static final LText OPTION_VANILLA_PACK = new LText(
		"「空包」光影包条目：%s",
		"\"Empty pack\" entry: %s");
	public static final LText OPTION_REBUILD_CHUNKS = new LText(
		"切换时重做区块网格：%s",
		"Rebuild chunk meshes on switch: %s");
	public static final LText OPTION_SEAMLESS_REMESH = new LText(
		"逐区块后台重建（无感）：%s",
		"Background per-chunk rebuild: %s");
	public static final LText OPTION_SECONDARY_SCREENSHOT = new LText(
		"副管线截图（F2 用副管线）：%s",
		"Secondary-pipeline screenshot (F2): %s");
	public static final LText OPTION_SCREENSHOT_JPG = new LText(
		"JPG 模式（压缩截图）：%s",
		"JPG mode (compressed screenshots): %s");
	public static final LText OPTION_SCREENSHOT_WARMUP = new LText(
		"取景预热帧数：%s 帧",
		"Capture warm-up frames: %s");
	public static final LText OPTION_NO_FRAME_TIMEOUT = new LText(
		"最大静默等待时间：%s",
		"Max silent wait: %s");
	public static final LText OPTION_SCREENSHOT_WIDTH = new LText(
		"截图宽度（像素）",
		"Capture width (px)");
	public static final LText OPTION_SCREENSHOT_HEIGHT = new LText(
		"截图高度（像素）",
		"Capture height (px)");
	public static final LText OPTION_REMESH_POLICY = new LText(
		"截图副管线性能调度：%s",
		"Screenshot secondary-pipeline scheduling: %s");

	// ------------------------------------------------------------------
	// 通用取值文案（开/关/无/分辨率等）
	// ------------------------------------------------------------------
	public static final LText VALUE_ON = new LText(
		"开",
		"ON");
	public static final LText VALUE_OFF = new LText(
		"关",
		"OFF");
	public static final LText VALUE_NONE = new LText(
		"未选择",
		"none");
	public static final LText VALUE_FOLLOW_WINDOW = new LText(
		"跟随窗口（%s×%s）",
		"follow the window (%s×%s)");
	public static final LText VALUE_FOLLOW_WINDOW_SHORT = new LText(
		"跟随窗口",
		"follow window");
	public static final LText VALUE_RESOLUTION = new LText(
		"%s×%s（自定义）",
		"%s×%s (custom)");

	// ------------------------------------------------------------------
	// 设置界面：按钮标签
	// ------------------------------------------------------------------
	public static final LText BUTTON_PICK_PACK = new LText(
		"副光影包：%s",
		"Secondary pack: %s");
	public static final LText BUTTON_PREWARM = new LText(
		"预编译 / 重建副管线",
		"Prewarm / rebuild secondary");
	public static final LText BUTTON_CANCEL_PREWARM = new LText(
		"取消预编译",
		"Cancel prewarm");
	public static final LText BUTTON_SWAP = new LText(
		"立即切换主/副",
		"Swap primary / secondary now");
	public static final LText BUTTON_DIAGNOSE = new LText(
		"兼容性自检",
		"Compatibility check");
	public static final LText BUTTON_RESET_GHOST = new LText(
		"解除离屏渲染停用",
		"Re-enable offscreen rendering");
	public static final LText BUTTON_FILL_HEIGHT = new LText(
		"按窗口宽高比补全高度",
		"Fill height from window aspect");
	public static final LText BUTTON_FILL_WIDTH = new LText(
		"按窗口宽高比补全宽度",
		"Fill width from window aspect");

	// ------------------------------------------------------------------
	// 设置界面：说明文字（会按宽度自动折行）
	// ------------------------------------------------------------------
	public static final LText NOTE_INITIAL_WARMUP = new LText(
		"控制初始化渲染副管线时是否启用初始帧生成，默认为开",
		"Controls whether a newly built secondary pipeline renders one initial frame. On by default.");
	public static final LText NOTE_SCREENSHOT = new LText(
		"开启会接管原版的截图行为：副管线会加载地形并渲染一帧，并写入生成的截图文件。这个过程可能会造成地形的临时闪烁，但对游戏不会有大的影响",
		"When enabled, F2 is taken over by this mod: the secondary pipeline loads the terrain, renders one frame and writes it to the screenshot file. The terrain may flicker briefly while this happens, but it has no lasting effect on the game.");
	public static final LText NOTE_SCREENSHOT_JPG = new LText(
		"开启后副管线截图保存为 JPG（质量约 85%）：文件明显更小，但没有 PNG 的无损画质，边缘会出现压缩痕迹。文件名后缀为 .jpg。",
		"When enabled, secondary-pipeline screenshots are saved as JPG (quality about 85%): much smaller files, but lossy - edges show compression artefacts. The file name ends with .jpg.");
	public static final LText NOTE_VANILLA = new LText(
		"「空包」是本模组提供的空光影包，通过Iris的回落机制，尽量模拟原版着色器的行为。如不需要可关掉此选项",
		"\"Empty pack\" is an empty shader pack provided by this mod. Through Iris's fallback mechanism it mimics vanilla shader behaviour as closely as it can. Turn this option off if you do not want it.");
	public static final LText NOTE_KEYS = new LText(
		"快捷键可在“选项 → 控制”中修改：切换 F7、开关 F6、设置 F8。",
		"Keybinds can be changed in Options -> Controls: swap F7, toggle F6, settings F8.");
	public static final LText NOTE_NO_FRAME_TIMEOUT = new LText(
		"在无渲染帧输出的情况下，静默等待副管线截图的超时阈值",
		"Timeout for silently waiting for a secondary-pipeline screenshot while no frames are being rendered.");
	public static final LText NOTE_SCREENSHOT_RESOLUTION = new LText(
		"当前：%s",
		"Current: %s");
	public static final LText NOTE_SCREENSHOT_RESOLUTION_WARN = new LText(
		"注意：当前目标 %s×%s（约 %s MP）超过建议值（约 33 MP）这将可能导致游戏卡顿或显存不足而崩溃，请自行确认。",
		"Note: the current target %s x %s (~%s MP) is above the suggested ~33 MP. This may cause stutter or an out-of-VRAM crash - please make sure you are fine with that.");
	public static final LText NOTE_REMESH_POLICY = new LText(
		"这会影响「副管线截图」取景前每tick的地形重建目标数，这不能决定最终的地形重建速度，因为显卡总的处理能力有限",
		"This affects how many chunk rebuilds are submitted per tick before a secondary-pipeline screenshot is framed. It does not decide the final rebuild speed, which is limited by the GPU's overall throughput.");

	// ------------------------------------------------------------------
	// 光影包相关标注
	// ------------------------------------------------------------------
	public static final LText PACK_VANILLA_SUFFIX = new LText(
		"类原版渲染兼容包",
		"vanilla-like rendering compatibility pack");

	// ------------------------------------------------------------------
	// 状态区（HUD 与设置界面顶部的状态块）
	// ------------------------------------------------------------------
	public static final LText STATUS_PRIMARY = new LText(
		"主管线：%s",
		"Primary: %s");
	public static final LText STATUS_SECONDARY = new LText(
		"副管线：%s（预编译 %s ms）",
		"Secondary: %s (prewarmed in %s ms)");
	public static final LText STATUS_SECONDARY_NONE = new LText(
		"副管线：未加载",
		"Secondary: not loaded");
	public static final LText STATUS_PREWARMING = new LText(
		"正在预编译 %s：%s（%s%%）",
		"Prewarming %s: %s (%s%%)");
	public static final LText STATUS_SWAP_PENDING = new LText(
		"预编译完成后会自动切换到副管线",
		"Will switch to the secondary pipeline once the prewarm finishes");
	public static final LText STATUS_CAPTURING = new LText(
		"正在等待副管线 %s 加载地形",
		"Waiting for secondary pipeline %s to load the terrain");
	public static final LText STATUS_RESOLUTION = new LText(
		"副渲染截图分辨率：%s",
		"Secondary-capture resolution: %s");
	public static final LText STATUS_INITIAL_WARMUP = new LText(
		"初始化渲染：%s",
		"Initial warm-up render: %s");
	public static final LText STATUS_INITIAL_WARMUP_SKIPPED = new LText(
		"（已跳过，尚未渲染过）",
		" (skipped; never rendered yet)");
	public static final LText STATUS_COST = new LText(
		"副管线本帧耗时：%s ms",
		"Secondary pass cost: %s ms");
	public static final LText STATUS_SWAP = new LText(
		"上次切换：%s ms",
		"Last swap: %s ms");
	public static final LText STATUS_ERROR = new LText(
		"状态：%s",
		"Status: %s");

	// ------------------------------------------------------------------
	// 预编译阶段名
	// ------------------------------------------------------------------
	public static final LText PREWARM_PHASE_PREPARE = new LText(
		"准备",
		"preparing");
	public static final LText PREWARM_PHASE_UNPACK = new LText(
		"解包",
		"unpacking");
	public static final LText PREWARM_PHASE_PARSE = new LText(
		"解析光影包",
		"parsing the pack");
	public static final LText PREWARM_PHASE_COMPILE = new LText(
		"编译着色器",
		"compiling shaders");

	// ------------------------------------------------------------------
	// 聊天框提示
	// ------------------------------------------------------------------
	public static final LText MESSAGE_ENABLED = new LText(
		"双管线已开启，正在准备副管线……",
		"Dual pipeline enabled, preparing the secondary pipeline...");
	public static final LText MESSAGE_DISABLED = new LText(
		"双管线已关闭，副管线资源已释放",
		"Dual pipeline disabled, secondary resources released");
	public static final LText MESSAGE_NO_IRIS = new LText(
		"未检测到 Iris",
		"Iris is not installed");
	public static final LText MESSAGE_NO_PACK = new LText(
		"还没有选择副光影包（打开设置界面选择，默认 F8）",
		"No secondary shader pack chosen yet (open the settings, default F8)");
	public static final LText MESSAGE_NO_SHADER = new LText(
		"Iris 当前没有启用光影包；请先启用一个光影包再开启双管线",
		"Iris has no shader pack enabled; enable one before turning on the dual pipeline");
	public static final LText MESSAGE_PACK_MISSING = new LText(
		"找不到光影包 %s",
		"Shader pack not found: %s");
	public static final LText MESSAGE_PREWARM_STARTED = new LText(
		"正在后台预编译副管线 %s……",
		"Prewarming the secondary pipeline %s in the background...");
	public static final LText MESSAGE_PREWARM_RUNNING = new LText(
		"%s 已经在预编译中",
		"%s is already being prewarmed");
	public static final LText MESSAGE_PRELOAD_AFTER_DIMENSION_CHANGE = new LText(
		"换了维度，已释放旧维度的副管线；需要时请手动预编译一次",
		"Dimension changed, so the secondary pipeline for the old dimension was released; prewarm again manually when you need it");
	public static final LText MESSAGE_PREWARMED = new LText(
		"副管线 %s 预编译完成，用时 %s ms",
		"Secondary pipeline %s prewarmed in %s ms");
	public static final LText MESSAGE_BUILD_FAILED = new LText(
		"副管线创建失败：%s",
		"Failed to build the secondary pipeline: %s");
	public static final LText MESSAGE_DISABLED_HINT = new LText(
		"双管线未开启（F6 或设置界面里打开）",
		"Dual pipeline is off (press F6 or open the settings)");
	public static final LText MESSAGE_NO_WORLD = new LText(
		"需要先进入世界",
		"Join a world first");
	public static final LText MESSAGE_SWAP_FAILED = new LText(
		"切换失败：%s",
		"Swap failed: %s");
	public static final LText MESSAGE_SWAPPED = new LText(
		"已切换到 %s，耗时 %s ms（这条管线重新编译本来要 %s）",
		"Switched to %s in %s ms (a full recompile would have cost %s)");
	public static final LText MESSAGE_GHOST_DISABLED = new LText(
		"副管线渲染已停用：%s（可在设置界面解除）",
		"Ghost rendering disabled: %s (re-enable it in the settings)");
	public static final LText MESSAGE_SCREENSHOT_NO_SECONDARY = new LText(
		"暂无可用副管线，本次为原版截图",
		"No secondary pipeline available; using the vanilla screenshot this time");
	public static final LText MESSAGE_SCREENSHOT_PREWARMING = new LText(
		"副管线仍在预编译，本次使用原版截图",
		"The secondary pipeline is still prewarming; using the vanilla screenshot this time");
	public static final LText MESSAGE_SCREENSHOT_FAILED = new LText(
		"副管线截图未完成，已还原主管线材质",
		"The secondary-pipeline screenshot did not complete; the primary material tables have been restored");
	public static final LText MESSAGE_SCREENSHOT_DONE = new LText(
		"已用副管线 %s 截图：%s×%s",
		"Screenshot taken with secondary pipeline %s: %s×%s");
	public static final LText MESSAGE_SCREENSHOT_JPG_DONE = new LText(
		"已保存压缩截图（JPG）：%s",
		"Saved the compressed screenshot (JPG): %s");
	public static final LText MESSAGE_REMESH = new LText(
		"两包差异（%s），已把 %s 个区块段排队后台重建",
		"Packs differ (%s); %s chunk sections queued for background rebuild");
	public static final LText MESSAGE_LINKED_FAILED = new LText(
		"副管线 %s 有 %s 个着色器程序没能链接成功，加载失败",
		"Secondary pipeline %s has %s shader program(s) that failed to link; loading failed");
	public static final LText MESSAGE_LINKED_REFUSE = new LText(
		"副管线 %s 有未链接的程序（%s），不能提升为主管线",
		"Secondary pipeline %s has unlinked program(s) (%s) and cannot be promoted to primary");

	// ------------------------------------------------------------------
	// 兼容性自检输出
	// ------------------------------------------------------------------
	public static final LText DIAG_HEADER = new LText(
		"兼容性自检",
		"Compatibility check");
	public static final LText DIAG_NO_PRIMARY = new LText(
		"主管线未就绪",
		"Primary pipeline is not ready");
	public static final LText DIAG_NO_SECONDARY = new LText(
		"副管线未加载，先预编译一次",
		"Secondary pipeline is not loaded, prewarm it first");
	public static final LText DIAG_DIMENSION_CHANGED = new LText(
		"维度已变化，副管线需要重建",
		"Dimension changed, the secondary pipeline must be rebuilt");
	public static final LText DIAG_AO = new LText(
		"环境光遮蔽等级：主 %s / 副 %s（%s）",
		"Ambient occlusion: primary %s / secondary %s (%s)");
	public static final LText DIAG_VOXELIZE = new LText(
		"体素化光源方块：主 %s / 副 %s（%s）",
		"Voxelize light blocks: primary %s / secondary %s (%s)");
	public static final LText DIAG_ENTITY_DRAWS = new LText(
		"实体分离绘制：主 %s / 副 %s（%s）",
		"Separate entity draws: primary %s / secondary %s (%s)");
	public static final LText DIAG_SHADOW = new LText(
		"阴影分辨率：主 %s / 副 %s（%s）",
		"Shadow resolution: primary %s / secondary %s (%s)");
	public static final LText DIAG_BLOCK_IDS = new LText(
		"方块材质 ID 表：%s",
		"Block material ID table: %s");
	public static final LText DIAG_BLOCK_IDS_NOTE = new LText(
		"两条管线的材质 ID 表不一致：区块顶点里保存的 ID 来自当前的主管线，副管线调用时渲染可能不符合预期，切到副包时 Iris 需要重建一次区块网格。",
		"The two pipelines map block material IDs differently: chunk vertices carry the IDs written for the current primary pipeline, so rendering with the secondary pipeline may not match expectations. Switching to that pack makes Iris rebuild the chunk meshes once.");
	public static final LText DIAG_PACK_PROGRAMS = new LText(
		"副包自带程序：%s %s %s %s（=若无， Iris 会模拟原版着色器的对应行为）",
		"Programs the pack itself provides: %s %s %s %s (missing ones fall back to Iris's synthesized vanilla-like shaders)");
	public static final LText DIAG_VANILLA = new LText(
		"检测到副包是「空包」：它被设计成不提供任何程序，检测列表均为无是预期行为。",
		"The secondary pack is the \"Empty pack\": by design it provides no programs, so an all-empty list above is expected.");

	// ------------------------------------------------------------------
	// 附加建议（跟在提示后面）
	// ------------------------------------------------------------------
	public static final LText HINT_LINKED_RESOURCES = new LText(
		"这可能是显存不足导致的：建议更换负载更轻的光影包、或者在Iris中重载光影后重试",
		"This may be caused by running out of VRAM: try a lighter shader pack, or reload the shader pack in Iris and retry");

	// ------------------------------------------------------------------
	// HUD 上的截图进度块
	// ------------------------------------------------------------------
	public static final LText HUD_CAPTURE_TITLE = new LText(
		"副管线截图（%s）：%s",
		"Secondary-pipeline screenshot (%s): %s");
	public static final LText HUD_CAPTURE_TERRAIN = new LText(
		"正在通过副包重建地形",
		"rebuilding terrain with the secondary pack");
	public static final LText HUD_CAPTURE_WARMUP = new LText(
		"正在预热副管线画面",
		"warming up the secondary pipeline");
	public static final LText HUD_CAPTURE_READY = new LText(
		"即将按下快门！",
		"about to shoot!");
	public static final LText HUD_CAPTURE_CHUNKS = new LText(
		"区块段 %s / %s（%s%%）",
		"chunk sections %s / %s (%s%%)");
	public static final LText HUD_CAPTURE_UNKNOWN = new LText(
		"区块段统计不可用，等待重建完成…",
		"section statistics unavailable, waiting for the rebuild to finish...");
	public static final LText HUD_CAPTURE_FRAMES = new LText(
		"预热 %s / %s 帧 · 已等待 %s ms",
		"warm-up %s / %s frames · waited %s ms");
	public static final LText HUD_CAPTURE_RESOLUTION = new LText(
		"目标分辨率 %s",
		"target resolution %s");

	// ------------------------------------------------------------------
	// 「截图副管线性能调度」五档名称
	// ------------------------------------------------------------------
	public static final LText POLICY_SMOOTHEST = new LText(
		"极低负载",
		"minimal load");
	public static final LText POLICY_SMOOTH = new LText(
		"低负载",
		"low load");
	public static final LText POLICY_BALANCED = new LText(
		"中负载",
		"medium load");
	public static final LText POLICY_FAST = new LText(
		"高负载",
		"high load");
	public static final LText POLICY_FASTEST = new LText(
		"最大负载",
		"max load");

	// ------------------------------------------------------------------
	// 自检与状态里的碎片（HUD 前缀、错误原因、判读用词）
	// ------------------------------------------------------------------
	/** HUD 每行的前缀。 */
	public static final LText TAG = new LText(
		"[闪光灯] ",
		"[Shader Flash] ");
	/** 还没渲染过时，自检里显示的那句话。 */
	public static final LText STATUS_NEVER_RENDERED = new LText(
		"（尚未渲染）",
		"(not rendered yet)");
	/** 「没有 / 空」这类短词。 */
	public static final LText VALUE_NONE_SHORT = new LText(
		"无",
		"none");
	public static final LText VALUE_EMPTY_SLOT = new LText(
		"空",
		"empty");
	/** 自检里“两包是否一致”的判读词。 */
	public static final LText VALUE_SAME = new LText(
		"一致",
		"same");
	public static final LText VALUE_DIFFERENT = new LText(
		"不同",
		"differs");
	/** 自检里“这个包自带某个程序吗”的两半。 */
	public static final LText VALUE_HAS_PROGRAM = new LText(
		"%s=自带",
		"%s=built-in");
	public static final LText VALUE_NO_PROGRAM = new LText(
		"%s=无",
		"%s=none");
	public static final LText LABEL_TERRAIN = new LText("地形", "terrain");
	public static final LText LABEL_ENTITIES = new LText("实体", "entities");
	public static final LText LABEL_WATER = new LText("水面", "water");
	public static final LText LABEL_SHADOW = new LText("阴影", "shadow");
	/** 自检：渲染中途抓到的即时状态（纯技术细节，占位符都是数字/布尔）。 */
	public static final LText DIAG_RUNTIME_PIPELINE = new LText(
		"当前管线类：%s，当前包：%s",
		"current pipeline class: %s, current pack: %s");
	public static final LText DIAG_RUNTIME_STATE = new LText(
		"渲染时的真实状态：%s（Iris 全局阴影标志 ACTIVE=%s）",
		"real state while rendering: %s (Iris global shadow flag ACTIVE=%s)");
	public static final LText DIAG_FALLBACK_NOTE = new LText(
		"注意：ShaderMap 里没有的程序说明 Iris 用的是它自己合成的 fallback（接近原版），并不代表“没生效”。",
		"Note: a program missing from the ShaderMap means Iris is using its own synthesised fallback (close to vanilla); it does not mean \"the pack is not applied\".");
	public static final LText DIAG_SLOT = new LText(
		"副管线槽位：%s；副渲染已停用=%s",
		"secondary slot: %s; secondary rendering disabled=%s");
	public static final LText DIAG_SNAPSHOT_IMMEDIATE = new LText(
		"即时状态：isRenderingLevel=%s，renderWithExtendedVertexFormat=%s，entityId=%s，blockEntityId=%s，itemId=%s",
		"live state: isRenderingLevel=%s, renderWithExtendedVertexFormat=%s, entityId=%s, blockEntityId=%s, itemId=%s");
	public static final LText DIAG_SNAPSHOT_ENTITIES = new LText(
		"实体路径：深度颜色锁=%s，手部渲染中=%s，阴影ACTIVE=%s，扩展顶点格式(全局)=%s",
		"entity path: depth/color locked=%s, hand rendering=%s, shadow ACTIVE=%s, extended vertex format(global)=%s");
	public static final LText DIAG_SNAPSHOT_PROGRAMS = new LText(
		"程序链接：主管线=%s，副管线=%s",
		"linked programs: primary=%s, secondary=%s");
	/** 自检：着色器覆盖开关（后面接 true/false）。 */
	public static final LText DIAG_SHADER_OVERRIDE = new LText(
		"；shouldOverrideShaders=%s",
		"; shouldOverrideShaders=%s");
	/** 程序链接检查的结论词。 */
	public static final LText HEALTH_ALL_LINKED = new LText(
		"全部已链接",
		"all linked");
	public static final LText HEALTH_UNLINKED = new LText(
		"未链接 %s",
		"unlinked %s");
	public static final LText HEALTH_UNKNOWN = new LText(
		"未知",
		"unknown");
	/** 状态行里显示的失败原因。 */
	public static final LText ERROR_UNKNOWN = new LText(
		"未知错误",
		"unknown error");
	public static final LText ERROR_NO_IRIS = new LText(
		"未安装 Iris",
		"Iris is not installed");
	public static final LText ERROR_NO_SECONDARY_PACK = new LText(
		"尚未选择副光影包",
		"no secondary shader pack selected");
	public static final LText ERROR_NO_SHADER_PACK = new LText(
		"Iris 当前没有启用光影包（原版渲染管线无法参与双管线）",
		"Iris has no shader pack enabled (the vanilla rendering pipeline cannot take part in the dual pipeline)");
	public static final LText ERROR_UNKNOWN_DIMENSION = new LText(
		"当前维度未知",
		"current dimension is unknown");
	public static final LText ERROR_PACK_MISSING = new LText(
		"找不到光影包：%s",
		"shader pack not found: %s");
	public static final LText ERROR_IRIS_UNAVAILABLE = new LText(
		"Iris 不可用",
		"Iris is unavailable");
	public static final LText ERROR_PACK_NO_SHADERS = new LText(
		"光影包缺失 shaders 目录：%s",
		"the shader pack has no shaders directory: %s");
	public static final LText ERROR_INJECTION_MISSING = new LText(
		"注入点缺失：%s",
		"injection point missing: %s");
	public static final LText ERROR_REDIRECT_FAILED = new LText(
		"主帧缓冲重定向未生效（Iris/载入器版本不匹配？）",
		"main framebuffer redirection did not take effect (Iris/loader version mismatch?)");
	public static final LText ERROR_RENDER_FAILED = new LText(
		"副管线渲染异常：%s",
		"secondary pipeline render failed: %s");
	/** 无渲染帧阈值那条选项的量词与单位（占位符是数字）。 */
	public static final LText VALUE_SECONDS_FRACTION = new LText(
		"%.1f 秒",
		"%.1fs");
	public static final LText VALUE_SECONDS = new LText(
		"%.0f 秒",
		"%.0fs");
	public static final LText VALUE_MINUTES = new LText(
		"%.1f 分",
		"%.1f min");
	public static final LText VALUE_TICKS_APPROX = new LText(
		"%s tick（约 %s）",
		"%s ticks (~%s)");

	// ------------------------------------------------------------------
	// 取值：按当前游戏语言挑一条文本并格式化
	// ------------------------------------------------------------------

	/** 当前游戏语言是不是中文（zh_cn / zh_tw 都算）。 */
	private static boolean chinese() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.getLanguageManager() == null) {
			return true; // 还没进游戏（例如自检）：默认中文
		}
		String code = client.getLanguageManager().getLanguage();
		return code == null || code.startsWith("zh");
	}

	/** 取一段文本（不格式化），把 {@code %%} 还原成百分号。 */
	public static String raw(LText text) {
		String template = text == null ? "" : (chinese() ? text.zh() : text.en());
		if (template == null || template.isEmpty()) {
			template = text == null ? "" : text.zh();
		}
		return template.replace("%%", "%");
	}

	/** 取一段文本并按 {@code %s} 顺序填充参数。参数个数不对只警告，不崩。 */
	public static String str(LText text, Object... args) {
		String template = text == null ? "" : (chinese() ? text.zh() : text.en());
		if (template == null || template.isEmpty()) {
			template = text == null ? "" : text.zh();
		}
		if (args == null || args.length == 0) {
			return template.replace("%%", "%");
		}
		try {
			return String.format(Locale.ROOT, template, args);
		} catch (RuntimeException error) {
			ShaderFlash.LOGGER.warn("[ShaderFlash] 文本占位符不匹配（{}）：{}", error.getMessage(), template);
			return template;
		}
	}

	/** 同上，但返回 {@link Text}（按钮、聊天消息用；返回类型是可变的，方便继续 {@code .formatted(...)}）。 */
	public static MutableText text(LText text, Object... args) {
		return Text.literal(str(text, args));
	}

	/** 带颜色的 Text（原来的 {@code .formatted(...)} 用法）。 */
	public static MutableText styled(LText text, Formatting formatting, Object... args) {
		return Text.literal(str(text, args)).formatted(formatting);
	}

	/**
	 * 形如「双管线：%s」的标签，第一个 {@code %s} 换成一段独立的 {@link Text}
	 * （这样开关的“开/关”还能保留自己的颜色）。
	 */
	public static MutableText join(LText text, Text value) {
		String template = text == null ? "" : (chinese() ? text.zh() : text.en());
		if (template == null || template.isEmpty()) {
			template = text == null ? "" : text.zh();
		}
		int index = template.indexOf("%s");
		if (index < 0) {
			return Text.literal(template.replace("%%", "%"));
		}
		return Text.literal(template.substring(0, index).replace("%%", "%"))
			.append(value)
			.append(template.substring(index + 2).replace("%%", "%"));
	}

	/** 开关的取值文本：开 = 绿色、关 = 红色。 */
	public static MutableText onOff(boolean value) {
		return Text.literal(str(value ? VALUE_ON : VALUE_OFF))
			.formatted(value ? Formatting.GREEN : Formatting.RED);
	}

	/** 「截图副管线性能调度」的五档名称，按策略编号取。 */
	public static LText policy(int index) {
		return switch (index) {
			case DuoConfig.POLICY_SEAMLESS -> POLICY_SMOOTHEST;
			case DuoConfig.POLICY_LOW -> POLICY_SMOOTH;
			case DuoConfig.POLICY_HIGH -> POLICY_FAST;
			case DuoConfig.POLICY_REALTIME -> POLICY_FASTEST;
			default -> POLICY_BALANCED;
		};
	}
}
