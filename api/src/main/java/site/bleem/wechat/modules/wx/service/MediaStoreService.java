package site.bleem.wechat.modules.wx.service;

import java.io.InputStream;

/**
 * 私信媒体转存
 *
 * 微信 mediaId 3 天过期，不能用于长期展示，所以入站媒体要转存到对象存储。
 * MinIO 未配置时所有方法返回 null，调用方降级（图片回退 picUrl，出站图片拒绝发送）。
 *
 * 语音尤其依赖这个：图片还有 picUrl 可以兜底，语音在微信侧没有任何长期可访问的地址，
 * mediaId 一过期原始音频就永久拿不回来了。
 */
public interface MediaStoreService {
    /**
     * 对象存储是否可用
     */
    boolean isAvailable();

    /**
     * 上传流到对象存储
     *
     * @param appid       所属公众号，用于对象路径分区
     * @param in          数据流，由本方法负责关闭
     * @param size        字节数，-1 表示未知（走分块上传）
     * @param contentType MIME，null 时用 application/octet-stream
     * @param ext         文件扩展名，不含点，可为 null
     * @return 公开访问地址，失败或未配置返回 null
     */
    String upload(String appid, InputStream in, long size, String contentType, String ext);

    /**
     * 把入站消息里的微信媒体转存到对象存储，并回填 wx_msg.detail.url
     *
     * 异步执行：回调有 5 秒超时，下载加上传远超这个时间。
     *
     * @param msgId     wx_msg 主键
     * @param appid     公众号
     * @param mediaId   微信媒体 id
     * @param preferExt 扩展名提示，不含点。下载接口的 Content-Type 不一定可靠，
     *                  语音这类已知格式直接用微信 XML 里的 format 更稳。传 null 时按下载结果推断。
     */
    void transferInboundMediaAsync(Long msgId, String appid, String mediaId, String preferExt);
}
