# TGClean — 项目上下文

## 项目状态
- **仓库**: https://github.com/qxxwwyy/TGClean
- **正式版本**: v1.0.0
- **开发分支**: `feature/in-app-ui`（PR #1）
- **当前测试版本**: v19（RX-DEBUG 调试 + 表情匹配健壮化，versionCode 6 / 1.2.2）
- **构建**: GitHub Actions CI（ubuntu-latest + JDK 17），服务器 ARM64 无法本地构建
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
│ │  ├─ 复制频道ID菜单│          │ │   channels prefs  │
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
├── config/
│   ├── FilterConfig.java             # Hook 端只读配置（规则集+全局+白名单+Reactions）
│   └── FilterConfigWriter.java       # App 端可写配置（规则集CRUD+频道映射+旧数据迁移）
├── filter/
│   └── KeywordEngine.java           # 匹配引擎（规则集遍历+频道归属+关键词/正则）
├── hooks/
│   ├── ChatHelperHook.java          # ChatActivity hook（频道发现+复制ID菜单）
│   ├── KeywordFilterHook.java       # 双阶段过滤（构造函数标记+Adapter清理）
│   └── SponsoredMessageHook.java     # 赞助消息拦截
├── receiver/
│   └── ChannelReceiver.java          # BroadcastReceiver（接收频道发现数据）
└── ui/
    ├── SettingsActivity.java         # 主页（规则集列表+频道汇总+全局设置）
    └── RuleSetDetailActivity.java    # 规则集详情（关键词CRUD+频道勾选）

app/src/main/res/layout/
├── activity_settings.xml             # 主页布局
├── activity_rule_set_detail.xml     # 规则集详情布局
├── item_rule_set.xml                # 规则集列表 item
├── item_channel_summary.xml         # 频道汇总 item
├── item_channel_checkbox.xml        # 频道勾选 item
└── item_keyword.xml                 # 关键词 item
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
| `reactions_channel_rules` | JSON object | 每频道表情过滤规则 `{dialogId: {enabled, whitelist, emoji, minCount, emoji2, maxCount}}` |
| `migrated_legacy_v2` | boolean | 旧数据迁移标记 |

频道发现数据存储在 App 端 `discovered_channels` prefs（`channels_json` key）。

## 关键技术决策
1. **规则集为核心过滤单元**（v15+），关键词从规则集角度管理，频道变为被动方
2. **libxposed API 101**（非102，102未发布Maven Central）
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

## 发布规则
- **只有用户明确说明"正式版本"时才发布 GitHub Release**
- 其余所有修改：push → CI 构建 → 发 APK 到 Telegram 测试，**不创建 Release**
- v1.0.0 为当前唯一正式版本，后续版本需用户确认后才可发布

## 已知坑点
- libxposed API 101 无 `.after()`，void 方法用 `.intercept()` + `return null`
- `Class.forName()` 对 host 类不可用，必须 `tgClassLoader.loadClass()`
- `MODE_WORLD_READABLE` 在 Android 7.0+ 抛 SecurityException
- Hook 端 RemotePreferences 只读，edit() 抛 UnsupportedOperationException
- `resolveContentProvider()` 在 TG 进程返回 NULL（Android 11+ package visibility）
- `materialButtonTextStyle` attr 在 Material3 中不存在，用 `borderlessButtonStyle`
- CI artifact 名为 `TGClean-debug`（非 `app-debug`）
- Chromium snap 存根不可用，Playwright 路径含版本号可能过期

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
