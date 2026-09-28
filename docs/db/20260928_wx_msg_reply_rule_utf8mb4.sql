-- 自动回复规则表转 utf8mb4
-- 现网这张表是 utf8mb3，回复内容里带 emoji（如 🎉）保存时报
--   Incorrect string value: '\xF0\x9F...' for column reply_content
-- 前端只看到「未知异常，请联系管理员」。
-- CONVERT TO 会重建表并把现有数据按字符转码，utf8mb3 是 utf8mb4 的子集，不丢数据；可重复执行。
-- 表很小（规则几十条量级），重建是毫秒级，不需要停机窗口。
-- appid 是 char(20) 且有索引：utf8mb4 下 80 字节，远低于 InnoDB 索引 3072 字节上限。
ALTER TABLE `wx_msg_reply_rule` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
