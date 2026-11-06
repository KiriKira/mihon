# Model-free spread evidence probe

这是 [设计讨论 #16](https://github.com/KiriKira/mihon/pull/16) 的**最小可执行消融探针**，不是新的跨页分类器。无需训练、无 Android/图像库/ML 依赖。生产阅读器没有调用它，现有切分行为不变。

## 已实现的内容

核心只有一份纯 Kotlin 实现：

- [`SpreadContinuityProbe.kt`](../../core/common/src/main/kotlin/tachiyomi/core/common/util/system/SpreadContinuityProbe.kt)
- [`SpreadContinuityProbeTest.kt`](../../core/common/src/test/java/tachiyomi/core/common/util/system/SpreadContinuityProbeTest.kt)

输入：调用者提供的 `IntArray` 灰度图（0..255）、宽高，以及**已知/候选**缝区间 `[seamStart,seamEnd)`。每个维度最多 512，输入借用且不修改。探针不负责定位接缝、解码、缩放、推断纸色或切分图片。

输出：测量状态、最佳上下偏移、完整相关性曲线、与远端竞争峰的差、搜索边界标记、四个高度区带的局部最佳偏移、固定全局偏移下的区带一致性计数。

**明确没有** `isSpread`、`shouldSplit`、概率、置信度或自动决策。`MEASURED` 只表示该特征能计算；`LOW_TEXTURE` 表示缺乏该种证据，不表示图片没有内容，更不表示该切。

## 算法定义

默认在缝左右各跳过 2 像素 guard，读取各 16 像素宽的条带。条带不完整时返回 `INSUFFICIENT_INTERIOR`，不补白、不裁成两个不同宽度。

每个条带逐行计算两条 profile，`n` 是条带宽度：

```text
luma[y] = sum(I[x,y]) / (255*n)
texture[y] = sum(abs(I[x+1,y]-I[x,y])) / (255*(n-1))
```

两条 profile 均按 `[1,2,1]/4` 沿 y 平滑。texture 是**行内水平方向总变差**，不是水平方向线段检测，也没有删除所有水平绘画内容的语义规则。

全局搜索整数偏移 `d ∈ [-M,M]`，默认 M=8、硬上限 16。正 d 表示右侧内容比左侧低，即比较 `left[y]` 和 `right[y+d]`。

所有偏移严格使用同一左侧支持区间：

```text
y ∈ [M+1, H-M-1)
```

保留一行供平滑使用；支持区至少 32 行。不会让大位移凭更短、更容易匹配的重叠区获利；没有循环 wrap 或移动后补零。

用 centered two-pass Pearson correlation 搜索 texture profile 的最佳偏移。两侧标准差任一小于 `1/255`（初始信息量门槛，可配置）时记缺测，不把低方差除法产生的 NaN/无穷或零当成有效匹配。相关性仅为描述统计量，截断到 [-1,1]。

数值相差不超过 `1e-12` 时优先较小绝对偏移，再优先较小的带符号偏移；该 tolerance 只是确定性 tie-break。另报告：

- `peakSeparation`：最佳分数减去离最佳偏移超过 2 像素的最佳竞争分数；没有竞争点则缺测。不是 z-score/p-value。
- `atSearchBoundary`：非零搜索范围的最优点碰到边界，不能据此认为范围外没有更好解。
- 将共同支持区分成四段，逐段执行同一搜索；记录局部最优偏移与距全局最优不超过 1 像素的段数。这些是相关的诊断量，不是独立投票，不套二项显著性。
- 同时输出零位移/最佳位移下的 luma correlation，便于比较“普通亮度相关”和“texture 相关”的失败模式。

只有实际输入变换没有裁剪、且标准差仍高于信息量门槛时，Pearson 对常数曝光偏移/正比例对比度变换具有相应不变性。非均匀阴影、曲线、倾斜和宽缺损不在此保证内。

## 为什么选这个小实验

- 普通跨缝亮度 profile 会被水平格框轻易骗过；测试明确构造出其相关性为 1。
- 一条横向均匀的格框行，其行内水平总变差为 0；这个通道可在该受控情形下报告缺证据，而非给出高相关。
- 但真实格框还有竖边、对白、网点，相似图案也可以共享同一 texture profile。**这不解决所有格框假匹配。**
- 测试还故意构造“像素图案不同、profile 完全相同、相关性为 1”的反例。它用于约束 API 不把分数升级成识别结论，而不是为了让算法看起来无所不能。
- 周期纹理会有多个高峰；返回竞争峰和偏移曲线供消融，不使用没有验证的循环置换零分布或固定 z>=3 保证。
- 三维几何、Sobel/NMS/链追踪和 32 维训练模型均未实现。先验证这个廉价通道有没有增量价值，再决定下一步。

白/灰/黑缝测试成立，是因为调用者指定的缝和 guard 根本不进入 profile；**它不证明探针能自动找到各种颜色的缝**。

## 运行

CLI 只接受**已经归一化**的单张 8-bit binary PGM (`P5`, maxval=255)，从而避免悄悄增加 ImageIO/JPEG 解码差异。可使用已确认的 Android 导出或其他明确记录的预处理来源；当前 CLI 不提供这样的 Android 导出器。

有 JDK 和 Kotlin CLI 时，从仓库根目录：

```sh
mkdir -p /path/to/scratch/spread-probe
kotlinc \
  core/common/src/main/kotlin/tachiyomi/core/common/util/system/SpreadContinuityProbe.kt \
  tools/spread-probe/SpreadProbeCli.kt \
  -include-runtime -d /path/to/scratch/spread-probe/probe.jar
java -cp /path/to/scratch/spread-probe/probe.jar \
  tachiyomi.core.common.util.system.SpreadProbeCliKt \
  /path/to/normalized.pgm 248 264 8 16
```

最后四个参数分别是 seamStart、seamEnd、maxShift、stripWidth；后两个可省略。输出一行表头和一行 CSV，空字段表示缺测。数字是分析图坐标，不是原始高分辨率坐标。

不安装 Android SDK 也可以跑 CLI 的真实进程检查：

```sh
python tools/spread-probe/check_cli.py \
  --classpath /path/to/scratch/spread-probe/probe.jar
```

`--java /path/to/java` 可指定 JDK。检查会在当前目录创建并自动清理临时目录，不接触漫画库。包含合法 PGM、CR/LF/CRLF header 边界、首像素恰好是换行/空格/#、CSV 文件名转义、已知错位，以及超尺寸/截断/多余数据等无效输入。

核心 JUnit 使用工程现有测试依赖，正常工程环境中：

```sh
./gradlew :core:common:testDebugUnitTest \
  --tests '*SpreadContinuityProbeTest'
```

## 资源界限

- 输入上限 512×512；`IntArray` 由调用方提供，最大像素载荷 1,048,576 bytes。
- 四条 Double profile，最大原始载荷 **16,384 bytes**；少量分数/结果对象和 JVM 对象头另计。没有整图梯度、图结构、全图副本或模型资产。
- 输入验证 O(W×H)，条带提取 O(H×stripWidth)，搜索 O(H×maxShift)；没有按像素分配对象。
- 在 H=512、M=16 时，最多 33 个全局偏移；全局搜索与四段搜索都覆盖固定行数。计入两次 luma 测量与两遍中心化，总计 65,008 次成对行访问；条带读取最多 32,768 像素次。它们不是 CPU 指令数或毫秒数。
- 不解码、不启动后台任务、不接阅读器，因此这个提交没有新增阅读时分析开销。生产内存、APK 增量、Android 延迟/耗电均未测。

## 已实际执行的验证

本地使用隔离的 JDK 21、Kotlin 2.1.0 编译器和 JUnit Platform 1.11.4 编译**原始 Kotlin 源码**执行：

- 新探针 21 项测试，全部通过。
- 当前 `DoublePageSpreadDetectorTest` 与修复后的 `SpreadPageStructureTest` 一起运行，总计 41 项全部通过。
- CLI 实际进程检查 26 个用例全部通过。
- 已知合成错位样例：bestShift=3、textureCorrelation=1.0、四段均同意；这只证明受控变换/实现契约，不是真实漫画准确率。
- 使用仓库相同版本 ktlint 1.8.0 格式检查；完整 Android/Gradle 验证由 PR CI 单列记录，不能用上述独立 JVM 测试代替。

## 真正决定去留的实验

在相同作品分组与冻结图源测试集上比较：

1. 当前已发布规则（legacy + 当前增强），不能只以 raw legacy 为基准。
2. 布局特征基线。
3. 布局 + luma profile。
4. 布局 + texture profile + 峰歧义/局部一致性诊断。

固定真跨页误切约束，报告普通双页正确切分覆盖率、UNKNOWN、耗时和最差图源；纯普通页难负例组报告错误保留率，不计算缺少正类的 ROC AUC。分组重采样不能把四个区带或合成变体当独立样本。

当前没有训练集/真实图源消融结果；代码的价值是提供可以被证伪的、开销可控的测量，而不是已经证明比当前算法更准。
