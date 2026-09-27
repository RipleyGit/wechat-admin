package site.bleem.wechat.modules.wx.form;

import lombok.Data;

/**
 * 设置粉丝备注
 * <p>
 * remark 允许为空串，表示清除备注，所以这里不加非空校验。
 */
@Data
public class WxUserRemarkForm {
    private String openid;
    private String remark;
}
