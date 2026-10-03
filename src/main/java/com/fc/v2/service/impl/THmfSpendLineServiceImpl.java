package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.THmfAcctCardMapper;
import com.fc.v2.mapper.auto.THmfSpendLineMapper;
import com.fc.v2.model.auto.THmfAcctCard;
import com.fc.v2.model.auto.THmfSpendLine;
import com.fc.v2.model.custom.SpendCheckReceipt;
import com.fc.v2.model.custom.SpendCheckRequest;
import com.fc.v2.model.custom.SpendLineSaveReceipt;
import com.fc.v2.model.custom.SpendLineVerdict;
import com.fc.v2.service.ITHmfSpendLineService;

/**
 * 支出审定额线 Service业务层处理。
 *
 * 全辖同一类事只准一套说法、取哪一条线只准走一个地方：
 * 在场认定、让线裁夺、无可用线回话、双口径取哪一头，都在本类同一处规矩
 * （{@link #presentLines(Date)} ＋ {@link #resolveLine(Date)} ＋ {@link #checkSpend}）里，
 * 入口之外不留第二道判得出来的门。屏上在场名单、角标计数、逐条判定都照同一份名单给，
 * 两处数字对得齐。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class THmfSpendLineServiceImpl implements ITHmfSpendLineService {

    /** 线的情形：现行（在场候选） */
    private static final int STATUS_ACTIVE = 0;
    /** 线的情形：已停用（已交棒，档里留着给人翻，现行这一路不碰它） */
    private static final int STATUS_RETIRED = 1;

    /** 三界金额库口：decimal(12,2)，今年只收 [0, 9999999999.99] 里的数（负数在门口挡住） */
    private static final BigDecimal LIMIT_MIN = BigDecimal.ZERO;
    private static final BigDecimal LIMIT_MAX = new BigDecimal("9999999999.99");

    /** 建面摊额算到分 */
    private static final int MONEY_SCALE = 2;

    /** 让线顺位缺省值（存量老账没填顺位也不至于没法比） */
    private static final int PRIORITY_MISSING = 0;

    /**
     * 唯一让线次序：让线顺位大的先说话；顺位相同取代号靠后的（字典序大者）；
     * 再相同取启用之日靠后的、主键靠后的兜底。随机那一套不认，同一回看刻两回算必须同一条。
     */
    private static final Comparator<THmfSpendLine> SPEAK_ORDER = new Comparator<THmfSpendLine>() {
        @Override
        public int compare(THmfSpendLine a, THmfSpendLine b) {
            int pa = a.getPriority() == null ? PRIORITY_MISSING : a.getPriority();
            int pb = b.getPriority() == null ? PRIORITY_MISSING : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            int byCode = cb.compareTo(ca);
            if (byCode != 0) {
                return byCode;
            }
            Date sa = a.getEffStart();
            Date sb = b.getEffStart();
            if (sa == null && sb != null) {
                return -1;
            }
            if (sa != null && sb == null) {
                return 1;
            }
            if (sa != null && sb != null && !sa.equals(sb)) {
                return sb.compareTo(sa);
            }
            Long ia = a.getId() == null ? 0L : a.getId();
            Long ib = b.getId() == null ? 0L : b.getId();
            return ib.compareTo(ia);
        }
    };

    @javax.annotation.Resource
    private THmfSpendLineMapper hmfSpendLineMapper;

    @javax.annotation.Resource
    private THmfAcctCardMapper hmfAcctCardMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）+ 字符串绑定：
     * JDBC 的 serverTimezone 与本机时区不对称，直接把 java.util.Date 作参数会整体偏移，
     * 使边界判定在"切换当日"这类用例上随机错档（实测踩到）。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public THmfSpendLine selectTHmfSpendLineById(Long id) {
        return this.hmfSpendLineMapper.selectById(id);
    }

    // ---------------------------------------------------------------- 旧读法（按代号求值一字不改）

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        THmfSpendLine rule = findRule(ruleCode, at);
        if (rule == null) {
            return 0;
        }
        return levelOf(rule, input);
    }

    // ---------------------------------------------------------------- 唯一在场名单（同一回计算）

    @Override
    public List<THmfSpendLine> listAvailable(Date at) {
        return presentLines(at);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0) {
            return 0;
        }
        List<THmfSpendLine> avail = presentLines(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        THmfSpendLine r = this.hmfSpendLineMapper.selectById(id);
        return r != null && isPresent(r, at);
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        return presentLines(at).size();
    }

    // ---------------------------------------------------------------- 查线的路子（新方法名）

    @Override
    public SpendLineVerdict resolveLine(Date at) {
        if (at == null) {
            return SpendLineVerdict.none();
        }
        List<THmfSpendLine> present = presentLines(at);
        if (present.isEmpty()) {
            // 一条也够不着：不硬凑，代号与启用之日两栏空着，不拿邻近那条的数凑。
            return SpendLineVerdict.none();
        }
        return SpendLineVerdict.of(present.get(0));
    }

    // ---------------------------------------------------------------- 立线（门口校验）

    @Override
    public SpendLineSaveReceipt saveSpendLine(THmfSpendLine in) {
        SpendLineSaveReceipt receipt = new SpendLineSaveReceipt();
        if (in == null) {
            receipt.setMessage("空册子，一条线也没落");
            return receipt;
        }

        // 一、缺栏：线名、代号、启用之日与三条分界数必须填（交棒之日可空——长期在场）。
        if (isBlank(in.getRuleCode())) {
            receipt.getBlankFields().add(new SpendLineSaveReceipt.BlankField("rule_code", "线代号"));
        }
        if (isBlank(in.getRuleName())) {
            receipt.getBlankFields().add(new SpendLineSaveReceipt.BlankField("rule_name", "线名"));
        }
        if (in.getEffStart() == null) {
            receipt.getBlankFields().add(new SpendLineSaveReceipt.BlankField("eff_start", "启用之日"));
        }
        BigDecimal th1 = in.getTh1Max();
        BigDecimal th2 = in.getTh2Max();
        BigDecimal th3 = in.getTh3Max();
        if (th1 == null) {
            receipt.getBlankFields().add(new SpendLineSaveReceipt.BlankField("th1_max", "低界"));
        }
        if (th2 == null) {
            receipt.getBlankFields().add(new SpendLineSaveReceipt.BlankField("th2_max", "中界"));
        }
        if (th3 == null) {
            receipt.getBlankFields().add(new SpendLineSaveReceipt.BlankField("th3_max", "高界"));
        }

        // 二、越界：取值范围之外的数今年不收（去年那两条负数没人拦，今年在门口挡住）。
        // 只对填了的栏判越界，缺栏不重复算账。
        checkRange(receipt, "th1_max", "低界", th1);
        checkRange(receipt, "th2_max", "中界", th2);
        checkRange(receipt, "th3_max", "高界", th3);

        // 三、次序：三条分界数从低到高，严格递增；交棒之日要晚于启用之日。
        if (th1 != null && th2 != null && th1.compareTo(th2) >= 0) {
            receipt.getOrderFaults().add("低界(" + money(th1) + ")须小于中界(" + money(th2) + ")");
        }
        if (th2 != null && th3 != null && th2.compareTo(th3) >= 0) {
            receipt.getOrderFaults().add("中界(" + money(th2) + ")须小于高界(" + money(th3) + ")");
        }
        if (in.getEffStart() != null && in.getEffEnd() != null
                && !in.getEffEnd().after(in.getEffStart())) {
            receipt.getOrderFaults().add("交棒之日(" + ts(in.getEffEnd())
                    + ")须晚于启用之日(" + ts(in.getEffStart()) + ")");
        }

        if (!receipt.getBlankFields().isEmpty() || !receipt.getRangeFaults().isEmpty()
                || !receipt.getOrderFaults().isEmpty()) {
            receipt.setMessage(buildSaveFaultMessage(receipt));
            return receipt;
        }

        THmfSpendLine r = new THmfSpendLine();
        r.setRuleCode(in.getRuleCode().trim());
        r.setRuleName(in.getRuleName().trim());
        r.setTh1Max(money(th1));
        r.setTh2Max(money(th2));
        r.setTh3Max(money(th3));
        r.setEffStart(in.getEffStart());
        r.setEffEnd(in.getEffEnd());
        r.setPriority(in.getPriority() == null ? PRIORITY_MISSING : in.getPriority());
        r.setStatus(in.getStatus() == null ? STATUS_ACTIVE : in.getStatus());
        r.setDelFlag(0);
        r.setRemark(in.getRemark());
        Date now = new Date();
        r.setCreateTime(now);
        r.setUpdateTime(now);
        this.hmfSpendLineMapper.insert(r);

        receipt.setAccepted(true);
        receipt.setLine(r);
        receipt.setMessage("线[" + r.getRuleCode() + "「" + r.getRuleName() + "」]已立，启用之日"
                + ts(r.getEffStart())
                + (r.getEffEnd() == null ? "，未填交棒之日按长期在场认" : "，交棒之日" + ts(r.getEffEnd())));
        return receipt;
    }

    // ---------------------------------------------------------------- 审一笔维修开支（四问收口）

    @Override
    public SpendCheckReceipt checkSpend(SpendCheckRequest request) {
        SpendCheckReceipt rc = new SpendCheckReceipt();
        if (request == null) {
            rc.setCode(SpendCheckReceipt.CODE_BAD_INPUT);
            rc.setMessage("空请求，申请注明那一刻、所属小区与本笔金额三样都要");
            return rc;
        }
        rc.setAt(request.getAt());
        rc.setSiteNo(request.getSiteNo());
        rc.setAmount(request.getAmount());

        Date at = request.getAt();
        String siteNo = request.getSiteNo();
        BigDecimal amount = request.getAmount();
        if (at == null || isBlank(siteNo) || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            rc.setCode(SpendCheckReceipt.CODE_BAD_INPUT);
            List<String> miss = new ArrayList<>();
            if (at == null) {
                miss.add("申请注明那一刻");
            }
            if (isBlank(siteNo)) {
                miss.add("所属小区底册代号");
            }
            if (amount == null) {
                miss.add("本笔开支金额");
            } else if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                miss.add("本笔金额得是正数（递来" + money(amount) + "）");
            }
            rc.setMessage("进门的数不像话：" + String.join("、", miss) + "；未进判定");
            return rc;
        }
        String site = siteNo.trim();
        rc.setSiteNo(site);

        // 名单与判定同一回计算：先把这一刻在场名单一次点出，说话的只取头一条；
        // 屏上名单照同一份回，不许另一处再点一遍点出两样。
        List<THmfSpendLine> present = presentLines(at);
        rc.setPresent(present);
        if (present.isEmpty()) {
            rc.setCode(SpendCheckReceipt.CODE_NO_LINE);
            // 无可用线：代号与启用之日两栏空着，不给结论，不拿邻近那条凑。
            rc.setMessage("申请注明那一刻(" + ts(at) + ")线册里一条都够不着：无可用线，不给审定结论");
            return rc;
        }
        THmfSpendLine line = present.get(0);
        rc.setRuleCode(line.getRuleCode());
        rc.setEffStart(line.getEffStart());
        rc.setRuleName(line.getRuleName());

        // 口径头一：单笔那三条界。等于高界不算越；高出高界即这一头越了。
        BigDecimal cap = line.getTh3Max();
        int tier = levelOf(line, amount);
        rc.setAmountTier(tier);
        rc.setAmountCap(cap);
        if (tier == 4) {
            rc.setAmountBreach(true);
            rc.setAmountOver(money(amount.subtract(cap)));
        } else {
            rc.setAmountOver(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }

        // 口径头二：同一条线按建面摊——本笔摊到该小区在册分户账立户单最新版建面合计上，
        // 每平方米摊额高过高界即这一头越了。两头各自不越才算过。
        BigDecimal areaTotal = siteAreaTotal(site);
        if (areaTotal == null || areaTotal.compareTo(BigDecimal.ZERO) <= 0) {
            // 建面点不出来，这一头无从判：不许硬凑一个面积让它过，也不替它销警。
            rc.setAreaUnresolved(true);
            rc.setCode(SpendCheckReceipt.CODE_FAIL);
            rc.setPass(false);
            rc.setMessage("小区" + site + "在册分户账建面合计点不出来，按建面摊这一头无从判，本笔不算过；"
                    + "听线" + line.getRuleCode() + "（启用之日" + ts(line.getEffStart()) + "）");
            return rc;
        }
        rc.setAreaTotal(money(areaTotal));
        rc.setAreaCap(cap);
        BigDecimal perSquare = money(amount.divide(areaTotal, MONEY_SCALE, RoundingMode.HALF_UP));
        rc.setAreaRate(perSquare);
        if (perSquare.compareTo(cap) > 0) {
            rc.setAreaBreach(true);
            rc.setAreaOver(money(perSquare.subtract(cap)));
        } else {
            rc.setAreaOver(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }

        // 降档（单笔头）与销警（建面摊头）是两笔账，各留各的；这一趟回话只带这一趟的两栏，
        // 先前那一头的提示不留。
        boolean pass = !rc.isAmountBreach() && !rc.isAreaBreach();
        rc.setPass(pass);
        rc.setCode(pass ? SpendCheckReceipt.CODE_PASS : SpendCheckReceipt.CODE_FAIL);
        rc.setMessage(buildCheckMessage(rc, line, amount, perSquare));
        return rc;
    }

    // ---------------------------------------------------------------- 在场唯一算法

    /**
     * at 时刻在场名单（已按让线次序排好）：未删、情形为现行（status 空当老账现行认）、
     * 启用之日够得着（eff_start &lt;= at）、交棒之日还没到（eff_end 为空＝长期在场，
     * 兼容只有启用之日的存量老账；非空则 at &lt; eff_end——交棒当天旧线退出、新线当天进来）。
     * 已停用/已交棒的不进这一份，档里留着给人翻。
     */
    private List<THmfSpendLine> presentLines(Date at) {
        List<THmfSpendLine> all = this.hmfSpendLineMapper.selectList(
                new QueryWrapper<THmfSpendLine>().eq("del_flag", 0));
        List<THmfSpendLine> present = new ArrayList<>();
        for (THmfSpendLine r : all) {
            if (isPresent(r, at)) {
                present.add(r);
            }
        }
        present.sort(SPEAK_ORDER);
        return present;
    }

    /** 一条线在 at 时刻在不在场（名单、角标、单条可用性、说话裁夺同此一处口径）。 */
    private boolean isPresent(THmfSpendLine r, Date at) {
        if (r == null || at == null) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus() == STATUS_RETIRED) {
            // 已交棒的那条不进现行计算，档里留着给人翻，现行这一路不碰它。
            return false;
        }
        Date start = r.getEffStart();
        if (start == null || start.after(at)) {
            return false;
        }
        Date end = r.getEffEnd();
        // 交棒之日空着：存量老账按长期在场认，不能因为缺栏就把它当没有。
        return end == null || end.after(at);
    }

    // ---------------------------------------------------------------- 私有帮手

    /**
     * 点某小区在册分户账立户单的建筑面积合计：同一立户单号有多版时认版次序(node_no)最新那版，
     * 与册子核销认卡的口径同源；未删、建面为正的才进合计。
     */
    private BigDecimal siteAreaTotal(String siteNo) {
        List<THmfAcctCard> cards = this.hmfAcctCardMapper.selectList(new QueryWrapper<THmfAcctCard>()
                .eq("site_no", siteNo).eq("del_flag", 0));
        Map<String, THmfAcctCard> latestByBill = new HashMap<>();
        for (THmfAcctCard c : cards) {
            if (isBlank(c.getBillNo()) || c.getQty() == null
                    || c.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String bill = c.getBillNo().trim();
            THmfAcctCard old = latestByBill.get(bill);
            if (old == null || versionOf(c) > versionOf(old)) {
                latestByBill.put(bill, c);
            }
        }
        if (latestByBill.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (THmfAcctCard c : latestByBill.values()) {
            total = total.add(c.getQty());
        }
        return total;
    }

    private int versionOf(THmfAcctCard c) {
        return c.getNodeNo() == null ? 0 : c.getNodeNo();
    }

    private void checkRange(SpendLineSaveReceipt receipt, String column, String label, BigDecimal v) {
        if (v == null) {
            return; // 缺栏已在前面点过名，这里不重复算
        }
        if (v.compareTo(LIMIT_MIN) < 0 || v.compareTo(LIMIT_MAX) > 0) {
            receipt.getRangeFaults().add(new SpendLineSaveReceipt.RangeFault(column, label,
                    money(v).toPlainString(), LIMIT_MIN.toPlainString() + "～" + LIMIT_MAX.toPlainString()));
        }
    }

    private String buildSaveFaultMessage(SpendLineSaveReceipt receipt) {
        StringBuilder sb = new StringBuilder("这条线起不来：");
        List<String> parts = new ArrayList<>();
        if (!receipt.getBlankFields().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (SpendLineSaveReceipt.BlankField f : receipt.getBlankFields()) {
                names.add(f.getLabel() + "(" + f.getColumn() + ")");
            }
            parts.add("空着的栏：" + String.join("、", names));
        }
        if (!receipt.getRangeFaults().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (SpendLineSaveReceipt.RangeFault f : receipt.getRangeFaults()) {
                names.add(f.getLabel() + "(" + f.getColumn() + ")填的是" + f.getValue()
                        + "，许进范围" + f.getRange());
            }
            parts.add("越界的栏：" + String.join("；", names));
        }
        if (!receipt.getOrderFaults().isEmpty()) {
            parts.add("次序不对：" + String.join("；", receipt.getOrderFaults()));
        }
        sb.append(String.join("；", parts));
        return sb.toString();
    }

    private String buildCheckMessage(SpendCheckReceipt rc, THmfSpendLine line,
                                     BigDecimal amount, BigDecimal perSquare) {
        StringBuilder sb = new StringBuilder();
        sb.append(rc.isPass() ? "过：两头各自都不越。" : "不过：");
        if (rc.isAmountBreach()) {
            sb.append("单笔这一头越了——本笔").append(money(amount))
              .append("高过高界").append(money(rc.getAmountCap()))
              .append("，超出").append(money(rc.getAmountOver())).append("；");
        } else {
            sb.append("单笔").append(money(amount)).append("落第").append(rc.getAmountTier())
              .append("档（高界").append(money(rc.getAmountCap())).append("以内）；");
        }
        if (rc.isAreaBreach()) {
            sb.append("按建面摊这一头越了——摊到建面").append(money(rc.getAreaTotal()))
              .append("平方米合每平方米").append(money(perSquare))
              .append("，高过高界").append(money(rc.getAreaCap()))
              .append("，超出").append(money(rc.getAreaOver())).append("。");
        } else {
            sb.append("按建面摊每平方米").append(money(perSquare))
              .append("（建面合计").append(money(rc.getAreaTotal()))
              .append("平方米），在高界").append(money(rc.getAreaCap())).append("以内。");
        }
        sb.append("听线").append(line.getRuleCode())
          .append("（启用之日").append(ts(line.getEffStart())).append("）");
        return sb.toString();
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private THmfSpendLine findRule(String ruleCode, Date at) {
        List<THmfSpendLine> hit = this.hmfSpendLineMapper.selectList(new QueryWrapper<THmfSpendLine>()
                .eq("del_flag", 0).eq("rule_code", ruleCode));
        THmfSpendLine newest = null;
        for (THmfSpendLine r : hit) {
            if (newest == null || r.getEffEnd() == null) {
                newest = r;
            } else if (newest.getEffEnd() != null && r.getEffEnd().after(newest.getEffEnd())) {
                newest = r;
            }
        }
        return newest;
    }

    /** 分档：等于上限取高一档 */
    private int levelOf(THmfSpendLine rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) <= 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) <= 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
