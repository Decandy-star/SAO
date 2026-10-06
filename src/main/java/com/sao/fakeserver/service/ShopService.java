package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.ActExtCfg;
import com.sao.fakeserver.table.EconomyTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class ShopService {
    private static final Logger log = LoggerFactory.getLogger(ShopService.class);
    private final Random rng = new Random();

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final EconomyTables tables;
    private final TaskService task;
    private final SessionHub sessions;
    private final ActExtCfg actExt;

    public ShopService(PlayerStore store, PlayerDumpService dump, ProgressService progress,
                       EconomyTables tables, TaskService task, SessionHub sessions, ActExtCfg actExt) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.tables = tables;
        this.task = task;
        this.sessions = sessions;
        this.actExt = actExt;
    }

    public void onShopInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        int type = Pb.read(pkt.body).getInt(1, 1);
        EconomyTables.ShopCfg cfg = tables.shop(type);
        if (belowOpenLevel(rec, cfg)) {
            session.send(MsgIds.S2C_SHOP_INFO, pkt, dump.shopInfo(emptyShop(type)));
            return;
        }
        PlayerRecord.ShopState existing = rec.shops.get(Integer.valueOf(type));
        int[] hours = cfg == null ? null : cfg.refreshHours;
        int lastSlot = 0;
        if (existing != null) {
            lastSlot = existing.lastSysSlot;
            if (lastSlot <= 0 && existing.lastSys > 0f && hours != null) {
                LocalDateTime t = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond((long) existing.lastSys), EconomyTables.SHOP_ZONE);
                lastSlot = EconomyTables.latestPassedShopSlot(hours, t);
                existing.lastSysSlot = lastSlot;
            }
        }
        boolean clock = EconomyTables.shopDueSysRefresh(hours, lastSlot, System.currentTimeMillis());
        PlayerRecord.ShopState shop = ensureShop(rec, type, clock);
        store.save(rec);
        session.send(MsgIds.S2C_SHOP_INFO, pkt, dump.shopInfo(shop));
    }

    /**
     * 表整点（9/12/18/21 上海）对在线号：已开店类型重 roll 货架，再推 S2C 1701 {@code CCMsgShopType}。
     * 店内 UI 收 1701 → EN_SHOP_NOTIFY → 立刻 C2S 1301 拉 1702。
     */
    public void pushClockRefreshToOnline() {
        int n = 0;
        for (GameSession session : sessions.onlineSnapshot()) {
            if (notifySysRefresh(session)) {
                n++;
            }
        }
        if (n > 0) {
            log.info("shop sys-refresh notify online={}", Integer.valueOf(n));
        }
    }

    private boolean notifySysRefresh(GameSession session) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return false;
        }
        boolean any = false;
        for (int type = 1; type <= 5; type++) {
            EconomyTables.ShopCfg cfg = tables.shop(type);
            if (belowOpenLevel(rec, cfg)) {
                continue;
            }
            ensureShop(rec, type, true);
            session.send(MsgIds.S2C_NOTIFY_SHOP_REFRESH, 0, dump.shopType(type));
            any = true;
        }
        if (any) {
            store.save(rec);
        }
        return any;
    }

    public void onBuyShop(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        Pb.Fields f = Pb.read(pkt.body);
        int type = f.getInt(1, 1);
        int field = f.getInt(2, 0);
        if (belowOpenLevel(rec, tables.shop(type))) {
            return;
        }
        PlayerRecord.ShopState shop = ensureShop(rec, type, false);
        if (field < 0 || field >= shop.fields.size()) {
            return;
        }
        PlayerRecord.ShopField slot = shop.fields.get(field);
        if (slot.sellOut || slot.ori == null || slot.ori.isEmpty()) {
            return;
        }
        if (!payShop(rec, type, slot)) {
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        progress.addGoods(rec, slot.ori, Math.max(1, slot.count));
        progress.markGoods(changed, slot.ori);
        slot.sellOut = true;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        pushShopCurrency(session, pkt, rec, type, slot.rmb);
        session.send(MsgIds.S2C_SHOP_SELLOUT, pkt, dump.shopSellOut(type, field));
    }

    public void onRefreshShop(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        int type = Pb.read(pkt.body).getInt(1, 1);
        EconomyTables.ShopCfg cfg = tables.shop(type);
        if (belowOpenLevel(rec, cfg)) {
            return;
        }
        int cost = cfg == null ? 50 : cfg.refreshDiamond;
        PlayerRecord.ShopState shop = ensureShop(rec, type, false);
        if (shop.freeRefresh < (cfg == null ? 0 : cfg.freeRefresh)) {
            shop.freeRefresh++;
        } else {
            if (rec.diamond < cost) {
                return;
            }
            rec.diamond -= cost;
            shop.payRefresh++;
            progress.pushDiamond(session, pkt, rec);
        }
        ensureShop(rec, type, true);
        store.save(rec);
        session.send(MsgIds.S2C_SHOP_INFO, pkt, dump.shopInfo(rec.shops.get(Integer.valueOf(type))));
    }

    public void onBuyTiLi(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        int max = tables.buyTiLiMaxTimes(rec.economy.chargedDiamond);
        if (rec.economy.buyTiLiToday >= max) {
            return;
        }
        EconomyTables.BuyRow row = tables.buyTiLi(rec.economy.buyTiLiToday + 1);
        int cost = row == null ? 50 : row.cost;
        int add = row == null ? 150 : Math.max(1, row.gain);
        if (rec.diamond < cost) {
            return;
        }
        rec.diamond -= cost;
        rec.stamina += add;
        rec.economy.buyTiLiToday++;
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        progress.pushPlayerProgress(session, pkt, rec, false, false, true, false, false);
        session.send(MsgIds.S2C_BUY_TILI_RET, pkt, dump.buyTiLiRet(add));
    }

    public void onBuyJinBi(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        int max = tables.buyJinBiMaxTimes(rec.economy.chargedDiamond);
        if (rec.economy.buyJinBiToday >= max) {
            return;
        }
        EconomyTables.BuyRow row = tables.buyJinBi(rec.economy.buyJinBiToday + 1);
        int cost = row == null ? 2 : row.cost;
        if (rec.diamond < cost) {
            return;
        }
        rec.diamond -= cost;
        rec.economy.buyJinBiToday++;
        int baoji = rng.nextInt(4) == 0 ? 2 : 1;
        int add = (row == null ? 20000 : Math.max(1, row.gain)) * baoji;
        rec.gold += add;
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_BUY_JINBI_RET, pkt, dump.buyJinBiRet(cost, add, baoji));
        task.onDailyAction(session, pkt, rec, TaskService.DAILY_BUY_GOLD, 1);
    }

    public void onExchangeBuy(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        String ori = f.getString(1);
        int num = Math.max(1, f.getInt(2, 1));
        EconomyTables.ExchangeRow row = tables.exchange(ori);
        if (row == null) {
            return;
        }
        int wnsp = row.wnsp * num;
        int dust = row.moFaChen * num;
        if (rec.wannengFragments < wnsp || rec.moFaChen < dust) {
            return;
        }
        rec.wannengFragments -= wnsp;
        rec.moFaChen -= dust;
        Map<String, Integer> changed = progress.emptyChanged();
        progress.addGoods(rec, ori, row.count * num);
        progress.markGoods(changed, ori);
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushWnsp(session, pkt, rec);
        progress.pushMoFaChen(session, pkt, rec);
    }

    public void onExchangeInfo(GameSession session, GamePacket pkt) {
        int subId = Pb.read(pkt.body).getInt(1, 0);
        ActExtCfg.ExchangeTab tab = actExt.exchangeTab(subId);
        session.send(MsgIds.S2C_EXCHANGE_GOODS_INFO, pkt, dump.actExchangeInfo(subId, tab));
    }

    public void onExchangeDo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int subId = f.getInt(1, 0);
        int id = f.getInt(2, 0);
        ActExtCfg.ExchangeRow row = actExt.exchangeRow(subId, id);
        if (row == null) {
            return;
        }
        for (ActExtCfg.ExchangeMat m : row.materials) {
            if (!progress.hasGoods(rec, m.ori, m.count)) {
                return;
            }
        }
        Map<String, Integer> changed = progress.emptyChanged();
        for (ActExtCfg.ExchangeMat m : row.materials) {
            if (!progress.consumeGoods(rec, m.ori, m.count)) {
                return;
            }
            progress.markGoods(changed, m.ori);
        }
        progress.addGoods(rec, row.getOri, row.getCount);
        progress.markGoods(changed, row.getOri);
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_EXCHANGE_GOODS_DO, pkt, dump.actExchangeDo(subId, id));
    }

    private static boolean belowOpenLevel(PlayerRecord rec, EconomyTables.ShopCfg cfg) {
        return cfg != null && rec.level < cfg.openLevel;
    }

    private static PlayerRecord.ShopState emptyShop(int type) {
        PlayerRecord.ShopState shop = new PlayerRecord.ShopState();
        shop.type = type;
        shop.fields = new ArrayList<>();
        return shop;
    }

    private PlayerRecord.ShopState ensureShop(PlayerRecord rec, int type, boolean force) {
        PlayerRecord.ShopState shop = rec.shops.get(Integer.valueOf(type));
        if (shop == null) {
            shop = new PlayerRecord.ShopState();
            shop.type = type;
            rec.shops.put(Integer.valueOf(type), shop);
            force = true;
        }
        if (shop.fields == null) {
            shop.fields = new ArrayList<>();
        }
        EconomyTables.ShopCfg cfg = tables.shop(type);
        int n = cfg == null ? 8 : cfg.fieldCount;
        if (force || shop.fields.size() != n) {
            shop.fields = rollFields(rec, type, n);
            LocalDateTime now = LocalDateTime.now(EconomyTables.SHOP_ZONE);
            shop.lastSys = (float) now.atZone(EconomyTables.SHOP_ZONE).toEpochSecond();
            shop.lastSysSlot = EconomyTables.latestPassedShopSlot(cfg == null ? null : cfg.refreshHours, now);
        }
        return shop;
    }

    private List<PlayerRecord.ShopField> rollFields(PlayerRecord rec, int type, int n) {
        int lv = Math.max(1, rec.level);
        List<EconomyTables.ShopOffer> pool = filterOffers(tables.shopOffers(type), lv);
        if (pool.isEmpty()) {
            pool = lowestMinLevelOffers(tables.shopOffers(type));
        }
        List<PlayerRecord.ShopField> fields = new ArrayList<>();
        if (pool.isEmpty()) {
            PlayerRecord.ShopField f = new PlayerRecord.ShopField();
            f.ori = "GOODS104";
            f.count = 1;
            f.price = 100;
            fields.add(f);
            return fields;
        }
        List<EconomyTables.ShopOffer> bag = new ArrayList<>(pool);
        for (int i = 0; i < n; i++) {
            if (bag.isEmpty()) {
                bag = new ArrayList<>(pool);
            }
            EconomyTables.ShopOffer offer = pickWeighted(bag);
            bag.remove(offer);
            EconomyTables.GoodsCfg g = tables.goods(offer.ori);
            PlayerRecord.ShopField f = new PlayerRecord.ShopField();
            f.ori = offer.ori;
            int cmin = offer.countMin;
            int cmax = offer.countMax;
            f.count = cmin >= cmax ? cmin : (cmin + rng.nextInt(cmax - cmin + 1));
            int quality = g == null ? 1 : Math.max(1, g.quality);
            int gold = g == null ? 100 : Math.max(10, g.goldPrice);
            if (type == 1) {
                f.rmb = offer.preferRmb || rng.nextInt(8) == 0;
                f.price = f.rmb ? Math.max(5, gold / 200) : Math.max(10, gold);
            } else if (type == 2) {
                f.price = Math.max(1, quality * 8 + gold / 100);
            } else if (type == 3) {
                f.price = Math.max(1, quality * 25 + Math.max(0, gold / 80));
            } else if (type == 4) {
                // 公会店：货单带 GoodsList col8 公会货币基价（见 EconomyTables.buildShopCatalogs），
                // 缺基价的行（如 ZBSX 之外的 a4）才退回按品质/金币价推算。
                f.price = offer.price > 0 ? offer.price
                        : Math.max(1, quality * 12 + Math.max(0, gold / 120));
            } else {
                // 英魄店：q4 碎片约 42；一轮 BOB 满清约 223 英魄 → 约 5 片
                f.price = Math.max(15, 10 + quality * 8);
            }
            fields.add(f);
        }
        return fields;
    }

    private static List<EconomyTables.ShopOffer> lowestMinLevelOffers(List<EconomyTables.ShopOffer> src) {
        List<EconomyTables.ShopOffer> out = new ArrayList<>();
        if (src == null || src.isEmpty()) {
            return out;
        }
        int min = Integer.MAX_VALUE;
        for (EconomyTables.ShopOffer o : src) {
            if (o != null && o.minLevel < min) {
                min = o.minLevel;
            }
        }
        for (EconomyTables.ShopOffer o : src) {
            if (o != null && o.minLevel == min) {
                out.add(o);
            }
        }
        return out;
    }

    private static List<EconomyTables.ShopOffer> filterOffers(List<EconomyTables.ShopOffer> src, int level) {
        List<EconomyTables.ShopOffer> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (EconomyTables.ShopOffer o : src) {
            if (o != null && o.minLevel <= level) {
                out.add(o);
            }
        }
        return out;
    }

    private EconomyTables.ShopOffer pickWeighted(List<EconomyTables.ShopOffer> bag) {
        int sum = 0;
        for (EconomyTables.ShopOffer o : bag) {
            sum += Math.max(1, o.weight);
        }
        int r = rng.nextInt(Math.max(1, sum));
        int acc = 0;
        for (EconomyTables.ShopOffer o : bag) {
            acc += Math.max(1, o.weight);
            if (r < acc) {
                return o;
            }
        }
        return bag.get(bag.size() - 1);
    }

    private boolean payShop(PlayerRecord rec, int type, PlayerRecord.ShopField slot) {
        if (type == 1) {
            if (slot.rmb) {
                if (rec.diamond < slot.price) {
                    return false;
                }
                rec.diamond -= slot.price;
            } else {
                if (rec.gold < slot.price) {
                    return false;
                }
                rec.gold -= slot.price;
            }
            return true;
        }
        if (type == 2) {
            if (rec.wannengFragments < slot.price) {
                return false;
            }
            rec.wannengFragments -= slot.price;
            return true;
        }
        if (type == 3) {
            if (rec.jjcScore < slot.price) {
                return false;
            }
            rec.jjcScore -= slot.price;
            return true;
        }
        if (type == 4) {
            if (rec.guild.brotherCoin < slot.price) {
                return false;
            }
            rec.guild.brotherCoin -= slot.price;
            return true;
        }
        if (rec.yingPo < slot.price) {
            return false;
        }
        rec.yingPo -= slot.price;
        return true;
    }

    private void pushShopCurrency(GameSession session, GamePacket pkt, PlayerRecord rec, int type, boolean rmb) {
        if (type == 1) {
            if (rmb) {
                progress.pushDiamond(session, pkt, rec);
            } else {
                progress.pushGold(session, pkt, rec);
            }
        } else if (type == 2) {
            progress.pushWnsp(session, pkt, rec);
        } else if (type == 3) {
            progress.pushJjcScore(session, pkt, rec);
        } else if (type == 4) {
            session.send(MsgIds.S2C_UNION_PLAYER_RES, pkt, dump.unionPlayerRes(rec));
        } else if (type == 5) {
            progress.pushYingPo(session, pkt, rec);
        }
    }
}
