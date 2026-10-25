package site.bleem.wechat.modules.wx.entity;

import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 小程序用户
 *
 * 与 {@link WxUser}（公众号粉丝）是两套身份：同一个人在小程序下的 openid
 * 与在服务号下的 openid 不是同一个值。两者靠 unionid（需小程序与服务号绑在
 * 同一个微信开放平台帐号下）或手机号关联，所以这里同时保留 unionid 与
 * serviceOpenid 两个字段。
 *
 * 业务唯一键是 (miniAppid, miniOpenid)，见 uk_mini_user。
 */
@Data
@TableName("wx_mini_user")
public class WxMiniUser implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 小程序 appid */
    private String miniAppid;

    /** 用户在该小程序下的 openid */
    private String miniOpenid;

    /** 关联的服务号 appid */
    private String serviceAppid;

    /** 同一用户在服务号下的 openid。小程序登录时拿不到，靠 unionid 或手机号回填 */
    private String serviceOpenid;

    /** 开放平台 unionid，自动关联服务号用户的唯一可靠依据 */
    private String unionid;

    private String nickname;

    private String userPhone;

    private String remark;

    /** 默认应用 code。非空且在 appCodes 内时，登录后直达该子应用 */
    private String defaultCode;

    /** 可见应用 code 数组，如 ["pco"]。空数组表示工作台不显示任何应用 */
    private JSONArray appCodes;

    /** 是否已完善过资料。用户取消表单也置 true，避免每次进来都弹 */
    private Boolean profileFilled;

    /** 停用后登录接口仍返回档案，但可见应用视为空 */
    private Boolean enabled;

    /**
     * 建档时间。本项目没有 MetaObjectHandler，create_time / update_time
     * 都由数据库默认值与 ON UPDATE 维护，插入和更新时不要显式赋值。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER, insertStrategy = FieldStrategy.NEVER)
    private Date createTime;

    @TableField(updateStrategy = FieldStrategy.NEVER, insertStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    public WxMiniUser() {
    }

    public WxMiniUser(String miniAppid, String miniOpenid, String serviceAppid) {
        this.miniAppid = miniAppid;
        this.miniOpenid = miniOpenid;
        this.serviceAppid = serviceAppid;
        this.appCodes = new JSONArray();
        this.profileFilled = false;
        this.enabled = true;
    }
}
