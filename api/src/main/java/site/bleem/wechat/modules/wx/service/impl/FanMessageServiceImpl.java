package site.bleem.wechat.modules.wx.service.impl;

import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.common.bean.result.WxMediaUploadResult;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.kefu.WxMpKefuMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import site.bleem.wechat.common.exception.RRException;
import site.bleem.wechat.modules.wx.entity.WxMsg;
import site.bleem.wechat.modules.wx.entity.WxMsgSession;
import site.bleem.wechat.modules.wx.service.FanMessageService;
import site.bleem.wechat.modules.wx.service.MediaStoreService;
import site.bleem.wechat.modules.wx.service.WxMsgService;
import site.bleem.wechat.modules.wx.service.WxMsgSessionService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Date;

/**
 * @see FanMessageService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FanMessageServiceImpl implements FanMessageService {

    /** 客服消息图片上限 2MB */
    private static final long MAX_IMAGE_SIZE = 2L * 1024 * 1024;

    private final WxMpService wxMpService;
    private final WxMsgService wxMsgService;
    private final WxMsgSessionService wxMsgSessionService;
    private final MediaStoreService mediaStoreService;

    @Override
    public long remainingWindowMillis(String appid, String openid) {
        WxMsgSession session = wxMsgSessionService.getSession(appid, openid);
        if (session == null || session.getLastInTime() == null) {
            return 0;
        }
        long remaining = session.getLastInTime().getTime() + INTERACT_WINDOW_MILLIS - System.currentTimeMillis();
        return Math.max(remaining, 0);
    }

    @Override
    public void sendText(String appid, String openid, String content) {
        if (!StringUtils.hasText(content)) {
            throw new RRException("消息内容不能为空");
        }
        assertWindowOpen(appid, openid);
        // ThreadLocal 是 Tomcat 复用的线程带过来的，不切就会发到上一个请求的公众号
        wxMpService.switchoverTo(appid);
        try {
            wxMpService.getKefuService().sendKefuMessage(
                    WxMpKefuMessage.TEXT().toUser(openid).content(content).build());
        } catch (WxErrorException e) {
            log.error("私信发送失败, appid={}, openid={}", appid, openid, e);
            throw new RRException("发送失败：" + e.getError().getErrorMsg());
        }
        JSONObject detail = new JSONObject().fluentPut("content", content);
        wxMsgService.addWxMsgSync(buildOutMsg(appid, WxConsts.KefuMsgType.TEXT, openid, detail));
    }

    @Override
    public void sendImage(String appid, String openid, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RRException("请选择图片");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new RRException("图片不能超过 2MB");
        }
        assertWindowOpen(appid, openid);

        byte[] bytes;
        try {
            // 读进内存一次，临时素材和 MinIO 各用一遍。2MB 上限，内存开销可控
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RRException("读取图片失败");
        }
        String ext = extOf(file.getOriginalFilename());

        wxMpService.switchoverTo(appid);
        String mediaId;
        try {
            // 临时素材：不占永久素材配额，三天有效期够发送用
            WxMediaUploadResult uploadResult = wxMpService.getMaterialService()
                    .mediaUpload(WxConsts.MediaFileType.IMAGE, ext, new ByteArrayInputStream(bytes));
            mediaId = uploadResult.getMediaId();
        } catch (WxErrorException e) {
            log.error("临时素材上传失败, appid={}", appid, e);
            throw new RRException("图片上传微信失败：" + e.getError().getErrorMsg());
        }

        try {
            wxMpService.getKefuService().sendKefuMessage(
                    WxMpKefuMessage.IMAGE().toUser(openid).mediaId(mediaId).build());
        } catch (WxErrorException e) {
            log.error("私信图片发送失败, appid={}, openid={}", appid, openid, e);
            throw new RRException("发送失败：" + e.getError().getErrorMsg());
        }

        JSONObject detail = new JSONObject().fluentPut("mediaId", mediaId);
        // 已经发出去了，转存失败不能回滚，只是气泡三天后显示不出来
        String url = mediaStoreService.upload(appid, new ByteArrayInputStream(bytes),
                bytes.length, file.getContentType(), ext);
        if (StringUtils.hasText(url)) {
            detail.put("url", url);
        }
        wxMsgService.addWxMsgSync(buildOutMsg(appid, WxConsts.KefuMsgType.IMAGE, openid, detail));
    }

    /**
     * 前端禁用输入框只是体验，这里是真正的闸门
     */
    private void assertWindowOpen(String appid, String openid) {
        if (remainingWindowMillis(appid, openid) <= 0) {
            throw new RRException("距粉丝最后一次互动已超过 48 小时，微信不允许再发送客服消息");
        }
    }

    /**
     * 不用 WxMsg.buildOutMsg：它从 WxMpConfigStorageHolder 取 appid，
     * 依赖调用时机的 ThreadLocal 状态。这里 appid 是明确的，直接写。
     */
    private WxMsg buildOutMsg(String appid, String msgType, String openid, JSONObject detail) {
        WxMsg msg = new WxMsg();
        msg.setAppid(appid);
        msg.setOpenid(openid);
        msg.setMsgType(msgType);
        msg.setDetail(detail);
        msg.setInOut(WxMsg.WxMsgInOut.OUT);
        msg.setCreateTime(new Date());
        return msg;
    }

    private String extOf(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "jpg";
        }
        int i = fileName.lastIndexOf('.');
        String ext = i > -1 && i < fileName.length() - 1 ? fileName.substring(i + 1).toLowerCase() : null;
        return StringUtils.hasText(ext) ? ext : "jpg";
    }
}
