package com.sao.fakeserver.table;

import com.sao.fakeserver.config.SaoProperties;
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

/**
 * 战斗 FX 段伤比例 + NewBuffProperty + EquipmentEffect。
 */
@Component
public class FightConfigTables {
    private static final Logger log = LoggerFactory.getLogger(FightConfigTables.class);

    private final SaoProperties props;
    /** modelSkillPath(ALO_TR/Skill_A) → mID → proportion */
    private final Map<String, Map<Integer, Float>> proportions = new HashMap<>();
    private final Map<Integer, BuffCfg> buffs = new HashMap<>();
    /** effectType(10000+) → cfg */
    private final Map<Integer, EquipEffectCfg> equipEffects = new HashMap<>();
    /** equipType*100 + equipJobType → cfg（开战按穿戴匹配） */
    private final Map<Integer, EquipEffectCfg> equipByTypeJob = new HashMap<>();
    /** MonsterProperty.ini：OriName → 表行战斗属性 */
    private final Map<String, MonsterCfg> monstersByOri = new HashMap<>();

    public FightConfigTables(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Path dir = Paths.get(props.getTablesDir());
        parseProportions(read(dir, "SkillDamageProportion.txt"));
        parseBuffs(read(dir, "NewBuffProperty.ini"));
        parseEquipEffects(read(dir, "EquipmentEffect.txt"));
        parseMonsters(read(dir, "MonsterProperty.ini"));
        log.info("fight-config proportions={} buffs={} equipEffects={} monsters={}",
                proportions.size(), buffs.size(), equipEffects.size(), monstersByOri.size());
    }

    public MonsterCfg monsterByOri(String ori) {
        if (ori == null || ori.isEmpty()) {
            return null;
        }
        return monstersByOri.get(ori);
    }

    public float proportion(String modelSkillPath, int mId, float fallback) {
        if (modelSkillPath == null || modelSkillPath.isEmpty()) {
            log.warn("fx proportion miss: empty modelPath mId={} → fallback={}", mId, fallback);
            return fallback;
        }
        Map<Integer, Float> map = proportions.get(modelSkillPath);
        if (map == null) {
            // 尝试只取文件名
            int slash = modelSkillPath.lastIndexOf('/');
            if (slash >= 0) {
                map = proportions.get(modelSkillPath.substring(slash + 1));
            }
        }
        if (map == null) {
            log.warn("fx proportion miss: path={} mId={} → fallback={}", modelSkillPath, mId, fallback);
            return fallback;
        }
        Float v = map.get(Integer.valueOf(mId));
        if (v == null) {
            log.warn("fx proportion miss: path={} mId={} → fallback={}", modelSkillPath, mId, fallback);
            return fallback;
        }
        return v.floatValue();
    }

    public BuffCfg buff(int id) {
        return buffs.get(Integer.valueOf(id));
    }

    public EquipEffectCfg equipEffect(int id) {
        return equipEffects.get(Integer.valueOf(id));
    }

    /** 按装备大类+职业匹配特效（武器 type=1 / 防具 type=2）。 */
    public EquipEffectCfg equipEffectByTypeJob(int equipType, int equipJobType) {
        return equipByTypeJob.get(Integer.valueOf(equipType * 100 + equipJobType));
    }

    private void parseProportions(String text) {
        proportions.clear();
        if (text == null) {
            return;
        }
        for (String line : text.split("\r?\n")) {
            if (!line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (cols.length < 4 || "modelSkillPath".equals(cols[1])) {
                continue;
            }
            String path = cols[1].trim();
            int mid = toInt(cols[2]);
            float prop = toFloat(cols[3]);
            proportions.computeIfAbsent(path, k -> new HashMap<>()).put(Integer.valueOf(mid), Float.valueOf(prop));
        }
    }

    /**
     * 对齐客户端 Buff(string[]) 列序（A_0[0]="#"）。
     */
    private void parseBuffs(String text) {
        buffs.clear();
        if (text == null) {
            return;
        }
        for (String line : text.split("\r?\n")) {
            String[] cols = splitWs(line);
            if (cols.length < 30 || !"#".equals(cols[0])) {
                continue;
            }
            BuffCfg b = new BuffCfg();
            b.id = toInt(cols[1]);
            if (b.id <= 0) {
                continue;
            }
            b.name = cols[2];
            b.duration = toFloat(cols[3]);
            // 13=interval 14=atkRatio(血量/吸收系数)
            b.interval = toFloat(cols, 13);
            b.atkRatio = toFloat(cols, 14);
            b.hpModifyBase = toFloat(cols, 15);
            b.hpModifyGrow = toFloat(cols, 16);
            b.curHpRatioBase = toFloat(cols, 17);
            b.curHpRatioGrow = toFloat(cols, 18);
            b.maxHpPeriodicBase = toFloat(cols, 19);
            b.maxHpPeriodicGrow = toFloat(cols, 20);
            b.maxHpBase = toFloat(cols, 21);
            b.maxHpGrow = toFloat(cols, 22);
            b.maxHpRatioBase = toFloat(cols, 23);
            b.maxHpRatioGrow = toFloat(cols, 24);
            b.attackBase = toFloat(cols, 25);
            b.attackGrow = toFloat(cols, 26);
            b.attackRatioBase = toFloat(cols, 27);
            b.attackRatioGrow = toFloat(cols, 28);
            b.pdefBase = toFloat(cols, 29);
            b.pdefGrow = toFloat(cols, 30);
            b.damageRatioBase = toFloat(cols, 31);
            b.damageRatioGrow = toFloat(cols, 32);
            b.mdefBase = toFloat(cols, 33);
            b.mdefGrow = toFloat(cols, 34);
            b.healRatioBase = toFloat(cols, 35);
            b.healRatioGrow = toFloat(cols, 36);
            b.healValueBase = toFloat(cols, 37);
            b.healValueGrow = toFloat(cols, 38);
            // 39-40 receive heal skipped indices carefully — Buff.cs reads receive heal then crit
            b.receiveHealBase = toFloat(cols, 39);
            b.receiveHealGrow = toFloat(cols, 40);
            b.critRatioBase = toFloat(cols, 41);
            b.critRatioGrow = toFloat(cols, 42);
            // 43-44 slow
            b.phyAbsorbBase = toFloat(cols, 45);
            b.phyAbsorbGrow = toFloat(cols, 46);
            b.magAbsorbBase = toFloat(cols, 47);
            b.magAbsorbGrow = toFloat(cols, 48);
            b.allAbsorbBase = toFloat(cols, 49);
            b.allAbsorbGrow = toFloat(cols, 50);
            b.phyTakenRatioBase = toFloat(cols, 51);
            b.phyTakenRatioGrow = toFloat(cols, 52);
            b.magTakenRatioBase = toFloat(cols, 53);
            b.magTakenRatioGrow = toFloat(cols, 54);
            // 55–56 反弹；57–58 物吸+反弹串；59–60 魔吸+反弹串；65–66 吸血；78 分摊；103 技能替换
            b.reflectBase = toFloat(cols, 55);
            b.reflectGrow = toFloat(cols, 56);
            parseAbsorbReboundPair(cols, 57, b, true);
            parseAbsorbReboundPair(cols, 59, b, false);
            b.xiXueBase = toFloat(cols, 65);
            b.xiXueGrow = toFloat(cols, 66);
            b.damageShareRatio = toFloat(cols, 78);
            // 88=aoe 伤基(可带半径 base_r)；89=成长
            if (cols.length > 88) {
                String aoe = cols[88];
                if (aoe != null && !aoe.isEmpty() && !"0".equals(aoe)) {
                    int us = aoe.indexOf('_');
                    b.aoeDamageBase = toFloat(us > 0 ? aoe.substring(0, us) : aoe);
                }
            }
            b.aoeDamageGrow = toFloat(cols, 89);
            b.noDieHpValue = (int) toFloat(cols, 93);
            b.noDieHpRatio = toFloat(cols, 94);
            b.defenceReduceBase = toFloat(cols, 101);
            b.defenceReduceGrow = toFloat(cols, 102);
            if (cols.length > 103) {
                b.skillChangeRaw = cols[103];
            }
            if (cols.length > 116) {
                b.skillTypeDamageRaw = cols[116];
            }
            b.dodgeRateBase = toFloat(cols, 117);
            b.dodgeRateGrow = toFloat(cols, 118);
            b.allResistBase = toFloat(cols, 121);
            b.allResistGrow = toFloat(cols, 122);
            b.resistBase = toFloat(cols, 123);
            b.resistGrow = toFloat(cols, 124);
            buffs.put(Integer.valueOf(b.id), b);
        }
    }

    /** Buff.cs：物/魔吸盾+反弹两串为 \"absBase_rebRatio\" / \"absGrow_rebGrow\"（例 1000_0.3）。 */
    private static void parseAbsorbReboundPair(String[] cols, int idx, BuffCfg b, boolean phy) {
        if (cols.length <= idx + 1) {
            return;
        }
        String first = cols[idx];
        String second = cols[idx + 1];
        float[] a = splitPair(first);
        float[] g = splitPair(second);
        // array2[0]=absBase, array2[1]=rebRatio；array3[0]=absGrow, array3[1]=rebGrow
        if (phy) {
            b.phyAbsorbReboundBase = a[0];
            b.phyReboundRatioBase = a[1];
            b.phyAbsorbReboundGrow = g[0];
            b.phyReboundRatioGrow = g[1];
        } else {
            b.magAbsorbReboundBase = a[0];
            b.magReboundRatioBase = a[1];
            b.magAbsorbReboundGrow = g[0];
            b.magReboundRatioGrow = g[1];
        }
    }

    private static float[] splitPair(String s) {
        float[] out = new float[2];
        if (s == null || s.isEmpty() || "0".equals(s)) {
            return out;
        }
        String[] p = s.split("_");
        if (p.length >= 1) {
            out[0] = toFloat(p[0]);
        }
        if (p.length >= 2) {
            out[1] = toFloat(p[1]);
        }
        return out;
    }

    private void parseEquipEffects(String text) {
        equipEffects.clear();
        equipByTypeJob.clear();
        if (text == null) {
            return;
        }
        for (String line : text.split("\r?\n")) {
            if (!line.startsWith("#")) {
                continue;
            }
            // 对齐 EquipmentEffect(string[])：空白切分
            String[] cols = splitWs(line);
            if (cols.length < 9 || !"#".equals(cols[0])) {
                continue;
            }
            EquipEffectCfg e = new EquipEffectCfg();
            e.equipType = toInt(cols[1]);
            e.equipJobType = toInt(cols[2]);
            // cols[3]=所属装备名（跳过）
            e.type = toInt(cols[4]);
            e.name = cols[5];
            // cols[6]=description；param1=7 param2=8
            e.param1 = toFloat(cols[7]);
            e.param2 = toFloat(cols[8]);
            e.id = e.type;
            if (e.type <= 0) {
                continue;
            }
            equipEffects.put(Integer.valueOf(e.type), e);
            equipByTypeJob.put(Integer.valueOf(e.equipType * 100 + e.equipJobType), e);
        }
    }

    /** 对齐 MonsterPropertyCfg：# 行 Tab 分列，按 OriName 索引。 */
    private void parseMonsters(String text) {
        monstersByOri.clear();
        if (text == null) {
            return;
        }
        for (String line : text.split("\r?\n")) {
            if (!line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (cols.length < 35 || "ID".equals(cols[1])) {
                continue;
            }
            MonsterCfg m = new MonsterCfg();
            m.id = toInt(cols[1]);
            m.oriName = cols[2] != null ? cols[2].trim() : "";
            if (m.oriName.isEmpty()) {
                continue;
            }
            m.maxHp = Math.max(1, Math.round(toFloat(cols[13])));
            m.phyAtk = toFloat(cols[14]);
            m.pdef = toFloat(cols[15]);
            m.magAtk = toFloat(cols[16]);
            m.mdef = toFloat(cols[17]);
            m.damagePlusValue = toFloat(cols[18]);
            m.damagePlusRate = toFloat(cols[19]);
            m.phyDamageReduceRatio = toFloat(cols[21]);
            m.magDamageReduceRatio = toFloat(cols[23]);
            m.skillLianXu = toInt(cols[27]);
            m.skillLianXuLv = Math.max(0, toInt(cols[28]));
            m.skillSpell1 = toInt(cols[29]);
            m.skillSpell1Lv = Math.max(0, toInt(cols[30]));
            m.skillSpell2 = toInt(cols[31]);
            m.skillSpell2Lv = Math.max(0, toInt(cols[32]));
            m.skillMingJiang = toInt(cols[33]);
            m.skillMingJiangLv = Math.max(0, toInt(cols[34]));
            // 被动：可 id 或 id1_id2，等级共一列（对齐 MonsterPropertyCfg）
            String beiDong = cols.length > 35 && cols[35] != null ? cols[35].trim() : "0";
            if (!beiDong.isEmpty() && !"0".equals(beiDong)) {
                int us = beiDong.indexOf('_');
                if (us < 0) {
                    m.skillBeiDong1 = toInt(beiDong);
                } else {
                    m.skillBeiDong1 = toInt(beiDong.substring(0, us));
                    m.skillBeiDong2 = toInt(beiDong.substring(us + 1));
                }
            }
            m.skillBeiDongLv = cols.length > 36 ? Math.max(0, toInt(cols[36])) : 0;
            monstersByOri.put(m.oriName, m);
        }
    }

    private static String[] splitWs(String line) {
        List<String> out = new ArrayList<>();
        for (String p : line.split("[ \\t]+")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out.toArray(new String[0]);
    }

    private static String read(Path dir, String name) {
        Path p = dir.resolve(name);
        if (!Files.isRegularFile(p)) {
            log.warn("missing {}", p);
            return null;
        }
        try {
            byte[] raw = Files.readAllBytes(p);
            if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF) {
                return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
            }
            return new String(raw, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("read {} fail {}", name, e.toString());
            return null;
        }
    }

    private static float toFloat(String[] cols, int i) {
        if (i >= cols.length) {
            return 0f;
        }
        return toFloat(cols[i]);
    }

    private static float toFloat(String s) {
        if (s == null || s.isEmpty()) {
            return 0f;
        }
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            try {
                return (int) Float.parseFloat(s.trim());
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    public static final class BuffCfg {
        public int id;
        public String name = "";
        public float duration;
        public float interval;
        public float atkRatio;
        public float hpModifyBase, hpModifyGrow;
        public float curHpRatioBase, curHpRatioGrow;
        public float maxHpPeriodicBase, maxHpPeriodicGrow;
        public float maxHpBase, maxHpGrow;
        public float maxHpRatioBase, maxHpRatioGrow;
        public float attackBase, attackGrow;
        public float attackRatioBase, attackRatioGrow;
        public float pdefBase, pdefGrow;
        public float damageRatioBase, damageRatioGrow;
        public float mdefBase, mdefGrow;
        public float healRatioBase, healRatioGrow;
        public float healValueBase, healValueGrow;
        public float receiveHealBase, receiveHealGrow;
        public float critRatioBase, critRatioGrow;
        public float phyAbsorbBase, phyAbsorbGrow;
        public float magAbsorbBase, magAbsorbGrow;
        public float allAbsorbBase, allAbsorbGrow;
        public float phyTakenRatioBase, phyTakenRatioGrow;
        public float magTakenRatioBase, magTakenRatioGrow;
        public float reflectBase, reflectGrow;
        public float xiXueBase, xiXueGrow;
        public float phyAbsorbReboundBase, phyAbsorbReboundGrow;
        public float phyReboundRatioBase, phyReboundRatioGrow;
        public float magAbsorbReboundBase, magAbsorbReboundGrow;
        public float magReboundRatioBase, magReboundRatioGrow;
        public float damageShareRatio;
        public float aoeDamageBase, aoeDamageGrow;
        public int noDieHpValue;
        public float noDieHpRatio;
        public float defenceReduceBase, defenceReduceGrow;
        /** EquipSkillChange：normal_A_B */
        public String skillChangeRaw = "0";
        /** EquipSkillTypeDamageRatio：NORMAL_A_B_MJ 下划线串。 */
        public String skillTypeDamageRaw = "0";
        public float dodgeRateBase, dodgeRateGrow;
        /** EquipAllResistBuffElement / EquipResistBuffElement 基础+成长。 */
        public float allResistBase, allResistGrow;
        public float resistBase, resistGrow;

        public static float scaled(float base, float grow, int level) {
            // 对齐客户端 base + grow*(level-1)；level=0 → base-grow（禁止抬成 0 乘子）
            return base + grow * (level - 1);
        }

        /** 技能类型 → 伤比；对齐 Buff.cs array9[0..3]。索引：0普攻 1A 2B 3名将/无双。 */
        public float skillTypeDamageRatio(int skillTypeIndex) {
            if (skillTypeDamageRaw == null || skillTypeDamageRaw.isEmpty() || "0".equals(skillTypeDamageRaw)) {
                return 0f;
            }
            String[] parts = skillTypeDamageRaw.split("_");
            int idx = skillTypeIndex;
            if (idx == 4) {
                // WUSHUANG 与名将共用第 4 段
                idx = 3;
            }
            if (idx < 0 || idx >= parts.length) {
                return 0f;
            }
            return FightConfigTables.toFloat(parts[idx]);
        }

        /** 吸收盾量：atk*系数 + 基础 + 成长*(lv-1) */
        public float absorbAmount(float atk, float base, float grow, int level) {
            return atk * atkRatio + scaled(base, grow, level);
        }
    }

    public static final class EquipEffectCfg {
        public int id;
        /** EquipBuffType：10000 折射 … 10007 致伤 */
        public int type;
        public int equipType;
        public int equipJobType;
        public String name = "";
        public float param1;
        public float param2;

        /** 万分比 → 比率（对齐客户端 /10000f） */
        public float param1Ratio() {
            return param1 / 10000f;
        }

        public float param2Ratio() {
            return param2 / 10000f;
        }
    }

    /**
     * MonsterProperty.ini 一行（对齐 MonsterPropertyCfg / MonsterAttribute）。
     * 列序：# ID OriName … HP 物攻 物防 魔攻 魔防 增伤值 增伤率 … 技能…
     */
    public static final class MonsterCfg {
        public int id;
        public String oriName = "";
        public int maxHp;
        public float phyAtk;
        public float pdef;
        public float magAtk;
        public float mdef;
        public float damagePlusValue;
        public float damagePlusRate;
        public float phyDamageReduceRatio;
        public float magDamageReduceRatio;
        public int skillLianXu;
        public int skillLianXuLv = 1;
        public int skillSpell1;
        public int skillSpell1Lv = 1;
        public int skillSpell2;
        public int skillSpell2Lv = 1;
        public int skillMingJiang;
        public int skillMingJiangLv = 1;
        public int skillBeiDong1;
        public int skillBeiDong2;
        public int skillBeiDongLv = 1;
    }

    /** EquipBuffType 常量（与客户端一致）。 */
    public static final int EE_XI_SHOU = 10000;
    public static final int EE_FAN_JI = 10001;
    public static final int EE_GE_DANG = 10002;
    public static final int EE_NENG_LIANG = 10003;
    public static final int EE_LIAN_ZHAN = 10004;
    public static final int EE_JU_LI = 10005;
    public static final int EE_BAO_TOU = 10006;
    public static final int EE_ZHI_SHANG = 10007;
}
