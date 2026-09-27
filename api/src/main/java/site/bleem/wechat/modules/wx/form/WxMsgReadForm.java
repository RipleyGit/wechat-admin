package site.bleem.wechat.modules.wx.form;

import lombok.Data;
import site.bleem.wechat.common.exception.RRException;

@Data
public class WxMsgReadForm {
    private String openid;
    /**
     * 已读到的消息 id，取前端当前渲染到的最大 id
     */
    private Long lastReadMsgId;

    public void validate() {
        if (openid == null || openid.isEmpty()) {
            throw new RRException("缺少必要参数");
        }
        if (lastReadMsgId == null || lastReadMsgId < 0) {
            throw new RRException("已读位点不合法");
        }
    }
}
