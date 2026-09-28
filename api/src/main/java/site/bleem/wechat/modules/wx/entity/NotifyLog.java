package site.bleem.wechat.modules.wx.entity;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 消息推送记录
 *
 * payload 是调用方传进来的变量，content 是渲染后真正发出去的内容。
 * 两份都留是为了排查「文案不对」时能分清是调用方传错了还是模板写错了。
 */
@Data
@TableName("wx_notify_log")
public class NotifyLog implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String SOURCE_WEBHOOK = "webhook";
    public static final String SOURCE_MANUAL = "manual";

    @TableId
    private Long id;
    private String appid;
    private Long channelId;
    /**
     * 冗余一份，通道删了记录还能看
     */
    private String channelCode;
    /**
     * webhook / manual
     */
    private String source;
    private JSONObject payload;
    private String content;
    private Integer recipientCount;
    private Integer successCount;
    private Integer failCount;
    private String errorMsg;
    private Date createTime;
}
