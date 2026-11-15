package site.bleem.wechat.modules.wx.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.bleem.wechat.common.exception.RRException;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.entity.WxAccount;
import site.bleem.wechat.modules.wx.service.WxAccountService;

import javax.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 扫码查看公众号的推送接入信息。
 *
 * 运营台在「编辑公众号 → 推送密钥」下展示二维码，内容是本接口地址加一个随机 token。
 * 手机扫码后直接看到公众号名称、类型、AppID、推送地址和密钥，省得在电脑和手机之间抄写。
 *
 * 手机上没有后台登录态，所以这个接口是匿名的，安全性靠 token：32 位随机、10 分钟过期，
 * 只有持有公众号配置权限的人能在运营台生成。响应里含密钥，禁止中间层缓存。
 */
@Slf4j
@RestController
@RequestMapping("/push-info")
@RequiredArgsConstructor
public class PushInfoController {

    /** token → appid，由 WxAccountConfigController 写入 */
    static final String BIND_KEY = "wx:push:bind:";
    static final Duration BIND_TTL = Duration.ofMinutes(10);

    private final WxAccountService wxAccountService;
    private final RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/{token}")
    public R info(@PathVariable String token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");

        Object appid = redisTemplate.opsForValue().get(BIND_KEY + token);
        if (appid == null) {
            throw new RRException("二维码已失效，请在运营台刷新后重新扫码", 400);
        }
        WxAccount account = wxAccountService.getById(String.valueOf(appid));
        if (account == null) {
            throw new RRException("公众号不存在", 404);
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", account.getName());
        info.put("type", account.getType());
        info.put("appid", account.getAppid());
        info.put("notifySecret", account.getNotifySecret());
        return R.ok().put(info);
    }
}
