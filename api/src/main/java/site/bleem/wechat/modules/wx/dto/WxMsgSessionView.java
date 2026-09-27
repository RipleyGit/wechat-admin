package site.bleem.wechat.modules.wx.dto;

import lombok.Data;

import java.util.Date;

/**
 * 会话列表的一行：会话状态 + 粉丝资料 + 当前运营的未读数
 *
 * 昵称头像来自 wx_user 的 left join。粉丝可能在 wx_user 里没记录（历史消息、取关后清理），
 * 所以这两个字段允许为空，前端要兜住。
 */
@Data
public class WxMsgSessionView {
    private String openid;
    private String nickname;
    private String headimgurl;
    private String remark;

    /** 最后一条可展示消息的时间，列表排序依据 */
    private Date lastMsgTime;
    private String lastMsgSummary;

    /** 最后一次入站交互，含 event。48h 窗口算这个 */
    private Date lastInTime;

    private int unreadCount;
}
