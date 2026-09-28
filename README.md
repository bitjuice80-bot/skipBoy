# 少年Jump —— 低资源开屏广告自动跳过（Android 无障碍）

基于 **Android AccessibilityService** 的开屏广告 / 弹窗自动跳过工具。应用显示名「少年Jump」，工程名与包名保持 `SkipAd` / `com.skipad` 不变（改包名会牵动全部源码，无收益）。事件驱动、零轮询、运行时零联网，通过归一化整合 **李跳跳规则** 与 **GKD 订阅** 两套社区规则并去重，降低规则总量与资源消耗。

> 当前状态：功能可跑通（打包/安装/订阅更新/调试浮窗均已验证），订阅解析与个别图标类弹窗仍在调优中。

---

## 一、为什么低资源

设计三铁律（见 `SkipAdService`）：

1. **事件驱动**：仅在系统 `onAccessibilityEvent` 回调里处理，绝不起线程轮询；
2. **以活跃窗口为过滤基准**：事件包名（可能来自状态栏/桌面/文件选择器）≠ 实际搜索的窗口，以 `getRootInActiveWindow()` 的包名为准——**自身窗口直接忽略**（设置页日志框文本含"跳过/关闭"，仅按事件包名过滤拦不住跨包事件）、桌面/状态栏/AOD/输入法等永无广告的窗口拉黑；`CONTENT_CHANGED` 按**窗口包名** 200ms 去抖，`STATE_CHANGED`（新窗口出现，最关键的跳过时机）始终立即处理；
3. **单趟遍历 + 延迟点击**：一次 DFS 同时匹配当前包名下全部候选规则（此前每条规则各遍历一次整树，12 条全局规则的开销是现在的 12 倍），每个节点只取一次文本供所有规则复用，带 4000 节点安全上限；命中后延迟 ~300ms 再点，规避按钮尚在加载时的误触；**点击调度后 450ms 冷却**，同一控件不会被多事件源连点（实测曾 1 秒内把 B 站弹幕开关点掉 10 次）。

其余常驻优化：规则查询走零分配快路径（包名分片 + 合并结果缓存，无规则包静默返回，不产生日志与分配）；调试日志时间戳按秒缓存、无规则事件不打日志。

匹配器（`RuleMatcher`）命中文字节点后，会**向上回溯最多 6 层**找第一个 `isClickable` 的祖先节点点击——因为“跳过”按钮的热区往往在容器/卡片/整屏上，不只父级一层。

---

## 二、项目结构

```
SkipAd/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/skipad/
│       │   ├── SkipAdService.java          # 无障碍服务核心：事件驱动入口
│       │   ├── model/InternalRule.java      # 统一规则模型（双源归一后只认它）
│       │   ├── engine/
│       │   │   ├── SelectorEngine.java      # GKD 选择器子集解析/求值
│       │   │   └── RuleMatcher.java         # 单条规则匹配与（延迟）点击
│       │   ├── rule/
│       │   │   ├── RuleNormalizer.java      # 李跳跳 / GKD 双源归一化
│       │   │   └── RuleEngine.java          # 规则加载 / 按包名分片 / 内存常驻
│       │   ├── merger/Merger.java           # 跨源指纹去重合并
│       │   ├── subscriber/Subscriber.java   # 双源更新 + 版本/ETag 检测
│       │   ├── ui/
│       │   │   ├── SettingsActivity.java    # 设置页（开关/更新/导入/导出日志）
│       │   │   └── DebugOverlay.java        # 可拖动/缩放/隐藏的悬浮调试窗
│       │   └── util/DebugLog.java           # 环形缓冲日志（设置页 + 浮窗双显示）
│       ├── res/
│       │   ├── raw/rules_builtin.json       # 内置通用规则（不依赖网络）
│       │   ├── mipmap-*/                    # 启动图标（跳跃少年，mdpi~xxxhdpi 共10个PNG）
│       │   ├── xml/accessibility_service_config.xml
│       │   ├── layout/activity_settings.xml + overlay_debug.xml
│       │   └── values/strings.xml
├── tools/gen_icon.py                        # 图标源图 → 各密度 mipmap 切图脚本（Pillow）
├── build.gradle / gradle.properties / settings.gradle
└── README.md
```

**技术栈**：纯 Java（JDK 17）、AGP 8.5.2、minSdk 24 / targetSdk 34、**零额外依赖**（仅用框架内置 `org.json`）。

---

## 三、规则系统

| 来源 | 格式 | 说明 |
|---|---|---|
| 内置 `rules_builtin.json` | 统一内部格式 | 12 条全局通用文案（跳过/关闭/继续浏览/残忍拒绝…），**离线即用**；裸「关闭」用全等匹配（TEXT_EQUALS），只命中独立的"关闭"按钮，避免误伤"关闭弹幕"等含"关闭"字样的控件 |
| 李跳跳规则 | `AllRules.json` | 顶层数组，key 是**包名 hashCode**，value 是转义 JSON 字符串；用 `PackageManager` 反查真实包名 |
| GKD 订阅 | `ganlin_gkd.json5`（JSON5） | 含 `globalGroups` / `apps[].groups[].rules[].matches` 高级选择器 |

**归一化 + 去重**：两套异构源都转成 `InternalRule`；`RuleNormalizer` 把简单选择器 `[text*="跳过"]` 识别为 `TEXT_CONTAINS`，使其与李跳跳同义规则**指纹一致**；`Merger` 按 `包名|归一化匹配|动作` 去重，李跳跳→GKD 顺序下 GKD 同指纹优先。

> 镜像源（针对大陆网络）：李跳跳走 `fastly.jsdelivr.net` → `cdn.jsdelivr.net` → `raw` 三级兜底；GKD 主订阅走阿里 `registry.npmmirror.com`（国内直连）。GKD 版本探测为尽力而为，失败不阻断主流程。

---

## 四、构建与打包

Android Studio 打开 `SkipAd/` 根目录（识别 `settings.gradle`）：

```
Build → Build App Bundles or APKs → Generate APK(s)
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 改了 Java 但编辑器仍飘红，多为 AS 静态缓存：先 `Build → Rebuild Project`，仍红则 `File → Invalidate Caches / Restart`。

---

## 五、安装与权限

1. 安装 APK：`adb install app/build/outputs/apk/debug/app-debug.apk`
2. 开启无障碍：设置 → 系统/辅助功能 → 已下载的服务 → **少年Jump** → 开启。
   - **Android 13+ 离线安装**：默认禁用无障碍，需在「设置 → 应用 → 少年Jump → ⋮ → 允许受限设置」后再开启。
3. 电池白名单：设置页「电池白名单引导」→ 取消优化，防止服务被后台杀。
4. 调试浮窗（可选）：设置页开关「调试浮窗」→ 首次跳系统授权页允许「在其他应用上层显示」。

---

## 六、离线部署（隔离网络场景）

订阅源在严格隔离环境拉不到时，用内置的**导入本地规则文件**：

1. 在能联网的机器上把 `AllRules.json` / `ganlin_gkd.json5` 下载好，拷到手机；
2. 设置页「导入本地规则文件」→ 自动识别李跳跳 / GKD / 统一内部格式 → 写本地库并 reload；
3. 全程不依赖网络，适合工业/内网隔离机。

---

## 七、调试

- **悬浮调试窗**：可拖动（标题栏）、可缩放（右下角手柄）、可隐藏（右上角 ×），实时显示 `Detect / Match / Click` 日志。
- **设置页日志框**：不开启浮窗也能在设置页看实时日志。
- **日志导出**：设置页「导出日志」→ 系统选择器存为 `skipad_log_时间.txt`（免存储权限）。
- **adb**：`adb logcat -s SkipAd`。

日志关键行：
```
[Detect] 打开/检测到界面 包名=X 事件=STATE/CONTENT 候选规则数=N
[Match]  命中候选 包名=X 类型=TEXT_CONTAINS 值="跳过" 命中节点数=N
[Click]  真实点击 文本="..." 命中可点击祖先=true/false
[Sub]    订阅完成 李跳跳=N条 GKD=M条 合并去重=K条
[Engine] 规则加载完成 内置=N条 订阅合并库=M条 总计=K条
```

---

## 八、已知问题与排查

| 现象 | 排查方向 |
|---|---|
| 更新订阅 timeout | 大概率是 `raw.githubusercontent.com` 被墙；已改为 jsDelivr/npmmirror 镜像 |
| **候选规则数恒 = 内置条数** | 订阅合并库没生效：看 `[Sub]` 行 GKD 是否 = 0（JSON5 清洗问题）或 `[Engine]` 行「订阅合并库」是否 = 0（写盘/读盘问题） |
| 设置页日志框被误点 | 已修复：过滤以**活跃窗口包名**为准、自身窗口直接忽略——旧逻辑只比对事件包名，事件来自 launcher/systemui 时会绕过自过滤去搜设置页窗口 |
| B 站弹幕开关等控件被误点 | 已修复：裸「关闭」改为全等匹配（不再命中”关闭弹幕/已关闭弹幕”），且点击调度后有 450ms 冷却防连点 |
| 图标类 × 关不掉 | 文本规则够不着；依赖 × 的 `contentDescription`（全等”关闭”仍覆盖）或 GKD 按资源 id 的专项规则 |

---

## 九、免责声明

本工具仅用于自动跳过用户自身设备上的开屏广告/弹窗，规则来自公开社区订阅，请遵守各 App 服务条款与当地法规，勿用于绕过付费或违规场景。
