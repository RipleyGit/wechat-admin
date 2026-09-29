-- 推送网关鉴权从「每个通道一个密钥」改为「每个公众号一个推送密钥」
--
-- 网关先按请求头 X-Notify-Secret 找到公众号，再在该公众号下按 code 找通道。
-- 同一个公众号的通道共用一个密钥，所以每个通道另加一个 gateway_enabled 开关，
-- 只有打开的通道能被网关触发；页面手动发送不受这个开关影响。
--
-- 只加列不删列，新旧两版 jar 都能跑在这份表结构上：
-- 先执行本脚本，再发布新版本。wx_notify_channel.secret 已不再使用，
-- 等新版本稳定后再单独删。本脚本可重复执行。

-- 推送密钥明文存储（已确认的取舍：页面上可以随时查看、复制）。
-- 用 NULL 表示未生成，唯一索引允许多个 NULL，所以没生成的公众号不会互相冲突。
ALTER TABLE `wx_account`
  ADD COLUMN IF NOT EXISTS `notify_secret` varchar(64) DEFAULT NULL
    COMMENT '推送网关密钥，对应请求头 X-Notify-Secret；NULL 表示未生成';
CREATE UNIQUE INDEX IF NOT EXISTS `uk_notify_secret` ON `wx_account` (`notify_secret`);

-- 默认关闭：新建的通道要在页面上显式打开，才会被网关触发
ALTER TABLE `wx_notify_channel`
  ADD COLUMN IF NOT EXISTS `gateway_enabled` tinyint(1) NOT NULL DEFAULT 0
    COMMENT '是否允许通过推送网关触发' AFTER `enabled`;

-- code 改为在公众号内唯一：通道挂在公众号下面，不同公众号可以有同名通道。
-- 先建新索引再删旧索引，中间任何时刻 code 都有唯一约束。
CREATE UNIQUE INDEX IF NOT EXISTS `uk_appid_code` ON `wx_notify_channel` (`appid`, `code`);
DROP INDEX IF EXISTS `uk_code` ON `wx_notify_channel`;

-- 部署通知的两个通道要从网关进来，打开开关
UPDATE `wx_notify_channel` SET `gateway_enabled` = 1
WHERE `code` IN ('deploy-success', 'deploy-failure');
