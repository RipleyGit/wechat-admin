package site.bleem.wechat.modules.wx.service.impl;

import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import site.bleem.wechat.common.exception.RRException;
import site.bleem.wechat.config.TaskExcutor;
import site.bleem.wechat.modules.wx.entity.MsgReplyRule;
import site.bleem.wechat.modules.wx.handler.WxRawMessageHolder;
import site.bleem.wechat.modules.wx.service.MsgForwardService;
import site.bleem.wechat.modules.wx.service.MsgReplyRuleService;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class MsgForwardServiceImpl implements MsgForwardService {
    private static final String QR_SCENE_PREFIX = "qrscene_";
    private static final int TIMEOUT_MILLIS = 3000;

    private final MsgReplyRuleService msgReplyRuleService;
    private final RestTemplate restTemplate;

    public MsgForwardServiceImpl(MsgReplyRuleService msgReplyRuleService) {
        this.msgReplyRuleService = msgReplyRuleService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MILLIS);
        factory.setReadTimeout(TIMEOUT_MILLIS);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public List<MsgReplyRule> matchRules(String appid, WxMpXmlMessage message) {
        if (!WxConsts.EventType.SUBSCRIBE.equals(message.getEvent()) && !WxConsts.EventType.SCAN.equals(message.getEvent())) {
            return Collections.emptyList();
        }
        if (WxRawMessageHolder.get() == null) {
            log.warn("转发规则未生效：当前为加密消息，无法获取原始 XML，openid={}", message.getFromUser());
            return Collections.emptyList();
        }
        String scene = extractScene(message);
        if (!StringUtils.hasText(scene)) {
            return Collections.emptyList();
        }
        return msgReplyRuleService.getMatchedForwardRules(appid, message.getEvent(), scene);
    }

    @Override
    public void forward(String appid, WxMpXmlMessage message, List<MsgReplyRule> rules) {
        String rawXml = WxRawMessageHolder.get();
        if (rawXml == null) {
            return;
        }
        byte[] body = rawXml.getBytes(StandardCharsets.UTF_8);
        for (MsgReplyRule rule : rules) {
            String target = rule.getReplyContent().trim();
            TaskExcutor.submit(() -> post(rule.getRuleId(), target, body, message));
        }
    }

    private void post(Long ruleId, String target, byte[] body, WxMpXmlMessage message) {
        try {
            validateTargetUrl(target);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType("text", "xml", StandardCharsets.UTF_8));
            ResponseEntity<String> response = restTemplate.postForEntity(target, new HttpEntity<>(body, headers), String.class);
            log.info("转发完成：ruleId={}, openid={}, EventKey={}, target={}, status={}",
                ruleId, message.getFromUser(), message.getEventKey(), target, response.getStatusCodeValue());
        } catch (RestClientException | RRException e) {
            log.error("转发失败：ruleId={}, openid={}, EventKey={}, target={}, 原因={}",
                ruleId, message.getFromUser(), message.getEventKey(), target, e.getMessage());
        }
    }

    /**
     * 校验转发地址：只允许 https，且解析出的 IP 不得为回环、内网、链路本地、组播地址
     */
    public static void validateTargetUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new RRException("转发地址格式错误");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new RRException("转发地址必须是 https 地址");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new RRException("转发地址的域名无法解析");
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new RRException("转发地址不能指向内网或本机地址");
            }
        }
    }

    private static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
            || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 唯一本地地址 fc00::/7
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static String extractScene(WxMpXmlMessage message) {
        String eventKey = message.getEventKey();
        if (!StringUtils.hasText(eventKey)) {
            return null;
        }
        if (eventKey.startsWith(QR_SCENE_PREFIX)) {
            return eventKey.substring(QR_SCENE_PREFIX.length());
        }
        return eventKey;
    }
}
