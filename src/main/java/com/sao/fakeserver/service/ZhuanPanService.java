package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.ZhuanPanCfg;
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
 * 大厅「梦幻转盘」。之前假服对 C2S 4601-4605 <b>零实现</b>（无常量、无 case），
 * 而大厅入口是可见的，结果：
 * <ul>
 *   <li>{@code LunPanChouJiangUI.cs:406→408} 在 {@code ZhuanPanBaseInfo == null} 时对 null 取
 *       {@code zuanShiOneCost} ⇒ <b>NullReferenceException</b>（单抽/十连/悬浮都炸）；</li>
 *   <li>{@code DaTingMainUISystem.cs:443} 因 {@code ZhuanPanStatus == null} 强制隐藏大厅入口；</li>
 *   <li>榜单永远空（{@code LunPanRankList.cs:49} 发 4604 无回包）。</li>
 * </ul>
 *
 * <p>号段说明：C2S 4601-4605 与争霸战 S2C 4601-4605 <b>同号不同向</b>，4605 是「奖池信息」查询，
 * 客户端在已有缓存时改发它（{@code LunPanChouJiangUI.cs:86-93}），两个号共用同一个 5101 回包。</p>
 *
 * <p>玩家状态只写 {@link PlayerRecord#varis}（key {@code zhuanpan.drawTime} = 累计抽奖次数），
 * 不改存档 schema。跑马灯（5106）是进程内环形缓冲、不落档，重启即清空 —— 属展示性数据。</p>
 *
 * <p>抽奖概率、奖池内容、代币 ori、结束时间是「需抓真服包才能定论」的数值，
 * 见 docs/PROTOCOL_GAP_REPORT.md §7；本实现全部走 {@code tables/zhuanpan.json}。</p>
 */
@Service
public class ZhuanPanService {
    private static final Logger log = LoggerFactory.getLogger(ZhuanPanService.class);

    /** {@link PlayerRecord#varis} 里累计抽奖次数的 key（决定榜单 drawTime 与名次）。 */
    private static final String VAR_DRAW_TIME = "zhuanpan.drawTime";
    /** 判定「大奖」的阈值：钻石 ≥ 该值或道具星级 ≥ 4 就上跑马灯。 */
    private static final int BIG_DIAMOND = 80;
    private static final int BIG_STAR = 4;
    private static final int BIG_KEEP = 10;

    /** 进程内跑马灯，最新在前。 */
    private final List<PlayerDumpService.ZhuanPanBigAward> recentBig =
            new ArrayList<PlayerDumpService.ZhuanPanBigAward>();
    private final Random rng = new Random();

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final ZhuanPanCfg cfg;
    private final ArenaService arena;

    public ZhuanPanService(PlayerStore store, PlayerDumpService dump, ProgressService progress,
                           ZhuanPanCfg cfg, ArenaService arena) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.cfg = cfg;
        this.arena = arena;
    }

    // ------------------------------------------------------------------ C2S 入口

    /** C2S 4601（面板首次打开）/ 4605（已有缓存）→ S2C 5101。 */
    public void onBaseInfo(GameSession session, GamePacket pkt) {
        if (!requirePlayer(session)) {
            return;
        }
        session.send(MsgIds.S2C_ZHUANPAN_BASE_INFO, pkt, dump.zhuanPanBaseInfo(cfg));
    }

    /** C2S 4602 单抽 → S2C 5103。 */
    public void onDrawOne(GameSession session, GamePacket pkt) {
        draw(session, pkt, 1, MsgIds.S2C_ZHUANPAN_DRAW_ONE_RET);
    }

    /** C2S 4603 十连 → S2C 5104。 */
    public void onDrawTen(GameSession session, GamePacket pkt) {
        draw(session, pkt, 10, MsgIds.S2C_ZHUANPAN_DRAW_TEN_RET);
    }

    /** C2S 4604 排行榜 → S2C 5105。 */
    public void onRank(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        List<PlayerDumpService.ZhuanPanRankRow> rows = rankRows();
        PlayerDumpService.ZhuanPanRankRow mine = rowOf(rec, 0);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).guid == rec.playerId) {
                mine.rank = i + 1;
                break;
            }
        }
        session.send(MsgIds.S2C_ZHUANPAN_RANK_RET, pkt, dump.zhuanPanRankRet(rows, mine));
    }

    /** 登录 / 进主城时推 5102 状态与 5106 跑马灯（客户端是 AddRange，必须在面板打开前到）。 */
    public void onLogin(GameSession session, GamePacket pkt, PlayerRecord rec) {
        if (rec == null) {
            return;
        }
        pushStatus(session, pkt);
        pushBigAwards(session, pkt);
    }

    // ------------------------------------------------------------------ 出站

    /** S2C 5102：控制大厅入口显隐（{@code DaTingMainUISystem.cs:439/:443}）。 */
    public void pushStatus(GameSession session, GamePacket pkt) {
        session.send(MsgIds.S2C_ZHUANPAN_STATUS, pkt, dump.zhuanPanStatus(cfg.isOpen(), cfg.curAwardPool()));
    }

    /** S2C 5106：跑马灯。客户端 {@code ᝁ.cs:8364-8378} 只 AddRange、不发事件。 */
    public void pushBigAwards(GameSession session, GamePacket pkt) {
        List<PlayerDumpService.ZhuanPanBigAward> snapshot;
        synchronized (recentBig) {
            if (recentBig.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<PlayerDumpService.ZhuanPanBigAward>(recentBig);
        }
        session.send(MsgIds.S2C_ZHUANPAN_BIG_AWARD_ADD, pkt, dump.zhuanPanBigAwardAdd(snapshot));
    }

    // ------------------------------------------------------------------ 抽奖

    private void draw(GameSession session, GamePacket pkt, int times, int retId) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        if (!cfg.isOpen()) {
            log.info("{} zhuanpan draw rejected: closed", rec.account);
            return;
        }
        // 扣费优先代币（与客户端价签口径一致：token 够显示代币价，否则显示钻石价）。
        String token = cfg.tokenItem();
        int tokenNeed = times == 1 ? cfg.tokenOneCost() : cfg.tokenTenCost();
        int diamondNeed = times == 1 ? cfg.zuanShiOneCost() : cfg.zuanShiTenCost();
        int haveToken = token.isEmpty() ? 0 : countBag(rec, token);
        boolean useToken = tokenNeed > 0 && haveToken >= tokenNeed;
        if (!useToken) {
            if (rec.diamond < diamondNeed) {
                log.info("{} zhuanpan draw rejected: token={}/{} diamond={}/{}", rec.account,
                        Integer.valueOf(haveToken), Integer.valueOf(tokenNeed),
                        Integer.valueOf(rec.diamond), Integer.valueOf(diamondNeed));
                return;
            }
            rec.diamond -= diamondNeed;
        } else {
            spendBag(rec, token, tokenNeed);
        }

        Map<String, Integer> changed = new LinkedHashMap<String, Integer>();
        List<PlayerDumpService.ZhuanPanDraw> awards = new ArrayList<PlayerDumpService.ZhuanPanDraw>();
        for (int i = 0; i < times; i++) {
            awards.add(rollAndGrant(rec, changed, true));
        }
        int total = intVar(rec, VAR_DRAW_TIME) + times;
        rec.varis.put(VAR_DRAW_TIME, Integer.toString(total));
        store.save(rec);

        if (useToken) {
            progress.markGoods(changed, token);
        }
        if (!changed.isEmpty()) {
            progress.pushGoods(session, pkt, rec, changed);
        }
        progress.pushDiamond(session, pkt, rec);

        session.send(retId, pkt, dump.zhuanPanDrawRet(awards, rowOf(rec, 0)));
        log.info("{} zhuanpan draw times={} token={} total={}", rec.account, Integer.valueOf(times),
                Boolean.valueOf(useToken), Integer.valueOf(total));
    }

    /** 抽一格 + 立即发奖。{@code grant=false} 时只算不奖（当前没有这种调用，留作池信息预览）。 */
    private PlayerDumpService.ZhuanPanDraw rollAndGrant(PlayerRecord rec, Map<String, Integer> changed,
                                                        boolean grant) {
        ZhuanPanCfg.Slot slot = cfg.roll(rng);
        float rate = cfg.backRate(slot.pos);
        PlayerDumpService.ZhuanPanDraw d = new PlayerDumpService.ZhuanPanDraw();
        d.pos = slot.pos;
        d.oriName = slot.oriName == null ? "" : slot.oriName;
        d.count = slot.count;
        d.star = slot.star;
        d.zuanShi = Math.round(slot.zuanShi * rate);
        d.jinBi = Math.round(slot.jinBi * rate);
        if (grant) {
            if (!d.oriName.isEmpty() && d.count > 0) {
                progress.addGoods(rec, d.oriName, d.count);
                progress.markGoods(changed, d.oriName);
            }
            if (d.jinBi > 0) {
                rec.gold += d.jinBi;
            }
            if (d.zuanShi > 0) {
                rec.diamond += d.zuanShi;
            }
        }
        d.isBig = d.zuanShi >= BIG_DIAMOND || d.star >= BIG_STAR;
        if (d.isBig) {
            addBigAward(rec, d);
        }
        return d;
    }

    private void addBigAward(PlayerRecord rec, PlayerDumpService.ZhuanPanDraw d) {
        PlayerDumpService.ZhuanPanBigAward b = new PlayerDumpService.ZhuanPanBigAward();
        b.player = rowOf(rec, 0);
        b.zuanshi = d.zuanShi;
        b.backRate = cfg.backRate(d.pos);
        synchronized (recentBig) {
            recentBig.add(0, b);
            while (recentBig.size() > BIG_KEEP) {
                recentBig.remove(recentBig.size() - 1);
            }
        }
    }

    // ------------------------------------------------------------------ 榜单

    /** 按累计抽奖次数排序（假服无原厂榜单表，用真实玩家口径，不用机器人）。 */
    private List<PlayerDumpService.ZhuanPanRankRow> rankRows() {
        List<PlayerRecord> all = new ArrayList<PlayerRecord>();
        for (PlayerRecord p : store.all()) {
            if (p != null && intVar(p, VAR_DRAW_TIME) > 0) {
                all.add(p);
            }
        }
        Collections.sort(all, new Comparator<PlayerRecord>() {
            @Override
            public int compare(PlayerRecord a, PlayerRecord b) {
                return Integer.compare(intVar(b, VAR_DRAW_TIME), intVar(a, VAR_DRAW_TIME));
            }
        });
        List<PlayerDumpService.ZhuanPanRankRow> rows = new ArrayList<PlayerDumpService.ZhuanPanRankRow>();
        int n = Math.min(50, all.size());
        for (int i = 0; i < n; i++) {
            rows.add(rowOf(all.get(i), i + 1));
        }
        return rows;
    }

    private PlayerDumpService.ZhuanPanRankRow rowOf(PlayerRecord rec, int rank) {
        PlayerDumpService.ZhuanPanRankRow r = new PlayerDumpService.ZhuanPanRankRow();
        r.guid = rec.playerId;
        r.name = rec.roleName == null ? "" : rec.roleName;
        r.unionName = unionNameOf(rec.playerId);
        r.resId = rec.mainHeroIndex;
        r.level = Math.max(1, rec.level);
        r.serverId = 1;
        r.drawTime = intVar(rec, VAR_DRAW_TIME);
        r.rank = rank;
        return r;
    }

    private String unionNameOf(int playerId) {
        return arena.unionNameFor(playerId);
    }

    // ------------------------------------------------------------------ 小工具

    private boolean requirePlayer(GameSession session) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            log.info("zhuanpan request without player");
            return false;
        }
        return true;
    }

    private int countBag(PlayerRecord rec, String ori) {
        Integer n = rec.bag.get(ori);
        return n == null ? 0 : n;
    }

    private void spendBag(PlayerRecord rec, String ori, int count) {
        int left = countBag(rec, ori) - count;
        if (left <= 0) {
            rec.bag.remove(ori);
        } else {
            rec.bag.put(ori, left);
        }
    }

    private int intVar(PlayerRecord rec, String key) {
        if (rec == null || rec.varis == null) {
            return 0;
        }
        String v = rec.varis.get(key);
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
