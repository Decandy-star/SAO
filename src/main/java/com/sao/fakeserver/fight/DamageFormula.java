package com.sao.fakeserver.fight;

import com.sao.fakeserver.table.CultivateTables;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 移植客户端 {@code MobileGameDemo.Damage#damage/cure}（真服一致目标，见 docs/FIGHT_TRUE_SERVER.md）。
 */
public final class DamageFormula {
    private DamageFormula() {
    }

    public static final class Result {
        public float damage;
        public boolean crited;
        public float phyAbsorbed;
        public float magAbsorbed;
        public float allAbsorbed;
        public Trace trace = new Trace();
    }

    /** 单次伤害全量中间量，供 INFO 对照真服。 */
    public static final class Trace {
        public int skillId;
        public int skillLv;
        public int damageType;
        public float proportion;
        public float shuaiJian;
        public float skillDamage;
        public int atkType;
        public float atkPhy;
        public float atkMag;
        public float atkPlusValue;
        public float atkPlusRatio;
        public float atkDamageRatio;
        public float atkCritRatio;
        public float atkCritDamage;
        public float atkDefenceReduce;
        public float atkChunCui;
        public float atkBaoTou;
        public float atkNengLiangPen;
        public float tgtPdef;
        public float tgtMdef;
        public float tgtXiShouLv;
        public float tgtPhyReduce;
        public float tgtMagReduce;
        public float tgtPhyAbsorbRatio;
        public float tgtMagAbsorbRatio;
        public float tgtJuLiResist;
        public float tgtZhiShangResist;
        public float phyDefEff;
        public float magDefEff;
        public boolean baoTouTriggered;
        public float phyRaw;
        public float magRaw;
        public float phyAfterDef;
        public float magAfterDef;
        public float phyAfterMul;
        public float magAfterMul;
        public float critRoll;
        public float critMul = 1f;
        public float variance;
        public float phyAfterCritVar;
        public float magAfterCritVar;
        public float chunCuiApplied;
        public float phyBeforeAbsorb;
        public float magBeforeAbsorb;
        public float phyAbsorbed;
        public float magAbsorbed;
        public float allAbsorbed;
        public float totalBeforeProp;
        public float finalDamage;
        public boolean immune;
        public String immuneReason = "";

        public String summary() {
            return String.format(
                    "skill=%d lv=%d dmgType=%d prop=%.4f shuai=%.3f skDmg=%.1f atkType=%d "
                            + "atkPhy=%.1f atkMag=%.1f plusV=%.1f plusR=%.4f dmgR=%.3f critR=%.4f critD=%.3f ignDef=%.1f "
                            + "tgtPdef=%.1f tgtMdef=%.1f xiShou=%.3f phyRed=%.4f magRed=%.4f "
                            + "defEffP=%.1f defEffM=%.1f baoTou=%s rawP=%.1f rawM=%.1f afterDefP=%.1f afterDefM=%.1f "
                            + "afterMulP=%.1f afterMulM=%.1f critRoll=%.0f critMul=%.3f var=%.3f "
                            + "chun=%.4f beforeAbsP=%.1f beforeAbsM=%.1f absP=%.1f absM=%.1f absAll=%.1f "
                            + "total=%.1f final=%.1f immune=%s%s",
                    skillId, skillLv, damageType, proportion, shuaiJian, skillDamage, atkType,
                    atkPhy, atkMag, atkPlusValue, atkPlusRatio, atkDamageRatio, atkCritRatio, atkCritDamage, atkDefenceReduce,
                    tgtPdef, tgtMdef, tgtXiShouLv, tgtPhyReduce, tgtMagReduce,
                    phyDefEff, magDefEff, baoTouTriggered, phyRaw, magRaw, phyAfterDef, magAfterDef,
                    phyAfterMul, magAfterMul, critRoll, critMul, variance,
                    chunCuiApplied, phyBeforeAbsorb, magBeforeAbsorb, phyAbsorbed, magAbsorbed, allAbsorbed,
                    totalBeforeProp, finalDamage, immune, immune ? (":" + immuneReason) : "");
        }
    }

    public static Result damage(FightUnit atk, FightUnit target, CultivateTables.SkillProp skill,
                                float skillDamage, int damageType, float proportion) {
        return damage(atk, target, skill, skillDamage, damageType, proportion, true);
    }

    /**
     * @param consumeAbsorb false=仅按盾量扣减返回伤（对齐 IsServerCal 下 GetDamage 不改盾），
     *                      true=真正消耗盾（Hurt / 独占结算 Buff）。
     */
    public static Result damage(FightUnit atk, FightUnit target, CultivateTables.SkillProp skill,
                                float skillDamage, int damageType, float proportion,
                                boolean consumeAbsorb) {
        Result r = new Result();
        Trace t = r.trace;
        t.skillDamage = skillDamage;
        t.damageType = damageType;
        t.proportion = proportion;
        if (skill != null) {
            t.skillId = skill.id;
        }
        if (atk == null || target == null) {
            t.immune = true;
            t.immuneReason = "null-unit";
            return r;
        }
        t.atkType = atk.atkType;
        t.atkPhy = atk.phyAtk();
        t.atkMag = atk.magAtk();
        t.atkPlusValue = atk.damagePlusValue();
        t.atkPlusRatio = atk.damagePlusRatio();
        t.atkDamageRatio = atk.damageRatio();
        t.atkCritRatio = atk.critRatio();
        t.atkCritDamage = atk.critDamage();
        t.atkDefenceReduce = atk.defenceReduce();
        t.atkChunCui = atk.chunCuiRatio();
        t.atkBaoTou = atk.baoTouRatio;
        t.atkNengLiangPen = atk.nengLiangPenetrate;
        t.tgtPdef = target.pdef();
        t.tgtMdef = target.mdef();
        t.tgtXiShouLv = target.xiShouLv();
        t.tgtPhyReduce = target.phyReduceRatio();
        t.tgtMagReduce = target.magReduceRatio();
        t.tgtPhyAbsorbRatio = target.phyAbsorbRatio();
        t.tgtMagAbsorbRatio = target.magAbsorbRatio();
        t.tgtJuLiResist = target.juLiResist();
        t.tgtZhiShangResist = target.zhiShangResist();

        float shuai = skill == null ? 1f : skill.shuaiJian;
        if (shuai <= 0f) {
            shuai = 1f;
        }
        t.shuaiJian = shuai;

        if (atk.atkType == 1 && target.pdef() > 999990f) {
            t.immune = true;
            t.immuneReason = "phy-imm";
            return r;
        }
        if (atk.atkType == 2 && target.mdef() > 999990f) {
            t.immune = true;
            t.immuneReason = "mag-imm";
            return r;
        }
        if (atk.phyAtk() != 0f && atk.magAtk() == 0f && target.pdef() > 999990f) {
            t.immune = true;
            t.immuneReason = "phy-only-imm";
            return r;
        }
        if (atk.magAtk() != 0f && atk.phyAtk() == 0f && target.mdef() > 999990f) {
            t.immune = true;
            t.immuneReason = "mag-only-imm";
            return r;
        }

        float phyDefEff = Math.max((target.pdef() - atk.defenceReduce()) * target.xiShouLv(), 0f);
        float magDefEff = Math.max((target.mdef() - atk.defenceReduce()) * target.xiShouLv(), 0f);
        boolean normalSkill = isNormalSkill(atk, skill);
        t.phyDefEff = phyDefEff;
        t.magDefEff = magDefEff;

        float phyRaw = atk.phyAtk() * shuai + skillDamage;
        float magRaw = atk.magAtk() * shuai + skillDamage;
        t.phyRaw = phyRaw;
        t.magRaw = magRaw;
        float phy = Math.max(phyRaw - phyDefEff, 0f) + atk.damagePlusValue();
        float mag = Math.max(magRaw - magDefEff, 0f) + atk.damagePlusValue();
        t.phyAfterDef = phy;
        t.magAfterDef = mag;

        phy = phy * (1f + atk.damagePlusRatio()) * (1f - target.phyReduceRatio())
                * target.phyAbsorbRatio() * atk.damageRatio();

        float magReduce = target.magReduceRatio() - atk.nengLiangPenetrate;
        if (magReduce < 0f) {
            magReduce = 0f;
        }
        mag = mag * (1f + atk.damagePlusRatio()) * (1f - magReduce)
                * target.magAbsorbRatio() * atk.damageRatio();
        t.phyAfterMul = phy;
        t.magAfterMul = mag;

        float roll = 1f + ThreadLocalRandom.current().nextFloat() * 9999f;
        t.critRoll = roll;
        float critMul = 1f;
        if (damageType != 1 && atk.critRatio() * 10000f >= roll) {
            float cd = atk.critDamage() - target.juLiResist();
            if (cd < 0f) {
                cd = 0f;
            }
            critMul = 1f + cd;
            r.crited = true;
        }
        t.critMul = critMul;
        float variance = (90f + ThreadLocalRandom.current().nextFloat() * 20f) * 0.01f;
        t.variance = variance;
        phy *= variance * critMul;
        mag *= variance * critMul;
        t.phyAfterCritVar = phy;
        t.magAfterCritVar = mag;

        if (damageType != 1 && normalSkill) {
            float chun = atk.chunCuiRatio() - target.zhiShangResist();
            if (chun < 0f) {
                chun = 0f;
            }
            t.chunCuiApplied = chun;
            if (chun > 0f) {
                phy *= 1f + chun;
                mag *= 1f + chun;
            }
        }

        if (atk.atkType == 1) {
            phy = Math.max(phy, 1f);
            mag = 0f;
        } else if (atk.atkType == 2) {
            phy = 0f;
            mag = Math.max(mag, 1f);
        } else {
            if (atk.phyAtk() != 0f) {
                phy = Math.max(phy, 1f);
            }
            if (atk.magAtk() != 0f) {
                mag = Math.max(mag, 1f);
            }
        }
        t.phyBeforeAbsorb = phy;
        t.magBeforeAbsorb = mag;

        if (target.physicsAbsorbSum() > 0f && phy > 0f) {
            float shield = target.physicsAbsorbSum();
            if (phy >= shield) {
                phy -= shield;
                r.phyAbsorbed = shield;
                if (consumeAbsorb) {
                    target.consumeAbsorb(1, shield);
                }
            } else {
                r.phyAbsorbed = phy;
                if (consumeAbsorb) {
                    target.consumeAbsorb(1, phy);
                }
                phy = 0f;
            }
        }
        if (target.magicAbsorbSum() > 0f && mag > 0f) {
            float shield = target.magicAbsorbSum();
            if (mag >= shield) {
                mag -= shield;
                r.magAbsorbed = shield;
                if (consumeAbsorb) {
                    target.consumeAbsorb(2, shield);
                }
            } else {
                r.magAbsorbed = mag;
                if (consumeAbsorb) {
                    target.consumeAbsorb(2, mag);
                }
                mag = 0f;
            }
        }
        float total = phy + mag;
        if (target.allAbsorbSum() > 0f && total > 0f) {
            float shield = target.allAbsorbSum();
            if (total >= shield) {
                total -= shield;
                r.allAbsorbed = shield;
                if (consumeAbsorb) {
                    target.consumeAbsorb(3, shield);
                }
            } else {
                r.allAbsorbed = total;
                if (consumeAbsorb) {
                    target.consumeAbsorb(3, total);
                }
                total = 0f;
            }
        }
        t.phyAbsorbed = r.phyAbsorbed;
        t.magAbsorbed = r.magAbsorbed;
        t.allAbsorbed = r.allAbsorbed;
        if (consumeAbsorb) {
            target.lastAbsorbedPhy = r.phyAbsorbed;
            target.lastAbsorbedMag = r.magAbsorbed;
            target.lastAbsorbedAll = r.allAbsorbed;
        }
        t.totalBeforeProp = total;
        r.damage = Math.max(0f, total * Math.max(0f, proportion));
        t.finalDamage = r.damage;
        return r;
    }

    public static Result cure(FightUnit atk, FightUnit target, CultivateTables.SkillProp skill,
                              float skillAddHp, float proportion) {
        Result r = new Result();
        if (atk == null || target == null) {
            return r;
        }
        float shuai = skill == null ? 1f : skill.shuaiJian;
        if (shuai <= 0f) {
            shuai = 1f;
        }
        float phy = atk.phyAtk() * shuai;
        float mag = atk.magAtk() * shuai;
        if (atk.atkType == 1) {
            mag = 0f;
        } else if (atk.atkType == 2) {
            phy = 0f;
        }
        float roll = 1f + ThreadLocalRandom.current().nextFloat() * 9999f;
        float critMul = 1f;
        if (atk.critRatio() * 10000f >= roll) {
            critMul = 1.5f;
            r.crited = true;
        }
        float heal = (phy + mag + skillAddHp) * critMul * (1f + atk.healAddRatio())
                + atk.healAddValue() + target.healedValue();
        r.damage = Math.max(0f, heal * Math.max(0f, proportion));
        Trace t = r.trace;
        t.skillDamage = skillAddHp;
        t.proportion = proportion;
        t.shuaiJian = shuai;
        t.atkPhy = atk.phyAtk();
        t.atkMag = atk.magAtk();
        t.critMul = critMul;
        t.finalDamage = r.damage;
        if (skill != null) {
            t.skillId = skill.id;
        }
        return r;
    }

    /** 对齐 WuJiangInfo.GetSkillType → EUNIT_SKILL_NORMAL（含 Buff49 替换普攻）。 */
    public static boolean isNormalSkill(FightUnit atk, CultivateTables.SkillProp skill) {
        if (atk == null || skill == null) {
            return false;
        }
        if (atk.skillOverrideNormal > 0 && skill.id == atk.skillOverrideNormal) {
            return true;
        }
        return atk.stats != null && skill.id == atk.stats.skillNormal;
    }

    /**
     * 对齐 {@code Unit.GetSkillDamage}：
     * <ul>
     *   <li>怪：恒 {@code fSkillDamageBase}（不加 grow、不看等级）</li>
     *   <li>武将：{@code level==0 → 0}；否则 {@code base + grow*(level-1)}</li>
     * </ul>
     */
    public static float skillDamageValue(CultivateTables.SkillProp skill, FightUnit attacker, int level) {
        if (skill == null) {
            return 0f;
        }
        if (attacker != null && attacker.isMonster()) {
            return skill.dmgBase;
        }
        if (level == 0) {
            return 0f;
        }
        return skill.dmgBase + skill.dmgGrow * (level - 1);
    }

    /**
     * 段伤比例：优先 FX 表（modelPath + damageProportionID=mID）。
     * APK {@code SkillBuilder.DamageProportion} 缺省为 1f（FX XML 无属性时）；
     * <b>没有</b> NewSkillProperty.hits 均分逻辑——旧 1/hits 已删。
     */
    public static float proportionOf(CultivateTables.SkillProp skill, int damageProportionId) {
        if (skill == null) {
            return 1f;
        }
        // 无 FX 表时与客户端缺省一致
        return 1f;
    }

    public static float proportionOf(CultivateTables.SkillProp skill, int damageProportionId,
                                     com.sao.fakeserver.table.FightConfigTables fx) {
        float fallback = 1f;
        if (fx == null || skill == null || skill.modelPath == null || skill.modelPath.isEmpty()) {
            return fallback;
        }
        return fx.proportion(skill.modelPath, damageProportionId, fallback);
    }
}
