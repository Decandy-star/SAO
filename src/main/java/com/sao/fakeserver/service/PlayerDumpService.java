package com.sao.fakeserver.service;

import com.google.protobuf.CodedOutputStream;
import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.fight.FightRosterBuilder;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.WorldStore;
import com.sao.fakeserver.table.ActExtCfg;
import com.sao.fakeserver.table.ActivityTables;
import com.sao.fakeserver.table.AnnounceCfg;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.table.GameTables;
import com.sao.fakeserver.table.GiftCodeCfg;
import com.sao.fakeserver.table.KfHappyCfg;
import com.sao.fakeserver.table.ZhuanPanCfg;
import com.sao.fakeserver.util.GameTime;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PlayerDumpService {
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * 从未挑战时的线上下发时间。不能发空串：Msg.dll 里 LastChallengeTime DefaultValue=""，
     * 省略字段反序列化仍是 ""，ArenaMainDialog.Refresh 的 ParseExact 会炸，
     * Open 到不了 Show → 寻路图标不关、JJC 被压住。挑一个远早于 CD(600s) 的合法时间。
     */
    public static final String JJC_NEVER_CHALLENGED_AT = "2000-01-01 00:00:00";

    /** 与 detail 59–62 / RecaculatePowerReturn 同源。 */
    public static final int JIBAN_FP_RETURN_STANDARD = 5000;
    public static final int JIBAN_FP_RETURN_ATK = 10;
    public static final int JIBAN_FP_RETURN_DEF = 4;
    public static final int JIBAN_FP_RETURN_HP = 300;
    /**
     * 羁绊刷新消耗：索引 = 该槽 3 个天赋孔里<b>被锁</b>的个数（0/1/2，客户端 {@code BuddiesSystem.cs:2484-2500}
     * 同判据）。收费优先级 = 先扣材料 {@link #JIBAN_REFRESH_ITEM_ORI}，材料不足才走钻石
     * （客户端 {@code BuddiesSystem.cs:2922} 也是「材料不够 && 钻不够」才拦）。
     * <p>这两个数组与 detail 的 113/114 两列同源，<b>必须共用同一常量</b>
     * —— 之前 dump 里写 10/20/40、{@code SideService} 里写死 10，两处漂移就是旧的 N4 bug。
     * <p>无表权威（羁绊刷新价在 {@code tables\} 里没有对应表，只有 {@code Buddies.txt} 的缘分加成），
     * 要逐值对齐真服必须抓包看登录 {@code CMsgDetailPlayerInfo} 的 58/113/114 真实取值。
     */
    public static final String JIBAN_REFRESH_ITEM_ORI = "PY001";
    public static final int[] JIBAN_REFRESH_ITEM_COST = {10, 20, 40};
    public static final int[] JIBAN_REFRESH_ZUANSHI_COST = {10, 20, 40};
    private static final int BUDDIES_SLOTS = 10;

    private final SaoProperties props;
    private final GameTables tables;
    private final CultivateTables cultivate;
    private final EconomyTables economy;
    private final ProgressService progress;
    private final GiftCodeCfg giftCode;

    public PlayerDumpService(SaoProperties props, GameTables tables, CultivateTables cultivate,
                             EconomyTables economy, @Lazy ProgressService progress, GiftCodeCfg giftCode) {
        this.props = props;
        this.tables = tables;
        this.cultivate = cultivate;
        this.economy = economy;
        this.progress = progress;
        this.giftCode = giftCode;
    }

    public static String now() {
        return GameTime.now().format(TIME);
    }

    /** 毫秒时间戳 → 上海本地 {@link #TIME} 文案；{@code ms<=0} 回空串（对齐「在线」语义）。 */
    public static String timeAt(long ms) {
        if (ms <= 0L) {
            return "";
        }
        return java.time.Instant.ofEpochMilli(ms).atZone(GameTime.ZONE).format(TIME);
    }

    /** 存档空 → 下发 JJC_NEVER_CHALLENGED_AT；已有记录原样。 */
    public static String jjcLastChallengeWire(String stored) {
        if (stored == null || stored.isEmpty()) {
            return JJC_NEVER_CHALLENGED_AT;
        }
        return stored;
    }

    public PlayerRecord newPlayer(String account, int playerId, int wujiangIndex, int mainRoleIndex, String roleName) {
        PlayerRecord rec = new PlayerRecord();
        rec.account = account;
        rec.roleName = (roleName == null || roleName.trim().isEmpty()) ? account : roleName;
        rec.playerId = playerId;
        rec.mainHeroIndex = wujiangIndex;
        rec.mainRoleIndex = (mainRoleIndex == 2) ? 2 : 1;
        rec.createdAt = now();
        // 新号进新手本：登录包 alreadyNewUserGuideFB=false；region 先记 99，回主城(C2S 304)后再改主城
        rec.alreadyNewUserGuideFB = false;
        rec.currentRegionId = 99;
        rec.diamond = props.getInitRmb();
        rec.arena.challengesLeft = props.getJjcDailyTimes();
        rec.bag.put(props.getDropOriName(), 10);
        rec.bag.put("TS101", 8);
        rec.bag.put("TS201", 8);
        rec.bag.put("TS301", 8);
        rec.bag.put("TS401", 8);

        PlayerRecord.Hero wj = new PlayerRecord.Hero();
        wj.heroIndex = wujiangIndex;
        wj.id = guidOf(account, wujiangIndex);
        // stage 保持 0：进阶阶段决定客户端英雄列表的名字颜色/品阶框（0 白 → 5+ 紫）。
        // 初始号应为默认白色（0 阶），被动/技能 A/B 图标等玩家自己进阶后自然解锁，不能创号直接给 5 阶。
        rec.heroes.add(wj);

        rec.formation = fiveSlots(wj.id);
        rec.setFormationSlots(PlayerRecord.FORMATION_PVE, rec.formation);
        rec.ensureCollections();
        return rec;
    }

    public static String guidOf(String account, int index) {
        return account + "-wj-" + index;
    }

    public static List<String> fiveSlots(String commanderGuid) {
        List<String> slots = new ArrayList<>(5);
        slots.add(commanderGuid);
        for (int i = 0; i < 4; i++) {
            slots.add("");
        }
        return slots;
    }

    public byte[] mainPlayer(PlayerRecord rec) {
        byte[] detail = detail(rec);
        return Pb.write(out -> {
            Pb.int32(out, 1, rec.playerId);
            Pb.int32(out, 2, rec.mainHeroIndex);
            Pb.string(out, 3, rec.roleName);
            Pb.bytes(out, 4, detail);
        });
    }

    public byte[] detail(PlayerRecord rec) {
        return Pb.write(out -> writeDetail(out, rec));
    }

    private void writeDetail(CodedOutputStream out, PlayerRecord rec) throws IOException {
        Pb.int32(out, 1, rec.playerId);
        Pb.string(out, 2, rec.account);
        Pb.int32(out, 3, rec.level);
        Pb.int32(out, 5, rec.exp);
        Pb.int32(out, 6, rec.stamina);
        Pb.int32(out, 7, rec.diamond);
        Pb.int32(out, 8, rec.gold);
        Pb.int32(out, 12, rec.skillPoints);
        Pb.int32(out, 13, rec.wannengFragments);
        Pb.int32(out, 9, rec.progress.lastNormalStage);
        Pb.int32(out, 10, rec.progress.lastHardStage);
        // 22 默认 0 会让 BOBIsForceExit 判定“中途退出后回大厅”，每帧 EN_OPEN_BOBCHALLENGEMAP
        rec.ensureCollections();
        Pb.int32Always(out, 22, rec.bob.roundNumber <= 0 ? 1 : rec.bob.roundNumber);
        Pb.bool(out, 23, rec.bob.forceExit);
        Pb.int32(out, 14, rec.jjcScore);
        Pb.int32(out, 15, rec.economy.chargedDiamond);
        Pb.string(out, 16, rec.guild.name);
        Pb.int32(out, 17, rec.guildJobCode());
        Pb.stringAlways(out, 40, rec.economy.vipAwardGetInfo == null ? "" : rec.economy.vipAwardGetInfo);
        Pb.int32(out, 33, rec.economy.buyTiLiToday);
        Pb.int32(out, 34, rec.economy.buyJinBiToday);
        Pb.stringAlways(out, 30, rec.gacha.lastGoldFreeAt);
        Pb.stringAlways(out, 31, rec.gacha.lastDiamondFreeAt);
        Pb.int32(out, 32, rec.gacha.goldFreeLeft);
        // 35=创角左右(1/2)，不是武将表 ID；写成 18 会导致次日登录 PrizeInfoItems 越界卡死
        Pb.int32(out, 35, resolveMainRoleIndex(rec));
        // 36=距可领的剩余秒(0=已到次日/已领)；37=是否已领。当天创号给倒计时，避免客户端提前领次日奖
        // 必须是秒：客户端 mLeftTimeToSecondLoginDay 按秒减 deltaTime，UI 用 /3600 显示
        Pb.int32(out, 36, (int) SecondLoginDayService.leftTimeSec(rec));
        Pb.bool(out, 37, rec.isDrawSecondDayLoginPrized);
        Pb.int32Always(out, 38, rec.arena.qieCuoLeftTimes);
        Pb.int32(out, 39, rec.zhengZhanShuiJin);
        // 41–44：矿战。Msg DefaultValue=0；假服落档+自然恢复。41=距上次恢复已流逝秒。
        progress.tickZzRestore(rec);
        Pb.int32(out, 41, progress.nextRestroeZzElapsedSec(rec));
        Pb.int32(out, 42, rec.qkBuyReliveTimes);
        Pb.int32(out, 43, rec.coDefenseKuangCnt);
        Pb.int32(out, 44, rec.defenseKuangCnt);
        Pb.int32(out, 45, rec.yingPo);
        Pb.bool(out, 46, rec.economy.hasGet1stChongZhiAward);
        Pb.int32(out, 47, rec.economy.rmbChongZhi);
        Pb.int32(out, 49, rec.economy.curDayChongZhiRmb);
        Pb.int32(out, 50, rec.economy.curDayCostZuanShi);
        // 51/52：对齐客户端 MainPlayerAttribute 字段初值 100000f（Msg.dll ProtoMember DefaultValue=0，
        // 缺写会冲掉客户端 100000 → 普攻必闪）。禁止改成「随便一个正数」。
        Pb.float32(out, 51, 100000f);
        Pb.float32(out, 52, 100000f);
        Pb.stringAlways(out, 48, rec.createdAt);
        Pb.bool(out, 53, rec.cards.zhiZun);
        Pb.bool(out, 54, rec.cards.todayZhiZun);
        Pb.stringAlways(out, 55, rec.cards.teQuanEnd);
        Pb.bool(out, 56, rec.cards.todayTeQuan);
        // 开关/等级：来源写清。权威对照 docs/PROTOCOL_FIELD_AUDIT.md；禁止无来源字面量。
        // 57=teQuanCardEnabled — 假服开放（待真服包）
        Pb.bool(out, 57, true);
        // 58/59–62/113/114：羁绊刷新价 + 战力返还常数（客户端 RecaculatePowerReturn）
        // 58=PY001（GoodsList「刷新球」）；59=5000（网文「每 5000 战力一档」）；
        // 60–62=10/4/300：按 Lv30 解锁时桐人裸属性 ≈913/303/26647 取约 1% 作「少许」一档，
        // 比例贴近成长系数 28.8:9.6:836≈3:1:87；随档数线性涨，中后期约占面板 3%–6%。无表权威，产品估。
        // 113/114=10/20/40：锁 0/1/2 钻价与球价一致（产品确认；球不够走钻）。
        Pb.string(out, 58, JIBAN_REFRESH_ITEM_ORI);
        Pb.int32(out, 59, JIBAN_FP_RETURN_STANDARD);
        Pb.int32(out, 60, JIBAN_FP_RETURN_ATK);
        Pb.int32(out, 61, JIBAN_FP_RETURN_DEF);
        Pb.int32(out, 62, JIBAN_FP_RETURN_HP);
        for (int i = 0; i < JIBAN_REFRESH_ZUANSHI_COST.length; i++) {
            Pb.int32(out, 113, JIBAN_REFRESH_ZUANSHI_COST[i]);
        }
        for (int i = 0; i < JIBAN_REFRESH_ITEM_COST.length; i++) {
            Pb.int32(out, 114, JIBAN_REFRESH_ITEM_COST[i]);
        }
        // 63=EnableBuddies（不是 openCuiLian）— 假服开放（待真服包）
        Pb.bool(out, 63, true);
        // 84=openCuiLian — EquipmentUI 读此开关；缺=false 淬炼入口全关。假服开放。
        Pb.bool(out, 84, true);
        // 78=EquipSoulEnableLevel ← GlobalSetup_CH「器魂开启等级」40；缺=0 则 QiHun 按 0 级可进
        Pb.int32(out, 78, 40);
        // 19=ResourceFBSaoDangLevelRequire ← 客户端字段初值 1U；缺会被盖成 0
        Pb.int32(out, 19, 1);
        Pb.int32(out, 87, rec.moFaChen);
        // 88/89/90 克隆：ChallengeEntryUI 读 Attribute（登录包），不是 GlobalSetup 单例。
        // GlobalSetup_CH：开启等级=25、人数要求=2。客户端字段初值 35/1 会被本包覆盖。
        Pb.bool(out, 88, true);
        Pb.int32(out, 89, 25);
        Pb.int32(out, 90, 2);
        // 79/80：产品指定上限 90%。79→Limit=/10000；80 为配件 job8 特效 param1 原值上限（Buff 再/10000）。
        // 9000 → 0.9。APK 无表权威，由运营拍板。
        Pb.int32(out, 79, 9000);
        Pb.int32(out, 80, 9000);
        // 85 跨服聊天（保持缺省=false：假服没有跨服聊天，开了会露出未接 tab）
        // 86 EnableLiBaoMa ← 礼包码入口开关，由 tables/gift-code.json 决定（无表则 false=不显示入口）
        Pb.bool(out, 86, giftCode.isEnableLibaoMa());
        // 11 世界聊天免费次数；1001–1007 红点
        Pb.bool(out, 1006, rec.qkFightRecordTip);
        Pb.bool(out, 1007, rec.qkResourceTip);
        Pb.bytes(out, 66, zs10PriceInfo(rec));
        Pb.int32(out, 116, rec.gacha.diamondTenCount);
        // 104/105 是并行 repeated（客户端 MainPlayer.cs:379-381 以 FBCapterBaoXiangPrizeCapterID.Count
        // 为界同下标取 FBCapterBaoXiangPrizeStatus[j]）：protobuf-net 写 repeated 元素不省略默认值，
        // 必须用 Always —— 用跳过型的 Pb.int32 时 status==0 的档会让 105 短一格 → 客户端越界。
        rec.economy.chapterChests.forEach((chapter, status) -> {
            try {
                Pb.int32Always(out, 104, chapter);
                Pb.int32Always(out, 105, status);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // 103=FBStar：res_id_and_level=关卡ID*10+难度(1普/2精英)，供章节星显示；缺则通关重登星级全空
        rec.progress.stageStars.forEach((key, star) -> {
            try {
                Pb.bytes(out, 103, fbStar(rec, key, star == null ? 0 : star.intValue()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        rec.resourceFb.forEach((type, fb) -> {
            try {
                Pb.bytes(out, 115, resourceFb(fb));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        for (PlayerRecord.Hero wj : rec.heroes) {
            Pb.bytes(out, 101, wuJiang(rec, wj));
        }
        rec.bag.forEach((ori, count) -> {
            try {
                Pb.bytes(out, 102, goods(ori, count));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        for (PlayerRecord.Equipment eq : rec.equipments) {
            Pb.bytes(out, 106, equipment(eq));
        }
        rec.jiban.ensure();
        // 108：固定 10 槽含 0，避免跳过空位导致重登槽位压缩
        List<Integer> buddiesPad = paddedBuddies(rec);
        for (Integer idx : buddiesPad) {
            Pb.int32Always(out, 108, idx == null ? 0 : idx.intValue());
        }
        for (PlayerRecord.JiBanSlot slot : rec.jiban.slots) {
            Pb.bytes(out, 111, jiBanSlot(slot));
        }
        // 112 FightPowerReturn：登录故意不写；客户端 RecaculatePowerReturn 用 59–62 本地填。
        // 开战 myTeam / Clone Fighter 必须下发成品，否则 UnPack 会盖成空/垫 0。
        if (rec.varis != null) {
            rec.varis.forEach((name, value) -> {
                if (name == null || name.isEmpty()) {
                    return;
                }
                try {
                    Pb.bytes(out, 109, playerVaris(name, value == null ? "" : value));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        if (rec.economy.payBuyCounts != null) {
            boolean has1stId = false;
            int max1st = economy.max1stPayGoodsId();
            for (Integer id : rec.economy.payBuyCounts.keySet()) {
                if (id != null && id.intValue() > 0 && id.intValue() <= max1st) {
                    has1stId = true;
                    break;
                }
            }
            rec.economy.payBuyCounts.forEach((id, cnt) -> {
                if (id == null || cnt == null || cnt.intValue() <= 0) {
                    return;
                }
                try {
                    Pb.bytes(out, 110, userPayItem(id.intValue(), cnt.intValue()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            // 假服若只购了常规档 8+，客户端 GetCur1stChongZhiRMB 要求 GoodsID<=msMax1stID；
            // 镜像写 1st 表对应 ID，否则 mHas1stChongZhi 永远 false。
            if (!has1stId) {
                rec.economy.payBuyCounts.forEach((id, cnt) -> {
                    if (id == null || cnt == null || cnt.intValue() <= 0) {
                        return;
                    }
                    int mapped = id.intValue() - 7;
                    if (mapped < 1 || mapped > max1st) {
                        return;
                    }
                    try {
                        Pb.bytes(out, 110, userPayItem(mapped, cnt.intValue()));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    private byte[] wuJiang(PlayerRecord rec, PlayerRecord.Hero wj) {
        return Pb.write(out -> {
            Pb.string(out, 1, wj.id);
            Pb.int32(out, 2, wj.heroIndex);
            Pb.int32(out, 3, wj.level);
            Pb.int32(out, 4, wj.stage);
            Pb.int32(out, 5, wj.exp);
            Pb.int32(out, 6, wj.stars);
            Pb.int32(out, 7, wj.stagePara1);
            Pb.int32(out, 8, wj.stagePara2);
            Pb.int32(out, 9, wj.stagePara3);
            Pb.int32(out, 10, wj.stagePara4);
            Pb.int32(out, 11, wj.skill1);
            Pb.int32(out, 12, wj.skill2);
            Pb.int32(out, 13, wj.skill3);
            Pb.int32(out, 14, wj.skill4);
            // 15=成长战力（羁绊返还只读此项）；17=装备附加（可卸）
            int[] fp = cultivate.computeFightPowerBaseAndAdd(rec, wj);
            wj.fightPower = fp[0] + fp[1];
            Pb.int32(out, 15, fp[0]);
            wj.ensureTimeStones();
            for (int i = 0; i < 7; i++) {
                Pb.bytes(out, 16, timeStoneSlot(wj.timeStone(i), wj.timeStoneFx(i)));
            }
            Pb.int32(out, 17, fp[1]);
            PlayerRecord.Soul soul = rec == null ? null : rec.souls.get(Integer.valueOf(wj.heroIndex));
            if (soul != null && soul.composed) {
                Pb.bytes(out, 18, soulInner(soul));
            }
            for (Integer sid : cultivate.suitEffectIds(cultivate.equippedOriOf(rec, wj.id))) {
                if (sid != null && sid.intValue() > 0) {
                    Pb.int32(out, 19, sid.intValue());
                }
            }
        });
    }

    private byte[] soulInner(PlayerRecord.Soul soul) {
        return Pb.write(out -> {
            Pb.bool(out, 1, soul.composed);
            Pb.int32(out, 2, soul.stage);
            Pb.int32(out, 3, soul.exp);
        });
    }

    private byte[] timeStoneSlot(String ori, int fx) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, ori == null ? "" : ori);
            Pb.int32(out, 2, fx);
        });
    }

    private byte[] goods(String ori, int count) {
        return Pb.write(out -> {
            Pb.string(out, 1, ori);
            Pb.int32Always(out, 2, count);
        });
    }

    /** CMsgGoods{1 oriName,2 count}：公会 Boss 掉落等复用。 */
    public byte[] goodsItem(String ori, int count) {
        return goods(ori, count);
    }

    public byte[] equipment(PlayerRecord.Equipment eq) {
        int baseScore = cultivate.computeEquipBaseScore(eq);
        eq.baseScore = baseScore;
        return Pb.write(out -> {
            Pb.string(out, 1, eq.id);
            Pb.string(out, 2, eq.ori);
            Pb.string(out, 3, eq.owner);
            Pb.int32(out, 4, eq.level);
            Pb.int32(out, 5, eq.stars);
            Pb.int32(out, 6, eq.guhua);
            Pb.int32(out, 7, baseScore);
            Pb.int32(out, 8, eq.uplevelGold);
            Pb.bool(out, 10, eq.openCuiLian);
            boolean[] parts = eq.cuiLianParts == null ? new boolean[4] : eq.cuiLianParts;
            for (int i = 0; i < 4; i++) {
                Pb.boolAlways(out, 11, i < parts.length && parts[i]);
            }
            Pb.int32(out, 12, eq.jingLianLevel);
            Pb.int32(out, 13, eq.jingLianExp);
            Pb.int32(out, 14, eq.jingLianSubLevel1);
            Pb.int32(out, 15, eq.jingLianSubExp1);
            Pb.int32(out, 16, eq.jingLianSubLevel2);
            Pb.int32(out, 17, eq.jingLianSubExp2);
            if (eq.xiLianValue != 0) {
                Pb.bytes(out, 9, xiLianProp(eq.xiLianType, eq.xiLianValue));
            }
        });
    }

    private byte[] xiLianProp(int type, int value) {
        return Pb.write(out -> {
            Pb.int32(out, 1, type);
            Pb.int32Always(out, 2, value);
        });
    }

    public byte[] formation(PlayerRecord rec, int type) {
        List<String> five = rec.formationSlots(type);
        return Pb.write(out -> {
            Pb.int32(out, 1, type);
            for (int i = 0; i < 5; i++) {
                Pb.stringAlways(out, 2, five.get(i));
            }
        });
    }

    public byte[] vector3(float x, float y, float z) {
        return Pb.write(out -> {
            Pb.float32(out, 1, x);
            Pb.float32(out, 2, y);
            Pb.float32(out, 3, z);
        });
    }

    public byte[] changeRegion(int regionId) {
        return Pb.write(out -> Pb.int32(out, 1, regionId));
    }

    public byte[] accountEnterRet(PlayerRecord rec) {
        return Pb.write(out -> {
            Pb.int32(out, 1, rec.playerId);
            Pb.bool(out, 2, rec.alreadyNewUserGuideFB);
            Pb.int32(out, 4, resolveMainRoleIndex(rec));
            Pb.stringAlways(out, 5, now());
            // 6 isEnableHundredTower -> BCTCommonInfo.mEnable（ChallengeEntryUI.cs:278/669、
            //   GameModesUnlockUI.cs:436、RankListMainDialog.cs:74）。曾经不下发，客户端读到的
            //   默认值是 false，导致百层塔入口永久灰锁 —— 玩法本体（3201-3209/3606）其实已全实装。
            Pb.boolAlways(out, 6, true);
            // 7 isEnableHundredTowerSDDH -> BCTCommonInfo.mEnableSaoDangAni（百层塔扫荡动画）。
            Pb.boolAlways(out, 7, true);
            // 8 isEnableLP -> RecManager.mEnable（录像 SDK 开关）。本环境没有 RecPlay SDK，
            //   显式发 false 保持「录像不可用」的现状，避免客户端点亮一个点不动的录像按钮。
            Pb.boolAlways(out, 8, false);
        });
    }

    /** S2C 20 {@code CMsgGiftPackRet}：ret 取 GiftCodeService.RET_*（0 OK/1 无效/2 全服用完/3 已领过）。 */
    public byte[] giftPackRet(int ret) {
        return Pb.write(out -> Pb.int32Always(out, 1, ret));
    }

    /** S2C 22 {@code CMsg_Account_Check_JiHuoMa_Ret}：1=无效 2=已被使用（客户端只处理这两个值）。 */
    public byte[] activationFail(int ret) {
        return Pb.write(out -> Pb.int32Always(out, 1, ret));
    }

    /** S2C 21 {@code CCMsg_NotifyClient_Check_JiHuoMa}：仅一个 account，客户端据此弹激活码 UI。 */
    public byte[] activationNotify(String account) {
        return Pb.write(out -> Pb.stringAlways(out, 1, account == null ? "" : account));
    }

    /**
     * S2C 30 {@code CCMsg_SysAnnouncement}。客户端 {@code ᝁ.cs:5253-5278} 按 type 分派：
     * 1=走马灯、5=喇叭、2/3/4/5=聊天系统行；{@code SystemAnnouncement.cs:103} 只在
     * regionType≠1 或玩家在主城时入队，故一律显式下发 regionType。
     */
    public byte[] sysAnnouncement(AnnounceCfg.Item it) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, it.type);
            Pb.int32Always(out, 2, it.regionType);
            Pb.stringAlways(out, 3, it.content == null ? "" : it.content);
            Pb.int32Always(out, 4, it.level);
            Pb.int32Always(out, 5, it.contentkey);
        });
    }

    /** 创角左右 1/2；旧档若误把武将表 ID 写进 mainRoleIndex 则按主将推断。 */
    public int resolveMainRoleIndex(PlayerRecord rec) {
        if (rec.mainRoleIndex == 1 || rec.mainRoleIndex == 2) {
            return rec.mainRoleIndex;
        }
        return rec.mainHeroIndex == props.getAltWujiangIndex() ? 2 : 1;
    }

    public byte[] secondLoginDayPrizeRet(int suiPianCount) {
        return Pb.write(out -> Pb.int32(out, 1, suiPianCount));
    }

    public byte[] init(int serialNumber) {
        return Pb.write(out -> Pb.int32Always(out, 1, serialNumber));
    }

    public byte[] attri(int type, int value) {
        return Pb.write(out -> {
            Pb.int32(out, 1, type);
            Pb.int32Always(out, 2, value);
        });
    }

    public byte[] wuJiangAttri(String guid, int type, int value) {
        return wuJiangAttri(guid, type, value, null);
    }

    public byte[] wuJiangAttri(String guid, int type, int value, String strValue) {
        return Pb.write(out -> {
            Pb.string(out, 1, guid);
            Pb.int32(out, 2, type);
            Pb.int32Always(out, 3, value);
            if (strValue != null) {
                Pb.stringAlways(out, 4, strValue);
            }
        });
    }

    /** S2C 5901 CCMsgSutiEffect */
    public byte[] suitEffect(String wujiangGuid, List<Integer> effectIds) {
        return Pb.write(out -> {
            Pb.string(out, 1, wujiangGuid == null ? "" : wujiangGuid);
            if (effectIds != null) {
                for (Integer id : effectIds) {
                    if (id != null && id.intValue() > 0) {
                        Pb.int32(out, 2, id.intValue());
                    }
                }
            }
        });
    }

    /** S2C 4002：刷新一名武将的 15/17 战力拆分（index=表 ID） */
    public byte[] updateWuJiangFightPower(PlayerRecord rec, PlayerRecord.Hero wj) {
        int[] fp = cultivate.computeFightPowerBaseAndAdd(rec, wj);
        wj.fightPower = fp[0] + fp[1];
        return Pb.write(out -> Pb.bytes(out, 1, wuJiangFightPowerItem(wj.heroIndex, fp[0], fp[1])));
    }

    private byte[] wuJiangFightPowerItem(int heroIndex, int fightpower, int addfightpower) {
        return Pb.write(out -> {
            Pb.int32(out, 1, heroIndex);
            Pb.int32(out, 2, fightpower);
            Pb.int32(out, 3, addfightpower);
        });
    }

    public byte[] equipAttri(String guid, int type, int iValue, String strValue) {
        return Pb.write(out -> {
            Pb.string(out, 1, guid);
            Pb.int32(out, 2, type);
            Pb.int32Always(out, 3, iValue);
            Pb.stringAlways(out, 4, strValue);
        });
    }

    public byte[] equipGuid(String guid) {
        return Pb.write(out -> Pb.string(out, 1, guid));
    }

    public byte[] jinJieBookRet(String ori) {
        return Pb.write(out -> Pb.string(out, 1, ori));
    }

    public byte[] decomposeRet(int gold, int wnsp, Map<String, Integer> goods) {
        return Pb.write(out -> {
            Pb.int32(out, 1, gold);
            Pb.int32(out, 2, wnsp);
            if (goods != null) {
                for (Map.Entry<String, Integer> e : goods.entrySet()) {
                    Pb.bytes(out, 3, goods(e.getKey(), e.getValue()));
                }
            }
        });
    }

    public byte[] oneKeyLevelUpItem(String guid, int preLevel, int baoJiSave, int baoJiLevel) {
        return Pb.write(out -> {
            Pb.string(out, 1, guid);
            Pb.int32(out, 2, preLevel);
            Pb.int32(out, 3, baoJiSave);
            Pb.int32(out, 4, baoJiLevel);
        });
    }

    public byte[] oneKeyLevelUpRet(List<byte[]> items) {
        return Pb.write(out -> {
            if (items != null) {
                for (byte[] item : items) {
                    Pb.bytes(out, 1, item);
                }
            }
        });
    }

    public byte[] goodsUpdate(String ori, int count) {
        return goods(ori, count);
    }

    public byte[] resultFb(int result, int starCount) {
        return Pb.write(out -> {
            Pb.int32(out, 1, result);
            Pb.int32(out, 2, starCount);
        });
    }

    /**
     * CMsgFBStar：1 res_id_and_level=关卡ID*10+难度(1/2)，2 star，
     * 3 当日剩余次数，4 当日已买重置次数。
     * key 形如 {@code 1010/normal}。
     */
    public byte[] fbStar(PlayerRecord rec, String stageKey, int star) {
        int[] parsed = parseStageKey(stageKey);
        int regionId = parsed[0];
        int diffCode = parsed[1];
        int levelAndDiff = regionId * 10 + diffCode;
        int playLeft = 0;
        int buyTimes = 0;
        if (needMainFbPlayLimit(regionId)) {
            PlayerRecord.StagePlayLimit lim = ensureMainFbPlayLimit(rec, stageKey, regionId, diffCode);
            playLeft = lim.playLeft;
            buyTimes = lim.buyTimes;
        }
        int playFinal = playLeft;
        int buyFinal = buyTimes;
        return Pb.write(out -> {
            Pb.int32(out, 1, levelAndDiff);
            Pb.int32(out, 2, Math.max(0, Math.min(3, star)));
            Pb.int32(out, 3, playFinal);
            Pb.int32(out, 4, buyFinal);
        });
    }

    /** S2C 1002：更新若干关的剩余次数 / 已买次数。 */
    public byte[] fbPlayTimeUpdate(int fbId, int playLeft, int buyTimes) {
        byte[] one = Pb.write(out -> {
            Pb.int32(out, 1, fbId);
            Pb.int32Always(out, 2, playLeft);
            Pb.int32Always(out, 3, buyTimes);
        });
        return Pb.write(out -> Pb.bytes(out, 1, one));
    }

    public static int[] parseStageKey(String stageKey) {
        int slash = stageKey == null ? -1 : stageKey.indexOf('/');
        int regionId = 0;
        int diffCode = 1;
        if (slash > 0) {
            try {
                regionId = Integer.parseInt(stageKey.substring(0, slash));
            } catch (NumberFormatException ignored) {
                regionId = 0;
            }
            diffCode = "hard".equals(stageKey.substring(slash + 1)) ? 2 : 1;
        }
        return new int[]{regionId, diffCode};
    }

    /** 对齐客户端 FBStarInfo：章内关号 3/6/9/10 才限次。 */
    public static boolean needMainFbPlayLimit(int regionId) {
        int stageInChapter = regionId % 100;
        return stageInChapter == 3 || stageInChapter == 6
                || stageInChapter == 9 || stageInChapter == 10;
    }

    public static int mainFbPlayMax(int diffCode) {
        return diffCode == 2 ? 3 : 10;
    }

    public PlayerRecord.StagePlayLimit ensureMainFbPlayLimit(PlayerRecord rec, String stageKey,
                                                            int regionId, int diffCode) {
        if (rec.progress.stagePlayLimits == null) {
            rec.progress.stagePlayLimits = new java.util.LinkedHashMap<>();
        }
        PlayerRecord.StagePlayLimit lim = rec.progress.stagePlayLimits.get(stageKey);
        if (lim == null) {
            lim = new PlayerRecord.StagePlayLimit();
            lim.playLeft = mainFbPlayMax(diffCode);
            lim.buyTimes = 0;
            rec.progress.stagePlayLimits.put(stageKey, lim);
        }
        return lim;
    }

    /** S2C 1601 CCMsgUpdateResID。 */
    public byte[] updateResId(int playerDynId, int resId) {
        return Pb.write(out -> {
            Pb.int32(out, 1, playerDynId);
            Pb.int32(out, 2, resId);
        });
    }

    /** CMsgDetailPlayerInfo.Varis / CMsgPlayerVaris：1 VariName 2 VariValue。 */
    public byte[] playerVaris(String name, String value) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, name == null ? "" : name);
            Pb.stringAlways(out, 2, value == null ? "" : value);
        });
    }

    public byte[] createRoleRet(int retCode) {
        return Pb.write(out -> Pb.int32(out, 1, retCode));
    }

    /**
     * S2C 2301 改名结果：{@code CCMsgModifyRoleName_Ret{retCode(1), newName(2)}}。
     * retCode 0 = 成功（客户端冒字 100935 并覆盖 mName），1 = 昵称重复（冒字 100936，见 PlayGameState.cs:5273-5293）。
     * newName 用 Always：retCode!=0 时客户端不读它，但真服同包仍带字段。
     */
    public byte[] modifyRoleNameRet(int retCode, String newName) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, retCode);
            Pb.stringAlways(out, 2, newName == null ? "" : newName);
        });
    }

    /** S2C 2302 广播改名：{@code CCMsgUpdatePlayerRoleName{dynID(1), newName(2)}}（dynID = playerId）。 */
    public byte[] updatePlayerRoleName(int dynId, String newName) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, dynId);
            Pb.stringAlways(out, 2, newName == null ? "" : newName);
        });
    }

    public byte[] reconnectOk(PlayerRecord rec) {
        byte[] detail = detail(rec);
        return Pb.write(out -> {
            Pb.int32(out, 1, 1);
            Pb.bytes(out, 2, detail);
            Pb.stringAlways(out, 3, now());
        });
    }

    public byte[] addWuJiang(PlayerRecord.Hero wj, boolean show) {
        byte[] info = wuJiang(null, wj);
        return Pb.write(out -> {
            // f1 用 bytesAlways：武将信息为空时也不能省掉字段，客户端按固定结构反序列化
            Pb.bytesAlways(out, 1, info);
            Pb.bool(out, 2, show);
        });
    }

    public byte[] drawRet(int type, boolean isSingle, List<byte[]> awards, String extraOri, int extraCount) {
        return Pb.write(out -> {
            Pb.int32(out, 1, type);
            Pb.bool(out, 2, isSingle);
            if (awards != null) {
                for (byte[] a : awards) {
                    Pb.bytes(out, 3, a);
                }
            }
            if (extraCount > 0 && extraOri != null && !extraOri.isEmpty()) {
                Pb.bytes(out, 4, goods(extraOri, extraCount));
            }
        });
    }

    public byte[] drawAwardEquip(String guid) {
        return Pb.write(out -> {
            Pb.int32(out, 1, 1);
            Pb.string(out, 2, guid);
        });
    }

    public byte[] drawAwardItem(String ori, int count) {
        byte[] item = goods(ori, count);
        return Pb.write(out -> {
            Pb.int32(out, 1, 3);
            Pb.bytes(out, 4, item);
        });
    }

    public byte[] drawAwardHero(int index, String fragmentOri, int fragmentCount) {
        byte[] role = Pb.write(r -> {
            Pb.int32(r, 1, index);
            Pb.int32(r, 2, fragmentCount);
            Pb.string(r, 3, fragmentOri);
        });
        return Pb.write(out -> {
            Pb.int32(out, 1, 2);
            Pb.bytes(out, 3, role);
            if (fragmentCount > 0 && fragmentOri != null) {
                Pb.bytes(out, 4, goods(fragmentOri, fragmentCount));
            }
        });
    }

    public byte[] drawUpdate(PlayerRecord rec) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, rec.gacha.lastGoldFreeAt);
            Pb.stringAlways(out, 2, rec.gacha.lastDiamondFreeAt);
            Pb.int32(out, 3, rec.gacha.goldFreeLeft);
            Pb.int32(out, 4, rec.gacha.diamondTenCount);
        });
    }

    /** CMsgDetailPlayerInfo.66 / S2C 2403：ZSDrawBaoXiangPriceInfo。 */
    public byte[] zs10PriceInfo(PlayerRecord rec) {
        GameTables.DrawConfig cfg = tables.draw();
        int tenCount = rec == null || rec.gacha == null ? 0 : rec.gacha.diamondTenCount;
        float k = cfg.zsTenZheKou(tenCount);
        int sale = cfg.zsTenZheKouPrice(tenCount);
        return Pb.write(out -> {
            Pb.int32(out, 1, cfg.zsTen);
            Pb.int32(out, 2, sale);
            Pb.float32(out, 3, k);
        });
    }

    public byte[] unionCreateRet(boolean ok, String name) {
        return Pb.write(out -> {
            Pb.bool(out, 1, ok);
            Pb.string(out, 2, name);
        });
    }

    /**
     * S2C 1904 {@code CCMsgNotifyPlayerJoinInUnion_Ret}{1 Success, 2 UnionName, 3 FailReason}。
     *
     * <p><b>f3 必须 Always 写</b>：proto 侧 {@code CCMsgNotifyPlayerJoinInUnion_Ret.cs:15}
     * 的字段初始化器是 {@code EFailJoinInUnionReason.EFJIUR_ALREADYHAVEUNION = 1}
     * （{@code :48 [DefaultValue(...ALREADYHAVEUNION)]}），protobuf-net 缺字段时回落到 1
     * —— 不是 0。而服务端用 {@code failReason = 0} 表示「无失败原因」（审批制下「申请已提交」），
     * 一旦走 {@link Pb#int32} 省略 0，客户端
     * {@code UnionCreateAndJoinSystem.cs:183-197} 的 {@code switch (FailReason)} 就命中
     * case 1 → {@code StrTable 100708}「已经有了公会」⇒ 申请成功的玩家收到假提示。
     * 显式写 0 时该 switch 无 case 0（{@code :198 if (text != "")} 才弹）⇒ 静默，正是本意。
     */
    public byte[] unionJoinRet(boolean ok, String name, int failReason) {
        return Pb.write(out -> {
            Pb.bool(out, 1, ok);
            Pb.stringAlways(out, 2, name == null ? "" : name);
            Pb.int32Always(out, 3, failReason);
        });
    }

    /** 公会默认图标：APK {@code GameData/PublicIcon.txt:10「公会默认图标」= WuPinTuBiao/EQ011}。 */
    public static final String DEFAULT_UNION_ICON = "WuPinTuBiao/EQ011";

    /**
     * 公会图标必须形如 {@code atlas/sprite}。客户端所有公会图标渲染路径都无条件
     * {@code Split(' ', '\t', '/')} 后取 {@code [1]}：{@code UnionPVPRankListInfo.cs:32-39}、
     * {@code UnionRankListInfo.cs:31-38}、{@code UnionInfoTips.cs:73-74}、
     * {@code UnionBattleRecordTips.cs:95/102/104} ⇒ 缺 '/'（或含空格/制表符）会
     * IndexOutOfRangeException，1951/1952/1953/1954/1955/1956 六个包一起挂。
     * 正常流程由 {@code UnionCreateAndJoinSystem.cs:373} 拼 {@code atlas + "/" + sprite} 保证，
     * 这里对历史存档与伪造包兜底。
     */
    public static String unionIcon(String icon) {
        if (icon == null || icon.indexOf('/') <= 0 || icon.endsWith("/")
                || icon.indexOf(' ') >= 0 || icon.indexOf('\t') >= 0) {
            return DEFAULT_UNION_ICON;
        }
        return icon;
    }

    public byte[] unionSimple(WorldStore.UnionRecord u, boolean requested) {
        return Pb.write(out -> {
            Pb.string(out, 1, u.id);
            Pb.string(out, 2, unionIcon(u.icon));
            Pb.string(out, 3, u.name);
            Pb.int32(out, 4, u.members.size());
            Pb.int32(out, 5, u.joinLevel);
            Pb.int32(out, 6, u.joinTypeCode());
            Pb.string(out, 7, u.notice);
            Pb.bool(out, 8, requested);
            Pb.int32(out, 9, u.level);
        });
    }

    /**
     * 1903 公会列表。{@code requested} 是 {@code CUnionSimpleInfo.IsRequest}（字段 8）：
     * 客户端 {@code UnionCreateAndJoinSystem.cs:272} 靠它把「申请加入」按钮灰化并显示
     * StrTable 100176「已申请」；审批制申请的回包 {@code unionJoinRet(false, name, 0)} 在客户端
     * {@code :183-197} 不弹任何提示 ⇒ 这个字段是「已申请」的唯一反馈通道。
     */
    public byte[] unionList(List<WorldStore.UnionRecord> list, int playerId) {
        return Pb.write(out -> {
            if (list == null) {
                return;
            }
            for (WorldStore.UnionRecord u : list) {
                boolean requested = false;
                if (u.requesters != null) {
                    for (WorldStore.Member r : u.requesters) {
                        if (r.playerId == playerId) {
                            requested = true;
                            break;
                        }
                    }
                }
                Pb.bytes(out, 1, unionSimple(u, requested));
            }
        });
    }

    public byte[] unionMember(WorldStore.Member m, boolean online) {
        return Pb.write(out -> {
            Pb.int32(out, 1, m.playerId);
            Pb.int32(out, 2, m.heroIndex);
            Pb.int32(out, 3, m.level);
            Pb.string(out, 4, m.name);
            Pb.int32(out, 5, PlayerRecord.jobCode(m.job));
            Pb.int32(out, 6, m.contribution);
            Pb.bool(out, 7, online);
            // 客户端 UnionManagerSystem 管理面板用 OfflineTime=="" 判在线；在线给空串、离线给时刻
            Pb.stringAlways(out, 8, online ? "" : timeAt(m.lastOnlineAt));
        });
    }

    public byte[] unionDetail(WorldStore.UnionRecord u, java.util.Set<Integer> onlineGuids) {
        byte[] members = unionMemberInfo(u.members, onlineGuids);
        return Pb.write(out -> {
            Pb.string(out, 1, u.id);
            Pb.string(out, 2, unionIcon(u.icon));
            Pb.string(out, 3, u.name);
            Pb.int32(out, 4, u.members.size());
            Pb.string(out, 5, u.notice);
            Pb.int32(out, 6, u.joinLevel);
            Pb.int32(out, 7, u.joinTypeCode());
            Pb.int32(out, 8, u.level);
            Pb.int32(out, 9, u.crystal);
            Pb.int32(out, 10, u.growth);
            // 11-13 解散阈值：9999 = 不启用（客户端 UnionManagerSystem.cs:206 直接隐藏两行提示）
            Pb.int32Always(out, 11, 9999);
            Pb.int32Always(out, 12, 0);
            Pb.int32Always(out, 13, 0);
            // 必须用 bytesAlways：Pb.bytes 在 nested.length == 0 时不写 tag，
            // 成员列表为空时字段 14 整个消失，客户端 MemberInfo 保持 null ⇒ PlayGameState.cs:4086 NRE。
            Pb.bytesAlways(out, 14, members);
        });
    }

    /**
     * 1906 的最小合法包：只写字段 14 的空 {@code CUnionMembersInfo}。
     *
     * <p>字段 14 {@code CUnionDetailInfo._MemberInfo} 在 APK 里无初值，且是
     * {@code [DefaultValue(null)][ProtoMember(14, IsRequired = false)]}；客户端
     * {@code PlayGameState.cs:4070} 拿到 1906 后直接 {@code MemberInfo.Clear()}、{@code :4086} 又读
     * {@code MemberInfo.MemberInfo.Count} ⇒ 字段 14 缺失会 NRE 并把本地公会详情
     * （Name/Guid/Notice/MemberCount）清成默认值。公会不存在时用它兜底。</p>
     *
     * <p>注意必须用 {@link Pb#bytesAlways}：{@link Pb#bytes} 在 {@code nested.length == 0} 时
     * <b>不写 tag</b>（{@code Pb.java:81-85}），于是这个「空消息」在线上是 0 字节 body，
     * 与注释的意图正好相反 —— 客户端仍然拿到 null 并 NRE。</p>
     */
    public byte[] unionDetailEmpty() {
        return Pb.write(out -> {
            // 字段 11 必须写 9999（解散功能停用）：缺省 0 会被客户端
            // UnionManagerSystem.cs:206-209 判成「公会即将解散」并显示两行提示，
            // 与正常包（unionDetail 里 int32Always(11,9999)）口径不一致。
            Pb.int32Always(out, 11, 9999);
            Pb.bytesAlways(out, 14, new byte[0]);
        });
    }

    /** S2C 1922 CUnionMembersInfo：成员列表（1525 请求 / 1906 内嵌共用）。 */
    public byte[] unionMemberInfo(List<WorldStore.Member> members, java.util.Set<Integer> onlineGuids) {
        return Pb.write(out -> {
            if (members != null) {
                for (WorldStore.Member m : members) {
                    boolean on = onlineGuids != null && onlineGuids.contains(Integer.valueOf(m.playerId));
                    Pb.bytes(out, 1, unionMember(m, on));
                }
            }
        });
    }

    /** S2C 1907 CCMsgGetUnionRequestPlayers_Ret。 */
    public byte[] unionRequesters(List<WorldStore.Member> requesters) {
        return Pb.write(out -> {
            if (requesters != null) {
                for (WorldStore.Member m : requesters) {
                    Pb.bytes(out, 1, Pb.write(o -> {
                        Pb.int32(o, 1, m.playerId);
                        Pb.stringAlways(o, 2, m.name == null ? "" : m.name);
                        Pb.int32Always(o, 3, m.level);
                        Pb.int32Always(o, 4, m.fightPower);
                    }));
                }
            }
        });
    }

    /**
     * S2C 1905 CCMsgNotifyUnionOwnerOrElderRequesterJoinInUnion_Ret。
     * 会长/长老在线时实时把新成员插进成员列表（UnionManagerSystem.cs:472-498 读 newMember）。
     */
    public byte[] handleRequesterRet(boolean ok, int failReason, WorldStore.Member newMember, boolean online) {
        return Pb.write(out -> {
            Pb.bool(out, 1, ok);
            Pb.int32(out, 2, failReason);
            if (newMember != null) {
                Pb.bytes(out, 3, unionMember(newMember, online));
            }
        });
    }

    /** S2C 1908 CMsgUnionJob：自己的职位变更（任命/罢免/转让后回推）。 */
    public byte[] unionJobUpdate(String job) {
        return Pb.write(out -> Pb.int32Always(out, 1, PlayerRecord.jobCode(job)));
    }

    /** S2C 1910 CCMsgAppointElder_Ret。 */
    public byte[] appointElderRet(boolean ok, boolean appoint, int guid) {
        return Pb.write(out -> {
            Pb.bool(out, 1, ok);
            Pb.bool(out, 2, appoint);
            Pb.int32Always(out, 3, guid);
        });
    }

    /** S2C 1911 CCMsgPlayerGuid：新会长的 playerId。 */
    public byte[] notifyUnionOwner(int guid) {
        return Pb.write(out -> Pb.int32Always(out, 1, guid));
    }

    /** S2C 1968 CCMsgUnionNotifyOwnerChangeOnce：会长久未登录自动让位的提示文案。 */
    public byte[] notifyOwnerChange(String ownerName) {
        return Pb.write(out -> Pb.stringAlways(out, 1, ownerName == null ? "" : ownerName));
    }

    // ------------------------------------------------ 公会战 PvP（1549–1568 → 1946–1965）

    /** S2C 1947 CCMsgRequestIsEnrollUnionPvP_Ret{1 IsEnRolled}。 */
    public byte[] pvpIsEnrollRet(boolean enrolled) {
        return Pb.write(out -> Pb.bool(out, 1, enrolled));
    }

    /**
     * S2C 1946 / 1949 空 body。APK 里 {@code CCMsgRequestEnrollUnionPvP_Ret} /
     * {@code CCMsgUpdateOnePointDefFormation_Ret} 两个类**不存在**，客户端 handler 也完全不反序列化
     * （APK Client\ᝁ.cs:6295 / :6336），发空包即正确。
     */
    public byte[] pvpEmptyRet() {
        return new byte[0];
    }

    /** S2C 1948 CCMsgRequestDefPointDefFormation_Ret{1 DefPointIndex,2 repeated CMsgDefPointDefFormation}。 */
    public byte[] pvpDefPointFormation(int pointIndex, List<WorldStore.PvpFormation> formations) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, pointIndex);
            for (WorldStore.PvpFormation f : formations) {
                Pb.bytesAlways(out, 2, pvpDefFormation(f));
            }
        });
    }

    /** CMsgDefPointDefFormation{1 FormationIndex,2 FightPower,3 FormationFromPlayerName,4 repeated wjJobAndBriefInfo}。 */
    private byte[] pvpDefFormation(WorldStore.PvpFormation f) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, f.formationIndex);
            Pb.int32Always(out, 2, f.fightPower);
            Pb.stringAlways(out, 3, f.playerName == null ? "" : f.playerName);
            for (WorldStore.PvpWjBrief w : pvpWjs(f)) {
                Pb.bytesAlways(out, 4, wjJobAndBrief(w.job, w.index, w.level, w.stage, w.stars));
            }
        });
    }

    /** S2C 1950 CCMsgRequestAllUnionPvPDefFormation_Ret{1 repeated CMsgOneUnionPvPDefFormation}。 */
    public byte[] pvpAllDefFormations(List<WorldStore.PvpFormation> formations) {
        return Pb.write(out -> {
            for (WorldStore.PvpFormation f : formations) {
                Pb.bytesAlways(out, 1, pvpOneDefFormation(f));
            }
        });
    }

    /** CMsgOneUnionPvPDefFormation{1 PlayerGuid,2 PlayerFormationType,3 PlayerName,4 PlayerLevel,5 PlayerResID,6 FightPower,7 DefPointsIndex,8 wjJobAndBriefInfo}。 */
    private byte[] pvpOneDefFormation(WorldStore.PvpFormation f) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, f.playerId);
            Pb.int32Always(out, 2, f.formationType);
            Pb.stringAlways(out, 3, f.playerName == null ? "" : f.playerName);
            Pb.int32Always(out, 4, f.playerLevel);
            Pb.int32Always(out, 5, f.playerResId);
            Pb.int32Always(out, 6, f.fightPower);
            Pb.int32Always(out, 7, f.point);
            for (WorldStore.PvpWjBrief w : pvpWjs(f)) {
                Pb.bytesAlways(out, 8, wjJobAndBrief(w.job, w.index, w.level, w.stage, w.stars));
            }
        });
    }

    /** CMsgWuJiangJobAndBriefInfo{1 job(eFormationJob),2 wjBriefInfo(CMsgWuJiangBriefInfo)}。 */
    public byte[] wjJobAndBrief(int job, int index, int level, int stage, int stars) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, job);
            Pb.bytesAlways(out, 2, wjBrief(index, level, stage, stars));
        });
    }

    /** S2C 1951 / 1952 CCMsgUnionRankList{1 myUnionRank,2 myUnionValue,3 repeated CCMsgUnionRankListItem}。 */
    public byte[] unionRankList(int myRank, int myValue, List<byte[]> items) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, myRank);
            Pb.int32Always(out, 2, myValue);
            for (byte[] it : items) {
                Pb.bytesAlways(out, 3, it);
            }
        });
    }

    /** CCMsgUnionRankListItem{1 guid,2 rank,3 name,4 icon,5 level,6 value}。 */
    public byte[] unionRankListItem(String guid, int rank, String name, String icon, int level, int value) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, guid == null ? "" : guid);
            Pb.int32Always(out, 2, rank);
            Pb.stringAlways(out, 3, name == null ? "" : name);
            Pb.stringAlways(out, 4, unionIcon(icon));
            Pb.int32Always(out, 5, level);
            Pb.int32Always(out, 6, value);
        });
    }

    /** S2C 1953 CCMsgUnionPvPFightRankList{1 myUnionRank,2 mywinTimes,3 myfailTimes,4 repeated rankItem}。 */
    public byte[] pvpFightRankList(int myRank, int myWin, int myFail, List<byte[]> items) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, myRank);
            Pb.int32Always(out, 2, myWin);
            Pb.int32Always(out, 3, myFail);
            for (byte[] it : items) {
                Pb.bytesAlways(out, 4, it);
            }
        });
    }

    /** CCMsgUnionPvPFightRankListItem{1 guid,2 rank,3 name,4 icon,5 level,6 winTimes,7 failTimes}。 */
    public byte[] pvpFightRankItem(String guid, int rank, String name, String icon, int level, int win, int fail) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, guid == null ? "" : guid);
            Pb.int32Always(out, 2, rank);
            Pb.stringAlways(out, 3, name == null ? "" : name);
            Pb.stringAlways(out, 4, unionIcon(icon));
            Pb.int32Always(out, 5, level);
            Pb.int32Always(out, 6, win);
            Pb.int32Always(out, 7, fail);
        });
    }

    /** S2C 1954 / 1955 CCMsgUnionBriefInfo{1 rankItem,2 owner,3 memberCnt,4 notice}。 */
    public byte[] unionBriefInfo(byte[] rankItem, String owner, int memberCnt, String notice) {
        return Pb.write(out -> {
            if (rankItem != null) {
                Pb.bytesAlways(out, 1, rankItem);
            }
            Pb.stringAlways(out, 2, owner == null ? "" : owner);
            Pb.int32Always(out, 3, memberCnt);
            Pb.stringAlways(out, 4, notice == null ? "" : notice);
        });
    }

    /** S2C 1956 CCMsgRequestUnionPvPFightRank_FightRecord_Ret{1 repeated OneFightRecord}。 */
    public byte[] pvpFightRecords(List<WorldStore.PvpFightRecord> records) {
        return Pb.write(out -> {
            for (WorldStore.PvpFightRecord r : records) {
                Pb.bytesAlways(out, 1, Pb.write(o -> {
                    Pb.stringAlways(o, 1, r.myName == null ? "" : r.myName);
                    Pb.stringAlways(o, 2, r.myIcon == null ? "" : r.myIcon);
                    Pb.int32Always(o, 3, r.myLevel);
                    Pb.stringAlways(o, 4, r.targetName == null ? "" : r.targetName);
                    Pb.stringAlways(o, 5, r.targetIcon == null ? "" : r.targetIcon);
                    Pb.int32Always(o, 6, r.targetLevel);
                    Pb.bool(o, 7, r.win);
                }));
            }
        });
    }

    /** S2C 1957 CCMsgRequestMyUnionPvPDefPointBriefInfo_Ret{1 repeated CMsgOnePointTotalFightPower}。 */
    public byte[] pvpMyDefPointBrief(List<byte[]> points) {
        return Pb.write(out -> {
            for (byte[] p : points) {
                Pb.bytesAlways(out, 1, p);
            }
        });
    }

    /** CMsgOnePointTotalFightPower{1 defPointIndex,2 defTotalFightPower,3 IsRequesterDefThisPoint}。 */
    public byte[] pvpPointTotalFightPower(int point, int totalFightPower, boolean mine) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, point);
            Pb.int32Always(out, 2, totalFightPower);
            Pb.bool(out, 3, mine);
        });
    }

    /**
     * S2C 1958 CCMsgRequestFightTargetUnionDefPointBriefInfo_Ret
     * {1 selfAttackedDefPoints,2 targetAttackedDefPoints,3 repeated onePointBriefInfo,4 targetUnionName}。
     * 客户端在 {@code targetUnionName == ""} 时回发 1562（APK PlayGameState.cs:6359），故无对手时传空串。
     */
    public byte[] pvpTargetDefPointBrief(int selfAttacked, int targetAttacked, List<byte[]> points,
                                         String targetUnionName) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, selfAttacked);
            Pb.int32Always(out, 2, targetAttacked);
            for (byte[] p : points) {
                Pb.bytesAlways(out, 3, p);
            }
            Pb.stringAlways(out, 4, targetUnionName == null ? "" : targetUnionName);
        });
    }

    /** CMsgUnionPvPFightTargetOnePointBriefInfo{1 defPointIndex,2 isAttacked,3 fightPower,5 isCanAttack}（无字段 4）。 */
    public byte[] pvpTargetPointBrief(int point, boolean attacked, int fightPower, boolean canAttack) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, point);
            Pb.bool(out, 2, attacked);
            Pb.int32Always(out, 3, fightPower);
            Pb.bool(out, 5, canAttack);
        });
    }

    /** S2C 1959 CCMsgRequestMyUnionPvPDefPointLeftFormationCnt_Ret{1 selfAttackedDefPoints,2 targetAttackedDefPoints,3 repeated leftCnt}。 */
    public byte[] pvpLeftDefFormation(int selfAttacked, int targetAttacked, List<byte[]> points) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, selfAttacked);
            Pb.int32Always(out, 2, targetAttacked);
            for (byte[] p : points) {
                Pb.bytesAlways(out, 3, p);
            }
        });
    }

    /** CMsgOnePointLeftDefFormationCnt{1 defPointIndex,2 leftFormationCnt,3 isBeAttacked}。 */
    public byte[] pvpPointLeftFormation(int point, int left, boolean attacked) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, point);
            Pb.int32Always(out, 2, left);
            Pb.bool(out, 3, attacked);
        });
    }

    /**
     * S2C 1960 CCMsgRequestUnionPvPFightDefPointDetailInfoInWarStart_Ret{1 PointIndex,2 IsMySelf,3 repeated FormationBriefInfo}。
     *
     * <p>列表**含已被打掉的阵容**（{@code killerName} 非空）：客户端用 f7 判「该阵容已阵亡」——
     * 整行降 200 深度变暗、显示骷髅、写击杀者名并隐藏「攻击」按钮
     * （{@code GongHuiZhanJuDianAttackUI.cs:120/133/148-157}），f8 显示「攻击中」标记。
     */
    public byte[] pvpDefPointDetail(int point, boolean mine, List<WorldStore.PvpFormation> formations) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, point);
            Pb.bool(out, 2, mine);
            for (WorldStore.PvpFormation f : formations) {
                Pb.bytesAlways(out, 3, Pb.write(o -> {
                    Pb.int32Always(o, 1, f.formationIndex);
                    Pb.stringAlways(o, 2, f.playerName == null ? "" : f.playerName);
                    Pb.int32Always(o, 3, f.playerResId);
                    Pb.int32Always(o, 4, f.playerLevel);
                    Pb.int32Always(o, 5, f.fightPower);
                    for (WorldStore.PvpWjBrief w : pvpWjs(f)) {
                        Pb.bytesAlways(o, 6, wjJobAndBrief(w.job, w.index, w.level, w.stage, w.stars));
                    }
                    Pb.stringAlways(o, 7, f.killerName == null ? "" : f.killerName);
                    Pb.bool(o, 8, f.isFighting);
                }));
            }
        });
    }

    /**
     * S2C 1961 {@code CCMsgUnionPvPRealTeamDetailInfo}{1 myTeam,2 targetTeam}。
     * <b>必须两个子消息都在</b>：两者 getter 无惰性初始化，空 body 会让客户端
     * {@code MatchPlayer.UnPackUnionPVP(null)} / {@code MainPlayer.UnPackDataForMyTeam(null)} /
     * {@code GongHuiZhanPvPController.UnPackExtraInfo} 三处 NRE（APK Client\ᝁ.cs:6404-6406）。
     */
    public byte[] pvpRealTeamDetail(PlayerRecord self, PlayerRecord target) {
        return pvpRealTeamDetail(self, target, null, null, null, null);
    }

    /**
     * 带双方队伍武将（f2 {@code WJ}）的 1961。
     *
     * <p><b>f2 不能省</b>：客户端 {@code MatchPlayer.cs:192} 按 {@code info.WJ.Count} 建对手全队、
     * {@code :219-236} 按 job 给防守方五个 GUID 赋值，{@code GongHuiZhanPvPController.cs:27-41} 按
     * {@code WJ[i].index/curHp} 填双方血量表；只发 playerGuid 会让防守方 GUID 全空、开战建不出将
     * （{@code :493 CreateWuJiangByGUID} 拿到空 GUID）。{@code MainPlayer.cs:548/555} 也用己方
     * {@code WJ[i]} 同步武将属性。</p>
     *
     * @param selfWjs   己方队伍武将（可空；为空则 f2 不写）
     * @param selfHps   与 selfWjs 同序的血量（0=阵亡，非 0=存活；客户端只判 0/非 0）
     * @param targetWjs 对手队伍武将（可空）
     * @param targetHps 与 targetWjs 同序的血量
     */
    public byte[] pvpRealTeamDetail(PlayerRecord self, PlayerRecord target,
                                    List<WorldStore.PvpWjBrief> selfWjs, List<Integer> selfHps,
                                    List<WorldStore.PvpWjBrief> targetWjs, List<Integer> targetHps) {
        return pvpRealTeamDetail(self, target, selfWjs, selfHps, targetWjs, targetHps, target);
    }

    /**
     * 同上，但对手侧的养成/装备/器魂/套装从 {@code targetGearOwner} 取（{@code null} 时对手只写 f1–f6）。
     *
     * <p>防守方存档查不到时仍要回包（客户端两个子消息都必须存在），此时用攻方存档顶位以保证外层
     * playerId/羁绊字段可解析，但**不能**拿攻方的装备去填对手武将 —— 武将 index 是全局表索引，
     * 攻方若持有同一个武将就会把攻方装备错当对手装备。</p>
     */
    public byte[] pvpRealTeamDetail(PlayerRecord self, PlayerRecord target,
                                    List<WorldStore.PvpWjBrief> selfWjs, List<Integer> selfHps,
                                    List<WorldStore.PvpWjBrief> targetWjs, List<Integer> targetHps,
                                    PlayerRecord targetGearOwner) {
        return Pb.write(out -> {
            Pb.bytesAlways(out, 1, pvpRealFightTarget(self, selfWjs, selfHps, self));
            Pb.bytesAlways(out, 2, pvpRealFightTarget(target, targetWjs, targetHps, targetGearOwner));
        });
    }

    /**
     * CCMsgUnionPvPRealFightTargetDetailInfo{1 PlayerGuid,2 WJ,3 BuddiesIndex,4 JiBanSlotInfo,5 FightPowerReturn}。
     * 注意字段号与 JJC 版（3 WJ / 4 BuddiesIndex / 5 JiBanSlotInfo / 6 FightPowerReturn）不同，不能复用
     * {@link #jjcFightTargetDetailSelf}。己方必须带羁绊，否则开战会把登录羁绊整表覆盖为空。
     */
    public byte[] pvpRealFightTarget(PlayerRecord rec) {
        return pvpRealFightTarget(rec, null, null);
    }

    /**
     * 带 f2 {@code WJ}（repeated {@code CCMsgWuJiangAllInfoAndJobAndHp}）的
     * {@code CCMsgUnionPvPRealFightTargetDetailInfo}。子消息字段：
     * 1 job(eFormationJob) / 2 curHp / 3 index / 4 level / 5 jieduan / 6 stars
     * / 7-10 jieduan_type_N_para / 11-14 skillindex_N_level / 15 timestoneinfo / 16 equipments
     * / 17 equipSoul / 18 suiteffectid。
     *
     * <p>用户 m24587 #2「1961 协议要求补全」：f7–f18 原来恒缺，客户端
     * {@code MatchPlayer.cs:192-212} 按 f16 建对手装备、{@code WuJiang.cs:860} 用 f7–f10 经
     * {@code WuJiangJinjiePropertyCfgMgr.GetToTalJinJieGrow} 算进阶属性、{@code WuJiang.cs:318-322}
     * 用 f18 生成套装被动 ⇒ 缺这些字段时对手是「无装备/无进阶参数/无套装」的空壳，本地战斗必败。
     * 字段号与 {@code CCMsgWuJiangAllInfoAndJob}（{@code allInfoAndJob}，无 curHp 故整体前移 1）同序。</p>
     *
     * @param gearOwner 该队武将所属存档，用于取养成/装备/器魂/套装；{@code null} 时只写 f1–f6
     */
    public byte[] pvpRealFightTarget(PlayerRecord rec, List<WorldStore.PvpWjBrief> wjs, List<Integer> hps) {
        return pvpRealFightTarget(rec, wjs, hps, rec);
    }

    /** 见 {@link #pvpRealFightTarget(PlayerRecord, List, List, PlayerRecord)}。 */
    public byte[] pvpRealFightTarget(PlayerRecord rec, List<WorldStore.PvpWjBrief> wjs, List<Integer> hps,
                                     PlayerRecord gearOwner) {
        if (rec == null) {
            return Pb.write(out -> Pb.int32Always(out, 1, 0));
        }
        rec.jiban.ensure();
        List<Integer> buddies = paddedBuddies(rec);
        int[] fpRet = computeFightPowerReturn(rec);
        return Pb.write(out -> {
            Pb.int32Always(out, 1, rec.playerId);
            if (wjs != null) {
                for (int i = 0; i < wjs.size(); i++) {
                    WorldStore.PvpWjBrief w = wjs.get(i);
                    if (w == null) {
                        continue;
                    }
                    int hp = (hps != null && i < hps.size() && hps.get(i) != null)
                            ? hps.get(i).intValue() : 1;
                    Pb.bytesAlways(out, 2, Pb.write(o -> {
                        Pb.int32Always(o, 1, w.job);
                        Pb.int32Always(o, 2, hp);
                        Pb.int32Always(o, 3, w.index);
                        Pb.int32Always(o, 4, w.level);
                        Pb.int32Always(o, 5, w.stage);
                        Pb.int32Always(o, 6, w.stars);
                        PlayerRecord.Hero hero = gearOwner == null ? null : gearOwner.findHeroByIndex(w.index);
                        if (hero != null) {
                            Pb.int32Always(o, 7, Math.max(0, hero.stagePara1));
                            Pb.int32Always(o, 8, Math.max(0, hero.stagePara2));
                            Pb.int32Always(o, 9, Math.max(0, hero.stagePara3));
                            Pb.int32Always(o, 10, Math.max(0, hero.stagePara4));
                            Pb.int32Always(o, 11, Math.max(1, hero.skill1));
                            Pb.int32Always(o, 12, Math.max(1, hero.skill2));
                            Pb.int32Always(o, 13, Math.max(1, hero.skill3));
                            Pb.int32Always(o, 14, Math.max(1, hero.skill4));
                            writeWjGear(o, gearOwner, hero, 15, 16, 17, 18);
                        }
                    }));
                }
            }
            for (Integer idx : buddies) {
                Pb.int32Always(out, 3, idx == null ? 0 : idx.intValue());
            }
            for (PlayerRecord.JiBanSlot slot : rec.jiban.slots) {
                Pb.bytesAlways(out, 4, jiBanSlot(slot));
            }
            Pb.int32Always(out, 5, fpRet[0]);
            Pb.int32Always(out, 5, fpRet[1]);
            Pb.int32Always(out, 5, fpRet[2]);
        });
    }

    /** S2C 1963 CCMsgUpdateUnionPvPPointFormationAttacker{1 AttackerCnt}。 */
    public byte[] pvpPointAttackers(int attackerCnt) {
        return Pb.write(out -> Pb.int32Always(out, 1, attackerCnt));
    }

    /**
     * S2C 1964 CCMsgRequestUnionPvPDeadWJ_Ret{1 repeated WJIndex,2 repeated WJHP}。
     * 两个 repeated 长度**必须相等**，否则客户端 {@code Client\ᝁ.cs:6417} 按序号取值会越界。
     */
    public byte[] pvpDeadWj(List<Integer> wjIndexes, List<Integer> wjHp) {
        return Pb.write(out -> {
            int n = Math.min(wjIndexes == null ? 0 : wjIndexes.size(), wjHp == null ? 0 : wjHp.size());
            for (int i = 0; i < n; i++) {
                Pb.int32Always(out, 1, wjIndexes.get(i).intValue());
                Pb.int32Always(out, 2, wjHp.get(i).intValue());
            }
        });
    }

    /** S2C 1965 CCMsgUnionPvPFightRecordToClient{1 IsWin,2 UnionGrow,3 UnionJinShi,4 SelfAttackedDefPoints,5 TargetAttackedDefPoints,6 TargetUnionName}。 */
    public byte[] pvpFightRecordToClient(boolean win, int grow, int jinShi, int selfAttacked,
                                         int targetAttacked, String targetUnionName) {
        return Pb.write(out -> {
            Pb.bool(out, 1, win);
            Pb.int32Always(out, 2, grow);
            Pb.int32Always(out, 3, jinShi);
            Pb.int32Always(out, 4, selfAttacked);
            Pb.int32Always(out, 5, targetAttacked);
            Pb.stringAlways(out, 6, targetUnionName == null ? "" : targetUnionName);
        });
    }

    private static List<WorldStore.PvpWjBrief> pvpWjs(WorldStore.PvpFormation f) {
        if (f.wjs == null) {
            f.wjs = new ArrayList<>();
        }
        return f.wjs;
    }

    /** S2C 1921 CCMsgCancelLevelUpOneUnionBuilding_Ret。 */
    public byte[] unionCancelLevelUp(int type, boolean ok) {
        return Pb.write(out -> {
            Pb.int32(out, 1, type);
            Pb.bool(out, 2, ok);
        });
    }

    /** S2C 1918 CCMsgReplaceOneEmployersByPlayer_Ret。 */
    public byte[] replaceEmployerRet(int result) {
        return Pb.write(out -> Pb.int32Always(out, 1, result));
    }

    /**
     * S2C 1914 CCMsgUnionAttriUpdate{1 attribute(EUnionAttribute),2 iValue,3 strValue}。
     * EUnionAttribute：1 捐赠次数 / 2 兄弟币 / 3 贡献 / 4 等级 / 5 晶石 / 6 成长值 / 7 勇气币。
     */
    public byte[] unionAttriUpdate(int attribute, int iValue, String strValue) {
        return Pb.write(out -> {
            Pb.int32(out, 1, attribute);
            Pb.int32Always(out, 2, iValue);
            Pb.stringAlways(out, 3, strValue == null ? "" : strValue);
        });
    }

    /**
     * S2C 1924 CCMsgRequestEmployAWuJiang_ZZS_Ret{1 suc,2 employInfo(CCMsgRemotePlayerWuJiangDetail),3 CDTimeLeft,4 EmployPrice}。
     *
     * <p><b>f2 的 detailInfo 必须写</b>：客户端 {@code PlayGameState.cs:5630} 在调
     * {@code ConfirmEmployRet()}（{@code :5631}）**之前**无条件读 {@code employInfo.detailInfo.index}，
     * 而 proto 里 {@code CCMsgRemotePlayerWuJiangDetail.detailInfo} 是
     * {@code [DefaultValue(null)]} 且不初始化（{@code CCMsgRemotePlayerWuJiangDetail.cs:42-54}）
     * ⇒ 只写 playerGuid 会让客户端 NRE、雇佣面板卡在等待态。</p>
     */
    public byte[] employWjZzsRet(boolean suc, int playerGuid, int cdTimeLeft, int employPrice) {
        return employWjZzsRet(suc, playerGuid, 0, 0, 0, 0, 0, cdTimeLeft, employPrice);
    }

    /** 带被雇佣武将详情（CMsgWuJiang 的 index/level/jieduan/stars/fightpower）的 1924。 */
    public byte[] employWjZzsRet(boolean suc, int playerGuid, int wjIndex, int level, int jieduan, int stars,
                                 int fightPower, int cdTimeLeft, int employPrice) {
        return Pb.write(out -> {
            Pb.bool(out, 1, suc);
            if (suc) {
                Pb.bytes(out, 2, Pb.write(o -> {
                    Pb.int32Always(o, 1, playerGuid);
                    Pb.bytes(o, 2, Pb.write(d -> {
                        Pb.int32Always(d, 2, wjIndex);
                        Pb.int32Always(d, 3, level);
                        Pb.int32Always(d, 4, jieduan);
                        Pb.int32Always(d, 6, stars);
                        Pb.int32Always(d, 15, fightPower);
                    }));
                }));
            }
            Pb.int32Always(out, 3, cdTimeLeft);
            Pb.int32Always(out, 4, employPrice);
        });
    }

    /**
     * S2C 2901 {@code CCMsgFightPowerRankListInfo}：1 myRank、2 repeated 行。
     * 行 = {@code CCMsgFightPowerRankListItem}：1 rank、2 guid、3 name、4 resID(头像/主将)、
     * 5 level、6 totalFightPower（消费点 {@code RankListFightInfo.cs:23-30}、
     * {@code RankListMainDialog.cs:509/544/599/602/604}）。
     */
    public byte[] fightPowerRankList(int myRank, List<WorldStore.JjcSlot> rows) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, Math.max(1, myRank));
            if (rows == null) {
                return;
            }
            int n = Math.min(50, rows.size());
            for (int i = 0; i < n; i++) {
                WorldStore.JjcSlot s = rows.get(i);
                if (s == null) {
                    continue;
                }
                final int rank = i + 1;
                Pb.bytes(out, 2, Pb.write(item -> {
                    Pb.int32Always(item, 1, rank);
                    Pb.int32Always(item, 2, s.targetGuid);
                    Pb.stringAlways(item, 3, s.name == null ? "" : s.name);
                    Pb.int32Always(item, 4, s.heroIndex);
                    Pb.int32Always(item, 5, s.level);
                    Pb.int32Always(item, 6, Math.max(1, s.fightPower));
                }));
            }
        });
    }

    /**
     * S2C 2902 {@code CCMsgRemotePlayerBreifInfo}（2502 榜内点人 / 2504 场景点人共用）。
     * <p>1 rankListInfo（<b>必须回填 guid</b>：客户端 {@code TargetPlayerInfo.cs:70-78} 拿
     * {@code rankListInfo.guid} 当后续 2503 的 playerGuid）、2 unionName、3 repeated wuJiangs。</p>
     * <p>⚠️ 3 的元素是 {@code CCMsgRemoteWuJiangBreifInfo}：1 index、<b>2 jieduan</b>、<b>3 level</b>、4 stars
     * —— 与 {@code wjBrief()} 用的 {@code CCMsgWuJiangBriefInfo}(2 level / 3 stage) 顺序相反，
     * 不能复用。{@code wjs} 每项 = {index, jieduan, level, stars}。</p>
     */
    public byte[] remotePlayerBrief(WorldStore.JjcSlot slot, String unionName, List<int[]> wjs) {
        return Pb.write(out -> {
            if (slot != null) {
                Pb.bytesAlways(out, 1, Pb.write(item -> {
                    Pb.int32Always(item, 2, slot.targetGuid);
                    Pb.stringAlways(item, 3, slot.name == null ? "" : slot.name);
                    Pb.int32Always(item, 4, slot.heroIndex);
                    Pb.int32Always(item, 5, slot.level);
                    Pb.int32Always(item, 6, Math.max(1, slot.fightPower));
                }));
            }
            Pb.stringAlways(out, 2, unionName == null ? "" : unionName);
            if (wjs != null) {
                for (int[] w : wjs) {
                    if (w == null || w.length < 4) {
                        continue;
                    }
                    Pb.bytes(out, 3, Pb.write(item -> {
                        Pb.int32Always(item, 1, w[0]);
                        Pb.int32Always(item, 2, w[1]);
                        Pb.int32Always(item, 3, w[2]);
                        Pb.int32Always(item, 4, w[3]);
                    }));
                }
            }
        });
    }

    /**
     * S2C 2903 {@code CCMsgRemotePlayerWuJiangDetail}：1 playerGuid、2 detailInfo(CMsgWuJiang)。
     * <p>⚠️ {@code detailInfo} 客户端不初始化且无条件解引用（{@code TargetWJDetailInfo.cs:480/:536}）
     * ⇒ 必须写 2 且至少含 index。机器人分支只用 playerGuid + index，其余客户端本地造
     * （{@code WuJiangInfo.cs:626-629 GetPropertyCfg(index)}）。</p>
     */
    public byte[] remotePlayerWjDetail(int playerGuid, int wjIndex, int level, int jieduan, int stars,
                                       int fightPower) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, playerGuid);
            Pb.bytesAlways(out, 2, Pb.write(d -> {
                Pb.int32Always(d, 2, wjIndex);
                Pb.int32Always(d, 3, level);
                Pb.int32Always(d, 4, jieduan);
                Pb.int32Always(d, 6, stars);
                Pb.int32Always(d, 15, fightPower);
            }));
        });
    }

    // ---------------------------------------------------------------- 大厅转盘（5101-5106）

    /** 转盘抽到的一格（对齐 {@code CCMsgZhuanPanDrawAward}）。 */
    public static class ZhuanPanDraw {
        public String oriName = "";
        public int count;
        public int star;
        public int zuanShi;
        public int jinBi;
        /** 1..8，客户端按它决定大转盘停位。 */
        public int pos;
        public boolean isBig;
    }

    /** 转盘榜单/我的信息一行（对齐 {@code CCMsgZhuanPanRankListItem}）。 */
    public static class ZhuanPanRankRow {
        public int guid;
        public String name = "";
        public String unionName = "";
        public int resId;
        public int level;
        public int serverId = 1;
        public int drawTime;
        public int rank;
    }

    /** 跑马灯一条（对齐 {@code CCMsgZhuanPanBigAward}）。 */
    public static class ZhuanPanBigAward {
        public ZhuanPanRankRow player;
        public long zuanshi;
        public float backRate = 1f;
    }

    /**
     * S2C 5101 {@code CCMsgRequestZhuanPanBaseInfo_Ret}。C2S 4601（面板首次打开）与 4605（已有缓存）
     * 共用这一个回包 —— 客户端 {@code LunPanChouJiangUI.cs:86-93} 只是按缓存有无换号。
     * <p>不回 5101 会导致 {@code :406→408} 对 null 取 {@code zuanShiOneCost} 直接 NRE。</p>
     */
    public byte[] zhuanPanBaseInfo(ZhuanPanCfg cfg) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, cfg.zuanShiOneCost());
            Pb.int32Always(out, 2, cfg.zuanShiTenCost());
            Pb.stringAlways(out, 3, cfg.tokenItem());
            Pb.int32Always(out, 4, cfg.tokenOneCost());
            Pb.int32Always(out, 5, cfg.tokenTenCost());
            Pb.float32Always(out, 6, cfg.turnModifier());
            Pb.stringAlways(out, 7, cfg.endTime());
            for (ZhuanPanCfg.TopAward t : cfg.topAwards()) {
                if (t == null) {
                    continue;
                }
                Pb.bytesAlways(out, 8, Pb.write(a -> {
                    Pb.int32Always(a, 1, t.jinBi);
                    Pb.int32Always(a, 2, t.zuanShi);
                    if (t.goodsItems != null) {
                        for (ZhuanPanCfg.TopAward.Goods g : t.goodsItems) {
                            if (g == null) {
                                continue;
                            }
                            Pb.bytesAlways(a, 3, Pb.write(gi -> {
                                Pb.stringAlways(gi, 1, g.oriName == null ? "" : g.oriName);
                                Pb.int32Always(gi, 2, g.count);
                                Pb.int32Always(gi, 3, g.star);
                            }));
                        }
                    }
                    Pb.int32Always(a, 4, t.timeReq);
                }));
            }
            for (ZhuanPanCfg.Slot s : cfg.slots()) {
                if (s == null) {
                    continue;
                }
                Pb.bytesAlways(out, 9, Pb.write(si -> {
                    Pb.stringAlways(si, 1, s.oriName == null ? "" : s.oriName);
                    Pb.int32Always(si, 2, s.count);
                    Pb.int32Always(si, 3, s.star);
                    Pb.int32Always(si, 4, s.zuanShi);
                    Pb.int32Always(si, 5, s.jinBi);
                    Pb.int32Always(si, 6, s.pos);
                }));
            }
            for (ZhuanPanCfg.Special sp : cfg.specials()) {
                if (sp == null) {
                    continue;
                }
                Pb.bytesAlways(out, 10, Pb.write(spi -> {
                    Pb.float32Always(spi, 1, sp.backRate <= 0f ? 1f : sp.backRate);
                    Pb.int32Always(spi, 2, sp.pos);
                }));
            }
        });
    }

    /**
     * S2C 5102 {@code CCMsgUpdateZhuanPanStatus}（1 isOpen、2 curAwardPool）。
     * <p><b>必须发</b>：大厅入口显隐由 {@code DaTingMainUISystem.cs:439} 读 {@code ZhuanPanStatus.isOpen}
     * 决定，{@code :443} 在 null 时强制隐藏入口。</p>
     */
    public byte[] zhuanPanStatus(boolean open, long curAwardPool) {
        return Pb.write(out -> {
            Pb.boolAlways(out, 1, open);
            Pb.int32Always(out, 2, (int) Math.max(0L, Math.min(Integer.MAX_VALUE, curAwardPool)));
        });
    }

    /**
     * S2C 5103/5104 共用 {@code CCMsgRequestZhuanPanDraw_Ret}：1 repeated awards、2 myInfo。
     * <p>⚠️ {@code awards} 不能为空：客户端 {@code LunPanChouJiangUI.cs:461} 取
     * {@code A_0[A_0.Count-1].pos} 定停位，空列表直接崩。</p>
     */
    public byte[] zhuanPanDrawRet(List<ZhuanPanDraw> awards, ZhuanPanRankRow myInfo) {
        return Pb.write(out -> {
            if (awards != null) {
                for (ZhuanPanDraw a : awards) {
                    if (a == null) {
                        continue;
                    }
                    Pb.bytesAlways(out, 1, Pb.write(item -> {
                        Pb.stringAlways(item, 1, a.oriName == null ? "" : a.oriName);
                        Pb.int32Always(item, 2, a.count);
                        Pb.int32Always(item, 3, a.star);
                        Pb.int32Always(item, 4, a.zuanShi);
                        Pb.int32Always(item, 5, a.jinBi);
                        Pb.int32Always(item, 6, a.pos);
                        Pb.boolAlways(item, 7, a.isBig);
                    }));
                }
            }
            if (myInfo != null) {
                Pb.bytesAlways(out, 2, zhuanPanRankRow(myInfo));
            }
        });
    }

    /**
     * S2C 5105 {@code CCMsgRequestZhuanPanRankListRet}：1 rankList、<b>2 myInfo 必须非 null</b>
     * （{@code LunPanRankList.cs:202} 无条件解引用 {@code info.myInfo.drawTime}）。
     */
    public byte[] zhuanPanRankRet(List<ZhuanPanRankRow> rows, ZhuanPanRankRow myInfo) {
        return Pb.write(out -> {
            if (rows != null) {
                for (ZhuanPanRankRow r : rows) {
                    if (r == null) {
                        continue;
                    }
                    Pb.bytesAlways(out, 1, zhuanPanRankRow(r));
                }
            }
            if (myInfo != null) {
                Pb.bytesAlways(out, 2, zhuanPanRankRow(myInfo));
            }
        });
    }

    /** S2C 5106 {@code CCMsgZhuanPanBigAwardList}：1 repeated list（客户端是 AddRange，>10 截断）。 */
    public byte[] zhuanPanBigAwardAdd(List<ZhuanPanBigAward> list) {
        return Pb.write(out -> {
            if (list == null) {
                return;
            }
            for (ZhuanPanBigAward b : list) {
                if (b == null) {
                    continue;
                }
                Pb.bytesAlways(out, 1, Pb.write(item -> {
                    if (b.player != null) {
                        Pb.bytesAlways(item, 1, zhuanPanRankRow(b.player));
                    }
                    Pb.int32Always(item, 2, (int) Math.max(0L, Math.min(Integer.MAX_VALUE, b.zuanshi)));
                    Pb.float32Always(item, 3, b.backRate <= 0f ? 1f : b.backRate);
                }));
            }
        });
    }

    private byte[] zhuanPanRankRow(ZhuanPanRankRow r) {
        return Pb.write(o -> {
            Pb.int32Always(o, 1, r.guid);
            Pb.stringAlways(o, 2, r.name == null ? "" : r.name);
            Pb.stringAlways(o, 3, r.unionName == null ? "" : r.unionName);
            Pb.int32Always(o, 4, r.resId);
            Pb.int32Always(o, 5, r.level);
            Pb.int32Always(o, 6, r.serverId > 0 ? r.serverId : 1);
            Pb.int32Always(o, 7, r.drawTime);
            Pb.int32Always(o, 8, r.rank);
        });
    }

    // ==================== 开服狂欢（7 日狂欢 / 半月庆典）====================
    // 协议依据（逐字段读自 pyfoot\tmp_msgdll\NetProto\）：
    //   S2C 6201 CCMsgHappySomeDayActivity        : 唯一字段 tag1 repeated CCMsgKFHappyOneDayActivity
    //   S2C 6202 CCMsgKFHappyHalfMonthActivity_ret: 直接就是一个 CCMsgKFHappyOneDayActivity
    //   S2C 6203 CCMsgKFHappyGetOneAwardRet       : 1 day / 2 type / 3 ID / 4 GetState
    // type 号 ≠ tag 号（客户端 ActivityPropertyMgr.cs:1409-1500 的 switch 按 type 找列表），
    // 映射写死在 kfHappyOneDay 里，依据是 ActivityPropertyMgr.cs:1127-1158 的赋值。

    /** 6201 的载荷：只有 tag1 一个 repeated，元素是 day 包。 */
    public byte[] kfHappySomeDayRet(List<byte[]> days) {
        return Pb.write(out -> {
            if (days != null) {
                for (byte[] d : days) {
                    Pb.bytes(out, 1, d);
                }
            }
        });
    }

    /**
     * 一个 {@code CCMsgKFHappyOneDayActivity}（6202 的整个包体 / 6201 的一个元素）。
     * <p>tag 布局：1 day(uint) / 2 ActivityEndTime / 3 LingQuEndTime / 4 YeQian /
     * 5 MeiRiFuli / 6 DengjiTuPo / 7 HalfDiscount / 8 LevelChallenge / 9 PlayChallenge /
     * 10 GoodsSale / 11 StarTuPo / 12 FightPowerTuPo。
     * <p><b>两个时间字段必须写合法 {@code yyyy-MM-dd HH:mm:ss}</b>：类里两个属性都有 {@code = ""}
     * 初始化器 + {@code [DefaultValue("")]}，客户端因此 {@code == null} 判假、空串直接送进
     * {@code ParseExact} 抛 {@code FormatException}（{@code KFHappyMainUI.cs:2051-2056/:2064-2069}）。
     */
    public byte[] kfHappyOneDay(KfHappyDay d) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, d.day);
            Pb.stringAlways(out, 2, d.activityEndTime == null ? "" : d.activityEndTime);
            Pb.stringAlways(out, 3, d.lingQuEndTime == null ? "" : d.lingQuEndTime);
            for (KfHappyYeQian y : d.yeQian) {
                Pb.bytes(out, 4, Pb.write(o -> {
                    Pb.int32Always(o, 1, y.id);
                    Pb.stringAlways(o, 2, y.name == null ? "" : y.name);
                }));
            }
            for (KfHappyEntry e : d.entries) {
                int tag = KfHappyCfg.tagOf(e.type);
                if (tag == 0) {
                    continue;
                }
                Pb.bytes(out, tag, kfHappyEntry(e));
            }
        });
    }

    /**
     * 一个条目（各 type 的内部 tag 布局互不相同，必须逐类型写）。
     * <p>type1 MeiRiFuLi / type3 DengJiTuPo / type4 StarTuPo / type5 PlayChallenge /
     * type7 FightPowerTuPo：{@code 1 ID / 2 jinbi / 3 zuanshi / 4 text / 5 goodsAward /
     * 6 state / 7 fenzi / 8 fenmu}。
     * <p>type2 LevelChallenge：同上去掉 7/8。
     * <p>type6 HalfDiscount：{@code 1 ID / 2 jinbi / 3 zuanshi / 4 yuanjia / 5 xianjia /
     * 6 text / 7 goodsAward / 8 state}。
     * <p>type8 GoodsSale：{@code 1 ID / 2 jinbi / 3 zuanshi / 4 yuanjia / 5 xianjia /
     * 6 text / 7 goodsAward / 8 state / 9 fenzi / 10 fenmu}。
     */
    private byte[] kfHappyEntry(KfHappyEntry e) {
        return Pb.write(out -> {
            if (KfHappyCfg.hasPrice(e.type)) {
                Pb.int32Always(out, 1, e.id);
                Pb.int32Always(out, 2, e.jinbi);
                Pb.int32Always(out, 3, e.zuanshi);
                Pb.int32Always(out, 4, e.yuanjia);
                Pb.int32Always(out, 5, e.xianjia);
                Pb.stringAlways(out, 6, e.text == null ? "" : e.text);
                for (KfHappyAward a : e.goods) {
                    Pb.bytes(out, 7, kfHappyAward(a));
                }
                Pb.int32Always(out, 8, e.state);
                if (e.type == 8) {
                    Pb.int32Always(out, 9, e.fenzi);
                    Pb.int32Always(out, 10, e.fenmu);
                }
                return;
            }
            Pb.int32Always(out, 1, e.id);
            Pb.int32Always(out, 2, e.jinbi);
            Pb.int32Always(out, 3, e.zuanshi);
            Pb.stringAlways(out, 4, e.text == null ? "" : e.text);
            for (KfHappyAward a : e.goods) {
                Pb.bytes(out, 5, kfHappyAward(a));
            }
            Pb.int32Always(out, 6, e.state);
            if (KfHappyCfg.hasFen(e.type)) {
                Pb.int32Always(out, 7, e.fenzi);
                Pb.int32Always(out, 8, e.fenmu);
            }
        });
    }

    /**
     * {@code CCMsgTimeActivityAwards}：1 oriName / 2 count / <b>3 stars（int，不是 bool）</b>。
     * <p>⚠️ 不要复用 {@code shiningAward}（:3043 附近）—— 它的 tag3 是 bool，属于另一个类
     * {@code CCMsgShiningAwardItem}，两者只是 tag 号碰巧相同。
     */
    private byte[] kfHappyAward(KfHappyAward a) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, a.ori == null ? "" : a.ori);
            Pb.int32Always(out, 2, Math.max(1, a.count));
            Pb.int32Always(out, 3, a.stars);
        });
    }

    /** 6203 {@code CCMsgKFHappyGetOneAwardRet}：1 day / 2 type / 3 ID / 4 GetState。 */
    public byte[] kfHappyGetAwardRet(int day, int type, int id, int getState) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, day);
            Pb.int32Always(out, 2, type);
            Pb.int32Always(out, 3, id);
            Pb.int32Always(out, 4, getState);
        });
    }

    /** 6201/6202 的一个 day 包。 */
    public static final class KfHappyDay {
        public int day;
        public String activityEndTime = "";
        public String lingQuEndTime = "";
        public final List<KfHappyYeQian> yeQian = new ArrayList<>();
        public final List<KfHappyEntry> entries = new ArrayList<>();
    }

    public static final class KfHappyYeQian {
        public int id;
        public String name = "";
    }

    public static final class KfHappyEntry {
        /** 语义 type 1..8（不是协议 tag 号，tag 由 {@code KfHappyCfg.tagOf} 映射）。 */
        public int type;
        public int id = 1;
        public int jinbi;
        public int zuanshi;
        public int yuanjia;
        public int xianjia;
        public String text = "";
        /** 1=条件未达成 / 2=可领取 / 3=已领取。不要用 0。 */
        public int state = 1;
        public int fenzi;
        public int fenmu;
        public final List<KfHappyAward> goods = new ArrayList<>();
    }

    public static final class KfHappyAward {
        public String ori = "";
        public int count = 1;
        public int stars;
    }

    /** S2C 1917 CCMsgRequestOneUnionBuildingAllEmployers_Ret。 */
    public byte[] buildingAllEmployers(int type, List<WorldStore.Employer> list) {
        return Pb.write(out -> {
            Pb.int32(out, 1, type);
            if (list != null) {
                for (WorldStore.Employer e : list) {
                    Pb.bytes(out, 2, Pb.write(o -> {
                        Pb.int32Always(o, 1, e.playerId);
                        Pb.stringAlways(o, 2, e.name == null ? "" : e.name);
                        Pb.bytes(o, 3, wjBrief(e.wjIndex, e.wjLevel, e.wjStage, e.wjStars));
                        Pb.int32Always(o, 4, e.fightPower);
                        Pb.int32Always(o, 5, cdLeftSec(e.cdEnd));
                        Pb.int32Always(o, 6, e.price);
                    }));
                }
            }
        });
    }

    /**
     * S2C 1933 CCMsgRequestEmployDetailInfo_Ret：雇佣武将详细信息。
     *
     * <p>字段 = 1 buildingType / 2 wjDetail(CCMsgRemotePlayerWuJiangDetail) / 3 playerName / 4 price；
     * wjDetail 的 1 playerGuid + 2 detailInfo(CMsgWuJiang)。客户端 EmployerSystem.cs:277-301 用
     * playerName + detailInfo 的 index/stars/jieduan/fightpower/addfightpower/level，
     * MatchPlayer.cs:595-631 → WuJiangInfo.cs:285-315 同样只读 detailInfo（equipments/BuddiesIndex/
     * JiBanSlotInfo 缺失是安全的：BuddiesProperty.cs:18-30 对 null 直接返回 null）。</p>
     */
    public byte[] employDetail(int buildingType, int playerGuid, int wjIndex, String playerName,
                               int level, int jieduan, int stars, int fightPower, int price) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, buildingType);
            Pb.bytes(out, 2, Pb.write(o -> {
                Pb.int32Always(o, 1, playerGuid);
                Pb.bytes(o, 2, Pb.write(w -> {
                    Pb.int32Always(w, 2, wjIndex);
                    Pb.int32Always(w, 3, level);
                    Pb.int32Always(w, 4, jieduan);
                    Pb.int32Always(w, 6, stars);
                    Pb.int32Always(w, 15, fightPower);
                }));
            }));
            Pb.stringAlways(out, 3, playerName == null ? "" : playerName);
            Pb.int32Always(out, 4, price);
        });
    }

    private byte[] wjBrief(int index, int level, int stage, int stars) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, index);
            Pb.int32Always(out, 2, level);
            Pb.int32Always(out, 3, stage);
            Pb.int32Always(out, 4, stars);
        });
    }

    private static int cdLeftSec(long cdEnd) {
        long left = cdEnd - System.currentTimeMillis();
        return left <= 0L ? 0 : (int) (left / 1000L);
    }

    /**
     * JJCChallengeTargetBriefInfo（2001.6 / 2002 repeated）。
     * 机器人：客户端只用 Guid+FightPower+RankIndex，名/级/头/胜场走本地表。
     * 真人：7 个字段全用包内 → 必须 Always，避免 0/空省略。
     * ResID = 武将表 uId（GetSmallHeadImg）；玩家槽用 mainHeroIndex。
     */
    public byte[] jjcTarget(WorldStore.JjcSlot slot) {
        String name = (slot.name == null || slot.name.isEmpty())
                ? ("#" + slot.targetGuid) : slot.name;
        return Pb.write(out -> {
            // RankIndex==0 时 ArenaMainDialog.Refresh 直接 continue 不画该槽
            Pb.int32Always(out, 1, slot.targetGuid);
            Pb.stringAlways(out, 2, name);
            Pb.int32Always(out, 3, Math.max(1, slot.fightPower));
            Pb.int32Always(out, 4, slot.rank);
            Pb.int32Always(out, 5, slot.heroIndex);
            Pb.int32Always(out, 6, Math.max(0, slot.level));
            Pb.int32Always(out, 7, Math.max(0, slot.wins));
        });
    }

    public byte[] jjcTargetsWrap(List<WorldStore.JjcSlot> targets) {
        return Pb.write(out -> {
            for (WorldStore.JjcSlot s : targets) {
                Pb.bytes(out, 1, jjcTarget(s));
            }
        });
    }

    public byte[] jjcInfo(PlayerRecord rec, int fightPower, List<WorldStore.JjcSlot> targets) {
        byte[] wrap = jjcTargetsWrap(targets);
        return Pb.write(out -> {
            Pb.int32Always(out, 1, Math.max(1000, fightPower));
            Pb.int32Always(out, 2, rec.arena.rank);
            Pb.int32Always(out, 3, rec.arena.challengesLeft);
            Pb.int32Always(out, 4, rec.arena.payResetCount);
            // 必须发合法 yyyy-MM-dd HH:mm:ss；空/省略都会变成 ""，ParseExact 炸
            Pb.stringAlways(out, 5, jjcLastChallengeWire(rec.arena.lastChallengeAt));
            // 空目标也要写出嵌套，否则 TargetBriefInfos=null → UpdateData NRE
            Pb.bytesAlways(out, 6, wrap);
        });
    }

    public byte[] jjcRankBrief(WorldStore.JjcSlot slot) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, slot.targetGuid);
            Pb.int32Always(out, 2, slot.heroIndex);
            Pb.int32Always(out, 3, slot.level);
            Pb.stringAlways(out, 4, slot.name == null ? "" : slot.name);
            Pb.int32Always(out, 5, slot.wins);
            Pb.int32Always(out, 6, slot.rank);
        });
    }

    public byte[] jjcRankList(List<WorldStore.JjcSlot> slots) {
        return Pb.write(out -> {
            for (WorldStore.JjcSlot s : slots) {
                Pb.bytesAlways(out, 1, jjcRankBrief(s));
            }
        });
    }

    public byte[] jjcTargetGuidOnly(int targetGuid) {
        return Pb.write(out -> Pb.int32Always(out, 1, targetGuid));
    }

    public byte[] jjcWjJob(PlayerRecord.Hero wj, int job) {
        return Pb.write(out -> {
            Pb.int32(out, 1, job);
            Pb.int32(out, 2, wj.heroIndex);
            Pb.int32(out, 3, wj.level);
            Pb.int32(out, 4, wj.stage);
            Pb.int32(out, 5, wj.stars);
            Pb.int32(out, 10, wj.skill1);
            Pb.int32(out, 11, wj.skill2);
            Pb.int32(out, 12, wj.skill3);
            Pb.int32(out, 13, wj.skill4);
        });
    }

    /**
     * CCMsgJJCRealFightTargetDetailInfo：机器人开战只需 TargetGuid（field1）。
     * guid≤1000000 时客户端用本地 JJC_Robot 填阵；乱填 WJ(field3) 会按 CCMsgWuJiangAllInfoAndJob 解失败。
     */
    public byte[] jjcFightTargetDetail(int targetGuid) {
        return Pb.write(out -> Pb.int32Always(out, 1, targetGuid));
    }

    /**
     * 己方开战 myTeam：必须带 BuddiesIndex + JiBanSlot + FightPowerReturn(长度3)。
     * UnPackDataForMyTeam 会整表覆盖登录羁绊；空发=开战清羁绊/返还垫 0。
     * 空孔也要用 bytesAlways，否则 repeated 孔数被压短。
     */
    public byte[] jjcFightTargetDetailSelf(PlayerRecord rec) {
        rec.jiban.ensure();
        List<Integer> buddies = paddedBuddies(rec);
        int[] fpRet = computeFightPowerReturn(rec);
        return Pb.write(out -> {
            Pb.int32Always(out, 1, rec.playerId);
            for (Integer idx : buddies) {
                Pb.int32Always(out, 4, idx == null ? 0 : idx.intValue());
            }
            for (PlayerRecord.JiBanSlot slot : rec.jiban.slots) {
                Pb.bytesAlways(out, 5, jiBanSlot(slot));
            }
            Pb.int32Always(out, 6, fpRet[0]);
            Pb.int32Always(out, 6, fpRet[1]);
            Pb.int32Always(out, 6, fpRet[2]);
        });
    }

    /** @deprecated 结构不对，开战请用 {@link #jjcFightTargetDetail(int)} / {@link #jjcFightTargetDetailSelf} */
    public byte[] jjcTeam(int targetGuid, List<byte[]> wjs) {
        return jjcFightTargetDetail(targetGuid);
    }

    /**
     * 对齐客户端 RecaculatePowerReturn：羁绊位基础战力总和 / 59 → 档数 × 60/61/62。
     * Proto 为 List&lt;Int32&gt; 长度 3，无按武将映射。
     */
    public int[] computeFightPowerReturn(PlayerRecord rec) {
        return com.sao.fakeserver.fight.CombatAttrCalculator.fightPowerReturn(cultivate, rec);
    }

    public static List<Integer> paddedBuddies(PlayerRecord rec) {
        List<Integer> out = new ArrayList<>(BUDDIES_SLOTS);
        if (rec != null && rec.jiban != null && rec.jiban.buddies != null) {
            for (Integer v : rec.jiban.buddies) {
                out.add(v == null ? Integer.valueOf(0) : v);
            }
        }
        while (out.size() < BUDDIES_SLOTS) {
            out.add(Integer.valueOf(0));
        }
        if (out.size() > BUDDIES_SLOTS) {
            return new ArrayList<>(out.subList(0, BUDDIES_SLOTS));
        }
        return out;
    }

    private static PlayerRecord.Hero findHeroByIndex(PlayerRecord rec, int heroIndex) {
        if (rec == null || rec.heroes == null) {
            return null;
        }
        for (PlayerRecord.Hero h : rec.heroes) {
            if (h != null && h.heroIndex == heroIndex) {
                return h;
            }
        }
        return null;
    }

    public byte[] jjcFightTeams(byte[] myTeam, byte[] otherTeam) {
        return Pb.write(out -> {
            Pb.bytesAlways(out, 1, myTeam);
            Pb.bytesAlways(out, 2, otherTeam);
        });
    }

    /**
     * 争霸开战灌 matchPlayer：复用 S2C 2004 解包路径。
     * TargetGuid = robotGuid+1e6（&gt;1e6 走 WJ 列表，只灌本轮 1 主将）；≤1e6 会整队读 JJC_Robot。
     */
    /** 争霸对真人/NPC：TargetGuid=playerId；本轮 1 守将来自 zbz.defenseWuJiangIds。 */
    public byte[] zbzFightOtherTeamFromPlayer(PlayerRecord foe, int round1Based, int fightGuid) {
        if (foe == null) {
            return zbzFightOtherTeam(null, 18, fightGuid);
        }
        foe.ensureCollections();
        PlayerRecord.Hero hero = zbzDefenseHero(foe, round1Based);
        if (hero == null) {
            hero = new PlayerRecord.Hero();
            hero.id = "zbz-pad";
            hero.heroIndex = foe.mainHeroIndex > 0 ? foe.mainHeroIndex : 18;
            hero.level = Math.max(1, foe.level);
            hero.stars = 1;
            hero.skill1 = 1;
            hero.skill2 = 1;
            hero.skill3 = 1;
            hero.skill4 = 1;
        }
        final PlayerRecord.Hero wj = hero;
        return Pb.write(out -> {
            Pb.int32Always(out, 1, fightGuid);
            Pb.bytesAlways(out, 3, allInfoAndJob(1, wj, foe));
        });
    }

    public byte[] zbzFightOtherTeam(GameTables.RobotRow robot, int heroIndex, int fightGuid) {
        PlayerRecord.Hero stub = robotHeroStub(robot, heroIndex);
        PlayerRecord owner = robotEquipOwner(robot, stub);
        return Pb.write(out -> {
            Pb.int32Always(out, 1, fightGuid);
            Pb.bytesAlways(out, 3, allInfoAndJob(1, stub, owner));
        });
    }

    /**
     * KFZ 开战灌 matchPlayer：TargetGuid=fightGuid&gt;1e6；5 武将 job=1..5（CCMsgWuJiangAllInfoAndJob）。
     */
    public byte[] kfzFightOtherTeam(GameTables.RobotRow robot, List<Integer> heroes5, int fightGuid) {
        List<Integer> heroes = padHeroIndices(heroes5, robot != null && robot.resId > 0 ? robot.resId : 18, 5);
        return Pb.write(out -> {
            Pb.int32Always(out, 1, fightGuid);
            for (int job = 1; job <= 5; job++) {
                PlayerRecord.Hero stub = robotHeroStub(robot, heroes.get(job - 1).intValue());
                PlayerRecord owner = robotEquipOwner(robot, stub);
                Pb.bytesAlways(out, 3, allInfoAndJob(job, stub, owner));
            }
        });
    }

    /** KFZ 对真人/NPC：用对方实际武将 + 存档装/石。 */
    public byte[] kfzFightOtherTeamFromPlayer(PlayerRecord foe, List<Integer> heroes5, int fightGuid) {
        int fallback = foe != null && foe.mainHeroIndex > 0 ? foe.mainHeroIndex : 18;
        List<Integer> heroes = padHeroIndices(heroes5, fallback, 5);
        return Pb.write(out -> {
            Pb.int32Always(out, 1, fightGuid);
            for (int job = 1; job <= 5; job++) {
                final int idx = heroes.get(job - 1).intValue();
                PlayerRecord.Hero hero = foe != null ? foe.findHeroByIndex(idx) : null;
                if (hero == null) {
                    hero = new PlayerRecord.Hero();
                    hero.id = "kfz-pad-" + job;
                    hero.heroIndex = idx;
                    hero.level = foe != null ? Math.max(1, foe.level) : 1;
                    hero.stars = 1;
                    hero.skill1 = 1;
                    hero.skill2 = 1;
                    hero.skill3 = 1;
                    hero.skill4 = 1;
                }
                Pb.bytesAlways(out, 3, allInfoAndJob(job, hero, foe));
            }
        });
    }

    /** {@code CCMsgWuJiangAllInfoAndJob}：1–13 养成 + 14 时光石 / 15 Brief / 16 器魂 / 17 套装。 */
    private byte[] allInfoAndJob(int job, PlayerRecord.Hero wj, PlayerRecord owner) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, job);
            Pb.int32Always(out, 2, wj.heroIndex);
            Pb.int32Always(out, 3, Math.max(1, wj.level));
            Pb.int32Always(out, 4, Math.max(0, wj.stage));
            Pb.int32Always(out, 5, Math.max(1, wj.stars));
            Pb.int32Always(out, 6, Math.max(0, wj.stagePara1));
            Pb.int32Always(out, 7, Math.max(0, wj.stagePara2));
            Pb.int32Always(out, 8, Math.max(0, wj.stagePara3));
            Pb.int32Always(out, 9, Math.max(0, wj.stagePara4));
            Pb.int32Always(out, 10, Math.max(1, wj.skill1));
            Pb.int32Always(out, 11, Math.max(1, wj.skill2));
            Pb.int32Always(out, 12, Math.max(1, wj.skill3));
            Pb.int32Always(out, 13, Math.max(1, wj.skill4));
            writeWjGear(out, owner, wj, 14, 15, 16, 17);
        });
    }

    /**
     * 时光石 / Brief 装 / 器魂 / 套装。field 号因消息而异：
     * AllInfoAndJob=14..17；AllInfoAndJobAndHpAndEnergy=16..19。
     */
    private void writeWjGear(com.google.protobuf.CodedOutputStream out, PlayerRecord owner, PlayerRecord.Hero wj,
                             int timeF, int eqF, int soulF, int suitF) throws java.io.IOException {
        if (wj == null) {
            return;
        }
        wj.ensureTimeStones();
        for (int i = 0; i < 7; i++) {
            Pb.bytesAlways(out, timeF, timeStoneSlot(wj.timeStone(i), wj.timeStoneFx(i)));
        }
        if (owner != null && owner.equipments != null && wj.id != null) {
            for (PlayerRecord.Equipment eq : owner.equipments) {
                if (eq == null || eq.owner == null || !eq.owner.equals(wj.id)) {
                    continue;
                }
                Pb.bytesAlways(out, eqF, equipBrief(eq));
            }
        }
        if (owner != null && owner.souls != null) {
            PlayerRecord.Soul soul = owner.souls.get(Integer.valueOf(wj.heroIndex));
            if (soul != null && soul.composed) {
                Pb.bytes(out, soulF, soulInner(soul));
            }
        }
        if (owner != null && wj.id != null) {
            for (Integer sid : cultivate.suitEffectIds(cultivate.equippedOriOf(owner, wj.id))) {
                if (sid != null && sid.intValue() > 0) {
                    Pb.int32Always(out, suitF, sid.intValue());
                }
            }
        }
    }

    private PlayerRecord.Hero zbzDefenseHero(PlayerRecord foe, int round1Based) {
        int idx = 0;
        for (String id : foe.zbz.defenseWuJiangIds) {
            if (id == null || id.isEmpty() || foe.findHero(id) == null) {
                continue;
            }
            idx++;
            if (idx == Math.max(1, round1Based)) {
                return foe.findHero(id);
            }
        }
        for (String id : foe.formationSlots(PlayerRecord.FORMATION_ZBZ)) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            PlayerRecord.Hero h = foe.findHero(id);
            if (h != null) {
                return h;
            }
        }
        return foe.findHeroByIndex(foe.mainHeroIndex);
    }

    private PlayerRecord.Hero robotHeroStub(GameTables.RobotRow robot, int heroIndex) {
        int idx = heroIndex > 0 ? heroIndex : (robot != null && robot.resId > 0 ? robot.resId : 18);
        PlayerRecord.Hero h = new PlayerRecord.Hero();
        h.id = "robot-wj-" + idx;
        h.heroIndex = idx;
        h.level = robot != null && robot.level > 0 ? robot.level : 1;
        h.stars = robot != null ? Math.max(1, robot.stars) : 1;
        h.stage = robot != null ? Math.max(0, robot.stage) : 0;
        int shu = robot != null ? Math.max(0, robot.shuLianDu) : 0;
        h.stagePara1 = shu;
        h.stagePara2 = shu;
        h.stagePara3 = shu;
        h.stagePara4 = shu;
        h.skill1 = robot != null ? Math.max(1, robot.skillMingJiang) : 1;
        h.skill2 = robot != null ? Math.max(1, robot.skillA) : 1;
        h.skill3 = robot != null ? Math.max(1, robot.skillB) : 1;
        h.skill4 = robot != null ? Math.max(1, robot.skillPassive) : 1;
        h.ensureTimeStones();
        return h;
    }

    /** 机器人装备快照（与 FightRosterBuilder 同源），供开战包 Brief 写出。 */
    private PlayerRecord robotEquipOwner(GameTables.RobotRow robot, PlayerRecord.Hero stub) {
        PlayerRecord fake = new PlayerRecord();
        fake.equipments = new ArrayList<>();
        if (robot == null || stub == null || robot.equipLibId <= 0) {
            return fake;
        }
        GameTables.RobotEquipLib lib = tables.robotEquipLib(robot.equipLibId);
        FightRosterBuilder.attachRobotEquips(fake, stub, lib, cultivate);
        return fake;
    }

    private static List<Integer> padHeroIndices(List<Integer> heroes5, int fallback, int n) {
        List<Integer> heroes = new ArrayList<>();
        if (heroes5 != null) {
            for (Integer h : heroes5) {
                if (h != null && h.intValue() > 0) {
                    heroes.add(h);
                }
                if (heroes.size() >= n) {
                    break;
                }
            }
        }
        while (heroes.size() < n) {
            heroes.add(Integer.valueOf(fallback > 0 ? fallback : 18));
        }
        return heroes;
    }

    public byte[] jjcTimes(int left, int payReset) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, left);
            Pb.int32Always(out, 2, payReset);
        });
    }

    public byte[] jjcLastChallenge(String time) {
        return Pb.write(out -> Pb.stringAlways(out, 1, jjcLastChallengeWire(time)));
    }

    /** S2C 2010 CCMsgJJCRankDetailInfoThanBriefInfo：机器人客户端读 RobotWJFightPower[0..4] 无越界检查。 */
    public byte[] jjcRankDetail(int targetGuid, int fightPower, int level, int rank, int wins) {
        int per = Math.max(1, fightPower / 5);
        return Pb.write(out -> {
            Pb.int32Always(out, 1, fightPower);
            // UnionName 空 → 客户端显示「无公会」文案；省略同空串
            Pb.stringAlways(out, 2, "");
            for (int i = 0; i < 5; i++) {
                Pb.int32Always(out, 3, per);
            }
            Pb.int32Always(out, 4, targetGuid);
            Pb.int32Always(out, 7, level);
            Pb.int32Always(out, 8, rank);
            Pb.int32Always(out, 9, wins);
        });
    }

    /** S2C 2011 CCMsgRequestJJCFightRecord_Ret */
    public byte[] jjcFightRecordList(List<PlayerRecord.JjcFightRecord> records) {
        return Pb.write(out -> {
            if (records == null) {
                return;
            }
            for (PlayerRecord.JjcFightRecord r : records) {
                Pb.bytesAlways(out, 1, jjcFightRecordBrief(r));
            }
        });
    }

    public byte[] jjcFightRecordBrief(PlayerRecord.JjcFightRecord r) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, r.resId);
            Pb.stringAlways(out, 2, r.name == null ? "" : r.name);
            Pb.int32Always(out, 3, r.level);
            Pb.boolAlways(out, 4, r.win);
            Pb.int32Always(out, 5, r.rankChange);
            Pb.stringAlways(out, 6, r.fightTime == null || r.fightTime.isEmpty()
                    ? JJC_NEVER_CHALLENGED_AT : r.fightTime);
        });
    }

    /**
     * S2C 2012：机器人 TargetGuid → 客户端补对方 WJ；己方 WJ brief + Hurt 供伤害条。
     * Hurt 必须 Always（含 0），否则 repeated 长度被压短。
     */
    public byte[] jjcFightRecordDetail(PlayerRecord rec, int targetGuid, PlayerRecord.JjcFightRecord row) {
        List<Integer> selfHurt = row != null && row.selfHurt != null ? row.selfHurt : java.util.Collections.<Integer>emptyList();
        List<Integer> targetHurt = row != null && row.targetHurt != null ? row.targetHurt : java.util.Collections.<Integer>emptyList();
        return Pb.write(out -> {
            List<String> slots = rec != null ? rec.formationSlots(PlayerRecord.FORMATION_JJC_ATK) : java.util.Collections.<String>emptyList();
            for (int i = 0; i < slots.size() && i < 5; i++) {
                String guid = slots.get(i);
                if (guid == null || guid.isEmpty()) {
                    continue;
                }
                PlayerRecord.Hero h = rec.findHero(guid);
                if (h == null) {
                    continue;
                }
                Pb.bytesAlways(out, 1, wjJobAndBrief(i + 1, h));
            }
            for (Integer v : selfHurt) {
                Pb.int32Always(out, 2, v == null ? 0 : v.intValue());
            }
            Pb.int32Always(out, 3, targetGuid);
            for (Integer v : targetHurt) {
                Pb.int32Always(out, 5, v == null ? 0 : v.intValue());
            }
        });
    }

    /** @deprecated 用 {@link #jjcFightRecordDetail(PlayerRecord, int, PlayerRecord.JjcFightRecord)} */
    public byte[] jjcFightRecordDetail(int targetGuid) {
        return Pb.write(out -> Pb.int32Always(out, 3, targetGuid));
    }

    /** CMsgWuJiangJobAndBriefInfo：1 job + 2 nested CMsgWuJiangBriefInfo */
    public byte[] wjJobAndBrief(int job, PlayerRecord.Hero h) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, job);
            Pb.bytesAlways(out, 2, wjBrief(h));
        });
    }

    /** CMsgWuJiangBriefInfo：1 index 2 level 3 jieduan 4 stars */
    public byte[] wjBrief(PlayerRecord.Hero h) {
        PlayerRecord.Hero x = h == null ? new PlayerRecord.Hero() : h;
        return Pb.write(out -> {
            Pb.int32Always(out, 1, x.heroIndex);
            Pb.int32Always(out, 2, x.level);
            Pb.int32Always(out, 3, x.stage);
            Pb.int32Always(out, 4, x.stars);
        });
    }

    /** S2C 2014 CCMsgRequestJJCQieCuoRet：0=成功进战；1=失败弹 100418。 */
    public byte[] jjcQieCuoRet(int result) {
        return Pb.write(out -> Pb.int32Always(out, 1, result));
    }

    /** S2C 2013 */
    public byte[] jjcMyRank(int rankIndex) {
        return Pb.write(out -> Pb.int32Always(out, 1, rankIndex));
    }

    public byte[] saoDangResult(List<byte[]> baseAwards) {
        return Pb.write(out -> {
            if (baseAwards != null) {
                for (byte[] one : baseAwards) {
                    Pb.bytes(out, 1, one);
                }
            }
        });
    }

    public byte[] saoDangBase(int exp, int gold, int wnsp, int yingPo, List<GameTables.GoodsDrop> goods) {
        return Pb.write(out -> {
            Pb.int32(out, 1, exp);
            Pb.int32(out, 2, gold);
            Pb.int32(out, 3, wnsp);
            Pb.int32(out, 4, yingPo);
            if (goods != null) {
                for (GameTables.GoodsDrop g : goods) {
                    Pb.bytes(out, 5, goods(g.ori, g.count));
                }
            }
        });
    }

    public byte[] unionBuildings(Map<Integer, Integer> levels, Map<Integer, Long> upgradeEnd) {
        return Pb.write(out -> {
            for (int t = 1; t <= 7; t++) {
                int lv = 1;
                if (levels != null && levels.containsKey(Integer.valueOf(t))) {
                    lv = Math.max(1, levels.get(Integer.valueOf(t)).intValue());
                }
                long end = 0L;
                if (upgradeEnd != null && upgradeEnd.containsKey(Integer.valueOf(t))) {
                    Long v = upgradeEnd.get(Integer.valueOf(t));
                    end = v == null ? 0L : v.longValue();
                }
                Pb.bytes(out, 1, unionBuilding(t, lv, end));
            }
        });
    }

    /** CMsgOneUnionBuildingLevelUpInfo：字段 3 leftLevelUpTime 驱动「升级中」进度条与停止按钮。 */
    private byte[] unionBuilding(int type, int level, long upgradeEndMs) {
        long left = upgradeEndMs - System.currentTimeMillis();
        int leftSec = left <= 0L ? 0 : (int) (left / 1000L);
        return Pb.write(out -> {
            Pb.int32(out, 1, type);
            Pb.int32(out, 2, level);
            Pb.int32Always(out, 3, leftSec);
        });
    }

    public byte[] unionPlayerRes(PlayerRecord rec) {
        // 字段 3 是「今日**已**捐次数」：客户端 UnionManagerSystem 显示 上限−已捐，门禁也按它比
        int max = economy.unionDonateMaxTimes(rec.economy.chargedDiamond);
        int used = Math.max(0, max - rec.guild.donateLeft);
        return Pb.write(out -> {
            Pb.int32(out, 1, rec.guild.brotherCoin);
            Pb.int32(out, 2, rec.guild.contribution);
            Pb.int32Always(out, 3, used);
            Pb.stringAlways(out, 4, rec.guild.lastKitchenStaminaAt);
            Pb.int32(out, 5, rec.guild.courageCoin);
        });
    }

    public byte[] unionProfit(int buildingType, int gold) {
        return Pb.write(out -> {
            Pb.int32(out, 1, buildingType);
            Pb.int32(out, 2, gold);
        });
    }

    public byte[] unionLevelUp(int type, int ret, int value) {
        return Pb.write(out -> {
            Pb.int32(out, 1, type);
            Pb.int32(out, 2, ret);
            Pb.int32(out, 3, value);
        });
    }

    /** S2C 1936 CCMsgRequestMaJiuInfoRet 的组装输入。 */
    public static class MajiuView {
        /** UnionYunBiaoPhase：1 Prepare / 2 Begin / 3 End。 */
        public int phase;
        public int leftSec;
        public int resetTime;
        public int myRaidSucTime;
        public int raidXDB;
        public int raidJinShi;
        public int raidJinbi;
        public boolean hasGetAward;
        /** field7 myBiaoCheInfo：repeated string，自己的车 GUID。 */
        public List<String> myCartIds = new ArrayList<>();
        /** field4 biaoCheInfo：repeated CCMsgRaidBiaoCheInfo，**别人**的车（拦截列表）。 */
        public List<byte[]> raidCarts = new ArrayList<>();
    }

    /**
     * S2C 1936 CCMsgRequestMaJiuInfoRet（客户端 MaJiuBaseLanJieUI/MaJiuLanJieDuiWuUI/MaJiuShuaXinUI 消费）。
     * field3 curResetTime=**已刷新次数**（客户端 MaJiuShuaXinUI.cs:69 用
     * {@code refreshTimePerActive - curResetTime} 算剩余次数，故不可下发超过刷新上限的值）、
     * field5 myRaidSucTime=我**成功劫镖**次数（MaJiuLanJieDuiWuUI.cs:278 与 VipManager.JieBiaoCount 比较）、
     * field8-10=可掠夺资源。
     */
    public byte[] majiuInfo(MajiuView v) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, v.phase);
            Pb.int32Always(out, 2, v.leftSec);
            Pb.int32Always(out, 3, v.resetTime);
            for (byte[] cart : v.raidCarts) {
                Pb.bytesAlways(out, 4, cart);
            }
            Pb.int32Always(out, 5, v.myRaidSucTime);
            Pb.bool(out, 6, v.hasGetAward);
            for (String id : v.myCartIds) {
                Pb.stringAlways(out, 7, id == null ? "" : id);
            }
            Pb.int32Always(out, 8, v.raidXDB);
            Pb.int32Always(out, 9, v.raidJinShi);
            Pb.int32Always(out, 10, v.raidJinbi);
        });
    }

    /** 兼容旧调用：只填 phase/leftSec/自己的车 ID。 */
    public byte[] majiuInfo(int phase, int leftSec, String cartId) {
        MajiuView v = new MajiuView();
        v.phase = phase;
        v.leftSec = leftSec;
        if (phase >= 2 && cartId != null && !cartId.isEmpty()) {
            v.myCartIds.add(cartId);
        }
        return majiuInfo(v);
    }

    /** S2C 1937/1938 CCMsgUnionBiaoCheInfo{1 repeated CCMsgPlayerBiaoCheInfo}。 */
    public byte[] escortCarts(List<byte[]> carts) {
        return Pb.write(out -> {
            if (carts != null) {
                for (byte[] one : carts) {
                    if (one != null && one.length > 0) {
                        Pb.bytesAlways(out, 1, one);
                    }
                }
            }
        });
    }

    /**
     * CCMsgPlayerBiaoCheInfo：一辆镖车（我方或他人）。
     * field6 {@code beRaidTime} 是**被掠夺次数**不是时间（客户端 MaJiuDuiWuLieBiaoUI.cs:113-115
     * 渲染成 StrTable 100797「（被掠夺{0}次）」）；field7 {@code defenceSucRaiderUnionName}
     * 是「成功防守的劫掠者公会名」列表（本版本客户端不消费，仅按 proto 保真写出）。
     */
    public byte[] playerBiaoChe(String cartId, int targetId, PlayerRecord owner, int beRaidTime) {
        List<byte[]> formation = cartFormation(owner);
        int headIndex = owner == null ? 0 : owner.mainHeroIndex;
        String defendUnion = owner == null ? "" : owner.economy.escortDefendUnion;
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, cartId == null ? "" : cartId);
            Pb.int32Always(out, 2, targetId);
            Pb.int32Always(out, 3, owner == null ? 0 : owner.playerId);
            Pb.stringAlways(out, 4, owner == null ? "" : owner.roleName);
            for (byte[] wj : formation) {
                Pb.bytesAlways(out, 5, wj);
            }
            Pb.int32Always(out, 6, beRaidTime);
            if (defendUnion != null && !defendUnion.isEmpty()) {
                Pb.stringAlways(out, 7, defendUnion);
            }
            Pb.int32Always(out, 8, owner == null ? 1 : owner.level);
            Pb.int32Always(out, 9, headIndex);
        });
    }

    /**
     * CCMsgBiaoCheWjBriefInfo{1 wjBriefInfo(CMsgWuJiangBriefInfo),2 wjFormationJob,3 FightPower}。
     * 注意 field1 必须是**子消息**：写成 varint 会让客户端 ProtoReader 抛 Invalid wire-type，整包报废。
     */
    public byte[] biaoCheWj(PlayerRecord.Hero wj, int job) {
        if (wj == null) {
            return new byte[0];
        }
        return Pb.write(out -> {
            Pb.bytesAlways(out, 1, wjBrief(wj.heroIndex, wj.level, wj.stage, wj.stars));
            Pb.int32Always(out, 2, job);
            Pb.int32Always(out, 3, wj.fightPower);
        });
    }

    /** 镖车阵容：马厩阵型（FORMATION_MAJIU=13）5 个槽位，空槽不发。 */
    public List<byte[]> cartFormation(PlayerRecord owner) {
        List<byte[]> out = new ArrayList<>();
        if (owner == null) {
            return out;
        }
        owner.ensureCollections();
        List<String> slots = owner.formationSlots(PlayerRecord.FORMATION_MAJIU);
        for (int job = 1; job <= 5; job++) {
            String guid = slots.size() >= job ? slots.get(job - 1) : "";
            if (guid == null || guid.isEmpty()) {
                continue;
            }
            byte[] one = biaoCheWj(owner.findHero(guid), job);
            if (one.length > 0) {
                out.add(one);
            }
        }
        return out;
    }

    /** CCMsgRaidBiaoCheInfo{1 hasBeenRaid,2 raidingPlayerName,3 biaoCheInfo}：拦截列表一行。 */
    public byte[] raidBiaoChe(boolean hasBeenRaid, String raiderName, byte[] cart) {
        return Pb.write(out -> {
            Pb.bool(out, 1, hasBeenRaid);
            Pb.stringAlways(out, 2, raiderName == null ? "" : raiderName);
            if (cart != null && cart.length > 0) {
                Pb.bytesAlways(out, 3, cart);
            }
        });
    }

    /** 1923/1925/1926/1929 用的章节 Boss 视图（HP 数据由 UnionService 按 MonsterProperty.ini 组装）。 */
    public static class BossView {
        public int chapter;
        public String ori = "";
        public int curHp;
        public java.util.List<WorldStore.DamageEntry> hurt = new ArrayList<>();
    }

    /**
     * S2C 1923 CCMsgRequestZuoZhanShiBossInfo_Ret。
     * field1 repeated CCMsgUnionBossPlayTimeUpdate、field2 CMsgEmployInofo（自己在作战室的雇佣）、
     * field3 repeated CCMsgUnionBossInfo（已解锁章节）。
     */
    public byte[] unionBossInfo(List<BossView> bosses, int employPlayerId, int employWjIndex,
                                Map<Integer, Integer> playTimes) {
        return Pb.write(out -> {
            if (playTimes == null || playTimes.isEmpty()) {
                Pb.bytesAlways(out, 1, unionBossPlayTime(1, 0));
            } else {
                for (Map.Entry<Integer, Integer> e : playTimes.entrySet()) {
                    Pb.bytesAlways(out, 1, unionBossPlayTime(e.getKey().intValue(), e.getValue().intValue()));
                }
            }
            Pb.bytesAlways(out, 2, Pb.write(o -> {
                Pb.int32Always(o, 1, employPlayerId);
                Pb.int32Always(o, 2, employWjIndex);
            }));
            if (bosses != null) {
                for (BossView b : bosses) {
                    Pb.bytesAlways(out, 3, unionBossFlat(b));
                }
            }
        });
    }

    public byte[] unionBossPlayTime(int bossId, int curPlayTime) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, bossId);
            Pb.int32Always(out, 2, curPlayTime);
        });
    }

    /** S2C 1925 CCMsgRequestFightBossRet{bossInfo,severCheck}：severCheck=true 时客户端交服务端算。 */
    public byte[] unionBossFightRet(BossView boss) {
        return Pb.write(out -> {
            Pb.bytesAlways(out, 1, unionBossFlat(boss));
            Pb.bool(out, 2, false);
        });
    }

    /** S2C 1926 CCMsgResultUnionBossFight（UnionZhanDouJieSuan 消费 1/3/4/5/6/7/8/9/10）。 */
    public byte[] unionBossResultRet(int expPerBattle, int expForWj, int jinbi, int contri, int xdb,
                                     int yqb, int jinShi, int growValue, List<byte[]> goods, BossView boss) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, expPerBattle);
            Pb.int32Always(out, 2, expForWj);
            Pb.int32Always(out, 3, jinbi);
            Pb.int32Always(out, 4, contri);
            Pb.int32Always(out, 5, xdb);
            Pb.int32Always(out, 6, yqb);
            Pb.int32Always(out, 7, jinShi);
            Pb.int32Always(out, 8, growValue);
            if (goods != null) {
                for (byte[] g : goods) {
                    Pb.bytesAlways(out, 9, g);
                }
            }
            Pb.bytesAlways(out, 10, unionBossFlat(boss));
        });
    }

    /** S2C 1929 CCMsgFightBossFinishInfo{bossInfo,goods}：打开结算面板 WarRoomFinish 的唯一入口。 */
    public byte[] bossFinishInfo(BossView boss, List<byte[]> goods) {
        return Pb.write(out -> {
            Pb.bytesAlways(out, 1, unionBossFlat(boss));
            if (goods != null) {
                for (byte[] g : goods) {
                    Pb.bytesAlways(out, 2, g);
                }
            }
        });
    }

    /** CCMsgUnionBossInfo{1 chapterID,2 bossOriName,3 curHP,4 repeated CCMsgBossFightPlayerHurtInfo}。 */
    public byte[] unionBossFlat(BossView b) {
        if (b == null) {
            // 无公会/无该章数据时退化为空消息，避免调用方（1926/1929）NPE 把回包整包吞掉。
            return new byte[0];
        }
        return Pb.write(out -> {
            Pb.int32Always(out, 1, b.chapter);
            Pb.stringAlways(out, 2, b.ori == null ? "" : b.ori);
            Pb.int32Always(out, 3, b.curHp);
            if (b.hurt != null) {
                for (WorldStore.DamageEntry e : b.hurt) {
                    Pb.bytesAlways(out, 4, Pb.write(o -> {
                        Pb.stringAlways(o, 1, e.name == null ? "" : e.name);
                        Pb.int32Always(o, 2, e.heroIndex);
                        Pb.int32Always(o, 3, e.level);
                        Pb.int32Always(o, 4, e.damage);
                    }));
                }
            }
        });
    }

    public byte[] hundredTowerLayer(int gold, List<GameTables.GoodsDrop> goods) {
        return Pb.write(out -> {
            Pb.int32(out, 1, gold);
            if (goods != null) {
                for (GameTables.GoodsDrop g : goods) {
                    Pb.bytes(out, 2, goods(g.ori, g.count));
                }
            }
        });
    }

    /** S2C 451 CCMsgResouceFBExtAwards：双倍日额外份，进结算 ExtItems。 */
    public byte[] resourceFbExtAwards(List<GameTables.GoodsDrop> extra) {
        List<GameTables.GoodsDrop> list = extra == null ? java.util.Collections.emptyList() : extra;
        return Pb.write(out -> {
            for (GameTables.GoodsDrop g : list) {
                if (g == null || g.ori == null || g.ori.isEmpty() || g.count <= 0) {
                    continue;
                }
                Pb.bytes(out, 1, dropGoodsItem(g.ori, g.count));
            }
        });
    }

    public byte[] resourceFb(PlayerRecord.ResourceFb fb) {
        return Pb.write(out -> {
            Pb.int32(out, 1, fb.regionType);
            Pb.int32Always(out, 2, fb.playTime);
            Pb.stringAlways(out, 3, fb.lastPlay == null ? "" : fb.lastPlay);
            if (fb.stars != null) {
                for (Map.Entry<Integer, Integer> e : fb.stars.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) {
                        continue;
                    }
                    int level = e.getKey().intValue();
                    int star = Math.max(0, Math.min(3, e.getValue().intValue()));
                    Pb.bytes(out, 4, Pb.write(s -> {
                        Pb.int32(s, 1, level);
                        Pb.int32(s, 2, star);
                    }));
                }
            }
        });
    }

    public byte[] resourceFbUpdate(PlayerRecord rec) {
        return Pb.write(out -> {
            for (PlayerRecord.ResourceFb fb : rec.resourceFb.values()) {
                Pb.bytes(out, 1, resourceFb(fb));
            }
        });
    }

    public byte[] fbInfo(int region, int gold, int exp, int wjExp) {
        return fbInfo(region, gold, exp, wjExp, 0, 0, null, false);
    }

    /**
     * S2C 401 CCSMsgFBInfo。dropGoods/WNSP/YingPo 供局内掉落池 + 结算 UI；
     * needCheck→IsServerCal（主线 PVE 应为 false）。
     */
    public byte[] fbInfo(int region, int gold, int exp, int wjExp,
                         int wnsp, int yingPo, List<GameTables.GoodsDrop> drops, boolean needCheck) {
        List<GameTables.GoodsDrop> list = drops == null ? java.util.Collections.emptyList() : drops;
        return Pb.write(out -> {
            Pb.int32(out, 1, region);
            Pb.int32(out, 2, gold);
            Pb.int32(out, 3, exp);
            Pb.int32(out, 4, wjExp);
            Pb.int32(out, 5, wnsp);
            Pb.int32(out, 6, yingPo);
            for (GameTables.GoodsDrop g : list) {
                if (g == null || g.ori == null || g.ori.isEmpty() || g.count <= 0) {
                    continue;
                }
                Pb.bytes(out, 7, dropGoodsItem(g.ori, g.count));
            }
            if (needCheck) {
                Pb.bool(out, 8, true);
            }
        });
    }

    private byte[] dropGoodsItem(String ori, int count) {
        return Pb.write(out -> {
            Pb.string(out, 1, ori);
            Pb.int32(out, 2, count);
        });
    }

    public byte[] chapterChest(int chapterId, int baoxiangId) {
        return Pb.write(out -> {
            Pb.int32(out, 1, chapterId);
            Pb.int32(out, 2, baoxiangId);
        });
    }

    public byte[] qianDaoInfo(PlayerRecord rec) {
        return Pb.write(out -> {
            Pb.int32(out, 1, rec.signIn.year);
            Pb.int32(out, 2, rec.signIn.month);
            Pb.int32(out, 3, rec.signIn.totalDays);
            if (rec.signIn.prizeStatus != null) {
                // APK：list 长度=当天日号；最后一格是今天，更后的格子走普通底板（未到）。短于整月才能补签前几天。
                int today = GameTime.today().getDayOfMonth();
                int n = Math.min(today, rec.signIn.prizeStatus.size());
                for (int i = 0; i < n; i++) {
                    Integer st = rec.signIn.prizeStatus.get(i);
                    int status = st == null ? 0 : st.intValue();
                    // 假服不接补签协议（SignInService.ALLOW_MAKEUP=false），但客户端对
                    // 「日号<长度 且 status==1」的格子会画成可领并真发 1402（SignInSystem.cs:189-197、:396-407），
                    // 点下去必被拒、玩家零反馈。客户端只有「status != 1 才画成不可领」这一种表达，
                    // 所以过去未领的格子在这里下发成已领；存档仍留 1，翻译只发生在本方法。
                    if (i + 1 < today && status == SignInService.NOPRIZED) {
                        status = SignInService.NORMAL;
                    }
                    Pb.int32Always(out, 4, status);
                }
            }
        });
    }

    public byte[] qianDaoPrize(int dayNumber) {
        return Pb.write(out -> Pb.int32(out, 1, dayNumber));
    }

    public byte[] shopType(int type) {
        return Pb.write(out -> Pb.int32(out, 1, type));
    }

    public byte[] shopInfo(PlayerRecord.ShopState shop) {
        return Pb.write(out -> {
            int type = shop == null ? 0 : shop.type;
            EconomyTables.ShopCfg cfg = economy.shop(type);
            int remaining = 0;
            if (cfg != null && shop != null) {
                remaining = Math.max(0, cfg.freeRefresh - shop.freeRefresh);
            }
            Pb.int32(out, 1, type);
            Pb.float32(out, 2, shop == null ? 0f : shop.lastSys);
            if (shop != null && shop.fields != null) {
                for (PlayerRecord.ShopField f : shop.fields) {
                    Pb.bytes(out, 3, shopField(f));
                }
            }
            Pb.int32Always(out, 4, remaining);
            Pb.int32Always(out, 5, shop == null ? 0 : shop.payRefresh);
        });
    }

    public byte[] shopField(PlayerRecord.ShopField f) {
        return Pb.write(out -> {
            Pb.string(out, 1, f.ori);
            Pb.int32(out, 2, f.count);
            Pb.int32(out, 3, f.price);
            Pb.bool(out, 4, f.rmb);
            Pb.bool(out, 5, f.sellOut);
        });
    }

    public byte[] shopSellOut(int shopType, int fieldNum) {
        return Pb.write(out -> {
            Pb.int32(out, 1, shopType);
            Pb.int32(out, 2, fieldNum);
        });
    }

    public byte[] buyTiLiRet(int add) {
        return Pb.write(out -> Pb.int32(out, 1, add));
    }

    public byte[] buyJinBiRet(int cost, int add, int baoji) {
        return Pb.write(out -> {
            Pb.int32(out, 1, cost);
            Pb.int32(out, 2, add);
            Pb.int32(out, 3, baoji);
        });
    }

    public byte[] actExchangeInfo(int subId, ActExtCfg.ExchangeTab tab) {
        return Pb.write(out -> {
            Pb.int32(out, 1, tab == null ? subId : tab.subID);
            Pb.stringAlways(out, 2, tab == null || tab.title == null ? "" : tab.title);
            Pb.stringAlways(out, 3, tab == null || tab.desc == null ? "" : tab.desc);
            if (tab != null && tab.rows != null) {
                for (ActExtCfg.ExchangeRow row : tab.rows) {
                    Pb.bytes(out, 4, actExchangeRow(row));
                }
            }
        });
    }

    public byte[] actExchangeRow(ActExtCfg.ExchangeRow row) {
        return Pb.write(out -> {
            Pb.int32(out, 1, row.id);
            Pb.stringAlways(out, 2, row.getOri == null ? "" : row.getOri);
            Pb.int32(out, 3, row.getCount);
            if (row.materials != null) {
                for (ActExtCfg.ExchangeMat m : row.materials) {
                    Pb.bytes(out, 4, actExchangeMat(m));
                }
            }
        });
    }

    public byte[] actExchangeMat(ActExtCfg.ExchangeMat m) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, m.ori == null ? "" : m.ori);
            Pb.int32(out, 2, m.count);
        });
    }

    public byte[] actExchangeDo(int subId, int id) {
        return Pb.write(out -> {
            Pb.int32(out, 1, subId);
            Pb.int32(out, 2, id);
        });
    }

    public byte[] payIdRet(String orderId, int orderType) {
        return Pb.write(out -> {
            // stringAlways：客户端 OnSdkPayIdAskRet 用 orderID.Length == 0 判定「服务端拒绝」，
            // 拒绝时字段必须显式下发空串（省略字段在反序列化后可能是 null）
            Pb.stringAlways(out, 1, orderId == null ? "" : orderId);
            Pb.int32(out, 2, orderType);
            Pb.string(out, 3, "ok");
            Pb.string(out, 4, "ok");
        });
    }

    public byte[] vipAwardItem(String ori, int count, int stars) {
        return Pb.write(out -> {
            Pb.string(out, 1, ori);
            Pb.int32(out, 2, count);
            Pb.int32(out, 3, stars);
        });
    }

    public byte[] vipLevelAwardRet(String vipAwardGetInfo, List<byte[]> items) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, vipAwardGetInfo == null ? "" : vipAwardGetInfo);
            if (items != null) {
                for (byte[] it : items) {
                    Pb.bytes(out, 2, it);
                }
            }
        });
    }

    /**
     * S2C 2617 单条 VIP 礼包档位（{@code CCMsgQueryVIPGiftItem}：1 mID / 2 mVipLevel /
     * 3 mOriName / 4 mCount / 5 mNeedRMB）。
     *
     * <p>五个字段都用 Always：真服 protobuf-net 对 message 字段不省略默认值，省略会让客户端
     * 反序列化出 null/0（mOriName=null 会让 {@code VipBuyItem.OnItemPressed} 的
     * {@code GetPropertyCfg(null)} 走空指针分支）。
     */
    public byte[] vipGiftItem(int id, int vipLevel, String ori, int count, int needRmb) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, id);
            Pb.int32Always(out, 2, vipLevel);
            Pb.stringAlways(out, 3, ori == null ? "" : ori);
            Pb.int32Always(out, 4, count);
            Pb.int32Always(out, 5, needRmb);
        });
    }

    /** S2C 2617 {@code CCMsgQueryVIPGiftConfigInfo_Ret.field1=items}（空表 = 面板无礼包）。 */
    public byte[] vipGiftConfigRet(List<byte[]> items) {
        return Pb.write(out -> {
            if (items != null) {
                for (byte[] it : items) {
                    Pb.bytes(out, 1, it);
                }
            }
        });
    }

    /**
     * S2C 2609 {@code CCMsgQueryVIPGiftInfo_Ret.field1=curBoughtVIPGifts}（{@code "|id|id|"} 串）。
     *
     * <p>stringAlways：客户端 {@code ActivityMainUI.RefreshVIPBuyed} 直接存串并做
     * {@code Contains("|id|")} 判断，null 会抛空指针。
     */
    public byte[] vipGiftBoughtRet(String bought) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, bought == null ? "" : bought);
        });
    }

    /**
     * S2C 2610 {@code CCMsgBuyVIPGift_Ret.field1=buyID}。
     * <p>客户端处理器 {@code PlayGameState.cs:247 → :7054-7073} 用 buyID 在 {@code mVipBuy}
     * 里查配置，命中就冒字 100151「获得 X xN」并刷新 VIP 标题 → 这是**成功提示**，
     * 只在真正扣款成功时发（见 {@code VipGiftService.onBuy} 的拒绝路径）。
     */
    public byte[] vipGiftBuyRet(int buyId) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, buyId);
        });
    }

    public byte[] paySuc(int goodsId) {
        return Pb.write(out -> {
            Pb.int32(out, 1, goodsId);
            Pb.stringAlways(out, 2, "");
        });
    }

    /** S2C 3102：各档已购次数（限次另赠进度）。 */
    public byte[] userPayInfo(PlayerRecord rec) {
        return Pb.write(out -> {
            if (rec.economy.payBuyCounts == null) {
                return;
            }
            int max1st = economy.max1stPayGoodsId();
            boolean has1stId = false;
            for (Integer id : rec.economy.payBuyCounts.keySet()) {
                if (id != null && id.intValue() > 0 && id.intValue() <= max1st) {
                    has1stId = true;
                    break;
                }
            }
            boolean mirror = !has1stId;
            rec.economy.payBuyCounts.forEach((id, cnt) -> {
                if (id == null || cnt == null || cnt.intValue() <= 0) {
                    return;
                }
                try {
                    Pb.bytes(out, 1, userPayItem(id.intValue(), cnt.intValue()));
                    if (mirror) {
                        int mapped = id.intValue() - 7;
                        if (mapped >= 1 && mapped <= max1st) {
                            Pb.bytes(out, 1, userPayItem(mapped, cnt.intValue()));
                        }
                    }
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        });
    }

    public byte[] userPayItem(int goodsId, int buyCount) {
        return Pb.write(out -> {
            Pb.int32(out, 1, goodsId);
            Pb.int32(out, 2, buyCount);
        });
    }

    public byte[] payCheckRet(String orderId) {
        return Pb.write(out -> {
            Pb.string(out, 1, orderId);
            Pb.int32(out, 2, 1);
        });
    }

    public byte[] xiLianInfo(String guid, int type, int value) {
        byte[] prop = Pb.write(out -> {
            Pb.int32(out, 1, type);
            Pb.int32Always(out, 2, value);
        });
        return Pb.write(out -> {
            Pb.string(out, 1, guid);
            Pb.bytes(out, 2, prop);
        });
    }

    public byte[] resetRet(int gold, Map<String, Integer> items) {
        return Pb.write(out -> {
            Pb.int32(out, 1, gold);
            if (items != null) {
                for (Map.Entry<String, Integer> e : items.entrySet()) {
                    Pb.bytes(out, 2, resetItem(e.getKey(), 0, e.getValue()));
                }
            }
        });
    }

    public byte[] resetItem(String ori, int stars, int count) {
        return Pb.write(out -> {
            Pb.string(out, 1, ori);
            Pb.int32(out, 2, stars);
            Pb.int32(out, 3, count);
        });
    }

    public byte[] jingLianExpRet(String ori, int type) {
        return Pb.write(out -> {
            Pb.string(out, 1, ori);
            Pb.int32Always(out, 2, type);
        });
    }

    public byte[] openBaoXiangRet(int gold, int diamond, int stamina, int wnsp, int yingPo,
                                  List<byte[]> awards, boolean single, int moFaChen, int jjc, int xdb) {
        return Pb.write(out -> {
            Pb.int32(out, 1, gold);
            Pb.int32(out, 2, diamond);
            Pb.int32(out, 3, stamina);
            Pb.int32(out, 4, wnsp);
            Pb.int32(out, 5, yingPo);
            if (awards != null) {
                for (byte[] a : awards) {
                    Pb.bytes(out, 6, a);
                }
            }
            Pb.bool(out, 7, single);
            Pb.int32(out, 8, moFaChen);
            Pb.int32(out, 9, jjc);
            Pb.int32(out, 10, xdb);
        });
    }

    public byte[] preChoiceBaoXiang(String ori, int count, List<EconomyTables.ChoiceCell> cells) {
        return Pb.write(out -> {
            Pb.string(out, 1, ori == null ? "" : ori);
            Pb.int32(out, 2, Math.max(1, count));
            if (cells != null) {
                for (EconomyTables.ChoiceCell c : cells) {
                    Pb.bytes(out, 3, choiceBaoXiangCell(c));
                }
            }
        });
    }

    public byte[] choiceBaoXiangCell(EconomyTables.ChoiceCell c) {
        return Pb.write(out -> {
            Pb.int32(out, 1, c.id);
            Pb.int32(out, 2, c.type);
            Pb.string(out, 3, c.goodId == null ? "" : c.goodId);
            Pb.int32(out, 4, Math.max(1, c.amount));
            Pb.int32(out, 5, c.star);
        });
    }

    public byte[] towerInfo(PlayerRecord rec) {
        return towerInfo(rec, null);
    }

    public byte[] towerInfo(PlayerRecord rec, PlayerRecord buddy) {
        return towerInfo(rec, buddy, false);
    }

    public byte[] towerInfo(PlayerRecord rec, PlayerRecord buddy, boolean buddyOnline) {
        PlayerRecord b = buddy;
        boolean on = b != null && buddyOnline;
        return Pb.write(out -> {
            Pb.int32(out, 1, rec.tower.historyLayer);
            Pb.int32(out, 2, rec.tower.curLayer);
            Pb.int32Always(out, 3, rec.tower.challengeTimes);
            Pb.string(out, 4, rec.tower.buddyName == null ? "" : rec.tower.buddyName);
            Pb.int32Always(out, 5, b == null ? 0 : b.mainHeroIndex);
            Pb.int32Always(out, 6, b == null ? 0 : Math.max(1, b.tower.curLayer));
            if (on) {
                Pb.bool(out, 7, true);
            }
            Pb.int32Always(out, 8, b == null ? 0 : b.level);
        });
    }

    /**
     * S2C 3601 CCSMsgHundredTowerRegionInfo。
     * region_res_id 须等于 HundredTowerList.regionResID（切图）；drop 供掉落池；
     * isServerFight→BCTCommonInfo.mSycServerFight / IsServerCal。
     */
    public byte[] towerRegion(int region, int gold, List<GameTables.GoodsDrop> drops,
                              int buddyCurLayer, boolean serverFight) {
        List<GameTables.GoodsDrop> list = drops == null ? java.util.Collections.emptyList() : drops;
        return Pb.write(out -> {
            Pb.int32(out, 1, region);
            Pb.int32(out, 2, gold);
            for (GameTables.GoodsDrop g : list) {
                if (g == null || g.ori == null || g.ori.isEmpty() || g.count <= 0) {
                    continue;
                }
                Pb.bytes(out, 3, dropGoodsItem(g.ori, g.count));
            }
            Pb.int32Always(out, 4, buddyCurLayer);
            if (serverFight) {
                Pb.bool(out, 5, true);
            }
        });
    }

    public byte[] towerRegion(int region, int gold) {
        return towerRegion(region, gold, null, 0, false);
    }

    public byte[] towerTimes(int times) {
        return Pb.write(out -> Pb.int32Always(out, 1, times));
    }

    public byte[] towerBuddyLayer(int layer) {
        return Pb.write(out -> Pb.int32Always(out, 1, layer));
    }

    // §6-9 死代码清理：原 public byte[] towerRank(int myRank)（恒打空榜）全工程零调用 ⇒ 已删。
    // 现役入口是 DungeonService.onTowerRank(:1104) → towerRankList(store.all(), rec)。

    public byte[] towerRankList(java.util.Collection<PlayerRecord> all, PlayerRecord me) {
        List<PlayerRecord> rows = new ArrayList<>();
        if (all != null) {
            for (PlayerRecord p : all) {
                if (p != null && p.tower != null) {
                    rows.add(p);
                }
            }
        }
        rows.sort((a, b) -> Integer.compare(b.tower.historyLayer, a.tower.historyLayer));
        int myRank = 1;
        if (me != null) {
            myRank = rows.indexOf(me) + 1;
            if (myRank <= 0) {
                myRank = rows.size() + 1;
            }
        }
        final int rank = Math.max(1, myRank);
        return Pb.write(out -> {
            Pb.int32(out, 1, rank);
            int n = Math.min(50, rows.size());
            for (int i = 0; i < n; i++) {
                PlayerRecord p = rows.get(i);
                final int ri = i + 1;
                Pb.bytes(out, 2, Pb.write(item -> {
                    Pb.int32(item, 1, p.playerId);
                    Pb.int32(item, 2, ri);
                    Pb.string(item, 3, p.roleName == null ? "" : p.roleName);
                    Pb.int32(item, 4, p.level);
                    Pb.int32(item, 5, p.mainHeroIndex);
                    Pb.int32(item, 6, Math.max(1, p.tower.historyLayer));
                }));
            }
        });
    }

    public byte[] soulUpdate(int wjIndex, boolean composed, int stage, int exp) {
        byte[] inner = Pb.write(out -> {
            Pb.bool(out, 1, composed);
            Pb.int32(out, 2, stage);
            Pb.int32(out, 3, exp);
        });
        return Pb.write(out -> {
            Pb.int32(out, 1, wjIndex);
            // 必须 bytesAlways：composed=false 且 stage/exp 都是默认 0 时 inner 为空，
            // 普通 Pb.bytes 会整个省略 f2 ⇒ 客户端读 .equipSoulUpdate.curExp 直接 NRE
            Pb.bytesAlways(out, 2, inner);
        });
    }

    public byte[] jiBanSlot(PlayerRecord.JiBanSlot slot) {
        if (slot == null) {
            slot = new PlayerRecord.JiBanSlot();
        }
        slot.ensure();
        PlayerRecord.JiBanSlot s = slot;
        return Pb.write(out -> {
            for (PlayerRecord.TianFu t : s.tianFu) {
                Pb.bytes(out, 1, tianFu(t));
            }
        });
    }

    private byte[] tianFu(PlayerRecord.TianFu t) {
        PlayerRecord.TianFu x = t == null ? new PlayerRecord.TianFu() : t;
        return Pb.write(out -> {
            Pb.bool(out, 1, x.hasNew);
            Pb.int32Always(out, 2, x.star);
            Pb.int32(out, 3, x.type);
            Pb.int32(out, 4, x.roleId);
            Pb.int32(out, 5, x.attack);
            Pb.int32(out, 6, x.defend);
            Pb.int32(out, 7, x.hp);
            Pb.bool(out, 8, x.locked);
            Pb.int32(out, 9, x.newRoleId);
            Pb.int32(out, 10, x.newStar);
            Pb.int32(out, 11, x.newAttack);
            Pb.int32(out, 12, x.newDefend);
            Pb.int32(out, 13, x.newHp);
        });
    }

    public byte[] jiBanRefresh(int index, PlayerRecord.JiBanSlot slot) {
        return Pb.write(out -> {
            Pb.int32(out, 1, index);
            Pb.bytes(out, 2, jiBanSlot(slot));
        });
    }

    public byte[] teQuanInfo(PlayerRecord rec) {
        return Pb.write(out -> {
            Pb.bool(out, 1, rec.cards.zhiZun);
            Pb.bool(out, 2, rec.cards.todayZhiZun);
            Pb.stringAlways(out, 3, rec.cards.teQuanEnd);
            Pb.bool(out, 4, rec.cards.todayTeQuan);
        });
    }

    /**
     * S2C 3902 {@code CMsgTeQuanCardAward}：1 type / 2 zuanShiAward / 3 goodsAward / 4 goodsCount。
     * <p>客户端 {@code PlayGameState.cs:7342-7376} 只在 {@code goodsCount != 0} 时把
     * {@code goodsAward} 作为奖励行加进弹窗 → 无物品时字段 3/4 写空/0 与真服一致。
     */
    public byte[] teQuanAward(int type, int diamond, String goodsOri, int goodsCount) {
        return Pb.write(out -> {
            Pb.int32(out, 1, type);
            Pb.int32(out, 2, diamond);
            Pb.string(out, 3, goodsOri == null ? "" : goodsOri);
            Pb.int32(out, 4, goodsCount);
        });
    }

    /** S2C 2601：推开全部有 prefab 的活动 tab；奖池未接的点开可能空，侧栏图标由客户端关掉。 */
    public byte[] activityStatus(PlayerRecord rec, ActivityTables tables, List<ActivityTables.TitleSpec> titles) {
        int activeVp = tables.activeVpType(GameTime.localTime());
        String gained = "";
        if (rec.activity != null && rec.activity.gainedVpTypes != null && !rec.activity.gainedVpTypes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rec.activity.gainedVpTypes.size(); i++) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(rec.activity.gainedVpTypes.get(i));
            }
            gained = sb.toString();
        }
        final String gainedCsv = gained;
        return Pb.write(out -> {
            for (int i = 0; i < titles.size(); i++) {
                ActivityTables.TitleSpec t = titles.get(i);
                // atalas/icon 留空：客户端已隐藏 Icon，避免坏图集加载
                Pb.bytes(out, 1, activityTitle(t.type, t.subId, i, t.name, "", "", "", false));
            }
            Pb.bool(out, 2, rec.activity.today7Day);
            Pb.int32(out, 3, rec.activity.day7Count);
            Pb.int32(out, 4, activeVp);
            // 必须 Always：客户端 ActivityStatusInfo.cs:531 直接 .Split，省略字段反序列化后可能是 null
            // （当天没领过体力时 gainedCsv 为空串，普通 Pb.string 会跳过不写）
            Pb.stringAlways(out, 5, gainedCsv);
        });
    }

    private byte[] activityTitle(int type, int subId, int index, String name, String atlas, String icon,
                                 String endTime, boolean isNew) {
        return Pb.write(out -> {
            Pb.int32(out, 1, type);
            Pb.int32(out, 2, subId);
            Pb.int32(out, 3, index);
            Pb.string(out, 4, name);
            Pb.string(out, 5, atlas);
            Pb.string(out, 6, icon);
            Pb.string(out, 7, endTime);
            Pb.bool(out, 8, isNew);
        });
    }

    /** S2C 2602 CCMsgRequest7DayQianDaoRet。 */
    public byte[] activity7DayRet(int rmb, int gold, List<String> equipGuids,
                                  List<String> itemOris, List<Integer> itemCounts) {
        return Pb.write(out -> {
            Pb.int32(out, 1, rmb);
            Pb.int32(out, 2, gold);
            if (equipGuids != null) {
                for (String guid : equipGuids) {
                    Pb.string(out, 3, guid);
                }
            }
            if (itemOris != null) {
                for (int i = 0; i < itemOris.size(); i++) {
                    int c = (itemCounts != null && i < itemCounts.size()) ? itemCounts.get(i).intValue() : 1;
                    Pb.bytes(out, 4, goods(itemOris.get(i), c));
                }
            }
        });
    }

    /** S2C 2603 CCMsgRequestAwardVPRet。 */
    public byte[] activityVpRet(int errorCode, int type, int vpAward) {
        return Pb.write(out -> {
            Pb.int32(out, 1, errorCode);
            Pb.stringAlways(out, 2, now());
            Pb.int32(out, 3, type);
            Pb.int32(out, 4, vpAward);
        });
    }

    /** 每日累计充值的单档内奖励项 CCMsgShiningAwardItem（field1 oriName/2 count/3 isShining）。 */
    public byte[] shiningAward(String ori, int count, boolean shining) {
        return Pb.write(out -> {
            Pb.string(out, 1, ori);
            Pb.int32(out, 2, count);
            Pb.bool(out, 3, shining);
        });
    }

    /** 每日累计充值档位条目 CCMsgDailyChongZhiAwardItem（field1 rmb/2 钻/3 金币/4 items）。 */
    public byte[] dailyChongZhiTier(int rmb, int zuanShiAward, int jinbiAward, List<byte[]> items) {
        return Pb.write(out -> {
            Pb.int32(out, 1, rmb);
            Pb.int32(out, 2, zuanShiAward);
            Pb.int32(out, 3, jinbiAward);
            if (items != null) {
                for (byte[] it : items) {
                    Pb.bytes(out, 4, it);
                }
            }
        });
    }

    /**
     * CCMsg1stChongZhiItem：#1 mJE / #2 mJinBi / #3 mRmb /
     * #4 oriName* / #5 mStart* / #6 count* / #7 isShining*（Msg.dll，各 repeated 分开写）。
     */
    public byte[] firstChongZhiItem(int mJE, int mJinBi, int mRmb,
                                    List<String> oriNames, List<Integer> stars,
                                    List<Integer> counts, List<Boolean> shining) {
        return Pb.write(out -> {
            Pb.int32(out, 1, mJE);
            Pb.int32(out, 2, mJinBi);
            Pb.int32(out, 3, mRmb);
            if (oriNames == null) {
                return;
            }
            // 4/5/6/7 是四条**并行 repeated 列表**（客户端字段名 oriName/mStart/count/isShining）：
            // ActivityPropertyMgr.cs:269-277 以 `oriName.Count` 为循环上界，同一 num2 下标同时取四列，
            // 所以四列必须等长。protobuf-net 写 repeated 元素**不省略默认值**（真服 false/0 也会发 38 00），
            // 而 Pb.int32/Pb.bool 会跳过 0/false → 少一个元素即越界抛 ArgumentOutOfRangeException
            // （PlayGameState.cs:7112-7117 无 try/catch）→ 首充面板整块不刷新。故这里一律用 Always。
            int n = oriNames.size();
            for (int i = 0; i < n; i++) {
                Pb.stringAlways(out, 4, oriNames.get(i));
                Pb.int32Always(out, 5, intAt(stars, i));
                Pb.int32Always(out, 6, intAt(counts, i));
                Pb.boolAlways(out, 7, shining != null && i < shining.size() && Boolean.TRUE.equals(shining.get(i)));
            }
        });
    }

    private static int intAt(List<Integer> list, int i) {
        if (list == null || i >= list.size() || list.get(i) == null) {
            return 0;
        }
        return list.get(i).intValue();
    }

    /** S2C 2618 CCMsgQuery1stChongZhiAward_Ret.field1=items。 */
    public byte[] firstChongZhiQueryRet(List<byte[]> items) {
        return Pb.write(out -> {
            if (items != null) {
                for (byte[] it : items) {
                    Pb.bytes(out, 1, it);
                }
            }
        });
    }

    /** S2C 2611 CCMsgQueryDailyChongZhiAwardInfo_Ret。field1=已领串  field2=各档。 */
    public byte[] dailyChongZhiRet(String curDayAwardedItems, List<byte[]> tiers) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, curDayAwardedItems == null ? "" : curDayAwardedItems);
            if (tiers != null) {
                for (byte[] t : tiers) {
                    Pb.bytes(out, 2, t);
                }
            }
        });
    }

    /** S2C 2612 CCMsgAwardDailyChongZhiAward_Ret。field1=领档 rmb，field2=更新后已领串。 */
    public byte[] dailyChongZhiAwardRet(int rmb, String curDayAwardedItems) {
        return Pb.write(out -> {
            Pb.int32(out, 1, rmb);
            Pb.stringAlways(out, 2, curDayAwardedItems == null ? "" : curDayAwardedItems);
        });
    }

    /** 魔盒格子 CCMsgMagicBoxPrize（field1 quality/2 type/3 goodsname/4 count/5 state/6 star）。 */
    public byte[] magicBoxPrize(int quality, int type, String goodsname, int count, int state, int star) {
        return Pb.write(out -> {
            Pb.int32(out, 1, quality);
            Pb.int32(out, 2, type);
            Pb.string(out, 3, goodsname);
            Pb.int32(out, 4, count);
            Pb.int32(out, 5, state);
            Pb.int32(out, 6, star);
        });
    }

    /** 魔盒中奖记录 CCMsgMagicBoxPrizeRecord（field1 quality/2 type/3 goodsname/4 count/5 state/6 playername/7 star）。 */
    public byte[] magicBoxRecord(PlayerRecord.MbRecord r, String playerName) {
        return Pb.write(out -> {
            Pb.int32(out, 1, r.quality);
            Pb.int32(out, 2, r.type);
            Pb.string(out, 3, r.goodsname);
            Pb.int32(out, 4, r.count);
            Pb.int32(out, 5, 1);
            Pb.string(out, 6, playerName == null ? "" : playerName);
            Pb.int32(out, 7, r.star);
        });
    }

    /** 魔盒位置映射 CCMsgMagicBoxCardPos（field1 CardPos 物理位/2 RealPos 奖品下标）。 */
    public byte[] magicBoxCardPos(int cardPos, int realPos) {
        return Pb.write(out -> {
            Pb.int32(out, 1, cardPos);
            Pb.int32(out, 2, realPos);
        });
    }

    /**
     * S2C 4901 CCMsgMagicBoxInfo（查询/重置回包）。
     * field1 CostRMB=钻石兜底  2 CostGoodsName  3 CostGoodsNum=本次魔瓶数  4 ResetCostRMB
     * 5 XiPaiState  6 prize 7 Record 8 CardPos。
     */
    public byte[] magicBoxInfo(PlayerRecord rec, String goodsOri, int costRmb, int costGoodsNum,
                               int resetCostRmb, int xiPaiState, List<byte[]> prize, List<byte[]> record,
                               List<byte[]> cardPos) {
        return Pb.write(out -> {
            Pb.int32(out, 1, costRmb);
            Pb.string(out, 2, goodsOri);
            Pb.int32(out, 3, costGoodsNum);
            Pb.int32(out, 4, resetCostRmb);
            Pb.int32(out, 5, xiPaiState);
            if (prize != null) {
                for (byte[] p : prize) {
                    Pb.bytes(out, 6, p);
                }
            }
            if (record != null) {
                for (byte[] r : record) {
                    Pb.bytes(out, 7, r);
                }
            }
            if (cardPos != null) {
                for (byte[] c : cardPos) {
                    Pb.bytes(out, 8, c);
                }
            }
        });
    }

    /** S2C 4902 CCMsgMagicBoxGivePrizeInfo（开奖回包）。field1 id=奖品下标 2 Pos 3 CostRMB 4 CostGoodsNum 5 Record。 */
    public byte[] magicBoxGivePrize(int id, int pos, int costRmb, int costGoodsNum, List<byte[]> record) {
        return Pb.write(out -> {
            Pb.int32(out, 1, id);
            Pb.int32(out, 2, pos);
            Pb.int32(out, 3, costRmb);
            Pb.int32(out, 4, costGoodsNum);
            if (record != null) {
                for (byte[] r : record) {
                    Pb.bytes(out, 5, r);
                }
            }
        });
    }

    /** S2C 4903 CCMsgMagicBoxXiPai_Ret。客户端不解析 body，只触发洗牌动画。 */
    public byte[] magicBoxXiPaiRet(int state) {
        return Pb.write(out -> Pb.int32Always(out, 1, state));
    }

    public byte[] buildingEmployers(List<byte[]> items) {
        return Pb.write(out -> {
            for (byte[] one : items) {
                Pb.bytes(out, 1, one);
            }
        });
    }

    /**
     * S2C 1915 的一条 COnePlayerInOneBuildingEmployerInfo。
     * f2/f3 是**并行 repeated**（wjIndex / wjEmployLeftCDTime），客户端按 wjIndex.Count 同时取两列
     * （MyBuildingWithYongBingItem_InYongBingMianBan.cs:141-188、UnionManagerSystem.cs:298-327），
     * 所以两列必须等长且不省略 0 → 一律 Always。
     */
    public byte[] buildingEmployer(int type, int baseProfit, int employProfit, List<WorldStore.Employer> list) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, type);
            if (list != null) {
                for (WorldStore.Employer e : list) {
                    Pb.int32Always(out, 2, e.wjIndex);
                }
                for (WorldStore.Employer e : list) {
                    Pb.int32Always(out, 3, cdLeftSec(e.cdEnd));
                }
            }
            Pb.int32Always(out, 4, baseProfit);
            Pb.int32Always(out, 5, employProfit);
        });
    }

    public byte[] timeStoneRet(String guid, String stoneId) {
        return Pb.write(out -> {
            Pb.string(out, 1, guid);
            Pb.string(out, 2, stoneId);
        });
    }

    public byte[] timeStoneCompose(boolean ok, String ori, int count) {
        return Pb.write(out -> {
            Pb.bool(out, 1, ok);
            Pb.string(out, 2, ori);
            Pb.int32(out, 3, count);
        });
    }

    public byte[] timeStoneColor(String name) {
        return Pb.write(out -> Pb.string(out, 1, name));
    }

    public byte[] kuangPage(List<byte[]> infos, int empty, int type, boolean rob, int page, boolean isKong) {
        return Pb.write(out -> {
            if (infos != null) {
                for (byte[] info : infos) {
                    Pb.bytes(out, 1, info);
                }
            }
            Pb.int32Always(out, 2, empty);
            Pb.int32(out, 3, type);
            Pb.bool(out, 4, rob);
            // Always：寻空全无时 PageNumber=-1（缺写会被读成 0）
            Pb.int32Always(out, 5, page);
            Pb.boolAlways(out, 6, isKong);
        });
    }

    /** 兼容旧调用。 */
    public byte[] kuangPage(List<byte[]> infos, int empty, int type, boolean rob, int page) {
        return kuangPage(infos, empty, type, rob, page, false);
    }

    public byte[] kuangBrief(int id, int holderId, int holderLv, String holderName, int leftSec,
                             int protectRobLeft, boolean fighting, int rebuildLeft, int coDefCnt) {
        return Pb.write(out -> {
            Pb.int32(out, 1, id);
            Pb.int32(out, 2, holderId);
            Pb.int32(out, 3, holderLv);
            Pb.string(out, 4, holderName == null ? "" : holderName);
            Pb.int32(out, 5, leftSec);
            Pb.int32Always(out, 6, protectRobLeft);
            Pb.boolAlways(out, 7, fighting);
            Pb.int32Always(out, 8, rebuildLeft);
            Pb.int32Always(out, 9, coDefCnt);
        });
    }

    /** S2C 2111 {@code CSelfKuangBriefInfo}：1 id 2 LeftSec 3 Name 4 coDefCnt。 */
    public byte[] kuangSelfBrief(int id, int leftSec, String holderName, int coDefCnt) {
        return Pb.write(out -> {
            Pb.int32(out, 1, id);
            Pb.int32Always(out, 2, leftSec);
            Pb.stringAlways(out, 3, holderName == null ? "" : holderName);
            Pb.int32Always(out, 4, coDefCnt);
        });
    }

    public byte[] kuangMyList(List<byte[]> selfBriefs) {
        return Pb.write(out -> {
            if (selfBriefs != null) {
                for (byte[] one : selfBriefs) {
                    Pb.bytes(out, 1, one);
                }
            }
        });
    }

    /** S2C 2119 {@code CCMsgRequestQiangKuangFightRecord_Ret}。 */
    public byte[] kuangFightRecords(List<PlayerRecord.QkFightRecord> records) {
        return Pb.write(out -> {
            if (records == null) {
                return;
            }
            for (PlayerRecord.QkFightRecord r : records) {
                if (r == null) {
                    continue;
                }
                Pb.bytes(out, 1, kuangFightRecordBrief(r));
            }
        });
    }

    public byte[] kuangFightRecordBrief(PlayerRecord.QkFightRecord r) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, r.attackerName == null ? "" : r.attackerName);
            Pb.stringAlways(out, 2, r.fightStartTime == null ? "" : r.fightStartTime);
            Pb.int32Always(out, 3, r.type);
            Pb.boolAlways(out, 4, r.isAttack);
            Pb.boolAlways(out, 5, r.isZhanLing);
            Pb.boolAlways(out, 6, r.isWin);
            Pb.boolAlways(out, 7, r.isCoDefense);
            Pb.int32Always(out, 8, r.jinBiLose);
            Pb.int32Always(out, 9, r.rmbLose);
        });
    }

    /**
     * S2C 2103 {@code CCMsgSelfKuangDetailInfo}。
     * 8=wujiangs；9=coDefenseFormationBriefInfo。
     */
    public byte[] kuangSelfDetail(int id, int holderGuid, String holderName,
                                  int cumRmb, int cumGold, int leftSec, int fightPower,
                                  PlayerRecord holder, List<byte[]> coDefBriefs) {
        return kuangSelfDetail(id, holderGuid, holderName, cumRmb, cumGold, leftSec, fightPower,
                holder, coDefBriefs, null);
    }

    public byte[] kuangSelfDetail(int id, int holderGuid, String holderName,
                                  int cumRmb, int cumGold, int leftSec, int fightPower,
                                  PlayerRecord holder, List<byte[]> coDefBriefs, List<String> defSlots) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, id);
            Pb.int32Always(out, 2, holderGuid);
            Pb.stringAlways(out, 3, holderName == null ? "" : holderName);
            Pb.int32Always(out, 4, cumRmb);
            Pb.int32Always(out, 5, cumGold);
            Pb.int32Always(out, 6, leftSec);
            Pb.int32Always(out, 7, fightPower);
            writeKuangDetailWjs(out, holder, defSlots);
            if (coDefBriefs != null) {
                for (byte[] one : coDefBriefs) {
                    Pb.bytes(out, 9, one);
                }
            }
        });
    }

    /**
     * S2C 2104 {@code CCMsgNotSelfKuangDetailInfo}：2/3=可掠 RMB/金；5=HolderName；9=协防。
     */
    public byte[] kuangOtherDetail(int id, int canRobRmb, int canRobGold, String unionName,
                                   String holderName, int leftSec, int fightPower,
                                   PlayerRecord holder, List<byte[]> coDefBriefs) {
        return kuangOtherDetail(id, canRobRmb, canRobGold, unionName, holderName, leftSec, fightPower,
                holder, coDefBriefs, null);
    }

    public byte[] kuangOtherDetail(int id, int canRobRmb, int canRobGold, String unionName,
                                   String holderName, int leftSec, int fightPower,
                                   PlayerRecord holder, List<byte[]> coDefBriefs, List<String> defSlots) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, id);
            Pb.int32Always(out, 2, canRobRmb);
            Pb.int32Always(out, 3, canRobGold);
            Pb.stringAlways(out, 4, unionName == null ? "" : unionName);
            Pb.stringAlways(out, 5, holderName == null ? "" : holderName);
            Pb.int32Always(out, 6, leftSec);
            Pb.int32Always(out, 7, fightPower);
            writeKuangDetailWjs(out, holder, defSlots);
            if (coDefBriefs != null) {
                for (byte[] one : coDefBriefs) {
                    Pb.bytes(out, 9, one);
                }
            }
        });
    }

    /** {@code CMsgCoDefenseFormationBriefInfo}：1 name 2 FP 3 wujiangs Job+Brief。 */
    public byte[] kuangCoDefBrief(String name, int fightPower, PlayerRecord co, List<String> slots) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, name == null ? "" : name);
            Pb.int32Always(out, 2, fightPower);
            if (co != null && slots != null) {
                for (int job = 1; job <= 5; job++) {
                    String guid = slots.size() >= job ? slots.get(job - 1) : "";
                    PlayerRecord.Hero h = co.findHero(guid);
                    if (h == null) {
                        continue;
                    }
                    Pb.bytes(out, 3, kuangJobAndBrief(co, job, h));
                }
            }
        });
    }

    /**
     * S2C 2121 {@code CCMsgQKCoWJs}：平行数组 WJGuid/job/KuangID。
     * 客户端 PlayGameState.cs:6182-6188 以 WJGuid.Count 为界同下标取 KuangID[i]/job[i]，
     * 三列都必须用 Always（protobuf-net 写 repeated 元素不省略默认值），否则空 guid 或 kid==0 会错位/越界。
     */
    public byte[] kuangMyCoWjs(List<String> guids, List<Integer> jobs, List<Integer> kuangIds) {
        return Pb.write(out -> {
            if (guids == null) {
                return;
            }
            for (int i = 0; i < guids.size(); i++) {
                Pb.stringAlways(out, 1, guids.get(i));
                int job = (jobs != null && i < jobs.size() && jobs.get(i) != null) ? jobs.get(i).intValue() : (i + 1);
                Pb.int32Always(out, 2, job);
                int kid = (kuangIds != null && i < kuangIds.size() && kuangIds.get(i) != null)
                        ? kuangIds.get(i).intValue() : 0;
                Pb.int32Always(out, 3, kid);
            }
        });
    }

    /** S2C 2127 {@code CCMsgUpdateCoDefenseFormation_Ret}：0=成功无 tip；1/2/3=失败 tip。 */
    public byte[] kuangCoDefRet(int retType) {
        return Pb.write(out -> Pb.int32Always(out, 1, retType));
    }

    /** S2C 2117 {@code CCMsgUpdateKuangAttacker}。 */
    public byte[] kuangAttackerCnt(int cnt) {
        return Pb.write(out -> Pb.int32Always(out, 1, cnt));
    }

    /** S2C 2106 {@code CCMsgNotifyKuangEarnings}：1 KuangID 2 RMB 3 JinBi。 */
    public byte[] kuangEarnings(int kuangId, int rmb, int jinbi) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, kuangId);
            Pb.int32Always(out, 2, rmb);
            Pb.int32Always(out, 3, jinbi);
        });
    }

    /** S2C 2129 {@code CCMsgEnemyKuangsBriefInfo_Ret}。 */
    public byte[] kuangEnemyList(List<byte[]> briefs) {
        return Pb.write(out -> {
            if (briefs == null) {
                return;
            }
            for (byte[] one : briefs) {
                Pb.bytes(out, 1, one);
            }
        });
    }

    /** {@code CEnemyKuangBriefInfo}。 */
    public byte[] kuangEnemyBrief(int id, int leftSec, String holderName, int coDefCnt,
                                  int holderGuid, int protectLeft, boolean fighting,
                                  int rebuildLeft, int holderLv) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, id);
            Pb.int32Always(out, 2, leftSec);
            Pb.stringAlways(out, 3, holderName == null ? "" : holderName);
            Pb.int32Always(out, 4, coDefCnt);
            Pb.int32Always(out, 5, holderGuid);
            Pb.int32Always(out, 6, protectLeft);
            Pb.boolAlways(out, 7, fighting);
            Pb.int32Always(out, 8, rebuildLeft);
            Pb.int32Always(out, 9, holderLv);
        });
    }

    /** S2C 2102 死亡武将：平行 wjIndex / reliveLeftTime。 */
    public byte[] kuangDeadWjs(List<Integer> indexes, List<Integer> leftSecs) {
        return Pb.write(out -> {
            if (indexes == null) {
                return;
            }
            for (int i = 0; i < indexes.size(); i++) {
                Pb.int32(out, 1, indexes.get(i) == null ? 0 : indexes.get(i).intValue());
            }
            if (leftSecs == null) {
                return;
            }
            for (int i = 0; i < leftSecs.size(); i++) {
                Pb.int32(out, 2, leftSecs.get(i) == null ? 0 : leftSecs.get(i).intValue());
            }
        });
    }

    /** S2C 2120 战报详情：多回合 + 双方头像等级 + Hurt。 */
    public byte[] kuangFightRecordDetail(PlayerRecord.QkFightRecord brief, PlayerRecord self) {
        return Pb.write(out -> {
            if (brief == null) {
                return;
            }
            List<PlayerRecord.QkFightRound> rounds = brief.rounds;
            if (rounds == null || rounds.isEmpty()) {
                Pb.bytes(out, 1, kuangFightRound(brief, self, null));
                return;
            }
            for (PlayerRecord.QkFightRound r : rounds) {
                Pb.bytes(out, 1, kuangFightRound(brief, self, r));
            }
        });
    }

    private byte[] kuangFightRound(PlayerRecord.QkFightRecord brief, PlayerRecord self,
                                   PlayerRecord.QkFightRound round) {
        String selfName = self != null && self.roleName != null ? self.roleName : "";
        String atk = brief.attackerName == null ? "" : brief.attackerName;
        String foe = brief.foeName == null || brief.foeName.isEmpty() ? atk : brief.foeName;
        boolean selfIsAtk = brief.isAttack;
        final boolean roundWin;
        if (round != null) {
            // 回合包 isWin 是攻方上报；防守方存档要取反
            roundWin = selfIsAtk ? round.isWin : !round.isWin;
        } else {
            roundWin = brief.isWin;
        }
        int selfRes = self != null ? self.mainHeroIndex : 0;
        int selfLv = self != null ? self.level : 0;
        int foeRes = brief.foeResId;
        int foeLv = brief.foeLevel;
        String leftName = selfIsAtk ? selfName : foe;
        int leftRes = selfIsAtk ? selfRes : foeRes;
        int leftLv = selfIsAtk ? selfLv : foeLv;
        String rightName = selfIsAtk ? foe : selfName;
        int rightRes = selfIsAtk ? foeRes : selfRes;
        int rightLv = selfIsAtk ? foeLv : selfLv;
        List<PlayerRecord.QkFightHurt> leftHurts = round == null ? null
                : (selfIsAtk ? round.selfHurts : round.targetHurts);
        List<PlayerRecord.QkFightHurt> rightHurts = round == null ? null
                : (selfIsAtk ? round.targetHurts : round.selfHurts);
        return Pb.write(out -> {
            Pb.boolAlways(out, 1, roundWin);
            Pb.stringAlways(out, 2, leftName);
            Pb.int32Always(out, 3, leftRes);
            Pb.int32Always(out, 4, leftLv);
            if (leftHurts != null) {
                for (PlayerRecord.QkFightHurt h : leftHurts) {
                    if (h != null) {
                        Pb.bytesAlways(out, 5, kuangFightHurt(h));
                    }
                }
            }
            Pb.stringAlways(out, 6, rightName);
            Pb.int32Always(out, 7, rightRes);
            Pb.int32Always(out, 8, rightLv);
            if (rightHurts != null) {
                for (PlayerRecord.QkFightHurt h : rightHurts) {
                    if (h != null) {
                        Pb.bytesAlways(out, 9, kuangFightHurt(h));
                    }
                }
            }
        });
    }

    private byte[] kuangFightHurt(PlayerRecord.QkFightHurt h) {
        return Pb.write(out -> {
            Pb.bytesAlways(out, 1, Pb.write(brief -> {
                Pb.int32Always(brief, 1, h.wjIndex);
                Pb.int32Always(brief, 2, h.level);
                Pb.int32Always(brief, 3, h.stage);
                Pb.int32Always(brief, 4, h.stars);
            }));
            Pb.int32Always(out, 2, h.hurts);
        });
    }

    /**
     * S2C 801 私聊/公会聊一条，Text 可带协防超链 {@code [url=1:kuangId_holderId]...}。
     * type：1世界 2公会 3私聊 4DEBUG。
     * <p>⚠️ tag 8/9（SrcServerID / TarServerID）曾经漏写，导致客户端
     * {@code ChatSystem.cs:645} 把 {@code serverId == 0} 一律判成「跨服」，
     * 再被 {@code :691-694 if (!mIsOpenKFChat) return;} 拦掉 ⇒ 系统播报一条都进不了世界频道。
     * 现在由 {@link #localServerId()} 填本服号。</p>
     */
    public byte[] chatToCli(int type, String name, int resId, int level, String text,
                            String time, String unionName, int senderGuid, int targetGuid,
                            String targetName) {
        return chatToCli(type, name, resId, level, text, time, unionName, senderGuid, targetGuid,
                targetName, localServerId(), 0);
    }

    /**
     * S2C 801（显式指定 8 SrcServerID / 9 TarServerID）。
     * <p>私聊把 {@code tarServerId} 填对方服号；本服聊天填 {@code 0} 即可
     * （客户端 {@code ChatSystem.cs:645} 判本服只看 SrcServerID）。</p>
     */
    public byte[] chatToCli(int type, String name, int resId, int level, String text,
                            String time, String unionName, int senderGuid, int targetGuid,
                            String targetName, int srcServerId, int tarServerId) {
        return Pb.write(out -> Pb.bytes(out, 1,
                chatRecordToCli(type, name, resId, level, text, time, unionName, senderGuid,
                        targetGuid, targetName, srcServerId, tarServerId)));
    }

    /**
     * {@code CChatRecordToCli}（801 的 tag1 元素）完整 12 字段。
     * <p>字段号来自 {@code pyfoot\tmp_msgdll\NetProto\CCMsgChatToCli.cs}
     * 的 {@code CChatRecordToCli}：1 type、2 Name、3 ResId、4 Level、5 Text、6 Time、
     * 7 UnionName、8 SrcServerID、9 TarServerID、10 TargetName、11 senderGUID、12 targetGuid。</p>
     */
    public byte[] chatRecordToCli(int type, String name, int resId, int level, String text,
                                  String time, String unionName, int senderGuid, int targetGuid,
                                  String targetName, int srcServerId, int tarServerId) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, type);
            Pb.stringAlways(out, 2, name == null ? "" : name);
            Pb.int32Always(out, 3, resId);
            Pb.int32Always(out, 4, level);
            Pb.stringAlways(out, 5, text == null ? "" : text);
            Pb.stringAlways(out, 6, time == null ? "" : time);
            Pb.stringAlways(out, 7, unionName == null ? "" : unionName);
            Pb.int32Always(out, 8, srcServerId);
            Pb.int32Always(out, 9, tarServerId);
            Pb.stringAlways(out, 10, targetName == null ? "" : targetName);
            Pb.int32Always(out, 11, senderGuid);
            Pb.int32Always(out, 12, targetGuid);
        });
    }

    /**
     * S2C 801（C2S 501 的开面板历史回包）：1 repeated CChatRecordToCli。
     * <p>客户端 {@code ChatSystem.cs:898} 对空列表直接 return ⇒ 假服至少要回一个空数组包，
     * 否则历史区永远空白（本地缓存也不会有内容）。</p>
     */
    public byte[] chatHistory(List<byte[]> records) {
        return Pb.write(out -> {
            if (records != null) {
                for (byte[] r : records) {
                    Pb.bytes(out, 1, r);
                }
            }
        });
    }

    /** S2C 802 {@code CCMsgUpdateBlackList}：1 IsAdd、2 CPlayerGuidAndNameAndResIDAndLevel。 */
    public byte[] updateBlackList(boolean isAdd, int guid, String name, int resId, int level,
                                  int serverId) {
        return Pb.write(out -> {
            Pb.boolAlways(out, 1, isAdd);
            Pb.bytesAlways(out, 2, blackPlayer(guid, name, resId, level, serverId));
        });
    }

    /**
     * {@code CPlayerGuidAndNameAndResIDAndLevel}（802 tag2 / 503 / 504 请求体同构）：
     * 1 Guid、2 Name、3 ResID、4 Level、5 serverID。
     * <p>客户端 {@code BlackList.cs:199-208} 用 {@code Guid + serverID} 双键判等删改
     * ⇒ 这两个字段必须原样回显请求里的值，不能自己编。</p>
     */
    public byte[] blackPlayer(int guid, String name, int resId, int level, int serverId) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, guid);
            Pb.stringAlways(out, 2, name == null ? "" : name);
            Pb.int32Always(out, 3, resId);
            Pb.int32Always(out, 4, level);
            Pb.int32Always(out, 5, serverId);
        });
    }

    /**
     * 本服 serverId（{@code application.yml} 的 {@code sao.server-id}）。
     * <p>客户端拿它和 {@code ServerInfo.CurChoiceServerInfo.ServerId} 比对判跨服；
     * 返回 0 会被 {@code ChatSystem.cs:645} 判成跨服而丢弃。</p>
     */
    public int localServerId() {
        try {
            String s = props == null ? null : props.getServerId();
            return s == null || s.trim().isEmpty() ? 1 : Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 1;
        }
    }

    public byte[] kuangFightTeams(PlayerRecord self, PlayerRecord foe) {
        return kuangFightTeams(self, foe, null);
    }

    /** S2C 2107 空队：客户端弹 100413 并退出矿战（拒战可感知）。 */
    public byte[] kuangFightTeamsEmpty() {
        return new byte[0];
    }

    /**
     * S2C 2107。foeSlots 非空时敌方用该 5 槽（矿防阵/协防阵），否则用账号 type3 防阵。
     */
    public byte[] kuangFightTeams(PlayerRecord self, PlayerRecord foe, List<String> foeSlots) {
        byte[] my = kuangTeamDetail(self, self.playerId, PlayerRecord.FORMATION_QIANGKUANG_ATK, true);
        byte[] target = foeSlots != null
                ? kuangTeamDetailSlots(foe, foe.playerId, foeSlots, true)
                : kuangTeamDetail(foe, foe.playerId, PlayerRecord.FORMATION_QIANGKUANG_DEF, true);
        return Pb.write(out -> {
            Pb.bytesAlways(out, 1, my);
            Pb.bytesAlways(out, 2, target);
        });
    }

    private void writeKuangDetailWjs(com.google.protobuf.CodedOutputStream out, PlayerRecord holder,
                                     List<String> defSlots) throws java.io.IOException {
        if (holder == null) {
            return;
        }
        List<String> slots = defSlots;
        if (slots == null || slots.isEmpty()) {
            slots = holder.formationSlots(PlayerRecord.FORMATION_QIANGKUANG_DEF);
        }
        PlayerRecord.Hero pad = holder.heroes.isEmpty() ? null : holder.heroes.get(0);
        for (int job = 1; job <= 5; job++) {
            String guid = slots.size() >= job ? slots.get(job - 1) : "";
            PlayerRecord.Hero wj = holder.findHero(guid);
            if (wj == null) {
                wj = pad;
            }
            if (wj == null) {
                continue;
            }
            final PlayerRecord.Hero hero = wj;
            final int j = job;
            Pb.bytesAlways(out, 8, Pb.write(inner -> {
                Pb.int32Always(inner, 1, j);
                Pb.bytesAlways(inner, 2, Pb.write(brief -> {
                    Pb.int32Always(brief, 1, hero.heroIndex);
                    Pb.int32Always(brief, 2, hero.level);
                    Pb.int32Always(brief, 3, hero.stage);
                    Pb.int32Always(brief, 4, hero.stars);
                }));
            }));
        }
    }

    /** S2C 2112 {@code CCMsgMyQiangKuangDefAllWJ}：各矿独立 DefWJ{KuangID,job,WJGuid}。 */
    public byte[] kuangDefAllWj(List<WorldStore.MineSlot> ownedMines, PlayerRecord rec) {
        return Pb.write(out -> {
            if (ownedMines == null || rec == null) {
                return;
            }
            for (WorldStore.MineSlot slot : ownedMines) {
                if (slot == null || slot.id <= 0) {
                    continue;
                }
                List<String> slots = slot.defWj;
                if (slots == null || slots.isEmpty()) {
                    slots = rec.formationSlots(PlayerRecord.FORMATION_QIANGKUANG_DEF);
                }
                for (int job = 1; job <= 5; job++) {
                    String guid = slots.size() >= job ? slots.get(job - 1) : "";
                    if (guid == null || guid.isEmpty()) {
                        continue;
                    }
                    final int kid = slot.id;
                    final int j = job;
                    final String g = guid;
                    Pb.bytesAlways(out, 1, Pb.write(one -> {
                        Pb.int32Always(one, 1, kid);
                        Pb.int32Always(one, 2, j);
                        Pb.stringAlways(one, 3, g);
                    }));
                }
            }
        });
    }

    /** @deprecated 单矿兼容；请用 {@link #kuangDefAllWj(List, PlayerRecord)}。 */
    public byte[] kuangDefAllWj(int kuangId, PlayerRecord rec) {
        if (rec == null || kuangId <= 0) {
            return kuangDefAllWj(null, null);
        }
        WorldStore.MineSlot fake = new WorldStore.MineSlot();
        fake.id = kuangId;
        fake.defWj = new ArrayList<>(rec.formationSlots(PlayerRecord.FORMATION_QIANGKUANG_DEF));
        List<WorldStore.MineSlot> one = new ArrayList<>();
        one.add(fake);
        return kuangDefAllWj(one, rec);
    }

    public byte[] kuangEmptyCnt(int type, int cnt) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, type);
            Pb.int32Always(out, 2, cnt);
        });
    }

    public byte[] kuangHolderId(int holderGuid) {
        return Pb.write(out -> Pb.int32Always(out, 1, holderGuid));
    }

    public byte[] kuangHoldRet(boolean ok) {
        return kuangHoldRet(ok, "");
    }

    public byte[] kuangHoldRet(boolean ok, String failInfo) {
        return Pb.write(out -> {
            Pb.bool(out, 1, ok);
            if (failInfo != null && !failInfo.isEmpty()) {
                Pb.string(out, 2, failInfo);
            }
        });
    }

    public byte[] kuangResource(int gold, int diamond) {
        return Pb.write(out -> {
            Pb.string(out, 1, "矿洞产出");
            Pb.int32(out, 2, gold);
            Pb.int32(out, 3, diamond);
        });
    }

    public byte[] auctionList(List<byte[]> items) {
        return Pb.write(out -> {
            if (items != null) {
                for (byte[] item : items) {
                    Pb.bytes(out, 1, item);
                }
            }
        });
    }

    public byte[] auctionItem(int dropId, String ori, int count, String player, int yqb) {
        return Pb.write(out -> {
            Pb.int32(out, 1, dropId);
            Pb.string(out, 2, ori);
            Pb.int32(out, 3, count);
            Pb.string(out, 4, player);
            Pb.int32(out, 5, yqb);
        });
    }

    /**
     * S2C 1931 CCMsgRequestYiYuanDeadWJInfo_Ret{1 CureType,2 repeated CMsgYiYuanDeadWJInfo}。
     * 子消息 f3 {@code IsEmergencyTreatme} = 该病人是否已被医师治疗过（{@link PlayerRecord.QkDeadWj#emergencyTreated}）：
     * 客户端 {@code BingRen.cs:60-71} 据此禁用「资料」按钮 ⇒ 改前恒 false 时同一病人可被反复治疗、反复扣金币。
     * 病人列表来源就是本地阵亡武将 PlayerRecord.qkDeadWjs（MineService 死亡时写入），
     * ReliveLeftSeconds 由 reliveUntilMs 推。
     */
    public byte[] hospital(int cureType, PlayerRecord rec) {
        List<PlayerRecord.QkDeadWj> dead = rec == null || rec.qkDeadWjs == null
                ? java.util.Collections.<PlayerRecord.QkDeadWj>emptyList() : rec.qkDeadWjs;
        long now = System.currentTimeMillis();
        return Pb.write(out -> {
            Pb.int32Always(out, 1, cureType);
            for (PlayerRecord.QkDeadWj d : dead) {
                if (d == null || d.wjIndex <= 0) {
                    continue;
                }
                PlayerRecord.Hero h = rec.findHeroByIndex(d.wjIndex);
                int left = (int) Math.max(0L, (d.reliveUntilMs - now) / 1000L);
                Pb.bytesAlways(out, 2, Pb.write(o -> {
                    Pb.bytesAlways(o, 1, wjBrief(d.wjIndex,
                            h == null ? 1 : h.level, h == null ? 0 : h.stage, h == null ? 0 : h.stars));
                    Pb.int32Always(o, 2, left);
                    Pb.boolAlways(o, 3, d.emergencyTreated);
                }));
            }
        });
    }

    public byte[] kitchenRet() {
        return new byte[0];
    }

    /** 1934 的一个训练坑位：CXunLianWJInfo。 */
    public static class TrainSlot {
        public int wjIndex;
        public int totalExp;
        public int trainingType;
        public int leftTime;
        public int employerWjIndex;
        public int employerWjLevel;
        public int employerWjStage;
        public int employerWjStars;
        public float employerRatio;
    }

    /**
     * S2C 1934 CCMsgRequestUnionXunLianChangWJInfo_Ret{1 repeated CXunLianWJInfo}。
     * 必须**按坑位顺序**发（UnionTrainRoomInfo.cs:27-47 用 listTrainInfo[j] 取第 j 坑），空坑 wjIndex=0。
     * wjIndex!=0 时客户端无条件解引用 f5 employerWJInfo.index（UnionTrainRoomInfo.cs:40）→ f5 必须存在。
     */
    public byte[] trainInfo(List<TrainSlot> slots) {
        return Pb.write(out -> {
            if (slots == null) {
                return;
            }
            for (TrainSlot s : slots) {
                Pb.bytesAlways(out, 1, Pb.write(o -> {
                    Pb.int32Always(o, 1, s.wjIndex);
                    Pb.int32Always(o, 2, s.totalExp);
                    Pb.int32Always(o, 3, s.trainingType);
                    Pb.int32Always(o, 4, s.leftTime);
                    Pb.bytesAlways(o, 5, wjBrief(s.employerWjIndex, s.employerWjLevel,
                            s.employerWjStage, s.employerWjStars));
                    Pb.float32Always(o, 6, s.employerRatio);
                }));
            }
        });
    }

    public byte[] trainStart(int wjIndex) {
        return Pb.write(out -> Pb.int32Always(out, 1, wjIndex));
    }

    /**
     * S2C 1935 的一条训练完成结果：{@code CWJXunLianResult}
     * {1 wjIndex,2 wjPreLevel,3 curLevel,4 totalExp}。
     */
    public static class TrainResult {
        public final int wjIndex;
        public final int preLevel;
        public final int curLevel;
        public final int totalExp;

        public TrainResult(int wjIndex, int preLevel, int curLevel, int totalExp) {
            this.wjIndex = wjIndex;
            this.preLevel = preLevel;
            this.curLevel = curLevel;
            this.totalExp = totalExp;
        }
    }

    /**
     * S2C 1935 {@code CCMsgNotifyWJXunLiangFinishResult}：**f1 repeated {@code CWJXunLianResult}**
     * + f2 repeated {@code CMsgGoods expGoods}。多坑可同时到点 ⇒ 一次推多条结果（客户端
     * {@code UnionTrainFinish.cs} 按 {@code wjXLResult} 列表逐条渲染）。
     */
    public byte[] trainFinish(List<TrainResult> results) {
        return Pb.write(out -> {
            if (results == null) {
                return;
            }
            for (TrainResult r : results) {
                Pb.bytesAlways(out, 1, Pb.write(o -> {
                    Pb.int32Always(o, 1, r.wjIndex);
                    Pb.int32Always(o, 2, r.preLevel);
                    Pb.int32Always(o, 3, r.curLevel);
                    Pb.int32Always(o, 4, r.totalExp);
                }));
            }
        });
    }

    /**
     * S2C 1935 {@code CCMsgNotifyWJXunLiangFinishResult}：**f1 repeated {@code CWJXunLianResult}
     * {1 wjIndex,2 wjPreLevel,3 curLevel,4 totalExp}** + f2 repeated {@code CMsgGoods expGoods}。
     *
     * <p>改前这里直接复用 1934 的 {@link #trainInfo(List)} 形体发出去 —— 客户端
     * {@code UnionTrainFinish.cs:74-117} 按 {@code wjPreLevel/curLevel/totalExp} 三个字段渲染
     * 训练完成面板，收到 1934 形体就会把 TotalExp 读成等级、TrainingType 读成当前等级，
     * 面板等级/经验全错。{@code expGoods} 留空：经验已经直接加到武将身上，真服是否有
     * 「经验道具」附件无表可依（见 docs 待拍服数据项）。
     */
    public byte[] trainFinish(int wjIndex, int preLevel, int curLevel, int totalExp) {
        return trainFinish(java.util.Collections.singletonList(
                new TrainResult(wjIndex, preLevel, curLevel, totalExp)));
    }

    /**
     * S2C 1938 CCMsgUnionBiaoCheInfo（1543 的回包）：**只放刚发的那一辆车**。
     * 客户端 `MaJiuBasePaiQianUI.cs:122-127` 无条件取 `biaoCheInfo[0]` 当作「本次新发的车」
     * 追加进 `myBiaoCheInfo` / `GetBiaoCheInfo()`，所以 index 0 必须是新车；
     * 完整在途列表由 1936/1942 的 f7 快照负责。
     */
    public byte[] escortSendRet(PlayerRecord rec, PlayerRecord.Economy.EscortCart cart) {
        if (cart == null) {
            return new byte[0];
        }
        List<byte[]> one = new ArrayList<>();
        one.add(playerBiaoChe(cart.cartId, cart.targetId, rec, cart.beRaidCnt));
        return escortCarts(one);
    }

    /**
     * S2C 1939 CCMsgRequestRaidABiaoCheRet。
     * 客户端只在 ret==0 时 UnPackData(beRaidFormation) 进战斗（PlayGameState.cs:5962-5979），
     * 所以成功路径必须回 ret=0 并把被劫方阵容放在 f4（CCMsgJJCRealFightTargetDetailInfo）。
     * f3 {@code msgUpdate}（CCMsgRaidBiaoCheInfo）= 本次拦截后这辆车的状态行，用于刷新
     * 「掠夺中 / 已被打劫」标记；ret 分档语义（4/3/2/1）见 {@code onRaidCart}。
     */
    public byte[] escortRaidRet(int ret, int myRaidSucTime, byte[] msgUpdate, byte[] beRaidFormation) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, ret);
            Pb.int32Always(out, 2, myRaidSucTime);
            if (msgUpdate != null && msgUpdate.length > 0) {
                Pb.bytesAlways(out, 3, msgUpdate);
            }
            if (beRaidFormation != null) {
                Pb.bytesAlways(out, 4, beRaidFormation);
            }
            // f5 needServerCheck=false：假服务端信任客户端上报的 1545 战斗结果
            // （客户端 JieBiaoController.cs:1269-1280 / MainSuspendSystem.cs:271-277 都会发），
            // 置 true 会让客户端等一个本服务端不做的服务端算。
            Pb.boolAlways(out, 5, false);
        });
    }

    public byte[] escortRaidResult(int xdb, int crystal, int gold) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, xdb);
            Pb.int32Always(out, 2, crystal);
            Pb.int32Always(out, 3, gold);
        });
    }

    /** S2C 1941 CCMsgUnionRaidRankList{1 repeated CCMsgUnionRaidRankListItem}。 */
    public byte[] escortRaidRank(List<byte[]> rows) {
        return Pb.write(out -> {
            if (rows != null) {
                for (byte[] r : rows) {
                    Pb.bytesAlways(out, 1, r);
                }
            }
        });
    }

    public byte[] raidRankRow(int playerGuid, String name, int headIndex, int level,
                              int raidXdb, int raidJinShi, int raidJinbi) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, playerGuid);
            Pb.stringAlways(out, 2, name == null ? "" : name);
            Pb.int32Always(out, 3, headIndex);
            Pb.int32Always(out, 4, level);
            Pb.int32Always(out, 5, raidXdb);
            Pb.int32Always(out, 6, raidJinShi);
            Pb.int32Always(out, 7, raidJinbi);
        });
    }

    /**
     * S2C 1943 CCMsgRequestGetYunBiaoAwardRet。
     * f1 raidRank 决定整行是否被 DestroyImmediate（MaJiuGetAwardUI.cs:89-92/142），必须给真实名次；
     * f4 raidJinShi = 我方劫镖晶石合计（改前漏写，客户端本版本不消费但属字段缺漏）。
     */
    public byte[] escortAward(int raidRank, int raidJinBi, int raidXdb, int raidJinShi,
                              List<byte[]> awardItems, List<byte[]> defenceAwards) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, raidRank);
            Pb.int32Always(out, 2, raidJinBi);
            Pb.int32Always(out, 3, raidXdb);
            Pb.int32Always(out, 4, raidJinShi);
            if (awardItems != null) {
                for (byte[] it : awardItems) {
                    Pb.bytesAlways(out, 5, it);
                }
            }
            if (defenceAwards != null) {
                for (byte[] d : defenceAwards) {
                    Pb.bytesAlways(out, 6, d);
                }
            }
        });
    }

    /** CCMsgYunBiaoAwardItem{1 targeID,2 beRaidCnt,3 jinBi,4 XDB,5 jinShi}。 */
    public byte[] yunBiaoAwardItem(int targetId, int beRaidCnt, int jinBi, int xdb, int jinShi) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, targetId);
            Pb.int32Always(out, 2, beRaidCnt);
            Pb.int32Always(out, 3, jinBi);
            Pb.int32Always(out, 4, xdb);
            Pb.int32Always(out, 5, jinShi);
        });
    }

    /** CCMsgDefenceAward{1 defendedUnion,2 jinBi}。 */
    public byte[] defenceAward(String unionName, int jinBi) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, unionName == null ? "" : unionName);
            Pb.int32Always(out, 2, jinBi);
        });
    }

    public byte[] cloneBase(PlayerRecord rec, PlayerRecord partner, boolean inRoom, boolean isCliReq, int bossId,
                            boolean allowQuickJoin, int[] bosses) {
        PlayerRecord.Hero me = rec.heroes.isEmpty() ? null : rec.heroes.get(0);
        List<String> cloneSlots = rec.formationSlots(PlayerRecord.FORMATION_CLONE_ATK);
        if (!cloneSlots.isEmpty()) {
            PlayerRecord.Hero f = rec.findHero(cloneSlots.get(0));
            if (f != null) {
                me = f;
            }
        }
        PlayerRecord.Hero self = me;
        int shownBoss = bossId > 0 ? bossId : 18;
        return Pb.write(out -> {
            if (inRoom) {
                Pb.int32(out, 1, shownBoss);
            } else if (bosses != null) {
                for (int b : bosses) {
                    Pb.int32(out, 1, b);
                }
            }
            Pb.bool(out, 2, inRoom);
            Pb.bool(out, 3, allowQuickJoin);
            if (inRoom) {
                Pb.bytes(out, 4, cloneMember(rec.playerId, rec.roleName, 0, self));
                if (partner != null && partner.playerId != rec.playerId) {
                    PlayerRecord.Hero ph = partnerHero(partner);
                    Pb.bytes(out, 4, cloneMember(partner.playerId, partner.roleName, 0, ph));
                }
            }
            Pb.bool(out, 6, isCliReq);
            // 产品：主奖=随机武将碎片+精炼；金/钻预览置 0；房内 goods 用已 lock 的碎片
            Pb.int32Always(out, 7, 0);
            Pb.int32Always(out, 8, 0);
            int previewBoss = inRoom ? shownBoss : (bosses != null && bosses.length > 0 ? bosses[0] : 18);
            String previewFrag = inRoom && rec.clone != null ? rec.clone.rewardFragOri : null;
            for (GameTables.GoodsDrop g : CloneService.winDrops(previewBoss, previewFrag)) {
                if (g == null || g.ori == null) {
                    continue;
                }
                Pb.bytes(out, 9, cloneGoods(g.ori));
            }
        });
    }

    private byte[] cloneGoods(String ori) {
        return Pb.write(out -> Pb.string(out, 1, ori == null ? "" : ori));
    }

    private static PlayerRecord.Hero partnerHero(PlayerRecord partner) {
        if (partner == null) {
            return null;
        }
        partner.ensureCollections();
        List<String> slots = partner.formationSlots(PlayerRecord.FORMATION_CLONE_ATK);
        if (!slots.isEmpty()) {
            PlayerRecord.Hero f = partner.findHero(slots.get(0));
            if (f != null) {
                return f;
            }
        }
        return partner.heroes.isEmpty() ? null : partner.heroes.get(0);
    }

    public byte[] cloneMember(int guid, String name, int status, PlayerRecord.Hero wj) {
        byte[] brief = Pb.write(out -> {
            if (wj != null) {
                Pb.int32(out, 1, wj.heroIndex);
                Pb.int32(out, 2, wj.level);
                Pb.int32(out, 3, wj.stage);
                Pb.int32(out, 4, wj.stars);
            } else {
                Pb.int32(out, 1, 18);
                Pb.int32(out, 2, 10);
            }
        });
        return Pb.write(out -> {
            Pb.int32(out, 1, guid);
            Pb.string(out, 2, name == null ? "" : name);
            Pb.int32(out, 3, status);
            Pb.bytes(out, 4, brief);
        });
    }

    public byte[] cloneFightTeams(PlayerRecord rec, int bossIndex) {
        List<byte[]> mine = new ArrayList<>();
        List<byte[]> enemy = new ArrayList<>();
        PlayerRecord.Hero pad = rec.heroes.isEmpty() ? null : rec.heroes.get(0);
        List<String> cloneForm = rec.formationSlots(PlayerRecord.FORMATION_CLONE_ATK);
        for (int job = 1; job <= 5; job++) {
            String guid = "";
            if (cloneForm.size() >= job) {
                guid = cloneForm.get(job - 1);
            }
            PlayerRecord.Hero wj = rec.findHero(guid);
            if (wj == null) {
                wj = pad;
            }
            if (wj == null) {
                continue;
            }
            mine.add(cloneFighter(rec, rec.playerId, job, wj, wj.id));
        }
        int boss = bossIndex > 0 ? bossIndex : 18;
        for (int job = 1; job <= 5; job++) {
            // guid 按槽错开：与 beginCloneBattle / GetCloneZhanWuJiangByIndex 一致
            enemy.add(cloneFighter(null, FightSyncService.CLONE_BOSS_GUID + (job - 1), job,
                    cloneBossHero(boss), "clone-boss-" + job));
        }
        return Pb.write(out -> {
            for (byte[] one : mine) {
                Pb.bytes(out, 1, one);
            }
            for (byte[] one : enemy) {
                Pb.bytes(out, 2, one);
            }
        });
    }

    public byte[] cloneRoomTeam(PlayerRecord rec) {
        return Pb.write(out -> {
            PlayerRecord.Hero pad = rec.heroes.isEmpty() ? null : rec.heroes.get(0);
            List<String> form = rec.formationSlots(PlayerRecord.FORMATION_CLONE_ATK);
            for (int job = 1; job <= 5; job++) {
                String guid = "";
                if (form.size() >= job) {
                    guid = form.get(job - 1);
                }
                PlayerRecord.Hero wj = rec.findHero(guid);
                if (wj == null) {
                    wj = pad;
                }
                if (wj == null) {
                    continue;
                }
                Pb.bytes(out, 1, cloneFighter(rec, rec.playerId, job, wj, wj.id));
            }
        });
    }

    public byte[] cloneResult(int result) {
        return Pb.write(out -> Pb.int32(out, 1, result));
    }

    public byte[] cloneQuickJoinAllow(boolean allow) {
        return Pb.write(out -> Pb.bool(out, 1, allow));
    }

    /**
     * S2C 2107 {@code CCMsgFightKuangTargetDetailInfo}：见上方重载；保留注释锚点。
     * WJ={@code CCMsgWuJiangAllInfoAndJobAndHp}：15 石 / 16 Brief / 17 器魂 / 18 套装；curHp=-1 满血。
     */
    private byte[] kuangTeamDetail(PlayerRecord owner, int playerGuid, int formationType, boolean withBuddies) {
        return kuangTeamDetailSlots(owner, playerGuid, owner.formationSlots(formationType), withBuddies);
    }

    private byte[] kuangTeamDetailSlots(PlayerRecord owner, int playerGuid, List<String> slots,
                                        boolean withBuddies) {
        PlayerRecord.Hero pad = owner == null || owner.heroes.isEmpty() ? null : owner.heroes.get(0);
        List<String> use = slots == null ? new ArrayList<>() : slots;
        return Pb.write(out -> {
            Pb.int32Always(out, 1, playerGuid);
            for (int job = 1; job <= 5; job++) {
                String guid = use.size() >= job ? use.get(job - 1) : "";
                PlayerRecord.Hero wj = owner == null ? null : owner.findHero(guid);
                if (wj == null) {
                    wj = pad;
                }
                if (wj == null) {
                    continue;
                }
                Pb.bytesAlways(out, 2, kuangWjAndHp(owner, job, wj));
            }
            if (withBuddies && owner != null) {
                owner.jiban.ensure();
                for (Integer idx : paddedBuddies(owner)) {
                    Pb.int32Always(out, 3, idx == null ? 0 : idx.intValue());
                }
                for (PlayerRecord.JiBanSlot slot : owner.jiban.slots) {
                    Pb.bytesAlways(out, 4, jiBanSlot(slot));
                }
                int[] fpRet = computeFightPowerReturn(owner);
                Pb.int32Always(out, 5, fpRet[0]);
                Pb.int32Always(out, 5, fpRet[1]);
                Pb.int32Always(out, 5, fpRet[2]);
            }
        });
    }

    private byte[] kuangJobAndBrief(PlayerRecord owner, int job, PlayerRecord.Hero hero) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, job);
            Pb.bytesAlways(out, 2, Pb.write(brief -> {
                Pb.int32Always(brief, 1, hero.heroIndex);
                Pb.int32Always(brief, 2, hero.level);
                Pb.int32Always(brief, 3, hero.stage);
                Pb.int32Always(brief, 4, hero.stars);
            }));
        });
    }

    /** AllInfoAndJobAndHp：1 job 2 curHp 3 index … 14 skill4；15–18 石/装/器魂/套装。 */
    private byte[] kuangWjAndHp(PlayerRecord owner, int job, PlayerRecord.Hero wj) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, job);
            Pb.int32Always(out, 2, -1);
            Pb.int32Always(out, 3, wj.heroIndex);
            Pb.int32Always(out, 4, wj.level);
            Pb.int32Always(out, 5, wj.stage);
            Pb.int32Always(out, 6, wj.stars);
            Pb.int32Always(out, 7, wj.stagePara1);
            Pb.int32Always(out, 8, wj.stagePara2);
            Pb.int32Always(out, 9, wj.stagePara3);
            Pb.int32Always(out, 10, wj.stagePara4);
            Pb.int32Always(out, 11, wj.skill1);
            Pb.int32Always(out, 12, wj.skill2);
            Pb.int32Always(out, 13, wj.skill3);
            Pb.int32Always(out, 14, wj.skill4);
            writeWjGear(out, owner, wj, 15, 16, 17, 18);
        });
    }

    private PlayerRecord.Hero cloneBossHero(int index) {
        return CloneService.bossHeroFor(index);
    }

    public byte[] cloneJoinByIdRet(int errCode) {
        return Pb.write(out -> Pb.int32Always(out, 1, errCode));
    }

    /**
     * Clone Fighter：owner!=null 时写 BuddiesIndex/JiBanSlot/FightPowerReturn（CreateWuJiang 用，不经 UnPackDataForMyTeam）。
     * 6–10 与 beginCloneBattle 同源 computeCombatStats；敌方 APK {@code CloneTeamPlayer.ModifyShuZhi} 会覆盖面板，缺写即 0。
     * WJ={@code CCMsgWuJiangAllInfoAndJob}：14 时光石 / 15 {@code CEquipmentInfo_Brief} / 16 器魂 / 17 套装。
     */
    private byte[] cloneFighter(PlayerRecord owner, int playerGuid, int job, PlayerRecord.Hero wj,
                                String wjGuid) {
        byte[] info = allInfoAndJob(job, wj, owner);
        int[] fpRet = owner == null ? new int[]{0, 0, 0} : computeFightPowerReturn(owner);
        List<Integer> buddies = owner == null ? java.util.Collections.<Integer>emptyList() : paddedBuddies(owner);
        CultivateTables.CombatStats st = cloneFighterStats(owner, wj);
        return Pb.write(out -> {
            Pb.int32(out, 1, playerGuid);
            Pb.bytes(out, 2, info);
            if (owner != null) {
                owner.jiban.ensure();
                for (Integer idx : buddies) {
                    Pb.int32Always(out, 3, idx == null ? 0 : idx.intValue());
                }
                for (PlayerRecord.JiBanSlot slot : owner.jiban.slots) {
                    Pb.bytes(out, 4, jiBanSlot(slot));
                }
                Pb.int32Always(out, 5, fpRet[0]);
                Pb.int32Always(out, 5, fpRet[1]);
                Pb.int32Always(out, 5, fpRet[2]);
            }
            if (st != null) {
                Pb.float32Always(out, 6, st.phyAtk);
                Pb.float32Always(out, 7, st.magAtk);
                Pb.float32Always(out, 8, st.pdef);
                Pb.float32Always(out, 9, st.mdef);
                Pb.int32Always(out, 10, st.maxHp);
            }
            Pb.string(out, 11, wjGuid);
        });
    }

    /** {@code CEquipmentInfo_Brief}：1 OriName 2 Level 3 Stars 4 GuHua 5 洗练 6 开淬炼 7 部位×4 8–10 精炼。 */
    private byte[] equipBrief(PlayerRecord.Equipment eq) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, eq.ori == null ? "" : eq.ori);
            Pb.int32Always(out, 2, eq.level);
            Pb.int32Always(out, 3, eq.stars);
            Pb.int32Always(out, 4, eq.guhua);
            if (eq.xiLianValue != 0) {
                Pb.bytes(out, 5, xiLianProp(eq.xiLianType, eq.xiLianValue));
            }
            Pb.boolAlways(out, 6, eq.openCuiLian);
            boolean[] parts = eq.cuiLianParts == null ? new boolean[4] : eq.cuiLianParts;
            for (int i = 0; i < 4; i++) {
                Pb.boolAlways(out, 7, i < parts.length && parts[i]);
            }
            Pb.int32Always(out, 8, eq.jingLianLevel);
            Pb.int32Always(out, 9, eq.jingLianSubLevel1);
            Pb.int32Always(out, 10, eq.jingLianSubLevel2);
        });
    }

    /** 己方用存档；敌方用与 cloneBossUnit 相同的空档 stub。 */
    private CultivateTables.CombatStats cloneFighterStats(PlayerRecord owner, PlayerRecord.Hero wj) {
        if (wj == null) {
            return null;
        }
        if (owner != null) {
            return cultivate.computeCombatStats(owner, wj);
        }
        PlayerRecord fake = new PlayerRecord();
        fake.playerId = FightSyncService.CLONE_BOSS_GUID;
        fake.equipments = new ArrayList<>();
        return cultivate.computeCombatStats(fake, wj);
    }

    public byte[] mailList(PlayerRecord rec) {
        return Pb.write(out -> {
            if (rec.mails == null) {
                return;
            }
            for (PlayerRecord.Mail mail : rec.mails) {
                if ("sys".equals(mail.kind)) {
                    Pb.bytes(out, 2, sysMail(mail));
                } else {
                    Pb.bytes(out, 1, gmMail(mail));
                }
            }
        });
    }

    public byte[] oneMail(PlayerRecord.Mail mail) {
        return Pb.write(out -> {
            if ("sys".equals(mail.kind)) {
                Pb.bytes(out, 2, sysMail(mail));
            } else {
                Pb.bytes(out, 1, gmMail(mail));
            }
        });
    }

    public byte[] mailDynId(int id) {
        return Pb.write(out -> Pb.int32(out, 1, id));
    }

    private byte[] sysMail(PlayerRecord.Mail mail) {
        return Pb.write(out -> {
            Pb.int32(out, 1, mail.mailDynId);
            Pb.int32Always(out, 2, mail.mailType);
            Pb.stringAlways(out, 3, mail.sendTime);
            Pb.int32(out, 4, mail.gold);
            Pb.int32(out, 5, mail.diamond);
            Pb.int32(out, 6, mail.stamina);
            Pb.int32(out, 7, mail.exp);
            Pb.int32(out, 8, mail.jjcScore);
            Pb.int32(out, 9, mail.brotherCoin);
            Pb.int32(out, 10, mail.wannengFragments);
            Pb.int32(out, 11, mail.yingPo);
            if (mail.paras != null) {
                for (String p : mail.paras) {
                    Pb.string(out, 12, p);
                }
            }
            if (mail.titleParm != null) {
                for (String p : mail.titleParm) {
                    Pb.string(out, 13, p);
                }
            }
            Pb.int32(out, 14, mail.courageCoin);
            writeMailItems(out, mail);
        });
    }

    private byte[] gmMail(PlayerRecord.Mail mail) {
        return Pb.write(out -> {
            Pb.int32(out, 1, mail.mailDynId);
            Pb.string(out, 2, mail.title);
            Pb.string(out, 3, mail.sender);
            Pb.string(out, 4, mail.iconAtlas);
            Pb.string(out, 5, mail.iconSprite);
            Pb.string(out, 6, mail.text);
            Pb.stringAlways(out, 7, mail.sendTime);
            Pb.int32(out, 8, mail.gold);
            Pb.int32(out, 9, mail.diamond);
            Pb.int32(out, 10, mail.stamina);
            Pb.int32(out, 11, mail.exp);
            Pb.int32(out, 12, mail.jjcScore);
            Pb.int32(out, 13, mail.brotherCoin);
            Pb.int32(out, 14, mail.wannengFragments);
            Pb.int32(out, 15, mail.yingPo);
            Pb.bool(out, 16, mail.handled);
            Pb.int32(out, 17, mail.courageCoin);
            writeMailItems(out, mail);
        });
    }

    private static void writeMailItems(com.google.protobuf.CodedOutputStream out, PlayerRecord.Mail mail)
            throws java.io.IOException {
        if (mail.items == null) {
            return;
        }
        int field = "sys".equals(mail.kind) ? 15 : 18;
        for (PlayerRecord.MailItem it : mail.items) {
            if (it == null || it.ori == null || it.ori.isEmpty()) {
                continue;
            }
            Pb.bytes(out, field, Pb.write(item -> {
                Pb.string(item, 1, it.ori);
                Pb.int32(item, 2, it.count);
                Pb.int32(item, 3, it.stars);
            }));
        }
    }

    public byte[] dailyTaskInfo(int taskId, int finishTimes, boolean prized) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, taskId);
            Pb.int32(out, 2, finishTimes);
            Pb.bool(out, 3, prized);
        });
    }

    public byte[] onceTaskInfo(int taskId, int finishTimes, boolean delete) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, taskId);
            Pb.int32(out, 2, finishTimes);
            Pb.bool(out, 3, delete);
        });
    }

    public byte[] dailyTaskList(List<byte[]> items) {
        return Pb.write(out -> {
            for (byte[] one : items) {
                Pb.bytes(out, 1, one);
            }
        });
    }

    public byte[] onceTaskList(List<byte[]> items) {
        return dailyTaskList(items);
    }

    public byte[] int1(int v) {
        return Pb.write(out -> Pb.int32(out, 1, v));
    }

    public byte[] bool1(boolean v) {
        return Pb.write(out -> Pb.boolAlways(out, 1, v));
    }

    // ---------- 限时神将（S2C 3702~3705） ----------

    /** S2C 3702 CMsgUpdateLTSJInfo：activeId<=0 表示未开启（客户端隐藏入口/关面板）。 */
    public byte[] ltsjInfo(com.sao.fakeserver.store.PlayerRecord.Ltsj l, int activeId, int heroIndex,
                           int heroStar, String endTime, int costOnce, int costTen, int wenZiMax,
                           int wjSuiPianCount, String wjSuiPianName, String desc, List<String> words) {
        return Pb.write(out -> {
            Pb.int32(out, 1, activeId);
            Pb.int32(out, 2, l == null ? 0 : l.freeLeft);
            Pb.int32(out, 3, heroIndex);
            Pb.int32(out, 4, heroStar);
            Pb.int32(out, 5, l == null ? 0 : l.wjState);
            Pb.int32(out, 6, wenZiMax);
            Pb.int32(out, 7, costOnce);
            // Desc 走 stringAlways：客户端 XianShiShenJiang.cs:229 直接 mInfo.Desc.Replace(...)，
            // 字段被省略时反序列化成 null → NRE 打不开面板（关闭态 ActiveID=0 先 return，不会走到）。
            Pb.stringAlways(out, 8, desc);
            Pb.string(out, 9, endTime);
            if (l != null) {
                for (int v : l.wenZiExp) {
                    Pb.int32Always(out, 10, v);
                }
                // 11/12/13 三个列表必须逐项 Always 写出：客户端按同一下标取
                // PrizeGoodsName[num]/PrizeGoodsNum[num]/PrizeGoodsState[num]（XianShiShenJiang.cs:396/551），
                // 空串被 Pb.string 丢弃会让后面几格整体前移 → 错位/越界。
                for (String s : l.boxOri) {
                    Pb.stringAlways(out, 11, s);
                }
                for (int v : l.boxNum) {
                    Pb.int32Always(out, 12, v);
                }
                for (int v : l.boxState) {
                    Pb.int32Always(out, 13, v);
                }
            }
            Pb.stringAlways(out, 14, "0");
            Pb.int32(out, 15, costTen);
            Pb.int32(out, 16, wjSuiPianCount);
            Pb.string(out, 17, wjSuiPianName);
            if (words != null) {
                for (String s : words) {
                    Pb.string(out, 18, s);
                }
            }
        });
    }

    /** S2C 3703 CMsgUpdateLTSJPrize：抽卡奖励（awards 复用 CMsgDrawBaoXiangAwrad 数组；1-5 货币留 0）。 */
    public byte[] ltsjPrize(List<byte[]> awards) {
        return Pb.write(out -> {
            if (awards != null) {
                for (byte[] a : awards) {
                    Pb.bytes(out, 6, a);
                }
            }
        });
    }

    /** S2C 3704 CMsgUpdateLTSJWenZiExp：命中文字槽位列表（每项播特效）。 */
    public byte[] ltsjWenZiExp(List<Integer> pos) {
        return Pb.write(out -> {
            if (pos != null) {
                // 必须 int32Always：槽位 0 是合法值，被 Pb.int32 省略后客户端
                // XianShiShenJiang.cs:500-509 遍历到的列表里没有 0 → 第 0 个字永不播特效。
                for (int p : pos) {
                    Pb.int32Always(out, 1, p);
                }
            }
        });
    }

    /** S2C 3705 CMsgUpdateLTSJBaoXiangState：pos 0-3 宝箱 / 4 神将；state 1 可领、2 已领。 */
    public byte[] ltsjBoxState(int pos, int state) {
        return Pb.write(out -> {
            // pos=0（第 1 个宝箱）是合法值，写 Always 免得被省略后只能靠 protobuf 默认值兜。
            Pb.int32Always(out, 1, pos);
            Pb.int32Always(out, 2, state);
        });
    }

    // ---- 挑战赛 / 争霸 / 跨服（字段号来自 NetProto / Client_real.dll）----

    /** S2C 2209 CCMsgTiaoZhanSaiSelfInfo */
    public byte[] bobSelfInfo(PlayerRecord rec) {
        rec.ensureCollections();
        return Pb.write(out -> {
            Pb.int32(out, 1, rec.bob.resetTimes);
            Pb.bool(out, 2, rec.bob.cupPrized[0]);
            Pb.bool(out, 3, rec.bob.cupPrized[1]);
            Pb.bool(out, 4, rec.bob.cupPrized[2]);
            Pb.bool(out, 5, rec.bob.cupPrized[3]);
        });
    }

    /** S2C 2201 CCMsgTiaoZhanSaiTargetsNameAndLevelAndRes：10 个机器人目标。 */
    public byte[] bobTargets(List<GameTables.RobotRow> robots) {
        return Pb.write(out -> {
            if (robots == null) {
                return;
            }
            for (GameTables.RobotRow r : robots) {
                writeBobTarget(out, r.name, r.level, r.resId);
            }
        });
    }

    /** S2C 2201：BOB 目标（真人捏造 NPC 或表机器人）。 */
    public byte[] bobTargetsFromEntries(List<BobService.BobTargetEntry> targets) {
        return Pb.write(out -> {
            if (targets == null) {
                return;
            }
            for (BobService.BobTargetEntry t : targets) {
                if (t == null) {
                    continue;
                }
                writeBobTarget(out, t.name, t.level, t.resId);
            }
        });
    }

    /**
     * 2201 的 1/2/3 是并行 repeated（客户端 BOBChallengeInfo.cs:233-239 以 TargetResID.Count 为界
     * 同下标取 TargetName[i]/TargetLevel[i]）：protobuf-net 写 repeated 元素不省略默认值，
     * 必须用 Always —— 跳过型的 Pb.string/Pb.int32 会在空名/0 级/0 资源 ID 时让某一列短一格 → 客户端越界。
     */
    private static void writeBobTarget(com.google.protobuf.CodedOutputStream out, String name, int level, int resId)
            throws IOException {
        Pb.stringAlways(out, 1, name == null ? "" : name);
        Pb.int32Always(out, 2, level);
        Pb.int32Always(out, 3, resId);
    }

    /** S2C 2203 CCMsgTiaoZhanSaiTargetBriefInfo；TargetRobotWJCurHp 按 job 槽位索引（role2→[1]），须垫满 5；-1=满血。 */
    public byte[] bobTargetBrief(int targetGuid, int fightPower) {
        return Pb.write(out -> {
            Pb.int32(out, 1, fightPower);
            Pb.int32(out, 2, targetGuid);
            for (int i = 0; i < 5; i++) {
                Pb.int32Always(out, 3, -1);
            }
        });
    }

    /**
     * S2C 2204 CCMsgTiaoZhanSaiTargetDetailInfo；机器人 guid≤1e6 客户端查 JJC_Robot。
     * TargetRobotHpAndEnergy 同样按 job 槽位索引，须垫满 5。
     */
    public byte[] bobTargetDetail(int targetGuid) {
        return Pb.write(out -> {
            Pb.int32(out, 1, targetGuid);
            for (int i = 0; i < 5; i++) {
                Pb.bytesAlways(out, 2, Pb.write(hp -> {
                    Pb.int32Always(hp, 1, 100000);
                    Pb.int32(hp, 2, 0);
                }));
            }
        });
    }

    /**
     * S2C 2204：TargetGuid&gt;1e6 时客户端读 field3 WJ（{@code CCMsgWuJiangAllInfoAndJobAndHpAndEnergy}）。
     */
    public byte[] bobTargetDetail(PlayerRecord foe) {
        if (foe == null) {
            return bobTargetDetail(0);
        }
        foe.ensureCollections();
        List<String> slots = foe.formationSlots(PlayerRecord.FORMATION_JJC_DEF);
        PlayerRecord.Hero pad = foe.heroes.isEmpty() ? null : foe.heroes.get(0);
        return Pb.write(out -> {
            Pb.int32(out, 1, foe.playerId);
            for (int i = 0; i < 5; i++) {
                Pb.bytesAlways(out, 2, Pb.write(hp -> {
                    Pb.int32Always(hp, 1, 100000);
                    Pb.int32(hp, 2, 0);
                }));
            }
            for (int job = 1; job <= 5; job++) {
                String guid = slots.size() >= job ? slots.get(job - 1) : "";
                PlayerRecord.Hero wj = foe.findHero(guid);
                if (wj == null) {
                    wj = pad;
                }
                if (wj == null) {
                    continue;
                }
                final int j = job;
                final PlayerRecord.Hero hero = wj;
                Pb.bytesAlways(out, 3, bobFightWjHpEnergy(foe, j, hero, 100000, 0));
            }
        });
    }

    /** CCMsgWuJiangAllInfoAndJobAndHpAndEnergy：1 job 2 curHp 3 curEnergy 4 index … 15 skill4；16 石 / 17 Brief / 18 器魂 / 19 套装。 */
    private byte[] bobFightWjHpEnergy(PlayerRecord owner, int job, PlayerRecord.Hero wj,
                                      int curHp, int curEnergy) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, job);
            Pb.int32Always(out, 2, curHp);
            Pb.int32Always(out, 3, curEnergy);
            Pb.int32Always(out, 4, wj.heroIndex);
            Pb.int32Always(out, 5, wj.level);
            Pb.int32Always(out, 6, wj.stage);
            Pb.int32Always(out, 7, wj.stars);
            Pb.int32Always(out, 8, wj.stagePara1);
            Pb.int32Always(out, 9, wj.stagePara2);
            Pb.int32Always(out, 10, wj.stagePara3);
            Pb.int32Always(out, 11, wj.stagePara4);
            Pb.int32Always(out, 12, wj.skill1);
            Pb.int32Always(out, 13, wj.skill2);
            Pb.int32Always(out, 14, wj.skill3);
            Pb.int32Always(out, 15, wj.skill4);
            writeWjGear(out, owner, wj, 16, 17, 18, 19);
        });
    }

    /** S2C 2202 CMsgAllWuJiangHpAndEnergy：WJsHpAndEnergy[]={guid,curHp,curEnergy}；空=客户端清列表当满血。 */
    public byte[] bobAllHp(PlayerRecord rec) {
        rec.ensureCollections();
        return Pb.write(out -> {
            if (rec.bob.allHp == null) {
                return;
            }
            for (PlayerRecord.BobHp h : rec.bob.allHp) {
                if (h == null || h.guid == null || h.guid.isEmpty()) {
                    continue;
                }
                Pb.bytes(out, 1, Pb.write(inner -> {
                    Pb.string(inner, 1, h.guid);
                    Pb.int32(inner, 2, h.curHp);
                    Pb.int32(inner, 3, h.curEnergy);
                }));
            }
        });
    }

    /** S2C 2206 / 2205 重置次数。 */
    public byte[] bobResetTimes(int times) {
        return Pb.write(out -> Pb.int32Always(out, 1, times));
    }

    /** S2C 2207 / 2208 CCMsgTiaoZhanSaiPrizeInfo；dropGoods=CMsgDropGoods{ori,count}。 */
    public byte[] bobPrize(int gold, int yingPo, List<GameTables.GoodsDrop> drops) {
        return Pb.write(out -> {
            Pb.int32(out, 1, gold);
            if (drops != null) {
                for (GameTables.GoodsDrop g : drops) {
                    if (g == null || g.ori == null || g.ori.isEmpty() || g.count <= 0) {
                        continue;
                    }
                    Pb.bytes(out, 2, dropGoodsItem(g.ori, g.count));
                }
            }
            Pb.int32(out, 6, yingPo);
        });
    }

    /** S2C 4602 CCMsgZBZInfo */
    public byte[] zbzInfo(PlayerRecord rec, List<byte[]> matchPlayers,
                          List<byte[]> gambleInfos, List<byte[]> rankTop3, String jueZhanStartTime) {
        rec.ensureCollections();
        return Pb.write(out -> {
            Pb.int32(out, 1, rec.zbz.curState);
            Pb.int32(out, 2, rec.zbz.curTargetPlayerId);
            Pb.int32(out, 3, rec.zbz.xingJi);
            Pb.int32(out, 4, rec.zbz.jiFen);
            Pb.bool(out, 5, rec.zbz.turnOn);
            Pb.int32(out, 6, rec.zbz.turnOnDay);
            if (gambleInfos != null) {
                for (byte[] g : gambleInfos) {
                    Pb.bytes(out, 7, g);
                }
            }
            if (matchPlayers != null) {
                for (byte[] p : matchPlayers) {
                    Pb.bytes(out, 8, p);
                }
            }
            if (rec.zbz.defenseWuJiangIds != null) {
                for (String id : rec.zbz.defenseWuJiangIds) {
                    if (id != null && !id.isEmpty()) {
                        Pb.string(out, 9, id);
                    }
                }
            }
            if (rankTop3 != null) {
                for (byte[] r : rankTop3) {
                    Pb.bytes(out, 10, r);
                }
            }
            Pb.bool(out, 11, rec.zbz.hadDefense);
            if (jueZhanStartTime != null && !jueZhanStartTime.isEmpty()) {
                Pb.string(out, 12, jueZhanStartTime);
            }
        });
    }

    // §6-9 死代码清理：原 5 参短重载 zbzMatchPlayer(playerId,name,resId,level,jiFen)
    // 全工程零调用 ⇒ 已删。现役只有下面这个 13 参全量版，被 ZbzService.java:712/:775 使用。

    /** CCMsgZBZMatchPlayerInfo nested（排位可只填 1–4/6/7；Top8 补决战字段）。 */
    public byte[] zbzMatchPlayerFull(int playerId, String name, int resId, int level, int jiFen1,
                                     boolean bFight, int jiFen2, int kill1, int kill2, int win,
                                     int jueGroup, int jueRank, int juePos) {
        return Pb.write(out -> {
            Pb.int32(out, 1, playerId);
            Pb.string(out, 2, name);
            Pb.int32(out, 3, resId);
            Pb.int32(out, 4, level);
            if (bFight) {
                Pb.bool(out, 5, true);
            }
            Pb.string(out, 6, "本服");
            Pb.int32Always(out, 7, jiFen1);
            Pb.int32Always(out, 8, jiFen2);
            if (kill1 != 0) {
                Pb.int32(out, 9, kill1);
            }
            if (kill2 != 0) {
                Pb.int32(out, 10, kill2);
            }
            if (win != 0) {
                Pb.int32(out, 11, win);
            }
            if (jueGroup != 0) {
                Pb.int32(out, 12, jueGroup);
            }
            if (jueRank != 0) {
                Pb.int32(out, 13, jueRank);
            }
            if (juePos != 0) {
                Pb.int32(out, 14, juePos);
            }
        });
    }

    /** S2C 4604 CCMsgNextFighter。 */
    public byte[] zbzNextFighter(List<byte[]> defenseWjs, List<byte[]> attackerDefenseWjs, List<String> hadUsed,
                                 int curRoundCnt, int targetCurRoundCnt) {
        int round = Math.max(1, curRoundCnt);
        int targetRound = Math.max(1, targetCurRoundCnt);
        return Pb.write(out -> {
            Pb.int32Always(out, 1, round);
            Pb.int32Always(out, 2, targetRound);
            if (defenseWjs != null) {
                for (byte[] wj : defenseWjs) {
                    Pb.bytes(out, 3, wj);
                }
            }
            if (attackerDefenseWjs != null) {
                for (byte[] wj : attackerDefenseWjs) {
                    Pb.bytes(out, 4, wj);
                }
            }
            if (hadUsed != null) {
                for (String id : hadUsed) {
                    if (id != null && !id.isEmpty()) {
                        Pb.string(out, 5, id);
                    }
                }
            }
        });
    }

    public byte[] zbzNextFighter(List<byte[]> defenseWjs, List<byte[]> attackerDefenseWjs, List<String> hadUsed) {
        return zbzNextFighter(defenseWjs, attackerDefenseWjs, hadUsed, 1, 1);
    }

    /** CCMsgZBZDefenseWuJiang；field5 State：0=已败/禁用，非0=可用。 */
    public byte[] zbzDefenseWj(int wjId, int level, int star, int pinZhi, int state) {
        return Pb.write(out -> {
            Pb.int32(out, 1, wjId);
            Pb.int32(out, 2, level);
            Pb.int32(out, 3, star);
            Pb.int32(out, 4, Math.max(1, pinZhi));
            Pb.int32(out, 5, state);
        });
    }

    public byte[] zbzDefenseWj(int wjId, int level, int star, int pinZhi) {
        return zbzDefenseWj(wjId, level, star, pinZhi, 1);
    }

    public byte[] zbzDefenseWj(int wjId, int level, int star) {
        return zbzDefenseWj(wjId, level, star, 1, 1);
    }

    /** CCMsgZBZGambleInfo；gambletype：0 未押 / 1 已押金 / 2 已押钻 / 3 双押。 */
    public byte[] zbzGambleInfo(int rankId, int playerId, String name, int resId, int level,
                                int gambleType, float rate) {
        return Pb.write(out -> {
            Pb.int32(out, 1, rankId);
            Pb.int32(out, 2, playerId);
            Pb.string(out, 3, name);
            Pb.int32(out, 4, resId);
            Pb.int32(out, 5, level);
            Pb.int32(out, 6, gambleType);
            Pb.float32(out, 7, rate);
        });
    }

    /** @deprecated 用带 gambleType 重载。 */
    public byte[] zbzGambleInfo(int rankId, int playerId, String name, int resId, int level, float rate) {
        return zbzGambleInfo(rankId, playerId, name, resId, level, 0, rate);
    }

    /** CCMsgZBZ3RankPlayerInfo */
    public byte[] zbzTop3(String name, int resId, String unionName) {
        return Pb.write(out -> {
            Pb.string(out, 1, name);
            Pb.int32(out, 2, resId);
            Pb.string(out, 3, unionName == null ? "本服" : unionName);
        });
    }

    /** S2C 4605 CCMsgZBZStateChange */
    public byte[] zbzStateChange(int curState) {
        return Pb.write(out -> Pb.int32Always(out, 1, curState));
    }

    /** S2C 4607 CCMsgZBZJueZhanAward */
    public byte[] zbzJueZhanAward(int rankId, int gold, int diamond, int wnsp, int yingPo,
                                  String goodsOri, int goodsCount) {
        return Pb.write(out -> {
            Pb.int32(out, 1, rankId);
            Pb.int32(out, 2, gold);
            Pb.int32(out, 3, diamond);
            Pb.int32(out, 4, wnsp);
            Pb.int32(out, 5, yingPo);
            if (goodsOri != null && !goodsOri.isEmpty() && goodsCount > 0) {
                Pb.bytes(out, 6, Pb.write(g -> {
                    Pb.string(g, 1, goodsOri);
                    Pb.int32(g, 2, goodsCount);
                }));
            }
        });
    }

    /** S2C 4712 CCMsgUpdateKFZPhase */
    public byte[] kfzPhase(int phase) {
        return Pb.write(out -> Pb.int32Always(out, 1, phase));
    }

    /** S2C 4715 CCMsgPlayerKFZStatus；statusRank 巅峰阶段须 0（见 KfzService.statusRank）。 */
    public byte[] kfzPlayerStatus(int statusRank, PlayerRecord rec) {
        rec.ensureCollections();
        return Pb.write(out -> {
            Pb.int32(out, 1, rec.playerId);
            Pb.bool(out, 2, rec.kfz.hasFightingDfz);
            Pb.int32(out, 3, statusRank);
        });
    }

    /** CCMsgKFZPlayerInfo */
    /** CCMsgKFZPlayerInfo；serverId 真人=本服 1，机器人可捏造异服；lastOfflineTime=0。 */
    public byte[] kfzPlayerInfo(int guid, String name, int resId, int level) {
        return kfzPlayerInfo(guid, name, resId, level, 1);
    }

    public byte[] kfzPlayerInfo(int guid, String name, int resId, int level, int serverId) {
        return Pb.write(out -> {
            Pb.int32(out, 1, guid);
            Pb.stringAlways(out, 2, name);
            Pb.stringAlways(out, 3, "本服");
            Pb.int32Always(out, 4, resId);
            Pb.int32Always(out, 5, level);
            Pb.int32Always(out, 6, serverId > 0 ? serverId : 1);
            Pb.int32Always(out, 7, 0);
        });
    }

    /** S2C 4704 CCMsgKFZRequestPYSSaiCheng_Ret */
    public byte[] kfzPysSaiCheng(byte[] selfBrief, int stars, int score, List<byte[]> enemies) {
        return Pb.write(out -> {
            Pb.bytes(out, 1, selfBrief);
            Pb.int32(out, 2, stars);
            Pb.int32(out, 3, score);
            if (enemies != null) {
                for (byte[] e : enemies) {
                    Pb.bytes(out, 4, e);
                }
            }
        });
    }

    /**
     * CCMsgKFZRequestPYSSaiChengItem（S2C 4704 敌条）。
     * status：1 可打 / 2 等结果 / 3 我胜 / 4 我负（APK OneFightItem）。
     */
    public byte[] kfzPysEnemyItem(byte[] brief, int status,
                                  int myKill, int otherKill, int myScore, int otherScore) {
        return Pb.write(out -> {
            Pb.bytesAlways(out, 1, brief);
            Pb.int32Always(out, 2, status);
            Pb.int32Always(out, 3, Math.max(0, myKill));
            Pb.int32Always(out, 4, Math.max(0, otherKill));
            Pb.int32Always(out, 5, myScore);
            Pb.int32Always(out, 6, otherScore);
        });
    }

    /**
     * S2C 4705 CCMsgKFZRequestDuiZhanList_Ret。
     * APK：duiZhanList 非空；myWinStatus/targetWinStatus 元素 2=胜场计入。
     * 2/3 是「逐局」平行 repeated：客户端 {@code KFZ_DuiZhanLieBiao.cs:229-241} 按
     * {@code winOrLose[i-1]} 显示第 i 局的 ShengBai{i}/{status}，位置就是局号 ——
     * 用跳过型的 Pb.int32 会丢掉 0（未打/未胜）那一局，后面整列前移 → 局号与胜负全部错位。
     */
    public byte[] kfzDuiZhan(byte[] targetBrief, byte[] myBrief, List<byte[]> duiZhanItems,
                             List<Integer> myWinStatus, List<Integer> targetWinStatus) {
        return Pb.write(out -> {
            Pb.bytes(out, 1, targetBrief);
            if (myWinStatus != null) {
                for (Integer v : myWinStatus) {
                    Pb.int32Always(out, 2, v == null ? 0 : v);
                }
            }
            if (targetWinStatus != null) {
                for (Integer v : targetWinStatus) {
                    Pb.int32Always(out, 3, v == null ? 0 : v);
                }
            }
            if (duiZhanItems != null) {
                for (byte[] item : duiZhanItems) {
                    Pb.bytes(out, 4, item);
                }
            }
            Pb.bytes(out, 5, myBrief);
        });
    }

    /** CCMsgKFZRequestDuiZhanListItem：1 wjList 2 status 3 myScore 4 targetScore。wjList 须非 null（可空）。 */
    public byte[] kfzDuiZhanItem(List<byte[]> wjList, int status, int myScore, int targetScore) {
        return Pb.write(out -> {
            if (wjList != null) {
                for (byte[] wj : wjList) {
                    Pb.bytes(out, 1, wj);
                }
            }
            Pb.int32(out, 2, status);
            Pb.int32(out, 3, myScore);
            Pb.int32(out, 4, targetScore);
        });
    }

    /** CCMsgKFZDuiZhanListWJInfo：1 RemoteWuJiangBreif 2 isDead。 */
    public byte[] kfzDuiZhanWj(int heroIndex, int level, int star, int jieDuan, boolean dead) {
        byte[] brief = Pb.write(b -> {
            // CCMsgRemoteWuJiangBreifInfo：1 index 2 jieduan 3 level 4 stars
            Pb.int32(b, 1, heroIndex);
            Pb.int32(b, 2, Math.max(1, jieDuan));
            Pb.int32(b, 3, level);
            Pb.int32(b, 4, Math.max(1, star));
        });
        return Pb.write(out -> {
            Pb.bytes(out, 1, brief);
            if (dead) {
                Pb.bool(out, 2, true);
            }
        });
    }

    /**
     * S2C 4707 CCMsgKFZRequestOffenceBuZhenInfo_Ret。
     * myWjInfo：index=heroIndex；hasPlayed/isDead 跨三场锁定（APK EmBattle GetKFZWuJiangState）。
     */
    public byte[] kfzOffenceBuZhen(byte[] targetBrief, byte[] myBrief, List<byte[]> myWjInfo) {
        return Pb.write(out -> {
            Pb.bytes(out, 1, targetBrief);
            if (myWjInfo != null) {
                for (byte[] wj : myWjInfo) {
                    Pb.bytes(out, 2, wj);
                }
            }
            Pb.bytes(out, 3, myBrief);
        });
    }

    /** CCMsgKFZRequestOffenceBuZhenWJ */
    public byte[] kfzOffenceWj(int index, boolean hasPlayed, boolean isDead) {
        return Pb.write(out -> {
            Pb.int32(out, 1, index);
            if (hasPlayed) {
                Pb.bool(out, 2, true);
            }
            if (isDead) {
                Pb.bool(out, 3, true);
            }
        });
    }

    /** S2C 4709 CCMsgKFZRequestRankList_Ret（巅峰/总榜）：仅 rankList。 */
    public byte[] kfzRankList(List<byte[]> items) {
        return Pb.write(out -> {
            if (items != null) {
                for (byte[] item : items) {
                    Pb.bytes(out, 1, item);
                }
            }
        });
    }

    /** S2C 4710 CCMsgKFZRequestPaiWeiSaiRankList_Ret */
    public byte[] kfzPaiWeiRank(List<byte[]> items, byte[] myRank, byte[] myInfo) {
        return Pb.write(out -> {
            if (items != null) {
                for (byte[] item : items) {
                    Pb.bytes(out, 1, item);
                }
            }
            Pb.bytes(out, 2, myRank);
            Pb.bytes(out, 3, myInfo);
        });
    }

    /** CCMsgKFZRankListItem：1 player 2 rank 3 star 4 score */
    public byte[] kfzRankItem(byte[] playerInfo, int rank, int star, int score) {
        return Pb.write(out -> {
            Pb.bytesAlways(out, 1, playerInfo);
            Pb.int32(out, 2, rank);
            Pb.int32(out, 3, star);
            Pb.int32(out, 4, score);
        });
    }

    /** S2C 4706 CCMsgKFZCurrentTop3（主城雕像同源包） */
    public byte[] kfzTop3(List<byte[]> items) {
        return Pb.write(out -> {
            if (items != null) {
                for (byte[] item : items) {
                    Pb.bytesAlways(out, 1, item);
                }
            }
        });
    }

    /**
     * S2C 4714 CCMsgKFZRefreshWoreshipStatus。
     * APK MoBai 无空判 awardedPlayer；空列表时写哨兵 0（名次仅 1–3 有效）。
     */
    public byte[] kfzWorshipStatus(List<Integer> awardedRanks) {
        return Pb.write(out -> {
            if (awardedRanks == null || awardedRanks.isEmpty()) {
                Pb.int32(out, 1, 0);
                return;
            }
            for (Integer r : awardedRanks) {
                if (r != null) {
                    Pb.int32(out, 1, r);
                }
            }
        });
    }

    /** S2C 4713 CCMsgKFZRequestWoreshipAward_Ret */
    public byte[] kfzWorshipRet(int awardedRank) {
        return Pb.write(out -> Pb.int32(out, 1, awardedRank));
    }

    /** S2C 4711 CCMsgKFZRequestDFZSaiCheng_Ret：playerInfo 须 8 或 64。 */
    public byte[] kfzDfzSaiCheng(byte[] selfBrief, List<byte[]> items) {
        return Pb.write(out -> {
            Pb.bytes(out, 1, selfBrief);
            if (items != null) {
                for (byte[] item : items) {
                    Pb.bytes(out, 2, item);
                }
            }
        });
    }

    /** CCMsgKFZRequestDFZSaiChengItem：1 player 2 target 3 hasFailed 4 rank(0=仍在打)。 */
    public byte[] kfzDfzItem(byte[] player, byte[] target, boolean hasFailed, int rank) {
        return Pb.write(out -> {
            Pb.bytes(out, 1, player);
            if (target != null) {
                Pb.bytes(out, 2, target);
            }
            if (hasFailed) {
                Pb.bool(out, 3, true);
            }
            Pb.int32(out, 4, rank);
        });
    }

    /** S2C 4701 CCMsgKFZRequestMyServerZhanKuang_Ret */
    public byte[] kfzZhanKuang(byte[] selfBrief, List<byte[]> items) {
        return Pb.write(out -> {
            if (items != null) {
                for (byte[] item : items) {
                    Pb.bytes(out, 1, item);
                }
            }
            Pb.bytes(out, 2, selfBrief);
        });
    }

    /** CCMsgKFZRequestMyServerZhanKuangItem：1 playerBrief 2 stars 3 rank */
    public byte[] kfzZhanKuangItem(byte[] brief, int stars, int rank) {
        return Pb.write(out -> {
            Pb.bytes(out, 1, brief);
            Pb.int32(out, 2, stars);
            Pb.int32(out, 3, rank);
        });
    }

    /** S2C 4702 CCMsgKFZRequestMyServerXiangXiZhanBao_Ret */
    public byte[] kfzXiangXi(byte[] selfBrief, List<byte[]> items) {
        return Pb.write(out -> {
            if (items != null) {
                for (byte[] item : items) {
                    Pb.bytes(out, 1, item);
                }
            }
            Pb.bytes(out, 2, selfBrief);
        });
    }

    /** CCMsgKFZRequestMyServerXiangXiZhanBaoItem：1 type 2 rank 3 winner 4 winnerServer 5 loser 6 loserServer */
    public byte[] kfzXiangXiItem(int type, int rank, String winner, int winnerServer,
                                String loser, int loserServer) {
        return Pb.write(out -> {
            Pb.int32(out, 1, type);
            Pb.int32(out, 2, rank);
            Pb.string(out, 3, winner);
            Pb.int32(out, 4, winnerServer);
            Pb.string(out, 5, loser);
            Pb.int32(out, 6, loserServer);
        });
    }

    /** S2C 4703 CCMsgKFZRequestMingRenTang_Ret */
    public byte[] kfzMingRen(byte[] selfBrief, List<byte[]> items) {
        return Pb.write(out -> {
            if (items != null) {
                for (byte[] item : items) {
                    Pb.bytes(out, 1, item);
                }
            }
            Pb.bytes(out, 2, selfBrief);
        });
    }

    /** CCMsgKFZRequestMingRenTangItem：1 index 2 rank 3 playerBrief */
    public byte[] kfzMingRenItem(int index, int rank, byte[] brief) {
        return Pb.write(out -> {
            Pb.int32(out, 1, index);
            Pb.int32(out, 2, rank);
            Pb.bytes(out, 3, brief);
        });
    }

    // ================================================================ 喇叭（4701/4702 → 5201/5202）

    /**
     * S2C 5201 {@code NET_CCMsgRequestOpenLaBa_Ret}。
     * <p>空体即可：客户端处理函数 {@code ᝁ.cs:8381} 是抄开宝箱的，
     * 反序列化的是 {@code CCMsgOpenBaoXiang_Ret}（{@code :8384}），喇叭字段一个都不读。</p>
     */
    public byte[] laBaOpenRet() {
        return new byte[0];
    }

    /**
     * S2C 5202 {@code NET_CCMsgRequestBroadCastLaBa}
     * （{@code CCMsgLaBaOpenMessage}：1 words、2 oriName、3 dynID、4 playerName、
     * 5 playerGuid、6 serverID）。
     * <p>⚠️ 客户端 {@code ᝁ.cs:8492-8506}：先用 tag2 {@code oriName} 查
     * {@code GetPropertyCfg}，查不到就 {@code :8494} 直接解引用 null 崩；tag3 {@code dynID}
     * 查不到单位时 {@code :8504} 有判空保护，安全。所以 {@code oriName} 必须是
     * {@code GoodsList} 里真实存在的喇叭道具 ori（{@code GOODS250}/{@code GOODS251}）。</p>
     */
    public byte[] laBaBroadcast(String words, String oriName, int dynID, String playerName,
                               int playerGuid, int serverID) {
        return Pb.write(out -> {
            Pb.stringAlways(out, 1, words == null ? "" : words);
            Pb.stringAlways(out, 2, oriName == null ? "" : oriName);
            Pb.int32Always(out, 3, dynID);
            Pb.stringAlways(out, 4, playerName == null ? "" : playerName);
            Pb.int32Always(out, 5, playerGuid);
            Pb.int32Always(out, 6, serverID);
        });
    }

    // ================================================================ 龙腾 / 限时兑换（3601-3603 → 4101-4104）

    /** {@code CCMsgExchangePrize}：1 moriname、2 goodsstar、3 goodsnum、4 medallevel、5 medalnum、6 honournum。 */
    public static class LtPrize {
        public String moriName = "";
        public int goodsStar;
        public int goodsNum;
        /** 1..5（客户端 {@code PublicIconPropertyCfg.cs:588 mXunZhangIconName = new string[5,2]}）。 */
        public int medalLevel;
        public int medalNum;
        public int honourNum;
    }

    /** {@code CCMsgExchangeRankPrize}：1 JinBi、2 Rmb、3 YingPo、4/5 Goods1、6/7 Goods2、8 RankLimit。 */
    public static class LtRankPrize {
        public int jinBi;
        public int rmb;
        public int yingPo;
        public String goods1Name = "";
        public int goods1Num;
        public String goods2Name = "";
        public int goods2Num;
        public int rankLimit;
    }

    /** {@code CCMsgUpdateLTSJPrize}：1 jinBi、2 zuanShi、3 tiLi、4 wnsp、5 yingPo、6 awards。 */
    public static class LtDrawPrize {
        public int jinBi;
        public int zuanShi;
        public int tiLi;
        public int wnsp;
        public int yingPo;
        /** 每项是 {@code CCMsgDrawBaoXiangAwrad}，直接塞 {@link #drawAwardItem}/{@link #drawAwardHero}。 */
        public List<byte[]> awards = new ArrayList<>();
    }

    /** {@code CCMsgRankListItem}：1 rank、2 guid、3 name、4 resID、5 level、6 value。 */
    public static class LtRankRow {
        public int rank;
        public int guid;
        public String name = "";
        public int resID;
        public int level;
        public int value;
    }

    /**
     * S2C 4101 {@code CMsgUpdateLTExchangeInfo}（龙腾/限时兑换主数据）。
     * <p><b>没有对应的 C2S</b>：客户端只在收到这个包之后才 {@code ExchangeInATimeInfo.UnPacket}
     * 灌初始数据，所以必须由假服主动 push（登录时推一次即可）。
     * <p>1 activeonoff（{@code ==1} 才开界面）、2 oncermbcost、3 tenrmbcost、4 honourname、
     * 5 endtime（<b>必须</b> {@code yyyy-MM-dd HH:mm:ss}，客户端 {@code ExchangeInATime.cs:278}
     * 用 {@code DateTime.ParseExact} 严格解析）、6 repeated medalname、7 repeated CCMsgExchangePrize、
     * 8 tokenname、9 oncetokencost、10 tentokencost、11 repeated CCMsgExchangeRankPrize、12 rankid。
     * <p>⚠️ 6/7/11 必须至少各回一条：{@code ExchangeGoods.cs:176} 直接
     * {@code exchangeInATimeInfo.xunzhangName.Count} 不判空，不回就 FormatException/NRE。</p>
     */
    public byte[] ltExchangeInfo(int activeOnOff, int onceRmbCost, int tenRmbCost, String honourName,
                                 String endTime, List<String> medalName, List<LtPrize> prizes,
                                 String tokenName, int onceTokenCost, int tenTokenCost,
                                 List<LtRankPrize> rankPrizes, int rankId) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, activeOnOff);
            Pb.int32Always(out, 2, onceRmbCost);
            Pb.int32Always(out, 3, tenRmbCost);
            Pb.stringAlways(out, 4, honourName == null ? "" : honourName);
            Pb.stringAlways(out, 5, endTime == null ? "" : endTime);
            if (medalName != null) {
                for (String s : medalName) {
                    Pb.stringAlways(out, 6, s == null ? "" : s);
                }
            }
            if (prizes != null) {
                for (LtPrize p : prizes) {
                    if (p == null) {
                        continue;
                    }
                    Pb.bytes(out, 7, Pb.write(item -> {
                        Pb.stringAlways(item, 1, p.moriName == null ? "" : p.moriName);
                        Pb.int32Always(item, 2, p.goodsStar);
                        Pb.int32Always(item, 3, p.goodsNum);
                        // medallevel 是 1..5 的档位，0 会让客户端取到越界下标。
                        Pb.int32Always(item, 4, Math.max(1, Math.min(5, p.medalLevel)));
                        Pb.int32Always(item, 5, p.medalNum);
                        Pb.int32Always(item, 6, p.honourNum);
                    }));
                }
            }
            Pb.stringAlways(out, 8, tokenName == null ? "" : tokenName);
            Pb.int32Always(out, 9, onceTokenCost);
            Pb.int32Always(out, 10, tenTokenCost);
            if (rankPrizes != null) {
                for (LtRankPrize r : rankPrizes) {
                    if (r == null) {
                        continue;
                    }
                    Pb.bytes(out, 11, Pb.write(item -> {
                        Pb.int32Always(item, 1, r.jinBi);
                        Pb.int32Always(item, 2, r.rmb);
                        Pb.int32Always(item, 3, r.yingPo);
                        Pb.stringAlways(item, 4, r.goods1Name == null ? "" : r.goods1Name);
                        Pb.int32Always(item, 5, r.goods1Num);
                        Pb.stringAlways(item, 6, r.goods2Name == null ? "" : r.goods2Name);
                        Pb.int32Always(item, 7, r.goods2Num);
                        Pb.int32Always(item, 8, r.rankLimit);
                    }));
                }
            }
            Pb.int32Always(out, 12, rankId);
        });
    }

    /**
     * S2C 4102 {@code CMsgUpdateLTExchangePrizeRet}：1 repeated CCMsgUpdateLTSJPrize。
     * <p>客户端 {@code ᝁ.cs:4703-4709} 只有在这个 handler 里才会
     * {@code ExchangeResultUI.Open()} ⇒ 抽奖「点了没反应」的唯一原因就是没回这个包。</p>
     */
    public byte[] ltExchangePrizeRet(List<LtDrawPrize> list) {
        return Pb.write(out -> {
            if (list != null) {
                for (LtDrawPrize p : list) {
                    if (p == null) {
                        continue;
                    }
                    Pb.bytes(out, 1, Pb.write(item -> {
                        Pb.int32Always(item, 1, p.jinBi);
                        Pb.int32Always(item, 2, p.zuanShi);
                        Pb.int32Always(item, 3, p.tiLi);
                        Pb.int32Always(item, 4, p.wnsp);
                        Pb.int32Always(item, 5, p.yingPo);
                        if (p.awards != null) {
                            for (byte[] a : p.awards) {
                                Pb.bytes(item, 6, a);
                            }
                        }
                    }));
                }
            }
        });
    }

    /** S2C 4103 {@code CCMsgLTExchangeRankListInfo}：1 myRank、2 repeated CCMsgRankListItem。 */
    public byte[] ltExchangeRankRet(int myRank, List<LtRankRow> rows) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, Math.max(0, myRank));
            if (rows != null) {
                for (LtRankRow r : rows) {
                    if (r == null) {
                        continue;
                    }
                    Pb.bytes(out, 2, Pb.write(item -> {
                        Pb.int32Always(item, 1, r.rank);
                        Pb.int32Always(item, 2, r.guid);
                        Pb.stringAlways(item, 3, r.name == null ? "" : r.name);
                        Pb.int32Always(item, 4, r.resID);
                        Pb.int32Always(item, 5, r.level);
                        Pb.int32Always(item, 6, r.value);
                    }));
                }
            }
        });
    }

    /**
     * S2C 4104 {@code CMsgLTExchangeGoodsRet}：1 goodstype、2 goodsname、3 goodsstar、
     * 4 goodsnum、5 suipianname、6 suipiannum。
     * <p>客户端 {@code ᝁ.cs:4714-4764}：{@code goodstype==1} 展示名字+数量；
     * {@code ==2} 时 {@code suipiannum!=0} 走碎片分支，否则把 {@code goodsname}
     * {@code Convert.ToInt32} 当武将下标；{@code ==3} 另走一路。</p>
     */
    public byte[] ltExchangeGoodsRet(int goodsType, String goodsName, int goodsStar, int goodsNum,
                                     String suipianName, int suipianNum) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, goodsType);
            Pb.stringAlways(out, 2, goodsName == null ? "" : goodsName);
            Pb.int32Always(out, 3, goodsStar);
            Pb.int32Always(out, 4, goodsNum);
            Pb.stringAlways(out, 5, suipianName == null ? "" : suipianName);
            Pb.int32Always(out, 6, suipianNum);
        });
    }

    // ================================================================ 好友（1403-1418 → 1303-1320）

    /**
     * 好友基础信息 {@code CFriendBase}：1 Guid、2 ResId、3 Level、4 Name、5 UnionName、
     * 6 FightPower、7 IsOnline、8 OfflineTime。
     * <p>⚠️ {@code offlineTime} 必须是非空且合法的 {@code yyyy-MM-dd HH:mm:ss}：
     * {@code FriendItem.cs:108} 用 {@code DateTime.ParseExact} 且不判空 ⇒ 离线好友给空串会 FormatException。
     * 在线好友可传 {@code null}（tag 省略）。</p>
     */
    public byte[] friendBase(int guid, int resId, int level, String name, String unionName,
                             int fightPower, boolean online, String offlineTime) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, guid);
            Pb.int32Always(out, 2, resId);
            Pb.int32Always(out, 3, level);
            Pb.stringAlways(out, 4, name == null ? "" : name);
            Pb.stringAlways(out, 5, unionName == null ? "" : unionName);
            Pb.int32Always(out, 6, fightPower);
            Pb.boolAlways(out, 7, online);
            if (!online && offlineTime != null && !offlineTime.isEmpty()) {
                Pb.stringAlways(out, 8, offlineTime);
            }
        });
    }

    /**
     * {@code CFriendInfo}：1 CFriendBase、2 IsGive(bool)、3 IsReceive(int)。
     * <p>⚠️ IsReceive 是 tag <b>3</b>，不是 2（{@code FriendSystem.cs:81-82}）。</p>
     */
    public byte[] friendInfo(byte[] baseInfo, boolean isGive, int isReceive) {
        return Pb.write(out -> {
            Pb.bytesAlways(out, 1, baseInfo);
            Pb.boolAlways(out, 2, isGive);
            Pb.int32Always(out, 3, isReceive);
        });
    }

    /** S2C 1303 {@code CFriendsInfo}：1 repeated CFriendInfo（好友列表回包）。 */
    public byte[] friendListRet(List<byte[]> infos) {
        return Pb.write(out -> {
            if (infos != null) {
                for (byte[] i : infos) {
                    Pb.bytes(out, 1, i);
                }
            }
        });
    }

    /** S2C 1314 {@code CFriendsBase}：1 repeated CFriendBase（申请列表，不带赠/领体力状态）。 */
    public byte[] friendApplyListRet(List<byte[]> bases) {
        return Pb.write(out -> {
            if (bases != null) {
                for (byte[] b : bases) {
                    Pb.bytes(out, 1, b);
                }
            }
        });
    }

    /** {@code CFriendGuid}：1 Guid。 */
    public byte[] friendGuid(int guid) {
        return Pb.write(out -> Pb.int32Always(out, 1, guid));
    }

    /** {@code CFriendName}：1 name。 */
    public byte[] friendName(String name) {
        return Pb.write(out -> Pb.stringAlways(out, 1, name == null ? "" : name));
    }

    /** {@code CFriendBoolRet}：1 success。 */
    public byte[] friendBoolRet(boolean success) {
        return Pb.write(out -> Pb.boolAlways(out, 1, success));
    }

    /** {@code CFriendAccPowerRet}：1 guid、2 count（领取体力后回的新体力数）。 */
    public byte[] friendAccPowerRet(int guid, int count) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, guid);
            Pb.int32Always(out, 2, count);
        });
    }

    /**
     * S2C 1320 {@code CFriendRedPoint}：1 rewards(bool)、2 applies(int)、3 flushtime(int)。
     * <p>这是好友红点的唯一写点（{@code FriendSystem.cs:998-1001}）：不回这个包，
     * 大厅好友入口的红点永远不会亮。同时 {@code flushtime} 是加好友页的刷新倒计时。</p>
     */
    public byte[] friendRedPoint(boolean rewards, int applies, int flushTime) {
        return Pb.write(out -> {
            Pb.boolAlways(out, 1, rewards);
            Pb.int32Always(out, 2, applies);
            Pb.int32Always(out, 3, flushTime);
        });
    }

    /**
     * S2C 1308 / 1311 {@code CAddFriendRet}：1 success、2 type、3 guid、4 errorcode。
     * <p>errorcode：1 已经是好友、2 玩家不存在、3 对方申请数已满（客户端 {@code FriendSystem.cs:464-482}）。</p>
     */
    public byte[] addFriendRet(boolean success, int type, int guid, int errorCode) {
        return Pb.write(out -> {
            Pb.boolAlways(out, 1, success);
            Pb.int32Always(out, 2, type);
            Pb.int32Always(out, 3, guid);
            Pb.int32Always(out, 4, errorCode);
        });
    }

    /**
     * S2C 1315 {@code CAgreeFriendRet}：1 success、2 type、3 guid、4 errorcode。
     * <p>客户端 {@code FriendSystem.cs:941} 对 {@code errorcode == 1} 报「好友数量已达上限」。</p>
     */
    public byte[] agreeFriendRet(boolean success, int type, int guid, int errorCode) {
        return addFriendRet(success, type, guid, errorCode);
    }

    /**
     * S2C 1312 {@code CInviteCount}：1 count、2 realcount。
     * <p>客户端只读 {@code realcount}（{@code FriendSystem.cs:668}），但两个都写更保险。</p>
     */
    public byte[] inviteCount(int count, int realCount) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, count);
            Pb.int32Always(out, 2, realCount);
        });
    }

    /**
     * S2C 1318 {@code CInviteMyInfo}：1 guid、2 rewards。
     * <p>{@code guid == 0} 时客户端显示「尚未被邀请」（{@code FriendSystem.cs:746}）。</p>
     */
    public byte[] inviteMyInfo(int guid, int rewards) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, guid);
            Pb.int32Always(out, 2, rewards);
        });
    }

    /**
     * S2C 1313 {@code CInviteReward}：1 type、2 success、3 index。
     * <p>客户端 {@code FriendSystem.cs:585} 只特判 {@code type == 1} 走「命中档位发奖」分支。</p>
     */
    public byte[] inviteReward(int type, boolean success, int index) {
        return Pb.write(out -> {
            Pb.int32Always(out, 1, type);
            Pb.boolAlways(out, 2, success);
            Pb.int32Always(out, 3, index);
        });
    }

    /** S2C 1317 {@code CInviteRewards}：1 repeated id（已领过的邀请档位人数）。 */
    public byte[] inviteRewards(List<Integer> ids) {
        return Pb.write(out -> {
            if (ids != null) {
                for (int id : ids) {
                    Pb.int32Always(out, 1, id);
                }
            }
        });
    }

    /**
     * S2C 1316 好友详情。包体与 S2C 2902 完全同构（{@code CCMsgRemotePlayerBreifInfo}）
     * ⇒ 直接转发 {@link #remotePlayerBrief}。
     */
    public byte[] friendBrief(WorldStore.JjcSlot slot, String unionName, List<int[]> wjs) {
        return remotePlayerBrief(slot, unionName, wjs);
    }
}
