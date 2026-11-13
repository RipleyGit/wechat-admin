package site.bleem.wechat.modules.wx.form;

import lombok.Data;

/**
 * 小程序登录入参
 *
 * miniOpenid 与 code 二选一：
 *  - code：小程序 wx.login 拿到的临时凭证，服务端换取 openid 与 unionid（推荐，
 *    openid 不可伪造，还能顺便拿到 unionid 以自动关联服务号用户）。
 *    需要配置 wx.mini.secret。
 *  - miniOpenid：调用方已经持有 openid 时直接传。注意本接口无鉴权，
 *    直传 openid 意味着任何人都能用别人的 openid 读写档案。
 */
@Data
public class WxMiniUserLoginForm {
    /** 小程序 appid */
    private String miniAppid;

    /** 关联的服务号 appid */
    private String serviceAppid;

    /** 小程序用户 openid，与 code 二选一 */
    private String miniOpenid;

    /** wx.login 的临时凭证，与 miniOpenid 二选一，优先使用 */
    private String code;

    /**
     * 可选。启动链接带 {@code ?defaultCode=app_xxx} 时上报，登录时把它设为默认应用
     * 并去重并入 appCodes。
     *
     * 走 code 登录时才有意义：openid 由服务端换取，调用方伪造不了。前端会先确认
     * 这个 code 是本小程序已挂载的应用，后端不认识应用清单，只负责去重落库。
     */
    private String defaultCode;
}
