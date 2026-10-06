package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.store.WorldStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.table.GameTables;
import com.sao.fakeserver.table.UnionCfg;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProgressService {
    private static final Logger log = LoggerFactory.getLogger(ProgressService.class);
    private static final DateTimeFormatter GACHA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GameTables tables;
    private final EconomyTables economy;
    private final CultivateTables cultivate;
    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final WorldStore world;
    private final SessionHub sessions;
    /** 跨日重播日常（1102）用；TaskService 反向依赖本类，故 @Lazy 断环。 */
    private final TaskService task;
    /** 公会成长值/贡献折算系数（Union.txt）。 */
    private final UnionCfg unionCfg;

    public ProgressService(GameTables tables, EconomyTables economy, CultivateTables cultivate,
                           PlayerStore store, PlayerDumpService dump, WorldStore world, SessionHub sessions,
                           @Lazy TaskService task, UnionCfg unionCfg) {
        this.tables = tables;
        this.economy = economy;
        this.cultivate = cultivate;
        this.store = store;
        this.dump = dump;
        this.world = world;
        this.sessions = sessions;
        this.task = task;
        this.unionCfg = unionCfg;
    }

    public boolean addPlayerExp(PlayerRecord rec, int add) {
        if (add <= 0) {
            return false;
        }
        boolean leveled = false;
        rec.exp += add;
        while (rec.level < tables.maxPlayerLevel()) {
            int need = tables.nextPlayerExp(rec.level);
            if (need <= 0 || rec.exp < need) {
                break;
            }
            rec.exp -= need;
            rec.level++;
            rec.stamina += tables.vpOnLevelUp(rec.level);
            leveled = true;
        }
        return leveled;
    }

    /**
     * C2S 1801 写玩家变量（对话/引导已播）。body=CMsgPlayerVaris；无回包。
     * 名/值超 40 字时与客户端一致直接丢弃。
     */
    public void onUpdatePlayerVaris(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        String name = f.getString(1);
        String value = f.getString(2);
        boolean delete = f.getBool(3);
        if (name == null || name.isEmpty() || name.length() > 40) {
            return;
        }
        if (rec.varis == null) {
            rec.varis = new LinkedHashMap<>();
        }
        if (delete) {
            rec.varis.remove(name);
            store.save(rec);
            return;
        }
        if (value == null) {
            value = "";
        }
        if (value.length() > 40) {
            return;
        }
        String old = rec.varis.get(name);
        if (value.equals(old)) {
            return;
        }
        rec.varis.put(name, value);
        store.save(rec);
    }

    /**
     * 武将加经验（含升级循环）。
     *
     * <p>等级上限 = min(表上限, 账号等级)：客户端 {@code MainPlayer.cs:1746-1758 WuJiangCanLevelUp}
     * 在 {@code wujiang.Level >= Attribute.mLevel} 时返回 false，即武将等级不得高于账号等级；
     * 到顶后经验最多存满一条（客户端 {@code WuJiangDetailSystem.cs:88-135} 仅在
     * {@code Level == mLevel && Curexp == mNextLevelExp} 时禁止再喂经验道具）。
     *
     * @param owner 武将所属账号档，用于取账号等级；传 null 时退化为只按表上限
     */
    public boolean addWjExp(PlayerRecord owner, PlayerRecord.Hero wj, int add) {
        if (wj == null || add <= 0) {
            return false;
        }
        int cap = tables.maxWjLevel();
        if (owner != null) {
            cap = Math.min(cap, Math.max(1, owner.level));
        }
        boolean leveled = false;
        wj.exp += add;
        while (wj.level < cap) {
            int need = tables.nextWjExp(wj.level);
            if (need <= 0 || wj.exp < need) {
                break;
            }
            wj.exp -= need;
            wj.level++;
            leveled = true;
        }
        if (wj.level >= cap) {
            int need = tables.nextWjExp(wj.level);
            if (need > 0 && wj.exp > need) {
                wj.exp = need;
            }
        }
        return leveled;
    }

    public void addGoods(PlayerRecord rec, String ori, int count) {
        if (ori == null || ori.isEmpty() || "0".equals(ori) || count <= 0) {
            return;
        }
        // 装备必须走实例列表；误进 bag 会导致登录/结算 UI 异常（1-9 首通 EQ0012 曾踩坑）
        if (cultivate.equip(ori) != null) {
            for (int i = 0; i < count; i++) {
                grantEquip(rec, ori);
            }
            return;
        }
        rec.bag.merge(ori, count, Integer::sum);
    }

    /**
     * 掉落/发奖：装备 → {@link #grantEquip}；道具 → 背包。
     *
     * @param bagChanged 仅道具写入（绝对值供 S2C 301）；装备不进此 map
     * @return 本次新装备实例
     */
    public List<PlayerRecord.Equipment> grantReward(PlayerRecord rec, String ori, int count,
                                                    Map<String, Integer> bagChanged) {
        List<PlayerRecord.Equipment> eqs = new ArrayList<>();
        if (ori == null || ori.isEmpty() || "0".equals(ori) || count <= 0) {
            return eqs;
        }
        if (cultivate.equip(ori) != null) {
            for (int i = 0; i < count; i++) {
                eqs.add(grantEquip(rec, ori));
            }
            return eqs;
        }
        addGoods(rec, ori, count);
        if (bagChanged != null) {
            bagChanged.put(ori, rec.bag.getOrDefault(ori, 0));
        }
        return eqs;
    }

    public void pushEquips(GameSession session, GamePacket pkt, List<PlayerRecord.Equipment> eqs) {
        if (eqs == null || eqs.isEmpty()) {
            return;
        }
        for (PlayerRecord.Equipment eq : eqs) {
            session.send(MsgIds.S2C_ADD_EQUIP, pkt, dump.equipment(eq));
        }
    }

    /**
     * 把误写入 bag 的装备原名拆成装备实例并移出背包（修旧档 / 防登录炸）。
     *
     * @return 是否改过存档
     */
    public boolean sanitizeEquipInBag(PlayerRecord rec) {
        if (rec == null || rec.bag == null || rec.bag.isEmpty()) {
            return false;
        }
        boolean changed = false;
        Iterator<Map.Entry<String, Integer>> it = rec.bag.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> e = it.next();
            String ori = e.getKey();
            int count = e.getValue() == null ? 0 : e.getValue();
            if (count <= 0 || cultivate.equip(ori) == null) {
                continue;
            }
            for (int i = 0; i < count; i++) {
                grantEquip(rec, ori);
            }
            it.remove();
            changed = true;
        }
        return changed;
    }

    /**
     * 删掉抽卡误发的怪/NPC 武将（index≥1000 或无合成碎片），并清掉阵容里对应槽。
     *
     * @return 是否改过存档
     */
    public boolean sanitizeInvalidHeroes(PlayerRecord rec) {
        if (rec == null || rec.heroes == null || rec.heroes.isEmpty()) {
            return false;
        }
        java.util.Set<String> removedIds = new java.util.HashSet<String>();
        boolean changed = false;
        Iterator<PlayerRecord.Hero> it = rec.heroes.iterator();
        while (it.hasNext()) {
            PlayerRecord.Hero h = it.next();
            if (cultivate.isPlayableHero(h.heroIndex)) {
                continue;
            }
            if (h.id != null) {
                removedIds.add(h.id);
            }
            log.info("{} remove invalid hero index={} id={}", rec.account, h.heroIndex, h.id);
            it.remove();
            changed = true;
        }
        if (!changed) {
            return false;
        }
        for (List<String> slots : rec.allFormationSlotLists()) {
            if (slots == null) {
                continue;
            }
            for (int i = 0; i < slots.size(); i++) {
                String slot = slots.get(i);
                if (slot != null && removedIds.contains(slot)) {
                    slots.set(i, "");
                }
            }
        }
        return true;
    }

    public int bagCount(PlayerRecord rec, String ori) {
        if (ori == null || ori.isEmpty()) {
            return 0;
        }
        return rec.bag.getOrDefault(ori, 0);
    }

    public boolean hasGoods(PlayerRecord rec, String ori, int count) {
        return count <= 0 || bagCount(rec, ori) >= count;
    }

    public boolean consumeGoods(PlayerRecord rec, String ori, int count) {
        if (ori == null || ori.isEmpty() || "0".equals(ori) || count <= 0) {
            return true;
        }
        int have = bagCount(rec, ori);
        if (have < count) {
            return false;
        }
        int left = have - count;
        if (left <= 0) {
            rec.bag.remove(ori);
        } else {
            rec.bag.put(ori, left);
        }
        return true;
    }

    public void pushGold(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(3, rec.gold));
    }

    public void pushStamina(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(2, rec.stamina));
    }

    /**
     * 统一扣体力入口：消耗体力的地方都应走这里，不要直接改 {@code rec.stamina}。
     *
     * <p>依据 {@code tables\Union.txt:11}「成员消耗1点体力值公会获得的成长值(必须为整形)」=1 与
     * {@code Union.txt:17}「玩家提供的公会成长值转换为玩家公会贡献系数」=30：每**实际**消耗 1 点体力
     * 给公会 +{@code staminaGrow()} 成长值，成长值再按 {@code growToContri()} 折成个人贡献；不足一次的
     * 余数留在 {@link PlayerRecord.Guild#contributionGrowRemainder}（1/30 整除恒为 0，不累加永远换不到贡献）。</p>
     *
     * <p>返回值是实际扣掉的体力：四个调用点原来都写 {@code Math.max(0, stamina - cost)}，体力不够时
     * 按名义值记成长值会凭空生成长值，故必须先夹再算。</p>
     *
     * <p>客户端侧这两个系数**只解析不消费**（{@code MobileGameDemo\UnionProperty.cs:50/:56} 解析、
     * {@code :111/:129} 声明，全树零使用；唯一有消费的兄弟键 {@code mJinShi2ContriRatio} 在
     * {@code UnionManagerSystem.cs:684}），所以成长值/贡献的入账完全由服务端权威计算。</p>
     */
    public int spendStamina(PlayerRecord rec, int want) {
        if (rec == null || want <= 0 || rec.stamina <= 0) {
            return 0;
        }
        int spent = Math.min(want, rec.stamina);
        rec.stamina -= spent;
        if (rec.guild == null || rec.guild.id == null || rec.guild.id.isEmpty()) {
            return spent;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null) {
            return spent;
        }
        int per = Math.max(0, unionCfg.staminaGrow());
        int added = per * spent;
        if (added > 0) {
            u.growth = Math.min(unionCfg.growCap(), u.growth + added);
        }
        creditUnionGrowth(rec, added);
        syncMemberContribution(rec);
        world.saveUnions();
        if (added > 0) {
            // 成长值是公会级属性（EUnionAttribute 6），推给全体在线成员。
            byte[] body = dump.unionAttriUpdate(6, u.growth, "");
            for (WorldStore.Member m : u.members) {
                GameSession s = m.account == null ? null : sessions.get(m.account);
                if (s != null) {
                    s.send(MsgIds.S2C_UNION_ATTRI_UPDATE, 0, body);
                }
            }
        }
        return spent;
    }

    /**
     * 把一次「玩家为公会提供的成长值」折算成个人贡献（与 {@link #spendStamina} 同一套账）。
     *
     * <p>依据 {@code tables\Union.txt:17}「玩家提供的公会成长值转换为玩家公会贡献系数」=30。
     * 不足一次的余数留在 {@link PlayerRecord.Guild#contributionGrowRemainder} 等下次累加。
     * 目前两个调用点：消耗体力（每点 +{@code staminaGrow}=1）与公会 Boss 单场奖励
     * （{@code UnionBoss.txt} 的成长值列，150/场）。</p>
     *
     * @return 本次实际入账的贡献点数（0 = 余数还不够 1 点）
     */
    public int creditUnionGrowth(PlayerRecord rec, int growth) {
        if (rec == null || rec.guild == null || growth <= 0) {
            return 0;
        }
        int ratio = Math.max(1, unionCfg.growToContri());
        int pool = rec.guild.contributionGrowRemainder + growth;
        int gained = pool / ratio;
        rec.guild.contributionGrowRemainder = pool % ratio;
        if (gained > 0) {
            rec.guild.contribution += gained;
            syncMemberContribution(rec);
        }
        return gained;
    }

    /**
     * 把玩家身上的贡献值同步进公会成员表。
     *
     * <p>1906 成员列表 f6 读的是 {@link WorldStore.Member#contribution}
     * （{@code PlayerDumpService.java:855}），而它此前**生产代码零写入**（{@code memberOf()} 不复制）
     * ⇒ 客户端成员列表所有人贡献恒 0，会长让位并列 tiebreak（{@code UnionService.java:3224}）也永远平手。
     * 贡献的两条来源是捐赠（晶石→贡献，{@code UnionService.java:628}）与本类的体力消耗。</p>
     */
    public void syncMemberContribution(PlayerRecord rec) {
        if (rec == null || rec.guild == null || rec.guild.id == null || rec.guild.id.isEmpty()) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null) {
            return;
        }
        for (WorldStore.Member m : u.members) {
            if (m.playerId == rec.playerId) {
                m.contribution = rec.guild.contribution;
                return;
            }
        }
    }

    public void pushDiamond(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(4, rec.diamond));
    }

    public void pushChargedDiamond(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(10, rec.economy.chargedDiamond));
    }

    /**
     * EAttriType=16（EAT_RMB_BUY）：累计充值 RMB，充值后推送。
     * <p>客户端登录读 detail field47（MainPlayer.cs:293 mCurBuyRMB = playerData.rmb_chong_zhi），
     * 运行时只认 attri 16（MainPlayer.cs:940-942 case 16: mCurBuyRMB = intValue）→ 不推则本次
     * 会话内 mCurBuyRMB 停在上次登录值。消费者是三日充（type3，当前表里 enabled:false）。
     */
    public void pushRmbChongZhi(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(16, rec.economy.rmbChongZhi));
    }

    /** EAttriType=17：当日累计充值 RMB，充值后推送，客户端刷新今日充值面板。 */
    public void pushTodayChongZhi(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(17, rec.economy.curDayChongZhiRmb));
    }

    /** EAttriType=18：当日累计消耗钻石，消耗入口推送，客户端刷新消耗返利面板。 */
    public void pushTodayCost(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(18, rec.economy.curDayCostZuanShi));
    }

    /** 记账当日钻石消耗（含魔盒钻兜底等），并推 EAttriType=18。 */
    public void addTodayCost(GameSession session, GamePacket pkt, PlayerRecord rec, int diamond) {
        if (diamond <= 0) {
            return;
        }
        rec.economy.curDayCostZuanShi += diamond;
        store.save(rec);
        pushTodayCost(session, pkt, rec);
    }

    public void pushSkillPoints(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(7, rec.skillPoints));
    }

    public void ensureSkillPointRecoveryAnchor(PlayerRecord rec) {
        if (rec.skillPoints >= cultivate.maxSkillPoint()) {
            rec.skillPointRecoverAnchorMs = 0;
        } else if (rec.skillPointRecoverAnchorMs <= 0) {
            rec.skillPointRecoverAnchorMs = System.currentTimeMillis();
        }
    }

    public void tickSkillPointRecovery(GameSession session, GamePacket pkt, PlayerRecord rec) {
        int max = cultivate.maxSkillPoint();
        if (rec.skillPoints >= max) {
            rec.skillPointRecoverAnchorMs = 0;
            return;
        }
        ensureSkillPointRecoveryAnchor(rec);
        long intervalMs = cultivate.skillPointRetrieveSeconds() * 1000L;
        long elapsed = System.currentTimeMillis() - rec.skillPointRecoverAnchorMs;
        int gained = (int) (elapsed / intervalMs);
        if (gained <= 0) {
            return;
        }
        int room = max - rec.skillPoints;
        int actual = Math.min(gained, room);
        rec.skillPoints += actual;
        rec.skillPointRecoverAnchorMs += actual * intervalMs;
        if (rec.skillPoints >= max) {
            rec.skillPointRecoverAnchorMs = 0;
        }
        store.save(rec);
        pushSkillPoints(session, pkt, rec);
    }

    public void pushWnsp(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(8, rec.wannengFragments));
    }

    public void pushJjcScore(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(9, rec.jjcScore));
    }

    public void pushYingPo(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(15, rec.yingPo));
    }

    public void pushMoFaChen(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(31, rec.moFaChen));
    }

    public void pushZz(GameSession session, GamePacket pkt, PlayerRecord rec) {
        tickZzRestore(rec);
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(12, rec.zhengZhanShuiJin));
    }

    public void pushDefenseKuang(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(13, rec.defenseKuangCnt));
    }

    public void pushCoDefenseKuang(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(14, rec.coDefenseKuangCnt));
    }

    /**
     * 征战水晶自然恢复。对齐客户端：field41=距上次恢复已流逝秒；满点不走表。
     * @return 是否改变了 zhengZhanShuiJin（调用方可 push）
     */
    public boolean tickZzRestore(PlayerRecord rec) {
        if (rec == null) {
            return false;
        }
        EconomyTables.MineCommon m = economy.mine();
        int max = Math.max(1, m.zzMax);
        int restoreSec = Math.max(1, m.zzRestoreSec);
        if (rec.zhengZhanShuiJin >= max) {
            rec.lastZzRestoreAtMs = 0;
            return false;
        }
        long now = System.currentTimeMillis();
        if (rec.lastZzRestoreAtMs <= 0) {
            rec.lastZzRestoreAtMs = now;
            return false;
        }
        long step = restoreSec * 1000L;
        boolean changed = false;
        while (rec.zhengZhanShuiJin < max && now - rec.lastZzRestoreAtMs >= step) {
            rec.zhengZhanShuiJin++;
            rec.lastZzRestoreAtMs += step;
            changed = true;
        }
        if (rec.zhengZhanShuiJin >= max) {
            rec.lastZzRestoreAtMs = 0;
        }
        return changed;
    }

    /** 登录 field41：当前恢复周期已流逝秒（0=刚恢复或已满）。须先 {@link #tickZzRestore}。 */
    public int nextRestroeZzElapsedSec(PlayerRecord rec) {
        if (rec == null || rec.zhengZhanShuiJin >= economy.mine().zzMax || rec.lastZzRestoreAtMs <= 0) {
            return 0;
        }
        long elapsed = (System.currentTimeMillis() - rec.lastZzRestoreAtMs) / 1000L;
        if (elapsed < 0) {
            return 0;
        }
        int cap = Math.max(1, economy.mine().zzRestoreSec);
        return (int) Math.min(elapsed, (long) cap);
    }

    /** 扣征战水晶后若未满，启动/保持恢复锚点。 */
    public void onZzConsumed(PlayerRecord rec) {
        if (rec == null) {
            return;
        }
        if (rec.zhengZhanShuiJin >= economy.mine().zzMax) {
            rec.lastZzRestoreAtMs = 0;
            return;
        }
        if (rec.lastZzRestoreAtMs <= 0) {
            rec.lastZzRestoreAtMs = System.currentTimeMillis();
        }
    }

    /**
     * 用户级日刷入口（登录与业务包懒调）：按日历日清当日次数/标记，并刷新白银宝箱免费次数。
     * 无独立定时器；客户端协议不变，只保证进号 dump 字段已是今日值。
     * 跨日且该号已 bind 在线时顺带空包 S2C 2702/2703。登录 ensureDaily 在 bind 前，不会误推。
     * 商店免费/付费刷新次数的重置**不在这里**：表 {@code ShopCommom} 公会商店行第 16 列 = 5 点，
     * 见 {@link #resetShopFreeRefreshDaily(PlayerRecord)}。
     * @return true=跨日已清（在线另应推 S2C 2125）
     */
    public boolean ensureDaily(PlayerRecord rec) {
        rec.ensureCollections();
        DungeonService.ensureAllResourceFbSlots(rec);
        // 与 dailyKey 解耦：即使今日已走过 ensureDaily，仍按上次免费抽日期补次数，
        // 避免「dailyKey 已是今天、但 goldFreeLeft 仍停在昨天用完的 0」。
        refreshGachaGoldFreeDaily(rec);
        // 商店免费/付费刷新次数同理：重置时刻走 ShopCommom 公会商店行第 16 列 = 5 点，
        // 与金币免费抽同一 5:00 游戏日界（gachaGameDay），不跟 0 点日历日块。
        resetShopFreeRefreshDaily(rec);
        String today = GameTime.today().toString();
        if (today.equals(rec.economy.dailyKey)) {
            return false;
        }
        rec.economy.dailyKey = today;
        rec.economy.buyTiLiToday = 0;
        rec.economy.buyJinBiToday = 0;
        rec.economy.curDayChongZhiRmb = 0;
        rec.economy.curDayCostZuanShi = 0;
        rec.qkBuyReliveTimes = 0;
        // 当日占领次数：日清归零（客户端 vs VIP ShouKuangCount）
        rec.defenseKuangCnt = 0;
        // 协防次数：按仍挂在矿上的协防重算，勿盲清导致超 VIP join 上限
        rec.coDefenseKuangCnt = countActiveCoDefs(rec.playerId);
        rec.economy.kitchenStaminaLeft = 1;
        rec.economy.escortRaidLeft = economy.jieBiaoCount(rec.economy.chargedDiamond);
        // 发镖额度没有「当日已发次数」字段可下发（1942 无对应 field），客户端
        // EmBattleSystem.cs:2653 直接用 myBiaoCheInfo.Count 与 VipManager.FaBiaoCount 比较
        // ⇒ 额度就是「同时在途镖车数上限」，由 UnionService.onSendCart 按 escortCarts.size() 判。
        rec.tower.challengeTimes = 0;
        rec.bob.resetTimes = 0;
        // 公会捐赠次数上限按 VipCfg col11（VIP0=3 … VIP6=10），客户端显示「上限 − 已捐」
        rec.guild.donateLeft = economy.unionDonateMaxTimes(rec.economy.chargedDiamond);
        rec.guild.bossPlayTimes.clear();
        rec.guild.bossPending.clear();
        rec.cards.todayZhiZun = false;
        rec.cards.todayTeQuan = false;
        rec.cards.awardDay = today;
        // 好友：赠送/领取体力按日重置（FriendsParams.txt 的「最大领取 20」是展示值，
        // 服务端同样按日清；IsGive/IsReceive 也随之回到 0）。
        if (rec.friendGaveToday == null) {
            rec.friendGaveToday = new ArrayList<>();
        }
        if (rec.friendReceiveState == null) {
            rec.friendReceiveState = new LinkedHashMap<>();
        }
        rec.friendGaveToday.clear();
        rec.friendReceiveState.clear();
        rec.friendReceivedToday = 0;
        rec.friendGaveCount = 0;
        if (rec.activity == null) {
            rec.activity = new PlayerRecord.Activity();
        }
        if (rec.activity.gainedVpTypes == null) {
            rec.activity.gainedVpTypes = new ArrayList<>();
        }
        rec.activity.today7Day = false;
        rec.activity.gainedVpTypes.clear();
        if (rec.activity.dailyChongZhiAwarded == null) {
            rec.activity.dailyChongZhiAwarded = "";
        }
        rec.activity.dailyChongZhiAwarded = "";
        if (rec.activity.dailyCostAwarded == null) {
            rec.activity.dailyCostAwarded = "";
        }
        rec.activity.dailyCostAwarded = "";
        rec.tasks.ensure();
        for (PlayerRecord.DailyTask t : rec.tasks.daily.values()) {
            t.finishTimes = 0;
            t.prized = false;
        }
        // 资源本 playTime=已用；日清归 0（勿写成 3，否则一进号就少 3 次）
        for (PlayerRecord.ResourceFb fb : rec.resourceFb.values()) {
            fb.playTime = 0;
            fb.buyTimes = 0;
        }
        // 主线受限关：清空后首见再灌满；同日重登不走这里，保留 playLeft/buyTimes
        if (rec.progress.stagePlayLimits == null) {
            rec.progress.stagePlayLimits = new LinkedHashMap<>();
        } else {
            rec.progress.stagePlayLimits.clear();
        }
        pushBuyTimesDailyReset(rec);
        pushDailyTaskDailyReset(rec);
        pushDayChargeReset(rec);
        pushTeQuanInfoDailyReset(rec);
        return true;
    }

    /**
     * 跨日（0 点）对在线号跑一遍 {@link #ensureDaily}：清当日次数/标记，并把归零后的 attri 17/18 推给客户端。
     *
     * <p>在线挂机的号 0 点后客户端本地 {@code Attribute.curDayChongZhiRmb/curDayCostZuanShi} 仍是昨天的值
     * （只有登录 dump 或 S2C 106 会更新，客户端没有本地跨日重置），于是大厅红点、壕送大礼/消耗返利的
     * 「今日已充值/耗钻」与「可领」判定（{@code ActivityMainUI.cs:2490/:2687}、
     * {@code ActivityStatusInfo.cs:85/:103}）都按昨天算；服端已归零 → 点 2313/2315 被静默拒。
     * 与商店整点 1701、LTSJ 3702 的主动推同性质，由公共调度在 0 点调用。
     */
    public void resetDailyChargeToOnline() {
        int n = 0;
        for (GameSession session : sessions.onlineSnapshot()) {
            PlayerRecord rec = session.player();
            if (rec == null) {
                continue;
            }
            if (!ensureDaily(rec)) {
                continue;
            }
            store.save(rec);
            n++;
        }
        if (n > 0) {
            log.info("daily reset clock online={}", Integer.valueOf(n));
        }
    }

    /**
     * 在线号跨日：日常 {@code finishTimes/prized} 已清，必须把整份日常重播给客户端。
     * 否则客户端 {@code mAllDailyTaskInfo} 仍停在昨天（1101 全量被 NeedSyncServer 门控丢弃、
     * 增量 1102 只在玩家做事时才有），面板会显示「已完成/已领」且点领取无反应
     * （服端 {@code onPrizeDaily} 静默 return，无回包）。
     */
    private void pushDailyTaskDailyReset(PlayerRecord rec) {
        if (rec == null || rec.account == null) {
            return;
        }
        GameSession session = sessions.get(rec.account);
        if (session == null || session.player() != rec) {
            return;
        }
        task.pushDailyReset(session, null, rec);
        log.info("{} daily reset → S2C 1102 full daily list", rec.account);
    }

    /** 在线号跨日：空包 2702/2703，对齐 APK {@code OnResetBuyTiLiToday}/{@code OnResetBuyJinBiToday}。 */
    private void pushBuyTimesDailyReset(PlayerRecord rec) {
        if (rec == null || rec.account == null) {
            return;
        }
        GameSession session = sessions.get(rec.account);
        if (session == null || session.player() != rec) {
            return;
        }
        session.send(MsgIds.S2C_RESET_BUY_TILI_TODAY, 0, new byte[0]);
        session.send(MsgIds.S2C_RESET_BUY_JINBI_TODAY, 0, new byte[0]);
    }

    /**
     * 在线号跨日：S2C 1704 {@code CCMsgShopType}，对齐 APK {@code OnResetOneShopFreeRefreshTime}。
     * 店内再 EN_SHOP_NOTIFY → 1301，1702.4 回到表上限（产品拍板每日 3 次）。
     */
    private void pushShopFreeRefreshDailyReset(PlayerRecord rec) {
        if (rec == null || rec.account == null) {
            return;
        }
        GameSession session = sessions.get(rec.account);
        if (session == null || session.player() != rec) {
            return;
        }
        for (int type = 1; type <= 5; type++) {
            EconomyTables.ShopCfg cfg = economy.shop(type);
            if (cfg == null || rec.level < cfg.openLevel) {
                continue;
            }
            session.send(MsgIds.S2C_RESET_SHOP_FREE_REFRESH, 0, dump.shopType(type));
        }
    }

    /**
     * 商店每日免费/付费刷新次数按「5:00 游戏日」重置。
     *
     * <p>依据 {@code tables\ShopCommom.txt} 公会商店行（物理第 9 行）第 16 列
     * 「重置每日免费刷新次数时间」= 5（时）。客户端 {@code out2\Client\MobileGameDemo\ShopPropertyCfg.cs:26}
     * 解析了该列（{@code mResetDailyFreeRefreshTime}）但**全 APK 无消费方**（免费次数只认服端
     * 1702 f4「剩余次数」，见 {@code PlayerDumpService.shopInfo}），所以重置时刻纯服务端口径。
     * 改前挂在 0 点日历日块（{@code ensureDaily} 的 dailyKey 分支）里，0:00–5:00 这一段玩家
     * 会提前拿回当天次数。
     *
     * <p>与 {@link #refreshGachaGoldFreeDaily} 同样**与 dailyKey 解耦**：键存在
     * {@code PlayerRecord.ShopState.freeRefreshDay}，所以同一天内重复调用是幂等的。
     *
     * @return true=本次确实跨了商店游戏日（次数被归零）
     */
    public boolean resetShopFreeRefreshDaily(PlayerRecord rec) {
        return resetShopFreeRefreshDaily(rec, GameTime.now());
    }

    /** 便于测试的重载：{@code now} 决定商店游戏日键（{@link #gameDayOf}，小时取自表）。 */
    public boolean resetShopFreeRefreshDaily(PlayerRecord rec, LocalDateTime now) {
        if (rec == null || rec.shops == null) {
            return false;
        }
        boolean reset = false;
        for (Map.Entry<Integer, PlayerRecord.ShopState> entry : rec.shops.entrySet()) {
            PlayerRecord.ShopState shop = entry.getValue();
            if (shop == null) {
                continue;
            }
            // 重置时刻读 ShopCommom 第 16 列（五行均为 5），不再写死 —— 该列客户端零消费，纯服务端口径
            int hour = economy.shopFreeRefreshResetHour(entry.getKey().intValue());
            String key = gameDayOf(now, hour).toString();
            if (!key.equals(shop.freeRefreshDay)) {
                shop.freeRefreshDay = key;
                shop.freeRefresh = 0;
                shop.payRefresh = 0;
                reset = true;
            }
        }
        return reset;
    }

    /** 商店免费/付费刷新次数按游戏日归零，并对已 bind 在线号推 S2C 1704（调度固定在 5:00，与表值一致）。 */
    public void pushShopFreeRefreshGameDayResetToOnline() {
        int n = 0;
        for (GameSession session : sessions.onlineSnapshot()) {
            PlayerRecord rec = session.player();
            if (rec == null) {
                continue;
            }
            if (resetShopFreeRefreshDaily(rec)) {
                store.save(rec);
                pushShopFreeRefreshDailyReset(rec);
                n++;
            }
        }
        if (n > 0) {
            log.info("shop free-refresh 5:00 reset online={}", Integer.valueOf(n));
        }
    }

    /**
     * 在线号跨日：attri 17/18 归零（{@code EAttriType} 17=当日累充 RMB、18=当日耗钻）。
     *
     * <p>客户端这两个字段只由登录 dump（{@code MainPlayer.cs:319-320}）或 S2C 106 推送
     * （{@code MainPlayer.cs:943-950}）写入，**没有本地跨日重置**；而壕送大礼/消耗返利的面板数值与
     * 「可领」判定、大厅红点都用它（{@code ActivityMainUI.cs:2409/:2490/:2606/:2687}、
     * {@code ActivityStatusInfo.cs:85/:103}）。服端跨日已把两值清 0，不推则客户端仍按昨天的数显示
     * 「可领」，点 2313/2315 会被服端按 0 静默拒。
     * 客户端收到 17/18 会立刻重查 2312/2314（{@code ActivityMainUI.cs:123-126/434/437}）→ 面板与按钮随之刷新。
     */
    private void pushDayChargeReset(PlayerRecord rec) {
        if (rec == null || rec.account == null) {
            return;
        }
        GameSession session = sessions.get(rec.account);
        if (session == null || session.player() != rec) {
            return;
        }
        session.send(MsgIds.S2C_ATTRI_UPDATE, 0, dump.attri(17, rec.economy.curDayChongZhiRmb));
        session.send(MsgIds.S2C_ATTRI_UPDATE, 0, dump.attri(18, rec.economy.curDayCostZuanShi));
    }

    /**
     * 在线号跨日：重推 S2C 3901 {@code CMsgTeQuanCardInfo}（月卡/至尊卡当日领取状态）。
     *
     * <p>客户端 {@code mTodayHasAwardZhiZunCard/mTodayHasAwardTeQuanCard} 只有两个来源：登录 dump
     * （{@code MainPlayer.cs:326-333}）与 S2C 3901（{@code PlayGameState.cs:7322-7338}），**没有本地跨日重置**；
     * 而日常面板里月卡/至尊卡「每日返」按钮的状态就按它判（{@code TaskSystem.cs:666-730}：
     * 已领 → 按钮灰、BoxCollider 关）。服端 {@code cards.todayTeQuan/todayZhiZun} 跨日已清 0，
     * 不推则在线跨夜的号按钮整天停在「已领取」、3501/3502 发不出去，要重登才能领当天那份。
     * 客户端收到后发 {@code EN_REFRESH_TEQUANFULI} 复位按钮（与 3501/3502 领奖回包同一处理）。
     */
    private void pushTeQuanInfoDailyReset(PlayerRecord rec) {
        if (rec == null || rec.account == null) {
            return;
        }
        GameSession session = sessions.get(rec.account);
        if (session == null || session.player() != rec) {
            return;
        }
        session.send(MsgIds.S2C_TEQUAN_INFO, 0, dump.teQuanInfo(rec));
    }

    /**
     * 金币免费次数：APK 次数用尽后倒计到「TimeNow 的次日 05:00」，次数只认服端 dump/2402。
     * 日界 = 上海时钟减 5 小时后的日期（与百层塔 TimeNow−5h 同一天界）。
     */
    public void refreshGachaGoldFreeDaily(PlayerRecord rec) {
        if (rec == null) {
            return;
        }
        if (rec.gacha == null) {
            rec.gacha = new PlayerRecord.Gacha();
        }
        int times = tables.draw().jbFreeTimes;
        LocalDate gameToday = gachaGameDay(LocalDateTime.now(EconomyTables.SHOP_ZONE));
        String last = rec.gacha.lastGoldFreeAt;
        if (last == null || last.isEmpty()) {
            if (rec.gacha.goldFreeLeft <= 0) {
                rec.gacha.goldFreeLeft = times;
            }
            return;
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(last, GACHA_TIME);
            if (gachaGameDay(parsed).isBefore(gameToday)) {
                rec.gacha.goldFreeLeft = times;
            }
        } catch (Exception e) {
            rec.gacha.goldFreeLeft = times;
        }
    }

    /** 与 {@code BuyTreasureSystem} 次日 5:00 对齐：5 点前仍算前一个游戏日。 */
    static LocalDate gachaGameDay(LocalDateTime t) {
        return t.minusHours(5).toLocalDate();
    }

    /**
     * 以 {@code hour} 点为界的「游戏日」（该点前仍算前一个游戏日）。
     *
     * <p>商店免费刷新次数的日界小时读 {@code ShopCommom} 第 16 列
     * （{@link EconomyTables#shopFreeRefreshResetHour(int)}，五行均为 5），不再写死 5；
     * 金币抽卡的日界仍走 {@link #gachaGameDay}（同一个 5 点，但来源不同，不要互相绑死）。
     */
    static LocalDate gameDayOf(LocalDateTime t, int hour) {
        int h = (hour >= 0 && hour <= 23) ? hour : 0;
        return t.minusHours(h).toLocalDate();
    }

    /** 上海 5:00：补金免费次数并对已 bind 在线推 S2C 2402。 */
    public void pushGoldFreeGameDayResetToOnline() {
        int n = 0;
        for (GameSession session : sessions.onlineSnapshot()) {
            PlayerRecord rec = session.player();
            if (rec == null) {
                continue;
            }
            refreshGachaGoldFreeDaily(rec);
            store.save(rec);
            session.send(MsgIds.S2C_DRAW_UPDATE, 0, dump.drawUpdate(rec));
            n++;
        }
        if (n > 0) {
            log.info("gacha gold-free 5:00 reset online={}", Integer.valueOf(n));
        }
    }

    public void pushPlayerProgress(GameSession session, GamePacket pkt, PlayerRecord rec,
                                   boolean exp, boolean gold, boolean vp, boolean level, boolean rmb) {
        if (exp) {
            session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(1, rec.exp));
        }
        if (vp) {
            session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(2, rec.stamina));
        }
        if (gold) {
            session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(3, rec.gold));
        }
        if (rmb) {
            session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(4, rec.diamond));
        }
        if (level) {
            session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(6, rec.level));
        }
    }

    public void pushWjProgress(GameSession session, GamePacket pkt, PlayerRecord rec, PlayerRecord.Hero wj,
                               boolean leveled) {
        session.send(MsgIds.S2C_WUJIANG_ATTRI_UPDATE, pkt, dump.wuJiangAttri(wj.id, 1, wj.exp));
        if (leveled) {
            session.send(MsgIds.S2C_WUJIANG_ATTRI_UPDATE, pkt, dump.wuJiangAttri(wj.id, 2, wj.level));
            session.send(MsgIds.S2C_UPDATE_ALL_WUJIANG_FIGHT_POWER, pkt, dump.updateWuJiangFightPower(rec, wj));
        }
    }

    public void pushTimeStone(GameSession session, GamePacket pkt, PlayerRecord.Hero wj, int loc) {
        session.send(MsgIds.S2C_WUJIANG_ATTRI_UPDATE, pkt,
                dump.wuJiangAttri(wj.id, 14 + loc, wj.timeStoneFx(loc), wj.timeStone(loc)));
    }

    public void pushGoods(GameSession session, GamePacket pkt, PlayerRecord rec, Map<String, Integer> changed) {
        for (Map.Entry<String, Integer> e : changed.entrySet()) {
            session.send(MsgIds.S2C_UPDATE_GOODS, pkt, dump.goodsUpdate(e.getKey(), rec.bag.getOrDefault(e.getKey(), 0)));
        }
    }

    public void markGoods(Map<String, Integer> changed, String ori) {
        if (ori != null && !ori.isEmpty() && !"0".equals(ori)) {
            changed.put(ori, 0);
        }
    }

    public PlayerRecord.Equipment grantEquip(PlayerRecord rec, String ori) {
        PlayerRecord.Equipment eq = new PlayerRecord.Equipment();
        eq.id = rec.account + "-eq-" + (rec.nextEquipSeq++);
        eq.ori = ori;
        eq.level = 1;
        eq.cuiLianParts = new boolean[]{false, false, false, false};
        rec.equipments.add(eq);
        return eq;
    }

    public void addWnsp(PlayerRecord rec, int add) {
        if (add > 0) {
            rec.wannengFragments += add;
        }
    }

    public void addYingPo(PlayerRecord rec, int add) {
        if (add > 0) {
            rec.yingPo += add;
        }
    }

    public Map<String, Integer> emptyChanged() {
        return new LinkedHashMap<>();
    }

    /** 玩家当前挂在哪些矿上的协防数（世界矿状态）。 */
    private int countActiveCoDefs(int playerId) {
        if (playerId <= 0 || world == null) {
            return 0;
        }
        int n = 0;
        for (WorldStore.MineSlot s : world.mines().values()) {
            if (s == null || s.coDefs == null) {
                continue;
            }
            for (WorldStore.CoDefender c : s.coDefs) {
                if (c != null && c.playerId == playerId) {
                    n++;
                }
            }
        }
        return n;
    }

    public void save(PlayerRecord rec) {
        store.save(rec);
    }

    public boolean monthlyCardActive(PlayerRecord rec) {
        if (rec.cards == null || rec.cards.teQuanEnd == null || rec.cards.teQuanEnd.isEmpty()) {
            return false;
        }
        try {
            return GameTime.now().isBefore(
                    java.time.LocalDateTime.parse(rec.cards.teQuanEnd, PlayerDumpService.TIME));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * @deprecated 日返已改邮件 {@code MailService.grantCardDailies}，勿再直加钻。
     */
    @Deprecated
    public int grantCards(PlayerRecord rec) {
        return 0;
    }
}
