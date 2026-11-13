package site.bleem.wechat.modules.wx.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.entity.WxAccount;
import site.bleem.wechat.modules.wx.form.TemplatePushForm;
import site.bleem.wechat.modules.wx.form.TemplatePushResult;
import site.bleem.wechat.modules.wx.service.TemplatePushService;
import site.bleem.wechat.modules.wx.service.WxAccountService;

import javax.servlet.http.HttpServletRequest;

/**
 * 通用模板消息推送网关。
 *
 * 与 {@link NotifyGatewayController} 的区别：这里是**调用方驱动**——模板 ID、消息内容、
 * 收件人都由请求决定，适合业务系统按事件实时推送；那里是**通道驱动**，收件人和文案预置在通道里。
 * 两者复用同一个 notify_secret 鉴权，路径都在 /wx/** 下（Shiro 匿名），不走登录态。
 *
 * 详细设计见 docs/模板消息推送设计方案.md
 */
@RestController
@RequestMapping("/push")
@RequiredArgsConstructor
@Slf4j
@Api(tags = {"通用消息推送"})
public class PushGatewayController {

    private final TemplatePushService templatePushService;
    private final WxAccountService wxAccountService;

    @PostMapping("/template")
    @ApiOperation(value = "模板消息推送")
    public R pushTemplate(@RequestHeader(value = "X-Notify-Secret", required = false) String secret,
                          @RequestBody TemplatePushForm form,
                          HttpServletRequest request) {
        // 复用通道网关同一套密钥：按密钥查库本身就是比对，WxAccountService 已做长度校验
        WxAccount account = wxAccountService.getByNotifySecret(secret);
        if (account == null) {
            log.warn("[通用推送] 鉴权失败");
            return R.error(401, "invalid notify secret");
        }

        TemplatePushResult result = templatePushService.push(
                account, form, clientIp(request), request.getHeader("User-Agent"));
        return R.ok().put(result);
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(ip)) {
            int comma = ip.indexOf(',');
            return comma > 0 ? ip.substring(0, comma).trim() : ip.trim();
        }
        ip = request.getHeader("X-Real-IP");
        return ip != null ? ip : request.getRemoteAddr();
    }
}
