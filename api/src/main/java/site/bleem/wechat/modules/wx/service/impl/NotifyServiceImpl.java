package site.bleem.wechat.modules.wx.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.kefu.WxMpKefuMessage;
import me.chanjar.weixin.mp.bean.tag.WxUserTag;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateData;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import site.bleem.wechat.common.utils.PageUtils;
import site.bleem.wechat.common.utils.Query;
import site.bleem.wechat.modules.wx.dao.NotifyChannelMapper;
import site.bleem.wechat.modules.wx.dao.NotifyLogMapper;
import site.bleem.wechat.modules.wx.entity.NotifyChannel;
import site.bleem.wechat.modules.wx.entity.NotifyLog;
import site.bleem.wechat.modules.wx.entity.WxUser;
import site.bleem.wechat.modules.wx.service.NotifyService;
import site.bleem.wechat.modules.wx.service.WxUserService;
import site.bleem.wechat.modules.wx.service.WxUserTagsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service("notifyService")
@Slf4j
public class NotifyServiceImpl extends ServiceImpl<NotifyChannelMapper, NotifyChannel> implements NotifyService {

    /**
     * 占位符 {变量名} 或 {变量名?}，变量名只认字母数字下划线，避免把文案里的中文大括号也当占位符。
     * 带 ? 的是可选变量，为空时整行丢掉，见 render
     */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{([A-Za-z0-9_]+)(\\?)?}");
    /**
     * 客服消息文本上限是 2048 字节，这里按字符留足余量，超了微信会整条拒收
     */
    private static final int MAX_CONTENT_LENGTH = 600;

    @Autowired
    private NotifyLogMapper notifyLogMapper;
    @Autowired
    private WxMpService wxMpService;
    @Autowired
    private WxUserService wxUserService;
    @Autowired
    private WxUserTagsService wxUserTagsService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        String appid = (String) params.get("appid");
        String code = (String) params.get("code");
        String name = (String) params.get("name");
        IPage<NotifyChannel> page = this.page(
                new Query<NotifyChannel>().getPage(params),
                new QueryWrapper<NotifyChannel>()
                        .eq(StringUtils.hasText(appid), "appid", appid)
                        .like(StringUtils.hasText(code), "code", code)
                        .like(StringUtils.hasText(name), "`name`", name)
                        .orderByDesc("id")
        );
        return new PageUtils(page);
    }

    @Override
    public NotifyChannel getByCode(String appid, String code) {
        if (!StringUtils.hasText(appid) || !StringUtils.hasText(code)) {
            return null;
        }
        return this.getOne(new QueryWrapper<NotifyChannel>()
                .eq("appid", appid)
                .eq("code", code)
                .last("LIMIT 1"));
    }

    @Override
    public NotifyLog dispatch(NotifyChannel channel, JSONObject vars, String source) {
        NotifyLog notifyLog = new NotifyLog();
        notifyLog.setAppid(channel.getAppid());
        notifyLog.setChannelId(channel.getId());
        notifyLog.setChannelCode(channel.getCode());
        notifyLog.setSource(source);
        notifyLog.setPayload(vars);
        notifyLog.setRecipientCount(0);
        notifyLog.setSuccessCount(0);
        notifyLog.setFailCount(0);

        try {
            // 网关线程没有 ThreadLocal 里的 appid，必须自己切
            wxMpService.switchoverTo(channel.getAppid());

            String content = resolveContent(channel, vars);
            notifyLog.setContent(content);

            List<String> openids = resolveRecipients(channel);
            notifyLog.setRecipientCount(openids.size());
            if (openids.isEmpty()) {
                notifyLog.setErrorMsg("没有匹配到收件人");
                log.warn("推送通道[{}]没有匹配到收件人", channel.getCode());
            } else if (NotifyChannel.SEND_TYPE_TEMPLATE.equals(channel.getSendType())) {
                sendTemplate(channel, vars, openids, notifyLog);
            } else {
                sendKefu(content, openids, notifyLog);
            }
        } catch (Exception e) {
            log.error("推送通道[{}]执行失败", channel.getCode(), e);
            notifyLog.setErrorMsg(brief(e.getMessage()));
        }

        notifyLogMapper.insert(notifyLog);
        rememberPayload(channel, vars, source);
        return notifyLog;
    }

    /**
     * passthrough 直接用调用方给的 content，render 用通道里的文案渲染
     */
    private String resolveContent(NotifyChannel channel, JSONObject vars) {
        if (NotifyChannel.CONTENT_MODE_PASSTHROUGH.equals(channel.getContentMode())) {
            String content = vars == null ? null : vars.getString("content");
            if (!StringUtils.hasText(content)) {
                throw new IllegalArgumentException("透传模式下请求体必须带 content");
            }
            return truncate(content);
        }
        return truncate(render(channel.getContentTemplate(), vars));
    }

    private List<String> resolveRecipients(NotifyChannel channel) throws Exception {
        String value = channel.getRecipientValue();
        if (!StringUtils.hasText(value)) {
            return new ArrayList<>();
        }
        if (NotifyChannel.RECIPIENT_TYPE_OPENID.equals(channel.getRecipientType())) {
            return java.util.Arrays.stream(value.split("[,，\\s]+"))
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
        }

        Long tagId = findTagIdByName(channel.getAppid(), value.trim());
        if (tagId == null) {
            throw new IllegalStateException("未找到标签[" + value + "]");
        }
        List<WxUser> fans = wxUserService.list(new QueryWrapper<WxUser>()
                .eq("appid", channel.getAppid())
                .eq("subscribe", true)
                .apply("JSON_CONTAINS(tagid_list,{0})", String.valueOf(tagId)));
        return fans.stream().map(WxUser::getOpenid).collect(Collectors.toList());
    }

    private Long findTagIdByName(String appid, String tagName) throws Exception {
        List<WxUserTag> tags = wxUserTagsService.getWxTags(appid);
        for (WxUserTag tag : tags) {
            if (tagName.equals(tag.getName())) {
                return tag.getId();
            }
        }
        return null;
    }

    /**
     * 逐个发。客服消息受 48 小时互动窗口限制，超窗的粉丝会单独失败，
     * 不能因为一个人失败就中断其余的人
     */
    private void sendKefu(String content, List<String> openids, NotifyLog notifyLog) {
        int success = 0;
        String firstError = null;
        for (String openid : openids) {
            try {
                wxMpService.getKefuService().sendKefuMessage(
                        WxMpKefuMessage.TEXT().toUser(openid).content(content).build());
                success++;
            } catch (Exception e) {
                log.error("推送失败，openid={}", openid, e);
                if (firstError == null) {
                    firstError = brief(e.getMessage());
                }
            }
        }
        notifyLog.setSuccessCount(success);
        notifyLog.setFailCount(openids.size() - success);
        notifyLog.setErrorMsg(firstError);
    }

    /**
     * 模板消息。骨架先搭着，等模板在微信后台申请下来再补
     */
    private void sendTemplate(NotifyChannel channel, JSONObject vars, List<String> openids, NotifyLog notifyLog) {
        if (!StringUtils.hasText(channel.getTemplateId())) {
            throw new IllegalStateException("模板消息未配置 templateId");
        }
        List<WxMpTemplateData> data = buildTemplateData(channel, vars);
        int success = 0;
        String firstError = null;
        for (String openid : openids) {
            try {
                WxMpTemplateMessage msg = WxMpTemplateMessage.builder()
                        .toUser(openid)
                        .templateId(channel.getTemplateId())
                        .url(channel.getTemplateUrl())
                        .data(data)
                        .build();
                wxMpService.getTemplateMsgService().sendTemplateMsg(msg);
                success++;
            } catch (Exception e) {
                log.error("模板消息推送失败，openid={}", openid, e);
                if (firstError == null) {
                    firstError = brief(e.getMessage());
                }
            }
        }
        notifyLog.setSuccessCount(success);
        notifyLog.setFailCount(openids.size() - success);
        notifyLog.setErrorMsg(firstError);
    }

    /**
     * templateData 里 value 同样支持 {变量名}，走一遍渲染
     */
    private List<WxMpTemplateData> buildTemplateData(NotifyChannel channel, JSONObject vars) {
        List<WxMpTemplateData> data = new ArrayList<>();
        if (channel.getTemplateData() == null) {
            return data;
        }
        for (int i = 0; i < channel.getTemplateData().size(); i++) {
            JSONObject item = channel.getTemplateData().getJSONObject(i);
            if (item == null || !StringUtils.hasText(item.getString("name"))) {
                continue;
            }
            data.add(new WxMpTemplateData(item.getString("name"),
                    render(item.getString("value"), vars), item.getString("color")));
        }
        return data;
    }

    @Override
    public String render(String template, JSONObject vars) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        // 文案里存的是字面 \n，页面上按换行编辑，统一先还原成真换行
        String normalized = template.replace("\\n", "\n");
        List<String> kept = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            if (hasEmptyOptionalVar(line, vars)) {
                continue;
            }
            kept.add(renderLine(line, vars));
        }
        return String.join("\n", kept);
    }

    /**
     * 行里有 {变量?} 且取不到值，整行丢掉
     *
     * 部署失败的「失败步骤：{failed_step?}」不一定有值，不丢行就会留下一个光秃秃的
     * 「失败步骤：」。只认显式标了 ? 的变量：不标的一律当必填，空就渲染成空字符串，
     * 免得「✅ {commit_short} 部署成功」这种带正文的行被误伤。
     */
    private boolean hasEmptyOptionalVar(String line, JSONObject vars) {
        Matcher matcher = VAR_PATTERN.matcher(line);
        while (matcher.find()) {
            if (matcher.group(2) != null && !StringUtils.hasText(valueOf(vars, matcher.group(1)))) {
                return true;
            }
        }
        return false;
    }

    private String renderLine(String line, JSONObject vars) {
        Matcher matcher = VAR_PATTERN.matcher(line);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String value = valueOf(vars, matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String valueOf(JSONObject vars, String key) {
        if (vars == null) {
            return null;
        }
        Object value = vars.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 记下最近一次网关请求体，页面预览时当样例用，省得手编假数据。
     * 只更新这一个字段，别把整个通道回写回去覆盖掉页面上的改动
     */
    private void rememberPayload(NotifyChannel channel, JSONObject vars, String source) {
        if (!NotifyLog.SOURCE_WEBHOOK.equals(source) || vars == null || vars.isEmpty()) {
            return;
        }
        try {
            NotifyChannel patch = new NotifyChannel();
            patch.setId(channel.getId());
            patch.setLastPayload(vars);
            this.updateById(patch);
        } catch (Exception e) {
            log.warn("记录推送样例失败，channel={}", channel.getCode(), e);
        }
    }

    @Override
    public PageUtils queryLogPage(Map<String, Object> params) {
        String appid = (String) params.get("appid");
        String channelCode = (String) params.get("channelCode");
        IPage<NotifyLog> page = notifyLogMapper.selectPage(
                new Query<NotifyLog>().getPage(params),
                new QueryWrapper<NotifyLog>()
                        .eq(StringUtils.hasText(appid), "appid", appid)
                        .eq(StringUtils.hasText(channelCode), "channel_code", channelCode)
                        .orderByDesc("id")
        );
        return new PageUtils(page);
    }

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > MAX_CONTENT_LENGTH ? content.substring(0, MAX_CONTENT_LENGTH) + "…" : content;
    }

    private String brief(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
