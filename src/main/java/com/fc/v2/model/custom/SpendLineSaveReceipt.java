package com.fc.v2.model.custom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fc.v2.model.auto.THmfSpendLine;

/**
 * 立一条审定线进门的回执。
 *
 * 三条分界数必须填，缺一项起不来；取值范围之外的数今年不收（去年那两条负数没人拦、
 * 照样保存照样算的旧账，今年在门口挡住）。回执必须写清哪一栏空着、哪一栏越界、
 * 哪两栏次序不对，只回一句“数不像话”不算交代。一样不合都不落库。
 *
 * @author fuce
 * @date 2026-10-03
 */
public class SpendLineSaveReceipt implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 空着的栏（列名 + 中文名） */
    public static class BlankField implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String column;
        private final String label;

        public BlankField(String column, String label) {
            this.column = column;
            this.label = label;
        }

        public String getColumn() {
            return column;
        }

        public String getLabel() {
            return label;
        }
    }

    /** 越界的栏：栏名、填进来的数、许进的范围 */
    public static class RangeFault implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String column;
        private final String label;
        private final String value;
        private final String range;

        public RangeFault(String column, String label, String value, String range) {
            this.column = column;
            this.label = label;
            this.value = value;
            this.range = range;
        }

        public String getColumn() {
            return column;
        }

        public String getLabel() {
            return label;
        }

        public String getValue() {
            return value;
        }

        public String getRange() {
            return range;
        }
    }

    /** 收不收：true 三界齐、都在范围内、次序对，已落库 */
    private boolean accepted;

    /** 落库后的线（不收时为 null） */
    private THmfSpendLine line;

    /** 哪一栏空着 */
    private final List<BlankField> blankFields = new ArrayList<>();

    /** 哪一栏越界（含负数） */
    private final List<RangeFault> rangeFaults = new ArrayList<>();

    /** 次序不对的说明（低界须小于中界、中界须小于高界、交棒日须晚于启用日） */
    private final List<String> orderFaults = new ArrayList<>();

    /** 给柜员看的那句话：逐栏点明，不拿一句“数不像话”打发 */
    private String message;

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public THmfSpendLine getLine() {
        return line;
    }

    public void setLine(THmfSpendLine line) {
        this.line = line;
    }

    public List<BlankField> getBlankFields() {
        return blankFields;
    }

    public List<RangeFault> getRangeFaults() {
        return rangeFaults;
    }

    public List<String> getOrderFaults() {
        return orderFaults;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
