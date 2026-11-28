package site.bleem.wechat.modules.wx.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.bleem.wechat.modules.wx.service.MiniLinkService;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 小程序 web-view 里打开的服务号网页授权页。
 *
 * 不另外部署 H5：start 直接 302 到微信授权，callback 处理完返回一段很小的 HTML，
 * 用 wx.miniProgram.navigateBack 回到小程序引导页，引导页再查 ticket 状态。
 *
 * 前提：wechat.bleem.site 同时配成服务号「网页授权域名」和小程序「业务域名」。
 * 路径在 /wx/** 下，Shiro 匿名。
 */
@Slf4j
@RestController
@RequestMapping("/miniLink")
@RequiredArgsConstructor
@Api(tags = {"小程序关联服务号-网页授权"})
public class MiniLinkController {

    /** ticket 是去掉横线的 UUID，校验格式后才拼进 URL 和 HTML */
    private static final Pattern TICKET = Pattern.compile("^[0-9a-f]{32}$");

    private final MiniLinkService miniLinkService;

    @GetMapping("/start")
    @ApiOperation(value = "跳转服务号网页授权（snsapi_base）")
    public void start(@RequestParam String ticket, HttpServletResponse response) throws IOException {
        if (!TICKET.matcher(ticket).matches()) {
            writeHtml(response, MiniLinkService.STATUS_EXPIRED, null);
            return;
        }
        String url;
        try {
            url = miniLinkService.buildAuthorizeUrl(ticket);
        } catch (Exception e) {
            // switchoverTo 找不到公众号配置时抛运行时异常，别让 web-view 里出现 500 堆栈页
            log.error("[小程序关联] 生成网页授权地址失败 ticket={}", ticket, e);
            writeHtml(response, MiniLinkService.STATUS_ERROR, ticket);
            return;
        }
        if (url == null) {
            writeHtml(response, MiniLinkService.STATUS_EXPIRED, null);
            return;
        }
        response.sendRedirect(url);
    }

    @GetMapping("/callback")
    @ApiOperation(value = "网页授权回调")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         HttpServletResponse response) throws IOException {
        if (state == null || !TICKET.matcher(state).matches()) {
            writeHtml(response, MiniLinkService.STATUS_EXPIRED, null);
            return;
        }
        String status;
        try {
            status = miniLinkService.handleOauthCallback(code, state);
        } catch (Exception e) {
            log.error("[小程序关联] 网页授权回调处理失败 ticket={}", state, e);
            status = MiniLinkService.STATUS_ERROR;
        }
        writeHtml(response, status, state);
    }

    /**
     * 结果页。error 给「重试」（重新走一次授权，code 只能用一次）；
     * 其余状态都自动回小程序，由引导页按 linkStatus 决定显示什么。
     */
    private void writeHtml(HttpServletResponse response, String status, String ticket) throws IOException {
        boolean error = MiniLinkService.STATUS_ERROR.equals(status);
        String message;
        switch (status) {
            case MiniLinkService.STATUS_LINKED: message = "关联成功，正在返回…"; break;
            case MiniLinkService.STATUS_NEED_FOLLOW: message = "还没有关注服务号，正在返回…"; break;
            case MiniLinkService.STATUS_CONFLICT: message = "该微信号已关联其他用户，正在返回…"; break;
            case MiniLinkService.STATUS_EXPIRED: message = "关联凭证已过期，正在返回…"; break;
            default: message = "授权失败，请重试"; break;
        }
        // ticket 已经过正则校验，只含 [0-9a-f]，可以直接拼进 HTML
        String retry = error && ticket != null
            ? "<button onclick=\"location.replace('start?ticket=" + ticket + "')\">重试</button>"
            : "";

        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>关联服务号</title>"
            + "<style>body{margin:0;display:flex;flex-direction:column;align-items:center;"
            + "justify-content:center;height:100vh;font:15px -apple-system,sans-serif;color:#3f3f46;"
            + "background:#f8f9fa}button{margin-top:16px;padding:8px 28px;font-size:15px;"
            + "border:1px solid #d4d4d8;border-radius:6px;background:#fff}</style>"
            + "<script src=\"https://res.wx.qq.com/open/js/jweixin-1.6.0.js\"></script>"
            + "</head><body><p>" + message + "</p>" + retry
            + "<button onclick=\"back()\">返回小程序</button>"
            + "<script>function back(){if(window.wx&&wx.miniProgram){wx.miniProgram.navigateBack()}}"
            + (error ? "" : "setTimeout(back,600);")
            + "</script></body></html>";

        response.setContentType(MediaType.TEXT_HTML_VALUE + ";charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(html);
    }
}
