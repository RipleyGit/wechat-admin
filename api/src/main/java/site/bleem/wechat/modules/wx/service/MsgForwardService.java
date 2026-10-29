package site.bleem.wechat.modules.wx.service;

import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import site.bleem.wechat.modules.wx.entity.MsgReplyRule;

import java.util.List;

/**
 * 自动回复「转发」规则：把命中的 SCAN / subscribe / unsubscribe 事件的微信原始 XML 转发到规则配置的地址。
 * 转发与本地的关注、扫码、取消关注处理并行执行，互不影响。
 */
public interface MsgForwardService {

    /**
     * 取命中该事件的转发规则。非 SCAN / subscribe / unsubscribe 事件，或没有原始 XML 时返回空列表。
     * 关注和扫码还需要场景值；取消关注不需要，只按事件类型匹配。
     */
    List<MsgReplyRule> matchRules(String appid, WxMpXmlMessage message);

    /**
     * 异步转发给每条命中的规则，调用方无需等待结果
     */
    void forward(String appid, WxMpXmlMessage message, List<MsgReplyRule> rules);
}
