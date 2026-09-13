# 版本记录

## 版本号说明

**0.1 是本模组第一个对外版本**，版本号特意从 0 起，表示它还处在早期阶段
（依赖 Iris 内部实现、只验证过一个 Minecraft 版本、还没有经过大规模玩家验证）。

开发期间用过 1.0.0 ~ 1.4.1 这套临时迭代号（见下方对照表），
`docs/` 里几篇排查记录沿用了那套编号 —— 阅读时请以下表为准。

| 对外版本 | 开发迭代（文档旧编号） | 内容 |
| --- | --- | --- |
| **0.3.2** | —— | 两件事：① **修「JPG 模式下截图无法生成」** —— 根因是 1.20.4 的 `NativeImage.getBytes()` **不是原始像素**，而是**把图像编码成 PNG 后的字节**（字节码里是 `ByteArrayOutputStream` + `write(WritableByteChannel)` + STB 的失败原因），而按“原始 RGBA 像素”去索引它必然越界（日志：`ArrayIndexOutOfBoundsException: Index 43332033 out of bounds for length 43332033`，同一尺寸两次的长度还不一样——那正是 PNG 压缩结果）。现在改成「PNG 字节 → `ImageIO` 解码 → 统一成 `TYPE_INT_RGB` → JPEG 编码」，顺带不用猜通道顺序；编码/解码都在 IO 线程上做。② **删除“进世界自动预编译副管线”整条链路**（不再尝试修它）：移除 `autoBuildOnWorldJoin` 与 `rebuildAfterIrisReload` 两个开关、两个 `*AutoBuildPending` 标志、`onClientTick` 里的自动建/重建判断与只为它服务的退避字段；`onIrisPipelinesDestroyed` 只释放并提示手动预编译；**换维度也不再自动重建**（释放 + 提示）。副管线现在只由玩家主动构建：设置界面的「预编译 / 重建副管线」按钮、按 F7 时就地构建、打开总开关 |
| **0.3.1** | —— | 三件事：① **修「进世界自动预编译的管线不能渲染实体，而 F8 手动预编译的没事」** —— 两条路径其实是同一个函数，差异在**运行时机**：进世界时的自动预编译跑在“主管线还没构造出来”的那几个 tick 里，此时 Iris 的全局材质设置（方块材质表、实体/物品 ID 表、扩展顶点格式）还是**默认值**，而旧代码用“构造前的快照”去还原，就把这些默认值写回了全局；Iris 只在新建管线时设置它们，于是主管线之后一直用错误的值渲染 —— 实体顶点按原版格式写出、着色器却按扩展格式读 → 实体消失（`EntityShaderGuardMixin` 覆盖的是反方向，救不了）。现在改成**按主管线的包重放一遍全局设置**（`restorePrimarySettings` + `WorldSettings.applyFor` 支持主管线尚未构造的情况），并加了一道不变量安全网（光影管线下 `useExtendedVertexFormat` 必须为 true，被写坏就就地校正并打一条 WARN）。② **新增「JPG 模式（压缩截图）」开关**（`screenshotJpg`，默认关）：副管线截图可存成 JPG（质量 85%），体积明显更小；像素来源与 PNG 路径完全相同（同一个 `takeScreenshot`），编码与写盘在 IO 线程上做，文件名后缀 `.jpg`，完成提示带“点开文件”。③ 修正玩家润色文本里的两处笔误（“修改改”→“修改”、“崩溃败”→“崩溃”） |
| **0.3** | —— | **玩家润色中文文本 + 英文按新中文重写**：`ModTexts.java` 里的中文由玩家逐条润色（表述更贴近实际行为、去掉了过时的解释），30+ 条英文随之重写以保持一致；顺带修掉 `DIAG_PACK_PROGRAMS` 里历史遗留的占位符多一个 `%s`（调用点只给 4 个参数，自检那一行会显示原文）；文档里引用的界面名称（五档调度名、无渲染帧阈值）同步为新的说法。**技术逻辑零改动**，只动文本 |
| **0.2.6** | —— | **把所有玩家可见文本集中到一个文件**，全部调用改成编译期引用：新增 `dev/shaderflash/ModTexts.java`（150 条双语常量：设置界面、聊天提示、HUD、截图进度、兼容性自检、状态与错误原因、五档调度名、预编译阶段名……），代码统一写成 `ModTexts.text(ModTexts.MESSAGE_SWAPPED, …)` / `ModTexts.str(…)` / `ModTexts.join(…)` —— 键名写错**直接编译不过**，不再有“翻译键打错、游戏里显示一串键名”的可能。`assets/shaderflash/lang/*.json` 只保留 Minecraft 按键系统必须的 4 条按键文本（按翻译键解析，Java 常量喂不进去）；日志文本（`[ShaderFlash] …`）有意留在代码里（那是排查用的技术描述）。同时新增 [`docs/18-文本与本地化.md`](18-文本与本地化.md) 说明改法与边界。**下一版 0.3 留给“玩家润色文本之后”的编译**（版本号 0.3 由玩家指定） |
| **0.2.5** | —— | 新增**调试开关**「新管线初始化渲染」（`initialWarmupRender`，默认开 = 一直以来的行为）：关掉后新管线建好时不再补渲染那一帧（省掉一整遍世界渲染，预编译刚结束时更顺、响应更快），代价是这条管线在被真正用到之前一帧都没画过 —— 可能 F7 切过去的第一帧是黑的、第一次切换要现编译地形程序。开关可随时打回去（回退到旧行为，不必换版本），状态区会显示“初始化渲染：开/关（已跳过，尚未渲染过）”，日志也会记录“初始化渲染完成（N ms）”或“已按调试开关跳过”。顺带确认：副管线构造时已把 Iris 的 `initializedBlockIds` 预置为 true，所以关掉这一帧不会引发整世界区块重建 |
| **0.2.4** | —— | 按玩家要求改掉两处：① **删掉“临时放大真实主帧缓冲”的做法** —— 它牵动全局状态、兼容性风险大。高清截图现在**只动副管线自己的目标**：预热帧与快门帧都渲染进副管线自己的离屏目标（主帧缓冲全程保持窗口尺寸）。真正的根因也从副管线侧修掉了：原版**实体描边后期处理器**的缓冲按窗口尺寸创建、它的 pass 会设置“自己缓冲尺寸”的视口且渲染完不还视口（`WorldRenderer.render` 里 `entityOutlinePostProcessor.render(...)` 之后只 `beginWrite(false)`），紧接着画的手部就按窗口分辨率落位；现在这一遍用 `WorldRenderer.onResized(目标尺寸)` 把这些后期缓冲一起调到目标尺寸，`ViewportGuard` 退化为保险（命中次数写日志、预期 0）。② **像素总量不再是硬上限**：超过约 33M（7680×4320）只在界面与日志里**警告**，仍然按玩家填的尺寸渲染（失败或极慢由玩家自行承担）；单边区间放宽到 `64 ~ 16384`（后者是 GL 常见的 `GL_MAX_TEXTURE_SIZE`） |
| **0.2.3** | —— | 修 0.2.2 高清截图的两处问题并把输入方式做对：① **手部错位 / 实体看不见** —— 高清档位原来把副管线渲染进一块更大的离屏目标，实测那一遍里有人把 `glViewport` 设回了**窗口尺寸**，于是手部只按窗口那么大、还挤在左下角，实体被地形挡住。现在快门那一帧改成把**真实主帧缓冲临时放大**再画（与「跟随窗口」同一条已验证路径），并新增 `ViewportGuard`（挂在 `GlStateManager._viewport`）把“设回窗口尺寸”的视口改写成目标尺寸、把改写次数写进日志；预热帧也改成离屏渲染且不画手部（避免把错的画面写进光影包的时域历史）。② **分辨率输入改成两个文本框**（宽 / 高）+ 两个按钮（按窗口宽高比补全高度 / 补全宽度），配置字段由 `screenshotLongEdge` 换成 `screenshotWidth` / `screenshotHeight`，并加上像素总量上限（≈33M）与单边 64~8192 的夹取 |
| **0.2.2** | —— | 三件事：① **删掉「渲染模式」这套设置**（只保留“编译好、资源就绪、平时不渲染”这一种行为）——「每帧 / 每 N 帧」两档实测很少用到，连带删除「帧间隔」「开界面时暂停副渲染」两个选项与全部关联代码（`mode` / `intervalFrames` / `skipWhenScreenOpen`）。副管线现在只在“新管线建好后补渲染一帧”与「副管线截图」时渲染，不做截图就完全不产生 GPU 开销。② **删掉截图“不闪屏 / 不切屏”的描述**：那是相对最初那版“切屏拍摄”说的；现在两包材质表不一致时，重建期间屏幕上的地形确实会跟着换成副包的材质分类，文档与界面文案照实说明。③ **新增「副渲染截图分辨率」**（`screenshotLongEdge`，档位：跟随窗口 / 1280 / 1920 / 2560 / 3840 / 5120 / 7680 长边）：副管线那一遍渲染进模组自己的离屏目标，与游戏窗口分辨率完全独立，主画面的分辨率与内容一个像素都不动 |
| **0.2.1** | —— | 把截图等待期间的「**无渲染帧取消阈值**」从写死的 100 tick 做成**可调项**（设置界面「副管线截图」一节，左键 +1 档 / 右键 -1 档，`100 / 200 / 400 / 600 / 1000 / 2000 / 4000 / 8000 / 12000 / 16384 / 24000 / 32767` tick ≈ 5 秒 ~ 27 分钟），配置字段 `noFrameTimeoutTicks`（越界自动夹回 100 ~ 32767）。触发时日志写明计数与阈值。这不是超时回落（0.2 已删），只是“一帧都没渲染出来”时的收尾；调大便于调试 |
| **0.2** | —— | 三件事：① **严格限定“截图副管线性能调度”的作用域** —— 玩家实测发现该档位不仅拖慢截图取景那一轮的重建，还拖慢了 **F7 主副互换后新管线的区块更新速度**，而后者与截图无关。现在只有**截图取景**那一轮按档位分批投递，F7 互换与截图后的材质还原一律一次性投完。② **该设置改名并去掉“调度解析/编译”的部分**：解析与着色器编译都是必须在渲染线程上完成的原子操作，围着它们做档位是名不副实的，相关代码整体删除；按钮由「预编译调度」改为「截图副管线性能调度」，只保留对投递区块数的影响。③ **删除截图流程的「超时回落」**（`SETTLE_FALLBACK_MILLIS = 12 s` / `HARD_TIMEOUT_MILLIS = 60 s`）：有了进度条之后玩家能看到还要等多久，这条兜底已无实用意义 |
| **0.1.17** | —— | 两件事：① **「后台预编译」改成五档调度策略**（无感后台 / 低负载 / 中负载 / 高负载 / 实时）——解析与编译必须在渲染线程且是原子的，所以“后台”只能挑时机（暂停菜单、开着界面、帧率够）并把两段拆到不同 tick；低档位下前台不再感到卡顿。区块重建也改成**按策略预算分批投递**（以前一次性排十几万个区块段会打出明显的 CPU 尖峰），解包线程优先级降到最低。② **副管线截图进度提示**：取景期间 HUD 显示“区块段 已做/总数（百分比）+ 进度条 + 预热帧数 + 已等待时间”，三个阶段（重建地形 → 预热画面 → 即将按快门）依次提示。③ 按玩家实测结论**更正“概率黑屏”的解释**：那是 MFU 这类包自身的“人眼适应”显示策略（需要连续渲染 10~20 帧才亮起来），与模组无关；新增 `取景预热帧数`（默认 20）在按快门前先渲染这么多帧 |
| **0.1.16** | —— | 定位并修「F7 切换后手部与实体消失」的**真正根因**：预编译出来的那条副管线里，**部分着色器程序链接失败**（玩家实测日志：`GLSL link failed ... Out of resource error`、`Error encountered when linking program containing VS sky_textured and FS sky_textured`，Intel 核显 + iterationT 这类大包），而 Iris/原版只打一条 WARN 就继续用——用未链接的程序绘制等于什么都不画，切过去后手部/实体（连同天空）就没了。这也解释了此前的怪规律：**同一个主副组合会 ✓✗✓✗ 交替**（坏的是某一个*管线实例*）、**按 R 重载必然恢复**（重新编译后链接成功）。现在预编译完成与切换前都检查 `GL_LINK_STATUS`：不健康就**释放后重试一次**，仍不健康就**放弃这条管线并明确告知原因**，绝不把坏管线提升为主管线；兼容性自检新增「程序链接」一行 |
| **0.1.15** | —— | 修「主管线是空包、副管线是别的光影时，按 F7 主副切换后实体不渲染」：根因是**实体顶点格式与实体着色器分属两处独立判断**（顶点按扩展格式写出，着色器却可能落回原版），一旦错配实体的位置/UV/法线全部错位，表现就是实体消失。新增**实体着色器安全网**（错配则改用当前管线的实体程序）与**幽灵渲染的完整状态屏障**（深度/颜色写入锁、手部渲染标志），并把这两项纳入兼容性自检 |
| **0.1.14** | —— | 修 0.1.13 把 F2 改坏的问题（死锁）：`renderedOnce`（副管线先渲染过一整帧）被写成了*取景条件*，而预热帧本身又在取景入口里 —— 于是「仅预编译」模式下永远等不到、按 F2 毫无反应。现在把预热帧移出取景判定：只要在等截图就先渲染一帧预热，之后才真正判取景条件 |
| **0.1.13** | —— | 回退 0.1.12 的「绑定原生管线」：它会让每次切进/切出都**整世界重建**（原生顶点格式与光影包网格格式不同），感知极明显，玩家判定不可接受；保留“空包 + Iris fallback”的做法，并把包与相关文案统一改名为**「空包」**，直说它接近但不是原版（旧目录 `shaderpacks/原版` 会被自动改名）；副管线截图**删掉黑屏检测/重拍**，改为从取景时机解决（副管线必须先渲染过一整帧才开始取景 + 地形重建稳定 500 ms 后再拍），剩余不确定性写进 [`docs/03`](docs/03-限制与风险.md) 的已知问题 |
| **0.1.12** | —— | 「原版」改为绑定 Iris 的**真·原生管线**（`VanillaRenderingPipeline`，即“光影关闭”那条）——名字与实际终于一致，不再用 fallback 近似；修「副管线截图有概率得到纯黑图」：抓到的画面整张纯黑时不落盘、稍后重拍（最多 5 次），并补上“顶点格式不同 → 整世界重建”的等待与还原；主管线是原生管线时也照常渲染副管线那一遍（「原版 ↔ 光影包」秒切） |
| **0.1.11** | —— | 新功能「**原版光影包**」：模组在 `shaderpacks/` 下生成一个叫 `原版` 的目录型光影包（不提供任何程序 → Iris 为每个程序合成原生等价的 fallback 着色器、Sodium 退回自带原生等价地形程序），于是 Iris 的选包界面与本模组的副管线列表里都多出这一项，「原版」可以像普通光影包一样被 F7 秒切、被副管线截图、被自检；设置界面新增开关 `vanillaPackEntry` |
| **0.1.10** | —— | 修「切到 MakeUp 时闪退」：Iris 的阴影渲染目标是懒创建的，而 Sodium 的地形程序是懒编译的，Iris 那句 `requireNonNull(shadowRenderTargets)` 对 MakeUp 这类包不成立 → 我们在构造副管线时把阴影目标补齐；修「副管线截图内容不对」：改成在**帧首用副管线渲染进主帧缓冲**再读像素（和 F7 切过去走完全相同的路径），不再读离屏 FBO（实测那条路会拿到只有天空/未走完 final 的画面）；预编译改为「解包进后台，解析与编译留在渲染线程」（实测 Iris 解析期会做 GL 查询，后台解析对三个真实光影包无一成功） |
| **0.1.9** | —— | 删除「兼容 GLSL 4.x 保留字」功能（含源码/界面/缓存格式）；副管线预编译改为后台（解包 + 解析进工作线程，界面显示进度，F7 可登记“编译完自动切换”）；副管线截图改为**完全不切屏**（直接读离屏 FBO），并会先切材质表、等地形重建完再拍，仅预编译模式也会主动拉起那一遍渲染；设置界面重做（可滚动 / 窄窗口自适应 / 分节 / 帧间隔左键 +1 右键 -1），删除「截图层数」 |
| **0.1.8** | —— | 副管线截图改为直接读离屏帧缓冲（不再切屏闪一帧）；截图期间强制渲染幽灵那一遍（仅预编译模式也能拍）；包准备流程不再做任何内容改写 |
| **0.1.7** | —— | 删除 0.1.5 的包改写（MakeUp 恢复可加载）；副管线缓存戳记升到 v3；截图等待改为「先忙后空」三阶段判定 |
| **0.1.6** | —— | 修正 0.1.5 的错误结论（实体花斑与 MakeUp 无关）；改为让副管线在「Iris 认为当前包就是它」的条件下构造；修副管线截图：此前 `isTerrainRenderComplete()` 在任务入队前恒为真导致「等待」失效，且截图那次切换根本不换材质表 |
| **0.1.5** | —— | 定位并修「MakeUp 这类老包当主管线时实体花屏」：老包用浮点 `mc_Entity` 读 Iris 的整数实体 ID 属性，读到乱码；在**副本**上按程序类型改用 Iris 的同名 uniform |
| **0.1.4** | —— | 修「切换后地形替换过慢」：重建任务此前按普通优先级投递（`addLast`）被排到队尾；改为高优先级 + 由远到近投递；副管线截图会等重建完成再拍；幽灵渲染前后显式保存/还原 Iris 的即时状态（顶点布局标志） |
| **0.1.3** | —— | 修「F7 切换后材质 ID / 地形着色器不同步」：区块重建一直用旧包的方块 ID 表（植被不摇动）、地形着色器把「分离 AO」烘焙进源码导致切换后错配 |
| **0.1.2** | —— | 修 0.1.1 引入的崩溃：`IllegalStateException: Pose stack not empty`（启用双管线后进世界即崩）；改为“另建入口姿态的栈”实现同一修复 |
| **0.1.1** | —— | 修 4 个正确性问题：幽灵渲染视角叠加、截图后视锥体不复原、`rebuildAfterIrisReload` 未生效、离屏 FBO 不释放 |
| **0.1** | 1.4.1（含 1.0.0 ~ 1.4.1 的全部改动） | 首个对外版本：双管线 + 秒切 + Mod Menu 设置 + 副管线截图 |

## 0.3.2 —— 修 JPG 模式；删除“进世界自动预编译”

### ① JPG 模式下截图生成失败

玩家实测：开了 JPG 模式后截图不落盘，日志里是

```
java.lang.ArrayIndexOutOfBoundsException: Index 43332033 out of bounds for length 43332033
    at dev.shaderflash.JpegScreenshot.toBufferedImage(JpegScreenshot.java:88)
```

**根因**：1.20.4 的 `NativeImage.getBytes()` **不是原始像素缓冲**，而是**把图像编码成 PNG**
之后的字节（字节码：`ByteArrayOutputStream` → `write(WritableByteChannel)` → 失败时读
`STBImage.stbi_failure_reason()`）。0.3.1 按“原始 RGBA 像素”去索引它，索引必然越界；
同一尺寸两次的数组长度不同（43332033 / 42659380）也正好说明那是 PNG 压缩结果。

**修法**：换成「PNG 字节 → `ImageIO.read` 解码 → 统一成 `TYPE_INT_RGB` → JPEG 编码」。
好处是完全不用猜通道顺序（`Format.RGBA` 的字节布局、alpha 合成都由 ImageIO 处理），
编码/解码仍在 IO 工作线程上做。已离线验证该路径（640×360 的 RGBA PNG → 11 KB JPEG，
颜色方向正确）。

### ② 删除“进世界自动预编译副管线”

0.3.1 已从全局材质状态的角度修过一次，玩家实测仍能在该路径上遇到实体渲染不出来。
按玩家要求不再尝试修它，直接把这条链路删掉，让“必须手动预编译”来规避：

* 删除开关 `autoBuildOnWorldJoin`（界面上的「进世界自动预编译」）与
  `rebuildAfterIrisReload`（JSON-only 的「重载后自动重建」）；
* 删除 `worldAutoBuildPending` / `reloadAutoBuildPending` 两个标志与
  `onClientTick` 里那段“自动建/重建”判断；只为它服务的 `tickCounter` /
  `nextBuildAttempt` 退避字段一并清掉；
* `onIrisPipelinesDestroyed()` 只**释放**副管线，日志改为
  `Iris 已重载；副管线已释放，需要时请手动预编译`；
* **换维度**同样不再自动重建：释放旧维度的副管线 + 提示
  `换了维度，已释放旧维度的副管线；需要时请手动预编译一次`。

副管线现在只有三种**玩家主动**的构建时机：设置界面的「预编译 / 重建副管线」按钮、
按 F7 时若没有可用副管线（就地构建）、打开双管线总开关 —— 这些都发生在世界跑起来之后，
正是实测中一直正常的那个时间窗。

## 0.3.1 —— 修“进世界预编译的管线不能渲染实体”；新增 JPG 模式

### ① 进世界自动预编译 vs F8 手动预编译：差异在“编译时主管线有没有就绪”

玩家实测：进世界自动预编译出来的副管线**不能渲染实体**，而在游戏里用 F8 手动重建同一条管线就正常。
两条路径其实调用的是同一个 `ensureSecondary(...)`，唯一区别是**运行时机**：

* `ensureSecondary` 只要求 `Iris.getCurrentPack()` 有值（Iris 解析完包就有），
  **不要求主管线已经构造**；而各维度管线是懒加载的（第一次渲染那个维度时才 `new`）；
* 进世界 / Iris 刚重载时，模组在“下一 tick”就开跑，那一刻 `WorldRenderingSettings` 里可能还是默认值：
  `blockStateIds=null`、`entityIds=null`、`useExtendedVertexFormat=false`；
* 旧代码在构造副管线之后 `snapshot.restore()`，把这些**默认值**原样写回全局；
  而 Iris 只在“新建管线”时设置它们 → 主管线之后一直用错误的值渲染：
  实体顶点按**原版格式**写出、着色器却按**扩展格式**读属性 → 实体消失
  （`EntityShaderGuardMixin` 管的是反方向：扩展顶点 + 原版着色器，救不了这一种）；
* 手动路径那一刻全局值一定是主管线那一份，快照 = 当前值，`restore()` 等于空操作 —— 所以它没事。

**修法**：改成 `restorePrimarySettings(...)` —— 按**主管线的包**重放一遍全局材质设置
（`WorldSettings.applyFor(primaryPack, pipelineOrNull, dimension, false)` + `clearReloadRequired()`），
与构造先后无关；`WorldSettings.applyFor` 现在允许主管线尚未构造（方向性明暗按包的
`directives.isOldLighting()` 计算）。另外加了一道不变量安全网
`keepExtendedVertexFormat()`：光影管线下该值必须为 true，被写坏就地校正并打一条 WARN。

细节与验证方法见 [`docs/19-排查记录-进世界预编译与手动预编译的差异.md`](19-排查记录-进世界预编译与手动预编译的差异.md)。

### ② 新增「JPG 模式（压缩截图）」

配置字段 `screenshotJpg`（默认关），设置界面「副管线截图」一节里的开关：

* 关（默认）＝ 与 vanilla 一致的无损 PNG；
* 开 ＝ JPG（质量 85%，后缀 `.jpg`），体积明显更小，代价是有损压缩；
* 两者像素来源完全相同（同一个 `ScreenshotRecorder.takeScreenshot`），只是换了编码器
  （`JpegScreenshot`，JDK 的 JPEG writer）；编码与写盘在 `Util.getIoWorkerExecutor()` 上做，
  渲染线程只负责读像素，所以大图也不卡游戏；完成提示带“点开文件”点击事件。

### ③ 两处中文笔误

按玩家要求修正 `NOTE_KEYS`（“修改改”→“修改”）与 `NOTE_SCREENSHOT_RESOLUTION_WARN`
（“崩溃败”→“崩溃”），第三处（括注后缺标点）保持原样。

## 0.3 —— 中文润色定稿；英文按新中文重写

玩家完成了 `ModTexts.java` 的中文润色（表述更贴近实际行为、删掉了过时的解释，例如原来的
“无渲染帧取消阈值”改成“最大静默等待时间”、五档调度名改成“极低/低/中/高/最大负载”）。
本次按润色后的中文重写英文，并只做这些改动：

* **30 条英文重写**：覆盖被打磨过的说明、聊天提示、状态行、自检输出与五档名称；
* **1 处功能性修正**：`DIAG_PACK_PROGRAMS` 的中文多了一个 `%s`（调用点只传 4 个参数），
  会导致自检那一行显示成原始模板 —— 去掉多余的占位符；
* **文档同步**：README 与 `docs/` 里引用的界面名称（五档名、阈值名）改成润色后的说法。

技术逻辑零改动（没有碰任何渲染/调度代码）。`ModTexts` 的占位符审计（中文 / 英文 / 调用点
三方对齐）已跑通：150 条常量、96 个调用点、0 处不匹配。

## 0.2.6 —— 玩家可见文本集中到 ModTexts.java（编译期引用）

### ① 一个文件装下所有文本

新增 `source/src/main/java/dev/shaderflash/ModTexts.java`：**150 条**双语常量，覆盖

* 设置界面（标题、分节、选项、按钮、说明、取值文案）；
* 聊天框提示与附加建议；
* HUD 状态与截图进度块；
* 「兼容性自检」的全部输出；
* 状态行的失败原因、自检里的判读用词；
* 「截图副管线性能调度」五档名称、预编译阶段名。

每条都是：

```java
public static final LText MESSAGE_SWAPPED = new LText(
	"已切换到 %s，耗时 %s ms（重新编译这条管线本来要 %s）",
	"Switched to %s in %s ms (a fresh compile would have cost %s)");
```

### ② 调用全部改成编译期引用

| 之前 | 现在 |
| --- | --- |
| `Text.translatable("shaderflash.message.swapped", a, b, c)` | `ModTexts.text(ModTexts.MESSAGE_SWAPPED, a, b, c)` |
| `Text.translatable("shaderflash.status.cost", x).getString()` | `ModTexts.str(ModTexts.STATUS_COST, x)` |
| `Text.translatable(key, Text.translatable(on/off))` | `ModTexts.join(label, ModTexts.onOff(value))` |
| `Text.translatable("shaderflash.policy." + key)`（拼字符串） | `ModTexts.policy(index)`（switch） |
| `job.phase = "shaderflash.prewarm.phase.unpack"` | `job.phase = ModTexts.PREWARM_PHASE_UNPACK` |

于是**键名写错会直接编译不过**，“游戏里显示成一串翻译键”这类问题从根上没有了。
占位符个数写错也不崩：日志一条 WARN + 界面按原文显示（`ModTexts.str` 里的兜底）。

### ③ 有意保留的两个例外

* **按键文本**（4 条）留在 `assets/shaderflash/lang/*.json`：Minecraft 的按键系统按翻译键解析它们；
* **日志文本**（`[ShaderFlash] …`，69 条）留在代码里：那是排查用的技术描述（类名/数字/耗时）。

改法与边界见 [`docs/18-文本与本地化.md`](docs/18-文本与本地化.md)。

## 0.2.5 —— 「初始化渲染」做成可回退的调试开关

玩家想验证一件事：新管线建好后补渲染那一帧，是否真的必要？
那一帧要付出一整遍世界渲染的代价（预编译刚结束时会卡一下），如果去掉，
副管线就能“零帧就绪”。于是把它做成配置页里的调试开关，出问题一键回退。

|  | 开（默认） | 关（试验） |
| --- | --- | --- |
| 新管线建好后的行为 | 尾部补渲染一帧（离屏），时长写进日志 | 什么都不画，日志写“已按调试开关跳过” |
| 好处 | F7 切过去第一帧正常；地形程序已就绪 | 省掉一整遍世界渲染，预编译刚结束时更顺 |
| 代价 | 多一遍世界渲染的开销 | ① 第一次切过去的第一帧可能是黑的（Iris 的 composite/final 翻转状态未就绪）；② 第一次切换要现编译 Sodium 地形程序，多一次卡顿 |

其它：

* 状态区（HUD / 设置界面）会显示「初始化渲染：开/关」，关掉且尚未渲染过时会补一句
  「已跳过，尚未渲染过」，方便随时确认开关是否生效；
* 关掉这一帧**不会**引发整世界区块重建：副管线构造时就已经把 Iris 的
  `initializedBlockIds` 预置为 `true`（见 `IrisRenderingPipelineAccessor` 的说明）；
* 取景路径不受影响：按 F2 时本来就会先渲染 `screenshotWarmupFrames` 帧（那几帧同时也让
  “这条管线至少渲染过一整帧”成立），所以关掉开关后截图仍然正常。

## 0.2.4 —— 高清截图只动副管线；像素总量改成警告

### ① 删掉“临时放大真实主帧缓冲”

0.2.3 为了让手部落在正确的视口里，把**真实主帧缓冲**临时放大到目标尺寸再渲染。
玩家指出这会牵动全局状态、有潜在兼容性问题，要求改成**从副管线本身想办法**。

现在高清档位的两遍（预热 + 快门）都渲染进**副管线自己的离屏目标**，
真实主帧缓冲全程保持窗口尺寸、一个像素都不动。

### ② 根因从副管线侧修掉：原版后期缓冲也要按目标尺寸

重新追了一遍视口来源，真凶是**原版的实体描边后期处理器**
（`WorldRenderer.entityOutlinePostProcessor`）：

* 它的缓冲在资源加载时按**窗口尺寸**创建；
* 它的 pass（`PostEffectPass.process`）会 `RenderSystem.viewport(0, 0, 自己缓冲尺寸)`；
* 原版渲染完**并不还视口** —— `WorldRenderer.render` 里那段是
  `entityOutlinePostProcessor.render(...)` 紧接着 `getFramebuffer().beginWrite(false)`，
  而 `beginWrite(false)` 不设视口；
* 手部正好在这之后由 `GameRenderer.renderHand` 画 → 于是按窗口分辨率的视口落位，
  在高清图上表现为“手部只有窗口那么大、挤在左下角”。

修法：副渲染那一遍用 `WorldRenderer.onResized(目标尺寸)` 把这些后期处理缓冲一起调到
目标尺寸（渲染完再调回窗口尺寸），它们的 pass 设置出来的视口自然就是目标尺寸。
`ViewportGuard` 因此退化为**保险**：命中就改写并写日志，预期是 0 次。

### ③ 像素总量：从硬上限改成警告

超过约 33M 像素（7680×4320）时只在设置界面和日志里**警告**，不再裁剪尺寸 ——
超量渲染是玩家的选择，风险由玩家承担。单边区间放宽到 `64 ~ 16384`
（16384 是 GL 常见的 `GL_MAX_TEXTURE_SIZE`）；目标建不出来时（显存不足 / 超过 GL 上限）
这一次截图会退回窗口分辨率并在日志里说明。

## 0.2.3 —— 修高清截图的手部/实体错位，输入改成填宽高

### ① 症状与根因：那一遍的视口被设回了窗口尺寸

玩家实测（0.2.2）：高清档位拍出来的图里**手部只有窗口那么大、还挤在左下角**，
而且**看不到实体**（按 F7 把副管线切成主管线时一切正常）。

把 7680×4742 的图与同场景的窗口分辨率图逐像素比对后可以定量确认：手部/手持物在图里的
位置与像素尺寸，正好等于「用窗口尺寸的视口、锚在左下角」画出来的结果 ——
也就是说副渲染那一遍里，**渲染途中某处把 `glViewport` 设回了窗口尺寸**，
之后画的东西（实体、手部）全按窗口分辨率落位：实体因此被地形深度挡住，手部则位置和大小都不对。

### ② 修法

* **快门那一帧改回主帧缓冲**：把<b>真实主帧缓冲临时放大</b>到目标尺寸再渲染、读像素、还原
  —— 与「跟随窗口」完全同一条路径（手部/实体本来就正确），只是分辨率不同。
  读像素被放进这一遍内部（`shoot` 参数），保证发生在尺寸还原之前。
* **视口护栏**：新增 `ViewportGuard` + `ViewportGuardMixin`（挂在 `GlStateManager._viewport`，
  `RenderSystem.viewport` 与 `Framebuffer.bindWrite(true)` 都经过它）：副渲染那一遍里
  若有谁把视口设成“窗口尺寸”，就把它改写成这一遍真正的目标尺寸；其它尺寸不动。
  改写次数写进日志，**护栏能自证有没有命中**。
* **预热帧**：离屏渲染（不动主帧缓冲）、尺寸与快门一致（避免 Iris 重建渲染目标而清掉
  光影包的“人眼适应”历史），并且这一遍**不画手部**（`GameRendererAccessor` 临时关掉
  `renderHand`）—— 手部对时域适应没有意义，画错位置还会留在历史缓冲里变成鬼影。

### ③ 分辨率输入：两个文本框 + 两个补全按钮

原来是一个“长边像素数”的档位按钮，改成：

| 控件 | 作用 |
| --- | --- |
| 文本框「截图宽度」 | 目标宽度（像素）；留空 = 0 = 跟随窗口 |
| 文本框「截图高度」 | 目标高度（像素）；留空 = 0 = 跟随窗口 |
| 按钮「按窗口宽高比补全高度」 | 用宽度 + 窗口宽高比算出高度并填入 |
| 按钮「按窗口宽高比补全宽度」 | 反过来 |

配置字段 `screenshotLongEdge` → `screenshotWidth` / `screenshotHeight`；
只填一边时另一边在计算目标尺寸时也会按窗口比例自动推出。单边夹在 64 ~ 8192，
像素总量上限 ≈33M（7680×4320）；超了按比例缩下来。

## 0.2.2 —— 只留一种渲染行为、删掉“不闪屏”的说法、加入高清副渲染截图

### ① 删掉「渲染模式」（只保留“平时不渲染”）

0.1.x 有「每帧 / 每 N 帧 / 仅预编译」三档渲染模式。玩家实测下来，除了“仅预编译”，
另外两档几乎不会被用到，而它们带来的复杂度不小（每帧多渲染一整遍世界、按帧计数节流）。

0.2.2 把这三档连同附属设置一起删掉：

| 删除的东西 | 位置 |
| --- | --- |
| 渲染模式按钮（`mode`） | `DuoConfig` / `DuoConfigScreen` / `DuoManager.shouldRenderThisFrame()` |
| 帧间隔按钮（`intervalFrames`） | 同上（含 `INTERVALS` 档位表与 `modeName()`） |
| 开界面时暂停副渲染（`skipWhenScreenOpen`） | 同上（含 `onWorldRenderTail` 里的判断） |
| 周期性的“幽灵渲染” | `onWorldRenderTail` 改成**只在管线刚建好时补渲染一帧** |
| 对应的界面文案与语言键 | `shaderflash.option.mode` / `option.interval` / `option.skip_screen` / `status.mode` |

保留下来的那一次离屏渲染不是可有可无的：刚构造好的管线第一遍渲染时，Iris 的
composite/final 翻转状态还没就绪，补这一帧可以保证 F7 切过去的第一帧不会黑，
同时也满足截图取景“这条管线至少渲染过一整帧”的前置条件。

净效果：**不做副管线截图时，副管线完全不产生 GPU 开销**（只占一份显存）；
F7 切换依然只是交换指针。

### ② 删掉截图“不闪屏 / 不切屏”的说法

这句话是相对模组最初那版“把屏幕切到副管线拍一张再切回来”说的。经过多轮迭代之后，
现实是：**取景那一帧确实看不到**（帧首渲染、随后被正常渲染覆盖），
但**两包材质表不一致时，重建期间屏幕上的地形会跟着换成副包的材质分类** ——
这是单套区块网格的固有限制。README、`docs/02`、`docs/08`、界面文案与日志里的
“不闪屏 / 不切屏 / 不阻塞”措辞已按实际情况改写。

### ③ 新增「副渲染截图分辨率」（高清截图）

配置字段 `screenshotLongEdge`，设置界面「副管线截图」一节：

| 档位 | 实际尺寸 |
| --- | --- |
| 跟随窗口（默认） | = 游戏窗口分辨率（渲染进主帧缓冲，与旧版行为一致） |
| 1280 / 1920 / 2560 / 3840 / 5120 / 7680 | 长边取该值，短边按窗口宽高比换算（取偶数） |

做法：取景那一遍把模组自己的离屏目标调成目标尺寸，并让 `Window` 在**这一次渲染期间**
报告目标尺寸（原版的视口/投影都取自 `Window`，否则只会画满目标的一小块），渲染完立刻还原。
Iris 会按“当前主目标”的尺寸重建这条管线自己的渲染目标，所以拿到的是真·目标分辨率渲染。

* **主帧缓冲一个像素都不动**：主画面的分辨率与内容不受影响；
* 预热帧与快门用同一分辨率（尺寸变化会清掉光影包累积的“人眼适应”历史）；
* 代价：副管线要按目标尺寸多占一整套渲染目标，分辨率越高越明显（核显建议 1920~2560）；
* 目标尺寸与真实尺寸都会写进日志与 HUD，聊天提示也会带上 `宽×高`。

细节见 [`docs/08-功能-副管线截图.md`](docs/08-功能-副管线截图.md)。

## 0.2.1 —— 「无渲染帧取消阈值」做成可调项

0.2 删掉超时回落之后，截图等待只剩一条收尾分支：**连续多少个 tick 一帧都没渲染出来**
（窗口最小化 / 暂停 / 渲染卡死）就取消本次截图并还原材质表。它原本写死 100 tick（≈5 秒），
实测调试时太容易踩到 —— 切一次窗口、加载卡一下就可能把等待打断。

于是把它做成设置界面「副管线截图」一节里的可调项（左键 +1 档、右键 -1 档）：

| 档位 | 100 / 200 / 400 / 600 / 1000 / 2000 / 4000 / 8000 / 12000 / 16384 / 24000 / 32767 tick |
| --- | --- |
| 折算 | ≈5 秒 ~ ≈27 分钟（20 tick = 1 秒） |

* 配置字段 `noFrameTimeoutTicks`，默认 100；越界值会被夹回 `100 ~ 32767`（手工改 JSON 也安全）；
* **只决定“收尾”什么时候发生**：它不改变取景时机，也不是超时回落 —— 只要还在出帧，
  就仍然一直等到地形重建稳定（进度条会显示到哪一步）；
* 触发时日志写明当时的计数与阈值：`连续 N tick 没有任何渲染帧（阈值 M tick），已取消`。

## 0.2 —— 把「截图副管线性能调度」关回截图里、删掉超时回落

0.1.17 把「后台预编译」做成了五档策略，顺带让这套档位去影响区块重建的投递节奏。
玩家实测后发现两个问题，0.2 一并处理。

### ① 作用域必须严格限定：F7 互换不该被拖慢

现象：把档位调到「最平滑」，按 F7 主副互换之后，**新主管线的区块更新明显变慢**。
可这条路径跟截图没有任何关系 —— 玩家按下 F7 就是马上要看结果，没有理由跟着一个
“截图性能”设置一起变慢。

改法（`DuoManager` / `SodiumRemesh`）：

| 路径 | 0.1.17 | 0.2 |
| --- | --- | --- |
| 副管线截图：取景前切到副包材质 | 按档位分批投递 | **按档位分批投递**（唯一受影响的路径） |
| F7 主副互换后的重建 | 按档位分批投递 | 一次性投完（`Integer.MAX_VALUE`） |
| 截图结束后还原主包材质 | 按档位分批投递 | 一次性投完 |

实现上给 `SodiumRemesh.prepare(client, batched)` 加了 `batched` 参数，
只有截图取景那一处传 `true`；每 tick 的投递循环里，`isPendingBatched()` 为假就直接
`submitBatch(Integer.MAX_VALUE)`。

### ② “预编译调度”名不副实 → 改名并删除对应代码

解析（`new ShaderPack(...)`）与编译（`new IrisRenderingPipeline(...)`）都必须在渲染线程上、
且都是不可分解的原子操作（Iris 的管线构造函数里直接调 GL），围着它们做“低负载/高负载”档位
并不能真的把卡顿挪走。0.2 删掉 `mayRunHeavyStep(...)` 判定、`parsedTick` 与“解析与编译拆到
两个 tick”的逻辑；设置项改名为 **「截图副管线性能调度」**（配置字段 `prewarmPolicy` →
`remeshPolicy`，界面文案、日志与说明同步更新）。

### ③ 删除截图的「超时回落」

旧流程有一条“等太久就按当前画面拍”的兜底（12 秒回落、60 秒硬上限）。
0.1.17 已经加了截图进度条，玩家能看见“还在重建 / 还在预热”，这条兜底只会拍出半成品，
于是整体删除：`shouldShootNow()` 未稳定时**返回 false 一直等**，`SETTLE_FALLBACK_MILLIS`
与 `HARD_TIMEOUT_MILLIS` 及对应分支都不再存在。保留的只有“连续 100 tick 没有渲染帧
（暂停 / 最小化）就取消本次截图并还原材质”的保护。

细节见 [`docs/17-功能-截图副管线性能调度与截图进度.md`](docs/17-功能-截图副管线性能调度与截图进度.md)。

## 0.1.13 —— 回退原生管线、改名「空包」、从取景时机解决黑屏

### ① 回退：不把「原版」绑到原生管线（代价是整世界重建）

0.1.12 让「原版」绑定 Iris 的 `VanillaRenderingPipeline`（= “关闭光影”那条），名字确实与画面统一了，
但玩家实测反馈：**每次切进/切出它都会整世界重建区块网格、感知极其明显**。原因是原生管线用原版的
顶点格式（`useExtendedVertexFormat=false`），和光影包网格的格式不一致 —— 顶点格式在建网格时就定死了，
只能整世界重建，这个代价不可接受。

所以回退成 0.1.11 的做法（空包 + Iris fallback 着色器，两边格式一致、切换零重建），
并把包与全部文案统一改名为 **「空包」**，直说它“接近但不是原版渲染”：

* `VanillaPack.NAME`：`原版` → `空包`；旧目录 `shaderpacks/原版` 若确认是本模组生成的，
  启动时会自动改名为 `shaderpacks/空包`（玩家自己的同名包不会被动）；
* 副管线列表标注：`◆ 空包（空包：原生等效，非原版）`；设置界面的说明也改成“想完全原生请在 Iris 里关掉光影”；
* 删掉 0.1.12 新增的 `IrisPipelineFactoryMixin`、`WorldSettings` 里的原生管线分支、
  取景路径里的“顶点格式不同 → 整世界重建”，主管线是 `VanillaRenderingPipeline` 时仍按老规矩跳过副渲染。

### ② 副管线截图黑屏：删掉“检测黑图再重拍”，改从取景时机解决

0.1.12 的“抓完检查是否纯黑、黑就重拍”被认为针对性过强、可维护性差，已删除
（存盘回到原版 `ScreenshotRecorder.saveScreenshot`，命名/提示与 F2 原生行为一致）。

重新对照日志后，黑屏那一张有两个明确的特征（见下表），据此改成两条**取景前置条件**：

| 截图 | 请求→完成 | 副管线此前是否渲染过 | 结果 |
| --- | --- | --- | --- |
| 23:15:51 | 3446 ms | 渲染过（当主管线跑过 5 s） | 正常 |
| 23:16:47 | 3041 ms | 渲染过 | 正常 |
| 23:17:37 | **1757 ms**（几乎刚到最小等待） | **没有**（管线 23:17:31 才建好） | **纯黑** |

1. **副管线必须先渲染过至少一整帧**才开始取景：刚构造好的管线第一遍渲染时，
   Iris 的 composite/final 缓冲翻转状态还没跑起来，那一帧可能整张是黑的
   （`PackSlot.renderedOnce`；取景那一遍会让它渲染起来，所以等价于“第一次取景只当预热，
   第二次才真拍”，多花一帧的代价）；
2. **地形重建“稳定”500 ms 之后再拍**：`isRemeshSettled()` 刚翻成 true 的那一刻，
   可能只是视距内那一批区块刚建完，再等半秒能避免抢拍。

仍然解释不了的部分（例如“黑屏是不是只由第一帧引起”）如实记在
[`docs/03-限制与风险.md`](docs/03-限制与风险.md) 的已知问题里，不再用事后检测去掩盖。

## 0.1.12 —— 「原版」改成真·原生管线 + 修副管线截图黑屏

### ① 「原版」名实统一：直接绑定 Iris 的原生管线

玩家实测「空包」（= 不含程序、走 Iris fallback 着色器）的画面与真正关闭光影的观感有出入，
因此要求要么改名、要么做出“真正的原版”。

**为什么不能把原版 MC 的 core shader 文件原样抽成一个光影包**：那不是“抽取”，而是**重写**。
原版 1.20.4 的 core shader（`assets/minecraft/shaders/core/*.json + .vsh/.fsh`）用的是
GLSL 150 + `Sampler0/ModelViewMat/...` 这类由 JSON 声明的 uniform、以及 `gl_VertexID` 取属性的
顶点模型；而 Iris 的光影包程序用的是另一套约定（`gbuffers_*` + `vaPosition/vaColor/texture/...`，
地形在 Sodium 下更是 Iris 自己的顶点格式）。把原版 core shader 原样拷进包里既编译不过，
也拿不到原版渲染 —— 想要“真正的原版”只能重写全部程序，工作量巨大且必然比 Iris 自带 fallback
更不准。

**改法**：让「原版」这一项直接绑定 Iris 自己的**原生管线**
（`VanillaRenderingPipeline` —— 就是玩家“把光影关掉”时用的那条）。新增
`IrisPipelineFactoryMixin`：当当前包名是「原版」时，`Iris.createPipeline(...)` 返回
`new VanillaRenderingPipeline()` 而不是 `IrisRenderingPipeline`。于是：

* 画面就是**逐像素的原生渲染**（与关闭光影完全一致），名字与实际对上了；
* 它仍然是一个正常的、出现在 Iris 选包界面与副管线列表里的光影包（Iris 照常加载它、
  `currentPackName` 就是「原版」）；
* 主管线是原生管线时，模组**照常渲染副管线那一遍**（原来会直接跳过），
  所以「原版 ↔ 光影包」的 F7 秒切依旧成立；
* `WorldSettings.applyFor` 对原生管线单独走一套取值（`useExtendedVertexFormat=false`、
  `blockTypeIds=null`、AO=1.0…），和 Iris 自己在 `VanillaRenderingPipeline` 构造函数里的取值一致，
  避免实体/地形按扩展顶点格式绑顶点而渲染错乱；副管线那一遍前后也加了全局设置快照/还原。

### ② 副管线截图有概率得到纯黑图

玩家截图 `2026-09-12_23.17.37.png` 实测是**整张纯黑**（采样 10112 点，最大亮度 0、平均 0），
而同一配置上一次截图（23.16.47）正常。对照日志：

| 截图 | 请求→完成 | 结果 |
| --- | --- | --- |
| 23:15:51 | 3446 ms | 正常 |
| 23:16:47 | 3041 ms | 正常 |
| 23:17:37 | **1757 ms**（几乎刚到最小等待就被放行） | **纯黑** |

黑屏那次与正常那次的差别是：副管线（MakeUp）是 23:17:31 才建好的**全新管线、此前一帧都没渲染过**
（23:16 那次的 MakeUp 已经在 23:16:34–42 当过主管线、渲染过若干帧），而且那次几乎是刚到
最小等待就被放行取景。也就是说：**新建管线的第一遍渲染 / 地形还没按副包材质重建完时抓帧，
有可能抓到一整张黑**。

**修法**（不赌哪一种成因，直接把“黑图”这条路堵死）：

1. 截图不再调用原版 `ScreenshotRecorder.saveScreenshot`，而是自己
   `takeScreenshot` → **采样检查是否整张纯黑**（最亮 < 8 且平均 < 3 判为空）→ 正常才按原版命名规则
   落盘（`screenshots/<时间戳>[_n].png`）并发原版的 `screenshot.success` 提示；
2. 判为空就**丢掉这一帧、700 ms 后重拍**（最多 5 次，期间保持材质表切到副包的状态）；
   连续黑屏才放弃并明确告诉玩家“没有保存”，不会再把黑图塞进 screenshots 目录；
3. 顶点格式不同（一边是「原版」原生管线、一边是光影包）时，逐区块重建不够 ——
   取景/还原都改为整世界重建并同样等待，避免拿旧顶点格式的网格去拍。

## 0.1.11 —— 新功能：「原版」光影包

### 需求

在 Iris 的光影选择界面加一个「原版」条目：让 Iris **以外置光影包的形式**加载原版的着色器；
本模组的副管线列表也加同一项，于是「原版」能作为副管线参与 F7 秒切、副管线截图、自检等功能。

### 实现方式

**「原版」= 一个真实的、放在 shaderpacks 下的目录型光影包**（由模组生成，名字就叫 `原版`）：

```
shaderpacks/原版/
└── shaders/
    └── shaders.properties     ← 只有说明性注释，没有任何程序
```

为什么“空包”就等于“原版的着色器”：Iris 对**缺省的程序**会走它自己的 fallback 路径 ——
按原生渲染的语义合成一份等价程序（`ShaderCreator.createFallback` / `ShaderSynthesizer`），
Sodium 的地形程序也退回 Sodium 自带的原生等价程序
（`IrisChunkProgramOverrides.createShader` 在包没提供地形着色器时返回 null，
Sodium 就用它自己的）。也就是说「空包」在 Iris 里本来就被当作“尽量按原生渲染”。

为什么做成文件包而不是虚拟条目：

* Iris 的选包界面直接枚举 `shaderpacks/`（`ShaderpackDirectoryManager.enumerate()`，
  `Iris.isValidToShowPack` 接受目录），所以放一个目录进去，它就自然出现在 Iris 的列表里，
  **不需要改 Iris 的界面与加载代码**；
* Iris 加载目录包的路径（`Iris.tryLoadShaderpack` → `<包>/shaders`）、每个包的选项文件
  （`原版.txt`）都按普通包处理；
* 于是本模组的其它功能一行特判都不用加：`ensureSecondary` / `swapPrimary` / 材质表与网格重做 /
  副管线截图 / 兼容性自检 / 维度变化重建，全部照旧工作。

### 具体改动

* 新增 `VanillaPack`：生成 `shaderpacks/原版/shaders/shaders.properties`（**只补缺失文件**，
  已有文件一个字都不动；玩家想改造这个包也可以，界面上的“原生等效”标注会自动收起来）、
  `isVanilla()` / `isPristine()`；
* `ShaderFlash` 在客户端初始化时确保它存在；`PackScanner.listPacks()` 每次列包前也确保一次，
  并把它排到列表最前面；
* 副管线列表里这一行用 `◆` + 青色 + 「（原生等效着色器）」标注（玩家塞过程序后自动去掉后缀）；
* 设置界面新增开关 `vanillaPackEntry`（默认开）：关掉就不再生成（已生成的不会删）；
* 「兼容性自检」在副包是「原版」时额外提示“全是「=无」是预期行为”；
* 文档：新增 [`docs/13-功能-原版光影包.md`](docs/13-功能-原版光影包.md)。

### 已知差异（说清楚）

* 「原版」走的是 Iris 的 fallback，不是“把光影关掉”的那条原生管线。
  两者观感非常接近，但不保证逐像素相同（例如 Iris 自己的注释里就提到 fallback 的
  Sodium 地形“不支持水下的 EXP2 雾”）。要**完全**原生的渲染，请在 Iris 里把光影关掉；
  只是那样就无法参与双管线（双管线要求两边都是光影管线）。
* 想在双管线里用「原版」，请**在 Iris 里选中「原版」这个包**，而不是关掉光影
  （关掉光影时 Iris 用的是 `VanillaRenderingPipeline`，本模组不把它当副管线的对照对象）。

## 0.1.10 —— 修切到 MakeUp 的闪退 + 截图改走真实像素路径

### ① 主副切换 MakeUp 时闪退（`NullPointerException`）

崩溃栈（玩家报告 `crash-2026-09-12_17.03.07-client.txt`）：

```
java.lang.NullPointerException
  at IrisRenderingPipeline.lambda$new$6(IrisRenderingPipeline.java:351)   ← Objects.requireNonNull(shadowRenderTargets)
  at SodiumTerrainPipeline.initTerrainSamplers(SodiumTerrainPipeline.java:633)
  at IrisChunkShaderInterface.<init>(IrisChunkShaderInterface.java:95)
  at IrisChunkProgramOverrides.createShader(...)  ← Sodium 懒编译地形程序
```

**成因**：Iris 的阴影渲染目标是**懒创建**的 —— 只有 `shaders.properties` 里显式写了
`shadow.enabled = true`，或者某个**非 Sodium 版程序**在编译时绑定到阴影采样器，它才会被建出来。
而 Sodium 的地形程序是**懒编译**的，`createTerrainSamplers` 里直接
`Objects.requireNonNull(shadowRenderTargets)` —— Iris 的注释写着“非 Sodium 版早就编译过了，
所以这里不可能是 null”。MakeUp 恰好踩中这个前提不成立的情况：

* 它的 `shaders.properties` **没有** `shadow.enabled`（于是构造期不建阴影目标）；
* 但它的 `lib/config.glsl` 里 `#define SHADOW_CASTING` 是默认打开的，
  地形着色器确实声明了 `shadowtex`（`common/solid_blocks_fragment.glsl` 里 `#if defined SHADOW_CASTING`）；
* 于是 Sodium 懒编译地形程序时走到 `requireNonNull(null)` → NPE 崩游戏。

**修法**：我们在构造副管线之后补一句「如果这个包确实带阴影程序、而阴影目标还没建，就建出来」
（`DuoManager.ensureShadowTargets`，通过 `IrisRenderingPipelineAccessor` 的
`shadowTargetsSupplier` accessor 调 Iris 自己的创建逻辑）。没有阴影程序的包不补，不白占显存。
同时把这条信息打进日志（`副管线阴影渲染目标：已就位/缺失，阴影程序：有/无`），便于后续核对。

### ② 副管线截图内容不对（只有天空底色 / 天空位置出现扭曲地形）

0.1.8/0.1.9 直接读「幽灵渲染」写的那块**离屏 FBO**。玩家实测那样拿到的画面不总是对的：
两张 Cyanide 副包的截图是**纯色**（两次颜色不同、都是干净的单色 → 整张图只有天空），
一张 Photon 副包的截图则是**没走完 composite/final 的中间态**（天空位置出现扭曲的地形）。

**成因**：离屏那一遍会让 `MinecraftClient.getFramebuffer()` 返回另一块缓冲，而 Iris 内部多处用
“当前这个缓冲是不是主缓冲”来判断流程走向（`MixinRenderTarget` 直接拿
`this == getFramebuffer()` 判定，决定 begin/composite/final 那套绑定逻辑走不走）。
也就是说：**离屏那一遍的像素 ≠ 把这条管线当主管线时的画面**。

**修法**：截图改成在**一帧的最开头**（`GameRenderer.renderWorld` 的 HEAD）用副管线把世界
渲染进**主帧缓冲**，紧接着读像素存盘；本帧随后的正常渲染会把它整个覆盖掉，
玩家一帧都看不到。像素走的就是和“按 F7 切过去”完全相同的路径。
附带变化：文件里不再包含 HUD（取景发生在 HUD 之前）。

### ③ 后台预编译：解包进后台，解析与编译留在渲染线程

0.1.9 试图把“解包 + 解析”都放到工作线程。实测（三种真实光影包的日志）**解析无一例外失败**：

```
[ShaderFlash] 后台解析失败（java.lang.IllegalStateException: Rendersystem called from wrong thread）
```

Iris 解析 `shaders.properties` 时会做 GL 查询，所以解析也离不开渲染线程。现在明确拆成：

* **解包**（把 zip 铺成 packcache 目录，40 MB 的包要几秒）→ 工作线程；
* **解析 + 编译**→ 渲染线程（编译本来就必须在渲染线程）。

分段耗时照旧打进日志（`解包 A ms / 解析 B ms / 编译 C ms`），进度照样显示在界面与 HUD 上。

## 0.1.9 —— 删掉保留字兼容、后台预编译、截图彻底不切屏、设置界面重做

### ① 删除「兼容 GLSL 4.x 保留字」

玩家的判断是对的，这里照办：**如果一个光影默认情况下 Iris 根本加载不了，玩家就不会去用它**，
而改名也修不回**已经丢失的语义** —— 那类老写法能跑靠的是旧版 GLSL 的语义，
换个名字只是让编译通过，行为并不等价。既然救不了，就按项目简洁性要求整个删掉：
`ReservedKeywords.java`、`fixLegacyKeywords` 配置与界面开关、`PackPreparer` 的改写分支与
缓存戳记里的 `fix=` / `rewritten=`、`PackSlot.rewrittenKeywords`、相关语言键，
以及三个只服务于该功能的离线小工具（`KwTest` / `RewriteTest` / `LegacyAttrRewriteTest`）。
缓存格式升到 `v4`（旧缓存会自动重建）。

### ② 副管线预编译改为后台执行

一次完整预编译拆成三段：**解包**（纯文件 I/O）→ **解析光影包**（纯 CPU 文本处理）
→ **编译着色器**。

* 前两段搬到工作线程（`shaderflash-prewarm-<包名>`），游戏照常出帧；
* 第三段 `new IrisRenderingPipeline(programSet)` **必须**留在渲染线程：Iris 在构造函数里
  就直接调 GL 编译并链接所有程序，而 GL 上下文只属于渲染线程。硬切开会需要 fork
  它的整条管线构造流程（`ShaderMap` / `CompositeRenderer` / 各类 render target 都在构造函数里
  一次性建好，`Program` 还是 final 类），代价与风险都不成比例，所以这一段保持原样；
* 于是收益是**把解包 + 解析从卡顿里摘出去**，并且给玩家实时反馈：
  状态区与 HUD 会显示「正在预编译 xxx：解包 / 解析光影包 / 编译着色器（百分比）」；
* 预编译期间按 F7 不再干等：记下意图，编译一完成自动切换；
* 解析并不总能离开渲染线程 —— Iris 解析 `blend.x.y` 这类指令时会查一次 GL 能力
  （`IrisRenderSystem.supportsBufferBlending()`）。所以后台线程一旦抛错，
  会自动退回渲染线程重跑一次，而不是让功能失效；
* 标准宏（`StandardMacros.createStandardEnvironmentDefines()`）本身会做 GL 查询
  （`glGetString` / `glGetStringi`），因此在**启动预编译时**就在渲染线程上算好再交给工作线程；
* 开关 `backgroundPrewarm`（默认开）可以退回原来的一口气同步完成。

### ③ 副管线截图：不切屏 + 主动拉起渲染 + 等地形重建完

玩家指出的漏洞成立：截图流程依赖离屏 FBO 里有帧数据，而「仅预编译」模式下那一遍根本不跑，
FBO 是空的。修法分三步：

1. **主动拉起**：截图期间 `DuoManager.onWorldRenderTail` 无条件渲染幽灵那一遍
   （绕过“渲染模式”和“开界面时暂停”两个开关），FBO 一定有当前帧数据；
2. **材质表对齐**：`beginSecondaryCapture()` 把副包的材质表应用到全局
   （与 F7 切换同一套逻辑，含“分离 AO 变化时让地形程序重编一次”），
   再把视距内的区块网格排队重建 —— 否则拍到的是「主管线材质 + 副管线着色器」的混合画面；
3. **等待条件**：Sodium 构建队列走完一轮「忙 → 空」，并且之后至少渲染出一整帧才拍；
   12 秒兜底放行、60 秒硬上限，拍完立刻还原材质表并把地形重建回主管线的样子。

另外修掉了等待逻辑里一个会造成“明明 10 秒就重建完了却一直不拍”的坑：
若调度之后**始终没观测到队列非空**（重建太快、或这一轮根本没排上队），
过了 3 秒观察期就认定它已经做完，而不是无限等下去。

整个过程**不动管线指针、不动屏幕**，所以没有画面切换与闪回；像素由原版截图函数从离屏 FBO 读。
旧版那套“把屏幕切到副管线再切回来”的代码（连同视锥体/剔除开关的保存还原）已整体删除。

### ④ 设置界面重做

* 窗口够宽：左列表 + 右**可滚动**选项面板；窗口很窄：列表收成一个按钮，点开专门的选包页 ——
  任何窗口尺寸下都不会再有开关被挤没；
* 选项按功能分节：状态 / 副管线 / 主副切换 / 副管线截图 / 界面与提示 / 诊断；
* 新增的控件框架（`OptionsPanel`）按“行”组织：标题行、说明行（自动折行）、整行控件、
  一行两个控件（放不下时自动改成上下两行）。加一个开关就是一行代码，方便继续扩；
* 「**帧间隔**」改为**左键 +1、右键 -1**（在 1/2/3/4/6/8/12/16/32 里走，到头即停）；
* 删除「截图层数」（`screenshotHoldFrames`）—— 新截图流程由“地形重建完 + 至少一帧”自动决定时机；
* 删除「兼容 GLSL 4.x 保留字」开关（见 ①）。

## 0.1.6 —— 修正 0.1.5 的结论 + 重做副管线截图

### ① 0.1.5 的结论是错的（已按玩家的新证据推翻）

玩家实测规律：

> 一个光影**只有在用户通过 Iris 的光影选择界面主动加载之后**，
> 它作为主管线/副管线渲染时才不会出现实体问题。

这说明问题不在光影包本身（MakeUp 单独加载完全正常），而在**管线是怎么被创建出来的**：
由本模组直接 `new IrisRenderingPipeline(...)` 得到的管线，实体渲染是坏的。

因此：

* `fixLegacyEntityAttribute` 默认值改回 **false**（那条改写保留但默认不启用）；
* 新增一项对齐改动：**构造副管线时，把 `Iris.currentPack` / `currentPackName`
  临时指向该副包**（构造完立刻还原）。Iris 自己建管线时这两个字段本来就是指向该包的，
  本模组此前是在「当前包是主管线」的条件下构造，这是一个确定存在的差异。

如果花斑仍在，请提供实体出问题时的自检输出（见 [`docs/12-…`](docs/12-待补.md)），
下一步按 `shouldOverrideShaders` / 实体着色器类 / `ImmediateState` 三项区分原因。

### ② 副管线截图：两处实现问题

**问题 1：等待逻辑恒不生效。**

0.1.4 用 `SodiumWorldRenderer.isTerrainRenderComplete()`（= 构建队列是否为空）判断
“地形是否重建完”。但 Sodium 的任务是**先登记、后入队**：
`scheduleRebuildForChunk` 只是给区块打标记，真正的任务要等 Sodium 下一次 `update()`
才进队列。所以刚调度完的一瞬间队列仍为空 → 判断成“已经完成” → 立刻拍照。

修法：改成跟踪一次完整的「忙 → 空」过程（`DuoManager.isRemeshSettled()`）——
必须先看到队列非空，之后变空才算真的做完。

**问题 2：截图那次切换根本不换材质表。**

`beginScreenshotSwap` 此前调用 `WorldSettings.applyFor(..., false)`，
刻意只应用“渲染时读取”的设置、不重建区块。于是截图里的方块材质分类仍来自主管线，
即使等到重建结束也拍不到副管线的真实画面。

修法：截图切换改为**和 F7 一样的完整切换**（应用副包材质表 + 排队重建），
拍完还原时再按主包材质重建一次；`ScreenshotCapture` 会等重建真正做完再拍，
上限 60 秒，超时会提示。开关沿用 `rebuildChunksOnSwitch`。

## 0.1.5 —— 实体花屏：老包的 `mc_Entity` 读到整数属性的乱码

### 定位过程（用玩家聊天反馈做的相关性分析）

日志里玩家的实时标注：

```
14:47:52 <Tenfrend_b> 此时渲染一切正常      ← 主管线 photon，副管线 MakeUp
14:48:09 [双管线] 已切换到 MakeUp-UltraFast-9.5e
14:48:32 <Tenfrend_b> 此时实体花屏          ← 主管线变成 MakeUp
14:49:26 [双管线] 已切换到 photon_v1.3b
14:49:42 <Tenfrend_b> 此时实体正常
15:02:47 <Tenfrend_b> 这种状态下加载的副管线C光影也没有实体问题
```

**花屏只出现在 MakeUp 作为主管线时**；photon / Cyanide 当主管线都正常。

### 根因

MakeUp 的实体程序沿用 OptiFine 约定，用**浮点**属性读实体 ID：

```glsl
// common/solid_blocks_vertex.glsl
attribute vec4 mc_Entity;
...
if (mc_Entity.x == ENTITY_METAL) { ... }      // ENTITY_METAL = 10400.0
```

而 Iris 1.7 在**实体程序**里把这个属性绑成**整数**：

```java
// IrisVertexFormats
ENTITY_ID_ELEMENT = new VertexFormatElement(11, Type.USHORT, Usage.UV, 3);

// VertexFormatElement.Usage.UV：非 FLOAT 类型走整数指针
if (glType == 5126) _vertexAttribPointer(...); else _vertexAttribIPointer(...);
```

用浮点声明去读整数属性属于未定义行为，驱动给出乱码 → `mc_Entity.x == ENTITY_METAL`
之类的比较乱命中 → 实体被套上错误材质（金属/自发光/沙地）→ **表面花斑/花屏**。

对照：Photon 的实体分支用的是 Iris 的 `entityId` uniform（被 `EntityPatcher` 正确映射成
`iris_entityInfo`→`iris_Entity` 整数），所以不受影响；Cyanide 没有实体程序，
走 Iris 自己合成的 fallback，也不受影响。

### 修法

在**副包副本**上（原文件不动）把这类声明换成按程序类型自适应的版本：

```glsl
#if defined GBUFFER_ENTITIES
uniform int entityId;
#define mc_Entity vec4(float(entityId), 0.0, 0.0, 1.0)
#elif defined GBUFFER_HAND
uniform int currentRenderedItemId;
#define mc_Entity vec4(float(currentRenderedItemId), 0.0, 0.0, 1.0)
#elif defined GBUFFER_BLOCK
uniform int blockEntityId;
#define mc_Entity vec4(float(blockEntityId), 0.0, 0.0, 1.0)
#else
attribute vec4 mc_Entity;      // 地形/阴影程序保持原样
#endif
```

* 数值天然一致：`entityId` 是 Iris 查**该包自己的 `entity.properties`** 得到的，
  和 MakeUp 的 `ENTITY_*` 常量是同一套编号；
* 只改副本；开关是 `fixLegacyEntityAttribute`（默认开，配置在 `config/shaderflash.json`）；
* 已对真实 MakeUp 包验证命中 3 个文件（`solid_blocks_vertex` / `water_blocks_vertex` / `shadow_vertex`），
  且地形与阴影程序落在 `#else` 分支，行为不变；
* 已知未覆盖：包如果用 `GBUFFER_HAND_WATER` 这类派生宏，需要再补一条分支。

## 0.1.4 —— 切换后的地形替换太慢 / 截图拍到半成品

玩家反馈（0.1.3 之后）：

> 按下 F7 可以察觉到植被/水体有一个明显的从近到远的重载过程，这个过程速度过慢，
> 以至于「副管线截图」功能无法获取地形完整替换的截图。
> 实体材质花斑问题依旧。

### ① 重建任务被投递到了队尾（这是“慢”的直接原因）

Sodium 的构建队列实现：

```java
public void add(ChunkJob job, boolean important) {
    if (important) { this.jobs.addFirst(job); }   // 插队首
    else           { this.jobs.addLast(job); }    // 排到队尾
    ...
}
```

本模组此前调用的是 `scheduleRebuildForChunks(..., important = false)`
—— 等于把**几万个重建任务全部排到队尾**，跟正常区块加载抢不到优先权，
于是地形替换只能零零散散地往前挪，玩家看到的就是那条缓慢的“由近到远”的重载波。

另外 `RenderSectionManager.scheduleRebuild(...)` 里，`important=true`
会把任务升级成 `IMPORTANT_REBUILD`，而 `false` 时只有**距相机 16 格以内**的区块才会被自动升级。

**修法**：全部改用 `important = true` 投递。

由于 `important` 走的是 `addFirst`（后进先出），投递顺序会反过来决定执行顺序，
所以改成**由远到近投递**，最终执行顺序就是“从玩家脚下向外扩散”，
近处最先补齐。

### ② 副管线截图会等地形替换完成

切换刚触发过区块重建时按 F2，拍到的是「一半旧材质、一半新材质」的地形。

**修法**：`ScreenshotCapture` 会检查 Sodium 的构建队列
（`SodiumWorldRenderer.isTerrainRenderComplete()`）——
如果最近 60 秒内触发过重建且队列还没空，就先保持副管线的画面继续等，
直到队列清空再拍（上限 60 秒，超时会提示并按当前画面拍）。
聊天框会先提示“地形还在重建，等替换完成后再截图”。

### ③ 幽灵渲染不再向主画面泄漏 Iris 的即时状态

`ImmediateState.isRenderingLevel` / `ImmediateState.renderWithExtendedVertexFormat`
这两个**全局静态**标志决定“顶点缓冲按哪套布局建立与绑定”。
幽灵那一遍会用自己的管线把它们改掉，正常路径下会对称还原，但只要中间出现任何异常路径，
就会留下「数据是扩展格式、绑定时按普通格式」的错配——这正是实体表面出现杂色/花斑的典型成因。

**修法**：幽灵渲染前后显式保存/还原这两个标志，以及
`CapturedRenderingState` 里的 entityId / blockEntityId / itemId。

### ④ 自检增加实体渲染状态

设置界面的「兼容性自检」现在会打印采样到的即时状态：

```
即时状态：isRenderingLevel=…，renderWithExtendedVertexFormat=…，
          entityId=…，blockEntityId=…，itemId=…
```

如果实体花斑仍然出现，按一次自检并把这几行发我，就能直接区分
“实体没用上光影包的着色器”和“顶点布局错配”两种情况。

## 0.1.3 —— 切换后「材质 ID」与「地形着色器」不同步

玩家实测回报的两个现象：**F7 切换后实体表面出现闪烁的异常色块**、
**副管线预编译的光影包切为主管线后不再播放植被摇动动画**
（而用 Iris 自己的界面切到同一个包时摇动正常）。

排查结论：这是**两个独立缺陷**，都属于“提升已存在的管线为主管线”这条路上漏掉了
Iris 缓存的、与光影包相关的状态。完整证据链见
[`docs/10-排查记录-切换后材质与着色器不同步.md`](docs/10-排查记录-切换后材质与着色器不同步.md)。

### ① 区块构建一直使用旧包的方块 ID 表（→ 植被不摇动）

Iris 的 Sodium 兼容层在 `ChunkBuildBuffers` **构造时**就把
`WorldRenderingSettings.getBlockStateIds()` 存进了 `BlockContextHolder`：

```java
this.contextHolder = new BlockContextHolder(WorldRenderingSettings.INSTANCE.getBlockStateIds());
```

而这个对象由 `RenderSectionManager` 创建，生命周期是**整个世界会话**。
于是本模组的“逐区块后台重建”虽然确实重建了网格，写入顶点的 `mc_Entity.x`
却仍然是旧光影包的材质 ID：光影包靠 `material_mask = mc_Entity.x - 10000`
判断“哪些方块要摇”，ID 不对就永远匹配不上，**且不会自行恢复**。
用 Iris 界面切换之所以正常，是因为那条路会重建 `RenderSectionManager`（整世界重载）。

* 修法：新增条件加载的 `BlockContextHolderMixin`，把取值改成读当前表；
  在加载不了它的环境里，`remeshFor(...)` 会**退回整世界重载**，绝不留旧 ID。
* 运行时验证：`tools/BlockIDRefreshTest.java`（用真实 Iris 类复现，见 0.1.3 的构建说明）。

### ② 地形着色器把「分离 AO」烘焙进了源码（→ 切换后画面错配）

`SodiumTransformer.injectVertInit` 会读 `WorldRenderingSettings.shouldUseSeparateAo()`
并把它拼进地形着色器源码——**编译期就定死了**。副管线的地形程序是在
“主管线的全局值”下编译的，切为主管线后全局值变了、区块网格也按新值重建了，
但缓存里的地形程序还带着旧值，两边对不上。

* 修法：切换时若两包在这一项上不同，bump 一次 `versionCounterForSodiumShaderReload`，
  让 Iris 丢掉地形程序并在新取值下重编；**只在确实要重建区块网格时做**，
  避免“新着色器 + 旧顶点数据”。
* 代价：这种情况下切换会多一次地形程序编译（0.1.1 之前刻意省掉的优化，
  但那个优化建立在一个错误前提上）。

## 0.1.2 —— 修 0.1.1 引入的崩溃

### 症状

启用双管线后**一进世界就崩**，报告里是：

```
java.lang.IllegalStateException: Pose stack not empty
	at net.minecraft.class_761.method_22979   // LevelRenderer.checkPoseStack
	at net.minecraft.class_761.method_22710   // LevelRenderer.renderLevel
	at net.minecraft.class_757.method_3188    // GameRenderer.renderWorld
```

### 原因（0.1.1 的修法错了）

0.1.1 为了修“副管线视角被叠加两次相机旋转”，在 `GameRenderer.renderWorld` 的入口
`matrices.push()`、尾部 `matrices.pop()`。但原版
`LevelRenderer.renderLevel` 在渲染中段会调用：

```java
public boolean clear() { return this.poseStack.size() == 1; }   // PoseStack.clear()，只判断不清空

private void checkPoseStack(PoseStack stack) {
    if (!stack.clear()) throw new IllegalStateException("Pose stack not empty");
}
```

也就是说**世界渲染期间传入的 PoseStack 必须只有 1 帧**。push 出来的那一帧在整个
`renderWorld` 方法体里一直挂着，于是副管线那一遍（递归调用 `renderWorld`）走到
`checkPoseStack` 就抛异常。

### 正确修法

不再动调用方那个栈，改成“入口采样 + 另建一个栈”：

* `GameRendererMixin` 在 `@HEAD` 只**记录**顶层姿态（`peek().getPositionMatrix()/getNormalMatrix()` 的副本）；
* `DuoManager.onWorldRenderTail` 在真正要渲染副管线时，`new MatrixStack()` 并把快照写进去；
  这个新栈只有 1 帧，`checkPoseStack` 通过，内容又等于 `renderWorld` 入口时的姿态，
  所以相机旋转只应用一次；
* 原版那个栈**一个字都不动**，其它 mod / 原版代码看到的状态与没有本模组时完全一致。

`tools/RotationTest.java` 现在同时覆盖了这两个前提：

```
reference (one pass)          : [+0.7314, -0.2719, +0.6254, +0.0000]
reuse caller stack (broken)   : [+0.1083, -0.6307, +0.7684, +0.0000]
fresh stack from entry (0.1.2): [+0.7314, -0.2719, +0.6254, +0.0000]

push/pop 方案: checkPoseStack 会通过吗 ? false   (false = 会抛 Pose stack not empty)
fresh 栈     : checkPoseStack 会通过吗 ? true
```

> 教训：这个模组会在渲染中途“再跑一遍”原版流程，所以任何“临时改一下原版会看到的状态”
> 的修法，都必须先确认原版流程中段没有对那个状态的断言。`checkPoseStack` 就是一处。

## 0.1.1 —— 接手后的 bug 修复

这一版只改行为正确性，不加新功能。四条都在真机跑之前看不出来（副管线输出不上屏，
所以前两条不会表现为“画面花了”），但都会让功能名不副实或留下副作用。

### ① 幽灵渲染的视角被叠加了两次相机旋转（重要）

`GameRenderer.renderLevel`（Yarn `renderWorld`）会**直接改写调用方传进来的 PoseStack**：
它在方法中段对同一个栈做 `mulPose(相机 pitch)` 与 `mulPose(相机 yaw + 180)`，
而且方法内部**没有配对的 push/pop**——原版不需要，因为 `GameRenderer.render` 每帧传的是
`new PoseStack()`，用完即弃。本模组在 `renderLevel` 尾部复用的正是这同一个栈，
于是副管线那一遍是在“已经转过一次”的矩阵上再转一次。

后果：副管线看到的视角与主管线完全不同（不是同一帧的同一视角）。因为那一遍不上屏，
画面上看不出任何异常，但“预热”失去了本来的意义，而且 `RenderSystem.setInverseViewRotationMatrix`
会被留成错误值直到本帧结束。

修法：`GameRendererMixin` 增加一个 `@Inject(at = HEAD)` 做 `matrices.push()`，
尾部注入在调用副渲染之前先 `matrices.pop()`，让两条管线从完全相同的姿态起算。
数值验证见 `tools/RotationTest.java` 与 [`docs/09-修复记录-0.1.1.md`](docs/09-修复记录-0.1.1.md)。

### ② 副管线截图之后视锥体不复原

Iris 在 `WorldRenderer.render` 开头会按“当前管线”把视锥体换成不剔除的版本
（`shouldDisableFrustumCulling()` 为真时），并且**自己从不还原**。
幽灵渲染那条路本模组已经手动还原了，截图那条路（`begin/endScreenshotSwap`）没有：
用「禁用视锥剔除」的光影包截一次图，主管线就会一直用 `NonCullingFrustum` 全量渲染，
直到重载世界。`chunkCullingEnabled` 同样没还原。

修法：截图切换时一并快照/还原视锥体与 `chunkCullingEnabled`，并在收尾里防御性复位
`ShadowRenderer.ACTIVE`（与幽灵渲染那条路一致）。

### ③ `rebuildAfterIrisReload` 是死配置

它此前只被用来打一行日志，真正决定“Iris 重载后要不要自动重建副管线”的是
`autoBuildOnWorldJoin`。也就是说把这个开关关掉并没有效果（`docs/02-配置项.md` 的描述对不上实现）。

修法：把“进世界自动预编译”和“重载后自动重建”拆成两个独立的待办标志，各自受对应开关控制；
另外把“换维度”单独拎出来（现有副管线维度对不上时无条件重建，否则换个维度副管线就永久失效了）。

### ④ 离屏 FBO 从不解散

`GhostFramebuffer.destroy()` 此前没有任何调用点：关掉开关或离开世界时只销毁了管线，
那块整屏彩色 + 深度的离屏帧缓冲会一直占着显存，而 README 声称“关闭时副管线资源会被释放”。

修法：新增 `DuoManager.releaseGhostTarget()`，在「关闭双管线」「离开世界」两个安全时刻调用
（绝不在渲染中途删除 GL 资源）。

## 0.1 包含的功能与修复

按开发顺序（括号里是文档旧编号）：

1. **双管线同时驻留 + 幽灵渲染**（1.0.0）
   * 按 Iris 自己的做法解析第二个光影包并编译成第二条 `IrisRenderingPipeline`；
   * 每帧在主画面画完后，临时换管线、换主帧缓冲，再用同一条 `GameRenderer.renderWorld`
     渲染一遍，输出只进离屏 FBO，永不上屏；
   * Mod Menu + 自带设置界面、快捷键（F6 开关 / F7 互切 / F8 设置）。

2. **老光影包保留字兼容**（1.1.0）
   * Iris 1.7.1+ 会把所有包转译成 `#version 410 core`，老包里当变量名用的 `sample` 等词会编译失败；
   * 加载副包前先铺一份副本，在副本上做保留字改名（原文件不动），并修复了副包选项文件名读错、
     误用主管线选项队列两个问题。

3. **幽灵渲染擦屏 / 切换后不重建材质表**（1.2.0）
   * 原版世界渲染在绑定渲染目标之前会 `RenderSystem.clear(...)`，它清的是“当前绑定”的帧缓冲，
     导致副管线那一遍把主画面擦成背景色（每帧模式=只剩天空色，节流模式=闪烁）；
     修法是在幽灵渲染前先 `beginWrite` 切到离屏 FBO、结束后切回主缓冲；
   * 主副互换时补上 Iris 的 `reloadRequired` 消费（材质表/顶点格式变化后重建区块网格）；
   * 写进 `iris.properties` 的包名改成真实条目名（带 `.zip`）。

4. **无感切换 + 自检升级**（1.3.0）
   * 先精确判断“是否真的需要重建网格”（只比会被烘焙进网格的 7 项设置）；
   * 需要重建时走 Sodium 的逐区块后台排队重建，不再有可见的区块卸载/重载；
   * 切换时不再丢 Sodium 地形着色器缓存（以前每次切换都要重编，几百毫秒卡顿）；
   * 自检改为在**渲染中途**采样，并列出“副包自带哪些程序”，用来区分“包本身没有”与“没生效”。

5. **副管线截图**（1.4.0 初版 → 1.4.1 修复）
   * 打开后接管原版 F2：切到副管线 → 渲染若干帧 → 用原版截图函数把那一帧存盘 → 还原主管线；
   * 1.4.0 的问题：F2 的按键回调发生在本帧世界渲染**之后**，当场截图拍到的还是主管线的画面，
     副管线一帧都没渲染过（屏幕也看不到切换）。1.4.1 改为换管线后**至少再等一整帧**才拍。

## 已知限制（详见 `docs/03-限制与风险.md`）

* 只验证过 **Minecraft 1.20.4 + Iris 1.7.1 / 1.7.2**，注入点与 Iris 版本强绑定；
* 幽灵渲染会让 GPU 负载接近翻倍，核显建议用「每 N 帧」或「仅预编译」；
* 两条管线共享同一套区块网格，“烘焙进网格”的设置（材质 ID 表、渲染类型表、顶点格式、AO 等）
  以主管线为准；两包这些设置不同切换时会做一次后台重建（无感）；
* 副管线截图同样不做区块重建，材质分类沿用主管线；
* **没有做真机自动化测试**：所有结论来自代码/字节码分析 + 离线校验器 + 玩家实测反馈。
