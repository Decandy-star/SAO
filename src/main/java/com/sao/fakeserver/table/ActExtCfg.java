package com.sao.fakeserver.table;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sao.fakeserver.config.SaoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 活动扩展表：目录/每日累计充值(轮换)/神域魔盒 的配置与池。
 * <ul>
 *   <li>activities.json：看板 titleList（enabled=false 过滤）；缺文件时退回原硬编码全部目录。</li>
 *   <li>act-daily-pay.json：type4 A/B 每日轮换档位；heroFragment 每天首次访问预 roll 定档，当天固定。</li>
 *   <li>act-exchange.json：看板 type23 圣诞/新年兑换档。</li>
 * </ul>
 */
@Component
public class ActExtCfg {
    private static final Logger log = LoggerFactory.getLogger(ActExtCfg.class);
    private static final String TITLES_FILE = "activities.json";
    private static final String DAYPAY_FILE = "act-daily-pay.json";
    private static final String DAYCOST_FILE = "act-daily-cost.json";
    private static final String MAGIC_FILE = "magicbox.json";
    private static final String FIRST_CHARGE_FILE = "act-first-charge.json";
    private static final String EXCHANGE_FILE = "act-exchange.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SaoProperties props;
    private final EconomyTables economy;
    private final CultivateTables cultivate;

    private List<ActivityTables.TitleSpec> titles = new ArrayList<>();
    private DayPayFile dayPay = DayPayFile.fallback();
    /** 消耗返利(type6)档位；复用同构结构（rmb 字段=钻石消耗门槛）。 */
    private DayPayFile dayCost = DayPayFile.costFallback();
    private MagicFile magic = MagicFile.fallback();
    private FirstChargeFile firstCharge = FirstChargeFile.fallback();
    private final Map<Integer, ExchangeTab> exchangeTabs = new HashMap<>();

    /** 魔盒品质候选：quality(3/4/5) -> 候选。 */
    private final Map<Integer, List<MbCand>> mbPool = new HashMap<>();

    public ActExtCfg(SaoProperties props, EconomyTables economy, CultivateTables cultivate) {
        this.props = props;
        this.economy = economy;
        this.cultivate = cultivate;
    }

    @PostConstruct
    public void init() {
        Path dir = Paths.get(props.getTablesDir());
        loadTitles(dir.resolve(TITLES_FILE));
        loadDayPay(dir.resolve(DAYPAY_FILE));
        loadDayCost(dir.resolve(DAYCOST_FILE));
        loadMagic(dir.resolve(MAGIC_FILE));
        loadFirstCharge(dir.resolve(FIRST_CHARGE_FILE));
        loadExchange(dir.resolve(EXCHANGE_FILE));
        buildMbPool();
        log.info("act-ext titles={} dayPaySchemes={} dayCostSchemes={} firstChargeTiers={} magicGoods={} exchangeTabs={} mbPool3/4/5={}/{}/{}",
                titles.size(), dayPay.schemes.size(), dayCost.schemes.size(),
                firstCharge.tiers == null ? 0 : firstCharge.tiers.size(), magic.goodsOri,
                Integer.valueOf(exchangeTabs.size()),
                poolSize(3), poolSize(4), poolSize(5));
    }

    private int poolSize(int q) {
        List<MbCand> l = mbPool.get(q);
        return l == null ? 0 : l.size();
    }

    // ---------- activities.json 目录 ----------

    private void loadTitles(Path file) {
        if (!Files.isRegularFile(file)) {
            titles = fallbackTitles();
            log.warn("missing {}, all activity tabs on", file);
            return;
        }
        try {
            TitlesFile f = mapper.readValue(file.toFile(), TitlesFile.class);
            List<ActivityTables.TitleSpec> out = new ArrayList<>();
            if (f != null && f.titles != null) {
                for (TitleRow r : f.titles) {
                    if (r == null || r.type < 0) {
                        continue;
                    }
                    if (!r.enabled) {
                        continue;
                    }
                    ActivityTables.TitleSpec s = new ActivityTables.TitleSpec();
                    s.type = r.type;
                    s.subId = r.subID;
                    s.name = r.name == null || r.name.isEmpty() ? "活动" : r.name;
                    out.add(s);
                }
            }
            titles = out.isEmpty() ? fallbackTitles() : out;
        } catch (IOException e) {
            titles = fallbackTitles();
            log.warn("read {} failed: {}", file, e.toString());
        }
    }

    private static List<ActivityTables.TitleSpec> fallbackTitles() {
        List<ActivityTables.TitleSpec> out = new ArrayList<>();
        out.add(t(1, 0, "免费吃大餐"));
        out.add(t(0, 0, "登录送壕礼"));
        out.add(t(2, 0, "首冲大回馈"));
        out.add(t(10, 0, "至尊福利"));
        out.add(t(4, 0, "壕送大礼"));
        out.add(t(5, 0, "VIP特权礼包"));
        out.add(t(3, 0, "限时送好礼"));
        out.add(t(9, 0, "单冲返利"));
        out.add(t(6, 0, "消耗返利"));
        out.add(t(11, 1, "累计充值"));
        out.add(t(12, 1, "积天返利"));
        out.add(t(7, 1, "全服礼包"));
        out.add(t(8, 1, "限时兑换"));
        out.add(t(15, 1, "英雄兑换"));
        out.add(t(17, 1, "可选奖励"));
        out.add(t(16, 1, "可选消费"));
        out.add(t(18, 1, "限购送礼"));
        out.add(t(19, 1, "好礼总动员"));
        out.add(t(13, 1, "消耗排行"));
        out.add(t(14, 1, "充值排行"));
        out.add(t(21, 1, "累计消耗"));
        out.add(t(22, 0, "神域魔盒"));
        out.add(t(23, 1, "圣诞兑换"));
        out.add(t(23, 2, "新年兑换"));
        out.add(t(24, 0, "开服基金"));
        out.add(t(25, 0, "等级奖励"));
        return out;
    }

    private static ActivityTables.TitleSpec t(int type, int subId, String name) {
        ActivityTables.TitleSpec s = new ActivityTables.TitleSpec();
        s.type = type;
        s.subId = subId;
        s.name = name;
        return s;
    }

    /** 看板目录（已过滤 enabled）。 */
    public List<ActivityTables.TitleSpec> titles() {
        return new ArrayList<>(titles);
    }

    // ---------- act-exchange.json 看板 type23 ----------

    private void loadExchange(Path file) {
        exchangeTabs.clear();
        ExchangeFile parsed = null;
        if (Files.isRegularFile(file)) {
            try {
                parsed = mapper.readValue(file.toFile(), ExchangeFile.class);
            } catch (IOException e) {
                log.warn("read {} failed: {}", file, e.toString());
            }
        } else {
            log.warn("missing {}, type23 fallback tabs", file);
        }
        List<ExchangeTab> tabs = parsed != null && parsed.tabs != null && !parsed.tabs.isEmpty()
                ? parsed.tabs : ExchangeFile.fallback().tabs;
        for (ExchangeTab tab : tabs) {
            if (tab == null || tab.subID <= 0) {
                continue;
            }
            List<ExchangeRow> ok = new ArrayList<>();
            if (tab.rows != null) {
                for (ExchangeRow row : tab.rows) {
                    if (acceptExchangeRow(row)) {
                        ok.add(row);
                    }
                }
            }
            tab.rows = ok;
            exchangeTabs.put(Integer.valueOf(tab.subID), tab);
        }
    }

    private boolean acceptExchangeRow(ExchangeRow row) {
        if (row == null || row.id <= 0 || row.getOri == null || row.getOri.isEmpty() || row.getCount <= 0) {
            return false;
        }
        if (economy.goods(row.getOri) == null) {
            log.warn("act-exchange drop getOri not in GoodsList: {}", row.getOri);
            return false;
        }
        if (row.materials == null || row.materials.isEmpty()) {
            return false;
        }
        List<ExchangeMat> mats = new ArrayList<>();
        for (ExchangeMat m : row.materials) {
            if (m == null || m.ori == null || m.ori.isEmpty() || m.count <= 0) {
                continue;
            }
            if (economy.goods(m.ori) == null) {
                log.warn("act-exchange drop material not in GoodsList: {}", m.ori);
                continue;
            }
            mats.add(m);
        }
        if (mats.isEmpty()) {
            return false;
        }
        row.materials = mats;
        return true;
    }

    public ExchangeTab exchangeTab(int subId) {
        return exchangeTabs.get(Integer.valueOf(subId));
    }

    public ExchangeRow exchangeRow(int subId, int id) {
        ExchangeTab tab = exchangeTab(subId);
        if (tab == null || tab.rows == null) {
            return null;
        }
        for (ExchangeRow r : tab.rows) {
            if (r != null && r.id == id) {
                return r;
            }
        }
        return null;
    }

    // ---------- act-daily-cost.json 消耗返利(type6) ----------

    private void loadDayCost(Path file) {
        if (!Files.isRegularFile(file)) {
            dayCost = DayPayFile.costFallback();
            log.warn("missing {}, type6 fallback tiers", file);
            return;
        }
        try {
            DayPayFile f = mapper.readValue(file.toFile(), DayPayFile.class);
            if (f != null && f.schemes != null && !f.schemes.isEmpty()) {
                dayCost = f;
            }
        } catch (IOException e) {
            log.warn("read {} failed: {}", file, e.toString());
        }
    }

    public DayPayFile dayCost() {
        return dayCost;
    }

    /** 消耗返利当天生效方案（单套固定档位）。 */
    public DayPayScheme activeCostScheme(LocalDate date) {
        if (dayCost.schemes == null || dayCost.schemes.isEmpty()) {
            return null;
        }
        return dayCost.schemes.get(0);
    }

    // ---------- act-daily-pay.json 每日累计充值 ----------

    private void loadDayPay(Path file) {
        if (!Files.isRegularFile(file)) {
            dayPay = DayPayFile.fallback();
            log.warn("missing {}, type4 fallback tiers", file);
            return;
        }
        try {
            DayPayFile f = mapper.readValue(file.toFile(), DayPayFile.class);
            if (f != null && f.schemes != null && !f.schemes.isEmpty()) {
                dayPay = f;
            }
        } catch (IOException e) {
            log.warn("read {} failed: {}", file, e.toString());
        }
    }

    public DayPayFile dayPay() {
        return dayPay;
    }

    /** 当天生效方案：自然日序号 % cycleDays 选第几套。 */
    public DayPayScheme activeScheme(LocalDate date) {
        if (dayPay.schemes == null || dayPay.schemes.isEmpty()) {
            return null;
        }
        int cycle = Math.max(1, dayPay.cycleDays);
        int idx = (int) Math.floorMod(date.toEpochDay(), cycle);
        if (idx >= dayPay.schemes.size()) {
            idx = 0;
        }
        return dayPay.schemes.get(idx);
    }

    /** 随机一名武将，返回其碎片 ori；minStar>0 时只取合成初始星级 ≥ minStar 的武将（如 A 套“三星以上”）。 */
    public String rollHeroFragmentOri(Random rng) {
        return rollHeroFragmentOri(rng, 0);
    }

    public String rollHeroFragmentOri(Random rng, int minStar) {
        Collection<CultivateTables.HeroCfg> heroes = cultivate.allHeroes();
        List<CultivateTables.HeroCfg> list = new ArrayList<>();
        for (CultivateTables.HeroCfg h : heroes) {
            if (h == null || !cultivate.isPlayableHero(h.index)) {
                continue;
            }
            if (minStar > 0 && h.composeStar < minStar) {
                continue;
            }
            list.add(h);
        }
        if (list.isEmpty()) {
            return "SP048";
        }
        CultivateTables.HeroCfg pick = list.get(rng.nextInt(list.size()));
        return pick.fragmentOri;
    }

    // ---------- act-first-charge.json 首冲大回馈 ----------

    private void loadFirstCharge(Path file) {
        if (!Files.isRegularFile(file)) {
            firstCharge = FirstChargeFile.fallback();
            log.warn("missing {}, firstCharge fallback tiers", file);
            return;
        }
        try {
            FirstChargeFile f = mapper.readValue(file.toFile(), FirstChargeFile.class);
            if (f != null && f.tiers != null && !f.tiers.isEmpty()) {
                firstCharge = f;
            }
        } catch (IOException e) {
            firstCharge = FirstChargeFile.fallback();
            log.warn("read {} failed: {}", file, e.toString());
        }
    }

    public List<FirstChargeTier> firstChargeTiers() {
        return firstCharge.tiers == null ? new ArrayList<>() : new ArrayList<>(firstCharge.tiers);
    }

    // ---------- magicbox.json 神域魔盒 ----------

    private void loadMagic(Path file) {
        if (!Files.isRegularFile(file)) {
            magic = MagicFile.fallback();
            log.warn("missing {}, magicbox fallback params", file);
            return;
        }
        try {
            MagicFile f = mapper.readValue(file.toFile(), MagicFile.class);
            if (f != null && f.grids != null && !f.grids.isEmpty()) {
                magic = f;
            }
        } catch (IOException e) {
            log.warn("read {} failed: {}", file, e.toString());
        }
    }

    public MagicFile magic() {
        return magic;
    }

    /** 从两张表扫出「有品质即入池」的候选：物品(type3，排除双倍卡/喇叭)+装备(type1)。 */
    private void buildMbPool() {
        mbPool.clear();
        for (int q = 1; q <= 5; q++) {
            mbPool.put(q, new ArrayList<>());
        }
        for (EconomyTables.GoodsCfg g : economy.allGoods()) {
            if (g == null || g.ori == null || g.ori.isEmpty() || g.quality < 1 || g.quality > 5) {
                continue;
            }
            // 12 喇叭 / 14 双倍充值卡 不入抽奖池
            if (g.attrType == 12 || g.attrType == 14) {
                continue;
            }
            if ("0".equals(g.ori)) {
                continue;
            }
            mbPool.get(g.quality).add(new MbCand(g.ori, 3, g.quality));
        }
        for (CultivateTables.EquipCfg e : cultivate.allEquips()) {
            if (e == null || e.ori == null || e.ori.isEmpty() || e.quality < 1 || e.quality > 5) {
                continue;
            }
            mbPool.get(e.quality).add(new MbCand(e.ori, 1, e.quality));
        }
    }

    /** 某品质随机抽一件（找不到回落上一品质，再无则蓝装 BX128）。 */
    public MbCand rollMbCand(int quality, Random rng) {
        int actual = quality;
        List<MbCand> pool = mbPool.get(quality);
        if (pool == null || pool.isEmpty()) {
            for (int q = quality - 1; q >= 1; q--) {
                pool = mbPool.get(q);
                if (pool != null && !pool.isEmpty()) {
                    actual = q;
                    break;
                }
            }
        }
        if (pool == null || pool.isEmpty()) {
            return new MbCand("BX128", 3, 3);
        }
        return pool.get(rng.nextInt(pool.size()));
    }

    /** 魔盒格子奖品候选。 */
    public static final class MbCand {
        public final String ori;
        /** 1 装备 / 3 物品。 */
        public final int type;
        /** 实际品质（1-5，rollMbCand 回落后仍填真实值）。 */
        public final int quality;

        public MbCand(String ori, int type, int quality) {
            this.ori = ori;
            this.type = type;
            this.quality = quality;
        }
    }

    // ---------- json 模型 ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TitlesFile {
        public List<TitleRow> titles = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TitleRow {
        public int type;
        public int subID;
        public String name = "";
        public boolean enabled = true;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FirstChargeFile {
        public List<FirstChargeTier> tiers = new ArrayList<>();

        static FirstChargeFile fallback() {
            FirstChargeFile f = new FirstChargeFile();
            f.tiers.add(fcTier(6, 100000, 60, fcGoods("ZBSX01", 20, false), fcGoods("GOODS3", 10, true)));
            f.tiers.add(fcTier(30, 300000, 300, fcGoods("BX128", 2, false)));
            f.tiers.add(fcTier(98, 800000, 980, fcGoods("TS507", 1, true), fcGoods("PY001", 1000, false)));
            return f;
        }

        private static FirstChargeTier fcTier(int je, int jinbi, int rmb, FirstChargeGoods... goods) {
            FirstChargeTier t = new FirstChargeTier();
            t.mJE = je;
            t.mJinBi = jinbi;
            t.mRmb = rmb;
            if (goods != null) {
                for (FirstChargeGoods g : goods) {
                    t.goods.add(g);
                }
            }
            return t;
        }

        private static FirstChargeGoods fcGoods(String ori, int count, boolean shining) {
            FirstChargeGoods g = new FirstChargeGoods();
            g.ori = ori;
            g.count = count;
            g.shining = shining;
            return g;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FirstChargeTier {
        public int mJE;
        public int mJinBi;
        public int mRmb;
        public List<FirstChargeGoods> goods = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FirstChargeGoods {
        public String ori = "";
        public int count;
        public int stars;
        public boolean shining;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DayPayFile {
        public int cycleDays = 2;
        public List<DayPayScheme> schemes = new ArrayList<>();

        static DayPayFile fallback() {
            DayPayFile d = new DayPayFile();
            d.cycleDays = 2;
            DayPayScheme a = new DayPayScheme();
            a.id = "A";
            a.name = "壕送大礼";
            a.tiers.add(tier(6, 0, 400000, item("ZBSX01", 20)));
            a.tiers.add(tier(30, 0, 480000, item("BX128", 2)));
            a.tiers.add(tier(96, 0, 800000, heroFragment(15)));
            a.tiers.add(tier(200, 0, 800000, item("TS507", 1), item("PY001", 7000)));
            DayPayScheme b = new DayPayScheme();
            b.id = "B";
            b.name = "每日充值·魔瓶版";
            b.tiers.add(tier(6, 0, 0, item("SYMH01", 20), heroFragment(10)));
            b.tiers.add(tier(30, 0, 0, item("SYMH01", 40), heroFragment(15)));
            b.tiers.add(tier(120, 0, 0, item("SYMH01", 80), heroFragment(25)));
            d.schemes.add(a);
            d.schemes.add(b);
            return d;
        }

        /** 消耗返利(type6)缺配置文件兜底：档位 rmb=当日钻石消耗门槛。 */
        static DayPayFile costFallback() {
            DayPayFile d = new DayPayFile();
            d.cycleDays = 1;
            DayPayScheme s = new DayPayScheme();
            s.id = "cost";
            s.name = "消耗返利";
            s.tiers.add(tier(2000, 0, 500000, item("PY001", 500)));
            s.tiers.add(tier(5000, 0, 700000, item("PY001", 800)));
            s.tiers.add(tier(10000, 0, 1000000, item("PY001", 1000)));
            s.tiers.add(tier(15000, 0, 1500000, item("ZBSX01", 800), item("PY001", 1200)));
            s.tiers.add(tier(20000, 0, 2000000, item("ZBSX01", 1500), item("PY001", 1500)));
            s.tiers.add(tier(30000, 0, 3000000, item("ZBSX02", 200), item("PY001", 1800)));
            d.schemes.add(s);
            return d;
        }

        private static PayTier tier(int rmb, int zuanShi, int jinbi, PayItem... items) {
            PayTier t = new PayTier();
            t.rmb = rmb;
            t.zuanShiAward = zuanShi;
            t.jinbiAward = jinbi;
            for (PayItem it : items) {
                t.items.add(it);
            }
            return t;
        }

        private static PayItem item(String ori, int count) {
            PayItem it = new PayItem();
            it.ori = ori;
            it.count = count;
            return it;
        }

        private static PayItem heroFragment(int count) {
            PayItem it = new PayItem();
            it.heroFragment = true;
            it.count = count;
            return it;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DayPayScheme {
        public String id = "";
        public String name = "";
        public List<PayTier> tiers = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PayTier {
        /** 档位门槛 RMB。 */
        public int rmb;
        /** 钻石奖励（items 满 3 个时客户端可能省略展示，仍会发）。 */
        public int zuanShiAward;
        /** 金币奖励。 */
        public int jinbiAward;
        /** 物品奖励（heroFragment=true 为随机英雄碎片，现场 roll）。 */
        public List<PayItem> items = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PayItem {
        public String ori = "";
        public int count;
        public boolean heroFragment;
        public boolean shining;
        /** heroFragment=true 时的星级下限（0=全部武将，3=A 套 96 元档“三星以上”）。 */
        public int minStar;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MagicFile {
        /** 单抽消耗道具 ori。 */
        public String goodsOri = "SYMH01";
        /** 魔瓶不足时钻石兜底。 */
        public int diamondCost = 200;
        /** 第一次消耗数。 */
        public int costGoodsBase = 1;
        /** 每次是否翻倍。 */
        public boolean doublePerDraw = true;
        /** 重置一组所需钻石（0=免费）。 */
        public int resetCostDiamond = 0;
        /** 9 格配置，顺序即生成顺序：先金再紫再蓝。 */
        public Map<String, MbGridCfg> grids = new HashMap<>();
        /** 中奖记录保留条数。 */
        public int recordKeep = 20;
        /** 跨日是否自动换新一组。 */
        public boolean refreshDaily = true;

        static MagicFile fallback() {
            MagicFile m = new MagicFile();
            m.goodsOri = "SYMH01";
            m.diamondCost = 200;
            m.costGoodsBase = 1;
            m.doublePerDraw = true;
            m.resetCostDiamond = 0;
            m.grids.put("gold", grid(1, 5, 1, 3));
            m.grids.put("purple", grid(7, 4, 1, 2));
            m.grids.put("blue", grid(1, 3, 1, 3));
            return m;
        }

        private static MbGridCfg grid(int count, int quality, int min, int max) {
            MbGridCfg c = new MbGridCfg();
            c.count = count;
            c.quality = quality;
            c.countMin = min;
            c.countMax = max;
            return c;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MbGridCfg {
        public int count;
        public int quality;
        public int countMin = 1;
        public int countMax = 1;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExchangeFile {
        public List<ExchangeTab> tabs = new ArrayList<>();

        static ExchangeFile fallback() {
            ExchangeFile f = new ExchangeFile();
            ExchangeTab xmas = tab(1, "圣诞兑换", "用材料换冬季礼物。");
            xmas.rows.add(row(1, "GOODS104", 20, mat("GOODS111", 10)));
            xmas.rows.add(row(2, "GOODS112", 5, mat("GOODS111", 25)));
            f.tabs.add(xmas);
            ExchangeTab nye = tab(2, "新年兑换", "用材料换新春礼物。");
            nye.rows.add(row(1, "GOODS104", 20, mat("GOODS111", 10)));
            nye.rows.add(row(2, "BX237", 1, mat("PY001", 8)));
            f.tabs.add(nye);
            return f;
        }

        private static ExchangeTab tab(int sub, String title, String desc) {
            ExchangeTab t = new ExchangeTab();
            t.subID = sub;
            t.title = title;
            t.desc = desc;
            return t;
        }

        private static ExchangeRow row(int id, String ori, int n, ExchangeMat... mats) {
            ExchangeRow r = new ExchangeRow();
            r.id = id;
            r.getOri = ori;
            r.getCount = n;
            if (mats != null) {
                for (ExchangeMat m : mats) {
                    r.materials.add(m);
                }
            }
            return r;
        }

        private static ExchangeMat mat(String ori, int n) {
            ExchangeMat m = new ExchangeMat();
            m.ori = ori;
            m.count = n;
            return m;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExchangeTab {
        public int subID;
        public String title = "";
        public String desc = "";
        public List<ExchangeRow> rows = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExchangeRow {
        public int id;
        public String getOri = "";
        public int getCount;
        public List<ExchangeMat> materials = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExchangeMat {
        public String ori = "";
        public int count;
    }
}
