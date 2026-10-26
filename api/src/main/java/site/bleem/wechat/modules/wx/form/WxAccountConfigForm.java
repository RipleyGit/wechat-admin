package site.bleem.wechat.modules.wx.form;

import lombok.Data;

@Data
public class WxAccountConfigForm {
    private String appid;
    private String name;
    private Integer type;
    private Boolean verified;
    private String secret;
    private String token;
    private String aesKey;
    private Boolean clearAesKey;
    /**
     * 关联的门户小程序 appid。留空表示保持现有值不变，要解除关联用 clearMini
     */
    private String miniAppid;
    /**
     * 门户小程序的 AppSecret。留空表示保持现有值不变（页面上永远不回显明文）
     */
    private String miniSecret;
    /**
     * 解除小程序关联，miniAppid 与 miniSecret 一起清空。
     * 和 clearAesKey 同样的套路：空串在"不改"和"清空"之间有歧义，用一个显式开关区分
     */
    private Boolean clearMini;
}
