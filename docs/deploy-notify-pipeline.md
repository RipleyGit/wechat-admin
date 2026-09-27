# 自动构建部署与微信标签推送

记录 GitHub Actions 自动构建/部署的完整链路，以及部署完成后按粉丝标签推送微信通知的实现。

服务器目录结构、systemd 安装、GitHub secrets 清单、回滚步骤见 [github-actions-deployment.md](github-actions-deployment.md)，本文不重复。

## 整体链路

```
push main
  └─ build job（构建前端 dist + 后端 jar，上传为 artifact）
       └─ deploy job
            ├─ 下载 artifact
            ├─ rsync 到 .deploy/<commit-sha>/ 暂存
            ├─ Activate release：就地切换 + 重启 + 就绪探测
            └─ Notify：POST https://wechat.bleem.site/wx/deploy/webhook
                 └─ DeployWebhookController
                      ├─ 校验 X-Webhook-Secret
                      ├─ 按名字查 tag id（Redis 缓存 10min）
                      ├─ 本地库查该标签下的粉丝（JSON_CONTAINS）
                      └─ 逐个发 kefu 客服消息
```

## 一、GitHub Actions 侧

文件：`.github/workflows/deploy.yml`

### 触发与并发

- `pull_request`：只跑 `build`，不部署。
- `push` 到 `main`：`build` → `deploy`。
- 并发组 `wechat-admin-production`，`cancel-in-progress: false` —— 排队等待，不会打断正在进行的部署。
- `deploy` job 绑定 GitHub environment `production`，受该环境的 secrets 和保护规则约束。

### build job

`ubuntu-24.04`：

1. checkout
2. Node 20（缓存 key 为 `web/package-lock.json`）→ `cd web && npm ci && npm run build`
3. Java 8 temurin（maven 缓存）→ `cd api && mvn -q -DskipTests package`（**CI 不跑测试**）
4. 上传 artifact `web-dist`（`web/dist`）和 `api-jar`（`api/target/wx-api.jar`），均 `if-no-files-found: error`，保留 7 天

### deploy job

条件：`github.event_name == 'push' && github.ref == 'refs/heads/main'`。

步骤依次为：

| step id | 作用 |
| --- | --- |
| `download_web` / `download_api` | 下载两个 artifact 到 `release/web`、`release/api` |
| `configure_ssh` | 写入 `DEPLOY_SSH_PRIVATE_KEY` 到 `~/.ssh/id_ed25519`（600）、`DEPLOY_KNOWN_HOSTS` 到 `~/.ssh/known_hosts` |
| `upload` | ssh mkdir `$DEPLOY_PATH/.deploy/$RELEASE_ID/{api,web}`，`rsync -az --delete` 前端、`rsync -az` jar |
| `activate` | 见下 |
| Notify success / failure | 见第二节 |

`RELEASE_ID` 用的是 `github.sha`，所以每个 commit 有独立的暂存目录。

### Activate release 的切换逻辑

单个 ssh session 内跑 `set -eu` 脚本：

1. 校验暂存目录里 `wx-api.jar` 非空、`web/index.html` 存在（**先校验再切换**，避免半成品上线）
2. 删除旧的 `web.previous`，把当前 `web` 改名为 `web.previous`，再把暂存的 `web` 移到线上位置
3. jar 同理：`wechat-admin.jar` → `wechat-admin.previous.jar`，暂存 jar 移入
4. `systemctl restart wechat-admin` + `systemctl is-active --quiet wechat-admin`
5. 就绪探测（见下）
6. `rm -rf` 暂存目录

注意这里是**改名切换而非符号链接**，`.previous` 就是回滚用的上一版本。

### 就绪探测的真实语义

```bash
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18088/wx/deploy/webhook \
  -X POST -H 'Content-Type: application/json' \
  -H 'X-Webhook-Secret: x' -d '{}'
```

最多循环 30 次、间隔 2s（约 60s 超时）。

**这里故意用了错误的 secret `x`**，它只判断能否拿到任意 HTTP 状态码（非 `000`/非空），也就是"进程起来了、端口在听"。它**不校验 webhook 本身是否工作正常**，401 也算通过。所以就绪探测通过 ≠ 通知功能正常。

## 二、通知步骤

### Notify success

`if: success()`，`continue-on-error: true`（**通知失败不影响部署结论**）。

内联 python3，POST 到 `https://wechat.bleem.site/wx/deploy/webhook`，header `X-Webhook-Secret: ${{ secrets.DEPLOY_WEBHOOK_SECRET }}`，body：

```json
{"branch":"...","commit_msg":"...","actor":"...","status":"success","run_url":"...","deploy_time":"UTC+8 时间"}
```

`commit_msg` 只取第一行。

### Notify failure

`if: failure() || cancelled()`，同样的 URL 和 header，额外读取 `download_web`/`download_api`/`configure_ssh`/`upload`/`activate` 各步的 `outcome`，映射成中文步骤名（`下载前端构件`/`下载后端构件`/`配置部署 SSH 密钥`/`上传发布文件到服务器`/`激活新版本并重启服务`），放进 `failed_step`；都不匹配则为 `未知步骤`。

注意通知步骤打的是**公网 HTTPS 地址**，而不是就绪探测用的 loopback。也就是说通知能否送达还额外依赖 `wechat.bleem.site` 的 nginx 反代正常 —— 这是和就绪探测不同的一条路径。

## 三、服务端接收：DeployWebhookController

文件：`api/src/main/java/site/bleem/wechat/modules/wx/controller/DeployWebhookController.java`

类上 `@RequestMapping("/deploy")`，叠加 `server.servlet.context-path: /wx`，对外路径为 `POST /wx/deploy/webhook`。

### 鉴权

读 header `X-Webhook-Secret`（Spring 层面 `required = false`），与 `deploy.webhook.secret` 做 `String.equals` 比较。缺失、空白或不匹配 → `R.error(401, "invalid webhook secret")`。

没有防重放、没有限流、不是常量时间比较。

### 请求体

`DeployWebhookForm`，Jackson `@JsonProperty` 映射 snake_case：`branch`、`commit_msg`、`actor`、`status`、`run_url`、`deploy_time`、`failed_step`。**没有任何校验注解**，空 body `{}` 能正常绑定成一个全 null 的对象。

### 处理流程

1. 从 `DeployWebhookProperties` 取 `appid` 和 `tagName`（**来自配置，不是请求体**）
2. `wxMpService.switchoverTo(appid)` 切换多账号上下文
3. `findTagIdByName(appid, tagName)`：调 `wxUserTagsService.getWxTags(appid)`（走 Redis 缓存），线性扫描名字相等的 `WxUserTag` 返回其 id。**找不到就打 warn 并返回 `R.ok().put("notifySent", 0)`** —— 静默跳过，不报错
4. 查收信人：MyBatis-Plus `QueryWrapper` 查本地 `wx_user`，条件 `appid` + `subscribe=true` + `JSON_CONTAINS(tagid_list, <tagId>)`
5. `buildNotifyText(form)` 拼纯文本，形如：
   ```
   ✅ <commit msg 前20字>… 部署成功
   分支：main
   提交：<完整首行>
   作者：RipleyGit
   时间：2026-09-27 12:01:30
   ```
   失败时追加 `失败步骤：...` 和 `日志：...`
6. 逐个发送 `wxMpService.getKefuService().sendKefuMessage(WxMpKefuMessage.TEXT()...)`，**每次发送单独 try/catch**，单个失败不中断整批，最后返回成功条数 `notifySent`

### 关键点：用的是客服消息，不是模板消息

`sendKefuMessage` 受微信平台 **48 小时限制** —— 只能给最近 48 小时内与公众号有过交互的用户发。代码里没有做这个判断，超窗口的用户发送会抛异常、被 catch 记日志、不计入 `notifySent`。

所以"部署成功了但没收到微信通知"最常见的原因不是代码 bug，而是你太久没跟公众号说话了。随便给公众号发条消息就能重新进入窗口。

## 四、相关配置

`api/src/main/resources/application.yml`：

```yaml
server:
  port: ${SERVER_PORT:18088}
  servlet:
    context-path: /wx

deploy:
  webhook:
    appid: ${DEPLOY_WEBHOOK_APPID:wxfd938c6a7ced4eab}
    secret: ${DEPLOY_WEBHOOK_SECRET:wechat-admin}
    tag-name: ${DEPLOY_WEBHOOK_TAG_NAME:develop}
```

绑定到 `DeployWebhookProperties`（`@ConfigurationProperties(prefix = "deploy.webhook")`）。

生产环境通过 systemd `EnvironmentFile=/etc/wechat-admin/wechat-admin.env` 注入环境变量覆盖。

### 标签缓存

`CacheManagerConfig`：缓存名 `wxUserTagsServiceCache`，**TTL 10 分钟**，用 `RedisCacheConfiguration.defaultCacheConfig()`（即 Spring 默认的 JDK 序列化）。

Redis key 形如 `wxUserTagsServiceCache::WX_USER_TAGSwxfd938c6a7ced4eab`。

在本系统内做标签增删改会主动 `@CacheEvict`；但**直接在微信公众号后台改标签，本地缓存最多有 10 分钟不一致**，期间 `findTagIdByName` 用的是旧数据。可以调 `DELETE /manage/wxUserTags/refresh` 强制刷新。

## 五、已知行为与注意事项

### 1. 标签是按名字匹配的，不是按 id

`tag-name` 配的是标签**名字**，代码遍历比对 `getName()`。曾经踩过的坑：有人把默认值从 `develop` 改成了 `100`（`2bd13f9`），而微信那边实际是 `{"id":100,"name":"develop"}` —— id 是 100、名字是 develop，把 id 当名字填了，导致查不到标签、静默跳过推送，日志里只有一行 `未找到标签[100]`。已在 `1dad1f3` 改回。

**排查入口**：`journalctl -u wechat-admin --since '10 min ago' | grep -iE 'DeployWebhook|标签'`，正常应该看到 `部署通知推送完成，标签[develop]粉丝数=N，成功=N`。

> 改进建议：`findTagIdByName` 找不到标签时目前只 warn + 返回 `notifySent: 0`，HTTP 状态仍是 200，CI 侧完全无感。可以考虑返回非 2xx 或在响应里带明确的 `tagNotFound` 标记，让配置错误能被及早发现。

### 2. 收信人来自本地库，不是实时拉取

第 4 步查的是本地 `wx_user.tagid_list`。而打标签操作对本地 `tagid_list` 的更新是**异步最终一致**的（详见 [tag-fan-management.md](tag-fan-management.md)）。所以刚打上标签就触发部署，这个人可能收不到通知。

> 改进建议：如果对准确性敏感，可以改成调微信的 `tagGetUsers` 接口实时拉取该标签下的 openid 列表，代价是多一次 API 调用且要处理分页。

### 3. `.env.example` 缺三个 webhook 参数

`api/deploy/wechat-admin.env.example` 里只有 `PROFILE_ACTIVE`、`SERVER_ADDRESS`、`SERVER_PORT`、`JAVA_OPTS`、MySQL、Redis，**没有** `DEPLOY_WEBHOOK_APPID` / `DEPLOY_WEBHOOK_SECRET` / `DEPLOY_WEBHOOK_TAG_NAME`。

按这个模板新建服务器环境文件，这三项会直接吃 yml 里的硬编码默认值 —— 其中 `secret` 默认是 `wechat-admin`，一个能猜到的弱口令，而这个接口是公网可达的。

> 改进建议：补进 `.env.example`（至少作为必填占位），并在生产环境用随机串覆盖 `DEPLOY_WEBHOOK_SECRET`，同时同步更新 GitHub 的 `DEPLOY_WEBHOOK_SECRET` secret。另外 `github-actions-deployment.md` 的 secrets 表格里也漏了 `DEPLOY_WEBHOOK_SECRET` 这一项。

### 4. 失败通知的 `err_detail` 是死代码

failure 通知步骤里 `step_map` 每个条目的 `err_detail` 都硬编码成空字符串，所以拼接 `\n错误：...` 那段永远不会生效，`failed_step` 实际只有中文步骤名，不含具体报错。

> 改进建议：要么去掉这段无效拼接，要么真的把失败步骤的日志摘要取出来传进去（GitHub Actions 里拿 step 日志不方便，实用做法是让 `activate` 脚本把关键错误写到 step output）。

### 5. CI 不跑测试

`mvn -q -DskipTests package`。部署前没有任何自动化测试关卡，就绪探测也只验活。

> 改进建议：至少在 `build` job 里对 `pull_request` 跑一次 `mvn test`，不阻塞主干部署速度。

### 6. 通知失败不影响部署状态

两个 notify 步骤都是 `continue-on-error: true`。这是有意的（通知挂了不该让部署显示失败），但副作用是 **webhook 长期坏掉也不会有任何人被告知** —— 因为唯一的告知渠道就是它自己。

> 改进建议：如果在意，可以让 notify 步骤失败时输出一条 GitHub Actions 的 `::warning::` 注解，这样 run 页面上能看到，而 run 结论仍是 success。

