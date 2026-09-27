package site.bleem.wechat.modules.wx.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 私信已读位点，每个 (appid, openid, 运营账号) 一行
 *
 * 已读状态按运营账号各自独立：A 看过不代表 B 看过。
 * 只存位点不逐条标记，未读数 = 该会话中 id > lastReadMsgId 的入站真实消息条数。
 */
@Data
@TableName("wx_msg_read")
public class WxMsgRead implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;
    private String appid;
    private String openid;
    /**
     * sys_user.user_id
     */
    private Long userId;
    private Long lastReadMsgId;
    private Date updateTime;
}
