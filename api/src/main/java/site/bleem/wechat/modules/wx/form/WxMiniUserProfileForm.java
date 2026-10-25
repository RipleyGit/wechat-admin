package site.bleem.wechat.modules.wx.form;

import lombok.Data;

import java.util.List;

/**
 * 小程序用户自己完善资料的入参
 *
 * 只含用户可改的字段。appCodes / defaultCode 之所以允许用户改，是因为当前
 * 需求里「默认应用」和「我的应用」就是用户自己的偏好；若以后要做成由后台
 * 授权，把这两个字段从本表单移除，改到 /manage/wxMiniUser 下即可。
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

    /** 默认应用 code，空串表示不直达 */
    private String defaultCode;

    /** 可见应用 code 列表 */
    private List<String> appCodes;

    /**
     * 用户点了取消。此时只把 profileFilled 置 true，不改其他字段，
     * 避免下次进来又弹表单。
     */
    private Boolean cancelled;
}
