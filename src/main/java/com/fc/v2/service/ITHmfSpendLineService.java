package com.fc.v2.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.THmfSpendLine;
import com.fc.v2.model.custom.SpendCheckReceipt;
import com.fc.v2.model.custom.SpendCheckRequest;
import com.fc.v2.model.custom.SpendLineSaveReceipt;
import com.fc.v2.model.custom.SpendLineVerdict;

/**
 * 支出审定额线 Service接口。
 *
 * 全辖同一类事只准一套说法、取哪一条线只准走一个地方：某笔支出在申请注明那一刻
 * 该按哪条线判、线册一条够不着时给不给结论、几条线顺位相同听谁的、双口径摆在一处
 * 取哪一头——这四问的口径统一收在求值入口 {@link #resolveLine(Date)} 与
 * {@link #checkSpend(SpendCheckRequest)}，入口之外不留第二道判得出来的门。
 * 线代号与启用之日这些结论字段，最终以求值入口的回话为准。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITHmfSpendLineService {

    /** 按主键查询规则 */
    THmfSpendLine selectTHmfSpendLineById(Long id);

    /**
     * 单规则求值：按 ruleCode 在 at 时刻生效的版本判档（1..4）。
     * 无生效版本 / 输入越界 / 参数缺失一律返回 0。
     */
    int evaluate(String ruleCode, BigDecimal input, Date at);

    /** 多规则求值：at 时刻可用规则中优先级最高者的档位；无可用规则返回 0 */
    int evaluateTop(BigDecimal input, Date at);

    /** at 时刻可用规则（优先级降序、ruleCode 降序） */
    List<THmfSpendLine> listAvailable(Date at);

    /** 单条规则在 at 时刻是否可用（批量导入/报表等旁路复用，口径须与定位/列表一致） */
    boolean usable(Long id, Date at);

    /** at 时刻可用规则条数（列表页角标 / 看板） */
    int countAvailable(Date at);

    /**
     * 查线的路子（新方法名，与旧的按代号求值并列）：按回看时刻定位唯一说话的那条线。
     *
     * 在场＝启用之日够得着（eff_start &lt;= at）、交棒之日还没到（eff_end 为空按长期在场认，
     * 兼容只有启用之日的存量老账；非空则 at &lt; eff_end，交棒当天旧线退出、新线当天进来），
     * 且情形为现行、未删。并列在场时让线顺位大的说话，顺位也相同取代号靠后的；
     * 随机那一套不认，两次算给回两条不同的线即算错。一条也够不着时不硬凑：
     * 回话 available=false、代号与启用之日两栏空着，写明“无可用线”，不拿邻近那条凑。
     */
    SpendLineVerdict resolveLine(Date at);

    /**
     * 立一条线：线名代号齐、启用之日有、三界必填、三界非负且低界&lt;中界&lt;高界、
     * 交棒日晚于启用日，一样不合都不落库；回执逐栏写清哪栏空着、哪栏越界、哪两栏次序不对。
     */
    SpendLineSaveReceipt saveSpendLine(THmfSpendLine line);

    /**
     * 审一笔维修开支（四问收口的求值入口）：按请求注明那一刻回看在场线说话，
     * 单笔三界与按建面摊的顶两头各自不越才算过，越了把那一头的数亮出来；
     * 无可用线不给结论。线代号与启用之日随结论压死，在场名单同回给出。
     */
    SpendCheckReceipt checkSpend(SpendCheckRequest request);
}
