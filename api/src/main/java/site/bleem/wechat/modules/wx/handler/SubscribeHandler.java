package site.bleem.wechat.modules.wx.handler;

import java.util.Map;

import me.chanjar.weixin.mp.util.WxMpConfigStorageHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import site.bleem.wechat.modules.wx.service.WxUserService;
import site.bleem.wechat.modules.wx.service.MsgReplyService;
import site.bleem.wechat.modules.wx.service.ScanSessionService;
import site.bleem.wechat.modules.wx.service.MiniLinkService;
import me.chanjar.weixin.common.session.WxSessionManager;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import org.springframework.util.StringUtils;

/**
 * @author Binary Wang
 */
@Component
public class SubscribeHandler extends AbstractHandler {
    @Autowired
    MsgReplyService msgReplyService;
    @Autowired
    WxUserService userService;
    @Autowired
    ScanSessionService scanSessionService;
    @Autowired
    MiniLinkService miniLinkService;

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage wxMessage, Map<String, Object> context, WxMpService wxMpService,
                                    WxSessionManager sessionManager) {

        this.logger.info("新关注用户 OPENID: " + wxMessage.getFromUser() + "，事件：" + wxMessage.getEventKey());
        String appid = WxMpConfigStorageHolder.get();
        this.logger.info("appid:{}",appid);
        userService.refreshUserInfo(wxMessage.getFromUser(),appid);

        msgReplyService.tryAutoReply(appid, true, wxMessage.getFromUser(), wxMessage.getEvent());

        if (StringUtils.hasText(wxMessage.getEventKey())) {// 处理特殊事件，如用户扫描带参二维码关注
            // 扫码关注的 EventKey 带 qrscene_ 前缀，剥掉后才能和 SCAN 事件用同一条规则
            String scene = extractQrScene(wxMessage.getEventKey());
            this.logger.info("扫码关注，场景值：{}", scene);
            // 扫码识别用户：未关注的人扫码会先落到关注页，关注完成后才走到这里
            scanSessionService.record(appid, scene, wxMessage.getFromUser());
            // 小程序用户关联服务号：场景值 link_<ticket>，其他场景值内部忽略
            linkScene(appid, scene, wxMessage.getFromUser());
            msgReplyService.tryAutoReply(appid, true, wxMessage.getFromUser(), scene);
        }
        return null;
    }

    private void linkScene(String appid, String scene, String openid) {
        try {
            miniLinkService.onScene(appid, scene, openid);
        } catch (Exception e) {
            this.logger.error("小程序关联处理失败，场景值：{}", scene, e);
        }
    }

    /**
     * 处理特殊请求，比如如果是扫码进来的，可以做相应处理
     */
    protected WxMpXmlOutMessage handleSpecial(WxMpXmlMessage wxMessage) {
        this.logger.info("特殊请求-新关注用户 OPENID: " + wxMessage.getFromUser());
        //对关注事件和扫码事件分别处理
        String appid = WxMpConfigStorageHolder.get();
        userService.refreshUserInfo(wxMessage.getFromUser(),appid);
        msgReplyService.tryAutoReply(appid, true, wxMessage.getFromUser(), wxMessage.getEvent());
        if (StringUtils.hasText(wxMessage.getEventKey())) {
            msgReplyService.tryAutoReply(appid, true, wxMessage.getFromUser(), extractQrScene(wxMessage.getEventKey()));
        }
        return null;
    }

}
