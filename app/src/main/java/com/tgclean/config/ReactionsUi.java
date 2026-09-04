package com.tgclean.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 表情过滤 UI 共用纯逻辑 —— TG 内 ⚡ 弹窗与 App 端规则编辑器的单一来源。
 *
 * 两个界面分属两个进程（TG 侧仅 framework 控件、App 侧 Material），控件层
 * 无法共享；但纯 Java 逻辑（token 解析、深度档位构建、输入钳制）双端必须
 * 逐字节一致，否则同一规则在两端显示/校验行为漂移。此类是这些逻辑的唯一
 * 定义处（proguard 已整体保留 com.tgclean.config.**）。
 */
public final class ReactionsUi {
    private ReactionsUi() {}

    /** 表情快速选择行（双端一致；点击行为由各自界面实现：目标行=切换选中，负面行=单选写入） */
    public static final String[] QUICK_EMOJIS = {"👍", "👎", "❤️", "🔥", "🥰", "😂", "🤩", "💯"};

    /**
     * 拆分空格/逗号分隔的表情 token：去重保序、过滤超长项（上限见
     * ReactionsRule.MAX_EMOJI_CODEPOINTS）。目标表情多选输入的统一入口。
     */
    public static List<String> parseEmojiTokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : s.trim().split("[\\s,，]+")) {
            if (t.isEmpty()) continue;
            if (t.codePointCount(0, t.length()) > ReactionsRule.MAX_EMOJI_CODEPOINTS) continue;
            // 纯 FE0F 变体符等剥除归一化后为空的 token 永远无法匹配,直接拒绝
            if (t.replace("\uFE0F", "").isEmpty()) continue;
            if (!out.contains(t)) out.add(t);
        }
        return out;
    }

    public static int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 深度自定义档输入解析：正整数钳制到 [MIN_DEPTH, MAX_DEPTH]；
     * 非数字超长串按上限钳制（10 位以上纯数字视为溢出输入），非法返回 -1。
     */
    public static int parseCustomDepthInput(String t) {
        int v;
        try {
            v = Integer.parseInt(t);
        } catch (NumberFormatException e) {
            v = t.matches("\\d{10,}") ? ReactionsRule.MAX_DEPTH : -1;
        }
        return v > 0 ? ReactionsRule.clampDepth(v) : -1;
    }

    /** 深度选择档位（构建结果 + 预填状态） */
    public static final class DepthChoices {
        /** 0=跟随全局默认，1..N=预设，customIdx=自定义 */
        public final String[] labels;
        public final int customIdx;
        public final int preselect;
        public final int prefillCustom;

        DepthChoices(String[] labels, int customIdx, int preselect, int prefillCustom) {
            this.labels = labels;
            this.customIdx = customIdx;
            this.preselect = preselect;
            this.prefillCustom = prefillCustom;
        }
    }

    /**
     * 构建深度下拉档位：首位"跟随全局默认（当前 X 条）"，中段预设，末位固定
     * "自定义…"（非预设历史值如 15000 落在该档并回填标签）。
     *
     * @param ruleMaxDepth 频道规则深度（0 = 跟随全局）
     */
    public static DepthChoices buildDepthChoices(int globalDepth, int ruleMaxDepth) {
        final int customIdx = ReactionsRule.DEPTH_PRESETS.length + 1;
        final String[] labels = new String[ReactionsRule.DEPTH_PRESETS.length + 2];
        labels[0] = "跟随全局默认（当前 " + ReactionsRule.formatDepth(globalDepth) + " 条）";
        for (int i = 0; i < ReactionsRule.DEPTH_PRESETS.length; i++) {
            labels[i + 1] = ReactionsRule.formatDepth(ReactionsRule.DEPTH_PRESETS[i]) + " 条";
        }
        labels[customIdx] = "自定义…";
        int preselect = 0;
        int prefillCustom = 0;
        if (ruleMaxDepth > 0) {
            for (int i = 0; i < ReactionsRule.DEPTH_PRESETS.length; i++) {
                if (ReactionsRule.DEPTH_PRESETS[i] == ruleMaxDepth) {
                    preselect = i + 1;
                    break;
                }
            }
            if (preselect == 0) {
                preselect = customIdx;
                prefillCustom = ruleMaxDepth;
                labels[customIdx] = "自定义（" + ReactionsRule.formatDepth(ruleMaxDepth) + " 条）";
            }
        }
        return new DepthChoices(labels, customIdx, preselect, prefillCustom);
    }
}
