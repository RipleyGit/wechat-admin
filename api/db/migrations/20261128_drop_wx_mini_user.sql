-- 【最后一步】删除 wx_mini_user 及其后台菜单权限
--
-- ⚠ 只在前面的迁移脚本执行完、且线上登录/关联/应用列表都确认正常之后再执行。
--   这一步不可逆，执行前请确认 wx_mini_user 已备份。
--
-- 代码里 WxMiniUser 相关的类（entity/mapper/service/manage controller）已随本次
-- 改动一起删除，所以这张表删掉后没有任何 Java 代码再引用它。
-- 方案见 portal-wechat-app/docs/小程序用户应用授权重构方案.md。

-- 旧的「小程序用户」菜单与权限（20261025_add_wx_mini_user.sql 种的）。
-- 先删子菜单（按钮/权限），再删父菜单，最后清理 sys_role_menu 里的悬挂引用。
DELETE rm FROM `sys_role_menu` rm
  JOIN `sys_menu` m ON rm.`menu_id` = m.`menu_id`
  WHERE m.`perms` IN (
    'wx:wxminiuser:list,wx:wxminiuser:info',
    'wx:wxminiuser:update',
    'wx:wxminiuser:delete'
  ) OR m.`url` = 'wx/miniuser';

DELETE FROM `sys_menu` WHERE `perms` IN (
  'wx:wxminiuser:list,wx:wxminiuser:info',
  'wx:wxminiuser:update',
  'wx:wxminiuser:delete'
);
DELETE FROM `sys_menu` WHERE `url` = 'wx/miniuser';

DROP TABLE IF EXISTS `wx_mini_user`;
