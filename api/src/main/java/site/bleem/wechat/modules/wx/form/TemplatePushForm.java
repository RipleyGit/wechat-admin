package site.bleem.wechat.modules.wx.form;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用模板消息推送请求体。POST /wx/push/template
 *
 * 四种收件人至少填一个，填多个取并集去重；任一目标在系统里不存在则整批失败。
 * 字段语义见 docs/模板消息推送设计方案.md §2.2
 */
@Data
public class TemplatePushForm {

    /** 公众号模板 ID */
    private String templateId;

    /**
     * 模板字段，name 须覆盖模板里的全部 {{xxx.DATA}}。
     * color 可选，微信官方格式。
     */
    private List<DataItem> data;

    /** 点击跳转 H5；与 miniprogram 同时存在时新版本微信优先打开小程序 */
    private String url;

    /** 跳转小程序。字段名用驼峰 appId / pagePath：Spring 用 Jackson 反序列化，
     * 不认 weixin-java-mp 上 MiniProgram 的 Gson @SerializedName，写成 appid/pagepath
     * 会被当未知字段静默丢弃（和菜单按钮同一个坑） */
    private MiniProgram miniprogram;

    private PushTarget targets;

    /** 调用方幂等键，重复请求返回首次结果 */
    private String clientMsgId;

    /** 默认 template；预留 kefu */
    private String sendType;

    @Data
    public static class DataItem {
        private String name;
        private String value;
        private String color;
    }

    /**
     * 跳转小程序信息。字段名用驼峰，和菜单按钮一致，避免 Jackson 丢弃。
     * 映射到 WxMpTemplateMessage.MiniProgram 的 appid / pagePath / usePath。
     */
    @Data
    public static class MiniProgram {
        private String appId;
        private String pagePath;
    }
}
