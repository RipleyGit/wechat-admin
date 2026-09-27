package site.bleem.wechat.modules.wx.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import site.bleem.wechat.modules.wx.entity.WxUser;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class WxUserVO extends WxUser {
    private List<String> tagNames;

    public WxUserVO(WxUser user) {
        this.setOpenid(user.getOpenid());
        this.setAppid(user.getAppid());
        this.setUnionid(user.getUnionid());
        this.setNickname(user.getNickname());
        this.setSex(user.getSex());
        this.setCity(user.getCity());
        this.setProvince(user.getProvince());
        this.setCountry(user.getCountry());
        this.setHeadimgurl(user.getHeadimgurl());
        this.setSubscribe(user.isSubscribe());
        this.setSubscribeTime(user.getSubscribeTime());
        this.setSubscribeScene(user.getSubscribeScene());
        this.setQrSceneStr(user.getQrSceneStr());
        this.setTagidList(user.getTagidList());
        this.setRemark(user.getRemark());
        this.setPhone(user.getPhone());
    }
}
