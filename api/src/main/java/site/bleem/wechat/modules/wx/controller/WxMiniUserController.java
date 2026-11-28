package site.bleem.wechat.modules.wx.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.form.WxMiniUserLoginForm;
import site.bleem.wechat.modules.wx.form.WxMiniUserProfileForm;
import site.bleem.wechat.modules.wx.service.MiniLinkService;
import site.bleem.wechat.modules.wx.service.WxMiniAuthService;

import java.util.HashMap;
import java.util.Map;

/**
 * 小程序用户（小程序端调用）
 *
 * ⚠ 无鉴权。ShiroConfig 里只有 /sys/** 与 /manage/** 走 oauth2，其余都是 anon。
 * 所以所有接口都必须用 code 换 openid，绝不接受调用方直传 openid —— 直传
 * 无法验证来源，任何人都能用别人的 openid 读写数据。
 *
 * 身份与资料基于 wx_user（服务号粉丝），应用可见性基于 sys_client_user_auth，
 * 见 {@link WxMiniAuthService} 与 docs/小程序用户应用授权重构方案.md。
 */
@RestController
@RequestMapping("/wxMiniUser")
@Api(tags = {"小程序用户-小程序端"})
@RequiredArgsConstructor
public class WxMiniUserController {

    private final WxMiniAuthService wxMiniAuthService;
    private final MiniLinkService miniLinkService;

    /**
     * 登录。
     *
     * 未关联服务号：返回 serviceLinked=false，前端拦去关联引导页，不建任何档。
     * 已关联：返回资料 + appCodes + defaultCode + needProfile。
     * 带 defaultCode 时（启动链接 ?defaultCode=）顺带自助开通并设为默认。
     */
    @PostMapping("/login")
    @ApiOperation(value = "小程序登录", notes = "只接受 code")
    public R login(@RequestBody WxMiniUserLoginForm form) {
        return R.ok().put(wxMiniAuthService.login(form.getMiniAppid(), form.getCode(), form.getDefaultCode()));
    }

    /**
     * 完善资料，写入 wx_user。cancelled=true 时只标记已弹过。必须带 code。
     */
    @PutMapping("/profile")
    @ApiOperation(value = "完善小程序用户资料")
    public R profile(@RequestBody WxMiniUserProfileForm form) {
        Map<String, Object> view = wxMiniAuthService.updateProfile(
                form.getMiniAppid(), form.getCode(), form.getNickname(),
                form.getUserPhone(), Boolean.TRUE.equals(form.getCancelled()));
        return R.ok().put(view);
    }

    /**
     * 签发子应用登录授权码（ES256 JWT）。
     * 门户进入子应用前调用，把 token 交给子应用登录接口。
     * 见 docs/子应用对接门户登录授权码指南.md。
     */
    @PostMapping("/clientToken")
    @ApiOperation(value = "签发子应用登录授权码", notes = "入参 miniAppid + code + clientCode")
    public R clientToken(@RequestBody WxMiniUserLoginForm form) {
        return R.ok().put(wxMiniAuthService.issueClientToken(
                form.getMiniAppid(), form.getCode(), form.getClientCode()));
    }

    /**
     * 签发关联服务号用的一次性 ticket。只认 code。已关联过直接返回 status=linked。
     */
    @PostMapping("/linkTicket")
    @ApiOperation(value = "签发关联服务号的 ticket", notes = "入参 miniAppid + code")
    public R linkTicket(@RequestBody WxMiniUserLoginForm form) {
        return R.ok().put(miniLinkService.issueTicket(form.getMiniAppid(), form.getCode()));
    }

    /** 查关联状态：pending / linked / need_follow / conflict / expired / error */
    @GetMapping("/linkStatus")
    @ApiOperation(value = "查询关联服务号的状态")
    public R linkStatus(@RequestParam String ticket) {
        Map<String, Object> data = new HashMap<>(2);
        data.put("status", miniLinkService.status(ticket));
        return R.ok().put(data);
    }

    /** 场景值为 link_<ticket> 的服务号临时二维码，返回 data URL */
    @GetMapping("/linkQrcode")
    @ApiOperation(value = "生成关联服务号的带参二维码")
    public R linkQrcode(@RequestParam String ticket) {
        Map<String, Object> data = new HashMap<>(2);
        data.put("base64", miniLinkService.qrcode(ticket));
        return R.ok().put(data);
    }
}
