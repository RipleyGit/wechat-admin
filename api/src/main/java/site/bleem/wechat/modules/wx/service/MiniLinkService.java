package site.bleem.wechat.modules.wx.service;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.bean.oauth2.WxOAuth2AccessToken;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.kefu.WxMpKefuMessage;
import me.chanjar.weixin.mp.bean.result.WxMpQrCodeTicket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import site.bleem.wechat.common.exception.RRException;
import site.bleem.wechat.modules.wx.entity.WxAccount;
import site.bleem.wechat.modules.wx.entity.WxMiniUser;
import site.bleem.wechat.modules.wx.entity.WxUser;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 小程序用户关联服务号粉丝。
 *
 * 小程序和服务号没有绑同一个开放平台，拿不到 unionid，所以服务号 openid 只能从
 * 服务号自己那边拿。两条通道，都以一次性 ticket 把「哪个小程序用户」带过去：
 *
 * <ol>
 *     <li>网页授权：小程序 web-view 打开 /wx/miniLink/start?ticket=，跳 snsapi_base
 *     静默授权，回调里 code 换服务号 openid。已关注的用户全程无感。</li>
 *     <li>带参二维码：场景值 link_&lt;ticket&gt;。未关注的用户扫码关注触发 subscribe，
 *     已关注的扫码触发 SCAN，事件里的 FromUser 就是服务号 openid。</li>
 * </ol>
 *
 * 关联成功后两边互写：wx_user.mini_openid ← 小程序 openid，
 * wx_mini_user.service_openid ← 服务号 openid。两边是一对一，任一边已关联了别人就拒绝。
 *
 * 方案见 portal-wechat-app/docs/小程序用户关联服务号粉丝方案.md。
 */
@Slf4j
@Service
public class MiniLinkService {

    public static final String SCENE_PREFIX = "link_";
    /** ticket → {miniUserId, miniAppid, miniOpenid, serviceAppid} */
    private static final String TICKET_KEY = "wx:minilink:ticket:";
    /** ticket → 状态，见 STATUS_* */
    private static final String STATUS_KEY = "wx:minilink:status:";
    public static final int EXPIRE_SECONDS = 1800;

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_LINKED = "linked";
    public static final String STATUS_NEED_FOLLOW = "need_follow";
    public static final String STATUS_CONFLICT = "conflict";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_ERROR = "error";

    /** 关联成功后回流的小程序页面 */
    private static final String BACK_PAGE = "pages/launch/launch";

    private final RedisTemplate<String, Object> redisTemplate;
    private final WxMpService wxMpService;
    private final WxUserService wxUserService;
    private final WxMiniUserService wxMiniUserService;
    private final WxAccountService wxAccountService;
    private final TransactionTemplate transactionTemplate;

    /** 网页授权回调的对外根地址，要带 context-path /wx，域名须配成服务号「网页授权域名」 */
    @Value("${wx.mini.link-base-url:https://wechat.bleem.site/wx}")
    private String linkBaseUrl;

    /** 回流小程序卡片的封面图 media_id（永久素材）。不配就退回发一段文字 */
    @Value("${wx.mini.link-card-thumb-media-id:}")
    private String cardThumbMediaId;

    @Value("${wx.mini.link-card-title:已完成关联，点击返回小程序}")
    private String cardTitle;

    public MiniLinkService(RedisTemplate<String, Object> redisTemplate, WxMpService wxMpService,
                           WxUserService wxUserService, WxMiniUserService wxMiniUserService,
                           WxAccountService wxAccountService, TransactionTemplate transactionTemplate) {
        this.redisTemplate = redisTemplate;
        this.wxMpService = wxMpService;
        this.wxUserService = wxUserService;
        this.wxMiniUserService = wxMiniUserService;
        this.wxAccountService = wxAccountService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 签发 ticket。只接受 wx.login 的 code：本接口无鉴权，若允许直传 openid，
     * 任何人都能拿别人的 openid 去关联。
     *
     * @return status=linked 表示已经关联过，不需要 ticket；否则带 ticket 与 expiresIn
     */
    public Map<String, Object> issueTicket(String miniAppid, String code) {
        WxMiniUser user = wxMiniUserService.getByCode(miniAppid, code);
        if (user == null) {
            throw new RRException("用户档案不存在，请先登录");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (StringUtils.hasText(user.getServiceOpenid())) {
            result.put("status", STATUS_LINKED);
            return result;
        }

        // 服务号 appid 由服务端配置决定，不信任调用方
        WxAccount account = wxAccountService.getByMiniAppid(miniAppid);
        if (account == null) {
            throw new RRException("小程序 " + miniAppid + " 未关联服务号，请在运营台配置");
        }

        JSONObject info = new JSONObject();
        info.put("miniUserId", user.getId());
        info.put("miniAppid", user.getMiniAppid());
        info.put("miniOpenid", user.getMiniOpenid());
        info.put("serviceAppid", account.getAppid());

        String ticket = UUID.randomUUID().toString().replace("-", "");
        Duration ttl = Duration.ofSeconds(EXPIRE_SECONDS);
        redisTemplate.opsForValue().set(TICKET_KEY + ticket, info.toJSONString(), ttl);
        redisTemplate.opsForValue().set(STATUS_KEY + ticket, STATUS_PENDING, ttl);

        result.put("status", STATUS_PENDING);
        result.put("ticket", ticket);
        result.put("expiresIn", EXPIRE_SECONDS);
        return result;
    }

    /** 查询 ticket 状态。ticket 过期或不存在返回 expired */
    public String status(String ticket) {
        if (!StringUtils.hasText(ticket)) return STATUS_EXPIRED;
        Object value = redisTemplate.opsForValue().get(STATUS_KEY + ticket);
        return value == null ? STATUS_EXPIRED : String.valueOf(value);
    }

    /**
     * 网页授权跳转地址（snsapi_base，静默，不弹窗）。ticket 放在 state 里带回来。
     *
     * @return null 表示 ticket 已过期
     */
    public String buildAuthorizeUrl(String ticket) {
        JSONObject info = loadTicket(ticket);
        if (info == null) return null;
        wxMpService.switchoverTo(info.getString("serviceAppid"));
        return wxMpService.getOAuth2Service()
            .buildAuthorizationUrl(linkBaseUrl + "/miniLink/callback", "snsapi_base", ticket);
    }

    /**
     * 网页授权回调：code 换服务号 openid 后关联。
     *
     * @return 关联后的状态
     */
    public String handleOauthCallback(String code, String ticket) {
        JSONObject info = loadTicket(ticket);
        if (info == null) return STATUS_EXPIRED;
        if (!StringUtils.hasText(code)) return STATUS_ERROR;

        String current = status(ticket);
        if (STATUS_LINKED.equals(current) || STATUS_CONFLICT.equals(current)) {
            return current;
        }

        String serviceOpenid;
        try {
            wxMpService.switchoverTo(info.getString("serviceAppid"));
            WxOAuth2AccessToken token = wxMpService.getOAuth2Service().getAccessToken(code);
            serviceOpenid = token.getOpenId();
        } catch (WxErrorException e) {
            log.warn("[小程序关联] 网页授权 code 换 openid 失败 ticket={}", ticket, e);
            return STATUS_ERROR;
        }
        return link(info, ticket, serviceOpenid);
    }

    /**
     * 扫码事件（subscribe 或 SCAN）。只处理 link_ 前缀的场景值，其余原样忽略。
     * ticket 一次性：已经 linked / conflict 的再扫不生效。
     */
    public void onScene(String appid, String scene, String openid) {
        if (!StringUtils.hasText(scene) || !scene.startsWith(SCENE_PREFIX)
            || !StringUtils.hasText(openid)) {
            return;
        }
        String ticket = scene.substring(SCENE_PREFIX.length());
        JSONObject info = loadTicket(ticket);
        if (info == null || !info.getString("serviceAppid").equals(appid)) {
            return;
        }
        String current = status(ticket);
        if (STATUS_LINKED.equals(current) || STATUS_CONFLICT.equals(current)) {
            log.info("[小程序关联] ticket 已使用过，忽略 ticket={} openid={}", ticket, openid);
            return;
        }

        String result = link(info, ticket, openid);
        if (STATUS_LINKED.equals(result)) {
            sendBackCard(info, openid);
        }
    }

    /**
     * 生成场景值为 link_&lt;ticket&gt; 的临时二维码，有效期与 ticket 剩余时间一致。
     *
     * @return data URL（image/jpeg）
     */
    public String qrcode(String ticket) {
        JSONObject info = loadTicket(ticket);
        if (info == null) {
            throw new RRException("关联凭证已过期，请重新进入");
        }
        Long ttl = redisTemplate.getExpire(TICKET_KEY + ticket);
        int seconds = ttl == null || ttl <= 0 ? EXPIRE_SECONDS : ttl.intValue();
        try {
            wxMpService.switchoverTo(info.getString("serviceAppid"));
            WxMpQrCodeTicket qr = wxMpService.getQrcodeService()
                .qrCodeCreateTmpTicket(SCENE_PREFIX + ticket, seconds);
            File file = wxMpService.getQrcodeService().qrCodePicture(qr);
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
            } finally {
                if (file != null && !file.delete()) {
                    log.warn("[小程序关联] 临时二维码文件删除失败：{}", file.getAbsolutePath());
                }
            }
        } catch (WxErrorException e) {
            log.warn("[小程序关联] 生成二维码失败 ticket={}", ticket, e);
            throw new RRException("生成二维码失败：" + e.getError().getErrorMsg());
        } catch (java.io.IOException e) {
            throw new RRException("读取二维码图片失败");
        }
    }

    /**
     * 关联核心：确认粉丝在关注，再在一个事务里两边互写。结果写回 ticket 状态。
     */
    private String link(JSONObject info, String ticket, String serviceOpenid) {
        String serviceAppid = info.getString("serviceAppid");
        String miniAppid = info.getString("miniAppid");
        String miniOpenid = info.getString("miniOpenid");
        Long miniUserId = info.getLong("miniUserId");

        // 本地粉丝表可能没同步过这个人，或者记录的是旧的关注状态，以微信为准再查一次
        WxUser fan = wxUserService.getById(serviceOpenid);
        if (fan == null || !fan.isSubscribe()) {
            WxUser fresh = wxUserService.refreshUserInfo(serviceOpenid, serviceAppid);
            if (fresh != null) fan = fresh;
        }
        if (fan == null || !serviceAppid.equals(fan.getAppid()) || !fan.isSubscribe()) {
            return setStatus(ticket, STATUS_NEED_FOLLOW);
        }

        // 一对一检查：粉丝已关联别的小程序用户
        if (StringUtils.hasText(fan.getMiniOpenid())
            && !(miniOpenid.equals(fan.getMiniOpenid()) && miniAppid.equals(fan.getMiniAppid()))) {
            log.warn("[小程序关联] 冲突：粉丝 {} 已关联小程序用户 {}，本次 {}",
                serviceOpenid, fan.getMiniOpenid(), miniOpenid);
            return setStatus(ticket, STATUS_CONFLICT);
        }

        WxMiniUser mini = wxMiniUserService.getById(miniUserId);
        if (mini == null) {
            return setStatus(ticket, STATUS_ERROR);
        }
        // 一对一检查：小程序用户已关联别的粉丝
        if (StringUtils.hasText(mini.getServiceOpenid()) && !serviceOpenid.equals(mini.getServiceOpenid())) {
            log.warn("[小程序关联] 冲突：小程序用户 {} 已关联粉丝 {}，本次 {}",
                miniOpenid, mini.getServiceOpenid(), serviceOpenid);
            return setStatus(ticket, STATUS_CONFLICT);
        }

        Boolean ok;
        try {
            ok = transactionTemplate.execute(tx -> {
                // 条件更新兜住并发：只在对方还没关联、或关联的就是自己时才写
                boolean fanOk = wxUserService.update(new UpdateWrapper<WxUser>()
                    .eq("openid", serviceOpenid)
                    .and(w -> w.isNull("mini_openid").or().eq("mini_openid", miniOpenid))
                    .set("mini_appid", miniAppid)
                    .set("mini_openid", miniOpenid)
                    .set("mini_link_time", new Date()));
                boolean miniOk = fanOk && wxMiniUserService.update(new UpdateWrapper<WxMiniUser>()
                    .eq("id", miniUserId)
                    .and(w -> w.isNull("service_openid").or().eq("service_openid", serviceOpenid))
                    .set("service_openid", serviceOpenid)
                    .set("service_appid", serviceAppid));
                if (!miniOk) tx.setRollbackOnly();
                return miniOk;
            });
        } catch (DuplicateKeyException e) {
            ok = false;
        }

        if (!Boolean.TRUE.equals(ok)) {
            log.warn("[小程序关联] 写库未生效（并发冲突）miniOpenid={} serviceOpenid={}", miniOpenid, serviceOpenid);
            return setStatus(ticket, STATUS_CONFLICT);
        }
        log.info("[小程序关联] 完成 miniOpenid={} → serviceOpenid={}", miniOpenid, serviceOpenid);
        return setStatus(ticket, STATUS_LINKED);
    }

    /**
     * 扫码关联后回流：发一张小程序卡片，用户点了回到门户。
     * 用户刚关注/扫码，处在 48 小时客服消息窗口内。失败只打日志，不影响关联结果。
     */
    private void sendBackCard(JSONObject info, String openid) {
        try {
            wxMpService.switchoverTo(info.getString("serviceAppid"));
            WxMpKefuMessage message;
            if (StringUtils.hasText(cardThumbMediaId)) {
                message = WxMpKefuMessage.MINIPROGRAMPAGE()
                    .toUser(openid)
                    .appId(info.getString("miniAppid"))
                    .title(cardTitle)
                    .pagePath(BACK_PAGE)
                    .thumbMediaId(cardThumbMediaId)
                    .build();
            } else {
                message = WxMpKefuMessage.TEXT()
                    .toUser(openid)
                    .content("已完成关联，请返回小程序继续使用。")
                    .build();
            }
            wxMpService.getKefuService().sendKefuMessage(message);
        } catch (Exception e) {
            log.warn("[小程序关联] 回流消息发送失败 openid={}", openid, e);
        }
    }

    private JSONObject loadTicket(String ticket) {
        if (!StringUtils.hasText(ticket)) return null;
        Object raw = redisTemplate.opsForValue().get(TICKET_KEY + ticket);
        return raw == null ? null : JSONObject.parseObject(String.valueOf(raw));
    }

    /** 状态与 ticket 同寿 */
    private String setStatus(String ticket, String status) {
        Long ttl = redisTemplate.getExpire(TICKET_KEY + ticket);
        long seconds = ttl == null || ttl <= 0 ? EXPIRE_SECONDS : ttl;
        redisTemplate.opsForValue().set(STATUS_KEY + ticket, status, Duration.ofSeconds(seconds));
        return status;
    }
}
