package site.bleem.wechat.modules.wx.handler;

import me.chanjar.weixin.common.session.WxSessionManager;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import me.chanjar.weixin.mp.util.WxMpConfigStorageHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import site.bleem.wechat.modules.wx.entity.MsgReplyRule;
import site.bleem.wechat.modules.wx.service.MsgForwardService;

import java.util.List;
import java.util.Map;

/**
 * 命中转发规则的事件在此转发，不再执行本地的关注 / 扫码处理
 */
@Component
public class ForwardHandler extends AbstractHandler {
    @Autowired
    MsgForwardService msgForwardService;

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage message, Map<String, Object> context,
                                    WxMpService wxMpService, WxSessionManager sessionManager) {
        String appid = WxMpConfigStorageHolder.get();
        List<MsgReplyRule> rules = msgForwardService.matchRules(appid, message);
        msgForwardService.forward(appid, message, rules);
        return null;
    }
}
