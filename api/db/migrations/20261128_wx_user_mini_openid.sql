-- 粉丝表记录同一个人的小程序 openid
--
-- 门户小程序和服务号没有绑同一个开放平台，拿不到 unionid。关联改为：
-- 小程序 web-view 走服务号网页授权（snsapi_base）拿服务号 openid，或者用户扫
-- 场景值为 link_<ticket> 的带参二维码，再把两边的 openid 互相写进对方的表：
--   wx_user.mini_openid        ← 小程序 openid（本脚本新增）
--   wx_mini_user.service_openid ← 服务号 openid（已有列）
-- 方案见 portal-wechat-app/docs/小程序用户关联服务号粉丝方案.md。
--
-- 同一个微信号对同一个 appid 的 openid 固定不变，所以两边是一对一，
-- (mini_appid, mini_openid) 加唯一索引兜底，防止一个小程序用户被关联到两个粉丝上。
-- 唯一索引允许多个 NULL，没关联的粉丝互不冲突。
--
-- 只加列，不动既有列，新旧两版 jar 都能跑在这份表结构上。本脚本可重复执行。

ALTER TABLE `wx_user`
  ADD COLUMN IF NOT EXISTS `mini_appid` varchar(64) DEFAULT NULL
    COMMENT '关联的门户小程序 appid',
  ADD COLUMN IF NOT EXISTS `mini_openid` varchar(64) DEFAULT NULL
    COMMENT '同一用户在门户小程序下的 openid',
  ADD COLUMN IF NOT EXISTS `mini_link_time` datetime DEFAULT NULL
    COMMENT '与小程序用户完成关联的时间';

CREATE UNIQUE INDEX IF NOT EXISTS `uk_mini_openid` ON `wx_user` (`mini_appid`, `mini_openid`);
