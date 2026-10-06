package com.sao.fakeserver.service;

import com.sao.fakeserver.fight.FightRosterBuilder;
import com.sao.fakeserver.fight.FightUnit;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.FightConfigTables;
import com.sao.fakeserver.table.GameTables;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 争霸战：登录推 S2C 4602；CurState 按 ZhengBaZhan.txt 日时段（产品：假服跟表走钟）。
 * 1 排位 / 2 下注 / 3 八强 / 4 四强 / 5 决赛。
 * C2S 4106 空 body；胜负读 FightSync。
 */
@Service
public class ZbzService {
    private static final Logger log = LoggerFactory.getLogger(ZbzService.class);
    private static final int REGION = 80;
    /** 排位 UI 只展示前 3 条对手（不含自己）。 */
    private static final int RANKING_OPPONENT_SLOTS = 3;
    private static final int RANK_LIST_SIZE = 50;
    private static final int BRACKET_SIZE = 8;
    /** Top8UI.mPlayerSlotList 共 14 格；客 ShowPlayer 用 JueZhanPos-1 下标，越界即崩。 */
    private static final int JUE_ZHAN_POS_MIN = 1;
    private static final int JUE_ZHAN_POS_MAX = 14;
    /** 产品：单日参战机器人随机下限/上限（整表 ~5200 过大）。 */
    private static final int POOL_MIN = 50;
    private static final int POOL_MAX = 100;
    /** 建池后每机器人模拟排位场次数（真实 ΔJiFen 落盘）。 */
    private static final int POOL_SEED_MATCHES = 5;
    /**
     * 开战 matchPlayer.TargetGuid = robotGuid + 本偏移（&gt;1e6），客户端走 WJ 列表只灌本轮 1 将；
     * ≤1e6 会整队读 JJC_Robot。FightSync 注册同一 fightGuid。
     */
    public static final int FIGHT_GUID_OFFSET = 1_000_000;

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final GameTables tables;
    private final ProgressService progress;
    private final FightSyncService fightSync;
    private final CultivateTables cultivate;
    private final FightConfigTables fightCfg;

    public ZbzService(PlayerStore store, PlayerDumpService dump, GameTables tables, ProgressService progress,
                      FightSyncService fightSync, CultivateTables cultivate, FightConfigTables fightCfg) {
        this.store = store;
        this.dump = dump;
        this.tables = tables;
        this.progress = progress;
        this.fightSync = fightSync;
        this.cultivate = cultivate;
        this.fightCfg = fightCfg;
    }

    /**
     * 调度到点推阶段：对齐真服主动 S2C 4605。
     * 无变化则静默；有变化则 {@link #syncPhase} 内发 4605（客户端再 4101 刷 4602）。
     */
    public void pushPhaseIfChanged(GameSession session) {
        if (session == null || session.player() == null) {
            return;
        }
        PlayerRecord rec = session.player();
        rec.ensureCollections();
        GamePacket pkt = new GamePacket(0, 0, new byte[0]);
        syncPhase(session, pkt, rec);
    }

    public void pushInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        syncPhase(session, pkt, rec);
        session.send(MsgIds.S2C_ZBZ_INFO, pkt, buildInfoBody(rec));
    }

    /** 回城补推（已过新手本）；进界面仍会 4101。 */
    public void pushInfoIfReady(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null || !rec.alreadyNewUserGuideFB) {
            return;
        }
        pushInfo(session, pkt);
    }

    public void onMatchPlayer(GameSession session, GamePacket pkt) {
        pushInfo(session, pkt);
    }

    public void onCurFighter(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        if (denyUnderOpenLevel(rec, "curFighter")) {
            return;
        }
        syncPhase(session, pkt, rec);
        int targetId = Pb.read(pkt.body).getInt(1, 0);
        if (targetId <= 0) {
            targetId = rec.zbz.curTargetPlayerId > 0 ? rec.zbz.curTargetPlayerId : pickRobotGuid(rec, 1);
        }
        // 整场已结：APK Continue 仍可能对「上一对手」发 4104；回 curRoundCnt=11 收口
        if (rec.zbz.matchSettled) {
            int settled = rec.zbz.lastSettledTargetId;
            if (settled > 0 && (targetId == settled || targetId <= 0)) {
                List<byte[]> defense = buildFoeDefenseWjs(settled, rec.level, rec);
                List<byte[]> atkDef = buildAttackerDefenseWjs(rec);
                session.send(MsgIds.S2C_ZBZ_CUR_FIGHTER, pkt,
                        dump.zbzNextFighter(defense, atkDef, rec.zbz.hadUsedWuJiangIds, 11, 11));
                log.info("{} zbz curFighter settled target={} round=11", rec.account, settled);
                return;
            }
            // 换对手：清收口标记再开
            clearMatch(rec);
            beginOrContinueMatch(rec, targetId);
        } else {
            beginOrContinueMatch(rec, targetId);
        }
        store.save(rec);
        List<byte[]> defense = buildFoeDefenseWjs(targetId, rec.level, rec);
        List<byte[]> atkDef = buildAttackerDefenseWjs(rec);
        int round = Math.max(1, rec.zbz.matchCurRound);
        session.send(MsgIds.S2C_ZBZ_CUR_FIGHTER, pkt,
                dump.zbzNextFighter(defense, atkDef, rec.zbz.hadUsedWuJiangIds, round, round));
        log.info("{} zbz curFighter target={} round={}/{} def={} used={}",
                rec.account, targetId, round, rec.zbz.matchDefenseCount,
                defense.size(), rec.zbz.hadUsedWuJiangIds.size());
    }

    /** @return true=已开战回包，Dispatcher 才 beginBattle。 */
    public boolean onFight(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return false;
        }
        rec.ensureCollections();
        syncPhase(session, pkt, rec);
        if (denyUnderOpenLevel(rec, "fight")) {
            return false;
        }
        // APK：Top8 Fight 仅 CurState>2；排位 CurState=1；下注 State=2 无出击
        int st = rec.zbz.curState;
        if (st != 1 && st != 3 && st != 4 && st != 5) {
            log.info("{} zbz fight denied state={}", rec.account, st);
            return false;
        }
        int targetId = Pb.read(pkt.body).getInt(1, rec.zbz.curTargetPlayerId);
        if (targetId <= 0) {
            targetId = pickRobotGuid(rec, 1);
        }
        if (rec.zbz.matchSettled && targetId == rec.zbz.lastSettledTargetId) {
            log.info("{} zbz fight denied settled target={}", rec.account, targetId);
            return false;
        }
        if (rec.zbz.matchSettled) {
            clearMatch(rec);
        }
        beginOrContinueMatch(rec, targetId);
        rec.currentRegionId = REGION;
        store.save(rec);
        // 灌 matchPlayer：复用 2004 解包；TargetGuid=robot+1e6 只带本轮 1 主将
        int round = Math.max(1, rec.zbz.matchCurRound);
        PlayerRecord foeRec = store.findByPlayerId(targetId);
        int fightGuid;
        byte[] other;
        byte[] myTeam = dump.jjcFightTargetDetailSelf(rec);
        if (foeRec != null && foeRec.npcPassive) {
            fightGuid = foeRec.playerId;
            other = dump.zbzFightOtherTeamFromPlayer(foeRec, round, fightGuid);
        } else {
            fightGuid = targetId + FIGHT_GUID_OFFSET;
            GameTables.RobotRow robot = tables.robotByGuid(targetId);
            int heroIdx = FightRosterBuilder.robotRoundHeroIndex(robot, round);
            other = dump.zbzFightOtherTeam(robot, heroIdx, fightGuid);
        }
        session.send(MsgIds.S2C_JJC_FIGHT_TEAMS, pkt, dump.jjcFightTeams(myTeam, other));
        session.send(MsgIds.S2C_ZBZ_FIGHT_RET, pkt, new byte[0]);
        session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(REGION));
        log.info("{} zbz fight target={} round={} fightGuid={} state={} region={}",
                rec.account, targetId, round, fightGuid, rec.zbz.curState, REGION);
        return true;
    }

    public void onResult(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        if (rec.zbz.matchSettled) {
            log.info("{} zbz result denied: already settled", rec.account);
            session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(2, 0));
            return;
        }
        boolean hadBattle = fightSync.hasBattle(session);
        boolean win = hadBattle && fightSync.playerWon(session, rec.playerId);
        if (!hadBattle) {
            log.info("{} zbz result denied: no battle", rec.account);
            session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(2, 0));
            return;
        }
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        FightSyncService.ZbzBattleSnap snap = fightSync.zbzBattleSnap(session, rec.playerId);
        markAttackersUsed(rec);
        boolean matchOver;
        if (win) {
            if (!rec.zbz.matchFoeDeadRounds.contains(Integer.valueOf(rec.zbz.matchCurRound))) {
                rec.zbz.matchFoeDeadRounds.add(Integer.valueOf(rec.zbz.matchCurRound));
            }
            if (rec.zbz.matchCurRound < Math.max(1, rec.zbz.matchDefenseCount)
                    && rec.zbz.matchCurRound < 10) {
                rec.zbz.matchCurRound++;
                matchOver = false;
            } else {
                matchOver = true;
            }
        } else {
            matchOver = true;
        }
        if (!matchOver) {
            store.save(rec);
            session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(1, 3));
            log.info("{} zbz result mid-win round→{}/{} kill={}",
                    rec.account, rec.zbz.matchCurRound, rec.zbz.matchDefenseCount, snap.killNum);
            return;
        }
        GameTables.ZbzPrize prize = win
                ? (rec.zbz.curState >= 3 ? cfg.top8Win : cfg.pwsWin)
                : (rec.zbz.curState >= 3 ? cfg.top8Fail : cfg.pwsFail);
        grantTablePrize(session, pkt, rec, prize);
        // 整场击杀=已击败轮次数（中轮写入 matchFoeDeadRounds）；末局 snap 仅 1v1 一刀，不可单独当公式输入
        int totalKills = rec.zbz.matchFoeDeadRounds != null ? rec.zbz.matchFoeDeadRounds.size() : 0;
        if (totalKills <= 0) {
            totalKills = Math.max(0, snap.killNum);
        }
        int delta = calcJiFenDelta(cfg, rec.zbz.xingJi, win, totalKills, snap.foeFp);
        rec.zbz.jiFen = Math.max(0, rec.zbz.jiFen + delta);
        int foeId = rec.zbz.curTargetPlayerId;
        if (foeId > 0 && findPoolRobot(rec, foeId) != null) {
            applyPlayerVsPoolRobotScore(rec, foeId, win, totalKills, snap.foeFp, snap.selfDead, snap.myFp);
        }
        if (rec.zbz.curState >= 3) {
            applyBracketFightResult(rec, win, totalKills);
            settleOtherBracketMatches(rec);
        } else if (foeId > 0) {
            // 排位条：FillInfo 用 bFight+Win 显示胜负
            markRankingSlotResult(rec, foeId, win);
        }
        // 整场收口：CurTarget 清 0（排位其它槽可再出击）；lastSettled+round11 防同目标重开刷奖
        rec.zbz.matchSettled = true;
        rec.zbz.lastSettledTargetId = foeId;
        rec.zbz.matchCurRound = 11;
        rec.zbz.curTargetPlayerId = 0;
        store.save(rec);
        pushInfo(session, pkt);
        session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(win ? 1 : 2, win ? 3 : 0));
        log.info("{} zbz result end win={} state={} ΔjiFen={} kills={} foeFp={} gold+{}",
                rec.account, win, rec.zbz.curState, delta, totalKills, snap.foeFp, prize.gold);
    }

    /**
     * 产品拍板积分：Δ = 胜/败基础分 + ⌊XingJi×星级系数⌋ + ⌊击杀×敌方总战力×战斗系数⌋。
     * 表系数客户端不读；真服完整式未知，本式写入 PROTOCOL。
     */
    private static int calcJiFenDelta(GameTables.ZbzCfg cfg, int xingJi, boolean win, int killNum, int foeFp) {
        int base = win ? cfg.winBaseScore : cfg.failBaseScore;
        int starPart = (int) Math.floor(Math.max(0, xingJi) * cfg.starScoreCoeff);
        int kills = Math.max(0, killNum);
        int fp = Math.max(0, foeFp);
        int fightPart = (int) Math.floor(kills * (double) fp * cfg.fightScoreCoeff);
        return base + starPart + fightPart;
    }

    public void onRank(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        session.send(MsgIds.S2C_ZBZ_RANK, pkt, buildRankList(rec));
    }

    public void onChangeDefense(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        if (denyUnderOpenLevel(rec, "defense")) {
            return;
        }
        List<String> ids = Pb.read(pkt.body).getStrings(1);
        List<String> next = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.isEmpty()) {
                    continue;
                }
                if (rec.findHero(id) == null) {
                    log.info("{} zbz defense skip unknown hero {}", rec.account, id);
                    continue;
                }
                if (next.contains(id)) {
                    continue;
                }
                if (next.size() >= 10) {
                    break;
                }
                next.add(id);
            }
        }
        // APK BuFang：10 槽全满才可存；不足则拒写，避免「半布防」进战斗
        if (next.size() < 10) {
            log.info("{} zbz defense denied size={} need=10", rec.account, next.size());
            pushInfo(session, pkt);
            return;
        }
        rec.zbz.defenseWuJiangIds = next;
        rec.zbz.hadDefense = true;
        store.save(rec);
        pushInfo(session, pkt);
        log.info("{} zbz defense saved n={}", rec.account, next.size());
    }

    /** C2S 4102：MoneyType 1 金币 / 2 钻石；仅 CurState=2；目标须在当前括号（APK YaZhu 列表=Top8）。 */
    public void onGamble(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        if (denyUnderOpenLevel(rec, "gamble")) {
            return;
        }
        syncPhase(session, pkt, rec);
        if (rec.zbz.curState != 2) {
            log.info("{} zbz gamble denied state={}", rec.account, rec.zbz.curState);
            return;
        }
        ensureBracket(rec);
        Pb.Fields body = Pb.read(pkt.body);
        int targetId = body.getInt(1, 0);
        int moneyType = body.getInt(2, 0);
        if (findBracket(rec, targetId) == null) {
            log.info("{} zbz gamble denied target {} not in bracket", rec.account, targetId);
            pushInfo(session, pkt);
            return;
        }
        if (rec.zbz.gambleTargetId > 0 && targetId != rec.zbz.gambleTargetId
                && (rec.zbz.gambleGoldPaid || rec.zbz.gambleDiamondPaid)) {
            log.info("{} zbz gamble target locked have={} want={}",
                    rec.account, rec.zbz.gambleTargetId, targetId);
            pushInfo(session, pkt);
            return;
        }
        if (moneyType == 1 && rec.zbz.gambleGoldPaid) {
            log.info("{} zbz gamble gold already", rec.account);
            pushInfo(session, pkt);
            return;
        }
        if (moneyType == 2 && rec.zbz.gambleDiamondPaid) {
            log.info("{} zbz gamble diamond already", rec.account);
            pushInfo(session, pkt);
            return;
        }
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        if (moneyType == 1) {
            if (rec.gold < cfg.gambleGold) {
                log.info("{} zbz gamble gold lack have={} need={}", rec.account, rec.gold, cfg.gambleGold);
                return;
            }
            rec.gold -= cfg.gambleGold;
            progress.pushGold(session, pkt, rec);
            rec.zbz.gambleGoldPaid = true;
        } else if (moneyType == 2) {
            if (rec.diamond < cfg.gambleDiamond) {
                log.info("{} zbz gamble diamond lack have={} need={}", rec.account, rec.diamond, cfg.gambleDiamond);
                return;
            }
            rec.diamond -= cfg.gambleDiamond;
            progress.pushDiamond(session, pkt, rec);
            progress.addTodayCost(session, pkt, rec, cfg.gambleDiamond);
            rec.zbz.gambleDiamondPaid = true;
        } else {
            log.info("{} zbz gamble bad moneyType={}", rec.account, moneyType);
            return;
        }
        rec.zbz.gambleTargetId = targetId;
        rec.zbz.gambleMoneyType = moneyType;
        if (rec.zbz.gambleRate <= 0f) {
            rec.zbz.gambleRate = resolveGambleRate(rec, targetId);
        }
        rec.zbz.gamblePaid = rec.zbz.gambleGoldPaid || rec.zbz.gambleDiamondPaid;
        store.save(rec);
        pushInfo(session, pkt);
        log.info("{} zbz gamble target={} type={} gtype={}",
                rec.account, targetId, moneyType, resolveGambleType(rec));
    }

    /**
     * C2S 4108：决战名次奖 + 押冠赔付。
     * APK 仅 Top8UI 且自己在 playerinfo 时发；未入八强不发 4108——故淘汰结束时 {@link #syncPhase} 也会主动 settle。
     */
    public void onJueZhanAward(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        syncPhase(session, pkt, rec);
        settleJueZhanAwards(session, pkt, rec);
    }

    /**
     * 括号结束后发名次奖（入围者）+ 押冠赔付（任何人押中即赔，不依赖是否入八强）。
     * 入账后推 S2C 4607（客无请求门闩，来了就弹领奖）。
     */
    private void settleJueZhanAwards(GameSession session, GamePacket pkt, PlayerRecord rec) {
        if (rec.zbz.jueZhanAwarded) {
            return;
        }
        // 硬门闩：下注预览窗（State=2）Top8 会刷 4108，绝不可发奖/forceFinish
        if (rec.zbz.curState == 2) {
            return;
        }
        if (rec.zbz.curState >= 3 && rec.zbz.curState <= 5 && !bracketFinished(rec)) {
            return;
        }
        if (rec.zbz.bracket == null || rec.zbz.bracket.isEmpty()) {
            return;
        }
        forceFinishBracket(rec);
        sanitizeBracketPositions(rec);
        PlayerRecord.ZbzBracketPlayer self = findBracket(rec, rec.playerId);
        int place = 0;
        int gold = 0;
        int diamond = 0;
        int wnsp = 0;
        int yingPo = 0;
        String goodsOri = "";
        int goodsCount = 0;
        if (self != null) {
            place = placeFromBracket(rec, self);
            GameTables.ZbzPrize prize = placePrize(place);
            grantTablePrize(session, pkt, rec, prize);
            if (prize != null) {
                gold = prize.gold;
                diamond = prize.diamond;
                wnsp = prize.wnsp;
                yingPo = prize.yingPo;
                goodsOri = prize.goodsOri != null ? prize.goodsOri : "";
                goodsCount = prize.goodsCount;
            }
        }
        int gambleGold = 0;
        int gambleDiamond = 0;
        boolean gambleWin = rec.zbz.gamblePaid && isChampion(rec, rec.zbz.gambleTargetId);
        if (gambleWin) {
            int[] add = payoutGamble(session, pkt, rec);
            gambleGold = add[0];
            gambleDiamond = add[1];
        }
        // 未入围且未押 / 押空：无 4607；押空仍标记已结避免反复
        if (self == null && !rec.zbz.gamblePaid) {
            return;
        }
        if (self == null && rec.zbz.gamblePaid && !gambleWin) {
            rec.zbz.jueZhanAwarded = true;
            store.save(rec);
            log.info("{} zbz jueZhan settle: not in bracket, gamble miss", rec.account);
            return;
        }
        gold += gambleGold;
        diamond += gambleDiamond;
        rec.zbz.jueZhanAwarded = true;
        store.save(rec);
        session.send(MsgIds.S2C_ZBZ_JUEZHAN_AWARD, pkt, dump.zbzJueZhanAward(
                place, gold, diamond, wnsp, yingPo, goodsOri, goodsCount));
        log.info("{} zbz jueZhanAward place={} gambleWin={} gold={} diamond={}",
                rec.account, place, gambleWin, gold, diamond);
    }

    // ---- phase / bracket ----

    private void syncPhase(GameSession session, GamePacket pkt, PlayerRecord rec) {
        int next = computeCurState();
        String today = GameTime.today().toString();
        if (!today.equals(rec.zbz.bracketDay)) {
            rec.zbz.bracketDay = today;
            rec.zbz.bracket = new ArrayList<>();
            rec.zbz.hadUsedWuJiangIds = new ArrayList<>();
            rec.zbz.gamblePaid = false;
            rec.zbz.gambleGoldPaid = false;
            rec.zbz.gambleDiamondPaid = false;
            rec.zbz.gambleTargetId = 0;
            rec.zbz.gambleMoneyType = 0;
            rec.zbz.gambleRate = 0f;
            rec.zbz.jueZhanAwarded = false;
            clearMatch(rec);
            // 换日：清括号/下注/HadUsed/比赛进度；**保留 JiFen**（表无日清；排名邮按累计分）
            rec.zbz.hadUsedWuJiangIds = new ArrayList<>();
        }
        ensureParticipatingPool(rec);
        if (next == 2) {
            // 下注窗：首次建预览括号（供 Gamble）；不清 JiFen
            ensureBracket(rec);
        }
        if (rec.zbz.curState != next) {
            int old = rec.zbz.curState;
            // 淘汰子阶段切换/离开：先收口上一段未打完（含玩家 → 布防 1v1），对齐真服窗口结束结算
            if (old >= 3 && old <= 5) {
                forceFinishBracket(rec);
            }
            rec.zbz.curState = next;
            if (next >= 3 && old < 3) {
                // 进淘汰：按当时积分锁 Top8（覆盖早上下注预览）
                rebuildBracket(rec);
                resetBracketForKnockoutRound(rec);
                settleOtherBracketMatches(rec);
            } else if (next >= 3 && old >= 3) {
                resetBracketForKnockoutRound(rec);
                settleOtherBracketMatches(rec);
            }
            // 整段淘汰结束（回排位/休赛）：名次+押冠；未入八强无 4108，服主动 4607
            if (old >= 3 && old <= 5 && next < 3) {
                settleJueZhanAwards(session, pkt, rec);
            }
            store.save(rec);
            session.send(MsgIds.S2C_ZBZ_STATE_CHANGE, pkt, dump.zbzStateChange(next));
            log.info("{} zbz state {} -> {}", rec.account, old, next);
        } else if (rec.zbz.lastPushedState != next) {
            rec.zbz.lastPushedState = next;
            store.save(rec);
        }
        rec.zbz.lastPushedState = next;
        if (rec.zbz.curState >= 3) {
            ensureBracket(rec);
            settleOtherBracketMatches(rec);
        }
    }

    private boolean denyUnderOpenLevel(PlayerRecord rec, String op) {
        int open = tables.zbzCfg().openLevel;
        if (rec.level < open) {
            log.info("{} zbz {} denied lv={} open={}", rec.account, op, rec.level, open);
            return true;
        }
        return false;
    }

    /**
     * 按表日时段（对齐 APK 表语义，产品）：淘汰窗优先 → 排位窗内为 1 → 否则下注窗为 2 → 默认 1。
     * 避免「下注 6:00 起」吞掉白天排位（10:00–23:20）。
     */
    private int computeCurState() {
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        LocalTime now = GameTime.localTime();
        int min = now.getHour() * 60 + now.getMinute();
        if (min >= cfg.round8BeginMin && min < cfg.round8EndMin) {
            return 3;
        }
        if (min >= cfg.round8EndMin && min < cfg.round4EndMin) {
            return 4;
        }
        if (min >= cfg.round4EndMin && min < cfg.finalEndMin) {
            return 5;
        }
        if (min >= cfg.pwsBeginMin && min < cfg.pwsEndMin) {
            return 1;
        }
        if (min >= cfg.yaZhuMin && min < cfg.round8BeginMin) {
            return 2;
        }
        return 1;
    }

    private void ensureBracket(PlayerRecord rec) {
        if (rec.zbz.bracket != null && rec.zbz.bracket.size() >= BRACKET_SIZE) {
            return;
        }
        rebuildBracket(rec);
    }

    /** 按当前积分榜重建 Top8（晋级 Pos 从 1–8 起）；不改 JiFen。 */
    private void rebuildBracket(PlayerRecord rec) {
        List<RankEntry> entries = buildScoreBoard(rec);
        List<PlayerRecord.ZbzBracketPlayer> list = new ArrayList<>();
        int n = Math.min(BRACKET_SIZE, entries.size());
        for (int i = 0; i < n; i++) {
            RankEntry e = entries.get(i);
            PlayerRecord.ZbzBracketPlayer bp = new PlayerRecord.ZbzBracketPlayer();
            bp.playerId = e.guid;
            bp.name = e.name;
            bp.resId = e.resId;
            bp.level = e.level;
            bp.jiFen = e.jiFen;
            bp.jueZhanPos = clampJueZhanPos(i + 1);
            bp.jueZhanGroup = jueZhanGroupOf(bp.jueZhanPos);
            bp.jueZhanRank = 8;
            bp.win = 0;
            bp.bFight = false;
            bp.killNum1 = 0;
            bp.killNum2 = 0;
            list.add(bp);
        }
        rec.zbz.bracket = list;
        sanitizeBracketPositions(rec);
        store.save(rec);
    }

    private void beginOrContinueMatch(PlayerRecord rec, int targetId) {
        if (rec.zbz.matchSettled && rec.zbz.lastSettledTargetId == targetId) {
            return;
        }
        if (rec.zbz.curTargetPlayerId == targetId && rec.zbz.matchCurRound > 0
                && rec.zbz.matchCurRound <= 10
                && rec.zbz.matchDefenseCount > 0
                && !rec.zbz.matchSettled) {
            return;
        }
        rec.zbz.matchSettled = false;
        rec.zbz.lastSettledTargetId = 0;
        rec.zbz.curTargetPlayerId = targetId;
        rec.zbz.matchCurRound = 1;
        rec.zbz.matchFoeDeadRounds = new ArrayList<>();
        PlayerRecord foeNpc = store.findByPlayerId(targetId);
        int count;
        if (foeNpc != null && foeNpc.npcPassive) {
            count = FightRosterBuilder.playerDefenseCount(foeNpc);
        } else {
            GameTables.RobotRow robot = tables.robotByGuid(targetId);
            count = 0;
            if (robot != null) {
                for (int wjId : robot.wjIndex) {
                    if (wjId > 0) {
                        count++;
                    }
                }
            }
        }
        if (count <= 0) {
            count = 1;
        }
        rec.zbz.matchDefenseCount = Math.min(10, count);
        if (rec.zbz.curState < 3) {
            rec.zbz.hadUsedWuJiangIds = new ArrayList<>();
        }
    }

    private void clearMatch(PlayerRecord rec) {
        rec.zbz.curTargetPlayerId = 0;
        rec.zbz.matchCurRound = 0;
        rec.zbz.matchDefenseCount = 0;
        rec.zbz.matchFoeDeadRounds = new ArrayList<>();
        rec.zbz.matchSettled = false;
        rec.zbz.lastSettledTargetId = 0;
    }

    private void markRankingSlotResult(PlayerRecord rec, int foeId, boolean playerWin) {
        PlayerRecord.ZbzPoolRobot pr = findPoolRobot(rec, foeId);
        if (pr == null) {
            return;
        }
        pr.lastBFight = true;
        pr.lastWin = playerWin ? 1 : 2;
    }

    private void markAttackersUsed(PlayerRecord rec) {
        for (String id : rec.formationSlots(PlayerRecord.FORMATION_ZBZ)) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (!rec.zbz.hadUsedWuJiangIds.contains(id)) {
                rec.zbz.hadUsedWuJiangIds.add(id);
            }
        }
    }

    private byte[] buildInfoBody(PlayerRecord rec) {
        sanitizeBracketPositions(rec);
        List<byte[]> match;
        List<byte[]> gambles = null;
        List<byte[]> top3 = null;
        String startTime = "";
        if (rec.zbz.curState >= 2 && rec.zbz.bracket != null && !rec.zbz.bracket.isEmpty()) {
            match = new ArrayList<>();
            for (PlayerRecord.ZbzBracketPlayer bp : rec.zbz.bracket) {
                match.add(dump.zbzMatchPlayerFull(
                        bp.playerId, bp.name, bp.resId, bp.level, bp.jiFen,
                        bp.bFight, 0, bp.killNum1, bp.killNum2, bp.win,
                        bp.jueZhanGroup, bp.jueZhanRank, bp.jueZhanPos));
            }
            if (rec.zbz.curState == 2) {
                gambles = buildGambleInfos(rec);
            }
            top3 = buildTop3(rec);
            GameTables.ZbzCfg cfg = tables.zbzCfg();
            startTime = String.format("%02d:%02d:00", cfg.round8BeginMin / 60, cfg.round8BeginMin % 60);
        } else {
            match = buildRankingMatchPlayers(rec);
        }
        return dump.zbzInfo(rec, match, gambles, top3, startTime);
    }

    private List<byte[]> buildGambleInfos(PlayerRecord rec) {
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        List<byte[]> out = new ArrayList<>();
        List<PlayerRecord.ZbzBracketPlayer> sorted = new ArrayList<>(rec.zbz.bracket);
        sorted.sort(Comparator.comparingInt((PlayerRecord.ZbzBracketPlayer b) -> b.jiFen).reversed());
        for (int i = 0; i < sorted.size() && i < 8; i++) {
            PlayerRecord.ZbzBracketPlayer bp = sorted.get(i);
            float lo = cfg.gambleRateLo[Math.min(i, cfg.gambleRateLo.length - 1)];
            float hi = cfg.gambleRateHi[Math.min(i, cfg.gambleRateHi.length - 1)];
            // 未押：展示区间中点；已押该人：展示落盘锁定赔率（与赔付一致）
            float rate = (lo + hi) * 0.5f;
            int gtype = 0;
            if (bp.playerId == rec.zbz.gambleTargetId) {
                gtype = resolveGambleType(rec);
                if (rec.zbz.gambleRate > 0f) {
                    rate = rec.zbz.gambleRate;
                }
            }
            out.add(dump.zbzGambleInfo(i + 1, bp.playerId, bp.name, bp.resId, bp.level, gtype, rate));
        }
        return out;
    }

    private List<byte[]> buildTop3(PlayerRecord rec) {
        List<PlayerRecord.ZbzBracketPlayer> sorted = new ArrayList<>(rec.zbz.bracket);
        // 淘汰进行中/结束后：按 JueZhanRank 升序（冠=1）；否则按积分
        boolean byRank = rec.zbz.curState >= 3;
        if (byRank) {
            sorted.sort(Comparator
                    .comparingInt((PlayerRecord.ZbzBracketPlayer b) -> b.jueZhanRank <= 0 ? 99 : b.jueZhanRank)
                    .thenComparing(Comparator.comparingInt((PlayerRecord.ZbzBracketPlayer b) -> b.jiFen).reversed()));
        } else {
            sorted.sort(Comparator.comparingInt((PlayerRecord.ZbzBracketPlayer b) -> b.jiFen).reversed());
        }
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < 3 && i < sorted.size(); i++) {
            PlayerRecord.ZbzBracketPlayer bp = sorted.get(i);
            out.add(dump.zbzTop3(bp.name, bp.resId, "本服"));
        }
        return out;
    }

    private List<byte[]> buildRankingMatchPlayers(PlayerRecord rec) {
        // 排位 UI：对手条；Left=JiFen1/KillNum1 己方，Right=JiFen2/KillNum2 对手
        List<byte[]> list = new ArrayList<>();
        for (MatchCand c : pickRankingOpponents(rec, RANKING_OPPONENT_SLOTS)) {
            list.add(dump.zbzMatchPlayerFull(
                    c.guid, c.name, c.resId, c.level,
                    rec.zbz.jiFen, c.bFight, c.jiFen, c.killByPlayer, c.killByRobot,
                    c.win, 0, 0, 0));
        }
        return list;
    }

    /**
     * 匹配浮动：在「展示积分」落在 [己方JiFen±matchFloat] 的机器人里取对手；不足则扩大窗口。
     */
    private List<MatchCand> pickRankingOpponents(PlayerRecord rec, int need) {
        ensureParticipatingPool(rec);
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        int my = Math.max(0, rec.zbz.jiFen);
        int window = Math.max(1, cfg.matchFloat);
        List<MatchCand> pool = new ArrayList<>();
        for (PlayerRecord.ZbzPoolRobot pr : rec.zbz.poolRobots) {
            MatchCand c = matchCandForPoolGuid(pr);
            if (c != null) {
                pool.add(c);
            }
        }
        List<MatchCand> picked = new ArrayList<>();
        int expand = 0;
        while (picked.size() < need && expand < 10000) {
            int lo = my - window - expand;
            int hi = my + window + expand;
            for (MatchCand c : pool) {
                if (picked.size() >= need) {
                    break;
                }
                if (c.jiFen < lo || c.jiFen > hi) {
                    continue;
                }
                boolean dup = false;
                for (MatchCand p : picked) {
                    if (p.guid == c.guid) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    picked.add(c);
                }
            }
            if (picked.size() >= need) {
                break;
            }
            expand += window;
        }
        // 仍不足：按积分接近度补满
        if (picked.size() < need) {
            pool.sort(Comparator.comparingInt(c -> Math.abs(c.jiFen - my)));
            for (MatchCand c : pool) {
                if (picked.size() >= need) {
                    break;
                }
                boolean dup = false;
                for (MatchCand p : picked) {
                    if (p.guid == c.guid) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    picked.add(c);
                }
            }
        }
        return picked;
    }

    private void applyBracketFightResult(PlayerRecord rec, boolean win, int killNum) {
        ensureBracket(rec);
        PlayerRecord.ZbzBracketPlayer self = findBracket(rec, rec.playerId);
        PlayerRecord.ZbzBracketPlayer foe = findBracket(rec, rec.zbz.curTargetPlayerId);
        if (self == null) {
            return;
        }
        applyPairResult(self, foe, win, Math.max(1, killNum));
    }

    /**
     * 非玩家场次：真服服内结算，客户端不观战只刷树。
     * 假服：同 Group 且双方 !bFight 的对位模拟；含玩家的 Group 跳过。
     */
    private void settleOtherBracketMatches(PlayerRecord rec) {
        if (rec.zbz.bracket == null || rec.zbz.bracket.size() < 2 || rec.zbz.curState < 3) {
            return;
        }
        Map<Integer, List<PlayerRecord.ZbzBracketPlayer>> byGroup = new HashMap<>();
        for (PlayerRecord.ZbzBracketPlayer bp : rec.zbz.bracket) {
            if (bp.win == 2 || bp.bFight) {
                continue;
            }
            byGroup.computeIfAbsent(Integer.valueOf(bp.jueZhanGroup), k -> new ArrayList<>()).add(bp);
        }
        for (List<PlayerRecord.ZbzBracketPlayer> pair : byGroup.values()) {
            if (pair.size() < 2) {
                continue;
            }
            pair.sort(Comparator.comparingInt(b -> b.jueZhanPos));
            PlayerRecord.ZbzBracketPlayer a = pair.get(0);
            PlayerRecord.ZbzBracketPlayer b = pair.get(1);
            if (a.playerId == rec.playerId || b.playerId == rec.playerId) {
                continue;
            }
            FightSyncService.RobotMatchResult sim = fightSync.simulateRobotVsRobot(a.playerId, b.playerId);
            boolean aWins = sim.winnerGuid == a.playerId;
            applyPairResult(a, b, aWins, Math.max(1, sim.killNum));
            applySimScoreToPoolRobots(rec, sim);
        }
        store.save(rec);
    }

    /** 赛程结束：强制结算所有未打完对位；含玩家的对位用布防做机器人来攻模拟（不再无脑判负）。 */
    private void forceFinishBracket(PlayerRecord rec) {
        if (rec.zbz.bracket == null) {
            return;
        }
        for (int guard = 0; guard < 5; guard++) {
            for (PlayerRecord.ZbzBracketPlayer bp : rec.zbz.bracket) {
                if (bp.win != 2) {
                    bp.bFight = false;
                }
            }
            Map<Integer, List<PlayerRecord.ZbzBracketPlayer>> byGroup = new HashMap<>();
            for (PlayerRecord.ZbzBracketPlayer bp : rec.zbz.bracket) {
                if (bp.win == 2 || bp.bFight) {
                    continue;
                }
                byGroup.computeIfAbsent(Integer.valueOf(bp.jueZhanGroup), k -> new ArrayList<>()).add(bp);
            }
            if (byGroup.isEmpty()) {
                break;
            }
            boolean any = false;
            for (List<PlayerRecord.ZbzBracketPlayer> pair : byGroup.values()) {
                if (pair.size() < 2) {
                    continue;
                }
                pair.sort(Comparator.comparingInt(b -> b.jueZhanPos));
                PlayerRecord.ZbzBracketPlayer a = pair.get(0);
                PlayerRecord.ZbzBracketPlayer b = pair.get(1);
                boolean aPlayer = a.playerId == rec.playerId;
                boolean bPlayer = b.playerId == rec.playerId;
                if (aPlayer || bPlayer) {
                    int robotGuid = aPlayer ? b.playerId : a.playerId;
                    FightSyncService.RobotMatchResult sim =
                            fightSync.simulateRobotAttackPlayerDefense(robotGuid, rec);
                    applyDefenseChallengeOutcome(rec, a, b, sim);
                } else {
                    FightSyncService.RobotMatchResult sim =
                            fightSync.simulateRobotVsRobot(a.playerId, b.playerId);
                    applyPairResult(a, b, sim.winnerGuid == a.playerId, Math.max(1, sim.killNum));
                    applySimScoreToPoolRobots(rec, sim);
                }
                any = true;
            }
            if (!any) {
                break;
            }
        }
        store.save(rec);
    }

    /**
     * 机器人挑战主机（排位/后续来打）：用布防 1v1 模拟，写括号胜负与双方积分。
     * 含玩家的主动出击仍走客户端 4103；本方法供超时收口与服端挑战入口。
     */
    public FightSyncService.RobotMatchResult challengePlayerWithRobot(PlayerRecord rec, int robotGuid) {
        if (rec == null || robotGuid <= 0) {
            return null;
        }
        rec.ensureCollections();
        FightSyncService.RobotMatchResult sim =
                fightSync.simulateRobotAttackPlayerDefense(robotGuid, rec);
        PlayerRecord.ZbzBracketPlayer self = findBracket(rec, rec.playerId);
        PlayerRecord.ZbzBracketPlayer foe = findBracket(rec, robotGuid);
        if (self != null && foe != null) {
            applyDefenseChallengeOutcome(rec, self, foe, sim);
        } else {
            applyDefenseChallengeScoreOnly(rec, sim);
        }
        store.save(rec);
        return sim;
    }

    /**
     * 冒烟（无 Session）：强制重建参战池（优先 NPC）→ 4103 灌包可解 → 主机攻 NPC 布防 1v1 可结算。
     * @return null=通过；非空=失败原因
     */
    public String smokeVerify(PlayerRecord host) {
        if (host == null) {
            return "host null";
        }
        host.ensureCollections();
        int open = Math.max(1, tables.zbzCfg().openLevel);
        if (host.level < open) {
            host.level = open;
        }
        ensureZbzAttackAndDefense(host);
        if (FightRosterBuilder.fromPlayer(host, cultivate, fightCfg, PlayerRecord.FORMATION_ZBZ).isEmpty()) {
            return "host FORMATION_ZBZ empty";
        }
        if (FightRosterBuilder.playerDefenseCount(host) <= 0) {
            return "host defenseWuJiangIds empty";
        }
        // 强制重建池，验证 NPC 优先路径
        host.zbz.poolDay = "";
        host.zbz.poolRobots = new ArrayList<>();
        host.zbz.poolRobotGuids = new ArrayList<>();
        ensureParticipatingPool(host);
        if (host.zbz.poolRobots == null || host.zbz.poolRobots.size() < POOL_MIN) {
            return "pool size=" + (host.zbz.poolRobots == null ? 0 : host.zbz.poolRobots.size())
                    + " need>=" + POOL_MIN;
        }
        PlayerRecord firstNpc = null;
        int npcN = 0;
        for (PlayerRecord.ZbzPoolRobot pr : host.zbz.poolRobots) {
            if (pr == null) {
                continue;
            }
            PlayerRecord p = store.findByPlayerId(pr.guid);
            if (p != null && p.npcPassive) {
                npcN++;
                if (firstNpc == null) {
                    firstNpc = p;
                }
            }
        }
        if (firstNpc == null) {
            return "no npc in pool (npcN=0 size=" + host.zbz.poolRobots.size() + ")";
        }
        firstNpc.ensureCollections();
        if (FightRosterBuilder.playerDefenseCount(firstNpc) <= 0) {
            return "npc defense empty account=" + firstNpc.account;
        }
        byte[] other = dump.zbzFightOtherTeamFromPlayer(firstNpc, 1, firstNpc.playerId);
        Pb.Fields of = Pb.read(other);
        if (of.getInt(1, 0) != firstNpc.playerId) {
            return "2004 TargetGuid=" + of.getInt(1, 0) + " want=" + firstNpc.playerId;
        }
        byte[] wj = of.getBytes(3);
        if (wj == null || wj.length == 0) {
            return "2004 WJ empty";
        }
        FightUnit round1 = FightRosterBuilder.fromPlayerDefenseRound(
                firstNpc, 1, cultivate, fightCfg);
        if (round1 == null) {
            return "fromPlayerDefenseRound null";
        }
        FightSyncService.RobotMatchResult atkSim =
                fightSync.simulatePlayerAttackPlayerDefense(host, firstNpc);
        if (atkSim == null || atkSim.winnerGuid <= 0) {
            return "host→npc sim fail";
        }
        FightSyncService.RobotMatchResult defSim =
                fightSync.simulatePlayerAttackPlayerDefense(firstNpc, host);
        if (defSim == null || defSim.winnerGuid <= 0) {
            return "npc→host sim fail";
        }
        beginOrContinueMatch(host, firstNpc.playerId);
        if (host.zbz.matchDefenseCount <= 0) {
            return "matchDefenseCount=0";
        }
        store.save(host);
        log.info("zbz smoke OK host={} pool={} npcInPool={} sample={} defCnt={} atkWin={} defWin={}",
                host.account, host.zbz.poolRobots.size(), npcN, firstNpc.playerId,
                host.zbz.matchDefenseCount, atkSim.winnerGuid, defSim.winnerGuid);
        return null;
    }

    /** 冒烟用：补 type31 攻阵与 10 槽布防。 */
    private void ensureZbzAttackAndDefense(PlayerRecord rec) {
        List<String> ids = new ArrayList<>();
        if (rec.heroes != null) {
            for (PlayerRecord.Hero h : rec.heroes) {
                if (h != null && h.id != null && !h.id.isEmpty()) {
                    ids.add(h.id);
                }
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        if (FightRosterBuilder.fromPlayer(rec, cultivate, fightCfg, PlayerRecord.FORMATION_ZBZ).isEmpty()) {
            List<String> atk = new ArrayList<>(ids.subList(0, Math.min(5, ids.size())));
            rec.setFormationSlots(PlayerRecord.FORMATION_ZBZ, atk);
        }
        if (FightRosterBuilder.playerDefenseCount(rec) <= 0) {
            List<String> def = new ArrayList<>();
            for (String id : ids) {
                def.add(id);
                if (def.size() >= 10) {
                    break;
                }
            }
            while (def.size() < 10 && !ids.isEmpty()) {
                def.add(ids.get(def.size() % ids.size()));
            }
            rec.zbz.defenseWuJiangIds = def;
        }
    }

    /** 括号两侧：按模拟胜负写 Pos/Win，并给主机+池机器人加减分。 */
    private void applyDefenseChallengeOutcome(PlayerRecord rec,
                                              PlayerRecord.ZbzBracketPlayer a,
                                              PlayerRecord.ZbzBracketPlayer b,
                                              FightSyncService.RobotMatchResult sim) {
        if (sim == null) {
            return;
        }
        boolean aWins = sim.winnerGuid == a.playerId;
        // applyPairResult(first, second, firstWins, …)：击杀记在胜方；sim.killNum=胜方击杀
        applyPairResult(a, b, aWins, Math.max(1, sim.killNum));
        applyDefenseChallengeScoreOnly(rec, sim);
    }

    private void applyDefenseChallengeScoreOnly(PlayerRecord rec, FightSyncService.RobotMatchResult sim) {
        if (sim == null) {
            return;
        }
        boolean playerWin = sim.winnerGuid == rec.playerId;
        int robotGuid = playerWin ? sim.loserGuid : sim.winnerGuid;
        int playerKill = playerWin ? sim.killNum : sim.loserKillNum;
        int robotKill = playerWin ? sim.loserKillNum : sim.killNum;
        int foeFp = playerWin ? sim.loserFp : sim.winnerFp;
        int myFp = playerWin ? sim.winnerFp : sim.loserFp;
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        int delta = calcJiFenDelta(cfg, rec.zbz.xingJi, playerWin, Math.max(0, playerKill), foeFp);
        rec.zbz.jiFen = Math.max(0, rec.zbz.jiFen + delta);
        PlayerRecord.ZbzBracketPlayer selfBp = findBracket(rec, rec.playerId);
        if (selfBp != null) {
            selfBp.jiFen = rec.zbz.jiFen;
        }
        applyPlayerVsPoolRobotScore(rec, robotGuid, playerWin, playerKill, foeFp, robotKill, myFp);
    }

    /** 进入下一淘汰段时：存活者清 bFight，便于本轮再出击/再结算。 */
    private void resetBracketForKnockoutRound(PlayerRecord rec) {
        if (rec.zbz.bracket == null) {
            return;
        }
        for (PlayerRecord.ZbzBracketPlayer bp : rec.zbz.bracket) {
            if (bp.win == 2) {
                continue;
            }
            bp.bFight = false;
            // 保留上一轮 Win=1 仅作展示；本轮未开打前允许再点出击（APK：!bFight && Win!=2）
        }
    }

    private static void applyPairResult(PlayerRecord.ZbzBracketPlayer winnerSide,
                                        PlayerRecord.ZbzBracketPlayer loserSide,
                                        boolean firstWins, int killNum) {
        PlayerRecord.ZbzBracketPlayer winP = firstWins ? winnerSide : loserSide;
        PlayerRecord.ZbzBracketPlayer loseP = firstWins ? loserSide : winnerSide;
        if (winP != null) {
            winP.bFight = true;
            winP.win = 1;
            winP.killNum1 += Math.max(1, killNum);
            winP.jueZhanRank = Math.max(1, winP.jueZhanRank / 2);
            winP.jueZhanPos = advanceJueZhanPos(winP.jueZhanPos);
            winP.jueZhanGroup = jueZhanGroupOf(winP.jueZhanPos);
        }
        if (loseP != null) {
            loseP.bFight = true;
            loseP.win = 2;
        }
    }

    /**
     * 晋级槽：1–8 → 9–12（四强列）；9–12 → 13–14（决赛列）；13–14 胜者落 14（冠军展示位）。
     * APK Top8UI 仅 14 槽；原「→15」会使 Pos-1=14 越界崩溃，故决赛胜者钳在 14。
     */
    private static int advanceJueZhanPos(int pos) {
        int p = clampJueZhanPos(pos);
        if (p <= 8) {
            return clampJueZhanPos(8 + (p + 1) / 2);
        }
        if (p <= 12) {
            return clampJueZhanPos(12 + (p - 8 + 1) / 2);
        }
        // 决赛胜者：UI 末槽
        return JUE_ZHAN_POS_MAX;
    }

    private static int clampJueZhanPos(int pos) {
        if (pos < JUE_ZHAN_POS_MIN) {
            return JUE_ZHAN_POS_MIN;
        }
        if (pos > JUE_ZHAN_POS_MAX) {
            return JUE_ZHAN_POS_MAX;
        }
        return pos;
    }

    private static int jueZhanGroupOf(int pos) {
        return Math.max(1, (clampJueZhanPos(pos) + 1) / 2);
    }

    /** 下发前校正括号 Pos/Group，防止脏档拖垮客户端。 */
    private void sanitizeBracketPositions(PlayerRecord rec) {
        if (rec.zbz.bracket == null) {
            return;
        }
        for (PlayerRecord.ZbzBracketPlayer bp : rec.zbz.bracket) {
            if (bp == null) {
                continue;
            }
            int before = bp.jueZhanPos;
            bp.jueZhanPos = clampJueZhanPos(bp.jueZhanPos);
            bp.jueZhanGroup = jueZhanGroupOf(bp.jueZhanPos);
            if (before != bp.jueZhanPos) {
                log.warn("{} zbz clamp JueZhanPos {} -> {} playerId={}",
                        rec.account, before, bp.jueZhanPos, bp.playerId);
            }
        }
    }

    private List<byte[]> buildRobotDefenseWjs(GameTables.RobotRow robot, int fallbackLevel, PlayerRecord rec) {
        List<byte[]> defense = new ArrayList<>();
        if (robot == null) {
            int state = foeRoundState(rec, 1);
            defense.add(dump.zbzDefenseWj(18, fallbackLevel, 3, 1, state));
            return defense;
        }
        int level = robot.level > 0 ? robot.level : fallbackLevel;
        int star = Math.max(1, robot.stars);
        int pin = Math.max(1, robot.stage);
        int roundIdx = 0;
        for (int i = 0; i < robot.wjIndex.length; i++) {
            int wjId = robot.wjIndex[i];
            if (wjId <= 0) {
                continue;
            }
            roundIdx++;
            int state = foeRoundState(rec, roundIdx);
            defense.add(dump.zbzDefenseWj(wjId, level, star, pin, state));
        }
        if (defense.isEmpty()) {
            int res = robot.resId > 0 ? robot.resId : 18;
            defense.add(dump.zbzDefenseWj(res, level, star, pin, foeRoundState(rec, 1)));
        }
        return defense;
    }

    private static int foeRoundState(PlayerRecord rec, int round1Based) {
        if (rec.zbz.matchFoeDeadRounds != null
                && rec.zbz.matchFoeDeadRounds.contains(Integer.valueOf(round1Based))) {
            return 0;
        }
        return 1;
    }

    private List<RankEntry> buildScoreBoard(PlayerRecord rec) {
        ensureParticipatingPool(rec);
        List<RankEntry> entries = new ArrayList<>();
        entries.add(new RankEntry(rec.playerId, rec.roleName, rec.mainHeroIndex, rec.level,
                rec.zbz.jiFen, rec.zbz.xingJi));
        for (PlayerRecord.ZbzPoolRobot pr : rec.zbz.poolRobots) {
            RankEntry e = rankEntryForPoolGuid(pr.guid, pr.jiFen, pr.xingJi);
            if (e != null) {
                entries.add(e);
            }
        }
        entries.sort(Comparator.comparingInt((RankEntry e) -> e.jiFen).reversed()
                .thenComparingInt(e -> e.guid));
        return entries;
    }

    /**
     * 产品：每日随机抽 {@link #POOL_MIN}–{@link #POOL_MAX} 个 JJC_Robot 入参战池。
     * 建池后互打 {@link #POOL_SEED_MATCHES} 场，用同一套 ΔJiFen 落盘（非假展示分）。
     */
    private void ensureParticipatingPool(PlayerRecord rec) {
        String today = GameTime.today().toString();
        if (today.equals(rec.zbz.poolDay)
                && rec.zbz.poolRobots != null
                && rec.zbz.poolRobots.size() >= POOL_MIN
                && rec.zbz.poolRobots.size() <= POOL_MAX) {
            syncPoolGuids(rec);
            return;
        }
        // 旧档只有 guids：补成 poolRobots 并播种
        if (today.equals(rec.zbz.poolDay)
                && (rec.zbz.poolRobots == null || rec.zbz.poolRobots.isEmpty())
                && rec.zbz.poolRobotGuids != null
                && rec.zbz.poolRobotGuids.size() >= POOL_MIN) {
            rec.zbz.poolRobots = new ArrayList<>();
            for (Integer g : rec.zbz.poolRobotGuids) {
                if (g == null) {
                    continue;
                }
                PlayerRecord.ZbzPoolRobot pr = new PlayerRecord.ZbzPoolRobot();
                pr.guid = g.intValue();
                rec.zbz.poolRobots.add(pr);
            }
            seedPoolScores(rec);
            store.save(rec);
            log.info("{} zbz pool migrate+seed day={} size={}", rec.account, today, rec.zbz.poolRobots.size());
            return;
        }
        List<PlayerRecord> npcPool = listZbzNpcCandidates();
        int available = npcPool.size();
        if (available < POOL_MIN) {
            List<GameTables.RobotRow> all = tables.robots();
            available = all.size();
            if (available <= 0) {
                rec.zbz.poolDay = today;
                rec.zbz.poolRobotGuids = new ArrayList<>();
                rec.zbz.poolRobots = new ArrayList<>();
                return;
            }
            int want = Math.min(available, ThreadLocalRandom.current().nextInt(POOL_MIN, POOL_MAX + 1));
            List<Integer> idxs = new ArrayList<>(available);
            for (int i = 0; i < available; i++) {
                idxs.add(Integer.valueOf(i));
            }
            Collections.shuffle(idxs, ThreadLocalRandom.current());
            rec.zbz.poolRobots = new ArrayList<>(want);
            for (int i = 0; i < want; i++) {
                PlayerRecord.ZbzPoolRobot pr = new PlayerRecord.ZbzPoolRobot();
                pr.guid = all.get(idxs.get(i)).targetGuid;
                rec.zbz.poolRobots.add(pr);
            }
            rec.zbz.poolDay = today;
            syncPoolGuids(rec);
            seedPoolScores(rec);
            store.save(rec);
            log.info("{} zbz pool day={} size={} seeded (robots fallback)", rec.account, today,
                    rec.zbz.poolRobots.size());
            return;
        }
        int want = Math.min(available, ThreadLocalRandom.current().nextInt(POOL_MIN, POOL_MAX + 1));
        List<PlayerRecord> shuffled = new ArrayList<>(npcPool);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        rec.zbz.poolRobots = new ArrayList<>(want);
        for (int i = 0; i < want; i++) {
            PlayerRecord.ZbzPoolRobot pr = new PlayerRecord.ZbzPoolRobot();
            pr.guid = shuffled.get(i).playerId;
            rec.zbz.poolRobots.add(pr);
        }
        rec.zbz.poolDay = today;
        syncPoolGuids(rec);
        seedPoolScores(rec);
        store.save(rec);
        log.info("{} zbz pool day={} size={} seeded", rec.account, today, rec.zbz.poolRobots.size());
    }

    private List<PlayerRecord> listZbzNpcCandidates() {
        int open = Math.max(1, tables.zbzCfg().openLevel);
        List<PlayerRecord> out = new ArrayList<>();
        for (PlayerRecord p : store.all()) {
            if (p == null || !p.npcPassive || p.playerId <= 1_000_000 || p.level < open) {
                continue;
            }
            p.ensureCollections();
            out.add(p);
        }
        return out;
    }

    private FightSyncService.RobotMatchResult simulatePoolPair(int guidA, int guidB) {
        PlayerRecord pa = store.findByPlayerId(guidA);
        PlayerRecord pb = store.findByPlayerId(guidB);
        if (pa != null && pb != null && pa.npcPassive && pb.npcPassive) {
            return fightSync.simulatePlayerFormationVsPlayer(
                    pa, PlayerRecord.FORMATION_JJC_DEF, pb, PlayerRecord.FORMATION_JJC_DEF);
        }
        return fightSync.simulateRobotVsRobot(guidA, guidB);
    }

    private RankEntry rankEntryForPoolGuid(int guid, int jiFen, int xingJi) {
        PlayerRecord npc = store.findByPlayerId(guid);
        if (npc != null && npc.npcPassive) {
            int res = npc.mainHeroIndex > 0 ? npc.mainHeroIndex : 18;
            return new RankEntry(npc.playerId, npc.roleName, res, npc.level, jiFen, xingJi);
        }
        GameTables.RobotRow r = tables.robotByGuid(guid);
        if (r == null) {
            return null;
        }
        return new RankEntry(r.targetGuid, r.name, r.resId, r.level, jiFen, xingJi);
    }

    private MatchCand matchCandForPoolGuid(PlayerRecord.ZbzPoolRobot pr) {
        if (pr == null) {
            return null;
        }
        PlayerRecord npc = store.findByPlayerId(pr.guid);
        if (npc != null && npc.npcPassive) {
            int res = npc.mainHeroIndex > 0 ? npc.mainHeroIndex : 18;
            return new MatchCand(npc.playerId, npc.roleName, res, npc.level, pr.jiFen,
                    pr.lastKillByPlayer, pr.lastKillByRobot, pr.lastBFight, pr.lastWin);
        }
        GameTables.RobotRow r = tables.robotByGuid(pr.guid);
        if (r == null) {
            return null;
        }
        return new MatchCand(r.targetGuid, r.name, r.resId, r.level, pr.jiFen,
                pr.lastKillByPlayer, pr.lastKillByRobot, pr.lastBFight, pr.lastWin);
    }

    private List<byte[]> buildFoeDefenseWjs(int targetId, int fallbackLevel, PlayerRecord rec) {
        PlayerRecord foe = store.findByPlayerId(targetId);
        if (foe != null && foe.npcPassive) {
            return buildPlayerDefenseWjs(foe, fallbackLevel, rec);
        }
        return buildRobotDefenseWjs(tables.robotByGuid(targetId), fallbackLevel, rec);
    }

    private List<byte[]> buildPlayerDefenseWjs(PlayerRecord foe, int fallbackLevel, PlayerRecord rec) {
        List<byte[]> defense = new ArrayList<>();
        if (foe == null) {
            return defense;
        }
        foe.ensureCollections();
        int roundIdx = 0;
        for (String id : foe.zbz.defenseWuJiangIds) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            PlayerRecord.Hero h = foe.findHero(id);
            if (h == null) {
                continue;
            }
            roundIdx++;
            int state = foeRoundState(rec, roundIdx);
            defense.add(dump.zbzDefenseWj(h.heroIndex, h.level, Math.max(1, h.stars),
                    Math.max(1, h.stage), state));
        }
        if (defense.isEmpty() && foe.mainHeroIndex > 0) {
            defense.add(dump.zbzDefenseWj(foe.mainHeroIndex, fallbackLevel, 1, 1,
                    foeRoundState(rec, 1)));
        }
        return defense;
    }

    private static void syncPoolGuids(PlayerRecord rec) {
        List<Integer> guids = new ArrayList<>();
        for (PlayerRecord.ZbzPoolRobot pr : rec.zbz.poolRobots) {
            guids.add(Integer.valueOf(pr.guid));
        }
        rec.zbz.poolRobotGuids = guids;
    }

    /** 池内机器人互打播种真实积分。 */
    private void seedPoolScores(PlayerRecord rec) {
        List<PlayerRecord.ZbzPoolRobot> list = rec.zbz.poolRobots;
        if (list == null || list.size() < 2) {
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (PlayerRecord.ZbzPoolRobot self : list) {
            for (int m = 0; m < POOL_SEED_MATCHES; m++) {
                PlayerRecord.ZbzPoolRobot foe = list.get(rnd.nextInt(list.size()));
                if (foe.guid == self.guid) {
                    continue;
                }
                FightSyncService.RobotMatchResult sim = simulatePoolPair(self.guid, foe.guid);
                applySimScoreToPoolRobots(rec, sim);
            }
        }
    }

    /** 玩家打完池内机器人：机器人按相反胜负用同一公式加减分；排位条击杀落盘。 */
    private void applyPlayerVsPoolRobotScore(PlayerRecord rec, int robotGuid, boolean playerWin,
                                            int playerKill, int robotFp, int playerDead, int playerFp) {
        PlayerRecord.ZbzPoolRobot pr = findPoolRobot(rec, robotGuid);
        if (pr == null) {
            return;
        }
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        int myFp = Math.max(1, playerFp);
        int foeFp = Math.max(1, robotFp);
        if (playerWin) {
            int d = calcJiFenDelta(cfg, pr.xingJi, false, Math.max(0, playerDead), myFp);
            pr.jiFen = Math.max(0, pr.jiFen + d);
            pr.lastKillByPlayer = Math.max(1, playerKill);
            pr.lastKillByRobot = Math.max(0, playerDead);
        } else {
            int robotKills = Math.max(1, playerDead);
            int d = calcJiFenDelta(cfg, pr.xingJi, true, robotKills, myFp);
            pr.jiFen = Math.max(0, pr.jiFen + d);
            pr.lastKillByPlayer = Math.max(0, playerKill);
            pr.lastKillByRobot = robotKills;
        }
        PlayerRecord.ZbzBracketPlayer bp = findBracket(rec, robotGuid);
        if (bp != null) {
            bp.jiFen = pr.jiFen;
        }
    }

    private void applySimScoreToPoolRobots(PlayerRecord rec, FightSyncService.RobotMatchResult sim) {
        if (sim == null) {
            return;
        }
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        PlayerRecord.ZbzPoolRobot winP = findPoolRobot(rec, sim.winnerGuid);
        PlayerRecord.ZbzPoolRobot loseP = findPoolRobot(rec, sim.loserGuid);
        if (winP != null) {
            int d = calcJiFenDelta(cfg, winP.xingJi, true, sim.killNum, sim.loserFp);
            winP.jiFen = Math.max(0, winP.jiFen + d);
            PlayerRecord.ZbzBracketPlayer bp = findBracket(rec, winP.guid);
            if (bp != null) {
                bp.jiFen = winP.jiFen;
            }
        }
        if (loseP != null) {
            int d = calcJiFenDelta(cfg, loseP.xingJi, false, sim.loserKillNum, sim.winnerFp);
            loseP.jiFen = Math.max(0, loseP.jiFen + d);
            PlayerRecord.ZbzBracketPlayer bp = findBracket(rec, loseP.guid);
            if (bp != null) {
                bp.jiFen = loseP.jiFen;
            }
        }
    }

    private PlayerRecord.ZbzPoolRobot findPoolRobot(PlayerRecord rec, int guid) {
        if (rec.zbz.poolRobots == null) {
            return null;
        }
        for (PlayerRecord.ZbzPoolRobot pr : rec.zbz.poolRobots) {
            if (pr.guid == guid) {
                return pr;
            }
        }
        return null;
    }

    /** 当前账号在积分榜上的名次（1-based）。供排名邮使用。 */
    public int resolveMyRank(PlayerRecord rec) {
        List<RankEntry> entries = buildScoreBoard(rec);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).guid == rec.playerId) {
                return i + 1;
            }
        }
        return Math.max(1, entries.size());
    }

    private boolean bracketFinished(PlayerRecord rec) {
        if (rec.zbz.bracket == null || rec.zbz.bracket.isEmpty()) {
            return false;
        }
        int state = rec.zbz.curState;
        // APK：State=2 时 Top8.Refresh 对入围者连发 4108；下注预览窗绝不可判完赛（否则提前名次奖/forceFinish）
        if (state == 2) {
            return false;
        }
        // 8进4 / 4进2 进行中：不允许靠「残存人数」提前完赛
        if (state == 3 || state == 4) {
            return false;
        }
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        LocalTime now = GameTime.localTime();
        int min = now.getHour() * 60 + now.getMinute();
        // 已离开淘汰钟点（决赛结束之后，或深夜～下注前）
        if (min >= cfg.finalEndMin || (computeCurState() == 1 && min < cfg.yaZhuMin)) {
            return true;
        }
        // 决赛窗内：只剩一人未淘汰
        if (state >= 5) {
            int alive = 0;
            for (PlayerRecord.ZbzBracketPlayer bp : rec.zbz.bracket) {
                if (bp.win != 2) {
                    alive++;
                }
            }
            return alive <= 1;
        }
        return false;
    }

    private float resolveGambleRate(PlayerRecord rec, int targetId) {
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        int idx = 0;
        if (rec.zbz.bracket != null && !rec.zbz.bracket.isEmpty()) {
            List<PlayerRecord.ZbzBracketPlayer> sorted = new ArrayList<>(rec.zbz.bracket);
            sorted.sort(Comparator.comparingInt((PlayerRecord.ZbzBracketPlayer b) -> b.jiFen).reversed());
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i).playerId == targetId) {
                    idx = i;
                    break;
                }
            }
        }
        float lo = cfg.gambleRateLo[Math.min(idx, cfg.gambleRateLo.length - 1)];
        float hi = cfg.gambleRateHi[Math.min(idx, cfg.gambleRateHi.length - 1)];
        if (hi < lo) {
            float t = lo;
            lo = hi;
            hi = t;
        }
        // 表给区间；服端在 [lo,hi] 取一次（无客户端算法）
        if (hi <= lo) {
            return lo;
        }
        return lo + ThreadLocalRandom.current().nextFloat() * (hi - lo);
    }

    private static int resolveGambleType(PlayerRecord rec) {
        if (rec.zbz.gambleGoldPaid && rec.zbz.gambleDiamondPaid) {
            return 3;
        }
        if (rec.zbz.gambleGoldPaid) {
            return 1;
        }
        if (rec.zbz.gambleDiamondPaid) {
            return 2;
        }
        return 0;
    }

    /** 押中冠军赔付：返回 [goldAdd, diamondAdd]；调用方已用 {@link #isChampion} 判定。 */
    private int[] payoutGamble(GameSession session, GamePacket pkt, PlayerRecord rec) {
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        float rate = rec.zbz.gambleRate > 0f ? rec.zbz.gambleRate : cfg.gambleRateLo[0];
        int goldAdd = 0;
        int diamondAdd = 0;
        if (rec.zbz.gambleGoldPaid) {
            goldAdd = (int) (cfg.gambleGold * rate);
            rec.gold += goldAdd;
            progress.pushGold(session, pkt, rec);
        }
        if (rec.zbz.gambleDiamondPaid) {
            diamondAdd = (int) (cfg.gambleDiamond * rate);
            rec.diamond += diamondAdd;
            progress.pushDiamond(session, pkt, rec);
        }
        return new int[]{goldAdd, diamondAdd};
    }

    private int placeFromBracket(PlayerRecord rec, PlayerRecord.ZbzBracketPlayer self) {
        if (self.win == 2) {
            return self.jueZhanRank <= 2 ? 2 : (self.jueZhanRank <= 4 ? 4 : 8);
        }
        // 未淘汰：按当前 jueZhanRank 估名次档
        if (self.jueZhanRank <= 1) {
            return 1;
        }
        if (self.jueZhanRank <= 2) {
            return 2;
        }
        if (self.jueZhanRank <= 4) {
            return 4;
        }
        return 8;
    }

    private GameTables.ZbzPrize placePrize(int place) {
        GameTables.ZbzCfg cfg = tables.zbzCfg();
        if (place <= 1) {
            return cfg.placePrize[3];
        }
        if (place == 2) {
            return cfg.placePrize[2];
        }
        if (place <= 4) {
            return cfg.placePrize[1];
        }
        return cfg.placePrize[0];
    }

    private boolean isChampion(PlayerRecord rec, int playerId) {
        PlayerRecord.ZbzBracketPlayer bp = findBracket(rec, playerId);
        return bp != null && bp.win != 2 && bp.jueZhanRank <= 1;
    }

    private void grantTablePrize(GameSession session, GamePacket pkt, PlayerRecord rec, GameTables.ZbzPrize prize) {
        if (prize == null) {
            return;
        }
        if (prize.gold > 0) {
            rec.gold += prize.gold;
            progress.pushGold(session, pkt, rec);
        }
        if (prize.diamond > 0) {
            rec.diamond += prize.diamond;
            progress.pushDiamond(session, pkt, rec);
        }
        if (prize.yingPo > 0) {
            progress.addYingPo(rec, prize.yingPo);
            progress.pushYingPo(session, pkt, rec);
        }
        if (prize.wnsp > 0) {
            progress.addWnsp(rec, prize.wnsp);
            progress.pushWnsp(session, pkt, rec);
        }
        if (prize.goodsOri != null && !prize.goodsOri.isEmpty() && prize.goodsCount > 0) {
            progress.addGoods(rec, prize.goodsOri, prize.goodsCount);
            Map<String, Integer> bag = new HashMap<>();
            progress.markGoods(bag, prize.goodsOri);
            progress.pushGoods(session, pkt, rec, bag);
        }
    }

    private PlayerRecord.ZbzBracketPlayer findBracket(PlayerRecord rec, int playerId) {
        if (rec.zbz.bracket == null) {
            return null;
        }
        for (PlayerRecord.ZbzBracketPlayer bp : rec.zbz.bracket) {
            if (bp.playerId == playerId) {
                return bp;
            }
        }
        return null;
    }

    private byte[] buildRankList(PlayerRecord rec) {
        List<RankEntry> entries = buildScoreBoard(rec);
        int myRank = resolveMyRank(rec);
        final int myRankFinal = myRank;
        return Pb.write(out -> {
            Pb.int32Always(out, 1, myRankFinal);
            int limit = Math.min(RANK_LIST_SIZE, entries.size());
            for (int i = 0; i < limit; i++) {
                RankEntry e = entries.get(i);
                final int rank = i + 1;
                Pb.bytes(out, 2, Pb.write(item -> {
                    Pb.int32(item, 1, rank);
                    Pb.int32(item, 2, e.guid);
                    Pb.string(item, 3, e.name);
                    Pb.int32(item, 4, e.resId);
                    Pb.int32(item, 5, e.level);
                    Pb.int32(item, 6, e.xingJi);
                    Pb.int32(item, 7, e.jiFen);
                }));
            }
        });
    }

    private List<byte[]> buildAttackerDefenseWjs(PlayerRecord rec) {
        // Top8 FillMyInfo：AttackerDefense[i] = 第 i+1 轮己方出战将；下标 < TargetcurRound 打叉
        List<byte[]> out = new ArrayList<>();
        if (rec.zbz.hadUsedWuJiangIds != null) {
            for (String id : rec.zbz.hadUsedWuJiangIds) {
                if (id == null || id.isEmpty()) {
                    continue;
                }
                PlayerRecord.Hero h = rec.findHero(id);
                if (h == null) {
                    continue;
                }
                out.add(dump.zbzDefenseWj(h.heroIndex, h.level, Math.max(1, h.stars),
                        Math.max(1, h.stage), 0));
            }
        }
        // 当前轮出战（type31 仅主将）
        for (String id : rec.formationSlots(PlayerRecord.FORMATION_ZBZ)) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (rec.zbz.hadUsedWuJiangIds != null && rec.zbz.hadUsedWuJiangIds.contains(id)) {
                continue;
            }
            PlayerRecord.Hero h = rec.findHero(id);
            if (h == null) {
                continue;
            }
            out.add(dump.zbzDefenseWj(h.heroIndex, h.level, Math.max(1, h.stars),
                    Math.max(1, h.stage), 1));
            break;
        }
        return out;
    }

    private int pickRobotGuid(PlayerRecord rec, int index1Based) {
        ensureParticipatingPool(rec);
        List<Integer> pool = rec.zbz.poolRobotGuids;
        if (pool != null && !pool.isEmpty()) {
            int i = Math.max(0, Math.min(index1Based - 1, pool.size() - 1));
            return pool.get(i).intValue();
        }
        List<GameTables.RobotRow> robots = tables.robots();
        if (robots.isEmpty()) {
            return 1;
        }
        int i = Math.max(0, Math.min(index1Based - 1, robots.size() - 1));
        return robots.get(i).targetGuid;
    }

    private static final class MatchCand {
        final int guid;
        final String name;
        final int resId;
        final int level;
        final int jiFen;
        final int killByPlayer;
        final int killByRobot;
        final boolean bFight;
        final int win;

        MatchCand(int guid, String name, int resId, int level, int jiFen,
                  int killByPlayer, int killByRobot, boolean bFight, int win) {
            this.guid = guid;
            this.name = name == null ? "" : name;
            this.resId = resId;
            this.level = level;
            this.jiFen = jiFen;
            this.killByPlayer = killByPlayer;
            this.killByRobot = killByRobot;
            this.bFight = bFight;
            this.win = win;
        }
    }

    private static final class RankEntry {
        final int guid;
        final String name;
        final int resId;
        final int level;
        final int jiFen;
        final int xingJi;

        RankEntry(int guid, String name, int resId, int level, int jiFen, int xingJi) {
            this.guid = guid;
            this.name = name == null ? "" : name;
            this.resId = resId;
            this.level = level;
            this.jiFen = jiFen;
            this.xingJi = xingJi;
        }
    }
}
