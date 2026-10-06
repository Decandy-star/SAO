package com.sao.fakeserver.service;

import com.sao.fakeserver.fight.FightRosterBuilder;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.table.FightConfigTables;
import com.sao.fakeserver.table.GameTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 挑战赛 BOB：机器人 10 连战。协议权威 Client_real.dll / NetProto.CCMsgTiaoZhanSai*。
 * 开战地图 RegionList type=7（PVP3）取第一档 region=10。
 * IsServerCal=false：本地结算；服端存 C2S 2006 残血并回 S2C 2202。
 * <p>
 * 目标战力（产品规划，相对己方 BOB 阵容战力，公式与武将详情同源 {@link CultivateTables#fightPowerOf}）：
 * 预赛 1–3 / 初赛 4–6 ≈ 2/3；复赛 7–9 ≈ 3/4；决赛 10 ≈ 1.0±5%。
 * 掉落：金币读表；英魄/时光石按 {@code sao-fake-server-reward-plan} 自主规划（确定性档位，见 PROTOCOL）。
 * 本轮锁定：targetGuids + lockedAtkSlots/lockedMyFp（重置才清）。
 */
@Service
public class BobService {
    private static final Logger log = LoggerFactory.getLogger(BobService.class);
    private static final int TARGET_COUNT = 10;
    private static final int REGION = 10;
    /** 刷新球；登录 detail 58 同源。 */
    private static final String ORI_REFRESH_BALL = "PY001";
    /** 时光石四色系：红/黄/蓝/绿（TS101–TS401）；档 1–7 → TS*0N。 */
    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final GameTables tables;
    private final ProgressService progress;
    private final CultivateTables cultivate;
    private final FightConfigTables fightCfg;
    private final EconomyTables economy;
    private final TaskService task;
    /** 懒缓存：每行 JJC_Robot 的真公式战力 [guid, fp]。 */
    private volatile List<int[]> robotFpCache;
    /** 懒缓存：捏造 NPC 的 npcTotalFightPower [playerId, fp]。 */
    private volatile List<int[]> npcFpCache;

    /** BOB 锁定目标：playerId 或 robot targetGuid。 */
    public static final class BobTargetEntry {
        public final int targetGuid;
        public final String name;
        public final int level;
        public final int resId;
        public final PlayerRecord npc;
        public final GameTables.RobotRow robot;

        BobTargetEntry(int targetGuid, String name, int level, int resId,
                       PlayerRecord npc, GameTables.RobotRow robot) {
            this.targetGuid = targetGuid;
            this.name = name == null ? "" : name;
            this.level = level;
            this.resId = resId;
            this.npc = npc;
            this.robot = robot;
        }

        static BobTargetEntry fromNpc(PlayerRecord p) {
            int res = p.mainHeroIndex > 0 ? p.mainHeroIndex : 18;
            if (p.heroes != null && !p.heroes.isEmpty() && p.heroes.get(0) != null) {
                res = p.heroes.get(0).heroIndex;
            }
            return new BobTargetEntry(p.playerId, p.roleName, p.level, res, p, null);
        }

        static BobTargetEntry fromRobot(GameTables.RobotRow r) {
            return new BobTargetEntry(r.targetGuid, r.name, r.level, r.resId, null, r);
        }

        int fightPower(CultivateTables cultivate, GameTables tables, FightConfigTables fightCfg) {
            if (npc != null) {
                return Math.max(1, npc.npcTotalFightPower);
            }
            if (robot != null) {
                return FightRosterBuilder.robotFightPower(robot, cultivate, tables, fightCfg);
            }
            return 1000;
        }
    }

    public BobService(PlayerStore store, PlayerDumpService dump, GameTables tables, ProgressService progress,
                      CultivateTables cultivate, FightConfigTables fightCfg, EconomyTables economy, TaskService task) {
        this.store = store;
        this.dump = dump;
        this.tables = tables;
        this.progress = progress;
        this.cultivate = cultivate;
        this.fightCfg = fightCfg;
        this.economy = economy;
        this.task = task;
    }

    public void onSelfInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        store.save(rec);
        session.send(MsgIds.S2C_BOB_SELF_INFO, pkt, dump.bobSelfInfo(rec));
        // 2205：日清后/进页同步已用重置次数（只改次数，不清进度）
        pushResetTimes(session, pkt, rec);
    }

    /** 登录/回城主动推：同步当日已用重置次数。 */
    public void pushResetTimes(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        store.save(rec);
        pushResetTimes(session, pkt, rec);
    }

    private void pushResetTimes(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_BOB_RESET_TIMES, pkt, dump.bobResetTimes(rec.bob.resetTimes));
    }

    public void onTargets(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        // 已锁定则复用；仅空/残缺时重抽（重置会清 targetGuids）
        List<BobTargetEntry> targets = resolveStoredTargets(rec);
        store.save(rec);
        session.send(MsgIds.S2C_BOB_TARGETS, pkt, dump.bobTargetsFromEntries(targets));
    }

    public void onAllHp(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        session.send(MsgIds.S2C_BOB_ALL_HP, pkt, dump.bobAllHp(rec));
    }

    public void onTargetBrief(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        BobTargetEntry target = currentTarget(rec);
        int guid = target == null ? 1 : target.targetGuid;
        int fp = target == null ? 1000 : target.fightPower(cultivate, tables, fightCfg);
        rec.bob.lastTargetGuid = guid;
        store.save(rec);
        session.send(MsgIds.S2C_BOB_TARGET_BRIEF, pkt, dump.bobTargetBrief(guid, Math.max(1, fp)));
    }

    public void onFight(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        // 中途退出后须先重置；对齐客户端 IsCanBOBChallenge，拒硬发 2005
        if (rec.bob.forceExit) {
            log.info("{} bob fight denied: forceExit=true round={}", rec.account, rec.bob.roundNumber);
            return;
        }
        // 十连已打完（round=11）须重置，禁止再开战刷奖
        if (rec.bob.roundNumber >= 11) {
            log.info("{} bob fight denied: round={} finished", rec.account, rec.bob.roundNumber);
            return;
        }
        BobTargetEntry target = currentTarget(rec);
        int guid = target == null ? Math.max(1, rec.bob.lastTargetGuid) : target.targetGuid;
        rec.bob.lastTargetGuid = guid;
        rec.bob.awaitingResult = true;
        rec.currentRegionId = REGION;
        store.save(rec);
        if (target != null && target.npc != null) {
            session.send(MsgIds.S2C_BOB_TARGET_DETAIL, pkt, dump.bobTargetDetail(target.npc));
        } else {
            session.send(MsgIds.S2C_BOB_TARGET_DETAIL, pkt, dump.bobTargetDetail(guid));
        }
        session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(REGION));
        log.info("{} bob fight round={} target={}", rec.account, rec.bob.roundNumber, guid);
    }

    public void onResult(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        Pb.Fields body = Pb.read(pkt.body);
        boolean win = body.getBool(1);
        // 无 2005 / 已结算 / 中途退：拒领奖与推进（防硬刷 2006 IsWin）
        if (rec.bob.forceExit || !rec.bob.awaitingResult || rec.bob.roundNumber >= 11) {
            log.info("{} bob result denied win={} forceExit={} awaiting={} round={}",
                    rec.account, win, rec.bob.forceExit, rec.bob.awaitingResult, rec.bob.roundNumber);
            return;
        }
        rec.bob.awaitingResult = false;
        applyAttackerHp(rec, body.getBytesList(3));
        if (win) {
            // 仅 2009 中途退才 forceExit；胜清标记，避免败仗误写后卡住
            rec.bob.forceExit = false;
            int stage = Math.max(1, Math.min(10, rec.bob.roundNumber));
            int gold = tables.bobStageGold(rec.level, stage);
            // 养成曲线：十连胜英魄合计约 95，加四杯后一轮可换约 4–5 武将碎片（见 PROTOCOL）
            int yingPo = 4 + stage;
            List<GameTables.GoodsDrop> drops = stageDrops(stage);
            grantPrize(session, pkt, rec, gold, yingPo, drops);
            session.send(MsgIds.S2C_BOB_PRIZE_INFO, pkt, dump.bobPrize(gold, yingPo, drops));
            if (rec.bob.roundNumber < 11) {
                rec.bob.roundNumber++;
            }
            task.onDailyAction(session, pkt, rec, TaskService.DAILY_BOB, 1);
        }
        // 负：不写 forceExit（客户端败仗后仍可重打本关；中途关图才 2009）
        store.save(rec);
        session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(win ? 1 : 2, win ? 3 : 0));
        log.info("{} bob result win={} round={} hpEntries={}",
                rec.account, win, rec.bob.roundNumber, rec.bob.allHp.size());
    }

    public void onReset(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        int max = economy.bobResetMaxCount(rec.economy.chargedDiamond);
        if (rec.bob.resetTimes >= max) {
            log.info("{} bob reset denied times={} max={}", rec.account, rec.bob.resetTimes, max);
            // 禁止回 2206：客户端 OnResetTiaoZhanSaiRet 会清 round/杯/残血
            pushResetTimes(session, pkt, rec);
            return;
        }
        rec.bob.roundNumber = 1;
        rec.bob.forceExit = false;
        rec.bob.awaitingResult = false;
        rec.bob.resetTimes++;
        rec.bob.lastTargetGuid = 0;
        rec.bob.cupPrized = new boolean[4];
        rec.bob.allHp = new ArrayList<>();
        rec.bob.targetGuids = new ArrayList<>();
        rec.bob.lockedAtkSlots = new ArrayList<>();
        rec.bob.lockedMyFp = 0;
        store.save(rec);
        // 2206：成功重置；客户端清进度并把 mResetBOBTimes 写成回包次数
        session.send(MsgIds.S2C_BOB_RESET_RET, pkt, dump.bobResetTimes(rec.bob.resetTimes));
        log.info("{} bob reset times={}/{}", rec.account, rec.bob.resetTimes, max);
    }

    public void onPrizeCup(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        int cup = Pb.read(pkt.body).getInt(1, 0);
        int idx = Math.max(0, Math.min(3, cup));
        if (rec.bob.cupPrized[idx]) {
            log.info("{} bob cup={} already prized, skip", rec.account, idx);
            return;
        }
        int gold = tables.bobCupGold(rec.level, idx);
        int yingPo = 20 + idx * 8;
        List<GameTables.GoodsDrop> drops = cupDrops(idx);
        rec.bob.cupPrized[idx] = true;
        grantPrize(session, pkt, rec, gold, yingPo, drops);
        store.save(rec);
        session.send(MsgIds.S2C_BOB_PRIZE_CUP, pkt, dump.bobPrize(gold, yingPo, drops));
        log.info("{} bob cup={} gold={} yingPo={}", rec.account, idx, gold, yingPo);
    }

    public void onForceExit(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        rec.bob.forceExit = true;
        rec.bob.awaitingResult = false;
        store.save(rec);
    }

    /**
     * 冒烟（无 Session）：抽 10 目标（优先 NPC）→ 2204 含 WJ → 主机 BOB 阵 vs NPC JJC 防可开战。
     * @return null=通过；非空=失败原因
     */
    public String smokeVerify(PlayerRecord host) {
        if (host == null) {
            return "host null";
        }
        host.ensureCollections();
        if (host.level < 20) {
            host.level = 20;
        }
        // 保证 BOB 攻阵
        if (FightRosterBuilder.fromPlayer(host, cultivate, fightCfg, PlayerRecord.FORMATION_BOB_ATK).isEmpty()) {
            List<String> slots = host.formationSlots(PlayerRecord.FORMATION_JJC_DEF);
            if (slots.isEmpty()) {
                slots = host.formationSlots(PlayerRecord.FORMATION_PVE);
            }
            if (slots.isEmpty() && host.heroes != null) {
                slots = new ArrayList<>();
                for (PlayerRecord.Hero h : host.heroes) {
                    if (h != null && h.id != null) {
                        slots.add(h.id);
                    }
                    if (slots.size() >= 5) {
                        break;
                    }
                }
            }
            host.setFormationSlots(PlayerRecord.FORMATION_BOB_ATK, new ArrayList<>(slots));
        }
        host.bob.targetGuids = new ArrayList<>();
        host.bob.lockedAtkSlots = new ArrayList<>();
        host.bob.lockedMyFp = 0;
        List<BobTargetEntry> targets = resolveStoredTargets(host);
        if (targets.size() < TARGET_COUNT) {
            return "targets=" + targets.size() + " need " + TARGET_COUNT;
        }
        int npcN = 0;
        for (BobTargetEntry t : targets) {
            if (t != null && t.npc != null) {
                npcN++;
            }
        }
        BobTargetEntry firstNpc = null;
        for (BobTargetEntry t : targets) {
            if (t != null && t.npc != null) {
                firstNpc = t;
                break;
            }
        }
        if (firstNpc == null) {
            return "no npc in targets (npcN=0 pool weak?)";
        }
        byte[] detail = dump.bobTargetDetail(firstNpc.npc);
        Pb.Fields f = Pb.read(detail);
        List<byte[]> wjs = f.getBytesList(3);
        if (wjs == null || wjs.size() < 5) {
            return "2204 WJ count=" + (wjs == null ? 0 : wjs.size());
        }
        List<com.sao.fakeserver.fight.FightUnit> atk = FightRosterBuilder.fromPlayer(
                host, cultivate, fightCfg, PlayerRecord.FORMATION_BOB_ATK);
        List<com.sao.fakeserver.fight.FightUnit> def = FightRosterBuilder.fromPlayer(
                firstNpc.npc, cultivate, fightCfg, PlayerRecord.FORMATION_JJC_DEF);
        if (atk.isEmpty() || def.isEmpty()) {
            return "empty roster atk=" + atk.size() + " def=" + def.size();
        }
        store.save(host);
        log.info("bob smoke OK host={} targets={} npcTargets={} sampleNpc={} fp={} wj={}",
                host.account, targets.size(), npcN, firstNpc.targetGuid,
                firstNpc.fightPower(cultivate, tables, fightCfg), wjs.size());
        return null;
    }

    private void grantPrize(GameSession session, GamePacket pkt, PlayerRecord rec,
                            int gold, int yingPo, List<GameTables.GoodsDrop> drops) {
        if (gold > 0) {
            rec.gold += gold;
            progress.pushGold(session, pkt, rec);
        }
        if (yingPo > 0) {
            progress.addYingPo(rec, yingPo);
            progress.pushYingPo(session, pkt, rec);
        }
        if (drops == null || drops.isEmpty()) {
            return;
        }
        Map<String, Integer> changed = new HashMap<>();
        for (GameTables.GoodsDrop g : drops) {
            if (g == null || g.ori == null || g.count <= 0) {
                continue;
            }
            progress.addGoods(rec, g.ori, g.count);
            progress.markGoods(changed, g.ori);
        }
        progress.pushGoods(session, pkt, rec, changed);
    }

    /** 关卡胜掉落：低档量多、高档量少；颜色按场次轮换。自主规划。 */
    private static List<GameTables.GoodsDrop> stageDrops(int stage1to10) {
        int stage = Math.max(1, Math.min(10, stage1to10));
        int color = ((stage - 1) % 4) + 1;
        int grade;
        int count;
        if (stage <= 3) {
            grade = 1;
            count = 3;
        } else if (stage <= 6) {
            grade = 2;
            count = 2;
        } else if (stage <= 9) {
            grade = 3;
            count = 2;
        } else {
            grade = 4;
            count = 1;
        }
        List<GameTables.GoodsDrop> out = new ArrayList<>();
        out.add(new GameTables.GoodsDrop(timeStoneOri(color, grade), count));
        return out;
    }

    /**
     * 奖杯掉落：杯档越高品质越高、件数越少；另给刷新球。自主规划。
     * 杯0预赛→Ⅰ×4；杯1→Ⅱ×3；杯2→Ⅲ×2；杯3决赛→Ⅳ×1 + Ⅲ×1。
     */
    private static List<GameTables.GoodsDrop> cupDrops(int cup0to3) {
        int cup = Math.max(0, Math.min(3, cup0to3));
        int color = cup + 1;
        List<GameTables.GoodsDrop> out = new ArrayList<>();
        if (cup == 0) {
            out.add(new GameTables.GoodsDrop(timeStoneOri(color, 1), 4));
        } else if (cup == 1) {
            out.add(new GameTables.GoodsDrop(timeStoneOri(color, 2), 3));
        } else if (cup == 2) {
            out.add(new GameTables.GoodsDrop(timeStoneOri(color, 3), 2));
        } else {
            out.add(new GameTables.GoodsDrop(timeStoneOri(color, 4), 1));
            out.add(new GameTables.GoodsDrop(timeStoneOri(color, 3), 1));
        }
        out.add(new GameTables.GoodsDrop(ORI_REFRESH_BALL, 1 + cup));
        return out;
    }

    /** TS{color}0{grade}，color=1..5，grade=1..7。 */
    private static String timeStoneOri(int color1to5, int grade1to7) {
        int c = Math.max(1, Math.min(5, color1to5));
        int g = Math.max(1, Math.min(7, grade1to7));
        return "TS" + c + "0" + g;
    }

    private void applyAttackerHp(PlayerRecord rec, List<byte[]> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        List<String> slots = bobAtkSlots(rec);
        if (rec.bob.allHp == null) {
            rec.bob.allHp = new ArrayList<>();
        }
        for (byte[] nest : entries) {
            Pb.Fields e = Pb.read(nest);
            int job = e.getInt(1, 0);
            int curHp = e.getInt(2, 0);
            int curEnergy = e.getInt(4, 0);
            if (job < 1 || job > 5) {
                continue;
            }
            String guid = slots.get(job - 1);
            if (guid == null || guid.isEmpty()) {
                continue;
            }
            upsertHp(rec.bob.allHp, guid, curHp, curEnergy);
        }
    }

    private static void upsertHp(List<PlayerRecord.BobHp> list, String guid, int curHp, int curEnergy) {
        for (PlayerRecord.BobHp h : list) {
            if (guid.equals(h.guid)) {
                h.curHp = curHp;
                h.curEnergy = curEnergy;
                return;
            }
        }
        PlayerRecord.BobHp n = new PlayerRecord.BobHp();
        n.guid = guid;
        n.curHp = curHp;
        n.curEnergy = curEnergy;
        list.add(n);
    }

    private PlayerRecord require(GameSession session) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return null;
        }
        rec.ensureCollections();
        return rec;
    }

    /** C2S 2002：仅在无锁定列表时按战力比例抽 10 目标并写入 targetGuids。 */
    private List<BobTargetEntry> rebuildTargets(PlayerRecord rec) {
        List<BobTargetEntry> picked = pickTargetsByFightPower(rec);
        rec.bob.targetGuids = new ArrayList<>();
        for (BobTargetEntry r : picked) {
            rec.bob.targetGuids.add(Integer.valueOf(r.targetGuid));
        }
        lockRunRoster(rec);
        int myFp = rec.bob.lockedMyFp > 0 ? rec.bob.lockedMyFp : playerBobFightPower(rec);
        log.info("{} bob targets myFp={} guids={}", rec.account, myFp, rec.bob.targetGuids);
        return picked;
    }

    /** 本轮已抽目标则阵容锁定中（改阵 type7 拒绝覆盖）。 */
    public boolean isRunRosterLocked(PlayerRecord rec) {
        if (rec == null || rec.bob == null) {
            return false;
        }
        return rec.bob.targetGuids != null && rec.bob.targetGuids.size() >= TARGET_COUNT
                && rec.bob.lockedAtkSlots != null && hasAnyLockedSlot(rec.bob.lockedAtkSlots);
    }

    private static boolean hasAnyLockedSlot(List<String> slots) {
        if (slots == null) {
            return false;
        }
        for (String s : slots) {
            if (s != null && !s.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 抽目标同时冻结本轮攻阵与战力。 */
    private void lockRunRoster(PlayerRecord rec) {
        List<String> slots = new ArrayList<>(rec.formationSlots(PlayerRecord.FORMATION_BOB_ATK));
        rec.bob.lockedAtkSlots = new ArrayList<>(slots);
        rec.bob.lockedMyFp = playerBobFightPowerUnlocked(rec);
        // 写回 type7，保证拉阵/闪退重登读到同一份
        rec.setFormationSlots(PlayerRecord.FORMATION_BOB_ATK, slots);
    }

    /** 残血 job→guid、选怪战力：优先本轮锁定阵。 */
    private List<String> bobAtkSlots(PlayerRecord rec) {
        if (rec.bob.lockedAtkSlots != null && hasAnyLockedSlot(rec.bob.lockedAtkSlots)) {
            return rec.bob.lockedAtkSlots;
        }
        return rec.formationSlots(PlayerRecord.FORMATION_BOB_ATK);
    }

    private List<BobTargetEntry> resolveStoredTargets(PlayerRecord rec) {
        if (rec.bob.targetGuids == null || rec.bob.targetGuids.size() < TARGET_COUNT) {
            return rebuildTargets(rec);
        }
        List<BobTargetEntry> out = new ArrayList<>();
        for (Integer g : rec.bob.targetGuids) {
            if (g == null) {
                continue;
            }
            BobTargetEntry t = resolveTargetEntry(g.intValue());
            if (t != null) {
                out.add(t);
            }
        }
        if (out.size() < TARGET_COUNT) {
            return rebuildTargets(rec);
        }
        // 旧档：有目标无锁阵 → 补锁当前阵，避免闪退后映射漂移
        if (!hasAnyLockedSlot(rec.bob.lockedAtkSlots)) {
            lockRunRoster(rec);
        }
        return out;
    }

    /**
     * 预赛1–3 / 初赛4–6：约己方 2/3；复赛7–9：约 3/4；决赛10：己方 ±5%。
     * 候选人战力一律 {@link FightRosterBuilder#robotFightPower}（与武将详情同源）。
     */
    private List<BobTargetEntry> pickTargetsByFightPower(PlayerRecord rec) {
        List<int[]> pool = ensureBobTargetFpPool();
        List<BobTargetEntry> out = new ArrayList<>();
        if (pool == null || pool.isEmpty()) {
            return out;
        }
        int myFp = Math.max(1000, playerBobFightPower(rec));
        Set<Integer> used = new HashSet<>();
        for (int stage = 1; stage <= TARGET_COUNT; stage++) {
            int targetFp = stageTargetFp(myFp, stage);
            int bestGuid = -1;
            int bestDiff = Integer.MAX_VALUE;
            boolean finals = stage == 10;
            int lo = finals ? (int) Math.floor(myFp * 0.95) : 0;
            int hi = finals ? (int) Math.ceil(myFp * 1.05) : Integer.MAX_VALUE;
            for (int pass = 0; pass < (finals ? 2 : 1); pass++) {
                bestGuid = -1;
                bestDiff = Integer.MAX_VALUE;
                for (int[] row : pool) {
                    int guid = row[0];
                    int fp = row[1];
                    if (used.contains(Integer.valueOf(guid))) {
                        continue;
                    }
                    if (pass == 0 && finals && (fp < lo || fp > hi)) {
                        continue;
                    }
                    int d = Math.abs(fp - targetFp);
                    if (d < bestDiff) {
                        bestDiff = d;
                        bestGuid = guid;
                    }
                }
                if (bestGuid > 0) {
                    break;
                }
            }
            if (bestGuid <= 0) {
                break;
            }
            used.add(Integer.valueOf(bestGuid));
            BobTargetEntry t = resolveTargetEntry(bestGuid);
            if (t != null) {
                out.add(t);
            }
        }
        while (out.size() < TARGET_COUNT && !pool.isEmpty()) {
            int[] row = pool.get(out.size() % pool.size());
            BobTargetEntry t = resolveTargetEntry(row[0]);
            if (t != null) {
                out.add(t);
            } else {
                break;
            }
        }
        return out;
    }

    private BobTargetEntry resolveTargetEntry(int guid) {
        if (guid > 1_000_000) {
            PlayerRecord npc = store.findByPlayerId(guid);
            if (npc != null && npc.npcPassive) {
                return BobTargetEntry.fromNpc(npc);
            }
        }
        GameTables.RobotRow r = tables.robotByGuid(guid);
        return r == null ? null : BobTargetEntry.fromRobot(r);
    }

    /** 优先 npcPassive（≥10 才纯 NPC 池），否则回退 JJC_Robot。 */
    private List<int[]> ensureBobTargetFpPool() {
        List<int[]> npcPool = ensureNpcFpCache();
        if (npcPool != null && npcPool.size() >= TARGET_COUNT) {
            return npcPool;
        }
        ensureRobotFpCache();
        if (npcPool == null || npcPool.isEmpty()) {
            return robotFpCache;
        }
        List<int[]> merged = new ArrayList<>(npcPool);
        if (robotFpCache != null) {
            merged.addAll(robotFpCache);
        }
        return merged;
    }

    private List<int[]> ensureNpcFpCache() {
        if (npcFpCache != null) {
            return npcFpCache;
        }
        synchronized (this) {
            if (npcFpCache != null) {
                return npcFpCache;
            }
            List<int[]> list = new ArrayList<>();
            for (PlayerRecord p : store.all()) {
                if (p == null || !p.npcPassive || p.playerId <= 1_000_000) {
                    continue;
                }
                p.ensureCollections();
                int fp = Math.max(1, p.npcTotalFightPower);
                list.add(new int[]{p.playerId, fp});
            }
            npcFpCache = list;
            log.info("bob npcFpCache size={}", list.size());
            return npcFpCache;
        }
    }

    /** 关卡目标战力：预赛/初赛 2/3，复赛 3/4，决赛 1.0（选人时再套 ±5% 带）。 */
    private static int stageTargetFp(int myFp, int stage1to10) {
        if (stage1to10 <= 6) {
            return Math.max(1, (int) Math.round(myFp * (2.0 / 3.0)));
        }
        if (stage1to10 <= 9) {
            return Math.max(1, (int) Math.round(myFp * 0.75));
        }
        // 决赛中心：在 ±5% 内随机一点，避免十连都贴死同一值
        double jitter = 0.95 + ThreadLocalRandom.current().nextDouble() * 0.10;
        return Math.max(1, (int) Math.round(myFp * jitter));
    }

    /** 己方 BOB 战力：本轮已锁则用锁定值，保证重进不变。 */
    private int playerBobFightPower(PlayerRecord rec) {
        if (rec.bob.lockedMyFp > 0 && hasAnyLockedSlot(rec.bob.lockedAtkSlots)) {
            return rec.bob.lockedMyFp;
        }
        return playerBobFightPowerUnlocked(rec);
    }

    private int playerBobFightPowerUnlocked(PlayerRecord rec) {
        int sum = 0;
        for (String id : rec.formationSlots(PlayerRecord.FORMATION_BOB_ATK)) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            PlayerRecord.Hero wj = rec.findHero(id);
            if (wj == null) {
                continue;
            }
            int fp = cultivate.computeFightPower(rec, wj);
            wj.fightPower = fp;
            sum += fp;
        }
        if (sum <= 0) {
            for (PlayerRecord.Hero wj : rec.heroes) {
                int fp = cultivate.computeFightPower(rec, wj);
                wj.fightPower = fp;
                sum += fp;
            }
        }
        return Math.max(1000, sum);
    }

    private void ensureRobotFpCache() {
        if (robotFpCache != null) {
            return;
        }
        synchronized (this) {
            if (robotFpCache != null) {
                return;
            }
            long t0 = System.currentTimeMillis();
            List<int[]> list = new ArrayList<>();
            for (GameTables.RobotRow r : tables.robots()) {
                int fp = FightRosterBuilder.robotFightPower(r, cultivate, tables, fightCfg);
                list.add(new int[]{r.targetGuid, Math.max(1, fp)});
            }
            robotFpCache = list;
            log.info("bob robotFpCache size={} costMs={}", list.size(), System.currentTimeMillis() - t0);
        }
    }

    private BobTargetEntry currentTarget(PlayerRecord rec) {
        List<BobTargetEntry> targets = resolveStoredTargets(rec);
        if (targets.isEmpty()) {
            return null;
        }
        int idx = Math.max(0, Math.min(targets.size() - 1, rec.bob.roundNumber - 1));
        return targets.get(idx);
    }
}
