package com.fc.v2.model.custom;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 审一笔维修开支的进门请求：申请注明那一刻（at）、所属小区底册、单笔金额。
 *
 * 回看时刻以申请注明的那一刻为准，不许顺手拿最新立的线顶上去。
 * 所属底册用于点该小区分户账立户单最新版建筑面积合计（按建面摊那一口的底数）。
 *
 * @author fuce
 * @date 2026-10-03
 */
public class SpendCheckRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 申请注明那一刻（回看时刻，必填） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date at;

    /** 所属小区底册代号（按建面摊照此点建面合计，必填） */
    private String siteNo;

    /** 本笔开支金额（单笔口径，必填，正数） */
    private BigDecimal amount;

    public SpendCheckRequest() {
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
}
