package site.bleem.wechat.modules.wx.form;

import lombok.Data;

/**
 * 小程序登录 / 关联 / 签发授权码的入参。
 *
 * 一律只用 code：wx.login 的临时凭证，服务端换取 openid，调用方伪造不了。
 * 本接口无鉴权，不接受直传 openid。
 */
@Data
public class WxMiniUserLoginForm {
    /** 小程序 appid */
    private String miniAppid;

    /** wx.login 的临时凭证 */
    private String code;

    /**
     * 可选。启动链接带 {@code ?defaultCode=app_xxx} 时上报，登录时自助开通该应用
     * 并设为默认。前端已校验 code 属于本小程序已挂载的应用。
     */
    private String defaultCode;

    /** 签发子应用授权码（/clientToken）时的目标应用 code */
    private String clientCode;
}
