package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.GachaPool;
import com.sao.fakeserver.table.GameTables;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class DrawService {
    private static final Logger log = LoggerFactory.getLogger(DrawService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DRAW_GOLD = 1;
    private static final int DRAW_DIAMOND = 2;
    /** 账号首次黄金宝箱单抽必出：猫妖弓箭手（WuJiangBaseAttri index=31） */
    private static final int FIRST_DIAMOND_ONCE_HERO = 31;

    private final Random rng = new Random();
    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final GameTables tables;
    private final CultivateTables cultivate;
    private final GachaPool pool;
    private final ProgressService progress;
    private final TaskService task;

    public DrawService(PlayerStore store, PlayerDumpService dump, GameTables tables, CultivateTables cultivate,
                       GachaPool pool, ProgressService progress, TaskService task) {
        this.store = store;
        this.dump = dump;
        this.tables = tables;
        this.cultivate = cultivate;
        this.pool = pool;
        this.progress = progress;
        this.task = task;
    }

    public void onGold(GameSession session, GamePacket pkt) {
        draw(session, pkt, DRAW_GOLD);
    }

    public void onDiamond(GameSession session, GamePacket pkt) {
        draw(session, pkt, DRAW_DIAMOND);
    }

    private void draw(GameSession session, GamePacket pkt, int type) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        boolean isSingle = Pb.read(pkt.body).getBool(1);
        int times = isSingle ? 1 : 10;
        GameTables.DrawConfig cfg = tables.draw();
        LocalDateTime now = GameTime.now();
        boolean free = false;
        boolean goldChanged = false;
        boolean rmbChanged = false;

        if (type == DRAW_GOLD) {
            progress.refreshGachaGoldFreeDaily(rec);
            if (isSingle && rec.gacha.goldFreeLeft > 0 && offCd(rec.gacha.lastGoldFreeAt, now, cfg.jbFreeCdSec)) {
                free = true;
                rec.gacha.goldFreeLeft--;
                rec.gacha.lastGoldFreeAt = now.format(TIME);
            } else {
                int cost = isSingle ? cfg.jbOnce : cfg.jbTen;
                if (rec.gold < cost) {
                    log.info("{} gold draw skipped, gold={}", rec.account, rec.gold);
                    return;
                }
                rec.gold -= cost;
                goldChanged = true;
            }
        } else {
            if (isSingle && offCd(rec.gacha.lastDiamondFreeAt, now, cfg.zsFreeCdSec)) {
                free = true;
                rec.gacha.lastDiamondFreeAt = now.format(TIME);
            } else {
                int cost = isSingle ? cfg.zsOnce : cfg.zsTenZheKouPrice(rec.gacha.diamondTenCount);
                if (rec.diamond < cost) {
                    log.info("{} diamond draw skipped, rmb={}", rec.account, rec.diamond);
                    return;
                }
                rec.diamond -= cost;
                rmbChanged = true;
                if (!isSingle) {
                    rec.gacha.diamondTenCount++;
                }
            }
        }

        List<byte[]> awards = new ArrayList<>();
        List<PlayerRecord.Hero> newWj = new ArrayList<>();
        List<PlayerRecord.Equipment> newEq = new ArrayList<>();
        Map<String, Integer> changed = new LinkedHashMap<>();
        boolean firstDiamondOnce = type == DRAW_DIAMOND && isSingle && rec.gacha.diamondOnceCount == 0;
        for (int i = 0; i < times; i++) {
            Roll roll = firstDiamondOnce && i == 0
                    ? rollForcedHero(rec, FIRST_DIAMOND_ONCE_HERO)
                    : rollOne(rec, type);
            if (roll.equip) {
                PlayerRecord.Equipment eq = progress.grantEquip(rec, roll.ori);
                newEq.add(eq);
                awards.add(dump.drawAwardEquip(eq.id));
            } else if (roll.heroIndex > 0 && !owns(rec, roll.heroIndex)) {
                PlayerRecord.Hero wj = newHero(rec, roll.heroIndex);
                rec.heroes.add(wj);
                newWj.add(wj);
                awards.add(dump.drawAwardHero(roll.heroIndex, "", 0));
            } else if (roll.heroIndex > 0) {
                progress.addGoods(rec, roll.ori, roll.count);
                changed.put(roll.ori, rec.bag.getOrDefault(roll.ori, 0));
                awards.add(dump.drawAwardHero(roll.heroIndex, roll.ori, roll.count));
            } else {
                progress.addGoods(rec, roll.ori, roll.count);
                changed.put(roll.ori, rec.bag.getOrDefault(roll.ori, 0));
                awards.add(dump.drawAwardItem(roll.ori, roll.count));
            }
        }

        if (type == DRAW_DIAMOND && isSingle) {
            rec.gacha.diamondOnceCount++;
        }

        int extra = 0;
        String extraOri = "";
        if (type == DRAW_DIAMOND && cfg.extraFragment != null && !cfg.extraFragment.isEmpty()) {
            int min = isSingle ? cfg.extraOnceMin : cfg.extraTenMin;
            int max = isSingle ? cfg.extraOnceMax : cfg.extraTenMax;
            extra = min + (max > min ? rng.nextInt(max - min + 1) : 0);
            if (extra > 0) {
                extraOri = cfg.extraFragment;
                progress.addGoods(rec, extraOri, extra);
                changed.put(extraOri, rec.bag.getOrDefault(extraOri, 0));
            }
        }
        store.save(rec);

        progress.pushPlayerProgress(session, pkt, rec, false, goldChanged, false, false, rmbChanged);
        progress.pushGoods(session, pkt, rec, changed);
        for (PlayerRecord.Equipment eq : newEq) {
            session.send(MsgIds.S2C_ADD_EQUIP, pkt, dump.equipment(eq));
        }
        for (PlayerRecord.Hero wj : newWj) {
            session.send(MsgIds.S2C_ADD_WUJIANG, pkt, dump.addWuJiang(wj, false));
        }
        session.send(MsgIds.S2C_DRAW_RET, pkt, dump.drawRet(type, isSingle, awards, extraOri, extra));
        session.send(MsgIds.S2C_DRAW_UPDATE, pkt, dump.drawUpdate(rec));
        if (type == DRAW_DIAMOND && !isSingle) {
            session.send(MsgIds.S2C_ZS10_PRICE, pkt, dump.zs10PriceInfo(rec));
        }
        task.onDailyAction(session, pkt, rec, TaskService.DAILY_DRAW, times);
        task.syncOnce(session, pkt, rec);
        log.info("{} draw type={} single={} free={} firstDiamondOnce={} awards={}",
                rec.account, type, isSingle, free, firstDiamondOnce, awards.size());
    }

    private boolean offCd(String last, LocalDateTime now, int cdSec) {
        if (last == null || last.isEmpty()) {
            return true;
        }
        try {
            return ChronoUnit.SECONDS.between(LocalDateTime.parse(last, TIME), now) >= cdSec;
        } catch (Exception e) {
            return true;
        }
    }

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
        // 新武将固定 1 级（勿跟账号等级 rec.level，否则十级号抽到的整将会直接 10 级）
        wj.level = 1;
        CultivateTables.HeroCfg cfg = cultivate.heroByIndex(index);
        wj.stars = cfg == null ? 1 : Math.max(1, cfg.composeStar);
        wj.fightPower = cultivate.computeFightPower(index, wj.level, wj.stars);
        return wj;
    }

    private Roll rollOne(PlayerRecord rec, int type) {
        boolean diamond = type == DRAW_DIAMOND;
        // 池里若仍残留怪/NPC index，重抽；十连内 owns 已含本轮新加的英雄，同 index 会折碎片
        for (int attempt = 0; attempt < 32; attempt++) {
            GachaPool.Entry e = pool.roll(diamond, rng);
            if (e.isEquip()) {
                return Roll.equip(e.ori);
            }
            if (e.isHero()) {
                if (!cultivate.isPlayableHero(e.heroIndex)) {
                    continue;
                }
                if (owns(rec, e.heroIndex)) {
                    CultivateTables.HeroCfg cfg = cultivate.heroByIndex(e.heroIndex);
                    String frag = e.fragmentOri;
                    if (frag == null || frag.isEmpty() || "0".equals(frag)) {
                        frag = cfg != null && cfg.fragmentOri != null && !cfg.fragmentOri.isEmpty()
                                ? cfg.fragmentOri : "SP048";
                    }
                    // 折算数 = WuJiangUpStar「N星英雄折算碎片数」，N = 该武将合成初始星级
                    int n = cultivate.chaiJieFragForHero(e.heroIndex);
                    return Roll.heroDup(e.heroIndex, frag, n);
                }
                return Roll.heroNew(e.heroIndex);
            }
            String ori = e.ori == null || e.ori.isEmpty() ? "SP048" : e.ori;
            int n = e.count > 0 ? e.count : 1;
            return Roll.item(ori, n);
        }
        log.warn("{} gacha roll fallback after invalid hero entries, type={}", rec.account, type);
        return Roll.item("SP048", 1);
    }

    /** 指定英雄：未拥有发整卡，已拥有按合成星级折碎片。 */
    private Roll rollForcedHero(PlayerRecord rec, int heroIndex) {
        if (owns(rec, heroIndex)) {
            CultivateTables.HeroCfg cfg = cultivate.heroByIndex(heroIndex);
            String frag = cfg != null && cfg.fragmentOri != null && !cfg.fragmentOri.isEmpty()
                    ? cfg.fragmentOri : "GOODS119";
            return Roll.heroDup(heroIndex, frag, cultivate.chaiJieFragForHero(heroIndex));
        }
        return Roll.heroNew(heroIndex);
    }

    private static final class Roll {
        final int heroIndex;
        final String ori;
        final int count;
        final boolean equip;

        private Roll(int heroIndex, String ori, int count, boolean equip) {
            this.heroIndex = heroIndex;
            this.ori = ori;
            this.count = count;
            this.equip = equip;
        }

        static Roll item(String ori, int count) {
            return new Roll(0, ori, count, false);
        }

        static Roll heroNew(int index) {
            return new Roll(index, "", 0, false);
        }

        static Roll heroDup(int index, String frag, int n) {
            return new Roll(index, frag, n, false);
        }

        static Roll equip(String ori) {
            return new Roll(0, ori, 1, true);
        }
    }
}
