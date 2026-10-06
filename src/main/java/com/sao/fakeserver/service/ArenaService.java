package com.sao.fakeserver.service;

import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.store.WorldStore;
import com.sao.fakeserver.fight.FightRosterBuilder;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.DailyActivityTables;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.table.FightConfigTables;
import com.sao.fakeserver.table.GameTables;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ArenaService {
    private static final Logger log = LoggerFactory.getLogger(ArenaService.class);
    /** 与 ArenaMainDialog / IsCanTiaoZhan 一致：600s CD。 */
    private static final int JJC_CD_SECONDS = 600;
    /** 与 ArenaMainDialog.m_ᜀ(=10) 一致：清 CD 价按剩余整分钟查表。 */
    private static final int JJC_CD_MAX_MINUTES = 10;
    /** ArenaMainDialog 买次硬编码 150 钻（无独立表）。 */
    private static final int JJC_BUY_TIMES_DIAMOND = 150;

    private final SaoProperties props;
    private final PlayerStore players;
    private final WorldStore world;
    private final PlayerDumpService dump;
    private final GameTables tables;
    private final CultivateTables cultivate;
    private final DailyActivityTables activity;
    private final EconomyTables economy;
    private final ProgressService progress;
    private final TaskService task;
    private final FightConfigTables fightCfg;
    private final FightSyncService fightSync;

    public ArenaService(SaoProperties props, PlayerStore players, WorldStore world, PlayerDumpService dump,
                        GameTables tables, CultivateTables cultivate, DailyActivityTables activity,
                        EconomyTables economy, ProgressService progress, TaskService task,
                        FightConfigTables fightCfg, FightSyncService fightSync) {
        this.props = props;
        this.players = players;
        this.world = world;
        this.dump = dump;
        this.tables = tables;
        this.cultivate = cultivate;
        this.activity = activity;
        this.economy = economy;
        this.progress = progress;
        this.task = task;
        this.fightCfg = fightCfg;
        this.fightSync = fightSync;
    }

    public void onInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensureSlot(rec);
        refreshDaily(rec);
        resetWeeklyIfDue();
        players.save(rec);
        session.send(MsgIds.S2C_JJC_INFO_RET, pkt, dump.jjcInfo(rec, fightPower(rec), pickTargets(rec)));
    }

    public void onRefresh(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        session.send(MsgIds.S2C_JJC_REFRESH_RET, pkt, dump.jjcTargetsWrap(pickTargets(rec)));
    }

    public void onTargetDetail(GameSession session, GamePacket pkt) {
        int guid = Pb.read(pkt.body).getInt(2, Pb.read(pkt.body).getInt(1, 1));
        session.send(MsgIds.S2C_JJC_TARGET_DETAIL_RET, pkt, dump.jjcTargetGuidOnly(guid));
    }

    /** @return true=已发 2004+107；false=拒打（Dispatcher 勿 beginBattle） */
    public boolean onFight(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return false;
        }
        refreshDaily(rec);
        if (rec.arena.challengesLeft <= 0) {
            log.info("{} jjc fight refuse: no times left", rec.account);
            return false;
        }
        if (inChallengeCd(rec)) {
            log.info("{} jjc fight refuse: still in cd", rec.account);
            return false;
        }
        if (rec.arena.rank == 1) {
            log.info("{} jjc fight refuse: rank1 cannot challenge", rec.account);
            return false;
        }
        Pb.Fields f = Pb.read(pkt.body);
        // CCMsgJJCRankTarget: 1=RankIndex 2=TargetGuid
        int targetGuid = f.getInt(2, 0);
        if (targetGuid <= 0) {
            log.warn("{} jjc fight missing TargetGuid", rec.account);
            return false;
        }
        if (targetGuid > 1000000) {
            // 真人 PVP 暂缓：战斗同机器人，差在 otherTeam 须全量写出防守阵（见 PROTOCOL_FIELD_AUDIT B2）
            log.info("{} jjc refuse player target {} (pvp deferred)", rec.account, targetGuid);
            return false;
        }
        rec.arena.lastTargetGuid = targetGuid;
        // RegionList.txt：场景ID 2 = 竞技场 (type=3 → REGION_TYPE.PVP1)
        rec.currentRegionId = 2;
        players.save(rec);

        // CCMsgJJCRealFightTeamsInfo：机器人只需 otherTeam.TargetGuid，客户端本地查 JJC_Robot
        // myTeam.TargetGuid 必须等于自己 playerId，否则 UnPackDataForMyTeam 直接 return；
        // 且必须带 Buddies/JiBan/FightPowerReturn，否则开战覆盖登录羁绊为空。
        byte[] myTeam = dump.jjcFightTargetDetailSelf(rec);
        byte[] other = dump.jjcFightTargetDetail(targetGuid);
        session.send(MsgIds.S2C_JJC_FIGHT_TEAMS, pkt, dump.jjcFightTeams(myTeam, other));
        session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(2));
        log.info("{} jjc fight target={} → region 2", rec.account, targetGuid);
        return true;
    }

    public void onResult(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields result = Pb.read(pkt.body);
        boolean win = result.getBool(1);
        // CCMsgFightJJCResult: 1=IsWin 2=SelfHurt[] 3=TargetHurt[] —— 客户端本地累计 mAllAttackDamage
        List<Integer> selfHurt = result.getInts(2);
        List<Integer> targetHurt = result.getInts(3);
        log.info("{} jjc result-client-dmg selfHurt={} targetHurt={}", rec.account, selfHurt, targetHurt);
        fightSync.logSyncStats(session);
        int oldRank = rec.arena.rank;
        rec.arena.lastChallengeAt = PlayerDumpService.now();
        rec.arena.challengesLeft = Math.max(0, rec.arena.challengesLeft - 1);
        WorldStore.JjcSlot self = findByAccount(rec.account);
        WorldStore.JjcSlot other = findOrCreateTarget(rec.arena.lastTargetGuid);
        if (win) {
            rec.arena.wins++;
            if (self != null) {
                self.wins = rec.arena.wins;
            }
            if (self != null && other != null && other.rank < self.rank) {
                int tmp = self.rank;
                self.rank = other.rank;
                other.rank = tmp;
                rec.arena.rank = self.rank;
                if (other.account != null && !other.account.isEmpty()) {
                    PlayerRecord op = players.get(other.account);
                    if (op != null) {
                        op.arena.rank = other.rank;
                        players.save(op);
                    }
                }
                sortRanks();
            }
        }
        appendFightRecord(rec, other, win, oldRank - rec.arena.rank, selfHurt, targetHurt);
        players.save(rec);
        world.saveJjc();
        session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(win ? 1 : 2, win ? 3 : 0));
        // 回推 CD / 剩余次数，避免回大厅再开 JJC 才刷新
        session.send(MsgIds.S2C_JJC_LAST_CHALLENGE, pkt, dump.jjcLastChallenge(rec.arena.lastChallengeAt));
        session.send(MsgIds.S2C_JJC_RESET_TIMES, pkt, dump.jjcTimes(rec.arena.challengesLeft, rec.arena.payResetCount));
        if (win) {
            task.onDailyAction(session, pkt, rec, TaskService.DAILY_JJC, 1);
        }
        log.info("{} jjc result win={} rank={}", rec.account, win, rec.arena.rank);
    }

    public void onRank(GameSession session, GamePacket pkt) {
        List<WorldStore.JjcSlot> top = rankBoard();
        if (top.size() > 50) {
            top = top.subList(0, 50);
        }
        session.send(MsgIds.S2C_JJC_RANK_RET, pkt, dump.jjcRankList(top));
    }

    /** C2S 1709 → S2C 2010：排行点人详情；机器人需带 RobotWJFightPower[5]。 */
    public void onRankDetail(GameSession session, GamePacket pkt) {
        Pb.Fields f = Pb.read(pkt.body);
        int guid = f.getInt(2, f.getInt(1, 0));
        WorldStore.JjcSlot slot = findByGuid(guid);
        if (slot == null) {
            GameTables.RobotRow r = tables.robotByGuid(guid);
            if (r != null) {
                slot = slotFromRobot(r);
            }
        }
        int fp = slot != null ? Math.max(1, slot.fightPower) : 1000;
        int level = slot != null ? slot.level : 1;
        int rank = slot != null ? slot.rank : 0;
        int wins = slot != null ? slot.wins : 0;
        session.send(MsgIds.S2C_JJC_RANK_DETAIL_RET, pkt, dump.jjcRankDetail(guid, fp, level, rank, wins));
    }

    /** C2S 1710 → S2C 2011：空列表也会开战报 UI。 */
    public void onFightRecord(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensureRecords(rec);
        session.send(MsgIds.S2C_JJC_FIGHT_RECORD_RET, pkt, dump.jjcFightRecordList(rec.arena.records));
    }

    /** C2S 1711 → S2C 2012：机器人 TargetGuid；Hurt + 己方 WJ brief 供伤害条。 */
    public void onFightRecordDetail(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensureRecords(rec);
        int idx = Math.max(1, Pb.read(pkt.body).getInt(1, 1)) - 1;
        PlayerRecord.JjcFightRecord row = null;
        if (idx >= 0 && idx < rec.arena.records.size()) {
            row = rec.arena.records.get(idx);
        }
        int guid = row != null ? row.targetGuid : rec.arena.lastTargetGuid;
        session.send(MsgIds.S2C_JJC_FIGHT_RECORD_DETAIL_RET, pkt, dump.jjcFightRecordDetail(rec, guid, row));
    }

    /** C2S 1712 → S2C 2013 */
    public void onMyRank(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensureSlot(rec);
        session.send(MsgIds.S2C_JJC_MY_RANK_RET, pkt, dump.jjcMyRank(rec.arena.rank));
    }

    // ------------------------------------- 战力排行榜 / 查看他人 / 切磋面板（C2S 2501-2505）

    /**
     * C2S 2501（空包）→ S2C 2901 战力榜。
     * 数据源复用 JJC 榜（玩家 + 机器人）。
     * <p>过滤 {@code targetGuid != robotTableIndex + 1} 的机器人行：客户端用 {@code GetRobotData(guid-1)}
     * 索引本地机器人表（{@code TargetWJDetailInfo.cs:475}、{@code ArenaPlayerDetailInfo.cs:79}），
     * 而 KFZ 合成机器人（{@code GameTables.java:440-441} targetGuid=900100+syn）不满足 1 基约定，
     * 下发后点开必空。</p>
     */
    public void onFightPowerRank(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        List<WorldStore.JjcSlot> rows = new ArrayList<>();
        int myRank = 0;
        for (WorldStore.JjcSlot s : rankBoard()) {
            if (s == null) {
                continue;
            }
            if ("robot".equals(s.kind) && s.targetGuid != s.robotTableIndex + 1) {
                continue;
            }
            rows.add(s);
            if (myRank == 0 && rec.playerId > 0 && s.targetGuid == rec.playerId) {
                myRank = rows.size();
            }
        }
        if (myRank == 0) {
            myRank = rows.size() + 1;
        }
        session.send(MsgIds.S2C_FIGHT_POWER_RANK_RET, pkt, dump.fightPowerRankList(myRank, rows));
        log.info("{} fight power rank: {} rows myRank={}", rec.account, Integer.valueOf(rows.size()),
                Integer.valueOf(myRank));
    }

    /**
     * C2S 2502 → S2C 2902。guid 在 <b>嵌套</b>字段里：{@code CCMsgRemotePlayerBreifInfo.1
     * (CCMsgFightPowerRankListItem).2 = guid}（{@code RankListMainDialog.cs:1346} 只填这一个子字段）。
     */
    public void onRemotePlayerBrief(GameSession session, GamePacket pkt) {
        Pb.Fields f = Pb.read(pkt.body);
        byte[] inner = f.getBytes(1);
        int guid;
        if (inner != null && inner.length > 0) {
            guid = Pb.read(inner).getInt(2, 0);
        } else {
            guid = f.getInt(2, f.getInt(1, 0));
        }
        sendRemoteBrief(session, pkt, guid);
    }

    /** C2S 2504（场景点人→信息）：1 dynID，假服里 dynID == playerId。 → S2C 2902 */
    public void onRemotePlayerBriefByClick(GameSession session, GamePacket pkt) {
        sendRemoteBrief(session, pkt, Pb.read(pkt.body).getInt(1, 0));
    }

    /**
     * C2S 2503 → S2C 2903 他人武将详情。1 playerGuid、2 detailInfo(CMsgWuJiang) 的 index。
     * 机器人分支客户端只用 playerGuid + index（本地造数据）；真人分支读 level/jieduan/stars/fightpower。
     */
    public void onRemotePlayerWjDetail(GameSession session, GamePacket pkt) {
        Pb.Fields f = Pb.read(pkt.body);
        int guid = f.getInt(1, 0);
        int wjIndex = 0;
        byte[] inner = f.getBytes(2);
        if (inner != null && inner.length > 0) {
            wjIndex = Pb.read(inner).getInt(2, 0);
        }
        if (wjIndex <= 0) {
            wjIndex = f.getInt(2, 0);
        }
        if (guid <= 0 || wjIndex <= 0) {
            log.info("arena remote wj detail missing guid={} index={}", Integer.valueOf(guid),
                    Integer.valueOf(wjIndex));
            return;
        }
        PlayerRecord target = players.findByPlayerId(guid);
        if (target != null) {
            PlayerRecord.Hero hero = null;
            for (PlayerRecord.Hero h : target.heroes) {
                if (h != null && h.heroIndex == wjIndex) {
                    hero = h;
                    break;
                }
            }
            int level = hero != null ? hero.level : Math.max(1, target.level);
            int jieduan = hero != null ? hero.stage : 0;
            int stars = hero != null ? hero.stars : 0;
            int fp = hero != null ? Math.max(1, cultivate.computeFightPower(target, hero)) : 1000;
            session.send(MsgIds.S2C_REMOTE_PLAYER_WJ_RET, pkt,
                    dump.remotePlayerWjDetail(guid, wjIndex, level, jieduan, stars, fp));
            return;
        }
        GameTables.RobotRow r = tables.robotByGuid(guid);
        int level = r != null ? Math.max(1, r.level) : 1;
        int jieduan = r != null ? r.stage : 0;
        int stars = r != null ? r.stars : 0;
        session.send(MsgIds.S2C_REMOTE_PLAYER_WJ_RET, pkt,
                dump.remotePlayerWjDetail(guid, wjIndex, level, jieduan, stars, 0));
    }

    /**
     * C2S 2505（场景点人→切磋）→ <b>S2C 2010</b>（不是 2505 的专属回包：客户端
     * {@code ArenaPlayerDetailInfo} 的唯一入口是 2010，见 {@code ᝁ.cs:4293}；
     * {@code RemotePlayerMenu.cs:97-98} 预先写 PlayerName/gResID 就是在等这个面板）。
     * 真正的开战仍在面板内按攻击/防御 → C2S 1704 + 1713。
     */
    public void onQieCuoInfoByClick(GameSession session, GamePacket pkt) {
        int dynId = Pb.read(pkt.body).getInt(1, 0);
        WorldStore.JjcSlot slot = findSlotAny(dynId);
        if (slot == null) {
            log.info("arena qiecuo panel: guid {} not found", Integer.valueOf(dynId));
            return;
        }
        session.send(MsgIds.S2C_JJC_RANK_DETAIL_RET, pkt,
                dump.jjcRankDetail(dynId, Math.max(1, slot.fightPower), Math.max(1, slot.level), slot.rank, slot.wins));
    }

    /** 2502/2504 共用出包。 */
    private void sendRemoteBrief(GameSession session, GamePacket pkt, int guid) {
        WorldStore.JjcSlot slot = findSlotAny(guid);
        if (slot == null) {
            log.info("arena remote brief: guid {} not found", Integer.valueOf(guid));
            return;
        }
        List<int[]> wjs = new ArrayList<>();
        PlayerRecord target = players.findByPlayerId(guid);
        if (target != null) {
            int n = 0;
            for (PlayerRecord.Hero h : target.heroes) {
                if (h == null || n >= 5) {
                    continue;
                }
                wjs.add(new int[]{h.heroIndex, h.stage, Math.max(1, h.level), h.stars});
                n++;
            }
        } else {
            GameTables.RobotRow r = tables.robotByGuid(guid);
            if (r != null) {
                for (int i = 0; i < r.wjIndex.length; i++) {
                    if (r.wjIndex[i] > 0) {
                        wjs.add(new int[]{r.wjIndex[i], r.stage, Math.max(1, r.level), r.stars});
                    }
                }
            }
        }
        session.send(MsgIds.S2C_REMOTE_PLAYER_BRIEF_RET, pkt,
                dump.remotePlayerBrief(slot, unionNameFor(guid), wjs));
    }

    /** JJC 榜 → 机器人表 → 真人档，依次解析。 */
    private WorldStore.JjcSlot findSlotAny(int guid) {
        if (guid <= 0) {
            return null;
        }
        WorldStore.JjcSlot slot = findByGuid(guid);
        if (slot != null) {
            return slot;
        }
        GameTables.RobotRow r = tables.robotByGuid(guid);
        if (r != null) {
            return slotFromRobot(r);
        }
        PlayerRecord p = players.findByPlayerId(guid);
        if (p == null) {
            return null;
        }
        WorldStore.JjcSlot s = new WorldStore.JjcSlot();
        s.kind = "player";
        s.targetGuid = p.playerId;
        s.account = p.account;
        s.name = p.roleName;
        s.level = Math.max(1, p.level);
        s.heroIndex = p.mainHeroIndex;
        s.fightPower = totalFightPower(p);
        return s;
    }

    /** 公会名：2902 field2。空串时客户端用码表 100368 占位（{@code TargetPlayerInfo.cs:90/96}）。 */
    public String unionNameFor(int playerId) {
        for (WorldStore.UnionRecord u : world.unions()) {
            if (u != null && u.findMember(playerId) != null) {
                return u.name == null ? "" : u.name;
            }
        }
        return "";
    }

    /** 全武将求和口径：与客户端「我的战力」标签一致（{@code RankListMainDialog.cs:511-515}）。 */
    private int totalFightPower(PlayerRecord rec) {
        int sum = 0;
        for (PlayerRecord.Hero wj : rec.heroes) {
            if (wj == null) {
                continue;
            }
            int fp = cultivate.computeFightPower(rec, wj);
            wj.fightPower = fp;
            sum += fp;
        }
        return Math.max(1000, sum);
    }

    /**
     * C2S 1713 → S2C 2014 + 2015 + 107(region=4)。
     * RegionList：id=4 type=13=PVP_QIE_CUO（同竞技场 Scene_arena_01，勿发 region=2）。
     * 机器人只要 TargetGuid；真人全量 WJ 暂缓。
     */
    public void onQieCuo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        refreshDaily(rec);
        Pb.Fields f = Pb.read(pkt.body);
        // CCMsgRequestJJCQieCuo: 1 targetGuid 2 offenceTeam
        int targetGuid = f.getInt(1, 0);
        if (rec.arena.qieCuoLeftTimes <= 0) {
            log.info("{} jjc qiecuo refuse: no times", rec.account);
            session.send(MsgIds.S2C_JJC_QIECUO_RET, pkt, dump.jjcQieCuoRet(1));
            return;
        }
        if (targetGuid <= 0) {
            log.warn("{} jjc qiecuo missing TargetGuid", rec.account);
            session.send(MsgIds.S2C_JJC_QIECUO_RET, pkt, dump.jjcQieCuoRet(1));
            return;
        }
        if (targetGuid > 1000000) {
            log.info("{} jjc qiecuo refuse player target {} (pvp deferred)", rec.account, targetGuid);
            session.send(MsgIds.S2C_JJC_QIECUO_RET, pkt, dump.jjcQieCuoRet(1));
            return;
        }
        rec.arena.lastTargetGuid = targetGuid;
        rec.currentRegionId = 4;
        players.save(rec);
        session.send(MsgIds.S2C_JJC_QIECUO_RET, pkt, dump.jjcQieCuoRet(0));
        session.send(MsgIds.S2C_JJC_QIECUO_FIGHT, pkt, dump.jjcFightTargetDetail(targetGuid));
        session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(4));
        log.info("{} jjc qiecuo target={} → region 4", rec.account, targetGuid);
    }

    /** C2S 1714：切磋结束 ack；扣次数并推 EAttriType=11。不改名次/CD。 */
    public void onQieCuoResult(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        refreshDaily(rec);
        rec.arena.qieCuoLeftTimes = Math.max(0, rec.arena.qieCuoLeftTimes - 1);
        players.save(rec);
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(11, rec.arena.qieCuoLeftTimes));
        log.info("{} jjc qiecuo result left={}", rec.account, rec.arena.qieCuoLeftTimes);
    }

    public void onClearCd(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        refreshDaily(rec);
        if (!inChallengeCd(rec)) {
            log.info("{} jjc clearCd ignored: not in cd", rec.account);
            session.send(MsgIds.S2C_JJC_LAST_CHALLENGE, pkt,
                    dump.jjcLastChallenge(rec.arena.lastChallengeAt));
            return;
        }
        int price = clearCdDiamondCost(rec);
        if (price > 0 && rec.diamond < price) {
            log.info("{} jjc clearCd refuse: need={} have={}", rec.account, price, rec.diamond);
            return;
        }
        if (price > 0) {
            rec.diamond -= price;
            progress.pushDiamond(session, pkt, rec);
            progress.addTodayCost(session, pkt, rec, price);
        }
        // 清 CD：存档清空；线上发远过去时间（空串会让客户端 ParseExact 炸）
        rec.arena.lastChallengeAt = "";
        players.save(rec);
        session.send(MsgIds.S2C_JJC_LAST_CHALLENGE, pkt,
                dump.jjcLastChallenge(PlayerDumpService.JJC_NEVER_CHALLENGED_AT));
        log.info("{} jjc clearCd cost={}", rec.account, price);
    }

    public void onResetTimes(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        refreshDaily(rec);
        if (rec.arena.challengesLeft > 0) {
            log.info("{} jjc buyTimes refuse: still have times={}", rec.account, rec.arena.challengesLeft);
            return;
        }
        int maxBuy = economy.jjcResetMaxCount(rec.economy.chargedDiamond);
        if (rec.arena.payResetCount >= maxBuy) {
            log.info("{} jjc buyTimes refuse: payReset={} vipMax={}",
                    rec.account, rec.arena.payResetCount, maxBuy);
            return;
        }
        if (rec.diamond < JJC_BUY_TIMES_DIAMOND) {
            log.info("{} jjc buyTimes refuse: need={} have={}",
                    rec.account, JJC_BUY_TIMES_DIAMOND, rec.diamond);
            return;
        }
        rec.diamond -= JJC_BUY_TIMES_DIAMOND;
        progress.pushDiamond(session, pkt, rec);
        progress.addTodayCost(session, pkt, rec, JJC_BUY_TIMES_DIAMOND);
        int daily = props.getJjcDailyTimes();
        if (daily <= 0) {
            daily = 5;
        }
        rec.arena.challengesLeft = daily;
        rec.arena.payResetCount++;
        players.save(rec);
        session.send(MsgIds.S2C_JJC_RESET_TIMES, pkt, dump.jjcTimes(rec.arena.challengesLeft, rec.arena.payResetCount));
        log.info("{} jjc buyTimes cost={} left={} payReset={}",
                rec.account, JJC_BUY_TIMES_DIAMOND, rec.arena.challengesLeft, rec.arena.payResetCount);
    }

    /** 是否仍在 600s 挑战 CD 内（无记录 / 哨兵时间 / 解析失败 → 不在 CD）。 */
    private boolean inChallengeCd(PlayerRecord rec) {
        String at = rec.arena.lastChallengeAt;
        if (at == null || at.isEmpty()) {
            return false;
        }
        try {
            LocalDateTime last = LocalDateTime.parse(at, PlayerDumpService.TIME);
            long sec = Duration.between(last, GameTime.now()).getSeconds();
            return sec >= 0 && sec < JJC_CD_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 对齐客户端：GetClearPrice((int)(10 - elapsedMinutes))。
     * 刚打完约 40 钻；剩余不足 1 分钟约 4 钻。
     */
    private int clearCdDiamondCost(PlayerRecord rec) {
        String at = rec.arena.lastChallengeAt;
        if (at == null || at.isEmpty()) {
            return 0;
        }
        try {
            LocalDateTime last = LocalDateTime.parse(at, PlayerDumpService.TIME);
            double elapsedMin = Duration.between(last, GameTime.now()).toMillis() / 60000.0;
            int remainMin = (int) (JJC_CD_MAX_MINUTES - elapsedMin);
            return tables.jjcClearCdPrice(remainMin);
        } catch (Exception e) {
            return 0;
        }
    }

    public void ensureSlot(PlayerRecord rec) {
        WorldStore.JjcSlot exist = findByAccount(rec.account);
        if (exist != null) {
            rec.arena.rank = exist.rank;
            exist.kind = "player";
            exist.level = rec.level;
            exist.name = rec.roleName;
            exist.heroIndex = rec.mainHeroIndex;
            exist.fightPower = fightPower(rec);
            exist.targetGuid = rec.playerId;
            exist.wins = rec.arena.wins;
            exist.account = rec.account;
            return;
        }
        int maxRank = tables.robots().size();
        for (WorldStore.JjcSlot s : world.jjc().ranks) {
            maxRank = Math.max(maxRank, s.rank);
        }
        WorldStore.JjcSlot slot = new WorldStore.JjcSlot();
        slot.kind = "player";
        slot.account = rec.account;
        slot.targetGuid = rec.playerId;
        slot.rank = maxRank + 1;
        slot.name = rec.roleName;
        slot.level = rec.level;
        slot.heroIndex = rec.mainHeroIndex;
        slot.fightPower = fightPower(rec);
        slot.wins = rec.arena.wins;
        rec.arena.rank = slot.rank;
        world.jjc().ranks.add(slot);
        world.saveJjc();
    }

    private void refreshDaily(PlayerRecord rec) {
        int daily = props.getJjcDailyTimes();
        if (daily <= 0) {
            daily = 5;
        }
        int qieDaily = props.getJjcQieCuoDailyTimes();
        if (qieDaily <= 0) {
            qieDaily = 10;
        }
        // 旧档可能写成 10；对齐 JJC_Common「每日免费挑战次数=5」
        if (rec.arena.challengesLeft > daily) {
            rec.arena.challengesLeft = daily;
        }
        if (rec.arena.qieCuoLeftTimes > qieDaily) {
            rec.arena.qieCuoLeftTimes = qieDaily;
        }
        String today = GameTime.today().toString();
        if (today.equals(rec.arena.lastTimesResetDay)) {
            return;
        }
        // 跨自然日：灌满免费次并清付费重置计数（与 lastChallengeAt / 清 CD 解耦）
        rec.arena.challengesLeft = daily;
        rec.arena.qieCuoLeftTimes = qieDaily;
        rec.arena.payResetCount = 0;
        rec.arena.lastTimesResetDay = today;
    }

    /**
     * 每周重置：玩家名次全部排到机器人之后，相对名次保留。先发排名邮件再调这个。
     */
    public synchronized boolean resetWeeklyIfDue() {
        DailyActivityTables.JjcWeeklyReset cfg = activity.current().jjcWeeklyReset;
        if (cfg == null || !cfg.enabled) {
            return false;
        }
        LocalDateTime now = GameTime.now();
        int wd = Math.max(1, Math.min(7, cfg.weekday));
        LocalDate resetDay = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.of(wd)));
        LocalDateTime resetAt = resetDay.atTime(LocalTime.of(
                Math.max(0, Math.min(23, cfg.hour)), Math.max(0, Math.min(59, cfg.minute))));
        if (now.isBefore(resetAt)) {
            resetDay = resetDay.minusWeeks(1);
            resetAt = resetDay.atTime(resetAt.toLocalTime());
        }
        if (now.isBefore(resetAt)) {
            return false;
        }
        String key = resetDay.toString();
        String last = world.jjc().lastWeeklyReset;
        if (key.equals(last)) {
            return false;
        }
        if (last == null || last.isEmpty()) {
            world.jjc().lastWeeklyReset = key;
            world.saveJjc();
            log.info("jjc weekly reset baseline {}", key);
            return false;
        }
        List<WorldStore.JjcSlot> keep = new ArrayList<>();
        for (WorldStore.JjcSlot s : world.jjc().ranks) {
            if (s != null && "player".equals(s.kind)) {
                keep.add(s);
            }
        }
        Collections.sort(keep, new Comparator<WorldStore.JjcSlot>() {
            @Override
            public int compare(WorldStore.JjcSlot a, WorldStore.JjcSlot b) {
                return Integer.compare(a.rank, b.rank);
            }
        });
        int base = tables.robots().size();
        world.jjc().ranks.clear();
        for (int i = 0; i < keep.size(); i++) {
            WorldStore.JjcSlot s = keep.get(i);
            s.rank = base + 1 + i;
            world.jjc().ranks.add(s);
            if (s.account != null && !s.account.isEmpty()) {
                PlayerRecord rec = players.get(s.account);
                if (rec != null) {
                    rec.arena.rank = s.rank;
                    players.save(rec);
                }
            }
        }
        world.jjc().lastWeeklyReset = key;
        world.saveJjc();
        log.info("jjc weekly reset day={} players={} firstRank={}", key, keep.size(), base + 1);
        return true;
    }

    /**
     * 从当前名次往前挑最多 3 个挑战目标（名次附近有谁就谁：机器人或玩家），
     * 窗口不超过 jjcChallengeMaxAbove。不强制凑满。
     */
    private List<WorldStore.JjcSlot> pickTargets(PlayerRecord rec) {
        java.util.Map<Integer, WorldStore.JjcSlot> board = occupancy();
        int me = rec.arena.rank;
        if (me <= 0) {
            me = tables.robots().size() + 1;
        }
        int span = activity.current().jjcChallengeMaxAbove;
        if (span <= 0) {
            span = 50;
        }
        List<Integer> want = new ArrayList<>();
        if (me <= 1) {
            want.add(Integer.valueOf(2));
            want.add(Integer.valueOf(3));
            want.add(Integer.valueOf(4));
        } else {
            want.add(Integer.valueOf(me - 1));
            want.add(Integer.valueOf(Math.max(1, me - Math.max(2, span / 2))));
            want.add(Integer.valueOf(Math.max(1, me - span)));
        }
        List<WorldStore.JjcSlot> picked = new ArrayList<>();
        Set<Integer> usedGuid = new HashSet<>();
        usedGuid.add(Integer.valueOf(rec.playerId));
        addRankTargets(board, want, picked, usedGuid);
        if (picked.size() < 3) {
            List<Integer> rest = new ArrayList<>();
            int from = me <= 1 ? 2 : me - 1;
            int to = me <= 1 ? Math.max(board.size(), tables.robots().size()) : Math.max(1, me - span);
            if (me <= 1) {
                for (int r = from; r <= to && rest.size() < 8; r++) {
                    rest.add(Integer.valueOf(r));
                }
            } else {
                for (int r = from; r >= to && rest.size() < 12; r--) {
                    rest.add(Integer.valueOf(r));
                }
            }
            addRankTargets(board, rest, picked, usedGuid);
        }
        return picked;
    }

    /** 按名次取占位者（机器人或玩家），跳过空位与已选 guid。 */
    private void addRankTargets(java.util.Map<Integer, WorldStore.JjcSlot> board, List<Integer> ranks,
                                List<WorldStore.JjcSlot> picked, Set<Integer> usedGuid) {
        for (Integer rank : ranks) {
            if (picked.size() >= 3) {
                return;
            }
            WorldStore.JjcSlot slot = board.get(rank);
            if (slot == null || slot.targetGuid <= 0
                    || usedGuid.contains(Integer.valueOf(slot.targetGuid))) {
                continue;
            }
            usedGuid.add(Integer.valueOf(slot.targetGuid));
            picked.add(slot);
        }
    }

    private java.util.Map<Integer, WorldStore.JjcSlot> occupancy() {
        java.util.Map<Integer, WorldStore.JjcSlot> map = new java.util.HashMap<>();
        for (GameTables.RobotRow r : tables.robots()) {
            map.put(Integer.valueOf(r.tableIndex + 1), slotFromRobot(r));
        }
        for (WorldStore.JjcSlot s : world.jjc().ranks) {
            if (s == null) {
                continue;
            }
            // 玩家占位直接覆盖；机器人只保留换位后的 rank，战力一律按表现算（避免 jjc.json 旧 600）
            if (isRobot(s)) {
                GameTables.RobotRow row = tables.robotByGuid(s.targetGuid);
                if (row != null) {
                    WorldStore.JjcSlot fresh = slotFromRobot(row);
                    fresh.rank = s.rank;
                    map.put(Integer.valueOf(s.rank), fresh);
                    continue;
                }
            }
            map.put(Integer.valueOf(s.rank), s);
        }
        return map;
    }

    private static boolean isRobot(WorldStore.JjcSlot slot) {
        if (slot == null) {
            return false;
        }
        if ("player".equals(slot.kind)) {
            return false;
        }
        return slot.targetGuid > 0 && slot.targetGuid <= 1000000;
    }

    private List<WorldStore.JjcSlot> rankBoard() {
        List<WorldStore.JjcSlot> board = new ArrayList<>(world.jjc().ranks);
        Set<Integer> seen = new HashSet<>();
        for (WorldStore.JjcSlot s : board) {
            seen.add(s.targetGuid);
        }
        int n = Math.min(50, tables.robots().size());
        for (int i = 0; i < n; i++) {
            GameTables.RobotRow r = tables.robots().get(i);
            if (seen.contains(r.targetGuid)) {
                continue;
            }
            board.add(slotFromRobot(r));
        }
        Collections.sort(board, new Comparator<WorldStore.JjcSlot>() {
            @Override
            public int compare(WorldStore.JjcSlot a, WorldStore.JjcSlot b) {
                return Integer.compare(a.rank, b.rank);
            }
        });
        return board;
    }

    private WorldStore.JjcSlot resolveRobot(GameTables.RobotRow r) {
        WorldStore.JjcSlot exist = findByGuid(r.targetGuid);
        return exist != null ? exist : slotFromRobot(r);
    }

    private void ensureRecords(PlayerRecord rec) {
        rec.ensureCollections();
        if (rec.arena.records == null) {
            rec.arena.records = new ArrayList<>();
        }
    }

    private void appendFightRecord(PlayerRecord rec, WorldStore.JjcSlot other, boolean win, int rankChange,
                                   List<Integer> selfHurt, List<Integer> targetHurt) {
        ensureRecords(rec);
        PlayerRecord.JjcFightRecord row = new PlayerRecord.JjcFightRecord();
        if (other != null) {
            row.targetGuid = other.targetGuid;
            row.resId = other.heroIndex;
            row.name = other.name != null ? other.name : "";
            row.level = other.level;
        } else {
            row.targetGuid = rec.arena.lastTargetGuid;
            row.name = "对手";
            row.level = 1;
        }
        row.win = win;
        row.rankChange = rankChange;
        row.fightTime = PlayerDumpService.now();
        if (selfHurt != null) {
            row.selfHurt = new ArrayList<>(selfHurt);
        }
        if (targetHurt != null) {
            row.targetHurt = new ArrayList<>(targetHurt);
        }
        rec.arena.records.add(0, row);
        while (rec.arena.records.size() > 10) {
            rec.arena.records.remove(rec.arena.records.size() - 1);
        }
    }

    private WorldStore.JjcSlot findOrCreateTarget(int guid) {
        WorldStore.JjcSlot exist = findByGuid(guid);
        if (exist != null) {
            return exist;
        }
        GameTables.RobotRow row = tables.robotByGuid(guid);
        if (row == null) {
            return null;
        }
        WorldStore.JjcSlot slot = slotFromRobot(row);
        world.jjc().ranks.add(slot);
        return slot;
    }

    private WorldStore.JjcSlot slotFromRobot(GameTables.RobotRow r) {
        WorldStore.JjcSlot slot = new WorldStore.JjcSlot();
        slot.kind = "robot";
        slot.targetGuid = r.targetGuid;
        slot.robotTableIndex = r.tableIndex;
        slot.rank = r.tableIndex + 1;
        slot.name = r.name;
        slot.level = r.level;
        slot.heroIndex = r.resId;
        slot.wins = r.victoryNum;
        // 必须与开战 FightRosterBuilder 同公式，禁止 level*120 假战力
        slot.fightPower = FightRosterBuilder.robotFightPower(r, cultivate, tables, fightCfg);
        return slot;
    }

    private void sortRanks() {
        List<WorldStore.JjcSlot> ranks = world.jjc().ranks;
        Collections.sort(ranks, new Comparator<WorldStore.JjcSlot>() {
            @Override
            public int compare(WorldStore.JjcSlot a, WorldStore.JjcSlot b) {
                return Integer.compare(a.rank, b.rank);
            }
        });
    }

    private WorldStore.JjcSlot findByAccount(String account) {
        for (WorldStore.JjcSlot s : world.jjc().ranks) {
            if (account != null && account.equals(s.account)) {
                return s;
            }
        }
        return null;
    }

    private WorldStore.JjcSlot findByGuid(int guid) {
        for (WorldStore.JjcSlot s : world.jjc().ranks) {
            if (s.targetGuid == guid) {
                return s;
            }
        }
        return null;
    }

    /** S2C 2001 field1 DefenseFightPower：优先 type1 防守阵，空则全武将和。 */
    private int fightPower(PlayerRecord rec) {
        int sum = 0;
        for (String id : rec.formationSlots(PlayerRecord.FORMATION_JJC_DEF)) {
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
}
