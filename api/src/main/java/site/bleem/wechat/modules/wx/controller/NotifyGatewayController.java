package site.bleem.wechat.modules.wx.controller;

import com.alibaba.fastjson.JSONObject;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.entity.NotifyChannel;
import site.bleem.wechat.modules.wx.entity.NotifyLog;
import site.bleem.wechat.modules.wx.entity.WxAccount;
import site.bleem.wechat.modules.wx.service.NotifyService;
import site.bleem.wechat.modules.wx.service.WxAccountService;

/**
 * 通用消息推送网关
 *
 * 外部机器把事件 POST 进来，通道配置决定发给谁、发什么。
 * 这个路径故意不在 /manage/** 下面，不走登录拦截。
 *
 * 鉴权和定位分两步：先用请求头里的推送密钥找到公众号（找不到就是 401），
 * 再在这个公众号下按 code 找通道。密钥是公众号级的，通道自己的 gateway_enabled
 * 决定它能不能被网关触发。
 */
@RestController
@RequestMapping("/notify")
@RequiredArgsConstructor
@Slf4j
@Api(tags = {"消息推送网关"})
public class NotifyGatewayController {

    private final NotifyService notifyService;
    private final WxAccountService wxAccountService;

    @PostMapping("/{code}")
    @ApiOperation(value = "按通道推送消息")
    public R notify(@PathVariable String code,
                    @RequestHeader(value = "X-Notify-Secret", required = false) String secret,
                    @RequestBody(required = false) JSONObject payload) {
        // 按密钥查库本身就是比对，不存在逐字节比较的耗时差
        WxAccount account = wxAccountService.getByNotifySecret(secret);
        if (account == null) {
            log.warn("推送网关鉴权失败，code={}", code);
            return R.error(401, "invalid notify secret");
        }

        // 走到这里调用方已经证明自己持有这个公众号的密钥，可以明确告诉它是哪里没配好
        NotifyChannel channel = notifyService.getByCode(account.getAppid(), code);
        if (channel == null) {
            log.warn("推送网关找不到通道，appid={}, code={}", account.getAppid(), code);
            return R.error(404, "channel not found: " + code);
        }
        if (!Boolean.TRUE.equals(channel.getGatewayEnabled())) {
            log.warn("推送通道[{}]未允许接口调用，appid={}", code, account.getAppid());
            return R.error(403, "channel not open to gateway: " + code);
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
}
