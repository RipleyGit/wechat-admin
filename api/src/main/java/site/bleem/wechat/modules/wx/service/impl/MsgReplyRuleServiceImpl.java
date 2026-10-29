package site.bleem.wechat.modules.wx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import site.bleem.wechat.modules.wx.service.MsgReplyRuleService;
import site.bleem.wechat.common.utils.PageUtils;
import site.bleem.wechat.common.utils.Query;
import site.bleem.wechat.modules.wx.entity.MsgReplyRule;
import site.bleem.wechat.modules.wx.dao.MsgReplyRuleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import me.chanjar.weixin.common.api.WxConsts;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MsgReplyRuleServiceImpl extends ServiceImpl<MsgReplyRuleMapper, MsgReplyRule> implements MsgReplyRuleService {
    @Autowired
    MsgReplyRuleMapper msgReplyRuleMapper;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        String matchValue = (String) params.get("matchValue");
        String appid = (String) params.get("appid");
        IPage<MsgReplyRule> page = this.page(
            new Query<MsgReplyRule>().getPage(params),
            new QueryWrapper<MsgReplyRule>()
                    .eq(StringUtils.hasText(appid), "appid", appid)
                    .or()
                    .apply("appid is null or appid = ''")
                    .like(StringUtils.hasText(matchValue), "match_value", matchValue)
                    .orderByDesc("update_time")
        );

        return new PageUtils(page);
    }

    /**
     * 保存自动回复规则
     *
     * @param msgReplyRule
     */

    @Override
    public boolean save(MsgReplyRule msgReplyRule) {
        // 新建时前端传 ruleId:null，Long 直接和 0 比较会拆箱 NPE
        Long ruleId = msgReplyRule.getRuleId();
        if (ruleId != null && ruleId > 0) {
            return msgReplyRuleMapper.updateById(msgReplyRule) > 0;
        }
        return msgReplyRuleMapper.insert(msgReplyRule) > 0;
    }

    /**
     * 获取所有的回复规则
     *
     * @return
     */
    @Override
    public List<MsgReplyRule> getRules() {
        return msgReplyRuleMapper.selectList(new QueryWrapper<MsgReplyRule>().orderByDesc("rule_id"));
    }

    /**
     * 获取当前时段内所有有效的回复规则
     *
     * @return
     */
    @Override
    public List<MsgReplyRule> getValidRules() {
        return msgReplyRuleMapper.selectList(
            new QueryWrapper<MsgReplyRule>()
                .eq("status", 1)
                .isNotNull("match_value")
                .ne("match_value", "")
                .orderByDesc("priority"));
    }

    /**
     * 筛选符合条件的回复规则
     *
     *
     * @param appid 公众号appid
     * @param exactMatch 是否精确匹配
     * @param keywords   关键词
     * @return 规则列表
     */
    @Override
    public List<MsgReplyRule> getMatchedRules(String appid, boolean exactMatch, String keywords) {
        LocalTime now = LocalTime.now();
        return this.getValidRules().stream()
                .filter(rule->!MsgReplyRule.REPLY_TYPE_FORWARD.equals(rule.getReplyType())) // 转发规则由 MsgForwardService 处理
                .filter(rule->!StringUtils.hasText(rule.getAppid()) || appid.equals(rule.getAppid())) // 检测是否是对应公众号的规则，如果appid为空则为通用规则
                .filter(rule->isEffectiveAt(rule, now))
                .filter(rule->isMatch(exactMatch || rule.isExactMatch(),rule.getMatchValue().split(","),keywords)) //检测是否符合匹配规则
                .collect(Collectors.toList());
    }

    @Override
    public List<MsgReplyRule> getMatchedForwardRules(String appid, String eventType, String scene) {
        LocalTime now = LocalTime.now();
        return this.getValidForwardRules().stream()
                .filter(rule->!StringUtils.hasText(rule.getAppid()) || appid.equals(rule.getAppid()))
                .filter(rule->isEffectiveAt(rule, now))
                .filter(rule->isForwardEventMatch(rule, eventType, scene))
                .collect(Collectors.toList());
    }

    /**
     * 启用中的转发规则。取消关注允许没有匹配内容，所以这里不能复用 getValidRules 里对 match_value 的过滤。
     */
    private List<MsgReplyRule> getValidForwardRules() {
        return msgReplyRuleMapper.selectList(
            new QueryWrapper<MsgReplyRule>()
                .eq("status", 1)
                .eq("reply_type", MsgReplyRule.REPLY_TYPE_FORWARD)
                .orderByDesc("priority"));
    }

    /**
     * 转发规则是否命中当前事件。
     *
     * 取消关注没有场景值，只按事件类型匹配，规则必须显式选 unsubscribe，匹配内容不参与。
     * 关注和扫码带场景值：规则的事件类型为空或与当前事件一致，且场景值按匹配方式命中。
     */
    private static boolean isForwardEventMatch(MsgReplyRule rule, String eventType, String scene) {
        if (WxConsts.EventType.UNSUBSCRIBE.equals(eventType)) {
            return WxConsts.EventType.UNSUBSCRIBE.equals(rule.getEventType());
        }
        if (!StringUtils.hasText(scene)) {
            return false;
        }
        if (StringUtils.hasText(rule.getEventType()) && !rule.getEventType().equals(eventType)) {
            return false;
        }
        return isMatchByMode(rule, scene);
    }

    private static boolean isEffectiveAt(MsgReplyRule rule, LocalTime now) {
        return (null == rule.getEffectTimeStart() || rule.getEffectTimeStart().toLocalTime().isBefore(now))
                && (null == rule.getEffectTimeEnd() || rule.getEffectTimeEnd().toLocalTime().isAfter(now));
    }

    /**
     * 转发规则的匹配。matchMode 为空时沿用 exactMatch：精确或包含。
     */
    private static boolean isMatchByMode(MsgReplyRule rule, String scene) {
        String mode = rule.getMatchMode() != null ? rule.getMatchMode()
                : (rule.isExactMatch() ? "exact" : "contains");
        for (String words : rule.getMatchValue().split(",")) {
            if (words.isEmpty()) {
                continue;
            }
            switch (mode) {
                case "exact":
                    if (words.equals(scene)) return true;
                    break;
                case "prefix":
                    if (scene.startsWith(words)) return true;
                    break;
                case "contains":
                    if (scene.contains(words)) return true;
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    /**
     * 检测文字是否匹配规则
     * 精确匹配时，需关键词与规则词语一致
     * 非精确匹配时，检测文字需包含任意一个规则词语
     *
     * @param exactMatch 是否精确匹配
     * @param ruleWords  规则列表
     * @param checkWords 需检测的文字
     * @return
     */
    public static boolean isMatch(boolean exactMatch, String[] ruleWords, String checkWords) {
        if (!StringUtils.hasText(checkWords)) {
            return false;
        }
        for (String words : ruleWords) {
            if (exactMatch && words.equals(checkWords)) {
                return true;//精确匹配，需关键词与规则词语一致
            }
            if (!exactMatch && checkWords.contains(words)) {
                return true;//模糊匹配
            }
        }
        return false;
    }
}
