package site.bleem.wechat.modules.wx.entity;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 消息推送通道
 *
 * 一个通道对应一个事件，自带收件人和文案。外部机器 POST /wx/notify/{code} 触发，
 * 页面上也能手动发同一个通道。
 */
@Data
@TableName("wx_notify_channel")
public class NotifyChannel implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String SEND_TYPE_KEFU = "kefu";
    public static final String SEND_TYPE_TEMPLATE = "template";
    public static final String RECIPIENT_TYPE_TAG = "tag";
    public static final String RECIPIENT_TYPE_OPENID = "openid";
    public static final String CONTENT_MODE_RENDER = "render";
    public static final String CONTENT_MODE_PASSTHROUGH = "passthrough";

    @TableId
    private Long id;
    private String appid;
    /**
     * 通道标识，网关地址用它
     */
    private String code;
    @TableField("`name`")
    private String name;
    /**
     * 网关鉴权密钥，为空表示不开放网关，只能页面手动发
     */
    private String secret;
    private Boolean enabled;
    /**
     * kefu / template
     */
    private String sendType;
    /**
     * tag / openid
     */
    private String recipientType;
    /**
     * tag 时是标签名，openid 时是逗号分隔的 openid
     */
    private String recipientValue;
    /**
     * render 用 contentTemplate 渲染 / passthrough 直接用调用方传的 content
     */
    private String contentMode;
    /**
     * 客服消息文案，{变量名} 占位
     */
    private String contentTemplate;
    private String templateId;
    /**
     * 模板消息字段映射，[{"name":"keyword1","value":"{branch}"}]
     */
    private JSONArray templateData;
    private String templateUrl;
    /**
     * 最近一次网关请求体，页面预览时当样例用
     */
    private JSONObject lastPayload;
    private String remark;
    private Date createTime;
    private Date updateTime;
}
