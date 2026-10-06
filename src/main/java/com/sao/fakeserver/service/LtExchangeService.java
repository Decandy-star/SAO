package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.LongTengCfg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 大厅「龙腾 / 限时兑换」—— 对应 C2S 3601/3602/3603，S2C 4101/4102/4103/4104。
 *
 * <p>之前假服零实现，四个可见症状：</p>
 * <ul>
 *   <li>初始数据不加载 / 商品列表空：S2C 4101 从未下发，而客户端
 *       {@code ExchangeInATimeInfo.UnPacket} 只由 4101 触发（{@code ᝁ.cs:4694-4698}）；</li>
 *   <li>点抽奖无反应：{@code ExchangeResultUI.Open()} 只在 4102 的 handler 里
 *       （{@code ᝁ.cs:4703-4709}）；</li>
 *   <li>点兑换无反应：需要 4104（{@code ᝁ.cs:4714-4764}）；</li>
 *   <li>排行空：需要 4103（{@code ᝁ.cs:4785-4789}），且依赖 4101 先灌好档位奖。</li>
 * </ul>
 *
 * <p>号段说明：C2S 3601-3603 与 S2C 3601-3603（百重塔）是<b>同号反向</b>；
 * S2C 4101-4104 与 C2S 4101-4104（争霸战 ZBZ）也是反向。出站常量名已按方向区分。
 * 4101 <b>没有对应的 C2S</b>，只能由服务端主动 push（登录时推一次）。</p>
 *
 * <p>进度落 {@link PlayerRecord#ltExchange}（累计抽奖/兑换次数 + 已兑换 ori）。
 * 「扣费的到底是兑换币还是钻石」「4102 一次回几条」「RankPrize 的 8 档真实值」等都属
 * 需抓真服包才能定论的项，见 docs/PROTOCOL_GAP_REPORT.md §7；本实现策略是
 * <b>优先扣兑换币（{@code tokenName}），不足时扣钻石</b>（客户端 {@code ExchangeInATime.cs:115}
 * 也正是「两者都不够才拦」）。</p>
 */
@Service
public class LtExchangeService {
    private static final Logger log = LoggerFactory.getLogger(LtExchangeService.class);

    /** 单次抽奖最多 10 连（客户端 {@code ExchangeInATime.cs:143} 发 times=10）。 */
    private static final int MAX_TIMES = 10;
    /** 榜单展示条数。 */
    private static final int RANK_ROWS = 50;

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final LongTengCfg cfg;
    private final Random rng = new Random();

    public LtExchangeService(PlayerStore store, PlayerDumpService dump, ProgressService progress,
                             LongTengCfg cfg) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.cfg = cfg;
    }

    // ------------------------------------------------------------- 登录推送 4101

    /**
     * 登录时推 S2C 4101。没有这个包客户端连界面都打不开（{@code IsOpenExchange}
     * 默认 false，{@code ExchangeInATimeInfo.cs:49-59} 会直接弹 100858 关界面）。
     */
    public void onLogin(GameSession session, GamePacket pkt, PlayerRecord rec) {
        if (rec == null) {
            return;
        }
        session.send(MsgIds.S2C_LT_EXCHANGE_INFO, pkt, infoPacket());
        log.info("{} longteng info pushed (open={})", rec.account, Boolean.valueOf(cfg.isOpen()));
    }

    private byte[] infoPacket() {
        List<PlayerDumpService.LtPrize> prizes = new ArrayList<PlayerDumpService.LtPrize>();
        for (LongTengCfg.Prize p : cfg.prizes()) {
            if (p == null) {
                continue;
            }
            PlayerDumpService.LtPrize x = new PlayerDumpService.LtPrize();
            x.moriName = p.moriName;
            x.goodsStar = p.goodsStar;
            x.goodsNum = p.goodsNum;
            x.medalLevel = p.medalLevel;
            x.medalNum = p.medalNum;
            x.honourNum = p.honourNum;
            prizes.add(x);
        }
        List<PlayerDumpService.LtRankPrize> ranks = new ArrayList<PlayerDumpService.LtRankPrize>();
        for (LongTengCfg.RankPrize r : cfg.rankPrizes()) {
            if (r == null) {
                continue;
            }
            PlayerDumpService.LtRankPrize x = new PlayerDumpService.LtRankPrize();
            x.jinBi = r.jinBi;
            x.rmb = r.rmb;
            x.yingPo = r.yingPo;
            x.goods1Name = r.goods1Name;
            x.goods1Num = r.goods1Num;
            x.goods2Name = r.goods2Name;
            x.goods2Num = r.goods2Num;
            x.rankLimit = r.rankLimit;
            ranks.add(x);
        }
        return dump.ltExchangeInfo(cfg.activeOnOff(), cfg.onceRmbCost(), cfg.tenRmbCost(),
                cfg.honourName(), cfg.endTime(), cfg.medalNames(), prizes, cfg.tokenName(),
                cfg.onceTokenCost(), cfg.tenTokenCost(), ranks, cfg.rankId());
    }

    // ---------------------------------------------------------- C2S 3601 抽奖

    /** C2S 3601 {@code CCMsgLTExchangeLottery{1 times}}（times=1 单抽 / 10 十连）。 */
    public void onLottery(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        if (!cfg.isOpen()) {
            session.send(MsgIds.S2C_LT_EXCHANGE_PRIZE, pkt, dump.ltExchangePrizeRet(null));
            log.info("{} longteng lottery refused: activity closed", rec.account);
            return;
        }
        int times = Pb.read(pkt.body).getInt(1, 1);
        if (times <= 0) {
            times = 1;
        }
        if (times > MAX_TIMES) {
            times = MAX_TIMES;
        }
        if (!charge(session, pkt, rec, cfg.tokenCost(times), cfg.rmbCost(times))) {
            // 扣费失败：回一个空结果包，客户端至少能收尾（不会卡在等待态）。
            session.send(MsgIds.S2C_LT_EXCHANGE_PRIZE, pkt, dump.ltExchangePrizeRet(null));
            log.info("{} longteng lottery refused: not enough token/diamond (times={})",
                    rec.account, Integer.valueOf(times));
            return;
        }

        List<PlayerDumpService.LtDrawPrize> results =
                new ArrayList<PlayerDumpService.LtDrawPrize>(times);
        Map<String, Integer> changed = progress.emptyChanged();
        for (int i = 0; i < times; i++) {
            LongTengCfg.Prize p = rollPrize();
            if (p == null) {
                continue;
            }
            PlayerDumpService.LtDrawPrize r = new PlayerDumpService.LtDrawPrize();
            // 勋章/荣誉按奖池档位发放，走的也是背包，所以全部走 goods 变更集。
            if (p.medalLevel >= 1 && p.medalLevel <= cfg.medalNames().size() && p.medalNum > 0) {
                String medal = cfg.medalNames().get(p.medalLevel - 1);
                progress.addGoods(rec, medal, p.medalNum);
                progress.markGoods(changed, medal);
            }
            if (p.honourNum > 0 && cfg.honourName() != null && !cfg.honourName().isEmpty()) {
                progress.addGoods(rec, cfg.honourName(), p.honourNum);
                progress.markGoods(changed, cfg.honourName());
            }
            if (p.moriName != null && !p.moriName.isEmpty() && p.goodsNum > 0) {
                progress.addGoods(rec, p.moriName, p.goodsNum);
                progress.markGoods(changed, p.moriName);
                r.awards.add(dump.drawAwardItem(p.moriName, p.goodsNum));
            }
            results.add(r);
        }
        rec.ltExchange.drawCount += times;
        rec.ltExchange.lastDrawAtMs = System.currentTimeMillis();
        store.save(rec);
        if (!changed.isEmpty()) {
            progress.pushGoods(session, pkt, rec, changed);
        }
        session.send(MsgIds.S2C_LT_EXCHANGE_PRIZE, pkt, dump.ltExchangePrizeRet(results));
        log.info("{} longteng lottery times={} drawCount={}", rec.account, Integer.valueOf(times),
                Integer.valueOf(rec.ltExchange.drawCount));
    }

    // ------------------------------------------------------ C2S 3602 兑换商品

    /** C2S 3602 {@code CCMsgLTExchangeGoods{1 GoodsName}} → S2C 4104。 */
    public void onExchangeGoods(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        String ori = Pb.read(pkt.body).getString(1);
        LongTengCfg.Goods g = cfg.goodsByOri(ori == null ? null : ori.trim());
        if (!cfg.isOpen() || g == null) {
            log.info("{} longteng exchange unknown goods [{}]", rec.account, ori);
            return;
        }
        if (!charge(session, pkt, rec, g.tokenCost, g.rmbCost)) {
            log.info("{} longteng exchange refused: not enough token/diamond for [{}]",
                    rec.account, g.oriName);
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        if (g.oriName != null && !g.oriName.isEmpty() && g.goodsNum > 0) {
            progress.addGoods(rec, g.oriName, g.goodsNum);
            progress.markGoods(changed, g.oriName);
        }
        rec.ltExchange.exchangeCount++;
        if (g.oriName != null && !g.oriName.isEmpty() && !rec.ltExchange.claimed.contains(g.oriName)) {
            rec.ltExchange.claimed.add(g.oriName);
        }
        store.save(rec);
        if (!changed.isEmpty()) {
            progress.pushGoods(session, pkt, rec, changed);
        }
        session.send(MsgIds.S2C_LT_EXCHANGE_GOODS_RET, pkt, dump.ltExchangeGoodsRet(g.goodsType,
                g.oriName, g.goodsStar, g.goodsNum, g.suipianName, g.suipianNum));
        log.info("{} longteng exchange [{}] x{} type={}", rec.account, g.oriName,
                Integer.valueOf(g.goodsNum), Integer.valueOf(g.goodsType));
    }

    // -------------------------------------------------------- C2S 3603 榜单

    /** C2S 3603 {@code NET_CCMsgRequestLTExchangeRank}（无类，无 body）→ S2C 4103。 */
    public void onRank(GameSession session, GamePacket pkt) {
        PlayerRecord self = session.player();
        if (self == null) {
            return;
        }
        List<PlayerRecord> all = new ArrayList<PlayerRecord>(store.all());
        Collections.sort(all, new Comparator<PlayerRecord>() {
            @Override
            public int compare(PlayerRecord a, PlayerRecord b) {
                return drawCount(b) - drawCount(a);
            }
        });
        List<PlayerDumpService.LtRankRow> rows = new ArrayList<PlayerDumpService.LtRankRow>();
        int myRank = 0;
        int n = Math.min(RANK_ROWS, all.size());
        for (int i = 0; i < n; i++) {
            PlayerRecord r = all.get(i);
            if (r == null) {
                continue;
            }
            PlayerDumpService.LtRankRow row = new PlayerDumpService.LtRankRow();
            row.rank = i + 1;
            row.guid = r.playerId;
            row.name = r.roleName;
            row.resID = r.mainHeroIndex;
            row.level = r.level;
            // 原厂 value 的语义未知（需抓包），这里取累计抽奖次数。
            row.value = drawCount(r);
            rows.add(row);
            if (r == self) {
                myRank = row.rank;
            }
        }
        session.send(MsgIds.S2C_LT_EXCHANGE_RANK_RET, pkt, dump.ltExchangeRankRet(myRank, rows));
        log.info("{} longteng rank -> myRank={} rows={}", self.account, Integer.valueOf(myRank),
                Integer.valueOf(rows.size()));
    }

    // ------------------------------------------------------------------ 内部

    private static int drawCount(PlayerRecord r) {
        return r == null || r.ltExchange == null ? 0 : r.ltExchange.drawCount;
    }

    /**
     * 扣费：优先扣兑换币 {@code tokenName}，不够再扣钻石。
     * <p>返回 false 表示两者都不足（此时一个都没扣）。
     */
    private boolean charge(GameSession session, GamePacket pkt, PlayerRecord rec, int tokenCost,
                           int rmbCost) {
        String token = cfg.tokenName();
        if (tokenCost > 0 && token != null && !token.isEmpty()
                && progress.hasGoods(rec, token, tokenCost)) {
            Map<String, Integer> changed = progress.emptyChanged();
            if (progress.consumeGoods(rec, token, tokenCost)) {
                progress.markGoods(changed, token);
            }
            store.save(rec);
            if (!changed.isEmpty()) {
                progress.pushGoods(session, pkt, rec, changed);
            }
            return true;
        }
        if (rmbCost > 0 && rec.diamond >= rmbCost) {
            rec.diamond -= rmbCost;
            progress.pushDiamond(session, pkt, rec);
            store.save(rec);
            return true;
        }
        // tokenCost 与 rmbCost 都是 0 的免费档位
        return tokenCost <= 0 && rmbCost <= 0;
    }

    private LongTengCfg.Prize rollPrize() {
        List<LongTengCfg.Prize> pool = cfg.prizes();
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        int total = 0;
        for (LongTengCfg.Prize p : pool) {
            if (p != null) {
                total += Math.max(1, p.weight);
            }
        }
        if (total <= 0) {
            return null;
        }
        int hit = rng.nextInt(total);
        for (LongTengCfg.Prize p : pool) {
            if (p == null) {
                continue;
            }
            hit -= Math.max(1, p.weight);
            if (hit < 0) {
                return p;
            }
        }
        return pool.get(pool.size() - 1);
    }

    /** 供 {@code MessageDispatcher} 打印用：包体是空的时候不要去解析。 */
    public static boolean hasBody(GamePacket pkt) {
        return pkt != null && pkt.body != null && pkt.body.length > 0;
    }
}
