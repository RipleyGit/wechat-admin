package site.bleem.wechat.modules.wx.manage;

import site.bleem.wechat.common.utils.PageUtils;
import site.bleem.wechat.common.utils.R;
import site.bleem.wechat.modules.wx.entity.WxUser;
import site.bleem.wechat.modules.wx.service.WxUserService;
import site.bleem.wechat.modules.wx.service.WxUserTagsService;
import site.bleem.wechat.modules.wx.vo.WxUserVO;
import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import me.chanjar.weixin.mp.bean.tag.WxUserTag;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 用户表
 *
 * @author niefy
 * @email niefy@qq.com
 * @date 2020-03-07 13:55:23
 */
@RestController
@RequestMapping("/manage/wxUser")
@Api(tags = {"公众号粉丝-管理后台"})
public class WxUserManageController {
    @Autowired
    private WxUserService userService;
    @Autowired
    private WxUserTagsService userTagsService;

    /**
     * 列表
     */
    @GetMapping("/list")
    @RequiresPermissions("wx:wxuser:list")
    @ApiOperation(value = "列表")
    public R list(@CookieValue String appid,@RequestParam Map<String, Object> params) throws Exception {
        params.put("appid",appid);
        IPage<WxUser> queryPage = userService.queryPage(params);

        Map<Long, String> tagNameById = userTagsService.getWxTags(appid).stream()
                .collect(Collectors.toMap(WxUserTag::getId, WxUserTag::getName, (a, b) -> a));

        List<WxUserVO> records = queryPage.getRecords().stream().map(user -> {
            WxUserVO vo = new WxUserVO(user);
            JSONArray tagidList = user.getTagidList();
            if (tagidList != null) {
                List<String> tagNames = tagidList.stream()
                        .map(tagid -> tagNameById.get(((Number) tagid).longValue()))
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());
                vo.setTagNames(tagNames);
            }
            return vo;
        }).collect(Collectors.toList());

        IPage<WxUserVO> voPage = queryPage.convert(user -> null);
        voPage.setRecords(records);
        PageUtils page = new PageUtils(voPage);

        return R.ok().put("page", page);
    }

    /**
     * 列表
     */
    @PostMapping("/listByIds")
    @RequiresPermissions("wx:wxuser:list")
    @ApiOperation(value = "列表-ID查询")
    public R listByIds(@CookieValue String appid,@RequestBody String[] openids){
        List<WxUser> users = userService.listByIds(Arrays.asList(openids));
        return R.ok().put(users);
    }


    /**
     * 信息
     */
    @GetMapping("/info/{openid}")
    @RequiresPermissions("wx:wxuser:info")
    @ApiOperation(value = "详情")
    public R info(@CookieValue String appid,@PathVariable("openid") String openid) {
        WxUser wxUser = userService.getById(openid);

        return R.ok().put("wxUser", wxUser);
    }

    /**
     * 同步用户列表
     */
    @PostMapping("/syncWxUsers")
    @RequiresPermissions("wx:wxuser:save")
    @ApiOperation(value = "同步用户列表到数据库")
    public R syncWxUsers(@CookieValue String appid) {
        userService.syncWxUsers(appid);

        return R.ok("任务已建立");
    }



    /**
     * 删除
     */
    @PostMapping("/delete")
    @RequiresPermissions("wx:wxuser:delete")
    @ApiOperation(value = "删除")
    public R delete(@CookieValue String appid,@RequestBody String[] ids) {
        userService.removeByIds(Arrays.asList(ids));

        return R.ok();
    }

}
