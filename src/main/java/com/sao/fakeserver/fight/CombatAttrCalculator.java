package com.sao.fakeserver.fight;

import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.table.CultivateTables;

import java.util.ArrayList;
import java.util.List;

/**
 * 对齐客户端 WuJiang.CalculatePhyAkt / CalculatePhyDef / CalculateMagicAkt / CalculateMagicDef / CalculateHP
 * 以及进阶、羁绊、器魂、时光石、套装、暴击与增伤面板。
 */
public final class CombatAttrCalculator {
    public static final int MAX_BUDDIES = 10;
    public static final int BUDDIES_HOLE_SIZE = 3;
    public static final int JIE_DUAN_COUNT = 10;
    /** 客户端 DamageReduceRateLimit 缺档时不硬截；真档来自玩家存档字段。 */
    public static final float DEFAULT_DMG_REDUCE_LIMIT = 1f;

    private CombatAttrCalculator() {
    }

    public static CultivateTables.CombatStats build(CultivateTables tables, PlayerRecord rec, PlayerRecord.Hero wj) {
        return build(tables, rec, wj, true);
    }

    /**
     * @param includeRemovable true=含可卸部分（装备/套装/时光石/羁绊乘区）；
     *                         false=仅不可逆成长（等级/星/进阶/器魂）→ 协议 fightpower(15)。
     *                         addfightpower(17)=full−base。
     */
    public static CultivateTables.CombatStats build(CultivateTables tables, PlayerRecord rec, PlayerRecord.Hero wj,
                                                    boolean includeRemovable) {
        if (wj == null || tables == null) {
            return null;
        }
        CultivateTables.HeroCfg c = tables.heroByIndex(wj.heroIndex);
        if (c == null || (c.baseAtk <= 0f && c.baseHp <= 0f)) {
            return null;
        }
        // 对齐 APK WuJiang.Calculate*：Level / Stars 原样参与（含 0）；
        // Agility = base+(Star-1)*grow；攻血项再 × Level。禁止 Math.max 抬成 1。
        int star = wj.stars;
        int lv = wj.level;

        // 客户端 Calculate* 两段羁绊乘区位置不同，禁止合并：
        // 1) CalculateBuddiesGrowValue（天赋孔）在 进阶/装备 之前
        // 2) BuddiesPropertyCfgMgr.GetAddRadio（缘分）在 装备+套装 之后
        float slotAtk = 0f;
        float slotDef = 0f;
        float slotHp = 0f;
        float radioAtk = 0f;
        float radioDef = 0f;
        float radioHp = 0f;
        if (includeRemovable) {
            List<Integer> buddies = padBuddies(rec);
            slotAtk = buddiesSlotGrow(rec, wj.heroIndex, 0);
            slotDef = buddiesSlotGrow(rec, wj.heroIndex, 1);
            slotHp = buddiesSlotGrow(rec, wj.heroIndex, 2);
            radioAtk = tables.buddiesAddRadio(wj.heroIndex, buddies, 0);
            radioDef = tables.buddiesAddRadio(wj.heroIndex, buddies, 1);
            radioHp = tables.buddiesAddRadio(wj.heroIndex, buddies, 2);
        }

        // 进阶比率：基础算式含器魂；时光石属可卸，仅 full 计入
        float weaponAtkRate = jinJieTotal(tables, rec, wj, c, JinJieGrowType.WEAPON_ATK_ADD_RATE, includeRemovable, true);
        float peiDefRate = jinJieTotal(tables, rec, wj, c, JinJieGrowType.PEIJIAN_DEF_ADD_RATE, includeRemovable, true);
        float shipinHpRate = jinJieTotal(tables, rec, wj, c, JinJieGrowType.SHIPIN_MAXHP_ADD_RATE, includeRemovable, true);

        float equipAtk = 0f;
        float equipPdef = 0f;
        float equipMdef = 0f;
        float equipHp = 0f;
        int effectFp = 0;
        // 武器配缘：装备 EquipmentList col11 配缘角色index 命中该武将 ⇒ 名将技换无双技
        // （APK WuJiangInfo.cs:771/787-794 mIsOpenWuQiYuanFen + WuJiang.cs:351-354 三处换技能）
        boolean yuanFenUlt = false;
        List<String> equippedOri = new ArrayList<>();
        if (includeRemovable && rec != null && rec.equipments != null && wj.id != null) {
            for (PlayerRecord.Equipment eq : rec.equipments) {
                if (eq == null || eq.owner == null || !wj.id.equals(eq.owner)) {
                    continue;
                }
                CultivateTables.EquipCfg ec = tables.equip(eq.ori);
                if (ec == null) {
                    continue;
                }
                equippedOri.add(eq.ori);
                float atk = tables.equipAttrFloat(ec, 0, eq.level, eq.stars, eq.guhua, eq.cuiLianStatus(),
                        eq.jingLianSubLevel1, eq.jingLianSubLevel2, eq.jingLianLevel);
                float pdef = tables.equipAttrFloat(ec, 1, eq.level, eq.stars, eq.guhua, eq.cuiLianStatus(),
                        eq.jingLianSubLevel1, eq.jingLianSubLevel2, eq.jingLianLevel);
                float mdef = tables.equipAttrFloat(ec, 2, eq.level, eq.stars, eq.guhua, eq.cuiLianStatus(),
                        eq.jingLianSubLevel1, eq.jingLianSubLevel2, eq.jingLianLevel);
                float hp = tables.equipAttrFloat(ec, 3, eq.level, eq.stars, eq.guhua, eq.cuiLianStatus(),
                        eq.jingLianSubLevel1, eq.jingLianSubLevel2, eq.jingLianLevel);
                if (ec.type == 1) {
                    atk *= 1f + weaponAtkRate;
                    if (ec.yuanFenHeroIndex.contains(Integer.valueOf(wj.heroIndex))) {
                        yuanFenUlt = true;
                    }
                } else if (ec.type == 2) {
                    pdef *= 1f + peiDefRate;
                    mdef *= 1f + peiDefRate;
                } else if (ec.type == 3) {
                    hp *= 1f + shipinHpRate;
                }
                equipAtk += (int) atk;
                equipPdef += (int) pdef;
                equipMdef += (int) mdef;
                equipHp += (int) hp;
                int parts = 0;
                if (eq.cuiLianParts != null) {
                    for (boolean b : eq.cuiLianParts) {
                        if (b) {
                            parts++;
                        }
                    }
                }
                effectFp += parts * 18 + eq.jingLianLevel * 14;
                if (eq.xiLianValue != 0) {
                    effectFp += 28;
                }
            }
        }
        float suitAtk = includeRemovable ? tables.suitAttrAdd(0, equippedOri) : 0f;
        float suitPdef = includeRemovable ? tables.suitAttrAdd(1, equippedOri) : 0f;
        float suitMdef = includeRemovable ? tables.suitAttrAdd(2, equippedOri) : 0f;
        float suitHp = includeRemovable ? tables.suitAttrAdd(3, equippedOri) : 0f;

        float jinJieAkt = jinJieTotal(tables, rec, wj, c, JinJieGrowType.AKT, includeRemovable, true);
        float jinJiePhy = jinJieTotal(tables, rec, wj, c, JinJieGrowType.PHY_DEF, includeRemovable, true);
        float jinJieMag = jinJieTotal(tables, rec, wj, c, JinJieGrowType.MAGIC_DEF, includeRemovable, true);
        float jinJieCommon = jinJieTotal(tables, rec, wj, c, JinJieGrowType.COMMON_DEFENCE, includeRemovable, true);
        float jinJieLife = jinJieTotal(tables, rec, wj, c, JinJieGrowType.LIFE, includeRemovable, true);

        float agi = c.baseAgi + (star - 1) * c.agiGrow;
        float intel = c.baseInt + (star - 1) * c.intGrow;
        float str = c.baseStr + (star - 1) * c.strGrow;

        // CalculatePhyAkt：base → ×孔 → +进阶 → +装/套 → ×缘分 → +战力返还
        float phyAtk = c.baseAtk + agi * c.agiAtkRatio * lv * c.atkDefConvert;
        phyAtk *= 1f + slotAtk;
        phyAtk += jinJieAkt;
        phyAtk += equipAtk + suitAtk;
        if (includeRemovable) {
            phyAtk *= 1f + radioAtk;
        }
        // CalculateMagicAkt
        float magAtk = c.baseAtk + intel * c.intAtkRatio * lv * c.atkDefConvert;
        magAtk *= 1f + slotAtk;
        magAtk += jinJieAkt;
        magAtk += equipAtk + suitAtk;
        if (includeRemovable) {
            magAtk *= 1f + radioAtk;
        }
        // CalculatePhyDef
        float pdef = c.basePdef + agi * c.agiAtkRatio * lv;
        pdef *= 1f + slotDef;
        pdef += jinJiePhy + jinJieCommon;
        pdef += equipPdef + suitPdef;
        if (includeRemovable) {
            pdef *= 1f + radioDef;
        }
        // CalculateMagicDef
        float mdef = c.baseMdef + intel * c.intAtkRatio * lv;
        mdef *= 1f + slotDef;
        mdef += jinJieMag + jinJieCommon;
        mdef += equipMdef + suitMdef;
        if (includeRemovable) {
            mdef *= 1f + radioDef;
        }
        // CalculateHP
        float hp = c.baseHp + str * c.strHpRatio * lv;
        hp *= 1f + slotHp;
        hp += jinJieLife;
        hp += equipHp + suitHp;
        if (includeRemovable) {
            hp *= 1f + radioHp;
        }

        // FightPowerReturn：只加在 full；档位用羁绊位 bare 战力（协议 field15）汇总，避免循环
        if (includeRemovable) {
            int[] fpRet = fightPowerReturn(tables, rec);
            phyAtk += fpRet[0];
            magAtk += fpRet[0];
            pdef += fpRet[1];
            mdef += fpRet[1];
            hp += fpRet[2];
        }

        float dmgPlusValue = jinJieTotal(tables, rec, wj, c, JinJieGrowType.DAMAGE_PLUS_VALUE, includeRemovable, true);
        float dmgPlusRate = jinJieTotal(tables, rec, wj, c, JinJieGrowType.DAMAGE_PLUS_RATE, includeRemovable, true);
        float dmgReduce = jinJieTotal(tables, rec, wj, c, JinJieGrowType.DAMAGE_REDUCE_RATE, includeRemovable, true);
        if (dmgReduce > DEFAULT_DMG_REDUCE_LIMIT) {
            dmgReduce = DEFAULT_DMG_REDUCE_LIMIT;
        }
        float crit = c.baseCritRatio + jinJieTotal(tables, rec, wj, c, JinJieGrowType.BAO_JI, includeRemovable, true);
        float ignorDef = jinJieTotal(tables, rec, wj, c, JinJieGrowType.IGNOR_DEFENCE, includeRemovable, true);
        float healAdd = jinJieTotal(tables, rec, wj, c, JinJieGrowType.ADD_CURE_RATE, includeRemovable, true);

        CultivateTables.CombatStats st = new CultivateTables.CombatStats();
        st.heroIndex = wj.heroIndex;
        st.atkType = c.atkType;
        st.uType = c.uType;
        st.phyAtk = phyAtk;
        st.magAtk = magAtk;
        st.pdef = pdef;
        st.mdef = mdef;
        st.maxHp = Math.max(1, Math.round(hp));
        st.effectFp = effectFp;
        st.skillMingJiang = yuanFenUlt && c.skillWuShuang > 0 ? c.skillWuShuang : c.skillMingJiang;
        st.skillWuShuang = c.skillWuShuang;
        st.skillA = c.skillA;
        st.skillB = c.skillB;
        st.skillNormal = c.skillNormal;
        st.skillBeiDong1 = c.skillBeiDong1;
        st.skillBeiDong2 = c.skillBeiDong2;
        // 存档原值（可为 0）；对齐 GetSkillLevel，禁止一律抬成 1 扭曲 Buff 成长
        st.skillLvMingJiang = wj.skill1;
        st.skillLvA = wj.skill2;
        st.skillLvB = wj.skill3;
        st.skillLvPassive = wj.skill4;
        st.critRatio = crit;
        st.critDamage = c.baseCritDamage;
        st.damagePlusValue = dmgPlusValue;
        st.damagePlusRate = dmgPlusRate;
        st.damageRatio = 1f;
        st.defenceReduce = ignorDef;
        st.phyDamageReduceRatio = dmgReduce;
        // magDamageReduceRatio 留给装备能量转换；进阶减伤两边在 FightUnit 用 phyDamageReduceRatio
        st.healAddRatio = healAdd;
        return st;
    }

    /** 与 detail 59–62 / RecaculatePowerReturn 同源。 */
    public static final int JIBAN_FP_RETURN_STANDARD = 5000;
    public static final int JIBAN_FP_RETURN_ATK = 10;
    public static final int JIBAN_FP_RETURN_DEF = 4;
    public static final int JIBAN_FP_RETURN_HP = 300;

    /**
     * 对齐客户端 RecaculatePowerReturn + CalculateFightPowerReturnGrowValue。
     * 只用 bare 战力（不含可卸），再 build(full) 时加回，避免循环。
     */
    public static int[] fightPowerReturn(CultivateTables tables, PlayerRecord rec) {
        int totalBase = 0;
        if (rec != null && rec.jiban != null) {
            rec.jiban.ensure();
            List<Integer> buddies = padBuddies(rec);
            for (Integer idx : buddies) {
                if (idx == null || idx.intValue() <= 0) {
                    continue;
                }
                PlayerRecord.Hero hw = findHeroByIndex(rec, idx.intValue());
                if (hw == null) {
                    continue;
                }
                CultivateTables.CombatStats bare = build(tables, rec, hw, false);
                if (bare != null) {
                    totalBase += tables.fightPowerOf(bare);
                }
            }
        }
        int tiers = JIBAN_FP_RETURN_STANDARD <= 0 ? 0 : totalBase / JIBAN_FP_RETURN_STANDARD;
        return new int[]{
                tiers * JIBAN_FP_RETURN_ATK,
                tiers * JIBAN_FP_RETURN_DEF,
                tiers * JIBAN_FP_RETURN_HP
        };
    }

    private static PlayerRecord.Hero findHeroByIndex(PlayerRecord rec, int heroIndex) {
        if (rec == null || rec.heroes == null) {
            return null;
        }
        for (PlayerRecord.Hero h : rec.heroes) {
            if (h != null && h.heroIndex == heroIndex) {
                return h;
            }
        }
        return null;
    }

    public static float jinJieTotal(CultivateTables tables, PlayerRecord rec, PlayerRecord.Hero wj,
                                    CultivateTables.HeroCfg c, int growType) {
        return jinJieTotal(tables, rec, wj, c, growType, true, true);
    }

    public static float jinJieTotal(CultivateTables tables, PlayerRecord rec, PlayerRecord.Hero wj,
                                    CultivateTables.HeroCfg c, int growType,
                                    boolean includeTimeRock, boolean includeSoul) {
        if (c == null || c.jinJieType[0] == 0) {
            return 0f;
        }
        int stage = Math.max(0, wj.stage);
        float num = 0f;
        for (int i = 0; i < 4; i++) {
            CultivateTables.JinJieCfg cfg = tables.jinJie(c.jinJieType[i]);
            if (cfg == null) {
                continue;
            }
            num += cfg.getTotalJinJieGrow(stage, wj.stagePara(i + 1), growType);
        }
        if (includeTimeRock) {
            num += timeRockAdd(tables, wj, c, growType);
        }
        if (includeSoul && rec != null && rec.souls != null) {
            PlayerRecord.Soul soul = rec.souls.get(Integer.valueOf(wj.heroIndex));
            if (soul != null && soul.composed) {
                num += tables.equipSoulAdd(wj.heroIndex, growType, soul.stage, soul.exp);
            }
        }
        return num;
    }

    private static float timeRockAdd(CultivateTables tables, PlayerRecord.Hero wj,
                                     CultivateTables.HeroCfg c, int growType) {
        if (wj.timeStones == null) {
            return 0f;
        }
        wj.ensureTimeStones();
        float num = 0f;
        for (int i = 0; i < 7; i++) {
            String ori = wj.timeStones.get(i);
            if (ori == null || ori.isEmpty()) {
                continue;
            }
            int attrSlot = i < wj.timeStoneFx.size() ? wj.timeStoneFx.get(i).intValue() : 0;
            int jjType = 0;
            if (attrSlot == 1) {
                jjType = c.jinJieType[0];
            } else if (attrSlot == 2) {
                jjType = c.jinJieType[1];
            } else if (attrSlot == 3) {
                jjType = c.jinJieType[2];
            } else if (attrSlot == 4) {
                jjType = c.jinJieType[3];
            }
            if (jjType <= 0) {
                continue;
            }
            CultivateTables.JinJieCfg cfg = tables.jinJie(jjType);
            if (cfg == null || cfg.primaryGrowType() != growType) {
                continue;
            }
            Float add = tables.goodsTimeAttrAdd(ori, jjType);
            if (add != null) {
                num += add.floatValue();
            }
        }
        return num;
    }

    /** type: 0 attack / 1 defence / 2 maxHP — 对齐 CalculateBuddiesGrowValue（/10000）。 */
    private static float buddiesSlotGrow(PlayerRecord rec, int heroIndex, int addType) {
        if (rec == null || rec.jiban == null) {
            return 0f;
        }
        rec.jiban.ensure();
        List<Integer> buddies = padBuddies(rec);
        if (buddies.size() != MAX_BUDDIES || rec.jiban.slots == null || rec.jiban.slots.size() < MAX_BUDDIES) {
            return 0f;
        }
        for (int i = 0; i < MAX_BUDDIES; i++) {
            if (buddies.get(i).intValue() != heroIndex) {
                continue;
            }
            PlayerRecord.JiBanSlot slot = rec.jiban.slots.get(i);
            if (slot == null || slot.tianFu == null || slot.tianFu.size() != BUDDIES_HOLE_SIZE) {
                return 0f;
            }
            // 对齐客户端 CalculateJiBanSlotGrowValue：锁孔只挡刷新，仍计入乘区
            float num = 0f;
            PlayerRecord.TianFu main = slot.tianFu.get(0);
            if (main != null && main.roleId == heroIndex) {
                num += attrOf(main, addType);
            }
            for (int k = 1; k < BUDDIES_HOLE_SIZE; k++) {
                PlayerRecord.TianFu tf = slot.tianFu.get(k);
                if (tf != null) {
                    num += attrOf(tf, addType);
                }
            }
            return num / 10000f;
        }
        return 0f;
    }

    private static float attrOf(PlayerRecord.TianFu tf, int addType) {
        if (addType == 0) {
            return tf.attack;
        }
        if (addType == 1) {
            return tf.defend;
        }
        return tf.hp;
    }

    private static List<Integer> padBuddies(PlayerRecord rec) {
        List<Integer> out = new ArrayList<>(MAX_BUDDIES);
        if (rec != null && rec.jiban != null && rec.jiban.buddies != null) {
            for (Integer v : rec.jiban.buddies) {
                out.add(v == null ? Integer.valueOf(0) : v);
            }
        }
        while (out.size() < MAX_BUDDIES) {
            out.add(Integer.valueOf(0));
        }
        if (out.size() > MAX_BUDDIES) {
            return new ArrayList<>(out.subList(0, MAX_BUDDIES));
        }
        return out;
    }
}
