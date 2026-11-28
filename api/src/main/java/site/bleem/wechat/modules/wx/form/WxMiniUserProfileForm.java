package site.bleem.wechat.modules.wx.form;

import lombok.Data;

/**
 * 小程序用户完善资料的入参，写入 wx_user。
 *
 * 必须带 code：接口无鉴权，openid 由服务端换取，防止冒用他人身份。
 * 应用可见范围属于后台授权（sys_client_user_auth），不在这里改。
 */
@Data
public class WxMiniUserProfileForm {
    /** 小程序 appid */
    private String miniAppid;

    /** wx.login 的临时凭证 */
    private String code;

    private String nickname;

    private String userPhone;

    /** 用户点了取消。只把 profile_filled 置 1，不改资料 */
    private Boolean cancelled;
}
