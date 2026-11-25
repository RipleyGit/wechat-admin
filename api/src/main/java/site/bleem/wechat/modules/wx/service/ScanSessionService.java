package site.bleem.wechat.modules.wx.service;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import site.bleem.wechat.modules.wx.entity.WxUser;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 扫码识别用户：把微信的 SCAN / 扫码关注事件落到 Redis，供调用方轮询结果。
 *
 * 二维码由 /wx/scan/qrcode 生成，场景值为 {@code scan_<token>}。用户扫的是微信带参二维码：
 * 未关注时微信原生打开该公众号的关注页，关注后微信才会推送 subscribe 事件。
 * 这里只负责在事件到达时记下身份，不管关注页怎么跳。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanSessionService {

    /** token → appid，与二维码有效期一致 */
    public static final String TOKEN_KEY = "wx:scan:token:";
    /** token → 扫码结果 JSON */
    public static final String RESULT_KEY = "wx:scan:result:";
    public static final String SCENE_PREFIX = "scan_";
    public static final int EXPIRE_SECONDS = 1800;

    private final RedisTemplate<String, Object> redisTemplate;
    private final WxUserService wxUserService;

    /**
     * 处理一次扫码事件：只认本系统签发、且属于当前公众号的 token，其余场景值原样忽略。
     */
    public void record(String appid, String scene, String openid) {
        if (!StringUtils.hasText(appid) || !StringUtils.hasText(openid)
            || !StringUtils.hasText(scene) || !scene.startsWith(SCENE_PREFIX)) {
            return;
        }
        String token = scene.substring(SCENE_PREFIX.length());
        String tokenKey = TOKEN_KEY + token;

        Object owner = redisTemplate.opsForValue().get(tokenKey);
        if (owner == null || !appid.equals(String.valueOf(owner))) {
            return;
        }

        // 昵称和头像是事件里拿不到的。扫码关注时 SubscribeHandler 刚同步过，直接用本地档案；
        // 本地没有或不完整（老粉扫码）才回查微信
        WxUser user = wxUserService.getById(openid);
        if (user == null || !StringUtils.hasText(user.getNickname())) {
            WxUser fresh = wxUserService.refreshUserInfo(openid, appid);
            if (fresh != null) {
                user = fresh;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "scanned");
        result.put("openid", openid);
        result.put("nickname", user == null ? null : user.getNickname());
        result.put("headimgurl", user == null ? null : user.getHeadimgurl());
        result.put("subscribe", user == null || user.isSubscribe());

        // 结果和二维码同寿，二维码过期后调用方就不该再拿到身份
        Long ttl = redisTemplate.getExpire(tokenKey);
        redisTemplate.opsForValue().set(RESULT_KEY + token, JSON.toJSONString(result),
            Duration.ofSeconds(ttl == null || ttl <= 0 ? EXPIRE_SECONDS : ttl));
        log.info("[扫码] 记录扫码结果 appid={} openid={} token={}", appid, openid, token);
    }
}
