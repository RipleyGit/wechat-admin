package site.bleem.wechat.modules.wx.service;

import com.alibaba.fastjson.JSONObject;
import site.bleem.wechat.modules.wx.entity.WxUser;

import java.util.Map;

/**
 * 小程序用户登录与身份，基于 wx_user（服务号粉丝）+ sys_client_user_auth（应用授权）。
 *
 * 替代原来的 wx_mini_user：身份和资料以 wx_user 为准（靠 wx_user.mini_openid
 * 定位），能用哪些应用由 sys_client_user_auth 决定。登录不再建档。
 *
 * 方案见 portal-wechat-app/docs/小程序用户应用授权重构方案.md。
 */
public interface WxMiniAuthService {

    /** code 换小程序 openid（jscode2session）。openid 由服务端换取，不可伪造 */
    String code2Openid(String miniAppid, String code);

    /** 按小程序 (appid, openid) 找已关联的粉丝，没关联返回 null */
    WxUser findLinkedFan(String miniAppid, String miniOpenid);

    /**
     * 小程序登录。
     *
     * @return 给小程序的视图（见 docs）。未关联服务号时只含 serviceLinked=false
     *         和 mini 身份；已关联则带资料、appCodes、defaultCode、needProfile
     */
    Map<String, Object> login(String miniAppid, String code, String defaultCode);

    /**
     * 完善资料，写入 wx_user。cancelled=true 时只把 profile_filled 置 1。
     * 必须带 code：接口无鉴权，靠服务端换 openid 防止冒用他人身份。
     *
     * @return 同 login 的视图
     */
    Map<String, Object> updateProfile(String miniAppid, String code, String nickname,
                                      String userPhone, boolean cancelled);

    /**
     * 签发子应用登录授权码（ES256 JWT）。
     * 先确认该用户在 sys_client_user_auth 里有 clientCode 且 status=1，再签发。
     *
     * @return { token, expiresIn }
     */
    Map<String, Object> issueClientToken(String miniAppid, String code, String clientCode);
}
