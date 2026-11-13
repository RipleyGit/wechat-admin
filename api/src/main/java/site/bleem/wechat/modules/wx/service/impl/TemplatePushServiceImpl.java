package site.bleem.wechat.modules.wx.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.tag.WxUserTag;
import me.chanjar.weixin.mp.bean.template.WxMpTemplate;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateData;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateMessage;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import site.bleem.wechat.common.exception.RRException;
import site.bleem.wechat.modules.wx.dao.MsgTemplateMapper;
import site.bleem.wechat.modules.wx.dao.TemplateMsgLogMapper;
import site.bleem.wechat.modules.wx.dao.WxPushBatchMapper;
import site.bleem.wechat.modules.wx.entity.MsgTemplate;
import site.bleem.wechat.modules.wx.entity.TemplateMsgLog;
import site.bleem.wechat.modules.wx.entity.WxAccount;
import site.bleem.wechat.modules.wx.entity.WxPushBatch;
import site.bleem.wechat.modules.wx.entity.WxUser;
import site.bleem.wechat.modules.wx.form.PushTarget;
import site.bleem.wechat.modules.wx.form.TemplatePushForm;
import site.bleem.wechat.modules.wx.form.TemplatePushResult;
import site.bleem.wechat.modules.wx.service.MsgTemplateService;
import site.bleem.wechat.modules.wx.service.TemplatePushService;
import site.bleem.wechat.modules.wx.service.WxUserTagsService;
import site.bleem.wechat.modules.wx.service.WxUserService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service("templatePushService")
@Slf4j
@RequiredArgsConstructor
public class TemplatePushServiceImpl implements TemplatePushService {

    private static final int MAX_RECIPIENTS = 100;
    /** 模板拉取结果在 Redis 缓存 10 分钟，避免调用方传错 ID 时每次都打微信接口 */
    private static final Duration TEMPLATE_FETCH_TTL = Duration.ofMinutes(10);
    private static final String TEMPLATE_FETCH_KEY = "wx:push:templates:";
    /** 模板内容里的 {{keyword1.DATA}} 占位符 */
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("\\{\\{([A-Za-z0-9_]+)\\.DATA\\}\\}");
    private static final int MAX_FAILURES_IN_RESPONSE = 50;
    /** 串行发送间隔，避免对单账号瞬时高压 */
    private static final long SEND_INTERVAL_MILLIS = 50L;

    private final WxMpService wxMpService;
    private final WxUserService wxUserService;
    private final WxUserTagsService wxUserTagsService;
    private final MsgTemplateService msgTemplateService;
    private final MsgTemplateMapper msgTemplateMapper;
    private final WxPushBatchMapper wxPushBatchMapper;
    private final TemplateMsgLogMapper templateMsgLogMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public TemplatePushResult push(WxAccount account, TemplatePushForm form,
                                   String clientIp, String userAgent) {
        String appid = account.getAppid();
        validateForm(form);

        // 幂等：clientMsgId 命中已有批次就直接返回首次结果
        if (StringUtils.hasText(form.getClientMsgId())) {
            WxPushBatch existed = findBatchByIdempotency(appid, form.getClientMsgId());
            if (existed != null) {
                log.info("[通用推送] 幂等命中 appid={} clientMsgId={} batchId={} status={}",
                        appid, form.getClientMsgId(), existed.getBatchId(), existed.getStatus());
                return rebuildResult(existed);
            }
        }

        WxPushBatch batch = newBatch(account, form, clientIp, userAgent);
        // 校验阶段全部走完才允许发送，保证 4xx 时一条都没发
        try {
            wxMpService.switchoverTo(appid);

            MsgTemplate template = resolveTemplate(appid, form.getTemplateId());
            validateDataCoversKeywords(template, form.getData());

            List<String> openids = resolveRecipients(appid, form.getTargets(), batch);

            TemplatePushResult result = send(account, form, template, openids, batch);
            finalizeBatch(batch, result, WxPushBatch.STATUS_SUCCESS, WxPushBatch.STATUS_PARTIAL);
            wxPushBatchMapper.insert(batch);
            return result;
        } catch (RRException e) {
            // 校验失败：批次记 rejected，便于审计「谁试过发什么被拒」
            batch.setStatus(WxPushBatch.STATUS_REJECTED);
            batch.setErrorMsg(e.getMessage());
            batch.setSuccessCount(0);
            batch.setFailCount(0);
            safeInsert(batch);
            throw e;
        } catch (Exception e) {
            log.error("[通用推送] 执行失败 appid={} batchId={}", appid, batch.getBatchId(), e);
            batch.setStatus(WxPushBatch.STATUS_FAILED);
            batch.setErrorMsg(brief(e.getMessage()));
            safeInsert(batch);
            throw new RRException("推送执行失败：" + brief(e.getMessage()), 500);
        }
    }

    private void validateForm(TemplatePushForm form) {
        if (form == null) {
            throw new RRException("请求体不能为空", 400);
        }
        if (!StringUtils.hasText(form.getTemplateId())) {
            throw new RRException("templateId 不能为空", 400);
        }
        if (CollectionUtils.isEmpty(form.getData())) {
            throw new RRException("data 不能为空", 400);
        }
        PushTarget targets = form.getTargets();
        if (targets == null
                || (CollectionUtils.isEmpty(targets.getOpenids())
                && CollectionUtils.isEmpty(targets.getPhones())
                && CollectionUtils.isEmpty(targets.getNicknames())
                && CollectionUtils.isEmpty(targets.getTagids()))) {
            throw new RRException("至少指定一个推送目标", 400);
        }
        int requested = size(targets.getOpenids()) + size(targets.getPhones())
                + size(targets.getNicknames()) + size(targets.getTagids());
        if (requested > MAX_RECIPIENTS) {
            throw new RRException("收件人总数 " + requested + " 超过单次上限 " + MAX_RECIPIENTS + "，请分批", 400);
        }
    }

    private int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    /**
     * 模板解析：本地 → 微信 → 400。只能拉该公众号已添加过的模板，
     * 任意 templateId 不会凭空变出来（docs §3）。
     */
    private MsgTemplate resolveTemplate(String appid, String templateId) {
        MsgTemplate local = msgTemplateService.getOne(new QueryWrapper<MsgTemplate>()
                .eq("appid", appid)
                .eq("template_id", templateId)
                .eq("status", 1)
                .last("LIMIT 1"), false);
        if (local != null) {
            return local;
        }

        List<WxMpTemplate> remote = fetchAllPrivateTemplates(appid);
        for (WxMpTemplate t : remote) {
            if (templateId.equals(t.getTemplateId())) {
                return upsertTemplate(appid, t);
            }
        }
        throw new RRException("模板不存在或尚未添加到该公众号：" + templateId, 400);
    }

    private List<WxMpTemplate> fetchAllPrivateTemplates(String appid) {
        // 缓存原始 JSON 字符串而非对象 List：RedisTemplate 用 JSON 序列化器存 List<WxMpTemplate>
        // 时，泛型类型擦除会让读回来的元素变成 LinkedHashMap，强转会 ClassCastException。
        // 存字符串、取出来再 parseArray，类型安全且与 weixin-java-mp 的 Gson 模型一致。
        String cacheKey = TEMPLATE_FETCH_KEY + appid;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof String) {
            try {
                return JSON.parseArray((String) cached, WxMpTemplate.class);
            } catch (Exception e) {
                log.warn("[通用推送] 模板缓存反序列化失败，重新拉取 appid={}", appid, e);
            }
        }
        List<WxMpTemplate> templates;
        try {
            templates = wxMpService.getTemplateMsgService().getAllPrivateTemplate();
        } catch (WxErrorException e) {
            log.warn("[通用推送] 拉取公众号模板失败 appid={}", appid, e);
            throw new RRException("拉取公众号模板失败：" + e.getError().getErrorMsg(), 400);
        }
        redisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(templates), TEMPLATE_FETCH_TTL);
        return templates;
    }

    /**
     * 按 (appid, template_id) 定向 upsert，不复用 syncWxTemplate：那个按 template_id 全局删除，
     * 会误删别的公众号的同名模板（docs §7）。
     */
    private MsgTemplate upsertTemplate(String appid, WxMpTemplate remote) {
        MsgTemplate existing = msgTemplateService.getOne(new QueryWrapper<MsgTemplate>()
                .eq("appid", appid)
                .eq("template_id", remote.getTemplateId())
                .last("LIMIT 1"), false);
        MsgTemplate tpl = new MsgTemplate(remote, appid);
        if (existing != null) {
            tpl.setId(existing.getId());
            msgTemplateService.updateById(tpl);
        } else {
            msgTemplateService.save(tpl);
        }
        // 拉取结果已写入本地，下次可直接命中，不必再等缓存
        return tpl;
    }

    private void validateDataCoversKeywords(MsgTemplate template, List<TemplatePushForm.DataItem> data) {
        Set<String> keywords = extractKeywords(template.getContent());
        if (keywords.isEmpty()) {
            return;
        }
        Set<String> provided = data.stream()
                .map(TemplatePushForm.DataItem::getName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        List<String> missing = keywords.stream()
                .filter(k -> !provided.contains(k))
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            throw new RRException("模板字段缺失，未提供：" + String.join(", ", missing), 400);
        }
    }

    private Set<String> extractKeywords(String content) {
        if (!StringUtils.hasText(content)) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        Matcher matcher = KEYWORD_PATTERN.matcher(content);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * 收件人解析。四种来源各自校验存在性，再合并去重。
     * openid / 手机号 / 昵称命中已取关用户 → 整批 400；tagid 只展开 subscribe=1，不触发整批失败。
     */
    private List<String> resolveRecipients(String appid, PushTarget targets, WxPushBatch batch) {
        List<String> errors = new ArrayList<>();
        List<String> unsubscribed = new ArrayList<>();
        // 用 LinkedHashMap 保稳定顺序：解析顺序就是去重后的发送顺序，便于调用方对照
        LinkedHashMap<String, String> resolved = new LinkedHashMap<>();
        int requested = 0;

        // openid
        if (!CollectionUtils.isEmpty(targets.getOpenids())) {
            requested += targets.getOpenids().size();
            resolveByOpenids(appid, targets.getOpenids(), resolved, errors, unsubscribed);
        }
        // 手机号：只查 wx_user.phone（docs §4.2）
        if (!CollectionUtils.isEmpty(targets.getPhones())) {
            requested += targets.getPhones().size();
            resolveByPhones(appid, targets.getPhones(), resolved, errors, unsubscribed);
        }
        // 昵称：命中多个全部发送（docs §4.3）
        if (!CollectionUtils.isEmpty(targets.getNicknames())) {
            requested += targets.getNicknames().size();
            resolveByNicknames(appid, targets.getNicknames(), resolved, errors, unsubscribed);
        }
        // tagid：群体选择，只取 subscribe=1，不参与取关失败
        if (!CollectionUtils.isEmpty(targets.getTagids())) {
            requested += targets.getTagids().size();
            resolveByTagids(appid, targets.getTagids(), resolved, errors);
        }

        if (!errors.isEmpty()) {
            throw new RRException(String.join("\n", errors), 400);
        }
        if (!unsubscribed.isEmpty()) {
            throw new RRException("以下用户已取关，无法推送：" + String.join(", ", unsubscribed), 400);
        }
        if (resolved.isEmpty()) {
            throw new RRException("没有解析到任何收件人", 400);
        }
        // 输入数组上限挡不住 tagid 展开：一个标签可能命中上千粉丝。
        // 这里对去重后的实际收件人数再卡一次，超过就让调用方分批（docs §4.5）
        if (resolved.size() > MAX_RECIPIENTS) {
            throw new RRException("解析后收件人 " + resolved.size()
                    + " 超过单次上限 " + MAX_RECIPIENTS + "，请分批或缩小标签范围", 400);
        }

        batch.setRequestedCount(requested);
        batch.setResolvedCount(resolved.size());
        return new ArrayList<>(resolved.keySet());
    }

    private void resolveByOpenids(String appid, List<String> openids,
                                  Map<String, String> resolved, List<String> errors,
                                  List<String> unsubscribed) {
        List<WxUser> fans = wxUserService.list(new QueryWrapper<WxUser>()
                .eq("appid", appid)
                .in("openid", openids));
        Set<String> found = fans.stream().map(WxUser::getOpenid).collect(Collectors.toSet());
        List<String> missing = openids.stream()
                .filter(o -> !found.contains(o))
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            errors.add("以下 openid 不在公众号粉丝中：" + String.join(", ", missing));
        }
        collectSubscribed(fans, resolved, unsubscribed);
    }

    private void resolveByPhones(String appid, List<String> phones,
                                 Map<String, String> resolved, List<String> errors,
                                 List<String> unsubscribed) {
        List<WxUser> fans = wxUserService.list(new QueryWrapper<WxUser>()
                .eq("appid", appid)
                .in("phone", phones));
        Map<String, List<WxUser>> byPhone = fans.stream()
                .filter(f -> StringUtils.hasText(f.getPhone()))
                .collect(Collectors.groupingBy(WxUser::getPhone));
        List<String> ambiguous = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String phone : phones) {
            List<WxUser> matched = byPhone.get(phone);
            if (matched == null || matched.isEmpty()) {
                missing.add(phone);
            } else if (matched.size() > 1) {
                // 一号多粉无法确定收件人，不猜（docs §4.2）
                ambiguous.add(phone);
            }
        }
        if (!missing.isEmpty()) {
            errors.add("以下手机号无法解析到粉丝：" + maskPhones(missing));
        }
        if (!ambiguous.isEmpty()) {
            errors.add("以下手机号对应多个粉丝，无法确定收件人：" + maskPhones(ambiguous));
        }
        if (ambiguous.isEmpty() && missing.isEmpty()) {
            // 全部唯一命中时才计入收件人；有歧义就不发这批，避免部分送达
            collectSubscribed(fans, resolved, unsubscribed);
        }
    }

    private void resolveByNicknames(String appid, List<String> nicknames,
                                    Map<String, String> resolved, List<String> errors,
                                    List<String> unsubscribed) {
        List<WxUser> fans = wxUserService.list(new QueryWrapper<WxUser>()
                .eq("appid", appid)
                .in("nickname", nicknames));
        Map<String, List<WxUser>> byNickname = fans.stream()
                .filter(f -> StringUtils.hasText(f.getNickname()))
                .collect(Collectors.groupingBy(WxUser::getNickname));
        List<String> missing = new ArrayList<>();
        for (String nickname : nicknames) {
            if (!byNickname.containsKey(nickname)) {
                missing.add(nickname);
            }
        }
        if (!missing.isEmpty()) {
            errors.add("以下昵称查不到粉丝：" + String.join(", ", missing));
        }
        // 命中多个全部发送，collectSubscribed 里按 openid 去重
        collectSubscribed(fans, resolved, unsubscribed);
    }

    /**
     * tagid 在本地展开成 openid。JSON_CONTAINS 用不上索引（docs §4.4 性能提示），
     * 当前 NotifyServiceImpl.resolveRecipients 也是这个做法，粉丝量不大时可接受。
     */
    private void resolveByTagids(String appid, List<Integer> tagids,
                                 Map<String, String> resolved, List<String> errors) {
        List<WxUserTag> tags;
        try {
            tags = wxUserTagsService.getWxTags(appid);
        } catch (WxErrorException e) {
            log.warn("[通用推送] 拉取标签失败 appid={}", appid, e);
            throw new RRException("拉取标签失败：" + e.getError().getErrorMsg(), 400);
        }
        Set<Long> validIds = tags.stream().map(WxUserTag::getId).collect(Collectors.toSet());
        List<String> invalid = tagids.stream()
                .filter(id -> !validIds.contains(id.longValue()))
                .map(String::valueOf)
                .collect(Collectors.toList());
        if (!invalid.isEmpty()) {
            errors.add("以下 tagid 不存在：" + String.join(", ", invalid));
            return;
        }
        for (Integer tagid : tagids) {
            // 群体选择只展开 subscribe=1；已取关的不参与，也不触发整批失败
            List<WxUser> fans = wxUserService.list(new QueryWrapper<WxUser>()
                    .eq("appid", appid)
                    .eq("subscribe", true)
                    .apply("JSON_CONTAINS(tagid_list,{0})", String.valueOf(tagid)));
            for (WxUser fan : fans) {
                resolved.putIfAbsent(fan.getOpenid(), null);
            }
        }
    }

    /**
     * 把命中的已关注粉丝计入收件人；已取关的收进 unsubscribed，由调用方决定是否整批失败。
     */
    private void collectSubscribed(List<WxUser> fans, Map<String, String> resolved,
                                   List<String> unsubscribed) {
        for (WxUser fan : fans) {
            if (fan.isSubscribe()) {
                resolved.putIfAbsent(fan.getOpenid(), null);
            } else {
                unsubscribed.add(fan.getOpenid());
            }
        }
    }

    private TemplatePushResult send(WxAccount account, TemplatePushForm form,
                                    MsgTemplate template, List<String> openids,
                                    WxPushBatch batch) {
        String appid = account.getAppid();
        List<WxMpTemplateData> data = buildData(form.getData());
        WxMpTemplateMessage.MiniProgram miniProgram = buildMiniProgram(form.getMiniprogram());
        String batchId = batch.getBatchId();

        TemplatePushResult result = new TemplatePushResult();
        result.setBatchId(batchId);
        result.setRequested(batch.getRequestedCount());
        result.setResolved(openids.size());
        // duplicate = 请求数组元素总数 - 解析后 openid 数（昵称重名、多来源重复都会贡献）
        result.setDuplicate(Math.max(0, batch.getRequestedCount() - openids.size()));

        int success = 0;
        for (String openid : openids) {
            try {
                WxMpTemplateMessage msg = WxMpTemplateMessage.builder()
                        .toUser(openid)
                        .templateId(form.getTemplateId())
                        .url(form.getUrl())
                        .miniProgram(miniProgram)
                        .data(data)
                        .build();
                String sendResult = wxMpService.getTemplateMsgService().sendTemplateMsg(msg);
                success++;
                logTemplateMsg(msg, appid, sendResult, batchId);
            } catch (Exception e) {
                log.warn("[通用推送] 单条发送失败 appid={} openid={} batchId={}", appid, openid, batchId, e);
                if (result.getFailures().size() < MAX_FAILURES_IN_RESPONSE) {
                    result.getFailures().add(new TemplatePushResult.Failure(openid, brief(e.getMessage())));
                }
                logTemplateMsgFail(openid, form, appid, miniProgram, data, e.getMessage(), batchId);
            }
            sleepQuietly();
        }

        result.setSuccessCount(success);
        result.setFailCount(openids.size() - success);
        return result;
    }

    private List<WxMpTemplateData> buildData(List<TemplatePushForm.DataItem> items) {
        List<WxMpTemplateData> data = new ArrayList<>();
        for (TemplatePushForm.DataItem item : items) {
            if (!StringUtils.hasText(item.getName())) {
                continue;
            }
            data.add(new WxMpTemplateData(item.getName(), item.getValue(), item.getColor()));
        }
        return data;
    }

    private WxMpTemplateMessage.MiniProgram buildMiniProgram(TemplatePushForm.MiniProgram src) {
        if (src == null || !StringUtils.hasText(src.getAppId())) {
            return null;
        }
        WxMpTemplateMessage.MiniProgram mp = new WxMpTemplateMessage.MiniProgram();
        mp.setAppid(src.getAppId());
        mp.setPagePath(src.getPagePath());
        mp.setUsePath(StringUtils.hasText(src.getPagePath()));
        return mp;
    }

    private void logTemplateMsg(WxMpTemplateMessage msg, String appid, String sendResult, String batchId) {
        TemplateMsgLog logEntry = new TemplateMsgLog(msg, appid, sendResult);
        logEntry.setBatchId(batchId);
        logEntry.setSource(WxPushBatch.SOURCE_API);
        templateMsgLogMapper.insert(logEntry);
    }

    private void logTemplateMsgFail(String openid, TemplatePushForm form, String appid,
                                    WxMpTemplateMessage.MiniProgram miniProgram,
                                    List<WxMpTemplateData> data, String reason, String batchId) {
        WxMpTemplateMessage msg = WxMpTemplateMessage.builder()
                .toUser(openid)
                .templateId(form.getTemplateId())
                .url(form.getUrl())
                .miniProgram(miniProgram)
                .data(data)
                .build();
        TemplateMsgLog logEntry = new TemplateMsgLog(msg, appid, "失败：" + brief(reason));
        logEntry.setBatchId(batchId);
        logEntry.setSource(WxPushBatch.SOURCE_API);
        templateMsgLogMapper.insert(logEntry);
    }

    private WxPushBatch newBatch(WxAccount account, TemplatePushForm form,
                                 String clientIp, String userAgent) {
        WxPushBatch batch = new WxPushBatch();
        batch.setBatchId(generateBatchId());
        batch.setAppid(account.getAppid());
        batch.setClientMsgId(form.getClientMsgId());
        batch.setSendType(WxPushBatch.SEND_TYPE_TEMPLATE);
        batch.setTemplateId(form.getTemplateId());
        batch.setSource(WxPushBatch.SOURCE_API);
        batch.setStatus(WxPushBatch.STATUS_FAILED);
        batch.setRequestPayload(JSONObject.parseObject(JSON.toJSONString(form)));
        batch.setClientIp(clientIp);
        batch.setUserAgent(userAgent);
        batch.setRequestedCount(0);
        batch.setResolvedCount(0);
        batch.setSuccessCount(0);
        batch.setFailCount(0);
        return batch;
    }

    private String generateBatchId() {
        return "b_" + UUID.randomUUID().toString().replace("-", "");
    }

    private WxPushBatch findBatchByIdempotency(String appid, String clientMsgId) {
        return wxPushBatchMapper.selectOne(new QueryWrapper<WxPushBatch>()
                .eq("appid", appid)
                .eq("client_msg_id", clientMsgId)
                .last("LIMIT 1"));
    }

    private TemplatePushResult rebuildResult(WxPushBatch batch) {
        TemplatePushResult result = new TemplatePushResult();
        result.setBatchId(batch.getBatchId());
        result.setRequested(batch.getRequestedCount() == null ? 0 : batch.getRequestedCount());
        result.setResolved(batch.getResolvedCount() == null ? 0 : batch.getResolvedCount());
        result.setDuplicate(Math.max(0, result.getRequested() - result.getResolved()));
        result.setSuccessCount(batch.getSuccessCount() == null ? 0 : batch.getSuccessCount());
        result.setFailCount(batch.getFailCount() == null ? 0 : batch.getFailCount());
        return result;
    }

    private void finalizeBatch(WxPushBatch batch, TemplatePushResult result,
                               String successStatus, String partialStatus) {
        batch.setSuccessCount(result.getSuccessCount());
        batch.setFailCount(result.getFailCount());
        batch.setStatus(result.getFailCount() == 0 ? successStatus : partialStatus);
        if (result.getFailCount() > 0 && StringUtils.isEmpty(batch.getErrorMsg())) {
            batch.setErrorMsg("部分发送失败，详见逐条日志");
        }
    }

    private void safeInsert(WxPushBatch batch) {
        try {
            wxPushBatchMapper.insert(batch);
        } catch (Exception e) {
            log.warn("[通用推送] 批次落库失败 batchId={}", batch.getBatchId(), e);
        }
    }

    private void sleepQuietly() {
        if (SEND_INTERVAL_MILLIS <= 0) {
            return;
        }
        try {
            Thread.sleep(SEND_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String maskPhones(List<String> phones) {
        return phones.stream().map(this::maskPhone).collect(Collectors.joining(", "));
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String brief(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
