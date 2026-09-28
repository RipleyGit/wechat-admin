package site.bleem.wechat.modules.wx.handler;

import site.bleem.wechat.modules.wx.service.MsgReplyService;
import me.chanjar.weixin.common.session.WxSessionManager;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import me.chanjar.weixin.mp.util.WxMpConfigStorageHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * @author Binary Wang
 */
@Component
public class ScanHandler extends AbstractHandler {
    @Autowired
    MsgReplyService msgReplyService;

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage wxMpXmlMessage, Map<String, Object> map,
                                    WxMpService wxMpService, WxSessionManager wxSessionManager) {
        //扫码事件处理
        // SCAN 事件的 EventKey 本来就是裸场景值，这里走 extractQrScene 只是和 SubscribeHandler 保持一致
        String scene = extractQrScene(wxMpXmlMessage.getEventKey());
        this.logger.info("用户扫描带参二维码 OPENID: " + wxMpXmlMessage.getFromUser() + "，场景值：" + scene);
        String appid = WxMpConfigStorageHolder.get();
        msgReplyService.tryAutoReply(appid, true, wxMpXmlMessage.getFromUser(), scene);

        return null;
    }
}
