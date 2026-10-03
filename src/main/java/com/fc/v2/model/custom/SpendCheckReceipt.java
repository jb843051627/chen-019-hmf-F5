package com.fc.v2.model.custom;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fc.v2.model.auto.THmfSpendLine;

/**
 * 审一笔维修开支一趟的唯一回话（双口径同一回计算给出）。
 *
 * 两头各自都不越才算过：单笔那三条界判出档（1..3 在界内，高出高界即这一头越了）；
 * 另有按建面摊的线（小区分户账立户单最新版建面合计 × 高界）管着，本笔高过摊出来的顶
 * 即这一头越了。哪一头越了就把哪一头的数亮出来（顶是多少、本笔多少、超出多少），
 * 不并成一句含糊话。
 *
 * 降档与销警是两笔账，各留各的：单笔判档与建面摊顶在本回话分两栏写，
 * 回话只含这一趟算出来的东西，先前那一头的提示不许留在页面上不走。
 * 结论带着线代号与启用之日一起压死，往后换了线也不改这一段旧事。
 * 屏上那份在场名单与逐条判出来的名单同一回计算给出（{@link #present}），两处数字对得齐。
 *
 * @author fuce
 * @date 2026-10-03
 */
public class SpendCheckReceipt implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 请求本身不像话（回看时刻/底册/金额缺或不正），未进判定 */
    public static final int CODE_BAD_INPUT = 0;
    /** 线册里一条都够不着：不给结论，代号与启用之日两栏空着 */
    public static final int CODE_NO_LINE = 1;
    /** 两头各自都不越：过 */
    public static final int CODE_PASS = 2;
    /** 线在，但单笔界或建面摊顶有一头越了：不过，越的那头把数亮出 */
    public static final int CODE_FAIL = 3;

    /** 回话情形码，见本类常量 */
    private int code;

    /** 申请注明的回看时刻 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date at;

    /** 所属小区底册代号 */
    private String siteNo;

    /** 本笔开支金额 */
    private BigDecimal amount;

    // ---- 压死的线结论（无可用线时两栏空着）----

    /** 判定所听线的代号 */
    private String ruleCode;

    /** 判定所听线的启用之日（结论压死字段） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date effStart;

    /** 线名（给柜员看） */
    private String ruleName;

    // ---- 单笔口径（一笔记档）----

    /** 单笔落在哪一档 1..3；高出高界这一头越了时为 4 */
    private Integer amountTier;

    /** 单笔这一头越没越（本笔高过高界） */
    private boolean amountBreach;

    /** 单笔高界（越了亮出的顶） */
    private BigDecimal amountCap;

    /** 单笔超出高界多少（没越为 0） */
    private BigDecimal amountOver;

    // ---- 按建面摊口径（一笔销警）----

    /** 该小区分户账立户单最新版建筑面积合计（平方米） */
    private BigDecimal areaTotal;

    /** 本笔摊到每平方米的数 = 本笔金额 / 建面合计（元/平方米，算到分） */
    private BigDecimal areaRate;

    /** 这一头的顶：线高界（元/平方米）；每平方米摊额高过它即越 */
    private BigDecimal areaCap;

    /** 建面摊这一头越没越（本笔高过摊出来的顶） */
    private boolean areaBreach;

    /** 超出摊顶多少（没越为 0） */
    private BigDecimal areaOver;

    /** 建面合计点不出来（无在册立户单），这一头无从判，不算过 */
    private boolean areaUnresolved;

    /** 两头各自都不越 */
    private boolean pass;

    /** 同一回计算给出的在场名单（让线顺位降序、代号降序），屏上名单与此同源 */
    private List<THmfSpendLine> present = new ArrayList<>();

    /** 给柜员看的那句话 */
    private String message;

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public Date getAt() {
        return at;
    }

    public void setAt(Date at) {
        this.at = at;
    }

    public String getSiteNo() {
        return siteNo;
    }

    public void setSiteNo(String siteNo) {
        this.siteNo = siteNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
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

    public Integer getAmountTier() {
        return amountTier;
    }

    public void setAmountTier(Integer amountTier) {
        this.amountTier = amountTier;
    }

    public boolean isAmountBreach() {
        return amountBreach;
    }

    public void setAmountBreach(boolean amountBreach) {
        this.amountBreach = amountBreach;
    }

    public BigDecimal getAmountCap() {
        return amountCap;
    }

    public void setAmountCap(BigDecimal amountCap) {
        this.amountCap = amountCap;
    }

    public BigDecimal getAmountOver() {
        return amountOver;
    }

    public void setAmountOver(BigDecimal amountOver) {
        this.amountOver = amountOver;
    }

    public BigDecimal getAreaTotal() {
        return areaTotal;
    }

    public void setAreaTotal(BigDecimal areaTotal) {
        this.areaTotal = areaTotal;
    }

    public BigDecimal getAreaRate() {
        return areaRate;
    }

    public void setAreaRate(BigDecimal areaRate) {
        this.areaRate = areaRate;
    }

    public BigDecimal getAreaCap() {
        return areaCap;
    }

    public void setAreaCap(BigDecimal areaCap) {
        this.areaCap = areaCap;
    }

    public boolean isAreaBreach() {
        return areaBreach;
    }

    public void setAreaBreach(boolean areaBreach) {
        this.areaBreach = areaBreach;
    }

    public BigDecimal getAreaOver() {
        return areaOver;
    }

    public void setAreaOver(BigDecimal areaOver) {
        this.areaOver = areaOver;
    }

    public boolean isAreaUnresolved() {
        return areaUnresolved;
    }

    public void setAreaUnresolved(boolean areaUnresolved) {
        this.areaUnresolved = areaUnresolved;
    }

    public boolean isPass() {
        return pass;
    }

    public void setPass(boolean pass) {
        this.pass = pass;
    }

    public List<THmfSpendLine> getPresent() {
        return present;
    }

    public void setPresent(List<THmfSpendLine> present) {
        this.present = present;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
