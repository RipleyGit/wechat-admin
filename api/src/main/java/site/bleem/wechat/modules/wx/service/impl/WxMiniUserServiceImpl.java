package site.bleem.wechat.modules.wx.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import site.bleem.wechat.common.exception.RRException;
import site.bleem.wechat.common.utils.Query;
import site.bleem.wechat.modules.wx.dao.WxMiniUserMapper;
import site.bleem.wechat.modules.wx.entity.WxMiniUser;
import site.bleem.wechat.modules.wx.entity.WxUser;
import site.bleem.wechat.modules.wx.form.WxMiniUserLoginForm;
import site.bleem.wechat.modules.wx.form.WxMiniUserProfileForm;
import site.bleem.wechat.modules.wx.service.WxMiniUserService;
import site.bleem.wechat.modules.wx.service.WxUserService;

import java.util.List;
import java.util.Map;

@Service("wxMiniUserService")
public class WxMiniUserServiceImpl extends ServiceImpl<WxMiniUserMapper, WxMiniUser>
        implements WxMiniUserService {

    private static final Logger logger = LoggerFactory.getLogger(WxMiniUserServiceImpl.class);

    private static final String JSCODE2SESSION =
            "https://api.weixin.qq.com/sns/jscode2session"
                    + "?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code";

    private final WxUserService wxUserService;

    /**
     * 小程序的 AppSecret。只有配了才能用 code 换 openid。
     * 没配时登录接口要求调用方直传 miniOpenid。
     */
    @Value("${wx.mini.secret:}")
    private String miniSecret;

    /**
     * 是否允许调用方直传 miniOpenid 登录。
     *
     * 本接口无鉴权，直传 openid 等于任何人都能用别人的 openid 读写档案。
     * 配好 wx.mini.secret 之后应把这个开关关掉，只留 code 登录。
     */
    @Value("${wx.mini.allow-openid-login:true}")
    private boolean allowOpenidLogin;

    private final RestTemplate restTemplate = new RestTemplate();

    public WxMiniUserServiceImpl(WxUserService wxUserService) {
        this.wxUserService = wxUserService;
    }

    @Override
    public IPage<WxMiniUser> queryPage(Map<String, Object> params) {
        String miniAppid = (String) params.get("miniAppid");
        String miniOpenid = (String) params.get("miniOpenid");
        String serviceAppid = (String) params.get("serviceAppid");
        String nickname = (String) params.get("nickname");
        String userPhone = (String) params.get("userPhone");
        String defaultCode = (String) params.get("defaultCode");

        QueryWrapper<WxMiniUser> wrapper = new QueryWrapper<WxMiniUser>()
                .eq(StringUtils.hasText(miniAppid), "mini_appid", miniAppid)
                .eq(StringUtils.hasText(miniOpenid), "mini_openid", miniOpenid)
                .eq(StringUtils.hasText(serviceAppid), "service_appid", serviceAppid)
                .eq(StringUtils.hasText(defaultCode), "default_code", defaultCode)
                .like(StringUtils.hasText(nickname), "nickname", nickname)
                .like(StringUtils.hasText(userPhone), "user_phone", userPhone);

        return this.page(new Query<WxMiniUser>().getPage(params, "id", false), wrapper);
    }

    @Override
    public WxMiniUser getByMiniOpenid(String miniAppid, String miniOpenid) {
        return this.getOne(new QueryWrapper<WxMiniUser>()
                .eq("mini_appid", miniAppid)
                .eq("mini_openid", miniOpenid), false);
    }

    @Override
    public WxMiniUser login(WxMiniUserLoginForm form) {
        if (!StringUtils.hasText(form.getMiniAppid())) {
            throw new RRException("miniAppid 不能为空");
        }

        String miniOpenid = form.getMiniOpenid();
        String unionid = null;

        // code 优先：openid 由服务端换取，不可伪造，还能顺带拿到 unionid
        if (StringUtils.hasText(form.getCode())) {
            JSONObject session = code2Session(form.getMiniAppid(), form.getCode());
            miniOpenid = session.getString("openid");
            unionid = session.getString("unionid");
        }

        if (!StringUtils.hasText(form.getCode()) && !allowOpenidLogin) {
            throw new RRException("本接口已关闭 openid 直传，请改用 code 登录");
        }

        if (!StringUtils.hasText(miniOpenid)) {
            throw new RRException("miniOpenid 与 code 至少提供一个");
        }

        WxMiniUser user = getByMiniOpenid(form.getMiniAppid(), miniOpenid);

        if (user == null) {
            // 数据不存在：默认建档，由小程序弹资料表单让用户完善
            user = new WxMiniUser(form.getMiniAppid(), miniOpenid, form.getServiceAppid());
            user.setUnionid(unionid);
            linkServiceUser(user);
            this.save(user);
            logger.info("[小程序用户] 建档 appid={} openid={} unionid={}",
                    form.getMiniAppid(), miniOpenid, unionid);
            return user;
        }

        // 已有档案：补齐这次拿到的新信息，不覆盖用户填过的内容
        boolean dirty = false;
        if (StringUtils.hasText(unionid) && !unionid.equals(user.getUnionid())) {
            user.setUnionid(unionid);
            dirty = true;
        }
        if (StringUtils.hasText(form.getServiceAppid())
                && !form.getServiceAppid().equals(user.getServiceAppid())) {
            user.setServiceAppid(form.getServiceAppid());
            dirty = true;
        }
        if (!StringUtils.hasText(user.getServiceOpenid()) && linkServiceUser(user)) {
            dirty = true;
        }
        if (dirty) {
            this.updateById(user);
        }
        return user;
    }

    @Override
    public WxMiniUser updateProfile(WxMiniUserProfileForm form) {
        if (!StringUtils.hasText(form.getMiniAppid()) || !StringUtils.hasText(form.getMiniOpenid())) {
            throw new RRException("miniAppid 与 miniOpenid 不能为空");
        }

        WxMiniUser user = getByMiniOpenid(form.getMiniAppid(), form.getMiniOpenid());
        if (user == null) {
            throw new RRException("用户档案不存在，请先调用登录接口");
        }

        // 取消：只标记已弹过，不动其他字段
        if (Boolean.TRUE.equals(form.getCancelled())) {
            user.setProfileFilled(true);
            this.updateById(user);
            return user;
        }

        if (form.getNickname() != null) user.setNickname(form.getNickname());
        if (form.getUserPhone() != null) user.setUserPhone(form.getUserPhone());
        if (form.getRemark() != null) user.setRemark(form.getRemark());

        if (form.getAppCodes() != null) {
            user.setAppCodes(new JSONArray(new java.util.ArrayList<Object>(form.getAppCodes())));
        }

        // default_code 必须在 app_codes 内，否则直达会落到一个用户无权看的应用
        if (form.getDefaultCode() != null) {
            String code = form.getDefaultCode();
            if (!StringUtils.hasText(code)) {
                user.setDefaultCode(null);
            } else if (user.getAppCodes() != null && user.getAppCodes().contains(code)) {
                user.setDefaultCode(code);
            } else {
                throw new RRException("默认应用 " + code + " 不在该用户的应用范围内");
            }
        }

        user.setProfileFilled(true);
        this.updateById(user);

        // 用户刚填了手机号，可能正好能关联上服务号用户
        if (!StringUtils.hasText(user.getServiceOpenid()) && linkServiceUser(user)) {
            this.updateById(user);
        }
        return user;
    }

    @Override
    public boolean linkServiceUser(WxMiniUser user) {
        WxUser matched = null;

        // 优先 unionid：同一开放平台帐号下两边一致，是唯一可靠依据
        if (StringUtils.hasText(user.getUnionid())) {
            QueryWrapper<WxUser> w = new QueryWrapper<WxUser>().eq("unionid", user.getUnionid());
            if (StringUtils.hasText(user.getServiceAppid())) {
                w.eq("appid", user.getServiceAppid());
            }
            matched = wxUserService.getOne(w, false);
        }

        // 退路：手机号。会换号、也可能一号多人共用，所以只在 unionid 没命中时用
        if (matched == null && StringUtils.hasText(user.getUserPhone())) {
            QueryWrapper<WxUser> w = new QueryWrapper<WxUser>().eq("phone", user.getUserPhone());
            if (StringUtils.hasText(user.getServiceAppid())) {
                w.eq("appid", user.getServiceAppid());
            }
            List<WxUser> candidates = wxUserService.list(w);
            // 命中多条说明这个号对不上唯一的人，宁可不关联也不要关联错
            if (candidates != null && candidates.size() == 1) {
                matched = candidates.get(0);
            }
        }

        if (matched == null) return false;

        user.setServiceOpenid(matched.getOpenid());
        if (!StringUtils.hasText(user.getServiceAppid())) {
            user.setServiceAppid(matched.getAppid());
        }
        if (!StringUtils.hasText(user.getUnionid())) {
            user.setUnionid(matched.getUnionid());
        }
        logger.info("[小程序用户] 关联服务号 miniOpenid={} → serviceOpenid={}",
                user.getMiniOpenid(), matched.getOpenid());
        return true;
    }

    /**
     * code 换 openid / unionid。
     *
     * 本项目只引了 weixin-java-mp（公众号），没有小程序 SDK，所以直接调官方
     * HTTP 接口，不额外加依赖。
     */
    private JSONObject code2Session(String miniAppid, String code) {
        if (!StringUtils.hasText(miniSecret)) {
            throw new RRException("未配置 wx.mini.secret，无法用 code 换取 openid");
        }
        String body;
        try {
            body = restTemplate.getForObject(JSCODE2SESSION, String.class, miniAppid, miniSecret, code);
        } catch (Exception e) {
            logger.error("[小程序用户] jscode2session 调用失败", e);
            throw new RRException("微信登录失败，请稍后重试");
        }

        JSONObject json = JSONObject.parseObject(body);
        if (json == null) {
            throw new RRException("微信登录返回为空");
        }
        Integer errcode = json.getInteger("errcode");
        if (errcode != null && errcode != 0) {
            logger.warn("[小程序用户] jscode2session 返回错误: {}", body);
            throw new RRException("微信登录失败：" + json.getString("errmsg"));
        }
        return json;
    }
}
