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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class SideService {
    private static final Logger log = LoggerFactory.getLogger(SideService.class);
    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final EconomyTables tables;
    private final CultivateTables cultivate;
    private final TaskService task;
    private final Random rng = new Random();

    public SideService(PlayerStore store, PlayerDumpService dump, ProgressService progress,
                       EconomyTables tables, CultivateTables cultivate, TaskService task) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.tables = tables;
        this.cultivate = cultivate;
        this.task = task;
    }

    public void onTimeStoneOff(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        String guid = f.getString(1);
        int loc = f.getInt(2, 0);
        PlayerRecord.Hero wj = rec.findHero(guid);
        if (wj == null || loc < 0 || loc > 6) {
            return;
        }
        String stone = wj.timeStone(loc);
        if (stone == null || stone.isEmpty()) {
            // 空孔位不处理：改前会拿客户端 f3 当石头白送（f3 可以是任意 ori，含装备 ⇒ 直接生成装备实例）
            return;
        }
        // 卸下收钻石：GoodsList col12 第三段（Ⅰ–Ⅳ=0/Ⅴ=10/Ⅵ=50/Ⅶ=100/Ⅷ=150），
        // 客户端 RemoveTimeRock.cs:67-76 扣完 mRemoveCost 才发 2901，服务端改前完全不读该列
        int cost = tables.timeStoneRemoveCost(stone);
        if (rec.diamond < cost) {
            return;
        }
        rec.diamond -= cost;
        wj.setTimeStone(loc, "", 0);
        Map<String, Integer> changed = progress.emptyChanged();
        progress.addGoods(rec, stone, 1);
        progress.markGoods(changed, stone);
        store.save(rec);
        if (cost > 0) {
            progress.pushDiamond(session, pkt, rec);
        }
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushTimeStone(session, pkt, wj, loc);
        session.send(MsgIds.S2C_TIME_STONE_OFF, pkt, dump.timeStoneRet(guid, stone));
        session.send(MsgIds.S2C_UPDATE_ALL_WUJIANG_FIGHT_POWER, pkt, dump.updateWuJiangFightPower(rec, wj));
    }

    public void onTimeStoneSet(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        String guid = f.getString(1);
        int loc = f.getInt(2, 0);
        int fx = f.getInt(3, 1);
        String stone = f.getString(4);
        PlayerRecord.Hero wj = rec.findHero(guid);
        if (wj == null || loc < 0 || loc > 6 || stone == null || stone.isEmpty()) {
            return;
        }
        if (!progress.hasGoods(rec, stone, 1)) {
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        String old = wj.timeStone(loc);
        progress.consumeGoods(rec, stone, 1);
        progress.markGoods(changed, stone);
        if (old != null && !old.isEmpty()) {
            progress.addGoods(rec, old, 1);
            progress.markGoods(changed, old);
        }
        wj.setTimeStone(loc, stone, fx <= 0 ? 1 : fx);
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushTimeStone(session, pkt, wj, loc);
        session.send(MsgIds.S2C_TIME_STONE_SET, pkt, dump.timeStoneColor(stone));
        session.send(MsgIds.S2C_UPDATE_ALL_WUJIANG_FIGHT_POWER, pkt, dump.updateWuJiangFightPower(rec, wj));
    }

    public void onTimeStoneCompose(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        java.util.List<String> names = Pb.read(pkt.body).getStrings(1);
        // 客户端 BaoShiHeChengUI.cs:1117-1124 只保证「槽位填满 + 金币够」，配方/等级上限/扣料全在服务端。
        // 配方 = 同一颗石（同颜色同等级）≥ 2 颗，输入等级须在 TimeStoneCompose.txt 内（1..6，输出 2..7）。
        String in = names.isEmpty() ? "" : names.get(0);
        int[] parsed = parseTimeStone(in);
        boolean same = parsed != null && names.size() >= 2;
        for (String ori : names) {
            if (!in.equals(ori)) {
                same = false;
            }
        }
        int gold = same ? tables.timeStoneComposeGold(parsed[1]) : -1;
        if (gold < 0 || !progress.hasGoods(rec, in, names.size()) || rec.gold < gold) {
            // 失败不回成功包：CCMsgRequestComposeTimeStoneRet 的 Ret 位默认 false，客户端只走成功分支
            session.send(MsgIds.S2C_TIME_STONE_COMPOSE, pkt, dump.timeStoneCompose(false, "", 0));
            return;
        }
        int nextLevel = parsed[1] + 1;
        String out = "TS" + in.charAt(2) + (nextLevel < 10 ? "0" + nextLevel : String.valueOf(nextLevel));
        // N6 合成暴击：TimeStoneCompose.txt 第 4 列（此前只读前两列、这列被整个丢掉）。
        // 客户端语义：BaoShiHeChengUI.cs:102 判 TimeStoneCount > 1 就播暴击粒子
        // "eff_ui_baoshi_hecheng_baoji"（:1173-1182）并 StartCoroutine(:1579-1634) 延迟 1 秒后才弹窗；
        // BaoShiHeChengRet.cs:77-90 的弹窗也只有两组石头位 ⇒ 暴击产出上限就是 2 颗。
        // 不需要额外 S2C：下面 pushGoods 已让客户端看到数量变化，客户端自己播粒子 + 弹窗，
        // 再补一个包只会与那 1 秒延迟叠加。
        int outCount = 1;
        float crit = tables.timeStoneCrit(parsed[1]);
        if (crit > 0f && rng.nextFloat() < crit) {
            outCount = 2;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        progress.consumeGoods(rec, in, names.size());
        progress.markGoods(changed, in);
        rec.gold -= gold;
        progress.addGoods(rec, out, outCount);
        progress.markGoods(changed, out);
        store.save(rec);
        progress.pushGold(session, pkt, rec);
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_TIME_STONE_COMPOSE, pkt, dump.timeStoneCompose(true, out, outCount));
        task.syncOnce(session, pkt, rec);
    }

    public void onTimeStoneColor(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        Pb.Fields f = Pb.read(pkt.body);
        String src = f.getString(1);
        int[] parsed = parseTimeStone(src);
        if (parsed == null || !progress.hasGoods(rec, src, 1)) {
            session.send(MsgIds.S2C_TIME_STONE_COLOR, pkt, dump.timeStoneColor(src == null ? "" : src));
            return;
        }
        int srcColor = parsed[0];
        int level = parsed[1];
        int dstColor = f.getInt(2, 0);
        if (dstColor < 1 || dstColor > 5 || dstColor == srcColor) {
            dstColor = srcColor % 5 + 1;
        }
        String dst = "TS" + dstColor + src.substring(3);
        int[] cost = tables.timeStoneColorCost(level);
        int goldCost = cost[0];
        int diamondCost = cost[1];
        if (rec.gold < goldCost || rec.diamond < diamondCost) {
            session.send(MsgIds.S2C_TIME_STONE_COLOR, pkt, dump.timeStoneColor(src));
            return;
        }
        rec.gold -= goldCost;
        rec.diamond -= diamondCost;
        Map<String, Integer> changed = progress.emptyChanged();
        progress.consumeGoods(rec, src, 1);
        progress.markGoods(changed, src);
        progress.addGoods(rec, dst, 1);
        progress.markGoods(changed, dst);
        store.save(rec);
        if (goldCost > 0) {
            progress.pushGold(session, pkt, rec);
        }
        if (diamondCost > 0) {
            progress.pushDiamond(session, pkt, rec);
        }
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_TIME_STONE_COLOR, pkt, dump.timeStoneColor(dst));
    }

    /**
     * C2S 3701 {@code CCMsgRequestComposeEquipSoul}：合成器魂（把某个武将的器魂点亮）。
     *
     * <p>字段只有 f1 {@code wjIndex}（{@code CCMsgRequestComposeEquipSoul.cs:9-16}），材料要求
     * 在服务端表里：{@code EquipSoulList.txt}「合成所需物品ID / 合成所需数量」（cols[6]/cols[7]，
     * 启用的 21 行全是 {@code QH00x × 50}），见 {@link CultivateTables#soulCfg(int)}。
     * 客户端 {@code QiHunSys.cs:363-385} 正是用这两列画「已有/需要」进度条。
     *
     * <p>改前：直接 {@code composed = true}，不查表也不扣材料 —— 任何武将都能白拿器魂。
     * 注意客户端点了合成**即使材料不够也会照样发包**（{@code QiHunSys.cs:234-242} 只弹 100647
     * 提示、没有 return），而 4301 会让它弹 100363「合成成功」（{@code PlayGameState.cs:8095-8115}）
     * ⇒ 材料不足必须静默不回包，否则假成功。
     *
     * <p>已合成（{@code composed}）时按幂等处理：回现状、不重复扣材料（客户端合成按钮由
     * {@code hasComposed} 隐藏，重复包只可能来自重放）。
     */
    public void onSoulCompose(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int idx = Pb.read(pkt.body).getInt(1, 0);
        CultivateTables.SoulCfg cfg = cultivate.soulCfg(idx);
        if (cfg == null) {
            return;
        }
        PlayerRecord.Soul exist = rec.souls.get(Integer.valueOf(idx));
        if (exist != null && exist.composed) {
            session.send(MsgIds.S2C_SOUL_COMPOSE, pkt, dump.soulUpdate(idx, true, exist.stage, exist.exp));
            return;
        }
        boolean needMats = cfg.composeCount > 0 && !cfg.composeGoods.isEmpty();
        if (needMats && !progress.hasGoods(rec, cfg.composeGoods, cfg.composeCount)) {
            return;
        }
        PlayerRecord.Soul s = rec.souls.computeIfAbsent(Integer.valueOf(idx), k -> new PlayerRecord.Soul());
        s.composed = true;
        Map<String, Integer> changed = progress.emptyChanged();
        if (needMats) {
            progress.consumeGoods(rec, cfg.composeGoods, cfg.composeCount);
            progress.markGoods(changed, cfg.composeGoods);
        }
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_SOUL_COMPOSE, pkt, dump.soulUpdate(idx, true, s.stage, s.exp));
        pushFightPowerByHeroIndex(session, pkt, rec, idx);
    }

    /**
     * C2S 3702 {@code CCMsgRequestAddEquipSoulExp}：用背包里的「器魂吞噬材料」换器魂经验。
     *
     * <p>字段（{@code CCMsgRequestAddEquipSoulExp.cs:18-33}）= f1 {@code wjIndex} + f2
     * {@code List<CMsgGoods>{f1 oriName, f2 count}}。真服口径：每件材料折算 {@code GoodsList}
     * 第 15 字段「器魂吞噬经验值」（cols[14]，见 {@link EconomyTables#soulExp(String)}）——
     * 客户端 {@code QiHunSys.cs:643-655 ᜀ()} 正是按 {@code Σ(数量 × 该列)} 算本地预览，收包后用
     * 服务端返回的 {@code curExp} 弹「获得器魂经验 +Δ」（{@code ᝁ.cs:7802-7837}），
     * 所以服务端必须按同一列求和并扣掉材料，否则预览与结果当场对不上（改前是无条件 {@code exp += 20}）。
     *
     * <p>不按阶段经验值（{@code EquipSoulJinJie.txt}）截断：客户端自己按 {@code curExp - mExp}
     * 结转溢出（{@code QiHunSys.cs:841 GetAttriDesList(jieduan+1, curExp - mExp)}），截断反而对不上。
     *
     * <p>材料为空 / 含非器魂材料（该列 = 0）/ 背包不足 ⇒ 整包拒绝、不回 4302：客户端没有失败通道
     * （{@code ᝁ.cs} 只注册了成功回包），假成功会让它把本地预览当成已生效 —— 与 1537 厨房同一口径。
     */
    public void onSoulExp(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int idx = f.getInt(1, 0);
        // 同一 ori 可能分多条下发（客户端按点击逐条加，见 QiHunSys.cs:483-518），先按 ori 合并再校验总量，
        // 否则「同件材料拆两条、各自不超库存、合计超」会漏过库存检查。
        Map<String, Integer> need = new LinkedHashMap<>();
        int gain = 0;
        for (byte[] one : f.getBytesList(2)) {
            Pb.Fields g = Pb.read(one);
            String ori = g.getString(1);
            int count = g.getInt(2, 0);
            int per = tables.soulExp(ori);
            if (per <= 0 || count <= 0) {
                return;
            }
            need.merge(ori, Integer.valueOf(count), Integer::sum);
            gain += per * count;
        }
        if (gain <= 0) {
            return;
        }
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            if (!progress.hasGoods(rec, e.getKey(), e.getValue().intValue())) {
                return;
            }
        }
        PlayerRecord.Soul s = rec.souls.computeIfAbsent(Integer.valueOf(idx), k -> new PlayerRecord.Soul());
        s.composed = true;
        s.exp += gain;
        Map<String, Integer> changed = progress.emptyChanged();
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            progress.consumeGoods(rec, e.getKey(), e.getValue().intValue());
            progress.markGoods(changed, e.getKey());
        }
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_SOUL_EXP, pkt, dump.soulUpdate(idx, true, s.stage, s.exp));
        pushFightPowerByHeroIndex(session, pkt, rec, idx);
    }

    public void onSoulJinJie(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int idx = Pb.read(pkt.body).getInt(1, 0);
        PlayerRecord.Soul s = rec.souls.get(Integer.valueOf(idx));
        if (s == null || !s.composed) {
            return;
        }
        // 客户端 QiHunSys.cs:699 在「没有下一阶」时把两个突破节点全部隐藏、:708 要求当前阶经验 ≥ 该阶所需
        // （tables\EquipSoulJinJie.txt 第 4 字段），:841 预览下一阶用的是 curExp - mExp ⇒ 溢出要结转、不能清零。
        CultivateTables.SoulJinJieCfg cur = cultivate.soulJinJie(s.stage);
        if (cur == null || cultivate.soulJinJie(s.stage + 1) == null) {
            return;
        }
        if (s.exp < cur.exp) {
            return;
        }
        s.stage++;
        s.exp -= cur.exp;
        store.save(rec);
        session.send(MsgIds.S2C_SOUL_JINJIE, pkt, dump.soulUpdate(idx, true, s.stage, s.exp));
        pushFightPowerByHeroIndex(session, pkt, rec, idx);
    }

    public void onJiBanUpdate(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        rec.jiban.buddies = new ArrayList<Integer>(Pb.read(pkt.body).getInts(1));
        store.save(rec);
        // 羁绊乘区进 17：全员战力附加段可能变
        pushFightPowerAllHeroes(session, pkt, rec);
    }

    public void onJiBanRefresh(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        int index = Pb.read(pkt.body).getInt(1, 0);
        PlayerRecord.JiBanSlot slot = rec.jiban.slot(index);
        slot.ensure();
        // N4 羁绊刷新收费：原来恒扣 10 钻、完全不收材料，而且钻石不足时跳过扣费<b>继续刷新（免费刷新）</b>。
        // 正确口径（与客户端 BuddiesSystem.cs:2484-2500 / :2922 对齐）：
        //   索引 num = 该槽 3 孔中被锁的个数（0/1/2），num==3 时客户端自身也显示 0/0（:2492-2496）⇒ 不收费；
        //   收费先扣材料 PY001，材料不足才扣钻石，两者都不足则<b>直接返回、不刷新也不扣费</b>。
        // 索引取服务端持久值 slot.tianFu.get(i).locked（由 1605 onJiBanLock 同步），与下面刷新循环同一份数据，
        // 因此「收费的孔数」与「真正重 roll 的孔数」天然一致，且不会被改包篡改。
        int num = 0;
        for (int i = 0; i < slot.tianFu.size() && i < 3; i++) {
            if (slot.tianFu.get(i).locked) {
                num++;
            }
        }
        Map<String, Integer> changed = progress.emptyChanged();
        if (num < PlayerDumpService.JIBAN_REFRESH_ITEM_COST.length) {
            int needItem = PlayerDumpService.JIBAN_REFRESH_ITEM_COST[num];
            int needDia = PlayerDumpService.JIBAN_REFRESH_ZUANSHI_COST[num];
            if (progress.hasGoods(rec, PlayerDumpService.JIBAN_REFRESH_ITEM_ORI, needItem)) {
                progress.consumeGoods(rec, PlayerDumpService.JIBAN_REFRESH_ITEM_ORI, needItem);
                progress.markGoods(changed, PlayerDumpService.JIBAN_REFRESH_ITEM_ORI);
            } else if (rec.diamond >= needDia) {
                rec.diamond -= needDia;
                progress.pushDiamond(session, pkt, rec);
            } else {
                log.info("{} jiban refresh refused: no {} x{} and diamond {} < {}",
                        rec.account, PlayerDumpService.JIBAN_REFRESH_ITEM_ORI, Integer.valueOf(needItem),
                        Integer.valueOf(rec.diamond), Integer.valueOf(needDia));
                return;
            }
        }
        for (int i = 0; i < slot.tianFu.size(); i++) {
            PlayerRecord.TianFu t = slot.tianFu.get(i);
            ensureTianFuType(t, i);
            if (t.locked) {
                t.hasNew = false;
                continue;
            }
            rollNew(t);
        }
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_JIBAN_REFRESH, pkt, dump.jiBanRefresh(index, slot));
    }

    public void onJiBanApply(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        int index = Pb.read(pkt.body).getInt(1, 0);
        PlayerRecord.JiBanSlot slot = rec.jiban.slot(index);
        slot.ensure();
        for (int i = 0; i < slot.tianFu.size(); i++) {
            PlayerRecord.TianFu t = slot.tianFu.get(i);
            if (!t.hasNew || t.locked) {
                continue;
            }
            // type 无 new*：左右预览共用 field3，确认只拷 star/roleId/攻防血
            ensureTianFuType(t, i);
            t.roleId = t.newRoleId;
            t.star = t.newStar;
            t.attack = t.newAttack;
            t.defend = t.newDefend;
            t.hp = t.newHp;
            t.hasNew = false;
            t.newRoleId = 0;
            t.newStar = 0;
            t.newAttack = 0;
            t.newDefend = 0;
            t.newHp = 0;
        }
        store.save(rec);
        session.send(MsgIds.S2C_JIBAN_REFRESH, pkt, dump.jiBanRefresh(index, slot));
        pushFightPowerAllHeroes(session, pkt, rec);
    }

    public void onJiBanLock(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        Pb.Fields f = Pb.read(pkt.body);
        int index = f.getInt(1, 0);
        List<Integer> states = f.getInts(2);
        PlayerRecord.JiBanSlot slot = rec.jiban.slot(index);
        slot.ensure();
        for (int i = 0; i < slot.tianFu.size(); i++) {
            slot.tianFu.get(i).locked = i < states.size() && states.get(i).intValue() != 0;
        }
        store.save(rec);
        // 1605 只是 C2S（客户端 ᝁ.cs 没注册 1605 的 S2C），锁状态靠 4001 回填：
        // BuddiesSystem.cs:2284-2303 发 1605 后界面要等 4001 才刷新勾选，改前只存档不推包
        session.send(MsgIds.S2C_JIBAN_REFRESH, pkt, dump.jiBanRefresh(index, slot));
    }

    private void pushFightPowerByHeroIndex(GameSession session, GamePacket pkt, PlayerRecord rec, int heroIndex) {
        if (rec == null || rec.heroes == null) {
            return;
        }
        for (PlayerRecord.Hero h : rec.heroes) {
            if (h != null && h.heroIndex == heroIndex) {
                session.send(MsgIds.S2C_UPDATE_ALL_WUJIANG_FIGHT_POWER, pkt, dump.updateWuJiangFightPower(rec, h));
            }
        }
    }

    private void pushFightPowerAllHeroes(GameSession session, GamePacket pkt, PlayerRecord rec) {
        if (rec == null || rec.heroes == null) {
            return;
        }
        for (PlayerRecord.Hero h : rec.heroes) {
            if (h != null) {
                session.send(MsgIds.S2C_UPDATE_ALL_WUJIANG_FIGHT_POWER, pkt, dump.updateWuJiangFightPower(rec, h));
            }
        }
    }

    /**
     * 孔位展示类型（对齐客户端 RefreshTianFu*）：0 主孔武将头像；1/2 攻/防羁绊石。
     * Proto 无 newType，预览旧/新两侧都读当前 type，刷新不得乱改。
     */
    private static void ensureTianFuType(PlayerRecord.TianFu t, int holeIndex) {
        if (t == null) {
            return;
        }
        if (holeIndex <= 0) {
            t.type = 0;
        } else if (t.type != 1 && t.type != 2) {
            t.type = holeIndex == 1 ? 1 : 2;
        }
    }

    private void rollNew(PlayerRecord.TianFu t) {
        t.hasNew = true;
        t.newStar = 1 + rng.nextInt(5);
        t.newRoleId = 1 + rng.nextInt(40);
        t.newAttack = 50 + rng.nextInt(251);
        t.newDefend = 50 + rng.nextInt(251);
        t.newHp = 50 + rng.nextInt(251);
        // 不改 type（见 ensureTianFuType）
        if (t.star <= 0) {
            t.star = 1;
        }
    }

    /**
     * @return [color, level]；解析失败返回 null
     */
    private static int[] parseTimeStone(String ori) {
        if (ori == null || ori.length() < 4 || !ori.startsWith("TS")) {
            return null;
        }
        int color = ori.charAt(2) - '0';
        if (color < 1 || color > 5) {
            return null;
        }
        String levelText = ori.substring(3);
        int level;
        try {
            level = Integer.parseInt(levelText);
        } catch (NumberFormatException e) {
            return null;
        }
        if (level <= 0) {
            return null;
        }
        return new int[]{color, level};
    }
}
