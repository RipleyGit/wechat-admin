package site.bleem.wechat.modules.wx.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.entity.WxMiniUser;
import site.bleem.wechat.modules.wx.form.WxMiniUserLoginForm;
import site.bleem.wechat.modules.wx.form.WxMiniUserProfileForm;
import site.bleem.wechat.modules.wx.service.WxMiniUserService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 小程序用户（小程序端调用）
 *
 * ⚠ 无鉴权。ShiroConfig 里只有 /sys/** 与 /manage/** 走 oauth2，其余都是 anon，
 * 本控制器和既有的 /wxAuth 一样是公开的。因此：
 *  - 用 code 登录时 openid 由服务端换取，调用方伪造不了
 *  - 直传 miniOpenid 时无法验证来源，任何人都能用别人的 openid 读写档案
 * 生产环境建议只走 code，并把 miniOpenid 直传关掉（见 wx.mini.allow-openid-login）。
 */
@RestController
@RequestMapping("/wxMiniUser")
@Api(tags = {"小程序用户-小程序端"})
public class WxMiniUserController {

    @Autowired
    private WxMiniUserService wxMiniUserService;

    /**
     * 登录：查档案，不存在则建档。
     *
     * 返回里的 needProfile 表示小程序该不该弹资料表单，
     * directEntry 表示该不该直达某个子应用。
     */
    @PostMapping("/login")
    @ApiOperation(value = "小程序登录", notes = "miniOpenid 与 code 二选一，code 优先")
    public R login(@RequestBody WxMiniUserLoginForm form) {
        WxMiniUser user = wxMiniUserService.login(form);
        return R.ok().put(toView(user));
    }

    /**
     * 完善资料。cancelled=true 时只标记已弹过，不改其他字段。
     */
    @PutMapping("/profile")
    @ApiOperation(value = "完善小程序用户资料")
    public R profile(@RequestBody WxMiniUserProfileForm form) {
        WxMiniUser user = wxMiniUserService.updateProfile(form);
        return R.ok().put(toView(user));
    }

    /**
     * 读档案。小程序冷启动后想单独刷一次用。
     */
    @GetMapping("/info")
    @ApiOperation(value = "读取小程序用户资料")
    public R info(@RequestParam String miniAppid, @RequestParam String miniOpenid) {
        WxMiniUser user = wxMiniUserService.getByMiniOpenid(miniAppid, miniOpenid);
        if (user == null) {
            return R.error("用户档案不存在");
        }
        return R.ok().put(toView(user));
    }

    /**
     * 组装给小程序的视图。
     *
     * 刻意不直接回实体：serviceOpenid / unionid / remark 是后台侧信息，
     * 没必要下发到客户端。
     */
    private Map<String, Object> toView(WxMiniUser user) {
        boolean enabled = !Boolean.FALSE.equals(user.getEnabled());

        List<String> appCodes = new ArrayList<>();
        if (enabled && user.getAppCodes() != null) {
            for (int i = 0; i < user.getAppCodes().size(); i++) {
                String code = user.getAppCodes().getString(i);
                if (StringUtils.hasText(code)) appCodes.add(code);
            }
        }

        // 默认应用必须仍在可见范围内，否则会直达到一个用户看不到的应用
        String defaultCode = user.getDefaultCode();
        if (!StringUtils.hasText(defaultCode) || !appCodes.contains(defaultCode)) {
            defaultCode = "";
        }

        Map<String, Object> view = new HashMap<>(10);
        view.put("miniAppid", user.getMiniAppid());
        view.put("miniOpenid", user.getMiniOpenid());
        view.put("serviceAppid", user.getServiceAppid());
        view.put("nickname", user.getNickname());
        view.put("userPhone", user.getUserPhone());
        view.put("defaultCode", defaultCode);
        view.put("appCodes", appCodes);
        view.put("enabled", enabled);
        /** 资料没填过就让小程序弹表单，用户取消也算填过 */
        view.put("needProfile", !Boolean.TRUE.equals(user.getProfileFilled()));
        /** 是否已关联上服务号用户，便于前端提示「去关注服务号」 */
        view.put("serviceLinked", StringUtils.hasText(user.getServiceOpenid()));
        return view;
    }
}
