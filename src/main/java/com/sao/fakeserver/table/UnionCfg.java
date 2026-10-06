package com.sao.fakeserver.table;

import com.sao.fakeserver.config.SaoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D6 公会域 APK 权威表（全部来自客户端 GameData，文件名与客户端一致，放在 {@code tables\} 下）。
 *
 * <ul>
 *   <li>{@code Union.txt}：公会常量（开启等级/创建消耗/人数上限/捐献/成长与贡献系数/建筑解锁与使用等级/成长上限）。</li>
 *   <li>{@code UnionBuildingLevelUp.txt}：每建筑每级的升级时间、晶石、成长需求、议事厅需求、成长产出、酬劳/小时。</li>
 *   <li>{@code UnionBoss.txt}：5 个章节 Boss 的开启作战室等级、单场奖励、结算奖励（按排名）、显示掉落、刷新消耗晶石。</li>
 *   <li>{@code UnionMaJiuBase.txt} / {@code UnionMaJiuTarget.txt} / {@code UnionMaJiuTime.txt}：押镖时刻表、7 个目的地奖励。</li>
 *   <li>{@code UnionXunLian.txt} / {@code UnionXunLianPits.txt}：训练时长、四种训练室倍率与消耗系数、经验公式系数、训练场坑位。</li>
 *   <li>{@code UnionChuFangTiLi.txt} / {@code UnionYiYuanReliveTime.txt}：厨房各级可领体力、医院各级复活缩短秒数。</li>
 *   <li>{@code UnionWJSubsidiary.txt}：雇佣职业（0 作战室 / 1 教练 / 2 医师 / 3 厨师）各级酬劳、冷却、属性参数。</li>
 * </ul>
 *
 * 这些表都是客户端自带的 GameData 表（客户端侧 {@code UnionProperty}/{@code UnionBuildingLevelUpCfg} 等同表同列），
 * 所以「按 APK 逻辑」即以本类解析出的数值为准，替代此前服务端硬编码。
 */
@Component
public class UnionCfg {
    private static final Logger log = LoggerFactory.getLogger(UnionCfg.class);

    /** Union.txt：功能开启等级限制。 */
    private int openLevel = 15;
    /** Union.txt：创建公会 RMB 消耗。 */
    private int createRmb = 1000;
    private int elderMax = 3;
    private int memberMax = 40;
    private int requestMax = 100;
    private int quitCdSec = 28800;
    private int kickCdSec = 7200;
    /** Union.txt：单次捐赠金币数。 */
    private int donateGold = 30000;
    /** Union.txt：单次捐赠兑换兄弟币。 */
    private int donateBrotherCoin = 30;
    /** Union.txt：单次捐赠兑换增加的公会晶石。 */
    private int donateCrystal = 50;
    /** Union.txt：成员消耗 1 点体力值公会获得的成长值。 */
    private int staminaGrow = 1;
    /** Union.txt：公会取消升级返还晶石比例。 */
    private double cancelRefundRatio = 0.5d;
    /** Union.txt：每日挑战 boss 次数。 */
    private int bossMaxTimes = 2;
    /** Union.txt：作战室拍卖结算时间（24 小时制小时）。 */
    private int auctionSettleHour = 20;
    /** Union.txt：酬劳战力系数（作战室佣金 = 战斗力 * 本系数 * 雇佣酬劳）。 */
    private double choulaoCoef = 0.008125d;
    /** Union.txt：单个建筑玩家总收益上限。 */
    private int buildingProfitCap = 3000000;
    /** Union.txt：玩家提供的公会成长值转换为玩家公会贡献系数。 */
    private int growToContri = 30;
    /** Union.txt：玩家提供的公会晶石转换为玩家公会贡献系数。 */
    private int crystalToContri = 10;
    /** Union.txt：公会成长值上限。 */
    private int growCap = 3900950;
    private int fightRankRefreshMin = 60;
    private int growRankRefreshMin = 45;

    /** 建筑类型（EUnionBuildingType）→ 解锁所需议事厅等级。 */
    private final Map<Integer, Integer> unlockHallLevel = new HashMap<>();
    /** 建筑类型 → 使用所需攻略组等级。 */
    private final Map<Integer, Integer> usePlayerLevel = new HashMap<>();

    /** "type/level" → 建筑升级行。 */
    private final Map<String, BuildingRow> buildings = new HashMap<>();
    /** 建筑类型 → 最大等级。 */
    private final Map<Integer, Integer> buildingMaxLevel = new HashMap<>();
    /** 章节 → Boss 行。 */
    private final Map<Integer, BossRow> bosses = new LinkedHashMap<>();
    /** 目的地 ID → 押镖目的地行。 */
    private final Map<Integer, MaJiuTargetRow> majiuTargets = new LinkedHashMap<>();
    /** 厨房等级 → 可领体力。 */
    private final Map<Integer, Integer> kitchenStamina = new HashMap<>();
    /** 训练场等级 → 最大坑位。 */
    private final Map<Integer, Integer> trainPits = new HashMap<>();
    /** 医院等级 → 复活缩短秒数。 */
    private final Map<Integer, Integer> hospitalReliveSec = new HashMap<>();
    /** 押镖发镖开始时间。 */
    private int majiuStartHour = 18;
    private int majiuStartMinute = 0;
    /** 押镖/劫镖各持续秒数。 */
    private int majiuSendSec = 7200;
    private int majiuRaidSec = 7200;
    /** 押镖单次活动有效掠夺场次。 */
    private int raidTimes = 3;
    /** 「单次活动刷新次数」= 2（UnionMaJiuBase.txt 第 1 行）。 */
    private int majiuResetTimes = 2;
    /** 押镖发镖参与奖（兄弟币）。 */
    private int sendBrotherCoin = 100;
    /** 劫掠资金兑换公会晶石比例系数。 */
    private double raidXdbRatio = 0.0007d;
    /** 单个运镖成功公会成长值。 */
    private int sendGrow = 200;
    /** 单次劫镖成功公会成长值。 */
    private int raidGrow = 100;
    /** 掠夺排行兄弟币奖励（排名 → 兄弟币）。 */
    private final Map<Integer, Integer> raidRankBrother = new LinkedHashMap<>();
    /** 掠夺获取资源比例（线路金币的百分比）。 */
    private double raidGainRatio = 0.2d;
    /** 运镖单次被劫扣除比例。 */
    private double beRaidLoseRatio = 0.2d;
    /** 成功防守一次获得金币奖励（线路金币百分比）。 */
    private double defendGoldRatio;
    /** 单场掠夺超时时间（秒）。 */
    private int raidTimeoutSec = 300;
    /** 马厩等级 → 单次刷新所需公会晶石。 */
    private final Map<Integer, Integer> majiuResetCrystal = new LinkedHashMap<>();
    /** 训练时长（秒）。 */
    private int trainSec = 28800;
    /** ETrainingType（0 普通/1 白银/2 黄金/3 铂金）→ 经验倍率。 */
    private final double[] trainExpRatio = {2.5d, 3.75d, 5d, 7.5d};
    /** ETrainingType → 消耗系数。 */
    private final double[] trainCostRatio = {1.25d, 1d, 1000d, 800d};
    /** 训练场每小时经验：基础系数 A / 等级系数 B / 指数系数 C。 */
    private double trainBaseA = 300d;
    private double trainLevelB = 20d;
    private double trainExpC = 1.3d;
    /** "职业/级别" → 雇佣行。 */
    private final Map<String, SubsidiaryRow> subsidiaries = new HashMap<>();

    /** UnionPvP.txt：报名消耗的公会晶石。 */
    private int pvpEnrollCrystal = 500;
    /** UnionPvP.txt：胜利/失败获得的公会成长值。 */
    private int pvpWinGrow = 50000;
    private int pvpLoseGrow = 20000;
    /** UnionPvP.txt：胜利/失败获得的公会晶石。 */
    private int pvpWinCrystal = 1500;
    private int pvpLoseCrystal = 1000;
    /** UnionPvP.txt：胜利/失败个人获得的物品（原始名）。 */
    private String pvpWinGoods = "UFTS2";
    private String pvpLoseGoods = "UFTS3";
    /** UnionPvP.txt：胜利/失败个人获得的兄弟币基础奖励。 */
    private int pvpWinBrother = 1000;
    private int pvpLoseBrother = 500;
    /** UnionPvP.txt：攻占差系数。 */
    private double pvpCaptureRatio = 0.08d;
    /** UnionPvP.txt：个人打下单个部队数获得兄弟币。 */
    private int pvpUnitBrother = 50;
    /** UnionPvP.txt：个人打下单个部队数获得物品。 */
    private String pvpUnitGoods = "UFTS1";
    /** UnionPvPDefPointsInfo.txt：据点 ID → 据点名（插入顺序即地图顺序，共 10 个）。 */
    private final Map<Integer, String> pvpPoints = new LinkedHashMap<>();
    /**
     * UnionPvPDefPointsInfo.txt 第 3 列「下一个据点」反查出的**前置据点**：1←0、2←1、3←2、4←2、
     * 5←3、6←3、7←4、8←4、9←6，根据点 0 无前置。客户端
     * {@code UnionPvPDefPointsInfoMgr.GetPrevPointIndex} 就是这条反查（APK
     * MobileGameDemo\UnionPvPDefPointsInfoMgr.cs:93-103），`GongHuiZhanJuDianBoard.cs:77-88` 在
     * 「既未攻占也不可攻占」时用前置据点名弹 100744「先攻占 {0}」。
     */
    private final Map<Integer, Integer> pvpPrevPoint = new LinkedHashMap<>();
    /** UnionPvPTime.txt：开战时刻与持续秒数（7 行同值）。 */
    private int pvpStartHour = 19;
    private int pvpStartMinute = 0;
    private int pvpDurationSec = 3600;
    /** UnionPvPTime.txt：公会战结束之后多少时间开放报名（秒）。 */
    private int pvpEnrollOpenAfterSec = 3600;
    /** UnionPvPTime.txt：世界广播公会战开始提前时间(秒)。 */
    private int pvpBroadcastBeforeSec = 1800;
    /** UnionPvPTime.txt：世界广播公会战结束时间(秒)。 */
    private int pvpBroadcastEndSec = 1800;
    /**
     * UnionPvPTime.txt：开战日（第 1 列星期，0=周日）。**只收首列为 {@code #} 的行**，
     * 与客户端 {@code UnionPvPTimeInfoMgr.cs:16-35} 的 {@code array2[0] == "#"} 同判据；
     * 出厂表只有 1/3/6（周一/三/六）三行带 {@code #}，故实际值为 {@code {1,3,6}}，
     * 不是「1,2,3,4,5,6,0 覆盖全周」（那是改前用 {@code numRows} 全收 7 行的旧行为，见
     * {@link #parsePvpTime(String)} 的 javadoc）。改了这张表的 {@code #} 列就会改公会战开战日。
     */
    private final Set<Integer> pvpDays = new LinkedHashSet<>();

    /** UnionBuildingLevelUp.txt 一行。 */
    public static class BuildingRow {
        public int type;
        public int level;
        /** 到下一级需要消耗的时间(秒)。 */
        public int timeSec;
        /** 到下一级需要消耗的晶石。 */
        public int crystal;
        /** 到下一级需要达到的公会成长。 */
        public int growNeed;
        /** 到下一级需要的议事厅等级。 */
        public int hallNeed;
        /** 到下一级得到的公会成长。 */
        public int growGain;
        /** 酬劳/小时。 */
        public int choulao;
    }

    /** UnionBoss.txt 一行。 */
    public static class BossRow {
        public int chapter;
        public String name;
        /** BOSS 原始名（MonsterProperty.ini 的键，客户端用它查血量上限）。 */
        public String ori;
        /** 开启本章节所需作战室等级。 */
        public int hallNeed;
        public int expBattle;
        public int expWj;
        /** 总金币池。 */
        public int gold;
        public int brotherCoin;
        public int courageCoin;
        public int growValue;
        public int settleCrystal;
        /** 结算兄弟币：排名阈值 → 兄弟币。 */
        public final Map<Integer, Integer> settleBrother = new LinkedHashMap<>();
        /** 结算勇气币：排名阈值 → 勇气币。 */
        public final Map<Integer, Integer> settleCourage = new LinkedHashMap<>();
        /** 显示掉落（客户端结算面板用）。 */
        public final List<Drop> drops = new ArrayList<>();
        /** 刷新消耗（公会晶石）。 */
        public int refreshCrystal;
    }

    /** 掉落一项。 */
    public static class Drop {
        public String ori;
        public int count;

        public Drop(String ori, int count) {
            this.ori = ori;
            this.count = count;
        }
    }

    /** UnionMaJiuTarget.txt 一行。 */
    public static class MaJiuTargetRow {
        public int id;
        public int majiuLevel;
        public int fightPower;
        public int gold;
        public String name;
        public String resName;
    }

    /** UnionWJSubsidiary.txt 一行。 */
    public static class SubsidiaryRow {
        /** 0 无（作战室）；1 教练；2 医师；3 厨师。 */
        public int profession;
        public int level;
        public int fightPowerCap;
        /** 雇佣酬劳。 */
        public int price;
        public int cdSec;
        public double gainCoef;
        /** 属性参数：厨师体力 / 教练经验提速(万分比) / 医生复活缩短秒数。 */
        public int attr;
    }

    private final SaoProperties props;

    public UnionCfg(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Path dir = Paths.get(props.getTablesDir());
        parseUnion(text(dir, "Union.txt"));
        parseBuildings(text(dir, "UnionBuildingLevelUp.txt"));
        parseBosses(text(dir, "UnionBoss.txt"));
        parseMaJiuBase(text(dir, "UnionMaJiuBase.txt"));
        parseMaJiuTargets(text(dir, "UnionMaJiuTarget.txt"));
        parseMaJiuTime(text(dir, "UnionMaJiuTime.txt"));
        parseKitchen(text(dir, "UnionChuFangTiLi.txt"));
        parsePits(text(dir, "UnionXunLianPits.txt"));
        parseRelive(text(dir, "UnionYiYuanReliveTime.txt"));
        parseTrain(text(dir, "UnionXunLian.txt"));
        parseSubsidiary(text(dir, "UnionWJSubsidiary.txt"));
        parsePvp(text(dir, "UnionPvP.txt"));
        parsePvpPoints(text(dir, "UnionPvPDefPointsInfo.txt"));
        parsePvpTime(text(dir, "UnionPvPTime.txt"));
        log.info("union cfg openLevel={} createRmb={} memberMax={} donateGold={} bossTimes={} buildings={} bosses={} targets={} subsidiaries={}",
                openLevel, createRmb, memberMax, donateGold, bossMaxTimes, buildings.size(), bosses.size(),
                majiuTargets.size(), subsidiaries.size());
    }

    private static String text(Path dir, String name) {
        Path p = dir.resolve(name);
        if (!Files.isRegularFile(p)) {
            log.warn("missing union table {}", p);
            return null;
        }
        try {
            return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            log.warn("read union table {} failed: {}", p, e.toString());
            return null;
        }
    }

    // ---------------------------------------------------------------- Union.txt

    private void parseUnion(String text) {
        if (text == null) {
            return;
        }
        Map<String, String> kv = keyValues(text);
        openLevel = intOf(kv, "功能开启等级限制", openLevel);
        createRmb = intOf(kv, "创建公会RMB消耗", createRmb);
        elderMax = intOf(kv, "长老人数上限", elderMax);
        memberMax = intOf(kv, "成员人数上限", memberMax);
        requestMax = intOf(kv, "最大申请列表", requestMax);
        quitCdSec = intOf(kv, "退出公会->新公会CD时间(秒)", quitCdSec);
        kickCdSec = intOf(kv, "踢出公会->新公会CD时间(秒)", kickCdSec);
        donateGold = intOf(kv, "单次捐赠金币数", donateGold);
        donateBrotherCoin = intOf(kv, "单次捐赠兑换兄弟币", donateBrotherCoin);
        donateCrystal = intOf(kv, "单次捐赠兑换增加的公会晶石", donateCrystal);
        staminaGrow = intOf(kv, "成员消耗1点体力值公会获得的成长值(必须为整形)", staminaGrow);
        cancelRefundRatio = doubleOf(kv, "公会取消升级返还晶石比例", cancelRefundRatio);
        bossMaxTimes = intOf(kv, "每日挑战boss次数", bossMaxTimes);
        auctionSettleHour = intOf(kv, "作战室拍卖结算时间（24小时制小时）", auctionSettleHour);
        choulaoCoef = doubleOf(kv, "酬劳战力系数", choulaoCoef);
        buildingProfitCap = intOf(kv, "单个建筑玩家总收益上限", buildingProfitCap);
        growToContri = intOf(kv, "玩家提供的公会成长值转换为玩家公会贡献系数", growToContri);
        crystalToContri = intOf(kv, "玩家提供的公会晶石转换为玩家公会贡献系数", crystalToContri);
        fightRankRefreshMin = intOf(kv, "公会战力排行榜刷新时间（分钟，考虑效率，建议不要低于1小时）", fightRankRefreshMin);
        growRankRefreshMin = intOf(kv, "公会成长值排行榜刷新时间（分钟，考虑效率，建议不要低于半小时）", growRankRefreshMin);
        growCap = intOf(kv, "公会成长值上限", growCap);
        unlockHallLevel.put(Integer.valueOf(2), Integer.valueOf(intOf(kv, "厨房解锁所需议事厅等级", 1)));
        usePlayerLevel.put(Integer.valueOf(2), Integer.valueOf(intOf(kv, "使用厨房所需攻略组等级", 15)));
        unlockHallLevel.put(Integer.valueOf(3), Integer.valueOf(intOf(kv, "训练场解锁所需议事厅等级", 3)));
        usePlayerLevel.put(Integer.valueOf(3), Integer.valueOf(intOf(kv, "使用训练场所需攻略组等级", 28)));
        unlockHallLevel.put(Integer.valueOf(4), Integer.valueOf(intOf(kv, "商城解锁所需议事厅等级", 1)));
        usePlayerLevel.put(Integer.valueOf(4), Integer.valueOf(intOf(kv, "使用商城所需攻略组等级", 20)));
        unlockHallLevel.put(Integer.valueOf(5), Integer.valueOf(intOf(kv, "马厩解锁所需议事厅等级", 2)));
        usePlayerLevel.put(Integer.valueOf(5), Integer.valueOf(intOf(kv, "使用马厩所需攻略组等级", 25)));
        unlockHallLevel.put(Integer.valueOf(6), Integer.valueOf(intOf(kv, "作战室解锁所需议事厅等级", 1)));
        usePlayerLevel.put(Integer.valueOf(6), Integer.valueOf(intOf(kv, "使用作战室所需攻略组等级", 15)));
        unlockHallLevel.put(Integer.valueOf(7), Integer.valueOf(intOf(kv, "医院解锁所需议事厅等级", 4)));
        usePlayerLevel.put(Integer.valueOf(7), Integer.valueOf(intOf(kv, "使用医院所需攻略组等级", 28)));
        // 议事厅自身（type 1）解锁/使用等级按 0 处理。
        unlockHallLevel.put(Integer.valueOf(1), Integer.valueOf(0));
        usePlayerLevel.put(Integer.valueOf(1), Integer.valueOf(0));
    }

    /**
     * 键值表解析：最后一列是值，前面的全部是键（Union.txt 的键里可能带空格，
     * 例如「作战室拍卖结算时间（24小时制小时） 20」）。
     */
    private static Map<String, String> keyValues(String text) {
        Map<String, String> map = new HashMap<>();
        for (String line : text.split("\r?\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            int cut = -1;
            for (int i = s.length() - 1; i >= 0; i--) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t') {
                    cut = i;
                    break;
                }
            }
            if (cut <= 0) {
                continue;
            }
            String key = s.substring(0, cut).trim();
            String val = s.substring(cut + 1).trim();
            if (!key.isEmpty() && !val.isEmpty()) {
                map.put(key, val);
            }
        }
        return map;
    }

    // ------------------------------------------------- UnionBuildingLevelUp.txt

    private void parseBuildings(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : numRows(text)) {
            BuildingRow r = new BuildingRow();
            r.type = toInt(cols, 0);
            r.level = toInt(cols, 1);
            r.timeSec = toInt(cols, 2);
            r.crystal = toInt(cols, 3);
            r.growNeed = toInt(cols, 4);
            r.hallNeed = toInt(cols, 5);
            r.growGain = toInt(cols, 6);
            r.choulao = toInt(cols, 7);
            if (r.type <= 0 || r.level <= 0) {
                continue;
            }
            buildings.put(r.type + "/" + r.level, r);
            Integer max = buildingMaxLevel.get(Integer.valueOf(r.type));
            if (max == null || r.level > max.intValue()) {
                buildingMaxLevel.put(Integer.valueOf(r.type), Integer.valueOf(r.level));
            }
        }
    }

    // ------------------------------------------------------------- UnionBoss.txt

    private void parseBosses(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : numRows(text)) {
            BossRow r = new BossRow();
            r.chapter = toInt(cols, 0);
            if (r.chapter <= 0) {
                continue;
            }
            r.name = str(cols, 1);
            r.ori = str(cols, 2);
            r.hallNeed = toInt(cols, 3);
            r.expBattle = toInt(cols, 4);
            r.expWj = toInt(cols, 5);
            r.gold = toInt(cols, 6);
            r.brotherCoin = toInt(cols, 7);
            r.courageCoin = toInt(cols, 8);
            r.growValue = toInt(cols, 9);
            r.settleCrystal = toInt(cols, 10);
            parseRankMap(str(cols, 11), r.settleBrother);
            parseRankMap(str(cols, 12), r.settleCourage);
            for (int i = 0; i < 5; i++) {
                String ori = toOri(cols, 14 + i * 2);
                int count = toInt(cols, 15 + i * 2);
                if (!ori.isEmpty() && count > 0) {
                    r.drops.add(new Drop(ori, count));
                }
            }
            r.refreshCrystal = toInt(cols, 25);
            bosses.put(Integer.valueOf(r.chapter), r);
        }
    }

    /** 解析 {@code 1:320_2:270_10:170} 形式的排名阈值表（阈值 → 值）。 */
    private static void parseRankMap(String text, Map<Integer, Integer> out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (String part : text.split("_")) {
            int c = part.indexOf(':');
            if (c <= 0) {
                continue;
            }
            int rank = toInt(part.substring(0, c), 0);
            int val = toInt(part.substring(c + 1), 0);
            if (rank > 0) {
                out.put(Integer.valueOf(rank), Integer.valueOf(val));
            }
        }
    }

    // ------------------------------------- UnionMaJiuBase / Target / Time

    private void parseMaJiuBase(String text) {
        if (text == null) {
            return;
        }
        Map<String, String> kv = keyValues(text);
        raidTimes = intOf(kv, "单次活动有效掠夺场次（不管成功还是失败）", raidTimes);
        sendBrotherCoin = intOf(kv, "发镖参与的兄弟币奖励（发镖参与奖）", sendBrotherCoin);
        raidXdbRatio = doubleOf(kv, "劫掠资金兑换的公会晶石比例系数", raidXdbRatio);
        sendGrow = intOf(kv, "单个运镖成功公会成长值增加", sendGrow);
        raidGrow = intOf(kv, "单次劫镖成功公会成长值增加", raidGrow);
        raidGainRatio = doubleOf(kv, "掠夺获取资源比例", raidGainRatio);
        beRaidLoseRatio = doubleOf(kv, "运镖单次被劫扣除比例", beRaidLoseRatio);
        defendGoldRatio = doubleOf(kv, "成功防守一次获得金币奖励（线路提供的金币奖励的百分比）", defendGoldRatio);
        raidTimeoutSec = intOf(kv, "单场掠夺超时时间（秒）", raidTimeoutSec);
        // 「单次活动刷新次数」= 2：客户端 MaJiuShuaXinUI.cs:69-71 用 refreshTimePerActive-curResetTime
        // 显示剩余次数，但拦不拦得住全看服务端（改前 1547 无上限，可无限刷新目的地）。
        majiuResetTimes = intOf(kv, "单次活动刷新次数", majiuResetTimes);
        for (int lv = 1; lv <= 7; lv++) {
            int cost = intOf(kv, "等级" + lv + "刷新所需公会晶石", 0);
            majiuResetCrystal.put(Integer.valueOf(lv), Integer.valueOf(cost));
        }
        parseRankMap(kv.get("掠夺排行兄弟币奖励"), raidRankBrother);
    }

    private void parseMaJiuTargets(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : numRows(text)) {
            MaJiuTargetRow r = new MaJiuTargetRow();
            r.id = toInt(cols, 0);
            if (r.id <= 0) {
                continue;
            }
            r.majiuLevel = toInt(cols, 1);
            r.fightPower = toInt(cols, 2);
            r.gold = toInt(cols, 3);
            r.name = str(cols, 4);
            r.resName = str(cols, 5);
            majiuTargets.put(Integer.valueOf(r.id), r);
        }
    }

    /**
     * UnionMaJiuTime.txt：发镖开始时刻 / 发镖与劫镖持续秒数。
     * 列序（含输出符列）：0 {@code #} / 1 星期(0=周日) / 2 小时 / 3 分 / 4 发镖持续秒 / 5 劫镖持续秒 /
     * 6..11 三段世界广播的提前秒与间隔秒。
     *
     * <p>**故意不看第 0 列 `#`、也不判星期**：真服表里只有 2/4/5（周二/四/五）三行带 {@code #}
     * （客户端 {@code UnionMaJiuTimeInfoMgr.cs:22} 只收 `#` 行），但**用户 m19006 #1 拍板：假服押镖
     * 每天都开，`#` 星期过滤不实现、也不用改回去**（见 docs\SYSTEM_MAJIU.md:28-30/§6 ①）。
     * 7 行时刻数值完全一致，故取第一行即可。
     *
     * <p>第 6..11 列「世界广播提前/间隔」是死数据（客户端 `UnionMaJiuTimeInfo.cs` 解析后无消费方），
     * 只解析、不使用。
     */
    private void parseMaJiuTime(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : numRows(text)) {
            int week = toInt(cols, 0);
            if (week < 0 || week > 6) {
                continue;
            }
            majiuStartHour = toInt(cols, 1);
            majiuStartMinute = toInt(cols, 2);
            majiuSendSec = toInt(cols, 3);
            majiuRaidSec = toInt(cols, 4);
            return; // 7 行完全一致（周 0-6 同表），取第一行即可
        }
    }

    // ------------------------------------------- 厨房 / 训练场 / 医院 / 雇佣

    private void parseKitchen(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : numRows(text)) {
            int level = toInt(cols, 0);
            if (level > 0) {
                kitchenStamina.put(Integer.valueOf(level), Integer.valueOf(toInt(cols, 1)));
            }
        }
    }

    private void parsePits(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : numRows(text)) {
            int level = toInt(cols, 0);
            if (level > 0) {
                trainPits.put(Integer.valueOf(level), Integer.valueOf(toInt(cols, 1)));
            }
        }
    }

    private void parseRelive(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : numRows(text)) {
            int level = toInt(cols, 0);
            if (level > 0) {
                hospitalReliveSec.put(Integer.valueOf(level), Integer.valueOf(toInt(cols, 1)));
            }
        }
    }

    private void parseTrain(String text) {
        if (text == null) {
            return;
        }
        Map<String, String> kv = keyValues(text);
        trainSec = intOf(kv, "训练时长(秒)", trainSec);
        trainExpRatio[0] = doubleOf(kv, "普通训练室经验倍率", trainExpRatio[0]);
        trainExpRatio[1] = doubleOf(kv, "白银训练室经验倍率", trainExpRatio[1]);
        trainExpRatio[2] = doubleOf(kv, "黄金训练室经验倍率", trainExpRatio[2]);
        trainExpRatio[3] = doubleOf(kv, "铂金训练室经验倍率", trainExpRatio[3]);
        trainCostRatio[0] = doubleOf(kv, "普通训练室消耗系数", trainCostRatio[0]);
        trainCostRatio[1] = doubleOf(kv, "白银训练室消耗系数", trainCostRatio[1]);
        trainCostRatio[2] = doubleOf(kv, "黄金训练室消耗系数", trainCostRatio[2]);
        trainCostRatio[3] = doubleOf(kv, "铂金训练室消耗系数", trainCostRatio[3]);
        trainBaseA = doubleOf(kv, "训练场每小时获取经验基础系数A", trainBaseA);
        trainLevelB = doubleOf(kv, "训练场每小时获取经验等级系数B", trainLevelB);
        trainExpC = doubleOf(kv, "训练场每小时获取经验指数系数C", trainExpC);
    }

    private void parseSubsidiary(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : numRows(text)) {
            SubsidiaryRow r = new SubsidiaryRow();
            r.profession = toInt(cols, 0);
            r.level = toInt(cols, 1);
            r.fightPowerCap = toInt(cols, 2);
            r.price = toInt(cols, 3);
            r.cdSec = toInt(cols, 4);
            r.gainCoef = toDouble(cols, 5, 0d);
            r.attr = toInt(cols, 6);
            subsidiaries.put(r.profession + "/" + r.level, r);
        }
    }

    // ------------------------------------------------- UnionPvP*.txt

    /** UnionPvP.txt（键值表）：报名消耗、胜负的公会成长/晶石、个人物品与兄弟币。 */
    private void parsePvp(String text) {
        if (text == null) {
            return;
        }
        Map<String, String> kv = keyValues(text);
        pvpEnrollCrystal = intOf(kv, "报名消耗的公会晶石", pvpEnrollCrystal);
        pvpWinGrow = intOf(kv, "胜利获得的公会成长值", pvpWinGrow);
        pvpLoseGrow = intOf(kv, "失败获得的公会成长值", pvpLoseGrow);
        pvpWinCrystal = intOf(kv, "胜利获得的公会晶石", pvpWinCrystal);
        pvpLoseCrystal = intOf(kv, "失败获得的公会晶石", pvpLoseCrystal);
        String win = kv.get("胜利个人获得的物品");
        String lose = kv.get("失败个人获得的物品");
        if (win != null && !win.isEmpty()) {
            pvpWinGoods = win;
        }
        if (lose != null && !lose.isEmpty()) {
            pvpLoseGoods = lose;
        }
        pvpWinBrother = intOf(kv, "胜利个人获得的兄弟币基础奖励", pvpWinBrother);
        pvpLoseBrother = intOf(kv, "失败个人获得的兄弟币基础奖励", pvpLoseBrother);
        pvpCaptureRatio = doubleOf(kv, "攻占差系数", pvpCaptureRatio);
        pvpUnitBrother = intOf(kv, "个人打下单个部队数获得兄弟币", pvpUnitBrother);
        String unit = kv.get("个人打下单个部队数获得物品(多个物品通过|分隔)");
        if (unit != null && !unit.isEmpty()) {
            pvpUnitGoods = unit;
        }
    }

    /**
     * UnionPvPDefPointsInfo.txt：据点 ID → 名字（列：据点ID / 据点名字 / 下一个据点 / 路点特效 / 节点资源名）。
     * 第 3 列「下一个据点」形如 {@code 3;4}（分叉）或 {@code ;}（终点），本方法反查出
     * {@link #pvpPrevPoint} 供攻占资格判定使用。
     */
    private void parsePvpPoints(String text) {
        if (text == null) {
            return;
        }
        for (String[] cols : numRows(text)) {
            int id = toInt(cols, 0);
            pvpPoints.put(id, str(cols, 1));
            for (String next : str(cols, 2).split(";")) {
                String t = next.trim();
                if (!t.isEmpty() && isInt(t)) {
                    pvpPrevPoint.put(Integer.valueOf(Integer.parseInt(t)), Integer.valueOf(id));
                }
            }
        }
    }

    /**
     * UnionPvPTime.txt：开战星期/时刻/持续秒数/广播提前与结束秒数/结束后开放报名秒数。
     * 列序（含输出符列）：0 {@code #} 启用开关 / 1 星期(0=周日) / 2 小时 / 3 分 / 4 持续秒 /
     * 5 广播开始提前秒 / 6 间隔 / 7 广播结束秒 / 8 间隔 / 9 报名开放秒。
     *
     * <p>只有首列为 {@code #} 的行生效 —— 客户端 {@code MobileGameDemo\UnionPvPTimeInfoMgr.cs:16-35}
     * 逐行 {@code Split} 后判 {@code array2[0] == "#"} 才加载，出厂表只有 1/3/6（周一/三/六）三行带
     * {@code #}。改前用 {@code numRows} 把 7 行全收 ⇒ {@code pvpDays = {0..6}} ⇒
     * {@code UnionService.pvpBattleDay()} 恒 true，公会战每天都结算。
     */
    private void parsePvpTime(String text) {
        if (text == null) {
            return;
        }
        boolean first = true;
        for (String line : text.split("\r?\n")) {
            String[] cols = line.trim().split("[ \\t]+");
            if (cols.length < 10 || !"#".equals(cols[0]) || !isInt(cols[1])) {
                continue;
            }
            pvpDays.add(Integer.valueOf(Integer.parseInt(cols[1])));
            if (first) {
                first = false;
                pvpStartHour = toInt(cols, 2);
                pvpStartMinute = toInt(cols, 3);
                pvpDurationSec = toInt(cols, 4);
                pvpBroadcastBeforeSec = toInt(cols, 5);
                pvpBroadcastEndSec = toInt(cols, 7);
                pvpEnrollOpenAfterSec = toInt(cols, 9);
            }
        }
    }

    // ------------------------------------------------------------- 行读取工具

    /**
     * 取「数据行」：跳过表头与说明行 —— 首列（允许先有一个 {@code #} 输出符）必须是整数。
     * APK 这几张表的 {@code #} 输出符列填得不全（例如 UnionMaJiuTime.txt 只有 3/7 行有），
     * 所以不能像其它表那样只认 {@code #} 开头的行。
     */
    private static List<String[]> numRows(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String[] cols = line.trim().split("[ \\t]+");
            if (cols.length < 2) {
                continue;
            }
            int i = "#".equals(cols[0]) ? 1 : 0;
            if (i >= cols.length || !isInt(cols[i])) {
                continue;
            }
            String[] rest = new String[cols.length - i];
            System.arraycopy(cols, i, rest, 0, rest.length);
            rows.add(rest);
        }
        return rows;
    }

    private static boolean isInt(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String str(String[] cols, int i) {
        return i < cols.length ? cols[i] : "";
    }

    private static int toInt(String[] cols, int i) {
        return i < cols.length ? toInt(cols[i], 0) : 0;
    }

    private static double toDouble(String[] cols, int i, double def) {
        if (i >= cols.length) {
            return def;
        }
        try {
            return Double.parseDouble(cols[i].trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String toOri(String[] cols, int i) {
        if (i >= cols.length || cols[i] == null) {
            return "";
        }
        String s = cols[i].trim();
        return "0".equals(s) ? "" : s;
    }

    private static int toInt(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException e2) {
                return def;
            }
        }
    }

    private static int intOf(Map<String, String> kv, String key, int def) {
        String v = kv.get(key);
        return v == null ? def : toInt(v, def);
    }

    private static double doubleOf(Map<String, String> kv, String key, double def) {
        String v = kv.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ 访问器

    public int openLevel() {
        return openLevel;
    }

    public int createRmb() {
        return createRmb;
    }

    public int elderMax() {
        return elderMax;
    }

    public int memberMax() {
        return memberMax;
    }

    public int requestMax() {
        return requestMax;
    }

    public int quitCdSec() {
        return quitCdSec;
    }

    public int kickCdSec() {
        return kickCdSec;
    }

    public int donateGold() {
        return donateGold;
    }

    public int donateBrotherCoin() {
        return donateBrotherCoin;
    }

    public int donateCrystal() {
        return donateCrystal;
    }

    public int staminaGrow() {
        return staminaGrow;
    }

    public double cancelRefundRatio() {
        return cancelRefundRatio;
    }

    public int bossMaxTimes() {
        return Math.max(1, bossMaxTimes);
    }

    public int auctionSettleHour() {
        return auctionSettleHour;
    }

    public double choulaoCoef() {
        return choulaoCoef;
    }

    public int buildingProfitCap() {
        return buildingProfitCap;
    }

    public int growToContri() {
        return growToContri;
    }

    public int crystalToContri() {
        return crystalToContri;
    }

    public int growCap() {
        return growCap;
    }

    public int fightRankRefreshMin() {
        return fightRankRefreshMin;
    }

    public int growRankRefreshMin() {
        return growRankRefreshMin;
    }

    /** 建筑解锁所需议事厅等级（未知类型返回 0）。 */
    public int unlockHallLevel(int type) {
        Integer v = unlockHallLevel.get(Integer.valueOf(type));
        return v == null ? 0 : v.intValue();
    }

    /** 建筑使用所需攻略组等级（未知类型返回 0）。 */
    public int usePlayerLevel(int type) {
        Integer v = usePlayerLevel.get(Integer.valueOf(type));
        return v == null ? 0 : v.intValue();
    }

    public BuildingRow building(int type, int level) {
        return buildings.get(type + "/" + level);
    }

    /** 建筑最大等级（未配置返回 1）。 */
    public int maxBuildingLevel(int type) {
        Integer v = buildingMaxLevel.get(Integer.valueOf(type));
        return v == null ? 1 : v.intValue();
    }

    public int buildingChoulao(int type, int level) {
        BuildingRow r = building(type, level);
        return r == null ? 0 : r.choulao;
    }

    public BossRow boss(int chapter) {
        return bosses.get(Integer.valueOf(chapter));
    }

    public List<BossRow> bosses() {
        return new ArrayList<>(bosses.values());
    }

    /** 已按作战室等级解锁的章节（作战室等级 >= 章节开启需求）。 */
    public List<BossRow> unlockedBosses(int hallLevel) {
        List<BossRow> out = new ArrayList<>();
        for (BossRow r : bosses.values()) {
            if (hallLevel >= r.hallNeed) {
                out.add(r);
            }
        }
        return out;
    }

    /** 结算兄弟币/勇气币的排名阈值表：取「阈值 <= rank」的最大阈值（无命中返回 0）。 */
    public static int rankAward(Map<Integer, Integer> map, int rank) {
        int best = -1;
        int val = 0;
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            int k = e.getKey().intValue();
            if (k <= rank && k > best) {
                best = k;
                val = e.getValue().intValue();
            }
        }
        return val;
    }

    public MaJiuTargetRow majiuTarget(int id) {
        return majiuTargets.get(Integer.valueOf(id));
    }

    public List<MaJiuTargetRow> majiuTargets() {
        return new ArrayList<>(majiuTargets.values());
    }

    public int majiuStartHour() {
        return majiuStartHour;
    }

    public int majiuStartMinute() {
        return majiuStartMinute;
    }

    public int majiuSendSec() {
        return majiuSendSec;
    }

    public int majiuRaidSec() {
        return majiuRaidSec;
    }

    public int raidTimes() {
        return Math.max(1, raidTimes);
    }

    /** 「单次活动刷新次数」上限（UnionMaJiuBase.txt）= 2。 */
    public int majiuResetTimes() {
        return Math.max(1, majiuResetTimes);
    }

    public int sendBrotherCoin() {
        return sendBrotherCoin;
    }

    public double raidXdbRatio() {
        return raidXdbRatio;
    }

    public int sendGrow() {
        return sendGrow;
    }

    public int raidGrow() {
        return raidGrow;
    }

    public int raidRankBrother(int rank) {
        return rankAward(raidRankBrother, rank);
    }

    /** 掠夺获取资源比例（线路金币的百分比）。 */
    public double raidGainRatio() {
        return raidGainRatio;
    }

    /** 运镖单次被劫扣除比例。 */
    public double beRaidLoseRatio() {
        return beRaidLoseRatio;
    }

    /** 成功防守一次获得金币奖励（线路金币百分比）。 */
    public double defendGoldRatio() {
        return defendGoldRatio;
    }

    /** 单场掠夺超时时间（秒）。 */
    public int raidTimeoutSec() {
        return raidTimeoutSec <= 0 ? 300 : raidTimeoutSec;
    }

    /** 马厩等级对应的单次刷新消耗公会晶石。 */
    public int majiuResetCrystal(int majiuLevel) {
        Integer v = majiuResetCrystal.get(Integer.valueOf(Math.max(1, majiuLevel)));
        return v == null ? 0 : v.intValue();
    }

    public int kitchenStamina(int kitchenLevel) {
        Integer v = kitchenStamina.get(Integer.valueOf(Math.max(1, kitchenLevel)));
        return v == null ? 0 : v.intValue();
    }

    public int trainPits(int trainLevel) {
        Integer v = trainPits.get(Integer.valueOf(Math.max(1, trainLevel)));
        return v == null ? 0 : v.intValue();
    }

    public int hospitalReliveSec(int hospitalLevel) {
        Integer v = hospitalReliveSec.get(Integer.valueOf(Math.max(1, hospitalLevel)));
        return v == null ? 0 : v.intValue();
    }

    public int trainSec() {
        return trainSec;
    }

    /** ETrainingType 0..3 的经验倍率。 */
    public double trainExpRatio(int trainingType) {
        int i = trainingType < 0 || trainingType > 3 ? 0 : trainingType;
        return trainExpRatio[i];
    }

    /** ETrainingType 0..3 的消耗系数。 */
    public double trainCostRatio(int trainingType) {
        int i = trainingType < 0 || trainingType > 3 ? 0 : trainingType;
        return trainCostRatio[i];
    }

    /**
     * 训练场「每小时经验基数」= {@code UnionXunLian.txt} 的 {@code A + B * 等级^C}，
     * 其中**等级是玩家自身等级**（不是武将等级）。
     *
     * <p>客户端同源：{@code TrainInfo.cs:24-29}
     * {@code (trainGainExpRatioA + trainGainExpRatioB * Math.Pow(msMainPlayer.Attribute.mLevel, trainGainExpRatioC)) * trainTimeLong / 3600}。
     * 改前把 C 次幂套在 {@code A + B*等级} 上（40 级：8995/h vs 客户端 2718/h），量级差 3 倍以上。</p>
     */
    public double trainHourlyExp(int playerLevel) {
        return trainBaseA + trainLevelB * Math.pow(Math.max(1, playerLevel), trainExpC);
    }

    /** 训练室本身给的经验（不含教练加成）：{@code TrainInfo.cs:31 trainRoomExp = num2 * roomRatio}。 */
    public long trainRoomExp(int playerLevel, int trainingType, long seconds) {
        return (long) (trainHourlyExp(playerLevel) * seconds / 3600d * trainExpRatio(trainingType));
    }

    /**
     * 训练总经验（含教练加成）：{@code TrainInfo.cs:30 totalExp = num2 * (roomRatio + employerRatio)}
     * —— 教练加成是**与训练室倍率相加**，不是再乘一遍。
     */
    public long trainTotalExp(int playerLevel, int trainingType, double trainerRatio, long seconds) {
        return (long) (trainHourlyExp(playerLevel) * seconds / 3600d
                * (trainExpRatio(trainingType) + Math.max(0d, trainerRatio)));
    }

    /**
     * 训练消耗：客户端按 {@code totalExp / 消耗系数} 校验余额
     * （{@code SelectTrainRoom.cs:114/124} 金币房、{@code :134/:151} 钻石房），消耗系数即
     * {@code UnionXunLian.txt} 第 6-9 行「普通/白银/黄金/铂金训练室消耗系数」= 每 1 货币换多少经验。
     */
    public int trainCost(int playerLevel, int trainingType, double trainerRatio, long seconds) {
        double ratio = Math.max(0.0001d, trainCostRatio(trainingType));
        return (int) Math.floor(trainTotalExp(playerLevel, trainingType, trainerRatio, seconds) / ratio);
    }

    /** 黄金(2)/铂金(3)训练室消耗**钻石**，普通(0)/白银(1)消耗金币（{@code SelectTrainRoom.cs:114-164}）。 */
    public boolean trainCostInDiamond(int trainingType) {
        return trainingType >= 2;
    }

    public SubsidiaryRow subsidiary(int profession, int level) {
        SubsidiaryRow r = subsidiaries.get(profession + "/" + level);
        if (r != null) {
            return r;
        }
        return subsidiaries.get(profession + "/0");
    }

    /** 作战室雇佣酬劳：战斗力 * 酬劳战力系数 * 雇佣酬劳（UnionWJSubsidiary 职业 0）。 */
    public int zzsEmployPrice(int fightPower, int level) {
        SubsidiaryRow r = subsidiary(0, level);
        if (r == null) {
            return 0;
        }
        return (int) (fightPower * choulaoCoef * r.price);
    }

    // ------------------------------------------------------------------ PvP

    /** 报名消耗的公会晶石。 */
    public int pvpEnrollCrystal() {
        return pvpEnrollCrystal;
    }

    public int pvpWinGrow() {
        return pvpWinGrow;
    }

    public int pvpLoseGrow() {
        return pvpLoseGrow;
    }

    public int pvpWinCrystal() {
        return pvpWinCrystal;
    }

    public int pvpLoseCrystal() {
        return pvpLoseCrystal;
    }

    /** 胜利个人获得的物品原始名（UnionPvP.txt，默认 UFTS2）。 */
    public String pvpWinGoods() {
        return pvpWinGoods;
    }

    /** 失败个人获得的物品原始名（默认 UFTS3）。 */
    public String pvpLoseGoods() {
        return pvpLoseGoods;
    }

    public int pvpWinBrother() {
        return pvpWinBrother;
    }

    public int pvpLoseBrother() {
        return pvpLoseBrother;
    }

    public double pvpCaptureRatio() {
        return pvpCaptureRatio;
    }

    public int pvpUnitBrother() {
        return pvpUnitBrother;
    }

    public String pvpUnitGoods() {
        return pvpUnitGoods;
    }

    /** 据点 ID 列表（地图顺序，10 个）。 */
    public List<Integer> pvpPointIds() {
        return new ArrayList<>(pvpPoints.keySet());
    }

    public String pvpPointName(int id) {
        String name = pvpPoints.get(id);
        return name == null ? "" : name;
    }

    public int pvpStartHour() {
        return pvpStartHour;
    }

    public int pvpStartMinute() {
        return pvpStartMinute;
    }

    public int pvpDurationSec() {
        return pvpDurationSec;
    }

    public int pvpEnrollOpenAfterSec() {
        return pvpEnrollOpenAfterSec;
    }

    public int pvpBroadcastBeforeSec() {
        return pvpBroadcastBeforeSec;
    }

    public int pvpBroadcastEndSec() {
        return pvpBroadcastEndSec;
    }

    /** 开战日集合（UnionPvPTime.txt 第 1 列，0=周日；表内覆盖全周）。 */
    public Set<Integer> pvpDays() {
        return new LinkedHashSet<>(pvpDays);
    }

    /**
     * 据点 {@code id} 的前置据点；根据点（0）无前置返回 {@code null}。
     * 与客户端 {@code UnionPvPDefPointsInfoMgr.GetPrevPointIndex} 同源（反查「下一个据点」列）。
     */
    public Integer pvpPrevPoint(int id) {
        return pvpPrevPoint.get(Integer.valueOf(id));
    }
}
