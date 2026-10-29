-- 自动回复新增「转发」规则
--
-- 命中的 SCAN / subscribe 事件，由 wechat-admin 把微信原始 XML 原样 POST 到规则中
-- 配置的 https 地址，并跳过本地的关注 / 扫码处理。详见 docs/通用转发规则技术方案.md
--
-- match_mode: exact 精确 / contains 包含 / prefix 右模糊（前缀）。
--             NULL 表示历史规则，沿用 exact_match 的语义，不做回填。
-- event_type: SCAN / subscribe，NULL 表示不限。仅转发规则使用。
--
-- 只加列，不动既有列。本脚本可重复执行。

ALTER TABLE `wx_msg_reply_rule`
  ADD COLUMN IF NOT EXISTS `match_mode` varchar(16) DEFAULT NULL
    COMMENT '匹配模式：exact 精确 / contains 包含 / prefix 右模糊；NULL 沿用 exact_match',
  ADD COLUMN IF NOT EXISTS `event_type` varchar(16) DEFAULT NULL
    COMMENT '事件类型：SCAN / subscribe；NULL 表示不限，仅转发规则使用';
