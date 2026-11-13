-- 通用消息推送：调用方驱动的模板消息推送
--
-- /wx/push/template 让外部系统按 templateId + 收件人实时推送，
-- 与通道驱动的 /wx/notify/{code} 并存。详见 docs/模板消息推送设计方案.md
--
-- 只加表/加列/加索引，不动既有列。本脚本可重复执行。

-- 批次表：一次 /wx/push/template 调用一条。审计用，保留原始请求体与计数。
CREATE TABLE IF NOT EXISTS `wx_push_batch` (
  `id`              bigint(20)   NOT NULL AUTO_INCREMENT,
  `batch_id`        varchar(32)  NOT NULL COMMENT '业务批次号，uuid 去横线',
  `appid`           varchar(64)  NOT NULL,
  `client_msg_id`   varchar(128) DEFAULT NULL COMMENT '调用方幂等键；(appid, client_msg_id) 唯一',
  `send_type`       varchar(16)  NOT NULL DEFAULT 'template' COMMENT 'template | kefu（预留）',
  `template_id`     varchar(100) DEFAULT NULL,
  `source`          varchar(16)  NOT NULL DEFAULT 'api' COMMENT 'api | manual',
  `status`          varchar(16)  NOT NULL COMMENT 'success | partial | failed | rejected',
  `request_payload` longtext     DEFAULT NULL COMMENT '原始请求体 JSON，含手机号等 PII，接口不对外暴露查询' CHECK (json_valid(`request_payload`)),
  `requested_count` int(11)      NOT NULL DEFAULT 0 COMMENT '请求里各数组元素总数',
  `resolved_count`  int(11)      NOT NULL DEFAULT 0 COMMENT '解析去重后的 openid 数',
  `success_count`   int(11)      NOT NULL DEFAULT 0,
  `fail_count`      int(11)      NOT NULL DEFAULT 0,
  `error_msg`       varchar(1000) DEFAULT NULL COMMENT '聚合错误，含缺失/取关清单',
  `client_ip`       varchar(64)  DEFAULT NULL,
  `user_agent`      varchar(255) DEFAULT NULL,
  `create_time`     datetime     NOT NULL DEFAULT current_timestamp(),
  `update_time`     datetime     NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_id` (`batch_id`),
  UNIQUE KEY `uk_appid_client_msg_id` (`appid`, `client_msg_id`),
  KEY `idx_appid_create` (`appid`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用推送批次';

-- 逐条发送结果挂在 wx_template_msg_log 上，补两列关联批次与来源。
-- template_id 现是 varchar(50)，微信模板 ID 约 43 字符够用，但与 wx_msg_template 对齐放宽到 100。
ALTER TABLE `wx_template_msg_log`
  ADD COLUMN IF NOT EXISTS `batch_id` varchar(32) DEFAULT NULL COMMENT '关联 wx_push_batch.batch_id',
  ADD COLUMN IF NOT EXISTS `source`   varchar(16) DEFAULT NULL COMMENT 'api | manual',
  MODIFY COLUMN `template_id` varchar(100) NULL DEFAULT NULL COMMENT 'templateid';

CREATE INDEX IF NOT EXISTS `idx_appid_batch` ON `wx_template_msg_log` (`appid`, `batch_id`);

-- 收件人按手机号/昵称解析走 wx_user。phone 是 char(11) 精确等值，nickname 模糊查询用不到前缀索引，
-- 这里只为 phone/nickname 的等值 IN 查询加 (appid, phone) 复合索引，nickname 留作全扫描兜底（粉丝量不大时可用）。
CREATE INDEX IF NOT EXISTS `idx_appid_phone`    ON `wx_user` (`appid`, `phone`);
CREATE INDEX IF NOT EXISTS `idx_appid_nickname` ON `wx_user` (`appid`, `nickname`);

-- wx_msg_template.name 原是全局唯一，而 MsgTemplate(WxMpTemplate, appid) 把 name 写成 templateId，
-- 不同公众号插入同名模板会冲突，自动拉取模板的前置条件。先删全局唯一，再加 (appid, name)。
ALTER TABLE `wx_msg_template` DROP INDEX IF EXISTS `idx_name`;
CREATE UNIQUE INDEX IF NOT EXISTS `uk_appid_template_id` ON `wx_msg_template` (`appid`, `template_id`);
CREATE INDEX IF NOT EXISTS `idx_appid_name` ON `wx_msg_template` (`appid`, `name`);

-- v1 只做接口，没有管理端页面，因此不种 sys_menu 权限码。
-- 若后续做推送批次/日志查询页，权限码需同步种进 sys_menu，否则超管也是 403。
