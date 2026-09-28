package site.bleem.wechat.modules.wx.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import site.bleem.wechat.modules.wx.config.MinioProperties;
import site.bleem.wechat.modules.wx.dao.WxMsgMapper;
import site.bleem.wechat.modules.wx.entity.WxMsg;
import site.bleem.wechat.modules.wx.service.MediaStoreService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * @see MediaStoreService
 */
@Service
@Slf4j
public class MediaStoreServiceImpl implements MediaStoreService {
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    /**
     * 流式转存的大小上限。微信侧视频消息本身不超过 10MB，留一倍余量；
     * 主要作用是防止响应异常时把部署机那点内存吃光
     */
    private static final long MAX_STREAM_BYTES = 20L * 1024 * 1024;

    private final MinioProperties properties;
    private final WxMpService wxMpService;
    /**
     * 直接用 mapper 而不是 WxMsgService：转存由 WxMsgServiceImpl 触发，
     * 反过来注入 WxMsgService 会形成构造器循环依赖
     */
    private final WxMsgMapper wxMsgMapper;
    /**
     * MinioConfig 在未配置时返回 null bean，用 ObjectProvider 避免强制注入失败
     */
    private final ObjectProvider<MinioClient> minioClientProvider;

    public MediaStoreServiceImpl(MinioProperties properties,
                                 WxMpService wxMpService,
                                 WxMsgMapper wxMsgMapper,
                                 ObjectProvider<MinioClient> minioClientProvider) {
        this.properties = properties;
        this.wxMpService = wxMpService;
        this.wxMsgMapper = wxMsgMapper;
        this.minioClientProvider = minioClientProvider;
    }

    @Override
    public boolean isAvailable() {
        return properties.isConfigured() && minioClientProvider.getIfAvailable() != null;
    }

    @Override
    public String upload(String appid, InputStream in, long size, String contentType, String ext) {
        MinioClient client = minioClientProvider.getIfAvailable();
        if (client == null || !properties.isConfigured()) {
            log.warn("MinIO 不可用，跳过上传");
            closeQuietly(in);
            return null;
        }
        String objectName = buildObjectName(appid, ext);
        try {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .contentType(StringUtils.hasText(contentType) ? contentType : DEFAULT_CONTENT_TYPE);
            if (size > 0) {
                builder.stream(in, size, -1);
            } else {
                // 大小未知时用 5MB 分块
                builder.stream(in, -1, 5L * 1024 * 1024);
            }
            client.putObject(builder.build());
            return properties.publicUrl(objectName);
        } catch (Exception e) {
            log.error("上传对象存储失败，object={}", objectName, e);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    @Override
    @Async
    public void transferInboundMediaAsync(Long msgId, String appid, String mediaId, String preferExt) {
        if (msgId == null || !StringUtils.hasText(appid) || !StringUtils.hasText(mediaId)) {
            return;
        }
        if (!isAvailable()) {
            return;
        }
        File tmp = null;
        try {
            // 异步线程没有回调线程的 ThreadLocal，必须自己切
            wxMpService.switchoverTo(appid);
            try {
                tmp = wxMpService.getMaterialService().mediaDownload(mediaId);
            } catch (WxErrorException e) {
                // 视频的 /cgi-bin/media/get 不返回文件，返回的是一段含 video_url 的 JSON，
                // 而 SDK 见到 JSON 响应就直接抛异常，真正的地址只能从异常里掏出来
                String videoUrl = videoUrlOf(e);
                if (!StringUtils.hasText(videoUrl)) {
                    throw e;
                }
                String url = uploadFromUrl(appid, videoUrl);
                if (url != null) {
                    backfillUrl(msgId, url);
                }
                return;
            }
            if (tmp == null || !tmp.exists()) {
                log.warn("媒体下载为空，msgId={}, mediaId={}", msgId, mediaId);
                return;
            }
            // 下载接口的 Content-Type 不一定可靠，有明确提示就用提示
            String ext = StringUtils.hasText(preferExt) ? preferExt.toLowerCase() : extOf(tmp.getName());
            logMagic(msgId, ext, tmp);
            String url = upload(appid, new FileInputStream(tmp), tmp.length(), contentTypeOf(ext), ext);
            if (url != null) {
                backfillUrl(msgId, url);
            }
        } catch (Exception e) {
            // 转存失败不影响消息本身，前端显示"转存中"占位
            log.error("入站媒体转存失败，msgId={}, mediaId={}", msgId, mediaId, e);
        } finally {
            if (tmp != null && tmp.exists() && !tmp.delete()) {
                log.debug("临时文件删除失败：{}", tmp.getAbsolutePath());
            }
        }
    }

    /**
     * 从 SDK 抛出的异常里取视频真实地址
     *
     * 视频类 media_id 调下载接口拿到的是 {"video_url": "http://..."}，
     * SDK 的 MediaDownloadRequestExecutor 见到 application/json 就无条件抛异常，
     * 但原始响应体被保留在 WxError.json 里，所以地址还能捞回来。
     * 不是视频（真的是报错）时返回 null，交回原来的异常处理。
     */
    private String videoUrlOf(WxErrorException e) {
        WxError error = e.getError();
        if (error == null || !StringUtils.hasText(error.getJson())) {
            return null;
        }
        try {
            return JSON.parseObject(error.getJson()).getString("video_url");
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 直接把 HTTP 响应体流进对象存储，不落临时文件
     *
     * 部署机的 /tmp 是 tmpfs（内存盘），可用内存只剩一百多 MB 且没有 swap，
     * 十几 MB 的视频写进去是实打实的 OOM 风险。这里流式转发，内存里只有 MinIO
     * 客户端的分块缓冲。同时按 Content-Length 先挡一道，没有该头时用
     * LimitedInputStream 边读边挡，避免被超大响应拖垮。
     */
    private String uploadFromUrl(String appid, String fileUrl) {
        HttpURLConnection conn = null;
        try {
            conn = openFollowingRedirects(fileUrl);
            if (conn == null) {
                return null;
            }
            long size = conn.getContentLengthLong();
            if (size > MAX_STREAM_BYTES) {
                log.warn("媒体过大，跳过转存，size={} 上限={}", size, MAX_STREAM_BYTES);
                return null;
            }
            InputStream in = new LimitedInputStream(conn.getInputStream(), MAX_STREAM_BYTES);
            return upload(appid, in, size, contentTypeOf("mp4"), "mp4");
        } catch (Exception e) {
            log.error("拉取媒体失败", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 打开连接并手工跟随跳转
     *
     * 微信给的 video_url 是 http，HttpURLConnection 的自动跳转不跨协议
     * （http 跳 https 会被它悄悄放弃，只留一个 302 给调用方），所以自己跟。
     * 返回可读的 2xx 连接，失败返回 null。
     */
    private HttpURLConnection openFollowingRedirects(String fileUrl) throws IOException {
        String target = fileUrl;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);
            int code = conn.getResponseCode();
            if (code / 100 == 2) {
                return conn;
            }
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (code / 100 != 3 || !StringUtils.hasText(location)) {
                log.warn("拉取媒体失败，HTTP {}", code);
                return null;
            }
            // Location 可能是相对路径
            target = new URL(new URL(target), location).toString();
        }
        log.warn("拉取媒体跳转次数过多，放弃");
        return null;
    }

    /**
     * 把转存后的地址写回消息的 detail
     */
    private void backfillUrl(Long msgId, String url) {
        WxMsg msg = wxMsgMapper.selectById(msgId);
        if (msg == null) {
            log.warn("消息已不存在，放弃回填，msgId={}", msgId);
            return;
        }
        JSONObject detail = msg.getDetail() == null ? new JSONObject() : msg.getDetail();
        detail.put("url", url);
        msg.setDetail(detail);
        wxMsgMapper.updateById(msg);
        log.info("入站媒体转存完成，msgId={}", msgId);
    }

    /**
     * 读超过上限就抛，给没有 Content-Length 的响应兜底
     */
    private static class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long read;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        private void count(long n) throws IOException {
            if (n <= 0) {
                return;
            }
            read += n;
            if (read > limit) {
                throw new IOException("媒体超过 " + limit + " 字节上限");
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            count(b == -1 ? 0 : 1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            count(n);
            return n;
        }
    }

    /**
     * 把文件头几个字节打进日志，用来确认微信实际给的编码格式
     *
     * 前端的 amr 解码库只吃 AMR-NB（头部 #!AMR\n），AMR-WB（#!AMR-WB\n）和 SILK（#!SILK_V3）都解不了。
     * 微信回调里的 format 字段只说"amr"，不区分这三种，所以第一次收到语音时靠这条日志核实。
     * 日志量很小（每条语音一行），留着也方便以后排查播放失败。
     */
    private void logMagic(Long msgId, String ext, File f) {
        if (!"amr".equals(ext)) {
            return;
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] head = new byte[12];
            int n = in.read(head);
            if (n <= 0) {
                return;
            }
            StringBuilder printable = new StringBuilder();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < n; i++) {
                int b = head[i] & 0xFF;
                printable.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                hex.append(String.format("%02x ", b));
            }
            log.info("语音文件头，msgId={}, size={}B, text=[{}], hex=[{}]",
                    msgId, f.length(), printable, hex.toString().trim());
        } catch (Exception e) {
            log.debug("读取语音文件头失败，msgId={}", msgId, e);
        }
    }

    /**
     * 对象路径：wxmsg/{appid}/{yyyyMMdd}/{uuid}.{ext}
     * 按 appid 和日期分区，便于以后按账号或时间做清理
     */
    private String buildObjectName(String appid, String ext) {
        String name = UUID.randomUUID().toString().replace("-", "");
        if (StringUtils.hasText(ext)) {
            name = name + "." + ext;
        }
        return "wxmsg/" + appid + "/" + LocalDate.now().format(DATE_DIR) + "/" + name;
    }

    private String extOf(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        int i = fileName.lastIndexOf('.');
        return i > -1 && i < fileName.length() - 1 ? fileName.substring(i + 1).toLowerCase() : null;
    }

    private String contentTypeOf(String ext) {
        if (ext == null) {
            return DEFAULT_CONTENT_TYPE;
        }
        switch (ext) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "webp":
                return "image/webp";
            case "amr":
                // 浏览器原生不认这个类型，前端用 WASM 解码，所以只要别被当成下载附件就行
                return "audio/amr";
            case "mp3":
                return "audio/mpeg";
            case "mp4":
                // 浏览器 <video> 直接播，必须是这个类型，否则会被当成附件下载
                return "video/mp4";
            default:
                return DEFAULT_CONTENT_TYPE;
        }
    }

    private void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
