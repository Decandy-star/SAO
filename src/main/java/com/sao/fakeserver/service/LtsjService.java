package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.LtsjCfg;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 限时神将（大厅独立入口，非活动看板；S2C 3702~3705 / C2S 3302~3305）。
 *
 * <p>规则（与 ltsj.json + 官方 CommonRulesDesc 对齐）：
 * <ul>
 *   <li>每天按 heroes 配置轮换当期神将（长期循环），跨日重置每日免费次数并重开当期；</li>
 *   <li>抽取时随机给一个字 +exp（未满的字里挑）；满 wenZiMaxExp 激活对应宝箱；</li>
 *   <li>每次抽取同时按 drawQuality 权重附送一件随机品质物品（进背包）；</li>
 *   <li>四字全满 → WuJiangState=1 可领神将；已拥有该英雄则领 150 片英雄碎片（发碎片）；</li>
 *   <li>宝箱/神将状态 0 未激活 / 1 可领 / 2 已领，逐格 S2C 3705 推送。</li>
 * </ul>
 *
 * <p>客户端按钮与服务器双重校验：WuJiangState!=0 时禁抽；领奖须状态==1。
 */
@Service
public class LtsjService {
    private static final Logger log = LoggerFactory.getLogger(LtsjService.class);
    private static final int BOX_COUNT = 4;
    private static final int PRIZE_HERO_POS = 4;
    private static final int[] FALLBACK_BOX_QUALITY = {5, 4, 4, 3};

    private final Random rng = new Random();
    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final CultivateTables cultivate;
    private final LtsjCfg cfg;
    private final ProgressService progress;
    private final SessionHub sessions;

    public LtsjService(PlayerStore store, PlayerDumpService dump, CultivateTables cultivate,
                       LtsjCfg cfg, ProgressService progress, SessionHub sessions) {
        this.store = store;
        this.dump = dump;
        this.cultivate = cultivate;
        this.cfg = cfg;
        this.progress = progress;
        this.sessions = sessions;
    }

    // ---------- 对外入口 ----------

    /** C2S 3302 查询。 */
    public void onQuery(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensure(rec);
        store.save(rec);
        sendInfo(session, pkt, rec);
    }

    /** C2S 3303 单抽。 */
    public void onDrawOnce(GameSession session, GamePacket pkt) {
        draw(session, pkt, false);
    }

    /** C2S 3305 十连。 */
    public void onDrawTen(GameSession session, GamePacket pkt) {
        draw(session, pkt, true);
    }

    /** C2S 3304 领奖（pos 0-3 宝箱 / 4 神将）。 */
    public void onGetPrize(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        if (!cfg.enabled()) {
            sendInfo(session, pkt, rec);
            return;
        }
        PlayerRecord.Ltsj l = ensure(rec);
        // CMsgGetLTSJPrize.PrizePos 默认值=0，客户端领第 1 个宝箱(pos=0)时该字段
        // 与默认值相同会被 protobuf-net 省略不写 → body 为空，这里必须默认解析为 0
        // （否则 pos=0 领宝箱永远被拒，表现为「第一个宝箱可领但点了没反应」）。
        int pos = Pb.read(pkt.body).getInt(1, 0);
        if (pos >= 0 && pos < BOX_COUNT) {
            prizeBox(session, pkt, rec, l, pos);
        } else if (pos == PRIZE_HERO_POS) {
            prizeHero(session, pkt, rec, l);
        } else {
            log.info("{} ltsj prize deny pos={}", rec.account, pos);
            sendInfo(session, pkt, rec);
        }
    }

    /** 进大厅（onBackMainCity / onFirstEnterRegion）由 LoginService 调用推送状态。 */
    public void pushTo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        if (cfg.enabled()) {
            ensure(rec);
        }
        store.save(rec);
        sendInfo(session, pkt, rec);
    }

    /**
     * 跨日（0 点）对在线号主动重推 3702：本期神将轮换、每日免费次数重置、四字/宝箱状态重开都在
     * {@link #ensure} 里按 {@code LocalDate} 懒结算，而客户端**没有查询包**（全量 grep 只有 3304 发送点，
     * 3302 从不发）→ 在线挂机的号 0 点后面板仍是上一期数据、抽奖按钮仍是灰的，只能回大厅/重登才刷新。
     * 与商店整点 1701（{@code ShopService.pushClockRefreshToOnline}）同性质，由公共调度在 0 点调用。
     */
    public void pushToOnline() {
        if (!cfg.enabled()) {
            return;
        }
        int n = 0;
        for (GameSession session : sessions.onlineSnapshot()) {
            PlayerRecord rec = session.player();
            if (rec == null) {
                continue;
            }
            ensure(rec);
            store.save(rec);
            sendInfo(session, 0, rec);
            n++;
        }
        if (n > 0) {
            log.info("ltsj clock push online={}", Integer.valueOf(n));
        }
    }

    // ---------- 抽卡 ----------

    private void draw(GameSession session, GamePacket pkt, boolean ten) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        if (!cfg.enabled()) {
            sendInfo(session, pkt, rec);
            return;
        }
        PlayerRecord.Ltsj l = ensure(rec);
        int times = ten ? 10 : 1;
        if (l.wjState != 0) {
            // 四字已满（可领/已领）后不再接受抽卡，须先领神将
            log.info("{} ltsj draw deny wjState={}", rec.account, l.wjState);
            // 客户端 OnTryGoodLuckButton/10Button 发完 3303/3305 立刻把按钮 BoxCollider 关掉
            // （XianShiShenJiang.cs:349/:377），只有收到 3702 才会在 Refresh() :227-228 重新启用；
            // 拒绝时不回包 → 按钮永久变灰、面板废掉。真服对每次请求必有回包，这里补 3702 同步。
            sendInfo(session, pkt, rec);
            return;
        }

        boolean free = false;
        int diamondCost = 0;
        if (!ten && l.freeLeft > 0) {
            free = true;
            l.freeLeft--;
        } else {
            int cost = ten ? cfg.cfg().costTen : cfg.cfg().costOnce;
            if (rec.diamond < cost) {
                log.info("{} ltsj draw skip need={} have={}", rec.account, cost, rec.diamond);
                // 同上：不回包按钮会永久变灰。除 3702 外再推一次钻石，客户端 mCurRMB 是旧的
                // （XianShiShenJiang.cs:329/:357 用 mCurRMB 判够不够）→ 同步后下次点击才会弹确认框。
                progress.pushPlayerProgress(session, pkt, rec, false, false, false, false, true);
                sendInfo(session, pkt, rec);
                return;
            }
            rec.diamond -= cost;
            diamondCost = cost;
        }

        List<Integer> wordHits = new ArrayList<>();
        Map<String, Integer> changed = new LinkedHashMap<>();
        List<byte[]> awards = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            rollItem(rec, changed, awards);
            // 文字槽填满（rollWord 返回 -1）只代表本次不再有文字经验可落，
            // **不能中断整轮抽奖**：十连已扣满 3000 钻，若四字在中途恰好填满就 break，
            // 玩家会「付十连的价只拿到 2 件」。这里只记日志，物品照发（等价 continue）。
            int pos = rollWord(rec, l, wordHits);
            if (pos < 0) {
                log.warn("{} ltsj no unfull word left", rec.account);
            }
        }
        refreshWjState(l);
        store.save(rec);

        if (diamondCost > 0) {
            progress.pushPlayerProgress(session, pkt, rec, false, false, false, false, true);
            progress.addTodayCost(session, pkt, rec, diamondCost);
        }
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_LTSJ_PRIZE, pkt, dump.ltsjPrize(awards));
        if (!wordHits.isEmpty()) {
            session.send(MsgIds.S2C_LTSJ_WENZI_EXP, pkt, dump.ltsjWenZiExp(wordHits));
        }
        sendInfo(session, pkt, rec);
        log.info("{} ltsj draw ten={} free={} cost={} hits={} awards={}", rec.account,
                ten, free, diamondCost, wordHits.size(), awards.size());
    }

    /** 每次抽取附送一件随机品质物品（随 ltsj.json drawQuality/drawCount 掉落）。 */
    private void rollItem(PlayerRecord rec, Map<String, Integer> changed, List<byte[]> awards) {
        int quality = cfg.rollDrawQuality(rng);
        String ori = cfg.rollGoodsOri(quality, rng);
        int count = cfg.randCount(quality, false, rng);
        progress.addGoods(rec, ori, count);
        changed.put(ori, rec.bag.getOrDefault(ori, 0));
        awards.add(dump.drawAwardItem(ori, count));
    }

    /** 从未满的字中随机选一个 +1 exp；命中返回槽位，无未满字返回 -1。 */
    private int rollWord(PlayerRecord rec, PlayerRecord.Ltsj l, List<Integer> wordHits) {
        int max = Math.max(1, cfg.cfg().wenZiMaxExp);
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < BOX_COUNT; i++) {
            if (l.wenZiExp.get(i) < max) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            return -1;
        }
        int pos = candidates.get(rng.nextInt(candidates.size()));
        int add = cfg.expOnce(rng);
        int exp = Math.min(l.wenZiExp.get(pos) + add, max);
        l.wenZiExp.set(pos, exp);
        if (exp >= max && l.boxState.get(pos) == 0) {
            l.boxState.set(pos, 1);
        }
        wordHits.add(pos);
        return pos;
    }

    /** 四字全满则 WuJiangState 置 1（可领神将）。 */
    private void refreshWjState(PlayerRecord.Ltsj l) {
        if (l.wjState != 0) {
            return;
        }
        int max = Math.max(1, cfg.cfg().wenZiMaxExp);
        for (int i = 0; i < BOX_COUNT; i++) {
            if (l.wenZiExp.get(i) < max) {
                return;
            }
        }
        l.wjState = 1;
    }

    // ---------- 领奖 ----------

    /**
     * 宝箱：0-3 槽，须 state==1；发**宝箱本体**（{@code ltsj.json boxes[].ori}，如 BX191）并标 state=2。
     *
     * <p>不发箱内容物：客户端只用 GoodsList 解析 3702 的 PrizeGoodsName（悬浮 tooltip 与领奖冒字，
     * {@code WuPingTooltip.cs:106-107} / {@code XianShiShenJiang.cs:547-562}），而装备（EQ*）与时光石
     * （TS*）都不在 GoodsList 里，送内容物会静默不显示；且 BX191-194 的说明就是「一定获得…」，
     * 设计上箱是背包里可开的道具（属性 10，C2S 3101 开启，见 {@code DungeonService.onOpenBaoXiang}）。
     */
    private void prizeBox(GameSession session, GamePacket pkt, PlayerRecord rec, PlayerRecord.Ltsj l, int pos) {
        if (l.boxState.get(pos) != 1) {
            log.info("{} ltsj box prize deny pos={} state={}", rec.account, pos, l.boxState.get(pos));
            // 客户端只在 PrizeGoodsState[pos]==1 时才发 3304（XianShiShenJiang.cs:386），
            // 服务端已重置/已领时拒绝并回 3702，让客户端把「可领」动画收掉，避免点了没反应。
            sendInfo(session, pkt, rec);
            return;
        }
        String ori = l.boxOri.get(pos);
        int num = l.boxNum.get(pos);
        if (ori == null || ori.isEmpty() || num <= 0) {
            log.warn("{} ltsj box prize deny pos={} ori={} num={}", rec.account, pos, ori, num);
            // 同 state!=1 分支：不回包客户端会永远停在「可领取」旋转动画上（点不动），
            // 真服对每次 3304 必有回包，这里补 3702 收掉动画。
            sendInfo(session, pkt, rec);
            return;
        }
        l.boxState.set(pos, 2);
        progress.addGoods(rec, ori, num);
        store.save(rec);
        Map<String, Integer> changed = new LinkedHashMap<>();
        changed.put(ori, rec.bag.getOrDefault(ori, 0));
        progress.pushGoods(session, pkt, rec, changed);
        sendInfo(session, pkt, rec);
        session.send(MsgIds.S2C_LTSJ_BOX_STATE, pkt, dump.ltsjBoxState(pos, 2));
        log.info("{} ltsj box prize pos={} ori={} num={}", rec.account, pos, ori, num);
    }

    /** 神将：未拥有整卡入列；已拥有按 APK 折算表发该将星级对应的灵魂碎片（S2C 3702 带碎片数，客户端跳转 SG 视图）。 */
    private void prizeHero(GameSession session, GamePacket pkt, PlayerRecord rec, PlayerRecord.Ltsj l) {
        if (l.wjState != 1) {
            log.info("{} ltsj hero prize deny wjState={}", rec.account, l.wjState);
            // 同宝箱：客户端 WuJiangState==1 才发 3304（XianShiShenJiang.cs:316），拒绝时回 3702 收掉「可领」。
            sendInfo(session, pkt, rec);
            return;
        }
        int heroIndex = cfg.activeHeroIndex(GameTime.today());
        if (heroIndex <= 0 || !cultivate.isPlayableHero(heroIndex)) {
            log.warn("{} ltsj hero prize deny invalid hero index={}", rec.account, heroIndex);
            // 同上：无回包则客户端 WuJiangState 停在 1，「可领」态点不动。回 3702 同步。
            sendInfo(session, pkt, rec);
            return;
        }
        l.wjState = 2;
        if (owns(rec, heroIndex)) {
            String fragOri = fragmentOri(heroIndex);
            // 折算数按 APK 表：WuJiangUpStar 第 23-32 行「N星英雄折算碎片数」= 8/15/30/50/80/120/170/230/300/350，
            // 索引是该将的 composeStar（CultivateTables.chaiJieFragForHero），与抽卡重复武将同一口径
            // （DrawService:227 / DungeonService:923）。原先写死 150 不属于任何折算档（150 是「4星升5星所需」那一列），已废。
            int count = Math.max(1, cultivate.chaiJieFragForHero(heroIndex));
            l.wjSuiPianName = fragOri;
            l.wjSuiPianCount = count;
            progress.addGoods(rec, fragOri, count);
            Map<String, Integer> changed = new LinkedHashMap<>();
            changed.put(fragOri, rec.bag.getOrDefault(fragOri, 0));
            store.save(rec);
            progress.pushGoods(session, pkt, rec, changed);
            log.info("{} ltsj hero prize pos4 owned -> fragment {} x{}", rec.account, fragOri, count);
        } else {
            PlayerRecord.Hero wj = newHero(rec, heroIndex);
            rec.heroes.add(wj);
            store.save(rec);
            session.send(MsgIds.S2C_ADD_WUJIANG, pkt, dump.addWuJiang(wj, false));
            log.info("{} ltsj hero prize pos4 new hero index={} id={}", rec.account, heroIndex, wj.id);
        }
        sendInfo(session, pkt, rec);
        session.send(MsgIds.S2C_LTSJ_BOX_STATE, pkt, dump.ltsjBoxState(PRIZE_HERO_POS, 2));
    }

    // ---------- 状态维护 ----------

    /** 跨日重置 + 当期首开重 roll 宝箱；随后补齐四槽。未开启返回 null（调用方直接透传隐藏）。 */
    private PlayerRecord.Ltsj ensure(PlayerRecord rec) {
        rec.ensureCollections();
        PlayerRecord.Ltsj l = rec.ltsj;
        if (!cfg.enabled()) {
            return null;
        }
        LocalDate today = GameTime.today();
        int heroIndex = cfg.activeHeroIndex(today);
        String key = today.toString();
        boolean newDay = !key.equals(l.dateKey) || l.heroIndex != heroIndex;
        boolean emptyRound = l.boxOri.isEmpty() && l.boxNum.isEmpty() && l.boxState.isEmpty();
        if (newDay || emptyRound) {
            resetRound(l, key, heroIndex);
            store.save(rec);
            log.info("{} ltsj new round date={} hero={} free={}", rec.account, key, heroIndex, l.freeLeft);
        } else if (boxesStale(l)) {
            // 存量存档口径迁移：老档的四格是「按 boxQuality 随机 roll 出的普通道具」
            // （#24 之前的行为，例：data/players/admin.json 的 ZBSP46/GOODS85/BX136），
            // 现在配置已改为箱本体 BX191-194。这类档在当天既非跨日也非空期，原本要等到
            // 0 点才自愈 → 当天长按浮名/领取冒字/实发产出仍是旧道具，走不到 C2S 3101 开箱。
            // 按配置重写箱本体，保留 boxState/wenZiExp/wjState/freeLeft（不吞玩家进度）。
            applyBoxConfig(l);
            padLists(l);
            store.save(rec);
            log.info("{} ltsj box migrate date={} ori={} num={}", rec.account, key, l.boxOri, l.boxNum);
        } else {
            padLists(l);
        }
        return l;
    }

    /**
     * 开新一期：清空四格宝箱、字数/状态归零，免费次数满。
     *
     * <p>宝箱优先取 {@code ltsj.json} 的 {@code boxes}（表依据 GoodsList BX191-194
     * 「七夕神将宝箱一~四」，描述即「一定获得…」）；配置为空时回退到按 {@code boxQuality}
     * 随机 roll（历史行为，仅作兜底，此时发的是随机物品而非箱子）。
     */
    private void resetRound(PlayerRecord.Ltsj l, String dateKey, int heroIndex) {
        l.dateKey = dateKey;
        l.heroIndex = heroIndex;
        l.freeLeft = Math.max(0, cfg.cfg().freeTimesPerDay);
        l.wenZiExp = listOfInts(BOX_COUNT, 0);
        l.boxState = listOfInts(BOX_COUNT, 0);
        l.boxOri = new ArrayList<>();
        l.boxNum = new ArrayList<>();
        if (!applyBoxConfig(l)) {
            for (int i = 0; i < BOX_COUNT; i++) {
                int quality = boxQuality(i);
                l.boxOri.add(cfg.rollGoodsOri(quality, rng));
                l.boxNum.add(cfg.randCount(quality, true, rng));
            }
        }
        l.wjState = 0;
        l.wjSuiPianCount = 0;
        l.wjSuiPianName = "";
    }

    /**
     * 按 {@code ltsj.json} 的 {@code boxes} 重写四格箱本体（ori/num），不动状态与字数。
     * 配置不足 {@link #BOX_COUNT} 行时返回 false 且不改动（调用方走随机兜底）。
     */
    private boolean applyBoxConfig(PlayerRecord.Ltsj l) {
        List<LtsjCfg.LtsjFile.BoxRow> boxes = cfg.boxes();
        if (boxes == null || boxes.size() < BOX_COUNT) {
            return false;
        }
        List<String> ori = new ArrayList<>(BOX_COUNT);
        List<Integer> num = new ArrayList<>(BOX_COUNT);
        for (int i = 0; i < BOX_COUNT; i++) {
            LtsjCfg.LtsjFile.BoxRow b = boxes.get(i);
            ori.add(b == null || b.ori == null ? "" : b.ori);
            num.add(b == null ? 0 : Math.max(0, b.num));
        }
        l.boxOri = ori;
        l.boxNum = num;
        return true;
    }

    /** 四格箱本体是否与当前配置不一致（老档发的是随机道具）→ 需要迁移。 */
    private boolean boxesStale(PlayerRecord.Ltsj l) {
        List<LtsjCfg.LtsjFile.BoxRow> boxes = cfg.boxes();
        if (boxes == null || boxes.size() < BOX_COUNT) {
            return false;
        }
        for (int i = 0; i < BOX_COUNT; i++) {
            LtsjCfg.LtsjFile.BoxRow b = boxes.get(i);
            String wantOri = b == null || b.ori == null ? "" : b.ori;
            int wantNum = b == null ? 0 : Math.max(0, b.num);
            if (i >= l.boxOri.size() || i >= l.boxNum.size()
                    || !wantOri.equals(l.boxOri.get(i)) || wantNum != l.boxNum.get(i)) {
                return true;
            }
        }
        return false;
    }

    /** 补齐历史存档到四槽长度（不覆盖已有）。 */
    private void padLists(PlayerRecord.Ltsj l) {
        while (l.wenZiExp.size() < BOX_COUNT) {
            l.wenZiExp.add(0);
        }
        while (l.boxOri.size() < BOX_COUNT) {
            l.boxOri.add("");
        }
        while (l.boxNum.size() < BOX_COUNT) {
            l.boxNum.add(0);
        }
        while (l.boxState.size() < BOX_COUNT) {
            l.boxState.add(0);
        }
        // 箱本体缺失/数量非法的槽不能留非 0 状态：客户端 boxState==1 会显示「可领取」+ 旋转
        // 动画（XianShiShenJiang.cs:243-252），点击发 3304 后被服务端按 ori 非法拒 → 格子永久
        // 卡在「可领取」点不动（比抽奖拒绝更隐蔽）。归 0 后与状态一致，不再显示可领。
        for (int i = 0; i < BOX_COUNT; i++) {
            String ori = l.boxOri.get(i);
            if (l.boxState.get(i) != 0 && (ori == null || ori.isEmpty() || l.boxNum.get(i) <= 0)) {
                l.boxState.set(i, 0);
            }
        }
    }

    private int boxQuality(int slot) {
        List<Integer> qs = cfg.cfg().boxQuality;
        if (qs != null && !qs.isEmpty()) {
            int q = qs.get(slot % qs.size());
            if (q >= 3 && q <= 5) {
                return q;
            }
        }
        return FALLBACK_BOX_QUALITY[slot % FALLBACK_BOX_QUALITY.length];
    }

    private static List<Integer> listOfInts(int size, int v) {
        List<Integer> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(v);
        }
        return list;
    }

    // ---------- 组包 3702 ----------

    private void sendInfo(GameSession session, GamePacket pkt, PlayerRecord rec) {
        sendInfo(session, pkt.serial, rec);
    }

    /** serial=0 供无请求包的主动推送（{@link #pushToOnline}）使用。 */
    private void sendInfo(GameSession session, int serial, PlayerRecord rec) {
        PlayerRecord.Ltsj l = rec.ltsj == null ? null : rec.ltsj;
        boolean on = cfg.enabled();
        int activeId = on ? cfg.activeId() : 0;
        LocalDate today = GameTime.today();
        int heroIndex = 0;
        int heroStar = 1;
        if (on) {
            heroIndex = cfg.activeHeroIndex(today);
            CultivateTables.HeroCfg hero = cultivate.heroByIndex(heroIndex);
            heroStar = hero == null || hero.composeStar < 1 ? 1 : hero.composeStar;
        }
        List<String> words = imageNames();
        String desc = on ? cfg.cfg().desc : "";
        int costOnce = on ? cfg.cfg().costOnce : 0;
        int costTen = on ? cfg.cfg().costTen : 0;
        // 与 rollWord/refreshWjState 内部的 Math.max(1, ...) 保持一致：下发的分母必须 ≥1，
        // 否则客户端 XianShiShenJiang.cs:200-213 算 (float)exp/(float)0 = NaN 且 exp>=0 恒判「经验已满」。
        int wenZiMax = on ? Math.max(1, cfg.cfg().wenZiMaxExp) : 0;
        int suiCount = l == null ? 0 : l.wjSuiPianCount;
        String suiName = l == null ? "" : l.wjSuiPianName;
        session.send(MsgIds.S2C_LTSJ_INFO, serial,
                dump.ltsjInfo(l, activeId, heroIndex, heroStar, cfg.endTimeOf(today),
                        costOnce, costTen, wenZiMax, suiCount, suiName, desc, words));
    }

    /** 四字 ImageName（客户端固定取 [0..3]，不足补 "NULL" 隐藏）。 */
    private List<String> imageNames() {
        List<String> words = cfg.cfg().words;
        List<String> names = new ArrayList<>(BOX_COUNT);
        for (int i = 0; i < BOX_COUNT; i++) {
            String w = words != null && i < words.size() ? words.get(i) : null;
            names.add(w == null || w.isEmpty() ? "NULL" : w);
        }
        return names;
    }

    // ---------- 工具 ----------

    private boolean owns(PlayerRecord rec, int index) {
        for (PlayerRecord.Hero wj : rec.heroes) {
            if (wj.heroIndex == index) {
                return true;
            }
        }
        return false;
    }

    private PlayerRecord.Hero newHero(PlayerRecord rec, int index) {
        PlayerRecord.Hero wj = new PlayerRecord.Hero();
        wj.heroIndex = index;
        wj.id = PlayerDumpService.guidOf(rec.account, index);
        wj.level = 1;
        CultivateTables.HeroCfg hero = cultivate.heroByIndex(index);
        wj.stars = hero == null ? 1 : Math.max(1, hero.composeStar);
        wj.fightPower = cultivate.computeFightPower(index, wj.level, wj.stars);
        return wj;
    }

    private String fragmentOri(int heroIndex) {
        CultivateTables.HeroCfg hero = cultivate.heroByIndex(heroIndex);
        if (hero != null && hero.fragmentOri != null && !hero.fragmentOri.isEmpty()) {
            return hero.fragmentOri;
        }
        return String.format("SP%03d", heroIndex);
    }
}
