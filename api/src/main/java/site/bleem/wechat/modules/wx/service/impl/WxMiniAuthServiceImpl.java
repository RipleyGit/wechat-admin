package site.bleem.wechat.modules.wx.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import site.bleem.wechat.common.exception.RRException;
import site.bleem.wechat.modules.wx.entity.SysClientUserAuth;
import site.bleem.wechat.modules.wx.entity.WxAccount;
import site.bleem.wechat.modules.wx.entity.WxUser;
import site.bleem.wechat.modules.wx.service.ClientTokenSigner;
import site.bleem.wechat.modules.wx.service.SysClientUserAuthService;
import site.bleem.wechat.modules.wx.service.WxAccountService;
import site.bleem.wechat.modules.wx.service.WxMiniAuthService;
import site.bleem.wechat.modules.wx.service.WxUserService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 见 {@link WxMiniAuthService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxMiniAuthServiceImpl implements WxMiniAuthService {

    private static final String JSCODE2SESSION =
            "https://api.weixin.qq.com/sns/jscode2session"
                    + "?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code";

    private final WxUserService wxUserService;
    private final WxAccountService wxAccountService;
    private final SysClientUserAuthService authService;
    private final ClientTokenSigner tokenSigner;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String code2Openid(String miniAppid, String code) {
        if (!StringUtils.hasText(miniAppid) || !StringUtils.hasText(code)) {
            throw new RRException("miniAppid 与 code 不能为空");
        }
        WxAccount account = wxAccountService.getByMiniAppid(miniAppid);
        String miniSecret = account == null ? null : account.getMiniSecret();
        if (!StringUtils.hasText(miniSecret)) {
            throw new RRException("小程序 " + miniAppid
                    + " 未配置 AppSecret，请在运营台「选择工作公众号 → 配置」里填写");
        }
        String body;
        try {
            body = restTemplate.getForObject(JSCODE2SESSION, String.class, miniAppid, miniSecret, code);
        } catch (Exception e) {
            log.error("[小程序登录] jscode2session 调用失败", e);
            throw new RRException("微信登录失败，请稍后重试");
        }
        JSONObject json = JSONObject.parseObject(body);
        if (json == null) {
            throw new RRException("微信登录返回为空");
        }
        Integer errcode = json.getInteger("errcode");
        if (errcode != null && errcode != 0) {
            log.warn("[小程序登录] jscode2session 返回错误: {}", body);
            throw new RRException("微信登录失败：" + json.getString("errmsg"));
        }
        String openid = json.getString("openid");
        if (!StringUtils.hasText(openid)) {
            throw new RRException("微信登录失败：未取得 openid");
        }
        return openid;
    }

    @Override
    public WxUser findLinkedFan(String miniAppid, String miniOpenid) {
        if (!StringUtils.hasText(miniAppid) || !StringUtils.hasText(miniOpenid)) return null;
        return wxUserService.getOne(new QueryWrapper<WxUser>()
                .eq("mini_appid", miniAppid)
                .eq("mini_openid", miniOpenid), false);
    }

    @Override
    public Map<String, Object> login(String miniAppid, String code, String defaultCode) {
        String miniOpenid = code2Openid(miniAppid, code);
        WxUser fan = findLinkedFan(miniAppid, miniOpenid);

        // 未关联服务号：不建档，让前端去引导页
        if (fan == null) {
            return notLinkedView(miniAppid, miniOpenid);
        }

        // 自助开通：仅已关联用户、仅 code 登录生效。前端已校验 code 属于本小程序挂载的应用
        if (StringUtils.hasText(defaultCode)) {
            try {
                authService.claimDefault(miniOpenid, defaultCode);
            } catch (Exception e) {
                log.warn("[小程序登录] 自助开通失败 openid={} code={}", miniOpenid, defaultCode, e);
            }
        }

        authService.touchLastLogin(miniOpenid);
        return linkedView(miniAppid, miniOpenid, fan);
    }

    @Override
    public Map<String, Object> updateProfile(String miniAppid, String code, String nickname,
                                              String userPhone, boolean cancelled) {
        String miniOpenid = code2Openid(miniAppid, code);
        WxUser fan = findLinkedFan(miniAppid, miniOpenid);
        if (fan == null) {
            // 没关联就没有 wx_user 可写。按未关联处理，前端会拦去引导页
            return notLinkedView(miniAppid, miniOpenid);
        }

        UpdateWrapper<WxUser> update = new UpdateWrapper<WxUser>().eq("openid", fan.getOpenid());
        if (!cancelled) {
            if (nickname != null) update.set("nickname", nickname);
            if (userPhone != null) update.set("phone", userPhone);
        }
        update.set("profile_filled", true);
        wxUserService.update(update);

        WxUser fresh = wxUserService.getById(fan.getOpenid());
        return linkedView(miniAppid, miniOpenid, fresh != null ? fresh : fan);
    }

    @Override
    public Map<String, Object> issueClientToken(String miniAppid, String code, String clientCode) {
        if (!StringUtils.hasText(clientCode)) {
            throw new RRException("clientCode 不能为空");
        }
        String miniOpenid = code2Openid(miniAppid, code);
        WxUser fan = findLinkedFan(miniAppid, miniOpenid);
        if (fan == null) {
            throw new RRException("尚未关联服务号，无法进入应用", 403);
        }
        if (!authService.hasAccess(miniOpenid, clientCode)) {
            throw new RRException("未开通该应用，请联系管理员", 403);
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        if (StringUtils.hasText(fan.getNickname())) extra.put("nickname", fan.getNickname());
        if (StringUtils.hasText(fan.getPhone())) extra.put("phone", fan.getPhone());
        if (StringUtils.hasText(fan.getHeadimgurl())) extra.put("avatar", fan.getHeadimgurl());

        return tokenSigner.issue(clientCode, miniOpenid, miniAppid, extra);
    }

    /** 未关联视图：只告诉前端去关联，不下发其他信息 */
    private Map<String, Object> notLinkedView(String miniAppid, String miniOpenid) {
        Map<String, Object> view = new LinkedHashMap<>(4);
        view.put("serviceLinked", false);
        view.put("miniAppid", miniAppid);
        view.put("miniOpenid", miniOpenid);
        view.put("appCodes", new ArrayList<String>());
        return view;
    }

    /** 已关联视图：资料来自 wx_user，应用来自 sys_client_user_auth */
    private Map<String, Object> linkedView(String miniAppid, String miniOpenid, WxUser fan) {
        List<SysClientUserAuth> auths = authService.listEnabled(miniOpenid);
        List<String> appCodes = new ArrayList<>();
        String defaultCode = "";
        for (SysClientUserAuth a : auths) {
            if (StringUtils.hasText(a.getClientCode())) {
                appCodes.add(a.getClientCode());
                if (Boolean.TRUE.equals(a.getIsDefault())) {
                    defaultCode = a.getClientCode();
                }
            }
        }
        // 默认应用必须仍在可见范围内
        if (!appCodes.contains(defaultCode)) {
            defaultCode = "";
        }

        Map<String, Object> view = new LinkedHashMap<>(10);
        view.put("serviceLinked", true);
        view.put("miniAppid", miniAppid);
        view.put("miniOpenid", miniOpenid);
        view.put("nickname", fan.getNickname());
        view.put("headimgurl", fan.getHeadimgurl());
        view.put("userPhone", fan.getPhone());
        view.put("appCodes", appCodes);
        view.put("defaultCode", defaultCode);
        view.put("needProfile", !Boolean.TRUE.equals(fan.getProfileFilled()));
        return view;
    }
}
