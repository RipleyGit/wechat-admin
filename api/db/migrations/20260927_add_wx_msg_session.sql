-- 粉丝私信：会话状态 + 按运营账号的已读位点
-- 详见 docs/fan-messaging.md

-- 会话表：每个 (appid, openid) 一行
CREATE TABLE IF NOT EXISTS `wx_msg_session` (
  `id`               bigint(20)   NOT NULL AUTO_INCREMENT,
  `appid`            char(20)     NOT NULL,
  `openid`           varchar(32)  NOT NULL,
  `last_in_time`     datetime     DEFAULT NULL COMMENT '最后一次粉丝主动交互时间，含event，48h窗口依据',
  `last_msg_time`    datetime     DEFAULT NULL COMMENT '最后一条可展示消息时间',
  `last_msg_summary` varchar(120) DEFAULT NULL COMMENT '列表摘要',
  `has_real_msg`     tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否有过真实消息（非event）',
  `create_time`      datetime     DEFAULT current_timestamp(),
  `update_time`      datetime     DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_appid_openid` (`appid`,`openid`),
  KEY `idx_appid_lastmsg` (`appid`,`last_msg_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='粉丝私信会话';

-- 已读位点：每个 (appid, openid, 运营账号) 一行
CREATE TABLE IF NOT EXISTS `wx_msg_read` (
  `id`                bigint(20)  NOT NULL AUTO_INCREMENT,
  `appid`             char(20)    NOT NULL,
  `openid`            varchar(32) NOT NULL,
  `user_id`           bigint(20)  NOT NULL COMMENT 'sys_user.user_id',
  `last_read_msg_id`  bigint(20)  NOT NULL DEFAULT 0,
  `update_time`       datetime    DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_appid_openid_user` (`appid`,`openid`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私信已读位点，按运营账号独立';

-- wx_msg 原来只有 idx_appid，拉单会话时间线会全表扫
ALTER TABLE `wx_msg` ADD INDEX `idx_appid_openid_id` (`appid`,`openid`,`id`);
