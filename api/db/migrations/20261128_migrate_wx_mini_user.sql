-- 【数据迁移】wx_mini_user → wx_user + sys_client_user_auth
--
-- ⚠ 这个脚本不是自动跑的 schema migration，是上线时人工执行并逐步核对的迁移。
--   跑之前务必先备份 wx_mini_user，并确认下面「第 0 步」的格式检查。
--   前置：20261128_sys_client_user_auth.sql（建表）、20261128_wx_user_mini_openid.sql
--   （wx_user 加 mini_* 列）、以及 wx_user 的 profile_filled 列（见
--   20261128_wx_user_profile_filled.sql）都已执行。
--
-- 方案见 portal-wechat-app/docs/小程序用户应用授权重构方案.md。
-- 删表（DROP TABLE wx_mini_user）不在本脚本里，放到最后一步确认线上跑稳后单独执行。

-- ───────────────────────────────────────────────────────────────────
-- 第 0 步：格式核对（必看，不要跳过）
--
-- client_code 的取值必须和门户 utils/apps.generated.ts 里的 code 一致：
-- app_pco / app_cloude / app_aptum。但 wx_mini_user.app_codes 里存的到底是
-- 带前缀的 app_xxx 还是裸 xxx，线上没核实过。先跑这一句看看：
--
--   SELECT DISTINCT jt.code FROM wx_mini_user,
--     JSON_TABLE(app_codes, '$[*]' COLUMNS (code VARCHAR(100) PATH '$')) jt;
--
-- 如果出现裸 pco / cloude / aptum，先补一次规整（示例，按实际情况调整）：
--
--   UPDATE wx_mini_user SET app_codes = JSON_ARRAY(
--     * 这里需要把每个元素映射成 app_ 前缀，MySQL 里批量改 JSON 数组比较繁琐，
--       建议导出来用脚本改好再导回，或者逐条 UPDATE *
--   );
--
-- 规整确认无误后，再执行下面的第 1~3 步。本脚本默认 app_codes 里已经是 app_xxx。
-- JSON_TABLE 需要 MySQL 8.0+。
-- ───────────────────────────────────────────────────────────────────

-- 第 1 步：应用授权。把每个用户 app_codes 里的每个 code 展开成一行。
-- user_id = mini_openid，status 跟随 enabled，is_default 跟随 default_code。
-- 唯一键 uk_user_client 保证重复执行不会插重复。
INSERT IGNORE INTO sys_client_user_auth
    (user_id, user_type, client_code, is_default, status, create_time, updated_at)
SELECT
    m.mini_openid,
    'wx_user',
    jt.code,
    CASE WHEN jt.code = m.default_code THEN 1 ELSE 0 END,
    CASE WHEN m.enabled = 0 THEN 0 ELSE 1 END,
    NOW(),
    NOW()
FROM wx_mini_user m,
     JSON_TABLE(m.app_codes, '$[*]' COLUMNS (code VARCHAR(100) PATH '$')) jt
WHERE m.mini_openid IS NOT NULL AND jt.code IS NOT NULL AND jt.code <> '';

-- 第 2 步：用户资料回写 wx_user。只对已关联服务号（service_openid 非空）的用户，
-- 且仅在 wx_user 对应字段为空时写，不覆盖粉丝表已有的值。
UPDATE wx_user u
JOIN wx_mini_user m ON m.service_openid = u.openid
SET u.nickname = COALESCE(NULLIF(u.nickname, ''), m.nickname),
    u.phone    = COALESCE(NULLIF(u.phone, ''), m.user_phone)
WHERE m.service_openid IS NOT NULL AND m.service_openid <> '';

-- 第 3 步：回填关联关系，让老用户不必重新关联服务号。
-- 只在 wx_user.mini_openid 还为空时写，避免覆盖已有关联。
-- 若一个粉丝被多个 wx_mini_user 指向（service_openid 冲突），uk_mini_openid 或本条件
-- 会让后写的失败/跳过，需要人工排查，这里用 IGNORE 兜一下并在迁移后核对数量。
UPDATE IGNORE wx_user u
JOIN wx_mini_user m ON m.service_openid = u.openid
SET u.mini_appid = m.mini_appid,
    u.mini_openid = m.mini_openid,
    u.mini_link_time = NOW()
WHERE m.service_openid IS NOT NULL AND m.service_openid <> ''
  AND (u.mini_openid IS NULL OR u.mini_openid = '');

-- 第 4 步：校验。两句结果应当一致（授权行数 = 各用户 app_codes 元素之和）。
-- 不一致说明有 code 为空或重复被 IGNORE 掉，排查后再继续。
--   SELECT COUNT(*) FROM sys_client_user_auth WHERE user_type='wx_user';
--   SELECT SUM(JSON_LENGTH(app_codes)) FROM wx_mini_user WHERE app_codes IS NOT NULL;
