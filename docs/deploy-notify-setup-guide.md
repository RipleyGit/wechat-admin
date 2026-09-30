# 自动化部署与微信通知对接指南

从零对接：GitHub Actions 自动构建部署 + 部署完成后通过本平台给微信公众号发消息通知。

机制细节（链路每一步在做什么、为什么这么设计）见 [deploy-notify-pipeline.md](deploy-notify-pipeline.md)；服务器目录与 systemd 单元见 [github-actions-deployment.md](github-actions-deployment.md)。本文只讲**怎么配、在哪配、容易卡在哪**。

---

## 一、整体流程

```
push main
  → GitHub Actions build job（构建前端 dist + 后端 jar）
  → GitHub Actions deploy job
       → rsync 上传到服务器暂存目录
       → 切换文件 + systemctl restart wechat-admin
       → 就绪探测（确认端口起来了）
       → POST https://wechat.bleem.site/wx/notify/deploy-success
            → 本平台用请求头 X-Notify-Secret 定位公众号
            → 在该公众号下按 code 找通知通道
            → 渲染文案 → 按标签找收件人 → 发客服消息 → 记推送日志
```

通知失败**不会影响部署结论**（`continue-on-error: true`）。部署成功但没收到微信通知，绝大多数是通知链路的问题，不是部署的问题。

---

## 二、一次性配置（三处，有先后顺序）

### 第 1 处：部署服务器（SSH 上去操作）

**在哪执行**：`ssh -p 2232 root@aecs.bleem.site`（你的服务器）

1. 目录结构：
   ```
   /opt/wechat-admin/
     web/                       前端静态文件
     wechat-admin.jar           后端
     web.previous/              上一版（回滚用）
     wechat-admin.previous.jar
     .deploy/                   暂存目录（每次部署的 rsync 落点）
     logs/
   ```
2. 安装 systemd 单元：`api/deploy/wechat-admin.service` → `/etc/systemd/system/wechat-admin.service`
3. 创建 env 文件：`api/deploy/wechat-admin.env.example` → `/etc/wechat-admin/wechat-admin.env`，填入生产 MySQL / Redis 连接信息
4. `systemctl daemon-reload && systemctl enable wechat-admin`
5. 给**专用部署用户**（不是 root）的 `~/.ssh/authorized_keys` 加上 GitHub Actions 用的公钥

> ⚠️ **卡点 1**：`DEPLOY_USER` 不要用 root。GitHub Actions 的 SSH key 权限应只够写 `/opt/wechat-admin` 和 `systemctl restart wechat-admin`，不要给整个服务器 root 权限。

> ⚠️ **卡点 2**：服务器内存约 122 MB 可用且无 swap，**绝不能在生产服务器上手动起第二个 JVM**。只靠 systemd 管理的那一个进程。

### 第 2 处：GitHub 仓库（浏览器操作）

**在哪执行**：仓库页面 → `Settings` → 左侧 `Environments` → 点 `production` → 下拉到 `Environment secrets` → `Add secret`

必须先建一个名为 `production` 的 environment（workflow 里 `environment: production` 绑定它），下面 7 个 secret 都是**环境级**的，不是 repository 级的：

| Secret | 值 | 怎么拿 |
| --- | --- | --- |
| `DEPLOY_HOST` | `aecs.bleem.site` | 服务器域名 |
| `DEPLOY_PORT` | `2232` | SSH 端口 |
| `DEPLOY_USER` | 专用部署用户名 | 第 1 处建的那个，**不是 root** |
| `DEPLOY_PATH` | `/opt/wechat-admin` | 部署根目录 |
| `DEPLOY_SSH_PRIVATE_KEY` | 私钥全文（含 BEGIN/END 行） | `ssh-keygen -t ed25519` 生成，公钥加到服务器 deploy 用户的 authorized_keys |
| `DEPLOY_KNOWN_HOSTS` | ssh-keyscan 输出 | 本地跑 `ssh-keyscan -p 2232 aecs.bleem.site` |
| `NOTIFY_SECRET` | 公众号推送密钥（48 位十六进制） | **见第 3 处，必须在平台生成后才能填** |

> ⚠️ **卡点 3**：secret 必须建在 `production` environment 下，不是 `Settings → Secrets and variables → Actions → Repository secrets`。workflow 声明了 `environment: production`，只会读该 environment 的 secret，repository 级 secret 读不到。

> ⚠️ **卡点 4**：`DEPLOY_KNOWN_HOSTS` 很容易漏。不配或配错，rsync 第一次连接会因为 `Host key verification failed` 直接挂。在本机跑 `ssh-keyscan -p 2232 aecs.bleem.site`，整段输出粘进去。

### 第 3 处：本平台后台（浏览器操作）

**在哪执行**：`https://wechat.bleem.site` 登录后台

1. 进「公众号管理」→ 编辑要接收通知的那个公众号 → 下拉到「推送密钥」→ 点「生成」→ 复制那串 48 位十六进制
2. 把复制到的值填进 GitHub 的 `NOTIFY_SECRET`（回到第 2 处）
3. 进「消息推送」页面，确认有两个通道：
   - `deploy-success`（部署成功通知）
   - `deploy-failure`（部署失败通知）
4. 两个通道都要：收件人填标签名（默认 `develop`）、`enabled` 开、`gatewayEnabled`（接口调用）开

> ⚠️ **卡点 5（顺序最容易卡的一步）**：`NOTIFY_SECRET` 的值是平台生成的，不是你自己编的。顺序必须是**先在平台生成 → 复制 → 再填到 GitHub**。反过来填 GitHub 再去平台生成，值对不上，通知会返回 `401 invalid notify secret`。

> ⚠️ **卡点 6**：在平台**刷新推送密钥后，GitHub 的 `NOTIFY_SECRET` 必须同步更新**，否则旧密钥失效，通知全部 401。

> ⚠️ **卡点 7**：通道的 `gatewayEnabled`（接口调用）开关必须打开。没打开的通道被网关调用会返回 `403 channel not open to gateway`。`deploy-success` / `deploy-failure` 两个通道上线时已默认打开，自己新建的通道默认关闭。

---

## 三、每次部署的自动流程（不用人干预）

push 到 `main` 即触发。不需要打 tag、不需要手动 run。

1. **build job**：checkout → `npm ci && npm run build` → `mvn -DskipTests package` → 上传两个 artifact（`web-dist` / `api-jar`）
2. **deploy job**（只在 `push main` 跑，PR 只跑 build）：
   - 下载 artifact
   - 配 SSH key
   - rsync 到 `/opt/wechat-admin/.deploy/<commit-sha>/`
   - 校验 jar 非空 + index.html 存在 → 切换 `web` / `wechat-admin.jar`（旧的改名 `.previous`）→ `systemctl restart wechat-admin` → 就绪探测
   - `success` → POST `deploy-success`；`failure/cancelled` → POST `deploy-failure`

PR 不会触发部署，只会跑 build 校验编译是否过。

---

## 四、容易卡住的步骤（重点）

### 4.1 部署成功了，但没收到微信通知

这是最常见的"看起来坏了其实没坏"。按顺序排查：

**a. 看 GitHub Actions run 页面有没有 `::warning::`**

通知脚本会把业务错误打成一黄底的 warning 行。根据文案判断：

| warning 文案 | 含义 | 处理 |
| --- | --- | --- |
| `部署通知未发出：invalid notify secret` | 密钥不对 | 平台刷新密钥后没同步到 GitHub `NOTIFY_SECRET`，或 GitHub secret 填错 |
| `部署通知未发出：channel not found: deploy-success` | 通道不存在 | 平台「消息推送」页面没建这个 code 的通道，或 appid 不匹配 |
| `部署通知未发出：channel not open to gateway` | 通道没开接口调用 | 编辑通道，打开 `gatewayEnabled` |
| `部署通知通道已停用，未发送` | 通道 `enabled=0` | 编辑通道，启用它 |
| `部署通知成功 0/1：错误代码：45047...` | 微信客服接口额度耗尽 | **见 4.2** |
| `部署通知成功 0/1：`（空 errorMsg） | 没匹配到收件人 | 标签名写错 / 本地库没有这个标签的粉丝 |

**b. 没有 warning、响应 `successCount > 0`，但还是没收到**

微信把消息发出去了但你这边没收到 —— 检查收件人是不是真的关注了公众号、标签有没有打对。

### 4.2 微信 `45047`：客服接口下行条数超过上限

这是**外部额度**问题，不是代码 bug。微信客服消息（`sendKefuMessage`）有 48 小时窗口限制：只能给**最近 48 小时内与公众号有过交互**的用户发消息。超窗口的用户发送会失败。

**解决**：随便给那个公众号发一条消息（文字、图片都行），重新进入 48 小时窗口，下次部署通知就能收到了。

> 这是客服消息的固有限制，不是模板消息。如果业务场景需要不受 48 小时限制的推送，要改用模板消息（通道的 `send_type` 改成 `template`，目前预留未启用）。

### 4.3 调本平台后台接口返回「没有权限，请联系管理员授权」

`{"msg":"没有权限，请联系管理员授权","code":500}` 是 Shiro `@RequiresPermissions` 鉴权失败。两个原因：

**a. `sys_menu` 里没有这个 perm**

本平台连超级管理员的权限都是从 `sys_menu` 聚合来的（`ShiroServiceImpl.getUserPermissions`），不是写死的。新增 controller 的 `@RequiresPermissions("xxx")` 必须在 `sys_menu` 里种对应行，否则**连 admin 都是 403**。

**b. 种了 perm，但当前会话还是旧权限集**

Shiro 在登录时把权限快照进会话，DB 改了但会话不会自动刷新。

**解决**：**退出重新登录一次** admin，新权限才生效。

> 这次上线就踩了：`wx:notifychannel:send` 在生产 `sys_menu` 里缺失（迁移 SQL 的父菜单判重用 `url='wx/notify'`，但生产那条菜单的 url 是 NULL，导致子菜单整批漏插），手动 UPDATE 补上后还得重登才生效。

### 4.4 就绪探测期间应用日志有一条「推送网关鉴权失败」WARN

这是**预期行为**，不是故障。就绪探测故意用错误密钥 `x` 打 `/wx/notify/deploy-success`，只判断"能不能拿到 HTTP 状态码"（进程起来了、端口在听）。网关对长度不是 48 位的密钥直接拒绝不查库，探针不会发出任何消息。看到这条 WARN 忽略即可。

### 4.5 通知步骤打的地址是公网 HTTPS，不是 loopback

就绪探测用 `http://127.0.0.1:18088`（直连应用端口），但通知步骤打 `https://wechat.bleem.site`（过 nginx 反代）。所以**通知功能正常 ≠ 应用起来了**，还依赖 nginx 反代正常、域名解析正常。

### 4.6 GitHub Actions 上传 jar 很慢

81 MB 的 jar 通过 rsync 到全新暂存目录（每个 commit 一个新目录，无法增量），实测曾花 57 分钟（约 24 KB/s）。这是网络 + rsync 无法增量的叠加，目前没优化。不影响部署正确性，只影响速度。后续可预热暂存目录做增量。

### 4.7 本机直接构建会失败（JDK 版本）

CI 用 JDK 8。本机若装的是 JDK 21，Lombok 会报 `NoSuchFieldError: Class com.sun.tools.javac.tree.JCTree$JCImport does not have member field 'qualid'`。本机构建必须切到 JDK 8：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_271.jdk/Contents/Home \
  mvn -DskipTests package
```

### 4.8 从本机 push main 触发了部署，但 SSH 推送被代理拦

本机若开了 SOCKS 代理（如 127.0.0.1:7897），`github.com` 的 SSH 会被代理拒绝，报 `Connection closed by UNKNOWN port 65535`。改走 HTTPS + gh 凭据：

```bash
git -c credential.helper='!gh auth git-credential' \
  push https://github.com/RipleyGit/wechat-admin.git main:main
```

> ⚠️ **push main 即触发生产部署**。推送前确认改动已验证，不要随手 push。

---

## 五、验证清单

上线或改配置后，按这个顺序验证：

1. **服务器进程**：`ssh -p 2232 root@aecs.bleem.site 'systemctl is-active wechat-admin'` → `active`
2. **应用就绪**：`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18088/wx/notify/deploy-success -X POST -H 'X-Notify-Secret: x' -d '{}'` → 任意状态码（非 000）
3. **平台权限**：后台调 `POST /wx/manage/notifyChannel/send` → 不再返回"没有权限"
4. **网关鉴权**：用正确密钥打 `deploy-success` → 返回 `code:200`；用错误密钥 → `401`
5. **通道定位**：用正确密钥但 code 写错 → `404 channel not found`
6. **实际送达**：先给公众号发条消息进 48 小时窗口，再触发一次部署，确认收到微信通知
7. **Actions run 页面**：看有没有 `::warning::`，没有 = 全链路正常

---

## 六、相关文件

| 文件 | 作用 |
| --- | --- |
| `.github/workflows/deploy.yml` | CI/CD 主流程 |
| `api/src/main/java/site/bleem/wechat/modules/wx/controller/NotifyGatewayController.java` | 推送网关入口 `/wx/notify/{code}` |
| `api/src/main/java/site/bleem/wechat/modules/wx/manage/NotifyChannelManageController.java` | 后台通道配置 `/manage/notifyChannel/*` |
| `api/src/main/java/site/bleem/wechat/modules/wx/controller/WxAccountConfigController.java` | 公众号推送密钥生成 `/manage/console/account-config/{appid}/notify-secret` |
| `api/db/migrations/20260928_add_wx_notify_channel.sql` | 通道表 + 推送记录表 + sys_menu 权限种子 |
| `api/db/migrations/20260929_notify_secret_per_account.sql` | 公众号级推送密钥字段 + 通道网关开关 |
