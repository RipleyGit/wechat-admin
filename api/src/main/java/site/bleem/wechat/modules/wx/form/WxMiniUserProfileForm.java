package site.bleem.wechat.modules.wx.form;

import lombok.Data;

import java.util.List;

/**
 * 小程序用户自己完善资料的入参
 *
 * 只含用户可改的字段。**应用可见范围（appCodes）与默认应用（defaultCode）
 * 属于后台授权，不在这里**，由 /manage/wxMiniUser/update 维护 —— 否则用户
 * 自己就能给自己开通应用，可见范围形同虚设。
 */
@Data
public class WxMiniUserProfileForm {
    /** 定位用，必填 */
    private String miniAppid;

    /** 定位用，必填 */
    private String miniOpenid;

    private String nickname;

    private String userPhone;

    private String remark;

    /**
     * 用户点了取消。此时只把 profileFilled 置 true，不改其他字段，
     * 避免下次进来又弹表单。
     */
    private Boolean cancelled;
}
