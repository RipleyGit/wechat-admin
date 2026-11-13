package site.bleem.wechat.modules.wx.service;

import site.bleem.wechat.modules.wx.entity.WxAccount;
import site.bleem.wechat.modules.wx.form.TemplatePushForm;
import site.bleem.wechat.modules.wx.form.TemplatePushResult;

/**
 * 通用模板消息推送。调用方驱动：模板 ID、消息内容、收件人全由请求决定，
 * 与通道驱动的 {@code /wx/notify/{code}} 并存。
 */
public interface TemplatePushService {

    /**
     * @param account  鉴权已确定的公众号
     * @param form     请求体
     * @param clientIp 调用方 IP，审计用
     * @param userAgent 调用方 UA，审计用
     * @return 推送结果
     */
    TemplatePushResult push(WxAccount account, TemplatePushForm form, String clientIp, String userAgent);
}
