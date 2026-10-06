package com.sao.fakeserver.table;

import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 优先读 sao-fake-server/tables/（已从 GameText 抽出）。
 * 缺文件时才尝试 GameText.txt，并回写到 tables/，搬家后不再依赖 Unity 工程。
 */
@Component
public class GameTables {
    private static final Logger log = LoggerFactory.getLogger(GameTables.class);
    private static final String[] TABLE_FILES = {
            "PlayerLevelInfo.ini",
            "WuJiangLevelInfo.txt",
            "RegionDropList.txt",
            "DrawBaoXiangConfig.txt",
            "JJC_Robot.txt",
            "JJC_RobotEquips.txt",
            "JJC_RankPrize.txt",
            "JJC_CDPrice.txt",
            "TiaoZhanSai_Commom.txt",
            "ZhengBaZhan.txt",
            "KuaFuZhanPrize.txt",
            "KuaFuZhanBase.txt",
            "RegionList.txt",
            "HundredTowerList.txt",
            "HundredTowerCommon.txt",
            "ResourceFBData.txt",
            "ResourceFBLevelData.txt"
    };

    private final SaoProperties props;
    private final Map<Integer, Integer> playerNextExp = new HashMap<>();
    private final Map<Integer, Integer> playerVpOnLevel = new HashMap<>();
    private final Map<Integer, Integer> wjNextExp = new HashMap<>();
    private final Map<Integer, DropRow> drops = new HashMap<>();
    private final List<RobotRow> robots = new ArrayList<>();
    private final Map<Integer, RobotEquipLib> robotEquipsById = new HashMap<>();
    private final List<RankPrizeRow> jjcRankPrizes = new ArrayList<>();
    /** ZhengBaZhan 排位赛排名奖励（按名次档）。 */
    private final List<ZbzRankPrizeRow> zbzRankPrizes = new ArrayList<>();
    /** JJC_CDPrice：下标=剩余冷却整分钟(0..9)，与客户端 GetClearPrice 一致。 */
    private final List<Integer> jjcCdPriceByRemainMin = new ArrayList<>();
    private DrawConfig draw = DrawConfig.fallback();
    private int maxPlayerLevel = 80;
    private int maxWjLevel = 80;
    /** TiaoZhanSai_Commom：A/B/C + 10 关比例 + 4 杯比例。 */
    private BobMoneyCfg bobMoney = BobMoneyCfg.fallback();
    private ZbzCfg zbzCfg = ZbzCfg.fallback();
    /** KuaFuZhanPrize：膜拜 + 单场胜负奖。 */
    private KfzPrizeCfg kfzPrize = KfzPrizeCfg.fallback();
    /** KuaFuZhanBase：日时段 + 积分/匹配系数。 */
    private KfzBaseCfg kfzBase = KfzBaseCfg.fallback();
    /** KuaFuZhanPrize 排位赛排名奖励。 */
    private final List<KfzRankPrizeRow> kfzPaiWeiRankPrizes = new ArrayList<>();
    /** KuaFuZhanPrize 巅峰对决排名奖励。 */
    private final List<KfzRankPrizeRow> kfzDfzRankPrizes = new ArrayList<>();
    /** RegionList：type → 场景 ID 升序（资源挑战 getAllRegionID[0]）。 */
    private final Map<Integer, List<Integer>> regionIdsByType = new HashMap<>();
    private final Map<Integer, Integer> regionNormalEnergy = new HashMap<>();
    private final Map<Integer, BctLayer> bctByLayer = new HashMap<>();
    private final Map<Integer, ResourceFbLevelCfg> resourceFbLevel = new HashMap<>();
    private BctCommon bctCommon = new BctCommon();
    private final Map<Integer, ResourceFbCfg> resourceFbByType = new HashMap<>();
    private static final int KFZ_SYNTH_GUID_BASE = 900100;

    public GameTables(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Path dir = Paths.get(props.getTablesDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("cannot create tables dir {}", dir);
        }
        ensureTables(dir);
        parsePlayerLevel(readTable(dir, "PlayerLevelInfo.ini"));
        parseWjLevel(readTable(dir, "WuJiangLevelInfo.txt"));
        parseDrop(readTable(dir, "RegionDropList.txt"));
        parseDraw(readTable(dir, "DrawBaoXiangConfig.txt"));
        parseRobots(readTable(dir, "JJC_Robot.txt"));
        parseRobotEquips(readTable(dir, "JJC_RobotEquips.txt"));
        parseJjcRankPrize(readTable(dir, "JJC_RankPrize.txt"));
        parseJjcCdPrice(readTable(dir, "JJC_CDPrice.txt"));
        parseBobMoney(readTable(dir, "TiaoZhanSai_Commom.txt"));
        parseZbz(readTable(dir, "ZhengBaZhan.txt"));
        parseKfzPrize(readTable(dir, "KuaFuZhanPrize.txt"));
        parseKfzBase(readTable(dir, "KuaFuZhanBase.txt"));
        parseRegionList(readTable(dir, "RegionList.txt"));
        parseHundredTower(readTable(dir, "HundredTowerList.txt"));
        parseHundredTowerCommon(readTable(dir, "HundredTowerCommon.txt"));
        parseResourceFbData(readTable(dir, "ResourceFBData.txt"));
        parseResourceFbLevel(readTable(dir, "ResourceFBLevelData.txt"));
        log.info("tables playerLv={} wjLv={} drops={} robots={} robotEquips={} jjcPrize={} jjcCdPrice={} drawGoldOnce={} bobMoneyA={} zbzOpenLv={} zbzGambleGold={} kfzMoBai={} kfz64Begin={} regions={} bctLayers={} resourceFb={}",
                playerNextExp.size(), wjNextExp.size(), drops.size(), robots.size(),
                robotEquipsById.size(), jjcRankPrizes.size(), jjcCdPriceByRemainMin.size(), draw.jbOnce,
                bobMoney.coeffA, zbzCfg.openLevel, zbzCfg.gambleGold, kfzPrize.moBaiByRank.size(),
                kfzBase.round64BeginMin, regionIdsByType.size(), bctByLayer.size(), resourceFbByType.size());
    }

    public List<RobotRow> robots() {
        return robots;
    }

    public List<RankPrizeRow> jjcRankPrizes() {
        return jjcRankPrizes;
    }

    /** 按当前名次取每日邮件奖励。表外名次用最后一档。 */
    public RankPrizeRow prizeForRank(int rank) {
        RankPrizeRow last = null;
        for (RankPrizeRow row : jjcRankPrizes) {
            last = row;
            if (rank >= row.minRank && rank <= row.maxRank) {
                return row;
            }
        }
        if (last != null && rank > last.maxRank) {
            return last;
        }
        return last;
    }

    public List<ZbzRankPrizeRow> zbzRankPrizes() {
        return zbzRankPrizes;
    }

    /** 排位日排名奖：表档为上限名次（≤10 / ≤20 …），取第一档命中。 */
    public ZbzRankPrizeRow zbzPrizeForRank(int rank) {
        ZbzRankPrizeRow last = null;
        for (ZbzRankPrizeRow row : zbzRankPrizes) {
            last = row;
            if (rank <= row.rankMax) {
                return row;
            }
        }
        return last;
    }

    /** targetGuid = 表行号（从 1 计），对应客户端 GetRobotData(guid-1)。 */
    public RobotRow robotByGuid(int guid) {
        int i = guid - 1;
        if (i >= 0 && i < robots.size() && robots.get(i).targetGuid == guid) {
            return robots.get(i);
        }
        for (RobotRow r : robots) {
            if (r.targetGuid == guid) {
                return r;
            }
        }
        return null;
    }

    public RobotEquipLib robotEquipLib(int id) {
        return robotEquipsById.get(Integer.valueOf(id));
    }

    /** JJC_RobotEquips 全部库 id（假号挂装取材）。 */
    public List<Integer> robotEquipLibIds() {
        return new ArrayList<>(robotEquipsById.keySet());
    }

    public int nextPlayerExp(int level) {
        Integer v = playerNextExp.get(level);
        return v == null ? Math.max(100, level * 120) : v;
    }

    public int vpOnLevelUp(int newLevel) {
        Integer v = playerVpOnLevel.get(newLevel);
        return v == null ? 0 : v;
    }

    public int nextWjExp(int level) {
        Integer v = wjNextExp.get(level);
        return v == null ? Math.max(80, level * 80) : v;
    }

    public int maxPlayerLevel() {
        return maxPlayerLevel;
    }

    public int maxWjLevel() {
        return maxWjLevel;
    }

    public DropRow drop(int regionId, int difficult) {
        DropRow row = drops.get(regionId * 10 + difficult);
        if (row != null) {
            return row;
        }
        return drops.get(regionId * 10 + 1);
    }

    /** RegionList 该 REGION_TYPE 的第一个场景 ID（对齐 getAllRegionID[0]）。 */
    public int firstRegionOfType(int regionType) {
        List<Integer> ids = regionIdsByType.get(Integer.valueOf(regionType));
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return ids.get(0).intValue();
    }

    public int regionNormalEnergy(int regionId) {
        Integer e = regionNormalEnergy.get(Integer.valueOf(regionId));
        return e == null ? 0 : e.intValue();
    }

    /**
     * 资源挑战掉落行：RegionDropList 场景基号+难度（镜像 21–27=20+lv）。
     * 切图仍用 {@link #firstRegionOfType}。
     */
    public int resourceDropRegionId(int regionType, int level) {
        int scene = firstRegionOfType(regionType);
        int lv = Math.max(1, Math.min(7, level));
        return scene > 0 ? scene + lv : 0;
    }

    /**
     * 资源本发奖规划。仅致命/镰刀（APK 全局池空）。镜像/躲避/圣诞走 RegionDropList。
     * 数量：1–2 档 20/14，第 3 档起每件 10（产品）。
     */
    public ResourceFbPlan resourceFbPlan(int regionType, int level) {
        if (regionType != 8 && regionType != 10) {
            return null;
        }
        int lv = Math.max(1, Math.min(7, level));
        int i = lv - 1;
        ResourceFbPlan p = new ResourceFbPlan();
        String[] trio = regionType == 8 ? PHYS_JINJIE[i] : MAG_JINJIE[i];
        int n = JINJIE_COUNT[i];
        for (int k = 0; k < trio.length; k++) {
            p.goods.add(new GoodsDrop(trio[k], n));
        }
        return p;
    }

    /** 进阶三件套（产品）：1–2 档 20/14，第 3 档起每件固定 10。 */
    private static final int[] JINJIE_COUNT = {20, 14, 10, 10, 10, 10, 10};
    /** 物理：格斗印记 / 铸匠符文 / 野性晶石 Ⅱ–Ⅶ（对齐原 41–47 档）。 */
    private static final String[][] PHYS_JINJIE = {
            {"GOODS54", "GOODS60", "GOODS63"},
            {"GOODS54", "GOODS60", "GOODS63"},
            {"GOODS66", "GOODS72", "GOODS75"},
            {"GOODS78", "GOODS84", "GOODS87"},
            {"GOODS90", "GOODS96", "GOODS99"},
            {"GOODS120", "GOODS126", "GOODS129"},
            {"GOODS132", "GOODS138", "GOODS141"}
    };
    /** 魔法：体魄印记 / 掌控符文 / 奥术晶石 Ⅱ–Ⅶ（对齐原 61–67 档）。 */
    private static final String[][] MAG_JINJIE = {
            {"GOODS55", "GOODS61", "GOODS64"},
            {"GOODS55", "GOODS61", "GOODS64"},
            {"GOODS67", "GOODS73", "GOODS76"},
            {"GOODS79", "GOODS85", "GOODS88"},
            {"GOODS91", "GOODS97", "GOODS100"},
            {"GOODS121", "GOODS127", "GOODS130"},
            {"GOODS133", "GOODS139", "GOODS142"}
    };

    public static final class ResourceFbPlan {
        public int gold;
        public final List<GoodsDrop> goods = new ArrayList<>();
    }

    public BctLayer bctLayer(int layer) {
        return bctByLayer.get(Integer.valueOf(Math.max(1, layer)));
    }

    /** HundredTowerList 最大层号；表空时 50。 */
    public int bctMaxLayer() {
        int max = 0;
        for (Integer k : bctByLayer.keySet()) {
            if (k != null && k.intValue() > max) {
                max = k.intValue();
            }
        }
        return max > 0 ? max : 50;
    }

    /**
     * 百层发奖 —— <b>按表</b>：直接取 {@code tables\HundredTowerList.txt} 每行
     * 「物品_1..10_原始名/数量/掉落概率」列为奖励，金币取「金币」列（见 {@link #parseHundredTower}）。
     *
     * <p>表事实（本仓库 2026-10 版，50 层全查过）：物品列 50 层都有值；掉落概率列全部 10000
     * （万分率 ⇒ 列出的都是必给）；「金币」列与「全局掉落类别ID/概率」列 50 层全 0
     * ⇒ 本方法返回的 {@code gold} 就是 0，金币收益由表里发的 TWBX003/TWBX004（百战金币宝箱）
     * 开出，见 {@code EconomyTables.boxCurrency}。层 31 起额外带 BX240/BX241/BX242。
     *
     * <p>表缺行、或该行一个奖励都没填时，回退到旧的「产品规划」档（金币 + HTE 装备箱）保证不空手。
     */
    public ResourceFbPlan bctReward(int layer) {
        int lv = Math.max(1, layer);
        BctLayer row = bctLayer(lv);
        ResourceFbPlan p = new ResourceFbPlan();
        if (row != null && (!row.drops.isEmpty() || row.gold > 0)) {
            p.gold = row.gold;
            p.goods.addAll(row.drops);
            return p;
        }
        int band = Math.min(4, (lv - 1) / 10);
        p.gold = 3000 + band * 4000;
        if (lv % 5 == 0) {
            p.gold += p.gold / 2;
        }
        p.goods.add(new GoodsDrop(lv >= 41 ? "HTE3" : lv >= 21 ? "HTE2" : "HTE1", 1));
        return p;
    }

    public BctCommon bctCommon() {
        return bctCommon;
    }

    /** ResourceFBData 冷却秒；缺行 0。 */
    public int resourceFbCdSec(int regionType) {
        ResourceFbCfg c = resourceFbByType.get(Integer.valueOf(regionType));
        return c == null ? 0 : Math.max(0, c.cdSec);
    }

    /**
     * 双倍日：表 doubleTime 为 1–7（周一–日，_ 分隔）；与客户端 TimeNow−5h 的 ISO 星期一致。
     */
    public boolean resourceFbDoubleToday(int regionType) {
        ResourceFbCfg c = resourceFbByType.get(Integer.valueOf(regionType));
        if (c == null || c.doubleDays.isEmpty()) {
            return false;
        }
        int dow = GameTime.now().minusHours(5).getDayOfWeek().getValue();
        return c.doubleDays.contains(Integer.valueOf(dow));
    }

    /** 全部掉落行（克隆战排除主线已可达碎片池用）。 */
    public Collection<DropRow> allDrops() {
        return drops.values();
    }

    public DrawConfig draw() {
        return draw;
    }

    public BobMoneyCfg bobMoney() {
        return bobMoney;
    }

    public ZbzCfg zbzCfg() {
        return zbzCfg;
    }

    public KfzPrizeCfg kfzPrize() {
        return kfzPrize;
    }

    public KfzBaseCfg kfzBase() {
        return kfzBase;
    }

    public List<KfzRankPrizeRow> kfzPaiWeiRankPrizes() {
        return kfzPaiWeiRankPrizes;
    }

    public List<KfzRankPrizeRow> kfzDfzRankPrizes() {
        return kfzDfzRankPrizes;
    }

    /** 排位赛排名档：表名为次上限（≤70 / ≤75 …）。 */
    public KfzRankPrizeRow kfzPaiWeiPrizeForRank(int rank) {
        return firstKfzRankPrize(kfzPaiWeiRankPrizes, rank);
    }

    /** 巅峰对决排名档：表名为次上限（≤1 / ≤2 / ≤4 …）。 */
    public KfzRankPrizeRow kfzDfzPrizeForRank(int rank) {
        return firstKfzRankPrize(kfzDfzRankPrizes, rank);
    }

    private static KfzRankPrizeRow firstKfzRankPrize(List<KfzRankPrizeRow> rows, int rank) {
        KfzRankPrizeRow last = null;
        for (KfzRankPrizeRow row : rows) {
            last = row;
            if (rank <= row.rankCeiling) {
                return row;
            }
        }
        return last;
    }

    /**
     * 跨服巅峰 64 / 三场互异武将池：不足时追加合成机器人（guid 自 900100 起），
     * 属性拷自 robots[i%n]，wjIndex 从全表武将池重排。
     */
    public synchronized void ensureKfzRobotPool(int minSize) {
        if (minSize <= 0 || robots.size() >= minSize) {
            return;
        }
        int n = robots.size();
        if (n == 0) {
            log.warn("ensureKfzRobotPool: no base robots, skip");
            return;
        }
        List<Integer> heroPool = new ArrayList<>();
        for (RobotRow r : robots) {
            if (r == null || r.wjIndex == null) {
                continue;
            }
            for (int wj : r.wjIndex) {
                if (wj > 0 && !heroPool.contains(wj)) {
                    heroPool.add(wj);
                }
            }
        }
        for (int i = 1; i <= 80 && heroPool.size() < 40; i++) {
            if (!heroPool.contains(i)) {
                heroPool.add(i);
            }
        }
        if (heroPool.isEmpty()) {
            heroPool.add(18);
        }
        int syn = 0;
        while (robots.size() < minSize) {
            RobotRow src = robots.get(syn % n);
            RobotRow row = new RobotRow();
            row.tableIndex = robots.size();
            row.targetGuid = KFZ_SYNTH_GUID_BASE + syn;
            row.name = "跨服选手" + (syn + 1);
            row.level = src.level > 0 ? src.level : 30;
            row.resId = src.resId > 0 ? src.resId : 18;
            row.stars = Math.max(1, src.stars);
            row.stage = Math.max(0, src.stage);
            row.shuLianDu = src.shuLianDu;
            row.victoryNum = src.victoryNum;
            row.skillMingJiang = Math.max(1, src.skillMingJiang);
            row.skillA = Math.max(1, src.skillA);
            row.skillB = Math.max(1, src.skillB);
            row.skillPassive = Math.max(1, src.skillPassive);
            row.equipLibId = src.equipLibId;
            for (int s = 0; s < 5; s++) {
                row.wjIndex[s] = heroPool.get((syn * 5 + s) % heroPool.size());
            }
            robots.add(row);
            syn++;
        }
        log.info("ensureKfzRobotPool expanded to {} (synth={})", robots.size(), syn);
    }

    /**
     * 挑战赛金币。表字段 A/B/C×比例；Client_real 只加载不计算。
     * 拼装：floor((A + B * playerLevel) * C * ratio)；与关卡/奖杯列名一一对应。
     */
    public int bobStageGold(int playerLevel, int stage1to10) {
        float ratio = bobMoney.stageRatio(stage1to10);
        return bobGoldFromRatio(playerLevel, ratio);
    }

    public int bobCupGold(int playerLevel, int cup0to3) {
        float ratio = bobMoney.cupRatio(cup0to3);
        return bobGoldFromRatio(playerLevel, ratio);
    }

    private int bobGoldFromRatio(int playerLevel, float ratio) {
        int lv = Math.max(1, playerLevel);
        double base = bobMoney.coeffA + bobMoney.coeffB * lv;
        return (int) Math.floor(base * bobMoney.coeffC * ratio);
    }

    private void ensureTables(Path dir) {
        List<String> missing = new ArrayList<>();
        for (String name : TABLE_FILES) {
            if (!Files.isRegularFile(dir.resolve(name))) {
                missing.add(name);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        GameTextLoader loader = GameTextLoader.load(props.getGameText());
        if (loader.isEmpty()) {
            log.warn("tables incomplete and GameText not found; using fallback numbers");
            return;
        }
        for (String name : missing) {
            String text = loader.get(name);
            if (text == null) {
                continue;
            }
            try {
                Files.write(dir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
                log.info("extracted {} -> {}", name, dir.resolve(name).toAbsolutePath());
            } catch (IOException e) {
                log.warn("write {} failed: {}", name, e.toString());
            }
        }
    }

    private static String readTable(Path dir, String name) {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("read {} failed: {}", name, e.toString());
            return null;
        }
    }

    private void parseRobots(String text) {
        robots.clear();
        if (text == null) {
            return;
        }
        int tableIndex = 0;
        for (String[] cols : hashRows(text)) {
            if (cols.length < 8) {
                continue;
            }
            RobotRow r = new RobotRow();
            r.tableIndex = tableIndex;
            r.targetGuid = tableIndex + 1;
            r.name = cols.length > 2 ? cols[2] : ("机器人" + r.targetGuid);
            r.level = toInt(cols, 3);
            r.resId = toInt(cols, 4);
            r.stars = toInt(cols, 5);
            r.stage = toInt(cols, 6);
            r.shuLianDu = toInt(cols, 7);
            r.wjIndex[0] = toInt(cols, 8);
            r.wjIndex[1] = toInt(cols, 9);
            r.wjIndex[2] = toInt(cols, 10);
            r.wjIndex[3] = toInt(cols, 11);
            r.wjIndex[4] = toInt(cols, 12);
            r.victoryNum = cols.length > 13 ? toInt(cols, 13) : 0;
            r.skillMingJiang = cols.length > 14 ? toInt(cols, 14) : 1;
            r.skillA = cols.length > 15 ? toInt(cols, 15) : 1;
            r.skillB = cols.length > 16 ? toInt(cols, 16) : 1;
            r.skillPassive = cols.length > 17 ? toInt(cols, 17) : 1;
            r.equipLibId = cols.length > 18 ? toInt(cols, 18) : 0;
            robots.add(r);
            tableIndex++;
        }
    }

    /**
     * 对齐 JJCPropertyMgr.LoadJJCRobotEquips：
     * weaponsAndPeiJians[1..8]、equips[3..5]（饰品/翅膀/勋章）。
     */
    private void parseRobotEquips(String text) {
        robotEquipsById.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 12) {
                continue;
            }
            RobotEquipLib lib = new RobotEquipLib();
            lib.id = toInt(cols, 1);
            lib.level = toInt(cols, 2);
            lib.stars = toInt(cols, 3);
            lib.guHuaLevel = toInt(cols, 4);
            for (int i = 1; i <= 8; i++) {
                lib.weaponsAndPeiJians[i] = cols.length > 4 + i ? cols[4 + i] : "0";
            }
            for (int j = 0; j < 3; j++) {
                int col = 13 + j;
                lib.equips[3 + j] = cols.length > col ? cols[col] : "0";
            }
            if (lib.id > 0) {
                robotEquipsById.put(Integer.valueOf(lib.id), lib);
            }
        }
    }

    private void parseJjcRankPrize(String text) {
        jjcRankPrizes.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 6) {
                continue;
            }
            RankPrizeRow row = new RankPrizeRow();
            row.minRank = toInt(cols, 1);
            row.maxRank = toInt(cols, 2);
            row.diamond = toInt(cols, 3);
            row.gold = toInt(cols, 4);
            row.jjcScore = toInt(cols, 5);
            row.goods1 = cols.length > 6 ? cols[6] : "0";
            row.goods1Num = toInt(cols, 7);
            row.goods2 = cols.length > 8 ? cols[8] : "0";
            row.goods2Num = toInt(cols, 9);
            if (row.minRank > 0 && row.maxRank >= row.minRank) {
                jjcRankPrizes.add(row);
            }
        }
    }

    /**
     * JJC_CDPrice.txt：每行「说明\t钻价」，行序=剩余冷却整分钟 0..9。
     * 对齐客户端 JJCPropertyMgr.GetClearPrice（≥10 钳到 9；&lt;0 返回 0）。
     */
    private void parseJjcCdPrice(String text) {
        jjcCdPriceByRemainMin.clear();
        if (text != null) {
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("//")) {
                    continue;
                }
                String[] cols = line.split("[ \\t]+");
                if (cols.length < 2) {
                    continue;
                }
                try {
                    jjcCdPriceByRemainMin.add(Integer.valueOf(Integer.parseInt(cols[cols.length - 1].trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (jjcCdPriceByRemainMin.isEmpty()) {
            // 与 GameData/JJC_CDPrice.txt 兜底一致
            int[] fb = {4, 8, 12, 16, 20, 24, 28, 32, 36, 40};
            for (int p : fb) {
                jjcCdPriceByRemainMin.add(Integer.valueOf(p));
            }
        }
    }

    /**
     * TiaoZhanSai_Commom.txt：与客户端 BOBChallengeProperty.LoadBOBCommonData 同行序。
     * 行：开启等级 / 通关ID / A B C / 4 杯比例 / 10 关比例。
     */
    private void parseBobMoney(String text) {
        bobMoney = BobMoneyCfg.fallback();
        if (text == null || text.isEmpty()) {
            return;
        }
        List<String> vals = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            String[] cols = line.split("[ \\t]+");
            if (cols.length < 2) {
                continue;
            }
            vals.add(cols[cols.length - 1].trim());
        }
        // 跳过开启等级、通关ID；要 A B C + 4 cup + 10 stage = 17
        if (vals.size() < 2 + 3 + 4 + 10) {
            log.warn("TiaoZhanSai_Commom rows incomplete size={}", vals.size());
            return;
        }
        BobMoneyCfg cfg = new BobMoneyCfg();
        try {
            cfg.enterLevel = Integer.parseInt(vals.get(0));
            cfg.enterAfterFb = Integer.parseInt(vals.get(1));
            cfg.coeffA = Float.parseFloat(vals.get(2));
            cfg.coeffB = Float.parseFloat(vals.get(3));
            cfg.coeffC = Float.parseFloat(vals.get(4));
            for (int i = 0; i < 4; i++) {
                cfg.cupRatio[i] = Float.parseFloat(vals.get(5 + i));
            }
            for (int i = 0; i < 10; i++) {
                cfg.stageRatio[i] = Float.parseFloat(vals.get(9 + i));
            }
            bobMoney = cfg;
        } catch (NumberFormatException e) {
            log.warn("TiaoZhanSai_Commom parse fail: {}", e.toString());
        }
    }

    /**
     * ZhengBaZhan.txt：与 ZBZPropertyCfgMgr 时段/下注/决战奖对齐。
     */
    private void parseZbz(String text) {
        zbzCfg = ZbzCfg.fallback();
        if (text == null || text.isEmpty()) {
            return;
        }
        List<String> vals = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("[ \\t]+");
            if (cols.length < 2) {
                continue;
            }
            // 跳过纯中文表头行（无时间/数字尾巴时仍记录数值列）
            vals.add(cols[cols.length - 1].trim());
        }
        try {
            if (!vals.isEmpty()) {
                zbzCfg.openLevel = Integer.parseInt(vals.get(0));
            }
            // 0开级 1开服天 2胜基 3败基 4星系 5战系 6匹配 7排位起 8排位止 9下注 10八起 11八止 12四起 13四止 14决起 15决止
            if (vals.size() >= 4) {
                zbzCfg.winBaseScore = Integer.parseInt(vals.get(2));
                zbzCfg.failBaseScore = Integer.parseInt(vals.get(3));
            }
            if (vals.size() >= 7) {
                zbzCfg.starScoreCoeff = Float.parseFloat(vals.get(4));
                zbzCfg.fightScoreCoeff = Float.parseFloat(vals.get(5));
                zbzCfg.matchFloat = Integer.parseInt(vals.get(6));
            }
            if (vals.size() >= 9) {
                zbzCfg.pwsBeginMin = parseHmToMin(vals.get(7));
                zbzCfg.pwsEndMin = parseHmToMin(vals.get(8));
            }
            if (vals.size() >= 16) {
                zbzCfg.yaZhuMin = parseHmToMin(vals.get(9));
                zbzCfg.round8BeginMin = parseHmToMin(vals.get(10));
                zbzCfg.round8EndMin = parseHmToMin(vals.get(11));
                zbzCfg.round4EndMin = parseHmToMin(vals.get(13));
                zbzCfg.finalEndMin = parseHmToMin(vals.get(15));
            }
        } catch (Exception e) {
            log.warn("ZhengBaZhan time parse: {}", e.toString());
        }
        zbzRankPrizes.clear();
        boolean inRankPrize = false;
        boolean inGambleRates = false;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.contains("排位赛排名奖励")) {
                inRankPrize = true;
                inGambleRates = false;
                continue;
            }
            if (line.contains("PaiWeiSaiEnd")) {
                inRankPrize = false;
                continue;
            }
            if (line.contains("赔率配置")) {
                inGambleRates = true;
                continue;
            }
            if (line.contains("排位赛胜场") || line.contains("巅峰对决")) {
                inGambleRates = false;
            }
            if (inRankPrize && line.startsWith("#")) {
                String[] cols = line.split("[ \\t]+");
                // # rankMax gold diamond yingPo wnsp g1 n1 g2 n2
                if (cols.length >= 6) {
                    ZbzRankPrizeRow row = new ZbzRankPrizeRow();
                    row.rankMax = safeInt(cols[1], 0);
                    row.gold = safeInt(cols[2], 0);
                    row.diamond = safeInt(cols[3], 0);
                    row.yingPo = safeInt(cols[4], 0);
                    row.wnsp = safeInt(cols[5], 0);
                    if (row.rankMax > 0) {
                        zbzRankPrizes.add(row);
                    }
                }
                continue;
            }
            if (line.contains("金币押注")) {
                String[] c = line.split("[ \\t]+");
                if (c.length >= 2) {
                    zbzCfg.gambleGold = safeInt(c[c.length - 1], zbzCfg.gambleGold);
                }
            } else if (line.contains("钻石押注")) {
                String[] c = line.split("[ \\t]+");
                if (c.length >= 2) {
                    zbzCfg.gambleDiamond = safeInt(c[c.length - 1], zbzCfg.gambleDiamond);
                }
            } else if (line.startsWith("#") && inGambleRates) {
                String[] cols = line.split("[ \\t]+");
                // # rank lo hi
                if (cols.length >= 4) {
                    int rank = safeInt(cols[1], 0);
                    float lo = safeFloat(cols[2], 0f);
                    float hi = safeFloat(cols[3], 0f);
                    if (rank >= 1 && rank <= 8 && lo > 0f && hi > 0f) {
                        zbzCfg.gambleRateLo[rank - 1] = lo;
                        zbzCfg.gambleRateHi[rank - 1] = hi;
                    }
                }
            } else if (line.contains("排位赛胜场")) {
                applyZbzPrizeLine(zbzCfg.pwsWin, line);
            } else if (line.contains("排位赛败场")) {
                applyZbzPrizeLine(zbzCfg.pwsFail, line);
            } else if (line.contains("巅峰对决胜场")) {
                applyZbzPrizeLine(zbzCfg.top8Win, line);
            } else if (line.contains("巅峰对决败场")) {
                applyZbzPrizeLine(zbzCfg.top8Fail, line);
            } else if (line.contains("8强")) {
                applyZbzPrizeLine(zbzCfg.placePrize[0], line);
            } else if (line.contains("4强")) {
                applyZbzPrizeLine(zbzCfg.placePrize[1], line);
            } else if (line.contains("2名")) {
                applyZbzPrizeLine(zbzCfg.placePrize[2], line);
            } else if (line.contains("1名")) {
                applyZbzPrizeLine(zbzCfg.placePrize[3], line);
            }
        }
    }

    private static void applyZbzPrizeLine(ZbzPrize p, String line) {
        String[] cols = line.split("[ \\t]+");
        if (cols.length < 5) {
            return;
        }
        int i = 1;
        p.gold = safeInt(cols[i++], p.gold);
        p.diamond = safeInt(cols[i++], p.diamond);
        p.yingPo = safeInt(cols[i++], p.yingPo);
        p.wnsp = safeInt(cols[i++], p.wnsp);
        if (i < cols.length) {
            String ori = cols[i++];
            if (ori != null && !ori.isEmpty() && !"0".equals(ori)) {
                p.goodsOri = ori;
                p.goodsCount = i < cols.length ? safeInt(cols[i], 0) : 0;
            }
        }
    }

    /**
     * KuaFuZhanPrize.txt：按标题分段（对齐客户端 KuaFuZhanPrizeCfg）。
     * 排位/巅峰排名奖励 → kfzPaiWeiRankPrizes / kfzDfzRankPrizes；
     * 单场 + 膜拜 → kfzPrize。
     */
    private void parseKfzPrize(String text) {
        kfzPrize = KfzPrizeCfg.fallback();
        kfzPaiWeiRankPrizes.clear();
        kfzDfzRankPrizes.clear();
        if (text == null || text.isEmpty()) {
            return;
        }
        int section = 0; // 1=排位排名 2=巅峰排名 3=单场 4=膜拜
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.contains("排位赛排名奖励")) {
                section = 1;
                continue;
            }
            if (line.contains("巅峰对决排名奖励")) {
                section = 2;
                continue;
            }
            if (line.contains("单场奖励")) {
                section = 3;
                continue;
            }
            if (line.contains("膜拜奖励")) {
                section = 4;
                continue;
            }
            if (line.contains("全服奖励")) {
                section = 0;
                continue;
            }
            if (line.startsWith("#") && line.contains("End")) {
                section = 0;
                continue;
            }
            if (!line.startsWith("#") || line.contains("End")) {
                continue;
            }
            String[] cols = line.split("[ \\t]+");
            if (cols.length < 5) {
                continue;
            }
            if (section == 1 || section == 2) {
                int rankCeil = safeInt(cols[1], 0);
                if (rankCeil <= 0) {
                    continue;
                }
                KfzRankPrizeRow row = new KfzRankPrizeRow();
                row.rankCeiling = rankCeil;
                row.gold = safeInt(cols[2], 0);
                row.diamond = safeInt(cols[3], 0);
                row.jjc = safeInt(cols[4], 0);
                if (cols.length >= 7) {
                    row.goods1 = cols[5];
                    row.goods1Count = safeInt(cols[6], 0);
                }
                if (cols.length >= 9) {
                    row.goods2 = cols[7];
                    row.goods2Count = safeInt(cols[8], 0);
                }
                if (section == 1) {
                    kfzPaiWeiRankPrizes.add(row);
                } else {
                    kfzDfzRankPrizes.add(row);
                }
                continue;
            }
            if (section == 4) {
                int rank = safeInt(cols[1], 0);
                if (rank >= 1 && rank <= 3) {
                    KfzMatchPrize m = new KfzMatchPrize();
                    m.gold = safeInt(cols[2], 0);
                    m.stamina = safeInt(cols[3], 0);
                    m.jjcScore = safeInt(cols[4], 0);
                    kfzPrize.moBaiByRank.put(rank, m);
                }
                continue;
            }
            if (section == 3) {
                if (cols[1].contains("排位赛胜")) {
                    applyKfzMatchLine(kfzPrize.pysWin, cols);
                } else if (cols[1].contains("排位赛败")) {
                    applyKfzMatchLine(kfzPrize.pysFail, cols);
                } else if (cols[1].contains("巅峰对决胜")) {
                    applyKfzMatchLine(kfzPrize.dfzWin, cols);
                } else if (cols[1].contains("巅峰对决败")) {
                    applyKfzMatchLine(kfzPrize.dfzFail, cols);
                }
            }
        }
        if (kfzPrize.moBaiByRank.isEmpty()) {
            KfzPrizeCfg fb = KfzPrizeCfg.fallback();
            kfzPrize.moBaiByRank.putAll(fb.moBaiByRank);
        }
    }

    /**
     * KuaFuZhanBase.txt：按行序取值（对齐客户端 KuaFuZhanBaseProperty 下标）。
     * vals[3..10]=积分/匹配系数；[11]=战报容量；[12..23]=六段启止 HH_MM。
     * 64/32/16 与 8/4/决 表内钟点相同，假服用 finalEight 区分 phase 8–10 vs 11–13。
     */
    private void parseKfzBase(String text) {
        kfzBase = KfzBaseCfg.fallback();
        if (text == null || text.isEmpty()) {
            return;
        }
        List<String> vals = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            String[] cols = line.split("[ \\t]+");
            if (cols.length < 2) {
                continue;
            }
            vals.add(cols[cols.length - 1].trim());
        }
        try {
            if (vals.size() > 0) {
                kfzBase.openLevel = Integer.parseInt(vals.get(0));
            }
            if (vals.size() > 2) {
                kfzBase.winBaseScore = Integer.parseInt(vals.get(1));
                kfzBase.loseBaseScore = Integer.parseInt(vals.get(2));
            }
            if (vals.size() > 10) {
                kfzBase.fightScoreA = safeFloat(vals.get(3), kfzBase.fightScoreA);
                kfzBase.fightScoreB = safeFloat(vals.get(4), kfzBase.fightScoreB);
                kfzBase.maxFightScore = safeFloat(vals.get(5), kfzBase.maxFightScore);
                kfzBase.scoreModifier = safeFloat(vals.get(6), kfzBase.scoreModifier);
                kfzBase.starMatchValue = Integer.parseInt(vals.get(7));
                kfzBase.minPiPeiModifier = Integer.parseInt(vals.get(8));
                kfzBase.maxPiPeiModifier = Integer.parseInt(vals.get(9));
                kfzBase.serverOpenDayReq = Integer.parseInt(vals.get(10));
            }
            if (vals.size() > 11) {
                kfzBase.maxBattleRecordCnt = Integer.parseInt(vals.get(11));
            }
            if (vals.size() >= 24) {
                kfzBase.round64BeginMin = parseHmUnderscore(vals.get(12));
                kfzBase.round64EndMin = parseHmUnderscore(vals.get(13));
                kfzBase.round32BeginMin = parseHmUnderscore(vals.get(14));
                kfzBase.round32EndMin = parseHmUnderscore(vals.get(15));
                kfzBase.round16BeginMin = parseHmUnderscore(vals.get(16));
                kfzBase.round16EndMin = parseHmUnderscore(vals.get(17));
                kfzBase.quarterBeginMin = parseHmUnderscore(vals.get(18));
                kfzBase.quarterEndMin = parseHmUnderscore(vals.get(19));
                kfzBase.semiBeginMin = parseHmUnderscore(vals.get(20));
                kfzBase.semiEndMin = parseHmUnderscore(vals.get(21));
                kfzBase.finalBeginMin = parseHmUnderscore(vals.get(22));
                kfzBase.finalEndMin = parseHmUnderscore(vals.get(23));
            }
        } catch (Exception e) {
            log.warn("KuaFuZhanBase parse: {}", e.toString());
            kfzBase = KfzBaseCfg.fallback();
        }
    }

    private static int parseHmUnderscore(String hm) {
        String[] p = hm.split("[_:]");
        int h = Integer.parseInt(p[0].trim());
        int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
        return h * 60 + m;
    }

    private static void applyKfzMatchLine(KfzMatchPrize p, String[] cols) {
        p.gold = safeInt(cols[2], p.gold);
        p.jjcScore = safeInt(cols[4], p.jjcScore);
        if (cols.length >= 7) {
            String ori = cols[5];
            if (ori != null && !ori.isEmpty() && !"0".equals(ori)) {
                p.goodsOri = ori;
                p.goodsCount = safeInt(cols[6], 0);
            }
        }
    }

    private static int safeInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static float safeFloat(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseHmToMin(String hm) {
        String[] p = hm.split(":");
        int h = Integer.parseInt(p[0].trim());
        int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
        return h * 60 + m;
    }

    /** 清 CD 钻价：remainMinutes 为客户端 (10 - 已过分钟) 的 int 截断。 */
    public int jjcClearCdPrice(int remainMinutes) {
        if (remainMinutes < 0) {
            return 0;
        }
        int idx = remainMinutes;
        if (idx >= 10) {
            idx = 9;
        }
        if (idx >= jjcCdPriceByRemainMin.size()) {
            idx = jjcCdPriceByRemainMin.size() - 1;
        }
        return jjcCdPriceByRemainMin.get(idx).intValue();
    }

    public static final class RankPrizeRow {
        public int minRank;
        public int maxRank;
        public int diamond;
        public int gold;
        public int jjcScore;
        public String goods1 = "0";
        public int goods1Num;
        public String goods2 = "0";
        public int goods2Num;
    }

    /** ZhengBaZhan 排位赛排名奖励一行。 */
    public static final class ZbzRankPrizeRow {
        public int rankMax;
        public int gold;
        public int diamond;
        public int yingPo;
        public int wnsp;
    }

    public static final class RobotRow {
        public int tableIndex;
        public int targetGuid;
        public String name = "";
        public int level;
        public int resId;
        public int stars;
        public int stage;
        /** 该阶段下各子类型熟练度；客户端写入 Jieduan_type_1..4_para。 */
        public int shuLianDu;
        /** 5 个武将表 index（角色_1..5_Index）。 */
        public final int[] wjIndex = new int[5];
        public int victoryNum;
        public int skillMingJiang = 1;
        public int skillA = 1;
        public int skillB = 1;
        public int skillPassive = 1;
        public int equipLibId;
    }

    /** JJC_RobotEquips 一行。 */
    public static final class RobotEquipLib {
        public int id;
        public int level;
        public int stars;
        public int guHuaLevel;
        /** index 1..8 = 单手/双手/重型/轻型/实体盾/轻甲/离子盾/防护水晶 */
        public final String[] weaponsAndPeiJians = new String[9];
        /** index 3..5 = 饰品/翅膀/勋章 */
        public final String[] equips = new String[6];
    }

    private void parsePlayerLevel(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 3) {
                continue;
            }
            int lv = toInt(cols, 1);
            int exp = toInt(cols, 2);
            int vp = cols.length > 4 ? toInt(cols, 4) : 0;
            if (lv > 0) {
                playerNextExp.put(lv, exp);
                playerVpOnLevel.put(lv, vp);
                maxPlayerLevel = Math.max(maxPlayerLevel, lv);
            }
        }
    }

    private void parseWjLevel(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 3) {
                continue;
            }
            int lv = toInt(cols, 1);
            int exp = toInt(cols, 2);
            if (lv > 0) {
                wjNextExp.put(lv, exp);
                maxWjLevel = Math.max(maxWjLevel, lv);
            }
        }
    }

    private void parseDrop(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 20) {
                continue;
            }
            try {
                DropRow row = DropRow.parse(cols);
                drops.put(row.regionId * 10 + row.difficult, row);
            } catch (RuntimeException e) {
                log.debug("skip drop row: {}", e.toString());
            }
        }
    }

    private void parseRegionList(String text) {
        regionIdsByType.clear();
        regionNormalEnergy.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 6) {
                continue;
            }
            int id = toInt(cols, 1);
            int type = toInt(cols, 3);
            int energy = toInt(cols, 5);
            if (id <= 0) {
                continue;
            }
            regionNormalEnergy.put(Integer.valueOf(id), Integer.valueOf(energy));
            List<Integer> list = regionIdsByType.get(Integer.valueOf(type));
            if (list == null) {
                list = new ArrayList<>();
                regionIdsByType.put(Integer.valueOf(type), list);
            }
            list.add(Integer.valueOf(id));
        }
        for (List<Integer> list : regionIdsByType.values()) {
            Collections.sort(list);
        }
    }

    private void parseHundredTower(String text) {
        bctByLayer.clear();
        if (text == null) {
            return;
        }
        for (String line : text.split("\r?\n")) {
            if (!line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (cols.length < 17) {
                continue;
            }
            // cols[0]="#"
            BctLayer row = new BctLayer();
            row.layer = toInt(cols, 1);
            row.regionResId = toInt(cols, 2);
            row.regionType = toInt(cols, 4);
            row.playerLevelLimit = toInt(cols, 5);
            row.serverFight = toInt(cols, 12) == 1;
            row.gold = toInt(cols, 13);
            // 表头：物品_N_原始名 / 物品_N_数量 / 物品_N_掉落概率(万分率)，N=1..10 ⇒ 列 16..45。
            // 现网 50 行的概率全是 10000（=100%），所以这里只用 prob>0 作「该组有没有填」的判断；
            // 若哪天出现 0<prob<10000 的行，必须改成按 prob 掷骰（现在会把必给当默认值）。
            int i = 16;
            for (int g = 0; g < 10 && i + 2 < cols.length; g++) {
                String ori = cols[i].trim();
                int count = toInt(cols, i + 1);
                int prob = toInt(cols, i + 2);
                i += 3;
                if (count > 0 && prob > 0 && ori != null && !ori.isEmpty() && !"0".equals(ori)) {
                    row.drops.add(new GoodsDrop(ori, count));
                }
            }
            if (row.layer > 0 && row.regionResId > 0) {
                bctByLayer.put(Integer.valueOf(row.layer), row);
            }
        }
    }

    private void parseHundredTowerCommon(String text) {
        bctCommon = new BctCommon();
        if (text == null) {
            return;
        }
        List<String> vals = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String[] cols = line.trim().split("[ \\t]+");
            if (cols.length < 2) {
                continue;
            }
            vals.add(cols[cols.length - 1]);
        }
        if (vals.size() >= 6) {
            bctCommon.enableLevel = toInt(vals.get(0));
            bctCommon.freeTimes = Math.max(1, toInt(vals.get(1)));
            bctCommon.resetSaoDangCut = Math.max(0, toInt(vals.get(3)));
            bctCommon.buyTimesDiamond = Math.max(0, toInt(vals.get(4)));
            bctCommon.saoDangDiamond = Math.max(0, toInt(vals.get(5)));
            bctCommon.resetDays.clear();
            for (String p : vals.get(2).split("_")) {
                int d = toInt(p);
                if (d == 0) {
                    d = 7;
                }
                if (d >= 1 && d <= 7) {
                    bctCommon.resetDays.add(Integer.valueOf(d));
                }
            }
        }
    }

    private void parseResourceFbData(String text) {
        resourceFbByType.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 4) {
                continue;
            }
            ResourceFbCfg row = new ResourceFbCfg();
            row.type = toInt(cols, 1);
            row.cdSec = toInt(cols, 3);
            String days = cols.length > 2 ? cols[2].trim() : "0";
            for (String p : days.split("_")) {
                int d = toInt(p);
                if (d >= 1 && d <= 7) {
                    row.doubleDays.add(Integer.valueOf(d));
                }
            }
            if (row.type > 0) {
                resourceFbByType.put(Integer.valueOf(row.type), row);
            }
        }
    }

    private void parseResourceFbLevel(String text) {
        resourceFbLevel.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 4) {
                continue;
            }
            ResourceFbLevelCfg row = new ResourceFbLevelCfg();
            row.type = toInt(cols, 1);
            row.level = toInt(cols, 2);
            row.unlockLevel = toInt(cols, 3);
            row.unlockAfterMainFb = toInt(cols, 4);
            if (row.type > 0 && row.level > 0) {
                resourceFbLevel.put(Integer.valueOf(row.type * 10 + row.level), row);
            }
        }
    }

    /** 无行（镜像）或等级够则 true。unlockAfterMainFB 表全 0。 */
    public boolean resourceFbLevelOpen(int regionType, int level, int playerLevel) {
        ResourceFbLevelCfg c = resourceFbLevel.get(Integer.valueOf(regionType * 10 + level));
        if (c == null) {
            return true;
        }
        return playerLevel >= c.unlockLevel;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void parseDraw(String text) {
        if (text == null) {
            return;
        }
        List<String> values = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String[] cols = line.trim().split("[ \\t]+");
            if (cols.length == 3 && "#".equals(cols[0])) {
                values.add(cols[2]);
            }
        }
        if (values.size() >= 14) {
            draw = DrawConfig.parse(values);
        }
    }

    private static List<String[]> hashRows(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String[] cols = line.trim().split("[ \\t]+");
            if (cols.length > 1 && "#".equals(cols[0])) {
                rows.add(cols);
            }
        }
        return rows;
    }

    static int toInt(String[] cols, int i) {
        if (i >= cols.length) {
            return 0;
        }
        try {
            return Integer.parseInt(cols[i].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static final class BobMoneyCfg {
        public int enterLevel = 20;
        public int enterAfterFb;
        public float coeffA = 65000f;
        public float coeffB = 6500f;
        public float coeffC = 1.3f;
        public final float[] cupRatio = new float[]{0.2f, 0.22f, 0.24f, 0.3f};
        public final float[] stageRatio = new float[]{
                0.003f, 0.003f, 0.003f, 0.004f, 0.004f,
                0.004f, 0.004f, 0.005f, 0.005f, 0.005f
        };

        static BobMoneyCfg fallback() {
            return new BobMoneyCfg();
        }

        float stageRatio(int stage1to10) {
            int i = Math.max(1, Math.min(10, stage1to10)) - 1;
            return stageRatio[i];
        }

        float cupRatio(int cup0to3) {
            int i = Math.max(0, Math.min(3, cup0to3));
            return cupRatio[i];
        }
    }

    /**
     * ZhengBaZhan.txt：开启等级、日时段、下注金额、决战名次奖。
     * 行序对齐 ZBZPropertyCfgMgr.Load。
     */
    public static final class ZbzCfg {
        public int openLevel = 40;
        /** 表：获胜/失败基础分。 */
        public int winBaseScore = 10;
        public int failBaseScore = 5;
        /** 表：星级积分系数 / 战斗积分系数 / 匹配浮动值（产品拍板接入积分与匹配）。 */
        public float starScoreCoeff = 0.5f;
        public float fightScoreCoeff = 0.01f;
        public int matchFloat = 10;
        /** 排位赛日开启/结束（分钟）。 */
        public int pwsBeginMin = 10 * 60;
        public int pwsEndMin = 23 * 60 + 20;
        /** 分钟自 0 点：下注开始 / 8进4起止 / 4进2止 / 决赛止。 */
        public int yaZhuMin = 6 * 60;
        public int round8BeginMin = 19 * 60;
        public int round8EndMin = 19 * 60 + 20;
        public int round4EndMin = 19 * 60 + 40;
        public int finalEndMin = 20 * 60;
        public int gambleGold = 500000;
        public int gambleDiamond = 100;
        public final float[] gambleRateLo = {1.2f, 1.5f, 2f, 2.5f, 2.5f, 3f, 3.5f, 3.5f};
        public final float[] gambleRateHi = {1.5f, 2f, 2.5f, 3f, 3.2f, 3.5f, 3.8f, 4f};
        /** index0=8强 … index3=冠军。 */
        public final ZbzPrize[] placePrize = new ZbzPrize[]{
                ZbzPrize.of(2500000, 0, 2400, 13500, "ZBSX02", 60),
                ZbzPrize.of(2900000, 0, 2800, 15750, "ZBSX02", 70),
                ZbzPrize.of(3350000, 0, 3200, 18000, "ZBSX02", 80),
                ZbzPrize.of(4200000, 0, 4000, 22500, "ZBSX02", 100)
        };
        public final ZbzPrize pwsWin = ZbzPrize.of(145000, 0, 170, 625, null, 0);
        public final ZbzPrize pwsFail = ZbzPrize.of(110000, 0, 125, 470, null, 0);
        public final ZbzPrize top8Win = ZbzPrize.of(145000, 0, 170, 625, null, 0);
        public final ZbzPrize top8Fail = ZbzPrize.of(110000, 0, 125, 470, null, 0);

        static ZbzCfg fallback() {
            return new ZbzCfg();
        }
    }

    public static final class ZbzPrize {
        public int gold;
        public int diamond;
        public int yingPo;
        public int wnsp;
        public String goodsOri = "";
        public int goodsCount;

        static ZbzPrize of(int gold, int diamond, int yingPo, int wnsp, String ori, int count) {
            ZbzPrize p = new ZbzPrize();
            p.gold = gold;
            p.diamond = diamond;
            p.yingPo = yingPo;
            p.wnsp = wnsp;
            p.goodsOri = ori == null ? "" : ori;
            p.goodsCount = count;
            return p;
        }
    }

    /** KuaFuZhanPrize 跨服奖：膜拜按名次；单场胜负。 */
    public static final class KfzPrizeCfg {
        public final Map<Integer, KfzMatchPrize> moBaiByRank = new HashMap<>();
        public KfzMatchPrize pysWin = KfzMatchPrize.of(200000, 100, "TS102", 1);
        public KfzMatchPrize pysFail = KfzMatchPrize.of(100000, 50, null, 0);
        public KfzMatchPrize dfzWin = KfzMatchPrize.of(200000, 100, "TS102", 1);
        public KfzMatchPrize dfzFail = KfzMatchPrize.of(100000, 50, null, 0);

        static KfzPrizeCfg fallback() {
            KfzPrizeCfg c = new KfzPrizeCfg();
            c.moBaiByRank.put(1, KfzMatchPrize.of(50000, 20, null, 0));
            c.moBaiByRank.put(2, KfzMatchPrize.of(30000, 10, null, 0));
            c.moBaiByRank.put(3, KfzMatchPrize.of(20000, 10, null, 0));
            return c;
        }

        public KfzMatchPrize moBai(int rank) {
            return moBaiByRank.get(rank);
        }
    }

    public static final class KfzMatchPrize {
        public int gold;
        /** 膜拜表「体力」列；单场奖可忽略。 */
        public int stamina;
        public int jjcScore;
        public String goodsOri = "";
        public int goodsCount;

        static KfzMatchPrize of(int gold, int jjc, String ori, int count) {
            KfzMatchPrize p = new KfzMatchPrize();
            p.gold = gold;
            p.jjcScore = jjc;
            p.goodsOri = ori == null ? "" : ori;
            p.goodsCount = count;
            return p;
        }
    }

    /** KuaFuZhanPrize 排位/巅峰排名奖励一行。 */
    public static final class KfzRankPrizeRow {
        public int rankCeiling;
        public int gold;
        public int diamond;
        public int jjc;
        public String goods1 = "";
        public int goods1Count;
        public String goods2 = "";
        public int goods2Count;
    }

    /** KuaFuZhanBase（对齐 KuaFuZhanBaseProperty）。 */
    public static final class KfzBaseCfg {
        public int openLevel = 50;
        public int winBaseScore = 12;
        public int loseBaseScore = 6;
        public float fightScoreA = 0.0005f;
        public float fightScoreB = 500f;
        public float maxFightScore = 7500f;
        public float scoreModifier = 1f;
        public int starMatchValue = 3;
        public int minPiPeiModifier = 7;
        public int maxPiPeiModifier = 12;
        public int serverOpenDayReq = 30;
        public int maxBattleRecordCnt = 32;
        public int round64BeginMin = 20 * 60 + 30;
        public int round64EndMin = 20 * 60 + 50;
        public int round32BeginMin = 20 * 60 + 50;
        public int round32EndMin = 21 * 60 + 10;
        public int round16BeginMin = 21 * 60 + 10;
        public int round16EndMin = 21 * 60 + 30;
        public int quarterBeginMin = 20 * 60 + 30;
        public int quarterEndMin = 20 * 60 + 50;
        public int semiBeginMin = 20 * 60 + 50;
        public int semiEndMin = 21 * 60 + 10;
        public int finalBeginMin = 21 * 60 + 10;
        public int finalEndMin = 21 * 60 + 30;

        static KfzBaseCfg fallback() {
            return new KfzBaseCfg();
        }
    }

    public static final class DrawConfig {
        public int levelRequire = 1;
        public int jbOnce = 10000;
        public int jbTen = 90000;
        public int zsOnce = 280;
        public int zsTen = 2680;
        public String extraFragment = "SP048";
        public int extraOnceMin = 1;
        public int extraOnceMax = 2;
        public int extraTenMin = 8;
        public int extraTenMax = 12;
        public int jbFreeTimes = 5;
        public int jbFreeCdSec = 86400;
        public int zsFreeCdSec = 86400;
        /** 常驻钻石十连折扣倍率，1 = 原价。 */
        public float zsTenDiscount = 1f;
        /** 前 N 次钻石十连用的倍率；N=0 则不用。 */
        public float zsTenFirstDiscount = 1f;
        public int zsTenFirstTimes = 0;

        static DrawConfig fallback() {
            return new DrawConfig();
        }

        static DrawConfig parse(List<String> v) {
            DrawConfig c = new DrawConfig();
            int i = 0;
            c.levelRequire = parseInt(v.get(i++), 1);
            c.jbOnce = parseInt(v.get(i++), 10000);
            c.jbTen = parseInt(v.get(i++), 90000);
            c.zsOnce = parseInt(v.get(i++), 280);
            c.zsTen = parseInt(v.get(i++), 2680);
            c.extraFragment = v.get(i++);
            if (c.extraFragment == null || "0".equals(c.extraFragment)) {
                c.extraFragment = "";
            }
            c.extraOnceMin = parseInt(v.get(i++), 1);
            c.extraOnceMax = parseInt(v.get(i++), 2);
            c.extraTenMin = parseInt(v.get(i++), 8);
            c.extraTenMax = parseInt(v.get(i++), 12);
            c.jbFreeTimes = parseInt(v.get(i++), 5);
            // 表和客户端都是分钟，倒计时再 ×60
            c.jbFreeCdSec = parseInt(v.get(i++), 5) * 60;
            c.zsFreeCdSec = parseInt(v.get(i++), 2880) * 60;
            if (i < v.size()) {
                c.zsTenDiscount = parseFloat(v.get(i++), 1f);
            }
            if (i < v.size()) {
                c.zsTenFirstDiscount = parseFloat(v.get(i++), 1f);
            }
            if (i < v.size()) {
                c.zsTenFirstTimes = parseInt(v.get(i++), 0);
            }
            return c;
        }

        public float zsTenZheKou(int diamondTenCount) {
            float k = (zsTenFirstTimes > 0 && diamondTenCount < zsTenFirstTimes)
                    ? zsTenFirstDiscount : zsTenDiscount;
            if (!(k > 0f) || Float.isNaN(k)) {
                return 1f;
            }
            return k;
        }

        public int zsTenZheKouPrice(int diamondTenCount) {
            float k = zsTenZheKou(diamondTenCount);
            if (k >= 0.999f) {
                return zsTen;
            }
            return Math.max(0, Math.round(zsTen * k));
        }

        private static int parseInt(String s, int def) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                return def;
            }
        }

        private static float parseFloat(String s, float def) {
            try {
                return Float.parseFloat(s.trim());
            } catch (Exception e) {
                return def;
            }
        }
    }

    public static final class BctCommon {
        public int enableLevel = 35;
        public int freeTimes = 3;
        public int resetSaoDangCut = 5;
        public int buyTimesDiamond = 50;
        public int saoDangDiamond;
        public final Set<Integer> resetDays = new HashSet<>();
    }

    public static final class ResourceFbLevelCfg {
        public int type;
        public int level;
        public int unlockLevel;
        public int unlockAfterMainFb;
    }

    public static final class ResourceFbCfg {
        public int type;
        public int cdSec;
        public final java.util.Set<Integer> doubleDays = new java.util.HashSet<>();
    }

    public static final class BctLayer {
        public int layer;
        public int regionResId;
        public int regionType;
        public int playerLevelLimit;
        public boolean serverFight;
        public int gold;
        public final List<GoodsDrop> drops = new ArrayList<>();
    }

    public static final class DropRow {
        public int regionId;
        public int difficult;
        public int gold;
        public int playerExp;
        public int wjExp;
        public final List<GoodsDrop> certain = new ArrayList<>();
        public final List<RandDrop> random = new ArrayList<>();
        public String specialOri = "";
        public int specialCount;
        public int firstWnspCount;
        public int firstYingPoCount;
        public int yinPoCount;
        public int yinPoRadio;
        public int wnspCount;
        public int wnspRadio;
        /** 不可触 31–37 第 10 格：仅 3 星/扫荡发，不进普通随机。 */
        public GoodsDrop star3Extra;

        static DropRow parse(String[] cols) {
            DropRow r = new DropRow();
            int i = 1;
            r.regionId = toInt(cols, i++);
            r.difficult = toInt(cols, i++);
            r.gold = toInt(cols, i++);
            r.playerExp = toInt(cols, i++);
            r.wjExp = toInt(cols, i++);
            i += 2; // CertainDropWuJiangIndex1/2
            for (int c = 0; c < 3; c++) {
                String ori = i < cols.length ? cols[i++] : "0";
                int cnt = toInt(cols, i++);
                if (cnt > 0 && ori != null && !ori.isEmpty() && !"0".equals(ori)) {
                    r.certain.add(new GoodsDrop(ori, cnt));
                }
            }
            r.firstWnspCount = toInt(cols, i++);
            r.firstYingPoCount = toInt(cols, i++);
            i += 2; // 全局掉落类别ID / 概率（GameText 无独立池表，致命/镰刀行物品概率多为 0）
            for (int g = 0; g < 10; g++) {
                String ori = i < cols.length ? cols[i++] : "0";
                int cnt = toInt(cols, i++);
                int prob = toInt(cols, i++);
                if (cnt <= 0 || ori == null || ori.isEmpty() || "0".equals(ori)) {
                    continue;
                }
                boolean untouchStar3 = r.regionId >= 31 && r.regionId <= 37 && g == 9;
                if (untouchStar3) {
                    r.star3Extra = new GoodsDrop(ori, cnt);
                } else {
                    r.random.add(new RandDrop(ori, cnt, prob));
                }
            }
            r.specialOri = i < cols.length ? cols[i++] : "0";
            r.specialCount = toInt(cols, i++);
            i += 5; // 1st-5st drop radio
            r.yinPoCount = toInt(cols, i++);
            r.yinPoRadio = toInt(cols, i++);
            r.wnspCount = toInt(cols, i++);
            r.wnspRadio = toInt(cols, i);
            return r;
        }

        public List<GoodsDrop> roll(Random rng, boolean firstClear) {
            List<GoodsDrop> out = new ArrayList<>();
            if (firstClear) {
                out.addAll(certain);
            }
            int scale = 100;
            for (RandDrop d : random) {
                if (d.prob > 100) {
                    scale = 10000;
                    break;
                }
            }
            for (RandDrop d : random) {
                if (rng.nextInt(scale) < d.prob) {
                    out.add(new GoodsDrop(d.ori, d.count));
                }
            }
            if (specialCount > 0 && specialOri != null && !specialOri.isEmpty() && !"0".equals(specialOri)
                    && rng.nextInt(100) < 20) {
                out.add(new GoodsDrop(specialOri, specialCount));
            }
            return out;
        }

        public int rollWnsp(Random rng, boolean firstClear) {
            int n = firstClear ? Math.max(0, firstWnspCount) : 0;
            if (wnspRadio > 0 && rng.nextInt(10000) < wnspRadio) {
                n += Math.max(1, wnspCount);
            }
            return n;
        }

        public int rollYingPo(Random rng, boolean firstClear) {
            int n = firstClear ? Math.max(0, firstYingPoCount) : 0;
            if (yinPoRadio > 0 && rng.nextInt(10000) < yinPoRadio) {
                n += Math.max(1, yinPoCount);
            }
            return n;
        }
    }

    public static final class GoodsDrop {
        public final String ori;
        public final int count;

        public GoodsDrop(String ori, int count) {
            this.ori = ori;
            this.count = count;
        }
    }

    public static final class RandDrop {
        public final String ori;
        public final int count;
        public final int prob;

        public RandDrop(String ori, int count, int prob) {
            this.ori = ori;
            this.count = count;
            this.prob = prob;
        }
    }
}
