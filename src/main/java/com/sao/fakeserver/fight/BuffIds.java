package com.sao.fakeserver.fight;

/**
 * 与 NetProto.Buff_Enum / 客户端伤害相关 buff 对齐的常量。
 */
public final class BuffIds {
    private BuffIds() {
    }

    public static final int HP_MODIFY = 1;
    public static final int HP_MODIFY_RATIO = 2;
    public static final int HP_MODIFY_BY_MAX_HP_RATIO = 3;
    public static final int MAX_HP = 4;
    public static final int MAX_HP_RATIO = 5;
    public static final int ATTACK = 6;
    public static final int ATTACK_RATIO = 7;
    public static final int PHYSICS_DEFENCE = 8;
    public static final int DAMAGE_RATIO = 9;
    public static final int MAGIC_DEFENCE = 10;
    public static final int HEAL_ADD_RATIO = 11;
    public static final int HEAL_ADD_VALUE = 12;
    public static final int CRIT_RATIO = 13;
    public static final int PHYSICS_ATTACK_ABSORB = 15;
    public static final int MAGIC_ATTACK_ABSORB = 16;
    public static final int PHYSICS_ABSORB_RATIO = 17;
    public static final int MAGIC_ABSORB_RATIO = 18;
    public static final int DAMAGE_REBOUND = 19;
    public static final int PHYSICS_ABSORB_AND_REBOUND = 20;
    public static final int MAGIC_ABSORB_AND_REBOUND = 21;
    public static final int XI_XUE_RATIO = 24;
    public static final int DAMAGE_SHARE = 34;
    public static final int AOE_DAMAGE = 40;
    public static final int NO_DIE_HP = 43;
    public static final int NO_DIE_HP_RATIO = 44;
    public static final int DEFENCE_REDUCE = 48;
    public static final int SKILL_CHANGE = 49;
    public static final int EQUIP_XI_SHOU = 55;
    public static final int MAGIC_DAMAGE_REDUCE = 56;
    public static final int BAO_TOU = 57;
    public static final int ZHI_SHANG = 60;
    public static final int RECEIVE_HEAL = 62;
    public static final int DAMAGE_RATIO_BY_SKILL = 63;
    public static final int DODGE_RATE = 65;
    public static final int ALL_ATTACK_ABSORB = 67;
    /** EquipAllResistBuffElement：写入 FightAttr.Resist */
    public static final int ALL_RESIST = 69;
    /** EquipResistBuffElement：写入 FightAttr.Resist */
    public static final int EQUIP_RESIST = 70;
}
