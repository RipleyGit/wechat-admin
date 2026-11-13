package site.bleem.wechat.modules.wx.form;

import lombok.Data;

import java.util.List;

/**
 * 推送收件人。四种来源至少填一个，填多个取并集去重。
 *
 * - openids / phones / nicknames / tagids 任一在系统里不存在，整批 400，不做部分解析
 * - 命中用户里有已取关的，整批 400 返回取关清单；tagid 展开只取 subscribe=1，不触发整批失败
 */
@Data
public class PushTarget {
    private List<String> openids;
    private List<String> phones;
    private List<String> nicknames;
    private List<Integer> tagids;
}
