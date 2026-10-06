package com.sao.fakeserver.service;

import com.sao.fakeserver.fight.BuffIds;
import com.sao.fakeserver.fight.DamageFormula;
import com.sao.fakeserver.fight.FightRosterBuilder;
import com.sao.fakeserver.fight.FightUnit;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.FightConfigTables;
import com.sao.fakeserver.table.GameTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IsServerCal 完整战斗同步：Hurt/Cure/Buff/吸收盾/替补疗/召唤，按技能表+属性算伤。
 * JJC / ZBZ / KFZ / 克隆 / 抢矿等开战调用 {@link #beginBattle} 或专用 begin*。
 */
@Service
public class FightSyncService {
    private static final Logger log = LoggerFactory.getLogger(FightSyncService.class);

    /** 对齐 {@code PlayerDumpService.cloneFightTeams} 敌方 PlayerGuid。 */
    public static final int CLONE_BOSS_GUID = 9000001;

    private final CultivateTables cultivate;
    private final GameTables tables;
    private final FightConfigTables fightCfg;
    private final Map<String, Battle> byAccount = new ConcurrentHashMap<>();

    public FightSyncService(CultivateTables cultivate, GameTables tables, FightConfigTables fightCfg) {
        this.cultivate = cultivate;
        this.tables = tables;
        this.fightCfg = fightCfg;
    }

    public void clear(GameSession session) {
        String key = accountKey(session);
        if (key != null) {
            byAccount.remove(key);
        }
    }

    /** 当前账号是否仍有已注册的战斗（结算读胜负前调用）。 */
    public boolean hasBattle(GameSession session) {
        String key = accountKey(session);
        return key != null && byAccount.containsKey(key);
    }

    /**
     * 按当前 HP 判己方是否胜：敌方全灭且己方仍有存活=胜；己方全灭或双方都还有人（超时）=负。
     * 无战斗注册时返回 false（防空 4106 刷奖）。
     */
    public boolean playerWon(GameSession session, int selfPlayerId) {
        String key = accountKey(session);
        Battle b = key == null ? null : byAccount.get(key);
        if (b == null) {
            return false;
        }
        boolean selfAlive = false;
        boolean foeAlive = false;
        for (FightUnit u : b.byIdentity.values()) {
            if (u == null || u.curHp <= 0) {
                continue;
            }
            if (u.playerGuid == selfPlayerId) {
                selfAlive = true;
            } else {
                foeAlive = true;
            }
        }
        return selfAlive && !foeAlive;
    }

    /**
     * ZBZ 结算用：敌方击杀数（开战注册过且 curHp≤0 的非己方单位）+ 敌方开战战力合计。
     */
    public ZbzBattleSnap zbzBattleSnap(GameSession session, int selfPlayerId) {
        ZbzBattleSnap snap = new ZbzBattleSnap();
        String key = accountKey(session);
        Battle b = key == null ? null : byAccount.get(key);
        if (b == null) {
            return snap;
        }
        for (FightUnit u : b.byIdentity.values()) {
            if (u == null) {
                continue;
            }
            if (u.playerGuid == selfPlayerId) {
                if (u.stats != null) {
                    snap.myFp += cultivate.fightPowerOf(u.stats);
                }
                if (u.curHp <= 0) {
                    snap.selfDead++;
                }
                continue;
            }
            if (u.curHp <= 0) {
                snap.killNum++;
            }
            if (u.stats != null) {
                snap.foeFp += cultivate.fightPowerOf(u.stats);
            }
        }
        return snap;
    }

    public static final class ZbzBattleSnap {
        public int killNum;
        public int foeFp;
        public int selfDead;
        public int myFp;
    }

    /** KFZ 结算：己方开战武将 index + 是否阵亡（灌 4707 hasPlayed/isDead）。 */
    public List<KfzWjSnap> selfWjSnap(GameSession session, int selfPlayerId) {
        List<KfzWjSnap> out = new ArrayList<>();
        String key = accountKey(session);
        Battle b = key == null ? null : byAccount.get(key);
        if (b == null) {
            return out;
        }
        for (FightUnit u : b.byIdentity.values()) {
            if (u == null || u.playerGuid != selfPlayerId || u.wjId <= 0) {
                continue;
            }
            KfzWjSnap s = new KfzWjSnap();
            s.wjIndex = u.wjId;
            s.dead = u.curHp <= 0;
            out.add(s);
        }
        return out;
    }

    public static final class KfzWjSnap {
        public int wjIndex;
        public boolean dead;
    }

    /** 无客户端回合的机器人对打结果（ZBZ 括号他人场 / 池积分播种）。 */
    public static final class RobotMatchResult {
        public int winnerGuid;
        public int loserGuid;
        /** 胜方击杀数（败方阵亡单位数）。 */
        public int killNum;
        /** 败方击杀数（胜方阵亡单位数）。 */
        public int loserKillNum;
        /** 开战时胜方战力合计。 */
        public int winnerFp;
        /** 开战时败方战力合计。 */
        public int loserFp;
    }

    /**
     * ZBZ 非玩家场：双方按武将表序多轮 1v1（攻方 guidA 打守方 guidB 的武将槽），
     * 对齐 APK 争霸「一轮一人 / 守方一轮败则整场败」，不用整队团战。
     * 被动已折进 CombatStats；不进 session Battle，不发同步包。
     */
    public RobotMatchResult simulateRobotVsRobot(int guidA, int guidB) {
        GameTables.RobotRow ra = tables.robotByGuid(guidA);
        GameTables.RobotRow rb = tables.robotByGuid(guidB);
        List<FightUnit> atkAll = FightRosterBuilder.fromRobot(ra, cultivate, tables, fightCfg);
        List<FightUnit> defAll = FightRosterBuilder.fromRobot(rb, cultivate, tables, fightCfg);
        int fpA = FightRosterBuilder.robotFightPower(ra, cultivate, tables, fightCfg);
        int fpB = FightRosterBuilder.robotFightPower(rb, cultivate, tables, fightCfg);
        RobotMatchResult out = resolveZbzOneVOneSeries(atkAll, defAll, guidA, guidB, fpA, fpB);
        log.info("zbz sim robot {} vs {} (1v1) → win={} kill={}/{}",
                guidA, guidB, out.winnerGuid, out.killNum, out.loserKillNum);
        return out;
    }

    /**
     * KFZ/巅峰他人场：双方均有 PlayerRecord（真人或捏造 NPC）时，按指定防阵取武将序做多轮 1v1。
     * 属性走 {@link FightRosterBuilder#fromPlayer}，禁止把 playerId 当 robotGuid。
     */
    public RobotMatchResult simulatePlayerFormationVsPlayer(PlayerRecord a, int formA,
                                                            PlayerRecord b, int formB) {
        RobotMatchResult out = new RobotMatchResult();
        if (a == null || b == null) {
            out.winnerGuid = a != null ? a.playerId : (b != null ? b.playerId : 0);
            out.loserGuid = a != null && b != null
                    ? (out.winnerGuid == a.playerId ? b.playerId : a.playerId) : 0;
            return out;
        }
        List<FightUnit> atkAll = FightRosterBuilder.fromPlayer(a, cultivate, fightCfg, formA);
        List<FightUnit> defAll = FightRosterBuilder.fromPlayer(b, cultivate, fightCfg, formB);
        int fpA = formationFightPower(atkAll);
        int fpB = formationFightPower(defAll);
        out = resolveKfzTeamFiveVFive(atkAll, defAll, a.playerId, b.playerId, fpA, fpB);
        log.info("kfz sim 5v5 player {} vs {} form={}/{} → win={} kill={}/{}",
                a.playerId, b.playerId, formA, formB, out.winnerGuid, out.killNum, out.loserKillNum);
        return out;
    }

    /**
     * 巅峰他人场混合：PlayerRecord（真人/NPC）对表机器人。
     * {@code player} 作攻序，{@code robotGuid} 作守序；胜负 guid 分别为双方 id。
     */
    public RobotMatchResult simulatePlayerFormationVsRobot(PlayerRecord player, int form, int robotGuid) {
        RobotMatchResult out = new RobotMatchResult();
        if (player == null) {
            out.winnerGuid = robotGuid;
            out.loserGuid = 0;
            return out;
        }
        List<FightUnit> atkAll = FightRosterBuilder.fromPlayer(player, cultivate, fightCfg, form);
        GameTables.RobotRow robot = tables.robotByGuid(robotGuid);
        List<FightUnit> defAll = FightRosterBuilder.fromRobot(robot, cultivate, tables, fightCfg);
        int fpA = formationFightPower(atkAll);
        int fpB = FightRosterBuilder.robotFightPower(robot, cultivate, tables, fightCfg);
        out = resolveKfzTeamFiveVFive(atkAll, defAll, player.playerId, robotGuid, fpA, fpB);
        log.info("kfz sim 5v5 player{} vs robot{} → win={}", player.playerId, robotGuid, out.winnerGuid);
        return out;
    }

    private int formationFightPower(List<FightUnit> units) {
        int sum = 0;
        if (units == null) {
            return 0;
        }
        for (FightUnit u : units) {
            if (u != null && u.stats != null) {
                sum += cultivate.fightPowerOf(u.stats);
            }
        }
        return Math.max(0, sum);
    }

    /**
     * 机器人攻主机：对齐 APK 争霸多轮 1v1——攻方按机器人武将序、守方按 {@code defenseWuJiangIds} 序。
     * 无布防 → 守方空阵 → 机器人胜。供超时 forceFinish / challengePlayerWithRobot。
     */
    public RobotMatchResult simulateRobotAttackPlayerDefense(int robotGuid, PlayerRecord defender) {
        RobotMatchResult out = new RobotMatchResult();
        if (defender == null) {
            out.winnerGuid = robotGuid;
            out.loserGuid = 0;
            out.killNum = 1;
            return out;
        }
        GameTables.RobotRow robot = tables.robotByGuid(robotGuid);
        List<FightUnit> atkAll = FightRosterBuilder.fromRobot(robot, cultivate, tables, fightCfg);
        List<FightUnit> defAll = FightRosterBuilder.fromPlayerDefense(defender, cultivate, fightCfg);
        int fpAtk = FightRosterBuilder.robotFightPower(robot, cultivate, tables, fightCfg);
        int fpDef = FightRosterBuilder.playerDefenseFightPower(defender, cultivate, fightCfg);
        out = resolveZbzOneVOneSeries(atkAll, defAll, robotGuid, defender.playerId, fpAtk, fpDef);
        if (defAll.isEmpty()) {
            log.info("zbz sim robot{} attack player{}：无布防 → robot win", robotGuid, defender.playerId);
        } else {
            log.info("zbz sim robot{} attack player{} → win={} kill={}/{}",
                    robotGuid, defender.playerId, out.winnerGuid, out.killNum, out.loserKillNum);
        }
        return out;
    }

    /**
     * 争霸 1v1 系列：攻方 {@link PlayerRecord#FORMATION_ZBZ} 武将序 × 守方 {@code defenseWuJiangIds}。
     * 冒烟 / NPC↔主机；不进 session Battle。
     */
    public RobotMatchResult simulatePlayerAttackPlayerDefense(PlayerRecord attacker, PlayerRecord defender) {
        RobotMatchResult out = new RobotMatchResult();
        if (attacker == null || defender == null) {
            out.winnerGuid = attacker != null ? attacker.playerId : (defender != null ? defender.playerId : 0);
            out.loserGuid = attacker != null && defender != null
                    ? (out.winnerGuid == attacker.playerId ? defender.playerId : attacker.playerId) : 0;
            return out;
        }
        List<FightUnit> atkAll = FightRosterBuilder.fromPlayer(
                attacker, cultivate, fightCfg, PlayerRecord.FORMATION_ZBZ);
        List<FightUnit> defAll = FightRosterBuilder.fromPlayerDefense(defender, cultivate, fightCfg);
        int fpAtk = formationFightPower(atkAll);
        int fpDef = FightRosterBuilder.playerDefenseFightPower(defender, cultivate, fightCfg);
        out = resolveZbzOneVOneSeries(atkAll, defAll, attacker.playerId, defender.playerId, fpAtk, fpDef);
        log.info("zbz sim player{} attack player{} def → win={} kill={}/{}",
                attacker.playerId, defender.playerId, out.winnerGuid, out.killNum, out.loserKillNum);
        return out;
    }

    /**
     * KFZ 他人场：双方全员同时在场，轮流 swingSide（与开战 DamageFormula 同源），
     * 直至一方全滅。区别于争霸「一轮一人」{@link #resolveZbzOneVOneSeries}。
     */
    private RobotMatchResult resolveKfzTeamFiveVFive(List<FightUnit> atkSrc, List<FightUnit> defSrc,
                                                     int atkGuid, int defGuid, int fpAtk, int fpDef) {
        RobotMatchResult out = new RobotMatchResult();
        List<FightUnit> atkAll = new ArrayList<>();
        List<FightUnit> defAll = new ArrayList<>();
        if (atkSrc != null) {
            for (FightUnit u : atkSrc) {
                FightUnit c = copyUnitFresh(u);
                if (c != null) {
                    applyResistRatios(c);
                    atkAll.add(c);
                }
            }
        }
        if (defSrc != null) {
            for (FightUnit u : defSrc) {
                FightUnit c = copyUnitFresh(u);
                if (c != null) {
                    applyResistRatios(c);
                    defAll.add(c);
                }
            }
        }
        if (defAll.isEmpty() && atkAll.isEmpty()) {
            out.winnerGuid = atkGuid <= defGuid ? atkGuid : defGuid;
            out.loserGuid = out.winnerGuid == atkGuid ? defGuid : atkGuid;
            out.killNum = 1;
            out.winnerFp = out.winnerGuid == atkGuid ? fpAtk : fpDef;
            out.loserFp = out.winnerGuid == atkGuid ? fpDef : fpAtk;
            return out;
        }
        if (defAll.isEmpty()) {
            out.winnerGuid = atkGuid;
            out.loserGuid = defGuid;
            out.killNum = Math.max(1, atkAll.size());
            out.winnerFp = fpAtk;
            out.loserFp = fpDef;
            return out;
        }
        if (atkAll.isEmpty()) {
            out.winnerGuid = defGuid;
            out.loserGuid = atkGuid;
            out.killNum = Math.max(1, defAll.size());
            out.winnerFp = fpDef;
            out.loserFp = fpAtk;
            return out;
        }
        int atkStart = atkAll.size();
        int defStart = defAll.size();
        int ticks = 0;
        while (ticks++ < 8000 && anyAlive(atkAll) && anyAlive(defAll)) {
            swingSide(atkAll, defAll, ticks);
            if (!anyAlive(defAll)) {
                break;
            }
            swingSide(defAll, atkAll, ticks);
        }
        boolean atkWin = anyAlive(atkAll) && !anyAlive(defAll);
        boolean defWin = anyAlive(defAll) && !anyAlive(atkAll);
        if (!atkWin && !defWin) {
            // 超时：比剩余血量总和
            long hpA = 0;
            long hpB = 0;
            for (FightUnit u : atkAll) {
                if (u != null && u.curHp > 0) {
                    hpA += u.curHp;
                }
            }
            for (FightUnit u : defAll) {
                if (u != null && u.curHp > 0) {
                    hpB += u.curHp;
                }
            }
            atkWin = hpA >= hpB;
        }
        int atkAlive = 0;
        int defAlive = 0;
        for (FightUnit u : atkAll) {
            if (u != null && u.curHp > 0) {
                atkAlive++;
            }
        }
        for (FightUnit u : defAll) {
            if (u != null && u.curHp > 0) {
                defAlive++;
            }
        }
        if (atkWin) {
            out.winnerGuid = atkGuid;
            out.loserGuid = defGuid;
            out.killNum = Math.max(1, defStart - defAlive);
            out.loserKillNum = Math.max(0, atkStart - atkAlive);
            out.winnerFp = fpAtk;
            out.loserFp = fpDef;
        } else {
            out.winnerGuid = defGuid;
            out.loserGuid = atkGuid;
            out.killNum = Math.max(1, atkStart - atkAlive);
            out.loserKillNum = Math.max(0, defStart - defAlive);
            out.winnerFp = fpDef;
            out.loserFp = fpAtk;
        }
        return out;
    }

    /**
     * 争霸多轮 1v1：攻方按序打守方槽；守方赢一轮→整场守方胜；攻方打光守方→攻方胜。
     */
    private RobotMatchResult resolveZbzOneVOneSeries(List<FightUnit> atkAll, List<FightUnit> defAll,
                                                     int atkGuid, int defGuid, int fpAtk, int fpDef) {
        RobotMatchResult out = new RobotMatchResult();
        if (atkAll == null) {
            atkAll = new ArrayList<>();
        }
        if (defAll == null) {
            defAll = new ArrayList<>();
        }
        for (FightUnit u : atkAll) {
            applyResistRatios(u);
        }
        for (FightUnit u : defAll) {
            applyResistRatios(u);
        }
        if (defAll.isEmpty() && atkAll.isEmpty()) {
            out.winnerGuid = atkGuid <= defGuid ? atkGuid : defGuid;
            out.loserGuid = out.winnerGuid == atkGuid ? defGuid : atkGuid;
            out.killNum = 1;
            out.winnerFp = out.winnerGuid == atkGuid ? fpAtk : fpDef;
            out.loserFp = out.winnerGuid == atkGuid ? fpDef : fpAtk;
            return out;
        }
        if (defAll.isEmpty()) {
            out.winnerGuid = atkGuid;
            out.loserGuid = defGuid;
            out.killNum = 1;
            out.winnerFp = fpAtk;
            out.loserFp = fpDef;
            return out;
        }
        if (atkAll.isEmpty()) {
            out.winnerGuid = defGuid;
            out.loserGuid = atkGuid;
            out.killNum = 1;
            out.winnerFp = fpDef;
            out.loserFp = fpAtk;
            return out;
        }
        int atkKills = 0;
        int defKills = 0;
        int a = 0;
        int d = 0;
        while (a < atkAll.size() && d < defAll.size()) {
            FightUnit atk = copyUnitFresh(atkAll.get(a));
            FightUnit def = copyUnitFresh(defAll.get(d));
            applyResistRatios(atk);
            applyResistRatios(def);
            if (duelOneVOne(atk, def)) {
                atkKills++;
                d++;
                a++;
            } else {
                defKills++;
                out.winnerGuid = defGuid;
                out.loserGuid = atkGuid;
                out.killNum = Math.max(1, defKills);
                out.loserKillNum = atkKills;
                out.winnerFp = fpDef;
                out.loserFp = fpAtk;
                return out;
            }
        }
        if (d >= defAll.size()) {
            out.winnerGuid = atkGuid;
            out.loserGuid = defGuid;
            out.killNum = Math.max(1, atkKills);
            out.loserKillNum = defKills;
            out.winnerFp = fpAtk;
            out.loserFp = fpDef;
        } else {
            out.winnerGuid = defGuid;
            out.loserGuid = atkGuid;
            out.killNum = Math.max(1, defKills);
            out.loserKillNum = atkKills;
            out.winnerFp = fpDef;
            out.loserFp = fpAtk;
        }
        return out;
    }

    /** 单挑至一方阵亡；返回 true=攻方胜。 */
    private boolean duelOneVOne(FightUnit atk, FightUnit def) {
        List<FightUnit> sideA = new ArrayList<>();
        List<FightUnit> sideB = new ArrayList<>();
        sideA.add(atk);
        sideB.add(def);
        int ticks = 0;
        while (ticks++ < 5000 && atk.curHp > 0 && def.curHp > 0) {
            swingSide(sideA, sideB, ticks);
            if (def.curHp <= 0) {
                return true;
            }
            swingSide(sideB, sideA, ticks);
        }
        if (atk.curHp > 0 && def.curHp <= 0) {
            return true;
        }
        if (def.curHp > 0 && atk.curHp <= 0) {
            return false;
        }
        return atk.curHp >= def.curHp;
    }

    private static FightUnit copyUnitFresh(FightUnit src) {
        if (src == null || src.stats == null) {
            return src;
        }
        return FightUnit.fromStats(src.playerGuid, src.stats);
    }

    private void swingSide(List<FightUnit> attackers, List<FightUnit> defenders, int tick) {
        int i = 0;
        for (FightUnit atk : attackers) {
            if (atk == null || atk.curHp <= 0) {
                continue;
            }
            int skillId = pickSimSkillId(atk, tick + i);
            i++;
            CultivateTables.SkillProp skill = skillId > 0 ? cultivate.skill(skillId) : null;
            int skLv = atk.skillLevelOf(skillId);
            float skillDmg = DamageFormula.skillDamageValue(skill, atk, skLv);
            // 治疗技：抬己方残血最低者（对齐 skillType=3 / cureType）
            if (skill != null && (skill.skillType == 3 || skill.cureType > 0)) {
                FightUnit ally = lowestHpAlive(attackers);
                if (ally != null) {
                    DamageFormula.Result cr = DamageFormula.cure(atk, ally, skill, skillDmg, 1f);
                    ally.applyHeal(Math.max(0, Math.round(cr.damage)));
                }
                continue;
            }
            FightUnit tgt = firstAlive(defenders);
            if (tgt == null) {
                return;
            }
            DamageFormula.Result dr = DamageFormula.damage(atk, tgt, skill, skillDmg, 0, 1f, true);
            int dmg = Math.max(0, Math.round(dr.damage));
            tgt.applyDamage(dmg);
        }
    }

    /** 轮转：普攻 → SkillA → SkillB → 名将（有 id 才进序列）。被动不加招，已进面板。 */
    private static int pickSimSkillId(FightUnit u, int seq) {
        if (u == null || u.stats == null) {
            return 0;
        }
        CultivateTables.CombatStats st = u.stats;
        int[] cand = new int[]{st.skillNormal, st.skillA, st.skillB, st.skillMingJiang};
        int n = 0;
        for (int id : cand) {
            if (id > 0) {
                n++;
            }
        }
        if (n == 0) {
            return 0;
        }
        int want = Math.floorMod(seq, n);
        int seen = 0;
        for (int id : cand) {
            if (id <= 0) {
                continue;
            }
            if (seen == want) {
                return id;
            }
            seen++;
        }
        return st.skillNormal;
    }

    private static FightUnit lowestHpAlive(List<FightUnit> units) {
        FightUnit best = null;
        float bestRatio = 2f;
        for (FightUnit u : units) {
            if (u == null || u.curHp <= 0 || u.maxHp <= 0) {
                continue;
            }
            float r = (float) u.curHp / (float) u.maxHp;
            if (r < bestRatio) {
                bestRatio = r;
                best = u;
            }
        }
        return best;
    }

    private static FightUnit firstAlive(List<FightUnit> units) {
        for (FightUnit u : units) {
            if (u != null && u.curHp > 0) {
                return u;
            }
        }
        return null;
    }

    private static boolean anyAlive(List<FightUnit> units) {
        return firstAlive(units) != null;
    }

    private static int countDead(List<FightUnit> units) {
        int n = 0;
        for (FightUnit u : units) {
            if (u != null && u.curHp <= 0) {
                n++;
            }
        }
        return n;
    }

    private static int sumHp(List<FightUnit> units) {
        int s = 0;
        for (FightUnit u : units) {
            if (u != null && u.curHp > 0) {
                s += u.curHp;
            }
        }
        return s;
    }

    /** 开战：注册己方阵容 + 机器人（或空对方）。默认 PVE 阵。 */
    public void beginBattle(GameSession session, int robotGuid) {
        beginBattle(session, robotGuid, PlayerRecord.FORMATION_PVE);
    }

    /** @param formationType 对齐 eFormationType（JJC 攻=2 / ZBZ=31 / KFZ 攻=32） */
    public void beginBattle(GameSession session, int robotGuid, int formationType) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Battle b = new Battle();
        registerPlayer(b, rec, formationType);
        registerRobot(b, robotGuid);
        finishBegin(session, rec, b, formationType, robotGuid);
    }

    /**
     * 争霸开战：己方 type31 **仅主将 1 人**（APK EmBattle Clear+蒙四格）；敌方仅本轮 1 将。
     * {@code fightGuid} 须与开战前下发的 matchPlayer.TargetGuid 一致（通常 robotGuid+1e6）。
     */
    public void beginBattleZbz(GameSession session, int robotGuid, int round1Based, int fightGuid) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Battle b = new Battle();
        registerPlayerZbzAttacker(b, rec);
        GameTables.RobotRow robot = tables.robotByGuid(robotGuid);
        FightUnit foe = FightRosterBuilder.fromRobotRound(
                robot, Math.max(1, round1Based), fightGuid, cultivate, tables, fightCfg);
        if (foe != null) {
            applyResistRatios(foe);
            b.register(foe);
        } else {
            log.warn("zbz begin: no round hero robot={} round={}", robotGuid, round1Based);
        }
        finishBegin(session, rec, b, PlayerRecord.FORMATION_ZBZ, fightGuid);
    }

    /**
     * 争霸对捏造 NPC / 真人：守方按 {@link FightRosterBuilder#fromPlayerDefenseRound}；
     * {@code fightGuid} 与 2004 TargetGuid 一致（NPC 用 playerId，表机器人仍 robot+1e6）。
     */
    public void beginBattleZbzVsPlayer(GameSession session, PlayerRecord foe, int round1Based, int fightGuid) {
        PlayerRecord rec = session.player();
        if (rec == null || foe == null) {
            return;
        }
        Battle b = new Battle();
        registerPlayerZbzAttacker(b, rec);
        FightUnit foeUnit = FightRosterBuilder.fromPlayerDefenseRound(
                foe, Math.max(1, round1Based), cultivate, fightCfg);
        if (foeUnit != null) {
            if (fightGuid > 0) {
                foeUnit.playerGuid = fightGuid;
            }
            applyResistRatios(foeUnit);
            b.register(foeUnit);
        } else {
            log.warn("zbz vs player: no round hero foe={} round={}", foe.account, round1Based);
        }
        finishBegin(session, rec, b, PlayerRecord.FORMATION_ZBZ, fightGuid);
    }

    /**
     * KFZ 开战：己方 type32；敌方自定义 fightGuid&gt;1e6 + 指定 5 武将（三场互异防阵）。
     */
    public void beginBattleKfz(GameSession session, int fightGuid, int robotGuid, List<Integer> heroes) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Battle b = new Battle();
        registerPlayer(b, rec, PlayerRecord.FORMATION_KFZ_ATK);
        GameTables.RobotRow robot = tables.robotByGuid(robotGuid);
        List<FightUnit> foes = FightRosterBuilder.fromRobotHeroes(
                robot, heroes, fightGuid, cultivate, tables, fightCfg);
        for (FightUnit u : foes) {
            applyResistRatios(u);
            b.register(u);
        }
        if (foes.isEmpty()) {
            log.warn("kfz begin: no heroes robot={} fightGuid={}", robotGuid, fightGuid);
        }
        finishBegin(session, rec, b, PlayerRecord.FORMATION_KFZ_ATK, fightGuid);
    }

    /**
     * KFZ 对真人：己方 type32；敌方 {@code formationType}=DEF1/2/3。
     * 单位 identity=foe.playerId（与 4708 TargetGuid / lastFightGuid 一致）。
     */
    public void beginBattleKfzVsPlayer(GameSession session, PlayerRecord foe, int formationType) {
        PlayerRecord rec = session.player();
        if (rec == null || foe == null || foe.playerId == rec.playerId) {
            log.warn("kfz vs player refuse: missing foe");
            return;
        }
        Battle b = new Battle();
        registerPlayer(b, rec, PlayerRecord.FORMATION_KFZ_ATK);
        List<FightUnit> foes = FightRosterBuilder.fromPlayer(foe, cultivate, fightCfg, formationType);
        for (FightUnit u : foes) {
            applyResistRatios(u);
            b.register(u);
        }
        if (foes.isEmpty()) {
            log.warn("kfz vs player: empty def form={} foe={}", formationType, foe.account);
        }
        finishBegin(session, rec, b, PlayerRecord.FORMATION_KFZ_ATK, foe.playerId);
    }

    /** type31：只注册阵上第一个有效武将（主将）；APK EmBattle Clear 后只上一将。 */
    private void registerPlayerZbzAttacker(Battle b, PlayerRecord rec) {
        List<FightUnit> all = FightRosterBuilder.fromPlayer(
                rec, cultivate, fightCfg, PlayerRecord.FORMATION_ZBZ);
        if (all.isEmpty()) {
            log.warn("zbz begin: no attacker account={}", rec.account);
            return;
        }
        FightUnit u = all.get(0);
        applyResistRatios(u);
        b.register(u);
    }

    /**
     * 克隆战：己方 {@link PlayerRecord#FORMATION_CLONE_ATK}；
     * 敌方 5 槽同模（与 S2C 5802 / APK targetPlayers[0..4] 一致）。
     * guid={@link #CLONE_BOSS_GUID}+(job-1)，避免同 WJID 撞 identity；客 GetCloneZhanWuJiangByIndex 按 guid+WJID。
     */
    public void beginCloneBattle(GameSession session, int bossHeroIndex) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int boss = bossHeroIndex > 0 ? bossHeroIndex : 18;
        Battle b = new Battle();
        registerPlayer(b, rec, PlayerRecord.FORMATION_CLONE_ATK);
        for (int job = 1; job <= 5; job++) {
            FightUnit foe = cloneBossUnit(boss, CLONE_BOSS_GUID + (job - 1));
            if (foe != null) {
                applyResistRatios(foe);
                b.register(foe);
            }
        }
        finishBegin(session, rec, b, PlayerRecord.FORMATION_CLONE_ATK, boss);
    }

    /**
     * 抢矿：己方攻阵 type4；对方矿主/协防防阵。
     * foeSlots 非空 = 协防挂阵 5 槽。
     */
    public void beginKuangBattle(GameSession session, PlayerRecord foe) {
        beginKuangBattle(session, foe, null);
    }

    public void beginKuangBattle(GameSession session, PlayerRecord foe, List<String> foeSlots) {
        PlayerRecord rec = session.player();
        if (rec == null || foe == null || foe.playerId == rec.playerId) {
            log.warn("kuang begin refuse: missing foe");
            return;
        }
        Battle b = new Battle();
        registerPlayer(b, rec, PlayerRecord.FORMATION_QIANGKUANG_ATK);
        if (foeSlots != null) {
            for (FightUnit u : FightRosterBuilder.fromPlayerSlots(foe, cultivate, fightCfg, foeSlots)) {
                applyResistRatios(u);
                b.register(u);
            }
        } else {
            registerPlayer(b, foe, PlayerRecord.FORMATION_QIANGKUANG_DEF);
        }
        finishBegin(session, rec, b, PlayerRecord.FORMATION_QIANGKUANG_ATK, foe.playerId);
    }

    private void registerPlayer(Battle b, PlayerRecord rec, int formationType) {
        for (FightUnit u : FightRosterBuilder.fromPlayer(rec, cultivate, fightCfg, formationType)) {
            applyResistRatios(u);
            b.register(u);
        }
    }

    private void registerRobot(Battle b, int robotGuid) {
        if (robotGuid <= 0 || robotGuid > 1_000_000) {
            return;
        }
        GameTables.RobotRow robot = tables.robotByGuid(robotGuid);
        if (robot == null) {
            log.warn("fight begin: robot {} missing", robotGuid);
            return;
        }
        for (FightUnit u : FightRosterBuilder.fromRobot(robot, cultivate, tables, fightCfg)) {
            applyResistRatios(u);
            b.register(u);
        }
    }

    /** 与 {@link CloneService#bossHeroFor} 同源（难度档强度）；playerGuid 按槽错开。 */
    private FightUnit cloneBossUnit(int bossHeroIndex, int playerGuid) {
        PlayerRecord.Hero stub = CloneService.bossHeroFor(bossHeroIndex);
        PlayerRecord fake = new PlayerRecord();
        fake.playerId = playerGuid;
        fake.equipments = new ArrayList<>();
        CultivateTables.CombatStats st = cultivate.computeCombatStats(fake, stub);
        if (st == null) {
            log.warn("clone boss stats missing wj={} guid={}", bossHeroIndex, playerGuid);
            return null;
        }
        return FightUnit.fromStats(playerGuid, st);
    }

    private void finishBegin(GameSession session, PlayerRecord rec, Battle b, int formationType, int foeTag) {
        byAccount.put(accountKey(session), b);
        StringBuilder snap = new StringBuilder();
        int myFp = 0;
        int foeFp = 0;
        for (FightUnit u : b.byIdentity.values()) {
            int fp = u.stats != null ? cultivate.fightPowerOf(u.stats) : 0;
            if (u.playerGuid == rec.playerId) {
                myFp += fp;
            } else {
                foeFp += fp;
            }
            snap.append(String.format(
                    " [guid=%d wj=%d atk=%.0f/%.0f def=%.0f/%.0f hp=%d crit=%.3f plusR=%.4f plusV=%.1f ignDef=%.1f red=%.4f fp=%d]",
                    u.playerGuid, u.wjId, u.phyAtk(), u.magAtk(), u.pdef(), u.mdef(), u.maxHp,
                    u.critRatio(), u.damagePlusRatio(), u.damagePlusValue(), u.defenceReduce(),
                    u.phyReduceRatio(), fp));
        }
        log.info("fight begin account={} type={} units={} foeTag={} myFp≈{} foeFp≈{}{}",
                rec.account, formationType, b.byIdentity.size(), foeTag, myFp, foeFp, snap);
    }

    public void onC2SSync(GameSession session, GamePacket pkt) {
        if (session.player() == null) {
            return;
        }
        Battle battle = byAccount.computeIfAbsent(accountKey(session), k -> new Battle());
        Pb.Fields pack = Pb.read(pkt.body);
        List<byte[]> items = pack.getBytesList(1);
        if (items.isEmpty()) {
            return;
        }
        List<byte[]> syncItems = new ArrayList<>();
        for (byte[] itemBytes : items) {
            Pb.Fields item = Pb.read(itemBytes);
            byte[] hurt = item.getBytes(1);
            byte[] cure = item.getBytes(2);
            byte[] buffU = item.getBytes(3);
            byte[] buffE = item.getBytes(4);
            byte[] cast = item.getBytes(8);
            battle.noteSyncFields(hurt, cure, buffU, buffE, cast);
            // 原始字段号：有非 {8} 立刻打；前 3 个 item 也打，排除「漏解析 Hurt」怀疑
            boolean onlyCast = cast != null && cast.length > 0
                    && (hurt == null || hurt.length == 0)
                    && (cure == null || cure.length == 0)
                    && (buffU == null || buffU.length == 0)
                    && (buffE == null || buffE.length == 0)
                    && item.getBytes(5).length == 0
                    && item.getBytes(6).length == 0
                    && item.getBytes(7).length == 0;
            if (!onlyCast || battle.syncItems <= 3) {
                log.info("syncItem#{} fields={} lens hurt={} cure={} buffU={} buffE={} summon={} del={} backup={} cast={}",
                        battle.syncItems, item.fieldKeys(),
                        hurt == null ? 0 : hurt.length,
                        cure == null ? 0 : cure.length,
                        buffU == null ? 0 : buffU.length,
                        buffE == null ? 0 : buffE.length,
                        item.getBytes(5).length, item.getBytes(6).length, item.getBytes(7).length,
                        cast == null ? 0 : cast.length);
            }
            // 显式暴露「认识字段之外」的 tag，避免只盯 1..8 漏看
            for (Integer fk : item.fieldKeys()) {
                if (fk != null && (fk < 1 || fk > 8)) {
                    log.warn("syncItem#{} UNKNOWN field={} (not in Hurt..skillCast 1..8)", battle.syncItems, fk);
                }
            }
            handleHurt(battle, hurt, syncItems);
            handleCure(battle, cure, syncItems);
            handleBuff(battle, buffU, false, syncItems);
            handleBuff(battle, buffE, true, syncItems);
            handleAddSummon(battle, item.getBytes(5));
            handleDelSummon(battle, item.getBytes(6), syncItems);
            handleBackupCure(battle, item.getBytes(7), syncItems);
            // skillCast field 8：施法上报（有动作≠有 Hurt）；仅 CD/能量，不改血
            handleSkillCast(cast);
        }
        if (syncItems.isEmpty()) {
            return;
        }
        session.send(MsgIds.S2C_FIGHT_SYNC_PACK, pkt, fightS2CPack(syncItems));
    }

    /** 战斗结束时打出 C2S 同步字段统计（确认客户端是否从未上报 Hurt）。 */
    public void logSyncStats(GameSession session) {
        Battle b = byAccount.get(accountKey(session));
        if (b == null) {
            return;
        }
        log.info("fight sync-stats items={} hurt={} cure={} buffU={} buffE={} skillCast={} castOnly={}",
                b.syncItems, b.syncHurt, b.syncCure, b.syncBuffUpdate, b.syncBuffEnd, b.syncSkillCast, b.syncCastOnly);
    }

    /** 施法上报：有 skillCast 说明 UseSkill→PlaySkill→StartCD 已走；不等于碰撞命中 Hurt。 */
    private void handleSkillCast(byte[] castBytes) {
        if (castBytes == null || castBytes.length == 0) {
            return;
        }
        Pb.Fields c = Pb.read(castBytes);
        byte[] castWj = c.getBytes(1);
        int skillId = c.getInt(2, 0);
        int skillType = c.getInt(9, 0);
        log.info("skillCast {} skill={} type={}", wjIdentity(castWj), skillId, skillType);
    }

    private void handleHurt(Battle battle, byte[] notifyBytes, List<byte[]> out) {
        if (notifyBytes == null || notifyBytes.length == 0) {
            return;
        }
        Pb.Fields n = Pb.read(notifyBytes);
        byte[] srcBytes = n.getBytes(1);
        byte[] targetBytes = n.getBytes(2);
        int skillId = n.getInt(3, 0);
        int proportionId = n.getInt(4, -1);
        int damageType = n.getInt(5, 0);
        String rawSrc = wjIdentity(srcBytes);
        String rawTgt = wjIdentity(targetBytes);
        FightUnit target = resolveAndBind(battle, targetBytes);
        FightUnit src = resolveAndBind(battle, srcBytes);
        if (target == null) {
            log.warn("hurt DROP target=null skill={} src={} tgt={} srcResolved={} battleUnits={}",
                    skillId, rawSrc, rawTgt, src != null, battle.byIdentity.size());
            return;
        }
        if (src == null) {
            // 禁止用 target 顶攻方算伤（会静默错伤）；暴露未注册攻方 / 漏 beginBattle
            log.warn("hurt DROP src=null skill={} srcWire={} tgt={}", skillId, rawSrc, rawTgt);
            return;
        }
        CultivateTables.SkillProp skill = cultivate.skill(skillId);
        int skLv = src.skillLevelOf(skillId);
        float skillDmg = DamageFormula.skillDamageValue(skill, src, skLv);
        float prop = DamageFormula.proportionOf(skill, proportionId, fightCfg);
        DamageFormula.Result dr = DamageFormula.damage(src, target, skill, skillDmg, damageType, prop);
        if (src.stats != null) {
            dr.trace.skillLv = skLv;
        }
        int dmg = Math.max(0, Math.round(dr.damage));
        int hpBefore = target.curHp;
        int applied = target.applyDamage(dmg);
        boolean clearPhy = target.lastAbsorbedPhy > 0f && target.physicsAbsorbSum() <= 0f;
        boolean clearMag = target.lastAbsorbedMag > 0f && target.magicAbsorbSum() <= 0f;
        boolean clearAll = target.lastAbsorbedAll > 0f && target.allAbsorbSum() <= 0f;
        appendAbsorbSync(target, targetBytes, out, clearPhy, clearMag, clearAll);
        out.add(s2cItemHp(fightHpWj(rebuildWj(target, targetBytes), srcBytes, target.curHp, target.maxHp)));
        // 吸盾反弹：对齐 Buff20/21 — 本段吸收量×比率打回攻方（不依赖 phase3 包序）
        float reb = 0f;
        if (target.absorbReboundRatioPhy > 0f && target.lastAbsorbedPhy > 0f) {
            reb += target.lastAbsorbedPhy * target.absorbReboundRatioPhy;
        }
        if (target.absorbReboundRatioMag > 0f && target.lastAbsorbedMag > 0f) {
            reb += target.lastAbsorbedMag * target.absorbReboundRatioMag;
        }
        int rd = Math.max(0, Math.round(reb));
        if (rd > 0) {
            src.applyDamage(rd);
            out.add(s2cItemHp(fightHpWj(rebuildWj(src, srcBytes), targetBytes, src.curHp, src.maxHp)));
            target.lastAbsorbedPhy = 0f;
            target.lastAbsorbedMag = 0f;
        }
        log.info("hurt {}->{} hp {}->{}/{} dmg={} applied={} | {}",
                src.playerGuid + ":" + src.wjId,
                target.playerGuid + ":" + target.wjId,
                hpBefore, target.curHp, target.maxHp, dmg, applied,
                dr.trace.summary());
    }

    private void handleCure(Battle battle, byte[] notifyBytes, List<byte[]> out) {
        if (notifyBytes == null || notifyBytes.length == 0) {
            return;
        }
        Pb.Fields n = Pb.read(notifyBytes);
        byte[] srcBytes = n.getBytes(1);
        byte[] targetBytes = n.getBytes(2);
        int skillId = n.getInt(3, 0);
        int proportionId = n.getInt(4, -1);
        FightUnit target = resolveAndBind(battle, targetBytes);
        FightUnit src = resolveAndBind(battle, srcBytes);
        if (target == null) {
            return;
        }
        if (src == null) {
            log.warn("cure DROP src=null skill={} tgt={}", skillId, wjIdentity(targetBytes));
            return;
        }
        CultivateTables.SkillProp skill = cultivate.skill(skillId);
        int skLv = src.skillLevelOf(skillId);
        float skillDmg = DamageFormula.skillDamageValue(skill, src, skLv);
        float prop = DamageFormula.proportionOf(skill, proportionId, fightCfg);
        DamageFormula.Result dr = DamageFormula.cure(src, target, skill, skillDmg, prop);
        int heal = Math.max(0, Math.round(dr.damage));
        target.applyHeal(heal);
        out.add(s2cItemHp(fightHpWj(rebuildWj(target, targetBytes), srcBytes, target.curHp, target.maxHp)));
    }

    private void handleBuff(Battle battle, byte[] bytes, boolean end, List<byte[]> out) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        Pb.Fields n = Pb.read(bytes);
        int buffEnum = n.getInt(1, 0);
        int buffId = n.getInt(2, 0);
        byte[] creatorBytes = n.getBytes(3);
        byte[] srcWjBytes = n.getBytes(4);
        byte[] targetBytes = n.getBytes(5);
        int skillId = n.getInt(6, 0);
        int stacks = n.getInt(7, 1);
        // phase 兜底必须是 0（协议 [DefaultValue(0)]，NetProto\CCMsgFight_NotifyStatusChange.cs:130-132）：
        // 客户端 NotifyServerCreateNewBuff 显式传 phase=0（Buff.cs:680 → BuffManager.cs:387 赋值），
        // protobuf-net 对「等于 DefaultValue」的成员不写盘 → 报文里没有 field 8。
        // 原来兜底 1 会让下面「phase=0 直接 return」失效：周期子 buff 的创建通知被当常规结算
        // （多算一次周期伤害/治疗、装备吸收 55 把 add_params[0]=父玩家 GUID 当吸收率）。
        int phase = n.getInt(8, 0);
        List<Integer> addParams = n.getInts(9);
        float param = addParams.isEmpty() ? 0f : addParams.get(0).floatValue();
        // phase=0：NotifyServerCreateNewBuff（Periodic 生子 Buff），add_params=父 Buff 身份，不算伤/不加盾
        if (!end && phase == 0) {
            return;
        }
        FightUnit target = resolveAndBind(battle, targetBytes);
        // creatorWJ = mBuff.mSrcUnit（Buff 施法源）；srcWJ = 事件参与者（可空 / 可为敌人）
        FightUnit creator = null;
        if (creatorBytes != null && creatorBytes.length > 0) {
            creator = resolveAndBind(battle, creatorBytes);
        }
        FightUnit eventSrc = null;
        if (srcWjBytes != null && srcWjBytes.length > 0) {
            eventSrc = resolveAndBind(battle, srcWjBytes);
        }
        // 多数结算「攻方」：事件源缺则退回创作者（周期跳伤 Notify src=null）
        FightUnit src = eventSrc != null ? eventSrc : creator;
        if (target == null) {
            return;
        }
        // Buff 表成长等级：对齐 mSrcUnit = creatorWJ（禁止用 AOE 周围敌人等级）
        int level = 1;
        FightUnit levelUnit = creator != null ? creator : eventSrc;
        if (levelUnit != null && skillId > 0) {
            level = levelUnit.skillLevelOf(skillId);
        }
        FightConfigTables.BuffCfg cfg = fightCfg.buff(buffId);
        FightUnit tableAtk = creator != null ? creator : src;
        float tableVal = buffTableValue(cfg, buffEnum, tableAtk, level, buffId);
        if (param == 0f && tableVal != 0f) {
            param = tableVal;
        }

        // Buff63：phase2 按技能类型绝对覆盖 DamageRatio；phase3/4/end 清除
        if (buffEnum == BuffIds.DAMAGE_RATIO_BY_SKILL) {
            handleSkillTypeDamageRatio(target, cfg, phase, end, addParams);
            return;
        }

        // 周期跳伤/跳疗：IsServerCal 下客户端 Hurt(int)/AddHp 不改血，只靠本通知结算
        if (!end && (buffEnum == BuffIds.HP_MODIFY || buffEnum == BuffIds.HP_MODIFY_RATIO
                || buffEnum == BuffIds.HP_MODIFY_BY_MAX_HP_RATIO)) {
            handlePeriodicHpBuff(battle, buffEnum, cfg, src, target, level, stacks,
                    targetBytes, creatorBytes, out);
            return;
        }

        // 反弹19：伤害打到 src（攻击方）；add_params=[skillId, mid]
        if (!end && buffEnum == BuffIds.DAMAGE_REBOUND) {
            handleReflectBuff(src, target, cfg, level, stacks, addParams, srcWjBytes, targetBytes, out);
            return;
        }

        // 吸血24：对 target 结算技能伤，再按表比例回血给 src
        if (!end && buffEnum == BuffIds.XI_XUE_RATIO) {
            if (phase == 2) {
                return;
            }
            handleXiXueBuff(src, target, cfg, level, stacks, addParams, srcWjBytes, targetBytes, out);
            return;
        }

        // 装备吸收55：phase1 写入 XiShouLv；phase2 end=false 还原（对齐 EquipXiShou OnPostEvent）
        if (buffEnum == BuffIds.EQUIP_XI_SHOU) {
            if (end || phase == 2) {
                target.applyBuff(buffEnum, buffId, stacks, true, 0f);
            } else {
                float v = param > 0f ? param : tableVal;
                if (v <= 0f && buffId >= 10000) {
                    FightConfigTables.EquipEffectCfg ee = fightCfg.equipEffect(buffId);
                    if (ee != null) {
                        v = ee.param2;
                    }
                }
                target.applyBuff(buffEnum, buffId, stacks, false, v);
            }
            return;
        }

        // 分摊34：伤打挂 Buff 的 unit(target) 与创作者 creator；add_params=[skillId,mid,flag]
        if (!end && buffEnum == BuffIds.DAMAGE_SHARE) {
            handleDamageShareBuff(src, target, creator, cfg, stacks, addParams,
                    srcWjBytes, targetBytes, creatorBytes, out);
            return;
        }

        // 技能替换49：客户端本地改技能；服端记覆盖 ID，Hurt 里 skillId 用新 ID 时等级对齐槽位
        if (buffEnum == BuffIds.SKILL_CHANGE) {
            String raw = cfg != null ? cfg.skillChangeRaw : "0";
            target.applySkillChange(raw, end);
            target.applyBuff(buffEnum, buffId, stacks, end, 0f);
            return;
        }

        // AOE40：伤害目标在 srcWJ（周围敌人）；持有者在 targetWJ；攻来自 creator
        if (!end && buffEnum == BuffIds.AOE_DAMAGE) {
            if (creator == null) {
                log.warn("aoe DROP creator=null buffId={}", buffId);
                return;
            }
            handleAoeDamageBuff(eventSrc, creator, cfg, level, stacks, srcWjBytes, out);
            return;
        }

        // 爆头57：phase1 目标 XiShouLv=0；随后有独立 Hurt 包
        if (buffEnum == BuffIds.BAO_TOU) {
            if (end || phase == 2) {
                if (src != null) {
                    src.tempXiShouOverride = -1f;
                }
                // Notify 的 srcWJ=受击者 unit
                FightUnit victim = src != null ? src : target;
                victim.tempXiShouOverride = -1f;
            } else {
                FightUnit victim = src != null ? src : target;
                victim.tempXiShouOverride = 0f;
            }
            return;
        }

        // 致伤60：Notify(tar=持有者=攻方)；phase1 覆盖攻方纯伤；phase2 还原
        if (buffEnum == BuffIds.ZHI_SHANG) {
            if (end || phase == 2) {
                target.tempChunCuiOverride = -1f;
            } else {
                float v = param > 0f ? param : target.chunCuiDamageRatio;
                if (v <= 0f && buffId >= 10000) {
                    FightConfigTables.EquipEffectCfg ee = fightCfg.equipEffect(buffId);
                    if (ee != null) {
                        v = ee.param1Ratio();
                    }
                }
                if (v > 0f) {
                    target.tempChunCuiOverride = v;
                }
            }
            return;
        }

        // Buff6/7/65：phase1 登记；phase2 累加；end 清零。属性变不改血 → 不推 HP（防多余飘字/覆盖）
        if (buffEnum == BuffIds.ATTACK) {
            target.applyAttackFlatTick(param, stacks, phase, end);
            return;
        }
        if (buffEnum == BuffIds.ATTACK_RATIO) {
            target.applyAttackRatioTick(param, stacks, phase, end);
            return;
        }
        if (buffEnum == BuffIds.DODGE_RATE) {
            target.applyDodgeTick(param, stacks, phase, end);
            return;
        }

        // Buff4/5：对齐 EquipMaxHp / MaxHpRatio
        // Buff4 ReEnter：phase2 end=false 先还原，再 phase1 Enter（IsServerCal 客户端本地不改 max）
        if (buffEnum == BuffIds.MAX_HP || buffEnum == BuffIds.MAX_HP_RATIO) {
            if (!end && buffEnum == BuffIds.MAX_HP && phase == 2) {
                int maxBefore = target.maxHp;
                target.applyBuff(buffEnum, buffId, stacks, true, 0f);
                target.syncHpAfterMaxChange(target.maxHp - maxBefore);
                out.add(s2cItemHp(fightHpWj(rebuildWj(target, targetBytes), null, target.curHp, target.maxHp)));
                return;
            }
            float applyVal = param;
            if (!end && buffEnum == BuffIds.MAX_HP_RATIO) {
                // APK Enter：delta = ratio × 当时 maxHp × stacks（相对当前 max，非开战 base）
                int st = Math.max(1, stacks);
                if (target.buffs.containsKey(Integer.valueOf(BuffIds.MAX_HP_RATIO))) {
                    int maxBefore = target.maxHp;
                    target.applyBuff(BuffIds.MAX_HP_RATIO, buffId, stacks, true, 0f);
                    target.syncHpAfterMaxChange(target.maxHp - maxBefore);
                }
                applyVal = (int) (param * target.maxHp) * st;
            }
            int maxBefore = target.maxHp;
            target.applyBuff(buffEnum, buffId, stacks, end, applyVal);
            target.syncHpAfterMaxChange(target.maxHp - maxBefore);
            out.add(s2cItemHp(fightHpWj(rebuildWj(target, targetBytes), null, target.curHp, target.maxHp)));
            return;
        }

        if (!end && (buffEnum == BuffIds.PHYSICS_ATTACK_ABSORB
                || buffEnum == BuffIds.PHYSICS_ABSORB_AND_REBOUND)) {
            if (phase == 3) {
                handleAbsorbRebound(src, target, true, srcWjBytes, targetBytes, out);
                target.lastAbsorbedPhy = 0f;
                return;
            }
            float amt = absorbAmount(param, cfg, src, target, level, 1, buffEnum);
            if (amt <= 0f) {
                log.warn("buff absorb skip phy buffId={} param={} stacks={}", buffId, param, stacks);
                return;
            }
            target.addAbsorb(1, amt * Math.max(1, stacks), buffId);
            if (cfg != null && buffEnum == BuffIds.PHYSICS_ABSORB_AND_REBOUND) {
                target.absorbReboundRatioPhy = FightConfigTables.BuffCfg.scaled(
                        cfg.phyReboundRatioBase, cfg.phyReboundRatioGrow, level);
            }
            appendAbsorbSync(target, targetBytes, out, false, false, false);
        } else if (!end && (buffEnum == BuffIds.MAGIC_ATTACK_ABSORB
                || buffEnum == BuffIds.MAGIC_ABSORB_AND_REBOUND)) {
            if (phase == 3) {
                handleAbsorbRebound(src, target, false, srcWjBytes, targetBytes, out);
                target.lastAbsorbedMag = 0f;
                return;
            }
            float amt = absorbAmount(param, cfg, src, target, level, 2, buffEnum);
            if (amt <= 0f) {
                log.warn("buff absorb skip mag buffId={} param={} stacks={}", buffId, param, stacks);
                return;
            }
            target.addAbsorb(2, amt * Math.max(1, stacks), buffId);
            if (cfg != null && buffEnum == BuffIds.MAGIC_ABSORB_AND_REBOUND) {
                target.absorbReboundRatioMag = FightConfigTables.BuffCfg.scaled(
                        cfg.magReboundRatioBase, cfg.magReboundRatioGrow, level);
            }
            appendAbsorbSync(target, targetBytes, out, false, false, false);
        } else if (!end && buffEnum == BuffIds.ALL_ATTACK_ABSORB) {
            float amt = absorbAmount(param, cfg, src, target, level, 3, buffEnum);
            if (amt <= 0f) {
                log.warn("buff absorb skip all buffId={} param={} stacks={}", buffId, param, stacks);
                return;
            }
            target.addAbsorb(3, amt * Math.max(1, stacks), buffId);
            appendAbsorbSync(target, targetBytes, out, false, false, false);
        } else {
            target.applyBuff(buffEnum, buffId, stacks, end, param);
            // 8/9/10/13 等纯属性：客户端本地已改 FightAttr，血未变 → 不推 HP
            if (end && (buffEnum == BuffIds.PHYSICS_ATTACK_ABSORB || buffEnum == BuffIds.MAGIC_ATTACK_ABSORB
                    || buffEnum == BuffIds.ALL_ATTACK_ABSORB
                    || buffEnum == BuffIds.PHYSICS_ABSORB_AND_REBOUND
                    || buffEnum == BuffIds.MAGIC_ABSORB_AND_REBOUND)) {
                int type = buffEnum == BuffIds.MAGIC_ATTACK_ABSORB || buffEnum == BuffIds.MAGIC_ABSORB_AND_REBOUND ? 2
                        : (buffEnum == BuffIds.ALL_ATTACK_ABSORB ? 3 : 1);
                // 对齐 Remove*AbsorbElm(this)：只摘一条，禁止清整 type
                target.removeOneAbsorb(type, buffId);
                boolean emptyType = !hasAbsorbType(target, type);
                appendAbsorbSync(target, targetBytes, out,
                        type == 1 && emptyType, type == 2 && emptyType, type == 3 && emptyType);
            }
        }
    }

    private static boolean hasAbsorbType(FightUnit u, int type) {
        for (FightUnit.AbsorbElm e : u.absorbs) {
            if (e.type == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * Buff63：Enter 登记；phase2(add_params=skillType) 覆盖伤比；phase3/4/end 还原。
     * 伤比来自 NewBuffProperty 技能类型串，禁止占位默认。
     */
    private void handleSkillTypeDamageRatio(FightUnit target, FightConfigTables.BuffCfg cfg,
                                            int phase, boolean end, List<Integer> addParams) {
        if (end || phase == 3 || phase == 4) {
            target.tempDamageRatioOverride = 0f;
            target.applyBuff(BuffIds.DAMAGE_RATIO_BY_SKILL, 0, 1, true, 0f);
            return;
        }
        if (phase == 2 && addParams != null && !addParams.isEmpty() && cfg != null) {
            int skillType = addParams.get(0).intValue();
            float ratio = cfg.skillTypeDamageRatio(skillType);
            if (ratio > 0f) {
                target.tempDamageRatioOverride = ratio;
            }
            target.applyBuff(BuffIds.DAMAGE_RATIO_BY_SKILL, cfg.id, 1, false, ratio);
            return;
        }
        // phase1 Enter：只登记存在，数值等 phase2
        if (cfg != null) {
            target.applyBuff(BuffIds.DAMAGE_RATIO_BY_SKILL, cfg.id, 1, false, 0f);
        }
    }

    /**
     * Buff1/2/3 周期跳：对齐 EquipContinuousDamage / CurHpPeriodic / MaxHpPeriodic。
     * creator/src = 施法来源（攻）、target = 挂 Buff 单位。
     */
    private void handlePeriodicHpBuff(Battle battle, int buffEnum, FightConfigTables.BuffCfg cfg,
                                      FightUnit src, FightUnit target, int level, int stacks,
                                      byte[] targetBytes, byte[] creatorBytes, List<byte[]> out) {
        if (target == null || cfg == null) {
            return;
        }
        FightUnit atk = src;
        if (atk == null && creatorBytes != null) {
            atk = resolveAndBind(battle, creatorBytes);
        }
        int st = Math.max(1, stacks);
        int delta = 0;
        if (buffEnum == BuffIds.HP_MODIFY) {
            // (atk*atkRatio + base + grow*(lv-1)) * damageRatio * (1+damagePlusRatio) → int
            float atkVal = atk != null ? atk.attackValue() : 0f;
            float raw = atkVal * cfg.atkRatio
                    + FightConfigTables.BuffCfg.scaled(cfg.hpModifyBase, cfg.hpModifyGrow, level);
            float dmgR = atk != null ? atk.damageRatio() : 1f;
            float plusR = atk != null ? atk.damagePlusRatio() : 0f;
            int tick = (int) (raw * dmgR * (plusR + 1f));
            if (tick > 0) {
                float healAddR = atk != null ? atk.healAddRatio() : 0f;
                float healAddV = atk != null ? atk.healAddValue() : 0f;
                delta = (int) (tick * (1f + healAddR) + healAddV) * st;
            } else if (tick < 0) {
                delta = tick * st;
            }
        } else if (buffEnum == BuffIds.HP_MODIFY_RATIO) {
            float v = FightConfigTables.BuffCfg.scaled(cfg.curHpRatioBase, cfg.curHpRatioGrow, level);
            delta = (int) (target.curHp * v * st);
        } else if (buffEnum == BuffIds.HP_MODIFY_BY_MAX_HP_RATIO) {
            float v = FightConfigTables.BuffCfg.scaled(cfg.maxHpPeriodicBase, cfg.maxHpPeriodicGrow, level);
            delta = (int) (target.maxHp * v * st);
        }
        if (delta > 0) {
            target.applyHeal(delta);
        } else if (delta < 0) {
            target.applyDamage(-delta);
        } else {
            return;
        }
        out.add(s2cItemHp(fightHpWj(rebuildWj(target, targetBytes), creatorBytes, target.curHp, target.maxHp)));
        log.info("buffTick enum={} tgt={}:{} delta={} hp={}/{}",
                buffEnum, target.playerGuid, target.wjId, delta, target.curHp, target.maxHp);
    }

    /** Buff19 反弹：对齐 EquipDamageReflect — 伤害打到攻击者(src)。 */
    private void handleReflectBuff(FightUnit attacker, FightUnit defender, FightConfigTables.BuffCfg cfg,
                                   int level, int stacks, List<Integer> addParams,
                                   byte[] attackerBytes, byte[] defenderBytes, List<byte[]> out) {
        if (attacker == null || defender == null) {
            return;
        }
        int skillId = addParams != null && !addParams.isEmpty() ? addParams.get(0).intValue() : 0;
        int mid = addParams != null && addParams.size() > 1 ? addParams.get(1).intValue() : 0;
        CultivateTables.SkillProp skill = cultivate.skill(skillId);
        int skLv = 1;
        if (attacker != null) {
            skLv = attacker.skillLevelOf(skillId);
        }
        float skillDmg = DamageFormula.skillDamageValue(skill, attacker, skLv);
        float prop = DamageFormula.proportionOf(skill, mid, fightCfg);
        // 对齐 IsServerCal GetDamage：算伤可扣盾量，但不消耗盾（后续 Hurt 再耗）
        DamageFormula.Result dr = DamageFormula.damage(attacker, defender, skill, skillDmg, 0, prop, false);
        float ratio = cfg != null
                ? FightConfigTables.BuffCfg.scaled(cfg.reflectBase, cfg.reflectGrow, level) : 0f;
        int reflect = Math.max(0, Math.round(dr.damage * ratio * Math.max(1, stacks)));
        if (reflect <= 0) {
            return;
        }
        attacker.applyDamage(reflect);
        out.add(s2cItemHp(fightHpWj(rebuildWj(attacker, attackerBytes), defenderBytes,
                attacker.curHp, attacker.maxHp)));
        log.info("buffReflect atk={}:{} dmg={} ratio={} hp={}/{}",
                attacker.playerGuid, attacker.wjId, reflect, ratio, attacker.curHp, attacker.maxHp);
    }

    /** Buff24 吸血：对齐 XiXueBuffElement — 先伤目标再按比例回血攻方。
     * add_params=[skillId, mid, baoTou?, zhiShang?, skillType]。 */
    private void handleXiXueBuff(FightUnit attacker, FightUnit victim, FightConfigTables.BuffCfg cfg,
                                 int level, int stacks, List<Integer> addParams,
                                 byte[] attackerBytes, byte[] victimBytes, List<byte[]> out) {
        if (attacker == null || victim == null) {
            return;
        }
        int skillId = addParams != null && !addParams.isEmpty() ? addParams.get(0).intValue() : 0;
        int mid = addParams != null && addParams.size() > 1 ? addParams.get(1).intValue() : 0;
        boolean baoTou = addParams != null && addParams.size() > 2 && addParams.get(2).intValue() != 0;
        boolean zhiShang = addParams != null && addParams.size() > 3 && addParams.get(3).intValue() != 0;
        int skillType = addParams != null && addParams.size() > 4 ? addParams.get(4).intValue() : 0;
        if (baoTou) {
            victim.tempXiShouOverride = 0f;
        }
        if (zhiShang && attacker.chunCuiDamageRatio > 0f) {
            attacker.tempChunCuiOverride = attacker.chunCuiDamageRatio;
        }
        if (skillType > 0) {
            FightUnit.BuffStack st = attacker.buffs.get(Integer.valueOf(BuffIds.DAMAGE_RATIO_BY_SKILL));
            if (st != null) {
                FightConfigTables.BuffCfg skCfg = fightCfg.buff(st.buffId);
                if (skCfg != null) {
                    float ratio = skCfg.skillTypeDamageRatio(skillType);
                    if (ratio > 0f) {
                        attacker.tempDamageRatioOverride = ratio;
                    }
                }
            }
        }
        CultivateTables.SkillProp skill = cultivate.skill(skillId);
        int skLv = 1;
        if (attacker != null) {
            skLv = attacker.skillLevelOf(skillId);
        }
        float skillDmg = DamageFormula.skillDamageValue(skill, attacker, skLv);
        float prop = DamageFormula.proportionOf(skill, mid, fightCfg);
        DamageFormula.Result dr = DamageFormula.damage(attacker, victim, skill, skillDmg, 0, prop);
        int dmg = Math.max(0, Math.round(dr.damage));
        int applied = victim.applyDamage(dmg);
        boolean clearPhy = victim.lastAbsorbedPhy > 0f && victim.physicsAbsorbSum() <= 0f;
        boolean clearMag = victim.lastAbsorbedMag > 0f && victim.magicAbsorbSum() <= 0f;
        boolean clearAll = victim.lastAbsorbedAll > 0f && victim.allAbsorbSum() <= 0f;
        appendAbsorbSync(victim, victimBytes, out, clearPhy, clearMag, clearAll);
        out.add(s2cItemHp(fightHpWj(rebuildWj(victim, victimBytes), attackerBytes, victim.curHp, victim.maxHp)));
        float xixue = cfg != null
                ? FightConfigTables.BuffCfg.scaled(cfg.xiXueBase, cfg.xiXueGrow, level) : 0f;
        // 对齐 XiXue：回血基数是 GetDamage×prop（num7），不是 ReduceHp 实扣
        int heal = Math.max(0, Math.round(dmg * xixue * Math.max(1, stacks)));
        if (heal > 0) {
            attacker.applyHeal(heal);
            out.add(s2cItemHp(fightHpWj(rebuildWj(attacker, attackerBytes), victimBytes,
                    attacker.curHp, attacker.maxHp)));
        }
        if (baoTou) {
            victim.tempXiShouOverride = -1f;
        }
        if (zhiShang) {
            attacker.tempChunCuiOverride = -1f;
        }
        if (skillType > 0) {
            attacker.tempDamageRatioOverride = 0f;
        }
        log.info("buffXiXue {}->{} dmg={} applied={} heal={} xixue={}",
                attacker.wjId, victim.wjId, dmg, applied, heal, xixue);
    }

    /**
     * Buff40 AOE：Notify(tar=持有者, src=周围敌人)；伤害打在 src。
     * 量对齐 AoeDamageBuffElement.SetLevel：srcUnit 攻 × atkRatio + base + grow*(lv-1)。
     */
    /**
     * Buff34 分摊：对齐 EquipDamageShareBuffElement。
     * total = GetDamage(user→unit)*prop；share=total*ratio→creator；remain→unit。
     * Notify(tar=unit, src=user)；creatorWJ=mSrcUnit。
     */
    private void handleDamageShareBuff(FightUnit attacker, FightUnit victim, FightUnit shareTo,
                                       FightConfigTables.BuffCfg cfg, int stacks, List<Integer> addParams,
                                       byte[] attackerBytes, byte[] victimBytes, byte[] shareBytes,
                                       List<byte[]> out) {
        if (attacker == null || victim == null) {
            return;
        }
        if (addParams == null || addParams.size() < 3) {
            log.warn("damageShare DROP: need add_params[skill,mid,flag] got={}",
                    addParams == null ? 0 : addParams.size());
            return;
        }
        int skillId = addParams.get(0).intValue();
        int mid = addParams.get(1).intValue();
        int flag = addParams.get(2).intValue();
        CultivateTables.SkillProp skill = cultivate.skill(skillId);
        int skLv = attacker.skillLevelOf(skillId);
        float skillDmg = DamageFormula.skillDamageValue(skill, attacker, skLv);
        float prop = DamageFormula.proportionOf(skill, mid, fightCfg);
        DamageFormula.Result dr = DamageFormula.damage(attacker, victim, skill, skillDmg, 0, prop);
        int total = Math.max(0, Math.round(dr.damage));
        float ratio = cfg != null ? cfg.damageShareRatio : 0f;
        if (ratio < 0f) {
            ratio = 0f;
        }
        if (ratio > 1f) {
            ratio = 1f;
        }
        int share = (int) (total * ratio);
        int remain = total - share;
        // flag：3=双方都伤到；1=仅分摊方；2=仅自己（对齐客户端 Hurt 返回）
        boolean hitShare = shareTo != null && !shareToIsDead(shareTo) && share > 0 && (flag == 1 || flag == 3);
        boolean hitSelf = remain > 0 && (flag == 2 || flag == 3);
        if (shareTo == null || shareToIsDead(shareTo)) {
            hitShare = false;
            hitSelf = total > 0;
            remain = total;
            share = 0;
        }
        if (hitShare) {
            shareTo.applyDamage(share);
            out.add(s2cItemHp(fightHpWj(rebuildWj(shareTo, shareBytes), attackerBytes,
                    shareTo.curHp, shareTo.maxHp)));
        }
        if (hitSelf) {
            victim.applyDamage(remain);
            boolean clearPhy = victim.lastAbsorbedPhy > 0f && victim.physicsAbsorbSum() <= 0f;
            boolean clearMag = victim.lastAbsorbedMag > 0f && victim.magicAbsorbSum() <= 0f;
            boolean clearAll = victim.lastAbsorbedAll > 0f && victim.allAbsorbSum() <= 0f;
            appendAbsorbSync(victim, victimBytes, out, clearPhy, clearMag, clearAll);
            out.add(s2cItemHp(fightHpWj(rebuildWj(victim, victimBytes), attackerBytes,
                    victim.curHp, victim.maxHp)));
        }
        log.info("buffShare atk={} victim={} shareTo={} total={} share={} remain={} flag={}",
                attacker.wjId, victim.wjId, shareTo != null ? shareTo.wjId : -1,
                total, share, remain, flag);
    }

    private static boolean shareToIsDead(FightUnit u) {
        return u == null || u.curHp <= 0;
    }

    /**
     * Buff20/21 phase3：对齐吸盾反弹 — 反弹量 = 本段吸收 × 反弹比，打到攻击者(src)。
     * Notify(tar=defender, src=attacker)。
     */
    private void handleAbsorbRebound(FightUnit attacker, FightUnit defender, boolean phy,
                                     byte[] attackerBytes, byte[] defenderBytes, List<byte[]> out) {
        if (attacker == null || defender == null) {
            return;
        }
        float absorbed = phy ? defender.lastAbsorbedPhy : defender.lastAbsorbedMag;
        float ratio = phy ? defender.absorbReboundRatioPhy : defender.absorbReboundRatioMag;
        int dmg = Math.max(0, Math.round(absorbed * ratio));
        if (dmg <= 0) {
            return;
        }
        attacker.applyDamage(dmg);
        out.add(s2cItemHp(fightHpWj(rebuildWj(attacker, attackerBytes), defenderBytes,
                attacker.curHp, attacker.maxHp)));
        log.info("buffAbsorbRebound phy={} atk={}:{} dmg={} absorbed={} ratio={}",
                phy, attacker.playerGuid, attacker.wjId, dmg, absorbed, ratio);
    }

    private void handleAoeDamageBuff(FightUnit aroundEnemy, FightUnit atkSrc,
                                     FightConfigTables.BuffCfg cfg, int level, int stacks,
                                     byte[] aroundBytes, List<byte[]> out) {
        if (aroundEnemy == null || cfg == null) {
            return;
        }
        float atkVal = atkSrc != null ? atkSrc.attackValue() : 0f;
        float raw = atkVal * cfg.atkRatio
                + FightConfigTables.BuffCfg.scaled(cfg.aoeDamageBase, cfg.aoeDamageGrow, level);
        int dmg = Math.max(0, Math.round(raw * Math.max(1, stacks)));
        if (dmg <= 0) {
            return;
        }
        aroundEnemy.applyDamage(dmg);
        out.add(s2cItemHp(fightHpWj(rebuildWj(aroundEnemy, aroundBytes), null,
                aroundEnemy.curHp, aroundEnemy.maxHp)));
        log.info("buffAoe tgt={}:{} dmg={} hp={}/{}",
                aroundEnemy.playerGuid, aroundEnemy.wjId, dmg, aroundEnemy.curHp, aroundEnemy.maxHp);
    }

    /**
     * 吸盾量：优先包内 add_params，再 Buff 表；禁止再发明 maxHp×比例兜底。
     * 15/16/67：{@code GetAttackValue()*atkRatio + base + grow}；
     * 20/21：仅 {@code base + grow}（无攻系数，对齐 AbsorbAndRebound/MagicShield SetLevel）。
     * @param kind 1物 2魔 3全
     */
    private float absorbAmount(float param, FightConfigTables.BuffCfg cfg,
                                     FightUnit src, FightUnit target, int level, int kind, int buffEnum) {
        if (param > 0f) {
            return param;
        }
        if (cfg == null) {
            return 0f;
        }
        // Buff20/21：吸盾量不乘攻
        if (buffEnum == BuffIds.PHYSICS_ABSORB_AND_REBOUND) {
            return FightConfigTables.BuffCfg.scaled(cfg.phyAbsorbReboundBase, cfg.phyAbsorbReboundGrow, level);
        }
        if (buffEnum == BuffIds.MAGIC_ABSORB_AND_REBOUND) {
            return FightConfigTables.BuffCfg.scaled(cfg.magAbsorbReboundBase, cfg.magAbsorbReboundGrow, level);
        }
        float atk = 0f;
        if (src != null) {
            atk = src.attackValue();
        } else {
            // 禁止用 target 攻顶 creator（挂盾在己身时会把被打方攻当施法源）
            log.warn("absorbAmount src=null kind={} → atk=0", kind);
        }
        if (kind == 1) {
            return cfg.absorbAmount(atk, cfg.phyAbsorbBase, cfg.phyAbsorbGrow, level);
        }
        if (kind == 2) {
            return cfg.absorbAmount(atk, cfg.magAbsorbBase, cfg.magAbsorbGrow, level);
        }
        return cfg.absorbAmount(atk, cfg.allAbsorbBase, cfg.allAbsorbGrow, level);
    }

    private float buffTableValue(FightConfigTables.BuffCfg cfg, int buffEnum, FightUnit src,
                                 int level, int buffId) {
        if (cfg != null) {
            switch (buffEnum) {
                case BuffIds.ATTACK:
                    return FightConfigTables.BuffCfg.scaled(cfg.attackBase, cfg.attackGrow, level);
                case BuffIds.ATTACK_RATIO:
                    return FightConfigTables.BuffCfg.scaled(cfg.attackRatioBase, cfg.attackRatioGrow, level);
                case BuffIds.PHYSICS_DEFENCE:
                    return FightConfigTables.BuffCfg.scaled(cfg.pdefBase, cfg.pdefGrow, level);
                case BuffIds.MAGIC_DEFENCE:
                    return FightConfigTables.BuffCfg.scaled(cfg.mdefBase, cfg.mdefGrow, level);
                case BuffIds.DAMAGE_RATIO:
                    return FightConfigTables.BuffCfg.scaled(cfg.damageRatioBase, cfg.damageRatioGrow, level);
                case BuffIds.CRIT_RATIO:
                    return FightConfigTables.BuffCfg.scaled(cfg.critRatioBase, cfg.critRatioGrow, level);
                case BuffIds.MAX_HP:
                    // 对齐 EquipMaxHp.SetLevel：mSrcUnit.GetAttackValue()
                    float atk = src != null ? src.attackValue() : 0f;
                    return atk * cfg.atkRatio + FightConfigTables.BuffCfg.scaled(cfg.maxHpBase, cfg.maxHpGrow, level);
                case BuffIds.MAX_HP_RATIO:
                    return FightConfigTables.BuffCfg.scaled(cfg.maxHpRatioBase, cfg.maxHpRatioGrow, level);
                case BuffIds.HEAL_ADD_RATIO:
                    return FightConfigTables.BuffCfg.scaled(cfg.healRatioBase, cfg.healRatioGrow, level);
                case BuffIds.HEAL_ADD_VALUE:
                    return FightConfigTables.BuffCfg.scaled(cfg.healValueBase, cfg.healValueGrow, level);
                case BuffIds.RECEIVE_HEAL:
                    return FightConfigTables.BuffCfg.scaled(cfg.receiveHealBase, cfg.receiveHealGrow, level);
                case BuffIds.PHYSICS_ABSORB_RATIO:
                    return FightConfigTables.BuffCfg.scaled(cfg.phyTakenRatioBase, cfg.phyTakenRatioGrow, level);
                case BuffIds.MAGIC_ABSORB_RATIO:
                    return FightConfigTables.BuffCfg.scaled(cfg.magTakenRatioBase, cfg.magTakenRatioGrow, level);
                case BuffIds.DEFENCE_REDUCE:
                    return FightConfigTables.BuffCfg.scaled(cfg.defenceReduceBase, cfg.defenceReduceGrow, level);
                case BuffIds.DAMAGE_REBOUND:
                    return FightConfigTables.BuffCfg.scaled(cfg.reflectBase, cfg.reflectGrow, level);
                case BuffIds.XI_XUE_RATIO:
                    return FightConfigTables.BuffCfg.scaled(cfg.xiXueBase, cfg.xiXueGrow, level);
                case BuffIds.DAMAGE_SHARE:
                    return cfg.damageShareRatio;
                case BuffIds.NO_DIE_HP:
                    return cfg.noDieHpValue;
                case BuffIds.NO_DIE_HP_RATIO:
                    return cfg.noDieHpRatio;
                case BuffIds.DODGE_RATE:
                    return FightConfigTables.BuffCfg.scaled(cfg.dodgeRateBase, cfg.dodgeRateGrow, level);
                case BuffIds.ALL_RESIST:
                    return FightConfigTables.BuffCfg.scaled(cfg.allResistBase, cfg.allResistGrow, level);
                case BuffIds.EQUIP_RESIST:
                    return FightConfigTables.BuffCfg.scaled(cfg.resistBase, cfg.resistGrow, level);
                default:
                    break;
            }
        }
        // 装备特效类 Buff：mResId=EquipBuffType(10000+)，数值在 EquipmentEffect 而非 NewBuffProperty
        if (buffId >= 10000) {
            FightConfigTables.EquipEffectCfg ee = fightCfg.equipEffect(buffId);
            if (ee == null) {
                return 0f;
            }
            switch (buffEnum) {
                case BuffIds.MAGIC_DAMAGE_REDUCE:
                    return ee.param1Ratio();
                case BuffIds.EQUIP_XI_SHOU:
                    // SetParams：param2 为 XiShouLv 绝对值
                    return ee.param2;
                default:
                    return 0f;
            }
        }
        return 0f;
    }

    private void handleAddSummon(Battle battle, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        Pb.Fields n = Pb.read(bytes);
        // Proto：1=creatorWJ 2=skillID 3=summonWJ（曾误读对调 → 召唤恒 lv1）
        byte[] creatorBytes = n.getBytes(1);
        int skillId = n.getInt(2, 0);
        byte[] summonBytes = n.getBytes(3);
        if (summonBytes == null || summonBytes.length == 0) {
            return;
        }
        Pb.Fields s = Pb.read(summonBytes);
        int playerGuid = s.getInt(1, 0);
        int playerServerId = s.getInt(2, 0);
        int wjId = s.getInt(3, 0);
        int monsterIndex = s.getInt(4, 0);
        String monsterOri = s.getString(5);
        int subId = s.getInt(6, 0);

        // SpawneredMonster：仅 OriName+subID → MonsterProperty.ini 表值，禁止武将计算器/占位
        if (wjId <= 0 && skillId <= 0 && monsterOri != null && !monsterOri.isEmpty()) {
            FightConfigTables.MonsterCfg mc = fightCfg.monsterByOri(monsterOri);
            if (mc == null) {
                log.warn("addSummon skip: monster miss ori={} sub={}", monsterOri, subId);
                return;
            }
            FightUnit built = FightUnit.fromMonster(playerGuid, mc);
            built.playerServerId = playerServerId;
            built.subId = subId;
            built.monsterIndex = monsterIndex > 0 ? monsterIndex : 0;
            built.monsterOri = monsterOri;
            applyResistRatios(built);
            battle.register(built);
            log.info("addSummon monster ori={} sub={} hp={}", monsterOri, subId, built.maxHp);
            return;
        }

        // 无 WJID 时回退技能表召唤配置（对齐 SkillPropertyCfg.iSummonWujiangID）
        if (wjId <= 0 && skillId > 0) {
            CultivateTables.SkillProp sk = cultivate.skill(skillId);
            if (sk != null && sk.summonWujiangId > 0) {
                wjId = sk.summonWujiangId;
            }
        }
        if (wjId <= 0) {
            log.warn("addSummon skip: no wjId skill={} ori={}", skillId, monsterOri);
            return;
        }

        // 等级 = 施法者该技能等级（Player.SummonWujiang → CreateUnit(GetSkillLevel)）
        int level = 1;
        FightUnit creator = creatorBytes != null && creatorBytes.length > 0
                ? resolveAndBind(battle, creatorBytes) : null;
        if (creator != null && creator.stats != null && skillId > 0) {
            level = creator.stats.skillLevelOf(skillId);
        }

        // 召唤体：对齐 WuJiangInfo(level,index) 技能槽默认 0（非 1）
        CultivateTables.CombatStats st = cultivate.computeCombatStats(wjId, level, 0, 0, 0, 0, 0, 0);
        if (st == null) {
            log.warn("addSummon skip: no stats wj={} lv={} skill={}", wjId, level, skillId);
            return;
        }
        FightUnit built = FightUnit.fromStats(playerGuid, st);
        built.playerServerId = playerServerId;
        built.subId = subId;
        // -1 是客户端「未同步」哨兵，不是有效 MonsterIndex
        built.monsterIndex = monsterIndex > 0 ? monsterIndex : 0;
        built.monsterOri = monsterOri != null ? monsterOri : "";
        applyResistRatios(built);
        battle.register(built);
        log.info("addSummon skill={} wj={} lv={} sub={} hp={}", skillId, wjId, level, subId, built.maxHp);
    }

    /** 开战/召唤：灌入 GlobalSetup 韧性折算与能量穿透。 */
    private void applyResistRatios(FightUnit u) {
        if (u == null) {
            return;
        }
        u.resistBaoJiRatio = cultivate.resistBaoJiRatio();
        u.resistZhiShangRatio = cultivate.resistZhiShangRatio();
        // 面板 Penetrate 当前表/计算器恒 0；BuffsPenetrate 亦无写入源 → 结果为 0，与 APK 一致
        float pen = u.stats != null ? u.stats.penetrate : 0f;
        u.nengLiangPenetrate = cultivate.nengLiangToMoMianPenetrate(pen);
    }

    private void handleDelSummon(Battle battle, byte[] bytes, List<byte[]> out) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        FightUnit u = resolveAndBind(battle, bytes);
        if (u == null) {
            return;
        }
        u.curHp = 0;
        out.add(s2cItemHp(fightHpWj(rebuildWj(u, bytes), null, 0, u.maxHp)));
    }

    private void handleBackupCure(Battle battle, byte[] bytes, List<byte[]> out) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        FightUnit u = resolveAndBind(battle, bytes);
        if (u == null || u.maxHp <= 0) {
            return;
        }
        // APK BattleController：每 3s，若 cur/max < LimitRatio，则回 mInBackUpHpRestore*maxHp，且不超过 Limit
        float curRatio = (float) u.curHp / (float) u.maxHp;
        float limit = cultivate.changePioneerHpRestoreLimitRatio();
        if (curRatio >= limit) {
            return;
        }
        CultivateTables.HeroCfg hero = cultivate.heroByIndex(u.wjId);
        float healRatio = hero != null ? hero.inBackUpHpRestore : 0f;
        if (healRatio <= 0f) {
            return;
        }
        if (healRatio + curRatio > limit) {
            healRatio = limit - curRatio;
        }
        // 客户端：(int)(ratio * maxHp)，截断不四舍五入
        int heal = (int) (healRatio * (float) u.maxHp);
        if (heal <= 0) {
            return;
        }
        u.curHp = Math.min(u.maxHp, u.curHp + heal);
        out.add(s2cItemHp(fightHpWj(rebuildWj(u, bytes), null, u.curHp, u.maxHp)));
    }

    private FightUnit resolveAndBind(Battle battle, byte[] wjBytes) {
        if (wjBytes == null || wjBytes.length == 0) {
            return null;
        }
        Pb.Fields wj = Pb.read(wjBytes);
        int subId = wj.getInt(6, 0);
        int guid = wj.getInt(1, 0);
        int wjId = wj.getInt(3, 0);
        int monster = wj.getInt(4, 0);
        String ori = wj.getString(5);
        if (subId != 0) {
            FightUnit bySub = battle.bySubId.get(Integer.valueOf(subId));
            if (bySub != null) {
                return bySub;
            }
        }
        String key;
        // 客户端未同步怪会带 MonsterIndex=-1 且写 subID；-1 不是有效同步号
        if (monster > 0) {
            key = "m:" + monster;
        } else if (ori != null && !ori.isEmpty() && wjId == 0) {
            key = "mo:" + ori + ":" + subId;
        } else if (subId != 0) {
            key = guid + ":" + wjId + ":s" + subId;
        } else {
            key = guid + ":" + wjId;
        }
        FightUnit u = battle.byIdentity.get(key);
        // 开战阵容常以 guid:wjId 注册（subId 稍后绑定）
        if (u == null && subId != 0 && monster <= 0 && (ori == null || ori.isEmpty() || wjId != 0)) {
            u = battle.byIdentity.get(guid + ":" + wjId);
        }
        if (u == null) {
            // 未开战注册 / 未走 AddSummon：禁止再发明 lv30 stub，暴露缺 beginBattle 或漏召唤
            log.warn("resolve miss key={} guid={} wj={} sub={} monster={} ori={} units={}",
                    key, guid, wjId, subId, monster, ori, battle.byIdentity.size());
            return null;
        }
        if (subId != 0) {
            u.subId = subId;
            battle.bySubId.put(Integer.valueOf(subId), u);
        }
        u.playerGuid = guid != 0 ? guid : u.playerGuid;
        u.wjId = wjId != 0 ? wjId : u.wjId;
        if (monster > 0) {
            u.monsterIndex = monster;
        }
        if (ori != null && !ori.isEmpty()) {
            u.monsterOri = ori;
        }
        return u;
    }

    /**
     * 推吸盾同步。index 为各 type 内 0 基下标（对齐 SetXxxAbsorbElm）。
     * 耗尽槽保留在列表里并推 curHP=0，直到 BuffEnd 摘槽；列表已空时才用 clear* 补推 index=0。
     */
    private void appendAbsorbSync(FightUnit u, byte[] ownerBytes, List<byte[]> out,
                                  boolean clearPhy, boolean clearMag, boolean clearAll) {
        if (u == null) {
            return;
        }
        if (u.absorbs != null && !u.absorbs.isEmpty()) {
            out.add(s2cItemAbsorb(absorbData(u, ownerBytes)));
            return;
        }
        if (!clearPhy && !clearMag && !clearAll) {
            return;
        }
        out.add(s2cItemAbsorb(absorbClearData(u, ownerBytes, clearPhy, clearMag, clearAll)));
    }

    private static byte[] absorbData(FightUnit u, byte[] ownerBytes) {
        return Pb.write(out -> {
            Pb.bytes(out, 1, rebuildWj(u, ownerBytes));
            for (FightUnit.AbsorbElm e : u.absorbs) {
                Pb.bytes(out, 2, Pb.write(el -> {
                    Pb.int32Always(el, 1, e.type);
                    Pb.int32Always(el, 2, e.index);
                    Pb.int32Always(el, 3, Math.round(e.curHp));
                }));
            }
        });
    }

    private static byte[] absorbClearData(FightUnit u, byte[] ownerBytes,
                                          boolean clearPhy, boolean clearMag, boolean clearAll) {
        return Pb.write(out -> {
            Pb.bytes(out, 1, rebuildWj(u, ownerBytes));
            if (clearPhy) {
                Pb.bytes(out, 2, Pb.write(el -> {
                    Pb.int32Always(el, 1, 1);
                    Pb.int32Always(el, 2, 0);
                    Pb.int32Always(el, 3, 0);
                }));
            }
            if (clearMag) {
                Pb.bytes(out, 2, Pb.write(el -> {
                    Pb.int32Always(el, 1, 2);
                    Pb.int32Always(el, 2, 0);
                    Pb.int32Always(el, 3, 0);
                }));
            }
            if (clearAll) {
                Pb.bytes(out, 2, Pb.write(el -> {
                    Pb.int32Always(el, 1, 3);
                    Pb.int32Always(el, 2, 0);
                    Pb.int32Always(el, 3, 0);
                }));
            }
        });
    }

    private static String wjIdentity(byte[] wjBytes) {
        if (wjBytes == null || wjBytes.length == 0) {
            return "empty";
        }
        Pb.Fields wj = Pb.read(wjBytes);
        return wj.getInt(1, 0) + ":" + wj.getInt(3, 0) + "#sub=" + wj.getInt(6, 0);
    }

    private static byte[] rebuildWj(FightUnit u, byte[] fallback) {
        if (u == null && fallback != null) {
            return fallback;
        }
        if (u == null) {
            return new byte[0];
        }
        return Pb.write(out -> {
            Pb.int32(out, 1, u.playerGuid);
            Pb.int32(out, 2, u.playerServerId);
            Pb.int32(out, 3, u.wjId);
            Pb.int32(out, 4, u.monsterIndex);
            Pb.string(out, 5, u.monsterOri);
            Pb.int32Always(out, 6, u.subId);
        });
    }

    private static byte[] fightHpWj(byte[] syncWj, byte[] effectByWj, int curHp, int maxHp) {
        return Pb.write(out -> {
            Pb.bytes(out, 1, syncWj);
            if (effectByWj != null && effectByWj.length > 0) {
                Pb.bytes(out, 2, effectByWj);
            }
            Pb.int32Always(out, 3, curHp);
            Pb.int32Always(out, 4, maxHp);
        });
    }

    private static byte[] s2cItemHp(byte[] wjHpSync) {
        return Pb.write(out -> Pb.bytesAlways(out, 1, wjHpSync));
    }

    private static byte[] s2cItemAbsorb(byte[] absorb) {
        return Pb.write(out -> Pb.bytesAlways(out, 2, absorb));
    }

    private static byte[] fightS2CPack(List<byte[]> syncItems) {
        return Pb.write(out -> {
            for (byte[] it : syncItems) {
                Pb.bytesAlways(out, 1, it);
            }
        });
    }

    private static String accountKey(GameSession session) {
        if (session.player() != null) {
            return session.player().account;
        }
        return session.account();
    }

    private static final class Battle {
        final Map<String, FightUnit> byIdentity = new ConcurrentHashMap<>();
        final Map<Integer, FightUnit> bySubId = new ConcurrentHashMap<>();
        int syncItems;
        int syncHurt;
        int syncCure;
        int syncBuffUpdate;
        int syncBuffEnd;
        int syncSkillCast;
        int syncCastOnly;

        void register(FightUnit u) {
            byIdentity.put(u.identityKey(), u);
            if (u.subId != 0) {
                bySubId.put(Integer.valueOf(u.subId), u);
            }
        }

        void noteSyncFields(byte[] hurt, byte[] cure, byte[] buffU, byte[] buffE, byte[] cast) {
            syncItems++;
            boolean h = hurt != null && hurt.length > 0;
            boolean c = cure != null && cure.length > 0;
            boolean bu = buffU != null && buffU.length > 0;
            boolean be = buffE != null && buffE.length > 0;
            boolean sc = cast != null && cast.length > 0;
            if (h) {
                syncHurt++;
            }
            if (c) {
                syncCure++;
            }
            if (bu) {
                syncBuffUpdate++;
            }
            if (be) {
                syncBuffEnd++;
            }
            if (sc) {
                syncSkillCast++;
            }
            if (sc && !h && !c && !bu && !be) {
                syncCastOnly++;
            }
        }
    }
}
