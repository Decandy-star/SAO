package com.sao.fakeserver.table;

import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.fight.CombatAttrCalculator;
import com.sao.fakeserver.fight.JinJieGrowType;
import com.sao.fakeserver.store.PlayerRecord;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 武将养成 / 装备表。优先读 tables/，缺文件时从 GameText 抽出。
 */
@Component
public class CultivateTables {
    private static final Logger log = LoggerFactory.getLogger(CultivateTables.class);
    private static final String[] TABLE_FILES = {
            "EquipmentCompose.txt",
            "EquipmentList.txt",
            "EquipmentUpgrade.txt",
            "EquipmentXiLian.txt",
            "EquipmentStarUpgrade.txt",
            "EquipmentDeCompose.txt",
            "EquipmentCuiLian.txt",
            "EquipmentJingLian.txt",
            "WuJiangUpStar.txt",
            "WuJiangStarCommonInfo.txt",
            "WuJiangBaseAttri.ini",
            "WuJiangJinJie.txt",
            "SkillUpgrade.txt",
            "NewSkillProperty.ini",
            "NewSkillBeiDong.ini",
            "JinJieBookCompose.txt",
            "GoodsList.txt",
            "GlobalSetup_CH.txt",
            "Buddies.txt",
            "EquipmentSuit.txt",
            "EquipSoulList.txt",
            "EquipSoulJinJie.txt",
    };

    public static final int CUI_LIAN_PARTS = 4;
    public static final int MAX_EQUIP_STAR = 5;
    public static final int REAL_MAX_EQUIP_STAR = 10;
    public static final int MAX_GUHUA = 4;

    private final SaoProperties props;

    private int starUpper = 10;
    private int maxSkillPoint = 20;
    private int buySkillPointRmb = 1;
    private int buySkillPointCount = 20;
    private int skillPointRetrieveSeconds = 180;
    private int skillMinuMingJiang;
    private int skillMinuA = 2;
    private int skillMinuB = 14;
    private int skillMinuPassive = 29;
    /** GlobalSetup：名将/无双、技能A、技能B、被动的开启进阶阶段。 */
    private int skillOpenMingJiang;
    private int skillOpenA = 1;
    private int skillOpenB = 3;
    private int skillOpenPassive = 5;
    /**
     * GlobalSetup.ChangePioneerHPRestoreLimitRatio：替补血量占比上限；
     * 对齐 BattleController 每 3s 回血封顶（token 55）。
     */
    private float changePioneerHpRestoreLimitRatio = 1f;
    /**
     * GlobalSetup 韧性折算系数（token 105–108）；
     * 对齐 Unit.GetEquipEffectResist：Resist × ratio / 10000。
     */
    private int resistLianZhanRatio;
    private int resistZhiShangRatio;
    private int resistBaoJiRatio;
    private int resistBaoTouRatio;
    /** GlobalSetup 穿透折算（token 109–113）。 */
    private int penetrateGeDang;
    private int penetrateZheSheRatio;
    private int penetrateFanJiRatio;
    private int penetrateNengLiangToMoMianRatio;
    private int penetrateNengLiangToNengLiangRatio;

    private final List<Integer> upStarFrag = new ArrayList<>();
    private final List<Integer> upStarGold = new ArrayList<>();
    /** 各星英雄折算碎片数（WuJiangUpStar 第三段）；客户端 GetChaiJieStarBySuiPianCount 只认这些精确值。 */
    private final List<Integer> chaiJieFrag = new ArrayList<>();
    private final Map<Integer, HeroCfg> heroesByIndex = new HashMap<>();
    private final Map<String, Integer> heroIndexByFragment = new HashMap<>();
    private final Map<Integer, JinJieCfg> jinJieByType = new HashMap<>();
    private final Map<String, ComposeRecipe> bookCompose = new HashMap<>();
    private final List<int[]> skillGold = new ArrayList<>();
    private final Map<String, EquipCfg> equips = new HashMap<>();
    private final Map<String, ComposeRecipe> equipCompose = new HashMap<>();
    private static final int MAX_EQUIP_GU_HUA = 4;
    private final float[] equipStarGrowMod = new float[REAL_MAX_EQUIP_STAR + 1];
    private final float[] equipStarGuHuaMod = new float[REAL_MAX_EQUIP_STAR + 1];
    private int upgradeBaseGold = 120;
    private final float[] upgradeRate = new float[]{0, 0, 0.5f, 0.75f, 1f, 1.5f};
    private final List<Integer> upgradeDelta = new ArrayList<>();
    /** EquipmentUpgrade 1～7 倍升级万分率（下标=倍率）。 */
    private final int[] upgradeCritWan = new int[]{0, 8550, 1000, 300, 100, 50, 0, 0};
    /** EquipmentXiLian：(type<<16)|quality → 9 项属性万分率，下标=EXiLianProperty。 */
    private final Map<Integer, int[]> xiLianRates = new HashMap<>();
    private final List<StarCost> starCosts = new ArrayList<>();
    private final List<GuHuaCost> guhuaCosts = new ArrayList<>();
    private final List<DecomposeRow> decomposeEquip = new ArrayList<>();
    private final List<DecomposeRow> decomposeTuZi = new ArrayList<>();
    private CuiLianCost openCuiLian;
    /** EquipmentCuiLian 开启淬炼属性系数（对齐 EquipCuiLian.openCuiLianAttrModifier）。 */
    private float openCuiLianAttrModifier;
    private final List<PartCost> cuiLianParts = new ArrayList<>();
    private final List<CuiLianCost> feiYueCosts = new ArrayList<>();
    /** 装备类型 → 精炼左右槽提升属性类型（EquipRefineType 1攻…5血）。 */
    private final Map<Integer, int[]> jingLianAttTypeByEquipType = new HashMap<>();
    private final List<JingLianLeapRow> jingLianLeaps = new ArrayList<>();
    private final List<JingLianRefineRow> jingLianRefines = new ArrayList<>();
    private final Map<Integer, Integer> jingLianRefineUpper = new HashMap<>();
    private final Map<Integer, Integer> jingLianLeapUpper = new HashMap<>();
    private final Map<String, Integer> goodsQuality = new HashMap<>();
    private final Map<String, Integer> fragmentHero = new HashMap<>();
    /** goodsOri → (jinJieType → addValue)，时光石 CustomParam2；比率类已 /10000。 */
    private final Map<String, Map<Integer, Float>> goodsTimeAttr = new HashMap<>();
    private final Map<Integer, SkillProp> skillsById = new HashMap<>();
    private final Map<Integer, BeiDongProp> beiDongById = new HashMap<>();
    private final Map<Integer, BuddyCfg> buddiesById = new HashMap<>();
    private final Map<Integer, List<Integer>> buddyIdsByHero = new HashMap<>();
    private final List<SuitCfg> suits = new ArrayList<>();
    private final Map<Integer, SoulCfg> soulsByHero = new HashMap<>();
    private final Map<Integer, SoulJinJieCfg> soulJinJieById = new HashMap<>();

    public CultivateTables(SaoProperties props) {
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
        parseStarCommon(readTable(dir, "WuJiangStarCommonInfo.txt"));
        parseUpStar(readTable(dir, "WuJiangUpStar.txt"));
        parseHeroes(readTable(dir, "WuJiangBaseAttri.ini"));
        parseJinJie(readTable(dir, "WuJiangJinJie.txt"));
        parseBookCompose(readTable(dir, "JinJieBookCompose.txt"));
        parseSkillUpgrade(readTable(dir, "SkillUpgrade.txt"));
        parseSkillProps(readTable(dir, "NewSkillProperty.ini"));
        parseBeiDong(readTable(dir, "NewSkillBeiDong.ini"));
        parseGlobalSetup(readTable(dir, "GlobalSetup_CH.txt"));
        parseGoods(readTable(dir, "GoodsList.txt"));
        parseEquipList(readTable(dir, "EquipmentList.txt"));
        parseEquipCompose(readTable(dir, "EquipmentCompose.txt"));
        parseEquipUpgrade(readTable(dir, "EquipmentUpgrade.txt"));
        parseEquipXiLian(readTable(dir, "EquipmentXiLian.txt"));
        parseEquipStar(readTable(dir, "EquipmentStarUpgrade.txt"));
        parseDecompose(readTable(dir, "EquipmentDeCompose.txt"));
        parseCuiLian(readTable(dir, "EquipmentCuiLian.txt"));
        parseJingLian(readTable(dir, "EquipmentJingLian.txt"));
        parseBuddies(readTable(dir, "Buddies.txt"));
        parseSuits(readTable(dir, "EquipmentSuit.txt"));
        parseEquipSoul(readTable(dir, "EquipSoulList.txt"));
        parseEquipSoulJinJie(readTable(dir, "EquipSoulJinJie.txt"));
        // 时光石加成依赖 JinJie 主成长类型，须在 parseJinJie 之后重扫 Goods
        parseGoodsTimeAttr(readTable(dir, "GoodsList.txt"));
        log.info("cultivate heroes={} equips={} compose={} jinJie={} buddies={} suits={} souls={} skillProps={} beiDong={} openCuiLianMod={} xiLianRows={}",
                heroesByIndex.size(), equips.size(), equipCompose.size(), jinJieByType.size(),
                buddiesById.size(), suits.size(), soulsByHero.size(), skillsById.size(), beiDongById.size(),
                Float.valueOf(openCuiLianAttrModifier), Integer.valueOf(xiLianRates.size()));
    }

    public int starUpper() {
        return starUpper;
    }

    public int maxSkillPoint() {
        return maxSkillPoint;
    }

    public int buySkillPointRmb() {
        return buySkillPointRmb;
    }

    public int buySkillPointCount() {
        return buySkillPointCount;
    }

    public int skillPointRetrieveSeconds() {
        return skillPointRetrieveSeconds;
    }

    public int composeFragNeed(int composeStar) {
        return fragNeedToStar(composeStar);
    }

    public int fragNeedToStar(int toStar) {
        int sum = 0;
        for (int s = 1; s <= toStar && s < upStarFrag.size(); s++) {
            sum += upStarFrag.get(s);
        }
        return sum;
    }

    public int starUpFragNeed(int currentStar) {
        int next = currentStar + 1;
        if (next <= 0 || next >= upStarFrag.size()) {
            return 0;
        }
        return upStarFrag.get(next);
    }

    public int starUpGoldNeed(int currentStar) {
        int next = currentStar + 1;
        if (next <= 0 || next >= upStarGold.size()) {
            return 0;
        }
        return upStarGold.get(next);
    }

    /**
     * 重复抽到已有武将时发的折算碎片数。必须落在 WuJiangUpStar 第三段（客户端按精确数量反查星级）。
     * star = 该武将「合成初始星级」（1～4 不等），不是一律 1 星。
     */
    public int chaiJieFragByStar(int star) {
        if (star <= 0 || star >= chaiJieFrag.size()) {
            return chaiJieFrag.size() > 1 ? chaiJieFrag.get(1) : 8;
        }
        int n = chaiJieFrag.get(star);
        return n > 0 ? n : 8;
    }

    /** 按武将合成初始星级取重复抽折算碎片数。 */
    public int chaiJieFragForHero(int heroIndex) {
        HeroCfg h = heroesByIndex.get(heroIndex);
        int star = h == null ? 1 : Math.max(1, h.composeStar);
        return chaiJieFragByStar(star);
    }

    public HeroCfg heroByIndex(int index) {
        return heroesByIndex.get(index);
    }

    /**
     * 可进酒馆/钻石池的武将：表内存在、index&lt;1000、有合成碎片。
     * index≥1000 为怪物/NPC（与可玩武将同名，如 1001 幸 / 1003 机枪），禁止发放。
     */
    public boolean isPlayableHero(int index) {
        if (index <= 0 || index >= 1000) {
            return false;
        }
        HeroCfg h = heroesByIndex.get(index);
        if (h == null) {
            return false;
        }
        return h.fragmentOri != null && !h.fragmentOri.isEmpty() && !"0".equals(h.fragmentOri);
    }

    /** 全部武将配置（签到月初抽碎片等用）。 */
    public java.util.Collection<HeroCfg> allHeroes() {
        return heroesByIndex.values();
    }

    /**
     * 假服展示战力（protobuf field 15）。原厂系数不在客户端，这里按客户端
     * {@code WuJiang.Calculate*} 的最终属性（成长+进阶+装备+器魂+时光石+羁绊天赋）加权。
     * <p>
     * 权重按 uType（1攻 2防 3辅）拉开擅长项，血量系数压低避免膨胀。
     * 技能按 NewSkillProperty.fSkillType（0普攻伤害 / 1物理 / 2法术 / 3治疗）
     * 和被动作用对象（自身/队友/敌方）折进攻防血，再走同一套职业权重。
     * 不含羁绊战力反哺（那是用本字段算出来的，会循环）。
     */
    public int computeFightPower(int heroIndex, int level, int stars) {
        PlayerRecord.Hero stub = new PlayerRecord.Hero();
        stub.heroIndex = heroIndex;
        stub.level = level;
        stub.stars = stars;
        return computeFightPower(null, stub);
    }

    public int computeFightPower(PlayerRecord rec, PlayerRecord.Hero wj) {
        CombatStats st = computeCombatStats(rec, wj);
        if (st == null) {
            return 800;
        }
        return fightPowerOf(st);
    }

    /** 15=不可逆成长（等级/星/进阶/器魂）；17=可卸（装备/套装/时光石/羁绊）。羁绊返还只读 15。 */
    public int[] computeFightPowerBaseAndAdd(PlayerRecord rec, PlayerRecord.Hero wj) {
        CombatStats bare = CombatAttrCalculator.build(this, rec, wj, false);
        CombatStats full = CombatAttrCalculator.build(this, rec, wj, true);
        int base = bare == null ? 800 : fightPowerOf(bare);
        int total = full == null ? base : fightPowerOf(full);
        int add = Math.max(0, total - base);
        return new int[]{base, add};
    }

    /** 当前已穿 ori 激活的套装被动技能 id（EquipmentSuit 2/3/4/5 件档 beiDong）。 */
    public List<Integer> suitEffectIds(List<String> equippedOri) {
        List<Integer> out = new ArrayList<>();
        if (equippedOri == null || equippedOri.isEmpty() || suits.isEmpty()) {
            return out;
        }
        for (SuitCfg suit : suits) {
            int matched = 0;
            for (String need : suit.equipIds) {
                if (need == null || need.isEmpty() || "0".equals(need)) {
                    continue;
                }
                for (String have : equippedOri) {
                    if (need.equals(have)) {
                        matched++;
                        break;
                    }
                }
            }
            // mBeiDongID[0..3] ↔ 2/3/4/5 件；与 attrAdd 一样从 2 件起逐档叠加
            for (int tier = 0; tier < 4; tier++) {
                if (matched >= tier + 2 && suit.beiDong[tier] > 0) {
                    out.add(Integer.valueOf(suit.beiDong[tier]));
                }
            }
        }
        return out;
    }

    public List<String> equippedOriOf(PlayerRecord rec, String heroGuid) {
        List<String> equippedOri = new ArrayList<>();
        if (rec == null || rec.equipments == null || heroGuid == null || heroGuid.isEmpty()) {
            return equippedOri;
        }
        for (PlayerRecord.Equipment eq : rec.equipments) {
            if (eq == null || eq.owner == null || !heroGuid.equals(eq.owner) || eq.ori == null || eq.ori.isEmpty()) {
                continue;
            }
            equippedOri.add(eq.ori);
        }
        return equippedOri;
    }

    /** 开战快照 / 机器人战力展示共用。 */
    public int fightPowerOf(CombatStats st) {
        if (st == null) {
            return 800;
        }
        float wAtk, wHp, wDef;
        int role = st.uType;
        if (role == 2) {
            wAtk = 0.85f;
            wHp = 0.12f;
            wDef = 1.45f;
        } else if (role == 3) {
            wAtk = 1.05f;
            wHp = 0.10f;
            wDef = 1.15f;
        } else {
            wAtk = 1.35f;
            wHp = 0.08f;
            wDef = 0.90f;
        }
        float atk = st.atkType == 2 ? st.magAtk : st.phyAtk;
        int fp = Math.round(atk * wAtk + st.maxHp * wHp + (st.pdef + st.mdef) * wDef * 0.5f) + st.effectFp;
        return Math.max(1, fp);
    }

    /**
     * 开战/伤害结算用属性（对齐客户端 Calculate*：成长→孔乘→进阶→装/套→缘分乘→战力返还）。
     */
    public CombatStats computeCombatStats(PlayerRecord rec, PlayerRecord.Hero wj) {
        return CombatAttrCalculator.build(this, rec, wj);
    }

    /** 羁绊缘分比率（BuddiesProperty.GetAddRadio），addType 0攻/1防/2血。 */
    public float buddiesAddRadio(int heroIndex, List<Integer> buddies, int addType) {
        List<Integer> ids = buddyIdsByHero.get(Integer.valueOf(heroIndex));
        if (ids == null || buddies == null || buddies.size() != CombatAttrCalculator.MAX_BUDDIES) {
            return 0f;
        }
        float num = 0f;
        for (Integer yf : ids) {
            BuddyCfg cfg = buddiesById.get(yf);
            if (cfg == null || !yuanFenActive(buddies, cfg)) {
                continue;
            }
            if (addType == 0) {
                num += cfg.atkRadio;
            } else if (addType == 1) {
                num += cfg.defRadio;
            } else {
                num += cfg.hpRadio;
            }
        }
        return num;
    }

    public Float goodsTimeAttrAdd(String ori, int jinJieType) {
        Map<Integer, Float> m = goodsTimeAttr.get(ori);
        if (m == null) {
            return null;
        }
        return m.get(Integer.valueOf(jinJieType));
    }

    public float suitAttrAdd(int attrType, List<String> equippedOri) {
        if (equippedOri == null || equippedOri.isEmpty() || suits.isEmpty()) {
            return 0f;
        }
        float num = 0f;
        for (SuitCfg suit : suits) {
            int matched = 0;
            for (String need : suit.equipIds) {
                if (need == null || need.isEmpty() || "0".equals(need)) {
                    continue;
                }
                for (String have : equippedOri) {
                    if (need.equals(have)) {
                        matched++;
                        break;
                    }
                }
            }
            num += suit.attrAdd(attrType, matched);
        }
        return num;
    }

    /**
     * {@code EquipSoulList.txt} 按「所属角色ID」索引的器魂配置；没有启用行（该表首列输出符非 {@code #}）的武将返回 null。
     *
     * <p>C2S 3701 合成需要「合成所需物品ID/数量」，客户端 {@code QiHunSys.cs:363} 读同一列显示
     * {@code 已有/需要}，所以合成闸门必须按这张表校验。
     */
    public SoulCfg soulCfg(int heroIndex) {
        return soulsByHero.get(Integer.valueOf(heroIndex));
    }

    /**
     * {@code EquipSoulJinJie.txt} 按阶段ID 索引的进阶配置；表里没有该阶段返回 null。
     *
     * <p>C2S 3703 需要「当前阶所需经验」和「是否还有下一阶」：客户端 {@code QiHunSys.cs:699}
     * 用 {@code GetJinJieProperty(mId + 1) == null} 把突破按钮整个隐藏（末阶），
     * {@code :708} 又要求当前阶经验 ≥ 该阶所需，所以进阶闸门必须按这张表校验。
     */
    public SoulJinJieCfg soulJinJie(int stage) {
        return soulJinJieById.get(Integer.valueOf(stage));
    }

    public float equipSoulAdd(int heroIndex, int growType, int jieDuan, int exp) {
        SoulCfg soul = soulsByHero.get(Integer.valueOf(heroIndex));
        if (soul == null) {
            return 0f;
        }
        float base = soul.attrAdd[growType];
        if (base <= 0f) {
            return 0f;
        }
        float mul = 1f;
        int stage = Math.max(0, jieDuan);
        for (int n = 1; n <= stage; n++) {
            SoulJinJieCfg prev = soulJinJieById.get(Integer.valueOf(stage - n));
            if (prev != null) {
                mul += prev.sectionRate * prev.section + prev.jieDuanRate;
            }
        }
        SoulJinJieCfg cur = soulJinJieById.get(Integer.valueOf(stage));
        if (cur != null && cur.exp > 0) {
            long sec = (long) exp / cur.exp * cur.section;
            if (sec > cur.section) {
                sec = cur.section;
            }
            mul += cur.sectionRate * sec;
        }
        return base * mul;
    }

    private static boolean yuanFenActive(List<Integer> buddies, BuddyCfg cfg) {
        if (cfg.role1 != 0 && !buddies.contains(Integer.valueOf(cfg.role1))) {
            return false;
        }
        if (cfg.role2 != 0 && !buddies.contains(Integer.valueOf(cfg.role2))) {
            return false;
        }
        if (cfg.role3 != 0 && !buddies.contains(Integer.valueOf(cfg.role3))) {
            return false;
        }
        if (cfg.role4 != 0 && !buddies.contains(Integer.valueOf(cfg.role4))) {
            return false;
        }
        if (cfg.role5 != 0 && !buddies.contains(Integer.valueOf(cfg.role5))) {
            return false;
        }
        return true;
    }

    /** 无存档武将时（JJC 机器人）按 index/等级/星/阶/技能等级建属性。 */
    public CombatStats computeCombatStats(int heroIndex, int level, int stars, int stage,
                                          int skillMj, int skillA, int skillB, int skillPassive) {
        PlayerRecord.Hero stub = new PlayerRecord.Hero();
        stub.heroIndex = heroIndex;
        stub.level = level;
        stub.stars = stars;
        stub.stage = stage;
        stub.skill1 = skillMj;
        stub.skill2 = skillA;
        stub.skill3 = skillB;
        stub.skill4 = skillPassive;
        return computeCombatStats(null, stub);
    }

    public SkillProp skill(int id) {
        return skillsById.get(Integer.valueOf(id));
    }

    /**
     * 技能折成攻/血/防。未达 GlobalSetup 开启进阶阶段的槽不计。
     * 主动技看伤害类型：0普攻 1物理 2法术进攻击，3治疗进血；
     * 被动看作用对象：敌方进攻击，自身进防御，队友进血。
     * 系数压过，避免高等级伤害成长把战力撑爆。
     */
    private float[] skillFightExtras(PlayerRecord rec, PlayerRecord.Hero wj, HeroCfg c) {
        float[] out = new float[4];
        int ultId = c.skillMingJiang;
        if (rec != null && rec.souls != null && c.skillWuShuang > 0) {
            PlayerRecord.Soul soul = rec.souls.get(Integer.valueOf(wj.heroIndex));
            if (soul != null && soul.composed) {
                ultId = c.skillWuShuang;
            }
        }
        if (ultId <= 0) {
            ultId = c.skillWuShuang;
        }
        if (skillUnlocked(1, wj.stage) && wj.skill1 > 0) {
            addActiveSkill(c, ultId, wj.skill1, 1.00f, out);
        }
        if (skillUnlocked(2, wj.stage) && wj.skill2 > 0) {
            addActiveSkill(c, c.skillA, wj.skill2, 0.85f, out);
        }
        if (skillUnlocked(3, wj.stage) && wj.skill3 > 0) {
            addActiveSkill(c, c.skillB, wj.skill3, 0.85f, out);
        }
        if (skillUnlocked(4, wj.stage) && wj.skill4 > 0) {
            addPassiveSkill(c, c.skillBeiDong1, wj.skill4, 0.70f, out);
        }
        return out;
    }

    private void addActiveSkill(HeroCfg hero, int skillId, int lv, float slotW, float[] out) {
        if (skillId <= 0) {
            return;
        }
        SkillProp sk = skillsById.get(Integer.valueOf(skillId));
        if (sk == null) {
            return;
        }
        int lv1 = Math.max(1, lv);
        float raw = sk.dmgBase + sk.dmgGrow * (lv1 - 1);
        int hits = Math.min(2, Math.max(1, sk.hits));
        float mag;
        if (raw <= 0.01f) {
            mag = (6f + lv1 * 0.8f) * slotW;
        } else {
            mag = Math.min(800f, raw) * hits * 0.08f * slotW;
        }
        boolean heal = sk.skillType == 3 || sk.cureType > 0;
        if (!heal) {
            if (hero.atkType == 2) {
                if (sk.skillType == 2) {
                    mag *= 1.12f;
                } else if (sk.skillType == 1) {
                    mag *= 0.92f;
                }
            } else if (sk.skillType == 1) {
                mag *= 1.12f;
            } else if (sk.skillType == 2) {
                mag *= 0.92f;
            }
            if (hero.uType == 1) {
                mag *= 1.18f;
            } else if (hero.uType == 2) {
                mag *= 0.90f;
            }
            out[0] += mag;
        } else {
            if (hero.uType == 3) {
                mag *= 1.22f;
            } else if (hero.uType == 2) {
                mag *= 1.10f;
            } else {
                mag *= 0.88f;
            }
            out[1] += mag * 2.2f;
            out[2] += mag * 0.25f;
            out[3] += mag * 0.25f;
        }
    }

    private void addPassiveSkill(HeroCfg hero, int skillId, int lv, float slotW, float[] out) {
        if (skillId <= 0) {
            return;
        }
        BeiDongProp sk = beiDongById.get(Integer.valueOf(skillId));
        if (sk == null) {
            return;
        }
        int lv1 = Math.max(1, lv);
        float p = Math.min(1f, (sk.ratio + sk.ratioGrow * (lv1 - 1)) / 10000f);
        float mag = (22f + p * 36f + lv1 * 1.1f) * slotW;
        int t = sk.targetType;
        if (t == 4) {
            if (hero.uType == 1) {
                mag *= 1.15f;
            }
            out[0] += mag;
        } else if (t == 1) {
            if (hero.uType == 2) {
                mag *= 1.20f;
            }
            out[1] += mag * 1.2f;
            out[2] += mag * 0.55f;
            out[3] += mag * 0.55f;
        } else {
            if (hero.uType == 3) {
                mag *= 1.22f;
            }
            out[1] += mag * 1.8f;
            out[0] += mag * 0.25f;
        }
    }

    public HeroCfg heroByFragment(String ori) {
        Integer idx = heroIndexByFragment.get(ori);
        if (idx == null) {
            idx = fragmentHero.get(ori);
        }
        return idx == null ? null : heroesByIndex.get(idx);
    }

    public JinJieCfg jinJie(int type) {
        return jinJieByType.get(type);
    }

    public ComposeRecipe bookRecipe(String ori) {
        return bookCompose.get(ori);
    }

    public int skillGold(int skillIndex, int newLevel) {
        if (newLevel <= 1 || skillGold.isEmpty()) {
            return 0;
        }
        int row = newLevel - 2;
        if (row < 0 || row >= skillGold.size()) {
            return 0;
        }
        int col = skillIndex - 1;
        int[] line = skillGold.get(row);
        if (col < 0 || col >= line.length) {
            return 0;
        }
        return line[col];
    }

    public int skillMaxLevel(int skillIndex, int playerLevel) {
        int minu = 0;
        if (skillIndex == 1) {
            minu = skillMinuMingJiang;
        } else if (skillIndex == 2) {
            minu = skillMinuA;
        } else if (skillIndex == 3) {
            minu = skillMinuB;
        } else if (skillIndex == 4) {
            minu = skillMinuPassive;
        }
        return Math.max(1, playerLevel - minu);
    }

    /**
     * 与客户端 {@code SkillUpgradeCfgMgr.GetSkillOpenJieDuan} 相同：
     * 武将进阶阶段 {@code stage} ≥ 开启阶段才算解锁。
     */
    public boolean skillUnlocked(int skillIndex, int stage) {
        return stage >= skillOpenJieDuan(skillIndex);
    }

    public int skillOpenJieDuan(int skillIndex) {
        if (skillIndex == 1) {
            return skillOpenMingJiang;
        }
        if (skillIndex == 2) {
            return skillOpenA;
        }
        if (skillIndex == 3) {
            return skillOpenB;
        }
        if (skillIndex == 4) {
            return skillOpenPassive;
        }
        return 0;
    }

    public EquipCfg equip(String ori) {
        return equips.get(ori);
    }

    /**
     * 装备的「公会拍卖基础价格（勇气币）」= EquipmentList col8。
     * 装备不在 GoodsList 里（EQ* 是独立表），作战室拍卖的 EQ* 战利品起拍价靠这里兜底。
     */
    public int equipAuctionPrice(String ori) {
        EquipCfg e = equips.get(ori);
        return e == null ? 0 : e.auctionPrice;
    }

    /** 装备显示名（EquipmentList col2）；查不到返回空串。 */
    public String equipDisplayName(String ori) {
        EquipCfg e = equips.get(ori);
        return e == null || e.displayName == null ? "" : e.displayName;
    }

    /** 全部装备（魔盒/商店随机池需要按品质扫描）。 */
    public java.util.Collection<EquipCfg> allEquips() {
        return equips.values();
    }

    public ComposeRecipe equipRecipe(String ori) {
        return equipCompose.get(ori);
    }

    public int upgradeCost(int level, int quality) {
        int delta = 0;
        if (level > 0 && level - 1 < upgradeDelta.size()) {
            delta = upgradeDelta.get(level - 1);
        }
        float rate = 0f;
        if (quality >= 0 && quality < upgradeRate.length) {
            rate = upgradeRate[quality];
        }
        return (int) (upgradeBaseGold + rate * delta);
    }

    /** EquipmentUpgrade：1～7 倍，万分率骰。 */
    public int rollUpgradeJump(Random rng) {
        int r = rng.nextInt(10000);
        int acc = 0;
        for (int mul = 1; mul <= 7; mul++) {
            acc += upgradeCritWan[mul];
            if (r < acc) {
                return mul;
            }
        }
        return 1;
    }

    /** EquipmentXiLian 属性万分率；无行则 0–8 均匀。 */
    public int rollXiLianType(int equipType, int quality, Random rng) {
        int[] rates = xiLianRates.get(Integer.valueOf((equipType << 16) | quality));
        if (rates == null) {
            for (Map.Entry<Integer, int[]> e : xiLianRates.entrySet()) {
                if ((e.getKey().intValue() >> 16) == equipType) {
                    rates = e.getValue();
                    break;
                }
            }
        }
        if (rates == null && !xiLianRates.isEmpty()) {
            rates = xiLianRates.values().iterator().next();
        }
        int sum = 0;
        if (rates != null) {
            for (int n : rates) {
                sum += n;
            }
        }
        if (rates == null || sum <= 0) {
            return rng.nextInt(9);
        }
        int r = rng.nextInt(sum);
        int acc = 0;
        for (int i = 0; i < rates.length; i++) {
            acc += rates[i];
            if (r < acc) {
                return i;
            }
        }
        return 0;
    }

    public StarCost starCost(int quality, int currentStar) {
        int upTo = currentStar + 1;
        for (StarCost c : starCosts) {
            if (c.quality == quality && c.upToStar == upTo) {
                return c;
            }
        }
        return null;
    }

    public GuHuaCost guhuaCost(int quality, int star) {
        for (GuHuaCost c : guhuaCosts) {
            if (c.quality == quality && c.star == star) {
                return c;
            }
        }
        return null;
    }

    /**
     * 装备拆解行：<b>精确 (品质,星级) 匹配，无 fallback</b>（APK
     * {@code EquipDecomposeData.cs:100-106} 谓词 {@code equipQuality == q && star == s}，
     * 查不到返回 null ⇒ 0 万能碎片）。表内只有 star==0 行，且客户端
     * {@code EquipmentDecomposeUI.cs:137/146} 只让白板件进分解页签。
     */
    public DecomposeRow decomposeEquip(int quality, int star) {
        for (DecomposeRow r : decomposeEquip) {
            if (r.quality == quality && r.star == star) {
                return r;
            }
        }
        return null;
    }

    public DecomposeRow decomposeTuZi(int quality) {
        for (DecomposeRow r : decomposeTuZi) {
            if (r.quality == quality) {
                return r;
            }
        }
        return null;
    }

    /**
     * 精炼子等级行（键 = 品质 + 次数）；4901 投喂前查「所需淬炼等级」用
     * （APK {@code EquipRefineData.mLeapLevelLimit}）。
     */
    public JingLianRefineRow refineRow(int quality, int refineLevel) {
        for (JingLianRefineRow row : jingLianRefines) {
            if (row.quality == quality && row.refineLevel == refineLevel) {
                return row;
            }
        }
        return null;
    }

    public int goodsQuality(String ori) {
        Integer q = goodsQuality.get(ori);
        return q == null ? 1 : q;
    }

    public CuiLianCost openCuiLian() {
        return openCuiLian;
    }

    public PartCost cuiLianPart(int star) {
        int idx = star - MAX_EQUIP_STAR;
        if (idx < 0 || idx >= cuiLianParts.size()) {
            return null;
        }
        return cuiLianParts.get(idx);
    }

    public CuiLianCost feiYue(int star) {
        int idx = star - MAX_EQUIP_STAR;
        if (idx < 0 || idx >= feiYueCosts.size()) {
            return null;
        }
        return feiYueCosts.get(idx);
    }

    private void ensureTables(Path dir) {
        boolean missing = false;
        for (String name : TABLE_FILES) {
            if (!Files.isRegularFile(dir.resolve(name))) {
                missing = true;
                break;
            }
        }
        if (!missing) {
            return;
        }
        GameTextLoader loader = GameTextLoader.load(props.getGameText());
        if (loader.isEmpty()) {
            log.warn("cultivate tables incomplete and GameText not found");
            return;
        }
        for (String name : TABLE_FILES) {
            if (Files.isRegularFile(dir.resolve(name))) {
                continue;
            }
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

    private void parseStarCommon(String text) {
        if (text == null) {
            return;
        }
        for (String line : text.split("\r?\n")) {
            String[] cols = split(line);
            if (cols.length >= 1 && toInt(cols[0]) > 0) {
                starUpper = toInt(cols[0]);
                return;
            }
        }
    }

    private void parseUpStar(String text) {
        upStarFrag.clear();
        upStarGold.clear();
        chaiJieFrag.clear();
        upStarFrag.add(0);
        upStarGold.add(0);
        chaiJieFrag.add(0);
        if (text == null) {
            return;
        }
        List<Integer> values = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] cols = split(line);
            if (cols.length >= 2) {
                values.add(toInt(cols[1]));
            }
        }
        int n = starUpper;
        for (int i = 0; i < n && i < values.size(); i++) {
            upStarFrag.add(values.get(i));
        }
        for (int i = n; i < n * 2 && i < values.size(); i++) {
            upStarGold.add(values.get(i));
        }
        for (int i = n * 2; i < n * 3 && i < values.size(); i++) {
            chaiJieFrag.add(values.get(i));
        }
        while (upStarFrag.size() <= n) {
            upStarFrag.add(0);
        }
        while (upStarGold.size() <= n) {
            upStarGold.add(0);
        }
        while (chaiJieFrag.size() <= n) {
            chaiJieFrag.add(0);
        }
    }

    private void parseHeroes(String text) {
        heroesByIndex.clear();
        heroIndexByFragment.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 10) {
                continue;
            }
            HeroCfg h = new HeroCfg();
            h.index = toInt(cols, 1);
            h.fragmentOri = cols[8];
            h.composeStar = Math.max(1, toInt(cols, 9));
            h.atkType = toInt(cols, 11);
            h.uType = toInt(cols, 12);
            h.weaponJob = toInt(cols, 16);
            h.peiJianJob = toInt(cols, 17);
            // WuJiangPropertyCfg.mInBackUpHpRestore / mInBackUpEnergyRestore
            h.inBackUpHpRestore = toFloat(cols, 18);
            h.inBackUpEnergyRestore = toFloat(cols, 19);
            h.baseAtk = toFloat(cols, 20);
            h.basePdef = toFloat(cols, 21);
            h.baseMdef = toFloat(cols, 22);
            h.baseHp = toFloat(cols, 23);
            h.agiAtkRatio = toFloat(cols, 24);
            h.intAtkRatio = toFloat(cols, 25);
            h.strHpRatio = toFloat(cols, 26);
            h.atkDefConvert = toFloat(cols, 27);
            h.baseAgi = toFloat(cols, 28);
            h.agiGrow = toFloat(cols, 29);
            h.baseInt = toFloat(cols, 30);
            h.intGrow = toFloat(cols, 31);
            h.baseStr = toFloat(cols, 32);
            h.strGrow = toFloat(cols, 33);
            // WuJiangPropertyCfg.mBaseSpeed / mBaseBaojiGailv(/10000) / mBaseBaojiAtk
            if (cols.length > 36) {
                h.baseSpeed = toFloat(cols, 34);
                h.baseCritRatio = toFloat(cols, 35) / 10000f;
                h.baseCritDamage = toFloat(cols, 36);
            }
            h.skillNormal = toInt(cols, 37);
            h.skillA = toInt(cols, 38);
            h.skillB = toInt(cols, 39);
            h.skillMingJiang = toInt(cols, 40);
            h.skillWuShuang = toInt(cols, 41);
            if (cols.length > 42) {
                String bd = cols[42];
                if (bd != null && !bd.isEmpty() && !"0".equals(bd)) {
                    int cut = bd.indexOf('_');
                    if (cut < 0) {
                        h.skillBeiDong1 = toInt(bd);
                    } else {
                        h.skillBeiDong1 = toInt(bd.substring(0, cut));
                        h.skillBeiDong2 = toInt(bd.substring(cut + 1));
                    }
                }
            }
            if (cols.length > 50) {
                h.jinJieType[0] = toInt(cols, 47);
                h.jinJieType[1] = toInt(cols, 48);
                h.jinJieType[2] = toInt(cols, 49);
                h.jinJieType[3] = toInt(cols, 50);
            }
            // 替换主玩家形象星级（客户端 WuJiangPropertyCfg.mExchangeModelStarLimit）
            if (cols.length > 51) {
                h.exchangeModelStarLimit = Math.max(0, toInt(cols, 51));
            }
            if (h.index > 0) {
                heroesByIndex.put(h.index, h);
                if (h.fragmentOri != null && !h.fragmentOri.isEmpty() && !"0".equals(h.fragmentOri)) {
                    heroIndexByFragment.put(h.fragmentOri, h.index);
                }
            }
        }
    }

    private void parseJinJie(String text) {
        jinJieByType.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            // type + name + remark + 22 grow + 10*(ori,count,single,jinJie) ≥ 1+3+22+40
            if (cols.length < 27) {
                continue;
            }
            JinJieCfg cfg = new JinJieCfg();
            cfg.type = toInt(cols, 1);
            cfg.attack = toFloat(cols, 4);
            cfg.damagePlusValue = toFloat(cols, 5);
            cfg.physicDefense = toFloat(cols, 6);
            cfg.magicDefense = toFloat(cols, 7);
            cfg.commonDefence = toFloat(cols, 8);
            cfg.damagePlusRate = toFloat(cols, 9) / 10000f;
            cfg.damageReduceRate = toFloat(cols, 10) / 10000f;
            cfg.life = toFloat(cols, 11);
            cfg.ignorDefence = toFloat(cols, 12);
            cfg.atkAddEnergy = toFloat(cols, 13);
            cfg.backupEnergyAdd = toFloat(cols, 14);
            cfg.addCureRate = toFloat(cols, 15) / 10000f;
            cfg.weaponAtkAddRate = toFloat(cols, 16) / 10000f;
            cfg.peiJianDefAddRate = toFloat(cols, 17) / 10000f;
            cfg.shiPinMaxHpAddRate = toFloat(cols, 18) / 10000f;
            cfg.baoJi = toFloat(cols, 19) / 10000f;
            cfg.weaponEffectP1 = toFloat(cols, 20);
            cfg.weaponEffectP2 = toFloat(cols, 21);
            cfg.peiJianEffectP1 = toFloat(cols, 22);
            cfg.peiJianEffectP2 = toFloat(cols, 23);
            cfg.doggeValue = toFloat(cols, 24);
            cfg.beAttackEnergyAdd = toFloat(cols, 25);
            int idx = 26;
            for (int s = 0; s < CombatAttrCalculator.JIE_DUAN_COUNT && idx + 3 < cols.length; s++) {
                cfg.ori[s] = cols[idx++];
                cfg.count[s] = toInt(cols, idx++);
                cfg.singleGrow[s] = toFloat(cols, idx++);
                cfg.jinJieGrow[s] = toFloat(cols, idx++);
            }
            if (cfg.type > 0) {
                jinJieByType.put(cfg.type, cfg);
            }
        }
    }

    private void parseGoodsTimeAttr(String text) {
        goodsTimeAttr.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 14) {
                continue;
            }
            // attribute==7 时光石；cols: 1=ori … 11=attribute 12=param1 13=param2
            if (toInt(cols, 11) != 7) {
                continue;
            }
            String ori = cols[1];
            String param2 = cols[13];
            if (param2 == null || param2.isEmpty() || "0".equals(param2)) {
                continue;
            }
            Map<Integer, Float> map = new HashMap<>();
            for (String part : param2.split(";")) {
                if (part == null || part.isEmpty()) {
                    continue;
                }
                int cut = part.indexOf('_');
                if (cut <= 0) {
                    continue;
                }
                int jjType = toInt(part.substring(0, cut));
                float val = toFloat(part.substring(cut + 1));
                JinJieCfg jj = jinJieByType.get(Integer.valueOf(jjType));
                if (jj != null && JinJieGrowType.isRateType(jj.primaryGrowType())) {
                    val /= 10000f;
                }
                map.put(Integer.valueOf(jjType), Float.valueOf(val));
            }
            if (!map.isEmpty()) {
                goodsTimeAttr.put(ori, map);
            }
        }
    }

    private void parseBuddies(String text) {
        buddiesById.clear();
        buddyIdsByHero.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 12) {
                continue;
            }
            BuddyCfg b = new BuddyCfg();
            b.id = toInt(cols, 1);
            b.role1 = toInt(cols, 3);
            b.role2 = toInt(cols, 4);
            b.role3 = toInt(cols, 5);
            b.role4 = toInt(cols, 6);
            b.role5 = toInt(cols, 7);
            b.atkRadio = toInt(cols, 9) / 10000f;
            b.defRadio = toInt(cols, 10) / 10000f;
            b.hpRadio = toInt(cols, 11) / 10000f;
            if (b.id <= 0) {
                continue;
            }
            buddiesById.put(Integer.valueOf(b.id), b);
            indexBuddyRole(b.role1, b.id);
            indexBuddyRole(b.role2, b.id);
            indexBuddyRole(b.role3, b.id);
            indexBuddyRole(b.role4, b.id);
            indexBuddyRole(b.role5, b.id);
        }
    }

    private void indexBuddyRole(int role, int buddyId) {
        if (role == 0) {
            return;
        }
        List<Integer> list = buddyIdsByHero.get(Integer.valueOf(role));
        if (list == null) {
            list = new ArrayList<>();
            buddyIdsByHero.put(Integer.valueOf(role), list);
        }
        list.add(Integer.valueOf(buddyId));
    }

    private void parseSuits(String text) {
        suits.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            // id name + 11 equipId + 4*(beiDong,atk,pdef,mdef,hp,des)
            if (cols.length < 14) {
                continue;
            }
            SuitCfg s = new SuitCfg();
            s.id = toInt(cols, 1);
            int idx = 3;
            for (int i = 0; i < 11 && idx < cols.length; i++) {
                s.equipIds[i] = cols[idx++];
            }
            for (int j = 0; j < 4 && idx + 5 < cols.length; j++) {
                s.beiDong[j] = toInt(cols, idx++);
                s.atkAdd[j] = toInt(cols, idx++);
                s.pdefAdd[j] = toInt(cols, idx++);
                s.mdefAdd[j] = toInt(cols, idx++);
                s.hpAdd[j] = toInt(cols, idx++);
                idx++; // des
            }
            suits.add(s);
        }
    }

    private void parseEquipSoul(String text) {
        soulsByHero.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 30) {
                continue;
            }
            SoulCfg s = new SoulCfg();
            s.id = toInt(cols, 1);
            s.heroIndex = toInt(cols, 3);
            // 表头第 7/8 字段「合成所需物品ID」「合成所需数量」（cols[6]/cols[7]，cols[0] 是输出符 `#`）：
            // 30 行里启用的 21 行全是 QH00x × 50，客户端 QiHunSys.cs:363 读同一列做合成进度条。
            s.composeGoods = cols.length > 6 ? cols[6].trim() : "";
            s.composeCount = toInt(cols, 7);
            int idx = 8;
            s.attrAdd[JinJieGrowType.AKT] = toFloat(cols, idx++);
            s.attrAdd[JinJieGrowType.DAMAGE_PLUS_VALUE] = toFloat(cols, idx++);
            s.attrAdd[JinJieGrowType.PHY_DEF] = toFloat(cols, idx++);
            s.attrAdd[JinJieGrowType.MAGIC_DEF] = toFloat(cols, idx++);
            s.attrAdd[JinJieGrowType.COMMON_DEFENCE] = toFloat(cols, idx++);
            s.attrAdd[JinJieGrowType.DAMAGE_PLUS_RATE] = toFloat(cols, idx++) / 10000f;
            s.attrAdd[JinJieGrowType.DAMAGE_REDUCE_RATE] = toFloat(cols, idx++) / 10000f;
            s.attrAdd[JinJieGrowType.LIFE] = toFloat(cols, idx++);
            s.attrAdd[JinJieGrowType.IGNOR_DEFENCE] = toFloat(cols, idx++);
            s.attrAdd[JinJieGrowType.ATK_ADD_ENERGY] = toFloat(cols, idx++);
            s.attrAdd[JinJieGrowType.BACKUP_ENERGY_ADD] = toFloat(cols, idx++);
            s.attrAdd[JinJieGrowType.ADD_CURE_RATE] = toFloat(cols, idx++) / 10000f;
            s.attrAdd[JinJieGrowType.BAO_JI] = toFloat(cols, idx++) / 10000f;
            // 武器/配件特效 9 列（cols[21..29] = 客户端 EquipSoulProperty.cs:34-42 的 num2..num10）。
            // 客户端按武将的 mWeaponJobAttribute/mPeiJianJobAttribute 只取其中一列（:44-74），
            // 而武将职业就在 HeroCfg 里（weaponJob/peiJianJob，parseHeroes 在本方法之前跑）⇒ 解析时即可定档：
            //   武器 job1→num2  job2→num3  job3→num5  job4→num4
            //   配件 job5→num7  job6→num6  job7→num8  job8→num10(P1) + num9(P2)
            float[] effect = new float[9];
            for (int i = 0; i < 9 && idx < cols.length; i++) {
                effect[i] = toFloat(cols, idx++);
            }
            HeroCfg hero = heroesByIndex.get(Integer.valueOf(s.heroIndex));
            if (hero != null) {
                switch (hero.weaponJob) {
                    case 1:
                        s.attrAdd[JinJieGrowType.WEAPON_EFFECT_P1] = effect[0];
                        break;
                    case 2:
                        s.attrAdd[JinJieGrowType.WEAPON_EFFECT_P1] = effect[1];
                        break;
                    case 3:
                        s.attrAdd[JinJieGrowType.WEAPON_EFFECT_P1] = effect[3];
                        break;
                    case 4:
                        s.attrAdd[JinJieGrowType.WEAPON_EFFECT_P1] = effect[2];
                        break;
                    default:
                        break;
                }
                switch (hero.peiJianJob) {
                    case 5:
                        s.attrAdd[JinJieGrowType.PEIJIAN_EFFECT_P1] = effect[5];
                        break;
                    case 6:
                        s.attrAdd[JinJieGrowType.PEIJIAN_EFFECT_P1] = effect[4];
                        break;
                    case 7:
                        s.attrAdd[JinJieGrowType.PEIJIAN_EFFECT_P1] = effect[6];
                        break;
                    case 8:
                        s.attrAdd[JinJieGrowType.PEIJIAN_EFFECT_P1] = effect[8];
                        s.attrAdd[JinJieGrowType.PEIJIAN_EFFECT_P2] = effect[7];
                        break;
                    default:
                        break;
                }
            }
            if (idx < cols.length) {
                s.attrAdd[JinJieGrowType.DOGGE_VALUE] = toFloat(cols, idx++);
            }
            if (idx < cols.length) {
                s.attrAdd[JinJieGrowType.BE_ATTACK_ENERGY_ADD] = toFloat(cols, idx);
            }
            if (s.heroIndex > 0) {
                soulsByHero.put(Integer.valueOf(s.heroIndex), s);
            }
        }
    }

    private void parseEquipSoulJinJie(String text) {
        soulJinJieById.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 7) {
                continue;
            }
            SoulJinJieCfg c = new SoulJinJieCfg();
            c.id = toInt(cols, 1);
            c.exp = toInt(cols, 3);
            c.section = toInt(cols, 4);
            c.sectionRate = toFloat(cols, 5);
            c.jieDuanRate = toFloat(cols, 6);
            soulJinJieById.put(Integer.valueOf(c.id), c);
        }
    }

    private void parseBookCompose(String text) {
        bookCompose.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            ComposeRecipe r = parseRecipe(cols);
            if (r != null) {
                bookCompose.put(r.ori, r);
            }
        }
    }

    private void parseSkillUpgrade(String text) {
        skillGold.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 6) {
                continue;
            }
            skillGold.add(new int[]{toInt(cols, 2), toInt(cols, 3), toInt(cols, 4), toInt(cols, 5)});
        }
    }

    private void parseSkillProps(String text) {
        skillsById.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRowsTab(text)) {
            if (cols.length < 34) {
                continue;
            }
            SkillProp s = new SkillProp();
            s.id = toInt(cols, 1);
            if (s.id <= 0) {
                continue;
            }
            s.modelPath = cols[2].trim();
            s.skillType = toInt(cols, 25);
            s.shuaiJian = toFloat(cols, 26);
            s.dmgBase = toFloat(cols, 27);
            s.dmgGrow = toFloat(cols, 28);
            s.hits = Math.max(1, toInt(cols, 31));
            s.cureType = toInt(cols, 33);
            // SkillPropertyCfg：iSummonWujiangID / Count / PosRadiu / AI / Time
            if (cols.length > 38) {
                s.summonWujiangId = toInt(cols, 34);
                s.summonCount = Math.max(0, toInt(cols, 35));
                s.summonPosRadius = toFloat(cols, 36);
                s.summonAi = toInt(cols, 37);
                s.summonTime = toFloat(cols, 38);
            }
            skillsById.put(Integer.valueOf(s.id), s);
        }
    }

    private void parseBeiDong(String text) {
        beiDongById.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRowsTab(text)) {
            if (cols.length < 12) {
                continue;
            }
            BeiDongProp s = new BeiDongProp();
            s.id = toInt(cols, 1);
            if (s.id <= 0) {
                continue;
            }
            s.targetType = toInt(cols, 9);
            s.ratio = toInt(cols, 10);
            s.ratioGrow = toInt(cols, 11);
            beiDongById.put(Integer.valueOf(s.id), s);
        }
    }

    private void parseGlobalSetup(String text) {
        if (text == null) {
            return;
        }
        List<String> tokens = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String[] cols = split(line);
            for (int i = 1; i < cols.length; i++) {
                if (!cols[i].isEmpty()) {
                    tokens.add(cols[i]);
                }
            }
        }
        if (tokens.size() > 35) {
            skillMinuMingJiang = toInt(tokens.get(28));
            skillMinuA = toInt(tokens.get(29));
            skillMinuB = toInt(tokens.get(30));
            skillMinuPassive = toInt(tokens.get(31));
            maxSkillPoint = Math.max(1, toInt(tokens.get(32)));
            skillPointRetrieveSeconds = Math.max(1, toInt(tokens.get(33)));
            buySkillPointRmb = Math.max(0, toInt(tokens.get(34)));
            buySkillPointCount = Math.max(1, toInt(tokens.get(35)));
        }
        if (tokens.size() > 55) {
            // GlobalSetup array[55] = ChangePioneerHPRestoreLimitRatio
            changePioneerHpRestoreLimitRatio = toFloat(tokens.get(55));
            if (changePioneerHpRestoreLimitRatio <= 0f) {
                changePioneerHpRestoreLimitRatio = 1f;
            }
        }
        if (tokens.size() > 64) {
            skillOpenMingJiang = toInt(tokens.get(61));
            skillOpenA = toInt(tokens.get(62));
            skillOpenB = toInt(tokens.get(63));
            skillOpenPassive = toInt(tokens.get(64));
        }
        if (tokens.size() > 108) {
            // GlobalSetup：ResistLianZhan / ZhiShang / BaoJi / BaoTou（array 末段，token 105–108）
            resistLianZhanRatio = toInt(tokens.get(105));
            resistZhiShangRatio = toInt(tokens.get(106));
            resistBaoJiRatio = toInt(tokens.get(107));
            resistBaoTouRatio = toInt(tokens.get(108));
        }
        if (tokens.size() > 113) {
            // PenetrateGeDang / ZheShe / FanJi / NengLiangToMoMian / NengLiangToNengLiang（109–113）
            penetrateGeDang = toInt(tokens.get(109));
            penetrateZheSheRatio = toInt(tokens.get(110));
            penetrateFanJiRatio = toInt(tokens.get(111));
            penetrateNengLiangToMoMianRatio = toInt(tokens.get(112));
            penetrateNengLiangToNengLiangRatio = toInt(tokens.get(113));
        }
    }

    /** 替补回血血量占比上限（ChangePioneerHPRestoreLimitRatio）。 */
    public float changePioneerHpRestoreLimitRatio() {
        return changePioneerHpRestoreLimitRatio;
    }

    /** GlobalSetup.ResistBaoJiRatio：抗巨力（暴击伤害减免）折算。 */
    public int resistBaoJiRatio() {
        return resistBaoJiRatio;
    }

    /** GlobalSetup.ResistZhiShangRatio：抗致伤（纯伤减免）折算。 */
    public int resistZhiShangRatio() {
        return resistZhiShangRatio;
    }

    /** GlobalSetup.ResistLianZhanRatio。 */
    public int resistLianZhanRatio() {
        return resistLianZhanRatio;
    }

    /** GlobalSetup.ResistBaoTouRatio。 */
    public int resistBaoTouRatio() {
        return resistBaoTouRatio;
    }

    /** GetEquipEffectPenetrate1(NENG_LIANG)：Penetrate × PenetrateNengLiangToMoMianRatio / 10000。 */
    public float nengLiangToMoMianPenetrate(float penetrateValue) {
        return penetrateValue * penetrateNengLiangToMoMianRatio / 10000f;
    }

    /** GetEquipEffectPenetrate2(NENG_LIANG)：Penetrate × PenetrateNengLiangToNengLiangRatio / 10000。 */
    public float nengLiangToNengLiangPenetrate(float penetrateValue) {
        return penetrateValue * penetrateNengLiangToNengLiangRatio / 10000f;
    }

    private void parseGoods(String text) {
        goodsQuality.clear();
        fragmentHero.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 12) {
                continue;
            }
            String ori = cols[1];
            goodsQuality.put(ori, toInt(cols, 6));
            if (toInt(cols, 11) == 2) {
                int hero = toInt(cols, 12);
                if (hero > 0) {
                    fragmentHero.put(ori, hero);
                }
            }
        }
    }

    private void parseEquipList(String text) {
        equips.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 21) {
                continue;
            }
            EquipCfg e = new EquipCfg();
            e.ori = cols[1];
            e.displayName = cols.length > 2 ? cols[2] : "";
            e.quality = toInt(cols, 6);
            e.goldPrice = toInt(cols, 7);
            e.auctionPrice = toInt(cols, 8);
            e.type = toInt(cols, 9);
            e.job = toInt(cols, 10);
            fillYuanFen(e, cols[11]);
            e.yuanFenEffectId = toInt(cols, 12);
            // 对齐 EquipmentData：攻/物防/法防/血 base+grow
            e.atkBase = toFloat(cols, 13);
            e.atkGrow = toFloat(cols, 14);
            e.pdefBase = toFloat(cols, 15);
            e.pdefGrow = toFloat(cols, 16);
            e.mdefBase = toFloat(cols, 17);
            e.mdefGrow = toFloat(cols, 18);
            e.hpBase = toFloat(cols, 19);
            e.hpGrow = toFloat(cols, 20);
            // EquipmentData：21 notShow 22 翅膀 23 装备被动 24/25 图 26–29 淬炼部位 30 淬炼星级被动
            if (cols.length > 23) {
                e.beidongId = toInt(cols, 23);
            }
            // 26–29 淬炼四部位属性类型（EquipRefineType 语义：1攻2物防3魔防4双防5血）
            if (cols.length > 29) {
                for (int k = 0; k < 4; k++) {
                    e.cuiLianPartAttrType[k] = toInt(cols, 26 + k);
                }
            }
            if (cols.length > 30) {
                String cl = cols[30];
                if (cl != null && !cl.isEmpty() && !"0".equals(cl)) {
                    int cut = cl.indexOf('_');
                    if (cut > 0) {
                        e.cuiLianBeiDongActStar = toInt(cl.substring(0, cut));
                        e.cuiLianBeiDongId = toInt(cl.substring(cut + 1));
                    }
                }
            }
            equips.put(e.ori, e);
        }
    }

    /** EquipmentData.yuanFenRoleIndexList：col11 下划线武将 index，0 忽略。 */
    private static void fillYuanFen(EquipCfg e, String text) {
        if (e == null || text == null || text.isEmpty() || "0".equals(text)) {
            return;
        }
        for (String part : text.split("_")) {
            int n = toInt(part);
            if (n > 0) {
                e.yuanFenHeroIndex.add(Integer.valueOf(n));
            }
        }
    }

    /**
     * APK 换装列表：武器对 weaponJob、配件对 peiJianJob，其余槽不卡职。
     */
    public boolean canWear(HeroCfg hero, EquipCfg eq) {
        if (hero == null || eq == null) {
            return false;
        }
        if (eq.type == 1) {
            return eq.job == hero.weaponJob;
        }
        if (eq.type == 2) {
            return eq.job == hero.peiJianJob;
        }
        return true;
    }

    public boolean equipHasYuanFen(String ori, int heroIndex) {
        EquipCfg eq = equip(ori);
        if (eq == null || heroIndex <= 0) {
            return false;
        }
        return eq.yuanFenHeroIndex.contains(Integer.valueOf(heroIndex));
    }

    private void parseEquipCompose(String text) {
        equipCompose.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            ComposeRecipe r = parseRecipe(cols);
            if (r != null) {
                equipCompose.put(r.ori, r);
            }
        }
    }

    private void parseEquipUpgrade(String text) {
        upgradeDelta.clear();
        if (text == null) {
            return;
        }
        List<String> nums = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] cols = split(line);
            if (cols.length == 0) {
                continue;
            }
            if ("#".equals(cols[0])) {
                if (cols.length > 2) {
                    upgradeDelta.add(toInt(cols, 2));
                }
            } else if (cols.length > 1) {
                nums.add(cols[1]);
            }
        }
        if (!nums.isEmpty()) {
            upgradeBaseGold = toInt(nums.get(0));
            for (int i = 1; i < 6 && i < nums.size(); i++) {
                try {
                    upgradeRate[i] = Float.parseFloat(nums.get(i));
                } catch (NumberFormatException ignored) {
                    upgradeRate[i] = 0f;
                }
            }
            for (int i = 0; i < 7 && 6 + i < nums.size(); i++) {
                upgradeCritWan[i + 1] = toInt(nums.get(6 + i));
            }
        }
    }

    private void parseEquipXiLian(String text) {
        xiLianRates.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            int type = toInt(cols, 1);
            int quality = toInt(cols, 2);
            int[] rates = new int[9];
            for (int i = 0; i < 9; i++) {
                rates[i] = toInt(cols, 9 + i * 2);
            }
            xiLianRates.put(Integer.valueOf((type << 16) | quality), rates);
        }
    }

    private void parseEquipStar(String text) {
        starCosts.clear();
        guhuaCosts.clear();
        if (text == null) {
            return;
        }
        List<String[]> hashes = hashRows(text);
        int i = 0;
        // 前 REAL_MAX+1 行：星级成长系数 / 固化系数
        while (i < hashes.size() && i < REAL_MAX_EQUIP_STAR + 1) {
            String[] cols = hashes.get(i);
            if (cols.length >= 4) {
                equipStarGrowMod[i] = toFloat(cols, 2);
                equipStarGuHuaMod[i] = toFloat(cols, 3);
            }
            i++;
        }
        while (i < hashes.size()) {
            String[] cols = hashes.get(i++);
            if (cols.length > 1 && "EndGuaHua".equals(cols[1])) {
                break;
            }
            if (cols.length >= 6) {
                GuHuaCost g = new GuHuaCost();
                g.quality = toInt(cols, 1);
                g.star = toInt(cols, 2);
                g.gold = toInt(cols, 3);
                g.matOri = cols[4];
                g.matCount = toInt(cols, 5);
                guhuaCosts.add(g);
            }
        }
        while (i < hashes.size()) {
            String[] cols = hashes.get(i++);
            if (cols.length < 8) {
                continue;
            }
            StarCost s = new StarCost();
            s.quality = toInt(cols, 1);
            s.upToStar = toInt(cols, 2);
            s.playerLevel = toInt(cols, 3);
            s.gold = toInt(cols, 4);
            s.equipCost = toInt(cols, 5);
            addMat(s.mats, cols, 6);
            addMat(s.mats, cols, 8);
            starCosts.add(s);
        }
    }

    /**
     * 假服装备 BaseScore（协议 field 7）：客户端只比较高低，不本地重算。
     * DLL 无公式；按品级主序 + 当前四维折算 + 装备被动/养成附加。
     * 权重：quality×1_000_000 保证跨品级；同品级比属性与被动。
     */
    public int computeEquipBaseScore(PlayerRecord.Equipment eq) {
        if (eq == null) {
            return 0;
        }
        EquipCfg ec = equip(eq.ori);
        if (ec == null) {
            return Math.max(0, eq.baseScore);
        }
        int level = Math.max(1, eq.level);
        int star = Math.max(0, eq.stars);
        int guhua = Math.max(0, eq.guhua);
        float atk = equipAttrFloat(ec, 0, level, star, guhua, eq.cuiLianStatus(),
                eq.jingLianSubLevel1, eq.jingLianSubLevel2, eq.jingLianLevel);
        float pdef = equipAttrFloat(ec, 1, level, star, guhua, eq.cuiLianStatus(),
                eq.jingLianSubLevel1, eq.jingLianSubLevel2, eq.jingLianLevel);
        float mdef = equipAttrFloat(ec, 2, level, star, guhua, eq.cuiLianStatus(),
                eq.jingLianSubLevel1, eq.jingLianSubLevel2, eq.jingLianLevel);
        float hp = equipAttrFloat(ec, 3, level, star, guhua, eq.cuiLianStatus(),
                eq.jingLianSubLevel1, eq.jingLianSubLevel2, eq.jingLianLevel);
        // 与角色无关的中性折算（换装比较不绑武将职业权重）
        int attrScore = Math.round(atk * 1.20f + hp * 0.10f + (pdef + mdef) * 0.55f);
        int parts = 0;
        if (eq.cuiLianParts != null) {
            for (boolean b : eq.cuiLianParts) {
                if (b) {
                    parts++;
                }
            }
        }
        int effect = parts * 18 + Math.max(0, eq.jingLianLevel) * 14;
        if (eq.xiLianValue != 0) {
            effect += 28;
        }
        if (eq.openCuiLian) {
            effect += 40;
        }
        int passive = 0;
        if (ec.beidongId > 0) {
            passive += 50_000;
        }
        if (ec.cuiLianBeiDongId > 0 && eq.openCuiLian
                && star >= ec.cuiLianBeiDongActStar) {
            passive += 30_000;
        }
        int q = Math.max(0, ec.quality);
        return q * 1_000_000 + attrScore + effect + passive;
    }

    /**
     * 对齐 EquipmentData.GetAttrValueFloat / GetAllAddtionalAttr（精炼子属性+飞跃+淬炼均读表）。
     * attrType: 0攻 1物防 2魔防 3血；cuiLianStatus 同客户端 GetCuiLianStatusInfo。
     */
    public float equipAttrFloat(EquipCfg ec, int attrType, int level, int star, int guhua,
                                int cuiLianStatus, int jingLianSub1, int jingLianSub2, int jingLianFeiYue) {
        if (ec == null) {
            return 0f;
        }
        float grow = attrGrow(ec, attrType);
        float base = attrBase(ec, attrType);
        if (base <= 0f && grow <= 0f) {
            return 0f;
        }
        float basePart = base + level * grow * starGrowModifier(star);
        float add = jingLianAdditional(ec, attrType, grow, star, guhua, cuiLianStatus,
                jingLianSub1, jingLianSub2, jingLianFeiYue);
        return basePart + add;
    }

    /** 兼容旧调用：淬炼状态=0。 */
    public float equipAttrFloat(EquipCfg ec, int attrType, int level, int star, int guhua,
                                int jingLianSub1, int jingLianSub2, int jingLianFeiYue) {
        return equipAttrFloat(ec, attrType, level, star, guhua, 0, jingLianSub1, jingLianSub2, jingLianFeiYue);
    }

    private float attrBase(EquipCfg ec, int attrType) {
        switch (attrType) {
            case 0:
                return ec.atkBase;
            case 1:
                return ec.pdefBase;
            case 2:
                return ec.mdefBase;
            case 3:
                return ec.hpBase;
            default:
                return 0f;
        }
    }

    private float attrGrow(EquipCfg ec, int attrType) {
        switch (attrType) {
            case 0:
                return ec.atkGrow;
            case 1:
                return ec.pdefGrow;
            case 2:
                return ec.mdefGrow;
            case 3:
                return ec.hpGrow;
            default:
                return 0f;
        }
    }

    /** GetAllAddtionalAttr。 */
    private float jingLianAdditional(EquipCfg ec, int attrType, float grow, int star, int guhua,
                                     int cuiLianStatus, int sub1, int sub2, int feiYue) {
        float num = 0f;
        num += grow * jingLianSubRate(ec, sub1, sub2, attrType);
        num += jingLianFeiYueUp(ec, feiYue, attrType, 2);
        num += grow * jingLianFeiYueUp(ec, feiYue, attrType, 1);
        int maxStar = MAX_EQUIP_STAR;
        int capped = Math.min(star, maxStar);
        for (int s = 0; s < capped; s++) {
            num += guHuaAttr(grow, s, MAX_EQUIP_GU_HUA + 1);
        }
        num += guHuaAttr(grow, capped, guhua);
        // 未开淬炼或未满 5 星：无淬炼附加
        if (star < maxStar || cuiLianStatus % 10 == 0) {
            return num;
        }
        // 开启淬炼：+ 80级5星0固化裸属性 * openCuiLianAttrModifier（递归时 status=0 不会再进淬炼）
        num += equipAttrFloat(ec, attrType, 80, 5, 0, 0, 0, 0, 0) * openCuiLianAttrModifier;
        for (int j = 0; j < CUI_LIAN_PARTS; j++) {
            if (!isCuiLianPartAttr(ec.cuiLianPartAttrType[j], attrType)) {
                continue;
            }
            if (star > maxStar) {
                for (int k = 0; k < star - maxStar; k++) {
                    num += guHuaAttr(grow, k + maxStar, MAX_EQUIP_GU_HUA / 2);
                }
            }
            int digit = cuiLianStatus / (int) Math.pow(10, j + 1) % 10;
            if (digit != 0) {
                num += guHuaAttr(grow, star, MAX_EQUIP_GU_HUA / 2);
            }
        }
        if (star > maxStar) {
            for (int l = 0; l < star - maxStar; l++) {
                num += guHuaAttr(grow, l + maxStar, 1);
            }
        }
        return num;
    }

    private static boolean isCuiLianPartAttr(int partType, int attrType) {
        if (partType <= 0 || partType > 5) {
            return false;
        }
        if (partType == 5) {
            return attrType == 1 || attrType == 2;
        }
        return partType - 1 == attrType;
    }

    /** GetTotalJingLianSubAttrRateUp：左右槽按装备类型绑定的 RefineType 累加比例。 */
    private float jingLianSubRate(EquipCfg ec, int sub1, int sub2, int attrType) {
        return jingLianSubRateOne(ec, sub1, attrType, 0) + jingLianSubRateOne(ec, sub2, attrType, 1);
    }

    private float jingLianSubRateOne(EquipCfg ec, int subLevel, int attrType, int subIndex) {
        if (ec == null || subLevel <= 0) {
            return 0f;
        }
        Integer upper = jingLianRefineUpper.get(Integer.valueOf(ec.quality));
        if (upper == null || subLevel > upper.intValue()) {
            return 0f;
        }
        int[] types = jingLianAttTypeByEquipType.get(Integer.valueOf(ec.type));
        if (types == null || subIndex < 0 || subIndex >= types.length) {
            return 0f;
        }
        int refineType = types[subIndex];
        int ratioIdx = refineTypeIndex(refineType, attrType);
        if (ratioIdx < 0) {
            return 0f;
        }
        float num = 0f;
        for (JingLianRefineRow row : jingLianRefines) {
            if (row.quality == ec.quality && subLevel >= row.refineLevel) {
                num += row.improveRatio[ratioIdx];
            }
        }
        return num;
    }

    /** GetTotalJingLianFeiYueAttrUp：useValueType 1=成长系数 2=固定点数。 */
    private float jingLianFeiYueUp(EquipCfg ec, int feiYueLevel, int attrType, int useValueType) {
        if (ec == null || feiYueLevel <= 0) {
            return 0f;
        }
        Integer upper = jingLianLeapUpper.get(Integer.valueOf(ec.quality));
        if (upper == null || feiYueLevel > upper.intValue()) {
            return 0f;
        }
        float num = 0f;
        int slot = ec.type;
        if (slot < 1 || slot > 5) {
            return 0f;
        }
        for (JingLianLeapRow row : jingLianLeaps) {
            if (row.quality != ec.quality || feiYueLevel < row.leapLevel) {
                continue;
            }
            List<JingLianLeapImprove> list = row.improvesByEquipType[slot];
            if (list == null) {
                continue;
            }
            for (JingLianLeapImprove im : list) {
                if (im.useValueType == useValueType && refineTypeIndex(im.improveType, attrType) >= 0) {
                    num += im.value;
                }
            }
        }
        return num;
    }

    /** EquipRefineDataMgr.refineTypeIndex → improveRatio 下标 1..5；不匹配返回 -1。 */
    private static int refineTypeIndex(int refineType, int attrType) {
        switch (refineType) {
            case 1:
                return attrType == 0 ? 1 : -1;
            case 2:
                return attrType == 1 ? 2 : -1;
            case 3:
                return attrType == 2 ? 3 : -1;
            case 4:
                return (attrType == 1 || attrType == 2) ? 4 : -1;
            case 5:
                return attrType == 3 ? 5 : -1;
            default:
                return -1;
        }
    }

    private float starGrowModifier(int star) {
        if (star < 0 || star >= equipStarGrowMod.length) {
            return 1f;
        }
        float v = equipStarGrowMod[star];
        return v > 0f ? v : 1f;
    }

    private float guHuaAttr(float baseGrow, int starLevel, int guhuaLevel) {
        if (guhuaLevel <= 0 || baseGrow <= 0f) {
            return 0f;
        }
        float mod = 0.2f;
        if (starLevel >= 0 && starLevel < equipStarGuHuaMod.length && equipStarGuHuaMod[starLevel] > 0f) {
            mod = equipStarGuHuaMod[starLevel] / (MAX_EQUIP_GU_HUA + 1f);
        }
        return 80f * baseGrow * mod * guhuaLevel;
    }

    private void parseDecompose(String text) {
        decomposeEquip.clear();
        decomposeTuZi.clear();
        if (text == null) {
            return;
        }
        for (String line : text.split("\r?\n")) {
            String[] cols = split(line);
            if (cols.length < 6) {
                continue;
            }
            if ("#".equals(cols[0])) {
                DecomposeRow r = new DecomposeRow();
                r.quality = toInt(cols, 1);
                r.star = toInt(cols, 3);
                r.gold = toInt(cols, 4);
                r.wnsp = toInt(cols, 5);
                addProducts(r, cols, 6);
                decomposeEquip.add(r);
            } else if ("*".equals(cols[0])) {
                DecomposeRow r = new DecomposeRow();
                r.quality = toInt(cols, 1);
                r.gold = toInt(cols, 3);
                r.wnsp = toInt(cols, 4);
                addProducts(r, cols, 5);
                decomposeTuZi.add(r);
            }
        }
    }

    private void parseCuiLian(String text) {
        cuiLianParts.clear();
        feiYueCosts.clear();
        openCuiLian = null;
        openCuiLianAttrModifier = 0f;
        if (text == null) {
            return;
        }
        List<String[]> hashes = hashRows(text);
        if (hashes.isEmpty()) {
            return;
        }
        int i = 0;
        // 首行：开启淬炼属性系数
        openCuiLianAttrModifier = toFloat(hashes.get(i++), 1);
        if (i < hashes.size()) {
            openCuiLian = parseCuiLianCost(hashes.get(i++));
        }
        while (i < hashes.size() && cuiLianParts.size() < MAX_EQUIP_STAR) {
            cuiLianParts.add(parsePartCost(hashes.get(i++)));
        }
        while (i < hashes.size() && feiYueCosts.size() < MAX_EQUIP_STAR) {
            feiYueCosts.add(parseCuiLianCost(hashes.get(i++)));
        }
    }

    /** EquipmentJingLian.txt：AttType → Leap → Refine（对齐 EquipRefineDataMgr）。 */
    private void parseJingLian(String text) {
        jingLianAttTypeByEquipType.clear();
        jingLianLeaps.clear();
        jingLianRefines.clear();
        jingLianRefineUpper.clear();
        jingLianLeapUpper.clear();
        if (text == null) {
            return;
        }
        int section = 0;
        for (String line : text.split("\r?\n")) {
            String[] cols = split(line);
            if (cols.length == 0 || !"#".equals(cols[0])) {
                continue;
            }
            if (cols.length > 1 && ("JingLianAttrEnd".equals(cols[1]) || "JingLianEnd".equals(cols[1]))) {
                section++;
                continue;
            }
            if (section == 0) {
                if (cols.length < 4) {
                    continue;
                }
                int et = toInt(cols, 1);
                jingLianAttTypeByEquipType.put(Integer.valueOf(et), new int[]{toInt(cols, 2), toInt(cols, 3)});
            } else if (section == 1) {
                JingLianLeapRow row = parseLeapRow(cols);
                if (row != null) {
                    jingLianLeaps.add(row);
                    Integer cur = jingLianLeapUpper.get(Integer.valueOf(row.quality));
                    if (cur == null || row.leapLevel > cur.intValue()) {
                        jingLianLeapUpper.put(Integer.valueOf(row.quality), Integer.valueOf(row.leapLevel));
                    }
                }
            } else if (section == 2) {
                JingLianRefineRow row = parseRefineRow(cols);
                if (row != null) {
                    jingLianRefines.add(row);
                    Integer cur = jingLianRefineUpper.get(Integer.valueOf(row.quality));
                    if (cur == null || row.refineLevel > cur.intValue()) {
                        jingLianRefineUpper.put(Integer.valueOf(row.quality), Integer.valueOf(row.refineLevel));
                    }
                }
            }
        }
        log.info("jingLian attTypes={} leaps={} refines={}",
                jingLianAttTypeByEquipType.size(), jingLianLeaps.size(), jingLianRefines.size());
    }

    private static JingLianLeapRow parseLeapRow(String[] cols) {
        // # leapLv quality exp improve[1..5] levelLimit gold mats…
        if (cols.length < 10) {
            return null;
        }
        JingLianLeapRow row = new JingLianLeapRow();
        row.leapLevel = toInt(cols, 1);
        row.quality = toInt(cols, 2);
        row.exp = toInt(cols, 3);
        for (int k = 1; k <= 5; k++) {
            String raw = cols.length > 3 + k ? cols[3 + k] : "0";
            row.improvesByEquipType[k] = parseLeapImproves(raw);
        }
        return row;
    }

    private static List<JingLianLeapImprove> parseLeapImproves(String raw) {
        List<JingLianLeapImprove> list = new ArrayList<>();
        if (raw == null || raw.isEmpty() || "0".equals(raw)) {
            return list;
        }
        for (String part : raw.split("\\|")) {
            String[] a = part.split("_");
            if (a.length < 3) {
                continue;
            }
            JingLianLeapImprove im = new JingLianLeapImprove();
            im.improveType = toInt(a[0]);
            im.useValueType = toInt(a[1]);
            im.value = toFloat(a[2]);
            list.add(im);
        }
        return list;
    }

    private static JingLianRefineRow parseRefineRow(String[] cols) {
        // # quality refineLv exp leapLimit ratio[1..5]
        if (cols.length < 10) {
            return null;
        }
        JingLianRefineRow row = new JingLianRefineRow();
        row.quality = toInt(cols, 1);
        row.refineLevel = toInt(cols, 2);
        row.exp = toInt(cols, 3);
        row.leapLevelLimit = toInt(cols, 4);
        for (int i = 1; i <= 5; i++) {
            row.improveRatio[i] = toFloat(cols, 4 + i);
        }
        return row;
    }

    private static ComposeRecipe parseRecipe(String[] cols) {
        if (cols.length < 4) {
            return null;
        }
        ComposeRecipe r = new ComposeRecipe();
        r.ori = cols[1];
        r.gold = toInt(cols, 2);
        for (int i = 0; i < 4; i++) {
            int base = 3 + i * 2;
            if (base + 1 >= cols.length) {
                break;
            }
            String name = cols[base];
            int count = toInt(cols, base + 1);
            if (name != null && !"0".equals(name) && count > 0) {
                r.mats.add(new Mat(name, count));
            }
        }
        return r;
    }

    private static CuiLianCost parseCuiLianCost(String[] cols) {
        CuiLianCost c = new CuiLianCost();
        c.upToStar = toInt(cols, 1);
        c.gold = toInt(cols, 2);
        c.equipCost = toInt(cols, 3);
        addMat(c.mats, cols, 4);
        addMat(c.mats, cols, 6);
        addMat(c.mats, cols, 8);
        return c;
    }

    private static PartCost parsePartCost(String[] cols) {
        PartCost c = new PartCost();
        c.stars = toInt(cols, 1);
        c.gold = toInt(cols, 2);
        addMat(c.mats, cols, 3);
        addMat(c.mats, cols, 5);
        addMat(c.mats, cols, 7);
        addMat(c.mats, cols, 9);
        return c;
    }

    private static void addMat(List<Mat> mats, String[] cols, int i) {
        if (i + 1 >= cols.length) {
            return;
        }
        String name = cols[i];
        int count = toInt(cols, i + 1);
        if (name != null && !"0".equals(name) && count > 0) {
            mats.add(new Mat(name, count));
        }
    }

    private static void addProducts(DecomposeRow r, String[] cols, int start) {
        for (int i = 0; i < 5; i++) {
            int base = start + i * 2;
            if (base + 1 >= cols.length) {
                break;
            }
            String name = cols[base];
            int count = toInt(cols, base + 1);
            if (name != null && !"0".equals(name) && count > 0) {
                r.goods.add(new Mat(name, count));
            }
        }
    }

    private static List<String[]> hashRows(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String[] cols = split(line);
            if (cols.length > 1 && "#".equals(cols[0])) {
                rows.add(cols);
            }
        }
        return rows;
    }

    private static List<String[]> hashRowsTab(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            if (!line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (cols.length > 1 && "#".equals(cols[0])) {
                rows.add(cols);
            }
        }
        return rows;
    }

    private static String[] split(String line) {
        return line.trim().split("[ \\t]+");
    }

    private static int toInt(String[] cols, int i) {
        if (i >= cols.length) {
            return 0;
        }
        return toInt(cols[i]);
    }

    private static int toInt(String s) {
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Float.parseFloat(s.trim());
            } catch (NumberFormatException e2) {
                return 0;
            }
        }
    }

    private static float toFloat(String[] cols, int i) {
        if (i >= cols.length) {
            return 0f;
        }
        return toFloat(cols[i]);
    }

    private static float toFloat(String s) {
        if (s == null) {
            return 0f;
        }
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    public static final class HeroCfg {
        public int index;
        public String fragmentOri = "";
        public int composeStar = 1;
        /** 可替换主角形象所需最低星；0/1 表示默认可用。 */
        public int exchangeModelStarLimit = 1;
        public int atkType;
        public int uType;
        /** 武器/配件职业（对齐 mWeaponJobAttribute / mPeiJianJobAttribute） */
        public int weaponJob;
        public int peiJianJob;
        /** 替补位每 3s 回血比例（相对 maxHp），对齐 mInBackUpHpRestore */
        public float inBackUpHpRestore;
        /** 替补位每 3s 能量回复，对齐 mInBackUpEnergyRestore */
        public float inBackUpEnergyRestore;
        public float baseAtk;
        public float basePdef;
        public float baseMdef;
        public float baseHp;
        public float agiAtkRatio;
        public float intAtkRatio;
        public float strHpRatio;
        public float atkDefConvert;
        public float baseAgi;
        public float agiGrow;
        public float baseInt;
        public float intGrow;
        public float baseStr;
        public float strGrow;
        public float baseSpeed;
        /** 表值/10000，对齐 mBaseBaojiGailv */
        public float baseCritRatio = 0.05f;
        /** 对齐 mBaseBaojiAtk（暴击伤害加成，非倍率） */
        public float baseCritDamage = 0.5f;
        public int skillNormal;
        public int skillA;
        public int skillB;
        public int skillMingJiang;
        public int skillWuShuang;
        public int skillBeiDong1;
        public int skillBeiDong2;
        public final int[] jinJieType = new int[4];
    }

    public static final class SkillProp {
        public int id;
        /** NewSkillProperty 编译器路径，如 ALO_TR/Skill_A → SkillCfg FX */
        public String modelPath = "";
        /** 0普攻伤害 1物理 2法术 3治疗 */
        public int skillType;
        /** 属性伤害衰减 fSkillShangHaiShuaiJian */
        public float shuaiJian;
        public float dmgBase;
        public float dmgGrow;
        public int hits = 1;
        public int cureType;
        /** 召唤武将 ID；0=不召唤 */
        public int summonWujiangId;
        public int summonCount;
        public float summonPosRadius;
        public int summonAi;
        public float summonTime;
    }

    /** 战斗用属性快照（开战写入 FightUnit）。 */
    public static final class CombatStats {
        public int heroIndex;
        public int atkType;
        public int uType;
        public float phyAtk;
        public float magAtk;
        public float pdef;
        public float mdef;
        public int maxHp;
        public int effectFp;
        public float critRatio = 0.05f;
        public float critDamage = 0.5f;
        /** 装备致伤：普攻额外纯伤倍率（param1/10000） */
        public float chunCuiDamageRatio;
        /** 装备暴头：普攻无视防御概率 */
        public float baoTouRatio;
        /** 装备能量转换：自身魔法减伤（作为目标时，叠加进阶减伤） */
        public float magDamageReduceRatio;
        /** 装备格挡率：BlockValToRate = param1/(param1+battleBlockConstant=100000) */
        public float geDangRatio;
        /** DamagePlusValue / DamagePlusRate（进阶面板增伤） / DamageRatio */
        public float damagePlusValue;
        /** 对齐 FightAttr.DamagePlusRate → Unit.GetDamagePlusRatio */
        public float damagePlusRate;
        public float damageRatio = 1f;
        /** IgnorDefence → DefenceReduce */
        public float defenceReduce;
        /** 进阶减伤（物/法同用） */
        public float phyDamageReduceRatio;
        public float healAddRatio;
        /** 面板穿透（FightAttr.Penetrate）；参与能量穿透折算 */
        public float penetrate;
        public int skillNormal;
        public int skillA;
        public int skillB;
        public int skillMingJiang;
        public int skillWuShuang;
        /** 被动技 id（对齐 HeroCfg / Monster BeiDong；GetSkillLevel 命中用） */
        public int skillBeiDong1;
        public int skillBeiDong2;
        public int skillLvNormal = 1;
        public int skillLvMingJiang = 1;
        public int skillLvA = 1;
        public int skillLvB = 1;
        public int skillLvPassive = 1;

        public int skillLevelOf(int skillId) {
            if (skillId <= 0) {
                return 1;
            }
            if (skillId == skillMingJiang || skillId == skillWuShuang) {
                return skillLvMingJiang;
            }
            if (skillId == skillA) {
                return skillLvA;
            }
            if (skillId == skillB) {
                return skillLvB;
            }
            if (skillId == skillNormal) {
                return skillLvNormal;
            }
            // 对齐 WuJiangInfo / Monster.GetSkillLevel：BeiDong → Skillindex4Level / uSkillBeiDongLevel
            if (skillId == skillBeiDong1 || skillId == skillBeiDong2) {
                return skillLvPassive;
            }
            return 1;
        }
    }

    public static final class BeiDongProp {
        public int id;
        /** 1自身 2在场队友 3全部队友 4敌方 */
        public int targetType;
        public int ratio;
        public int ratioGrow;
    }

    public static final class JinJieCfg {
        public int type;
        public float attack;
        public float damagePlusValue;
        public float physicDefense;
        public float magicDefense;
        public float commonDefence;
        public float damagePlusRate;
        public float damageReduceRate;
        public float life;
        public float ignorDefence;
        public float atkAddEnergy;
        public float backupEnergyAdd;
        public float addCureRate;
        public float weaponAtkAddRate;
        public float peiJianDefAddRate;
        public float shiPinMaxHpAddRate;
        public float baoJi;
        public float weaponEffectP1;
        public float weaponEffectP2;
        public float peiJianEffectP1;
        public float peiJianEffectP2;
        public float doggeValue;
        public float beAttackEnergyAdd;
        public final String[] ori = new String[CombatAttrCalculator.JIE_DUAN_COUNT];
        public final int[] count = new int[CombatAttrCalculator.JIE_DUAN_COUNT];
        public final float[] singleGrow = new float[CombatAttrCalculator.JIE_DUAN_COUNT];
        public final float[] jinJieGrow = new float[CombatAttrCalculator.JIE_DUAN_COUNT];

        public float growValue(int type) {
            switch (type) {
                case JinJieGrowType.AKT:
                    return attack;
                case JinJieGrowType.DAMAGE_PLUS_VALUE:
                    return damagePlusValue;
                case JinJieGrowType.PHY_DEF:
                    return physicDefense;
                case JinJieGrowType.MAGIC_DEF:
                    return magicDefense;
                case JinJieGrowType.COMMON_DEFENCE:
                    return commonDefence;
                case JinJieGrowType.DAMAGE_PLUS_RATE:
                    return damagePlusRate;
                case JinJieGrowType.DAMAGE_REDUCE_RATE:
                    return damageReduceRate;
                case JinJieGrowType.LIFE:
                    return life;
                case JinJieGrowType.IGNOR_DEFENCE:
                    return ignorDefence;
                case JinJieGrowType.ATK_ADD_ENERGY:
                    return atkAddEnergy;
                case JinJieGrowType.BACKUP_ENERGY_ADD:
                    return backupEnergyAdd;
                case JinJieGrowType.ADD_CURE_RATE:
                    return addCureRate;
                case JinJieGrowType.WEAPON_ATK_ADD_RATE:
                    return weaponAtkAddRate;
                case JinJieGrowType.PEIJIAN_DEF_ADD_RATE:
                    return peiJianDefAddRate;
                case JinJieGrowType.SHIPIN_MAXHP_ADD_RATE:
                    return shiPinMaxHpAddRate;
                case JinJieGrowType.BAO_JI:
                    return baoJi;
                case JinJieGrowType.WEAPON_EFFECT_P1:
                    return weaponEffectP1;
                case JinJieGrowType.WEAPON_EFFECT_P2:
                    return weaponEffectP2;
                case JinJieGrowType.PEIJIAN_EFFECT_P1:
                    return peiJianEffectP1;
                case JinJieGrowType.PEIJIAN_EFFECT_P2:
                    return peiJianEffectP2;
                case JinJieGrowType.DOGGE_VALUE:
                    return doggeValue;
                case JinJieGrowType.BE_ATTACK_ENERGY_ADD:
                    return beAttackEnergyAdd;
                default:
                    return 0f;
            }
        }

        /** 对齐 WuJiangJinjiePropertyCfg.GetGrowType：取首个非 0 成长类型。 */
        public int primaryGrowType() {
            if (attack != 0f) {
                return JinJieGrowType.AKT;
            }
            if (damagePlusValue != 0f) {
                return JinJieGrowType.DAMAGE_PLUS_VALUE;
            }
            if (physicDefense != 0f) {
                return JinJieGrowType.PHY_DEF;
            }
            if (magicDefense != 0f) {
                return JinJieGrowType.MAGIC_DEF;
            }
            if (commonDefence != 0f) {
                return JinJieGrowType.COMMON_DEFENCE;
            }
            if (damagePlusRate != 0f) {
                return JinJieGrowType.DAMAGE_PLUS_RATE;
            }
            if (damageReduceRate != 0f) {
                return JinJieGrowType.DAMAGE_REDUCE_RATE;
            }
            if (life != 0f) {
                return JinJieGrowType.LIFE;
            }
            if (ignorDefence != 0f) {
                return JinJieGrowType.IGNOR_DEFENCE;
            }
            if (atkAddEnergy != 0f) {
                return JinJieGrowType.ATK_ADD_ENERGY;
            }
            if (backupEnergyAdd != 0f) {
                return JinJieGrowType.BACKUP_ENERGY_ADD;
            }
            if (addCureRate != 0f) {
                return JinJieGrowType.ADD_CURE_RATE;
            }
            if (weaponAtkAddRate != 0f) {
                return JinJieGrowType.WEAPON_ATK_ADD_RATE;
            }
            if (peiJianDefAddRate != 0f) {
                return JinJieGrowType.PEIJIAN_DEF_ADD_RATE;
            }
            if (shiPinMaxHpAddRate != 0f) {
                return JinJieGrowType.SHIPIN_MAXHP_ADD_RATE;
            }
            if (baoJi != 0f) {
                return JinJieGrowType.BAO_JI;
            }
            if (weaponEffectP1 != 0f) {
                return JinJieGrowType.WEAPON_EFFECT_P1;
            }
            if (weaponEffectP2 != 0f) {
                return JinJieGrowType.WEAPON_EFFECT_P2;
            }
            if (peiJianEffectP1 != 0f) {
                return JinJieGrowType.PEIJIAN_EFFECT_P1;
            }
            if (peiJianEffectP2 != 0f) {
                return JinJieGrowType.PEIJIAN_EFFECT_P2;
            }
            if (doggeValue != 0f) {
                return JinJieGrowType.DOGGE_VALUE;
            }
            return JinJieGrowType.BE_ATTACK_ENERGY_ADD;
        }

        /** 对齐 GetToTalJinJieGrow。 */
        public float getTotalJinJieGrow(int jieDuan, int jieDuanPara, int type) {
            float grow = growValue(type);
            float num = 0f;
            int stage = Math.max(0, jieDuan);
            for (int i = 0; i < stage && i < CombatAttrCalculator.JIE_DUAN_COUNT; i++) {
                float part = count[i] * singleGrow[i] * grow + jinJieGrow[i] * grow;
                if (type == JinJieGrowType.ATK_ADD_ENERGY) {
                    num += (int) (part * 100f) / 100f;
                } else if (JinJieGrowType.isNonTruncateGrow(type)) {
                    num += part;
                } else {
                    num += (int) part;
                }
            }
            if (stage < CombatAttrCalculator.JIE_DUAN_COUNT) {
                num += jieDuanPara * singleGrow[stage] * grow;
            }
            return num;
        }
    }

    public static final class BuddyCfg {
        public int id;
        public int role1, role2, role3, role4, role5;
        public float atkRadio;
        public float defRadio;
        public float hpRadio;
    }

    public static final class SuitCfg {
        public int id;
        public final String[] equipIds = new String[11];
        /** 2/3/4/5 件套被动技能 id（对齐 EquipSuitData.mBeiDongID） */
        public final int[] beiDong = new int[4];
        public final int[] atkAdd = new int[4];
        public final int[] pdefAdd = new int[4];
        public final int[] mdefAdd = new int[4];
        public final int[] hpAdd = new int[4];

        /** 对齐 EquipSuitData.GetAtrAddValue：validSuitCount 从 2 件起算。 */
        public float attrAdd(int attrType, int validSuitCount) {
            float num = 0f;
            int left = validSuitCount - 2;
            int idx = 0;
            while (left-- >= 0 && idx < 4) {
                if (attrType == 0) {
                    num += atkAdd[idx];
                } else if (attrType == 1) {
                    num += pdefAdd[idx];
                } else if (attrType == 2) {
                    num += mdefAdd[idx];
                } else {
                    num += hpAdd[idx];
                }
                idx++;
            }
            return num;
        }
    }

    public static final class SoulCfg {
        public int id;
        public int heroIndex;
        /** {@code EquipSoulList.txt}「合成所需物品ID」；空串表示该行没配材料。 */
        public String composeGoods = "";
        /** {@code EquipSoulList.txt}「合成所需数量」。 */
        public int composeCount;
        public final float[] attrAdd = new float[22];
    }

    public static final class SoulJinJieCfg {
        public int id;
        public int exp;
        public int section;
        public float sectionRate;
        public float jieDuanRate;
    }

    public static final class ComposeRecipe {
        public String ori = "";
        public int gold;
        public final List<Mat> mats = new ArrayList<>();
    }

    public static final class EquipCfg {
        public String ori = "";
        /** EquipmentList col2 显示名（拍卖战利品名用）。 */
        public String displayName = "";
        public int quality;
        public int type;
        public int job;
        /** EquipmentList col7 金币价格（拆解/售卖口径）。 */
        public int goldPrice;
        /** EquipmentList col8「公会拍卖基础价格（勇气币）」：作战室拍卖起拍价用。 */
        public int auctionPrice;
        /** EquipmentList col11 缘分武将 index。 */
        public final List<Integer> yuanFenHeroIndex = new ArrayList<Integer>();
        /** EquipmentList col12 配缘效果ID（客户端 {@code EquipmentData.yuanFenEffectID}）；0=无。 */
        public int yuanFenEffectId;
        public float atkBase, atkGrow;
        public float pdefBase, pdefGrow;
        public float mdefBase, mdefGrow;
        public float hpBase, hpGrow;
        /** EquipmentList 装备被动技能 id；0=无。 */
        public int beidongId;
        /** 淬炼四部位属性类型（1攻2物防3魔防4双防5血）。 */
        public final int[] cuiLianPartAttrType = new int[4];
        /** 淬炼星级被动：达到该星且已开淬炼生效。 */
        public int cuiLianBeiDongActStar;
        public int cuiLianBeiDongId;
    }

    public static final class JingLianLeapRow {
        public int leapLevel;
        public int quality;
        public int exp;
        /** 下标 1..5 = 装备类型；每项为 AttImprove 列表。 */
        @SuppressWarnings("unchecked")
        public final List<JingLianLeapImprove>[] improvesByEquipType = new List[6];
    }

    public static final class JingLianLeapImprove {
        public int improveType;
        public int useValueType;
        public float value;
    }

    public static final class JingLianRefineRow {
        public int quality;
        public int refineLevel;
        public int exp;
        public int leapLevelLimit;
        /** 下标 1..5 对齐 EquipRefineType。 */
        public final float[] improveRatio = new float[6];
    }

    public static final class StarCost {
        public int quality;
        public int upToStar;
        public int playerLevel;
        public int gold;
        public int equipCost;
        public final List<Mat> mats = new ArrayList<>();
    }

    public static final class GuHuaCost {
        public int quality;
        public int star;
        public int gold;
        public String matOri = "";
        public int matCount;
    }

    public static final class DecomposeRow {
        public int quality;
        public int star;
        public int gold;
        public int wnsp;
        public final List<Mat> goods = new ArrayList<>();
    }

    public static final class CuiLianCost {
        public int upToStar;
        public int gold;
        public int equipCost;
        public final List<Mat> mats = new ArrayList<>();
    }

    public static final class PartCost {
        public int stars;
        public int gold;
        public final List<Mat> mats = new ArrayList<>();
    }

    public static final class Mat {
        public final String ori;
        public final int count;

        public Mat(String ori, int count) {
            this.ori = ori;
            this.count = count;
        }
    }
}
