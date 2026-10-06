package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.store.WorldStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.EconomyTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 抢矿：页列表 / 占矿 / 开战 / 结算换主或掠夺 / 假号占位补位。
 * 权威：QiangKuang.txt 产出速度×时长 + QiangKuang_Common 掠夺/保护；
 * 1908 后推 1001 ResultFB（客户端 Exist 等 EN_RESULTFB_RET_SUCCESS）。
 */
@Service
@Order(60)
public class MineService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MineService.class);
    /** RegionList id=3 type=4=PVP2 抢矿（勿用 id=4，那是切磋）。 */
    private static final int REGION_QIANGKUANG = 3;
    /** 金/银/铜各填前若干页假号占位（不超过表页数），后面页留给真人空矿。 */
    private static final int NPC_FILL_PAGES = 5;
    /** 产品：占矿胜 pending 等 1904 超时（防卡死 fighting）。 */
    private static final long PENDING_OCCUPY_TIMEOUT_MS = 5L * 60L * 1000L;
    /** 产品：ForceExist 不发 1908 时，每队防守（矿主+协防）锁 3 分钟。 */
    private static final long FIGHT_LOCK_PER_TEAM_MS = 3L * 60L * 1000L;

    private final PlayerStore store;
    private final WorldStore world;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final EconomyTables tables;
    private final CultivateTables cultivate;
    private final TaskService task;
    private final SessionHub sessions;
    private final FightSyncService fightSync;

    /** account → 开战上下文（1907 写入，1908/1904 消费；pendingOccupy 断线仍保留）。 */
    private final ConcurrentHashMap<String, FightCtx> fightByAccount = new ConcurrentHashMap<>();
    /** account → 最近一次开战敌阵（按账号隔离，供 Dispatcher beginKuangBattle）。 */
    private final ConcurrentHashMap<String, FightLaunch> launchByAccount = new ConcurrentHashMap<>();
    /** account → 最近一次 1901 浏览的矿页（2118 只推给这些人，对齐真服「当前页观看者」）。 */
    private final ConcurrentHashMap<String, PageView> pageViewByAccount = new ConcurrentHashMap<>();
    /** 矿 ID → 结算/换主互斥锁（多人并行防双掠/双 pending）。 */
    private final ConcurrentHashMap<Integer, Object> mineLocks = new ConcurrentHashMap<>();

    /** 开战敌方快照（勿用单例字段，防多玩家串阵）。 */
    public static final class FightLaunch {
        public final PlayerRecord foe;
        public final List<String> foeSlots;

        public FightLaunch(PlayerRecord foe, List<String> foeSlots) {
            this.foe = foe;
            this.foeSlots = foeSlots;
        }
    }

    private static final class PageView {
        int type;
        int page;
        boolean emptyOnly;
    }

    public MineService(PlayerStore store, WorldStore world, PlayerDumpService dump, ProgressService progress,
                       EconomyTables tables, CultivateTables cultivate, TaskService task, SessionHub sessions,
                       FightSyncService fightSync) {
        this.store = store;
        this.world = world;
        this.dump = dump;
        this.progress = progress;
        this.tables = tables;
        this.cultivate = cultivate;
        this.task = task;
        this.sessions = sessions;
        this.fightSync = fightSync;
    }

    @Override
    public void run(ApplicationArguments args) {
        // FightCtx 不落盘：重启后一律清孤儿 fighting，避免永久拒战
        clearOrphanFighting("boot");
        ensureNpcHolders("boot");
    }

    /** 每分钟：超时解锁；孤儿 fighting；积满清矿（同路径）；待领 tip；假号仅补空位。 */
    @Scheduled(fixedDelay = 60000)
    public void tickNpcMines() {
        expireStaleFightLocks();
        clearOrphanFighting("tick");
        settleFullMines();
        pushPendingResourceTips();
        ensureNpcHolders("tick");
    }

    public void onPage(GameSession session, GamePacket pkt) {
        Pb.Fields f = Pb.read(pkt.body);
        int reqPage = f.getInt(1, 1);
        int type = f.getInt(2, 1);
        boolean emptyOnly = f.getBool(3);
        boolean rob = f.getBool(4);
        // 寻空：扫全 type 找首个有空位的页；全无则 PageNumber=-1（客户端 100612）
        int page;
        if (emptyOnly) {
            page = findFirstEmptyPage(type);
        } else {
            page = Math.max(1, reqPage);
        }
        PlayerRecord me = session.player();
        if (me != null && me.account != null) {
            PageView pv = new PageView();
            pv.type = type;
            pv.page = page > 0 ? page : 1;
            pv.emptyOnly = emptyOnly;
            pageViewByAccount.put(me.account, pv);
        }
        List<byte[]> infos = new ArrayList<>();
        int empty = 0;
        if (page > 0) {
            List<Integer> ids = tables.mineIdsOnPage(type, page);
            for (Integer idObj : ids) {
                int id = idObj.intValue();
                WorldStore.MineSlot occ = world.mines().get(Integer.valueOf(id));
                if (occ == null) {
                    empty++;
                    if (!emptyOnly && rob) {
                        continue;
                    }
                    infos.add(dump.kuangBrief(id, 0, 0, "", 0, 0, false, 0, 0));
                } else if (!emptyOnly) {
                    infos.add(dump.kuangBrief(id, occ.holderId, occ.level, occ.name, leftSec(occ),
                            protectLeft(occ), occ.fighting, rebuildLeft(occ), coDefCnt(occ)));
                }
            }
        }
        session.send(MsgIds.S2C_KUANG_PAGE, pkt, dump.kuangPage(infos, empty, type, rob, page, emptyOnly));
    }

    /**
     * C2S 1904 {@code CCMsgHoldOneKuang}：体为 {@code CCMsgKuangDefFormation}（KuangID+WJ）。
     * 空矿扣 ZZ 占；胜占矿 pending 换主不扣 ZZ；已是自己的则只更防阵。
     * 换主路径与 1908 同矿锁，并校验矿主仍为开战 foe。
     */
    public void onHold(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensureMineDaily(session, pkt, rec);
        if (!isMineOpen(rec)) {
            session.send(MsgIds.S2C_KUANG_HOLD, pkt, dump.kuangHoldRet(false, "矿战未开放"));
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int id = f.getInt(1, 0);
        List<String> five = readFiveWj(f.getStrings(2));
        synchronized (mineLock(id)) {
            FightCtx ctx = fightByAccount.get(rec.account);
            WorldStore.MineSlot occ = world.mines().get(Integer.valueOf(id));
            long now = System.currentTimeMillis();
            EconomyTables.MineCommon m = tables.mine();

            boolean pendingWinOccupy = ctx != null && ctx.pendingOccupy && ctx.kuangId == id && !ctx.isRob;
            boolean selfHold = occ != null && occ.holderId == rec.playerId;

            // pending 换主但矿已没了（原主撤离等）：作废，禁止误走空矿再扣 ZZ
            if (pendingWinOccupy && occ == null) {
                fightByAccount.remove(rec.account);
                session.send(MsgIds.S2C_KUANG_INVALID, pkt, new byte[0]);
                session.send(MsgIds.S2C_KUANG_HOLD, pkt, dump.kuangHoldRet(false, "矿已不存在"));
                log.info("{} hold pending but mine gone id={}", rec.account, id);
                return;
            }
            // pending：矿主必须仍是开战 foe（防他人先 Hold 后二次换主）
            if (pendingWinOccupy) {
                PlayerRecord expected = resolveByAccount(ctx.foeAccount);
                PlayerRecord actual = resolveHolder(occ);
                if (expected == null || actual == null || expected.playerId != actual.playerId) {
                    fightByAccount.remove(rec.account);
                    syncMineFightState(occ);
                    world.markMinesDirty();
                    session.send(MsgIds.S2C_KUANG_INVALID, pkt, new byte[0]);
                    session.send(MsgIds.S2C_KUANG_HOLD, pkt, dump.kuangHoldRet(false, "矿主已变更"));
                    log.info("{} hold pending foe mismatch id={}", rec.account, id);
                    return;
                }
            }

            if (occ != null && !selfHold && !pendingWinOccupy) {
                if (ctx != null && ctx.kuangId == id) {
                    session.send(MsgIds.S2C_KUANG_INVALID, pkt, new byte[0]);
                }
                session.send(MsgIds.S2C_KUANG_HOLD, pkt, dump.kuangHoldRet(false, "矿已被占领"));
                return;
            }

            EconomyTables.VipRow vip = tables.vip(rec.economy == null ? 0 : rec.economy.chargedDiamond);
            int occupyMax = vip == null ? 2 : Math.max(1, vip.occupyMineMax);
            // DefenseKuangCnt 日计 vs VipCfg 占领矿数量（客户端 EmptyKuang / ShouKuangCount）
            if (!selfHold && rec.defenseKuangCnt >= occupyMax) {
                if (pendingWinOccupy && occ != null) {
                    fightByAccount.remove(rec.account);
                    syncMineFightState(occ);
                    world.markMinesDirty();
                }
                session.send(MsgIds.S2C_KUANG_HOLD, pkt, dump.kuangHoldRet(false, "今日占领次数已达上限"));
                return;
            }

            if (occ == null) {
                if (countOwnedMines(rec.playerId) >= occupyMax) {
                    session.send(MsgIds.S2C_KUANG_HOLD, pkt, dump.kuangHoldRet(false, "占领矿数量已达上限"));
                    return;
                }
                if (rec.zhengZhanShuiJin < m.occupyZz) {
                    session.send(MsgIds.S2C_KUANG_HOLD, pkt, dump.kuangHoldRet(false, "征战水晶不足"));
                    return;
                }
                // putIfAbsent：防双人同时占同一空矿双扣 ZZ
                WorldStore.MineSlot created = new WorldStore.MineSlot();
                created.id = id;
                WorldStore.MineSlot raced = world.mines().putIfAbsent(Integer.valueOf(id), created);
                if (raced != null) {
                    session.send(MsgIds.S2C_KUANG_HOLD, pkt, dump.kuangHoldRet(false, "矿已被占领"));
                    return;
                }
                rec.zhengZhanShuiJin -= m.occupyZz;
                progress.onZzConsumed(rec);
                progress.pushZz(session, pkt, rec);
                occ = created;
            } else if (pendingWinOccupy) {
                // 换主后本矿会归自己，不算在当前已占数量里
                int owned = countOwnedMines(rec.playerId);
                if (owned >= occupyMax) {
                    // 解 pending 锁，避免矿一直 fighting
                    fightByAccount.remove(rec.account);
                    syncMineFightState(occ);
                    world.markMinesDirty();
                    session.send(MsgIds.S2C_KUANG_HOLD, pkt, dump.kuangHoldRet(false, "占领矿数量已达上限"));
                    return;
                }
                // 胜换主：不扣占领 ZZ（开战已扣占矿 ZZ）
                applyLeaveKeepToOldHolder(occ);
                releaseCoDefs(occ);
            }

            applyHolder(occ, rec, now, pendingWinOccupy || occ.occupiedAt <= 0);
            if (pendingWinOccupy) {
                ensureCoDefs(occ);
                occ.coDefs.clear();
                fightByAccount.remove(rec.account);
                // 换主后作废同矿其他在途攻方（防 2802 错队 / 再结算）
                invalidateOtherFightsOnMine(id, rec.account);
                syncMineFightState(occ);
            }
            if (pendingWinOccupy && m.rebuildSec > 0) {
                occ.rebuildUntil = now + m.rebuildSec * 1000L;
            }
            if (pendingWinOccupy && m.protectRobSec > 0) {
                occ.protectRobUntil = now + m.protectRobSec * 1000L;
            }
            applyDefFormation(rec, occ, five);
            if (!selfHold) {
                rec.defenseKuangCnt++;
            }
            store.save(rec);
            world.saveMines();
            progress.pushDefenseKuang(session, pkt, rec);
            session.send(MsgIds.S2C_KUANG_HOLD, pkt, dump.kuangHoldRet(true));
            broadcastRefreshMine(id, rec.account);
            log.info("{} hold mine {} pendingOccupy={} selfHold={}", rec.account, id, pendingWinOccupy, selfHold);
        }
    }

    /** C2S 1905 {@code CCMsgKuangDefFormation}：改防阵，禁止走占领扣 ZZ。 */
    public void onChangeDef(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int kuangId = f.getInt(1, 0);
        synchronized (mineLock(kuangId)) {
            WorldStore.MineSlot occ = world.mines().get(Integer.valueOf(kuangId));
            if (occ == null || occ.holderId != rec.playerId) {
                session.send(MsgIds.S2C_KUANG_DEF_WJ, pkt, dump.kuangDefAllWj(listOwnedMines(rec.playerId), rec));
                return;
            }
            applyDefFormation(rec, occ, readFiveWj(f.getStrings(2)));
            store.save(rec);
            world.saveMines();
            session.send(MsgIds.S2C_KUANG_DEF_WJ, pkt, dump.kuangDefAllWj(listOwnedMines(rec.playerId), rec));
            log.info("{} kuang changeDef id={}", rec.account, kuangId);
        }
    }

    /**
     * C2S 1906 撤离：仅矿主；fighting（开战中/占矿胜 pending）拒撤，防攻方 Hold 误走空矿。
     * 拒时不回 2115（客仍以为矿在）。
     */
    public void onLeave(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        int id = Pb.read(pkt.body).getInt(1, 0);
        if (rec == null) {
            return;
        }
        synchronized (mineLock(id)) {
            WorldStore.MineSlot occ = world.mines().get(Integer.valueOf(id));
            if (occ == null || occ.holderId != rec.playerId) {
                return;
            }
            if (occ.fighting) {
                log.info("{} kuang leave refuse fighting id={}", rec.account, id);
                return;
            }
            applyLeaveKeepToOldHolder(occ);
            releaseCoDefs(occ);
            store.save(rec);
            world.mines().remove(Integer.valueOf(id));
            world.saveMines();
            session.send(MsgIds.S2C_KUANG_CLEAR, pkt, dump.int1(id));
            broadcastRefreshMine(id, rec.account);
        }
        // 空位交给调度补假号（假服唯一主动占矿入口）
        ensureNpcHolders("leave");
    }

    /**
     * 开战：打已有矿主（真人/假号）。C2S {@code CCMsgFightKuang}：1=KuangID 2=IsRob。
     * 拒战发空 2107（客户端 100413）；掠夺扣 robZz、占矿开战扣 occupyZz。
     * 同矿可多人并行；已有占矿胜 pending 则拒新人。
     * @return true=已扣 ZZ 并下发 2107+107，可 beginKuangBattle
     */
    public boolean onFight(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return false;
        }
        ensureMineDaily(session, pkt, rec);
        Pb.Fields f = Pb.read(pkt.body);
        int id = f.getInt(1, 0);
        boolean isRob = f.getBool(2);
        if (!isMineOpen(rec)) {
            refuseFight(session, pkt, "openLevel");
            return false;
        }
        if (!isMineFightHour()) {
            refuseFight(session, pkt, "hour");
            return false;
        }
        // 与 1908/1904 同矿锁：pending 刚置时拒新人，禁止窗口期白扣 ZZ
        synchronized (mineLock(id)) {
            WorldStore.MineSlot slot = world.mines().get(Integer.valueOf(id));
            if (slot == null) {
                refuseFight(session, pkt, "empty");
                return false;
            }
            if (isRob && protectLeft(slot) > 0) {
                refuseFight(session, pkt, "protectRob");
                return false;
            }
            if (rebuildLeft(slot) > 0) {
                refuseFight(session, pkt, "rebuild");
                return false;
            }
            if (fightByAccount.containsKey(rec.account)) {
                refuseFight(session, pkt, "alreadyFight");
                return false;
            }
            // 已有人占矿胜待 1904：禁止新人插队换主；掠/占可多人并行打同一矿
            if (hasPendingOccupyOnMine(id, null)) {
                refuseFight(session, pkt, "pendingOccupy");
                return false;
            }
            PlayerRecord foe = resolveMineFoe(id, rec);
            if (foe == null) {
                refuseFight(session, pkt, "selfOrEmpty");
                return false;
            }
            // 活将：占矿需≥5、掠夺需≥1（对齐 OtherKuangDetailUISystem 客拦）
            int aliveWj = countAliveAtkWj(rec);
            if (aliveWj <= 0) {
                refuseFight(session, pkt, "noAliveWj");
                return false;
            }
            if (!isRob && aliveWj < 5) {
                refuseFight(session, pkt, "aliveLt5");
                return false;
            }
            // 占矿开战：与客拦一致，日计/并发达 VIP 上限则拒（避免白扣 ZZ）
            if (!isRob) {
                EconomyTables.VipRow vip = tables.vip(rec.economy == null ? 0 : rec.economy.chargedDiamond);
                int occupyMax = vip == null ? 2 : Math.max(1, vip.occupyMineMax);
                if (rec.defenseKuangCnt >= occupyMax) {
                    refuseFight(session, pkt, "dailyOccupy");
                    return false;
                }
                if (countOwnedMines(rec.playerId) >= occupyMax) {
                    refuseFight(session, pkt, "occupyMax");
                    return false;
                }
            }
            foe.ensureCollections();
            EconomyTables.MineCommon m = tables.mine();
            int cost = isRob ? m.robZz : m.occupyZz;
            if (rec.zhengZhanShuiJin < cost) {
                refuseFight(session, pkt, "zz");
                return false;
            }
            rec.zhengZhanShuiJin -= cost;
            progress.onZzConsumed(rec);
            store.save(rec);
            progress.pushZz(session, pkt, rec);

            List<String> foeSlots = slotDefSlots(slot, foe);
            FightCtx ctx = new FightCtx();
            ctx.kuangId = id;
            ctx.isRob = isRob;
            ctx.foeAccount = foe.account;
            ctx.teamIndex = 0;
            ctx.lockSinceMs = System.currentTimeMillis();
            ctx.defenseTeams = Math.max(1, 1 + coDefCnt(slot));
            fightByAccount.put(rec.account, ctx);
            launchByAccount.put(rec.account, new FightLaunch(foe, foeSlots));
            syncMineFightState(slot);

            session.send(MsgIds.S2C_KUANG_FIGHT, pkt, dump.kuangFightTeams(rec, foe, foeSlots));
            session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(REGION_QIANGKUANG));
            rec.currentRegionId = REGION_QIANGKUANG;
            store.save(rec);
            broadcastRefreshMine(id, rec.account);
            log.info("{} kuang fight id={} foe={} rob={} costZz={} attackers={} teams={}",
                    rec.account, id, foe.account, isRob, cost, countFightsOnMine(id), ctx.defenseTeams);
            return true;
        }
    }

    /** 取出并清除本账号开战敌阵快照。 */
    public FightLaunch takeLaunch(String account) {
        if (account == null || account.isEmpty()) {
            return null;
        }
        return launchByAccount.remove(account);
    }

    /**
     * C2S 1908：服端结算。掠夺胜→入账+保护；占矿胜→pending 等 1904 换主；负→重建保护。
     * 同矿锁串行：防双掠超发 / 双 pending；pending 中在途掠夺作废。
     * 推空包 2109 给防守方（若在线）点亮战报 tip。解析 FightRecords 供 2120。
     * 末尾推 S2C 1001 ResultFB（Exist 链）；作废时先 2128 再 1001（先置 mInvalidResult）。
     */
    public void onFightResult(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields body = Pb.read(pkt.body);
        boolean win = body.getBool(1);
        List<PlayerRecord.QkFightRound> rounds = parseFightRounds(body, rec);
        FightCtx ctx = fightByAccount.get(rec.account);
        if (ctx == null) {
            log.info("{} kuang result without fight ctx win={}", rec.account, win);
            session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(win ? 1 : 2, 0));
            return;
        }
        synchronized (mineLock(ctx.kuangId)) {
            // 超时/断线已在锁内丢掉 ctx：禁止再掠/pending
            if (fightByAccount.get(rec.account) != ctx) {
                markDeadFromFight(session, rec);
                session.send(MsgIds.S2C_KUANG_INVALID, pkt, new byte[0]);
                session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(win ? 1 : 2, 0));
                log.info("{} kuang result ctx gone id={}", rec.account, ctx.kuangId);
                return;
            }
            WorldStore.MineSlot slot = world.mines().get(Integer.valueOf(ctx.kuangId));
            PlayerRecord expected = resolveByAccount(ctx.foeAccount);
            PlayerRecord actual = slot == null ? null : resolveHolder(slot);
            if (slot == null || expected == null || actual == null || expected.playerId != actual.playerId) {
                markDeadFromFight(session, rec);
                session.send(MsgIds.S2C_KUANG_INVALID, pkt, new byte[0]);
                session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(win ? 1 : 2, 0));
                fightByAccount.remove(rec.account);
                if (slot != null) {
                    syncMineFightState(slot);
                    world.markMinesDirty();
                    broadcastRefreshMine(slot.id, rec.account);
                }
                return;
            }
            markDeadFromFight(session, rec);
            EconomyTables.MineCommon m = tables.mine();
            long now = System.currentTimeMillis();
            int plunderGold = 0;
            int plunderDiamond = 0;
            boolean recordWin = win;
            boolean occupyPending = false;
            PlayerRecord foe = expected;
            if (foe != null) {
                foe.ensureCollections();
                enrichHurtBriefs(rounds, foe, false);
            }
            enrichHurtBriefs(rounds, rec, true);

            if (win && ctx.isRob) {
                // 已有占矿胜 pending：在途掠夺作废（不掏矿）
                if (hasPendingOccupyOnMine(ctx.kuangId, null)) {
                    recordWin = false;
                    fightByAccount.remove(rec.account);
                    syncMineFightState(slot);
                    session.send(MsgIds.S2C_KUANG_INVALID, pkt, new byte[0]);
                } else {
                    int[] pend = pendingOne(slot);
                    plunderGold = clampRob(pend[0], m.robRatio, m.robGoldCap(tables.mineType(slot.id)));
                    plunderDiamond = clampRob(pend[1], m.robRatio, m.robDiamondCap(tables.mineType(slot.id)));
                    rec.gold += plunderGold;
                    rec.diamond += plunderDiamond;
                    shrinkPendingAfterRob(slot, m.keepRatio, now);
                    if (m.protectRobSec > 0) {
                        slot.protectRobUntil = now + m.protectRobSec * 1000L;
                    }
                    if (plunderGold > 0) {
                        progress.pushGold(session, pkt, rec);
                    }
                    if (plunderDiamond > 0) {
                        progress.pushDiamond(session, pkt, rec);
                    }
                    ctx.pendingOccupy = false;
                    fightByAccount.remove(rec.account);
                    syncMineFightState(slot);
                }
            } else if (win && !ctx.isRob) {
                if (hasPendingOccupyOnMine(ctx.kuangId, rec.account)) {
                    // 他人已占矿胜待换主：本场胜不作换主、不写占领胜报
                    recordWin = false;
                    fightByAccount.remove(rec.account);
                    syncMineFightState(slot);
                    session.send(MsgIds.S2C_KUANG_INVALID, pkt, new byte[0]);
                } else {
                    // 占矿胜：原子置 pending；他人仍可打完已开的场（再结算会 2128）
                    ctx.pendingOccupy = true;
                    ctx.lockSinceMs = now;
                    occupyPending = true;
                    syncMineFightState(slot);
                }
            } else {
                ctx.pendingOccupy = false;
                fightByAccount.remove(rec.account);
                syncMineFightState(slot);
                // 无人再打才挂重建，避免误拦其他在途攻方新人（在途不受 rebuild 踢）
                if (m.rebuildSec > 0 && countFightsOnMine(slot.id) == 0) {
                    slot.rebuildUntil = now + m.rebuildSec * 1000L;
                }
            }

            appendRecords(rec, foe, ctx, recordWin, plunderGold, plunderDiamond, rounds);
            store.save(rec);
            if (foe != null && !foe.npcPassive) {
                store.save(foe);
            }
            world.saveMines();
            // 保护/重建/IsFighting 变化 → 浏览者刷页
            broadcastRefreshMine(slot.id, rec.account);

            if (foe != null && !foe.npcPassive) {
                foe.qkFightRecordTip = true;
                store.save(foe);
                GameSession foeSes = sessions.get(foe.account);
                if (foeSes != null) {
                    foeSes.send(MsgIds.S2C_KUANG_FIGHT_TIP, 0, new byte[0]);
                }
            }

            task.onDailyAction(session, pkt, rec, TaskService.DAILY_MINE, 1);
            // Exist：LueDuoAndZhanLingKuangManager 等 EN_RESULTFB_RET_SUCCESS 才 Continue 撤离/占矿布防
            session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(win ? 1 : 2, 0));
            log.info("{} kuang result win={} recordWin={} rob={} pendingOccupy={} id={} plunderG={} D={} rounds={}",
                    rec.account, win, recordWin, ctx.isRob, occupyPending, ctx.kuangId, plunderGold, plunderDiamond,
                    rounds == null ? 0 : rounds.size());
        }
    }

    public void onGetResource(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int[] pend = pending(rec.playerId);
        session.send(MsgIds.S2C_KUANG_RES, pkt, dump.kuangResource(pend[0], pend[1]));
    }

    public void onConfirmResource(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        List<Integer> ids = new ArrayList<>();
        for (WorldStore.MineSlot occ : world.mines().values()) {
            if (occ != null && occ.holderId == rec.playerId) {
                ids.add(Integer.valueOf(occ.id));
            }
        }
        Collections.sort(ids);
        int gold = 0;
        int diamond = 0;
        long now = System.currentTimeMillis();
        for (Integer id : ids) {
            synchronized (mineLock(id.intValue())) {
                WorldStore.MineSlot occ = world.mines().get(id);
                if (occ == null || occ.holderId != rec.playerId) {
                    continue;
                }
                int[] one = pendingOne(occ);
                if (one[0] <= 0 && one[1] <= 0) {
                    continue;
                }
                gold += one[0];
                diamond += one[1];
                occ.lastCollectAt = now;
            }
        }
        if (gold <= 0 && diamond <= 0) {
            return;
        }
        rec.gold += gold;
        rec.diamond += diamond;
        rec.qkResourceTip = false;
        world.saveMines();
        store.save(rec);
        if (gold > 0) {
            progress.pushGold(session, pkt, rec);
        }
        if (diamond > 0) {
            progress.pushDiamond(session, pkt, rec);
        }
        log.info("{} claim mine gold={} diamond={}", rec.account, gold, diamond);
    }

    public void onMyMines(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        List<byte[]> infos = new ArrayList<>();
        if (rec != null) {
            for (Map.Entry<Integer, WorldStore.MineSlot> e : world.mines().entrySet()) {
                if (e.getValue().holderId == rec.playerId) {
                    infos.add(dump.kuangSelfBrief(e.getKey().intValue(), leftSec(e.getValue()),
                            rec.roleName, coDefCnt(e.getValue())));
                }
            }
        }
        session.send(MsgIds.S2C_KUANG_MY, pkt, dump.kuangMyList(infos));
    }

    public void onDefWj(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            session.send(MsgIds.S2C_KUANG_DEF_WJ, pkt, dump.kuangDefAllWj(null, null));
            return;
        }
        session.send(MsgIds.S2C_KUANG_DEF_WJ, pkt, dump.kuangDefAllWj(listOwnedMines(rec.playerId), rec));
    }

    public void onEmptyCnt(GameSession session, GamePacket pkt) {
        int type = Math.max(1, Pb.read(pkt.body).getInt(1, 1));
        int empty = 0;
        int pages = tables.minePageCount(type);
        for (int page = 1; page <= pages; page++) {
            for (Integer idObj : tables.mineIdsOnPage(type, page)) {
                if (!world.mines().containsKey(idObj)) {
                    empty++;
                }
            }
        }
        session.send(MsgIds.S2C_KUANG_EMPTY_CNT, pkt, dump.kuangEmptyCnt(type, empty));
    }

    public void onHolder(GameSession session, GamePacket pkt) {
        int id = Pb.read(pkt.body).getInt(1, 0);
        WorldStore.MineSlot occ = world.mines().get(Integer.valueOf(id));
        int holder = occ == null ? 0 : occ.holderId;
        session.send(MsgIds.S2C_KUANG_HOLDER, pkt, dump.kuangHolderId(holder));
    }

    public void onBuyZz(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensureMineDaily(session, pkt, rec);
        EconomyTables.MineCommon m = tables.mine();
        int zzMax = Math.max(1, m.zzMax);
        if (rec.zhengZhanShuiJin >= zzMax) {
            log.info("{} kuang buyZz refuse already max={}", rec.account, zzMax);
            return;
        }
        if (rec.diamond < m.buyZzDiamond) {
            return;
        }
        rec.diamond -= m.buyZzDiamond;
        rec.zhengZhanShuiJin += m.buyZzCount;
        if (rec.zhengZhanShuiJin > zzMax) {
            rec.zhengZhanShuiJin = zzMax;
        }
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        progress.pushZz(session, pkt, rec);
        session.send(MsgIds.S2C_KUANG_BUY_ZZ, pkt, dump.int1(m.buyZzCount));
    }

    public void onBuyRelive(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensureMineDaily(session, pkt, rec);
        rec.ensureCollections();
        int wj = Pb.read(pkt.body).getInt(1, 0);
        EconomyTables.VipRow vip = tables.vip(rec.economy == null ? 0 : rec.economy.chargedDiamond);
        int reliveMax = vip == null ? 2 : Math.max(0, vip.qkBuyReliveMax);
        if (rec.qkBuyReliveTimes >= reliveMax) {
            log.info("{} kuang buyRelive refuse max={} used={}", rec.account, reliveMax, rec.qkBuyReliveTimes);
            return;
        }
        pruneDeadWjs(rec);
        PlayerRecord.QkDeadWj target = null;
        for (PlayerRecord.QkDeadWj d : rec.qkDeadWjs) {
            if (d != null && d.wjIndex == wj) {
                target = d;
                break;
            }
        }
        if (target == null) {
            return;
        }
        // 与客户端一致：cost = leftSec / reliveSec * 表钻
        EconomyTables.MineCommon m = tables.mine();
        int left = (int) Math.max(0L, (target.reliveUntilMs - System.currentTimeMillis()) / 1000L);
        int cd = Math.max(1, m.reliveSec);
        int cost = (int) ((long) left * m.reliveDiamond / cd);
        if (cost <= 0) {
            // 倒计时已尽：免费清出（仍占买活次数）
            cost = 0;
        }
        if (rec.diamond < cost) {
            return;
        }
        rec.qkDeadWjs.remove(target);
        if (cost > 0) {
            rec.diamond -= cost;
        }
        rec.qkBuyReliveTimes++;
        store.save(rec);
        if (cost > 0) {
            progress.pushDiamond(session, pkt, rec);
        }
        session.send(MsgIds.S2C_KUANG_RELIVE, pkt, dump.int1(wj));
    }

    public void onDeadWj(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        List<Integer> indexes = new ArrayList<>();
        List<Integer> leftSecs = new ArrayList<>();
        if (rec != null) {
            rec.ensureCollections();
            pruneDeadWjs(rec);
            long now = System.currentTimeMillis();
            for (PlayerRecord.QkDeadWj d : rec.qkDeadWjs) {
                if (d == null) {
                    continue;
                }
                int left = (int) Math.max(0L, (d.reliveUntilMs - now) / 1000L);
                if (left <= 0) {
                    continue;
                }
                indexes.add(Integer.valueOf(d.wjIndex));
                leftSecs.add(Integer.valueOf(left));
            }
            store.save(rec);
        }
        session.send(MsgIds.S2C_KUANG_DEAD, pkt, dump.kuangDeadWjs(indexes, leftSecs));
    }

    public void onDetail(GameSession session, GamePacket pkt) {
        int id = Pb.read(pkt.body).getInt(1, 0);
        WorldStore.MineSlot occ = world.mines().get(Integer.valueOf(id));
        PlayerRecord me = session.player();
        if (occ == null) {
            session.send(MsgIds.S2C_KUANG_DETAIL_OTHER, pkt,
                    dump.kuangOtherDetail(id, 0, 0, "", "", 0, 0, null, null));
            return;
        }
        PlayerRecord holder = resolveHolder(occ);
        int[] pend = pendingOne(occ);
        EconomyTables.MineCommon m = tables.mine();
        int type = tables.mineType(occ.id);
        int canRobG = clampRob(pend[0], m.robRatio, m.robGoldCap(type));
        int canRobD = clampRob(pend[1], m.robRatio, m.robDiamondCap(type));
        List<String> defSlots = slotDefSlots(occ, holder);
        int fp = defenseFightPower(holder, defSlots);
        int left = leftSec(occ);
        List<byte[]> coBriefs = buildCoDefBriefs(occ);
        String unionName = "";
        if (holder != null && holder.guild != null && holder.guild.name != null) {
            unionName = holder.guild.name;
        }
        if (me != null && occ.holderId == me.playerId) {
            session.send(MsgIds.S2C_KUANG_DETAIL_SELF, pkt,
                    dump.kuangSelfDetail(id, occ.holderId, occ.name, pend[1], pend[0], left, fp, holder, coBriefs, defSlots));
        } else {
            session.send(MsgIds.S2C_KUANG_DETAIL_OTHER, pkt,
                    dump.kuangOtherDetail(id, canRobD, canRobG, unionName, occ.name, left, fp, holder, coBriefs, defSlots));
        }
    }

    public void onFightRecord(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            session.send(MsgIds.S2C_KUANG_RECORD, pkt, dump.kuangFightRecords(null));
            return;
        }
        rec.ensureCollections();
        if (rec.qkFightRecordTip) {
            rec.qkFightRecordTip = false;
            store.save(rec);
        }
        session.send(MsgIds.S2C_KUANG_RECORD, pkt, dump.kuangFightRecords(rec.qkFightRecords));
    }

    public void onFightRecordDetail(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        int num = Math.max(1, Pb.read(pkt.body).getInt(1, 1));
        PlayerRecord.QkFightRecord brief = null;
        if (rec != null) {
            rec.ensureCollections();
            int idx = num - 1;
            if (idx >= 0 && idx < rec.qkFightRecords.size()) {
                brief = rec.qkFightRecords.get(idx);
            }
        }
        session.send(MsgIds.S2C_KUANG_RECORD_DETAIL, pkt, dump.kuangFightRecordDetail(brief, rec));
    }

    /** C2S 1916：我挂出去的协防武将列表。 */
    public void onMyCoWj(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        List<String> guids = new ArrayList<>();
        List<Integer> jobs = new ArrayList<>();
        List<Integer> kids = new ArrayList<>();
        if (rec != null) {
            for (WorldStore.MineSlot slot : world.mines().values()) {
                if (slot.coDefs == null) {
                    continue;
                }
                for (WorldStore.CoDefender c : slot.coDefs) {
                    if (c == null || c.playerId != rec.playerId) {
                        continue;
                    }
                    List<String> wj = c.wj == null ? Collections.emptyList() : c.wj;
                    for (int job = 1; job <= 5; job++) {
                        String g = wj.size() >= job ? wj.get(job - 1) : "";
                        if (g == null || g.isEmpty()) {
                            continue;
                        }
                        guids.add(g);
                        jobs.add(Integer.valueOf(job));
                        kids.add(Integer.valueOf(slot.id));
                    }
                }
            }
        }
        session.send(MsgIds.S2C_KUANG_CO_WJ, pkt, dump.kuangMyCoWjs(guids, jobs, kids));
    }

    /**
     * C2S 1917 挂协防阵。RetType：0 成功；1/2/3 失败 tip（APK Str 100609–611）。
     */
    public void onCoUpdate(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int kuangId = f.getInt(1, 0);
        int holderGuid = f.getInt(2, 0);
        List<String> five = readFiveWj(f.getStrings(3));
        synchronized (mineLock(kuangId)) {
            WorldStore.MineSlot slot = world.mines().get(Integer.valueOf(kuangId));
            if (slot == null || slot.holderId != holderGuid) {
                session.send(MsgIds.S2C_KUANG_CO_UPDATE, pkt, dump.kuangCoDefRet(1));
                return;
            }
            if (slot.holderId == rec.playerId) {
                session.send(MsgIds.S2C_KUANG_CO_UPDATE, pkt, dump.kuangCoDefRet(2));
                return;
            }
            ensureCoDefs(slot);
            EconomyTables.VipRow holderVip = null;
            PlayerRecord holder = resolveHolder(slot);
            if (holder != null) {
                holderVip = tables.vip(holder.economy == null ? 0 : holder.economy.chargedDiamond);
            }
            int inviteMax = holderVip == null ? 0 : Math.max(0, holderVip.inviteCoDefMax);
            WorldStore.CoDefender exist = null;
            for (WorldStore.CoDefender c : slot.coDefs) {
                if (c != null && c.playerId == rec.playerId) {
                    exist = c;
                    break;
                }
            }
            if (exist == null) {
                EconomyTables.VipRow vip = tables.vip(rec.economy == null ? 0 : rec.economy.chargedDiamond);
                int joinMax = vip == null ? 1 : Math.max(0, vip.joinCoDefMax);
                if (rec.coDefenseKuangCnt >= joinMax) {
                    session.send(MsgIds.S2C_KUANG_CO_UPDATE, pkt, dump.kuangCoDefRet(3));
                    return;
                }
                if (inviteMax <= 0 || slot.coDefs.size() >= inviteMax) {
                    session.send(MsgIds.S2C_KUANG_CO_UPDATE, pkt, dump.kuangCoDefRet(2));
                    return;
                }
                exist = new WorldStore.CoDefender();
                exist.playerId = rec.playerId;
                exist.account = rec.account;
                exist.name = rec.roleName == null ? "" : rec.roleName;
                slot.coDefs.add(exist);
                rec.coDefenseKuangCnt++;
                progress.pushCoDefenseKuang(session, pkt, rec);
            }
            exist.wj = five;
            exist.name = rec.roleName == null ? "" : rec.roleName;
            world.saveMines();
            store.save(rec);
            session.send(MsgIds.S2C_KUANG_CO_UPDATE, pkt, dump.kuangCoDefRet(0));
            log.info("{} coDef update kuang={} holder={}", rec.account, kuangId, holderGuid);
        }
    }

    /** C2S 1918 邀请公会成员协防：推私聊超链（type=1）。 */
    public void onInvite(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int kuangId = f.getInt(1, 0);
        List<Integer> guids = f.getInts(2);
        WorldStore.MineSlot slot = world.mines().get(Integer.valueOf(kuangId));
        if (slot == null || slot.holderId != rec.playerId) {
            return;
        }
        EconomyTables.VipRow vip = tables.vip(rec.economy == null ? 0 : rec.economy.chargedDiamond);
        int inviteMax = vip == null ? 0 : Math.max(0, vip.inviteCoDefMax);
        ensureCoDefs(slot);
        if (inviteMax <= 0 || slot.coDefs.size() >= inviteMax) {
            return;
        }
        if (guids == null || guids.isEmpty()) {
            return;
        }
        String link = "[url=1:" + kuangId + "_" + rec.playerId + "][u]" + rec.roleName + "邀请协防[/u][/url]";
        String time = PlayerDumpService.now();
        String union = rec.guild != null && rec.guild.name != null ? rec.guild.name : "";
        for (Integer g : guids) {
            if (g == null || g.intValue() <= 0 || g.intValue() == rec.playerId) {
                continue;
            }
            PlayerRecord target = findByPlayerId(g.intValue());
            if (target == null || target.npcPassive) {
                continue;
            }
            GameSession ts = sessions.get(target.account);
            if (ts == null) {
                continue;
            }
            byte[] chat = dump.chatToCli(3, rec.roleName, rec.mainHeroIndex, rec.level, link, time,
                    union, rec.playerId, target.playerId, target.roleName);
            ts.send(MsgIds.S2C_CHAT_TO_CLI, 0, chat);
        }
        log.info("{} invite coDef kuang={} targets={}", rec.account, kuangId, guids);
    }

    /** C2S 1922 按敌方角色名搜其占矿。 */
    public void onEnemy(GameSession session, GamePacket pkt) {
        String name = Pb.read(pkt.body).getString(1);
        List<byte[]> briefs = new ArrayList<>();
        if (name != null && !name.isEmpty()) {
            String key = name.trim();
            for (WorldStore.MineSlot occ : world.mines().values()) {
                if (occ.name != null && occ.name.contains(key)) {
                    briefs.add(dump.kuangEnemyBrief(occ.id, leftSec(occ), occ.name, coDefCnt(occ),
                            occ.holderId, protectLeft(occ), occ.fighting, rebuildLeft(occ), occ.level));
                }
            }
        }
        session.send(MsgIds.S2C_KUANG_ENEMY, pkt, dump.kuangEnemyList(briefs));
    }

    /**
     * C2S 2802 矿内战下一队：推下一协防 2107 + beginKuangBattle + 空 3203。
     * 矿主已变 / 他人占矿胜 pending → 2128 作废，禁止错队空打。
     * @return true=已处理矿战续场
     */
    public boolean onStartNextBattle(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return false;
        }
        // 换队前记下本场阵亡
        markDeadFromFight(session, rec);
        FightCtx ctx = fightByAccount.get(rec.account);
        if (ctx == null) {
            return false;
        }
        boolean isNewRound = Pb.read(pkt.body).getBool(1);
        synchronized (mineLock(ctx.kuangId)) {
        if (fightByAccount.get(rec.account) != ctx) {
            launchByAccount.remove(rec.account);
            session.send(MsgIds.S2C_KUANG_INVALID, pkt, new byte[0]);
            session.send(MsgIds.S2C_START_ONE_NEW_BATTLE_RET, pkt, new byte[0]);
            log.info("{} kuang nextTeam abort ctx gone id={}", rec.account, ctx.kuangId);
            return true;
        }
        WorldStore.MineSlot slot = world.mines().get(Integer.valueOf(ctx.kuangId));
        if (slot == null) {
            fightByAccount.remove(rec.account);
            launchByAccount.remove(rec.account);
            session.send(MsgIds.S2C_KUANG_INVALID, pkt, new byte[0]);
            session.send(MsgIds.S2C_START_ONE_NEW_BATTLE_RET, pkt, new byte[0]);
            log.info("{} kuang nextTeam abort mine gone id={}", rec.account, ctx.kuangId);
            return true;
        }
        PlayerRecord holderNow = resolveHolder(slot);
        PlayerRecord expected = resolveByAccount(ctx.foeAccount);
        if (holderNow == null || expected == null || holderNow.playerId != expected.playerId
                || hasPendingOccupyOnMine(ctx.kuangId, rec.account)) {
            fightByAccount.remove(rec.account);
            launchByAccount.remove(rec.account);
            syncMineFightState(slot);
            world.markMinesDirty();
            session.send(MsgIds.S2C_KUANG_INVALID, pkt, new byte[0]);
            session.send(MsgIds.S2C_START_ONE_NEW_BATTLE_RET, pkt, new byte[0]);
            broadcastRefreshMine(slot.id, rec.account);
            log.info("{} kuang nextTeam abort holderChanged/pending id={}", rec.account, ctx.kuangId);
            return true;
        }
        if (isNewRound) {
            ctx.teamIndex++;
        }
        PlayerRecord foe;
        List<String> foeSlots;
        if (ctx.teamIndex <= 0) {
            foe = resolveHolder(slot);
            foeSlots = slotDefSlots(slot, foe);
        } else {
            ensureCoDefs(slot);
            int ci = ctx.teamIndex - 1;
            if (ci < 0 || ci >= slot.coDefs.size()) {
                session.send(MsgIds.S2C_START_ONE_NEW_BATTLE_RET, pkt, new byte[0]);
                return true;
            }
            WorldStore.CoDefender c = slot.coDefs.get(ci);
            foe = resolveByAccount(c.account);
            if (foe == null) {
                foe = findByPlayerId(c.playerId);
            }
            foeSlots = c.wj;
        }
        if (foe == null) {
            session.send(MsgIds.S2C_START_ONE_NEW_BATTLE_RET, pkt, new byte[0]);
            return true;
        }
        foe.ensureCollections();
        launchByAccount.put(rec.account, new FightLaunch(foe, foeSlots));
        session.send(MsgIds.S2C_KUANG_FIGHT, pkt, dump.kuangFightTeams(rec, foe, foeSlots));
        session.send(MsgIds.S2C_START_ONE_NEW_BATTLE_RET, pkt, new byte[0]);
        log.info("{} kuang nextTeam idx={} foe={}", rec.account, ctx.teamIndex, foe.account);
        return true;
        } // synchronized mineLock
    }

    /** 战报详情/未接协议：回对应空 S2C，禁止误回 2101。 */
    public void onEmpty(GameSession session, GamePacket pkt, int s2c) {
        session.send(s2c, pkt, new byte[0]);
    }

    // ---- 假服唯一主动占矿：空位补 npc_kfz_*（积满/撤离/开战与真人同一套逻辑）----

    /** 前 N 页空位 putIfAbsent 假号；不在此清积满（走 settleFullMines）。 */
    void ensureNpcHolders(String reason) {
        List<PlayerRecord> npcs = listNpcs();
        if (npcs.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean dirty = false;
        boolean pageChanged = false;
        // 迁移：清掉旧假 ID（type*10000+…），改走 QiangKuang.txt 真 ID（与积满同一清槽）
        List<Integer> legacy = new ArrayList<>();
        for (Integer id : world.mines().keySet()) {
            if (id != null && id.intValue() >= 10000 && tables.mineRow(id.intValue()) == null) {
                legacy.add(id);
            }
        }
        for (Integer id : legacy) {
            synchronized (mineLock(id.intValue())) {
                WorldStore.MineSlot gone = world.mines().get(id);
                if (gone == null || gone.fighting) {
                    continue;
                }
                payoutFullAndClear(gone);
                dirty = true;
                pageChanged = true;
            }
        }
        for (int type = 1; type <= 3; type++) {
            int maxPage = Math.min(NPC_FILL_PAGES, tables.minePageCount(type));
            for (int page = 1; page <= maxPage; page++) {
                for (Integer idObj : tables.mineIdsOnPage(type, page)) {
                    int id = idObj.intValue();
                    if (world.mines().containsKey(Integer.valueOf(id))) {
                        continue;
                    }
                    PlayerRecord npc = pickRandomNpc(npcs);
                    if (npc == null) {
                        continue;
                    }
                    WorldStore.MineSlot slot = new WorldStore.MineSlot();
                    slot.id = id;
                    applyHolder(slot, npc, now, true);
                    slot.npcHolder = true;
                    slot.defWj = new ArrayList<>(npc.formationSlots(PlayerRecord.FORMATION_QIANGKUANG_DEF));
                    synchronized (mineLock(id)) {
                        // putIfAbsent：勿盖真人刚占的空矿
                        if (world.mines().putIfAbsent(Integer.valueOf(id), slot) == null) {
                            dirty = true;
                            pageChanged = true;
                        }
                    }
                }
            }
        }
        if (dirty) {
            world.saveMines();
            log.info("mine npc fill reason={} mines={}", reason, world.mines().size());
        }
        if (pageChanged) {
            broadcastRefreshViewers(null, null, null);
        }
    }

    private List<PlayerRecord> listNpcs() {
        List<PlayerRecord> out = new ArrayList<>();
        for (PlayerRecord p : store.all()) {
            if (p != null && p.npcPassive && p.account != null
                    && p.account.startsWith(KfzNpcBootstrap.ACCOUNT_PREFIX)) {
                out.add(p);
            }
        }
        return out;
    }

    private PlayerRecord pickRandomNpc(List<PlayerRecord> npcs) {
        if (npcs.isEmpty()) {
            return null;
        }
        // 优先未占矿的；全占满则任意
        List<PlayerRecord> free = new ArrayList<>();
        for (PlayerRecord p : npcs) {
            if (!npcHoldsAny(p.playerId)) {
                free.add(p);
            }
        }
        List<PlayerRecord> pool = free.isEmpty() ? npcs : free;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private boolean npcHoldsAny(int playerId) {
        for (WorldStore.MineSlot s : world.mines().values()) {
            if (s.holderId == playerId) {
                return true;
            }
        }
        return false;
    }

    // ---- helpers ----

    private void applyHolder(WorldStore.MineSlot occ, PlayerRecord holder, long now, boolean resetClock) {
        occ.holderId = holder.playerId;
        occ.account = holder.account == null ? "" : holder.account;
        occ.name = holder.roleName == null ? "" : holder.roleName;
        occ.level = holder.level;
        occ.npcHolder = holder.npcPassive;
        if (resetClock) {
            occ.occupiedAt = now;
            occ.lastCollectAt = now;
        }
    }

    private void applyDefFormation(PlayerRecord rec, WorldStore.MineSlot occ, List<String> five) {
        boolean emptyDef = true;
        for (String s : five) {
            if (s != null && !s.isEmpty()) {
                emptyDef = false;
                break;
            }
        }
        List<String> slots;
        if (emptyDef) {
            slots = new ArrayList<>(rec.formationSlots(PlayerRecord.FORMATION_PVE));
        } else {
            slots = new ArrayList<>(five);
        }
        while (slots.size() < 5) {
            slots.add("");
        }
        if (occ != null) {
            occ.defWj = new ArrayList<>(slots);
        }
        // type3 仍作布阵 UI 工作集
        rec.setFormationSlots(PlayerRecord.FORMATION_QIANGKUANG_DEF, slots);
    }

    private static List<String> readFiveWj(List<String> wjs) {
        List<String> five = new ArrayList<>(5);
        if (wjs != null) {
            for (String g : wjs) {
                five.add(g == null ? "" : g);
                if (five.size() >= 5) {
                    break;
                }
            }
        }
        while (five.size() < 5) {
            five.add("");
        }
        return five;
    }

    private void applyLeaveKeepToOldHolder(WorldStore.MineSlot occ) {
        PlayerRecord old = resolveHolder(occ);
        if (old == null || old.npcPassive) {
            return;
        }
        int[] pend = pendingOne(occ);
        EconomyTables.MineCommon m = tables.mine();
        int g = (int) Math.floor(pend[0] * m.leaveKeepRatio);
        int d = (int) Math.floor(pend[1] * m.leaveKeepRatio);
        if (g <= 0 && d <= 0) {
            return;
        }
        old.gold += g;
        old.diamond += d;
        store.save(old);
        GameSession ses = sessions.get(old.account);
        if (ses != null) {
            if (g > 0) {
                ses.send(MsgIds.S2C_ATTRI_UPDATE, 0, dump.attri(3, old.gold));
            }
            if (d > 0) {
                ses.send(MsgIds.S2C_ATTRI_UPDATE, 0, dump.attri(4, old.diamond));
            }
            ses.send(MsgIds.S2C_KUANG_EARNINGS, 0, dump.kuangEarnings(occ.id, d, g));
        }
    }

    /**
     * 积满清矿（表可开采时长，多为 12h）：fighting 中不删；有可领产出则全额入账→2106→2115。
     * 假号/真人同一路径；假号无钱包则只清槽，空位由 ensureNpcHolders 再占。
     */
    private void settleFullMines() {
        List<Integer> full = new ArrayList<>();
        for (Map.Entry<Integer, WorldStore.MineSlot> e : world.mines().entrySet()) {
            WorldStore.MineSlot s = e.getValue();
            if (s == null || s.fighting) {
                continue;
            }
            if (leftSec(s) <= 0) {
                full.add(e.getKey());
            }
        }
        if (full.isEmpty()) {
            return;
        }
        Collections.sort(full);
        boolean dirty = false;
        for (Integer id : full) {
            synchronized (mineLock(id.intValue())) {
                WorldStore.MineSlot slot = world.mines().get(id);
                if (slot == null || slot.fighting || leftSec(slot) > 0) {
                    continue;
                }
                payoutFullAndClear(slot);
                dirty = true;
            }
        }
        if (dirty) {
            world.saveMines();
            broadcastRefreshViewers(null, null, null);
        }
    }

    /** 全额入账（仅非 npcPassive）并清槽；调用方已持 mineLock。 */
    private void payoutFullAndClear(WorldStore.MineSlot slot) {
        if (slot == null) {
            return;
        }
        PlayerRecord holder = resolveHolder(slot);
        int[] pend = pendingOne(slot);
        if (holder != null && !holder.npcPassive && (pend[0] > 0 || pend[1] > 0)) {
            holder.gold += pend[0];
            holder.diamond += pend[1];
            store.save(holder);
            GameSession hs = sessions.get(holder.account);
            if (hs != null) {
                if (pend[0] > 0) {
                    hs.send(MsgIds.S2C_ATTRI_UPDATE, 0, dump.attri(3, holder.gold));
                }
                if (pend[1] > 0) {
                    hs.send(MsgIds.S2C_ATTRI_UPDATE, 0, dump.attri(4, holder.diamond));
                }
                hs.send(MsgIds.S2C_KUANG_EARNINGS, 0, dump.kuangEarnings(slot.id, pend[1], pend[0]));
                hs.send(MsgIds.S2C_KUANG_CLEAR, 0, dump.int1(slot.id));
            }
            log.info("{} kuang full settle id={} g={} d={}", holder.account, slot.id, pend[0], pend[1]);
        }
        releaseCoDefs(slot);
        world.mines().remove(Integer.valueOf(slot.id));
    }

    /** 业务入口懒日清：跨日推 2125 + attri13/14（与心跳一致）。 */
    private void ensureMineDaily(GameSession session, GamePacket pkt, PlayerRecord rec) {
        if (rec == null || !progress.ensureDaily(rec)) {
            return;
        }
        store.save(rec);
        session.send(MsgIds.S2C_KUANG_RESET_RELIVE, pkt, new byte[0]);
        progress.pushDefenseKuang(session, pkt, rec);
        progress.pushCoDefenseKuang(session, pkt, rec);
        log.info("{} kuang ensureDaily → S2C 2125 + attri13/14", rec.account);
    }

    /**
     * S2C 2118：只推给最近拉过矿页（1901）的在线客户端；可选按 type/page 过滤，并排除操作者。
     * 对齐真服：未开矿洞 UI / 未浏览过的人不推；自己占/清矿也不弹「矿区变化」窗。
     */
    private void broadcastRefreshMine(int kuangId, String excludeAccount) {
        int type = tables.mineType(kuangId);
        int page = tables.minePageOf(kuangId);
        broadcastRefreshViewers(Integer.valueOf(type), page > 0 ? Integer.valueOf(page) : null, excludeAccount);
    }

    private void broadcastRefreshViewers(Integer typeFilter, Integer pageFilter, String excludeAccount) {
        byte[] empty = new byte[0];
        for (GameSession s : sessions.onlineSnapshot()) {
            PlayerRecord p = s.player();
            if (p == null || p.account == null) {
                continue;
            }
            if (excludeAccount != null && excludeAccount.equals(p.account)) {
                continue;
            }
            PageView v = pageViewByAccount.get(p.account);
            if (v == null) {
                continue;
            }
            if (typeFilter != null && v.type != typeFilter.intValue()) {
                continue;
            }
            // 寻空矿模式跨页扫空位：同 type 即推；普通浏览只推同页
            if (pageFilter != null && !v.emptyOnly && v.page != pageFilter.intValue()) {
                continue;
            }
            s.send(MsgIds.S2C_KUANG_REFRESH_PAGE, 0, empty);
        }
    }

    private void appendRecords(PlayerRecord atk, PlayerRecord def, FightCtx ctx, boolean atkWin,
                               int plunderG, int plunderD, List<PlayerRecord.QkFightRound> rounds) {
        int type = tables.mineType(ctx.kuangId);
        String time = PlayerDumpService.now();
        String atkName = atk.roleName == null ? "" : atk.roleName;
        String defName = def == null || def.roleName == null ? "" : def.roleName;
        int atkRes = atk.mainHeroIndex;
        int atkLv = atk.level;
        int defRes = def == null ? 0 : def.mainHeroIndex;
        int defLv = def == null ? 0 : def.level;
        EconomyTables.MineCommon m = tables.mine();
        int keep = m.recordKeepN;

        PlayerRecord.QkFightRecord a = new PlayerRecord.QkFightRecord();
        a.attackerName = atkName;
        a.fightStartTime = time;
        a.type = type;
        a.isAttack = true;
        a.isZhanLing = !ctx.isRob && atkWin;
        a.isWin = atkWin;
        a.isCoDefense = ctx.teamIndex > 0;
        a.jinBiLose = plunderG;
        a.rmbLose = plunderD;
        a.foeName = defName;
        a.foeResId = defRes;
        a.foeLevel = defLv;
        a.rounds = copyRounds(rounds);
        pushRecord(atk, a, keep);

        if (def != null) {
            PlayerRecord.QkFightRecord d = new PlayerRecord.QkFightRecord();
            d.attackerName = atkName;
            d.fightStartTime = time;
            d.type = type;
            d.isAttack = false;
            d.isZhanLing = !ctx.isRob && atkWin;
            d.isWin = !atkWin;
            d.isCoDefense = ctx.teamIndex > 0;
            d.jinBiLose = plunderG;
            d.rmbLose = plunderD;
            d.foeName = atkName;
            d.foeResId = atkRes;
            d.foeLevel = atkLv;
            d.rounds = copyRounds(rounds);
            pushRecord(def, d, keep);
        }
    }

    private static List<PlayerRecord.QkFightRound> copyRounds(List<PlayerRecord.QkFightRound> src) {
        if (src == null || src.isEmpty()) {
            return new ArrayList<>();
        }
        List<PlayerRecord.QkFightRound> out = new ArrayList<>(src.size());
        for (PlayerRecord.QkFightRound r : src) {
            if (r == null) {
                continue;
            }
            PlayerRecord.QkFightRound c = new PlayerRecord.QkFightRound();
            c.isWin = r.isWin;
            c.selfHurts = r.selfHurts == null ? new ArrayList<>() : new ArrayList<>(r.selfHurts);
            c.targetHurts = r.targetHurts == null ? new ArrayList<>() : new ArrayList<>(r.targetHurts);
            out.add(c);
        }
        return out;
    }

    private static void pushRecord(PlayerRecord rec, PlayerRecord.QkFightRecord r, int keep) {
        rec.ensureCollections();
        rec.qkFightRecords.add(0, r);
        while (rec.qkFightRecords.size() > keep) {
            rec.qkFightRecords.remove(rec.qkFightRecords.size() - 1);
        }
    }

    private static int clampRob(int pending, double ratio, int cap) {
        int v = (int) Math.floor(pending * ratio);
        if (v > cap) {
            v = cap;
        }
        return Math.max(0, v);
    }

    private void shrinkPendingAfterRob(WorldStore.MineSlot slot, double keepRatio, long now) {
        EconomyTables.MineRow row = tables.mineRowOrTypeFallback(slot.id);
        long fillMs = row == null ? 12L * 3600L * 1000L : row.fillMs();
        long from = slot.lastCollectAt > 0 ? slot.lastCollectAt : slot.occupiedAt;
        long elapsed = Math.max(0L, now - from);
        if (elapsed > fillMs) {
            elapsed = fillMs;
        }
        long keepElapsed = (long) Math.floor(elapsed * keepRatio);
        slot.lastCollectAt = now - keepElapsed;
    }

    private PlayerRecord resolveMineFoe(int kuangId, PlayerRecord self) {
        WorldStore.MineSlot slot = world.mines().get(Integer.valueOf(kuangId));
        if (slot == null || slot.holderId <= 0 || slot.holderId == self.playerId) {
            return null;
        }
        return resolveHolder(slot);
    }

    private PlayerRecord resolveByAccount(String account) {
        if (account == null || account.isEmpty()) {
            return null;
        }
        return store.get(account);
    }

    private PlayerRecord resolveHolder(WorldStore.MineSlot occ) {
        if (occ == null) {
            return null;
        }
        if (occ.account != null && !occ.account.isEmpty()) {
            PlayerRecord byAcc = store.get(occ.account);
            if (byAcc != null) {
                byAcc.ensureCollections();
                return byAcc;
            }
        }
        for (PlayerRecord p : store.all()) {
            if (p.playerId == occ.holderId) {
                p.ensureCollections();
                return p;
            }
        }
        return null;
    }

    private int defenseFightPower(PlayerRecord holder, List<String> slots) {
        if (holder == null) {
            return 0;
        }
        List<String> use = slots;
        if (use == null || use.isEmpty()) {
            use = holder.formationSlots(PlayerRecord.FORMATION_QIANGKUANG_DEF);
        }
        int total = 0;
        for (String id : use) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            PlayerRecord.Hero h = holder.findHero(id);
            if (h == null) {
                continue;
            }
            total += cultivate.computeFightPower(holder, h);
        }
        return total;
    }

    private List<String> slotDefSlots(WorldStore.MineSlot slot, PlayerRecord holder) {
        if (slot != null && slot.defWj != null && !slot.defWj.isEmpty()) {
            return new ArrayList<>(slot.defWj);
        }
        if (holder == null) {
            return null;
        }
        return new ArrayList<>(holder.formationSlots(PlayerRecord.FORMATION_QIANGKUANG_DEF));
    }

    private List<WorldStore.MineSlot> listOwnedMines(int playerId) {
        List<WorldStore.MineSlot> out = new ArrayList<>();
        for (WorldStore.MineSlot s : world.mines().values()) {
            if (s != null && s.holderId == playerId) {
                out.add(s);
            }
        }
        return out;
    }

    /** 攻方断线：非 pending 释放本攻方槽；pending 占矿胜保留 ctx。 */
    public void onAttackerDisconnect(String account) {
        if (account == null || account.isEmpty()) {
            return;
        }
        FightCtx ctx = fightByAccount.get(account);
        if (ctx == null) {
            return;
        }
        synchronized (mineLock(ctx.kuangId)) {
            FightCtx live = fightByAccount.get(account);
            if (live != ctx) {
                return;
            }
            if (ctx.pendingOccupy) {
                WorldStore.MineSlot slot = world.mines().get(Integer.valueOf(ctx.kuangId));
                if (slot != null) {
                    slot.fighting = true;
                    world.markMinesDirty();
                }
                log.info("{} kuang disconnect keep pendingOccupy id={}", account, ctx.kuangId);
                return;
            }
            fightByAccount.remove(account);
            launchByAccount.remove(account);
            WorldStore.MineSlot slot = world.mines().get(Integer.valueOf(ctx.kuangId));
            if (slot == null) {
                return;
            }
            syncMineFightState(slot);
            world.markMinesDirty();
            broadcastRefreshMine(slot.id, null);
            log.info("{} kuang disconnect leave attackers={} id={}", account, countFightsOnMine(slot.id), ctx.kuangId);
        }
    }

    private Object mineLock(int kuangId) {
        return mineLocks.computeIfAbsent(Integer.valueOf(kuangId), k -> new Object());
    }

    /** 换主后踢掉同矿其他 FightCtx，并推 2128。 */
    private void invalidateOtherFightsOnMine(int kuangId, String excludeAccount) {
        List<String> victims = new ArrayList<>();
        for (Map.Entry<String, FightCtx> e : fightByAccount.entrySet()) {
            FightCtx c = e.getValue();
            if (c == null || c.kuangId != kuangId) {
                continue;
            }
            if (excludeAccount != null && excludeAccount.equals(e.getKey())) {
                continue;
            }
            victims.add(e.getKey());
        }
        for (String account : victims) {
            fightByAccount.remove(account);
            launchByAccount.remove(account);
            GameSession ses = sessions.get(account);
            if (ses != null) {
                ses.send(MsgIds.S2C_KUANG_INVALID, 0, new byte[0]);
            }
            log.info("{} kuang invalidate after hold change id={}", account, kuangId);
        }
    }

    /** 拒战：空 2107 → 客户端弹 100413。 */
    private void refuseFight(GameSession session, GamePacket pkt, String reason) {
        PlayerRecord rec = session.player();
        log.info("{} kuang fight refuse reason={}", rec == null ? "?" : rec.account, reason);
        session.send(MsgIds.S2C_KUANG_FIGHT, pkt, dump.kuangFightTeamsEmpty());
    }

    /** 表开放等级 + 可选通关 ID（APK KuangCommonProperty）。 */
    private boolean isMineOpen(PlayerRecord rec) {
        EconomyTables.MineCommon m = tables.mine();
        if (m.openLevel > 0 && rec.level < m.openLevel) {
            return false;
        }
        if (m.openAfterMainFb > 0 && rec.progress != null
                && rec.progress.lastNormalStage < m.openAfterMainFb) {
            return false;
        }
        return true;
    }

    /** 客户端 QuBa：Hour&gt;22 或 Hour&lt;10 禁战（上海时区）。 */
    private static boolean isMineFightHour() {
        int hour = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).getHour();
        return !(hour > 22 || hour < 10);
    }

    /**
     * 解析 C2S 1908 field3 FightRecords → 各回合 Hurt（攻方视角）。
     */
    private List<PlayerRecord.QkFightRound> parseFightRounds(Pb.Fields body, PlayerRecord atk) {
        List<PlayerRecord.QkFightRound> out = new ArrayList<>();
        byte[] detail = body.getBytes(3);
        if (detail == null || detail.length == 0) {
            return out;
        }
        Pb.Fields detailF = Pb.read(detail);
        for (byte[] roundBytes : detailF.getBytesList(1)) {
            if (roundBytes == null || roundBytes.length == 0) {
                continue;
            }
            Pb.Fields rf = Pb.read(roundBytes);
            PlayerRecord.QkFightRound r = new PlayerRecord.QkFightRound();
            r.isWin = rf.getBool(1);
            for (byte[] hb : rf.getBytesList(2)) {
                PlayerRecord.QkFightHurt h = parseHurtToSvr(hb, atk);
                if (h != null) {
                    r.selfHurts.add(h);
                }
            }
            for (byte[] hb : rf.getBytesList(4)) {
                PlayerRecord.QkFightHurt h = parseHurtToSvr(hb, null);
                if (h != null) {
                    r.targetHurts.add(h);
                }
            }
            out.add(r);
        }
        return out;
    }

    private PlayerRecord.QkFightHurt parseHurtToSvr(byte[] raw, PlayerRecord owner) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        Pb.Fields f = Pb.read(raw);
        PlayerRecord.QkFightHurt h = new PlayerRecord.QkFightHurt();
        h.wjIndex = f.getInt(1, 0);
        h.hurts = f.getInt(2, 0);
        if (owner != null && h.wjIndex > 0) {
            PlayerRecord.Hero hero = owner.findHeroByIndex(h.wjIndex);
            if (hero != null) {
                h.level = hero.level;
                h.stage = hero.stage;
                h.stars = hero.stars;
            }
        }
        return h;
    }

    /** selfSide=true 填 selfHurts；false 填 targetHurts。 */
    private static void enrichHurtBriefs(List<PlayerRecord.QkFightRound> rounds, PlayerRecord owner, boolean selfSide) {
        if (rounds == null || owner == null) {
            return;
        }
        for (PlayerRecord.QkFightRound r : rounds) {
            if (r == null) {
                continue;
            }
            List<PlayerRecord.QkFightHurt> list = selfSide ? r.selfHurts : r.targetHurts;
            if (list == null) {
                continue;
            }
            for (PlayerRecord.QkFightHurt h : list) {
                if (h == null || h.wjIndex <= 0 || h.level > 0) {
                    continue;
                }
                PlayerRecord.Hero hero = owner.findHeroByIndex(h.wjIndex);
                if (hero != null) {
                    h.level = hero.level;
                    h.stage = hero.stage;
                    h.stars = hero.stars;
                }
            }
        }
    }

    private int leftSec(WorldStore.MineSlot occ) {
        if (occ == null) {
            return 0;
        }
        EconomyTables.MineRow row = tables.mineRowOrTypeFallback(occ.id);
        long fillMs = row == null ? 12L * 3600L * 1000L : row.fillMs();
        long from = occ.occupiedAt > 0 ? occ.occupiedAt : System.currentTimeMillis();
        long end = from + fillMs;
        long left = (end - System.currentTimeMillis()) / 1000L;
        return (int) Math.max(0L, left);
    }

    private int protectLeft(WorldStore.MineSlot occ) {
        if (occ == null || occ.protectRobUntil <= 0) {
            return 0;
        }
        long left = (occ.protectRobUntil - System.currentTimeMillis()) / 1000L;
        return (int) Math.max(0L, left);
    }

    private int rebuildLeft(WorldStore.MineSlot occ) {
        if (occ == null || occ.rebuildUntil <= 0) {
            return 0;
        }
        long left = (occ.rebuildUntil - System.currentTimeMillis()) / 1000L;
        return (int) Math.max(0L, left);
    }

    /**
     * 累计未领产出：QiangKuang 金/钻速(每小时) × 自 lastCollect 起流逝小时，封顶可开采时长。
     */
    private int[] pendingOne(WorldStore.MineSlot occ) {
        if (occ == null) {
            return new int[]{0, 0};
        }
        EconomyTables.MineRow row = tables.mineRowOrTypeFallback(occ.id);
        if (row == null) {
            return new int[]{0, 0};
        }
        long from = occ.lastCollectAt > 0 ? occ.lastCollectAt : occ.occupiedAt;
        long elapsed = Math.max(0L, System.currentTimeMillis() - from);
        long fillMs = row.fillMs();
        if (elapsed > fillMs) {
            elapsed = fillMs;
        }
        int gold = (int) (row.goldPerHour * elapsed / 3600000L);
        int diamond = (int) (row.diamondPerHour * elapsed / 3600000L);
        return new int[]{gold, diamond};
    }

    private int[] pending(int playerId) {
        int gold = 0;
        int diamond = 0;
        for (WorldStore.MineSlot occ : world.mines().values()) {
            if (occ.holderId != playerId) {
                continue;
            }
            int[] one = pendingOne(occ);
            gold += one[0];
            diamond += one[1];
        }
        return new int[]{gold, diamond};
    }

    private static final class FightCtx {
        int kuangId;
        boolean isRob;
        String foeAccount = "";
        boolean pendingOccupy;
        /** 0=矿主；1+=coDefs[index-1]。 */
        int teamIndex;
        /** 开战或进入 pendingOccupy 的时间，供超时解锁。 */
        long lockSinceMs;
        /** 开战时防守队数（矿主+协防），ForceExist 超时 = 队数×3min。 */
        int defenseTeams = 1;
    }

    private int countFightsOnMine(int kuangId) {
        int n = 0;
        for (FightCtx c : fightByAccount.values()) {
            if (c != null && c.kuangId == kuangId) {
                n++;
            }
        }
        return n;
    }

    /** excludeAccount 非空时忽略该账号（用于「自己刚胜占矿」判断他人是否已 pending）。 */
    private boolean hasPendingOccupyOnMine(int kuangId, String excludeAccount) {
        for (Map.Entry<String, FightCtx> e : fightByAccount.entrySet()) {
            FightCtx c = e.getValue();
            if (c == null || c.kuangId != kuangId || !c.pendingOccupy) {
                continue;
            }
            if (excludeAccount != null && excludeAccount.equals(e.getKey())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private long fightLockLimitMs(FightCtx c) {
        if (c == null) {
            return FIGHT_LOCK_PER_TEAM_MS;
        }
        if (c.pendingOccupy) {
            return PENDING_OCCUPY_TIMEOUT_MS;
        }
        int teams = Math.max(1, c.defenseTeams);
        return teams * FIGHT_LOCK_PER_TEAM_MS;
    }

    /** 按仍在打的攻方重算 fighting + 2117（矿主与各攻方战斗 UI）。 */
    private void syncMineFightState(WorldStore.MineSlot slot) {
        if (slot == null) {
            return;
        }
        int n = countFightsOnMine(slot.id);
        slot.fighting = n > 0;
        pushAttackerCnt(slot, n);
    }

    /** 无 FightCtx 却仍 fighting 的矿 → 清锁（重启/异常残留）。 */
    private void clearOrphanFighting(String reason) {
        boolean dirty = false;
        for (WorldStore.MineSlot s : world.mines().values()) {
            if (s == null || !s.fighting) {
                continue;
            }
            boolean held = false;
            for (FightCtx c : fightByAccount.values()) {
                if (c != null && c.kuangId == s.id) {
                    held = true;
                    break;
                }
            }
            if (held) {
                continue;
            }
            s.fighting = false;
            syncMineFightState(s);
            dirty = true;
            log.info("kuang clear orphan fighting id={} reason={}", s.id, reason);
        }
        if (dirty) {
            world.saveMines();
            broadcastRefreshViewers(null, null, null);
        }
    }

    /** pendingOccupy / 开战挂起超时 → 释锁并丢 ctx（产品防卡死）。 */
    private void expireStaleFightLocks() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, FightCtx> e : fightByAccount.entrySet()) {
            FightCtx c = e.getValue();
            if (c == null || c.lockSinceMs <= 0) {
                continue;
            }
            long limit = fightLockLimitMs(c);
            if (now - c.lockSinceMs >= limit) {
                expired.add(e.getKey());
            }
        }
        if (expired.isEmpty()) {
            return;
        }
        boolean dirty = false;
        for (String account : expired) {
            FightCtx c = fightByAccount.get(account);
            if (c == null) {
                continue;
            }
            synchronized (mineLock(c.kuangId)) {
                FightCtx live = fightByAccount.get(account);
                if (live != c) {
                    continue;
                }
                long limit = fightLockLimitMs(c);
                if (System.currentTimeMillis() - c.lockSinceMs < limit) {
                    continue;
                }
                fightByAccount.remove(account);
                launchByAccount.remove(account);
                WorldStore.MineSlot slot = world.mines().get(Integer.valueOf(c.kuangId));
                if (slot != null) {
                    syncMineFightState(slot);
                    dirty = true;
                    broadcastRefreshMine(slot.id, null);
                }
                GameSession ses = sessions.get(account);
                if (ses != null) {
                    ses.send(MsgIds.S2C_KUANG_INVALID, 0, new byte[0]);
                }
                log.info("{} kuang fight lock expired id={} pendingOccupy={}",
                        account, c.kuangId, c.pendingOccupy);
            }
        }
        if (dirty) {
            world.saveMines();
        }
    }

    /** 寻空：返回首个含空位的页号；全无返回 -1。 */
    private int findFirstEmptyPage(int type) {
        int pages = tables.minePageCount(type);
        for (int p = 1; p <= pages; p++) {
            for (Integer idObj : tables.mineIdsOnPage(type, p)) {
                if (!world.mines().containsKey(idObj)) {
                    return p;
                }
            }
        }
        return -1;
    }

    /** 武将总数 − 仍在复活 CD 的死将数（对齐客户端 WuJiangData − Dead）。 */
    private int countAliveAtkWj(PlayerRecord rec) {
        if (rec == null) {
            return 0;
        }
        rec.ensureCollections();
        pruneDeadWjs(rec);
        int total = rec.heroes == null ? 0 : rec.heroes.size();
        int dead = 0;
        long now = System.currentTimeMillis();
        if (rec.qkDeadWjs != null) {
            for (PlayerRecord.QkDeadWj d : rec.qkDeadWjs) {
                if (d != null && d.reliveUntilMs > now) {
                    dead++;
                }
            }
        }
        return Math.max(0, total - dead);
    }

    private static int coDefCnt(WorldStore.MineSlot occ) {
        return occ == null || occ.coDefs == null ? 0 : occ.coDefs.size();
    }

    /** 从当前 FightSync 快照登记攻方阵亡武将（表复活秒）。 */
    public void markDeadFromFight(GameSession session, PlayerRecord rec) {
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        List<FightSyncService.KfzWjSnap> snaps = fightSync.selfWjSnap(session, rec.playerId);
        if (snaps == null || snaps.isEmpty()) {
            return;
        }
        long until = System.currentTimeMillis() + tables.mine().reliveSec * 1000L;
        boolean dirty = false;
        for (FightSyncService.KfzWjSnap s : snaps) {
            if (s == null || !s.dead || s.wjIndex <= 0) {
                continue;
            }
            PlayerRecord.QkDeadWj exist = null;
            for (PlayerRecord.QkDeadWj d : rec.qkDeadWjs) {
                if (d != null && d.wjIndex == s.wjIndex) {
                    exist = d;
                    break;
                }
            }
            if (exist == null) {
                exist = new PlayerRecord.QkDeadWj();
                exist.wjIndex = s.wjIndex;
                rec.qkDeadWjs.add(exist);
            }
            exist.reliveUntilMs = until;
            // 重新阵亡 = 新的病人：清掉「已被医师治疗过」标记（1931 f3），否则同一武将
            // 二次阵亡时客户端会一直显示「已资料」、资料按钮永久禁用。条目可能是
            // pruneDeadWjs 还没来得及清掉的旧记录，故必须在此显式重置。
            exist.emergencyTreated = false;
            dirty = true;
        }
        if (dirty) {
            store.save(rec);
        }
    }

    private void pruneDeadWjs(PlayerRecord rec) {
        if (rec.qkDeadWjs == null || rec.qkDeadWjs.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        rec.qkDeadWjs.removeIf(d -> d == null || d.reliveUntilMs <= now);
    }

    private int countOwnedMines(int playerId) {
        int n = 0;
        for (WorldStore.MineSlot s : world.mines().values()) {
            if (s != null && s.holderId == playerId) {
                n++;
            }
        }
        return n;
    }

    /** S2C 2108：仅 tip 从 false→true 时推一次（登录靠 detail 1007；勿每分钟刷）。 */
    private void pushResTipsIfNeeded(PlayerRecord holder) {
        if (holder == null || holder.npcPassive) {
            return;
        }
        int[] pend = pending(holder.playerId);
        if (pend[0] <= 0 && pend[1] <= 0) {
            return;
        }
        if (holder.qkResourceTip) {
            return;
        }
        holder.qkResourceTip = true;
        store.save(holder);
        GameSession hs = sessions.get(holder.account);
        if (hs != null) {
            hs.send(MsgIds.S2C_KUANG_RES_TIPS, 0, new byte[0]);
        }
    }

    /** tick：在线矿主有待领产出 → 2108。 */
    private void pushPendingResourceTips() {
        for (GameSession s : sessions.onlineSnapshot()) {
            PlayerRecord rec = s.player();
            if (rec == null || rec.npcPassive) {
                continue;
            }
            pushResTipsIfNeeded(rec);
        }
    }

    private static void ensureCoDefs(WorldStore.MineSlot slot) {
        if (slot.coDefs == null) {
            slot.coDefs = new ArrayList<>();
        }
    }

    /** 矿清空/换主时退还协防人数计数，并清空列表。 */
    private void releaseCoDefs(WorldStore.MineSlot slot) {
        if (slot == null || slot.coDefs == null || slot.coDefs.isEmpty()) {
            return;
        }
        for (WorldStore.CoDefender c : slot.coDefs) {
            if (c == null) {
                continue;
            }
            PlayerRecord co = resolveByAccount(c.account);
            if (co == null) {
                co = findByPlayerId(c.playerId);
            }
            if (co == null) {
                continue;
            }
            if (co.coDefenseKuangCnt > 0) {
                co.coDefenseKuangCnt--;
            }
            store.save(co);
            GameSession cs = sessions.get(co.account);
            if (cs != null) {
                progress.pushCoDefenseKuang(cs, new GamePacket(0, 0, new byte[0]), co);
            }
        }
        slot.coDefs.clear();
    }

    /** S2C 2117：进攻人数给矿主 + 本矿各攻方（战斗 UI 100641/100642）。 */
    private void pushAttackerCnt(WorldStore.MineSlot slot, int cnt) {
        if (slot == null) {
            return;
        }
        byte[] body = dump.kuangAttackerCnt(cnt);
        PlayerRecord holder = resolveHolder(slot);
        if (holder != null && !holder.npcPassive) {
            GameSession hs = sessions.get(holder.account);
            if (hs != null) {
                hs.send(MsgIds.S2C_KUANG_ATTACKER, 0, body);
            }
        }
        for (Map.Entry<String, FightCtx> e : fightByAccount.entrySet()) {
            FightCtx c = e.getValue();
            if (c == null || c.kuangId != slot.id) {
                continue;
            }
            GameSession as = sessions.get(e.getKey());
            if (as != null) {
                as.send(MsgIds.S2C_KUANG_ATTACKER, 0, body);
            }
        }
    }

    private List<byte[]> buildCoDefBriefs(WorldStore.MineSlot occ) {
        List<byte[]> out = new ArrayList<>();
        if (occ == null || occ.coDefs == null) {
            return out;
        }
        for (WorldStore.CoDefender c : occ.coDefs) {
            if (c == null) {
                continue;
            }
            PlayerRecord co = resolveByAccount(c.account);
            if (co == null) {
                co = findByPlayerId(c.playerId);
            }
            int fp = 0;
            if (co != null) {
                co.ensureCollections();
                List<String> slots = c.wj != null ? c.wj : Collections.emptyList();
                for (String id : slots) {
                    if (id == null || id.isEmpty()) {
                        continue;
                    }
                    PlayerRecord.Hero h = co.findHero(id);
                    if (h != null) {
                        fp += cultivate.computeFightPower(co, h);
                    }
                }
            }
            out.add(dump.kuangCoDefBrief(c.name, fp, co, c.wj));
        }
        return out;
    }

    private PlayerRecord findByPlayerId(int playerId) {
        for (PlayerRecord p : store.all()) {
            if (p != null && p.playerId == playerId) {
                return p;
            }
        }
        return null;
    }
}
