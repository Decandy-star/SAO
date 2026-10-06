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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 限时神将（ltsj.json）配置与物品池。
 * <ul>
 *   <li>ltsj.json：价格/每日免费次数/字满阈值/四字/当期英雄排期（每天轮换下一人）/宝箱与抽取数量规则。</li>
 *   <li>物品池：直接扫 GoodsList，凡品质 3/4/5（蓝/紫/金）的非喇叭非双倍卡物品即入池（与魔盒候选同思路）。</li>
 * </ul>
 */
@Component
public class LtsjCfg {
    private static final Logger log = LoggerFactory.getLogger(LtsjCfg.class);
    private static final String FILE = "ltsj.json";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SaoProperties props;
    private final EconomyTables economy;

    private LtsjFile file = LtsjFile.fallback();

    /** 品质(3/4/5) → 物品候选 ori。 */
    private final Map<Integer, List<String>> goodsPool = new HashMap<>();

    public LtsjCfg(SaoProperties props, EconomyTables economy) {
        this.props = props;
        this.economy = economy;
    }

    @PostConstruct
    public void init() {
        load(Paths.get(props.getTablesDir()).resolve(FILE));
        buildGoodsPool();
        log.info("ltsj enabled={} costOnce={} costTen={} free={} wenZiMax={} heroes={} pool3/4/5={}/{}/{}",
                file.enabled, file.costOnce, file.costTen, file.freeTimesPerDay, file.wenZiMaxExp,
                file.heroes.size(), poolSize(3), poolSize(4), poolSize(5));
    }

    private int poolSize(int q) {
        List<String> l = goodsPool.get(q);
        return l == null ? 0 : l.size();
    }

    private void load(Path file) {
        if (!Files.isRegularFile(file)) {
            this.file = LtsjFile.fallback();
            log.warn("missing {}, ltsj fallback config", file);
            return;
        }
        try {
            LtsjFile f = mapper.readValue(file.toFile(), LtsjFile.class);
            if (f != null && f.heroes != null && !f.heroes.isEmpty()) {
                this.file = f;
            }
        } catch (IOException e) {
            log.warn("read {} failed: {}", file, e.toString());
        }
    }

    /**
     * 文字抽奖的「随机物品」池（品质 3-5）。排除口径与 {@code EconomyTables.buildBoxPools()}（随机箱产品池）一致：
     * 属性 10 随机箱 / 15 自选箱 / 6 / 12 喇叭 / 14 双倍充值卡、BX/WSJL/HTSP/HTJB/FBSGS 前缀的箱、
     * attrP1 非可玩武将的碎片、以及无属性道具 —— 否则会抽到「箱子里开箱子」和功能道具。
     */
    private void buildGoodsPool() {
        goodsPool.clear();
        for (int q = 3; q <= 5; q++) {
            goodsPool.put(q, new ArrayList<>());
        }
        for (EconomyTables.GoodsCfg g : economy.allGoods()) {
            if (g == null || g.ori == null || g.ori.isEmpty() || g.quality < 3 || g.quality > 5) {
                continue;
            }
            int a = g.attrType;
            if (a <= 0 || a == 6 || a == 10 || a == 12 || a == 14 || a == 15) {
                continue;
            }
            if (g.ori.startsWith("BX") || g.ori.startsWith("WSJL") || g.ori.startsWith("HTSP")
                    || g.ori.startsWith("HTJB") || g.ori.startsWith("FBSGS")) {
                continue;
            }
            if (a == 2 && (g.attrP1 <= 0 || g.attrP1 >= 1000)) {
                continue;
            }
            if ("0".equals(g.ori)) {
                continue;
            }
            goodsPool.get(g.quality).add(g.ori);
        }
    }

    public LtsjFile cfg() {
        return file;
    }

    public boolean enabled() {
        return file.enabled;
    }

    /**
     * 四格宝箱：{@code ori} 是领取时发放的宝箱道具本体（表依据：GoodsList 的
     * {@code BX191-194 七夕神将宝箱一~四}，客户端 prefab 名 {@code XianShi_5_20} 与「七夕」同名期），
     * {@code items} 是该箱在背包开启（C2S 3101）时产出的固定内容，见 {@link #boxItems(String)}。
     * 配置为空时 {@link com.sao.fakeserver.service.LtsjService} 回退到 boxQuality 随机 roll。
     */
    public List<LtsjFile.BoxRow> boxes() {
        return file.boxes;
    }

    /**
     * 宝箱原始名 → 开箱产出（{@code ltsj.json} 的 {@code boxes[].items}），供 C2S 3101 开箱用。
     * 未登记的箱（其它 BX*）返回空，调用方走随机池。
     */
    public List<LtsjFile.BoxItem> boxItems(String boxOri) {
        if (boxOri == null) {
            return Collections.emptyList();
        }
        for (LtsjFile.BoxRow b : file.boxes) {
            if (b != null && boxOri.equals(b.ori) && b.items != null && !b.items.isEmpty()) {
                return b.items;
            }
        }
        return Collections.emptyList();
    }

    public int activeId() {
        return file.activeId;
    }

    /** 当天生效的当期神将 index：heroes 数组按自然日序号轮换，长期循环。 */
    public int activeHeroIndex(LocalDate date) {
        List<LtsjFile.HeroRow> heroes = file.heroes;
        if (heroes.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.floorMod(date.toEpochDay(), heroes.size());
        LtsjFile.HeroRow h = heroes.get(idx);
        return h == null ? 0 : h.heroIndex;
    }

    /** 某品质随机一件物品；品质缺候选时回落到上一品质，再无则蓝品刷新球兜底。 */
    public String rollGoodsOri(int quality, Random rng) {
        List<String> pool = goodsPool.get(quality);
        if (pool == null || pool.isEmpty()) {
            for (int q = quality - 1; q >= 3; q--) {
                pool = goodsPool.get(q);
                if (pool != null && !pool.isEmpty()) {
                    quality = q;
                    break;
                }
            }
        }
        if (pool == null || pool.isEmpty()) {
            return "PY001";
        }
        return pool.get(rng.nextInt(pool.size()));
    }

    /** 品质数量区间（box=true 用宝箱四格规则，否则用抽取附送规则）。 */
    public int randCount(int quality, boolean box, Random rng) {
        Map<String, LtsjFile.Count> m = box ? file.boxCount : file.drawCount;
        LtsjFile.Count c = m == null ? null : m.get(String.valueOf(quality));
        int min = c == null || c.min < 1 ? 1 : c.min;
        int max = c == null || c.max < min ? min : c.max;
        if (max <= min) {
            return min;
        }
        return min + rng.nextInt(max - min + 1);
    }

    /** 抽取附送掉落品质（按 drawQuality 权重）。 */
    public int rollDrawQuality(Random rng) {
        int total = 0;
        for (LtsjFile.DrawWeight w : file.drawQuality) {
            total += Math.max(1, w.weight);
        }
        int r = total <= 0 ? 0 : rng.nextInt(total);
        for (LtsjFile.DrawWeight w : file.drawQuality) {
            r -= Math.max(1, w.weight);
            if (r < 0) {
                return w.quality >= 3 ? w.quality : 3;
            }
        }
        return 4;
    }

    /** 每次抽取随机获得的字经验点数（[expOnceMin, expOnceMax] 取整）。 */
    public int expOnce(Random rng) {
        int min = Math.max(1, file.expOnceMin);
        int max = Math.max(min, file.expOnceMax);
        if (max <= min) {
            return min;
        }
        return min + rng.nextInt(max - min + 1);
    }

    /** 当天结束时刻字符串（活动倒计时用，格式 yyyy-MM-dd HH:mm:ss）。 */
    public String endTimeOf(LocalDate date) {
        return date.plusDays(1).atStartOfDay().minusSeconds(1).format(TIME);
    }

    // ---------- json 模型 ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LtsjFile {
        public boolean enabled = true;
        public int activeId = 1;
        public int costOnce = 300;
        public int costTen = 3000;
        public int freeTimesPerDay = 2;
        public int wenZiMaxExp = 100;
        /** 每次抽取随机获得的字经验区间（抽一次在该区间内取整）。 */
        public int expOnceMin = 1;
        public int expOnceMax = 10;
        public String desc = "";
        public List<String> words = new ArrayList<>();
        public List<Integer> boxQuality = new ArrayList<>();
        /**
         * 四格宝箱（4 项，下标即宝箱槽位）。{@code ori} = 领取时发的宝箱道具本体（BX191-194），
         * {@code items} = 开箱产出。表依据 GoodsList 的 BX 行第 4 列说明：BX191 一定获得史诗勋章x3 /
         * BX192 影精灵碎片x80 / BX193 3星蝴蝶翅膀x1 / BX194 5级红色时光石x2。
         * 注：宝箱参数1 指向的抽取库 606/611/612/607/608/609 在假服 tables\ 里不存在，
         * 这里只发「一定获得」的固定内容，库里的附加掉落无法复现。
         */
        public List<BoxRow> boxes = new ArrayList<>();
        public Map<String, Count> boxCount = new HashMap<>();
        public List<DrawWeight> drawQuality = new ArrayList<>();
        public Map<String, Count> drawCount = new HashMap<>();
        public List<HeroRow> heroes = new ArrayList<>();

        static LtsjFile fallback() {
            LtsjFile f = new LtsjFile();
            f.desc = "抽取可得文字经验与随机物品。活动期间每天 2 次免费单抽。某个字经验条满即可激活对应宝箱；四字全满可领取当期神将；已拥有该英雄则领取时改发该将星级对应的灵魂碎片（WuJiangUpStar 折算表）。";
            f.words.add("限");
            f.words.add("时");
            f.words.add("神");
            f.words.add("将");
            f.boxQuality.add(5);
            f.boxQuality.add(4);
            f.boxQuality.add(4);
            f.boxQuality.add(3);
            // 四箱：本体 BX191-194 + 开箱产出（GoodsList 的「一定获得」）
            f.boxes.add(box("BX191", 1, boxItem("EQ0044", 3, 0)));
            f.boxes.add(box("BX192", 1, boxItem("GOODS115", 80, 0)));
            f.boxes.add(box("BX193", 1, boxItem("EQ0078", 1, 3)));
            f.boxes.add(box("BX194", 1, boxItem("TS105", 2, 0)));
            f.drawQuality.add(weight(5, 10));
            f.drawQuality.add(weight(4, 60));
            f.drawQuality.add(weight(3, 30));
            // 默认英雄池：屠戮者/圣咏剑姬/绝地武士/剑舞者/米娅（特殊活动产出）
            f.heroes.add(hero(52));
            f.heroes.add(hero(47));
            f.heroes.add(hero(40));
            f.heroes.add(hero(43));
            f.heroes.add(hero(39));
            return f;
        }

        private static DrawWeight weight(int quality, int weight) {
            DrawWeight w = new DrawWeight();
            w.quality = quality;
            w.weight = weight;
            return w;
        }

        private static HeroRow hero(int index) {
            HeroRow h = new HeroRow();
            h.heroIndex = index;
            return h;
        }

        private static BoxRow box(String ori, int num, BoxItem... items) {
            BoxRow b = new BoxRow();
            b.ori = ori;
            b.num = num;
            for (BoxItem it : items) {
                b.items.add(it);
            }
            return b;
        }

        private static BoxItem boxItem(String ori, int num, int stars) {
            BoxItem it = new BoxItem();
            it.ori = ori;
            it.num = num;
            it.stars = stars;
            return it;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class BoxRow {
            /**
             * 领取时发放的宝箱道具本体原始名（GoodsList 属性 10，如 BX191）。
             * 客户端只把 GoodsList 里查得到的 ori 显示成 tooltip / 冒字，送内容物（EQ、TS 开头）会静默不显示。
             */
            public String ori = "";
            /** 每次领取发放的箱数。 */
            public int num = 1;
            /** 该箱在背包开启（C2S 3101）时的固定产出。 */
            public List<BoxItem> items = new ArrayList<>();
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class BoxItem {
            /** 物品或装备原始名（装备原始名走 ProgressService.grantEquip 并发 1406）。 */
            public String ori = "";
            public int num = 1;
            /** 装备星级；0 = 1 星（与 ProgressService.grantEquip 默认一致）。 */
            public int stars;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class HeroRow {
            public int heroIndex;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Count {
            public int min = 1;
            public int max = 1;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class DrawWeight {
            public int quality;
            public int weight;
        }
    }
}
