# 闪光灯 · Shader Flash

> 让 Iris **同时驻留两套光影渲染管线**：按 F7 秒切，不用再等一次完整的光影重载；
> 还能用副管线取景截图 —— 支持自定义分辨率（可以拍 4K/8K 高清）与 JPG 压缩。

作者：**DeepSeek-V4.1-Flash**（Tenfrendd_b@outlook.com）· 许可证：**LGPL-3.0-or-later** · 目标版本：Minecraft **1.20.4**（Fabric，仅客户端）

---

## 它解决什么问题

原版 Iris 一次只加载一套光影管线。想换包时，它要**重新解析整个光影包 → 编译几十个着色器程序 →
重新分配渲染目标与阴影贴图 → 重建全部区块网格**，大包上要好几秒，期间游戏会卡住。

闪光灯的做法是**提前把第二条管线编译好并常驻显存**（副管线），于是切换只剩“交换指针”这一步，
外加两个包材质表不同时的一次区块重建 —— 实测从十数秒降到**几十毫秒**。（PS：AI在吹水，不过确实会快很多就是了）

## 主要功能

### 1. 超快的光影切换（F7）

* 两条管线都保持“编译完成、缓冲就位”，按 F7 交换指针即可；
* 两包的方块材质表 / 分离 AO 等设置不同时，会自动排队重建区块网格，
  并且走 Sodium 的**逐区块后台重建**（旧网格一直渲染到新网格建好，看不到地形卸载）；
* 互换后可以把原来的主管线**留作新的副管线**，反向切换同样零编译。

### 2. 副管线截图（F2）

开着主画面，用**副管线**拍一张图：

* 取景发生在某一帧的最开头，读像素后本帧的正常渲染会把它覆盖掉 —— **你看到的画面不受影响**；
* **自定义分辨率**：宽/高两个文本框，可一键按窗口宽高比补全另一边；分辨率与游戏窗口无关
  （例如窗口 1920×1080 也能拍 3840×2160 甚至 8640×5760）；
* **JPG 模式**：开启后保存为 JPG（质量 85%），体积明显小于无损 PNG；
* **进度提示**：HUD 显示“区块重建进度条 + 已做/总数 + 预热帧数 + 已等待时间”，拍完在聊天栏提示，
  点文件名可以直接用系统默认程序打开这张图（与原生截图一致）。

### 3. 「空包」光影包

模组会在 `shaderpacks/` 下生成一个叫 **`空包`** 的空光影包：它不提供任何程序，
Iris 会为它合成与原生等价的 fallback 着色器。于是**“原生渲染”也能作为双管线中的一条**，
可以像普通光影包一样加载并截图。

### 4. 设置界面与自检

* 按 **F8**（或从 Mod Menu 进入）打开设置界面：选副光影包、预编译/重建、立即切换、
  截图相关选项、HUD 状态、聊天提示等；
* **兼容性自检**：对比两条管线的材质 ID 表、AO 等级、体素化光源、实体分离绘制、阴影分辨率、
  各自自带的程序，并报告**着色器程序链接状态**。

### 5. 快捷键（可在“选项 → 控制 → 闪光灯”里改）

| 按键 | 作用 |
| --- | --- |
| **F7** | 主/副管线秒切（注意，需要手动在F8中编译一套副管线，自动加载会有实体渲染问题） |
| **F6** | 打开/关闭双管线 |
| **F8** | 打开设置界面 |
| **F2** | 副管线截图（需在设置里开启） |

## 安装

1. 需要 **Minecraft 1.20.4** + **Fabric Loader ≥ 0.15** + **Fabric API** + **Iris 1.7.x**；
   推荐同时安装 **Sodium**（地形性能）与 **Mod Menu**（图形化入口）。
2. 把 `shader-flash-1.0.jar` 放进 `.minecraft/mods/`。
3. 进入世界后，按 **F8 → 预编译 / 重建副管线**（见下面的“重要说明”），然后就可以 F7 秒切了。

## 重要说明

* **副管线需要你手动预编译一次**（0.3.2 起的策略）：进世界、Iris 重载、换维度都**不会**
  自动替你加载副管线。请在进入世界后按 F8 点「预编译 / 重建副管线」，或直接按 F7 让模组就地构建。
  原因是自动加载发生在“世界刚加载、主管线还没就绪”的时间窗里，那条路径会让实体渲染异常。
* **副管线会实打实占一份显存**（渲染目标 + 阴影贴图 + 全部程序），不做截图时它不产生 GPU 开销。
  核显建议只开一条副管线、并把截图分辨率控制在 1920×1080 ~ 2560×1440。
* **截图的“预热帧”**：部分光影包（如 MakeUp）刚加载时整屏很暗，需要连续渲染十几帧等它自己的
  “人眼适应”把亮度抬起来，所以取景前会先渲染若干帧（默认 20 帧，可调）。
* 兼容性上依赖 Iris 内部实现（注入点按 Iris 1.7.1 / 1.7.2 的真实成员写死），换大版本可能失效；
  模组检测到关键注入点失效时会**自动停用副渲染**并在聊天框说明，而不是把画面搞花。

## 从源码构建

这是一个标准的 Fabric Loom 工程：

```bash
./gradlew build          # Windows: gradlew.bat build
```

产物在 `build/libs/shader-flash-1.0.jar`。

> 开发环境：JDK 17（`--release 17`）、Gradle 8.8、Fabric Loom 1.6、Yarn 1.20.4+build.3。

## 目录结构

```
src/main/java/dev/shaderflash/     模组源码
src/main/resources/            资源：fabric.mod.json、mixin 配置、语言文件、图标
docs/                          设计说明与排查记录（原理、限制、每一次真实故障的证据链）
CHANGELOG.md                   版本记录
```

想了解内部原理，建议从 `docs/01-原理与实现.md` 开始；`docs/` 里还有一份
“所有玩家可见文本都在 `ModTexts.java`”的说明（`docs/18-文本与本地化.md`）。

## 已知限制

* Minecraft 不为每条管线各存一份区块网格：两包材质表不同时，重建期间屏幕上的地形材质分类会跟着变
  （截图功能同理，见 `docs/03-限制与风险.md`）；
* 副管线与主管线共享 Iris 的若干全局状态（材质表、顶点格式、阴影标志），
  模组在每次切换与渲染前后都做保存/还原，并把这些状态纳入自检；
* 只验证过 Minecraft 1.20.4 + Iris 1.7.1/1.7.2 + Sodium 0.5.8。

## 许可证

本项目以 **GNU Lesser General Public License v3.0 or later** 发布，全文见 [`LICENSE`](LICENSE)。

```
Shader Flash (闪光灯) —— Iris dual-pipeline mod
Copyright (C) 2026 Tenfrend_b

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU Lesser General Public License as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU Lesser General Public License for more details.
```

## 致谢

* **Iris Shaders** 与 **Sodium**：本模组是它们的附属模组，通过 Mixin 与反射调用其内部实现；
* **Mojang**：Minecraft 与原生截图实现（副管线截图的像素路径与文件名规则都沿用原版）；
* **FabricMC**：Fabric Loader / Loom 与示例模组模板（初版图标取自示例模组，仅作占位）。
