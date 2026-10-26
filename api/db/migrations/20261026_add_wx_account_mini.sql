-- 门户小程序的 appid / AppSecret 从环境变量搬进 wx_account
--
-- 原来是 application.yml 里的 wx.mini.appid / wx.mini.secret（由 WX_MINI_APPID /
-- WX_MINI_SECRET 注入）。问题有两个：
--
-- 1. 全局单例。一套 jar 只能配一个小程序，而 wechat-admin 本来就是多公众号接入的，
--    每个公众号配一个自己的门户小程序才是对的形状。
-- 2. wx.mini.appid 和 wx.mini.service-appid 其实是**死配置**，没有任何 Java 代码读过
--    （只有 secret 和 allow-openid-login 被 @Value 用了）。小程序 appid 一直是调用方
--    在请求体里自己传的，服务端无从校验。
--
-- 搬到 wx_account 之后 mini_appid 才真正有了用处：它是「小程序 → 公众号」的关联，
-- 服务端能用它反查 AppSecret，也能据此推出 service_appid，不必再信任调用方传的值。
--
-- 只加列，不动既有列，新旧两版 jar 都能跑在这份表结构上。本脚本可重复执行。

-- mini_secret 明文存储，和同表的 secret（公众号 AppSecret）一致。
-- 接口只回「是否已配置」，不回明文，见 WxAccountConfigView。
ALTER TABLE `wx_account`
  ADD COLUMN IF NOT EXISTS `mini_appid` varchar(64) DEFAULT NULL
    COMMENT '关联的门户小程序 appid；NULL 表示该公众号没有配套小程序',
  ADD COLUMN IF NOT EXISTS `mini_secret` varchar(128) DEFAULT NULL
    COMMENT '门户小程序的 AppSecret，用于 jscode2session 换取 openid；NULL 表示未配置';

-- 唯一索引：/wx/wxMiniUser/login 拿着 miniAppid 反查公众号和 AppSecret，
-- 两个公众号填同一个 mini_appid 会让这个反查有歧义。
-- 唯一索引允许多个 NULL，所以没配小程序的公众号不会互相冲突。
CREATE UNIQUE INDEX IF NOT EXISTS `uk_mini_appid` ON `wx_account` (`mini_appid`);

-- 把原来 yml 里的默认值落到对应的公众号上，这样改动上线后门户登录行为不变。
-- 只在该列还是 NULL 时写，重复执行不会覆盖页面上改过的值。
UPDATE `wx_account` SET `mini_appid` = 'wx62c3973df7065a2b'
WHERE `appid` = 'wxfd938c6a7ced4eab' AND `mini_appid` IS NULL;

-- mini_secret 刻意不在这里 seed：
-- 真实 AppSecret 不进仓库。本脚本跑完后要去「选择工作公众号 → 配置」页面，
-- 把原先 WX_MINI_SECRET 的值填进「小程序 AppSecret」再保存。
-- 在填进去之前，code 登录会返回「小程序 xxx 未配置 AppSecret」，
-- 和原先没配 WX_MINI_SECRET 时的降级行为一致。
