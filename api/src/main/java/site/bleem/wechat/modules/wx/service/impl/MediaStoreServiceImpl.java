package site.bleem.wechat.modules.wx.service.impl;

import com.alibaba.fastjson.JSONObject;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
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
import java.io.InputStream;
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
            tmp = wxMpService.getMaterialService().mediaDownload(mediaId);
            if (tmp == null || !tmp.exists()) {
                log.warn("媒体下载为空，msgId={}, mediaId={}", msgId, mediaId);
                return;
            }
            // 下载接口的 Content-Type 不一定可靠，有明确提示就用提示
            String ext = StringUtils.hasText(preferExt) ? preferExt.toLowerCase() : extOf(tmp.getName());
            logMagic(msgId, ext, tmp);
            String url = upload(appid, new FileInputStream(tmp), tmp.length(), contentTypeOf(ext), ext);
            if (url == null) {
                return;
            }
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
        } catch (Exception e) {
            // 转存失败不影响消息本身，图片前端回退 picUrl
            log.error("入站媒体转存失败，msgId={}, mediaId={}", msgId, mediaId, e);
        } finally {
            if (tmp != null && tmp.exists() && !tmp.delete()) {
                log.debug("临时文件删除失败：{}", tmp.getAbsolutePath());
            }
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
