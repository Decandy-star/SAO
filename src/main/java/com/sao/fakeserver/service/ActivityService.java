package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.ActExtCfg;
import com.sao.fakeserver.table.ActivityTables;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 活动页：标题目录 / 7日 / 体力 / 首冲(type2) / 每日累计充值(type4) / 消耗返利(type6) / 神域魔盒(type22)。
 * 看板 titleList(S2C 2601) 来源 = {@link ActExtCfg#titles()}（activities.json 过滤 enabled），
 * 新增活动只需在配置与 {@code MessageDispatcher} 挂 handler，本类内实现业务。
 */
@Service
public class ActivityService {
    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final ActivityTables tables;
    private final CultivateTables cultivate;
    private final ActExtCfg actExt;
    private final EconomyTables economy;
    private final SessionHub sessions;

    public ActivityService(PlayerStore store, PlayerDumpService dump, ProgressService progress,
                           ActivityTables tables, CultivateTables cultivate, ActExtCfg actExt,
                           EconomyTables economy, SessionHub sessions) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.tables = tables;
        this.cultivate = cultivate;
        this.actExt = actExt;
        this.economy = economy;
        this.sessions = sessions;
    }

    /** 启动即确定当天壕送大礼 heroFragment 定档（全服同一份）；跨日由公共调度再调。 */
    @PostConstruct
    public void initHeroFragmentPlan() {
        rollHeroFragmentPlanForToday();
    }

    /**
     * 壕送大礼：为当天各档 heroFragment 预 roll 全服共用碎片。
     * 启动与 {@link GlobalServerScheduler} 每日 0 点调用；已定档则 no-op。
     */
    public void rollHeroFragmentPlanForToday() {
        ensureHeroPlanNow();
    }

    // ---------------- 基座：目录与通用 ----------------

    /** 进大厅 / 打开活动页：推 S2C 2601（标题来自 activities.json）。 */
    public void pushStatus(GameSession session, GamePacket pkt) {
        pushStatus(session, pkt == null ? 0 : pkt.serial);
    }

    public void pushStatus(GameSession session, int serial) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        store.save(rec);
        session.send(MsgIds.S2C_ACTIVITY_STATUS, serial, dump.activityStatus(rec, tables, actExt.titles()));
        log.info("{} activity status 7day={}/{} today={} vpActive={} gained={}",
                rec.account, rec.activity.day7Count, 7, rec.activity.today7Day,
                tables.activeVpType(GameTime.localTime()), rec.activity.gainedVpTypes);
    }

    /**
     * 0 点（跨日）对在线号重推 2601。
     *
     * <p>客户端 {@code ActivityStatusInfo}（7 日签到态 / 今日体力档 / 各活动已领串）只在 2601 到达时写入
     * （{@code ActivityStatusInfo.cs:510-679}），**没有本地跨日重置**；大厅活动红点
     * {@code DaTingMainUISystem.SetActivityTiShi(...IsCanGainActivity())} 就按它判，
     * 而客户端只在打开活动页时才发 2318（{@code ActivityMainUI.cs:464}）→ 在线跨夜的号不重推
     * 则红点与「已领」态停在前一天，直到玩家自己打开一次活动页。与 attri 17/18、3702、3901
     * 的 0 点补推同性质。
     */
    public void pushStatusToOnline() {
        int n = 0;
        for (GameSession session : sessions.onlineSnapshot()) {
            PlayerRecord rec = session.player();
            if (rec == null) {
                continue;
            }
            pushStatus(session, 0);
            n++;
        }
        if (n > 0) {
            log.info("activity status clock push online={}", Integer.valueOf(n));
        }
    }

    public void onQuery(GameSession session, GamePacket pkt) {
        pushStatus(session, pkt);
    }

    // ---------------- type0/type1：7日与体力（原实现保留） ----------------

    /** C2S 2301：领当日七日登录奖 → S2C 2602，并再推 2601。 */
    public void on7Day(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        if (rec.activity.today7Day || rec.activity.day7Count >= 7) {
            return;
        }
        int nextDay = rec.activity.day7Count + 1;
        ActivityTables.DayRow row = tables.dayFor(rec.mainRoleIndex, nextDay);
        if (row == null) {
            return;
        }
        Map<String, Integer> changed = new LinkedHashMap<>();
        List<String> equipGuids = new ArrayList<>();
        List<String> itemOris = new ArrayList<>();
        List<Integer> itemCounts = new ArrayList<>();
        if (row.rmb > 0) {
            rec.diamond += row.rmb;
        }
        if (row.gold > 0) {
            rec.gold += row.gold;
        }
        for (ActivityTables.Award a : row.awards) {
            if (cultivate.equip(a.ori) != null) {
                for (int i = 0; i < a.count; i++) {
                    PlayerRecord.Equipment eq = progress.grantEquip(rec, a.ori);
                    eq.stars = a.stars;
                    equipGuids.add(eq.id);
                    session.send(MsgIds.S2C_ADD_EQUIP, pkt, dump.equipment(eq));
                }
            } else {
                progress.addGoods(rec, a.ori, a.count);
                progress.markGoods(changed, a.ori);
                itemOris.add(a.ori);
                itemCounts.add(Integer.valueOf(a.count));
            }
        }
        rec.activity.day7Count = nextDay;
        rec.activity.today7Day = true;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        if (row.gold > 0) {
            progress.pushGold(session, pkt, rec);
        }
        if (row.rmb > 0) {
            progress.pushDiamond(session, pkt, rec);
        }
        session.send(MsgIds.S2C_ACTIVITY_7DAY_RET, pkt, dump.activity7DayRet(row.rmb, row.gold, equipGuids, itemOris, itemCounts));
        pushStatus(session, pkt);
        log.info("{} activity 7day claim day={}", rec.account, nextDay);
    }

    /** C2S 2302：领当前时段体力 → S2C 2603，并再推 2601。 */
    public void onVp(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        int type = tables.activeVpType(GameTime.localTime());
        if (type == 0 || rec.activity.gainedVpTypes.contains(Integer.valueOf(type))) {
            session.send(MsgIds.S2C_ACTIVITY_VP_RET, pkt, dump.activityVpRet(1, type, 0));
            return;
        }
        ActivityTables.VpRow row = tables.vp(type);
        if (row == null) {
            return;
        }
        rec.stamina += row.awardVp;
        rec.activity.gainedVpTypes.add(Integer.valueOf(type));
        store.save(rec);
        progress.pushPlayerProgress(session, pkt, rec, false, false, true, false, false);
        session.send(MsgIds.S2C_ACTIVITY_VP_RET, pkt, dump.activityVpRet(0, type, row.awardVp));
        pushStatus(session, pkt);
        log.info("{} activity vp type={} +{}", rec.account, type, row.awardVp);
    }

    // ---------------- type2 首冲大回馈 ----------------

    /** C2S 2316 → S2C 2618：下发最多 3 档配置。 */
    public void onFirstChongZhiQuery(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        sendFirstChongZhiQuery(session, pkt, rec);
    }

    private void sendFirstChongZhiQuery(GameSession session, GamePacket pkt, PlayerRecord rec) {
        List<byte[]> items = new ArrayList<>();
        for (ActExtCfg.FirstChargeTier t : actExt.firstChargeTiers()) {
            if (t == null) {
                continue;
            }
            items.add(encodeFirstTier(t));
            if (items.size() >= 3) {
                break;
            }
        }
        session.send(MsgIds.S2C_FIRST_CHONGZHI_QUERY_RET, pkt, dump.firstChongZhiQueryRet(items));
        log.info("{} first chongzhi query tiers={}", rec.account, items.size());
    }

    /**
     * C2S 2317 → S2C 2615：已首充且未领则发对应档奖励。
     * 档位选取对齐客户端 OnGet1stAwardSuc：while (rmb > mJE) i++。
     */
    public void onFirstChongZhiAward(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        if (rec.economy.hasGet1stChongZhiAward) {
            log.info("{} first chongzhi award already", rec.account);
            sendFirstChongZhiQuery(session, pkt, rec);
            return;
        }
        int curRmb = cur1stChongZhiRmb(rec);
        if (curRmb <= 0) {
            log.info("{} first chongzhi award deny no pay", rec.account);
            sendFirstChongZhiQuery(session, pkt, rec);
            return;
        }
        List<ActExtCfg.FirstChargeTier> tiers = actExt.firstChargeTiers();
        if (tiers.isEmpty()) {
            sendFirstChongZhiQuery(session, pkt, rec);
            return;
        }
        int idx = 0;
        while (idx < tiers.size() && curRmb > tiers.get(idx).mJE) {
            idx++;
        }
        if (idx > tiers.size() - 1) {
            idx = tiers.size() - 1;
        }
        ActExtCfg.FirstChargeTier tier = tiers.get(idx);
        Map<String, Integer> changed = new LinkedHashMap<>();
        if (tier.mRmb > 0) {
            rec.diamond += tier.mRmb;
            progress.pushDiamond(session, pkt, rec);
        }
        if (tier.mJinBi > 0) {
            rec.gold += tier.mJinBi;
            progress.pushGold(session, pkt, rec);
        }
        List<PlayerRecord.Equipment> newEq = new ArrayList<>();
        if (tier.goods != null) {
            for (ActExtCfg.FirstChargeGoods g : tier.goods) {
                if (g == null || g.count <= 0 || g.ori == null || g.ori.isEmpty() || "0".equals(g.ori)) {
                    continue;
                }
                // 首槽是翅膀装备（客户端 ActivityMainUI.cs:1773-1778 硬要求 equipType=4，无判空）：
                // 装备必须发实例 + 1406 入包，当道具塞背包会变成查不到的 ori
                if (cultivate.equip(g.ori) != null) {
                    for (int i = 0; i < g.count; i++) {
                        PlayerRecord.Equipment eq = progress.grantEquip(rec, g.ori);
                        eq.stars = Math.max(1, g.stars);
                        newEq.add(eq);
                    }
                } else {
                    progress.addGoods(rec, g.ori, g.count);
                    progress.markGoods(changed, g.ori);
                }
            }
        }
        rec.economy.hasGet1stChongZhiAward = true;
        store.save(rec);
        if (!changed.isEmpty()) {
            progress.pushGoods(session, pkt, rec, changed);
        }
        for (PlayerRecord.Equipment eq : newEq) {
            session.send(MsgIds.S2C_ADD_EQUIP, pkt, dump.equipment(eq));
        }
        // 2615 body 空；客户端本地置 mHasGet1stChongZhiAward 并弹提示
        session.send(MsgIds.S2C_FIRST_CHONGZHI_AWARD_RET, pkt, new byte[0]);
        log.info("{} first chongzhi award mJE={} curRmb={}", rec.account, tier.mJE, curRmb);
    }

    private byte[] encodeFirstTier(ActExtCfg.FirstChargeTier t) {
        List<String> oris = new ArrayList<>();
        List<Integer> stars = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<Boolean> shining = new ArrayList<>();
        if (t.goods != null) {
            for (ActExtCfg.FirstChargeGoods g : t.goods) {
                if (g == null || g.ori == null || g.ori.isEmpty() || "0".equals(g.ori) || g.count <= 0) {
                    continue;
                }
                oris.add(g.ori);
                stars.add(Integer.valueOf(g.stars));
                counts.add(Integer.valueOf(g.count));
                shining.add(Boolean.valueOf(g.shining));
                if (oris.size() >= 4) {
                    break;
                }
            }
        }
        return dump.firstChongZhiItem(t.mJE, t.mJinBi, t.mRmb, oris, stars, counts, shining);
    }

    /**
     * 对齐 UserPayCfg.GetCur1stChongZhiRMB：已购且 ID≤msMax1stID 的最大 rmb；
     * 若只购了常规 8+，用 id-7 映射到首充表行。
     */
    private int cur1stChongZhiRmb(PlayerRecord rec) {
        if (rec.economy.payBuyCounts == null || rec.economy.payBuyCounts.isEmpty()) {
            return 0;
        }
        int max1st = economy.max1stPayGoodsId();
        int best = 0;
        for (Map.Entry<Integer, Integer> e : rec.economy.payBuyCounts.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().intValue() <= 0) {
                continue;
            }
            int id = e.getKey().intValue();
            int lookup = id;
            if (id > max1st && id <= max1st + 7) {
                lookup = id - 7;
            }
            if (lookup <= 0 || lookup > max1st) {
                continue;
            }
            EconomyTables.PayRow row = economy.pay(lookup);
            if (row == null) {
                row = economy.pay(id);
            }
            if (row != null && row.rmb > best) {
                best = row.rmb;
            }
        }
        return best;
    }

    // ---------------- 共用：档位奖励发放 ----------------

    /**
     * 发一个档位：钻石/金币直接加，物品逐个发；heroFragment 用服务端当天全局预定的碎片
     * （heroFragmentPlan，全服同一份，启动/跨天时确定一次），不现场随机。
     * 返回背包变化 map（null=无变化），上层决定是否 pushGoods。
     */
    private Map<String, Integer> grantTier(GameSession session, GamePacket pkt, PlayerRecord rec,
                                           ActExtCfg.PayTier tier) {
        Map<String, Integer> changed = new LinkedHashMap<>();
        if (tier.zuanShiAward > 0) {
            rec.diamond += tier.zuanShiAward;
            progress.pushDiamond(session, pkt, rec);
        }
        if (tier.jinbiAward > 0) {
            rec.gold += tier.jinbiAward;
            progress.pushGold(session, pkt, rec);
        }
        if (tier.items != null) {
            for (int i = 0; i < tier.items.size(); i++) {
                ActExtCfg.PayItem it = tier.items.get(i);
                if (it == null || it.count <= 0) {
                    continue;
                }
                String ori = chongZhiHeroOri(tier.rmb, i, it);
                if (ori == null || ori.isEmpty() || "0".equals(ori)) {
                    continue;
                }
                progress.addGoods(rec, ori, it.count);
                progress.markGoods(changed, ori);
            }
        }
        return changed;
    }

    // ---------------- type4 壕送大礼 = 每日累计充值（A/B 每日轮换） ----------------

    /** 当天全局 heroFragment 定档（key=rmb+"#"+items 下标）。全服玩家共享同一份，非每人 roll。 */
    private final Map<String, String> heroFragmentPlan = new ConcurrentHashMap<>();
    private final Object heroPlanLock = new Object();
    private volatile String heroPlanDate = "";

    /** 若当天尚未定档（启动后首次 / 跨天），按当天生效方案把各档 heroFragment 预 roll 成
     *  具体英雄碎片存入全局 plan。此后全服查询展示与领奖发放均读同一份，不现场随机。 */
    private void ensureHeroPlanNow() {
        String today = GameTime.today().toString();
        if (today.equals(heroPlanDate)) {
            return;
        }
        synchronized (heroPlanLock) {
            if (today.equals(heroPlanDate)) {
                return;
            }
            ActExtCfg.DayPayScheme scheme = actExt.activeScheme(GameTime.today());
            heroFragmentPlan.clear();
            if (scheme != null && scheme.tiers != null) {
                for (ActExtCfg.PayTier t : scheme.tiers) {
                    if (t == null || t.rmb <= 0 || t.items == null) {
                        continue;
                    }
                    for (int i = 0; i < t.items.size(); i++) {
                        ActExtCfg.PayItem it = t.items.get(i);
                        if (it == null || it.count <= 0 || !it.heroFragment) {
                            continue;
                        }
                        heroFragmentPlan.put(t.rmb + "#" + i,
                                actExt.rollHeroFragmentOri(ThreadLocalRandom.current(), it.minStar));
                    }
                }
            }
            heroPlanDate = today;
            log.info("act hero-fragment plan date={} scheme={} frags={}", today,
                    scheme == null ? "-" : scheme.id, heroFragmentPlan.size());
        }
    }

    /** heroFragment 档位返回当天全局预定碎片；普通 ori 原样返回。 */
    private String chongZhiHeroOri(int rmb, int index, ActExtCfg.PayItem it) {
        if (!it.heroFragment) {
            return it.ori;
        }
        ensureHeroPlanNow();
        return heroFragmentPlan.get(rmb + "#" + index);
    }

    /** C2S 2312 查询 → S2C 2611：按当天生效方案回各档 + 当日已领串。 */
    public void onDailyChongZhiQuery(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        ActExtCfg.DayPayScheme scheme = actExt.activeScheme(GameTime.today());
        List<byte[]> tiers = new ArrayList<>();
        if (scheme != null && scheme.tiers != null) {
            for (ActExtCfg.PayTier t : scheme.tiers) {
                if (t == null || t.rmb <= 0) {
                    continue;
                }
                List<byte[]> items = new ArrayList<>();
                if (t.items != null) {
                    for (int i = 0; i < t.items.size(); i++) {
                        ActExtCfg.PayItem it = t.items.get(i);
                        if (it == null || it.count <= 0) {
                            continue;
                        }
                        // 下发全服当天预定好的固定碎片（查询与领奖一致），面板不再每次随机变化
                        String ori = chongZhiHeroOri(t.rmb, i, it);
                        if (ori == null || ori.isEmpty() || "0".equals(ori)) {
                            continue;
                        }
                        items.add(dump.shiningAward(ori, it.count, it.shining));
                    }
                }
                tiers.add(dump.dailyChongZhiTier(t.rmb, t.zuanShiAward, t.jinbiAward, items));
            }
        }
        session.send(MsgIds.S2C_DAILY_CHONGZHI_QUERY_RET, pkt,
                dump.dailyChongZhiRet(rec.activity.dailyChongZhiAwarded, tiers));
        log.info("{} daily chongzhi query scheme={} tiers={}", rec.account,
                scheme == null ? "-" : scheme.id, tiers.size());
    }

    /** C2S 2313 领奖 → S2C 2612：校验当日达标且未领后发奖。 */
    public void onDailyChongZhiAward(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        int rmb = Pb.read(pkt.body).getInt(1, 0);
        ActExtCfg.DayPayScheme scheme = actExt.activeScheme(GameTime.today());
        ActExtCfg.PayTier tier = findTier(scheme, rmb);
        if (tier == null || rec.economy.curDayChongZhiRmb < rmb) {
            log.info("{} daily chongzhi award deny rmb={} cur={} scheme={}", rec.account, rmb,
                    rec.economy.curDayChongZhiRmb, scheme == null ? "-" : scheme.id);
            return;
        }
        if (rec.activity.dailyChongZhiAwarded.contains("|" + rmb + "|")) {
            log.info("{} daily chongzhi award already rmb={}", rec.account, rmb);
            return;
        }
        Map<String, Integer> changed = grantTier(session, pkt, rec, tier);
        rec.activity.dailyChongZhiAwarded += "|" + rmb + "|";
        store.save(rec);
        if (!changed.isEmpty()) {
            progress.pushGoods(session, pkt, rec, changed);
        }
        session.send(MsgIds.S2C_DAILY_CHONGZHI_AWARD_RET, pkt,
                dump.dailyChongZhiAwardRet(rmb, rec.activity.dailyChongZhiAwarded));
        log.info("{} daily chongzhi award rmb={} scheme={}", rec.account, rmb, scheme.id);
    }

    // ---------------- type6 消耗返利（每日钻石消耗档位） ----------------

    /** C2S 2314 查询 → S2C 2613。结构与 2611 同构（CCMsgDailyUseZuanShiAwardItem 字段同名）。 */
    public void onDailyCostQuery(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        ActExtCfg.DayPayScheme scheme = actExt.activeCostScheme(GameTime.today());
        List<byte[]> tiers = new ArrayList<>();
        if (scheme != null && scheme.tiers != null) {
            for (ActExtCfg.PayTier t : scheme.tiers) {
                if (t == null || t.rmb <= 0) {
                    continue;
                }
                List<byte[]> items = new ArrayList<>();
                if (t.items != null) {
                    for (ActExtCfg.PayItem it : t.items) {
                        if (it == null || it.count <= 0) {
                            continue;
                        }
                        items.add(dump.shiningAward(it.ori, it.count, it.shining));
                    }
                }
                tiers.add(dump.dailyChongZhiTier(t.rmb, t.zuanShiAward, t.jinbiAward, items));
            }
        }
        session.send(MsgIds.S2C_DAILY_COST_QUERY_RET, pkt,
                dump.dailyChongZhiRet(rec.activity.dailyCostAwarded, tiers));
        log.info("{} daily cost query tiers={}", rec.account, tiers.size());
    }

    /** C2S 2315 领奖 → S2C 2614：当日耗钻达标且未领 → 发档位奖励。 */
    public void onDailyCostAward(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        int rmb = Pb.read(pkt.body).getInt(1, 0);
        ActExtCfg.DayPayScheme scheme = actExt.activeCostScheme(GameTime.today());
        ActExtCfg.PayTier tier = findTier(scheme, rmb);
        if (tier == null || rec.economy.curDayCostZuanShi < rmb) {
            log.info("{} daily cost award deny rmb={} cur={}", rec.account, rmb,
                    rec.economy.curDayCostZuanShi);
            return;
        }
        if (rec.activity.dailyCostAwarded.contains("|" + rmb + "|")) {
            log.info("{} daily cost award already rmb={}", rec.account, rmb);
            return;
        }
        Map<String, Integer> changed = grantTier(session, pkt, rec, tier);
        rec.activity.dailyCostAwarded += "|" + rmb + "|";
        store.save(rec);
        if (!changed.isEmpty()) {
            progress.pushGoods(session, pkt, rec, changed);
        }
        session.send(MsgIds.S2C_DAILY_COST_AWARD_RET, pkt,
                dump.dailyChongZhiAwardRet(rmb, rec.activity.dailyCostAwarded));
        log.info("{} daily cost award rmb={}", rec.account, rmb);
    }

    private static ActExtCfg.PayTier findTier(ActExtCfg.DayPayScheme scheme, int rmb) {
        if (scheme == null || scheme.tiers == null) {
            return null;
        }
        for (ActExtCfg.PayTier t : scheme.tiers) {
            if (t != null && t.rmb == rmb) {
                return t;
            }
        }
        return null;
    }

    // ---------------- type22 神域魔盒 ----------------

    /** C2S 4401 查询 → S2C 4901：跨日换组/计数清零后回当前 9 格。 */
    public void onMagicBoxQuery(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        magicBoxEnsureDay(rec);
        store.save(rec);
        sendMagicBoxInfo(session, pkt, rec);
        log.info("{} magic box query state={} today={}", rec.account,
                rec.activity.magicBox.xiPaiState, rec.activity.magicBox.countToday);
    }

    /** C2S 4402 重置 → S2C 4901：花钻（可配 0=免费）重 roll 一组展示，翻倍计数归 0。 */
    public void onMagicBoxReset(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        magicBoxEnsureDay(rec);
        ActExtCfg.MagicFile cfg = actExt.magic();
        int cost = Math.max(0, cfg.resetCostDiamond);
        if (cost > 0) {
            if (rec.diamond < cost) {
                log.info("{} magic box reset deny need={} have={}", rec.account, cost, rec.diamond);
                return;
            }
            rec.diamond -= cost;
            progress.pushDiamond(session, pkt, rec);
            progress.addTodayCost(session, pkt, rec, cost);
        }
        rollMagicBoxRound(rec, cfg);
        store.save(rec);
        sendMagicBoxInfo(session, pkt, rec);
        log.info("{} magic box reset cost={}", rec.account, cost);
    }

    /** C2S 4403 洗牌 → S2C 4901 + 4903：仅新牌(XiPaiState=1)可洗；生成随机位置映射后盖牌。 */
    public void onMagicBoxXiPai(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        magicBoxEnsureDay(rec);
        PlayerRecord.MagicBox mb = rec.activity.magicBox;
        if (mb.xiPaiState != 1 || mb.prize.size() < 9) {
            log.info("{} magic box xipai skip state={} n={}", rec.account, mb.xiPaiState, mb.prize.size());
            return;
        }
        // 随机排列 0..8 -> 物理位卡背对应真实奖品下标
        List<Integer> perm = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            perm.add(Integer.valueOf(i));
        }
        Collections.shuffle(perm, ThreadLocalRandom.current());
        mb.cardPos.clear();
        for (int pos = 0; pos < 9; pos++) {
            PlayerRecord.MbCardPos cp = new PlayerRecord.MbCardPos();
            cp.cardPos = pos;
            cp.realPos = perm.get(pos).intValue();
            mb.cardPos.add(cp);
        }
        mb.xiPaiState = 0;
        store.save(rec);
        // 先 4901 带新 CardPos 重建卡片（全背面），再 4903 触发盖牌动画
        sendMagicBoxInfo(session, pkt, rec);
        session.send(MsgIds.S2C_MAGIC_BOX_XIPAI_RET, pkt, dump.magicBoxXiPaiRet(0));
        log.info("{} magic box xipai done", rec.account);
    }

    /** C2S 4404 开奖 → S2C 4902：洗牌态才可翻；先魔瓶后钻石兜底；开出即 state=1 记记录。 */
    public void onMagicBoxGivePrize(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        magicBoxEnsureDay(rec);
        PlayerRecord.MagicBox mb = rec.activity.magicBox;
        ActExtCfg.MagicFile cfg = actExt.magic();
        if (mb.xiPaiState != 0 || mb.prize.size() < 9) {
            log.info("{} magic box give deny state={} n={}", rec.account, mb.xiPaiState, mb.prize.size());
            return;
        }
        // 缺省必须是 0，不能拿 -1 当哨兵：客户端 Pos 声明为 [DefaultValue(0)]
        // [ProtoMember(1, IsRequired = false)]（Msg.dll 生成类，见
        // pyfoot\tmp_msgdll\NetProto\CCMsgRequestMagicBoxGivePrize.cs:15-16），
        // protobuf-net 对等于默认值的成员不写盘 → 翻第 0 张牌时 field1 根本不出现。
        // 客户端牌位 mPosID 是 0..8（MagicBox.cs:43 new Card(this, k-1)，k=1..9），
        // 用 -1 兜底会把「翻第一张」整条路径判成非法。
        int pos = Pb.read(pkt.body).getInt(1, 0);
        if (pos < 0 || pos >= 9) {
            log.info("{} magic box give deny pos={}", rec.account, pos);
            return;
        }
        int realPos = realPosOf(mb, pos);
        PlayerRecord.MbPrize p = mb.prize.get(realPos);
        if (p == null || p.state == 1) {
            log.info("{} magic box give deny realPos={} opened", rec.account, realPos);
            return;
        }
        // 本次应扣魔瓶 = 档位 base * 2^当日已翻数（第一抽 1 个）
        int goodsNeed = magicBoxCost(mb.countToday, cfg);
        int paidGoods = 0;
        Map<String, Integer> changed = new LinkedHashMap<>();
        if (progress.bagCount(rec, cfg.goodsOri) >= goodsNeed) {
            progress.consumeGoods(rec, cfg.goodsOri, goodsNeed);
            progress.markGoods(changed, cfg.goodsOri);
            paidGoods = goodsNeed;
        } else {
            // 魔瓶不足：钻石固定兜底
            if (rec.diamond < cfg.diamondCost) {
                log.info("{} magic box give deny no goods {} need={} no diamond", rec.account,
                        cfg.goodsOri, goodsNeed);
                return;
            }
            rec.diamond -= cfg.diamondCost;
            progress.pushDiamond(session, pkt, rec);
            progress.addTodayCost(session, pkt, rec, cfg.diamondCost);
        }
        // 发奖：装备给实例，物品进背包
        if (p.type == 1) {
            for (int i = 0; i < Math.max(1, p.count); i++) {
                PlayerRecord.Equipment eq = progress.grantEquip(rec, p.goodsname);
                eq.stars = Math.max(1, p.star);
                session.send(MsgIds.S2C_ADD_EQUIP, pkt, dump.equipment(eq));
            }
        } else {
            progress.addGoods(rec, p.goodsname, p.count);
            progress.markGoods(changed, p.goodsname);
        }
        p.state = 1;
        mb.countToday++;
        PlayerRecord.MbRecord r = new PlayerRecord.MbRecord();
        r.quality = p.quality;
        r.type = p.type;
        r.goodsname = p.goodsname;
        r.count = p.count;
        r.star = p.star;
        mb.record.add(0, r);
        int keep = Math.max(1, cfg.recordKeep);
        while (mb.record.size() > keep) {
            mb.record.remove(mb.record.size() - 1);
        }
        store.save(rec);
        if (!changed.isEmpty()) {
            progress.pushGoods(session, pkt, rec, changed);
        }
        List<byte[]> recBytes = new ArrayList<>();
        recBytes.add(dump.magicBoxRecord(mb.record.get(0), rec.roleName));
        // 回包 CostGoodsNum = 下一抽应付（客户端点下一张牌时弹框用它做缓存）
        int nextCost = magicBoxCost(mb.countToday, cfg);
        // CostRMB 是「下一抽的钻石价」而非本抽实付：客户端 MagicBox.cs:111 缓存它，
        // :1050 用它做余额校验（魔瓶支付时实付为 0 → 校验恒不触发，点下一张无反馈）
        session.send(MsgIds.S2C_MAGIC_BOX_GIVE_PRIZE_RET, pkt,
                dump.magicBoxGivePrize(realPos, pos, cfg.diamondCost, nextCost, recBytes));
        // 开奖后再补一份 4901（真服样式：服务端权威状态回灌）。客户端 4902 处理器
        // （MagicBox.cs:108-131 GetPrize）只做三件事：补 CardPos 映射、缓存下一抽价格、翻这张牌的动画，
        // **不会**把 prize[].state 置 1，也不刷新记录/洗牌态 → 本地模型停在「这张牌没开过」。
        // 补发 4901 让本地模型与存档一致（重复点同一张牌时服务端 :652 会拒，客户端拿不到回包）。
        // 安全性：4901 处理器 UpdateMagicBoxCfg（ActivityPropertyMgr.cs:832-844）只赋值 + 发
        // EN_REFRESH_MAGICBOX，而该事件在 ActivityMainUI.cs:426(IL_0CE2) 只调 RefreshExchangeGoodsStatus()，
        // 不重建卡牌列表，因此不会打断 :117 的翻牌动画。
        sendMagicBoxInfo(session, pkt, rec);
        log.info("{} magic box give pos={} real={} goods={} type={} {} x{} stateNow={}",
                rec.account, pos, realPos, paidGoods, p.type, p.goodsname, p.count, mb.countToday);
    }

    /** 当日检：跨日翻倍计数清 0；refreshDaily 时自动换新一组展示。 */
    private void magicBoxEnsureDay(PlayerRecord rec) {
        rec.ensureCollections();
        PlayerRecord.MagicBox mb = rec.activity.magicBox;
        ActExtCfg.MagicFile cfg = actExt.magic();
        String today = GameTime.today().toString();
        if (today.equals(mb.dateKey)) {
            return;
        }
        mb.countToday = 0;
        if (cfg.refreshDaily || mb.prize.isEmpty()) {
            rollMagicBoxRound(rec, cfg);
        } else {
            mb.dateKey = today;
        }
    }

    /**
     * 魔盒 {@code quality} 上线前转成「档位」：1 = 最高档，与物品真实品质（1 白…5 金）**相反**。
     *
     * <p>APK 原始程序集实测（{@code decompiled\_cache\verify_base\client_asm.dll}，从 {@code base.apk} 现抠）：
     * {@code MobileGameDemo\MagicBox.cs:353} {@code SetActive(ccmsgMagicBoxPrize.quality <= 2)} 挂在
     * 高品质装备特效节点 {@code Goods/GaoPingZhiZhuangBeiXiaoGuo} 上、{@code :548}
     * {@code if (1 == ccmsgMagicBoxPrize.quality)} 才播品质粒子（粒子贴图按 {@code goodsname} 的**本地真实品质**
     * 1..5 取），{@code ViewMagicCardUI.cs:137} 同款 {@code <= 2}；而同一个特效节点在 APK 别处一律按真实品质
     * {@code >= 4} 打开（{@code BagUISystem.cs:2345}、{@code LunPanChouJiangUI.cs:199}、
     * {@code BuyTreasureSystem.cs:1087}）→ 该字段是降序档位：1 = 金（特效+粒子）、2 = 紫（特效）、≥3 无特效。
     * 假服原先直接把真实品质 3/4/5 上线，{@code <= 2} 与 {@code == 1} 永不成立 → 金/紫/蓝三档全无特效。
     */
    private static int mbWireQuality(int realQuality) {
        return 6 - realQuality;
    }

    /** 生成全新 9 格（金/紫/蓝按配置数量+随机位置），洗牌态=1、翻倍归 0。 */
    private void rollMagicBoxRound(PlayerRecord rec, ActExtCfg.MagicFile cfg) {
        List<PlayerRecord.MbPrize> list = new ArrayList<>();
        String[] keys = {"gold", "purple", "blue"};
        for (String k : keys) {
            ActExtCfg.MbGridCfg gc = cfg.grids.get(k);
            if (gc == null || gc.count <= 0) {
                continue;
            }
            for (int i = 0; i < gc.count; i++) {
                ActExtCfg.MbCand cand = actExt.rollMbCand(gc.quality, ThreadLocalRandom.current());
                PlayerRecord.MbPrize p = new PlayerRecord.MbPrize();
                p.quality = mbWireQuality(cand.quality);
                p.type = cand.type;
                p.goodsname = cand.ori;
                // 装备一格一件；物品按配置数量区间
                p.count = cand.type == 1 ? 1 : randBetween(gc.countMin, gc.countMax);
                p.star = cand.type == 1 ? 1 : 0;
                p.state = 0;
                list.add(p);
            }
        }
        Collections.shuffle(list, ThreadLocalRandom.current());
        PlayerRecord.MagicBox mb = rec.activity.magicBox;
        mb.prize = list;
        mb.xiPaiState = 1;
        mb.countToday = 0;
        mb.cardPos.clear();
        mb.dateKey = GameTime.today().toString();
    }

    private static int randBetween(int min, int max) {
        int lo = Math.min(min, max);
        int hi = Math.max(min, max);
        if (hi <= lo) {
            return lo;
        }
        return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }

    /** 第 n 抽（countToday 已抽张数）应付魔瓶数 = base * 2^n；n 从 0 起（第一抽 base）。 */
    private static int magicBoxCost(int countToday, ActExtCfg.MagicFile cfg) {
        int base = Math.max(1, cfg.costGoodsBase);
        if (!cfg.doublePerDraw) {
            return base;
        }
        int shift = Math.max(0, countToday);
        return base << Math.min(shift, 20);
    }

    private static int realPosOf(PlayerRecord.MagicBox mb, int cardPos) {
        if (mb.cardPos != null) {
            for (PlayerRecord.MbCardPos cp : mb.cardPos) {
                if (cp != null && cp.cardPos == cardPos) {
                    return cp.realPos;
                }
            }
        }
        return cardPos;
    }

    /** 组包并推送 S2C 4901（Record 倒序下发，列表顶部=最新一条）。 */
    private void sendMagicBoxInfo(GameSession session, GamePacket pkt, PlayerRecord rec) {
        PlayerRecord.MagicBox mb = rec.activity.magicBox;
        ActExtCfg.MagicFile cfg = actExt.magic();
        List<byte[]> prizeBytes = new ArrayList<>();
        for (PlayerRecord.MbPrize p : mb.prize) {
            prizeBytes.add(dump.magicBoxPrize(p.quality, p.type, p.goodsname, p.count, p.state, p.star));
        }
        List<byte[]> recordBytes = new ArrayList<>();
        for (int i = mb.record.size() - 1; i >= 0; i--) {
            recordBytes.add(dump.magicBoxRecord(mb.record.get(i), rec.roleName));
        }
        List<byte[]> cardPosBytes = new ArrayList<>();
        for (PlayerRecord.MbCardPos cp : mb.cardPos) {
            cardPosBytes.add(dump.magicBoxCardPos(cp.cardPos, cp.realPos));
        }
        int nextCost = magicBoxCost(mb.countToday, cfg);
        session.send(MsgIds.S2C_MAGIC_BOX_UPDATE, pkt,
                dump.magicBoxInfo(rec, cfg.goodsOri, cfg.diamondCost, nextCost,
                        cfg.resetCostDiamond, mb.xiPaiState, prizeBytes, recordBytes, cardPosBytes));
    }
}
