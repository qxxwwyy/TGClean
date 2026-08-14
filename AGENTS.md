# TGClean — 项目上下文

## 项目状态
- **仓库**: https://github.com/qxxwwyy/TGClean
- **正式版本**: v1.0.0
- **开发分支**: `feature/in-app-ui`（PR #1）
- **当前测试版本**: v15（规则集系统重构）
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
| `reactions_filter_enabled` | boolean | Reactions 过滤开关 |
| `reactions_filter_emoji` | string | 目标 emoji |
| `reactions_filter_threshold` | int | 过滤阈值 |
| `migrated_legacy_v2` | boolean | 旧数据迁移标记 |

频道发现数据存储在 App 端 `discovered_channels` prefs（`channels_json` key）。

## 关键技术决策
1. **规则集为核心过滤单元**（v15+），关键词从规则集角度管理，频道变为被动方
2. **libxposed API 101**（非102，102未发布Maven Central）
3. **Java**（非Kotlin，参考项目全为Java，CI构建零障碍）
4. **双阶段过滤**（构造函数标记deleted=true + updateRowsSafe清理remove）
5. **component-explicit broadcast** 跨进程通信（绕过 Android 11+ package visibility）
6. **ChatActivity.onResume** 作为 hook 点（非 onCreateOptionsMenu，Telegram 不使用标准菜单系统）
7. **频道批量发现**：首次 onResume 反射 `MessagesController.dialogsChannelsOnly` 扫描所有频道
8. **BottomSheet 注入已废弃**（ClassLoader 冲突 + SharedPreferences 写入崩溃）
9. **ContentProvider 方案已废弃**（Android 11+ resolveContentProvider 返回 NULL）

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
