package com.sao.fakeserver.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号存档。字段名就是 JSON 里的键，手改 data/players/{账号}.json 即可。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
        "_说明", "account", "playerId", "roleName", "createdAt", "mainHeroIndex", "mainRoleIndex",
        "level", "exp", "stamina", "gold", "diamond", "skillPoints", "wannengFragments",
        "jjcScore", "yingPo", "moFaChen", "zhengZhanShuiJin",
        "currentRegionId", "progress", "bag", "heroes", "equipments", "nextEquipSeq",
        "formation", "formationsByType", "guild", "arena", "gacha", "economy", "tower", "resourceFb", "shops",
        "mails", "nextMailSeq", "souls", "clone", "cards", "jiban", "tasks", "signIn", "activity",
        "bob", "zbz", "kfz",
        "varis", "isDrawSecondDayLoginPrized", "alreadyNewUserGuideFB",
        "usedGiftCodes", "usedActivationCodes", "activationPrompted",
        "lastLogoutAtMs", "blackList", "ltExchange",
        "friendIds", "friendApplies", "friendGaveToday", "friendReceiveState",
        "friendReceivedToday", "friendGaveCount", "inviteClaimed", "inviterGuid",
        "friendRefreshAnchorMs", "friendPushPage"
    })
public class PlayerRecord {
    public static final String NOTE =
            "手改说明: gold=金币 diamond=钻石 stamina=体力 exp=本级经验 skillPoints=技能点 wannengFragments=万能碎片. "
                    + "bag 的 key=道具原名(如 GOODS3). heroes.heroIndex=武将表ID(18黑剑士/28精灵女王). "
                    + "mainRoleIndex=创角左右(1左/2右)，次日登录 UI 用它选奖池行，勿写成武将表ID. "
                    + "equipments 是装备实例, owner=穿在谁身上的武将 id, 空串=未穿. "
                    + "formation=PVE(type0) 5 槽兼容字段；全量按 type 见 formationsByType（0PVE/1JJC防/2JJC攻/…）. "
                    + "economy.chargedDiamond=累计充值钻石（不含赠送），登录和充值后客户端按 VipCfg 算 VIP 等级. "
                    + "progress.stageStars 键=关卡ID/normal 或 关卡ID/hard，值=星数 1-3. "
                    + "varis=玩家字符串变量(C2S 1801 / detail field 109)，如 InChat2=Played 表示对话已播；缺则重登对话会重复. "
                    + "isDrawSecondDayLoginPrized=是否已领次日登录奖；创建次日(自然日)起才可领，当天登录包 leftTime(秒)>0 倒计时禁领. "
                    + "alreadyNewUserGuideFB=false 先进新手本；回主城(C2S 304)后会写成 true. "
                    + "activity.day7Count=已领七日天数(0-7) today7Day=今日是否已领 gainedVpTypes=今日已领体力时段类型. "
                    + "signIn.prizeStatus=当月每天状态(1未领取/2已领/3已领VIP双倍)；未领的天务必记1，勿记0(APK客户端把0画成已领取图章). "
                    + "playerId 必须 >1000000（否则客户端当竞技场机器人）.";

    /** eFormationType：与客户端 MainPlayerCompleteMember / OnEmBattleUpdateFromServer 对齐。 */
    public static final int FORMATION_PVE = 0;
    public static final int FORMATION_JJC_DEF = 1;
    public static final int FORMATION_JJC_ATK = 2;
    public static final int FORMATION_QIANGKUANG_DEF = 3;
    public static final int FORMATION_QIANGKUANG_ATK = 4;
    public static final int FORMATION_MIRROR = 5;
    public static final int FORMATION_UNTOUCHABLE = 6;
    public static final int FORMATION_BOB_ATK = 7;
    public static final int FORMATION_DEADLY = 8;
    public static final int FORMATION_SANTA = 9;
    public static final int FORMATION_SCYTHE = 10;
    public static final int FORMATION_QIECUO = 11;
    /** eFormationType 12 = {@code FORMATION_TYPE_UNION_ZUO_ZHAN_BOSS}（作战室 Boss 出战阵容）。 */
    public static final int FORMATION_UNION_ZUO_ZHAN_BOSS = 12;
    public static final int FORMATION_MAJIU = 13;
    public static final int FORMATION_MAJIU_RAID = 23;
    public static final int FORMATION_UNION_PVP_DEF1 = 24;
    /** eFormationType 29 = {@code FORMATION_TYPE_UNION_PVP_OFFENSE}（公会战进攻阵容，1564→1961 己方队伍）。 */
    public static final int FORMATION_UNION_PVP_OFFENSE = 29;
    public static final int FORMATION_BCT = 30;
    public static final int FORMATION_ZBZ = 31;
    public static final int FORMATION_KFZ_ATK = 32;
    public static final int FORMATION_KFZ_DEF1 = 33;
    public static final int FORMATION_KFZ_DEF2 = 34;
    public static final int FORMATION_KFZ_DEF3 = 35;
    public static final int FORMATION_CLONE_ATK = 38;

    @JsonProperty("_说明")
    public String note = NOTE;

    public String account = "";
    public int playerId;
    public String roleName = "";
    public String createdAt = "";
    /**
     * 假服被动 NPC（捏造类真人）：有 PlayerRecord、可被打/进榜/进括号，
     * 不登录、不主动进攻、不收系统邮。playerId 仍 &gt;1e6，开战走真人路径。
     */
    public boolean npcPassive;
    /** NPC 创建时缓存的武将战力合计（展示/分档用；开战仍按养成现算）。 */
    public int npcTotalFightPower;
    public int mainHeroIndex = 18;
    /** 创角左右：1=左 2=右。对应 CMsgDetailPlayerInfo.mainRoleIndex / 次日登录奖池行。 */
    public int mainRoleIndex = 1;
    public int level = 1;
    public int exp;
    public int stamina = 120;
    public int gold = 100000;
    public int diamond;
    public int skillPoints = 20;
    /** 技能点被动恢复锚点时间(ms)；未满且计时中时有值。 */
    public long skillPointRecoverAnchorMs = 0;
    public int wannengFragments;
    public int jjcScore;
    public int yingPo;
    public int moFaChen;
    public int zhengZhanShuiJin = 20;
    /**
     * 征战水晶上次恢复锚点（毫秒）。登录 field41 = 距此已流逝秒数。
     * 0=尚未开始计时（满水晶或新号）；扣到不满后置 now。
     */
    public long lastZzRestoreAtMs;
    /** 当日矿战买复活次数（field42 / S2C 2125 日清）。 */
    public int qkBuyReliveTimes;
    /** 当日协防次数（field43）。 */
    public int coDefenseKuangCnt;
    /** 当日守矿/占领次数（field44）。 */
    public int defenseKuangCnt;
    /** 矿战战报（最近 N 场，表「保留最近N场矿战记录」）。 */
    public List<QkFightRecord> qkFightRecords = new ArrayList<>();
    /** 攻方阵亡武将：index + 复活截止毫秒（表 14400s）。 */
    public List<QkDeadWj> qkDeadWjs = new ArrayList<>();
    /** 登录 field1006 / S2C 2109：有未看战报 tip。 */
    public boolean qkFightRecordTip;
    /** 登录 field1007 / S2C 2108：有可领产出 tip。 */
    public boolean qkResourceTip;
    public int currentRegionId = 1;
    /**
     * 最近一次资源挑战的 REGION_TYPE（5/6/8/9/10）；0=主线/其它。
     * 结算用来写 starInfo，避免把难度 level 误当成主线 region 写进 stageStars。
     */
    public int lastResourceRegionType;
    /** 最近一次资源挑战难度 1–7（→ CCMsgResouceFBStarInfo.level）。 */
    public int lastResourceLevel = 1;
    /**
     * 进本时预 roll 的掉落（写进 S2C 401）；通关 {@code applyWin} 必须发同一份，避免结算 UI 与背包不一致。
     * 未进本或已结算后为 null。
     */
    public PendingFbReward pendingFbReward;

    public Progress progress = new Progress();
    public Map<String, Integer> bag = new LinkedHashMap<>();
    public List<Hero> heroes = new ArrayList<>();
    public List<Equipment> equipments = new ArrayList<>();
    public int nextEquipSeq = 1;
    /**
     * PVE(type0) 5 槽兼容字段；与 {@link #formationsByType}[0] 同步。
     * 其它玩法阵容只写 formationsByType，勿再只改本字段。
     */
    public List<String> formation = new ArrayList<>();
    /** eFormationType → 恰好 5 个武将 GUID（空串=空位）。 */
    public Map<Integer, List<String>> formationsByType = new LinkedHashMap<>();
    public Guild guild = new Guild();
    public Arena arena = new Arena();
    public Gacha gacha = new Gacha();
    public Economy economy = new Economy();
    public Tower tower = new Tower();
    public Map<Integer, ResourceFb> resourceFb = new LinkedHashMap<>();
    public Map<Integer, ShopState> shops = new LinkedHashMap<>();
    public Map<Integer, Soul> souls = new LinkedHashMap<>();
    public List<Mail> mails = new ArrayList<>();
    public int nextMailSeq = 1;
    public CloneRoom clone = new CloneRoom();
    /** 挑战赛 BOB（C2S 2001–2009）。 */
    public Bob bob = new Bob();
    /** 争霸战 ZBZ（S2C 4602 / C2S 4101–4108）。 */
    public Zbz zbz = new Zbz();
    /** 跨服战 KFZ 本服假跨服（S2C 4712/4715 / C2S 4201–4213）。 */
    public Kfz kfz = new Kfz();
    public Cards cards = new Cards();
    public JiBan jiban = new JiBan();
    public Tasks tasks = new Tasks();
    /** 日历签到（C2S 1401/1402）。 */
    public SignIn signIn = new SignIn();
    /** 活动页：七日登录 + 体力时段（S2C 2601 / C2S 2301·2302）。 */
    public Activity activity = new Activity();
    /** 限时神将（大厅独立入口；S2C 3702 / C2S 3302-3305）。 */
    public Ltsj ltsj = new Ltsj();
    /** 玩家变量：对话/引导已播等（CMsgDetailPlayerInfo.Varis / C2S 1801）。 */
    public Map<String, String> varis = new LinkedHashMap<>();
    /** 次日登录奖是否已领（C2S 2601 / S2C 3001）。 */
    public boolean isDrawSecondDayLoginPrized = false;
    /** 登录 CSMsgAccountEnterRet field 2。false=先进新手本(region 99)；回主城后置 true。 */
    public boolean alreadyNewUserGuideFB = false;
    /** 已兑换过的礼包码 key（C2S 20 / S2C 20）；码标了 oncePerAccount 时重复提交回 AlreadyGet。 */
    public List<String> usedGiftCodes = new ArrayList<>();
    /** 已成功使用过的激活码（C2S 30 / S2C 22 ret=2）。 */
    public List<String> usedActivationCodes = new ArrayList<>();
    /** 是否已推送过 S2C 21（激活码输入 UI 每号只弹一次，避免每次登录都弹）。 */
    public boolean activationPrompted = false;
    /** 最后离线时刻（ms）。S2C 1303 {@code CFriendBase.8 OfflineTime} 必须填合法
     *  {@code yyyy-MM-dd HH:mm:ss}，否则客户端 {@code FriendItem.cs:108} 的 ParseExact 会炸。 */
    public long lastLogoutAtMs = 0L;

    /**
     * 黑名单（C2S 503/504 → S2C 802）。
     * <p>客户端 {@code BlackList.cs:199-208} 的判等键是 {@code Guid && serverID}，
     * 所以回显时必须原样带上对方 guid 与本服 serverId。
     */
    public List<Black> blackList = new ArrayList<>();

    /** 好友 guid 列表（C2S 1403/1407/1408/1404/1416）。 */
    public List<Integer> friendIds = new ArrayList<>();
    /** 向我申请、待我处理的好友申请（C2S 1415/1416 → S2C 1314/1315）。 */
    public List<Integer> friendApplies = new ArrayList<>();
    /** 今日已赠送过体力的好友 guid（{@code CFriendInfo.IsGive=true}）。 */
    public List<Integer> friendGaveToday = new ArrayList<>();
    /** 体力领取状态：key = 对方 guid 字符串，value 1=可领 2=已领（{@code CFriendInfo.IsReceive}）。 */
    public Map<String, Integer> friendReceiveState = new LinkedHashMap<>();
    /** 今日已领取体力次数（客户端把 > 20 当错误，见 {@code FriendSystem.cs:251}）。 */
    public int friendReceivedToday = 0;
    /** 今日赠送体力次数（服务端上限，客户端不校验）。 */
    public int friendGaveCount = 0;
    /** 已领取的邀请档位人数（3/10/20/30/40）。 */
    public List<Integer> inviteClaimed = new ArrayList<>();
    /** 邀请我的玩家 guid（0 表示尚未被邀请）。 */
    public int inviterGuid = 0;
    /** 1320 {@code flushtime} 倒计时锚点(ms)，同时也是加好友推荐页的刷新锚点。 */
    public long friendRefreshAnchorMs = 0L;
    /** 上一次推荐列表返回的页码，供 1410「下一页」递增。 */
    public int friendPushPage = 0;

    /** 龙腾 / 限时兑换进度（大厅独立入口；C2S 3601-3603 / S2C 4101-4104）。 */
    public LtExchange ltExchange = new LtExchange();

    public void ensureCollections() {
        if (note == null || note.isEmpty()) {
            note = NOTE;
        }
        if (blackList == null) {
            blackList = new ArrayList<>();
        }
        if (friendIds == null) {
            friendIds = new ArrayList<>();
        }
        if (friendApplies == null) {
            friendApplies = new ArrayList<>();
        }
        if (friendGaveToday == null) {
            friendGaveToday = new ArrayList<>();
        }
        if (friendReceiveState == null) {
            friendReceiveState = new LinkedHashMap<>();
        }
        if (inviteClaimed == null) {
            inviteClaimed = new ArrayList<>();
        }
        if (ltExchange == null) {
            ltExchange = new LtExchange();
        }
        if (ltExchange.claimed == null) {
            ltExchange.claimed = new ArrayList<>();
        }
        if (account == null) {
            account = "";
        }
        if (roleName == null) {
            roleName = "";
        }
        if (createdAt == null) {
            createdAt = "";
        }
        if (progress == null) {
            progress = new Progress();
        }
        if (progress.stageStars == null) {
            progress.stageStars = new LinkedHashMap<>();
        }
        if (progress.stagePlayLimits == null) {
            progress.stagePlayLimits = new LinkedHashMap<>();
        }
        if (progress.lastBattleDifficulty == null || progress.lastBattleDifficulty.isEmpty()) {
            progress.lastBattleDifficulty = "normal";
        }
        if (bag == null) {
            bag = new LinkedHashMap<>();
        }
        if (heroes == null) {
            heroes = new ArrayList<>();
        }
        for (Hero h : heroes) {
            if (h != null) {
                h.ensureTimeStones();
            }
        }
        if (equipments == null) {
            equipments = new ArrayList<>();
        }
        if (nextEquipSeq <= 0) {
            nextEquipSeq = 1;
        }
        for (Equipment eq : equipments) {
            if (eq.id == null) {
                eq.id = "";
            }
            if (eq.ori == null) {
                eq.ori = "";
            }
            if (eq.owner == null) {
                eq.owner = "";
            }
            if (eq.level <= 0) {
                eq.level = 1;
            }
            if (eq.cuiLianParts == null || eq.cuiLianParts.length < 4) {
                boolean[] parts = new boolean[4];
                if (eq.cuiLianParts != null) {
                    System.arraycopy(eq.cuiLianParts, 0, parts, 0, eq.cuiLianParts.length);
                }
                eq.cuiLianParts = parts;
            }
        }
        if (formation == null) {
            formation = new ArrayList<>();
        }
        if (formationsByType == null) {
            formationsByType = new LinkedHashMap<>();
        }
        migrateFormationSlots();
        if (guild == null) {
            guild = new Guild();
        }
        if (guild.id == null) {
            guild.id = "";
        }
        if (guild.name == null) {
            guild.name = "";
        }
        if (guild.job == null || guild.job.isEmpty()) {
            guild.job = "none";
        }
        if (guild.bossPlayTimes == null) {
            guild.bossPlayTimes = new LinkedHashMap<>();
        }
        if (guild.bossPending == null) {
            guild.bossPending = new LinkedHashMap<>();
        }
        if (arena == null) {
            arena = new Arena();
        }
        if (arena.lastChallengeAt == null) {
            arena.lastChallengeAt = "";
        }
        if (arena.lastTimesResetDay == null) {
            arena.lastTimesResetDay = "";
        }
        if (arena.records == null) {
            arena.records = new ArrayList<>();
        }
        if (gacha == null) {
            gacha = new Gacha();
        }
        if (gacha.lastGoldFreeAt == null) {
            gacha.lastGoldFreeAt = "";
        }
        if (gacha.lastDiamondFreeAt == null) {
            gacha.lastDiamondFreeAt = "";
        }
        if (economy == null) {
            economy = new Economy();
        }
        if (economy.dailyKey == null) {
            economy.dailyKey = "";
        }
        if (economy.chapterChests == null) {
            economy.chapterChests = new LinkedHashMap<>();
        }
        if (economy.firstPayIds == null) {
            economy.firstPayIds = new ArrayList<>();
        }
        if (economy.payBuyCounts == null) {
            economy.payBuyCounts = new LinkedHashMap<>();
        }
        if (economy.payExtMonthKey == null) {
            economy.payExtMonthKey = "";
        }
        if (economy.vipAwardGetInfo == null) {
            economy.vipAwardGetInfo = "";
        }
        if (economy.vipBuyedGifts == null) {
            economy.vipBuyedGifts = "";
        }
        if (tower == null) {
            tower = new Tower();
        }
        if (tower.buddyName == null) {
            tower.buddyName = "";
        }
        if (tower.lastResetDate == null) {
            tower.lastResetDate = "";
        }
        if (resourceFb == null) {
            resourceFb = new LinkedHashMap<>();
        }
        if (shops == null) {
            shops = new LinkedHashMap<>();
        }
        if (souls == null) {
            souls = new LinkedHashMap<>();
        }
        if (clone == null) {
            clone = new CloneRoom();
        }
        if (bob == null) {
            bob = new Bob();
        }
        if (bob.cupPrized == null || bob.cupPrized.length < 4) {
            boolean[] cups = new boolean[4];
            if (bob.cupPrized != null) {
                System.arraycopy(bob.cupPrized, 0, cups, 0, bob.cupPrized.length);
            }
            bob.cupPrized = cups;
        }
        if (bob.roundNumber <= 0) {
            bob.roundNumber = 1;
        }
        if (bob.allHp == null) {
            bob.allHp = new ArrayList<>();
        }
        if (bob.targetGuids == null) {
            bob.targetGuids = new ArrayList<>();
        }
        if (bob.lockedAtkSlots == null) {
            bob.lockedAtkSlots = new ArrayList<>();
        }
        bob.lockedAtkSlots = padFiveSlots(bob.lockedAtkSlots);
        if (zbz == null) {
            zbz = new Zbz();
        }
        if (zbz.defenseWuJiangIds == null) {
            zbz.defenseWuJiangIds = new ArrayList<>();
        }
        if (zbz.bracket == null) {
            zbz.bracket = new ArrayList<>();
        }
        if (zbz.hadUsedWuJiangIds == null) {
            zbz.hadUsedWuJiangIds = new ArrayList<>();
        }
        if (zbz.bracketDay == null) {
            zbz.bracketDay = "";
        }
        if (zbz.matchFoeDeadRounds == null) {
            zbz.matchFoeDeadRounds = new ArrayList<>();
        }
        if (zbz.poolDay == null) {
            zbz.poolDay = "";
        }
        if (zbz.poolRobotGuids == null) {
            zbz.poolRobotGuids = new ArrayList<>();
        }
        if (zbz.poolRobots == null) {
            zbz.poolRobots = new ArrayList<>();
        }
        if (kfz == null) {
            kfz = new Kfz();
        }
        if (kfz.worshipedRanks == null) {
            kfz.worshipedRanks = new ArrayList<>();
        }
        if (kfz.worshipDay == null) {
            kfz.worshipDay = "";
        }
        if (kfz.seriesResults == null) {
            kfz.seriesResults = new ArrayList<>();
        }
        if (kfz.offenceWj == null) {
            kfz.offenceWj = new ArrayList<>();
        }
        if (kfz.dfzBracket == null) {
            kfz.dfzBracket = new ArrayList<>();
        }
        if (kfz.seriesDefHeroes == null) {
            kfz.seriesDefHeroes = new ArrayList<>();
        }
        if (kfz.seriesEquipTemplateGuids == null) {
            kfz.seriesEquipTemplateGuids = new ArrayList<>();
        }
        if (kfz.pysOpponentGuids == null) {
            kfz.pysOpponentGuids = new ArrayList<>();
        }
        if (kfz.pysOpponentDay == null) {
            kfz.pysOpponentDay = "";
        }
        if (kfz.pysEnemyResults == null) {
            kfz.pysEnemyResults = new ArrayList<>();
        }
        if (kfz.eligibleWeekId == null) {
            kfz.eligibleWeekId = "";
        }
        if (kfz.xiangXiReports == null) {
            kfz.xiangXiReports = new ArrayList<>();
        }
        if (kfz.paiWeiRankMailDay == null) {
            kfz.paiWeiRankMailDay = "";
        }
        if (kfz.dfzRankMailDay == null) {
            kfz.dfzRankMailDay = "";
        }
        if (cards == null) {
            cards = new Cards();
        }
        if (cards.teQuanEnd == null) {
            cards.teQuanEnd = "";
        }
        if (cards.awardDay == null) {
            cards.awardDay = "";
        }
        if (jiban == null) {
            jiban = new JiBan();
        }
        jiban.ensure();
        if (tasks == null) {
            tasks = new Tasks();
        }
        tasks.ensure();
        if (signIn == null) {
            signIn = new SignIn();
        }
        if (signIn.prizeStatus == null) {
            signIn.prizeStatus = new ArrayList<>();
        }
        if (activity == null) {
            activity = new Activity();
        }
        if (activity.gainedVpTypes == null) {
            activity.gainedVpTypes = new ArrayList<>();
        }
        if (activity.dailyChongZhiAwarded == null) {
            activity.dailyChongZhiAwarded = "";
        }
        if (activity.dailyCostAwarded == null) {
            activity.dailyCostAwarded = "";
        }
        if (activity.magicBox == null) {
            activity.magicBox = new MagicBox();
        }
        if (activity.magicBox.dateKey == null) {
            activity.magicBox.dateKey = "";
        }
        if (varis == null) {
            varis = new LinkedHashMap<>();
        }
        if (usedGiftCodes == null) {
            usedGiftCodes = new ArrayList<>();
        }
        if (usedActivationCodes == null) {
            usedActivationCodes = new ArrayList<>();
        }
        if (activity.magicBox.prize == null) {
            activity.magicBox.prize = new ArrayList<>();
        }
        if (activity.magicBox.cardPos == null) {
            activity.magicBox.cardPos = new ArrayList<>();
        }
        if (activity.magicBox.record == null) {
            activity.magicBox.record = new ArrayList<>();
        }
        if (ltsj == null) {
            ltsj = new Ltsj();
        }
        if (ltsj.dateKey == null) {
            ltsj.dateKey = "";
        }
        if (ltsj.wenZiExp == null) {
            ltsj.wenZiExp = new ArrayList<>();
        }
        if (ltsj.boxState == null) {
            ltsj.boxState = new ArrayList<>();
        }
        if (ltsj.boxOri == null) {
            ltsj.boxOri = new ArrayList<>();
        }
        if (ltsj.boxNum == null) {
            ltsj.boxNum = new ArrayList<>();
        }
        if (ltsj.wjSuiPianName == null) {
            ltsj.wjSuiPianName = "";
        }
        if (mainRoleIndex < 1 || mainRoleIndex > 2) {
            mainRoleIndex = 1;
        }
        if (signIn.lastSignDate == null) {
            signIn.lastSignDate = "";
        }
        if (economy.buildingProfitAt == null) {
            economy.buildingProfitAt = new LinkedHashMap<>();
        }
        if (economy.buildingProfitTotal == null) {
            economy.buildingProfitTotal = new LinkedHashMap<>();
        }
        if (economy.trainSlots == null) {
            economy.trainSlots = new ArrayList<>();
        }
        // 旧版单坑训练存档迁移：trainWjIndex>0 表示有武将正在训练，搬进坑位列表后清空旧字段。
        if (economy.trainWjIndex > 0 && economy.trainSlots.isEmpty()) {
            Economy.TrainSlot slot = new Economy.TrainSlot();
            slot.wjIndex = economy.trainWjIndex;
            slot.type = economy.trainType;
            slot.employerGuid = economy.trainEmployerGuid;
            slot.employerWj = economy.trainEmployerWj;
            slot.totalExp = economy.trainTotalExp;
            slot.startedAt = economy.trainStartedAt;
            slot.leftSec = economy.trainLeftSec;
            economy.trainSlots.add(slot);
        }
        economy.trainWjIndex = 0;
        economy.trainLeftSec = 0;
        economy.trainStartedAt = 0L;
        economy.trainType = 0;
        economy.trainEmployerWj = 0;
        economy.trainEmployerGuid = 0;
        economy.trainTotalExp = 0;
        if (mails == null) {
            mails = new ArrayList<>();
        }
        if (nextMailSeq <= 0) {
            nextMailSeq = 1;
        }
        for (Mail m : mails) {
            if (m != null && m.mailDynId >= nextMailSeq) {
                nextMailSeq = m.mailDynId + 1;
            }
            if (m != null && m.items == null) {
                m.items = new ArrayList<>();
            }
            if (m != null && m.paras == null) {
                m.paras = new ArrayList<>();
            }
            if (m != null && m.titleParm == null) {
                m.titleParm = new ArrayList<>();
            }
        }
        if (economy.mailGrantKeys == null) {
            economy.mailGrantKeys = new ArrayList<>();
        }
        if (economy.escortCarts == null) {
            economy.escortCarts = new ArrayList<>();
        }
        // 旧版单车存档迁移：lastCartId 非空且未领奖 = 有一辆在途镖车，搬进列表后清空旧字段。
        if (economy.lastCartId != null && !economy.lastCartId.isEmpty()
                && !economy.escortAwarded && economy.escortCarts.isEmpty()) {
            Economy.EscortCart cart = new Economy.EscortCart();
            cart.cartId = economy.lastCartId;
            cart.targetId = economy.escortTargetId;
            cart.sentAt = economy.escortSentAt;
            cart.hasBeenRaid = economy.escortHasBeenRaid;
            cart.beRaidCnt = economy.escortBeRaidCnt;
            cart.raiderName = economy.escortRaiderName == null ? "" : economy.escortRaiderName;
            economy.escortCarts.add(cart);
        }
        economy.lastCartId = "";
        economy.escortSentAt = 0L;
        economy.escortTargetId = 0;
        economy.escortAwarded = false;
        economy.escortHasBeenRaid = false;
        economy.escortBeRaidCnt = 0;
        economy.escortRaiderName = "";
        if (qkFightRecords == null) {
            qkFightRecords = new ArrayList<>();
        }
        if (qkDeadWjs == null) {
            qkDeadWjs = new ArrayList<>();
        }
    }

    public int lastBattleDifficultyCode() {
        return "hard".equals(progress.lastBattleDifficulty) ? 2 : 1;
    }

    public void setLastBattleDifficultyCode(int code) {
        progress.lastBattleDifficulty = code == 2 ? "hard" : "normal";
    }

    public static String stageKey(int regionId, int difficultyCode) {
        return regionId + "/" + (difficultyCode == 2 ? "hard" : "normal");
    }

    public int guildJobCode() {
        return jobCode(guild.job);
    }

    public void setGuildJobCode(int code) {
        guild.job = jobName(code);
    }

    public static int jobCode(String job) {
        if ("owner".equals(job)) {
            return 3;
        }
        if ("elder".equals(job)) {
            return 2;
        }
        if ("member".equals(job)) {
            return 1;
        }
        return 0;
    }

    public static String jobName(int code) {
        if (code == 3) {
            return "owner";
        }
        if (code == 2) {
            return "elder";
        }
        if (code == 1) {
            return "member";
        }
        return "none";
    }

    /** 旧档 formation → formationsByType[0]；双侧对齐。 */
    private void migrateFormationSlots() {
        List<String> legacy = padFiveSlots(formation);
        List<String> typed0 = formationsByType.get(Integer.valueOf(FORMATION_PVE));
        if (!hasAnyGuid(typed0) && hasAnyGuid(legacy)) {
            formationsByType.put(Integer.valueOf(FORMATION_PVE), new ArrayList<>(legacy));
        } else if (hasAnyGuid(typed0) && !hasAnyGuid(legacy)) {
            formation = new ArrayList<>(padFiveSlots(typed0));
        } else if (hasAnyGuid(typed0)) {
            formation = new ArrayList<>(padFiveSlots(typed0));
        } else {
            formation = legacy;
            formationsByType.put(Integer.valueOf(FORMATION_PVE), new ArrayList<>(legacy));
        }
    }

    /**
     * 读某 type 的 5 槽；该 type 从未单独写入则回退 PVE(type0)，避免 JJC 首次拉阵全空。
     */
    public List<String> formationSlots(int type) {
        if (formationsByType == null) {
            formationsByType = new LinkedHashMap<>();
        }
        if (formation == null) {
            formation = new ArrayList<>();
        }
        if (formationsByType.containsKey(Integer.valueOf(type))) {
            return padFiveSlots(formationsByType.get(Integer.valueOf(type)));
        }
        if (type != FORMATION_PVE && formationsByType.containsKey(Integer.valueOf(FORMATION_PVE))) {
            return padFiveSlots(formationsByType.get(Integer.valueOf(FORMATION_PVE)));
        }
        return padFiveSlots(formation);
    }

    /** 写入某 type 阵容；type0 同时回写兼容字段 formation。 */
    public void setFormationSlots(int type, List<String> slots) {
        if (formationsByType == null) {
            formationsByType = new LinkedHashMap<>();
        }
        List<String> five = padFiveSlots(slots);
        formationsByType.put(Integer.valueOf(type), five);
        if (type == FORMATION_PVE) {
            formation = new ArrayList<>(five);
        }
    }

    /** 遍历所有已存阵容槽（含兼容 formation），供清脏 GUID。 */
    public Iterable<List<String>> allFormationSlotLists() {
        if (formationsByType == null) {
            formationsByType = new LinkedHashMap<>();
        }
        migrateFormationSlots();
        List<List<String>> out = new ArrayList<>();
        out.add(formation);
        for (Map.Entry<Integer, List<String>> e : formationsByType.entrySet()) {
            if (e.getKey() != null && e.getKey().intValue() != FORMATION_PVE && e.getValue() != null) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    private static boolean hasAnyGuid(List<String> slots) {
        if (slots == null) {
            return false;
        }
        for (String s : slots) {
            if (s != null && !s.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> padFiveSlots(List<String> src) {
        List<String> five = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            if (src != null && i < src.size() && src.get(i) != null) {
                five.add(src.get(i));
            } else {
                five.add("");
            }
        }
        return five;
    }

    public Hero findHero(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Hero h : heroes) {
            if (id.equals(h.id)) {
                return h;
            }
        }
        return null;
    }

    public Hero findHeroByIndex(int index) {
        for (Hero h : heroes) {
            if (h.heroIndex == index) {
                return h;
            }
        }
        return null;
    }

    public Equipment findEquip(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Equipment e : equipments) {
            if (id.equals(e.id)) {
                return e;
            }
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QkFightRecord {
        public String attackerName = "";
        public String fightStartTime = "";
        /** EKuangType：1金/2银/3铜。 */
        public int type;
        public boolean isAttack;
        public boolean isZhanLing;
        public boolean isWin;
        public boolean isCoDefense;
        public int jinBiLose;
        public int rmbLose;
        /** 对手展示名/头像/等级（2120 对侧字段）。 */
        public String foeName = "";
        public int foeResId;
        public int foeLevel;
        /** 各回合伤害（来自 C2S 1908 FightRecords）。 */
        public List<QkFightRound> rounds = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QkFightRound {
        public boolean isWin;
        public List<QkFightHurt> selfHurts = new ArrayList<>();
        public List<QkFightHurt> targetHurts = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QkFightHurt {
        public int wjIndex;
        public int hurts;
        public int level;
        public int stage;
        public int stars;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QkDeadWj {
        public int wjIndex;
        public long reliveUntilMs;
        /**
         * 该病人是否已被义园医师紧急治疗过（S2C 1931 f3 {@code IsEmergencyTreatme}）。
         * 客户端 {@code BingRen.cs:60-71} 见到 true 会隐藏「可资料」、显示「已资料」并
         * **禁用资料按钮的 Collider** ⇒ 一个病人只能被治疗一次。武将再次阵亡时重置。
         */
        public boolean emergencyTreated;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PendingFbReward {
        public int region;
        public int difficult;
        public int gold;
        public int playerExp;
        public int wjExp;
        public int wnsp;
        public int yingPo;
        /** 发放用：含首通必掉（certain）那份。 */
        public List<PendingDrop> drops = new ArrayList<>();
        /**
         * 下发 401 用：**已扣掉首通必掉**，因为客户端在未通关时会自己再加一遍
         * （{@code NormalFBGoodsGrant.cs:174-268}、{@code DropGoodsManager.cs:215-236}）。
         */
        public List<PendingDrop> displayDrops = new ArrayList<>();
        /** 401 field5/6 用；{@code wnsp - firstWnspCount} / {@code yingPo - firstYingPoCount}。 */
        public int displayWnsp;
        public int displayYingPo;
        /**
         * {@code display*} 是否已按「扣掉首通必掉」算过。旧档（无此字段）为 false ⇒ 展示退回
         * {@link #drops} 全量 = 老行为，不会因为升级假服而把旧 pending 判成空。
         */
        public boolean displaySplit;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PendingDrop {
        public String ori = "";
        public int count;

        public PendingDrop() {
        }

        public PendingDrop(String ori, int count) {
            this.ori = ori == null ? "" : ori;
            this.count = count;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Progress {
        public int lastNormalStage;
        public int lastHardStage;
        /** normal 或 hard */
        public String lastBattleDifficulty = "normal";
        /** 键：12/normal ，值：星数 */
        public Map<String, Integer> stageStars = new LinkedHashMap<>();
        /**
         * 主线受限关每日次数（章内 3/6/9/10）。
         * 键同 stageStars；**仅跨日** ensureDaily 清空（同日重登保留 playLeft/buyTimes）。
         */
        public Map<String, StagePlayLimit> stagePlayLimits = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StagePlayLimit {
        /** 今日剩余可打/扫次数（→ CMsgFBStar.FBPlayTime） */
        public int playLeft;
        /** 今日已买重置次数（→ CMsgFBStar.curDayBuyTime） */
        public int buyTimes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"id", "heroIndex", "level", "exp", "stars", "stage",
            "stagePara1", "stagePara2", "stagePara3", "stagePara4", "fightPower",
            "skill1", "skill2", "skill3", "skill4", "timeStones", "timeStoneFx"})
    public static class Hero {
        public String id = "";
        /** 武将表 ID：18=黑剑士，28=精灵女王 */
        public int heroIndex;
        /** 武将等级，新建默认 1（账号等级另见外层 level） */
        public int level = 1;
        public int exp;
        public int stars = 1;
        /** 进阶阶段，0 起 */
        public int stage;
        public int stagePara1;
        public int stagePara2;
        public int stagePara3;
        public int stagePara4;
        public int fightPower = 1000;
        public int skill1 = 1;
        public int skill2 = 1;
        public int skill3 = 1;
        public int skill4 = 1;
        /** 7 槽时光石 OriName，空串=未镶 */
        public List<String> timeStones = new ArrayList<>();
        /** 7 槽效果序号 1–4 */
        public List<Integer> timeStoneFx = new ArrayList<>();

        public void ensureTimeStones() {
            if (timeStones == null) {
                timeStones = new ArrayList<>();
            }
            if (timeStoneFx == null) {
                timeStoneFx = new ArrayList<>();
            }
            while (timeStones.size() < 7) {
                timeStones.add("");
            }
            while (timeStoneFx.size() < 7) {
                timeStoneFx.add(Integer.valueOf(0));
            }
            if (timeStones.size() > 7) {
                timeStones = new ArrayList<>(timeStones.subList(0, 7));
            }
            if (timeStoneFx.size() > 7) {
                timeStoneFx = new ArrayList<>(timeStoneFx.subList(0, 7));
            }
            for (int i = 0; i < 7; i++) {
                if (timeStones.get(i) == null) {
                    timeStones.set(i, "");
                }
                if (timeStoneFx.get(i) == null) {
                    timeStoneFx.set(i, Integer.valueOf(0));
                }
            }
        }

        public String timeStone(int loc) {
            ensureTimeStones();
            if (loc < 0 || loc >= 7) {
                return "";
            }
            return timeStones.get(loc);
        }

        public int timeStoneFx(int loc) {
            ensureTimeStones();
            if (loc < 0 || loc >= 7) {
                return 0;
            }
            Integer v = timeStoneFx.get(loc);
            return v == null ? 0 : v.intValue();
        }

        public void setTimeStone(int loc, String ori, int fx) {
            ensureTimeStones();
            if (loc < 0 || loc >= 7) {
                return;
            }
            timeStones.set(loc, ori == null ? "" : ori);
            timeStoneFx.set(loc, Integer.valueOf(Math.max(0, fx)));
        }

        public int stagePara(int slot) {
            if (slot == 1) {
                return stagePara1;
            }
            if (slot == 2) {
                return stagePara2;
            }
            if (slot == 3) {
                return stagePara3;
            }
            if (slot == 4) {
                return stagePara4;
            }
            return 0;
        }

        public void setStagePara(int slot, int value) {
            if (slot == 1) {
                stagePara1 = value;
            } else if (slot == 2) {
                stagePara2 = value;
            } else if (slot == 3) {
                stagePara3 = value;
            } else if (slot == 4) {
                stagePara4 = value;
            }
        }

        public int skill(int index) {
            if (index == 1) {
                return skill1;
            }
            if (index == 2) {
                return skill2;
            }
            if (index == 3) {
                return skill3;
            }
            if (index == 4) {
                return skill4;
            }
            return 0;
        }

        public void setSkill(int index, int value) {
            if (index == 1) {
                skill1 = value;
            } else if (index == 2) {
                skill2 = value;
            } else if (index == 3) {
                skill3 = value;
            } else if (index == 4) {
                skill4 = value;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"id", "ori", "owner", "level", "stars", "guhua", "uplevelGold",
            "openCuiLian", "cuiLianParts", "jingLianLevel", "jingLianExp"})
    public static class Equipment {
        public String id = "";
        public String ori = "";
        /** 穿在该武将 id 上；空串=未穿 */
        public String owner = "";
        public int level = 1;
        public int stars;
        public int guhua;
        public int baseScore;
        public int uplevelGold;
        public boolean openCuiLian;
        public boolean[] cuiLianParts = new boolean[]{false, false, false, false};
        public int jingLianLevel;
        public int jingLianExp;
        public int jingLianSubLevel1;
        public int jingLianSubExp1;
        public int jingLianSubLevel2;
        public int jingLianSubExp2;
        public int pendingXiLianType;
        public int pendingXiLianValue;
        public int xiLianType;
        public int xiLianValue;

        public int cuiLianStatus() {
            int n = openCuiLian ? 1 : 0;
            boolean[] parts = cuiLianParts == null ? new boolean[4] : cuiLianParts;
            for (int i = 0; i < parts.length && i < 4; i++) {
                if (parts[i]) {
                    n += (int) Math.pow(10, i + 1);
                }
            }
            return n;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Guild {
        public String id = "";
        public String name = "";
        /** none / member / elder / owner */
        public String job = "none";
        public int donateLeft = 10;
        public int contribution;
        /**
         * 「玩家提供的公会成长值」尚未折算成贡献的余数（不足 {@code Union.txt:17} 的 30 时留存）。
         *
         * <p>来源：{@code Union.txt:11}「成员消耗1点体力值公会获得的成长值(必须为整形)」=1 ⇒
         * 每次消耗体力只给公会 +1 成长值，而 30 点成长值才换 1 点贡献，不累加永远换不到贡献。</p>
         */
        public int contributionGrowRemainder;
        public int brotherCoin;
        public int courageCoin;
        /** 当日公会 Boss 已打次数，key=章节 chapterId。跨日清空。 */
        public Map<Integer, Integer> bossPlayTimes = new LinkedHashMap<>();
        /**
         * 已发 1528（开打）、尚未收到对应 1529（结算）的凭据，key=章节 chapterId。
         *
         * <p>{@code bossPlayTimes} 是客户端「已打次数」显示（{@code UnionBossWarInfo.cs:153}、
         * {@code WarRoomMainDialog.cs:228} 用 {@code mBossMaxCiShu - playedTimes} 判挑战按钮），
         * 不能拿它当凭据减回去；1529 只认这里的计数，每次结算消耗 1。</p>
         */
        public Map<Integer, Integer> bossPending = new LinkedHashMap<>();
        public String lastKitchenStaminaAt = "";
        /** 退出/被踢出公会后可再加入的时刻（毫秒）。退会 CD 28800s、被踢 7200s（Union.txt）。 */
        public long quitUnionAt;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Arena {
        public int rank = 9999;
        public int wins;
        public int challengesLeft = 5;
        /** 每日切磋剩余（JJC_Common「每日竞技场排行榜切磋次数」=10）；与挑战次数分槽。 */
        public int qieCuoLeftTimes = 10;
        public int payResetCount;
        public String lastChallengeAt = "";
        /**
         * 次数/付费重置日清标记（yyyy-MM-dd）。
         * 不能绑 lastChallengeAt：清 CD 会把 lastChallenge 置空，跨日就刷不掉 payReset。
         */
        public String lastTimesResetDay = "";
        public int lastTargetGuid;
        /** 最近战报（最多 10，对齐 JJC_Common）。 */
        public List<JjcFightRecord> records = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JjcFightRecord {
        public int targetGuid;
        public int resId;
        public String name = "";
        public int level;
        public boolean win;
        public int rankChange;
        public String fightTime = "";
        /** 结算 C2S 1705 SelfHurt / TargetHurt；详情 2012 回放伤害条。 */
        public List<Integer> selfHurt = new ArrayList<>();
        public List<Integer> targetHurt = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Gacha {
        public String lastGoldFreeAt = "";
        public String lastDiamondFreeAt = "";
        public int goldFreeLeft = 5;
        public int diamondTenCount;
        /** 钻石（黄金宝箱）单抽次数。0 = 下一次单抽走首次必得猫妖弓箭手 */
        public int diamondOnceCount;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Economy {
        public String dailyKey = "";
        public int buyTiLiToday;
        public int buyJinBiToday;
        public int chargedDiamond;
        public int rmbChongZhi;
        /** 当日累计充值 RMB（EAttriType=17，CMsgDetailPlayerInfo field49），每日清零。 */
        public int curDayChongZhiRmb;
        /** 当日累计消耗钻石（EAttriType=18，CMsgDetailPlayerInfo field50），每日清零。 */
        public int curDayCostZuanShi;
        /** 是否已领首充大回馈（CMsgDetailPlayerInfo field46 HasAward1stChongZhi）。 */
        public boolean hasGet1stChongZhiAward;
        /** VIP 等级礼包已领记录，格式 |1||2|（与客户端 isRewardGoodsGeted 一致）。 */
        public String vipAwardGetInfo = "";
        /**
         * VIP 礼包（看板 type5）已购记录，格式 {@code |1||2|}，与客户端
         * {@code ActivityMainUI.mCurBoughtVIPGifts}（{@code :4606}）同格式、同判法（Contains）。
         * 由 S2C 2609 {@code curBoughtVIPGifts} 下发；购买成功时追加。**不重置**
         * （真服是否按活动周期清空无数据，见 {@code tables\vip-gift.json} 的「已购口径」）。
         */
        public String vipBuyedGifts = "";
        /**
         * 各充值档累计已购次数。key=goodsId。既是限次赠送进度，也是 {@code userPayInfo} 里
         * {@code buyCount} 的来源（客户端 {@code GetExtAwardTimeLeft()} 用它倒数、{@code GetCur1stChongZhiRMB()}
         * 用列表里的档位反推「是否已首充」）。**不跨月清空**（2026-10-04 拍板：首充双倍不按月重置）；
         * 只有 admin {@code resetPayExt} 会清。
         */
        public Map<Integer, Integer> payBuyCounts = new LinkedHashMap<>();
        /** @deprecated 已不再跨月清空（首充双倍不按月重置）；保留字段仅为读旧档。 */
        public String payExtMonthKey = "";
        /** @deprecated 改用 payBuyCounts；读档兼容：非空则视作买过 1 次。 */
        public List<Integer> firstPayIds = new ArrayList<>();
        public Map<Integer, Integer> chapterChests = new LinkedHashMap<>();
        public int kitchenStaminaLeft = 1;
        public int escortRaidLeft = 3;
        /**
         * 在途镖车列表（APK 允许**同时多辆**）。依据：
         * <ul>
         *   <li>1942 {@code CCMsgRequestMaJiuInfoRet} f7 {@code myBiaoCheInfo} 是
         *       {@code List<string>}（{@code pyfoot\tmp_msgdll\NetProto\CCMsgRequestMaJiuInfoRet.cs:107-108}）
         *       ——「我派遣过的镖车 GUID 列表」；</li>
         *   <li>客户端 {@code EmBattleSystem.cs:2653} 用
         *       {@code myBiaoCheInfo.Count >= VipManager.FaBiaoCount} 做发镖门控 ⇒ 发镖额度是
         *       **同时在途数上限**（{@code VipCfg.txt} 第 27 列：VIP0-10=1、VIP11+=2）；</li>
         *   <li>{@code MaJiuBasePaiQianUI.cs:123-125} 收到 1938 后把新车 GUID **追加**进该列表。</li>
         * </ul>
         * 改前是 9 个单值字段（lastCartId/escortSentAt/…），VIP11+ 的第二辆车会覆盖第一辆，
         * 当时的保底是「在途时拒发第二辆」⇒ 客户端按 FaBiaoCount 显示还剩 1 次、点了却没反应。
         */
        public List<EscortCart> escortCarts = new ArrayList<>();
        /** 旧版单车字段：仅作存档迁移输入，见 {@link PlayerRecord#ensureCollections()}。 */
        @Deprecated
        public String lastCartId = "";
        @Deprecated
        public long escortSentAt;
        @Deprecated
        public int escortTargetId;
        @Deprecated
        public boolean escortAwarded;
        @Deprecated
        public boolean escortHasBeenRaid;
        @Deprecated
        public int escortBeRaidCnt;
        @Deprecated
        public String escortRaiderName = "";
        /**
         * 我**成功劫镖**的次数（1936 f5 / 1939 f2 {@code myRaidSucTime}）。
         * 客户端 {@code MaJiuLanJieDuiWuUI.cs:278} 拿它与 {@code VipManager.JieBiaoCount}
         * 比较来决定还能不能继续拦截车队，所以这里必须是**成功次数**。
         */
        public int escortRaidSucTimes;
        /**
         * 本次押镖活动已用掉的**有效掠夺场次**（1545 结算一次算一次，成功与失败都算）。
         * 上限 = {@code UnionMaJiuBase.txt}「单次活动有效掠夺场次（不管成功还是失败） 3」
         * （{@code UnionCfg.raidTimes()}）。它与 {@link #escortRaidLeft} 是**两套独立上限**：
         * <ul>
         *   <li>{@link #escortRaidLeft} = 成功次数上限，按 {@code VipCfg.txt} 第 29 列「劫镖次数」
         *       （VIP0-6=1、VIP7-14=2、VIP15=3），客户端 {@code MaJiuLanJieDuiWuUI.cs:264}
         *       用 {@code myRaidSucTime >= VipManager.JieBiaoCount} 做同一门控；</li>
         *   <li>本字段 = 出手次数上限（3 次），**客户端没有任何对应字段**（1936/1943 都只带成功次数）
         *       ⇒ 只能服务端拦，拦到时回 1939 ret=4（与「成功额度用完」同一档）。</li>
         * </ul>
         */
        public int escortRaidTimes;
        /** 本次劫镖目标玩家 GUID（1544 f2 {@code raidPlayerGUID}，1545 结算时定位被劫方）。 */
        public String raidTargetId = "";
        /** 本次劫镖目标**镖车 GUID**（1544 f3 {@code targetBiaoCheGUID}）：一人可能多辆车，必须记车。 */
        public String raidTargetCartId = "";
        /**
         * 本次押镖活动「成功防守」累计获得的金币奖金（1943 f6 defenceAward.jinBi）。
         * 每次防守成功按「线路金币 × {@code UnionMaJiuBase.txt} 第 12 行 defenceSucAwardRate」
         * 累加；客户端 {@code MobileGameDemo\MaJiuGetAwardUI.cs:159-169} 只在 {@code jinBi > 0}
         * 时显示该行，标题 100806「你成功守住了来自 {0}工会 的拦截，获得一笔奖金！」。
         */
        public int escortDefendGold;
        /**
         * 成功防守时来犯者所属公会名（1943 f6 defenceAward.defendedUnion）。
         * 客户端拼成 {@code defendedUnion + StrTable.getStr(100785) = "工会"}，
         * 故这里必须是**劫掠方**公会名，不是自己的公会名。
         */
        public String escortDefendUnion = "";
        /** 劫镖累计战利品（1941 排行榜 / 1943 奖励）。 */
        public int raidJinbiTotal;
        public int raidXdbTotal;
        public int raidJinShiTotal;
        /** 本次押镖活动已刷新次数（1936 f3 curResetTime）。 */
        public int majiuResetTimes;
        /**
         * 公会战最近一次进攻的据点号（1564 FightUnionPvPFormation f1 记下，1567 FightUnionPvPResult 用）。
         * 1567 的请求体只有 {@code IsWin}，服务端必须自己记住「打的是哪个据点」才能判定攻占，
         * 否则只能按表序取第一个未攻占点（连刷可 0→9 全占）。跨日重置为 -1。
         */
        public int lastPvpPoint = -1;
        /** 公会战最近一次进攻选中的防守方阵容下标（1564 f2），胜利时用来消耗该防守部队。 */
        public int lastPvpFormation = -1;
        /** 本次押镖活动已领奖（1936 f6 hasGetAward）。 */
        public boolean majiuAwarded;
        /** 押镖活动归属日（yyyy-MM-dd），跨日重置用。 */
        public String majiuDay = "";
        /**
         * 公会训练场**坑位**（每坑一个武将，可同时训练多个）。坑位数 = {@code UnionXunLianPits.txt}
         * 「训练场等级 → 最大坑位」= 1 级 2 个、2 级 3 个 … 7 级 8 个（客户端
         * {@code UnionTrainRoomInfo.cs:18-19} 用训练场建筑等级取该表铺 {@code listTrainInfo}）。
         *
         * <p>改前是「每人一个全局训练槽」（trainWjIndex/trainLeftSec/…），与出厂表矛盾：满级训练场
         * 8 个坑却只能同时练 1 个武将。1934 f1 是**按坑位顺序**的 repeated（客户端
         * {@code UnionTrainRoomInfo.cs:27-47 listTrainInfo[j] = wjInfos[j]}），1538/1540 只带
         * {@code wjIndex}（不带坑号）⇒ 服务端按 wjIndex 认坑。</p>
         */
        public List<TrainSlot> trainSlots = new ArrayList<>();
        /** 旧版单坑训练字段：仅作存档迁移输入，见 {@link PlayerRecord#ensureCollections()}。 */
        @Deprecated
        public int trainWjIndex;
        @Deprecated
        public int trainLeftSec;
        /** 旧版训练开始时刻（毫秒）。 */
        @Deprecated
        public long trainStartedAt;
        /** 旧版训练类型 ETrainingType（0 普通/1 白银/2 黄金/3 铂金）。 */
        @Deprecated
        public int trainType;
        /** 旧版训练时雇佣的教练武将（0=无）。 */
        @Deprecated
        public int trainEmployerWj;
        @Deprecated
        public int trainEmployerGuid;
        /** 旧版训练累计获得经验（1934 f2 TotalExp）。 */
        @Deprecated
        public int trainTotalExp;

        /**
         * 一个训练坑位（{@code Economy.trainSlots} 元素）。字段与客户端
         * {@code CXunLianWJInfo} 一一对应：wjIndex/totalExp/trainingType/leftTime + 教练三件套。
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class TrainSlot {
            /** 受训武将配置 ID（1934 f1 / 1538 f1 / 1540 f1）。 */
            public int wjIndex;
            /** ETrainingType：0 普通 / 1 白银 / 2 黄金 / 3 铂金。 */
            public int type;
            /** 教练所属玩家 ID（0=无教练）。 */
            public int employerGuid;
            /** 教练武将配置 ID（0=无教练）。 */
            public int employerWj;
            /** 训练累计获得经验（1934 f2 TotalExp，面板显示用）。 */
            public int totalExp;
            /** 开始时刻（毫秒），按真实时间推进倒计时。 */
            public long startedAt;
            /** 剩余秒数（由 startedAt 与训练时长推算，落盘仅为可读性）。 */
            public int leftSec;
            /**
             * 本次训练的**实际时长**（秒）：有教官时按教官战力缩短（用户 m20090 #2 拍板，
             * 见 {@code UnionService.trainSecWithCoach}），无教官时 = {@code UnionXunLian.txt}
             * 「训练时长(秒)」28800。0 = 旧存档，按表值兜底。
             *
             * <p>只缩短**倒计时**，经验仍按整场（{@code UnionXunLian.txt} 表值时长）计算 ——
             * 客户端训练面板的总经验是它自己用 {@code trainTimeLong}（表值）算的
             * （{@code TrainInfo.cs:28-30}），缩短时长后经验若跟着缩水，两边显示会不一致。
             */
            public int totalSec;
        }

        /**
         * 一辆在途镖车（{@code Economy.escortCarts} 元素）。字段与客户端
         * {@code CCMsgPlayerBiaoCheInfo}（1937/1938 里的一行）一一对应。
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class EscortCart {
            /** 镖车 GUID（1942 f7 / 1938 f1；1544 f3 {@code targetBiaoCheGUID} 用它定位这一辆车）。 */
            public String cartId = "";
            /** 目的地（{@code UnionMaJiuTarget.txt} 行号，1543 f1 {@code targetID}）。 */
            public int targetId;
            /** 发车时刻（毫秒）。 */
            public long sentAt;
            /** 已领奖/已结算（从 1942 f7 {@code myBiaoCheInfo} 移除）。 */
            public boolean awarded;
            /**
             * 本车已被打劫成功（防守方全灭）。1942 f4 一行 f1 {@code hasBeenRaid}：
             * 客户端 {@code MaJiuLanJieDuiWuUI.cs:117-122} 据此隐藏「拦截」按钮并把守方画成阵亡，
             * {@code MaJiuBaseLanJieUI.cs:79-85} 用它数「还有几辆可劫」；1939 ret=2
             * （StrTable 100793「他已经被别人打劫过了，再没啥可捞的了」）也据此拒。
             */
            public boolean hasBeenRaid;
            /** 本车被掠夺次数（{@code CCMsgPlayerBiaoCheInfo} f6 {@code beRaidTime}，StrTable 100797）。 */
            public int beRaidCnt;
            /**
             * 正在拦截本车的玩家名（1942 f4 f2 {@code raidingPlayerName}）。非空且未结算 =
             * 拦截进行中：客户端 {@code MaJiuLanJieDuiWuUI.cs:139-142} 显示「掠夺中」，
             * 服务端对同车的第二个请求回 1939 ret=1（StrTable 100794「有人正在打劫他」）。
             */
            public String raiderName = "";
            /**
             * 本次拦截的发起时刻（毫秒），用于 {@code UnionMaJiuBase.txt}「单场掠夺超时时间（秒）」300s
             * 的**服务端兜底**：客户端战斗自己会在 300s 到点结束并发 1545，但如果玩家在战斗中掉线，
             * 服务端永远收不到 1545，{@link #raiderName} 会挂一整天 ⇒ 该车当天对所有人都是
             * 1939 ret=1「有人正在打劫他」、被劫方整天显示「掠夺中」。超过 300s + 宽限后由服务端清掉。
             * 旧档没有该字段（= 0）时按「首次见到即从现在起算」处理。
             */
            public long raidStartedAt;
        }

        /** 已发过的定时邮件键，如 2026-08-18/jjc-rank */
        public List<String> mailGrantKeys = new ArrayList<>();
        /** 建筑类型 → 上次领金币毫秒 */
        public Map<Integer, Long> buildingProfitAt = new LinkedHashMap<>();
        /**
         * 建筑类型 → 已领取收益累计（金币）。对应出厂表 {@code tables\Union.txt:16}
         * 「单个建筑玩家总收益上限」= 3000000：键名是「**总**收益上限」，且 3000000 ÷ 1500/小时
         * （{@code UnionBuildingLevelUp.txt} 第 8 列全表最大，L7 的厨房/训练场/马厩）= 2000 小时
         * ≈ 83 天，作单次领取上限实际不可达 ⇒ 按累计上限理解（语义仍需真服数据确认）。
         */
        public Map<Integer, Long> buildingProfitTotal = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"kind", "mailDynId", "mailType", "title", "sender", "text", "sendTime",
            "handled", "grantKey", "gold", "diamond", "stamina", "exp", "jjcScore",
            "wannengFragments", "yingPo", "moFaChen", "brotherCoin", "courageCoin", "items"})
    public static class Mail {
        /** sys=系统邮件（竞技场排名） gm=自定义标题正文 */
        public String kind = "gm";
        public int mailDynId;
        /** ESysMailTypeID，sys 用。1=JJC 排名奖 */
        public int mailType;
        public String title = "";
        public String sender = "";
        public String iconAtlas = "";
        public String iconSprite = "";
        public String text = "";
        public String sendTime = "";
        public boolean handled;
        public String grantKey = "";
        public int gold;
        public int diamond;
        public int stamina;
        public int exp;
        public int jjcScore;
        public int wannengFragments;
        public int yingPo;
        public int moFaChen;
        public int brotherCoin;
        public int courageCoin;
        public List<String> paras = new ArrayList<>();
        /** 标题模板参数（sys 邮件，field 13 TitleParm）：mailType=2 模板标题是 {0}，用它替换。 */
        public List<String> titleParm = new ArrayList<>();
        public List<MailItem> items = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MailItem {
        public String ori = "";
        public int count = 1;
        public int stars;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tower {
        public int historyLayer = 1;
        public int curLayer = 1;
        public int challengeTimes;
        public String buddyName = "";
        /** 助战对方 playerId；0=无。 */
        public int buddyGuid;
        /** 待接受邀请的发起方 playerId；0=无。点聊天超链 C2S 3202 才互绑。 */
        public int pendingInviteFromGuid;
        /** 上次周重置日期（TimeNow−5h 的日历日）。 */
        public String lastResetDate = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceFb {
        public int regionType;
        /** 今日已用次数（→ CCMsgResouceFBData.playTime）；剩余=VIP上限−playTime。日清归 0。 */
        public int playTime;
        public int buyTimes;
        public String lastPlay = "";
        /** 难度 level → 最高星（→ starInfo：1 level / 2 starCount）。 */
        public Map<Integer, Integer> stars = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ShopState {
        public int type;
        public int freeRefresh;
        public int payRefresh;
        public float lastSys;
        /** 上海 yyyyMMddHH，上次整点换货档；0 表示旧档仅有 lastSys。 */
        public int lastSysSlot;
        /**
         * 免费/付费刷新次数所属的「商店游戏日」键（yyyy-MM-dd，5:00 为界）。
         *
         * <p>依据 {@code tables\ShopCommom.txt} 公会商店行第 16 列「重置每日免费刷新次数时间」= 5（时）：
         * 客户端 {@code ShopPropertyCfg.cs:26} 解析了该列但**无消费方**，所以重置时刻纯服务端口径；
         * 改前挂在 0 点日历日块里，0:00–5:00 这一段会提前把当天次数还回去。现该小时由
         * {@code EconomyTables.shopFreeRefreshResetHour(type)} 读表给出（缺列回落 5）。</p>
         */
        public String freeRefreshDay = "";
        public List<ShopField> fields = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ShopField {
        public String ori = "";
        public int count = 1;
        public int price;
        public boolean rmb;
        public boolean sellOut;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Soul {
        public boolean composed;
        public int stage;
        public int exp;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CloneRoom {
        public boolean inRoom;
        public int targetResId;
        public boolean allowQuickJoin = true;
        /** 进房时 roll 的胜场武将碎片 Ori；结算与 5801 预览同源。 */
        public String rewardFragOri;
    }

    /** 挑战赛进度。roundNumber 必须 ≥1，否则客户端会乱开 BOB 地图。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bob {
        public int roundNumber = 1;
        public boolean forceExit;
        public int resetTimes;
        public int lastTargetGuid;
        /** 4 个奖杯是否已领（对应 cup 0–3）。 */
        public boolean[] cupPrized = new boolean[4];
        /**
         * 连战残血：C2S 2006 AttackerFormaton（job→阵容 guid）写入；
         * S2C 2202 CMsgAllWuJiangHpAndEnergy 回放。
         */
        public List<BobHp> allHp = new ArrayList<>();
        /**
         * 本轮 10 个目标 robot targetGuid（C2S 2002 按己方战力比例抽好后锁定，
         * 直到重置；避免每关重抽导致 Brief/开战不一致）。
         */
        public List<Integer> targetGuids = new ArrayList<>();
        /**
         * 本轮开战时锁定的 BOB 攻阵 5 槽 + 当时战力；闪退重进/改阵不得改变本轮对手匹配与残血映射。
         * 重置清空。
         */
        public List<String> lockedAtkSlots = new ArrayList<>();
        public int lockedMyFp;
        /**
         * C2S 2005 开战置 true，2006 结算消费后清 false；2009/重置也清。
         * 防止未开战硬刷 2006 IsWin 连领十关奖。
         */
        public boolean awaitingResult;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BobHp {
        public String guid = "";
        public int curHp;
        public int curEnergy;
    }

    /** 争霸战。turnOn=true；curState 由假服按 ZhengBaZhan 日时段推算（1排位/2下注/3八强/4四强/5决赛）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Zbz {
        public boolean turnOn = true;
        public int turnOnDay;
        public int curState = 1;
        public int curTargetPlayerId;
        public int xingJi = 1;
        public int jiFen;
        public boolean hadDefense;
        /** C2S 4105 / S2C 4602.9：布防武将 GUID（须满 10）；被挑战/超时 forceFinish 按此序 1v1 防守。 */
        public List<String> defenseWuJiangIds = new ArrayList<>();
        /** Top8 对阵条（8 人）；空则按当前阶段重建。 */
        public List<ZbzBracketPlayer> bracket = new ArrayList<>();
        /** 本届括号日期 yyyy-MM-dd。 */
        public String bracketDay = "";
        /** Top8 已用武将 GUID（S2C 4604.5）。 */
        public List<String> hadUsedWuJiangIds = new ArrayList<>();
        public int gambleTargetId;
        /** 1 金币 / 2 钻石。 */
        public int gambleMoneyType;
        /** 下注时展示赔率（用于决战赔付）。 */
        public float gambleRate;
        public boolean gamblePaid;
        public boolean jueZhanAwarded;
        public int lastPushedState = -1;
        /** 本场对决当前轮（1-based）；0=无进行中对决；整场结束后置 11 对齐 APK curRoundCnt>10 收口。 */
        public int matchCurRound;
        /** 本场敌方防守链长度（轮数上限）。 */
        public int matchDefenseCount;
        /** 本场已击败的敌方轮次（1-based），对应 DefenseWuJiang.State=0。 */
        public List<Integer> matchFoeDeadRounds = new ArrayList<>();
        /** 本场已整场结算（防 Continue→4104 重开刷奖）。 */
        public boolean matchSettled;
        /** 刚结算完的对手 guid（CurTarget 已清 0；4104 收口仍认此 id）。 */
        public int lastSettledTargetId;
        /** 下注：已押金币 / 已押钻石（可双押→gambletype=3）。 */
        public boolean gambleGoldPaid;
        public boolean gambleDiamondPaid;
        /** 当日参战机器人池日期 yyyy-MM-dd（产品：每日随机 50–100）。 */
        public String poolDay = "";
        /** 当日参战机器人 targetGuid 列表（与 poolRobots 同步，兼容旧档）。 */
        public List<Integer> poolRobotGuids = new ArrayList<>();
        /** 当日参战机器人真实积分（战斗模拟落盘，非展示假分）。 */
        public List<ZbzPoolRobot> poolRobots = new ArrayList<>();
    }

    /** 池内机器人积分档（排位榜/匹配/Top8 候选共用）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ZbzPoolRobot {
        public int guid;
        public int jiFen;
        public int xingJi = 1;
        /** 最近一场对玩家：玩家击杀数 → 排位条 KillNum1。 */
        public int lastKillByPlayer;
        /** 最近一场对玩家：机器人击杀数 → 排位条 KillNum2。 */
        public int lastKillByRobot;
        /** 最近一场对玩家：bFight。 */
        public boolean lastBFight;
        /** 最近一场对玩家：Win 0未出/1左胜(玩家胜)/2右胜(玩家负)。 */
        public int lastWin;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ZbzBracketPlayer {
        public int playerId;
        public String name = "";
        public int resId;
        public int level;
        public int jiFen;
        public int jueZhanGroup;
        public int jueZhanRank = 8;
        public int jueZhanPos;
        /** 0 未出结果 / 1 胜 / 2 负。 */
        public int win;
        public boolean bFight;
        public int killNum1;
        public int killNum2;
    }

    /** 跨服战本服假数据。phase 默认跟 KuaFuZhanBase 日时段；可 Admin 覆盖。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Kfz {
        /** KFZPhase：0 None / 1 PaiWeiSai / 2–7 等待 / 8–13 巅峰。 */
        public int phase = 1;
        /** ≥0 时跳过日时段，强制该 phase；-1=跟钟。 */
        public int phaseOverride = -1;
        /** 排位名次；巅峰进行中 status 推 0；未入围显示用 -1（客户端 tip）。 */
        public int rank = 50;
        public boolean hasFightingDfz;
        public int lastTargetGuid;
        public int score;
        public int stars;
        /** 已膜拜名次；按 worshipDay 日清。 */
        public List<Integer> worshipedRanks = new ArrayList<>();
        public String worshipDay = "";
        /** 对同一对手的三场：0 未赛 / 2 我胜 / 3 我负。 */
        public int seriesTargetGuid;
        /** 0=机器人 / 1=真人。 */
        public int seriesTargetKind;
        public List<Integer> seriesResults = new ArrayList<>();
        /** 三场防阵武将 index 扁平 15 个（每场 5，互不重复）。 */
        public List<Integer> seriesDefHeroes = new ArrayList<>();
        /**
         * 机器人三场装备/等级模板 robotGuid（三场互异）；真人时全 0。
         * fromRobotHeroes 按场次用对应模板的 equipLib。
         */
        public List<Integer> seriesEquipTemplateGuids = new ArrayList<>();
        /** 当日排位对手 guid 列表（真人优先，不足补机器人）。 */
        public List<Integer> pysOpponentGuids = new ArrayList<>();
        public String pysOpponentDay = "";
        /**
         * 当日排位对手系列终局（灌 4704 status/kill/score）。
         * status：3=我胜 / 4=我负（对齐 APK OneFightItem）。
         */
        public List<KfzPysEnemyResult> pysEnemyResults = new ArrayList<>();
        /** 当前系列累计击杀 / 积分 Δ（系列结束写入 pysEnemyResults）。 */
        public int seriesMyKills;
        public int seriesFoeKills;
        public int seriesMyScoreDelta;
        /** 本周是否入围（周日 21:00 JJC 前十快照）；Admin 可 override。 */
        public boolean eligibleThisWeek;
        public String eligibleWeekId = "";
        public int jjcRankAtSnapshot;
        /** Admin 强制入围（假服单机调试）。 */
        public boolean eligibleOverride;
        /** 本场开战用的自定义 guid（&gt;1e6），避开客户端整队读 JJC_Robot。 */
        public int lastFightGuid;
        /** 本场对应三场下标 0..2。 */
        public int fightMatchIndex;
        /** 进攻布阵武将状态（index=heroIndex）。 */
        public List<KfzWjState> offenceWj = new ArrayList<>();
        /** 巅峰括号（64）；晋级后 finalEight 时 4711 只下发仍在打的≤8。 */
        public List<KfzDfzSlot> dfzBracket = new ArrayList<>();
        public boolean dfzFinalEight;
        /** 详细战报（攻+被攻），容量上限见 KuaFuZhanBase.maxBattleRecordCnt。 */
        public List<KfzXiangXi> xiangXiReports = new ArrayList<>();
        /** 排位赛日排名邮已发标记日（YYYY-MM-DD）；邮件 key 另见 mailGrantKeys。 */
        public String paiWeiRankMailDay = "";
        public String dfzRankMailDay = "";
        /** 捏造 NPC 养成补齐版本（装备/技能 schema）；见 KfzNpcBootstrap.NPC_SCHEMA。 */
        public int npcSchema;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KfzWjState {
        public int index;
        public boolean hasPlayed;
        public boolean isDead;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KfzDfzSlot {
        public int guid;
        public String name = "";
        public int resId;
        public int level;
        /** 0=仍在打；非 0=淘汰名次（64/32/16/8/4/2/1）。 */
        public int rank;
        public boolean hasFailed;
    }

    /** 跨服详细战报一条（灌 4702 XiangXi）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KfzXiangXi {
        public int type;
        public int rank;
        public String winner = "";
        public int winnerServer = 1;
        public String loser = "";
        public int loserServer = 1;
    }

    /** 排位赛程一条对手终局（S2C 4704 PYSSaiChengItem）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KfzPysEnemyResult {
        public int targetGuid;
        /** 3=我胜 / 4=我负。 */
        public int status;
        public int myKillCount;
        public int otherKillCount;
        public int myScore;
        public int otherScore;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Cards {
        public boolean zhiZun;
        public String teQuanEnd = "";
        public boolean todayZhiZun;
        public boolean todayTeQuan;
        public String awardDay = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiBan {
        public List<Integer> buddies = new ArrayList<>();
        public List<JiBanSlot> slots = new ArrayList<>();

        public void ensure() {
            if (buddies == null) {
                buddies = new ArrayList<>();
            }
            if (slots == null) {
                slots = new ArrayList<>();
            }
            while (slots.size() < 10) {
                slots.add(new JiBanSlot());
            }
            for (JiBanSlot s : slots) {
                if (s != null) {
                    s.ensure();
                }
            }
        }

        public JiBanSlot slot(int index) {
            ensure();
            if (index < 0 || index >= slots.size()) {
                return slots.get(0);
            }
            return slots.get(index);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiBanSlot {
        public List<TianFu> tianFu = new ArrayList<>();

        public void ensure() {
            if (tianFu == null) {
                tianFu = new ArrayList<>();
            }
            while (tianFu.size() < 3) {
                tianFu.add(new TianFu());
            }
            // 对齐客户端：孔0=武将天赋(type0)；孔1/2=攻/防石(type1/2)。脏档 type∉{0,1,2} 校正。
            for (int i = 0; i < tianFu.size(); i++) {
                TianFu t = tianFu.get(i);
                if (t == null) {
                    t = new TianFu();
                    tianFu.set(i, t);
                }
                if (i == 0) {
                    t.type = 0;
                } else if (t.type != 1 && t.type != 2) {
                    t.type = i == 1 ? 1 : 2;
                }
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TianFu {
        public boolean locked;
        public int star;
        public int type;
        public int roleId;
        public int attack;
        public int defend;
        public int hp;
        public boolean hasNew;
        public int newStar;
        public int newRoleId;
        public int newAttack;
        public int newDefend;
        public int newHp;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tasks {
        public Map<Integer, DailyTask> daily = new LinkedHashMap<>();
        public Map<Integer, OnceTask> once = new LinkedHashMap<>();

        public void ensure() {
            if (daily == null) {
                daily = new LinkedHashMap<>();
            }
            if (once == null) {
                once = new LinkedHashMap<>();
            }
        }

        public DailyTask daily(int id) {
            ensure();
            DailyTask t = daily.get(Integer.valueOf(id));
            if (t == null) {
                t = new DailyTask();
                t.id = id;
                daily.put(Integer.valueOf(id), t);
            }
            return t;
        }

        public OnceTask once(int id) {
            ensure();
            OnceTask t = once.get(Integer.valueOf(id));
            if (t == null) {
                t = new OnceTask();
                t.id = id;
                once.put(Integer.valueOf(id), t);
            }
            return t;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    /** 日历签到（逐月）。 */
    public static class SignIn {
        /** 所在年。 */
        public int year;
        /** 所在月。 */
        public int month;
        /** 本月累计已领取天数（领奖时 +1）。 */
        public int totalDays;
        /** 当月每天状态 1..天数：1=未领取 / 2=已领取 / 3=已领取VIP双倍。勿记 0（APK 客户端把 0 画成已领取图章）。 */
        public List<Integer> prizeStatus = new ArrayList<>();
        /** 最近一次签到日期 yyyy-MM-dd。 */
        public String lastSignDate = "";
    }

    /** 活动页（七日登录 + 体力充电 + 每日累计充值 + 神域魔盒）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Activity {
        /** 已领七日天数 0–7；下一可领日 = day7Count+1。 */
        public int day7Count;
        /** 今日是否已领七日奖。 */
        public boolean today7Day;
        /** 今日已领体力时段类型（1 午餐 / 2 晚餐 / 3 夜宵）。 */
        public List<Integer> gainedVpTypes = new ArrayList<>();
        /** 每日累计充值(type4)当日已领档，格式 |6||30|（对齐客户端 curDayAwardedItems），每日清零。 */
        public String dailyChongZhiAwarded = "";
        /** 消耗返利(type6)当日已领档，格式 |2000||5000|（对齐客户端 curDayAwardedItems），每日清零。 */
        public String dailyCostAwarded = "";
        /** 神域魔盒(type22)状态。 */
        public MagicBox magicBox = new MagicBox();
        /**
         * 开服狂欢（7 日狂欢 S2C 6201 / 半月庆典 6202 / 领取 5403）已领记录。
         * <p>格式 {@code "day|type|ID;"} 拼接（如 {@code "3|1|1;4|6|1;"}）。
         * <p>用字符串而不是新嵌套对象，是为了<b>不动存档 schema</b>
         * （{@code @JsonPropertyOrder} 只列了顶层 {@code "activity"}，加字段不需要改它）。
         */
        public String kfHappyClaimed = "";
    }

    /** 黑名单一条（C2S 503/504 上送、S2C 802 回显；判等键 = guid + serverId）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Black {
        public int guid;
        public String name = "";
        public int resId;
        public int level = 1;
        public int serverId;
    }

    /** 龙腾 / 限时兑换进度（C2S 3601-3603）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LtExchange {
        /** 累计抽奖次数，用于排行名次（假服无原厂榜单表）。 */
        public int drawCount;
        /** 累计兑换次数。 */
        public int exchangeCount;
        /** 已兑换过的商品 ori，用于标记 ExchangeGoods 的已兑换态。 */
        public List<String> claimed = new ArrayList<>();
        /** 最近一次抽奖时间(ms)。 */
        public long lastDrawAtMs;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MagicBox {
        /** 本组奖品生成的日期 yyyy-MM-dd；跨日换新组。 */
        public String dateKey = "";
        /** 客户端 XiPaiState：1=新牌展示中（可洗牌/查看），0=已洗牌可开抽。 */
        public int xiPaiState;
        /** 当日已翻牌次数（决定下一次魔瓶消耗翻倍）。 */
        public int countToday;
        /** 9 格奖品。 */
        public List<MbPrize> prize = new ArrayList<>();
        /** 洗牌后的位置映射 CardPos→RealPos（prize 下标）；未洗牌时为空=同序。 */
        public List<MbCardPos> cardPos = new ArrayList<>();
        /** 中奖记录（近 recordKeep 条）。 */
        public List<MbRecord> record = new ArrayList<>();
    }

    /** 魔盒格子奖品（持久化；state 0 未抽 1 已抽）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MbPrize {
        /**
         * 上线档位（1 = 最高档，与物品真实品质 1..5 相反），由
         * {@code ActivityService.mbWireQuality} 写入；只用于回包，不是物品品质。
         */
        public int quality;
        public int type;
        public String goodsname = "";
        public int count;
        public int state;
        public int star;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MbCardPos {
        public int cardPos;
        public int realPos;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MbRecord {
        /** 与 {@link MbPrize#quality} 同一档位口径（1 = 最高档）。 */
        public int quality;
        public int type;
        public String goodsname = "";
        public int count;
        public int star;
    }

    /** 限时神将（每天按 heroes 配置轮换当期神将；跨日重置免费次数并重开当期）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ltsj {
        /** 当期状态归属日期 yyyy-MM-dd；跨日免费次数重置并按配置轮换当期神将。 */
        public String dateKey = "";
        /** 当期神将 heroIndex（与 dateKey 对应；由 ltsj.json heroes 按天轮换算出）。 */
        public int heroIndex;
        /** 当日免费单抽剩余次数。 */
        public int freeLeft;
        /** 四个字的经验（每个 0..WenZiMaxExp）。 */
        public List<Integer> wenZiExp = new ArrayList<>();
        /** 四宝箱状态：0 未激活 / 1 可领 / 2 已领。 */
        public List<Integer> boxState = new ArrayList<>();
        /** 当期宝箱奖励的**宝箱本体** ori（ltsj.json boxes[].ori，如 BX191；内容物由 C2S 3101 开箱发放）。 */
        public List<String> boxOri = new ArrayList<>();
        /** 当期宝箱奖励数量。 */
        public List<Integer> boxNum = new ArrayList<>();
        /** 神将状态：0 未可领 / 1 可领 / 2 已领。 */
        public int wjState;
        /** 神将领取结算：>0 表示发碎片（已拥有该英雄时，按 APK 折算表发该将 composeStar 对应的碎片数）。 */
        public int wjSuiPianCount;
        /** 神将领取结算碎片 ori（发碎片时用）。 */
        public String wjSuiPianName = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyTask {
        public int id;
        public int finishTimes;
        public boolean prized;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OnceTask {
        public int id;
        public int finishTimes;
        public boolean deleted;
    }
}
