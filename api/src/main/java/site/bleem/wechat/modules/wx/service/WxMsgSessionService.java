package site.bleem.wechat.modules.wx.service;

import com.baomidou.mybatisplus.extension.service.IService;
import me.chanjar.weixin.common.api.WxConsts;
import site.bleem.wechat.modules.wx.dto.WxMsgSessionView;
import site.bleem.wechat.modules.wx.entity.WxMsg;
import site.bleem.wechat.modules.wx.entity.WxMsgSession;

import java.util.Arrays;
import java.util.List;

/**
 * 私信会话状态
 */
public interface WxMsgSessionService extends IService<WxMsgSession> {

    /**
     * 时间线上不展示的消息类型
     *
     * event：关注、菜单点击、扫码，不是对话内容
     * transfer_customer_service：转多客服的系统出站记录，每条未命中自动回复的消息都会产生一条
     *
     * 这个列表和 WxMsgSessionMapper.xml 里的 visibleMsg 必须保持一致。
     */
    List<String> HIDDEN_MSG_TYPES = Arrays.asList(
            WxConsts.XmlMsgType.EVENT,
            WxConsts.KefuMsgType.TRANSFER_CUSTOMER_SERVICE
    );

    /**
     * 消息落库后更新会话状态
     *
     * 入站消息（含 event）刷新 last_in_time——48h 窗口被任何交互重置，
     * 菜单点击和扫码也算。可展示的消息另外刷新摘要并置 has_real_msg。
     */
    void onMsgSaved(WxMsg msg);

    /**
     * 会话列表
     */
    List<WxMsgSessionView> listSessions(String appid, Long userId, String keyword, int offset, int limit);

    long countSessions(String appid, String keyword);

    /**
     * 有未读的会话数，全局红点用
     */
    int countUnreadSessions(String appid, Long userId);

    /**
     * 标记已读到指定消息 id
     */
    void markRead(String appid, String openid, Long userId, Long lastReadMsgId);

    /**
     * 粉丝最后一次交互时间，判断 48h 窗口用。无记录返回 null
     */
    WxMsgSession getSession(String appid, String openid);
}
