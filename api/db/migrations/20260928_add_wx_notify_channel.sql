-- 通用消息推送：通道配置 + 推送记录
-- 把原先写死在 DeployWebhookController.buildNotifyText 里的部署通知文案搬到数据库，
-- 让任何机器都能 POST /wx/notify/{code} 触发推送，页面上也能手动发。

-- 通道表：一个通道 = 一个事件（一套收件人 + 一套文案）
-- secret 为空串表示不开放网关，只能在页面上手动发。
-- 代码里用 StringUtils.hasText 判断，空串和 NULL 走同一个分支，不用区分。
CREATE TABLE IF NOT EXISTS `wx_notify_channel` (
  `id`               bigint(20)    NOT NULL AUTO_INCREMENT,
  `appid`            varchar(64)   NOT NULL,
  `code`             varchar(64)   NOT NULL COMMENT 'URL 路径片段，全局唯一',
  `name`             varchar(64)   NOT NULL,
  `secret`           varchar(128)  NOT NULL DEFAULT '' COMMENT '该通道独立密钥，对应请求头 X-Notify-Secret；空串表示不开放网关',
  `enabled`          tinyint(1)    NOT NULL DEFAULT 1,
  `send_type`        varchar(16)   NOT NULL DEFAULT 'kefu' COMMENT 'kefu | template',
  `recipient_type`   varchar(16)   NOT NULL DEFAULT 'tag' COMMENT 'tag | openid',
  `recipient_value`  varchar(512)  NOT NULL COMMENT '标签名，或逗号分隔的 openid',
  `content_mode`     varchar(16)   NOT NULL DEFAULT 'render' COMMENT 'render | passthrough',
  `content_template` text          DEFAULT NULL COMMENT '带 {var} 占位符的文案，客服消息用',
  `template_id`      varchar(128)  DEFAULT NULL COMMENT '模板消息 ID，预留',
  `template_data`    longtext      DEFAULT NULL COMMENT '模板字段→变量映射，预留' CHECK (json_valid(`template_data`)),
  `template_url`     varchar(512)  DEFAULT NULL COMMENT '模板消息跳转链接，预留',
  `last_payload`     longtext      DEFAULT NULL COMMENT '最近一次收到的原始 payload，供页面预览' CHECK (json_valid(`last_payload`)),
  `remark`           varchar(255)  DEFAULT NULL,
  `create_time`      datetime      NOT NULL DEFAULT current_timestamp(),
  `update_time`      datetime      NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_appid` (`appid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知推送通道';

-- 这张表先前按方案手工建过一版，secret 是 NOT NULL 且没有默认值，
-- 会让下面不带 secret 的 seed 在严格模式下直接失败。MODIFY 可以重复执行。
ALTER TABLE `wx_notify_channel`
  MODIFY `secret` varchar(128) NOT NULL DEFAULT '' COMMENT '该通道独立密钥，对应请求头 X-Notify-Secret；空串表示不开放网关';

-- 推送记录：网关和手动发都记一条
-- 不复用 wx_template_msg_log：那张表的入口是 WxMpTemplateMessage，只覆盖模板消息，
-- 这里要同时留下「原始请求体」和「渲染结果」两份，客服消息也得记。
CREATE TABLE IF NOT EXISTS `wx_notify_log` (
  `id`               bigint(20)   NOT NULL AUTO_INCREMENT,
  `appid`            varchar(64)  NOT NULL,
  `channel_id`       bigint(20)   DEFAULT NULL,
  `channel_code`     varchar(64)  NOT NULL COMMENT '冗余一份，通道删了记录还能看',
  `source`           varchar(16)  NOT NULL COMMENT 'webhook 网关触发 | manual 页面手动发',
  `payload`          longtext     DEFAULT NULL COMMENT '调用方传的变量',
  `content`          text         DEFAULT NULL COMMENT '实际发出去的内容',
  `recipient_count`  int(11)      NOT NULL DEFAULT 0,
  `success_count`    int(11)      NOT NULL DEFAULT 0,
  `fail_count`       int(11)      NOT NULL DEFAULT 0,
  `error_msg`        varchar(500) DEFAULT NULL,
  `create_time`      datetime     NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_appid_code_id` (`appid`,`channel_code`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知推送记录';

-- 部署通知的两个通道，文案和改造前 buildNotifyText 的输出逐字节一致。
-- 这两行必须有，否则改动上线后第一次部署就静默不发通知了。
-- secret 给空串：部署通知走的还是原来的 /wx/deploy/webhook，鉴权用 deploy.webhook.secret，
-- 不从通用网关进来，所以不给它开网关密钥。
-- {commit_short} 由 DeployWebhookController 算好传进来（原来的 20 字截断逻辑）。
INSERT INTO `wx_notify_channel`
  (`appid`, `code`, `name`, `secret`, `enabled`, `send_type`, `recipient_type`, `recipient_value`, `content_mode`, `content_template`, `remark`)
SELECT 'wxfd938c6a7ced4eab', 'deploy-success', '部署成功通知', '', 1, 'kefu', 'tag', 'develop', 'render',
  '✅ {commit_short} 部署成功\n项目：{project}\n分支：{branch}\n提交：{commit_msg}\n作者：{actor}\n时间：{deploy_time}',
  'GitHub Actions 部署成功后自动推送'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `wx_notify_channel` WHERE `code` = 'deploy-success');

-- 失败多两行。占位符里的 ? 表示「变量为空就丢掉整行」，
-- 失败步骤和日志不一定有值，不丢行就会留下「失败步骤：」这种空标签。
INSERT INTO `wx_notify_channel`
  (`appid`, `code`, `name`, `secret`, `enabled`, `send_type`, `recipient_type`, `recipient_value`, `content_mode`, `content_template`, `remark`)
SELECT 'wxfd938c6a7ced4eab', 'deploy-failure', '部署失败通知', '', 1, 'kefu', 'tag', 'develop', 'render',
  '❌ {commit_short} 部署失败\n项目：{project}\n分支：{branch}\n提交：{commit_msg}\n作者：{actor}\n时间：{deploy_time}\n失败步骤：{failed_step?}\n日志：{run_url?}',
  'GitHub Actions 部署失败后自动推送'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `wx_notify_channel` WHERE `code` = 'deploy-failure');

-- 管理页权限。NotifyChannelManageController 上的 wx:notifychannel:* 之前没种进 sys_menu，
-- 而 ShiroServiceImpl 连超级管理员的权限也是从 sys_menu 收集的，所以不种这几行谁都是 403。
-- 生产库的 menu_id 自增值不一定和基线一致，这里不写死 id，按 url / perms 判重，可重复执行。
INSERT INTO `sys_menu` (`parent_id`, `name`, `url`, `perms`, `type`, `icon`, `order_num`)
SELECT 6, '消息推送', 'wx/notify', NULL, 1, 'config', 6
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `url` = 'wx/notify');

-- regenerateSecret / closeGateway 挂在 update 上，logs 挂在 list 上，preview 挂在 info 上。
INSERT INTO `sys_menu` (`parent_id`, `name`, `url`, `perms`, `type`, `icon`, `order_num`)
SELECT p.`menu_id`, b.`name`, NULL, b.`perms`, 2, NULL, 6
FROM (SELECT `menu_id` FROM `sys_menu` WHERE `url` = 'wx/notify' LIMIT 1) p
JOIN (
  SELECT '查看' AS `name`, 'wx:notifychannel:list,wx:notifychannel:info' AS `perms`
  UNION ALL SELECT '新增', 'wx:notifychannel:save'
  UNION ALL SELECT '修改', 'wx:notifychannel:update'
  UNION ALL SELECT '删除', 'wx:notifychannel:delete'
  UNION ALL SELECT '发送', 'wx:notifychannel:send'
) b
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` m WHERE m.`perms` = b.`perms`);
