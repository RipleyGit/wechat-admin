package site.bleem.wechat.modules.wx.controller;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.result.WxMpQrCodeTicket;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.entity.WxAccount;
import site.bleem.wechat.modules.wx.service.ScanSessionService;
import site.bleem.wechat.modules.wx.service.WxAccountService;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 扫码识别用户。
 *
 * 二维码是微信的带参数临时二维码，不是普通网址二维码：用户用微信扫，
 * 未关注会被微信原生引导到该公众号的关注页，关注后才会收到 subscribe 事件；
 * 已关注直接触发 SCAN 事件。两种情况都由 ScanSessionService 记下身份，
 * 调用方拿 token 轮询 /wx/scan/result 取结果。
 *
 * 鉴权复用公众号推送密钥，路径在 /wx/** 下（Shiro 匿名）。
 */
@Slf4j
@RestController
@RequestMapping("/scan")
@RequiredArgsConstructor
public class ScanQrcodeController {

    private final WxAccountService wxAccountService;
    private final WxMpService wxMpService;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 生成扫码二维码，返回 base64 图片和轮询用的 token
     */
    @GetMapping("/qrcode")
    public R qrcode(@RequestHeader(value = "X-Notify-Secret", required = false) String secret) {
        WxAccount account = wxAccountService.getByNotifySecret(secret);
        if (account == null) {
            log.warn("[扫码] 鉴权失败");
            return R.error(401, "invalid notify secret");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        String scene = ScanSessionService.SCENE_PREFIX + token;
        try {
            wxMpService.switchoverTo(account.getAppid());
            WxMpQrCodeTicket ticket = wxMpService.getQrcodeService()
                .qrCodeCreateTmpTicket(scene, ScanSessionService.EXPIRE_SECONDS);
            String base64 = readTicketImage(ticket);

            redisTemplate.opsForValue().set(ScanSessionService.TOKEN_KEY + token, account.getAppid(),
                Duration.ofSeconds(ScanSessionService.EXPIRE_SECONDS));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", token);
            data.put("expiresIn", ScanSessionService.EXPIRE_SECONDS);
            data.put("base64", base64);
            return R.ok().put(data);
        } catch (WxErrorException e) {
            log.warn("[扫码] 生成二维码失败 appid={}", account.getAppid(), e);
            return R.error(500, "生成二维码失败：" + e.getError().getErrorMsg());
        }
    }

    /**
     * 轮询扫码结果。未扫码时 status=pending，扫码后返回昵称、openid、头像
     */
    @GetMapping("/result")
    public R result(@RequestHeader(value = "X-Notify-Secret", required = false) String secret,
                    @RequestParam String token) {
        WxAccount account = wxAccountService.getByNotifySecret(secret);
        if (account == null) {
            return R.error(401, "invalid notify secret");
        }

        String tokenKey = ScanSessionService.TOKEN_KEY + token;
        Object owner = redisTemplate.opsForValue().get(tokenKey);
        if (owner == null || !account.getAppid().equals(String.valueOf(owner))) {
            return R.error(400, "二维码不存在或已过期");
        }

        Object cached = redisTemplate.opsForValue().get(ScanSessionService.RESULT_KEY + token);
        if (cached == null) {
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("status", "pending");
            return R.ok().put(pending);
        }
        return R.ok().put(JSON.parseObject(String.valueOf(cached)));
    }

    /**
     * 微信返回的二维码图片是临时文件，读成 base64 后直接删掉。
     * 图片是 JPEG，data URL 里写死 image/jpeg，前端可直接放进 img src。
     */
    private String readTicketImage(WxMpQrCodeTicket ticket) throws WxErrorException {
        File file = wxMpService.getQrcodeService().qrCodePicture(ticket);
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("读取二维码图片失败", e);
        } finally {
            if (file != null && !file.delete()) {
                log.warn("[扫码] 临时二维码文件删除失败：{}", file.getAbsolutePath());
            }
        }
    }
}
