package site.bleem.wechat.modules.wx.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import me.chanjar.weixin.mp.config.impl.WxMpDefaultConfigImpl;

import javax.validation.constraints.NotEmpty;
import java.io.Serializable;

/**
 * 公众号账号
 * 
 * @author niefy
 * @date 2020-06-17 13:56:51
 */
@Data
@TableName("wx_account")
public class WxAccount implements Serializable {
	private static final long serialVersionUID = 1L;

	/**
	 * id
	 */
	@TableId(type = IdType.INPUT)
	@NotEmpty(message = "appid不得为空")
	private String appid;
	/**
	 * 公众号名称
	 */
	@NotEmpty(message = "名称不得为空")
	private String name;
	/**
	 * 账号类型
	 */
	private int type;
	/**
	 * 认证状态
	 */
	private boolean verified;
	/**
	 * appsecret
	 */
	@NotEmpty(message = "appSecret不得为空")
	private String secret;
	/**
	 * token
	 */
	private String token;
	/**
	 * aesKey
	 */
	private String aesKey;
	/**
	 * 推送网关密钥，对应请求头 X-Notify-Secret，由系统生成，和微信那边的配置无关
	 */
	private String notifySecret;
	/**
	 * 关联的门户小程序 appid，NULL 表示该公众号没有配套小程序。
	 * 全表唯一（uk_mini_appid），登录接口靠它从 miniAppid 反查到本行
	 */
	private String miniAppid;
	/**
	 * 门户小程序的 AppSecret，用于 jscode2session 换取 openid。
	 * 和 secret 一样明文存储，接口只回「是否已配置」不回明文
	 */
	private String miniSecret;

	public WxMpDefaultConfigImpl toWxMpConfigStorage(){
		WxMpDefaultConfigImpl configStorage = new WxMpDefaultConfigImpl();
		configStorage.setAppId(appid);
		configStorage.setSecret(secret);
		configStorage.setToken(token);
		configStorage.setAesKey(aesKey);
		return configStorage;
	}

}
