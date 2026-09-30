# 自动构建部署与微信通知

记录 GitHub Actions 自动构建/部署的完整链路，以及部署完成后通过消息推送网关发微信通知的实现。

服务器目录结构、systemd 安装、GitHub secrets 清单、回滚步骤见 [github-actions-deployment.md](github-actions-deployment.md)，本文不重复。

## 整体链路

```
push main
  └─ build job（构建前端 dist + 后端 jar，上传为 artifact）
       └─ deploy job
            ├─ 下载 artifact
            ├─ rsync 到 .deploy/<commit-sha>/ 暂存
            ├─ Activate release：就地切换 + 重启 + 就绪探测
            └─ Notify：POST https://wechat.bleem.site/wx/notify/deploy-success（或 deploy-failure）
                 └─ NotifyGatewayController
                      ├─ 按请求头 X-Notify-Secret 找到公众号（找不到 → 401）
                      ├─ 在该公众号下按 code 找通道（找不到 → 404，未允许接口调用 → 403）
                      └─ NotifyService.dispatch：渲染文案 → 按标签/openid 找收件人 → 逐个发客服消息 → 记推送记录
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
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18088/wx/notify/deploy-success \
  -X POST -H 'Content-Type: application/json' \
  -H 'X-Notify-Secret: x' -d '{}'
```

最多循环 30 次、间隔 2s（约 60s 超时）。

**这里故意用了错误的密钥 `x`**，它只判断能否拿到任意 HTTP 状态码（非 `000`/非空），也就是"进程起来了、端口在听"。网关对长度不对的密钥直接拒绝、不查库，所以探针不会发出任何消息。它**不校验推送本身是否正常**，所以就绪探测通过 ≠ 通知功能正常。

## 二、通知步骤

两个步骤都是 `continue-on-error: true`（**通知失败不影响部署结论**），都用 GitHub secret `NOTIFY_SECRET` 作请求头 `X-Notify-Secret`。

### Notify success

`if: success()`，POST 到 `https://wechat.bleem.site/wx/notify/deploy-success`，body：

```json
{"project":"...","branch":"...","commit_msg":"...","commit_short":"...","actor":"...","run_url":"...","deploy_time":"UTC+8 时间"}
```

- `project` 是 `${{ github.event.repository.name }}`，即 GitHub 仓库名。
- `commit_msg` 只取提交信息第一行。
- `commit_short` 是 `commit_msg` 超过 20 个字符时截断加 `…`。模板只做占位替换，做不了截断，所以在 workflow 里算好传进去。

### Notify failure

`if: failure() || cancelled()`，POST 到 `.../wx/notify/deploy-failure`，body 多一个 `failed_step`：读取 `download_web`/`download_api`/`configure_ssh`/`upload`/`activate` 各步的 `outcome`，映射成中文步骤名（`下载前端构件`/`下载后端构件`/`配置部署 SSH 密钥`/`上传发布文件到服务器`/`激活新版本并重启服务`）；都不匹配则为 `未知步骤`。

**build job 失败时不会有失败通知**：通知步骤在 deploy job 里，而 deploy 依赖 build，build 挂了 deploy 整个被跳过。

### 通知结果如何在 Actions 里看到

网关的业务错误是 HTTP 200 + JSON 里的 `code`。脚本解析响应，以下情况打 `::warning::`，在 run 页面上能看到，run 结论仍是 success：

- `code != 200`：密钥不对（401）、通道不存在（404）、通道未允许接口调用（403）
- `skipped`：通道已停用
- `successCount` 为 0 或 `failCount > 0`：没匹配到收件人、超出 48 小时窗口、微信接口报错等

注意通知打的是**公网 HTTPS 地址**，而不是就绪探测用的 loopback，还额外依赖 `wechat.bleem.site` 的 nginx 反代正常。

## 三、服务端：推送网关

文件：`api/src/main/java/site/bleem/wechat/modules/wx/controller/NotifyGatewayController.java`

类上 `@RequestMapping("/notify")`，叠加 `server.servlet.context-path: /wx`，对外路径为 `POST /wx/notify/{code}`。不在 `/manage/**` 下，不走登录拦截。

### 鉴权和定位

1. 用请求头 `X-Notify-Secret` 查 `wx_account.notify_secret`，找到公众号。长度不是 48 位的直接拒绝，不查库。找不到 → `401 invalid notify secret`。
2. 在这个公众号下按 `code` 找 `wx_notify_channel`（`(appid, code)` 唯一）。找不到 → `404`。
3. 通道 `gateway_enabled = 0` → `403`。同一个公众号的通道共用一个推送密钥，这个开关决定拿到密钥的调用方能触发哪些通道。
4. 通道 `enabled = 0` → 返回 `skipped: true`，不发送。

### 推送密钥

- 在后台「编辑公众号配置 → 推送密钥」生成或刷新，24 字节随机数的十六进制（48 位）。
- 明文存储在 `wx_account.notify_secret`，页面上可随时查看、复制。公众号列表接口只返回"是否已配置"，不返回明文。
- 刷新后旧密钥立即失效，要同步更新 GitHub 的 `NOTIFY_SECRET`。
- 和微信的 AppSecret、EncodingAESKey 无关，泄露了最多是有人能触发这个公众号下允许接口调用的通道，刷新即可作废。

### 发送

`NotifyService.dispatch`（`NotifyServiceImpl`）：

1. `wxMpService.switchoverTo(channel.appid)`（网关线程没有 ThreadLocal 里的 appid，必须自己切）
2. 渲染文案：`{var}` 取请求体同名字段，取不到留空；`{var?}` 取不到时整行丢掉（部署失败的"失败步骤"、"日志"行靠它）
3. 找收件人：`tag` 模式先用标签名查 tag id（走 Redis 缓存），再查本地 `wx_user`（`appid` + `subscribe=true` + `JSON_CONTAINS(tagid_list, <tagId>)`）；`openid` 模式直接按逗号/空白拆
4. 逐个发客服消息，**每次发送单独 try/catch**，单个失败不中断整批
5. 写一条 `wx_notify_log`（来源 `webhook`），并把请求体记到通道的 `last_payload`，页面预览时当样例

### 用的是客服消息，不是模板消息

`sendKefuMessage` 受微信平台 **48 小时限制** —— 只能给最近 48 小时内与公众号有过交互的用户发。超窗口的用户发送会失败，计入 `failCount`。

所以"部署成功了但没收到微信通知"最常见的原因不是代码 bug，而是你太久没跟公众号说话了。随便给公众号发条消息就能重新进入窗口。Actions 的 run 页面上会有一条 `::warning::`。

### 模板消息作为替代方案

通道的 `send_type` 从 `kefu` 改成 `template` 即可切换到模板消息发送。模板消息**不受 48 小时窗口限制、不受客服下行条数上限约束**，适合客服消息总是发不出去的场景。

前提是公众号在微信后台「模板消息 → 类目模板库」里申请到模板（如「运维工作执行完成通知」，字段 `任务名称 / 项目名称 / 完成时间`）。注意**不是**「订阅模板消息」——那是另一套接口，模板_id 不通用，拿到后端老模板消息接口发会报 `40037 invalid template_id`。类目模板库里的模板申请通过后，到「我的模板」复制 template_id 填进通道，再点「同步」把模板列表拉到本地。在通道编辑里选「模板消息」→ 选模板 → 填字段映射，value 支持 `{变量}` 占位符，和客服消息走同一套渲染。切换不影响网关地址和鉴权，调用方无感知。

模板消息的字段类型有长度限制（`thing` 最多 20 字符），所以部署通知拆成了两个通道：`deploy-success-template` 用「运维工作执行完成通知」，`deploy-failure-template` 用「设备告警提醒」，靠模板标题区分成败，字段值只放变量本身，不拼状态词。

## 四、部署通知的两个通道

| code | 收件人 | 文案 |
| --- | --- | --- |
| `deploy-success` | 标签 `develop` | `✅ {commit_short} 部署成功`，加分支、提交、作者、时间 |
| `deploy-failure` | 标签 `develop` | `❌ {commit_short} 部署失败`，额外 `失败步骤：{failed_step?}`、`日志：{run_url?}` |

都在后台「消息推送」页面维护：改收件人、改文案、停用都不需要改代码或发版。换一个公众号接收部署通知：在那个公众号下建同名的两个通道并允许接口调用，再把 GitHub 的 `NOTIFY_SECRET` 换成那个公众号的推送密钥。

另有两条模板消息通道，`send_type = template`，不走 48 小时窗口限制，用来兜底客服消息总是发不出去的情况：

| code | 模板标题 | 字段映射 |
| --- | --- | --- |
| `deploy-success-template` | 运维工作执行完成通知 | `任务名称`→`{commit_short}`、`项目名称`→`{project}`、`完成时间`→`{deploy_time}` |
| `deploy-failure-template` | 设备告警提醒 | `告警原因`→`{failed_step?}`、`设备名称`→`{project}`、`告警时间`→`{deploy_time}` |

`thing` 字段上限 20 字符，所以 value 里不拼「部署成功/失败」这种状态词，成败靠模板标题区分。改动需要重新同步模板：微信后台申请到模板 → 复制 template_id 填通道 → 点「同步」。

## 五、已知行为与注意事项

### 1. 标签按名字匹配

通道的收件人填的是标签**名字**。微信那边改了标签名，通道要跟着改，否则会报"未找到标签"。

直接在微信公众号后台改标签，本地缓存（`wxUserTagsServiceCache`，TTL 10 分钟）最多有 10 分钟不一致。可以调 `DELETE /manage/wxUserTags/refresh` 强制刷新。

### 2. 收信人来自本地库，不是实时拉取

查的是本地 `wx_user.tagid_list`，而打标签对本地 `tagid_list` 的更新是**异步最终一致**的（详见 [tag-fan-management.md](tag-fan-management.md)）。刚打上标签就触发部署，这个人可能收不到通知。

### 3. 旧回调入口 `/wx/deploy/webhook`（过渡期保留）

workflow 已不再调用它。它仍用 `deploy.webhook.secret`（默认值 `wechat-admin`，弱口令）鉴权，按 `deploy.webhook.appid` 找通道，只为回滚到旧 workflow 时可用。新方式跑稳后连同 `DeployWebhookController`、`DeployWebhookProperties` 和 yml 里的 `deploy.webhook` 一起删除。

### 4. 失败通知的 `err_detail` 是死代码

failure 通知步骤里 `step_map` 每个条目的 `err_detail` 都硬编码成空字符串，拼接 `\n错误：...` 那段永远不会生效。

### 5. CI 不跑测试

`mvn -q -DskipTests package`。部署前没有任何自动化测试关卡，就绪探测也只验活。
