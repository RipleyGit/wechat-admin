-- 小程序用户：门户小程序的用户档案 + 应用可见性
--
-- 和 wx_user 的关系：wx_user 是「公众号粉丝」，主键是公众号下的 openid；
-- 本表是「小程序用户」，同一个人在小程序下的 openid 与公众号下的 openid
-- 是两个值，互不相通。两张表靠 unionid（同一开放平台帐号下才一致）或
-- 手机号关联，所以本表同时保留 unionid 与 service_openid 两列。
--
-- 为什么不扩 wx_user：那张表的语义全是公众号概念（subscribe / subscribe_time /
-- tagid_list / subscribe_scene），主键也是单列 openid。小程序用户的唯一键是
-- (mini_appid, mini_openid)，塞进去会让两种语义互相污染。
--
-- 主键用自增 id + 业务唯一键，跟 wx_notify_channel 一致（新表的约定），
-- 不沿用 wx_user 那种「openid 当主键」的老写法。

CREATE TABLE IF NOT EXISTS `wx_mini_user` (
  `id`             bigint(20)   NOT NULL AUTO_INCREMENT,
  `mini_appid`     varchar(64)  NOT NULL COMMENT '小程序 appid',
  `mini_openid`    varchar(64)  NOT NULL COMMENT '用户在该小程序下的 openid',
  `service_appid`  varchar(64)  DEFAULT NULL COMMENT '关联的服务号 appid',
  `service_openid` varchar(64)  DEFAULT NULL COMMENT '同一用户在服务号下的 openid。小程序登录时拿不到，靠 unionid 或手机号回填',
  `unionid`        varchar(64)  DEFAULT NULL COMMENT '开放平台 unionid。小程序与服务号绑在同一开放平台帐号时两边一致，是自动关联的唯一可靠依据',
  `nickname`       varchar(64)  DEFAULT NULL COMMENT '用户昵称',
  `user_phone`     char(11)     DEFAULT NULL COMMENT '用户手机号',
  `remark`         varchar(255) DEFAULT NULL COMMENT '备注',
  `default_code`   varchar(64)  DEFAULT NULL COMMENT '默认应用 code。非空且在 app_codes 内时，登录后直达该子应用',
  `app_codes`      longtext     DEFAULT NULL COMMENT '该用户可见的应用 code 数组，如 ["pco"]。空数组表示工作台不显示任何应用' CHECK (json_valid(`app_codes`)),
  `profile_filled` tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否已完善过资料。0 时小程序会弹资料表单，用户取消也置 1，避免每次进来都弹',
  `enabled`        tinyint(1)   NOT NULL DEFAULT 1 COMMENT '停用后登录接口仍返回档案但 app_codes 视为空',
  `create_time`    datetime     NOT NULL DEFAULT current_timestamp(),
  `update_time`    datetime     NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mini_user` (`mini_appid`, `mini_openid`),
  KEY `idx_unionid` (`unionid`),
  KEY `idx_service` (`service_appid`, `service_openid`),
  KEY `idx_phone` (`user_phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小程序用户';

-- 管理页菜单与权限。不种这几行，超级管理员访问 /manage/wxMiniUser/** 也是 403
-- （ShiroServiceImpl 连超管的权限也是从 sys_menu 收集的）。
-- 生产库 menu_id 自增值不一定和基线一致，这里不写死 id，按 url / perms 判重，可重复执行。
INSERT INTO `sys_menu` (`parent_id`, `name`, `url`, `perms`, `type`, `icon`, `order_num`)
SELECT 6, '小程序用户', 'wx/miniuser', NULL, 1, 'admin', 7
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `url` = 'wx/miniuser');

INSERT INTO `sys_menu` (`parent_id`, `name`, `url`, `perms`, `type`, `icon`, `order_num`)
SELECT p.`menu_id`, b.`name`, NULL, b.`perms`, 2, NULL, 7
FROM (SELECT `menu_id` FROM `sys_menu` WHERE `url` = 'wx/miniuser' LIMIT 1) p
JOIN (
  SELECT '查看' AS `name`, 'wx:wxminiuser:list,wx:wxminiuser:info' AS `perms`
  UNION ALL SELECT '新增', 'wx:wxminiuser:save'
  UNION ALL SELECT '修改', 'wx:wxminiuser:update'
  UNION ALL SELECT '删除', 'wx:wxminiuser:delete'
) b
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` m WHERE m.`perms` = b.`perms`);
