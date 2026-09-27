# 粉丝私信

给运营一个能直接和粉丝对话的页面，对应微信公众号后台「互动管理 > 私信」。记录数据模型、48h 窗口约束、媒体处理链路、接口和前端交互。

> 状态：设计定稿，两张表的 DDL 已在生产执行，业务代码待实现。

## 一、这个功能受一条硬约束支配

发消息走的是微信**客服消息接口**（`cgi-bin/message/custom/send`，SDK 里是 `wxMpService.getKefuService().sendKefuMessage(...)`），不是模板消息。客服消息有 **48 小时窗口**：只有粉丝在过去 48 小时内主动交互过，公众号才能给他发消息。

窗口被**任何入站交互**重置，不只是发消息：

- 发消息（text/image/voice/video/location/link）
- 点击自定义菜单
- 扫码
- 关注

这四类都从同一个回调进来，前三类在 `wx_msg` 里落成 `msg_type = 'event'` 的行。**这是理解后面表结构设计的关键**：判断"能不能发消息"的依据和判断"要不要在会话列表里显示"的依据不是同一个东西。

48h 过期后本系统的行为：前端禁用输入框 + 显示倒计时，不做任何绕过。后端预留了 `/reach` 接口位（见第五节），当前直接返回未启用。

## 二、数据模型

原有的 `wx_msg` 表不动（`id`/`appid`/`openid`/`in_out`/`msg_type`/`detail`/`create_time`，`in_out` 为 0=IN、1=OUT）。新增两张表。

### wx_msg_session：会话状态

`(appid, openid)` 唯一。承载会话列表需要的一切，避免每次翻 `wx_msg` 聚合。

| 字段 | 作用 |
| --- | --- |
| `last_in_time` | 最后一次粉丝**主动交互**时间，**含 event**。48h 窗口的唯一依据 |
| `last_msg_time` | 最后一条**可展示消息**时间，会话列表排序用 |
| `last_msg_summary` | 列表摘要，120 字符截断 |
| `has_real_msg` | 是否有过真实消息（非 event）。会话列表的准入条件 |

### 为什么 last_in_time 和 has_real_msg 必须分开

这两个字段看起来冗余，实际语义正交，合并任何一边都会坏：

- **只用 `last_in_time` 判断是否进列表** → 每个粉丝关注时都产生一条 subscribe event，于是会话列表 = 粉丝列表。运营打开私信页看到几千个从没说过话的人。
- **只用 `has_real_msg`/`last_msg_time` 判断 48h 窗口** → 粉丝 3 天前发过消息、10 分钟前点了菜单，窗口其实是开的（菜单点击重置了），但系统会判定已过期、禁用输入框。运营明明能回却回不了。

所以：`last_in_time` 对所有入站更新（不过滤 event），`has_real_msg` 和摘要只在真实消息时更新。

### wx_msg_read：已读位点

`(appid, openid, user_id)` 唯一，`user_id` 是 `sys_user.user_id`。已读状态**按运营账号各自独立**——A 看过不代表 B 看过，未读数是每个运营自己的。

只存 `last_read_msg_id` 一个位点，不逐条标记。未读数 = 该会话中 `id > last_read_msg_id` 的入站真实消息条数。没有记录时视作 0，即全部未读。

### 索引

`wx_msg` 原来只有 `idx_appid`，拉单个会话的时间线会全表扫。已加：

```sql
ALTER TABLE `wx_msg` ADD INDEX `idx_appid_openid_id` (`appid`,`openid`,`id`);
```

## 三、消息记录：两个必须过滤的噪音源

`LogHandler` 无条件把每条入站消息写进 `wx_msg`，包括 event。同时 `MsgHandler` 有这段逻辑：

```java
boolean autoReplyed = msgReplyService.tryAutoReply(appid, false, fromUser, textContent);
if (TRANSFER_CUSTOMER_SERVICE_KEY.equals(textContent) || !autoReplyed) {
    wxMsgService.addWxMsg(WxMsg.buildOutMsg(WxConsts.KefuMsgType.TRANSFER_CUSTOMER_SERVICE, fromUser, null));
    ...
}
```

**每一条没命中自动回复规则的消息，都会额外写一行 `msg_type = 'transfer_customer_service'` 的 OUT 记录。** 生产库里 3 条入站 text 对应 3 条这种记录，一比一。

所以时间线渲染必须过滤掉两种 `msg_type`：

| msg_type | 为什么过滤 | 但是 |
| --- | --- | --- |
| `event` | 菜单点击、扫码、关注不是对话内容 | 仍然要更新 `last_in_time` |
| `transfer_customer_service` | 是转多客服的协议消息，不是发给粉丝的内容，渲染出来就是一堆空气泡 | —— |

**过滤规则只在一处维护**：`WxMsgServiceImpl.addWxMsg` 里更新摘要时跳过这两种，和时间线查询用的是同一套判断。

### 私信不与自动回复互斥

这是明确的决策：**保持现状，不做互斥**。粉丝发消息，自动回复照常触发、转多客服照常写记录，私信只是额外多一条人工发送通道。

代价是时间线上会混着机器人的自动回复和运营的人工回复。自动回复的 OUT 行会正常展示（它确实发给了粉丝），但运营看不出哪条是机器发的、哪条是人发的。

> 改进建议：`WxMsg.detail` 加一个来源标记（如 `source: auto|manual`），前端给自动回复的气泡加个"自动"角标。现在不做是因为要改 `MsgReplyServiceImpl` 里全部 `replyXxx` 方法，收益不值当，但如果运营反馈混淆就该补。

### 会话状态在哪里更新

两个钩子，都用 `INSERT ... ON DUPLICATE KEY UPDATE`，幂等、并发安全：

| 位置 | 更新什么 | 过滤 event？ |
| --- | --- | --- |
| `WxMsgServiceImpl.addWxMsg` | `last_msg_time`、`last_msg_summary`、`has_real_msg` | 过滤（连 `transfer_customer_service` 一起） |
| `LogHandler` | `last_in_time` | **不过滤** |

`addWxMsg` 带 `@Async`，两条消息可能乱序落库，所以摘要更新要加 `last_msg_time` 时间守卫，只在更新的时间更晚时才覆盖摘要。

## 四、媒体处理

微信的 `mediaId` **3 天过期**，不能直接拿来长期展示。所以入站媒体要转存到 MinIO。

### 转存为什么可以挂 @Async

回调有 5 秒超时，转存（下载 + 上传）远超这个时间，必须异步。但**不能用 `TaskExcutor`**：

```java
new ThreadPoolExecutor(5, 30, 60L, TimeUnit.SECONDS,
    new SynchronousQueue<Runnable>(),
    new ThreadPoolExecutor.CallerRunsPolicy())
```

`SynchronousQueue` + `CallerRunsPolicy` 的组合意味着线程池打满时任务会**回压到提交线程**上同步执行——提交线程就是微信回调线程，直接把 5 秒超时吃掉。

`@Async` 走的是 Spring Boot 自动配置的 `applicationTaskExecutor`（core 8、无界队列，`@EnableAsync` 在 `BootApplication` 上），无界队列不会触发拒绝策略，所以**转存挂 `@Async` 是安全的，不需要新建线程池**。

### 三类媒体的处理差异

| 类型 | 微信给什么 | 怎么处理 | 前端渲染 |
| --- | --- | --- | --- |
| 入站图片 | `picUrl` + `mediaId` | 照旧记两个字段，另起 `@Async` 用 `mediaDownload` 拉下来传 MinIO，回填 `detail.url` | `detail.url ?? detail.picUrl` |
| 入站语音 | `mediaId` + `format`，**无任何 URL** | 不转存音频，只存识别文字 | `detail.recognition ?? '[语音]'` |
| 出站图片 | —— | 同时传 MinIO（展示用）和 `mediaUpload` 拿 mediaId（发送用），两个都记进 detail | `detail.url` |

入站图片保留 `picUrl` 作为兜底：它是 `mmbiz.qpic.cn` 的长期地址，转存失败时图片仍然能显示。**入站语音没有这个兜底**，`WxMsg` 里只存了 `format` 和 `mediaId`，一旦 3 天过期就彻底拿不到了。

### 语音转文字是免费的

公众号后台「设置与开发 > 接口权限」开启**接收语音识别结果**后，入站语音的回调 XML 自带 `<Recognition>` 元素，`WxMpXmlMessage.getRecognition()` 直接读。**零 API 调用、零配额、不增加回调延迟。**

对比另一条路 `WxMpAiOpenService`（`uploadVoice` → `queryRecognitionResult` 轮询）：要下载再上传再轮询，还要求已认证的服务号，重得多。不用。

`WxMsg` 的 VOICE 分支现在只存 `format` 和 `mediaId`，**要加一行** `detail.put("recognition", wxMessage.getRecognition())`。开关没开时返回 null，前端渲染 `[语音]` 占位，功能不崩。

### 为什么不存语音文件

微信语音是 AMR/speex，浏览器 `<audio>` 放不了，要转码成 mp3。生产是 systemd 裸 jar 部署（不是 Docker），**没有 ffmpeg**。所以只存识别文字，不留音频。

## 五、接口

全部加在现有 `WxMsgManageController`（`/manage/wxMsg`）里。**不新增权限**——`wx:wxmsg:list|info|save|delete` 四个在 `sys_menu` 里已经种好了（`api/db/mysql-0.7.0.sql` 的 id 113-117），直接复用。

| 接口 | 权限 | 说明 |
| --- | --- | --- |
| `GET /sessions` | `list` | 会话列表，带 nickname/headimgurl/未读数/`last_in_time` |
| `GET /timeline` | `list` | 单会话消息，按 id 游标向上翻，50/页 |
| `POST /read` | `list` | 标记已读，写 `last_read_msg_id` |
| `GET /unread-count` | `list` | 全局红点用，只返回一个数字 |
| `POST /send` | `save` | 发文字/图片 |
| `POST /reach` | `save` | **预留位**，当前返回未启用 |

appid 鉴权是自动的：`WxAccountAccessInterceptor` 注册在 `/manage/**` 上（排除 `wxAccount`、`console/accounts`、`console/account-config`），新接口自动继承，不用手写 `assertAccessible`。

`/sessions` 一条 SQL 出结果：`LEFT JOIN wx_user` 取昵称头像、`LEFT JOIN wx_msg_read` 取本运营位点、相关子查询算未读数，`WHERE has_real_msg = 1`，按 `last_msg_time DESC` 排，50/页。

### 现有的四个接口是死代码

`/list`、`/info/{id}`、`/reply`、`/delete` 前端一处都没引用（`web/src/api/console.js` 里没有任何 `wxMsg` 条目）。所以不存在"替换还是并存"的问题，新接口直接加，老的留着不碍事。

### /reach 这个预留位是什么

48h 过期后唯一合规的触达手段是**模板消息**，基础设施已经有了（`TemplateMsgService.sendTemplateMsg(WxMpTemplateMessage, appid)`）。但它不是自由文本的逃生口：

- 模板要先在公众号后台报备通过
- 字段结构固定，只能填预设的 `{{key.DATA}}` 占位符
- 发出去是「服务通知」而不是对话气泡，粉丝点进去才看到详情

所以它能触达但没法当聊天用。一期只占位：后端有接口、返回未启用，前端按钮位 `v-if="false"`。以后要接不用改架构。

## 六、前端

Element Plus 没有聊天组件，两栏 IM 用 `el-scrollbar` + `el-avatar` + `el-badge` 手搭。

新建 `web/src/views/MessagesView.vue`：左列会话（头像/昵称/最后一条/未读角标）、右侧消息时间线、底部输入框（文字 + `el-upload` 图片，照 `MaterialsView.vue` 的 `:show-file-list="false" :http-request="upload"` 写法）。

改三个文件：

- `web/src/api/console.js` —— 加 6 个条目，照现有扁平对象风格
- `web/src/router/index.js` —— 在 `ConsoleLayout` 的 children 里加一条
- `web/src/layouts/ConsoleLayout.vue` —— nav 数组里插「粉丝私信」，挂红点 + 轮询

### 新消息感知只能靠轮询

仓库里**没有任何 WebSocket/SSE/long-polling**，Redis pub/sub 只给 `event_wx_accounts_changed` 一个频道用。所以是 `ConsoleLayout` 每 30s 调一次 `/unread-count`，导航项上挂 `el-badge`。

放在 layout 而不是私信页里，是因为运营在系统里干别的事时也得能看见——配上 48h 窗口，错过就回不了了。

两个必须做的细节：`onUnmounted` 清 timer；`document.hidden` 为真时跳过请求，不然多开几个标签页就是每 30s 好几个请求。

### 48h 倒计时

前端用 `last_in_time` 算剩余时间（`last_in_time + 48h - now`）显示倒计时，过期即禁用输入框并说明原因。不做前端绕过，后端 `/send` 也要自己校验——前端禁用只是体验，不是权限。

## 七、MinIO

pom 加 `io.minio:minio:8.5.2`。用 `wechat` 桶（原本是空的），匿名读 + 列举都已开启，不用改桶策略。

### 8.5.2 这个版本是钉死的

**不能升 9.x。** minio 9.x 依赖 okhttp 5.3.2，而 okhttp 5.3.2 的主 jar 是一个 **767 字节的 Kotlin-Multiplatform 元数据壳**，真正的实现在 `okhttp-jvm` 里，但它的 POM 没有声明这个依赖。Maven 解析后 classpath 上只有空壳，运行时必然 `NoClassDefFoundError`。

8.5.2 在 Spring Boot 2.7.16 / Java 8 上是实测可用的组合。

### 配置不写默认值

全部从环境变量注入，**代码和 `application.yml` 里不留任何凭证默认值**：

```
MINIO_ENDPOINT / MINIO_ACCESS_KEY / MINIO_SECRET_KEY / MINIO_BUCKET / MINIO_SECURE
```

`pco-code/` 里 `app/config.py` 把真实 AK/SK 和阿里云密钥硬编码成 `os.getenv(key, "真实值")` 的默认值，跟着提交进了仓库。**这个模式不要复制过来。**

本地和生产的 `MINIO_ENDPOINT` 值**合理地不同**：生产填 `dsm.bleem.site:9000`（内网直连），本地必须填公网 IP，因为内网域名在办公网外解析不了。

### 服务器 nginx

`/usr/local/nginx/bleem-nginx/http_wechat.conf` 加 `/minio/` location 反代到 `127.0.0.1:9000`，然后 reload。**只在服务器改，不进仓库**——这几个 conf 在服务器上本来就是未跟踪状态。

## 八、已知行为与注意事项

### 1. WxMsgManageController.reply 缺 switchoverTo（必须先修）

```java
@PostMapping("/reply")
public R reply(@CookieValue String appid, @RequestBody WxMsgReplyForm form){
    msgReplyService.reply(form.getOpenid(), form.getReplyType(), form.getReplyContent());
    return R.ok();
}
```

**appid 收了但没用。** `WxMpConfigStorageHolder` 是 ThreadLocal，Tomcat 复用线程，所以这个方法会沿用该线程上一次请求残留的 appid——多账号下会把消息发到**别的公众号**去。

项目里其他碰微信 API 的地方（`WxMenuManageController`、`WxQrCodeManageController`、`WxAssetsServiceImpl`、`MsgTemplateManageController`）都显式调了 `switchoverTo`，没有全局钩子。

这是私信要走的同一条路径。新增的 `/send` 必须自己调，同时把 `/reply` 这个存量 bug 一起修掉。

### 2. MinIO 凭证越权，上线前应收窄

当前这把凭证能看见 6 个桶：`bleem`、`hao`、`ict`、`pco-haoyuan`、`pco-mgr`、`wechat`，**删除权限实测是通的**。接进 wechat-admin 等于给这个服务开了对其他团队存储的读写删权限。

> 改进建议：建一个只能访问 `wechat` 桶的 MinIO service account 替换掉。需要 MinIO 管理员权限。

### 3. 粉丝上传的图片是公开且可枚举的

`wechat` 桶匿名读 + **列举**都开着（沿用 pco 现状，已知并接受）。这意味着任何人不需要凭证就能列出桶里所有对象并下载——不只是"URL 猜不到所以安全"，是可以直接遍历。

粉丝私信里发的图片可能包含身份证、病历、聊天截图这类东西。

> 改进建议：关掉匿名列举（只留读），或改成预签名 URL。前者一条桶策略就能改，代价是 pco 那边如果依赖列举会受影响，要先确认。

### 4. KfSessionHandler 是空壳

`api/src/main/java/site/bleem/wechat/modules/wx/handler/KfSessionHandler.java` 的 `handle()` 直接 `return null`，`KF_CREATE_SESSION` / `KF_CLOSE_SESSION` / `KF_SWITCH_SESSION` 事件一律不记录。

本方案不依赖多客服会话状态，所以不影响。但如果以后要做"客服接入中"这类状态展示，得先把这个填上。

### 5. 没有历史回填

`wx_msg_session` 建表时是空的，靠新消息逐步填充。生产库当前只有 9 行消息、1 个 openid，所以不做回填。

如果以后在数据量大的账号上启用，会出现"老会话在列表里不可见，直到粉丝再发一条消息"。真要回填就一条 `INSERT ... SELECT` 从 `wx_msg` 聚合，但注意大部分老会话的 48h 窗口早过了，回填出来也是一堆不能回复的死会话。

### 6. 自动回复的气泡分不出人机

见第三节。

### 7. 消息没有保留策略

`wx_msg` 只增不删，`detail` 是 `longtext`。当前 9 行，短期无所谓。量级上来后时间线分页（50/页、id 游标）撑得住，但表本身会一直长。

> 改进建议：等单账号消息数到十万量级再考虑按时间归档，现在做是过度设计。

