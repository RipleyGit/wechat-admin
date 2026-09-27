package site.bleem.wechat.modules.wx.manage;

import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.mp.api.WxMpService;
import site.bleem.wechat.common.exception.RRException;
import site.bleem.wechat.common.utils.PageUtils;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.sys.controller.AbstractController;
import site.bleem.wechat.modules.wx.dto.PageSizeConstant;
import site.bleem.wechat.modules.wx.dto.WxMsgSessionView;
import site.bleem.wechat.modules.wx.entity.WxMsg;
import site.bleem.wechat.modules.wx.form.WxMsgReadForm;
import site.bleem.wechat.modules.wx.form.WxMsgReplyForm;
import site.bleem.wechat.modules.wx.service.FanMessageService;
import site.bleem.wechat.modules.wx.service.MsgReplyService;
import site.bleem.wechat.modules.wx.service.WxMsgService;
import site.bleem.wechat.modules.wx.service.WxMsgSessionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;


/**
 * 微信消息
 *
 * @author niefy
 * @date 2020-05-14 17:28:34
 */
@RestController
@RequestMapping("/manage/wxMsg")
@Api(tags = {"公众号消息记录-管理后台"})
public class WxMsgManageController extends AbstractController {
    @Autowired
    private WxMsgService wxMsgService;
    @Autowired
    private MsgReplyService msgReplyService;
    @Autowired
    private WxMsgSessionService wxMsgSessionService;
    @Autowired
    private FanMessageService fanMessageService;
    @Autowired
    private WxMpService wxMpService;

    /**
     * 列表
     */
    @GetMapping("/list")
    @RequiresPermissions("wx:wxmsg:list")
    @ApiOperation(value = "列表")
    public R list(@CookieValue String appid,@RequestParam Map<String, Object> params){
        params.put("appid",appid);
        PageUtils page = wxMsgService.queryPage(params);

        return R.ok().put("page", page);
    }


    /**
     * 信息
     */
    @GetMapping("/info/{id}")
    @RequiresPermissions("wx:wxmsg:info")
    @ApiOperation(value = "详情")
    public R info(@CookieValue String appid,@PathVariable("id") Long id){
		WxMsg wxMsg = wxMsgService.getById(id);

        return R.ok().put("wxMsg", wxMsg);
    }

    /**
     * 回复
     */
    @PostMapping("/reply")
    @RequiresPermissions("wx:wxmsg:save")
    @ApiOperation(value = "回复")
    public R reply(@CookieValue String appid,@RequestBody WxMsgReplyForm form){
        // 原来漏了这行：WxMpConfigStorageHolder 是 ThreadLocal，Tomcat 复用线程，
        // 不切换就会用上一个请求残留的公众号配置发消息
        wxMpService.switchoverTo(appid);
        msgReplyService.reply(form.getOpenid(),form.getReplyType(),form.getReplyContent());
        return R.ok();
    }

    /**
     * 会话列表
     */
    @GetMapping("/sessions")
    @RequiresPermissions("wx:wxmsg:list")
    @ApiOperation(value = "私信会话列表")
    public R sessions(@CookieValue String appid,
                     @RequestParam(required = false) String keyword,
                     @RequestParam(defaultValue = "1") int page) {
        Long userId = getUserId();
        int limit = PageSizeConstant.PAGE_SIZE_MEDIUM;
        int offset = Math.max(page - 1, 0) * limit;
        List<WxMsgSessionView> list = wxMsgSessionService.listSessions(appid, userId, keyword, offset, limit);
        long total = wxMsgSessionService.countSessions(appid, keyword);
        return R.ok().put("list", list).put("total", total).put("pageSize", limit);
    }

    /**
     * 单会话消息时间线
     */
    @GetMapping("/timeline")
    @RequiresPermissions("wx:wxmsg:list")
    @ApiOperation(value = "私信消息时间线")
    public R timeline(@CookieValue String appid,
                     @RequestParam String openid,
                     @RequestParam(required = false) Long beforeId) {
        int limit = PageSizeConstant.PAGE_SIZE_MEDIUM;
        List<WxMsg> list = wxMsgService.listTimeline(appid, openid, beforeId, limit);
        // 查询是按 id 倒序取的，翻页游标要在这一页的最老一条上
        Long nextCursor = list.size() < limit ? null : list.get(list.size() - 1).getId();
        Collections.reverse(list);
        return R.ok().put("list", list)
                .put("nextCursor", nextCursor)
                .put("remainingWindowMillis", fanMessageService.remainingWindowMillis(appid, openid));
    }

    /**
     * 标记已读
     */
    @PostMapping("/read")
    @RequiresPermissions("wx:wxmsg:list")
    @ApiOperation(value = "标记私信已读")
    public R read(@CookieValue String appid, @RequestBody WxMsgReadForm form) {
        form.validate();
        wxMsgSessionService.markRead(appid, form.getOpenid(), getUserId(), form.getLastReadMsgId());
        return R.ok();
    }

    /**
     * 未读会话数，全局红点用
     */
    @GetMapping("/unread-count")
    @RequiresPermissions("wx:wxmsg:list")
    @ApiOperation(value = "未读私信会话数")
    public R unreadCount(@CookieValue String appid) {
        return R.ok().put("count", wxMsgSessionService.countUnreadSessions(appid, getUserId()));
    }

    /**
     * 发私信
     *
     * 用 multipart 而不是 JSON：图片要直传，分成两个接口的话前端要判断两次
     */
    @PostMapping("/send")
    @RequiresPermissions("wx:wxmsg:save")
    @ApiOperation(value = "发送私信")
    public R send(@CookieValue String appid,
                  @RequestParam String openid,
                  @RequestParam(defaultValue = "text") String msgType,
                  @RequestParam(required = false) String content,
                  @RequestParam(required = false) MultipartFile file) {
        if (WxConsts.KefuMsgType.IMAGE.equals(msgType)) {
            fanMessageService.sendImage(appid, openid, file);
        } else if (WxConsts.KefuMsgType.TEXT.equals(msgType)) {
            fanMessageService.sendText(appid, openid, content);
        } else {
            throw new RRException("不支持的消息类型：" + msgType);
        }
        return R.ok();
    }

    /**
     * 48h 窗口外的触达，预留位
     *
     * 唯一合规手段是模板消息，但它要后台报备、字段固定、发出去是服务通知而不是对话气泡，
     * 当不了聊天用。一期只占位，详见 docs/fan-messaging.md
     */
    @PostMapping("/reach")
    @RequiresPermissions("wx:wxmsg:save")
    @ApiOperation(value = "窗口外触达（未启用）")
    public R reach(@CookieValue String appid, @RequestParam String openid) {
        return R.error("窗口外触达尚未启用");
    }

    /**
     * 删除
     */
    @PostMapping("/delete")
    @RequiresPermissions("wx:wxmsg:delete")
    @ApiOperation(value = "删除")
    public R delete(@CookieValue String appid,@RequestBody Long[] ids){
		wxMsgService.removeByIds(Arrays.asList(ids));

        return R.ok();
    }

}
