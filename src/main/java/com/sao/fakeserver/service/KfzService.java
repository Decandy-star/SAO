package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.store.WorldStore;
import com.sao.fakeserver.table.GameTables;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 跨服战。权威：NetProto.CCMsgKFZ* / APK OnNET_CCMsgKFZ* / KFZ_* UI / KuaFuZhanBase·Prize。
 * 周历：周一～五排位；周六巅峰 day1(8–10)；周日巅峰 day2(11–13)；周日 21:00 JJC 前十入围快照。
 * 对手：真人优先、不足补机器人；三队机器人互异装备模板。
 */
@Service
public class KfzService {
    private static final Logger log = LoggerFactory.getLogger(KfzService.class);
    private static final int REGION = 101;
    private static final int PHASE_PAIWEI = 1;
    private static final int PHASE_DFZ_MIN = 8;
    private static final int PHASE_DFZ_MAX = 13;
    private static final int PYS_STATUS_FIGHTABLE = 1;
    private static final int PYS_STATUS_WAIT = 2;
    private static final int PYS_STATUS_WIN = 3;
    private static final int PYS_STATUS_LOSE = 4;
    private static final int DUIZHAN_MATCHES = 3;
    private static final int ENEMY_COUNT = 5;
    private static final int DFZ_SIZE = 64;
    private static final int SERIES_WIN = 2;
    private static final int SERIES_LOSS = 3;
    private static final int SERIES_KIND_ROBOT = 0;
    private static final int SERIES_KIND_REAL = 1;
    private static final int XIANGXI_TYPE_ATTACK = 1;
    private static final int XIANGXI_TYPE_DEFENCE = 2;
    private static final int INELIGIBLE_STATUS_RANK = -1;

    private final PlayerStore store;
    private final WorldStore world;
    private final PlayerDumpService dump;
    private final GameTables tables;
    private final ProgressService progress;
    private final FightSyncService fightSync;
    private final KfzOpponentPool opponentPool;

    public KfzService(PlayerStore store, WorldStore world, PlayerDumpService dump, GameTables tables,
                      ProgressService progress, FightSyncService fightSync) {
        this.store = store;
        this.world = world;
        this.dump = dump;
        this.tables = tables;
        this.progress = progress;
        this.fightSync = fightSync;
        this.opponentPool = new KfzOpponentPool(store, tables);
    }

    /** 开战 TargetGuid：须 &gt;1e6，客户端走 WJ 列表而非整队读 JJC_Robot。 */
    public static int kfzFightGuid(int robotGuid, int match0) {
        return 2_000_000 + (robotGuid % 100000) * 3 + Math.max(0, Math.min(2, match0));
    }

    public void pushPhaseOnly(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        syncPhaseAndDay(rec);
        session.send(MsgIds.S2C_KFZ_PHASE, pkt, dump.kfzPhase(rec.kfz.phase));
        pushTop3AndWorship(session, pkt, rec);
    }

    /**
     * 主城雕像 = S2C 4706 {@code CCMsgKFZCurrentTop3}（无独立雕像 MsgId）。
     * 进主城(102) / 回城(304) 推送；客户端 KFZ_RuKou 监听后 RefreshModelInDaTing。
     */
    public void pushCityStatues(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        pushTop3AndWorship(session, pkt, rec);
    }

    public void pushStatus(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        syncPhaseAndDay(rec);
        session.send(MsgIds.S2C_KFZ_PHASE, pkt, dump.kfzPhase(rec.kfz.phase));
        session.send(MsgIds.S2C_KFZ_PLAYER_STATUS, pkt, dump.kfzPlayerStatus(statusRank(rec), rec));
        pushTop3AndWorship(session, pkt, rec);
    }

    /** Admin：phaseOverride≥0 强制；传 -1 恢复跟钟。 */
    public void setPhase(PlayerRecord rec, int phase) {
        rec.ensureCollections();
        if (phase < 0) {
            rec.kfz.phaseOverride = -1;
            rec.kfz.phase = computePhase(rec);
        } else {
            rec.kfz.phaseOverride = phase;
            rec.kfz.phase = phase;
            if (isDfzFightPhase(phase)) {
                rec.kfz.hasFightingDfz = false;
                ensureDfzBracket(rec, true);
            }
        }
        store.save(rec);
    }

    /** Admin：强制本周入围（假服单机调试）。 */
    public void setEligibleOverride(PlayerRecord rec, boolean eligible) {
        rec.ensureCollections();
        syncEligibility(rec);
        rec.kfz.eligibleOverride = eligible;
        if (eligible) {
            rec.kfz.eligibleThisWeek = true;
            if (rec.kfz.eligibleWeekId == null || rec.kfz.eligibleWeekId.isEmpty()) {
                rec.kfz.eligibleWeekId = competitionWeekId(GameTime.now());
            }
        }
        store.save(rec);
    }

    public void onZhanKuang(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        byte[] self = selfBrief(rec);
        List<byte[]> items = new ArrayList<>();
        List<RankRow> board = buildKfzLeaderboard();
        int n = Math.min(20, board.size());
        for (int i = 0; i < n; i++) {
            RankRow r = board.get(i);
            items.add(dump.kfzZhanKuangItem(briefOf(r), r.stars, i + 1));
        }
        session.send(MsgIds.S2C_KFZ_ZHAN_KUANG, pkt, dump.kfzZhanKuang(self, items));
    }

    public void onXiangXi(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        byte[] self = selfBrief(rec);
        List<byte[]> items = new ArrayList<>();
        for (PlayerRecord.KfzXiangXi x : rec.kfz.xiangXiReports) {
            if (x == null) {
                continue;
            }
            items.add(dump.kfzXiangXiItem(x.type, x.rank, x.winner, x.winnerServer, x.loser, x.loserServer));
        }
        if (items.isEmpty()) {
            List<GameTables.RobotRow> robots = tables.robots();
            int n = Math.min(3, Math.max(0, robots.size()));
            for (int i = 0; i < n; i++) {
                GameTables.RobotRow a = robots.get(i);
                GameTables.RobotRow b = robots.get((i + 1) % robots.size());
                items.add(dump.kfzXiangXiItem(XIANGXI_TYPE_ATTACK, i + 1, a.name, 1, b.name, 1));
            }
        }
        session.send(MsgIds.S2C_KFZ_XIANGXI, pkt, dump.kfzXiangXi(self, items));
    }

    public void onMingRen(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        byte[] self = selfBrief(rec);
        List<byte[]> items = new ArrayList<>();
        WorldStore.KfzPodiumSlot[] podium = resolveTop3PodiumSlots();
        for (int rank = 1; rank <= 3; rank++) {
            WorldStore.KfzPodiumSlot slot = podium[rank];
            if (slot == null) {
                continue;
            }
            items.add(dump.kfzMingRenItem(rank, rank,
                    dump.kfzPlayerInfo(slot.guid, slot.name,
                            slot.resId > 0 ? slot.resId : 18,
                            slot.level > 0 ? slot.level : 1,
                            slot.serverId > 0 ? slot.serverId : kfzServerIdForGuid(slot.guid))));
        }
        session.send(MsgIds.S2C_KFZ_MINGREN, pkt, dump.kfzMingRen(self, items));
    }

    public void onSaiCheng(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        pushStatus(session, pkt);
        if (isDfzFightPhase(rec.kfz.phase)) {
            ensureDfzBracket(rec, false);
            sendDfzSaiCheng(session, pkt, rec);
            return;
        }
        byte[] self = selfBrief(rec);
        List<byte[]> enemies = new ArrayList<>();
        for (KfzOpponentPool.Opponent o : ensurePysOpponents(rec)) {
            PlayerRecord.KfzPysEnemyResult done = findPysEnemyResult(rec, o.guid);
            int status;
            int myKill = 0;
            int otherKill = 0;
            int myScore = 0;
            int otherScore = 0;
            if (done != null && (done.status == PYS_STATUS_WIN || done.status == PYS_STATUS_LOSE)) {
                status = done.status;
                myKill = done.myKillCount;
                otherKill = done.otherKillCount;
                myScore = done.myScore;
                otherScore = done.otherScore;
            } else if (rec.kfz.seriesTargetGuid == o.guid && seriesInProgress(rec)) {
                // 系列未分胜负：仍可进对阵；赛程保持可打（非「等结果」——等结果留给战斗中）
                status = PYS_STATUS_FIGHTABLE;
                myKill = Math.max(0, rec.kfz.seriesMyKills);
                otherKill = Math.max(0, rec.kfz.seriesFoeKills);
                myScore = Math.max(0, rec.kfz.seriesMyScoreDelta);
                otherScore = Math.max(0, -rec.kfz.seriesMyScoreDelta);
            } else {
                status = PYS_STATUS_FIGHTABLE;
            }
            enemies.add(dump.kfzPysEnemyItem(
                    dump.kfzPlayerInfo(o.guid, o.name, o.resId, o.level, kfzServerIdForGuid(o.guid)),
                    status, myKill, otherKill, myScore, otherScore));
        }
        session.send(MsgIds.S2C_KFZ_PYS_SAICHENG, pkt,
                dump.kfzPysSaiCheng(self, rec.kfz.stars, rec.kfz.score, enemies));
    }

    public void onDuiZhan(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        int targetGuid = Pb.read(pkt.body).getInt(1, 0);
        if (targetGuid <= 0) {
            targetGuid = firstOpponentGuid(rec);
        }
        KfzOpponentPool.Opponent opp = ensureSeries(rec, targetGuid);
        rec.kfz.lastTargetGuid = targetGuid;
        store.save(rec);
        byte[] target = dump.kfzPlayerInfo(opp.guid, opp.name, opp.resId, opp.level,
                kfzServerIdForGuid(opp.guid));
        byte[] mine = selfBrief(rec);
        // 客户端头栏只用 duiZhanList[0].myScore/targetScore；与 4704 一致灌系列累计 Δ
        int seriesMySc = Math.max(0, rec.kfz.seriesMyScoreDelta);
        int seriesFoeSc = Math.max(0, -rec.kfz.seriesMyScoreDelta);
        PlayerRecord.KfzPysEnemyResult done = findPysEnemyResult(rec, targetGuid);
        if (done != null && (done.status == PYS_STATUS_WIN || done.status == PYS_STATUS_LOSE)) {
            seriesMySc = Math.max(0, done.myScore);
            seriesFoeSc = Math.max(0, done.otherScore);
        }
        List<byte[]> matches = new ArrayList<>();
        List<Integer> myWins = new ArrayList<>();
        List<Integer> foeWins = new ArrayList<>();
        int fightIdx = currentFightableMatch(rec);
        for (int i = 0; i < DUIZHAN_MATCHES; i++) {
            int result = seriesResultAt(rec, i);
            int status;
            if (result == SERIES_WIN) {
                status = 2;
            } else if (result == SERIES_LOSS) {
                status = 3;
            } else if (i == fightIdx) {
                status = 1;
            } else {
                status = 0;
            }
            List<Integer> heroes5 = seriesHeroesSlice(rec, i);
            List<byte[]> foeWjs = buildDuiZhanWjs(opp, heroes5, i, rec.level);
            matches.add(dump.kfzDuiZhanItem(foeWjs, status, seriesMySc, seriesFoeSc));
            myWins.add(result == SERIES_WIN ? SERIES_WIN : 0);
            foeWins.add(result == SERIES_LOSS ? SERIES_WIN : 0);
        }
        session.send(MsgIds.S2C_KFZ_DUIZHAN, pkt, dump.kfzDuiZhan(target, mine, matches, myWins, foeWins));
    }

    public void onOffenceBuZhen(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        Pb.Fields body = Pb.read(pkt.body);
        int targetGuid = body.getInt(1, rec.kfz.lastTargetGuid);
        if (targetGuid <= 0) {
            targetGuid = firstOpponentGuid(rec);
        }
        if (rec.kfz.seriesTargetGuid != targetGuid) {
            ensureSeries(rec, targetGuid);
        }
        rec.kfz.lastTargetGuid = targetGuid;
        store.save(rec);
        KfzOpponentPool.Opponent opp = opponentPool.resolveOpponent(rec, targetGuid);
        if (opp == null) {
            opp = ensureSeries(rec, targetGuid);
        }
        byte[] target = dump.kfzPlayerInfo(opp.guid, opp.name, opp.resId, opp.level,
                kfzServerIdForGuid(opp.guid));
        List<byte[]> wjInfo = new ArrayList<>();
        for (PlayerRecord.KfzWjState st : rec.kfz.offenceWj) {
            if (st != null && st.index > 0) {
                wjInfo.add(dump.kfzOffenceWj(st.index, st.hasPlayed, st.isDead));
            }
        }
        session.send(MsgIds.S2C_KFZ_OFFENCE_BUZHEN, pkt,
                dump.kfzOffenceBuZhen(target, selfBrief(rec), wjInfo));
    }

    public void onPysFight(GameSession session, GamePacket pkt) {
        beginFight(session, pkt);
    }

    public void onDfsFight(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        rec.kfz.hasFightingDfz = true;
        store.save(rec);
        beginFight(session, pkt);
        session.send(MsgIds.S2C_KFZ_PLAYER_STATUS, pkt, dump.kfzPlayerStatus(statusRank(rec), rec));
    }

    public void onPysResult(GameSession session, GamePacket pkt) {
        onFightResult(session, pkt, false);
    }

    public void onDfsResult(GameSession session, GamePacket pkt) {
        onFightResult(session, pkt, true);
    }

    public void onRank(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        session.send(MsgIds.S2C_KFZ_RANK, pkt, dump.kfzRankList(buildRankItems(rec)));
    }

    public void onPaiWeiRank(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        byte[] myInfo = selfBrief(rec);
        byte[] myRank = dump.kfzRankItem(myInfo, rec.kfz.rank, rec.kfz.stars, rec.kfz.score);
        session.send(MsgIds.S2C_KFZ_PAIWEI_RANK, pkt, dump.kfzPaiWeiRank(buildRankItems(rec), myRank, myInfo));
    }

    public void onWorship(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        syncWorshipDay(rec);
        int targetGuid = Pb.read(pkt.body).getInt(1, 0);
        int awardedRank = 0;
        WorldStore.KfzPodiumSlot[] podium = resolveTop3PodiumSlots();
        for (int rank = 1; rank <= 3; rank++) {
            WorldStore.KfzPodiumSlot slot = podium[rank];
            if (slot != null && slot.guid == targetGuid) {
                awardedRank = rank;
                break;
            }
        }
        if (awardedRank <= 0) {
            log.info("{} kfz worship denied: guid={} not in top3", rec.account, targetGuid);
            return;
        }
        if (rec.kfz.worshipedRanks.contains(awardedRank)) {
            session.send(MsgIds.S2C_KFZ_WORSHIP_STATUS, pkt, dump.kfzWorshipStatus(rec.kfz.worshipedRanks));
            return;
        }
        GameTables.KfzMatchPrize prize = tables.kfzPrize().moBai(awardedRank);
        if (prize == null) {
            return;
        }
        rec.kfz.worshipedRanks.add(awardedRank);
        grantMatchPrize(session, pkt, rec, prize);
        store.save(rec);
        session.send(MsgIds.S2C_KFZ_WORSHIP_RET, pkt, dump.kfzWorshipRet(awardedRank));
        session.send(MsgIds.S2C_KFZ_WORSHIP_STATUS, pkt, dump.kfzWorshipStatus(rec.kfz.worshipedRanks));
        log.info("{} kfz worship rank={}", rec.account, awardedRank);
    }

    // ---- fight / result ----

    private void beginFight(GameSession session, GamePacket pkt) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int targetGuid = f.getInt(1, rec.kfz.lastTargetGuid);
        if (targetGuid <= 0) {
            targetGuid = firstOpponentGuid(rec);
        }
        boolean dfz = isDfzFightPhase(rec.kfz.phase);
        if (!dfz) {
            PlayerRecord.KfzPysEnemyResult done = findPysEnemyResult(rec, targetGuid);
            if (done != null && (done.status == PYS_STATUS_WIN || done.status == PYS_STATUS_LOSE)) {
                log.info("{} kfz fight denied: target={} already finished status={}",
                        rec.account, targetGuid, done.status);
                return;
            }
        }
        KfzOpponentPool.Opponent opp = ensureSeries(rec, targetGuid);
        int matchIdx = currentFightableMatch(rec);
        if (matchIdx < 0) {
            log.info("{} kfz fight denied: target={} no fightable match (series done)",
                    rec.account, targetGuid);
            return;
        }
        rec.kfz.lastTargetGuid = targetGuid;
        rec.kfz.fightMatchIndex = matchIdx;
        List<Integer> heroes5 = seriesHeroesSlice(rec, matchIdx);
        byte[] other;
        if (opp.kind == KfzOpponentPool.Kind.REAL) {
            rec.kfz.lastFightGuid = opp.guid;
            other = dump.kfzFightOtherTeamFromPlayer(store.findByPlayerId(opp.guid), heroes5, opp.guid);
        } else {
            int templateGuid = seriesTemplateGuid(rec, matchIdx);
            rec.kfz.lastFightGuid = kfzFightGuid(targetGuid, matchIdx);
            GameTables.RobotRow robot = tables.robotByGuid(templateGuid);
            other = dump.kfzFightOtherTeam(robot, heroes5, rec.kfz.lastFightGuid);
        }
        rec.currentRegionId = REGION;
        store.save(rec);
        session.send(MsgIds.S2C_KFZ_FIGHT_TEAMS, pkt,
                dump.jjcFightTeams(dump.jjcFightTargetDetailSelf(rec), other));
        session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(REGION));
        beginFightSync(session, rec, opp, matchIdx, heroes5);
        log.info("{} kfz fight target={} kind={} fightGuid={} match={} phase={}",
                rec.account, targetGuid, opp.kind, rec.kfz.lastFightGuid, matchIdx, rec.kfz.phase);
    }

    /** MessageDispatcher 在 clear 后调用 onPys/DfsFight；开战注册放在本方法。 */
    private void beginFightSync(GameSession session, PlayerRecord rec, KfzOpponentPool.Opponent opp,
                                int matchIdx, List<Integer> heroes5) {
        if (opp.kind == KfzOpponentPool.Kind.REAL) {
            PlayerRecord foe = store.findByPlayerId(opp.guid);
            int form = PlayerRecord.FORMATION_KFZ_DEF1 + Math.max(0, Math.min(2, matchIdx));
            fightSync.beginBattleKfzVsPlayer(session, foe, form);
        } else {
            int templateGuid = seriesTemplateGuid(rec, matchIdx);
            fightSync.beginBattleKfz(session, rec.kfz.lastFightGuid, templateGuid, heroes5);
        }
    }

    private void onFightResult(GameSession session, GamePacket pkt, boolean dfz) {
        PlayerRecord rec = require(session);
        if (rec == null) {
            return;
        }
        boolean hadBattle = fightSync.hasBattle(session);
        boolean win = hadBattle && fightSync.playerWon(session, rec.playerId);
        if (!hadBattle) {
            session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(2, 0));
            return;
        }
        FightSyncService.ZbzBattleSnap snap = fightSync.zbzBattleSnap(session, rec.playerId);
        int myKills = snap.killNum > 0 ? snap.killNum : (win ? 1 : 0);
        int foeKills = Math.max(0, snap.selfDead);
        int foeFp = Math.max(0, snap.foeFp);
        applyOffenceFromBattle(session, rec);
        if (dfz) {
            applyDfzResult(rec, win);
            rec.kfz.hasFightingDfz = false;
            applyScoreDelta(rec, win, myKills, foeFp);
        } else {
            rec.kfz.seriesMyKills += myKills;
            rec.kfz.seriesFoeKills += foeKills;
            int before = rec.kfz.score;
            applySeriesResult(rec, win);
            applyScoreDelta(rec, win, myKills, foeFp);
            rec.kfz.seriesMyScoreDelta += (rec.kfz.score - before);
            maybeFinalizePysEnemyResult(rec);
            applyNpcFoeScoreMirror(rec, win, foeKills, Math.max(0, snap.myFp));
        }
        appendFightXiangXi(rec, win);
        GameTables.KfzMatchPrize prize = dfz
                ? (win ? tables.kfzPrize().dfzWin : tables.kfzPrize().dfzFail)
                : (win ? tables.kfzPrize().pysWin : tables.kfzPrize().pysFail);
        grantMatchPrize(session, pkt, rec, prize);
        store.save(rec);
        session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(win ? 1 : 2, win ? 3 : 0));
        if (dfz) {
            session.send(MsgIds.S2C_KFZ_PLAYER_STATUS, pkt, dump.kfzPlayerStatus(statusRank(rec), rec));
        }
        log.info("{} kfz result dfz={} win={} kill={}/{} foeFp={} score={} phase={}",
                rec.account, dfz, win, myKills, foeKills, foeFp, rec.kfz.score, rec.kfz.phase);
    }

    /**
     * 产品公式（服端独占，客户端不算）：
     * Δ = (win?+winBase:-loseBase) + ⌊stars×starMatchValue⌋
     *   + sign(win) × clamp(0..maxFightScore, ⌊killNum×foeFp×fightScoreA + fightScoreB⌋) × scoreModifier
     * 败场战斗分仍计入但取负；总分 clamp≥0。系数全来自 KuaFuZhanBase。
     */
    private void applyScoreDelta(PlayerRecord rec, boolean win, int killNum, int foeFp) {
        GameTables.KfzBaseCfg base = tables.kfzBase();
        int delta = calcScoreDelta(base, rec.kfz.stars, win, killNum, foeFp);
        rec.kfz.score = Math.max(0, rec.kfz.score + delta);
        if (win) {
            rec.kfz.stars = Math.min(5, rec.kfz.stars + 1);
            if (rec.kfz.rank > 1) {
                rec.kfz.rank--;
            }
        }
    }

    static int calcScoreDelta(GameTables.KfzBaseCfg base, int stars, boolean win, int killNum, int foeFp) {
        int basePart = win ? base.winBaseScore : -base.loseBaseScore;
        int starPart = (int) Math.floor(Math.max(0, stars) * (double) base.starMatchValue);
        double fightRaw = Math.max(0, killNum) * (double) Math.max(0, foeFp) * base.fightScoreA
                + base.fightScoreB;
        int fightClamped = (int) Math.floor(fightRaw);
        if (fightClamped < 0) {
            fightClamped = 0;
        }
        if (fightClamped > (int) base.maxFightScore) {
            fightClamped = (int) base.maxFightScore;
        }
        int fightPart = (int) Math.floor(fightClamped * (double) base.scoreModifier);
        int sign = win ? 1 : -1;
        return basePart + starPart + sign * fightPart;
    }

    /** 打赢/打输捏造 NPC 时镜像改对方积分（真人登录号不自动改，避免误伤）。 */
    private void applyNpcFoeScoreMirror(PlayerRecord self, boolean iWon, int foeKillsOnMe, int myFp) {
        int guid = self.kfz.seriesTargetGuid > 0 ? self.kfz.seriesTargetGuid : self.kfz.lastTargetGuid;
        PlayerRecord foe = store.findByPlayerId(guid);
        if (foe == null || !foe.npcPassive) {
            return;
        }
        foe.ensureCollections();
        int delta = calcScoreDelta(tables.kfzBase(), foe.kfz.stars, !iWon, foeKillsOnMe, myFp);
        foe.kfz.score = Math.max(0, foe.kfz.score + delta);
        if (!iWon) {
            foe.kfz.stars = Math.min(5, foe.kfz.stars + 1);
        }
        store.save(foe);
    }

    private void applyOffenceFromBattle(GameSession session, PlayerRecord rec) {
        for (FightSyncService.KfzWjSnap snap : fightSync.selfWjSnap(session, rec.playerId)) {
            PlayerRecord.KfzWjState st = findOrCreateOffence(rec, snap.wjIndex);
            st.hasPlayed = true;
            if (snap.dead) {
                st.isDead = true;
            }
        }
    }

    private PlayerRecord.KfzWjState findOrCreateOffence(PlayerRecord rec, int index) {
        for (PlayerRecord.KfzWjState st : rec.kfz.offenceWj) {
            if (st != null && st.index == index) {
                return st;
            }
        }
        PlayerRecord.KfzWjState st = new PlayerRecord.KfzWjState();
        st.index = index;
        rec.kfz.offenceWj.add(st);
        return st;
    }

    private void applySeriesResult(PlayerRecord rec, boolean win) {
        ensureSeries(rec, rec.kfz.lastTargetGuid);
        int idx = currentFightableMatch(rec);
        if (idx < 0 || idx >= DUIZHAN_MATCHES) {
            return;
        }
        while (rec.kfz.seriesResults.size() < DUIZHAN_MATCHES) {
            rec.kfz.seriesResults.add(0);
        }
        rec.kfz.seriesResults.set(idx, win ? SERIES_WIN : SERIES_LOSS);
        int myWins = 0;
        int foeWins = 0;
        for (int r : rec.kfz.seriesResults) {
            if (r == SERIES_WIN) {
                myWins++;
            } else if (r == SERIES_LOSS) {
                foeWins++;
            }
        }
        if (myWins >= 2 || foeWins >= 2 || idx >= DUIZHAN_MATCHES - 1) {
            rec.kfz.offenceWj.clear();
        }
    }

    /** 系列先胜两场（或打满）→ 写入当日 4704 终局条。 */
    private void maybeFinalizePysEnemyResult(PlayerRecord rec) {
        int myWins = 0;
        int foeWins = 0;
        for (int i = 0; i < DUIZHAN_MATCHES; i++) {
            int r = seriesResultAt(rec, i);
            if (r == SERIES_WIN) {
                myWins++;
            } else if (r == SERIES_LOSS) {
                foeWins++;
            }
        }
        boolean done = myWins >= 2 || foeWins >= 2;
        if (!done) {
            int played = 0;
            for (int i = 0; i < DUIZHAN_MATCHES; i++) {
                if (seriesResultAt(rec, i) != 0) {
                    played++;
                }
            }
            done = played >= DUIZHAN_MATCHES;
        }
        if (!done || rec.kfz.seriesTargetGuid <= 0) {
            return;
        }
        boolean iWon = myWins > foeWins;
        upsertPysEnemyResult(rec, rec.kfz.seriesTargetGuid,
                iWon ? PYS_STATUS_WIN : PYS_STATUS_LOSE,
                rec.kfz.seriesMyKills, rec.kfz.seriesFoeKills,
                Math.max(0, rec.kfz.seriesMyScoreDelta),
                iWon ? 0 : Math.max(0, -rec.kfz.seriesMyScoreDelta));
    }

    private void upsertPysEnemyResult(PlayerRecord rec, int targetGuid, int status,
                                      int myKill, int otherKill, int myScore, int otherScore) {
        if (rec.kfz.pysEnemyResults == null) {
            rec.kfz.pysEnemyResults = new ArrayList<>();
        }
        PlayerRecord.KfzPysEnemyResult row = findPysEnemyResult(rec, targetGuid);
        if (row == null) {
            row = new PlayerRecord.KfzPysEnemyResult();
            row.targetGuid = targetGuid;
            rec.kfz.pysEnemyResults.add(row);
        }
        row.status = status;
        row.myKillCount = Math.max(0, myKill);
        row.otherKillCount = Math.max(0, otherKill);
        row.myScore = myScore;
        row.otherScore = otherScore;
    }

    private PlayerRecord.KfzPysEnemyResult findPysEnemyResult(PlayerRecord rec, int targetGuid) {
        if (rec.kfz.pysEnemyResults == null) {
            return null;
        }
        for (PlayerRecord.KfzPysEnemyResult r : rec.kfz.pysEnemyResults) {
            if (r != null && r.targetGuid == targetGuid) {
                return r;
            }
        }
        return null;
    }

    private boolean seriesInProgress(PlayerRecord rec) {
        if (rec.kfz.seriesResults == null) {
            return false;
        }
        boolean any = false;
        int myWins = 0;
        int foeWins = 0;
        for (int i = 0; i < DUIZHAN_MATCHES; i++) {
            int r = seriesResultAt(rec, i);
            if (r != 0) {
                any = true;
            }
            if (r == SERIES_WIN) {
                myWins++;
            } else if (r == SERIES_LOSS) {
                foeWins++;
            }
        }
        if (!any) {
            return false;
        }
        return myWins < 2 && foeWins < 2;
    }

    private void applyDfzResult(PlayerRecord rec, boolean win) {
        ensureDfzBracket(rec);
        int foeGuid = rec.kfz.lastTargetGuid;
        PlayerRecord.KfzDfzSlot me = findDfzSlot(rec, rec.playerId);
        PlayerRecord.KfzDfzSlot foe = findDfzSlot(rec, foeGuid);
        int elim = elimRankForPhase(rec.kfz.phase);
        if (win) {
            if (foe != null) {
                foe.hasFailed = true;
                foe.rank = elim;
            }
            if (rec.kfz.phase == 13 && me != null) {
                me.rank = 1;
                rec.kfz.rank = 1;
            }
        } else {
            if (me != null) {
                me.hasFailed = true;
                me.rank = elim;
            }
            rec.kfz.rank = elim;
        }
        resolveOtherDfzPairs(rec);
        int alive = 0;
        for (PlayerRecord.KfzDfzSlot s : rec.kfz.dfzBracket) {
            if (s != null && s.rank == 0) {
                alive++;
            }
        }
        if (alive <= 8) {
            rec.kfz.dfzFinalEight = true;
        }
        rec.kfz.offenceWj.clear();
        updateWorldPodiumFromDfz(rec);
    }

    /** 决赛名次落定后刷新世界领奖台（display 1/2/3 ← DFZ rank 1/2/4）。无 SessionHub 广播，回城再推 4706。 */
    private void updateWorldPodiumFromDfz(PlayerRecord rec) {
        PlayerRecord.KfzDfzSlot rank1 = null;
        PlayerRecord.KfzDfzSlot rank2 = null;
        PlayerRecord.KfzDfzSlot rank4 = null;
        for (PlayerRecord.KfzDfzSlot s : rec.kfz.dfzBracket) {
            if (s == null) {
                continue;
            }
            if (s.rank == 1) {
                rank1 = s;
            } else if (s.rank == 2) {
                rank2 = s;
            } else if (s.rank == 4 && rank4 == null) {
                rank4 = s;
            }
        }
        if (rank1 == null) {
            return;
        }
        writePodiumFromDfzSlot(1, rank1);
        if (rank2 != null) {
            writePodiumFromDfzSlot(2, rank2);
        }
        if (rank4 != null) {
            writePodiumFromDfzSlot(3, rank4);
        }
    }

    private void writePodiumFromDfzSlot(int displayRank, PlayerRecord.KfzDfzSlot s) {
        // 前三不论真人/机器人都进领奖台（产品拍板）
        PlayerRecord p = store.findByPlayerId(s.guid);
        int stars;
        int score;
        int serverId;
        int resId = s.resId > 0 ? s.resId : 18;
        int level = s.level > 0 ? s.level : 1;
        String name = s.name == null ? "" : s.name;
        if (p != null) {
            p.ensureCollections();
            stars = p.kfz.stars;
            score = p.kfz.score;
            serverId = 1;
            if (resId <= 0) {
                resId = p.mainHeroIndex;
            }
            if (level <= 0) {
                level = p.level;
            }
            if (name.isEmpty()) {
                name = p.roleName;
            }
        } else {
            GameTables.RobotRow r = tables.robotByGuid(s.guid);
            stars = r != null ? Math.max(0, r.stars) : Math.max(0, 6 - displayRank);
            score = robotSeedScore(r);
            serverId = kfzServerIdForGuid(s.guid);
            if (r != null) {
                if (resId <= 0) {
                    resId = r.resId;
                }
                if (level <= 0) {
                    level = r.level;
                }
                if (name.isEmpty()) {
                    name = r.name;
                }
            }
        }
        world.setKfzPodiumSlot(displayRank, s.guid, name, resId, level, serverId, stars, score);
    }

    // ---- DFZ bracket ----

    private void ensureDfzBracket(PlayerRecord rec) {
        ensureDfzBracket(rec, false);
    }

    /**
     * @param forceRebuild true=Admin 切阶段强制重种子。
     */
    private void ensureDfzBracket(PlayerRecord rec, boolean forceRebuild) {
        if (!forceRebuild
                && rec.kfz.dfzBracket != null && rec.kfz.dfzBracket.size() >= DFZ_SIZE) {
            return;
        }
        tables.ensureKfzRobotPool(DFZ_SIZE);
        List<PlayerRecord.KfzDfzSlot> list = new ArrayList<>(DFZ_SIZE);
        List<PlayerRecord> seeds = new ArrayList<>();
        for (PlayerRecord p : store.all()) {
            if (p == null || p.level < tables.kfzBase().openLevel) {
                continue;
            }
            if (!isEligibleNow(p) && p.playerId != rec.playerId) {
                continue;
            }
            seeds.add(p);
        }
        seeds.sort(Comparator
                .comparingInt((PlayerRecord p) -> p.kfz.rank <= 0 ? 9999 : p.kfz.rank)
                .thenComparing((PlayerRecord p) -> -p.kfz.score));
        Set<Integer> used = new HashSet<>();
        for (PlayerRecord p : seeds) {
            if (list.size() >= DFZ_SIZE) {
                break;
            }
            PlayerRecord.KfzDfzSlot s = new PlayerRecord.KfzDfzSlot();
            s.guid = p.playerId;
            s.name = p.roleName;
            s.resId = p.mainHeroIndex;
            s.level = p.level;
            list.add(s);
            used.add(p.playerId);
        }
        if (!used.contains(rec.playerId) && list.size() < DFZ_SIZE) {
            PlayerRecord.KfzDfzSlot self = new PlayerRecord.KfzDfzSlot();
            self.guid = rec.playerId;
            self.name = rec.roleName;
            self.resId = rec.mainHeroIndex;
            self.level = rec.level;
            list.add(0, self);
            used.add(rec.playerId);
        }
        List<GameTables.RobotRow> robots = tables.robots();
        for (int i = 0; list.size() < DFZ_SIZE; i++) {
            PlayerRecord.KfzDfzSlot s = new PlayerRecord.KfzDfzSlot();
            if (i < robots.size()) {
                GameTables.RobotRow r = robots.get(i);
                if (used.contains(r.targetGuid)) {
                    continue;
                }
                s.guid = r.targetGuid;
                s.name = r.name;
                s.resId = r.resId;
                s.level = r.level;
                used.add(r.targetGuid);
            } else {
                s.guid = 9200000 + i;
                s.name = "巅峰选手" + (i + 1);
                s.resId = 18;
                s.level = rec.level;
            }
            list.add(s);
        }
        while (list.size() > DFZ_SIZE) {
            list.remove(list.size() - 1);
        }
        rec.kfz.dfzBracket = list;
        rec.kfz.dfzFinalEight = false;
        store.save(rec);
    }

    /** 当前轮其它对位：有 PlayerRecord（真人/NPC）走防阵模拟；仅表机器人才 robotVsRobot。 */
    private void resolveOtherDfzPairs(PlayerRecord rec) {
        List<PlayerRecord.KfzDfzSlot> alive = new ArrayList<>();
        for (PlayerRecord.KfzDfzSlot s : rec.kfz.dfzBracket) {
            if (s != null && s.rank == 0) {
                alive.add(s);
            }
        }
        int elim = elimRankForPhase(rec.kfz.phase);
        for (int i = 0; i + 1 < alive.size(); i += 2) {
            PlayerRecord.KfzDfzSlot a = alive.get(i);
            PlayerRecord.KfzDfzSlot b = alive.get(i + 1);
            if (a.guid == rec.playerId || b.guid == rec.playerId) {
                continue;
            }
            if (a.hasFailed || b.hasFailed) {
                continue;
            }
            FightSyncService.RobotMatchResult sim = simulateDfzOtherPair(a, b);
            int loserGuid = sim.loserGuid;
            PlayerRecord.KfzDfzSlot loser = a.guid == loserGuid ? a : b;
            PlayerRecord.KfzDfzSlot winner = a.guid == loserGuid ? b : a;
            loser.hasFailed = true;
            loser.rank = elim;
            appendXiangXi(rec, XIANGXI_TYPE_ATTACK, elim, winner.name, loser.name);
        }
        int left = 0;
        for (PlayerRecord.KfzDfzSlot s : rec.kfz.dfzBracket) {
            if (s != null && s.rank == 0) {
                left++;
            }
        }
        if (left <= 8) {
            rec.kfz.dfzFinalEight = true;
        }
    }

    private FightSyncService.RobotMatchResult simulateDfzOtherPair(PlayerRecord.KfzDfzSlot a,
                                                                   PlayerRecord.KfzDfzSlot b) {
        PlayerRecord pa = store.findByPlayerId(a.guid);
        PlayerRecord pb = store.findByPlayerId(b.guid);
        int form = PlayerRecord.FORMATION_KFZ_DEF1;
        if (pa != null && pb != null) {
            return fightSync.simulatePlayerFormationVsPlayer(pa, form, pb, form);
        }
        if (pa != null && pb == null) {
            // 左真人/NPC 守，右纯表机器人攻 → 互换成「有阵一方」用 fromPlayer 对 fromRobot
            return fightSync.simulatePlayerFormationVsRobot(pa, form, b.guid);
        }
        if (pa == null && pb != null) {
            return fightSync.simulatePlayerFormationVsRobot(pb, form, a.guid);
        }
        return fightSync.simulateRobotVsRobot(a.guid, b.guid);
    }

    private void sendDfzSaiCheng(GameSession session, GamePacket pkt, PlayerRecord rec) {
        List<PlayerRecord.KfzDfzSlot> slots;
        if (rec.kfz.phase >= 11 || rec.kfz.dfzFinalEight) {
            slots = new ArrayList<>();
            for (PlayerRecord.KfzDfzSlot s : rec.kfz.dfzBracket) {
                if (s != null && s.rank == 0) {
                    slots.add(s);
                }
            }
            if (slots.size() < 8) {
                for (PlayerRecord.KfzDfzSlot s : rec.kfz.dfzBracket) {
                    if (s == null || s.rank == 0) {
                        continue;
                    }
                    if (!containsGuid(slots, s.guid)) {
                        slots.add(s);
                    }
                    if (slots.size() >= 8) {
                        break;
                    }
                }
            }
            while (slots.size() > 8) {
                slots.remove(slots.size() - 1);
            }
        } else {
            slots = new ArrayList<>(rec.kfz.dfzBracket);
        }
        List<byte[]> items = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            PlayerRecord.KfzDfzSlot s = slots.get(i);
            PlayerRecord.KfzDfzSlot foe = slots.get(i ^ 1);
            if ((i ^ 1) >= slots.size()) {
                foe = s;
            }
            items.add(dump.kfzDfzItem(slotBrief(s), slotBrief(foe), s.hasFailed, s.rank));
        }
        if (items.size() != 8 && items.size() != 64) {
            log.warn("{} kfz dfz count={} pad/truncate", rec.account, items.size());
        }
        session.send(MsgIds.S2C_KFZ_DFZ_SAICHENG, pkt, dump.kfzDfzSaiCheng(selfBrief(rec), items));
        store.save(rec);
    }

    private static boolean containsGuid(List<PlayerRecord.KfzDfzSlot> list, int guid) {
        for (PlayerRecord.KfzDfzSlot s : list) {
            if (s != null && s.guid == guid) {
                return true;
            }
        }
        return false;
    }

    private byte[] slotBrief(PlayerRecord.KfzDfzSlot s) {
        return dump.kfzPlayerInfo(s.guid, s.name, s.resId > 0 ? s.resId : 18,
                s.level > 0 ? s.level : 1, kfzServerIdForGuid(s.guid));
    }

    private PlayerRecord.KfzDfzSlot findDfzSlot(PlayerRecord rec, int guid) {
        for (PlayerRecord.KfzDfzSlot s : rec.kfz.dfzBracket) {
            if (s != null && s.guid == guid) {
                return s;
            }
        }
        return null;
    }

    private static int elimRankForPhase(int phase) {
        switch (phase) {
            case 8:
                return 64;
            case 9:
                return 32;
            case 10:
                return 16;
            case 11:
                return 8;
            case 12:
                return 4;
            case 13:
                return 2;
            default:
                return 64;
        }
    }

    // ---- series / day / phase ----

    private List<KfzOpponentPool.Opponent> ensurePysOpponents(PlayerRecord rec) {
        String today = GameTime.today().toString();
        if (today.equals(rec.kfz.pysOpponentDay)
                && rec.kfz.pysOpponentGuids != null
                && rec.kfz.pysOpponentGuids.size() >= ENEMY_COUNT) {
            List<KfzOpponentPool.Opponent> cached = new ArrayList<>();
            for (Integer g : rec.kfz.pysOpponentGuids) {
                if (g == null) {
                    continue;
                }
                KfzOpponentPool.Opponent o = opponentPool.resolveOpponent(rec, g);
                if (o != null) {
                    cached.add(o);
                }
            }
            if (cached.size() >= ENEMY_COUNT) {
                return cached;
            }
        }
        List<KfzOpponentPool.Opponent> picked = opponentPool.pickPysOpponents(rec, ENEMY_COUNT);
        rec.kfz.pysOpponentDay = today;
        rec.kfz.pysOpponentGuids = new ArrayList<>();
        for (KfzOpponentPool.Opponent o : picked) {
            rec.kfz.pysOpponentGuids.add(o.guid);
        }
        // 换日换对手：清当日终局条
        rec.kfz.pysEnemyResults = new ArrayList<>();
        store.save(rec);
        return picked;
    }

    private KfzOpponentPool.Opponent ensureSeries(PlayerRecord rec, int targetGuid) {
        KfzOpponentPool.Opponent opp = opponentPool.resolveOpponent(rec, targetGuid);
        if (opp == null) {
            tables.ensureKfzRobotPool(DFZ_SIZE);
            GameTables.RobotRow r = tables.robotByGuid(targetGuid);
            if (r == null && !tables.robots().isEmpty()) {
                r = tables.robots().get(0);
                targetGuid = r.targetGuid;
            }
            opp = opponentPool.buildRobotOpponent(r);
            if (opp == null) {
                opp = new KfzOpponentPool.Opponent();
                opp.kind = KfzOpponentPool.Kind.ROBOT;
                opp.guid = targetGuid;
                opp.name = "机器人";
                opp.resId = 18;
                opp.level = rec.level;
                opp.equipTemplateGuid = new int[]{targetGuid, targetGuid, targetGuid};
                opp.defTeams = new ArrayList<>();
                for (int t = 0; t < 3; t++) {
                    List<Integer> team = new ArrayList<>();
                    for (int i = 0; i < 5; i++) {
                        team.add(18 + t * 5 + i);
                    }
                    opp.defTeams.add(team);
                }
            }
        }
        boolean same = rec.kfz.seriesTargetGuid == opp.guid
                && rec.kfz.seriesResults != null
                && rec.kfz.seriesResults.size() >= DUIZHAN_MATCHES
                && rec.kfz.seriesDefHeroes != null
                && rec.kfz.seriesDefHeroes.size() >= 15
                && rec.kfz.seriesTargetKind == (opp.kind == KfzOpponentPool.Kind.REAL
                ? SERIES_KIND_REAL : SERIES_KIND_ROBOT);
        if (same && opp.kind == KfzOpponentPool.Kind.ROBOT
                && (rec.kfz.seriesEquipTemplateGuids == null
                || rec.kfz.seriesEquipTemplateGuids.size() < 3)) {
            same = false;
        }
        if (same) {
            return opp;
        }
        rec.kfz.seriesTargetGuid = opp.guid;
        rec.kfz.seriesTargetKind = opp.kind == KfzOpponentPool.Kind.REAL
                ? SERIES_KIND_REAL : SERIES_KIND_ROBOT;
        rec.kfz.seriesResults = new ArrayList<>();
        for (int i = 0; i < DUIZHAN_MATCHES; i++) {
            rec.kfz.seriesResults.add(0);
        }
        rec.kfz.seriesDefHeroes = KfzOpponentPool.flattenDefTeams(opp.defTeams);
        rec.kfz.seriesEquipTemplateGuids = new ArrayList<>();
        if (opp.kind == KfzOpponentPool.Kind.ROBOT && opp.equipTemplateGuid != null) {
            for (int g : opp.equipTemplateGuid) {
                rec.kfz.seriesEquipTemplateGuids.add(g);
            }
        } else {
            rec.kfz.seriesEquipTemplateGuids.add(0);
            rec.kfz.seriesEquipTemplateGuids.add(0);
            rec.kfz.seriesEquipTemplateGuids.add(0);
        }
        rec.kfz.offenceWj.clear();
        rec.kfz.seriesMyKills = 0;
        rec.kfz.seriesFoeKills = 0;
        rec.kfz.seriesMyScoreDelta = 0;
        return opp;
    }

    private int seriesTemplateGuid(PlayerRecord rec, int match0) {
        int i = Math.max(0, Math.min(2, match0));
        if (rec.kfz.seriesEquipTemplateGuids != null
                && i < rec.kfz.seriesEquipTemplateGuids.size()) {
            Integer g = rec.kfz.seriesEquipTemplateGuids.get(i);
            if (g != null && g > 0) {
                return g;
            }
        }
        return rec.kfz.seriesTargetGuid > 0 ? rec.kfz.seriesTargetGuid : firstRobotGuid();
    }

    private List<Integer> seriesHeroesSlice(PlayerRecord rec, int match0) {
        List<Integer> out = new ArrayList<>(5);
        List<Integer> all = rec.kfz.seriesDefHeroes;
        int base = Math.max(0, match0) * 5;
        for (int i = 0; i < 5; i++) {
            int idx = base + i;
            if (all != null && idx < all.size() && all.get(idx) != null && all.get(idx) > 0) {
                out.add(all.get(idx));
            } else {
                out.add(18);
            }
        }
        return out;
    }

    private int seriesResultAt(PlayerRecord rec, int i) {
        if (rec.kfz.seriesResults == null || i < 0 || i >= rec.kfz.seriesResults.size()) {
            return 0;
        }
        Integer v = rec.kfz.seriesResults.get(i);
        return v == null ? 0 : v;
    }

    private int currentFightableMatch(PlayerRecord rec) {
        int myWins = 0;
        int foeWins = 0;
        for (int i = 0; i < DUIZHAN_MATCHES; i++) {
            int r = seriesResultAt(rec, i);
            if (r == 0) {
                return i;
            }
            if (r == SERIES_WIN) {
                myWins++;
            } else if (r == SERIES_LOSS) {
                foeWins++;
            }
            if (myWins >= 2 || foeWins >= 2) {
                return -1;
            }
        }
        return -1;
    }

    private void syncPhaseAndDay(PlayerRecord rec) {
        syncWorshipDay(rec);
        syncEligibility(rec);
        int prev = rec.kfz.phase;
        int next = rec.kfz.phaseOverride >= 0 ? rec.kfz.phaseOverride : computePhase(rec);
        if (next != prev) {
            DayOfWeek dow = GameTime.today().getDayOfWeek();
            // 周六首次进入巅峰窗：清括号，按周五封榜排位重种子（产品拍板）
            if (dow == DayOfWeek.SATURDAY
                    && rec.kfz.phaseOverride < 0
                    && isSatDfzWindowPhase(next)
                    && !isSatDfzWindowPhase(prev)) {
                rec.kfz.dfzBracket = new ArrayList<>();
                rec.kfz.dfzFinalEight = false;
                rec.kfz.hasFightingDfz = false;
            } else if (isDfzFightPhase(next) && !isDfzFightPhase(prev) && rec.kfz.phaseOverride < 0
                    && dow == DayOfWeek.SUNDAY) {
                // 周日进 11–13：保留括号，仅切 finalEight 语义
                rec.kfz.dfzFinalEight = true;
                rec.kfz.hasFightingDfz = false;
            } else if (isDfzFightPhase(next) && !isDfzFightPhase(prev) && rec.kfz.phaseOverride < 0) {
                rec.kfz.dfzBracket = new ArrayList<>();
                rec.kfz.dfzFinalEight = false;
                rec.kfz.hasFightingDfz = false;
            }
            rec.kfz.phase = next;
            store.save(rec);
        }
        if (isDfzFightPhase(rec.kfz.phase) || isSatDfzWindowPhase(rec.kfz.phase)) {
            ensureDfzBracket(rec, false);
        }
    }

    private static boolean isSatDfzWindowPhase(int phase) {
        return phase == 2 || phase == 3 || phase == 4 || (phase >= 8 && phase <= 10);
    }

    private void syncWorshipDay(PlayerRecord rec) {
        String today = GameTime.today().toString();
        if (!today.equals(rec.kfz.worshipDay)) {
            rec.kfz.worshipDay = today;
            rec.kfz.worshipedRanks.clear();
            store.save(rec);
        }
    }

    /**
     * 产品拍板周历：
     * 周一～五 → 排位 phase=1（无巅峰括号窗）；
     * 周六 → round64/32/16 钟 → 8/9/10（等待 2–4）；
     * 周日 → quarter/semi/final 钟 → 11/12/13（等待 5–7，finalEight 语义强制）。
     */
    private int computePhase(PlayerRecord rec) {
        DayOfWeek dow = GameTime.today().getDayOfWeek();
        if (dow.getValue() >= DayOfWeek.MONDAY.getValue()
                && dow.getValue() <= DayOfWeek.FRIDAY.getValue()) {
            return PHASE_PAIWEI;
        }
        GameTables.KfzBaseCfg cfg = tables.kfzBase();
        int min = GameTime.localTime().getHour() * 60 + GameTime.localTime().getMinute();
        boolean finals = dow == DayOfWeek.SUNDAY;
        int b1 = finals ? cfg.quarterBeginMin : cfg.round64BeginMin;
        int e1 = finals ? cfg.quarterEndMin : cfg.round64EndMin;
        int b2 = finals ? cfg.semiBeginMin : cfg.round32BeginMin;
        int e2 = finals ? cfg.semiEndMin : cfg.round32EndMin;
        int b3 = finals ? cfg.finalBeginMin : cfg.round16BeginMin;
        int e3 = finals ? cfg.finalEndMin : cfg.round16EndMin;
        int waitBefore = finals ? 5 : 2;
        int waitGap1 = finals ? 6 : 3;
        int waitGap2 = finals ? 7 : 4;
        int fight1 = finals ? 11 : 8;
        int fight2 = finals ? 12 : 9;
        int fight3 = finals ? 13 : 10;
        if (min < b1) {
            return waitBefore;
        }
        if (min >= b1 && min < e1) {
            return fight1;
        }
        if (min >= e1 && min < b2) {
            return waitGap1;
        }
        if (min >= b2 && min < e2) {
            return fight2;
        }
        if (min >= e2 && min < b3) {
            return waitGap2;
        }
        if (min >= b3 && min < e3) {
            return fight3;
        }
        return PHASE_PAIWEI;
    }

    private int statusRank(PlayerRecord rec) {
        if (!isEligibleNow(rec)) {
            return INELIGIBLE_STATUS_RANK;
        }
        if (isDfzFightPhase(rec.kfz.phase)) {
            PlayerRecord.KfzDfzSlot me = findDfzSlot(rec, rec.playerId);
            if (me != null && me.rank > 0) {
                return me.rank;
            }
            return 0;
        }
        return rec.kfz.rank;
    }

    static boolean isDfzFightPhase(int phase) {
        return phase >= PHASE_DFZ_MIN && phase <= PHASE_DFZ_MAX;
    }

    // ---- eligibility（周日 21:00 JJC 前十 → 下周入围）----

    /**
     * 定时 / 启动补跑：周日 21:00 按竞技场 rank 取前十写入下周 eligible。
     */
    public void runEligibilitySnapshotIfDue() {
        LocalDateTime now = GameTime.now();
        if (now.getDayOfWeek() != DayOfWeek.SUNDAY || now.getHour() < 21) {
            // 非快照时刻仍做 catch-up：若缺本周标记则用当前 JJC 前十启发式
            catchUpEligibilityAll();
            return;
        }
        String nextWeek = competitionWeekId(now.plusDays(1));
        applyArenaTop10Eligibility(nextWeek);
    }

    private void catchUpEligibilityAll() {
        String week = competitionWeekId(GameTime.now());
        boolean anyMarked = false;
        for (PlayerRecord p : store.all()) {
            if (p != null && week.equals(p.kfz.eligibleWeekId)) {
                anyMarked = true;
                break;
            }
        }
        if (!anyMarked) {
            // 尚未有周日快照：按当前 JJC 前十 + 等级门槛启发式（产品：直至首次周日）
            applyArenaTop10Eligibility(week);
        }
    }

    private void applyArenaTop10Eligibility(String weekId) {
        List<PlayerRecord> all = new ArrayList<>();
        for (PlayerRecord p : store.all()) {
            if (p != null) {
                all.add(p);
            }
        }
        all.sort(Comparator.comparingInt(p -> {
            int r = p.arena != null ? p.arena.rank : 999999;
            return r <= 0 ? 999999 : r;
        }));
        int openLv = tables.kfzBase().openLevel;
        Set<Integer> top10 = new HashSet<>();
        for (PlayerRecord p : all) {
            if (p.level < openLv) {
                continue;
            }
            int r = p.arena != null ? p.arena.rank : 0;
            if (r >= 1 && r <= 10) {
                top10.add(p.playerId);
            }
            if (top10.size() >= 10) {
                break;
            }
        }
        // 若不足 10 个有 rank≤10，按排序再补到 10
        for (PlayerRecord p : all) {
            if (top10.size() >= 10) {
                break;
            }
            if (p.level >= openLv) {
                top10.add(p.playerId);
            }
        }
        for (PlayerRecord p : all) {
            p.ensureCollections();
            p.kfz.eligibleWeekId = weekId;
            p.kfz.jjcRankAtSnapshot = p.arena != null ? p.arena.rank : 0;
            p.kfz.eligibleThisWeek = top10.contains(p.playerId) || p.kfz.eligibleOverride;
            store.save(p);
        }
        log.info("kfz eligibility snapshot week={} top10={}", weekId, top10.size());
    }

    private void syncEligibility(PlayerRecord rec) {
        String week = competitionWeekId(GameTime.now());
        if (!week.equals(rec.kfz.eligibleWeekId)) {
            // 本号尚未跟上本周快照：先全服补跑一次，再读自身
            catchUpEligibilityAll();
        }
        if (rec.kfz.eligibleOverride) {
            rec.kfz.eligibleThisWeek = true;
        }
        // 单机：无其它玩家时，等级达标且（JJC≤10 或已入围/ override）即可开
        int others = 0;
        for (PlayerRecord p : store.all()) {
            if (p != null && p.playerId != rec.playerId) {
                others++;
            }
        }
        if (others == 0 && rec.level >= tables.kfzBase().openLevel) {
            int ar = rec.arena != null ? rec.arena.rank : 0;
            if (ar <= 10 || ar <= 0 || rec.kfz.eligibleOverride || rec.kfz.eligibleThisWeek) {
                rec.kfz.eligibleThisWeek = true;
                if (rec.kfz.eligibleWeekId == null || rec.kfz.eligibleWeekId.isEmpty()) {
                    rec.kfz.eligibleWeekId = week;
                }
            }
        }
    }

    boolean isEligibleNow(PlayerRecord rec) {
        if (rec == null) {
            return false;
        }
        if (rec.level < tables.kfzBase().openLevel) {
            return false;
        }
        if (rec.kfz.eligibleOverride) {
            return true;
        }
        if (rec.kfz.eligibleThisWeek) {
            return true;
        }
        // 直至首次周日快照：JJC rank≤10 视为入围
        int ar = rec.arena != null ? rec.arena.rank : 0;
        return ar >= 1 && ar <= 10;
    }

    static String competitionWeekId(LocalDateTime when) {
        LocalDate d = when.toLocalDate();
        if (d.getDayOfWeek() == DayOfWeek.SUNDAY && when.getHour() >= 21) {
            d = d.plusDays(1);
        }
        LocalDate monday = d.with(DayOfWeek.MONDAY);
        WeekFields wf = WeekFields.ISO;
        int y = monday.get(wf.weekBasedYear());
        int w = monday.get(wf.weekOfWeekBasedYear());
        return String.format(Locale.ROOT, "%d-W%02d", y, w);
    }

    // ---- match / score helpers ----

    /** 机器人估计积分：由 guid 播种，落在常见排位分带。 */
    static int robotSeedScore(GameTables.RobotRow r) {
        if (r == null) {
            return 0;
        }
        return 40 + (Math.abs(r.targetGuid) * 17 % 360) + Math.max(0, r.stars) * 8;
    }

    // ---- xiangxi ----

    private void appendFightXiangXi(PlayerRecord rec, boolean win) {
        KfzOpponentPool.Opponent opp = opponentPool.resolveOpponent(rec, rec.kfz.lastTargetGuid);
        String foeName = opp == null ? "对手" : opp.name;
        if (win) {
            appendXiangXi(rec, XIANGXI_TYPE_ATTACK, Math.max(1, rec.kfz.rank), rec.roleName, foeName);
        } else {
            appendXiangXi(rec, XIANGXI_TYPE_DEFENCE, Math.max(1, rec.kfz.rank), foeName, rec.roleName);
        }
    }

    private void appendXiangXi(PlayerRecord rec, int type, int rank, String winner, String loser) {
        if (rec.kfz.xiangXiReports == null) {
            rec.kfz.xiangXiReports = new ArrayList<>();
        }
        PlayerRecord.KfzXiangXi x = new PlayerRecord.KfzXiangXi();
        x.type = type;
        x.rank = rank;
        x.winner = winner == null ? "" : winner;
        x.winnerServer = 1;
        x.loser = loser == null ? "" : loser;
        // 被攻方若是机器人，捏造异服 serverId（假跨服展示）
        x.loserServer = 1;
        if (rec.kfz.lastTargetGuid > 0 && store.findByPlayerId(rec.kfz.lastTargetGuid) == null) {
            int foeSid = kfzServerIdForGuid(rec.kfz.lastTargetGuid);
            if (type == XIANGXI_TYPE_ATTACK) {
                x.loserServer = foeSid;
            } else {
                x.winnerServer = foeSid;
            }
        }
        rec.kfz.xiangXiReports.add(0, x);
        int max = Math.max(1, tables.kfzBase().maxBattleRecordCnt);
        while (rec.kfz.xiangXiReports.size() > max) {
            rec.kfz.xiangXiReports.remove(rec.kfz.xiangXiReports.size() - 1);
        }
    }

    // ---- helpers ----

    private void pushTop3AndWorship(GameSession session, GamePacket pkt, PlayerRecord rec) {
        syncWorshipDay(rec);
        List<byte[]> top3 = buildTop3Items();
        // 有前三（真人/机器人均可）才推 4706；空则主城不刷雕像
        if (!top3.isEmpty()) {
            session.send(MsgIds.S2C_KFZ_TOP3, pkt, dump.kfzTop3(top3));
        }
        session.send(MsgIds.S2C_KFZ_WORSHIP_STATUS, pkt, dump.kfzWorshipStatus(rec.kfz.worshipedRanks));
    }

    /**
     * 主城雕像 / Top3：前三<strong>不论真人/机器人</strong>都进。
     * 优先 world/kfz-podium → DFZ rank1/2/4 → 当前积分榜 Top3。
     */
    private WorldStore.KfzPodiumSlot[] resolveTop3PodiumSlots() {
        WorldStore.KfzPodiumSlot[] byDisplay = new WorldStore.KfzPodiumSlot[4];
        for (WorldStore.KfzPodiumSlot s : world.kfzPodium().slots) {
            if (s == null || s.displayRank < 1 || s.displayRank > 3 || s.guid == 0) {
                continue;
            }
            byDisplay[s.displayRank] = s;
        }
        if (byDisplay[1] == null && byDisplay[2] == null && byDisplay[3] == null) {
            fillTop3FromDfzBrackets(byDisplay);
        }
        if (byDisplay[1] == null && byDisplay[2] == null && byDisplay[3] == null) {
            fillTop3FromLeaderboard(byDisplay);
        }
        return byDisplay;
    }

    private List<byte[]> buildTop3Items() {
        WorldStore.KfzPodiumSlot[] byDisplay = resolveTop3PodiumSlots();
        List<byte[]> items = new ArrayList<>(3);
        for (int rank = 1; rank <= 3; rank++) {
            WorldStore.KfzPodiumSlot slot = byDisplay[rank];
            if (slot == null) {
                continue;
            }
            int res = slot.resId > 0 ? slot.resId : 18;
            int lv = slot.level > 0 ? slot.level : 1;
            int star = slot.star > 0 ? slot.star : (6 - rank);
            int score = slot.score > 0 ? slot.score : (1000 - rank * 50);
            int sid = slot.serverId > 0 ? slot.serverId : kfzServerIdForGuid(slot.guid);
            items.add(dump.kfzRankItem(dump.kfzPlayerInfo(slot.guid, slot.name, res, lv, sid),
                    rank, star, score));
        }
        return items;
    }

    /** DFZ 淘汰名次：1=冠军、2=亚军、4=四强落败 → 展示位 1/2/3；含机器人。 */
    private void fillTop3FromDfzBrackets(WorldStore.KfzPodiumSlot[] byDisplay) {
        for (PlayerRecord p : store.all()) {
            if (p == null) {
                continue;
            }
            p.ensureCollections();
            if (p.kfz.dfzBracket == null) {
                continue;
            }
            for (PlayerRecord.KfzDfzSlot s : p.kfz.dfzBracket) {
                if (s == null || s.guid == 0) {
                    continue;
                }
                int display;
                if (s.rank == 1) {
                    display = 1;
                } else if (s.rank == 2) {
                    display = 2;
                } else if (s.rank == 4) {
                    display = 3;
                } else {
                    continue;
                }
                if (byDisplay[display] != null) {
                    continue;
                }
                byDisplay[display] = podiumSlotFromDfz(display, s);
            }
        }
    }

    private WorldStore.KfzPodiumSlot podiumSlotFromDfz(int display, PlayerRecord.KfzDfzSlot s) {
        WorldStore.KfzPodiumSlot slot = new WorldStore.KfzPodiumSlot();
        slot.displayRank = display;
        slot.guid = s.guid;
        slot.name = s.name;
        slot.resId = s.resId > 0 ? s.resId : 18;
        slot.level = s.level > 0 ? s.level : 1;
        PlayerRecord owner = store.findByPlayerId(s.guid);
        if (owner != null) {
            owner.ensureCollections();
            slot.star = owner.kfz.stars;
            slot.score = owner.kfz.score;
            slot.serverId = 1;
        } else {
            GameTables.RobotRow r = tables.robotByGuid(s.guid);
            slot.star = r != null ? Math.max(0, r.stars) : Math.max(0, 6 - display);
            slot.score = robotSeedScore(r);
            slot.serverId = kfzServerIdForGuid(s.guid);
            if (r != null) {
                if (slot.resId <= 0) {
                    slot.resId = r.resId;
                }
                if (slot.level <= 0) {
                    slot.level = r.level;
                }
                if (slot.name == null || slot.name.isEmpty()) {
                    slot.name = r.name;
                }
            }
        }
        return slot;
    }

    /** 无领奖台/无 DFZ 名次时：积分榜 Top3（含机器人）填雕像。 */
    private void fillTop3FromLeaderboard(WorldStore.KfzPodiumSlot[] byDisplay) {
        List<RankRow> board = buildKfzLeaderboard();
        int n = Math.min(3, board.size());
        for (int i = 0; i < n; i++) {
            RankRow r = board.get(i);
            WorldStore.KfzPodiumSlot slot = new WorldStore.KfzPodiumSlot();
            slot.displayRank = i + 1;
            slot.guid = r.guid;
            slot.name = r.name;
            slot.resId = r.resId;
            slot.level = r.level;
            slot.serverId = r.serverId;
            slot.star = r.stars;
            slot.score = r.score;
            byDisplay[i + 1] = slot;
        }
    }

    /** 假跨服：真人恒 1；机器人捏造 2..9。 */
    private int kfzServerIdForGuid(int guid) {
        if (guid <= 0) {
            return 1;
        }
        if (store.findByPlayerId(guid) != null) {
            return 1;
        }
        return 2 + Math.floorMod(Math.abs(guid), 8);
    }

    private byte[] briefOf(RankRow r) {
        return dump.kfzPlayerInfo(r.guid, r.name, r.resId, r.level, r.serverId);
    }

    private static final class RankRow {
        int guid;
        String name;
        int resId;
        int level;
        int stars;
        int score;
        int serverId;
    }

    /**
     * 真人 + 机器人统一积分榜（按 score↓ / stars↓）。
     * 机器人用 {@link #robotSeedScore}，与匹配池一致。
     */
    private List<RankRow> buildKfzLeaderboard() {
        Map<Integer, RankRow> byGuid = new LinkedHashMap<>();
        int openLv = tables.kfzBase().openLevel;
        for (PlayerRecord p : store.all()) {
            if (p == null || p.level < openLv) {
                continue;
            }
            p.ensureCollections();
            RankRow row = new RankRow();
            row.guid = p.playerId;
            row.name = p.roleName;
            row.resId = p.mainHeroIndex > 0 ? p.mainHeroIndex : 18;
            row.level = p.level;
            row.stars = p.kfz.stars;
            row.score = p.kfz.score;
            row.serverId = 1;
            byGuid.put(row.guid, row);
        }
        tables.ensureKfzRobotPool(32);
        for (GameTables.RobotRow r : tables.robots()) {
            if (r == null || r.targetGuid <= 0 || byGuid.containsKey(r.targetGuid)) {
                continue;
            }
            RankRow row = new RankRow();
            row.guid = r.targetGuid;
            row.name = r.name;
            row.resId = r.resId > 0 ? r.resId : 18;
            row.level = r.level > 0 ? r.level : 1;
            row.stars = Math.max(0, r.stars);
            row.score = robotSeedScore(r);
            row.serverId = kfzServerIdForGuid(r.targetGuid);
            byGuid.put(row.guid, row);
        }
        List<RankRow> list = new ArrayList<>(byGuid.values());
        list.sort(Comparator
                .comparingInt((RankRow a) -> -a.score)
                .thenComparingInt(a -> -a.stars)
                .thenComparingInt(a -> a.guid));
        return list;
    }

    private List<byte[]> buildDuiZhanWjs(KfzOpponentPool.Opponent opp, List<Integer> heroes,
                                         int match0, int fallbackLevel) {
        if (opp != null && opp.kind == KfzOpponentPool.Kind.REAL) {
            PlayerRecord foe = store.findByPlayerId(opp.guid);
            List<byte[]> out = new ArrayList<>();
            if (heroes != null) {
                for (Integer h : heroes) {
                    if (h == null || h <= 0) {
                        continue;
                    }
                    PlayerRecord.Hero hero = foe != null ? foe.findHeroByIndex(h) : null;
                    int level = hero != null && hero.level > 0 ? hero.level
                            : (foe != null ? foe.level : fallbackLevel);
                    int star = hero != null ? Math.max(1, hero.stars) : 1;
                    int pin = hero != null ? Math.max(1, hero.stage) : 1;
                    out.add(dump.kfzDuiZhanWj(h, level, star, pin, false));
                    if (out.size() >= 5) {
                        break;
                    }
                }
            }
            if (out.isEmpty()) {
                out.add(dump.kfzDuiZhanWj(opp.resId > 0 ? opp.resId : 18, fallbackLevel, 1, 1, false));
            }
            return out;
        }
        int templateGuid = opp != null && opp.equipTemplateGuid != null
                && match0 >= 0 && match0 < opp.equipTemplateGuid.length
                ? opp.equipTemplateGuid[match0] : (opp != null ? opp.guid : 0);
        GameTables.RobotRow robot = tables.robotByGuid(templateGuid);
        return buildDuiZhanWjsFromHeroes(robot, heroes, fallbackLevel);
    }

    private List<byte[]> buildDuiZhanWjsFromHeroes(GameTables.RobotRow robot, List<Integer> heroes,
                                                   int fallbackLevel) {
        List<byte[]> out = new ArrayList<>();
        int level = robot != null && robot.level > 0 ? robot.level : fallbackLevel;
        int star = robot != null ? Math.max(1, robot.stars) : 1;
        int pin = robot != null ? Math.max(1, robot.stage) : 1;
        if (heroes != null) {
            for (Integer h : heroes) {
                if (h == null || h <= 0) {
                    continue;
                }
                out.add(dump.kfzDuiZhanWj(h, level, star, pin, false));
                if (out.size() >= 5) {
                    break;
                }
            }
        }
        if (out.isEmpty()) {
            int idx = robot != null && robot.resId > 0 ? robot.resId : 18;
            out.add(dump.kfzDuiZhanWj(idx, level, star, pin, false));
        }
        return out;
    }

    private List<byte[]> buildRankItems(PlayerRecord rec) {
        List<RankRow> board = buildKfzLeaderboard();
        List<byte[]> items = new ArrayList<>();
        int myRank = 0;
        for (int i = 0; i < board.size(); i++) {
            RankRow r = board.get(i);
            int rank = i + 1;
            if (r.guid == rec.playerId) {
                myRank = rank;
                rec.kfz.rank = rank;
            }
            if (items.size() < 20) {
                items.add(dump.kfzRankItem(briefOf(r), rank, r.stars, r.score));
            }
        }
        if (myRank <= 0) {
            // 未达开启等级时仍把自己挂上榜尾展示
            items.add(0, dump.kfzRankItem(selfBrief(rec),
                    Math.max(1, rec.kfz.rank), rec.kfz.stars, rec.kfz.score));
        } else if (myRank > 20) {
            items.add(dump.kfzRankItem(selfBrief(rec), myRank, rec.kfz.stars, rec.kfz.score));
        }
        return items;
    }

    private void grantMatchPrize(GameSession session, GamePacket pkt, PlayerRecord rec,
                                 GameTables.KfzMatchPrize prize) {
        if (prize == null) {
            return;
        }
        if (prize.gold > 0) {
            rec.gold += prize.gold;
            progress.pushGold(session, pkt, rec);
        }
        if (prize.stamina > 0) {
            rec.stamina += prize.stamina;
            progress.pushStamina(session, pkt, rec);
        }
        if (prize.jjcScore > 0) {
            rec.jjcScore += prize.jjcScore;
            progress.pushJjcScore(session, pkt, rec);
        }
        if (prize.goodsOri != null && !prize.goodsOri.isEmpty() && !"0".equals(prize.goodsOri)
                && prize.goodsCount > 0) {
            progress.addGoods(rec, prize.goodsOri, prize.goodsCount);
            Map<String, Integer> bag = new HashMap<>();
            progress.markGoods(bag, prize.goodsOri);
            progress.pushGoods(session, pkt, rec, bag);
        }
    }

    private byte[] selfBrief(PlayerRecord rec) {
        return dump.kfzPlayerInfo(rec.playerId, rec.roleName, rec.mainHeroIndex, rec.level);
    }

    private PlayerRecord require(GameSession session) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return null;
        }
        rec.ensureCollections();
        syncPhaseAndDay(rec);
        return rec;
    }

    private int firstRobotGuid() {
        List<GameTables.RobotRow> robots = tables.robots();
        return robots.isEmpty() ? 1 : robots.get(0).targetGuid;
    }

    private int firstOpponentGuid(PlayerRecord rec) {
        List<KfzOpponentPool.Opponent> list = ensurePysOpponents(rec);
        if (!list.isEmpty()) {
            return list.get(0).guid;
        }
        return firstRobotGuid();
    }
}
