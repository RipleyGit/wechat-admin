package site.bleem.wechat.modules.wx.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.api.WxConsts;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import site.bleem.wechat.modules.wx.dao.WxMsgReadMapper;
import site.bleem.wechat.modules.wx.dao.WxMsgSessionMapper;
import site.bleem.wechat.modules.wx.dto.WxMsgSessionView;
import site.bleem.wechat.modules.wx.entity.WxMsg;
import site.bleem.wechat.modules.wx.entity.WxMsgSession;
import site.bleem.wechat.modules.wx.service.WxMsgSessionService;

import java.util.List;

@Service("wxMsgSessionService")
@RequiredArgsConstructor
@Slf4j
public class WxMsgSessionServiceImpl extends ServiceImpl<WxMsgSessionMapper, WxMsgSession>
        implements WxMsgSessionService {

    /** 摘要列是 varchar(120)，留点余量 */
    private static final int SUMMARY_MAX_LENGTH = 100;

    private final WxMsgReadMapper wxMsgReadMapper;

    @Override
    public void onMsgSaved(WxMsg msg) {
        if (msg == null || !StringUtils.hasText(msg.getAppid()) || !StringUtils.hasText(msg.getOpenid())) {
            return;
        }
        try {
            // 入站交互重置 48h 窗口，event 也算
            if (msg.getInOut() == WxMsg.WxMsgInOut.IN) {
                this.baseMapper.upsertLastInTime(msg.getAppid(), msg.getOpenid(), msg.getCreateTime());
            }
            // 噪音类型不进会话列表：只有 event 的粉丝不该出现，
            // transfer_customer_service 也不该覆盖粉丝真实消息的摘要
            if (!HIDDEN_MSG_TYPES.contains(msg.getMsgType())) {
                this.baseMapper.upsertLastMsg(msg.getAppid(), msg.getOpenid(),
                        msg.getCreateTime(), buildSummary(msg));
            }
        } catch (Exception e) {
            // 会话状态是派生数据，更新失败不能影响消息本身入库
            log.error("更新私信会话状态失败, appid={}, openid={}", msg.getAppid(), msg.getOpenid(), e);
        }
    }

    @Override
    public List<WxMsgSessionView> listSessions(String appid, Long userId, String keyword, int offset, int limit) {
        return this.baseMapper.selectSessionViews(appid, userId, keyword, offset, limit);
    }

    @Override
    public long countSessions(String appid, String keyword) {
        return this.baseMapper.countSessions(appid, keyword);
    }

    @Override
    public int countUnreadSessions(String appid, Long userId) {
        return this.baseMapper.countUnreadSessions(appid, userId);
    }

    @Override
    public void markRead(String appid, String openid, Long userId, Long lastReadMsgId) {
        wxMsgReadMapper.upsertLastReadMsgId(appid, openid, userId, lastReadMsgId);
    }

    @Override
    public WxMsgSession getSession(String appid, String openid) {
        return this.getOne(new QueryWrapper<WxMsgSession>()
                .eq("appid", appid)
                .eq("openid", openid), false);
    }

    /**
     * 列表里那一行灰字。非文本消息给个类型占位符
     */
    private String buildSummary(WxMsg msg) {
        String msgType = msg.getMsgType();
        JSONObject detail = msg.getDetail();
        String text = null;
        if (WxConsts.XmlMsgType.TEXT.equals(msgType)) {
            text = detail == null ? null : detail.getString("content");
        } else if (WxConsts.XmlMsgType.IMAGE.equals(msgType)) {
            text = "[图片]";
        } else if (WxConsts.XmlMsgType.VOICE.equals(msgType)) {
            // 开了语音识别就直接显示文字，否则只能给占位符
            String recognition = detail == null ? null : detail.getString("recognition");
            text = StringUtils.hasText(recognition) ? "[语音] " + recognition : "[语音]";
        } else if (WxConsts.XmlMsgType.VIDEO.equals(msgType) || WxConsts.XmlMsgType.SHORTVIDEO.equals(msgType)) {
            text = "[视频]";
        } else if (WxConsts.XmlMsgType.LOCATION.equals(msgType)) {
            String label = detail == null ? null : detail.getString("label");
            text = StringUtils.hasText(label) ? "[位置] " + label : "[位置]";
        } else if (WxConsts.XmlMsgType.LINK.equals(msgType)) {
            String title = detail == null ? null : detail.getString("title");
            text = StringUtils.hasText(title) ? "[链接] " + title : "[链接]";
        } else if (WxConsts.KefuMsgType.NEWS.equals(msgType) || WxConsts.KefuMsgType.MPNEWS.equals(msgType)) {
            text = "[图文]";
        } else if (WxConsts.KefuMsgType.MUSIC.equals(msgType)) {
            text = "[音乐]";
        } else if (WxConsts.KefuMsgType.WXCARD.equals(msgType)) {
            text = "[卡券]";
        } else if (WxConsts.KefuMsgType.MINIPROGRAMPAGE.equals(msgType)) {
            text = "[小程序]";
        } else if (WxConsts.KefuMsgType.MSGMENU.equals(msgType)) {
            text = "[菜单]";
        }
        if (!StringUtils.hasText(text)) {
            text = "[" + msgType + "]";
        }
        // 换行在列表里会撑高行高
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > SUMMARY_MAX_LENGTH ? text.substring(0, SUMMARY_MAX_LENGTH) : text;
    }
}
