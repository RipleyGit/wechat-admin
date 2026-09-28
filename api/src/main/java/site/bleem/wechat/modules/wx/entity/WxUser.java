package site.bleem.wechat.modules.wx.entity;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import site.bleem.wechat.common.utils.Json;
import lombok.Data;
import me.chanjar.weixin.common.bean.WxOAuth2UserInfo;
import me.chanjar.weixin.mp.bean.result.WxMpUser;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.Date;

/**
 * 微信粉丝
 * @author Nifury
 * @date 2017-9-27
 */
@Data
@TableName("wx_user")
public class WxUser implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.INPUT)
    private String openid;
    private String appid;
    private String phone;
    private String nickname;
    private Integer sex;
    private String city;
    private String province;
    private String country;
    private String headimgurl;
    @JSONField(name = "subscribe_time")
    private Date subscribeTime;
    private boolean subscribe;
    private String unionid;
    private String remark;
    private JSONArray tagidList;
    private String subscribeScene;
    private String qrSceneStr;

    public WxUser() {
    }

    public WxUser(String openid) {
        this.openid = openid;
    }

    public WxUser(WxMpUser wxMpUser,String appid) {
        this.openid = wxMpUser.getOpenId();
        this.appid = appid;
        this.subscribe=wxMpUser.getSubscribe();
        if(wxMpUser.getSubscribe()){
            this.nickname = wxMpUser.getNickname();
            this.headimgurl = wxMpUser.getHeadImgUrl();
            this.subscribeTime = new Date(wxMpUser.getSubscribeTime()*1000);
            this.unionid=wxMpUser.getUnionId();
            this.remark=wxMpUser.getRemark();
            this.tagidList=JSONArray.parseArray(JSONObject.toJSONString(wxMpUser.getTagIds()));
            this.subscribeScene=wxMpUser.getSubscribeScene();
            this.qrSceneStr= resolveQrScene(wxMpUser.getQrSceneStr(), wxMpUser.getQrScene());
        }
    }

    /**
     * 取扫码关注的场景值
     * <p>
     * 微信 user/info 会同时返回 qr_scene（整型 scene_id）和 qr_scene_str（字符串 scene_str），
     * 一次只有一个是真的：用 scene_str 建的码，qr_scene 固定回 0。
     * 所以优先取 scene_str，并且把 "0" 当成"没有场景值"，否则渠道来源会全部退化成 0。
     *
     * @param qrSceneStr 字符串场景值
     * @param qrScene    整型场景值，微信以字符串形式返回
     * @return 场景值，都没有则返回 null
     */
    private static String resolveQrScene(String qrSceneStr, String qrScene) {
        if (StringUtils.hasText(qrSceneStr)) {
            return qrSceneStr;
        }
        if (StringUtils.hasText(qrScene) && !"0".equals(qrScene.trim())) {
            return qrScene;
        }
        return null;
    }

    public WxUser(WxOAuth2UserInfo wxMpUser, String appid) {
        this.openid = wxMpUser.getOpenid();
        this.appid = appid;
        this.nickname = wxMpUser.getNickname();
        this.sex = wxMpUser.getSex();
        this.city = wxMpUser.getCity();
        this.province = wxMpUser.getProvince();
        this.country = wxMpUser.getCountry();
        this.headimgurl = wxMpUser.getHeadImgUrl();
        this.unionid = wxMpUser.getUnionId();
        this.subscribe = true;
    }

    @Override
    public String toString() {
        return Json.toJsonString(this);
    }
}
