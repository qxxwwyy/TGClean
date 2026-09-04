# TGClean — 项目上下文

## 项目状态
- **仓库**: https://github.com/qxxwwyy/TGClean
- **正式版本**: v1.0.0、v2.0.0、v2.0.2、**v2.1.0（2026-09-03，多表情合计/进频道前配置/自动重进/徽标治理/API 102，versionCode 21）**（tag 触发 release.yml，APK 直传 Release）
- **构建**: GitHub Actions CI（ubuntu-latest + JDK 17 + AGP 9.2.1 + Gradle 9.4.1），服务器 ARM64 无法本地构建
- **测试设备**: Android 16，官方 Telegram（MIUI）

## 架构概述

### 过滤系统（Hook 端 — 运行在 Telegram 进程内）

```
消息进入 → MessageObject 构造函数 → KeywordEngine.shouldFilter()
  ├─ 白名单？→ 放行
  ├─ 规则集匹配？→ 遍历启用的规则集，检查频道归属，匹配关键词 → 过滤
  ├─ 旧版分频道规则（兼容迁移期）
  └─ 全局关键词兜底 → 过滤

过滤方式：标记 deleted=true → updateRowsSafe() 清理 → 零占位移除
```

### 跨进程通信架构

```
Telegram 进程（Hook 端）          TGClean App 进程
┌─────────────────────┐          ┌─────────────────────┐
│ ModuleMain          │          │ SettingsActivity    │
│ ├─ ChatHelperHook   │──Broadcast──► ChannelReceiver │
│ │  ├─ 频道自动发现  │          │ ├─ 存入 discovered_ │
│ │  ├─ ⚡表情过滤菜单 │─规则保存──► ├─ RemoteConfigStore│
│ │  │  (弹窗配置)    │          │ │  → 写 remote prefs│
│ │  └─ 首次批量扫描  │          │ ├─ SettingsActivity │
│ ├─ KeywordFilterHook│          │ │  ├─ 规则集管理    │
│ │  └─ 双阶段过滤   │          │ │  ├─ 频道汇总展示  │
│ ├─ SponsoredMsgHook │          │ │  └─ 全局设置      │
│ └─ FilterConfig(只读)│◄──XposedService── FilterConfigWriter │
│    RemotePreferences │          │    (可写RemotePrefs)│
└─────────────────────┘          │ ├─ RuleSetDetail   │
                                 │ │  ├─ 关键词CRUD   │
                                 │ │  └─ 频道勾选     │
                                 │ └─ FilterConfigWriter│
                                 └─────────────────────┘
```

### 关键约束
- **Hook 端 RemotePreferences 只读**，App 端 XposedService.getRemotePreferences() 可写
- **Android 11+ package visibility**：TG 进程无法解析 ContentProvider，只能用 component-explicit broadcast
- **ClassLoader 安全**：所有 host 类加载必须用 `tgClassLoader.loadClass()`，禁止 `Class.forName()`
- **libxposed API 101**：无 `.after()` 方法，void 方法 hook 必须用 `.intercept()` + `return null`
- **BottomSheet 注入方案已废弃**（ClassLoader 冲突 + SharedPreferences 写入崩溃）

## 源码结构

```
app/src/main/java/com/tgclean/
├── App.java                          # Application，管理 XposedService 连接
├── ModuleMain.java                   # 模块入口，初始化所有 Hook
├── RemoteConfigStore.java            # App 端写队列（XposedService 未就绪时排队重试）
├── config/
│   ├── FilterConfig.java             # Hook 端只读配置（规则集+全局+白名单+Reactions）
│   ├── FilterConfigWriter.java       # App 端可写配置（规则集CRUD+频道映射+旧数据迁移）
│   ├── ReactionsRule.java            # 表情规则模型 + JSON/Intent 契约单一来源
│   └── ReactionsUi.java              # 表情 UI 共用纯逻辑（TG 弹窗与 App 编辑器双端一致）
├── filter/
│   └── KeywordEngine.java           # 匹配引擎（规则集遍历+频道归属+关键词/正则）
├── hooks/
│   ├── ChatHelperHook.java          # 频道发现 + ⚡菜单（频道内弹窗+会话列表注入）+保存链
│   ├── KeywordFilterHook.java       # 消息数组边界过滤（v18）+ 级联深挖 + 进度徽标
│   └── SponsoredMessageHook.java     # 赞助消息拦截
├── receiver/
│   └── ChannelReceiver.java          # BroadcastReceiver（频道发现 + 规则保存写入）
└── ui/
    ├── SettingsActivity.java         # 主页（规则集列表+频道汇总+全局设置+长按编辑）
    ├── RuleSetDetailActivity.java    # 规则集详情（关键词CRUD+频道勾选）
    └── WriteConfigActivity.java      # 规则写入兜底（透明页,MIUI 自启动拦截场景）

app/src/main/res/layout/
├── activity_settings.xml             # 主页布局
├── activity_rule_set_detail.xml     # 规则集详情布局
├── item_rule_set.xml                # 规则集列表 item
├── item_channel_summary.xml         # 频道汇总 item
├── item_channel_checkbox.xml        # 频道勾选 item
├── item_keyword.xml                 # 关键词 item
└── item_show_all.xml                # 频道列表"显示全部"footer
```

## 数据存储（SharedPreferences `tgclean_config`）

| Key | 类型 | 说明 |
|-----|------|------|
| `filter_enabled` | boolean | 总开关 |
| `use_regex` | boolean | 全局正则（兜底关键词用） |
| `rule_sets` | JSON array | 规则集定义 `[{id, name, enabled, use_regex, keywords[]}]` |
| `rule_set_channels` | JSON object | 规则集↔频道映射 `{ruleSetId: [dialogId, ...]}` |
| `global_keywords` | string | 全局关键词（换行分隔，兜底匹配） |
| `whitelist` | JSON array | 白名单频道 ID |
| `reactions_channel_rules` | JSON object | 每频道表情过滤规则 `{dialogId: {enabled, whitelist, emoji, emojiSet, minCount, emoji2, maxCount, maxDepth}}`（字段语义见 ReactionsRule 类注释） |
| `reactions_search_depth` | int | 表情筛选全局默认检索深度（条，默认 500） |
| `debug_log` | boolean | 调试日志开关（默认关） |
| `pairing_token` | string | 写通道配对令牌（UUID，框架数据目录） |
| `migrated_legacy_v2` | boolean | 旧数据迁移标记 |

频道发现数据存储在 App 端 `discovered_channels` prefs（`channels_json` key）。

## 关键技术决策
1. **规则集为核心过滤单元**（v15+），关键词从规则集角度管理，频道变为被动方
2. ~~libxposed API 101（非102）~~ **已被决策 #18 取代（2026-09-03 起升级 102）**
3. **Java**（非Kotlin，参考项目全为Java，CI构建零障碍）
4. **消息数组边界过滤**（v18，取代已废弃的双阶段方案）：hook ChatActivity 的 `didReceivedNotification_messagesDidLoad`（全部加载路径，args[1]=count/[2]=ArrayList/[14]=mode，仅 mode=0 过滤）与 `processNewMessages`（实时新消息+赞助），在消息进入 messages 列表前用 `chain.proceed(newArgs)` 剔除。两个方法均为超大方法，R8 不会内联
   - ⚠️ 已废弃的 v15~v17 双阶段方案（ctor 标记 deleted=true + updateRowsSafe 移除）失效根因：历史加载/重进走 L21414 直接调 `updateRowsInternal()` 绕过 updateRowsSafe；且 deleted=true 会被渲染层消费（L34646 跳过 cell 重绑定）→ 媒体显示但气泡空白的"半隐藏"状态
5. **component-explicit broadcast** 跨进程通信（绕过 Android 11+ package visibility）
6. **ChatActivity.onResume** 作为 hook 点（非 onCreateOptionsMenu，Telegram 不使用标准菜单系统）
7. **频道批量发现**：首次 onResume 反射扫描频道，优先 `getAllDialogs()+DialogObject.isChannel()`（覆盖频道+megagroup），回退 `dialogsChannelsOnly`（仅广播频道）
8. **BottomSheet 注入已废弃**（ClassLoader 冲突 + SharedPreferences 写入崩溃）
9. **ContentProvider 方案已废弃**（Android 11+ resolveContentProvider 返回 NULL）
10. **过滤不做跨实例去重**（v16）：同一 TL 消息会多次构造 MessageObject（通知预览/会话列表/聊天窗口），按 (dialogId,msgId) 去重跳过标记会导致打开频道时漏过滤"复活"，每实例独立评估
11. **每频道表情过滤**（v17，取代旧全局 reactions）：白名单模式只显示达标消息（如 ❤️≥10 且 👎≤20），黑名单模式隐藏达标消息；在 TG 频道菜单直接配置（headerItem 注入 ⚡ 项 + framework AlertDialog 弹窗 + 表情快速选择行），保存经显式广播 → ChannelReceiver → RemoteConfigStore（服务未就绪排队）写 remote prefs → 框架实时推送回 TG 进程热更新；匹配基于 TL_reactionEmoji.emoticon 字符串精确相等，自定义表情(document_id)/付费星星无法匹配
12. **检索深度两级配置**（v29）：筛选后剩行过少时级联自动向前翻找的消息条数上限。全局默认存 remote prefs `reactions_search_depth`（App 设置页 ⚡表情筛选 卡片，App 端 FilterConfigWriter 写 / hook 端 FilterConfig 读）；每频道覆盖存规则 JSON 字段 `maxDepth`（0=跟随默认，TG 内 ⚡弹窗单选行）。预设 {500,1000,2000,5000,1万}+自定义输入（钳制 100~10万，v2.0.2），默认 500（级联迭代修复后 500 已够用，深度直接决定流量与缓存占用）；额度=深度/批大小(100) 动态计算。频道启用表情规则且设了深度时，该频道一切级联（含关键词触发的）都按频道深度；无规则/未启用时用全局默认
13. **写通道配对令牌**（v2.0.0）：App 端 FilterConfigWriter.ensurePairingToken() 首次生成 UUID 写 remote prefs（框架数据目录，第三方不可读）；TG 侧 FilterConfig 只读后随保存广播 `token` extra 带回，写 op 内比对一致才落盘，防任意 App 伪造 intent 改写规则；首次保存（令牌刚生成、推送未达 TG）信任首次。回执另带一次性 `nonce` 防伪，TG 侧菜单标题在回执确认后才更新
14. **调试日志默认关**（v2.0.0，remote prefs `debug_log`）：FILTERED 逐条明细（含消息预览）、RX-DEBUG、启动关键词 dump 仅在开启时输出，用户通信内容不进 LSPosed 日志；批量摘要始终保留
15. **无"保存"按钮**（v2.0.0）：全 App 即改即存（remote prefs 实时推送架构下保存是伪概念），总开关拨动即写 + Snackbar 反馈；否则 100+ 频道时按钮沉底要滑很久
16. **频道列表默认折叠 10 条**（v2.0.0）：超过 10 条显示"显示全部 N 个频道"footer；搜索自动展开、清空恢复折叠；搜索 200ms 防抖 + 数据缓存内存过滤（不重复解析 JSON）
17. **级联额度实例内单调**（v2.0.2，日志确证的"从头再扫"根因）：健康批次（存活行≥5，多为用户上滑的原生回包）只休眠链（解除单飞+撤徽标+锚点 merge min），不清 cascadeCount/cascadeFound/terminalNotified——全清会让交替健康/滤空批次反复授满额度、徽章进度归零重启（用户观感 500/深度→100/深度 重扫）。僵尸链治理：看门狗预算 40→8（压栈实例请求永无推进响应，只能烧满预算自灭）+ cascadeActiveActivity 弱引用记录现任开火实例（terminal 链不占用身份），旧实例看门狗软让位（只解除单飞/停 re-post，状态全保留，回频道可无损续链——硬清会让快速 A→B→A 复现进度归零，审计 v2.0.2 B-1）。已知局限：重进频道产生新 classGuid，前沿不跨实例继承（缓存内重滤、开销可接受，dialogId 键迁移有跨实例污染/空视图死锁风险，暂不做）
18. **libxposed API 102 迁移**（v2.1.0，2026-09-03）：api/service 升 102.0.0——102 是 101 严格编译兼容超集，模块代码零改动（对照两版源码 tag 逐类核实）。配套：compileSdk 37（102 AAR minCompileSdk 硬要求）+ buildTools 37 + AGP 9.2.1；**102 AAR 不再自带模块侧 proguard 规则**，`-adaptresourcefilecontents java_init.list`/keep XposedModule 子类/`-dontwarn annotation` 三条已自补进 proguard-rules.pro（缺失时 release 混淆包入口类改名→模块装上失效而 debug 包正常，极隐蔽）。module.prop `targetApiVersion=102`+`minApiVersion=101`：JingMatrix/Vector 系框架对 target 无上限检查、101 框架照常加载（已核源码），但勿调用任何 102 专属 API（detach/setId/replaceHook/onHotReloading*），调用须 `getApiVersion()>=102` 门控。AGP 9.0.1→9.2.1 为 SDK 37 已验证组合

## 开发分支工作流（必须遵守）
1. **一切开发/修复先在测试分支进行**：提交推送到 `feature/*` 测试分支，**禁止直接推送 main**
2. **测试分支建 PR 指向 main 并保持打开**：PR 是 CI 的构建载体（push 到 PR 分支触发 build.yml debug+release 双构建），测试 APK 从 PR 的 Actions 产物 `TGClean-debug` / `TGClean-release` 下载发给用户实测
3. **模块代码变更编译前必须子代理独立审计**（站立规则）：审计有 BLOCKER/MAJOR 先修再编译提交
4. **用户实测确认 + 明确说"推送正式版/合并"才动 main**：合并 PR → 打 `v*` tag → release.yml 自动 APK 直传 Release；用户没说就停在测试分支，不合并、不发 Release
5. 上一轮 PR 合并后，新一轮开发重新建 PR 作为 CI 载体（直接 push 裸分支不触发 CI）

## 发布规则
- **签名(2026-09-02 定案)**:全新密钥只存 GitHub Secrets(`TGCLEAN_KS_B64`=keystore 的 base64,`TGCLEAN_KS_PASS`=密码),build.yml/release.yml 均有解码注入步骤;**私钥原件与密码存于 K70 `/home/ubuntu/tgclean-signing/`(600 权限,须纳入备份,丢失=换签名全员重装)**。Secrets 缺失时 build.yml 会黄字警告并回落 debug 签名(临时 runner 密钥,产物不可覆盖安装,视为 CI 红灯处理)。旧 v2.0.2 keystore+密码已随公开 git 历史永久泄露(d3c71fc 可提取),正式签名自 2026-09-02 起换新钥,升级需卸载重装一次。
- **只有用户明确说明"正式版本"时才发布 GitHub Release**
- 其余所有修改：push → CI 构建 → 发 APK 到 Telegram 测试，**不创建 Release**
- 正式发布流程（v2.0.0 起）：feature 分支审计通过 → 合并 PR 进 main → 打 `v*` tag → release.yml 自动构建 release APK 并**原样 .apk 直传** GitHub Release（不打包 zip）；build.yml 在每次 push/PR 同时构建 debug+release 验证 R8 路径
- 签名 keystore 随仓库公开（个人项目取舍），密码可用环境变量 `TGCLEAN_KS_PASS` 覆盖

## 已知坑点
- libxposed API 101 无 `.after()`，void 方法用 `.intercept()` + `return null`
- `Class.forName()` 对 host 类不可用，必须 `tgClassLoader.loadClass()`
- `MODE_WORLD_READABLE` 在 Android 7.0+ 抛 SecurityException
- Hook 端 RemotePreferences 只读，edit() 抛 UnsupportedOperationException
- `resolveContentProvider()` 在 TG 进程返回 NULL（Android 11+ package visibility）
- `materialButtonTextStyle` attr 在 Material3 中不存在，用 `borderlessButtonStyle`
- CI artifact 名为 `TGClean-debug`（非 `app-debug`）
- Chromium snap 存根不可用，Playwright 路径含版本号可能过期
- **级联防重复不能靠"到达批次清在途标记"**（v28→v29 教训）：高阈值整批滤空时 TG 空视图自身会高频重取最新窗口，这些非推进批次若触发发起，重复回包又触发更多发起——130ms 内同锚点 11 个在途请求，stuck 计数被自己的重复回包 10 连击烧断。正确纪律：只有"前沿推进"（= 响应已落地）才发起下一次；非推进批次纯忽略；丢包恢复交给时间看门狗（CASCADE_STALE_MS 重发，预算封顶）
- **级联锚点不能在健康批次时重置**：重置后 TG 最新窗口重取会以 prev==null 身份重新"推进"，把已扫描范围整段重扫。锚点语义应为"本实例见过的最老消息 id"，单调不升、实例生命周期内不重置
- **级联链必须在 onFragmentDestroy 清理**（v29→v30 教训）：看门狗 Handler 自续引用会苟过实例销毁，观察者已注销→响应永不推进→每 4.25s 空转重发至预算耗尽；日志实测同频道 10+ 条僵尸链并发 170s，load 风暴拖慢真实级联（"时灵时不灵"的直接诱因）。双保险：hook onFragmentDestroy 清全部 per-guid 状态 + 看门狗 getParentActivity()==null 兜底自检
- **TG 所有 ChatActivity 共宿主一个 LaunchActivity**：悬浮徽标挂 android.R.id.content 天然全局唯一，前台频道即语义归属者；退出频道不主动撤徽标（防误撤他台），靠 10s 无更新自动消失兜底

## v16 review 结论（2026-08-14，对照 TG master 12.9.2 + libxposed 101.0.1）
**修复的 bug**：
- KeywordFilterHook：删除 pendingFilterIds 跨实例去重（复活漏过滤），日志去重改 (dialogId,msgId) 组合 key
- ChatHelperHook：批量扫描改 getAllDialogs()+isChannel()，修复 megagroup 不被发现
- RuleSetDetailActivity：writer/currentRuleSet 未就绪时勾选频道/添加关键词 NPE 崩溃
- FilterConfigWriter：删除 getDiscoveredChannels()（读错存储的死代码）
- SponsoredMessageHook：误导性日志文案

**对照源码确认有效、勿再怀疑的设计**：
- `ChatActivity.messagesDict` 是 `SparseArray[]`（两份），`instanceof Object[]` 遍历 + `remove(int)` 有效
- `ActionBarMenuItem.addSubItem(int,int,CharSequence)` 存在（ActionBarMenuItem.java L524），headerItem 注入有效
- `BaseFragment` 有 `getContext()`（L658），ChatActivity 是 Fragment 非 Activity
- `MessagesController.getSponsoredMessages(long)` 返回 null 安全（两个调用点都判 null）
- `updateRowsSafe()` 是 `ChatActivity$ChatActivityAdapter` 的 public 方法
- 现代模块打包：`META-INF/xposed/{java_init.list,module.prop,scope.list}`，manifest 无需 xposed meta-data
- API 101（Maven Central `hook().intercept(lambda)` 模型）依赖 LSPosed 新分支实现（上游 stable 1.9.2 仅支持注解式 API 100）；本机 Android 16 环境已实测可用
- RemotePreferences 变更由框架按 key 实时推送到 hook 进程，KeywordEngine 快照热更新，配置无需重启 TG
