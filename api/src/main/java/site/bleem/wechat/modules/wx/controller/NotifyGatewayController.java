package site.bleem.wechat.modules.wx.controller;

import com.alibaba.fastjson.JSONObject;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.entity.NotifyChannel;
import site.bleem.wechat.modules.wx.entity.NotifyLog;
import site.bleem.wechat.modules.wx.service.NotifyService;

/**
 * 通用消息推送网关
 *
 * 外部机器把事件 POST 进来，通道配置决定发给谁、发什么。
 * 这个路径故意不在 /manage/** 下面，不走登录拦截，鉴权靠通道自己的 secret。
 */
@RestController
@RequestMapping("/notify")
@RequiredArgsConstructor
@Slf4j
@Api(tags = {"消息推送网关"})
public class NotifyGatewayController {

    private final NotifyService notifyService;

    @PostMapping("/{code}")
    @ApiOperation(value = "按通道推送消息")
    public R notify(@PathVariable String code,
                    @RequestHeader(value = "X-Notify-Secret", required = false) String secret,
                    @RequestBody(required = false) JSONObject payload) {
        NotifyChannel channel = notifyService.getByCode(code);
        // 通道不存在时也回 401，不告诉调用方哪些 code 存在
        if (channel == null || !StringUtils.hasText(channel.getSecret()) || !secretMatches(channel.getSecret(), secret)) {
            log.warn("推送网关鉴权失败，code={}", code);
            return R.error(401, "invalid notify secret");
        }
        if (!Boolean.TRUE.equals(channel.getEnabled())) {
            log.info("推送通道[{}]已停用，跳过", code);
            return R.ok().put("successCount", 0).put("skipped", true);
        }

        NotifyLog result = notifyService.dispatch(channel,
                payload == null ? new JSONObject() : payload, NotifyLog.SOURCE_WEBHOOK);
        return R.ok()
                .put("recipientCount", result.getRecipientCount())
                .put("successCount", result.getSuccessCount())
                .put("failCount", result.getFailCount())
                .put("errorMsg", result.getErrorMsg());
    }

    /**
     * 定长比较，不让比较耗时泄漏密钥前缀
     */
    private boolean secretMatches(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }
}
