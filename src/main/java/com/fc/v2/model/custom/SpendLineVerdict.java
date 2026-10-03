package com.fc.v2.model.custom;

import java.io.Serializable;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fc.v2.model.auto.THmfSpendLine;

/**
 * 审定线求值入口的唯一回话：某一刻该听哪条线，只由服务层求值入口这一处裁。
 *
 * 线代号与启用之日这些结论字段最终以本回话为准；页面不许先贴一个答案再等系统补签名。
 * 一条也够不着时不硬凑：{@link #available} 为 false，线代号与启用之日两栏空着，
 * 不拿邻近那条的数凑。结论压死在回话上——回话带出的是那一刻选中那条的快照，
 * 往后线换了、交棒了，这一段旧事不改。
 *
 * @author fuce
 * @date 2026-10-03
 */
public class SpendLineVerdict implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 这一刻有没有够得着的在场线（false=无可用线） */
    private boolean available;

    /** 选中的线代号；无可用线时空着 */
    private String ruleCode;

    /** 选中线的启用之日；无可用线时空着（结论压死字段，旧账回看照此钉住） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date effStart;

    /** 线名（随结论带回给柜员看，无可用线时空着） */
    private String ruleName;

    /** 选中线的让线顺位（无可用线时为 null） */
    private Integer priority;

    /** 选中线那一刻的整线快照（三界与结论同源，后续判档只照这一份，不再回库另取） */
    private THmfSpendLine line;

    public SpendLineVerdict() {
    }

    /** 无可用线：代号与启用之日两栏空着，不许拿邻近那条凑。 */
    public static SpendLineVerdict none() {
        return new SpendLineVerdict();
    }

    /** 在场取中：把代号与启用之日压死在回话上。 */
    public static SpendLineVerdict of(THmfSpendLine r) {
        SpendLineVerdict v = new SpendLineVerdict();
        v.available = true;
        v.ruleCode = r.getRuleCode();
        v.effStart = r.getEffStart();
        v.ruleName = r.getRuleName();
        v.priority = r.getPriority();
        v.line = r;
        return v;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public Date getEffStart() {
        return effStart;
    }

    public void setEffStart(Date effStart) {
        this.effStart = effStart;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public THmfSpendLine getLine() {
        return line;
    }

    public void setLine(THmfSpendLine line) {
        this.line = line;
    }
}
