package site.bleem.wechat.modules.wx.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 运营手动给粉丝发私信
 *
 * 和 MsgReplyService 分开：那个是规则驱动的自动回复，异常全部吞掉；
 * 这个是人在界面上点发送，失败必须报错给人看，不能假装成功。
 */
public interface FanMessageService {

    /** 微信客服消息的 48 小时交互窗口 */
    long INTERACT_WINDOW_MILLIS = 48L * 60 * 60 * 1000;

    /**
     * 距 48h 窗口关闭还剩多少毫秒，已关闭或无交互记录返回 0
     */
    long remainingWindowMillis(String appid, String openid);

    /**
     * 发文字
     */
    void sendText(String appid, String openid, String content);

    /**
     * 发图片
     *
     * 同时做两件事：传临时素材拿 mediaId 用于发送，传 MinIO 拿长期 URL 用于气泡展示。
     * mediaId 三天过期，只存它的话出站气泡三天后就空了。
     */
    void sendImage(String appid, String openid, MultipartFile file);
}
