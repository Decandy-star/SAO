package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.EconomyTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class CultivateService {
    private static final Logger log = LoggerFactory.getLogger(CultivateService.class);
    private final Random rng = new Random();

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final CultivateTables tables;
    private final EconomyTables economy;
    private final TaskService task;

    public CultivateService(PlayerStore store, PlayerDumpService dump, ProgressService progress,
                            CultivateTables tables, EconomyTables economy, TaskService task) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.tables = tables;
        this.economy = economy;
        this.task = task;
    }

    public void onComposeWuJiang(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        String fragOri = Pb.read(pkt.body).getString(1);
        CultivateTables.HeroCfg cfg = tables.heroByFragment(fragOri);
        if (cfg == null || rec.findHeroByIndex(cfg.index) != null) {
            log.info("{} compose wj skipped ori={}", rec.account, fragOri);
            return;
        }
        int need = tables.composeFragNeed(cfg.composeStar);
        if (need <= 0 || !progress.hasGoods(rec, fragOri, need)) {
            log.info("{} compose wj not enough {} need={}", rec.account, fragOri, need);
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        progress.consumeGoods(rec, fragOri, need);
        progress.markGoods(changed, fragOri);
        PlayerRecord.Hero wj = newHero(rec, cfg);
        rec.heroes.add(wj);
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_ADD_WUJIANG, pkt, dump.addWuJiang(wj, true));
        task.syncOnce(session, pkt, rec);
        log.info("{} compose wj index={} stars={}", rec.account, cfg.index, wj.stars);
    }

    /**
     * C2S 1201 更换主角形象：body=CCMsgWuJiangGuid。校验星级门槛后写 mainHeroIndex，回 S2C 1601。
     */
    public void onChangeAvatar(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        String guid = Pb.read(pkt.body).getString(1);
        PlayerRecord.Hero wj = rec.findHero(guid);
        if (wj == null) {
            log.info("{} change-avatar miss guid={}", rec.account, guid);
            return;
        }
        CultivateTables.HeroCfg cfg = tables.heroByIndex(wj.heroIndex);
        int needStar = cfg == null ? 1 : Math.max(0, cfg.exchangeModelStarLimit);
        if (wj.stars < needStar) {
            log.info("{} change-avatar deny index={} stars={} need={}",
                    rec.account, wj.heroIndex, wj.stars, needStar);
            return;
        }
        if (rec.mainHeroIndex == wj.heroIndex) {
            // 与客户端一致：已是当前形象不回包
            return;
        }
        rec.mainHeroIndex = wj.heroIndex;
        store.save(rec);
        session.send(MsgIds.S2C_UPDATE_RES_ID, pkt, dump.updateResId(rec.playerId, wj.heroIndex));
        log.info("{} change-avatar -> index={}", rec.account, wj.heroIndex);
    }

    public void onUpStar(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Hero wj = rec.findHero(Pb.read(pkt.body).getString(1));
        if (wj == null || wj.stars >= tables.starUpper()) {
            return;
        }
        CultivateTables.HeroCfg cfg = tables.heroByIndex(wj.heroIndex);
        String frag = cfg == null ? "" : cfg.fragmentOri;
        int needFrag = tables.starUpFragNeed(wj.stars);
        int needGold = tables.starUpGoldNeed(wj.stars);
        if (needFrag <= 0 || rec.gold < needGold || !progress.hasGoods(rec, frag, needFrag)) {
            return;
        }
        rec.gold -= needGold;
        Map<String, Integer> changed = progress.emptyChanged();
        progress.consumeGoods(rec, frag, needFrag);
        progress.markGoods(changed, frag);
        wj.stars++;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_WUJIANG_ATTRI_UPDATE, pkt, dump.wuJiangAttri(wj.id, 4, wj.stars));
        pushSuitAndFightPower(session, pkt, rec, wj);
    }

    public void onJinJieSlot(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        PlayerRecord.Hero wj = rec.findHero(f.getString(1));
        int slot = f.getInt(2, 0);
        if (wj == null || slot < 1 || slot > 4) {
            return;
        }
        CultivateTables.HeroCfg hero = tables.heroByIndex(wj.heroIndex);
        if (hero == null) {
            return;
        }
        CultivateTables.JinJieCfg jj = tables.jinJie(hero.jinJieType[slot - 1]);
        if (jj == null || wj.stage < 0 || wj.stage >= 10) {
            return;
        }
        int need = Math.max(1, jj.count[wj.stage]);
        if (wj.stagePara(slot) >= need) {
            return;
        }
        String book = jj.ori[wj.stage];
        if (!progress.hasGoods(rec, book, 1)) {
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        progress.consumeGoods(rec, book, 1);
        progress.markGoods(changed, book);
        wj.setStagePara(slot, wj.stagePara(slot) + 1);
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_WUJIANG_ATTRI_UPDATE, pkt,
                dump.wuJiangAttri(wj.id, 4 + slot, wj.stagePara(slot)));
        pushSuitAndFightPower(session, pkt, rec, wj);
    }

    public void onJinJieConfirm(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Hero wj = rec.findHero(Pb.read(pkt.body).getString(1));
        if (wj == null || wj.stage >= 10) {
            return;
        }
        CultivateTables.HeroCfg hero = tables.heroByIndex(wj.heroIndex);
        if (hero == null) {
            return;
        }
        for (int slot = 1; slot <= 4; slot++) {
            CultivateTables.JinJieCfg jj = tables.jinJie(hero.jinJieType[slot - 1]);
            int need = jj == null ? 1 : Math.max(1, jj.count[wj.stage]);
            if (wj.stagePara(slot) < need) {
                return;
            }
        }
        wj.stage++;
        wj.stagePara1 = 0;
        wj.stagePara2 = 0;
        wj.stagePara3 = 0;
        wj.stagePara4 = 0;
        wj.fightPower = tables.computeFightPower(rec, wj);
        store.save(rec);
        session.send(MsgIds.S2C_WUJIANG_ATTRI_UPDATE, pkt, dump.wuJiangAttri(wj.id, 3, wj.stage));
        for (int slot = 1; slot <= 4; slot++) {
            session.send(MsgIds.S2C_WUJIANG_ATTRI_UPDATE, pkt, dump.wuJiangAttri(wj.id, 4 + slot, 0));
        }
        pushSuitAndFightPower(session, pkt, rec, wj);
        task.syncOnce(session, pkt, rec);
    }

    public void onSkillUp(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        PlayerRecord.Hero wj = rec.findHero(f.getString(1));
        int skillIndex = f.getInt(2, 0);
        if (wj == null || skillIndex < 1 || skillIndex > 4 || rec.skillPoints <= 0) {
            return;
        }
        if (!tables.skillUnlocked(skillIndex, wj.stage)) {
            return;
        }
        int next = wj.skill(skillIndex) + 1;
        // 上限用**武将等级**而非账号等级：客户端 MainPlayer.cs:1879/1905/1931/1957 判据为
        // `wujiang.Level > skillLevel + <XxxMinuLevel>`，即 next <= wj.level - minu
        //（与 CultivateTables.skillMaxLevel(skillIndex, level) = max(1, level - minu) 同式）。
        if (next > tables.skillMaxLevel(skillIndex, wj.level)) {
            return;
        }
        int gold = tables.skillGold(skillIndex, next);
        if (rec.gold < gold) {
            return;
        }
        rec.gold -= gold;
        rec.skillPoints--;
        progress.ensureSkillPointRecoveryAnchor(rec);
        wj.setSkill(skillIndex, next);
        wj.fightPower = tables.computeFightPower(rec, wj);
        store.save(rec);
        progress.pushGold(session, pkt, rec);
        progress.pushSkillPoints(session, pkt, rec);
        session.send(MsgIds.S2C_WUJIANG_ATTRI_UPDATE, pkt, dump.wuJiangAttri(wj.id, 8 + skillIndex, next));
        pushSuitAndFightPower(session, pkt, rec, wj);
        task.onDailyAction(session, pkt, rec, TaskService.DAILY_SKILL_UP, 1);
    }

    public void onJinJieBookCompose(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        String ori = Pb.read(pkt.body).getString(1);
        CultivateTables.ComposeRecipe recipe = tables.bookRecipe(ori);
        if (recipe == null || !payRecipe(rec, recipe, progress.emptyChanged(), true)) {
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        payRecipe(rec, recipe, changed, false);
        progress.addGoods(rec, ori, 1);
        progress.markGoods(changed, ori);
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        if (recipe.gold > 0) {
            progress.pushGold(session, pkt, rec);
        }
        session.send(MsgIds.S2C_JINJIE_BOOK_COMPOSE_RET, pkt, dump.jinJieBookRet(ori));
    }

    public void onBuySkillPoints(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int add = tables.buySkillPointCount();
        int cost = tables.buySkillPointRmb() * add;
        int room = tables.maxSkillPoint() - rec.skillPoints;
        if (room <= 0 || rec.diamond < cost) {
            return;
        }
        rec.diamond -= cost;
        rec.skillPoints = Math.min(tables.maxSkillPoint(), rec.skillPoints + add);
        progress.ensureSkillPointRecoveryAnchor(rec);
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        progress.pushSkillPoints(session, pkt, rec);
    }

    public void onComposeEquip(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        String ori = Pb.read(pkt.body).getString(1);
        CultivateTables.ComposeRecipe recipe = tables.equipRecipe(ori);
        if (recipe == null || tables.equip(ori) == null) {
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        if (!payRecipe(rec, recipe, changed, true)) {
            return;
        }
        payRecipe(rec, recipe, changed, false);
        PlayerRecord.Equipment eq = newEquip(rec, ori);
        rec.equipments.add(eq);
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        if (recipe.gold > 0) {
            progress.pushGold(session, pkt, rec);
        }
        session.send(MsgIds.S2C_ADD_EQUIP, pkt, dump.equipment(eq));
        session.send(MsgIds.S2C_COMPOSE_EQUIP_RET, pkt, dump.equipGuid(eq.id));
        log.info("{} compose equip {} {}", rec.account, ori, eq.id);
    }

    public void onPutOn(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        PlayerRecord.Equipment eq = rec.findEquip(f.getString(1));
        PlayerRecord.Hero wj = rec.findHero(f.getString(2));
        if (eq == null || wj == null) {
            return;
        }
        CultivateTables.EquipCfg cfg = tables.equip(eq.ori);
        if (cfg == null) {
            return;
        }
        // 职业门：客户端 WuJiangContainerSystem.cs:1797 的可穿戴谓词要求
        // 武器/配件 equipJobType == 武将 mWeaponJobAttribute/mPeiJianJobAttribute（1104 自动穿戴走同一谓词），
        // 1102 直接穿戴改前只查装备/武将存在 ⇒ 能把任何职业的武器塞给任何武将
        CultivateTables.HeroCfg heroCfg = tables.heroByIndex(wj.heroIndex);
        if (heroCfg != null && !tables.canWear(heroCfg, cfg)) {
            return;
        }
        PlayerRecord.Equipment old = worn(rec, wj.id, cfg.type);
        if (old != null && old != eq) {
            old.owner = "";
            session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(old.id, 1, 0, ""));
        }
        eq.owner = wj.id;
        store.save(rec);
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 1, 0, wj.id));
        pushSuitAndFightPower(session, pkt, rec, wj);
    }

    public void onPutOff(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Equipment eq = rec.findEquip(Pb.read(pkt.body).getString(1));
        if (eq == null) {
            return;
        }
        String ownerGuid = eq.owner;
        eq.owner = "";
        store.save(rec);
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 1, 0, ""));
        PlayerRecord.Hero wj = ownerGuid == null || ownerGuid.isEmpty() ? null : rec.findHero(ownerGuid);
        if (wj != null) {
            pushSuitAndFightPower(session, pkt, rec, wj);
        }
    }

    public void onAutoPutOn(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Hero wj = rec.findHero(Pb.read(pkt.body).getString(1));
        if (wj == null) {
            return;
        }
        boolean changed = false;
        CultivateTables.HeroCfg heroCfg = tables.heroByIndex(wj.heroIndex);
        for (int type = 1; type <= 5; type++) {
            PlayerRecord.Equipment best = bestFree(rec, type, heroCfg);
            if (best == null) {
                continue;
            }
            PlayerRecord.Equipment old = worn(rec, wj.id, type);
            if (old != null && !better(best, old, wj.heroIndex)) {
                continue;
            }
            if (old != null) {
                old.owner = "";
                session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(old.id, 1, 0, ""));
            }
            best.owner = wj.id;
            session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(best.id, 1, 0, wj.id));
            changed = true;
        }
        if (changed) {
            store.save(rec);
            pushSuitAndFightPower(session, pkt, rec, wj);
        }
    }

    public void onAutoPutOff(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        String guid = Pb.read(pkt.body).getString(1);
        boolean changed = false;
        for (PlayerRecord.Equipment eq : rec.equipments) {
            if (guid.equals(eq.owner)) {
                eq.owner = "";
                session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 1, 0, ""));
                changed = true;
            }
        }
        if (changed) {
            store.save(rec);
            PlayerRecord.Hero wj = rec.findHero(guid);
            if (wj != null) {
                pushSuitAndFightPower(session, pkt, rec, wj);
            }
        }
    }

    public void onLevelUp(GameSession session, GamePacket pkt) {
        levelUpOnce(session, pkt, false);
    }

    public void onAutoLevelUp(GameSession session, GamePacket pkt) {
        levelUpOnce(session, pkt, true);
    }

    public void onStarUp(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Equipment eq = rec.findEquip(Pb.read(pkt.body).getString(1));
        CultivateTables.EquipCfg cfg = eq == null ? null : tables.equip(eq.ori);
        if (eq == null || cfg == null || eq.stars >= CultivateTables.MAX_EQUIP_STAR) {
            return;
        }
        CultivateTables.StarCost cost = tables.starCost(cfg.quality, eq.stars);
        if (cost == null || rec.level < cost.playerLevel || rec.gold < cost.gold) {
            return;
        }
        if (!hasMats(rec, cost.mats) || countFreeSameOri(rec, eq) < cost.equipCost) {
            return;
        }
        rec.gold -= cost.gold;
        Map<String, Integer> changed = progress.emptyChanged();
        consumeMats(rec, cost.mats, changed);
        eatSameOri(session, pkt, rec, eq, cost.equipCost);
        eq.stars++;
        eq.guhua = 0;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 3, eq.stars, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 4, eq.guhua, ""));
        pushFightPowerIfOwned(session, pkt, rec, eq);
        task.syncOnce(session, pkt, rec);
    }

    /**
     * 分解页签准入：白板件 = 未强化（level==1）、0 星、未固化、精炼/淬炼全零。
     * 与 APK {@code EquipmentDecomposeUI.cs:137}（进退星页的条件取反）+ {@code :146}
     * （{@code Stars == 0} 才进分解页）同口径。
     */
    private static boolean isDecomposable(PlayerRecord.Equipment eq) {
        return eq.level <= 1 && eq.stars == 0 && eq.guhua == 0
                && eq.jingLianLevel == 0 && eq.jingLianExp == 0
                && eq.jingLianSubLevel1 == 0 && eq.jingLianSubExp1 == 0
                && eq.jingLianSubLevel2 == 0 && eq.jingLianSubExp2 == 0;
    }

    public void onDecompose(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        List<String> guids = f.getStrings(1);
        int gold = 0;
        int wnsp = 0;
        Map<String, Integer> loot = new LinkedHashMap<>();
        Map<String, Integer> bagChanged = progress.emptyChanged();
        List<PlayerRecord.Equipment> remove = new ArrayList<>();
        for (String guid : unique(guids)) {
            PlayerRecord.Equipment eq = rec.findEquip(guid);
            if (eq == null || (eq.owner != null && !eq.owner.isEmpty())) {
                continue;
            }
            // 只有白板件能进分解页签（APK EquipmentDecomposeUI.cs:137/146）：
            // 强化过/升过星/固化过/精炼淬炼过的一律拒绝该件，既不删件也不给返还。
            if (!isDecomposable(eq)) {
                continue;
            }
            CultivateTables.EquipCfg cfg = tables.equip(eq.ori);
            int quality = cfg == null ? 1 : cfg.quality;
            CultivateTables.DecomposeRow row = tables.decomposeEquip(quality, eq.stars);
            if (row != null) {
                gold += row.gold;
                wnsp += row.wnsp;
                for (CultivateTables.Mat m : row.goods) {
                    loot.merge(m.ori, m.count, Integer::sum);
                }
            }
            remove.add(eq);
        }
        for (byte[] tuziBytes : f.getBytesList(2)) {
            Pb.Fields t = Pb.read(tuziBytes);
            String ori = t.getString(1);
            int count = t.getInt(2, 0);
            if (count <= 0 || !progress.hasGoods(rec, ori, count)) {
                continue;
            }
            progress.consumeGoods(rec, ori, count);
            progress.markGoods(bagChanged, ori);
            CultivateTables.DecomposeRow row = tables.decomposeTuZi(tables.goodsQuality(ori));
            if (row != null) {
                gold += row.gold * count;
                wnsp += row.wnsp * count;
                for (CultivateTables.Mat m : row.goods) {
                    loot.merge(m.ori, m.count * count, Integer::sum);
                }
            }
        }
        rec.equipments.removeAll(remove);
        rec.gold += gold;
        rec.wannengFragments += wnsp;
        for (Map.Entry<String, Integer> e : loot.entrySet()) {
            progress.addGoods(rec, e.getKey(), e.getValue());
            progress.markGoods(bagChanged, e.getKey());
        }
        store.save(rec);
        for (PlayerRecord.Equipment eq : remove) {
            session.send(MsgIds.S2C_REMOVE_EQUIP, pkt, dump.equipGuid(eq.id));
        }
        progress.pushGoods(session, pkt, rec, bagChanged);
        if (gold > 0) {
            progress.pushGold(session, pkt, rec);
        }
        if (wnsp > 0) {
            progress.pushWnsp(session, pkt, rec);
        }
        session.send(MsgIds.S2C_DECOMPOSE_EQUIP_RET, pkt, dump.decomposeRet(gold, wnsp, loot));
    }

    public void onOneKeyLevelUp(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        String wjGuid = Pb.read(pkt.body).getString(1);
        List<byte[]> items = new ArrayList<>();
        boolean goldChanged = false;
        for (PlayerRecord.Equipment eq : rec.equipments) {
            if (!wjGuid.equals(eq.owner)) {
                continue;
            }
            int pre = eq.level;
            int extra = 0;
            int saved = 0;
            boolean any = false;
            while (true) {
                LevelUpStep step = tryLevelUp(rec, eq);
                if (step == null) {
                    break;
                }
                any = true;
                extra += step.jump - 1;
                saved += step.saved;
            }
            if (!any) {
                continue;
            }
            goldChanged = true;
            session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 2, eq.level, ""));
            session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 5, eq.uplevelGold, ""));
            items.add(dump.oneKeyLevelUpItem(eq.id, pre, saved, extra));
        }
        if (items.isEmpty()) {
            // 零成果也要回 1410：客户端 EquipOneKeyLevelUpRet.cs:118 收到回包才结束等待态
            session.send(MsgIds.S2C_EQUIP_ONE_KEY_LEVEL_UP_RET, pkt, dump.oneKeyLevelUpRet(items));
            return;
        }
        store.save(rec);
        if (goldChanged) {
            progress.pushGold(session, pkt, rec);
        }
        session.send(MsgIds.S2C_EQUIP_ONE_KEY_LEVEL_UP_RET, pkt, dump.oneKeyLevelUpRet(items));
        PlayerRecord.Hero wj = rec.findHero(wjGuid);
        if (wj != null) {
            pushSuitAndFightPower(session, pkt, rec, wj);
        }
    }

    public void onGuhua(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Equipment eq = rec.findEquip(Pb.read(pkt.body).getString(1));
        CultivateTables.EquipCfg cfg = eq == null ? null : tables.equip(eq.ori);
        if (eq == null || cfg == null || eq.guhua >= CultivateTables.MAX_GUHUA) {
            return;
        }
        CultivateTables.GuHuaCost cost = tables.guhuaCost(cfg.quality, eq.stars);
        if (cost == null || rec.gold < cost.gold || !progress.hasGoods(rec, cost.matOri, cost.matCount)) {
            return;
        }
        rec.gold -= cost.gold;
        Map<String, Integer> changed = progress.emptyChanged();
        progress.consumeGoods(rec, cost.matOri, cost.matCount);
        progress.markGoods(changed, cost.matOri);
        eq.guhua++;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 4, eq.guhua, ""));
        pushFightPowerIfOwned(session, pkt, rec, eq);
    }

    public void onOpenCuiLian(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Equipment eq = rec.findEquip(Pb.read(pkt.body).getString(1));
        CultivateTables.CuiLianCost cost = tables.openCuiLian();
        if (eq == null || eq.openCuiLian || cost == null || rec.gold < cost.gold) {
            return;
        }
        if (!hasMats(rec, cost.mats) || countFreeSameOri(rec, eq) < cost.equipCost) {
            return;
        }
        rec.gold -= cost.gold;
        Map<String, Integer> changed = progress.emptyChanged();
        consumeMats(rec, cost.mats, changed);
        eatSameOri(session, pkt, rec, eq, cost.equipCost);
        eq.openCuiLian = true;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 6, eq.cuiLianStatus(), ""));
        session.send(MsgIds.S2C_EQUIP_OPEN_CUILIAN_RET, pkt, new byte[0]);
        pushFightPowerIfOwned(session, pkt, rec, eq);
    }

    public void onCuiLianPart(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        PlayerRecord.Equipment eq = rec.findEquip(f.getString(1));
        int partId = f.getInt(2, 0);
        int idx = partId - 1;
        CultivateTables.PartCost cost = eq == null ? null : tables.cuiLianPart(eq.stars);
        if (eq == null || !eq.openCuiLian || idx < 0 || idx >= 4 || eq.cuiLianParts[idx] || cost == null) {
            return;
        }
        if (rec.gold < cost.gold || !hasMats(rec, cost.mats)) {
            return;
        }
        rec.gold -= cost.gold;
        Map<String, Integer> changed = progress.emptyChanged();
        consumeMats(rec, cost.mats, changed);
        eq.cuiLianParts[idx] = true;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 6, eq.cuiLianStatus(), ""));
        session.send(MsgIds.S2C_EQUIP_CUILIAN_PART_RET, pkt, new byte[0]);
        pushFightPowerIfOwned(session, pkt, rec, eq);
    }

    public void onFeiYue(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Equipment eq = rec.findEquip(Pb.read(pkt.body).getString(1));
        CultivateTables.CuiLianCost cost = eq == null ? null : tables.feiYue(eq.stars);
        if (eq == null || eq.stars < CultivateTables.MAX_EQUIP_STAR
                || eq.stars >= CultivateTables.REAL_MAX_EQUIP_STAR || cost == null
                || !allCuiLianPartsOpen(eq)) {
            return;
        }
        if (rec.gold < cost.gold || !hasMats(rec, cost.mats) || countFreeSameOri(rec, eq) < cost.equipCost) {
            return;
        }
        rec.gold -= cost.gold;
        Map<String, Integer> changed = progress.emptyChanged();
        consumeMats(rec, cost.mats, changed);
        eatSameOri(session, pkt, rec, eq, cost.equipCost);
        eq.stars++;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 3, eq.stars, ""));
        session.send(MsgIds.S2C_EQUIP_FEIYUE_RET, pkt, new byte[0]);
        pushFightPowerIfOwned(session, pkt, rec, eq);
        task.syncOnce(session, pkt, rec);
    }

    // ⚠️ §6-9：下面 onLastXiLian / onXiLian / onConfirmXiLian 三个方法对应 C2S
    // 1109/1110/1111，客户端在 APK 全产物里零发送点（洗炼改走 1116/1117 → 1413/1414），
    // 因此它们回的 S2C 1402/1403/1404 永远不会发出。**有意保留未删**：见 MsgIds.java
    // 1109 处的完整理由（删除会连带废掉 EquipmentXiLian 表解析与装备 tag 9/5 的洗炼属性）。
    public void onLastXiLian(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Equipment eq = rec.findEquip(Pb.read(pkt.body).getString(1));
        if (eq == null) {
            return;
        }
        session.send(MsgIds.S2C_XILIAN_LAST, pkt, dump.xiLianInfo(eq.id, eq.pendingXiLianType, eq.pendingXiLianValue));
    }

    public void onXiLian(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        PlayerRecord.Equipment eq = rec.findEquip(f.getString(1));
        int type = f.getInt(2, 0);
        if (eq == null) {
            return;
        }
        EconomyTables.XiLianCommon xl = economy.xiLian();
        int stone = type == 2 ? xl.rmbStone : (type == 1 ? xl.goldStone : xl.normalStone);
        if (!progress.hasGoods(rec, xl.stoneOri, stone)) {
            return;
        }
        if (type == 1 && rec.gold < xl.goldCost) {
            return;
        }
        if (type == 2 && rec.diamond < xl.rmbCost) {
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        progress.consumeGoods(rec, xl.stoneOri, stone);
        progress.markGoods(changed, xl.stoneOri);
        if (type == 1) {
            rec.gold -= xl.goldCost;
        }
        if (type == 2) {
            rec.diamond -= xl.rmbCost;
        }
        int abs = xl.minAbs + (xl.maxAbs > xl.minAbs ? rng.nextInt(xl.maxAbs - xl.minAbs + 1) : 0);
        int posWan = type == 2 ? xl.rmbPosWan : (type == 1 ? xl.goldPosWan : xl.normalPosWan);
        CultivateTables.EquipCfg cfg = tables.equip(eq.ori);
        int eqType = cfg == null ? 1 : cfg.type;
        int quality = cfg == null ? 1 : cfg.quality;
        eq.pendingXiLianType = tables.rollXiLianType(eqType, quality, rng);
        eq.pendingXiLianValue = rng.nextInt(10000) < posWan ? abs : -abs;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        if (type == 1) {
            progress.pushGold(session, pkt, rec);
        }
        if (type == 2) {
            progress.pushDiamond(session, pkt, rec);
        }
        session.send(MsgIds.S2C_XILIAN_RET, pkt, dump.xiLianInfo(eq.id, eq.pendingXiLianType, eq.pendingXiLianValue));
    }

    public void onConfirmXiLian(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Equipment eq = rec.findEquip(Pb.read(pkt.body).getString(1));
        if (eq == null) {
            return;
        }
        eq.xiLianType = eq.pendingXiLianType;
        eq.xiLianValue = eq.pendingXiLianValue;
        store.save(rec);
        session.send(MsgIds.S2C_XILIAN_CONFIRM, pkt, dump.xiLianInfo(eq.id, eq.xiLianType, eq.xiLianValue));
        pushFightPowerIfOwned(session, pkt, rec, eq);
    }

    public void onDownStar(GameSession session, GamePacket pkt) {
        onResetEquip(session, pkt, true);
    }

    public void onResetEquip(GameSession session, GamePacket pkt) {
        onResetEquip(session, pkt, false);
    }

    public void onTransform(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Equipment eq = rec.findEquip(Pb.read(pkt.body).getString(1));
        EconomyTables.TransformRow row = eq == null ? null : economy.transform(eq.ori);
        if (eq == null || row == null || rec.gold < row.gold) {
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        if (!hasMats(rec, toMats(row.mats))) {
            return;
        }
        rec.gold -= row.gold;
        consumeMats(rec, toMats(row.mats), changed);
        eq.ori = row.to;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_REMOVE_EQUIP, pkt, dump.equipGuid(eq.id));
        session.send(MsgIds.S2C_ADD_EQUIP, pkt, dump.equipment(eq));
        session.send(MsgIds.S2C_EQUIP_TRANSFORM_RET, pkt, new byte[0]);
        pushFightPowerIfOwned(session, pkt, rec, eq);
    }

    public void onJingLianExp(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        PlayerRecord.Equipment eq = rec.findEquip(f.getString(1));
        String ori = f.getString(2);
        int num = Math.max(1, f.getInt(3, 1));
        int type = f.getInt(4, 0);
        int add = economy.jingLianStoneExp(ori) * num;
        if (eq == null || add <= 0 || !progress.hasGoods(rec, ori, num)) {
            return;
        }
        CultivateTables.EquipCfg cfg = tables.equip(eq.ori);
        int quality = cfg == null ? 4 : cfg.quality;
        // 4901 前置门槛：投喂的是「当前精炼子等级+1」那一行，要求装备淬炼等级 ≥ 该行
        // 「所需淬炼等级」（APK EquipmentUI.cs:1069/:1138 用 >= 放行，:1026/:1038 用 < 置灰，
        // 不足时弹 StrTable 101244）。表内该列全 0 ⇒ 门槛恒真，此处只堵改包。
        int nextSub = (type == 0 ? eq.jingLianSubLevel1 : eq.jingLianSubLevel2) + 1;
        CultivateTables.JingLianRefineRow need = tables.refineRow(quality, nextSub);
        if (need != null && eq.jingLianLevel < need.leapLevelLimit) {
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        progress.consumeGoods(rec, ori, num);
        progress.markGoods(changed, ori);
        eq.jingLianExp += add;
        if (type == 0) {
            eq.jingLianSubExp1 += add;
            bumpSub(eq, quality, true);
        } else {
            eq.jingLianSubExp2 += add;
            bumpSub(eq, quality, false);
        }
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 8, eq.jingLianExp, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 9, eq.jingLianSubLevel1, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 11, eq.jingLianSubExp1, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 10, eq.jingLianSubLevel2, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 12, eq.jingLianSubExp2, ""));
        session.send(MsgIds.S2C_JINGLIAN_EXP_RET, pkt, dump.jingLianExpRet(ori, type));
        pushFightPowerIfOwned(session, pkt, rec, eq);
    }

    public void onJingLianFeiYue(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Equipment eq = rec.findEquip(Pb.read(pkt.body).getString(1));
        CultivateTables.EquipCfg cfg = eq == null ? null : tables.equip(eq.ori);
        if (eq == null || cfg == null) {
            return;
        }
        EconomyTables.LeapRow leap = economy.leap(cfg.quality, eq.jingLianLevel + 1);
        if (leap == null || rec.level < leap.levelLimit || rec.gold < leap.gold || eq.jingLianExp < leap.exp) {
            return;
        }
        if (!hasMats(rec, toMats(leap.mats))) {
            return;
        }
        rec.gold -= leap.gold;
        Map<String, Integer> changed = progress.emptyChanged();
        consumeMats(rec, toMats(leap.mats), changed);
        eq.jingLianLevel++;
        eq.jingLianExp = 0;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 7, eq.jingLianLevel, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 8, eq.jingLianExp, ""));
        pushFightPowerIfOwned(session, pkt, rec, eq);
    }

    private void bumpSub(PlayerRecord.Equipment eq, int quality, boolean left) {
        int guard = 0;
        while (guard++ < 80) {
            int lv = left ? eq.jingLianSubLevel1 : eq.jingLianSubLevel2;
            int exp = left ? eq.jingLianSubExp1 : eq.jingLianSubExp2;
            EconomyTables.RefineRow need = economy.refine(quality, lv + 1);
            if (need == null || exp < need.exp) {
                return;
            }
            if (left) {
                eq.jingLianSubExp1 -= need.exp;
                eq.jingLianSubLevel1++;
            } else {
                eq.jingLianSubExp2 -= need.exp;
                eq.jingLianSubLevel2++;
            }
        }
    }

    private void onResetEquip(GameSession session, GamePacket pkt, boolean downStarMsg) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Equipment eq = rec.findEquip(Pb.read(pkt.body).getString(1));
        CultivateTables.EquipCfg cfg = eq == null ? null : tables.equip(eq.ori);
        if (eq == null || cfg == null) {
            return;
        }
        EconomyTables.ResetRow starRow = economy.resetStar(cfg.quality, eq.stars);
        EconomyTables.ResetRow jlRow = economy.resetJingLian(cfg.quality, eq.jingLianLevel);
        int diamond = economy.resetBaseCost();
        if ((eq.stars > 0 || eq.guhua > 0) && starRow != null) {
            diamond += starRow.diamond;
        }
        if ((eq.jingLianLevel > 0 || eq.jingLianExp > 0) && jlRow != null) {
            diamond += jlRow.diamond;
        }
        if (rec.diamond < diamond) {
            return;
        }

        Map<String, Integer> mats = new HashMap<>();
        int goldBack = eq.uplevelGold;
        int leftoverEquips = 0;
        if (eq.stars == 0 && eq.guhua > 0 && starRow != null) {
            goldBack += addGuHuaCost(cfg.quality, 0, eq.guhua, mats);
            applyResetRates(mats, starRow);
        } else if (eq.stars > 0 && starRow != null) {
            leftoverEquips = 0;
            for (int k = 1; k <= eq.stars; k++) {
                CultivateTables.StarCost sc = tables.starCost(cfg.quality, k - 1);
                if (sc == null) {
                    continue;
                }
                goldBack += sc.gold;
                leftoverEquips += sc.equipCost;
                addMats(mats, sc.mats);
            }
            for (int m = 0; m <= eq.stars; m++) {
                int guhuaTimes = (m == eq.stars) ? eq.guhua : 4;
                goldBack += addGuHuaCost(cfg.quality, m, guhuaTimes, mats);
            }
            leftoverEquips = (int) (leftoverEquips * (starRow.equipReturnRate / 10000f));
            leftoverEquips++;
            applyResetRates(mats, starRow);
        }

        Map<String, Integer> jlMats = new HashMap<>();
        if (eq.jingLianLevel == 0 && eq.jingLianExp > 0 && jlRow != null) {
            int exp = leftoverJingLianExp(cfg.quality, eq);
            int per = economy.jingLianStoneExp(economy.resetGiveGoods());
            if (per > 0 && exp >= per) {
                jlMats.put(economy.resetGiveGoods(), exp / per);
            }
        } else if (eq.jingLianLevel > 0 && jlRow != null) {
            int exp = leftoverJingLianExp(cfg.quality, eq);
            exp = (int) (exp * (economy.resetGiveGoodsRate() / 10000f));
            int per = economy.jingLianStoneExp(economy.resetGiveGoods());
            if (per > 0 && exp >= per) {
                jlMats.put(economy.resetGiveGoods(), exp / per);
            }
            for (int lv = 1; lv <= eq.jingLianLevel; lv++) {
                EconomyTables.LeapRow leap = economy.leap(cfg.quality, lv);
                if (leap == null) {
                    continue;
                }
                goldBack += leap.gold;
                addEconomyMats(jlMats, leap.mats);
            }
            applyResetRates(jlMats, jlRow);
        }

        rec.diamond -= diamond;
        rec.gold += goldBack;
        eq.level = 1;
        eq.stars = 0;
        eq.guhua = 0;
        eq.uplevelGold = 0;
        eq.jingLianLevel = 0;
        eq.jingLianExp = 0;
        eq.jingLianSubLevel1 = 0;
        eq.jingLianSubExp1 = 0;
        eq.jingLianSubLevel2 = 0;
        eq.jingLianSubExp2 = 0;

        Map<String, Integer> changed = progress.emptyChanged();
        Map<String, Integer> retItems = new LinkedHashMap<>();
        List<PlayerRecord.Equipment> newEq = new ArrayList<>();
        mergeGrant(rec, mats, leftoverEquips, eq.ori, changed, retItems, newEq);
        mergeGrant(rec, jlMats, 0, null, changed, retItems, newEq);
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        if (goldBack > 0) {
            progress.pushGold(session, pkt, rec);
        }
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushEquips(session, pkt, newEq);
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 2, eq.level, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 3, eq.stars, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 4, eq.guhua, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 5, eq.uplevelGold, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 7, 0, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 8, 0, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 9, 0, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 10, 0, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 11, 0, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 12, 0, ""));
        session.send(downStarMsg ? MsgIds.S2C_EQUIP_DOWN_STAR_RET : MsgIds.S2C_EQUIP_RESET_RET,
                pkt, dump.resetRet(goldBack, retItems));
        pushFightPowerIfOwned(session, pkt, rec, eq);
        log.info("{} equip-reset guid={} diamond={} goldBack={} items={}",
                rec.account, eq.id, diamond, goldBack, retItems);
    }

    private int addGuHuaCost(int quality, int star, int times, Map<String, Integer> mats) {
        int gold = 0;
        CultivateTables.GuHuaCost g = tables.guhuaCost(quality, star);
        if (g == null || times <= 0) {
            return 0;
        }
        for (int i = 0; i < times; i++) {
            gold += g.gold;
            if (g.matCount > 0 && g.matOri != null && !g.matOri.isEmpty() && !"0".equals(g.matOri)) {
                addCount(mats, g.matOri, g.matCount);
            }
        }
        return gold;
    }

    private int leftoverJingLianExp(int quality, PlayerRecord.Equipment eq) {
        int exp = eq.jingLianSubExp1 + eq.jingLianSubExp2;
        for (int lv = 1; lv <= eq.jingLianSubLevel1; lv++) {
            EconomyTables.RefineRow r = economy.refine(quality, lv);
            if (r != null) {
                exp += r.exp;
            }
        }
        for (int lv = 1; lv <= eq.jingLianSubLevel2; lv++) {
            EconomyTables.RefineRow r = economy.refine(quality, lv);
            if (r != null) {
                exp += r.exp;
            }
        }
        return exp;
    }

    private static void applyResetRates(Map<String, Integer> mats, EconomyTables.ResetRow row) {
        if (row == null || row.goodsRates == null) {
            return;
        }
        for (EconomyTables.Mat g : row.goodsRates) {
            if (g == null || g.ori == null || !mats.containsKey(g.ori)) {
                continue;
            }
            int n = (int) (mats.get(g.ori).intValue() * (g.count / 10000f));
            if (n <= 0) {
                mats.remove(g.ori);
            } else {
                mats.put(g.ori, Integer.valueOf(n));
            }
        }
    }

    private static void addMats(Map<String, Integer> dest, List<CultivateTables.Mat> src) {
        if (src == null) {
            return;
        }
        for (CultivateTables.Mat m : src) {
            if (m != null && m.count > 0) {
                addCount(dest, m.ori, m.count);
            }
        }
    }

    private static void addEconomyMats(Map<String, Integer> dest, List<EconomyTables.Mat> src) {
        if (src == null) {
            return;
        }
        for (EconomyTables.Mat m : src) {
            if (m != null && m.count > 0) {
                addCount(dest, m.ori, m.count);
            }
        }
    }

    private static void addCount(Map<String, Integer> dest, String ori, int n) {
        if (ori == null || ori.isEmpty() || "0".equals(ori) || n <= 0) {
            return;
        }
        dest.put(ori, Integer.valueOf(dest.getOrDefault(ori, 0).intValue() + n));
    }

    private void mergeGrant(PlayerRecord rec, Map<String, Integer> mats, int leftoverEquips, String eqOri,
                            Map<String, Integer> changed, Map<String, Integer> retItems,
                            List<PlayerRecord.Equipment> newEq) {
        if (mats != null) {
            for (Map.Entry<String, Integer> e : mats.entrySet()) {
                if (e.getValue() == null || e.getValue().intValue() <= 0) {
                    continue;
                }
                newEq.addAll(progress.grantReward(rec, e.getKey(), e.getValue().intValue(), changed));
                addCount(retItems, e.getKey(), e.getValue().intValue());
            }
        }
        if (leftoverEquips > 0 && eqOri != null) {
            newEq.addAll(progress.grantReward(rec, eqOri, leftoverEquips, changed));
            addCount(retItems, eqOri, leftoverEquips);
        }
    }

    private List<CultivateTables.Mat> toMats(List<EconomyTables.Mat> src) {
        List<CultivateTables.Mat> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (EconomyTables.Mat m : src) {
            out.add(new CultivateTables.Mat(m.ori, m.count));
        }
        return out;
    }

    private void levelUpOnce(GameSession session, GamePacket pkt, boolean untilFail) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        PlayerRecord.Equipment eq = rec.findEquip(Pb.read(pkt.body).getString(1));
        if (eq == null) {
            return;
        }
        boolean any = false;
        do {
            if (tryLevelUp(rec, eq) == null) {
                break;
            }
            any = true;
        } while (untilFail);
        if (!any) {
            return;
        }
        store.save(rec);
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 2, eq.level, ""));
        session.send(MsgIds.S2C_EQUIP_ATTRI_UPDATE, pkt, dump.equipAttri(eq.id, 5, eq.uplevelGold, ""));
        pushFightPowerIfOwned(session, pkt, rec, eq);
    }

    private LevelUpStep tryLevelUp(PlayerRecord rec, PlayerRecord.Equipment eq) {
        if (eq.level >= rec.level) {
            return null;
        }
        CultivateTables.EquipCfg cfg = tables.equip(eq.ori);
        int quality = cfg == null ? 1 : cfg.quality;
        int cost = tables.upgradeCost(eq.level, quality);
        if (cost <= 0 || rec.gold < cost) {
            return null;
        }
        int jump = tables.rollUpgradeJump(rng);
        int room = rec.level - eq.level;
        if (jump > room) {
            jump = room;
        }
        if (jump <= 0) {
            return null;
        }
        int saved = 0;
        for (int lv = eq.level + 1; lv < eq.level + jump; lv++) {
            saved += tables.upgradeCost(lv, quality);
        }
        rec.gold -= cost;
        eq.uplevelGold += cost;
        eq.level += jump;
        LevelUpStep step = new LevelUpStep();
        step.jump = jump;
        step.saved = saved;
        return step;
    }

    private static final class LevelUpStep {
        int jump;
        int saved;
    }

    private boolean payRecipe(PlayerRecord rec, CultivateTables.ComposeRecipe recipe,
                              Map<String, Integer> changed, boolean checkOnly) {
        if (rec.gold < recipe.gold || !hasMats(rec, recipe.mats)) {
            return false;
        }
        if (checkOnly) {
            return true;
        }
        rec.gold -= recipe.gold;
        consumeMats(rec, recipe.mats, changed);
        return true;
    }

    /**
     * 飞跃（C2S 1118）前置：淬炼已开且四个部位全部激活。
     *
     * <p>客户端 {@code EquipmentDetail.cs:124-142 EquipReadyFeiYue}（{@code EquipmentUI.cs:2408-2409} 调它）
     * 要求 {@code Stars ∈ [MAX_STAR, REAL_MAX_STAR)} + {@code isOpenCuiLian} + 四部位全 true，
     * 服务端改前只卡星级与金币/材料 ⇒ 没开淬炼的装备也能飞跃。
     */
    private static boolean allCuiLianPartsOpen(PlayerRecord.Equipment eq) {
        if (eq == null || !eq.openCuiLian || eq.cuiLianParts == null || eq.cuiLianParts.length < 4) {
            return false;
        }
        for (boolean b : eq.cuiLianParts) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    private boolean hasMats(PlayerRecord rec, List<CultivateTables.Mat> mats) {
        if (mats == null) {
            return true;
        }
        for (CultivateTables.Mat m : mats) {
            if (!progress.hasGoods(rec, m.ori, m.count)) {
                return false;
            }
        }
        return true;
    }

    private void consumeMats(PlayerRecord rec, List<CultivateTables.Mat> mats, Map<String, Integer> changed) {
        if (mats == null) {
            return;
        }
        for (CultivateTables.Mat m : mats) {
            progress.consumeGoods(rec, m.ori, m.count);
            progress.markGoods(changed, m.ori);
        }
    }

    private int countFreeSameOri(PlayerRecord rec, PlayerRecord.Equipment keep) {
        int n = 0;
        for (PlayerRecord.Equipment e : rec.equipments) {
            if (e != keep && keep.ori.equals(e.ori) && (e.owner == null || e.owner.isEmpty())) {
                n++;
            }
        }
        return n;
    }

    private void eatSameOri(GameSession session, GamePacket pkt, PlayerRecord rec,
                            PlayerRecord.Equipment keep, int need) {
        if (need <= 0) {
            return;
        }
        List<PlayerRecord.Equipment> eaten = new ArrayList<>();
        for (PlayerRecord.Equipment e : rec.equipments) {
            if (eaten.size() >= need) {
                break;
            }
            if (e != keep && keep.ori.equals(e.ori) && (e.owner == null || e.owner.isEmpty())) {
                eaten.add(e);
            }
        }
        rec.equipments.removeAll(eaten);
        for (PlayerRecord.Equipment e : eaten) {
            session.send(MsgIds.S2C_REMOVE_EQUIP, pkt, dump.equipGuid(e.id));
        }
    }

    private void pushSuitAndFightPower(GameSession session, GamePacket pkt, PlayerRecord rec, PlayerRecord.Hero wj) {
        if (wj == null) {
            return;
        }
        List<Integer> effects = tables.suitEffectIds(tables.equippedOriOf(rec, wj.id));
        session.send(MsgIds.S2C_UPDATE_SUIT_EFFECT, pkt, dump.suitEffect(wj.id, effects));
        session.send(MsgIds.S2C_UPDATE_ALL_WUJIANG_FIGHT_POWER, pkt, dump.updateWuJiangFightPower(rec, wj));
    }

    /** 装备养成后：若已穿在武将上，重推套装被动 + 战力 15/17（S2C 4002）。 */
    private void pushFightPowerIfOwned(GameSession session, GamePacket pkt, PlayerRecord rec, PlayerRecord.Equipment eq) {
        if (eq == null || eq.owner == null || eq.owner.isEmpty()) {
            return;
        }
        PlayerRecord.Hero wj = rec.findHero(eq.owner);
        if (wj != null) {
            pushSuitAndFightPower(session, pkt, rec, wj);
        }
    }

    private PlayerRecord.Equipment worn(PlayerRecord rec, String wjGuid, int type) {
        for (PlayerRecord.Equipment e : rec.equipments) {
            if (!wjGuid.equals(e.owner)) {
                continue;
            }
            CultivateTables.EquipCfg cfg = tables.equip(e.ori);
            if (cfg != null && cfg.type == type) {
                return e;
            }
        }
        return null;
    }

    private PlayerRecord.Equipment bestFree(PlayerRecord rec, int type, CultivateTables.HeroCfg hero) {
        PlayerRecord.Equipment best = null;
        for (PlayerRecord.Equipment e : rec.equipments) {
            if (e.owner != null && !e.owner.isEmpty()) {
                continue;
            }
            CultivateTables.EquipCfg cfg = tables.equip(e.ori);
            if (cfg == null || cfg.type != type || !tables.canWear(hero, cfg)) {
                continue;
            }
            if (best == null || better(e, best, hero == null ? 0 : hero.index)) {
                best = e;
            }
        }
        return best;
    }

    /** APK 列表：先比登录 field7 BaseScore；同分仅当候选有该武将缘分、当前没有时换。 */
    private boolean better(PlayerRecord.Equipment a, PlayerRecord.Equipment b, int heroIndex) {
        int sa = tables.computeEquipBaseScore(a);
        int sb = tables.computeEquipBaseScore(b);
        if (sa != sb) {
            return sa > sb;
        }
        return tables.equipHasYuanFen(a.ori, heroIndex) && !tables.equipHasYuanFen(b.ori, heroIndex);
    }

    private PlayerRecord.Hero newHero(PlayerRecord rec, CultivateTables.HeroCfg cfg) {
        PlayerRecord.Hero wj = new PlayerRecord.Hero();
        wj.heroIndex = cfg.index;
        wj.id = PlayerDumpService.guidOf(rec.account, cfg.index);
        // 新武将固定 1 级（勿跟账号等级）
        wj.level = 1;
        wj.stars = Math.max(1, cfg.composeStar);
        wj.fightPower = tables.computeFightPower(cfg.index, wj.level, wj.stars);
        return wj;
    }

    private PlayerRecord.Equipment newEquip(PlayerRecord rec, String ori) {
        PlayerRecord.Equipment eq = new PlayerRecord.Equipment();
        eq.id = rec.account + "-eq-" + (rec.nextEquipSeq++);
        eq.ori = ori;
        eq.level = 1;
        eq.cuiLianParts = new boolean[]{false, false, false, false};
        return eq;
    }

    private static List<String> unique(List<String> src) {
        List<String> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (String s : src) {
            if (s != null && !s.isEmpty() && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
