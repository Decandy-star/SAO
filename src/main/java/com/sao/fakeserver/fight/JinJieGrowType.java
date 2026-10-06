package com.sao.fakeserver.fight;

/**
 * 对齐客户端 {@code JinJieGrowType}。
 */
public final class JinJieGrowType {
    public static final int AKT = 0;
    public static final int DAMAGE_PLUS_VALUE = 1;
    public static final int PHY_DEF = 2;
    public static final int MAGIC_DEF = 3;
    public static final int COMMON_DEFENCE = 4;
    public static final int DAMAGE_PLUS_RATE = 5;
    public static final int DAMAGE_REDUCE_RATE = 6;
    public static final int LIFE = 7;
    public static final int IGNOR_DEFENCE = 8;
    public static final int ATK_ADD_ENERGY = 9;
    public static final int BACKUP_ENERGY_ADD = 10;
    public static final int ADD_CURE_RATE = 11;
    public static final int WEAPON_ATK_ADD_RATE = 12;
    public static final int PEIJIAN_DEF_ADD_RATE = 13;
    public static final int SHIPIN_MAXHP_ADD_RATE = 14;
    public static final int BAO_JI = 15;
    public static final int WEAPON_EFFECT_P1 = 16;
    public static final int WEAPON_EFFECT_P2 = 17;
    public static final int PEIJIAN_EFFECT_P1 = 18;
    public static final int PEIJIAN_EFFECT_P2 = 19;
    public static final int DOGGE_VALUE = 20;
    public static final int BE_ATTACK_ENERGY_ADD = 21;

    private JinJieGrowType() {
    }

    public static boolean isRateType(int type) {
        return type == DAMAGE_PLUS_RATE || type == DAMAGE_REDUCE_RATE
                || type == WEAPON_ATK_ADD_RATE || type == PEIJIAN_DEF_ADD_RATE
                || type == SHIPIN_MAXHP_ADD_RATE || type == ADD_CURE_RATE
                || type == BAO_JI;
    }

    /** GetToTalJinJieGrow 不取整的类型（比率 + 特效参 + 躲避）。 */
    public static boolean isNonTruncateGrow(int type) {
        return isRateType(type)
                || type == WEAPON_EFFECT_P1 || type == WEAPON_EFFECT_P2
                || type == PEIJIAN_EFFECT_P1 || type == PEIJIAN_EFFECT_P2
                || type == DOGGE_VALUE;
    }
}
