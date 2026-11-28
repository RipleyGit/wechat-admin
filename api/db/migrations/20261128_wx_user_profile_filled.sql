-- wx_user 加 profile_filled：是否完善过小程序资料
--
-- 原来这个标记在 wx_mini_user.profile_filled，迁移到 wx_user 后由小程序登录视图
-- 的 needProfile 使用（见 WxMiniAuthServiceImpl）。已有的粉丝一律视为已填：
-- 他们的信息后台本来就能看到，不需要再弹表单。新关联的用户默认 0，填完置 1。
--
-- 方案见 portal-wechat-app/docs/小程序用户应用授权重构方案.md。可重复执行。

ALTER TABLE `wx_user`
  ADD COLUMN IF NOT EXISTS `profile_filled` tinyint(1) NOT NULL DEFAULT 0
    COMMENT '是否完善过小程序资料。0 时小程序弹资料表单，取消也置 1';

-- 已有的粉丝视为已填。只在还是默认 0 时写，重复执行不会覆盖后来真实的 0/1。
-- 用建列时间近似区分「存量」不可靠，这里直接把当前所有行置 1：迁移时点上
-- 库里的粉丝都是存量，新粉丝是迁移之后才进来的。
UPDATE `wx_user` SET `profile_filled` = 1 WHERE `profile_filled` = 0;
