package com.fc.v2.hmf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fc.v2.model.auto.THmfAcctCard;
import com.fc.v2.model.auto.THmfSpendLine;
import com.fc.v2.model.custom.SpendCheckReceipt;
import com.fc.v2.model.custom.SpendCheckRequest;
import com.fc.v2.model.custom.SpendLineSaveReceipt;
import com.fc.v2.model.custom.SpendLineVerdict;
import com.fc.v2.service.impl.THmfSpendLineServiceImpl;

/**
 * 《支出审定额线口径》逐条核对：
 * 册子栏位、立线门口（缺栏/负数/越界/次序，回执逐栏点明）、存量老账长期在场、
 * 指定刻回看（启用够得着、交棒没到；交棒当天旧出新进）、补算不取最新、
 * 让线顺位大→代号靠后、确定性、无可用线空结论不给数、已交棒不进现行、
 * 双口径各自不越才过且越了亮数、结论压死代号与启用之日、降档销警各留各的、
 * 在场名单与逐条判定同回一致。
 */
class SpendLineEvalTest {

    private THmfSpendLineServiceImpl service;
    private FakeSpendLineMapper lines;
    private FakeAcctCardMapper cards;

    private final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @BeforeEach
    void setUp() throws Exception {
        this.service = new THmfSpendLineServiceImpl();
        this.lines = new FakeSpendLineMapper();
        this.cards = new FakeAcctCardMapper();
        inject("hmfSpendLineMapper", lines);
        inject("hmfAcctCardMapper", cards);
    }

    private void inject(String name, Object value) throws Exception {
        Field fd = THmfSpendLineServiceImpl.class.getDeclaredField(name);
        fd.setAccessible(true);
        fd.set(service, value);
    }

    private Date d(String s) {
        try {
            return f.parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private THmfSpendLine line(String code, String name, String t1, String t2, String t3,
                               String start, String end, int priority, int status) {
        THmfSpendLine r = new THmfSpendLine();
        r.setRuleCode(code);
        r.setRuleName(name);
        r.setTh1Max(new BigDecimal(t1));
        r.setTh2Max(new BigDecimal(t2));
        r.setTh3Max(new BigDecimal(t3));
        r.setEffStart(d(start));
        r.setEffEnd(end == null ? null : d(end));
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(0);
        lines.all().add(r);
        return r;
    }

    private THmfAcctCard card(String billNo, int version, String siteNo, String qty) {
        THmfAcctCard c = new THmfAcctCard();
        c.setBillNo(billNo);
        c.setNodeNo(version);
        c.setSiteNo(siteNo);
        c.setQty(new BigDecimal(qty));
        c.setDelFlag(0);
        cards.all().add(c);
        return c;
    }

    private SpendCheckRequest req(String at, String siteNo, String amount) {
        SpendCheckRequest q = new SpendCheckRequest();
        q.setAt(d(at));
        q.setSiteNo(siteNo);
        q.setAmount(new BigDecimal(amount));
        return q;
    }

    // ---- 立线门口：三界必填、非负、递增；回执逐栏点明 ----

    @Test
    void save_missingThresholdRejectedAndBlankFieldNamed() {
        THmfSpendLine in = new THmfSpendLine();
        in.setRuleCode("L01");
        in.setRuleName("一线");
        in.setEffStart(d("2026-01-01 00:00:00"));
        in.setTh1Max(new BigDecimal("100"));
        in.setTh3Max(new BigDecimal("1000"));
        // 中界空着
        SpendLineSaveReceipt rc = service.saveSpendLine(in);
        assertFalse(rc.isAccepted());
        assertNull(rc.getLine());
        assertEquals(1, rc.getBlankFields().size());
        assertEquals("th2_max", rc.getBlankFields().get(0).getColumn());
        assertTrue(lines.all().isEmpty(), "一样不合都不落库");
        assertTrue(rc.getMessage().contains("中界"), "回执得写明哪一栏空着，一句“数不像话”不算交代");
    }

    @Test
    void save_negativeThresholdRejectedWithColumnValueAndRange() {
        // 去年那两条填成负数的，没人拦照样保存照样算——今年门口挡住。
        THmfSpendLine in = new THmfSpendLine();
        in.setRuleCode("L01");
        in.setRuleName("一线");
        in.setEffStart(d("2026-01-01 00:00:00"));
        in.setTh1Max(new BigDecimal("100"));
        in.setTh2Max(new BigDecimal("-500"));
        in.setTh3Max(new BigDecimal("1000"));
        SpendLineSaveReceipt rc = service.saveSpendLine(in);
        assertFalse(rc.isAccepted());
        assertEquals(1, rc.getRangeFaults().size());
        SpendLineSaveReceipt.RangeFault fault = rc.getRangeFaults().get(0);
        assertEquals("th2_max", fault.getColumn());
        assertEquals("-500.00", fault.getValue());
        assertTrue(fault.getRange().contains("0"), "回执写明许进范围");
        assertTrue(rc.getMessage().contains("越界"));
        assertTrue(lines.all().isEmpty());
    }

    @Test
    void save_thresholdsNotAscendingRejectedWithOrderFault() {
        THmfSpendLine in = new THmfSpendLine();
        in.setRuleCode("L01");
        in.setRuleName("一线");
        in.setEffStart(d("2026-01-01 00:00:00"));
        in.setTh1Max(new BigDecimal("100"));
        in.setTh2Max(new BigDecimal("1000"));
        in.setTh3Max(new BigDecimal("1000")); // 中界=高界，不严格递增
        SpendLineSaveReceipt rc = service.saveSpendLine(in);
        assertFalse(rc.isAccepted());
        assertEquals(1, rc.getOrderFaults().size());
        assertTrue(lines.all().isEmpty());
    }

    @Test
    void save_effEndNotAfterEffStartRejected() {
        THmfSpendLine in = new THmfSpendLine();
        in.setRuleCode("L01");
        in.setRuleName("一线");
        in.setEffStart(d("2026-06-01 00:00:00"));
        in.setEffEnd(d("2026-05-01 00:00:00"));
        in.setTh1Max(new BigDecimal("100"));
        in.setTh2Max(new BigDecimal("500"));
        in.setTh3Max(new BigDecimal("1000"));
        SpendLineSaveReceipt rc = service.saveSpendLine(in);
        assertFalse(rc.isAccepted());
        assertEquals(1, rc.getOrderFaults().size());
    }

    @Test
    void save_validLineAcceptedNullEffEndMeansLongPresent() {
        THmfSpendLine in = new THmfSpendLine();
        in.setRuleCode("L01");
        in.setRuleName("一线");
        in.setEffStart(d("2026-01-01 00:00:00"));
        // 交棒之日不填：按长期在场认
        in.setTh1Max(new BigDecimal("100"));
        in.setTh2Max(new BigDecimal("500"));
        in.setTh3Max(new BigDecimal("1000"));
        SpendLineSaveReceipt rc = service.saveSpendLine(in);
        assertTrue(rc.isAccepted());
        assertNotNull(rc.getLine());
        assertEquals(1, lines.all().size());
        assertNull(rc.getLine().getEffEnd());
        assertEquals(Integer.valueOf(0), rc.getLine().getStatus());
        assertEquals(Integer.valueOf(0), rc.getLine().getPriority());
        SpendLineVerdict v = service.resolveLine(d("2099-01-01 00:00:00"));
        assertTrue(v.isAvailable(), "没填交棒之日的，远期也在场");
        assertEquals("L01", v.getRuleCode());
    }

    // ---- 在场窗口与交棒当日 ----

    @Test
    void present_startInclusiveEndExclusive_handoverDayOldOutNewIn() {
        line("OLD", "旧线", "100", "500", "1000",
                "2025-01-01 00:00:00", "2026-01-01 00:00:00", 1, 0);
        line("NEW", "新线", "100", "500", "1000",
                "2026-01-01 00:00:00", null, 1, 0);

        // 交棒前一刻：旧线说话
        assertEquals("OLD", service.resolveLine(d("2025-12-31 23:59:59")).getRuleCode());
        // 交棒当天 00:00:00：旧的当天退出、新的当天进来——同一件事两个地方问答案一模一样
        Date handover = d("2026-01-01 00:00:00");
        SpendLineVerdict v1 = service.resolveLine(handover);
        SpendLineVerdict v2 = service.resolveLine(handover);
        assertEquals("NEW", v1.getRuleCode());
        assertEquals("NEW", v2.getRuleCode());
        assertEquals(v1.getEffStart(), v2.getEffStart());
        assertEquals(1, service.listAvailable(handover).size(), "交接当日只有新线在场");
    }

    @Test
    void backfill_pastMomentUsesLinePresentThenNotNewest() {
        // 去年八月报的那笔，补算照去年下半年在场的旧线，不许拿年中新立的新线顶上去。
        line("OLD", "旧线", "100", "500", "1000",
                "2025-01-01 00:00:00", "2026-01-01 00:00:00", 1, 0);
        line("NEW", "新线", "100", "500", "9000",
                "2026-01-01 00:00:00", null, 1, 0);

        SpendCheckReceipt rc = service.checkSpend(req("2025-08-15 10:00:00", "JZ00", "800"));
        assertEquals("OLD", rc.getRuleCode());
        // 十一月复议同理：仍在旧线窗口里（去年十一月 < 2026-01-01）
        assertEquals("OLD", service.checkSpend(req("2025-11-15 10:00:00", "JZ00", "800")).getRuleCode());
        // 今年再问就是新线
        assertEquals("NEW", service.checkSpend(req("2026-03-01 10:00:00", "JZ00", "800")).getRuleCode());
    }

    @Test
    void tie_priorityHigherSpeaksThenCodeLaterAndDeterministic() {
        line("A", "甲线", "100", "500", "1000", "2026-01-01 00:00:00", null, 1, 0);
        line("B", "乙线", "100", "500", "1000", "2026-01-01 00:00:00", null, 2, 0);
        Date at = d("2026-02-01 00:00:00");
        assertEquals("B", service.resolveLine(at).getRuleCode(), "顺位大的说话");

        lines.all().clear();
        line("L01", "甲线", "100", "500", "1000", "2026-01-01 00:00:00", null, 5, 0);
        line("L02", "乙线", "100", "500", "1000", "2026-01-01 00:00:00", null, 5, 0);
        SpendLineVerdict first = service.resolveLine(at);
        SpendLineVerdict second = service.resolveLine(at);
        assertEquals("L02", first.getRuleCode(), "顺位相同取代号靠后的");
        assertEquals(first.getRuleCode(), second.getRuleCode(), "随机那一套不认，两次必须同一条");
        assertEquals(first.getEffStart(), second.getEffStart());
    }

    @Test
    void noLineReachable_blankVerdictNoConclusion() {
        line("FUTURE", "还没启用", "100", "500", "1000",
                "2026-06-01 00:00:00", null, 1, 0);
        line("PAST", "已交棒", "100", "500", "1000",
                "2025-01-01 00:00:00", "2025-12-31 00:00:00", 9, 0);
        Date at = d("2026-03-01 00:00:00");

        SpendLineVerdict v = service.resolveLine(at);
        assertFalse(v.isAvailable());
        assertNull(v.getRuleCode(), "代号栏空着");
        assertNull(v.getEffStart(), "启用之日栏空着");
        assertNull(v.getLine());

        SpendCheckReceipt rc = service.checkSpend(req("2026-03-01 00:00:00", "JZ00", "800"));
        assertEquals(SpendCheckReceipt.CODE_NO_LINE, rc.getCode());
        assertFalse(rc.isPass());
        assertNull(rc.getRuleCode());
        assertNull(rc.getEffStart());
        assertTrue(rc.getMessage().contains("无可用线"));
        assertEquals(0, rc.getPresent().size());
        assertEquals(0, service.countAvailable(at));
    }

    @Test
    void retiredAndDeletedLinesNeverSpeak() {
        THmfSpendLine retired = line("RET", "已停用", "100", "500", "1000",
                "2026-01-01 00:00:00", null, 9, 1); // status=已交棒，窗口还开着也不碰
        THmfSpendLine deleted = line("DEL", "删了", "100", "500", "1000",
                "2026-01-01 00:00:00", null, 9, 0);
        deleted.setDelFlag(1);
        line("RUN", "现行", "100", "500", "1000",
                "2026-01-01 00:00:00", null, 1, 0);
        Date at = d("2026-03-01 00:00:00");
        assertEquals("RUN", service.resolveLine(at).getRuleCode());
        assertFalse(service.usable(retired.getId(), at), "已交棒的不进现行计算，档里留着给人翻");
        assertFalse(service.usable(deleted.getId(), at));
        assertEquals(1, service.countAvailable(at));
    }

    // ---- 双口径：单笔界 + 按建面摊，各自不越才过，越了亮数 ----

    @Test
    void check_bothGaugesWithinPassesAndPinsLineFields() {
        line("L01", "定额线", "100", "1000", "100000",
                "2026-01-01 00:00:00", null, 1, 0);
        card("HU01", 1, "JZ00", "60.00");
        card("HU02", 1, "JZ00", "40.00"); // 建面合计 100 平方米
        SpendCheckReceipt rc = service.checkSpend(req("2026-03-01 00:00:00", "JZ00", "50000"));
        assertEquals(SpendCheckReceipt.CODE_PASS, rc.getCode());
        assertTrue(rc.isPass());
        assertFalse(rc.isAmountBreach());
        assertFalse(rc.isAreaBreach());
        assertEquals(Integer.valueOf(3), rc.getAmountTier(), "50000 在中界1000与高界100000之间，落高界档");
        assertEquals(0, new BigDecimal("100.00").compareTo(rc.getAreaTotal()));
        assertEquals(0, new BigDecimal("500.00").compareTo(rc.getAreaRate()),
                "50000/100=500 元/平方米，在高界 100000 以内");
        assertFalse(rc.isAreaBreach());
        assertEquals("L01", rc.getRuleCode());
        assertEquals(d("2026-01-01 00:00:00"), rc.getEffStart());
    }

    @Test
    void check_amountOverHighBreachesAmountGaugeAndShowsNumbers() {
        line("L01", "定额线", "100", "1000", "100000",
                "2026-01-01 00:00:00", null, 1, 0);
        card("HU01", 1, "JZ00", "10000.00"); // 面积够大，摊头不越
        SpendCheckReceipt rc = service.checkSpend(req("2026-03-01 00:00:00", "JZ00", "150000"));
        assertEquals(SpendCheckReceipt.CODE_FAIL, rc.getCode());
        assertFalse(rc.isPass());
        assertTrue(rc.isAmountBreach(), "单笔这一头越了");
        assertEquals(Integer.valueOf(4), rc.getAmountTier());
        assertEquals(0, new BigDecimal("100000.00").compareTo(rc.getAmountCap()));
        assertEquals(0, new BigDecimal("50000.00").compareTo(rc.getAmountOver()), "超出的数亮出来");
        assertFalse(rc.isAreaBreach(), "建面摊那一头没越，各留各的");
        assertTrue(rc.getMessage().contains("单笔这一头越了"));
    }

    @Test
    void check_areaProrationOverHighBreachesAreaGaugeOnly() {
        line("L01", "定额线", "100", "1000", "100000",
                "2026-01-01 00:00:00", null, 1, 0);
        card("HU01", 1, "JZ00", "0.40"); // 建面合计 0.4 平方米
        // 单笔 50000 ≤ 高界 100000；摊 50000/0.4=125000 > 100000，只建面摊这头越
        SpendCheckReceipt rc = service.checkSpend(req("2026-03-01 00:00:00", "JZ00", "50000"));
        assertEquals(SpendCheckReceipt.CODE_FAIL, rc.getCode());
        assertFalse(rc.isAmountBreach(), "单笔头没越");
        assertTrue(rc.isAreaBreach(), "按建面摊这一头越了");
        assertEquals(0, new BigDecimal("0.40").compareTo(rc.getAreaTotal()));
        assertEquals(0, new BigDecimal("100000.00").compareTo(rc.getAreaCap()));
        assertEquals(0, new BigDecimal("25000.00").compareTo(rc.getAreaOver()),
                "每平方米摊125000，超高界25000");
        assertTrue(rc.getMessage().contains("按建面摊"));
    }

    @Test
    void check_areaUnresolvedFailsWithoutFabricating() {
        line("L01", "定额线", "100", "1000", "100000",
                "2026-01-01 00:00:00", null, 1, 0);
        // JZ99 一张立户单都没有
        SpendCheckReceipt rc = service.checkSpend(req("2026-03-01 00:00:00", "JZ99", "50000"));
        assertEquals(SpendCheckReceipt.CODE_FAIL, rc.getCode());
        assertTrue(rc.isAreaUnresolved());
        assertFalse(rc.isPass(), "建面点不出来，不许硬凑一个面积让它过");
    }

    @Test
    void check_latestCardVersionCountsTowardArea() {
        line("L01", "定额线", "100", "1000", "100000",
                "2026-01-01 00:00:00", null, 1, 0);
        // 同一立户单两版：只认版次序最新那版（旧版 60，新版 90）
        card("HU01", 1, "JZ00", "60.00");
        card("HU01", 2, "JZ00", "90.00");
        SpendCheckReceipt rc = service.checkSpend(req("2026-03-01 00:00:00", "JZ00", "50000"));
        assertEquals(0, new BigDecimal("90.00").compareTo(rc.getAreaTotal()));
        assertTrue(rc.isPass());
    }

    @Test
    void check_badInputRejected() {
        assertEquals(SpendCheckReceipt.CODE_BAD_INPUT,
                service.checkSpend(req("2026-03-01 00:00:00", "JZ00", "0")).getCode());
        assertEquals(SpendCheckReceipt.CODE_BAD_INPUT,
                service.checkSpend(req("2026-03-01 00:00:00", "JZ00", "-5")).getCode());
        SpendCheckRequest noAt = req("2026-03-01 00:00:00", "JZ00", "100");
        noAt.setAt(null);
        assertEquals(SpendCheckReceipt.CODE_BAD_INPUT, service.checkSpend(noAt).getCode());
        SpendCheckRequest noSite = req("2026-03-01 00:00:00", "JZ00", "100");
        noSite.setSiteNo("  ");
        assertEquals(SpendCheckReceipt.CODE_BAD_INPUT, service.checkSpend(noSite).getCode());
    }

    // ---- 降档/销警两笔账：两趟回话互不残留；名单与判定同回一致 ----

    @Test
    void receiptsKeepTwoGaugesSeparateAcrossCalls() {
        line("L01", "定额线", "100", "1000", "100000",
                "2026-01-01 00:00:00", null, 1, 0);
        card("BIG", 1, "JZ00", "10000.00");
        card("TINY", 1, "JZ01", "0.40");
        Date at = d("2026-03-01 00:00:00");

        SpendCheckReceipt first = service.checkSpend(
                req("2026-03-01 00:00:00", "JZ00", "150000")); // 单笔头越
        SpendCheckReceipt second = service.checkSpend(
                req("2026-03-01 00:00:00", "JZ01", "50000"));  // 摊头越
        assertTrue(first.isAmountBreach());
        assertFalse(first.isAreaBreach(), "先前那头的提示不许带到下一趟");
        assertFalse(second.isAmountBreach());
        assertTrue(second.isAreaBreach());
        assertEquals(at, second.getAt());
    }

    @Test
    void presentListMatchesPerLineJudgementInSameShape() {
        THmfSpendLine a = line("A", "甲", "100", "500", "1000",
                "2026-01-01 00:00:00", null, 1, 0);
        THmfSpendLine b = line("B", "乙", "100", "500", "1000",
                "2026-01-01 00:00:00", null, 3, 0);
        THmfSpendLine c = line("C", "丙", "100", "500", "1000",
                "2026-01-01 00:00:00", null, 3, 0);
        line("OLD", "旧", "100", "500", "1000",
                "2025-01-01 00:00:00", "2025-12-31 00:00:00", 9, 0);
        Date at = d("2026-03-01 00:00:00");

        List<THmfSpendLine> listed = service.listAvailable(at);
        SpendCheckReceipt rc = service.checkSpend(req("2026-03-01 00:00:00", "JZ00", "100"));

        // 屏上名单与逐条判出来的名单同一回口径：条数一致、次序一致
        assertEquals(listed.size(), rc.getPresent().size());
        for (int i = 0; i < listed.size(); i++) {
            assertEquals(listed.get(i).getId(), rc.getPresent().get(i).getId());
        }
        assertEquals(3, listed.size());
        // 让线次序：顺位3 且代号靠后的 C 打头
        assertEquals(c.getId(), listed.get(0).getId());
        assertEquals(b.getId(), listed.get(1).getId());
        assertEquals(a.getId(), listed.get(2).getId());
        // 说话的就是名单头一条
        assertEquals("C", rc.getRuleCode());
        assertEquals(listed.get(0).getRuleCode(), rc.getRuleCode());
        // 角标与名单同数
        assertEquals(listed.size(), service.countAvailable(at));
    }

    // ---- 旧方法原样留着 ----

    @Test
    void legacyEvaluateByRuleCodeStillWorksUnchanged() {
        line("L01", "定额线", "100", "500", "1000",
                "2026-01-01 00:00:00", null, 1, 0);
        assertEquals(1, service.evaluate("L01", new BigDecimal("100"), d("2026-03-01 00:00:00")),
                "等于低界取第一档");
        assertEquals(3, service.evaluate("L01", new BigDecimal("1000"), d("2026-03-01 00:00:00")));
        assertEquals(4, service.evaluate("L01", new BigDecimal("1001"), d("2026-03-01 00:00:00")));
        assertEquals(0, service.evaluate("L01", new BigDecimal("1"), null));
        assertEquals(0, service.evaluate("NONE", new BigDecimal("1"), d("2026-03-01 00:00:00")));
    }

    @Test
    void evaluateTopNowSpeaksByPresentWindow() {
        line("OLD", "旧", "100", "500", "1000",
                "2025-01-01 00:00:00", "2026-01-01 00:00:00", 9, 0);
        line("NEW", "新", "100", "5000", "9000",
                "2026-01-01 00:00:00", null, 1, 0);
        // 旧线高界 1000：去年问 800 落第 3 档
        assertEquals(3, service.evaluateTop(new BigDecimal("800"), d("2025-08-01 00:00:00")));
        // 今年问 800：新线 500~5000 落第 2 档
        assertEquals(2, service.evaluateTop(new BigDecimal("800"), d("2026-03-01 00:00:00")));
        assertEquals(0, service.evaluateTop(new BigDecimal("-1"), d("2026-03-01 00:00:00")));
        assertEquals(0, service.evaluateTop(new BigDecimal("800"), d("2024-01-01 00:00:00")));
    }
}
