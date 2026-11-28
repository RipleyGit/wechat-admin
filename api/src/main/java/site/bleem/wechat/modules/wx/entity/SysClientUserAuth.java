package site.bleem.wechat.modules.wx.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 应用认证用户：谁能用哪个子应用。
 *
 * 表名带 sys_ 前缀是历史命名，但业务上和 wx_user / wx_mini_user 是同一个域
 * （小程序用户、服务号粉丝、应用授权三者的关系），所以放在 wx 模块下，不放
 * modules.sys —— 那边是后台管理员账号体系，和这里的小程序终端用户不是一回事。
 *
 * 设计成能支撑多种渠道的认证（user_type 区分），这次只用 user_type=WX_USER：
 * user_id 是小程序 openid，一条记录代表「这个小程序用户能用某个子应用」。
 * auth_identifier / credential 本期不用，留给以后接入账号密码类渠道。
 *
 * 业务唯一键是 (user_type, user_id, client_code)，见 uk_user_client。
 * 同一用户至多一条 is_default=1，由 service 层在同一事务里先清后设保证，
 * 数据库没有只对 is_default=1 生效的唯一索引可用。
 *
 * 方案见 portal-wechat-app/docs/小程序用户应用授权重构方案.md。
 */
@Data
@TableName("sys_client_user_auth")
public class SysClientUserAuth implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String USER_TYPE_WX_USER = "wx_user";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** user_type=wx_user 时为小程序 openid */
    private String userId;

    private String userType;

    /** 应用 code，如 app_pco，和门户 utils/workbench.ts 的 WorkbenchApp.code 一致 */
    private String clientCode;

    private String clientName;

    /** 是否默认应用，启动后直达 */
    private Boolean isDefault;

    /** 本期不用，留空 */
    private String authIdentifier;

    /** 本期不用，留空。敏感字段，后台列表不回显 */
    private String credential;

    private Date lastLoginAt;

    /** 0 禁用 1 正常 */
    private Integer status;

    private Long createUserId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Date createTime;

    private Date updatedAt;

    public SysClientUserAuth() {
    }

    public SysClientUserAuth(String userId, String clientCode) {
        this.userId = userId;
        this.userType = USER_TYPE_WX_USER;
        this.clientCode = clientCode;
        this.isDefault = false;
        this.status = 1;
    }
}
