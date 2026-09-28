package site.bleem.wechat.modules.wx.handler;

import me.chanjar.weixin.mp.api.WxMpMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Binary Wang
 */
public abstract class AbstractHandler implements WxMpMessageHandler {
    /**
     * 扫码关注事件的 EventKey 前缀
     */
    private static final String QR_SCENE_PREFIX = "qrscene_";

    protected Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 取扫码事件里的场景值
     * <p>
     * 同一个带参二维码，两种事件给的 EventKey 格式不一样：
     * <ul>
     *     <li>老粉扫码（SCAN）：{@code <场景值>}</li>
     *     <li>新粉扫码关注（SUBSCRIBE）：{@code qrscene_<场景值>}</li>
     * </ul>
     * 自动回复是精确匹配的，不剥前缀的话，按场景值配的规则只对老粉生效，
     * 对扫码新关注的人不生效 —— 而后者才是渠道码的主用途。
     *
     * @param eventKey 事件 EventKey
     * @return 场景值，普通关注（EventKey 为空）时原样返回
     */
    protected String extractQrScene(String eventKey) {
        if (eventKey != null && eventKey.startsWith(QR_SCENE_PREFIX)) {
            return eventKey.substring(QR_SCENE_PREFIX.length());
        }
        return eventKey;
    }
}
