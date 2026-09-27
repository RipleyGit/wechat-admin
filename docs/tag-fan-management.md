# 标签与粉丝管理

记录标签、粉丝（`wx_user`）的数据模型、同步机制、接口和前端交互。

## 一、数据模型：一个反直觉的设计

**标签目录本身没有本地表。**

- 标签的 id 和名字：**只存在于微信服务器**，本地仅有 Redis 缓存（`wxUserTagsServiceCache`，TTL 10 分钟），代码里用微信 SDK 的 `me.chanjar.weixin.mp.bean.tag.WxUserTag`（只有 `id`/`name`/`count` 三个字段）。库里没有 `wx_user_tag` 表，也没有对应 entity。
- 粉丝身上挂了哪些标签：存本地，`wx_user.tagid_list`，MySQL `json` 列，内容是标签 id 数组。

所以"标签叫什么名字"这个信息，每次都要么命中 Redis 缓存、要么实时问微信。粉丝列表想显示标签名，就得拿 `tagid_list` 里的 id 去和缓存的标签表做一次内存 join（见后面的 `/manage/wxUser/list`）。

### WxUser entity

`api/src/main/java/site/bleem/wechat/modules/wx/entity/WxUser.java`，`@TableName("wx_user")`，`@TableId(type = IdType.INPUT)` 打在 `openid` 上 —— 主键是 openid，非自增（微信分配的）。

字段：`openid`、`appid`、`phone`、`nickname`、`sex`、`city`、`province`、`country`、`headimgurl`、`subscribeTime`、`subscribe`、`unionid`、`remark`、`tagidList`（`JSONArray` → 列 `tagid_list`）、`subscribeScene`、`qrSceneStr`。

三个构造函数：

- `WxUser(String openid)` —— 裸的
- `WxUser(WxMpUser, appid)` —— 从微信用户详情接口结果转换。**只有 `subscribe` 为 true 时才填充** nickname/头像/关注时间/unionid/remark/tagidList 等字段；已取关的用户除了 openid/appid/subscribe=false 之外都是 null
- `WxUser(WxOAuth2UserInfo, appid)` —— 网页授权流程拿到的用户信息（字段更少），无条件置 `subscribe = true`

### remark 字段的来源

`remark` 是**微信侧的备注**（公众号后台给粉丝设的备注名），同步时从 `wxMpUser.getRemark()` 取，写入本地只做展示。

代码里**没有任何地方调用微信的"设置备注"接口**，也就是说这个系统对 remark 是只读同步 —— 本地改不了，要改得去微信后台改完再同步粉丝。

## 二、标签管理

### WxUserTagsServiceImpl

`@CacheConfig(cacheNames = {"wxUserTagsServiceCache"})`，缓存 key 为 `'WX_USER_TAGS' + #appid`。

| 方法 | 缓存行为 | 做了什么 |
| --- | --- | --- |
| `getWxTags(appid)` | `@Cacheable` | `switchoverTo` 后调微信 `tagGet()`，结果缓存 10 分钟 |
| `creatTag(appid, name)`（原代码拼写如此） | `@CacheEvict` | 调微信 `tagCreate` |
| `updateTag(appid, tagid, name)` | `@CacheEvict` | 调微信 `tagUpdate` |
| `deleteTag(appid, tagid)` | `@CacheEvict` | 调微信 `tagDelete` |
| `batchTagging(appid, tagid, openidList)` | —— | 调微信 `batchTagging`，然后 `refreshUserInfoAsync` |
| `batchUnTagging(appid, tagid, openidList)` | —— | 调微信 `batchUntagging`，然后 `refreshUserInfoAsync` |
| `tagging(tagid, openid)` / `untagging(tagid, openid)` | —— | 单用户版，appid 从 `WxMpConfigStorageHolder.get()` 取，之后调**同步**的 `refreshUserInfo` |
| `refreshTagCache(appid, user)` | `@CacheEvict` | 方法体只打日志，纯粹用来踢缓存 |

`@CacheEvict` 默认在方法正常返回后执行，所以微信 API 抛 `WxErrorException` 时缓存不会被清 —— 这个行为是对的。

### 打标签为什么是"最终一致"

`batchTagging` 的两步：

1. 调微信 `getUserTagService().batchTagging(tagid, openidList)` —— 微信侧生效
2. 调 `wxUserService.refreshUserInfoAsync(openidList, appid)`

**第 2 步不直接写 `tagid_list`**。它是 `@Async` 的，内部对每个 openid 提交任务到 `TaskExcutor` 线程池，任务里调微信 `userInfo(openid, null)` 拉完整用户信息、用 `WxUser(WxMpUser, appid)` 构造（顺带带上微信侧最新的 `tagidList`）、再 `saveOrUpdate`。

也就是说：**微信侧打标签成功 ≠ 本地 `tagid_list` 已更新**。中间隔了一轮异步的、每个用户一次的微信 API 往返。如果异步任务失败、或应用在任务跑完前重启，本地数据就会停留在旧状态，直到该用户被再次触碰、或跑一次全量同步。

## 三、粉丝同步

### 手动全量同步：syncWxUsers

`WxUserServiceImpl.syncWxUsers(appid)`，`@Async`，由 `POST /manage/wxUser/syncWxUsers` 触发（前端"同步粉丝"按钮）。

1. **Redis 锁**：`setIfAbsent` 到 key `wx:user:sync:lock`，value 为 appid，1 小时过期。拿不到锁则 `Assert.isTrue` 抛错，提示"后台有同步任务正在进行中，请稍后重试"。防止并发/重复跑
2. 分页拉 openid：`wxMpUserService.userList(nextOpenid)`，每页上限 10000，用 `nextOpenid` 做游标，条件是有下一页游标且上页数量 ≥ 10000
3. 每页交给重载的 `syncWxUsers(openids, appid)`
4. `finally` 里一定释放锁（即使抛 `WxErrorException`）

`syncWxUsers(List<String> openids, appid)` 负责拉详情：按 100 一批切分（微信 `userInfoList` 批量接口的上限），每批提交给 `TaskExcutor`，调 `userInfoList(subOpenids)`、转成 `WxUser`、`saveOrUpdateBatch`。

因为转换时带上了微信侧的 `tagidList`，**全量同步会用微信的权威数据覆盖所有粉丝的 `tagid_list`** —— 这是修正前面那种异步漂移的兜底手段。

没有找到 `@Scheduled` 或 cron 触发，**只能手动触发**。

### updateOrInsert 的部分更新语义

`WxUserServiceImpl.updateOrInsert(user)`：先按 openid count 判断存在性，不存在直接 `insert`；存在则构造 `UpdateWrapper`，**每个字段只在传入值非 null/非空时才 set**（字符串用 `StringUtils.hasText` 判断）。

唯一的例外是 `subscribe`：它是原始 `boolean`，没有"缺失"状态可判断，所以**无条件覆盖**。`tagidList` 则是 `!= null` 时才写。

这个方法主要服务于关注/取关等事件回调路径（本次梳理未逐一追溯调用方）。

## 四、接口清单

鉴权统一走 Shiro `@RequiresPermissions`，appid 从 cookie 读。

### 标签：`/manage/wxUserTags`

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/list` | `wx:wxuser:info` | 返回 `{list: WxUserTag[]}`，走缓存 |
| POST | `/save` | `wx:wxuser:save` | body `WxUserTagForm{id, name}`；`id` 为空或 ≤0 走新建，否则走改名 |
| POST | `/delete/{tagid}` | `wx:wxuser:save` | 校验 tagid 非空且为正数 |
| POST | `/batchTagging` | `wx:wxuser:save` | body `WxUserBatchTaggingForm{tagid, openidList}` |
| POST | `/batchUnTagging` | `wx:wxuser:save` | 同上 |
| DELETE | `/refresh` | `wx:wxuser:info` | 踢缓存后重新拉取并返回，用于微信后台直接改了标签的场景 |

表单约束：

- `WxUserTagForm`：`name` 非空、长度 1-30
- `WxUserBatchTaggingForm`：`tagid` 非空；`openidList` 为 `String[]`，`@Length(min=1,max=50)` —— **单次批量操作上限 50 个 openid**

### 粉丝：`/manage/wxUser`

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/list` | `wx:wxuser:list` | 支持 `nickname`（模糊）、`city`、`tagid`（逗号分隔多值）、`qrSceneStr` + 分页；返回 `{page: ...}`，记录类型为 `WxUserVO`（比 entity 多一个 `tagNames`） |
| POST | `/listByIds` | `wx:wxuser:list` | body `String[] openids` |
| GET | `/info/{openid}` | `wx:wxuser:info` | 返回 `{wxUser: ...}` |
| POST | `/syncWxUsers` | `wx:wxuser:save` | 异步触发全量同步，立即返回"任务已建立"，**没有进度查询接口** |
| POST | `/delete` | `wx:wxuser:delete` | 只删本地记录，**不调微信接口**（不是取关） |

### 标签名回填

`/list` 接口在拿到分页结果后，用 `getWxTags(appid)` 构造 `Map<Long,String>`（`Collectors.toMap(getId, getName, (a,b)->a)`），再把每个粉丝的 `tagidList` 映射成 `tagNames`，装进 `WxUserVO`。

`WxUserVO extends WxUser` 只多一个 `List<String> tagNames`，且只在 controller 里手工构造、不参与任何 mapper 查询 —— 这样避免给 entity 加非持久化字段影响自动映射。

### 多标签筛选是 OR 语义

`WxUserServiceImpl.queryPage`：`tagid` 参数按逗号切分后，在一个 `wrapper.and(w -> {...})` 里用 `w.apply("JSON_CONTAINS(tagid_list,{0})", id)` 打头、后续每个 `.or().apply(...)`，形成一个带括号的 OR 组。

即**选中多个标签时，命中任一标签的粉丝都会返回**（不是交集）。筛选由 MySQL 的 `JSON_CONTAINS` 完成，不在应用层过滤。

## 五、前端

### API 层

`web/src/api/console.js`，与后端一一对应：

```js
followers: (params) => http.get('/manage/wxUser/list', { params }),
syncFollowers: () => http.post('/manage/wxUser/syncWxUsers'),
tags: () => http.get('/manage/wxUserTags/list'),
saveTag: (data) => http.post('/manage/wxUserTags/save', data),
deleteTag: (id) => http.post(`/manage/wxUserTags/delete/${id}`),
batchTag: (data) => http.post('/manage/wxUserTags/batchTagging', data),
batchUnTag: (data) => http.post('/manage/wxUserTags/batchUnTagging', data),
```

### 粉丝管理页

`web/src/views/FollowersView.vue`（本文件是单行紧凑写法，改动时注意保持风格一致）。

列：头像、昵称（空则显示"未授权"）、openid、性别（0/1/2 → 未知/男/女）、城市+省份、关注场景、二维码场景值、备注、关注时间、标签（把 `tagNames` 渲染成 `el-tag` 芯片）。

- **标签筛选**：`el-select` multiple，选中的 id 数组 join 成逗号串作为 `tagid` 参数，正好对上后端的切分逻辑
- **打标签入口**：每行一个"打标签"按钮，顶部一个"批量打标签（N）"按钮（N 为表格勾选数），都走同一个 `openTagDialog(rows)`
- **对话框预勾选取的是交集**：`tagIdSets.every(s => s.has(t.id))` —— 批量场景下只有**所有**选中粉丝都有的标签才预勾选，不是并集。这样"保存"时不会误给部分人加上他们本来没有的标签

### diff-sync 保存逻辑

`saveTags()` 不是全量覆盖，而是算差集：

```
beforeIds = 所有选中行当前标签 id 的并集
afterIds  = 对话框里勾选的 id
toAdd     = afterIds - beforeIds   → 每个调一次 batchTag
toRemove  = beforeIds - afterIds   → 每个调一次 batchUnTag
```

每个变动的标签发一次请求，一次请求覆盖全部选中的 openid。所以一次"保存"可能触发多个串行请求。这样做是为了复用现成的批量接口，不必新增后端接口。

## 六、已知行为与注意事项

### 1. 打标签后本地数据是异步更新的（最容易踩）

如第二节所述，`batchTagging` 只保证微信侧生效，本地 `tagid_list` 靠异步 `refreshUserInfoAsync` 补上。

直接后果：`saveTags()` 在最后一个请求 resolve 之后立刻调 `load()` 刷新表格，而那个 promise 只代表**微信 API 返回了**，本地 DB 的更新还在另一个线程上飞。**所以保存后刷新出来的标签列，有可能还是旧的。**

> 改进建议：几个方向，按代价递增 ——（a）前端保存成功后延迟 1-2s 再 `load()`，最省事但不可靠；（b）前端乐观更新，直接用 `afterIds` 更新本地 row 的显示，不等后端；（c）后端 `batchTagging` 里同步更新一次本地 `tagid_list`（微信调用成功后直接改本地 json 列，不必再拉一次用户详情），异步 refresh 保留作为兜底 —— 这个最彻底。

### 2. 标签目录只在 Redis，微信后台直接改会有 10 分钟不一致

没有本地表，缓存 TTL 10 分钟。在本系统内增删改标签会主动踢缓存，但**在微信公众号后台直接操作不会**。

而且 `DELETE /manage/wxUserTags/refresh` 这个手动刷新接口，`console.js` 里没有对应封装，前端也没有任何按钮触发它 —— 目前只能直接调 API。

> 改进建议：在标签管理页加一个"刷新缓存"按钮接上这个接口，成本很低。

### 3. 批量操作上限 50，前端没有防护

后端 `@Length(max=50)` 卡在 50 个 openid。但前端表格可以一次勾选更多（分页 limit 可以调大），勾超了直接提交会被后端校验拒掉。

> 改进建议：前端在 `saveTags()` 里按 50 分片循环提交，或至少在勾选数超限时禁用按钮并提示。

### 4. 同步粉丝是黑盒

`POST /syncWxUsers` 立刻返回"任务已建立"，之后没有任何进度、成功/失败反馈。粉丝多的时候要跑很久（分页 10000/页拉 openid，再 100/批拉详情），用户只能靠反复刷列表猜。

失败也是静默的 —— 异步任务里的异常只进日志。

> 改进建议：把同步状态写进 Redis（正在跑的 key 已经有了，即 `wx:user:sync:lock`），加一个查询接口返回"是否在跑 + 已处理数量"，前端轮询显示进度。

### 5. `/manage/wxUser/delete` 只删本地

名字容易误解，它不会让用户取关，只是删本地记录。删完再跑一次全量同步，这些人又会回来（只要还关注着）。

### 6. remark 只读

见第一节。前端展示的备注来自微信，本系统改不了，也没有调微信设置备注接口的代码。

> 改进建议：微信有 `updateRemark` 接口，如果需要在本系统里维护备注，可以接上；否则文档里说清楚只读就够了，避免以后误以为是 bug。
