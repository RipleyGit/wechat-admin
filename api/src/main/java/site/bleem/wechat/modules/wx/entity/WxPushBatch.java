package site.bleem.wechat.modules.wx.entity;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import site.bleem.wechat.common.utils.Json;

import java.io.Serializable;
import java.util.Date;

/**
 * 通用推送批次。一次 /wx/push/template 调用一条，审计用。
 *
 * 字段约定见 docs/模板消息推送设计方案.md §6.1。request_payload 含手机号等 PII，
 * 只进数据库，接口不对外暴露查询。
 */
@Data
@TableName("wx_push_batch")
public class WxPushBatch implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String batchId;
    private String appid;
    private String clientMsgId;
    private String sendType;
    private String templateId;
    private String source;
    private String status;
    private JSONObject requestPayload;
    private Integer requestedCount;
    private Integer resolvedCount;
    private Integer successCount;
    private Integer failCount;
    private String errorMsg;
    private String clientIp;
    private String userAgent;
    private Date createTime;
    private Date updateTime;

    public static final String SOURCE_API = "api";
    public static final String SOURCE_MANUAL = "manual";

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_REJECTED = "rejected";

    public static final String SEND_TYPE_TEMPLATE = "template";
    public static final String SEND_TYPE_KEFU = "kefu";

    @Override
    public String toString() {
        return Json.toJsonString(this);
    }
}
