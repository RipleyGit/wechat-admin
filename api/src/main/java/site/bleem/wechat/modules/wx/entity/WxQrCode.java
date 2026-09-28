package site.bleem.wechat.modules.wx.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import site.bleem.wechat.modules.wx.form.WxQrCodeForm;
import lombok.Data;
import org.springframework.util.StringUtils;

/**
 * 公众号带参二维码
 *
 * @author niefy
 * @email niefy@qq.com
 * @date 2020-01-02 11:11:55
 */
@Data
@TableName("wx_qr_code")
public class WxQrCode implements Serializable {
    private static final long serialVersionUID = 1L;
    /**
     * 换二维码图片的公开接口，不需要 access_token
     */
    private static final String SHOW_QRCODE_URL = "https://mp.weixin.qq.com/cgi-bin/showqrcode?ticket=";

    /**
     * ID
     */
    @TableId
    private Long id;
    private String appid;
    /**
     * 二维码类型
     */
    private Boolean isTemp;
    /**
     * 场景值ID
     */
    private String sceneStr;
    /**
     * 二维码ticket
     */
    private String ticket;
    /**
     * 二维码图片解析后的地址
     */
    private String url;
    /**
     * 该二维码失效时间
     */
    private Date expireTime;
    /**
     * 该二维码创建时间
     */
    private Date createTime;
    /**
     * 二维码图片地址，由 ticket 换算，不入库
     */
    @TableField(exist = false)
    private String imageUrl;

    public WxQrCode() {
    }

    public WxQrCode(WxQrCodeForm form,String appid) {
        this.appid = appid;
        this.isTemp = form.getIsTemp();
        this.sceneStr = form.getSceneStr();
        this.createTime = new Date();
    }

    /**
     * 二维码图片地址
     * <p>
     * 库里存的 url 是二维码<b>解码后的内容</b>（weixin.qq.com/q/xxx），不是图片，点开只会跳网页。
     * 真正的图片要拿 ticket 换，showqrcode 是公开接口，不需要 access_token，所以这里直接拼给前端用。
     * 不落库：ticket 变了地址就跟着变，存下来只会有不一致的风险。
     *
     * @return 图片地址，没有 ticket 时返回 null
     */
    public String getImageUrl() {
        if (!StringUtils.hasText(this.ticket)) {
            return null;
        }
        try {
            return SHOW_QRCODE_URL + URLEncoder.encode(this.ticket, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 一定存在，走不到这里
            return null;
        }
    }
}
