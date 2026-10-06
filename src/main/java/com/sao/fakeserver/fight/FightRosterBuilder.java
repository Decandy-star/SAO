package com.sao.fakeserver.fight;

import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.FightConfigTables;
import com.sao.fakeserver.table.GameTables;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 开战时从存档 / JJC_Robot 组装双方 FightUnit。
 * 机器人装备对齐客户端 MatchPlayer + JJC_RobotEquips。
 */
public final class FightRosterBuilder {
    private FightRosterBuilder() {
    }

    public static List<FightUnit> fromPlayer(PlayerRecord rec, CultivateTables cultivate,
                                            FightConfigTables fightCfg) {
        return fromPlayer(rec, cultivate, fightCfg, PlayerRecord.FORMATION_PVE);
    }

    public static List<FightUnit> fromPlayer(PlayerRecord rec, CultivateTables cultivate,
                                            FightConfigTables fightCfg, int formationType) {
        if (rec == null) {
            return new ArrayList<>();
        }
        return fromPlayerSlots(rec, cultivate, fightCfg, rec.formationSlots(formationType));
    }

    /** 指定 5 槽 GUID（协防挂矿阵容，可不等于账号默认防阵）。 */
    public static List<FightUnit> fromPlayerSlots(PlayerRecord rec, CultivateTables cultivate,
                                                  FightConfigTables fightCfg, List<String> slots) {
        List<FightUnit> list = new ArrayList<>();
        if (rec == null || slots == null) {
            return list;
        }
        for (String id : slots) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            PlayerRecord.Hero h = rec.findHero(id);
            if (h == null) {
                continue;
            }
            CultivateTables.CombatStats st = cultivate.computeCombatStats(rec, h);
            if (st == null) {
                continue;
            }
            applyEquipEffects(st, rec, h, cultivate, fightCfg);
            list.add(FightUnit.fromStats(rec.playerId, st));
        }
        return list;
    }

    public static List<FightUnit> fromRobot(GameTables.RobotRow robot, CultivateTables cultivate,
                                           GameTables tables, FightConfigTables fightCfg) {
        List<FightUnit> list = new ArrayList<>();
        if (robot == null) {
            return list;
        }
        int guid = robot.targetGuid;
        GameTables.RobotEquipLib lib = null;
        if (tables != null && robot.equipLibId > 0) {
            lib = tables.robotEquipLib(robot.equipLibId);
        }
        for (int i = 0; i < robot.wjIndex.length; i++) {
            int idx = robot.wjIndex[i];
            if (idx <= 0) {
                continue;
            }
            FightUnit u = buildRobotUnit(guid, idx, robot, lib, cultivate, fightCfg);
            if (u == null && i == 0 && robot.resId > 0) {
                u = buildRobotUnit(guid, robot.resId, robot, lib, cultivate, fightCfg);
            }
            if (u != null) {
                list.add(u);
            }
        }
        if (list.isEmpty() && robot.resId > 0) {
            FightUnit u = buildRobotUnit(guid, robot.resId, robot, lib, cultivate, fightCfg);
            if (u != null) {
                list.add(u);
            }
        }
        return list;
    }

    /**
     * KFZ 单场：只用指定武将 index 列表（通常 5 人），playerGuid={@code fightGuid}&gt;1e6。
     */
    public static List<FightUnit> fromRobotHeroes(GameTables.RobotRow robot, List<Integer> heroIndices,
                                                  int fightGuid, CultivateTables cultivate,
                                                  GameTables tables, FightConfigTables fightCfg) {
        List<FightUnit> list = new ArrayList<>();
        if (robot == null || heroIndices == null || heroIndices.isEmpty()) {
            return list;
        }
        GameTables.RobotEquipLib lib = null;
        if (tables != null && robot.equipLibId > 0) {
            lib = tables.robotEquipLib(robot.equipLibId);
        }
        for (Integer raw : heroIndices) {
            if (raw == null || raw.intValue() <= 0) {
                continue;
            }
            FightUnit u = buildRobotUnit(fightGuid, raw.intValue(), robot, lib, cultivate, fightCfg);
            if (u != null) {
                list.add(u);
            }
        }
        return list;
    }

    /**
     * 争霸主机布防整队：按 {@link PlayerRecord.Zbz#defenseWuJiangIds} 顺序灌装（被挑战 / 超时防守）。
     * 未布防或 GUID 无效则跳过；空列表=无防守单位。
     */
    public static List<FightUnit> fromPlayerDefense(PlayerRecord rec, CultivateTables cultivate,
                                                    FightConfigTables fightCfg) {
        List<FightUnit> list = new ArrayList<>();
        if (rec == null || rec.zbz == null || rec.zbz.defenseWuJiangIds == null) {
            return list;
        }
        for (String id : rec.zbz.defenseWuJiangIds) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            PlayerRecord.Hero h = rec.findHero(id);
            if (h == null) {
                continue;
            }
            CultivateTables.CombatStats st = cultivate.computeCombatStats(rec, h);
            if (st == null) {
                continue;
            }
            applyEquipEffects(st, rec, h, cultivate, fightCfg);
            list.add(FightUnit.fromStats(rec.playerId, st));
        }
        return list;
    }

    /**
     * 争霸主机布防第 {@code round1Based} 人（1-based，与布防列表顺序一致）。
     */
    public static FightUnit fromPlayerDefenseRound(PlayerRecord rec, int round1Based,
                                                   CultivateTables cultivate, FightConfigTables fightCfg) {
        if (rec == null || round1Based <= 0 || rec.zbz == null || rec.zbz.defenseWuJiangIds == null) {
            return null;
        }
        int idx = 0;
        for (String id : rec.zbz.defenseWuJiangIds) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            PlayerRecord.Hero h = rec.findHero(id);
            if (h == null) {
                continue;
            }
            idx++;
            if (idx == round1Based) {
                CultivateTables.CombatStats st = cultivate.computeCombatStats(rec, h);
                if (st == null) {
                    return null;
                }
                applyEquipEffects(st, rec, h, cultivate, fightCfg);
                return FightUnit.fromStats(rec.playerId, st);
            }
        }
        return null;
    }

    /** 布防有效人数（跳过空/未知 GUID）。 */
    public static int playerDefenseCount(PlayerRecord rec) {
        if (rec == null || rec.zbz == null || rec.zbz.defenseWuJiangIds == null) {
            return 0;
        }
        int n = 0;
        for (String id : rec.zbz.defenseWuJiangIds) {
            if (id != null && !id.isEmpty() && rec.findHero(id) != null) {
                n++;
            }
        }
        return n;
    }

    public static int playerDefenseFightPower(PlayerRecord rec, CultivateTables cultivate,
                                             FightConfigTables fightCfg) {
        int sum = 0;
        for (FightUnit u : fromPlayerDefense(rec, cultivate, fightCfg)) {
            if (u.stats != null) {
                sum += cultivate.fightPowerOf(u.stats);
            }
        }
        return Math.max(0, sum);
    }

    /**
     * 争霸一轮一人：取机器人第 {@code round1Based} 个有效武将（与 4604 DefenseWuJiang 槽对齐）。
     * {@code fightGuid} 为客户端 matchPlayer.TargetGuid（假服用 robotGuid+1e6，避开 ≤1e6 整队灌表）。
     */
    public static FightUnit fromRobotRound(GameTables.RobotRow robot, int round1Based, int fightGuid,
                                           CultivateTables cultivate, GameTables tables,
                                           FightConfigTables fightCfg) {
        if (robot == null || round1Based <= 0) {
            return null;
        }
        GameTables.RobotEquipLib lib = null;
        if (tables != null && robot.equipLibId > 0) {
            lib = tables.robotEquipLib(robot.equipLibId);
        }
        int roundIdx = 0;
        for (int i = 0; i < robot.wjIndex.length; i++) {
            int idx = robot.wjIndex[i];
            if (idx <= 0) {
                continue;
            }
            roundIdx++;
            if (roundIdx == round1Based) {
                FightUnit u = buildRobotUnit(fightGuid, idx, robot, lib, cultivate, fightCfg);
                if (u == null && robot.resId > 0) {
                    u = buildRobotUnit(fightGuid, robot.resId, robot, lib, cultivate, fightCfg);
                }
                return u;
            }
        }
        if (round1Based == 1 && robot.resId > 0) {
            return buildRobotUnit(fightGuid, robot.resId, robot, lib, cultivate, fightCfg);
        }
        return null;
    }

    /** 机器人第 round 轮武将表 index；无则 0。 */
    public static int robotRoundHeroIndex(GameTables.RobotRow robot, int round1Based) {
        if (robot == null || round1Based <= 0) {
            return 0;
        }
        int roundIdx = 0;
        for (int i = 0; i < robot.wjIndex.length; i++) {
            int idx = robot.wjIndex[i];
            if (idx <= 0) {
                continue;
            }
            roundIdx++;
            if (roundIdx == round1Based) {
                return idx;
            }
        }
        return round1Based == 1 ? Math.max(0, robot.resId) : 0;
    }

    /** 兼容旧调用（无装备库）。 */
    public static List<FightUnit> fromRobot(GameTables.RobotRow robot, CultivateTables cultivate) {
        return fromRobot(robot, cultivate, null, null);
    }

    /** 机器人展示/开战战力：与 fromRobot 同一套属性。 */
    public static int robotFightPower(GameTables.RobotRow robot, CultivateTables cultivate,
                                     GameTables tables, FightConfigTables fightCfg) {
        int sum = 0;
        for (FightUnit u : fromRobot(robot, cultivate, tables, fightCfg)) {
            if (u.stats != null) {
                sum += cultivate.fightPowerOf(u.stats);
            }
        }
        return Math.max(1, sum);
    }

    private static FightUnit buildRobotUnit(int guid, int heroIndex, GameTables.RobotRow robot,
                                           GameTables.RobotEquipLib lib, CultivateTables cultivate,
                                           FightConfigTables fightCfg) {
        PlayerRecord.Hero stub = new PlayerRecord.Hero();
        stub.id = "robot-" + guid + "-" + heroIndex + "-" + UUID.randomUUID().toString().substring(0, 8);
        stub.heroIndex = heroIndex;
        stub.level = robot.level;
        stub.stars = Math.max(1, robot.stars);
        stub.stage = Math.max(0, robot.stage);
        // 对齐 WuJiangInfo Sync from JJC_Robot：四条进阶熟练度都用 shuLianDu
        stub.stagePara1 = robot.shuLianDu;
        stub.stagePara2 = robot.shuLianDu;
        stub.stagePara3 = robot.shuLianDu;
        stub.stagePara4 = robot.shuLianDu;
        stub.skill1 = Math.max(1, robot.skillMingJiang);
        stub.skill2 = Math.max(1, robot.skillA);
        stub.skill3 = Math.max(1, robot.skillB);
        stub.skill4 = Math.max(1, robot.skillPassive);

        PlayerRecord fake = new PlayerRecord();
        fake.playerId = guid;
        fake.equipments = new ArrayList<>();
        if (lib != null) {
            attachRobotEquips(fake, stub, lib, cultivate);
        }
        CultivateTables.CombatStats st = cultivate.computeCombatStats(fake, stub);
        if (st == null) {
            return null;
        }
        applyEquipEffects(st, fake, stub, cultivate, fightCfg);
        return FightUnit.fromStats(guid, st);
    }

    /** 对齐 MatchPlayer：武器 job + 配件 job + equips[3..5]。 */
    public static void attachRobotEquips(PlayerRecord fake, PlayerRecord.Hero stub,
                                        GameTables.RobotEquipLib lib, CultivateTables cultivate) {
        if (fake == null || stub == null || lib == null) {
            return;
        }
        if (fake.equipments == null) {
            fake.equipments = new ArrayList<>();
        }
        CultivateTables.HeroCfg cfg = cultivate == null ? null : cultivate.heroByIndex(stub.heroIndex);
        if (cfg != null) {
            addRobotEquip(fake, stub.id, lib, slotOri(lib.weaponsAndPeiJians, cfg.weaponJob));
            addRobotEquip(fake, stub.id, lib, slotOri(lib.weaponsAndPeiJians, cfg.peiJianJob));
        }
        for (int i = 3; i <= 5; i++) {
            addRobotEquip(fake, stub.id, lib, slotOri(lib.equips, i));
        }
    }

    private static String slotOri(String[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) {
            return null;
        }
        String s = arr[idx];
        if (s == null || s.isEmpty() || "0".equals(s)) {
            return null;
        }
        return s;
    }

    private static void addRobotEquip(PlayerRecord fake, String owner, GameTables.RobotEquipLib lib, String ori) {
        if (ori == null) {
            return;
        }
        PlayerRecord.Equipment eq = new PlayerRecord.Equipment();
        eq.id = UUID.randomUUID().toString();
        eq.ori = ori;
        eq.owner = owner;
        eq.level = Math.max(1, lib.level);
        eq.stars = Math.max(0, lib.stars);
        eq.guhua = Math.max(0, lib.guHuaLevel);
        fake.equipments.add(eq);
    }

    static void applyEquipEffects(CultivateTables.CombatStats st, PlayerRecord rec, PlayerRecord.Hero wj,
                                  CultivateTables cultivate, FightConfigTables fightCfg) {
        if (st == null || rec == null || wj == null || wj.id == null || fightCfg == null || cultivate == null) {
            return;
        }
        if (rec.equipments == null) {
            return;
        }
        FightConfigTables.EquipEffectCfg weaponFx = null;
        FightConfigTables.EquipEffectCfg armorFx = null;
        for (PlayerRecord.Equipment eq : rec.equipments) {
            if (eq == null || eq.owner == null || !wj.id.equals(eq.owner)) {
                continue;
            }
            CultivateTables.EquipCfg ec = cultivate.equip(eq.ori);
            if (ec == null) {
                continue;
            }
            FightConfigTables.EquipEffectCfg fx = fightCfg.equipEffectByTypeJob(ec.type, ec.job);
            if (fx == null) {
                continue;
            }
            if (ec.type == 1) {
                weaponFx = fx;
            } else if (ec.type == 2) {
                armorFx = fx;
            }
        }
        applyOneEffect(st, weaponFx);
        applyOneEffect(st, armorFx);
    }

    private static void applyOneEffect(CultivateTables.CombatStats st, FightConfigTables.EquipEffectCfg fx) {
        if (fx == null) {
            return;
        }
        float r1 = fx.param1Ratio();
        switch (fx.type) {
            case FightConfigTables.EE_JU_LI:
                st.critDamage *= (1f + r1);
                break;
            case FightConfigTables.EE_ZHI_SHANG:
                st.chunCuiDamageRatio += r1;
                break;
            case FightConfigTables.EE_BAO_TOU:
                st.baoTouRatio = Math.max(st.baoTouRatio, r1);
                break;
            case FightConfigTables.EE_NENG_LIANG:
                // 魔法减伤由 Buff56 Enter→Notify 灌入 FightUnit，开战预灌会与 Buff 双计
                break;
            case FightConfigTables.EE_GE_DANG:
                // EquipmentEffect.BlockValToRate(val) = val/(val+battleBlockConstant)；登录 detail52 默认 100000
                st.geDangRatio = Math.max(st.geDangRatio, fx.param1 / (fx.param1 + 100_000f));
                break;
            default:
                break;
        }
    }
}
