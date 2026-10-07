package com.sao.fakeserver.service;

import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.GameTables;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.table.LtsjCfg;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class DungeonService {
    private static final Logger log = LoggerFactory.getLogger(DungeonService.class);
    private final Random rng = new Random();

    /** 对齐客户端 REGION_TYPE：镜像/不可触/致命/圣诞/镰刀。 */
    public static final int RT_MIRROR = 5;
    public static final int RT_UNTOUCHABLE = 6;
    public static final int RT_DEADLY = 8;
    public static final int RT_SANTA = 9;
    public static final int RT_SCYTHE = 10;
    private static final int[] RESOURCE_REGION_TYPES = {
            RT_MIRROR, RT_UNTOUCHABLE, RT_DEADLY, RT_SANTA, RT_SCYTHE
    };

    private final SaoProperties props;
    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final GameTables tables;
    private final EconomyTables economy;
    private final CultivateTables cultivate;
    private final ProgressService progress;
    private final TaskService task;
    private final BobService bob;
    private final SessionHub sessions;
    /** 宝箱固定产出（ltsj.json 的 boxes[].items）：BX191-194 这类「一定获得」箱走这里。 */
    private final LtsjCfg ltsj;

    public DungeonService(SaoProperties props, PlayerStore store, PlayerDumpService dump,
                          GameTables tables, EconomyTables economy, CultivateTables cultivate,
                          ProgressService progress, TaskService task, BobService bob, SessionHub sessions,
                          LtsjCfg ltsj) {
        this.props = props;
        this.store = store;
        this.dump = dump;
        this.tables = tables;
        this.economy = economy;
        this.cultivate = cultivate;
        this.progress = progress;
        this.task = task;
        this.bob = bob;
        this.sessions = sessions;
        this.ltsj = ltsj;
    }

    /**
     * 主线（普通/精英）进本与扫荡的共用前置校验，逐条对齐客户端：
     * <ul>
     *   <li>region 必须是 {@code RegionList} 里的主线场景</li>
     *   <li>普通解锁 {@code ChapterGuanKaCommonInfo.GetPuTongLastOpenChapterGuanKaID()}（:67-93）</li>
     *   <li>精英解锁 {@code GetJinYingLastOpenChapterGuanKaID()}（:97-142）：总开关 = 普通进度 ≥
     *       {@code GlobalSetup_CH} token66（实表 3010），之后 3→6→9→10 递进</li>
     *   <li>受限关（章内 3/6/9/10）每日剩余次数 ≥ 1：客户端 {@code FBStarInfo.NeedCheckPlayTime}（:19）</li>
     *   <li>体力 ≥ {@code RegionList} col6/col7（普通 8 / 精英 16）：
     *       客户端 {@code NormalFBDescribeSystem.cs:785-797 CanPlayFB}</li>
     * </ul>
     * 客户端在发 C2S 301/310 之前已经把这些门都过了一遍，所以正常客户端不会撞到这里，
     * 这里拦的是改包/绕客户端。**不通过就静默不发包**（与 {@link #onBuyMainFbPlayTime} 的
     * {@code playLeft > 0} 分支同风格）；表没读到（{@link GameTables#mainFbGatesAvailable()} 为 false）
     * 时一律放行，避免一次读表失败把进本全砖掉。
     *
     * @return null = 允许；否则是不允许的原因（只用于日志）
     */
    private String mainFbRefuseReason(PlayerRecord rec, int region, int diff) {
        if (region <= 0) {
            return "region<=0";
        }
        if (!tables.mainFbGatesAvailable()) {
            return null;
        }
        if (tables.regionNormalEnergy(region) <= 0) {
            return "region " + region + " not in RegionList";
        }
        // 章节表（ChapterList.txt）没读到 ⇒ chapterCount()==0：跳过「可打到第几关」的推进校验
        // （否则停在章内第 10 关的账号会被卡在 1010，永远推不进下一章）。精英总开关来自
        // GlobalSetup_CH，与章节表无关，仍要判。
        boolean chapterTableReady = tables.chapterCount() > 0;
        if (diff == 2) {
            int unlock = tables.eliteUnlockRegion();
            if (rec.progress.lastNormalStage < unlock) {
                return "elite locked: normal " + rec.progress.lastNormalStage + " < " + unlock;
            }
            if (chapterTableReady) {
                int max = eliteMaxOpenRegion(rec);
                if (region > max) {
                    return "elite " + region + " > maxOpen " + max;
                }
            }
        } else if (chapterTableReady) {
            int max = normalMaxOpenRegion(rec);
            if (region > max) {
                return "normal " + region + " > maxOpen " + max;
            }
        }
        if (PlayerDumpService.needMainFbPlayLimit(region)) {
            progress.ensureDaily(rec);
            PlayerRecord.StagePlayLimit lim = dump.ensureMainFbPlayLimit(
                    rec, PlayerRecord.stageKey(region, diff), region, diff);
            if (lim.playLeft <= 0) {
                return "no play left for " + region + "/" + diff;
            }
        }
        int energy = mainFbEnergy(region, diff);
        if (rec.stamina < energy) {
            return "stamina " + rec.stamina + " < " + energy;
        }
        return null;
    }

    /** 主线单次体力：RegionList col6/col7（普通 8 / 精英 16）；表里没有才退回 dungeon-vp-cost。 */
    private int mainFbEnergy(int region, int diff) {
        int energy = tables.regionEnergy(region, diff);
        if (energy <= 0) {
            energy = tables.regionNormalEnergy(region);
        }
        return energy > 0 ? energy : props.getDungeonVpCost();
    }

    /**
     * 普通关当前可打到的最远关卡 ID：客户端 {@code ChapterGuanKaCommonInfo.GetPuTongLastOpenChapterGuanKaID()}
     * （:67-93）——上次通关关 +1；若停在章内第 10 关且角色等级 ≥ 下一章 {@code mOpenLevel} 则推进到下一章第 1 关；
     * 下限 1001、上限 {@code 章节数*1000+10}。
     */
    private int normalMaxOpenRegion(PlayerRecord rec) {
        int last = Math.max(0, rec.progress.lastNormalStage);
        int chapter = last / 1000;
        int stage = last % 1000;
        int count = tables.chapterCount();
        if (stage == 10 && chapter < count && rec.level >= tables.chapterOpenLevel(chapter + 1)) {
            chapter++;
            stage = 1;
        } else if (stage > 0 && stage < 10) {
            stage++;
        }
        int max = chapter * 1000 + stage;
        int upper = count > 0 ? count * 1000 + 10 : 28010;
        return Math.max(1001, Math.min(max, upper));
    }

    /**
     * 精英关当前可打到的最远关卡 ID：客户端 {@code GetJinYingLastOpenChapterGuanKaID()}（:97-142）——
     * 精英进度只在章内 3→6→9→10 递进，本关打完 10 且普通进度已进下一章时才跳到下一章第 3 关；
     * 下限 1003。总开关（普通进度 ≥ token66）由 {@link #mainFbRefuseReason} 单独判。
     */
    private int eliteMaxOpenRegion(PlayerRecord rec) {
        int lastHard = Math.max(0, rec.progress.lastHardStage);
        int lastNormal = Math.max(0, rec.progress.lastNormalStage);
        int hc = lastHard / 1000;
        int hs = lastHard % 1000;
        int nc = lastNormal / 1000;
        int ns = lastNormal % 1000;
        if (hs == 10 && (hc < nc - 1 || (hc == nc - 1 && ns == 10))) {
            hc++;
            hs = 3;
        } else if (hs < 10) {
            hs += 3;
        } else {
            hs++;
        }
        return Math.max(1003, hc * 1000 + hs);
    }

    public void onEnterFb(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int region = f.getInt(1, rec.currentRegionId);
        int difficult = f.getInt(2, 1);
        if (difficult <= 0) {
            difficult = 1;
        }
        String refuse = mainFbRefuseReason(rec, region, difficult);
        if (refuse != null) {
            log.info("{} enter fb refused region={} diff={}: {}", rec.account, region, difficult, refuse);
            return;
        }
        rec.currentRegionId = region;
        rec.setLastBattleDifficultyCode(difficult);
        rec.lastResourceRegionType = 0;
        progress.spendStamina(rec, mainFbEnergy(region, difficult));
        // 进本预 roll：401 结算展示与通关实发同一份
        String starKey = region + "/" + (difficult == 2 ? "hard" : "normal");
        boolean firstClear = rec.progress.stageStars.getOrDefault(starKey, 0) <= 0;
        GameTables.DropRow drop = tables.drop(region, difficult);
        PlayerRecord.PendingFbReward pending = buildPendingFb(region, difficult, drop, firstClear);
        rec.pendingFbReward = pending;
        store.save(rec);
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(2, rec.stamina));
        session.send(MsgIds.S2C_FB_INFO, pkt, dump.fbInfo(
                pending.region, pending.gold, pending.playerExp, pending.wjExp,
                displayWnsp(pending), displayYingPo(pending), toDisplayGoodsDrops(pending), false));
        session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(region));
        log.info("{} enter fb region={} diff={} drops={} wnsp={} ying={}",
                rec.account, region, difficult, pending.drops.size(), pending.wnsp, pending.yingPo);
    }

    public void onChangeFormation(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int type = f.getInt(1, 0);
        List<String> wj = f.getStrings(2);
        List<String> five = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            five.add(i < wj.size() ? wj.get(i) : "");
        }
        // BOB 本轮已锁阵：拒绝改 type7，回当前锁定阵（闪退重进/误改阵不漂移）
        if (type == PlayerRecord.FORMATION_BOB_ATK && bob.isRunRosterLocked(rec)) {
            log.info("{} bob formation locked, ignore change type7", rec.account);
            session.send(MsgIds.S2C_FORMATION, pkt, dump.formation(rec, type));
            return;
        }
        rec.setFormationSlots(type, five);
        store.save(rec);
        session.send(MsgIds.S2C_FORMATION, pkt, dump.formation(rec, type));
    }

    public void onResultFb(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int result = f.getInt(1, 0);
        int star = f.getInt(2, 0);
        int resourceType = rec.lastResourceRegionType;
        byte[] playTimeUpdate = null;
        if (result == 1) {
            if (resourceType > 0) {
                applyResourceWin(session, pkt, rec, star);
            } else {
                playTimeUpdate = applyWin(session, pkt, rec, star);
                task.onChapterWin(session, pkt, rec, rec.currentRegionId, rec.lastBattleDifficultyCode(), 1);
            }
        }
        clearLastResource(rec);
        store.save(rec);
        session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(result, star));
        // 1002 必须**晚于** 1001：客户端收到 1001 才会一路走到 ShowVictoryUI
        // （BattleController.cs:682-684 用 DelegateSendNetMsgManager 挂在 EN_RESULTFB_RET_SUCCESS 上），
        // 由它 new FBStarInfo(...) 建出该关条目（BattleController.cs:1390-1398，mFBPlayTimeLeft 默认 0）；
        // 而 OnFBPlayTimeUpdate（PlayGameState.cs:5773-5782）对 mFBStarList 里没有的 fbID 直接 continue。
        // 先发 1002 ⇒ 首次通关那一次更新被丢掉、随后条目被建成 0 ⇒ 界面上「挑战次数被置 0」。
        if (playTimeUpdate != null) {
            session.send(MsgIds.S2C_UPDATE_FB_PLAY_TIME, pkt, playTimeUpdate);
        }
        log.info("{} result fb result={} star={} resourceType={}",
                rec.account, result, star, resourceType);
    }

    public void onSaoDang(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int region = f.getInt(1, rec.currentRegionId);
        int diff = f.getInt(2, 1);
        if (diff <= 0) {
            diff = 1;
        }
        boolean once = f.getBool(3);
        String refuse = mainFbRefuseReason(rec, region, diff);
        if (refuse != null) {
            log.info("{} saodang refused region={} diff={}: {}", rec.account, region, diff, refuse);
            return;
        }
        // 只有受限关（章内 3/6/9/10）能扫荡：客户端 CheckSaoDang 对 !NeedCheckPlayTime 直接 return false
        // （NormalFBDescribeSystem.cs:774-778），非受限关扫荡按钮根本不亮。
        if (!PlayerDumpService.needMainFbPlayLimit(region)) {
            log.info("{} saodang refused region={} diff={}: not a limited stage", rec.account, region, diff);
            return;
        }
        // 三星才能扫荡（单次/十连同门）：NormalFBDescribeSystem.cs:754-755 mFBStarList[levelID].Stars < 3。
        if (rec.progress.stageStars.getOrDefault(PlayerRecord.stageKey(region, diff), 0) < 3) {
            log.info("{} saodang refused region={} diff={}: stars < 3", rec.account, region, diff);
            return;
        }
        // 十连扫荡的 VIP 门：VipCfg「开启十连扫荡」（客户端 CheckSaoDang :735 VipManager.IsOpenShaoDang10Ci，
        // 实表 VIP2 起为 1）；单次扫荡无 VIP 门。
        if (!once && !economy.openShaoDang10Ci(rec.economy.chargedDiamond)) {
            log.info("{} saodang refused region={} diff={}: 10x needs VIP", rec.account, region, diff);
            return;
        }
        int times = once ? 1 : sweepTimes(rec, region, diff);
        if (times <= 0) {
            log.info("{} saodang refused region={} diff={}: no times", rec.account, region, diff);
            return;
        }
        rec.currentRegionId = region;
        rec.setLastBattleDifficultyCode(diff);
        session.send(MsgIds.S2C_SAO_DANG_RET, pkt, sweep(session, pkt, rec, region, diff, times));
        task.onChapterWin(session, pkt, rec, region, diff, times);
        log.info("{} saodang region={} diff={} times={}", rec.account, region, diff, times);
    }

    public void onSaoDangResource(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int regionType = f.getInt(1, rec.currentRegionId);
        int diff = f.getInt(2, 1);
        if (diff <= 0) {
            diff = 1;
        }
        if (!canSaoDangResource(rec, regionType, diff)) {
            log.info("{} resource saodang refuse type={} lv={}", rec.account, regionType, diff);
            pushResourceFbState(session, pkt, rec);
            return;
        }
        consumeResourceFbPlay(session, pkt, rec, regionType);
        int sceneId = tables.firstRegionOfType(regionType);
        int energy = tables.regionNormalEnergy(sceneId);
        int dropRegion = tables.resourceDropRegionId(regionType, diff);
        GameTables.ResourceFbPlan plan = tables.resourceFbPlan(regionType, diff);
        List<GameTables.GoodsDrop> rolled = new ArrayList<>();
        byte[] saoBody;
        if (plan != null) {
            if (energy > 0) {
                progress.spendStamina(rec, energy);
                progress.pushStamina(session, pkt, rec);
            }
            rec.gold += plan.gold;
            rolled.addAll(plan.goods);
            GameTables.DropRow drop = tables.drop(dropRegion, 1);
            if (regionType == RT_UNTOUCHABLE && drop != null && drop.star3Extra != null) {
                rolled.add(drop.star3Extra);
            }
            grantResourceGoods(session, pkt, rec, rolled);
            if (plan.gold > 0) {
                progress.pushGold(session, pkt, rec);
            }
            store.save(rec);
            List<byte[]> bases = new ArrayList<>();
            bases.add(dump.saoDangBase(0, plan.gold, 0, 0, rolled));
            saoBody = dump.saoDangResult(bases);
        } else {
            saoBody = sweep(session, pkt, rec, dropRegion, 1, 1, energy, false, rolled);
            GameTables.DropRow drop = tables.drop(dropRegion, 1);
            if (regionType == RT_UNTOUCHABLE && drop != null && drop.star3Extra != null) {
                rolled.add(drop.star3Extra);
                grantResourceGoods(session, pkt, rec,
                        java.util.Collections.singletonList(drop.star3Extra));
                List<byte[]> bases = new ArrayList<>();
                bases.add(dump.saoDangBase(0, 0, 0, 0, rolled));
                saoBody = dump.saoDangResult(bases);
            }
        }
        List<GameTables.GoodsDrop> extra = resourceExtraDrops(rec, regionType, rolled);
        grantResourceExtra(session, pkt, rec, extra);
        session.send(MsgIds.S2C_SAO_DANG_RESOURCE_RET, pkt, saoBody);
        noteResourceDaily(session, pkt, rec, regionType);
        log.info("{} resource saodang type={} lv={}", rec.account, regionType, diff);
    }

    // §6-9 死代码清理：原 public void onResultSpecial(session, pkt)（仅转调 onResultFb）
    // 全工程零调用 ⇒ 已删。镜像/致命结算现由 dispatcher 的两个 case 直接走 onResultFb。

    public void onResultTower(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int result = Pb.read(pkt.body).getInt(1, 0);
        if (result == 1) {
            applyTowerWin(session, pkt, rec);
            advanceTowerLayer(rec);
            store.save(rec);
        } else {
            rec.tower.challengeTimes++;
            store.save(rec);
        }
        session.send(MsgIds.S2C_RESULT_FB_RET, pkt, dump.resultFb(result, result == 1 ? 3 : 0));
        session.send(MsgIds.S2C_TOWER_TIMES, pkt, dump.towerTimes(rec.tower.challengeTimes));
        notifyTowerBuddyLayer(pkt, rec);
        log.info("{} hundred-tower result={} layer={} alreadyTZ={}",
                rec.account, result, rec.tower.curLayer, rec.tower.challengeTimes);
    }

    public void onSaoDangTower(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        ensureTowerWeeklyReset(rec);
        store.save(rec);
        boolean all = f.getBool(1);
        if (all) {
            if (!economy.bctAllSaoDang(rec.economy.chargedDiamond)) {
                log.info("{} hundred-tower saodang refuse vip-all", rec.account);
                pushTowerState(session, pkt, rec);
                return;
            }
        } else if (!economy.bctSingleSaoDang(rec.economy.chargedDiamond)) {
            log.info("{} hundred-tower saodang refuse vip-single", rec.account);
            pushTowerState(session, pkt, rec);
            return;
        }
        if (rec.level < tables.bctCommon().enableLevel) {
            pushTowerState(session, pkt, rec);
            return;
        }
        int start = Math.max(1, rec.tower.curLayer);
        GameTables.BctCommon cm = tables.bctCommon();
        int maxLayer = tables.bctMaxLayer();
        int layers = all
                ? Math.max(0, rec.tower.historyLayer - start - cm.resetSaoDangCut)
                : 1;
        if (!all && start > maxLayer) {
            layers = 0;
        }
        if (layers <= 0) {
            log.info("{} hundred-tower saodang refuse all={} start={}", rec.account, all, start);
            pushTowerState(session, pkt, rec);
            return;
        }
        int cost = cm.saoDangDiamond * layers;
        if (cost > 0) {
            if (rec.diamond < cost) {
                pushTowerState(session, pkt, rec);
                return;
            }
            rec.diamond -= cost;
            progress.pushDiamond(session, pkt, rec);
        }
        for (int i = 0; i < layers; i++) {
            int layer = start + i;
            GameTables.ResourceFbPlan rew = tables.bctReward(layer);
            int gold = rew.gold;
            rec.gold += gold;
            List<GameTables.GoodsDrop> goods = new ArrayList<>(rew.goods);
            Map<String, Integer> changed = new LinkedHashMap<>();
            List<PlayerRecord.Equipment> newEq = new ArrayList<>();
            for (GameTables.GoodsDrop g : goods) {
                newEq.addAll(progress.grantReward(rec, g.ori, g.count, changed));
            }
            rec.tower.curLayer = Math.min(maxLayer, layer + 1);
            rec.tower.historyLayer = Math.max(rec.tower.historyLayer, rec.tower.curLayer);
            rec.tower.challengeTimes = 0;
            store.save(rec);
            progress.pushGoods(session, pkt, rec, changed);
            progress.pushEquips(session, pkt, newEq);
            if (gold > 0) {
                progress.pushGold(session, pkt, rec);
            }
            session.send(MsgIds.S2C_SAO_DANG_HUNDRED_TOWER, pkt, dump.hundredTowerLayer(gold, goods));
        }
        log.info("{} hundred-tower saodang start={} layers={} nowCur={}", rec.account, start, layers, rec.tower.curLayer);
        notifyTowerBuddyLayer(pkt, rec);
    }

    private byte[] sweep(GameSession session, GamePacket pkt, PlayerRecord rec, int region, int diff, int times) {
        return sweep(session, pkt, rec, region, diff, times, mainFbEnergy(region, diff), true, null);
    }

    /**
     * 十连扫荡实际次数：客户端 {@code FBStarInfo.GetRealPlayMaxTime()}（:45-58）——
     * {@code min(剩余次数, 当前体力/单次体力)}（体力整除为 0 时取剩余次数）。
     * 服务端按同一算式自己算（C2S 310 包里没有次数字段）。
     */
    private int sweepTimes(PlayerRecord rec, int region, int diff) {
        int energy = mainFbEnergy(region, diff);
        progress.ensureDaily(rec);
        PlayerRecord.StagePlayLimit lim = dump.ensureMainFbPlayLimit(
                rec, PlayerRecord.stageKey(region, diff), region, diff);
        int byStamina = energy > 0 ? rec.stamina / energy : lim.playLeft;
        return Math.min(lim.playLeft, byStamina);
    }

    private byte[] sweep(GameSession session, GamePacket pkt, PlayerRecord rec, int region, int diff, int times,
                         int vpPerRun, boolean consumeMainLimit, List<GameTables.GoodsDrop> rolledOut) {
        int cost = Math.max(0, vpPerRun) * times;
        progress.spendStamina(rec, cost);
        List<byte[]> bases = new ArrayList<>();
        Map<String, Integer> changed = new LinkedHashMap<>();
        List<PlayerRecord.Equipment> newEq = new ArrayList<>();
        boolean pLv = false;
        List<Boolean> wjLeveled = new ArrayList<>();
        for (int i = 0; i < rec.heroes.size(); i++) {
            wjLeveled.add(Boolean.FALSE);
        }
        for (int n = 0; n < times; n++) {
            GameTables.DropRow drop = tables.drop(region, diff);
            int gold = drop != null ? drop.gold : props.getDungeonGold();
            int pExp = drop != null ? drop.playerExp : props.getDungeonPlayerExp();
            int wExp = drop != null ? drop.wjExp : props.getDungeonWujiangExp();
            rec.gold += gold;
            pLv = progress.addPlayerExp(rec, pExp) || pLv;
            List<GameTables.GoodsDrop> drops = drop != null
                    ? drop.roll(rng, false)
                    : java.util.Collections.singletonList(
                    new GameTables.GoodsDrop(props.getDropOriName(), props.getDropCount()));
            if (drops == null) {
                drops = new ArrayList<>();
            }
            maybeAppendWordCharGoods(region, drops);
            if (rolledOut != null) {
                rolledOut.clear();
                rolledOut.addAll(drops);
            }
            int wnsp = drop != null ? drop.rollWnsp(rng, false) : 0;
            int ying = drop != null ? drop.rollYingPo(rng, false) : 0;
            progress.addWnsp(rec, wnsp);
            progress.addYingPo(rec, ying);
            for (GameTables.GoodsDrop g : drops) {
                newEq.addAll(progress.grantReward(rec, g.ori, g.count, changed));
            }
            for (int i = 0; i < rec.heroes.size(); i++) {
                if (progress.addWjExp(rec, rec.heroes.get(i), wExp)) {
                    wjLeveled.set(i, Boolean.TRUE);
                }
            }
            bases.add(dump.saoDangBase(pExp, gold, wnsp, ying, drops));
        }
        if (consumeMainLimit) {
            byte[] limUpdate = consumeMainFbPlayTime(rec, region, diff, times);
            if (limUpdate != null) {
                session.send(MsgIds.S2C_UPDATE_FB_PLAY_TIME, pkt, limUpdate);
            }
        }
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushEquips(session, pkt, newEq);
        progress.pushPlayerProgress(session, pkt, rec, true, true, cost > 0 || pLv, true, false);
        if (rec.wannengFragments > 0) {
            progress.pushWnsp(session, pkt, rec);
        }
        if (rec.yingPo > 0) {
            progress.pushYingPo(session, pkt, rec);
        }
        for (int i = 0; i < rec.heroes.size(); i++) {
            progress.pushWjProgress(session, pkt, rec, rec.heroes.get(i), wjLeveled.get(i).booleanValue());
        }
        task.onPlayerLeveled(session, pkt, rec, pLv);
        return dump.saoDangResult(bases);
    }

    /** @return 受限关次数的 S2C 1002 包（没有则 null）；由 {@link #onResultFb} 在 1001 **之后**发。 */
    private byte[] applyWin(GameSession session, GamePacket pkt, PlayerRecord rec, int star) {
        int region = rec.currentRegionId;
        int diff = rec.lastBattleDifficultyCode();
        String starKey = PlayerRecord.stageKey(region, diff);
        boolean firstClear = rec.progress.stageStars.getOrDefault(starKey, 0) <= 0;
        rec.progress.stageStars.put(starKey, Math.max(rec.progress.stageStars.getOrDefault(starKey, 0), star));
        if (diff == 2) {
            rec.progress.lastHardStage = Math.max(rec.progress.lastHardStage, region);
        } else {
            rec.progress.lastNormalStage = Math.max(rec.progress.lastNormalStage, region);
        }

        GameTables.DropRow drop = tables.drop(region, diff);
        PlayerRecord.PendingFbReward pending = takePendingFb(rec, region, diff, drop, firstClear);
        int gold = pending.gold;
        int pExp = pending.playerExp;
        int wExp = pending.wjExp;
        rec.gold += gold;
        boolean pLv = progress.addPlayerExp(rec, pExp);

        Map<String, Integer> changed = new LinkedHashMap<>();
        List<PlayerRecord.Equipment> newEq = new ArrayList<>();
        List<GameTables.GoodsDrop> drops = toGoodsDrops(pending);
        if (drops.isEmpty()) {
            drops = new ArrayList<>();
            drops.add(new GameTables.GoodsDrop(props.getDropOriName(), props.getDropCount()));
        }
        for (GameTables.GoodsDrop g : drops) {
            newEq.addAll(progress.grantReward(rec, g.ori, g.count, changed));
        }
        int wnsp = pending.wnsp;
        int ying = pending.yingPo;
        progress.addWnsp(rec, wnsp);
        progress.addYingPo(rec, ying);

        List<Boolean> wjLeveled = new ArrayList<>();
        for (PlayerRecord.Hero wj : rec.heroes) {
            wjLeveled.add(progress.addWjExp(rec, wj, wExp));
        }
        byte[] playTimeUpdate = consumeMainFbPlayTime(rec, region, diff, 1);
        store.save(rec);

        progress.pushGoods(session, pkt, rec, changed);
        progress.pushEquips(session, pkt, newEq);
        progress.pushPlayerProgress(session, pkt, rec, true, true, pLv, pLv, false);
        if (wnsp > 0) {
            progress.pushWnsp(session, pkt, rec);
        }
        if (ying > 0) {
            progress.pushYingPo(session, pkt, rec);
        }
        for (int i = 0; i < rec.heroes.size(); i++) {
            progress.pushWjProgress(session, pkt, rec, rec.heroes.get(i), wjLeveled.get(i));
        }
        task.onPlayerLeveled(session, pkt, rec, pLv);
        return playTimeUpdate;
    }

    /** 资源挑战通关：按玩法×难度落最高星，推 450；不写主线 stageStars、不扣主线限次。 */
    private void applyResourceWin(GameSession session, GamePacket pkt, PlayerRecord rec, int star) {
        int regionType = rec.lastResourceRegionType;
        int level = Math.max(1, Math.min(7, rec.lastResourceLevel));
        int starClamped = Math.max(0, Math.min(3, star));
        ensureAllResourceFbSlots(rec);
        PlayerRecord.ResourceFb fb = rec.resourceFb.get(Integer.valueOf(regionType));
        if (fb == null) {
            fb = new PlayerRecord.ResourceFb();
            fb.regionType = regionType;
            rec.resourceFb.put(Integer.valueOf(regionType), fb);
        }
        if (fb.stars == null) {
            fb.stars = new LinkedHashMap<>();
        }
        Integer prev = fb.stars.get(Integer.valueOf(level));
        int next = Math.max(prev == null ? 0 : prev.intValue(), starClamped);
        fb.stars.put(Integer.valueOf(level), Integer.valueOf(next));

        int sceneId = tables.firstRegionOfType(regionType);
        int dropRegion = tables.resourceDropRegionId(regionType, level);
        GameTables.DropRow drop = tables.drop(dropRegion, 1);
        GameTables.ResourceFbPlan plan = tables.resourceFbPlan(regionType, level);
        PlayerRecord.PendingFbReward pending = plan != null
                ? pendingFromPlan(sceneId, level, plan)
                : takePendingFb(rec, sceneId, level, drop, false);
        rec.pendingFbReward = null;
        int gold = pending.gold;
        int pExp = pending.playerExp;
        int wExp = pending.wjExp;
        rec.gold += gold;
        boolean pLv = progress.addPlayerExp(rec, pExp);

        Map<String, Integer> changed = new LinkedHashMap<>();
        List<PlayerRecord.Equipment> newEq = new ArrayList<>();
        List<GameTables.GoodsDrop> drops = toGoodsDrops(pending);
        if (regionType == RT_UNTOUCHABLE && starClamped >= 3 && drop != null && drop.star3Extra != null) {
            drops.add(drop.star3Extra);
        }
        for (GameTables.GoodsDrop g : drops) {
            newEq.addAll(progress.grantReward(rec, g.ori, g.count, changed));
        }
        int wnsp = pending.wnsp;
        int ying = pending.yingPo;
        progress.addWnsp(rec, wnsp);
        progress.addYingPo(rec, ying);

        List<Boolean> wjLeveled = new ArrayList<>();
        for (PlayerRecord.Hero wj : rec.heroes) {
            wjLeveled.add(progress.addWjExp(rec, wj, wExp));
        }
        store.save(rec);

        progress.pushGoods(session, pkt, rec, changed);
        progress.pushEquips(session, pkt, newEq);
        progress.pushPlayerProgress(session, pkt, rec, true, true, pLv, pLv, false);
        if (wnsp > 0) {
            progress.pushWnsp(session, pkt, rec);
        }
        if (ying > 0) {
            progress.pushYingPo(session, pkt, rec);
        }
        for (int i = 0; i < rec.heroes.size(); i++) {
            progress.pushWjProgress(session, pkt, rec, rec.heroes.get(i), wjLeveled.get(i));
        }
        session.send(MsgIds.S2C_RESOURCE_FB_UPDATE, pkt, dump.resourceFbUpdate(rec));
        grantResourceExtra(session, pkt, rec, resourceExtraDrops(rec, regionType, drops));
        noteResourceDaily(session, pkt, rec, regionType);
        task.onPlayerLeveled(session, pkt, rec, pLv);
        log.info("{} resource-fb star type={} level={} star={}", rec.account, regionType, level, next);
    }

    public void onUseGoodsExp(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        String ori = f.getString(1);
        int count = f.getInt(2, 0);
        String guid = f.getString(3);
        int have = rec.bag.getOrDefault(ori, 0);
        int use = Math.min(have, Math.max(0, count));
        rec.bag.put(ori, have - use);
        PlayerRecord.Hero wj = rec.findHero(guid);
        boolean leveled = false;
        EconomyTables.GoodsCfg g = economy.goods(ori);
        int per = (g != null && g.attrType == 1 && g.attrP1 > 0) ? g.attrP1 : 0;
        if (wj != null && use > 0 && per > 0) {
            leveled = progress.addWjExp(rec, wj, use * per);
        }
        store.save(rec);
        session.send(MsgIds.S2C_UPDATE_GOODS, pkt, dump.goodsUpdate(ori, rec.bag.getOrDefault(ori, 0)));
        if (wj != null) {
            progress.pushWjProgress(session, pkt, rec, wj, leveled);
        }
    }

    /**
     * 章节宝箱的「宝箱位 → 难度/门槛星数」。
     * 客户端 {@code MainPlayer.GetChapterBaoXiangState}（:2235-2258）：位 = {@code 1 << (level-1 + (nanDu==1?0:3))}
     * ⇒ 普通 1/2/4、精英 8/16/32；门槛 {@code num = nanDu==2?4:10}、要求 {@code 章节星数 >= num*level}
     * ⇒ 普通 10/20/30 星、精英 4/8/12 星。与 {@code ChapterBaoXiang.txt}「宝箱等级」列的表头注释同口径。
     *
     * @return {@code {难度, 门槛星数}}；认不出该位返回 null（不拦，交给原有逻辑）
     */
    private static int[] chestRequirement(int box) {
        switch (box) {
            case 1:
                return new int[]{1, 10};
            case 2:
                return new int[]{1, 20};
            case 4:
                return new int[]{1, 30};
            case 8:
                return new int[]{2, 4};
            case 16:
                return new int[]{2, 8};
            case 32:
                return new int[]{2, 12};
            default:
                return null;
        }
    }

    /** 章节星数合计：客户端 {@code ChapterGuanKaCommonInfo.GetChapterXinJi}（:39-51）逐关 1..10 累加同难度星数。 */
    private int chapterStars(PlayerRecord rec, int chapter, int nanDu) {
        int sum = 0;
        for (int stage = 1; stage <= 10; stage++) {
            int region = chapter * 1000 + stage;
            sum += rec.progress.stageStars.getOrDefault(PlayerRecord.stageKey(region, nanDu), 0);
        }
        return sum;
    }

    public void onChapterChest(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int chapter = f.getInt(1, 0);
        int box = f.getInt(2, 0);
        int have = rec.economy.chapterChests.getOrDefault(Integer.valueOf(chapter), 0);
        if ((have & box) != 0) {
            return;
        }
        // 星数门槛硬编码在客户端（MainPlayer.GetChapterBaoXiangState :2253 chapterXinJi < num*level），
        // 表里只有「宝箱等级」位掩码、没有门槛列，所以服务端按同一算式自己算。
        int[] need = chestRequirement(box);
        if (need != null) {
            int stars = chapterStars(rec, chapter, need[0]);
            if (stars < need[1]) {
                log.info("{} chapter chest refused chapter={} box={}: stars {} < {}",
                        rec.account, chapter, box, stars, need[1]);
                return;
            }
        }
        EconomyTables.ChestRow row = economy.chest(chapter, box);
        rec.economy.chapterChests.put(Integer.valueOf(chapter), have | box);
        Map<String, Integer> changed = progress.emptyChanged();
        if (row != null) {
            rec.gold += row.gold;
            rec.diamond += row.diamond;
            for (EconomyTables.Mat m : row.goods) {
                progress.addGoods(rec, m.ori, m.count);
                progress.markGoods(changed, m.ori);
            }
        } else {
            rec.gold += 3000;
        }
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushGold(session, pkt, rec);
        if (row != null && row.diamond > 0) {
            progress.pushDiamond(session, pkt, rec);
        }
        session.send(MsgIds.S2C_CHAPTER_CHEST, pkt, dump.chapterChest(chapter, box));
    }

    /**
     * 资源挑战进本（C2S 320/325/327/329/331）。
     * body.level=难度 1–7；切图 RegionList 该 TYPE 首个场景 ID；掉落 RegionDropList=场景ID+难度。
     * 401 needCheck=false（镜像等 Controller.Enter 置 IsServerCal=false）；己方装/石走登录 A2 本地创将。
     */
    public void onEnterResourceLike(GameSession session, GamePacket pkt, int s2cEnter, int regionType) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int level = Pb.read(pkt.body).getInt(1, 1);
        if (level <= 0) {
            level = 1;
        }
        int sceneId = tables.firstRegionOfType(regionType);
        if (sceneId <= 0) {
            log.warn("{} resource enter no RegionList type={}", rec.account, regionType);
            pushResourceFbState(session, pkt, rec);
            return;
        }
        if (!canEnterResource(rec, regionType, level)) {
            log.info("{} resource enter refuse type={} lv={}", rec.account, regionType, level);
            pushResourceFbState(session, pkt, rec);
            return;
        }
        rec.lastResourceRegionType = regionType;
        rec.lastResourceLevel = level;
        rec.currentRegionId = sceneId;
        rec.setLastBattleDifficultyCode(level);
        consumeResourceFbPlay(session, pkt, rec, regionType);
        int energy = tables.regionNormalEnergy(sceneId);
        if (energy > 0) {
            progress.spendStamina(rec, energy);
            progress.pushStamina(session, pkt, rec);
        }
        int dropRegion = tables.resourceDropRegionId(regionType, level);
        GameTables.DropRow drop = tables.drop(dropRegion, 1);
        GameTables.ResourceFbPlan plan = tables.resourceFbPlan(regionType, level);
        PlayerRecord.PendingFbReward pending = plan != null
                ? pendingFromPlan(sceneId, level, plan)
                : buildPendingFb(sceneId, level, drop, false);
        rec.pendingFbReward = pending;
        store.save(rec);
        if (s2cEnter > 0) {
            session.send(s2cEnter, pkt, new byte[0]);
        }
        session.send(MsgIds.S2C_FB_INFO, pkt, dump.fbInfo(
                pending.region, pending.gold, pending.playerExp, pending.wjExp,
                displayWnsp(pending), displayYingPo(pending), toDisplayGoodsDrops(pending), false));
        session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(sceneId));
        log.info("{} resource enter type={} lv={} scene={} dropRegion={}",
                rec.account, regionType, level, sceneId, dropRegion);
    }

    /** 资源本已用次数 +1，写 lastPlay，推 S2C 450。 */
    private void consumeResourceFbPlay(GameSession session, GamePacket pkt, PlayerRecord rec, int regionType) {
        progress.ensureDaily(rec);
        ensureAllResourceFbSlots(rec);
        PlayerRecord.ResourceFb fb = rec.resourceFb.get(Integer.valueOf(regionType));
        if (fb == null) {
            fb = new PlayerRecord.ResourceFb();
            fb.regionType = regionType;
            rec.resourceFb.put(Integer.valueOf(regionType), fb);
        }
        fb.playTime++;
        fb.lastPlay = PlayerDumpService.now();
        store.save(rec);
        session.send(MsgIds.S2C_RESOURCE_FB_UPDATE, pkt, dump.resourceFbUpdate(rec));
        log.info("{} resource-fb consume type={} used={}", rec.account, regionType, fb.playTime);
    }

    /** 登录/进本前保证五类资源本有档，避免客户端 GetResourceFBData 空、450 更新对不上。 */
    public static void ensureAllResourceFbSlots(PlayerRecord rec) {
        if (rec.resourceFb == null) {
            rec.resourceFb = new LinkedHashMap<>();
        }
        for (int type : RESOURCE_REGION_TYPES) {
            Integer key = Integer.valueOf(type);
            PlayerRecord.ResourceFb fb = rec.resourceFb.get(key);
            if (fb == null) {
                fb = new PlayerRecord.ResourceFb();
                fb.regionType = type;
                rec.resourceFb.put(key, fb);
            }
            if (fb.stars == null) {
                fb.stars = new LinkedHashMap<>();
            }
        }
    }

    public void onBuyFbTimes(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        int fbId = Pb.read(pkt.body).getInt(1, 0);
        // 主线/精英：fbID=关卡ID×10+难度(1/2)，如 10101；资源本 type 一般是小整数
        int rem = fbId % 10;
        if ((rem == 1 || rem == 2) && fbId >= 100) {
            onBuyMainFbPlayTime(session, pkt, rec, fbId);
            return;
        }
        onBuyResourceFbTimes(session, pkt, rec, fbId);
    }

    /** C2S 333 主线受限关买重置：扣钻，剩余次数灌满，推 S2C 1002。 */
    private void onBuyMainFbPlayTime(GameSession session, GamePacket pkt, PlayerRecord rec, int fbId) {
        int region = fbId / 10;
        int diff = fbId % 10;
        if (!PlayerDumpService.needMainFbPlayLimit(region)) {
            return;
        }
        String key = PlayerRecord.stageKey(region, diff);
        PlayerRecord.StagePlayLimit lim = dump.ensureMainFbPlayLimit(rec, key, region, diff);
        if (lim.playLeft > 0) {
            return;
        }
        boolean elite = diff == 2;
        // 每日购买次数上限：VipCfg「普通/精英副本重置」（客户端 NormalFBDescribeSystem.cs:867
        // mResetMaxCount = IsJY ? VipManager.JYFBResetMaxCount : VipManager.FBResetMaxCount，
        // 实表 VIP0=0、VIP1=1、VIP2=2 ⇒ VIP0 当天买不了）。
        int maxReset = elite ? economy.jyFbResetMaxCount(rec.economy.chargedDiamond)
                : economy.fbResetMaxCount(rec.economy.chargedDiamond);
        if (lim.buyTimes >= maxReset) {
            log.info("{} buy main-fb reset refused fbId={}: buyTimes {} >= max {}",
                    rec.account, fbId, lim.buyTimes, maxReset);
            return;
        }
        int cost = economy.buyFbDiamond(elite, lim.buyTimes + 1);
        if (cost <= 0 || rec.diamond < cost) {
            return;
        }
        rec.diamond -= cost;
        lim.buyTimes++;
        lim.playLeft = dump.mainFbPlayMax(diff, region);
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        session.send(MsgIds.S2C_UPDATE_FB_PLAY_TIME, pkt,
                dump.fbPlayTimeUpdate(fbId, lim.playLeft, lim.buyTimes));
        log.info("{} buy main-fb play reset fbId={} left={} buyTimes={}",
                rec.account, fbId, lim.playLeft, lim.buyTimes);
    }

    private void onBuyResourceFbTimes(GameSession session, GamePacket pkt, PlayerRecord rec, int fbId) {
        PlayerRecord.ResourceFb fb = rec.resourceFb.get(Integer.valueOf(fbId));
        if (fb == null) {
            fb = new PlayerRecord.ResourceFb();
            fb.regionType = fbId;
            rec.resourceFb.put(Integer.valueOf(fbId), fb);
        }
        int cost = economy.buyFbDiamond(false, fb.buyTimes + 1);
        if (rec.diamond < cost) {
            return;
        }
        rec.diamond -= cost;
        fb.buyTimes++;
        // playTime=已用；买一次多 1 次剩余 → 已用 −1（下限 0）
        fb.playTime = Math.max(0, fb.playTime - 1);
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        session.send(MsgIds.S2C_RESOURCE_FB_UPDATE, pkt, dump.resourceFbUpdate(rec));
    }

    /**
     * 受限关消耗剩余次数；不足则仍扣到 0（扫荡前客户端应已拦）。
     * **只造包不发送**：主线通关那次必须等 1001 发完再发 1002（见 {@link #onResultFb}）。
     *
     * @return S2C 1002 包；该关不限次/次数为 0 时返回 null
     */
    private byte[] consumeMainFbPlayTime(PlayerRecord rec, int region, int diff, int times) {
        if (!PlayerDumpService.needMainFbPlayLimit(region) || times <= 0) {
            return null;
        }
        progress.ensureDaily(rec);
        String key = PlayerRecord.stageKey(region, diff);
        PlayerRecord.StagePlayLimit lim = dump.ensureMainFbPlayLimit(rec, key, region, diff);
        lim.playLeft = Math.max(0, lim.playLeft - times);
        int fbId = region * 10 + (diff == 2 ? 2 : 1);
        return dump.fbPlayTimeUpdate(fbId, lim.playLeft, lim.buyTimes);
    }

    public void onSellGoods(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int gold = 0;
        Map<String, Integer> changed = progress.emptyChanged();
        for (byte[] one : Pb.read(pkt.body).getBytesList(1)) {
            Pb.Fields g = Pb.read(one);
            String ori = g.getString(2);
            String guid = g.getString(3);
            int count = Math.max(1, g.getInt(4, 1));
            if (guid != null && !guid.isEmpty()) {
                PlayerRecord.Equipment eq = rec.findEquip(guid);
                if (eq != null && (eq.owner == null || eq.owner.isEmpty())) {
                    rec.equipments.remove(eq);
                    // 装备卖价取 EquipmentList.txt 第 8 列「金币价格」（客户端 BagUISystem.cs:812/915 显示的就是它）。
                    // 改前走 economy.sellGold（只查 GoodsList，没有 EQ 键）⇒ 显示 300 实得 10 金
                    CultivateTables.EquipCfg eqCfg = cultivate.equip(eq.ori);
                    gold += eqCfg != null && eqCfg.goldPrice > 0 ? eqCfg.goldPrice
                            : Math.max(10, economy.sellGold(eq.ori, 1));
                    session.send(MsgIds.S2C_REMOVE_EQUIP, pkt, dump.equipGuid(eq.id));
                }
                continue;
            }
            if (!progress.hasGoods(rec, ori, count)) {
                continue;
            }
            gold += economy.sellGold(ori, count);
            progress.consumeGoods(rec, ori, count);
            progress.markGoods(changed, ori);
        }
        rec.gold += gold;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        if (gold > 0) {
            progress.pushGold(session, pkt, rec);
        }
        session.send(MsgIds.S2C_SELL_GOODS_RET, pkt, new byte[0]);
    }

    public void onOpenBaoXiang(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        boolean single = f.getBool(1);
        String ori = f.getString(2);
        int count = Math.max(1, f.getInt(3, 1));
        if (!economy.isBaoXiang(ori) || !progress.hasGoods(rec, ori, count)) {
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        progress.consumeGoods(rec, ori, count);
        progress.markGoods(changed, ori);
        // 货币箱（N7）：GoodsList 属性 10 的行只把产出写成 `99999:NN` 抽取库引用，而该解库表在假服与客户端
        // 都不存在；货币数量改由 EconomyTables.boxCurrency 硬编码（取自 GoodsList 名字/说明列）。
        // 客户端 8 个货币槽完全由服务端字段决定、不做本地换算（BagUISystem.cs:1375-1443 单开 / :1569-1636 十连），
        // 十连必须发 count 次总量。
        EconomyTables.BoxCurrency got = null;
        EconomyTables.BoxCurrency cur = economy.boxCurrency(ori);
        if (cur != null && cur.any()) {
            got = cur.mul(count);
            rec.gold += got.gold;
            rec.diamond += got.diamond;
            rec.stamina += got.stamina;
            rec.wannengFragments += got.wnsp;
            rec.yingPo += got.yingPo;
            rec.moFaChen += got.moFaChen;
            rec.jjcScore += got.jjc;
            if (got.xdb > 0 && rec.guild != null) {
                rec.guild.brotherCoin += got.xdb;
            }
        }
        List<byte[]> awards = new ArrayList<>();
        // 固定产出的箱（BX191-194 这类「一定获得…」，配置在 ltsj.json 的 boxes[].items）：
        // 真服的抽取库（GoodsList 第 13 列 spec 1:606|1:611|1:612、99999:607~609）假服没有对应表，
        // 只发说明列写死的那几件。未登记的箱维持随机池（历史行为）。
        List<LtsjCfg.LtsjFile.BoxItem> fixed = ltsj.boxItems(ori);
        for (int i = 0; i < count; i++) {
            if (fixed.isEmpty()) {
                // 纯货币箱不要再白送一件随机物品（否则「150 体力」箱会同时吐道具）；
                // 混合箱（OMBX001 = 三明治×5 + 体力×30）仍要发它说明列写死的那件道具。
                if (got != null && got.alsoItemOri == null) {
                    continue;
                }
                // 道具是**逐次**发的 ⇒ 这里取的是「单次开箱的量」，不能用 got.alsoItemCount：
                // got = cur.mul(count) 已是货币总量，若道具也乘 count 再进这个 count 次的循环就是 count²
                // （BX109 十连会发 200 片而不是 20 片）。BoxCurrency.mul 因此不放大 alsoItemCount。
                String prize = got != null ? got.alsoItemOri : economy.rollRandomBox(rng);
                int n = got != null ? Math.max(1, got.alsoItemCount) : 1;
                progress.addGoods(rec, prize, n);
                progress.markGoods(changed, prize);
                awards.add(dump.drawAwardItem(prize, n));
                continue;
            }
            for (LtsjCfg.LtsjFile.BoxItem item : fixed) {
                int n = Math.max(1, item.num);
                List<PlayerRecord.Equipment> eqs = progress.grantReward(rec, item.ori, n, changed);
                if (eqs.isEmpty()) {
                    awards.add(dump.drawAwardItem(item.ori, n));
                    continue;
                }
                for (PlayerRecord.Equipment eq : eqs) {
                    eq.stars = Math.max(1, item.stars);
                }
                // 装备 award（type=1 + guid）必须在实例之后到：客户端 PlayGameState.cs:7236-7240
                // 直接读 equipmentDetail.cfgData，1406 未到会 NRE（与 grantChoice 同一顺序）
                progress.pushEquips(session, pkt, eqs);
                for (PlayerRecord.Equipment eq : eqs) {
                    awards.add(dump.drawAwardEquip(eq.id));
                }
            }
        }
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        if (got != null && got.gold > 0) {
            progress.pushGold(session, pkt, rec);
        }
        if (got != null && got.diamond > 0) {
            progress.pushDiamond(session, pkt, rec);
        }
        if (got != null && got.stamina > 0) {
            progress.pushStamina(session, pkt, rec);
        }
        if (got != null && got.wnsp > 0) {
            progress.pushWnsp(session, pkt, rec);
        }
        if (got != null && got.yingPo > 0) {
            progress.pushYingPo(session, pkt, rec);
        }
        if (got != null && got.moFaChen > 0) {
            progress.pushMoFaChen(session, pkt, rec);
        }
        if (got != null && got.jjc > 0) {
            progress.pushJjcScore(session, pkt, rec);
        }
        if (got != null && got.xdb > 0 && rec.guild != null) {
            // 兄弟币没有 pushXxx（ProgressService 只有 attri 2/3/4/8/9/15/31），走公会 attri 推送。
            session.send(MsgIds.S2C_UNION_ATTRI_UPDATE, pkt,
                    dump.unionAttriUpdate(2, rec.guild.brotherCoin, ""));
        }
        // 形参顺序与 tag 不一致，务必对齐：1 gold / 2 diamond / 3 stamina / 4 wnsp / 5 yingPo /
        // 6 awards / 7 single / 8 moFaChen / 9 jjc / 10 xdb
        session.send(MsgIds.S2C_OPEN_BAOXIANG, pkt, dump.openBaoXiangRet(
                got == null ? 0 : got.gold,
                got == null ? 0 : got.diamond,
                got == null ? 0 : got.stamina,
                got == null ? 0 : got.wnsp,
                got == null ? 0 : got.yingPo,
                awards, single,
                got == null ? 0 : got.moFaChen,
                got == null ? 0 : got.jjc,
                got == null ? 0 : got.xdb));
    }

    public void onOpenChoiceBaoXiang(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        boolean single = f.getBool(1);
        String ori = f.getString(2);
        int count = Math.max(1, f.getInt(3, 1));
        int choice = f.getInt(4, 0);
        if (!economy.isChoiceBaoXiang(ori) || !progress.hasGoods(rec, ori, count)) {
            return;
        }
        EconomyTables.ChoiceCell cell = economy.choiceCell(ori, choice);
        if (cell == null) {
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        progress.consumeGoods(rec, ori, count);
        progress.markGoods(changed, ori);
        List<byte[]> awards = new ArrayList<>();
        grantChoice(session, pkt, rec, cell, count, changed, awards);
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        // 自选箱（属性 15）的选项全部来自 fillChoiceSchemes() 的物品/装备/武将，没有货币来源，
        // 8 个货币槽保持 0 是正确行为，不要照 onOpenBaoXiang 补货币。
        session.send(MsgIds.S2C_OPEN_BAOXIANG, pkt, dump.openBaoXiangRet(0, 0, 0, 0, 0, awards, single, 0, 0, 0));
    }

    public void onPreChoiceBaoXiang(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        String ori = f.getString(1);
        int count = Math.max(1, f.getInt(2, 1));
        if (!economy.isChoiceBaoXiang(ori)) {
            return;
        }
        session.send(MsgIds.S2C_PRE_CHOICE_BAOXIANG, pkt, dump.preChoiceBaoXiang(ori, count, economy.choiceCells(ori)));
    }

    private void grantChoice(GameSession session, GamePacket pkt, PlayerRecord rec, EconomyTables.ChoiceCell cell,
                             int boxCount, Map<String, Integer> changed, List<byte[]> awards) {
        int n = Math.max(1, cell.amount) * Math.max(1, boxCount);
        if (cell.type == 1) {
            List<PlayerRecord.Equipment> eqs = progress.grantReward(rec, cell.goodId, n, changed);
            progress.pushEquips(session, pkt, eqs);
            for (PlayerRecord.Equipment eq : eqs) {
                awards.add(dump.drawAwardEquip(eq.id));
            }
            return;
        }
        if (cell.type == 2) {
            int index;
            try {
                index = Integer.parseInt(cell.goodId.trim());
            } catch (Exception e) {
                return;
            }
            for (int i = 0; i < n; i++) {
                grantChoiceHero(session, pkt, rec, index, changed, awards);
            }
            return;
        }
        progress.addGoods(rec, cell.goodId, n);
        progress.markGoods(changed, cell.goodId);
        awards.add(dump.drawAwardItem(cell.goodId, n));
    }

    private void grantChoiceHero(GameSession session, GamePacket pkt, PlayerRecord rec, int index,
                                 Map<String, Integer> changed, List<byte[]> awards) {
        if (!cultivate.isPlayableHero(index)) {
            return;
        }
        if (ownsHero(rec, index)) {
            CultivateTables.HeroCfg cfg = cultivate.heroByIndex(index);
            String frag = cfg != null && cfg.fragmentOri != null && !cfg.fragmentOri.isEmpty()
                    ? cfg.fragmentOri : "SP048";
            int n = cultivate.chaiJieFragForHero(index);
            progress.addGoods(rec, frag, n);
            progress.markGoods(changed, frag);
            awards.add(dump.drawAwardHero(index, frag, n));
            return;
        }
        PlayerRecord.Hero wj = new PlayerRecord.Hero();
        wj.heroIndex = index;
        wj.id = PlayerDumpService.guidOf(rec.account, index);
        wj.level = 1;
        CultivateTables.HeroCfg cfg = cultivate.heroByIndex(index);
        wj.stars = cfg == null ? 1 : Math.max(1, cfg.composeStar);
        wj.fightPower = cultivate.computeFightPower(index, wj.level, wj.stars);
        rec.heroes.add(wj);
        session.send(MsgIds.S2C_ADD_WUJIANG, pkt, dump.addWuJiang(wj, false));
        awards.add(dump.drawAwardHero(index, "", 0));
    }

    private static boolean ownsHero(PlayerRecord rec, int index) {
        for (PlayerRecord.Hero wj : rec.heroes) {
            if (wj.heroIndex == index) {
                return true;
            }
        }
        return false;
    }

    public void onTowerInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensureTowerWeeklyReset(rec);
        store.save(rec);
        session.send(MsgIds.S2C_TOWER_INFO, pkt, dump.towerInfo(rec, towerBuddy(rec), towerBuddyOnline(rec)));
    }

    /**
     * C2S 3205：3601 RegionInfo（regionResID+掉落+isServerFight）+ 107。
     * @return true=表 isServerFight，Dispatcher 注册己方 BCT 阵（含装备外置）供 Hurt
     */
    public boolean onTowerFight(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return false;
        }
        ensureTowerWeeklyReset(rec);
        store.save(rec);
        if (rec.level < tables.bctCommon().enableLevel) {
            log.info("{} tower fight refuse enableLv={} have={}", rec.account, tables.bctCommon().enableLevel, rec.level);
            pushTowerState(session, pkt, rec);
            return false;
        }
        int maxLayer = tables.bctMaxLayer();
        if (rec.tower.curLayer > maxLayer) {
            rec.tower.curLayer = maxLayer;
            store.save(rec);
        }
        int layer = Math.max(1, rec.tower.curLayer);
        if (rec.tower.challengeTimes >= tables.bctCommon().freeTimes) {
            log.info("{} tower fight refuse alreadyTZ={} free={}",
                    rec.account, rec.tower.challengeTimes, tables.bctCommon().freeTimes);
            pushTowerState(session, pkt, rec);
            return false;
        }
        GameTables.BctLayer bct = tables.bctLayer(layer);
        if (bct == null || bct.regionResId <= 0) {
            log.warn("{} tower fight missing HundredTowerList layer={}", rec.account, layer);
            pushTowerState(session, pkt, rec);
            return false;
        }
        if (bct.playerLevelLimit > 0 && rec.level < bct.playerLevelLimit) {
            log.info("{} tower fight refuse layerLv={} have={}", rec.account, bct.playerLevelLimit, rec.level);
            pushTowerState(session, pkt, rec);
            return false;
        }
        rec.currentRegionId = bct.regionResId;
        rec.lastResourceRegionType = 0;
        store.save(rec);
        PlayerRecord buddy = towerBuddy(rec);
        int buddyLayer = buddy != null ? Math.max(1, buddy.tower.curLayer) : 0;
        GameTables.ResourceFbPlan rew = tables.bctReward(layer);
        session.send(MsgIds.S2C_TOWER_REGION, pkt,
                dump.towerRegion(bct.regionResId, rew.gold, rew.goods, buddyLayer, bct.serverFight));
        session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(bct.regionResId));
        log.info("{} tower fight layer={} region={} serverFight={} buddyLayer={}",
                rec.account, layer, bct.regionResId, bct.serverFight, buddyLayer);
        return bct.serverFight;
    }

    public void onTowerBuyTimes(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensureTowerWeeklyReset(rec);
        store.save(rec);
        int cost = tables.bctCommon().buyTimesDiamond;
        if (cost <= 0 || rec.diamond < cost) {
            pushTowerState(session, pkt, rec);
            return;
        }
        if (rec.tower.challengeTimes < tables.bctCommon().freeTimes) {
            pushTowerState(session, pkt, rec);
            return;
        }
        rec.diamond -= cost;
        rec.tower.challengeTimes = Math.max(0, rec.tower.challengeTimes - 1);
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        session.send(MsgIds.S2C_TOWER_TIMES, pkt, dump.towerTimes(rec.tower.challengeTimes));
    }

    public void onTowerRank(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        session.send(MsgIds.S2C_TOWER_RANK, pkt, dump.towerRankList(store.all(), rec));
    }

    public void onTowerInvite(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        List<Integer> guids = Pb.read(pkt.body).getInts(1);
        if (guids == null || guids.isEmpty()) {
            return;
        }
        session.send(MsgIds.S2C_TOWER_INVITE, pkt, dump.timeStoneColor(rec.roleName == null ? "" : rec.roleName));
        String time = PlayerDumpService.now();
        String union = rec.guild != null && rec.guild.name != null ? rec.guild.name : "";
        String show = rec.roleName == null ? "" : rec.roleName;
        String link = "[url=2:" + rec.playerId + "_" + show + "][u]" + show + "邀请百层塔[/u][/url]";
        for (Integer g : guids) {
            if (g == null || g.intValue() <= 0 || g.intValue() == rec.playerId) {
                continue;
            }
            PlayerRecord other = store.findByPlayerId(g.intValue());
            if (other == null || other.tower == null) {
                log.info("{} tower invite miss guid={}", rec.account, g);
                continue;
            }
            other.tower.pendingInviteFromGuid = rec.playerId;
            store.save(other);
            GameSession os = sessions.get(other.account);
            if (os == null) {
                continue;
            }
            byte[] chat = dump.chatToCli(3, show, rec.mainHeroIndex, rec.level, link, time,
                    union, rec.playerId, other.playerId, other.roleName == null ? "" : other.roleName);
            os.send(MsgIds.S2C_CHAT_TO_CLI, 0, chat);
        }
        log.info("{} tower invite sent n={}", rec.account, guids.size());
    }

    public void onTowerAccept(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int target = Pb.read(pkt.body).getInt(1, 0);
        if (target <= 0 || target == rec.playerId) {
            pushTowerState(session, pkt, rec);
            return;
        }
        if (rec.tower.pendingInviteFromGuid != 0 && rec.tower.pendingInviteFromGuid != target) {
            log.info("{} tower accept refuse pending={} got={}", rec.account, rec.tower.pendingInviteFromGuid, target);
            pushTowerState(session, pkt, rec);
            return;
        }
        pairTowerBuddy(session, pkt, rec, target);
    }

    public void onTowerUntie(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec != null) {
            PlayerRecord buddy = towerBuddy(rec);
            clearTowerBuddy(rec);
            store.save(rec);
            if (buddy != null) {
                clearTowerBuddy(buddy);
                store.save(buddy);
                GameSession bs = sessions.get(buddy.account);
                if (bs != null) {
                    bs.send(MsgIds.S2C_TOWER_UNTIE, pkt, dump.timeStoneColor(""));
                    bs.send(MsgIds.S2C_TOWER_INFO, pkt, dump.towerInfo(buddy, null, false));
                }
            }
        }
        session.send(MsgIds.S2C_TOWER_UNTIE, pkt, dump.timeStoneColor(""));
    }

    /** 百层通关发奖：按表 `HundredTowerList.txt` 物品_1..10 发（金币列全 0，金币靠 TWBX 金币箱开出）。 */
    private void applyTowerWin(GameSession session, GamePacket pkt, PlayerRecord rec) {
        int layer = Math.max(1, rec.tower.curLayer);
        GameTables.ResourceFbPlan rew = tables.bctReward(layer);
        int gold = rew.gold;
        rec.gold += gold;
        Map<String, Integer> changed = new LinkedHashMap<>();
        List<PlayerRecord.Equipment> newEq = new ArrayList<>();
        for (GameTables.GoodsDrop g : rew.goods) {
            newEq.addAll(progress.grantReward(rec, g.ori, g.count, changed));
        }
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushEquips(session, pkt, newEq);
        if (gold > 0) {
            progress.pushGold(session, pkt, rec);
        }
    }

    private static PlayerRecord.PendingFbReward pendingFromPlan(int sceneId, int level,
                                                               GameTables.ResourceFbPlan plan) {
        PlayerRecord.PendingFbReward p = new PlayerRecord.PendingFbReward();
        p.region = sceneId;
        p.difficult = level;
        p.gold = plan.gold;
        if (plan.goods != null) {
            for (GameTables.GoodsDrop g : plan.goods) {
                if (g != null && g.ori != null && !g.ori.isEmpty() && g.count > 0) {
                    p.drops.add(new PlayerRecord.PendingDrop(g.ori, g.count));
                }
            }
        }
        // 资源挑战没有「首通必掉」，展示列表与发放列表同份（仍置位，免走旧档回退分支）。
        p.displaySplit = true;
        p.displayDrops.addAll(p.drops);
        return p;
    }

    private PlayerRecord.PendingFbReward buildPendingFb(int region, int difficult,
                                                       GameTables.DropRow drop, boolean firstClear) {
        PlayerRecord.PendingFbReward p = new PlayerRecord.PendingFbReward();
        p.region = region;
        p.difficult = difficult;
        p.gold = drop != null ? drop.gold : props.getDungeonGold();
        p.playerExp = drop != null ? drop.playerExp : props.getDungeonPlayerExp();
        p.wjExp = drop != null ? drop.wjExp : props.getDungeonWujiangExp();
        List<GameTables.GoodsDrop> certain = new ArrayList<>();
        List<GameTables.GoodsDrop> rolled = drop != null
                ? drop.roll(rng, firstClear, certain)
                : java.util.Collections.singletonList(
                        new GameTables.GoodsDrop(props.getDropOriName(), props.getDropCount()));
        if (rolled == null) {
            rolled = new ArrayList<>();
        }
        if (rolled.isEmpty() && drop == null) {
            rolled = new ArrayList<>();
            rolled.add(new GameTables.GoodsDrop(props.getDropOriName(), props.getDropCount()));
        }
        for (GameTables.GoodsDrop g : rolled) {
            if (g != null && g.ori != null && !g.ori.isEmpty() && g.count > 0) {
                p.drops.add(new PlayerRecord.PendingDrop(g.ori, g.count));
            }
        }
        maybeAppendWordCharPending(region, p.drops);
        p.wnsp = drop != null ? drop.rollWnsp(rng, firstClear) : 0;
        p.yingPo = drop != null ? drop.rollYingPo(rng, firstClear) : 0;
        // 401 展示列表：扣掉「首通必掉」。客户端在未通关该关时会按 RegionDropList 表的
        // CertainDropGoods1..3 / CertainDropYinPoCount / CertainDropWNSPCount 自己再加一遍
        // （NormalFBGoodsGrant.cs:174-268 结算面板、DropGoodsManager.cs:215-236 局内掉落池），
        // 服务端若也下发同一份，玩家看到的就是背包实收的两倍。
        p.displaySplit = true;
        p.displayDrops.addAll(p.drops);
        for (GameTables.GoodsDrop c : certain) {
            removeOnePendingDrop(p.displayDrops, c.ori, c.count);
        }
        int certainWnsp = firstClear && drop != null ? Math.max(0, drop.firstWnspCount) : 0;
        int certainYingPo = firstClear && drop != null ? Math.max(0, drop.firstYingPoCount) : 0;
        p.displayWnsp = Math.max(0, p.wnsp - certainWnsp);
        p.displayYingPo = Math.max(0, p.yingPo - certainYingPo);
        return p;
    }

    /** 从展示列表里摘掉一条 (ori,count) —— 只摘一次，用于扣掉首通必掉里的那一件。 */
    private static void removeOnePendingDrop(List<PlayerRecord.PendingDrop> list, String ori, int count) {
        if (list == null || ori == null || ori.isEmpty()) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            PlayerRecord.PendingDrop d = list.get(i);
            if (d != null && ori.equals(d.ori) && d.count == count) {
                list.remove(i);
                return;
            }
        }
    }

    private void clearLastResource(PlayerRecord rec) {
        rec.lastResourceRegionType = 0;
    }

    /** 日常：type4=镜像/不可触；type3=致命/圣诞/镰刀（DailyTaskConfig 文案）。 */
    private void noteResourceDaily(GameSession session, GamePacket pkt, PlayerRecord rec, int regionType) {
        if (regionType == RT_MIRROR || regionType == RT_UNTOUCHABLE) {
            task.onDailyAction(session, pkt, rec, TaskService.DAILY_RESOURCE_FB, 1);
        } else if (regionType == RT_DEADLY || regionType == RT_SANTA || regionType == RT_SCYTHE) {
            task.onDailyAction(session, pkt, rec, TaskService.DAILY_MATERIAL_FB, 1);
        }
    }

    private boolean canEnterResource(PlayerRecord rec, int regionType, int level) {
        progress.ensureDaily(rec);
        ensureAllResourceFbSlots(rec);
        if (!tables.resourceFbLevelOpen(regionType, level, rec.level)) {
            return false;
        }
        PlayerRecord.ResourceFb fb = rec.resourceFb.get(Integer.valueOf(regionType));
        int used = fb == null ? 0 : fb.playTime;
        int max = economy.resourceFbMaxPlay(rec.economy.chargedDiamond, regionType);
        if (used >= max) {
            return false;
        }
        return resourceCdReady(rec, regionType);
    }

    private boolean canSaoDangResource(PlayerRecord rec, int regionType, int level) {
        if (!canEnterResource(rec, regionType, level)) {
            return false;
        }
        if (!resourceFbSaoDangAllowed(rec)) {
            return false;
        }
        PlayerRecord.ResourceFb fb = rec.resourceFb.get(Integer.valueOf(regionType));
        if (fb == null || fb.stars == null) {
            return false;
        }
        Integer star = fb.stars.get(Integer.valueOf(level));
        return star != null && star.intValue() >= 3;
    }

    /**
     * 资源挑战（镜像/不可触/致命/圣诞/镰刀）扫荡门 —— 与客户端
     * {@code TiaoZhanNanDu.ConfirmDiffulty}（TiaoZhanNanDu.cs:582-616）同一套判据，口径由登录 detail 下发：
     * 0 = 只按 VipCfg 第 46 列「日常活动扫荡」（现网整列 0 ⇒ 永远锁死，客户端会提示「需要VIP0」）；
     * 1 = 只按账号等级 ≥ {@code ResourceFBSaoDangLevelRequire}；2 = 两者都要。
     * 假服下发 requireType=1 / levelRequire=1（{@code PlayerDumpService#writeDetail}），这里必须同口径，
     * 否则客户端放行、服务端静默只回 450。
     */
    private boolean resourceFbSaoDangAllowed(PlayerRecord rec) {
        int type = PlayerDumpService.RESOURCE_FB_SAO_DANG_REQUIRE_TYPE;
        boolean byVip = economy.resourceFbSaoDangOpen(rec.economy.chargedDiamond);
        boolean byLevel = rec.level >= PlayerDumpService.RESOURCE_FB_SAO_DANG_LEVEL_REQUIRE;
        if (type == 0) {
            return byVip;
        }
        if (type == 1) {
            return byLevel;
        }
        return byVip && byLevel;
    }

    private boolean resourceCdReady(PlayerRecord rec, int regionType) {
        int cd = tables.resourceFbCdSec(regionType);
        if (cd <= 0) {
            return true;
        }
        PlayerRecord.ResourceFb fb = rec.resourceFb.get(Integer.valueOf(regionType));
        if (fb == null || fb.lastPlay == null || fb.lastPlay.isEmpty()) {
            return true;
        }
        try {
            LocalDateTime last = LocalDateTime.parse(fb.lastPlay, PlayerDumpService.TIME);
            return !GameTime.now().isBefore(last.plusSeconds(cd));
        } catch (RuntimeException e) {
            return true;
        }
    }

    private List<GameTables.GoodsDrop> resourceExtraDrops(PlayerRecord rec, int regionType,
                                                         List<GameTables.GoodsDrop> base) {
        List<GameTables.GoodsDrop> extra = new ArrayList<>();
        if (!tables.resourceFbDoubleToday(regionType) || base == null) {
            return extra;
        }
        float rate = economy.resourceFbRate(rec.economy.chargedDiamond, regionType);
        float mul = Math.max(rate, 2f);
        for (GameTables.GoodsDrop g : base) {
            if (g == null || g.ori == null || g.ori.isEmpty() || g.count <= 0) {
                continue;
            }
            int more = (int) Math.floor(g.count * mul + 1e-4f) - g.count;
            if (more > 0) {
                extra.add(new GameTables.GoodsDrop(g.ori, more));
            }
        }
        return extra;
    }

    private void grantResourceExtra(GameSession session, GamePacket pkt, PlayerRecord rec,
                                    List<GameTables.GoodsDrop> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        grantResourceGoods(session, pkt, rec, extra);
        session.send(MsgIds.S2C_RESOURCE_FB_EXT_AWARDS, pkt, dump.resourceFbExtAwards(extra));
    }

    private void grantResourceGoods(GameSession session, GamePacket pkt, PlayerRecord rec,
                                    List<GameTables.GoodsDrop> goods) {
        if (goods == null || goods.isEmpty()) {
            return;
        }
        Map<String, Integer> changed = new LinkedHashMap<>();
        List<PlayerRecord.Equipment> newEq = new ArrayList<>();
        for (GameTables.GoodsDrop g : goods) {
            newEq.addAll(progress.grantReward(rec, g.ori, g.count, changed));
        }
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushEquips(session, pkt, newEq);
    }

    /** 通关取进本预 roll；对不上 region/diff 则重 roll。 */
    private PlayerRecord.PendingFbReward takePendingFb(PlayerRecord rec, int region, int diff,
                                                      GameTables.DropRow drop, boolean firstClear) {
        PlayerRecord.PendingFbReward pending = rec.pendingFbReward;
        rec.pendingFbReward = null;
        if (pending != null && pending.region == region && pending.difficult == diff) {
            return pending;
        }
        return buildPendingFb(region, diff, drop, firstClear);
    }

    /**
     * 主线（region≥1000）胜/扫：约 1/4 掉一张文字卡。
     * 只从「圣诞快乐」WZDH01–04、「新春大吉」WZDH09–12 抽；不掉喜迎元旦/欢闹宵等。
     */
    private static final String[] MAIN_WORD_CHARS = {
            "WZDH01", "WZDH02", "WZDH03", "WZDH04",
            "WZDH09", "WZDH10", "WZDH11", "WZDH12"
    };

    private void maybeAppendWordCharPending(int region, List<PlayerRecord.PendingDrop> drops) {
        if (region < 1000 || drops == null) {
            return;
        }
        if (rng.nextInt(4) != 0) {
            return;
        }
        String ori = MAIN_WORD_CHARS[rng.nextInt(MAIN_WORD_CHARS.length)];
        drops.add(new PlayerRecord.PendingDrop(ori, 1));
    }

    private void maybeAppendWordCharGoods(int region, List<GameTables.GoodsDrop> drops) {
        if (region < 1000 || drops == null) {
            return;
        }
        if (rng.nextInt(4) != 0) {
            return;
        }
        String ori = MAIN_WORD_CHARS[rng.nextInt(MAIN_WORD_CHARS.length)];
        drops.add(new GameTables.GoodsDrop(ori, 1));
    }

    private static List<GameTables.GoodsDrop> toGoodsDrops(PlayerRecord.PendingFbReward pending) {
        if (pending == null) {
            return new ArrayList<>();
        }
        return toGoodsDrops(pending.drops);
    }

    /**
     * 下发 S2C 401 的展示用掉落：**不含首通必掉**（客户端会按本地表自己加，见
     * {@link #buildPendingFb}）。旧档（{@code displaySplit=false}）退回 {@link #toGoodsDrops}
     * 全量 = 老行为。
     */
    private static List<GameTables.GoodsDrop> toDisplayGoodsDrops(PlayerRecord.PendingFbReward pending) {
        if (pending == null) {
            return new ArrayList<>();
        }
        return toGoodsDrops(pending.displaySplit ? pending.displayDrops : pending.drops);
    }

    /** S2C 401 field5：首通那份不重复下发。 */
    private static int displayWnsp(PlayerRecord.PendingFbReward pending) {
        if (pending == null) {
            return 0;
        }
        return pending.displaySplit ? Math.max(0, pending.displayWnsp) : pending.wnsp;
    }

    /** S2C 401 field6：首通那份不重复下发。 */
    private static int displayYingPo(PlayerRecord.PendingFbReward pending) {
        if (pending == null) {
            return 0;
        }
        return pending.displaySplit ? Math.max(0, pending.displayYingPo) : pending.yingPo;
    }

    private static List<GameTables.GoodsDrop> toGoodsDrops(List<PlayerRecord.PendingDrop> drops) {
        List<GameTables.GoodsDrop> out = new ArrayList<>();
        if (drops == null) {
            return out;
        }
        for (PlayerRecord.PendingDrop d : drops) {
            if (d != null && d.ori != null && !d.ori.isEmpty() && d.count > 0) {
                out.add(new GameTables.GoodsDrop(d.ori, d.count));
            }
        }
        return out;
    }

    /** 日/三/五 5:00（TimeNow−5h）：错过重置日补 cur=1，保留 history。登录/心跳也会跑。 */
    public boolean ensureTowerWeeklyReset(PlayerRecord rec) {
        if (rec == null || rec.tower == null) {
            return false;
        }
        java.util.Set<Integer> days = tables.bctCommon().resetDays;
        if (days == null || days.isEmpty()) {
            return false;
        }
        LocalDate today = GameTime.now().minusHours(5).toLocalDate();
        LocalDate latest = null;
        for (int i = 0; i < 8; i++) {
            LocalDate d = today.minusDays(i);
            if (days.contains(Integer.valueOf(d.getDayOfWeek().getValue()))) {
                latest = d;
                break;
            }
        }
        if (latest == null) {
            return false;
        }
        String key = latest.toString();
        if (key.equals(rec.tower.lastResetDate)) {
            return false;
        }
        if (rec.tower.lastResetDate != null && !rec.tower.lastResetDate.isEmpty()) {
            try {
                LocalDate prev = LocalDate.parse(rec.tower.lastResetDate);
                if (!latest.isAfter(prev)) {
                    return false;
                }
            } catch (RuntimeException ignored) {
                // 旧档乱日期：按本次重置日覆盖
            }
        }
        rec.tower.lastResetDate = key;
        rec.tower.curLayer = 1;
        rec.tower.challengeTimes = 0;
        log.info("{} hundred-tower weekly reset date={} history={}", rec.account, key, rec.tower.historyLayer);
        return true;
    }

    private PlayerRecord towerBuddy(PlayerRecord rec) {
        if (rec == null || rec.tower == null) {
            return null;
        }
        if (rec.tower.buddyGuid > 0) {
            PlayerRecord b = store.findByPlayerId(rec.tower.buddyGuid);
            if (b != null && b.playerId != rec.playerId) {
                return b;
            }
        }
        return store.findByRoleName(rec.tower.buddyName);
    }

    private boolean towerBuddyOnline(PlayerRecord rec) {
        PlayerRecord b = towerBuddy(rec);
        return b != null && sessions.get(b.account) != null;
    }

    private void pairTowerBuddy(GameSession session, GamePacket pkt, PlayerRecord rec, int targetGuid) {
        if (targetGuid <= 0 || targetGuid == rec.playerId) {
            return;
        }
        PlayerRecord other = store.findByPlayerId(targetGuid);
        if (other == null) {
            log.info("{} tower buddy miss guid={}", rec.account, targetGuid);
            pushTowerState(session, pkt, rec);
            return;
        }
        if (rec.tower.buddyGuid > 0 && rec.tower.buddyGuid != other.playerId) {
            log.info("{} tower accept refuse already buddy={}", rec.account, rec.tower.buddyGuid);
            pushTowerState(session, pkt, rec);
            return;
        }
        if (other.tower.buddyGuid > 0 && other.tower.buddyGuid != rec.playerId) {
            log.info("{} tower accept refuse target has buddy={}", rec.account, other.tower.buddyGuid);
            pushTowerState(session, pkt, rec);
            return;
        }
        rec.tower.buddyGuid = other.playerId;
        rec.tower.buddyName = other.roleName == null ? "" : other.roleName;
        rec.tower.pendingInviteFromGuid = 0;
        other.tower.buddyGuid = rec.playerId;
        other.tower.buddyName = rec.roleName == null ? "" : rec.roleName;
        other.tower.pendingInviteFromGuid = 0;
        store.save(rec);
        store.save(other);
        GameSession os = sessions.get(other.account);
        session.send(MsgIds.S2C_TOWER_INVITE, pkt, dump.timeStoneColor(other.roleName == null ? "" : other.roleName));
        session.send(MsgIds.S2C_TOWER_INFO, pkt, dump.towerInfo(rec, other, os != null));
        if (os != null) {
            os.send(MsgIds.S2C_TOWER_INVITE, pkt, dump.timeStoneColor(rec.roleName == null ? "" : rec.roleName));
            os.send(MsgIds.S2C_TOWER_INFO, pkt, dump.towerInfo(other, rec, true));
            os.send(MsgIds.S2C_TOWER_BUDDY_LAYER, pkt, dump.towerBuddyLayer(rec.tower.curLayer));
        }
        session.send(MsgIds.S2C_TOWER_BUDDY_LAYER, pkt, dump.towerBuddyLayer(other.tower.curLayer));
        log.info("{} tower buddy pair {} <-> {}", rec.account, rec.playerId, other.playerId);
    }

    private void advanceTowerLayer(PlayerRecord rec) {
        int max = tables.bctMaxLayer();
        if (rec.tower.curLayer < max) {
            rec.tower.curLayer++;
        }
        rec.tower.historyLayer = Math.max(rec.tower.historyLayer, rec.tower.curLayer);
        rec.tower.challengeTimes = 0;
    }

    private void pushResourceFbState(GameSession session, GamePacket pkt, PlayerRecord rec) {
        ensureAllResourceFbSlots(rec);
        session.send(MsgIds.S2C_RESOURCE_FB_UPDATE, pkt, dump.resourceFbUpdate(rec));
    }

    private void pushTowerState(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_TOWER_INFO, pkt, dump.towerInfo(rec, towerBuddy(rec), towerBuddyOnline(rec)));
        session.send(MsgIds.S2C_TOWER_TIMES, pkt, dump.towerTimes(rec.tower.challengeTimes));
    }

    private void clearTowerBuddy(PlayerRecord rec) {
        rec.tower.buddyName = "";
        rec.tower.buddyGuid = 0;
        rec.tower.pendingInviteFromGuid = 0;
    }

    private void notifyTowerBuddyLayer(GamePacket pkt, PlayerRecord rec) {
        PlayerRecord buddy = towerBuddy(rec);
        if (buddy == null) {
            return;
        }
        GameSession bs = sessions.get(buddy.account);
        if (bs != null) {
            bs.send(MsgIds.S2C_TOWER_BUDDY_LAYER, pkt, dump.towerBuddyLayer(rec.tower.curLayer));
        }
    }
}
