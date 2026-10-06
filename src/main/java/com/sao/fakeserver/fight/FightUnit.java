package com.sao.fakeserver.fight;

import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.FightConfigTables;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一场战斗中的单位运行时状态（HP / 攻防 Buff / 吸收盾）。
 */
public final class FightUnit {
    public int playerGuid;
    public int playerServerId;
    public int wjId;
    public int subId;
    public int monsterIndex;
    public String monsterOri = "";

    public float basePhyAtk;
    public float baseMagAtk;
    public float basePdef;
    public float baseMdef;
    public int baseMaxHp;
    public int atkType;
    public float baseCritRatio = 0.05f;
    public float baseCritDamage = 0.5f;
    public float chunCuiDamageRatio;
    public float baoTouRatio;
    public float magDamageReduceFromEquip;
    public float geDangRatio;
    /** 进阶/面板增伤固定值（DamagePlusValue） */
    public float baseDamagePlusValue;
    /** 面板 DamagePlusRate → GetDamagePlusRatio */
    public float baseDamagePlusRatio;
    /** 面板 DamageRatio，默认 1 */
    public float baseDamageRatio = 1f;
    /** IgnorDefence */
    public float baseDefenceReduce;
    /** 进阶物/法减伤 */
    public float basePhyReduceRatio;
    public float baseMagReduceRatio;
    public float baseHealAddRatio;
    /** 攻方能量转换穿透（减目标魔法减伤） */
    public float nengLiangPenetrate;
    /**
     * GlobalSetup 韧性折算（开战灌入）；抗性 = {@link #buffResistValue} × ratio / 10000，
     * 对齐 Unit.GetEquipEffectResist。
     */
    public int resistBaoJiRatio;
    public int resistZhiShangRatio;
    /** Buff 69/70 叠加后的 Resist（未折算）。 */
    public float buffResistValue;

    public int curHp;
    public int maxHp;

    /** Buff_Enum → 叠加后的数值（绝对值或比率，按类型解释）。 */
    public final Map<Integer, BuffStack> buffs = new HashMap<>();
    /** 吸收盾元素：type 1物/2魔/3全；index 为类型内 0 基下标。 */
    public final List<AbsorbElm> absorbs = new ArrayList<>();

    public float buffPhyAtkFlat;
    public float buffMagAtkFlat;
    /** Buff6 累加平值（phase2 每次 +=）；End 清零。 */
    public float buffPhyFromAttack;
    public float buffMagFromAttack;
    /** Buff7 累加：每次 += 面板攻×比率（对齐客户端写入 FightAttr 平值）。 */
    public float buffPhyFromAtkRatio;
    public float buffMagFromAtkRatio;
    public float buffPdefFlat;
    public float buffMdefFlat;
    /** Buff9：叠加入 BuffsDamageRatio（1+Σ），再乘面板 DamageRatio。 */
    public float buffDamageRatioMul;
    public float buffCritRatio;
    public float buffDefenceReduce;
    public float buffXiShouLv = 1f;
    public float buffPhyReduceRatio;
    public float buffMagReduceRatio;
    /** 对齐 BuffsPhysicsAttackAbsorbRatio：1+Σbuff。 */
    public float buffPhyAbsorbRatio = 1f;
    public float buffMagAbsorbRatio = 1f;
    public float buffHealAddRatio;
    public float buffHealAddValue;
    public float buffHealedValue;
    public float maxHpFlatBonus;
    /** Buff5：Enter 时写入的绝对 max 增量（非比率）。 */
    public float maxHpRatioBonus;
    /**
     * Buff63 phase2：临时覆盖 DamageRatio（对齐客户端绝对赋值）；≤0 表示未覆盖。
     */
    public float tempDamageRatioOverride;
    /** Buff57：爆头窗口临时覆盖 XiShouLv；&lt;0 表示未覆盖。 */
    public float tempXiShouOverride = -1f;
    /** Buff60：致伤窗口临时覆盖纯伤倍率；&lt;0 表示未覆盖。 */
    public float tempChunCuiOverride = -1f;
    /** Buff43/44 不死保底：多实例按 buffId 登记，生效值取 max（对齐 BuSi / NoDieHpRatio Exit）。 */
    public final Map<Integer, Integer> noDieValueByBuffId = new HashMap<>();
    public final Map<Integer, Float> noDieRatioByBuffId = new HashMap<>();
    public int noDieHpValue;
    public float noDieHpRatio;
    /** Buff65 闪避率加成（客户端本地判定；服端存档供对拍）。 */
    public float buffDodgeRate;
    /** 本段 Hurt 被护盾吸收量（供 Buff20/21 phase3 反弹，对齐客户端 num2）。 */
    public float lastAbsorbedPhy;
    public float lastAbsorbedMag;
    public float lastAbsorbedAll;
    /** Buff20/21 反弹比率（Enter 时从表写入）。 */
    public float absorbReboundRatioPhy;
    public float absorbReboundRatioMag;
    /** Buff49 技能替换：覆盖普攻/A/B 技能 ID（0=未替换）。 */
    public int skillOverrideNormal;
    public int skillOverrideA;
    public int skillOverrideB;

    public CultivateTables.CombatStats stats;

    public static FightUnit fromStats(int playerGuid, CultivateTables.CombatStats st) {
        FightUnit u = new FightUnit();
        u.playerGuid = playerGuid;
        u.wjId = st.heroIndex;
        u.stats = st;
        u.atkType = st.atkType;
        u.basePhyAtk = st.phyAtk;
        u.baseMagAtk = st.magAtk;
        u.basePdef = st.pdef;
        u.baseMdef = st.mdef;
        u.baseMaxHp = st.maxHp;
        u.baseCritRatio = st.critRatio;
        u.baseCritDamage = st.critDamage;
        u.chunCuiDamageRatio = st.chunCuiDamageRatio;
        u.baoTouRatio = st.baoTouRatio;
        u.magDamageReduceFromEquip = st.magDamageReduceRatio;
        u.geDangRatio = st.geDangRatio;
        u.baseDamagePlusValue = st.damagePlusValue;
        u.baseDamagePlusRatio = st.damagePlusRate;
        u.baseDamageRatio = st.damageRatio <= 0f ? 1f : st.damageRatio;
        u.baseDefenceReduce = st.defenceReduce;
        u.basePhyReduceRatio = st.phyDamageReduceRatio;
        u.baseMagReduceRatio = st.phyDamageReduceRatio;
        u.baseHealAddRatio = st.healAddRatio;
        u.maxHp = st.maxHp;
        u.curHp = st.maxHp;
        return u;
    }

    /** 对齐 MonsterAttribute：表值直接灌面板，无等级重算；Crit 保持 0（FightAttr 初值）。 */
    public static FightUnit fromMonster(int playerGuid, FightConfigTables.MonsterCfg mc) {
        FightUnit u = new FightUnit();
        u.playerGuid = playerGuid;
        u.wjId = 0;
        u.monsterOri = mc.oriName != null ? mc.oriName : "";
        u.atkType = 0;
        u.basePhyAtk = mc.phyAtk;
        u.baseMagAtk = mc.magAtk;
        u.basePdef = mc.pdef;
        u.baseMdef = mc.mdef;
        u.baseMaxHp = mc.maxHp;
        // 禁止沿用武将字段默认 5%/0.5 — 怪表不写 Crit，FightAttr 初值=0
        u.baseCritRatio = 0f;
        u.baseCritDamage = 0f;
        u.baseDamagePlusValue = mc.damagePlusValue;
        u.baseDamagePlusRatio = mc.damagePlusRate;
        u.basePhyReduceRatio = mc.phyDamageReduceRatio;
        u.baseMagReduceRatio = mc.magDamageReduceRatio;
        u.baseDamageRatio = 1f;
        CultivateTables.CombatStats st = new CultivateTables.CombatStats();
        st.heroIndex = 0;
        st.atkType = 0;
        st.phyAtk = mc.phyAtk;
        st.magAtk = mc.magAtk;
        st.pdef = mc.pdef;
        st.mdef = mc.mdef;
        st.maxHp = mc.maxHp;
        st.critRatio = 0f;
        st.critDamage = 0f;
        st.damagePlusValue = mc.damagePlusValue;
        st.damagePlusRate = mc.damagePlusRate;
        st.phyDamageReduceRatio = mc.phyDamageReduceRatio;
        st.skillNormal = mc.skillLianXu;
        st.skillLvNormal = mc.skillLianXuLv;
        st.skillA = mc.skillSpell1;
        st.skillLvA = mc.skillSpell1Lv;
        st.skillB = mc.skillSpell2;
        st.skillLvB = mc.skillSpell2Lv;
        st.skillMingJiang = mc.skillMingJiang;
        st.skillLvMingJiang = mc.skillMingJiangLv;
        st.skillBeiDong1 = mc.skillBeiDong1;
        st.skillBeiDong2 = mc.skillBeiDong2;
        st.skillLvPassive = mc.skillBeiDongLv;
        u.stats = st;
        u.maxHp = mc.maxHp;
        u.curHp = mc.maxHp;
        return u;
    }

    public String identityKey() {
        if (monsterIndex > 0) {
            return "m:" + monsterIndex;
        }
        if (monsterOri != null && !monsterOri.isEmpty() && wjId == 0) {
            return "mo:" + monsterOri + ":" + subId;
        }
        // 同 guid+WJID 多重召唤靠 subId 区分（AddSummon 必带 subID）
        if (subId != 0) {
            return playerGuid + ":" + wjId + ":s" + subId;
        }
        return playerGuid + ":" + wjId;
    }

    /** 对齐 UnitType.UT_Monster（召唤怪 / SpawneredMonster）。 */
    public boolean isMonster() {
        return monsterIndex > 0 || (monsterOri != null && !monsterOri.isEmpty() && wjId == 0);
    }

    /**
     * 对齐 Unit.GetPhyAkt = 面板 + BuffsPhysicsAttatck（纯加）。
     * Buff7 在客户端写入的是 {@code 面板物攻×比率} 的平值，不是总攻乘区。
     */
    public float phyAtk() {
        return basePhyAtk + buffPhyFromAttack + buffPhyFromAtkRatio;
    }

    public float magAtk() {
        return baseMagAtk + buffMagFromAttack + buffMagFromAtkRatio;
    }

    /** 对齐 Unit.GetAttackValue：武将按 atkType 取物/法；怪取物+法。 */
    public float attackValue() {
        if (monsterIndex > 0 || (monsterOri != null && !monsterOri.isEmpty() && wjId == 0)) {
            return phyAtk() + magAtk();
        }
        if (atkType == 2) {
            return magAtk();
        }
        if (atkType == 1) {
            return phyAtk();
        }
        return Math.max(phyAtk(), magAtk());
    }

    public float pdef() {
        return basePdef + buffPdefFlat;
    }

    public float mdef() {
        return baseMdef + buffMdefFlat;
    }

    public float critRatio() {
        return baseCritRatio + buffCritRatio;
    }

    public float critDamage() {
        return baseCritDamage;
    }

    /** 对齐 Unit.GetEquipEffectResist(JU_LI)：Resist × ResistBaoJiRatio / 10000。 */
    public float juLiResist() {
        return buffResistValue * resistBaoJiRatio / 10000f;
    }

    /** 对齐 Unit.GetEquipEffectResist(ZHI_SHANG)：Resist × ResistZhiShangRatio / 10000。 */
    public float zhiShangResist() {
        return buffResistValue * resistZhiShangRatio / 10000f;
    }

    public float damagePlusRatio() {
        return baseDamagePlusRatio;
    }

    public float damagePlusValue() {
        return baseDamagePlusValue;
    }

    /**
     * 对齐 Unit.GetDamageRatio = 面板DamageRatio × BuffsDamageRatio(1+ΣBuff9)。
     * Buff63 只覆盖面板字段，仍乘 BuffsDamageRatio。
     */
    public float damageRatio() {
        float buffs = 1f + buffDamageRatioMul;
        if (tempDamageRatioOverride > 0f) {
            return tempDamageRatioOverride * buffs;
        }
        float base = baseDamageRatio <= 0f ? 1f : baseDamageRatio;
        return base * buffs;
    }

    public float defenceReduce() {
        return baseDefenceReduce + buffDefenceReduce;
    }

    public float xiShouLv() {
        if (tempXiShouOverride >= 0f) {
            return tempXiShouOverride;
        }
        return buffXiShouLv <= 0f ? 1f : buffXiShouLv;
    }

    /**
     * 对齐 Unit.GetChunCuiDamageRatio：面板默认 0，仅 Buff60/吸血内嵌窗口临时覆盖。
     * {@link #chunCuiDamageRatio} 为装备致伤比，供覆盖写入，不常驻乘伤。
     */
    public float chunCuiRatio() {
        if (tempChunCuiOverride >= 0f) {
            return tempChunCuiOverride;
        }
        return 0f;
    }

    /** 开战技能等级；含 Buff49 替换后的新技能 ID。 */
    public int skillLevelOf(int skillId) {
        if (stats == null) {
            return 1;
        }
        if (skillOverrideA > 0 && skillId == skillOverrideA) {
            return stats.skillLvA;
        }
        if (skillOverrideB > 0 && skillId == skillOverrideB) {
            return stats.skillLvB;
        }
        if (skillOverrideNormal > 0 && skillId == skillOverrideNormal) {
            return stats.skillLvNormal > 0 ? stats.skillLvNormal : 1;
        }
        return stats.skillLevelOf(skillId);
    }

    public void applySkillChange(String raw, boolean end) {
        if (end) {
            skillOverrideNormal = 0;
            skillOverrideA = 0;
            skillOverrideB = 0;
            return;
        }
        if (raw == null || raw.isEmpty() || "0".equals(raw)) {
            return;
        }
        String[] p = raw.split("_");
        if (p.length >= 1) {
            skillOverrideNormal = parseIntSafe(p[0]);
        }
        if (p.length >= 2) {
            skillOverrideA = parseIntSafe(p[1]);
        }
        if (p.length >= 3) {
            skillOverrideB = parseIntSafe(p[2]);
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 对齐 Unit.ReduceHp 不死保底：curHp 不低于 max(noDieHpValue, maxHp*noDieHpRatio)。
     * @return 实际扣血量
     */
    public int applyDamage(int raw) {
        if (raw <= 0 || curHp <= 0) {
            return 0;
        }
        float floor = noDieHpValue;
        if (noDieHpRatio > 0f) {
            float byRatio = maxHp * noDieHpRatio;
            if (byRatio > floor) {
                floor = byRatio;
            }
        }
        int dmg = raw;
        if (curHp <= floor) {
            dmg = 0;
        } else if (curHp - dmg < floor) {
            dmg = curHp - (int) floor;
        }
        if (dmg < 0) {
            dmg = 0;
        }
        if (curHp - dmg < 0) {
            dmg = curHp;
        }
        curHp -= dmg;
        return dmg;
    }

    /** @return 实际回血量 */
    public int applyHeal(int raw) {
        if (raw <= 0 || maxHp <= 0) {
            return 0;
        }
        int before = curHp;
        curHp = Math.min(maxHp, curHp + raw);
        return curHp - before;
    }

    public float phyReduceRatio() {
        return basePhyReduceRatio + buffPhyReduceRatio;
    }

    public float magReduceRatio() {
        return baseMagReduceRatio + buffMagReduceRatio + magDamageReduceFromEquip;
    }

    public float phyAbsorbRatio() {
        return buffPhyAbsorbRatio;
    }

    public float magAbsorbRatio() {
        return buffMagAbsorbRatio;
    }

    public float healAddRatio() {
        return baseHealAddRatio + buffHealAddRatio;
    }

    public float healAddValue() {
        return buffHealAddValue;
    }

    public float healedValue() {
        return buffHealedValue;
    }

    public float physicsAbsorbSum() {
        float s = 0f;
        for (AbsorbElm e : absorbs) {
            if (e.type == 1) {
                s += e.curHp;
            }
        }
        return s;
    }

    public float magicAbsorbSum() {
        float s = 0f;
        for (AbsorbElm e : absorbs) {
            if (e.type == 2) {
                s += e.curHp;
            }
        }
        return s;
    }

    public float allAbsorbSum() {
        float s = 0f;
        for (AbsorbElm e : absorbs) {
            if (e.type == 3) {
                s += e.curHp;
            }
        }
        return s;
    }

    /**
     * 扣吸收盾：只改 curHp，<b>不</b>移除、不重编 index。
     * 对齐客户端：Hurt 后 S2C {@code SetXxxAbsorbElm(index,cur)}；耗尽由 Buff Update→Exit→Remove，再 BuffEnd 摘槽。
     */
    public void consumeAbsorb(int type, float amount) {
        float left = amount;
        for (AbsorbElm e : absorbs) {
            if (e.type != type || left <= 0f) {
                continue;
            }
            if (e.curHp <= left) {
                left -= e.curHp;
                e.curHp = 0f;
            } else {
                e.curHp -= left;
                left = 0f;
            }
        }
    }

    /** 各 type 内 index = 客户端 mXxxAbsorbElms 下标（从 0）。 */
    public void reindexAbsorbs() {
        int phy = 0;
        int mag = 0;
        int all = 0;
        for (AbsorbElm e : absorbs) {
            if (e.type == 1) {
                e.index = phy++;
            } else if (e.type == 2) {
                e.index = mag++;
            } else if (e.type == 3) {
                e.index = all++;
            }
        }
    }

    /**
     * BuffEnd / Exit：摘掉对应吸盾槽（对齐 Remove*AbsorbElm(this)），再重编剩余下标。
     * 优先 buffId 匹配；否则先摘 curHp≤0；再 FIFO 同 type。
     */
    public boolean removeOneAbsorb(int type, int buffId) {
        int idx = -1;
        if (buffId > 0) {
            for (int i = 0; i < absorbs.size(); i++) {
                AbsorbElm e = absorbs.get(i);
                if (e.type == type && e.buffId == buffId) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0) {
            for (int i = 0; i < absorbs.size(); i++) {
                AbsorbElm e = absorbs.get(i);
                if (e.type == type && e.curHp <= 0f) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0) {
            for (int i = 0; i < absorbs.size(); i++) {
                AbsorbElm e = absorbs.get(i);
                if (e.type == type) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0) {
            return false;
        }
        absorbs.remove(idx);
        reindexAbsorbs();
        return true;
    }

    /**
     * Buff6：phase1 仅登记；phase2 累加平攻（对齐 EquipAllAttack）；end 清零。
     */
    public void applyAttackFlatTick(float param, int stacks, int phase, boolean end) {
        if (end) {
            buffPhyFromAttack = 0f;
            buffMagFromAttack = 0f;
            buffs.remove(Integer.valueOf(BuffIds.ATTACK));
            return;
        }
        if (phase <= 1) {
            BuffStack st = buffs.get(Integer.valueOf(BuffIds.ATTACK));
            if (st == null) {
                st = new BuffStack();
                buffs.put(Integer.valueOf(BuffIds.ATTACK), st);
            }
            st.value = param;
            st.stacks = Math.max(1, stacks);
            return;
        }
        float add = param * Math.max(1, stacks);
        buffPhyFromAttack += add;
        buffMagFromAttack += add;
        BuffStack st = buffs.get(Integer.valueOf(BuffIds.ATTACK));
        if (st == null) {
            st = new BuffStack();
            buffs.put(Integer.valueOf(BuffIds.ATTACK), st);
        }
        st.value = param;
        st.stacks = Math.max(1, stacks);
    }

    /**
     * Buff7：phase2 每次 += 面板攻×比率（对齐 EquipAttackRatio 写 FightAttr 平值）；end 清零。
     */
    public void applyAttackRatioTick(float param, int stacks, int phase, boolean end) {
        if (end) {
            buffPhyFromAtkRatio = 0f;
            buffMagFromAtkRatio = 0f;
            buffs.remove(Integer.valueOf(BuffIds.ATTACK_RATIO));
            return;
        }
        if (phase <= 1) {
            BuffStack st = buffs.get(Integer.valueOf(BuffIds.ATTACK_RATIO));
            if (st == null) {
                st = new BuffStack();
                buffs.put(Integer.valueOf(BuffIds.ATTACK_RATIO), st);
            }
            st.value = param;
            st.stacks = Math.max(1, stacks);
            return;
        }
        float mul = Math.max(1, stacks);
        buffPhyFromAtkRatio += basePhyAtk * param * mul;
        buffMagFromAtkRatio += baseMagAtk * param * mul;
        BuffStack st = buffs.get(Integer.valueOf(BuffIds.ATTACK_RATIO));
        if (st == null) {
            st = new BuffStack();
            buffs.put(Integer.valueOf(BuffIds.ATTACK_RATIO), st);
        }
        st.value = param;
        st.stacks = Math.max(1, stacks);
    }

    /** Buff65：phase1 登记；phase2 累加闪避；end 清零。 */
    public void applyDodgeTick(float param, int stacks, int phase, boolean end) {
        if (end) {
            buffDodgeRate = 0f;
            buffs.remove(Integer.valueOf(BuffIds.DODGE_RATE));
            return;
        }
        if (phase <= 1) {
            BuffStack st = buffs.get(Integer.valueOf(BuffIds.DODGE_RATE));
            if (st == null) {
                st = new BuffStack();
                buffs.put(Integer.valueOf(BuffIds.DODGE_RATE), st);
            }
            st.value = param;
            st.stacks = Math.max(1, stacks);
            return;
        }
        buffDodgeRate += param * Math.max(1, stacks);
        BuffStack st = buffs.get(Integer.valueOf(BuffIds.DODGE_RATE));
        if (st == null) {
            st = new BuffStack();
            buffs.put(Integer.valueOf(BuffIds.DODGE_RATE), st);
        }
        st.value = param;
        st.stacks = Math.max(1, stacks);
    }

    /**
     * max = 开战 base + Buff4 flat×层 + Buff5 进入时绝对值。
     * <b>不</b>在此钳 curHp——对齐 APK：改 max 后由 {@link #syncHpAfterMaxChange} 做一次 AddHp/ReduceHp。
     * @return 本次 maxHp 变化量
     */
    public int recomputeMaxHp() {
        int neu = Math.max(1, Math.round(baseMaxHp + maxHpFlatBonus + maxHpRatioBonus));
        int delta = neu - maxHp;
        maxHp = neu;
        return delta;
    }

    /** Buff4/5：对齐 AddHp / ReduceHp 绝对增减当前血（含缩 max 时保活 1）。 */
    public void syncHpAfterMaxChange(int maxDelta) {
        if (maxDelta > 0) {
            applyHeal(maxDelta);
        } else if (maxDelta < 0) {
            int abs = -maxDelta;
            if (abs >= curHp) {
                abs = Math.max(0, curHp - 1);
            }
            applyDamage(abs);
        }
        if (curHp > maxHp) {
            curHp = maxHp;
        }
    }

    public void applyBuff(int buffEnum, int buffId, int stacks, boolean end, float paramValue) {
        if (end) {
            buffs.remove(Integer.valueOf(buffEnum));
            if (buffEnum == BuffIds.DAMAGE_RATIO_BY_SKILL) {
                tempDamageRatioOverride = 0f;
            }
            if (buffEnum == BuffIds.BAO_TOU) {
                tempXiShouOverride = -1f;
            }
            if (buffEnum == BuffIds.ZHI_SHANG) {
                tempChunCuiOverride = -1f;
            }
            if (buffEnum == BuffIds.NO_DIE_HP) {
                noDieValueByBuffId.remove(Integer.valueOf(buffId));
                rebuildNoDieCaps();
            }
            if (buffEnum == BuffIds.NO_DIE_HP_RATIO) {
                noDieRatioByBuffId.remove(Integer.valueOf(buffId));
                rebuildNoDieCaps();
            }
            if (buffEnum == BuffIds.EQUIP_XI_SHOU) {
                buffXiShouLv = 1f;
            }
            if (buffEnum == BuffIds.SKILL_CHANGE) {
                skillOverrideNormal = 0;
                skillOverrideA = 0;
                skillOverrideB = 0;
            }
            if (buffEnum == BuffIds.PHYSICS_ABSORB_AND_REBOUND) {
                absorbReboundRatioPhy = 0f;
            }
            if (buffEnum == BuffIds.MAGIC_ABSORB_AND_REBOUND) {
                absorbReboundRatioMag = 0f;
            }
            if (buffEnum == BuffIds.ATTACK) {
                buffPhyFromAttack = 0f;
                buffMagFromAttack = 0f;
            }
            if (buffEnum == BuffIds.ATTACK_RATIO) {
                buffPhyFromAtkRatio = 0f;
                buffMagFromAtkRatio = 0f;
            }
            if (buffEnum == BuffIds.DODGE_RATE) {
                buffDodgeRate = 0f;
            }
        } else {
            BuffStack st = buffs.get(Integer.valueOf(buffEnum));
            if (st == null) {
                st = new BuffStack();
                buffs.put(Integer.valueOf(buffEnum), st);
            }
            st.buffId = buffId;
            st.stacks = Math.max(1, stacks);
            // 空参时由调用方已用表填好 param；禁止再发明默认值
            st.value = paramValue;
            if (buffEnum == BuffIds.NO_DIE_HP) {
                noDieValueByBuffId.put(Integer.valueOf(buffId), Integer.valueOf((int) paramValue));
                rebuildNoDieCaps();
            }
            if (buffEnum == BuffIds.NO_DIE_HP_RATIO) {
                noDieRatioByBuffId.put(Integer.valueOf(buffId), Float.valueOf(paramValue));
                rebuildNoDieCaps();
            }
            if (buffEnum == BuffIds.BAO_TOU && paramValue == 0f) {
                // phase1：爆头窗口 XiShouLv=0（值由 handleBuff 设 temp）
            }
        }
        rebuildBuffModifiers();
    }

    /** 对齐 BuSi / NoDieHpRatio：剩余实例取 max；全无则 0。 */
    private void rebuildNoDieCaps() {
        int vmax = 0;
        for (Integer v : noDieValueByBuffId.values()) {
            if (v != null && v.intValue() > vmax) {
                vmax = v.intValue();
            }
        }
        noDieHpValue = vmax;
        float rmax = 0f;
        for (Float r : noDieRatioByBuffId.values()) {
            if (r != null && r.floatValue() > rmax) {
                rmax = r.floatValue();
            }
        }
        noDieHpRatio = rmax;
    }

    public void addAbsorb(int type, float amount, int buffId) {
        AbsorbElm e = new AbsorbElm();
        e.type = type;
        e.buffId = buffId;
        int idx = 0;
        for (AbsorbElm x : absorbs) {
            if (x.type == type) {
                idx++;
            }
        }
        e.index = idx;
        e.curHp = amount;
        absorbs.add(e);
    }

    /** @deprecated 无 buffId；新路径用 {@link #addAbsorb(int, float, int)} */
    public void addAbsorb(int type, float amount) {
        addAbsorb(type, amount, 0);
    }

    private void rebuildBuffModifiers() {
        buffPdefFlat = 0f;
        buffMdefFlat = 0f;
        buffDamageRatioMul = 0f;
        buffCritRatio = 0f;
        buffDefenceReduce = 0f;
        buffXiShouLv = 1f;
        buffPhyReduceRatio = 0f;
        buffMagReduceRatio = 0f;
        buffPhyAbsorbRatio = 1f;
        buffMagAbsorbRatio = 1f;
        buffHealAddRatio = 0f;
        buffHealAddValue = 0f;
        buffHealedValue = 0f;
        maxHpFlatBonus = 0f;
        maxHpRatioBonus = 0f;
        buffResistValue = 0f;
        // 攻/闪避由 applyAttack*Tick / applyDodgeTick 累加维护，此处不重置
        // noDie 由 applyBuff 直接维护（取 max），此处不重置以免 rebuild 冲掉
        for (Map.Entry<Integer, BuffStack> en : buffs.entrySet()) {
            int be = en.getKey().intValue();
            BuffStack st = en.getValue();
            float v = st.value;
            float mul = st.stacks;
            switch (be) {
                case BuffIds.ATTACK:
                case BuffIds.ATTACK_RATIO:
                case BuffIds.DODGE_RATE:
                    // 累加字段已在 tick 方法维护
                    break;
                case BuffIds.PHYSICS_DEFENCE:
                    buffPdefFlat += v * mul;
                    break;
                case BuffIds.MAGIC_DEFENCE:
                    buffMdefFlat += v * mul;
                    break;
                case BuffIds.DAMAGE_RATIO:
                    buffDamageRatioMul += v * mul;
                    break;
                case BuffIds.CRIT_RATIO:
                    buffCritRatio += v * mul;
                    break;
                case BuffIds.MAX_HP:
                    maxHpFlatBonus += v * mul;
                    break;
                case BuffIds.MAX_HP_RATIO:
                    // v 已是 Enter 时算好的绝对增量（含层数），勿再 × stacks
                    maxHpRatioBonus += v;
                    break;
                case BuffIds.HEAL_ADD_RATIO:
                    buffHealAddRatio += v * mul;
                    break;
                case BuffIds.HEAL_ADD_VALUE:
                    buffHealAddValue += v * mul;
                    break;
                case BuffIds.RECEIVE_HEAL:
                    buffHealedValue += v * mul;
                    break;
                case BuffIds.PHYSICS_ABSORB_RATIO:
                    buffPhyAbsorbRatio += v * mul;
                    break;
                case BuffIds.MAGIC_ABSORB_RATIO:
                    buffMagAbsorbRatio += v * mul;
                    break;
                case BuffIds.DEFENCE_REDUCE:
                    buffDefenceReduce += v * mul;
                    break;
                case BuffIds.EQUIP_XI_SHOU:
                    if (v > 0f) {
                        buffXiShouLv = v;
                    }
                    break;
                case BuffIds.MAGIC_DAMAGE_REDUCE:
                    buffMagReduceRatio += v * mul;
                    break;
                case BuffIds.DAMAGE_RATIO_BY_SKILL:
                    break;
                case BuffIds.ALL_RESIST:
                case BuffIds.EQUIP_RESIST:
                    buffResistValue += v * mul;
                    break;
                default:
                    break;
            }
        }
        recomputeMaxHp();
    }

    public static final class BuffStack {
        public int buffId;
        public int stacks = 1;
        public float value;
    }

    public static final class AbsorbElm {
        public int type;
        public int index;
        public float curHp;
        /** 来源 Buff 表 id（End 时按 id 摘槽，对齐客户端按 BuffElement 移除） */
        public int buffId;
    }
}
