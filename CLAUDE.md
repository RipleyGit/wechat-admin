# wechat-admin 开发须知

微信公众号管理系统（多公众号接入），同时是**门户小程序的后端**。

Java 8 + Spring Boot + MyBatis-Plus + Shiro + MySQL，前端在 `web/`。
服务端监听 `18088`，`server.servlet.context-path: /wx`。

## 它是谁的后端：只服务门户，不碰 pco 业务

| 域名 | 服务 | 管什么 | 代码在哪 |
|------|------|--------|---------|
| `https://wechat.bleem.site/wx` | **本仓库** | **门户小程序**：用户档案、应用可见性；以及公众号/粉丝/模板消息管理 | 本仓库 |
| `https://pco.bleem.site/api` | pco-mgr-backend | **pco 子应用**：工单、合同、服务报告 | `pco-mgr` 仓库（本仓库里有软链 `pco-code/`） |

两个后端**职责与域名严格分开，没有共用代码，也不要互相调用**。判断依据：
这个接口是给**门户**用的（工作台、用户档案、应用可见性），还是进入 **pco 子应用**
之后才用的（工单、合同、报告）。后者一律去 `pco-mgr` 改。

门户小程序前端在 `portal-wechat-app` 仓库，调用地址配在它的
`miniprogram/utils/config.ts` 的 `ADMIN_API_ORIGIN`。

## 必须用 JDK 8 编译

lombok 1.18.22 在新 JDK 上会抛 `NoSuchFieldError: JCTree$JCImport.qualid`，
整个编译在注解处理阶段就崩，和业务代码无关：

```bash
cd api
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
mvn compile -DskipTests
```

## 鉴权边界

`config/ShiroConfig.java` 的过滤链只有两段需要登录，其余全是 `anon`：

```
/sys/login     anon
/sys/**        oauth2
/manage/**     oauth2
/wx/**         anon
/**            anon
```

所以**放在 `/manage/` 下的接口才有鉴权**，其他路径（如 `/wxAuth`、`/wxMiniUser`）
是公开的。新增面向小程序/H5 的公开接口时，不要相信客户端传来的身份标识：
能用微信 `code` 换 openid 的就别让调用方直传 openid。

管理接口的权限码（`@RequiresPermissions("wx:xxx:yyy")`）必须在 migration 里种进
`sys_menu`，否则连超级管理员也是 403 —— `ShiroServiceImpl` 连超管的权限也是从
`sys_menu` 收集的。

## 数据库变更走 migration

`api/db/mysql-0.7.0.sql` 是基线，新变更一律加文件到 `api/db/migrations/`，
命名 `YYYYMMDD_说明.sql`，并且**写成可重复执行**（`CREATE TABLE IF NOT EXISTS`、
`INSERT ... SELECT ... WHERE NOT EXISTS`）。生产库自增值和基线不一致，
种 `sys_menu` 时不要写死 `menu_id`。

新表的约定（参考 `wx_notify_channel`、`wx_mini_user`）：

- 自增 `bigint id` 主键 + 业务唯一键，不要再用 `wx_user` 那种「openid 当主键」
- `create_time` / `update_time` 用数据库默认值与 `ON UPDATE`，
  **项目里没有 `MetaObjectHandler`**，实体上别用 `@TableField(fill=...)`
- JSON 列用 `longtext ... CHECK (json_valid(...))`，实体侧用 fastjson 的 `JSONArray`
- `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`，字段带中文 `COMMENT`

## 两套用户表，不要混

| 表 | 是谁 | 主键 / 唯一键 |
|----|------|--------------|
| `wx_user` | **公众号粉丝**。语义全是公众号概念（`subscribe`、`subscribe_time`、`tagid_list`、`subscribe_scene`） | 主键 `openid` |
| `wx_mini_user` | **门户小程序用户**。档案 + 应用可见性（`app_codes` / `default_code`） | 唯一键 `(mini_appid, mini_openid)` |

同一个人在小程序下的 openid 与在公众号下的 openid **不是同一个值**。两张表靠
`unionid`（需小程序与公众号绑在同一个微信开放平台帐号下）或手机号关联，
见 `WxMiniUserServiceImpl.linkServiceUser()`。所以别试图把小程序用户塞进
`wx_user`，也别假设两边 openid 能直接对上。

## 部署

nginx 反代必须**保留 `/wx` 这一段**（context-path）：

```nginx
location /wx/ { proxy_pass http://<host>:18088; }   # 结尾不要带斜杠
```

带斜杠会把 `/wx` 剥掉，Spring 收到 `/wxMiniUser/login` 直接 404。
线上配置是服务器上的 `https_wechat.conf`（不在 `bleem-nginx` 仓库里）。

部署通知相关见 `docs/deploy-notify-pipeline.md`。
