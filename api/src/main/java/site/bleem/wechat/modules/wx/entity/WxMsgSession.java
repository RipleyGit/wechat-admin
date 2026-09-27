package site.bleem.wechat.modules.wx.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 粉丝私信会话，每个 (appid, openid) 一行
 *
 * lastInTime 和 hasRealMsg 语义正交，不能合并，详见 docs/fan-messaging.md：
 * - lastInTime 含 event（菜单点击、扫码、关注都重置 48h 窗口），是能否发消息的唯一依据
 * - hasRealMsg 只在真实消息时置位，是会话列表的准入条件（否则列表等于粉丝列表）
 */
@Data
@TableName("wx_msg_session")
public class WxMsgSession implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;
    private String appid;
    private String openid;
    /**
     * 最后一次粉丝主动交互时间，含 event，48h 窗口依据
     */
    private Date lastInTime;
    /**
     * 最后一条可展示消息时间
     */
    private Date lastMsgTime;
    /**
     * 列表摘要
     */
    private String lastMsgSummary;
    /**
     * 是否有过真实消息（非 event）
     */
    private Boolean hasRealMsg;
    private Date createTime;
    private Date updateTime;
}
