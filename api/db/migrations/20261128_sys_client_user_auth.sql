-- 应用认证用户：谁能用哪个子应用
--
-- 方案见 portal-wechat-app/docs/小程序用户应用授权重构方案.md。
-- 当前阶段只建表，不迁移 wx_mini_user 的数据，也不删 wx_mini_user ——
-- 按方案的上线顺序，迁移脚本和删表脚本是后续两步，确认线上跑稳再做。
--
-- 本表设计成可以给多种渠道的用户做认证，这次先只用于 user_type='wx_user'
-- （小程序用户，user_id 是小程序 openid）。auth_identifier / credential
-- 本期不用，留给以后接入账号密码之类的渠道。
--
-- 和原表草稿相比补了三处：is_default（默认应用需要一个地方标记）、
-- 唯一键 uk_user_client（同一用户同一应用只能有一行）、client_code 的索引
-- （后台按应用筛选用）。可重复执行。

CREATE TABLE IF NOT EXISTS `sys_client_user_auth` (
  `id`              bigint(20)   NOT NULL AUTO_INCREMENT,
  `user_id`         varchar(50)  NOT NULL COMMENT '用户id。user_type=wx_user 时为小程序 openid',
  `user_type`       varchar(50)  NOT NULL DEFAULT 'wx_user' COMMENT '用户类型:wx_user',
  `client_code`     varchar(100) NOT NULL COMMENT '应用Code:app_pco,app_cloude,app_aptum',
  `client_name`     varchar(100) DEFAULT NULL COMMENT '应用名',
  `is_default`      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否默认应用，启动后直达。同一用户至多一条为 1，由代码保证',
  `auth_identifier` varchar(255) DEFAULT NULL COMMENT '盐。微信渠道留空',
  `credential`      varchar(500) DEFAULT NULL COMMENT '加密凭证（密码哈希、token），敏感。微信渠道留空',
  `last_login_at`   datetime     DEFAULT NULL COMMENT '上次登录时间',
  `status`          tinyint(4)   NOT NULL DEFAULT 1 COMMENT '状态  0：禁用   1：正常',
  `create_user_id`  bigint(20)   DEFAULT NULL COMMENT '创建者ID。用户自助开通时为空',
  `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_client` (`user_type`, `user_id`, `client_code`) USING BTREE,
  KEY `idx_client` (`client_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='应用认证用户';

-- 管理页菜单与权限：「用户应用管理」。不种这几行，超级管理员访问
-- /manage/sysClientUserAuth/** 也是 403（ShiroServiceImpl 连超管的权限也是从
-- sys_menu 收集的）。menu_id 不写死，按 url / perms 判重，可重复执行。
INSERT INTO `sys_menu` (`parent_id`, `name`, `url`, `perms`, `type`, `icon`, `order_num`)
SELECT 6, '用户应用管理', 'wx/clientuserauth', NULL, 1, 'link', 8
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `url` = 'wx/clientuserauth');

INSERT INTO `sys_menu` (`parent_id`, `name`, `url`, `perms`, `type`, `icon`, `order_num`)
SELECT p.`menu_id`, b.`name`, NULL, b.`perms`, 2, NULL, 8
FROM (SELECT `menu_id` FROM `sys_menu` WHERE `url` = 'wx/clientuserauth' LIMIT 1) p
JOIN (
  SELECT '查看' AS `name`, 'wx:clientuserauth:list,wx:clientuserauth:info' AS `perms`
  UNION ALL SELECT '新增', 'wx:clientuserauth:save'
  UNION ALL SELECT '修改', 'wx:clientuserauth:update'
  UNION ALL SELECT '删除', 'wx:clientuserauth:delete'
) b
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` m WHERE m.`perms` = b.`perms`);
