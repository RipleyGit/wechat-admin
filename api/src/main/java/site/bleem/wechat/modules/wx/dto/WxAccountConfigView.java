package site.bleem.wechat.modules.wx.dto;

import lombok.Data;
import site.bleem.wechat.modules.wx.entity.WxAccount;

@Data
public class WxAccountConfigView {
    private String appid;
    private String name;
    private int type;
    private boolean verified;
    private boolean secretConfigured;
    private boolean tokenConfigured;
    private boolean aesKeyConfigured;
    private boolean notifySecretConfigured;
    /**
     * 小程序 appid 不是密钥，直接回显，页面要把它填回输入框
     */
    private String miniAppid;
    /**
     * 小程序 AppSecret 只回「配过没有」，不回明文 —— 和 secret / token / aesKey 一致。
     * 这个视图也给公众号列表用，回明文等于所有能看列表的人都拿到了密钥
     */
    private boolean miniSecretConfigured;

    public static WxAccountConfigView from(WxAccount account) {
        WxAccountConfigView view = new WxAccountConfigView();
        view.setAppid(account.getAppid());
        view.setName(account.getName());
        view.setType(account.getType());
        view.setVerified(account.isVerified());
        view.setSecretConfigured(account.getSecret() != null && !account.getSecret().trim().isEmpty());
        view.setTokenConfigured(account.getToken() != null && !account.getToken().trim().isEmpty());
        view.setAesKeyConfigured(account.getAesKey() != null && !account.getAesKey().trim().isEmpty());
        view.setNotifySecretConfigured(account.getNotifySecret() != null && !account.getNotifySecret().isEmpty());
        view.setMiniAppid(account.getMiniAppid());
        view.setMiniSecretConfigured(account.getMiniSecret() != null && !account.getMiniSecret().trim().isEmpty());
        return view;
    }
}
