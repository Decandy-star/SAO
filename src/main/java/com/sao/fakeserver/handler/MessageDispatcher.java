package com.sao.fakeserver.handler;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.service.ActivityService;
import com.sao.fakeserver.service.ArenaService;
import com.sao.fakeserver.service.BobService;
import com.sao.fakeserver.service.ChatService;
import com.sao.fakeserver.service.CityPosService;
import com.sao.fakeserver.service.CloneService;
import com.sao.fakeserver.service.CultivateService;
import com.sao.fakeserver.service.DrawService;
import com.sao.fakeserver.service.DungeonService;
import com.sao.fakeserver.service.FightSyncService;
import com.sao.fakeserver.service.FightWireTap;
import com.sao.fakeserver.service.FriendService;
import com.sao.fakeserver.service.GiftCodeService;
import com.sao.fakeserver.service.KfHappyService;
import com.sao.fakeserver.service.KfzService;
import com.sao.fakeserver.service.LaBaService;
import com.sao.fakeserver.service.LoginService;
import com.sao.fakeserver.service.LtExchangeService;
import com.sao.fakeserver.service.LtsjService;
import com.sao.fakeserver.service.MailService;
import com.sao.fakeserver.service.MineService;
import com.sao.fakeserver.service.PayService;
import com.sao.fakeserver.service.ProgressService;
import com.sao.fakeserver.service.ShopService;
import com.sao.fakeserver.service.SideService;
import com.sao.fakeserver.service.SignInService;
import com.sao.fakeserver.service.SecondLoginDayService;
import com.sao.fakeserver.service.TaskService;
import com.sao.fakeserver.service.UnionService;
import com.sao.fakeserver.service.VipGiftService;
import com.sao.fakeserver.service.ZbzService;
import com.sao.fakeserver.service.ZhuanPanService;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MessageDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    private final LoginService login;
    private final DungeonService dungeon;
    private final DrawService draw;
    private final UnionService union;
    private final ArenaService arena;
    private final CultivateService cultivate;
    private final ShopService shop;
    private final PayService pay;
    private final MineService mine;
    private final SideService side;
    private final MailService mail;
    private final CloneService clone;
    private final TaskService task;
    private final CityPosService cityPos;
    private final ProgressService progress;
    private final SignInService signIn;
    private final SecondLoginDayService secondLoginDay;
    private final ActivityService activity;
    private final LtsjService ltsj;
    private final BobService bob;
    private final ZbzService zbz;
    private final KfzService kfz;
    private final VipGiftService vipGift;
    private final FightSyncService fightSync;
    private final FightWireTap fightWire;
    private final PlayerStore playerStore;
    private final GiftCodeService giftCode;
    private final ZhuanPanService zhuanPan;
    private final KfHappyService kfHappy;
    private final ChatService chat;
    private final LaBaService laBa;
    private final LtExchangeService ltExchange;
    private final FriendService friend;

    public MessageDispatcher(LoginService login, DungeonService dungeon, DrawService draw,
                             UnionService union, ArenaService arena, CultivateService cultivate,
                             ShopService shop, PayService pay, MineService mine, SideService side,
                             MailService mail, CloneService clone, TaskService task,
                             CityPosService cityPos, ProgressService progress, SignInService signIn,
                             SecondLoginDayService secondLoginDay, ActivityService activity,
                             LtsjService ltsj, BobService bob, ZbzService zbz, KfzService kfz,
                             VipGiftService vipGift,
                             FightSyncService fightSync, FightWireTap fightWire, PlayerStore playerStore,
                             GiftCodeService giftCode, ZhuanPanService zhuanPan, KfHappyService kfHappy,
                             ChatService chat, LaBaService laBa, LtExchangeService ltExchange,
                             FriendService friend) {
        this.login = login;
        this.dungeon = dungeon;
        this.draw = draw;
        this.union = union;
        this.arena = arena;
        this.cultivate = cultivate;
        this.shop = shop;
        this.pay = pay;
        this.mine = mine;
        this.side = side;
        this.mail = mail;
        this.clone = clone;
        this.task = task;
        this.cityPos = cityPos;
        this.progress = progress;
        this.signIn = signIn;
        this.secondLoginDay = secondLoginDay;
        this.activity = activity;
        this.ltsj = ltsj;
        this.bob = bob;
        this.zbz = zbz;
        this.kfz = kfz;
        this.vipGift = vipGift;
        this.fightSync = fightSync;
        this.fightWire = fightWire;
        this.playerStore = playerStore;
        this.giftCode = giftCode;
        this.zhuanPan = zhuanPan;
        this.kfHappy = kfHappy;
        this.chat = chat;
        this.laBa = laBa;
        this.ltExchange = ltExchange;
        this.friend = friend;
    }

    public void dispatch(GameSession session, GamePacket pkt) {
        session.setWireTap(fightWire);
        if (session.isJjcFightWire()) {
            fightWire.onC2S(session, pkt);
        }
        if (session.player() != null) {
            progress.tickSkillPointRecovery(session, pkt, session.player());
        }
        switch (pkt.msgId) {
            case MsgIds.C2S_INIT:
                login.onInit(session, pkt);
                break;
            case MsgIds.C2S_HEARTBEAT:
                login.onHeartbeat(session, pkt);
                break;
            case MsgIds.C2S_GIFT_PACK:
                giftCode.onRequestGiftPack(session, pkt);
                break;
            case MsgIds.C2S_ACTIVATION_CODE:
                giftCode.onActivationCode(session, pkt);
                break;
            case MsgIds.C2S_ACCOUNT_ENTER:
                login.onAccountEnter(session, pkt);
                break;
            case MsgIds.C2S_CREATE_ROLE:
                login.onCreateRole(session, pkt);
                break;
            case MsgIds.C2S_MODIFY_ROLE_NAME:
                login.onModifyRoleName(session, pkt);
                break;
            case MsgIds.C2S_BACK_MAIN_CITY:
                login.onBackMainCity(session, pkt);
                break;
            case MsgIds.C2S_ENTITY_PHY_UPDATE:
                cityPos.onPhyUpdate(session, pkt);
                break;
            case MsgIds.C2S_FIRST_ENTER_REGION:
                login.onFirstEnterRegion(session, pkt);
                break;
            case MsgIds.C2S_REQUEST_FORMATION:
                login.onRequestFormation(session, pkt);
                break;
            case MsgIds.C2S_CHANGE_FORMATION:
                dungeon.onChangeFormation(session, pkt);
                break;
            case MsgIds.C2S_DAILY_TASK:
                task.onDailyList(session, pkt);
                break;
            case MsgIds.C2S_PRIZE_DAILY_TASK:
                task.onPrizeDaily(session, pkt);
                break;
            case MsgIds.C2S_ONCE_TASK:
                task.onOnceList(session, pkt);
                break;
            case MsgIds.C2S_PRIZE_ONCE_TASK:
                task.onPrizeOnce(session, pkt);
                break;
            case MsgIds.C2S_REQUEST_MAIL:
                mail.onRequest(session, pkt);
                break;
            case MsgIds.C2S_HANDLE_MAIL:
                mail.onHandle(session, pkt);
                break;
            case MsgIds.C2S_QIANDAO_INFO:
                signIn.onInfo(session, pkt);
                break;
            case MsgIds.C2S_QIANDAO_PRIZE:
                signIn.onPrize(session, pkt);
                break;
            case MsgIds.C2S_UPDATE_PLAYER_VARIS:
                progress.onUpdatePlayerVaris(session, pkt);
                break;
            case MsgIds.C2S_ACTIVITY_QUERY:
                activity.onQuery(session, pkt);
                break;
            case MsgIds.C2S_ACTIVITY_7DAY:
                activity.on7Day(session, pkt);
                break;
            case MsgIds.C2S_ACTIVITY_VP:
                activity.onVp(session, pkt);
                break;
            case MsgIds.C2S_DAILY_CHONGZHI_QUERY:
                activity.onDailyChongZhiQuery(session, pkt);
                break;
            case MsgIds.C2S_DAILY_CHONGZHI_AWARD:
                activity.onDailyChongZhiAward(session, pkt);
                break;
            case MsgIds.C2S_DAILY_COST_QUERY:
                activity.onDailyCostQuery(session, pkt);
                break;
            case MsgIds.C2S_DAILY_COST_AWARD:
                activity.onDailyCostAward(session, pkt);
                break;
            case MsgIds.C2S_FIRST_CHONGZHI_QUERY:
                activity.onFirstChongZhiQuery(session, pkt);
                break;
            case MsgIds.C2S_FIRST_CHONGZHI_AWARD:
                activity.onFirstChongZhiAward(session, pkt);
                break;
            case MsgIds.C2S_VIP_GIFT_CONFIG_QUERY:
                vipGift.onConfigQuery(session, pkt);
                break;
            case MsgIds.C2S_VIP_GIFT_BOUGHT_QUERY:
                vipGift.onBoughtQuery(session, pkt);
                break;
            case MsgIds.C2S_VIP_GIFT_BUY:
                vipGift.onBuy(session, pkt);
                break;
            case MsgIds.C2S_MAGIC_BOX_INFO:
                activity.onMagicBoxQuery(session, pkt);
                break;
            case MsgIds.C2S_MAGIC_BOX_RESET:
                activity.onMagicBoxReset(session, pkt);
                break;
            case MsgIds.C2S_MAGIC_BOX_XIPAI:
                activity.onMagicBoxXiPai(session, pkt);
                break;
            case MsgIds.C2S_MAGIC_BOX_GIVE_PRIZE:
                activity.onMagicBoxGivePrize(session, pkt);
                break;
            // §6-9：C2S 3302 客户端零发送点（APK 全产物无 Send 站点）。handler 有意保留：
            // 若某客户端变体发它，能拿到正确全量信息；删除零收益。见报告 §2.3 [A] 表。
            case MsgIds.C2S_LTSJ_INFO:
                ltsj.onQuery(session, pkt);
                break;
            case MsgIds.C2S_LTSJ_DRAW_ONCE:
                ltsj.onDrawOnce(session, pkt);
                break;
            case MsgIds.C2S_LTSJ_DRAW_TEN:
                ltsj.onDrawTen(session, pkt);
                break;
            case MsgIds.C2S_LTSJ_GET_PRIZE:
                ltsj.onGetPrize(session, pkt);
                break;
            case MsgIds.C2S_SECOND_LOGIN_DAY_PRIZE:
                secondLoginDay.onGetPrize(session, pkt);
                break;
            case MsgIds.C2S_RECONNECT:
                login.onReconnect(session, pkt);
                break;
            case MsgIds.C2S_ENTER_FB:
                dungeon.onEnterFb(session, pkt);
                break;
            case MsgIds.C2S_ENTER_UNTOUCHABLE:
                dungeon.onEnterResourceLike(session, pkt, 0, DungeonService.RT_UNTOUCHABLE);
                break;
            case MsgIds.C2S_ENTER_SANTA:
                dungeon.onEnterResourceLike(session, pkt, 0, DungeonService.RT_SANTA);
                break;
            case MsgIds.C2S_ENTER_SCYTHE:
                dungeon.onEnterResourceLike(session, pkt, 0, DungeonService.RT_SCYTHE);
                break;
            case MsgIds.C2S_RESULT_FB:
            case MsgIds.C2S_RESULT_UNTOUCHABLE:
            case MsgIds.C2S_RESULT_SANTA:
            case MsgIds.C2S_RESULT_SCYTHE:
                dungeon.onResultFb(session, pkt);
                break;
            case MsgIds.C2S_SAO_DANG:
                dungeon.onSaoDang(session, pkt);
                break;
            case MsgIds.C2S_SAO_DANG_RESOURCE:
                dungeon.onSaoDangResource(session, pkt);
                break;
            case MsgIds.C2S_RESULT_HUNDRED_TOWER:
                dungeon.onResultTower(session, pkt);
                break;
            case MsgIds.C2S_SAO_DANG_HUNDRED_TOWER:
                dungeon.onSaoDangTower(session, pkt);
                break;
            case MsgIds.C2S_COMPOSE_WUJIANG:
                cultivate.onComposeWuJiang(session, pkt);
                break;
            case MsgIds.C2S_CHANGE_AVATAR:
                cultivate.onChangeAvatar(session, pkt);
                break;
            case MsgIds.C2S_USE_GOODS_ADD_WJ_EXP:
                dungeon.onUseGoodsExp(session, pkt);
                break;
            case MsgIds.C2S_UP_WUJIANG_STAR:
                cultivate.onUpStar(session, pkt);
                break;
            case MsgIds.C2S_WUJIANG_JINJIE_SLOT:
                cultivate.onJinJieSlot(session, pkt);
                break;
            case MsgIds.C2S_WUJIANG_JINJIE:
                cultivate.onJinJieConfirm(session, pkt);
                break;
            case MsgIds.C2S_WUJIANG_SKILL_UP:
                cultivate.onSkillUp(session, pkt);
                break;
            case MsgIds.C2S_JINJIE_BOOK_COMPOSE:
                cultivate.onJinJieBookCompose(session, pkt);
                break;
            case MsgIds.C2S_BUY_SKILL_POINTS:
                cultivate.onBuySkillPoints(session, pkt);
                break;
            case MsgIds.C2S_COMPOSE_EQUIP:
                cultivate.onComposeEquip(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_PUT_ON:
                cultivate.onPutOn(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_PUT_OFF:
                cultivate.onPutOff(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_AUTO_PUT_ON:
                cultivate.onAutoPutOn(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_AUTO_PUT_OFF:
                cultivate.onAutoPutOff(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_LEVEL_UP:
                cultivate.onLevelUp(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_AUTO_LEVEL_UP:
                cultivate.onAutoLevelUp(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_STAR_UP:
                cultivate.onStarUp(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_DECOMPOSE:
                cultivate.onDecompose(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_ONE_KEY_LEVEL_UP:
                cultivate.onOneKeyLevelUp(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_GUHUA:
                cultivate.onGuhua(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_OPEN_CUILIAN:
                cultivate.onOpenCuiLian(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_CUILIAN_PART:
                cultivate.onCuiLianPart(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_FEIYUE:
                cultivate.onFeiYue(session, pkt);
                break;
            case MsgIds.C2S_CREATE_UNION:
                union.onCreate(session, pkt);
                break;
            case MsgIds.C2S_UNION_LIST:
                union.onList(session, pkt);
                break;
            case MsgIds.C2S_JOIN_UNION:
                union.onJoin(session, pkt);
                break;
            case MsgIds.C2S_UNION_DETAIL:
                union.onDetail(session, pkt);
                break;
            case MsgIds.C2S_UNION_REQUESTERS:
                union.onRequesters(session, pkt);
                break;
            case MsgIds.C2S_HANDLE_UNION_REQUESTER:
                union.onHandleRequester(session, pkt);
                break;
            case MsgIds.C2S_APPOINT_ELDER:
                union.onAppointElder(session, pkt);
                break;
            case MsgIds.C2S_CHANGE_UNION_OWNER:
                union.onChangeOwner(session, pkt);
                break;
            case MsgIds.C2S_MODIFY_UNION_NOTICE:
                union.onModifyNotice(session, pkt);
                break;
            case MsgIds.C2S_MODIFY_UNION_ICON:
                union.onModifyIcon(session, pkt);
                break;
            case MsgIds.C2S_MODIFY_UNION_JOIN_TYPE:
                union.onModifyJoinType(session, pkt);
                break;
            case MsgIds.C2S_MODIFY_UNION_JOIN_LEVEL:
                union.onModifyJoinLevel(session, pkt);
                break;
            case MsgIds.C2S_KICK_UNION_MEMBER:
                union.onKickMember(session, pkt);
                break;
            case MsgIds.C2S_UNION_MEMBER_INFO:
                union.onMemberInfo(session, pkt);
                break;
            case MsgIds.C2S_BUILDING_ALL_EMPLOYERS:
                union.onBuildingAllEmployers(session, pkt);
                break;
            case MsgIds.C2S_REPLACE_BUILDING_EMPLOYER:
                union.onReplaceEmployer(session, pkt);
                break;
            case MsgIds.C2S_CANCEL_BUILDING_LEVEL_UP:
                union.onCancelLevelUp(session, pkt);
                break;
            case MsgIds.C2S_EMPLOY_WJ_ZZS:
                union.onEmployWjZzs(session, pkt);
                break;
            // 公会战 PvP（1549–1568 → 1946–1965）：按 APK 补齐协议链路，配对/真实对局数据待真服。
            case MsgIds.C2S_UNION_PVP_ENROLL:
                union.onPvpEnroll(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_IS_ENROLL:
                union.onPvpIsEnroll(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_DEF_FORMATION:
                union.onPvpDefFormation(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_UPDATE_DEF_FORMATION:
                union.onPvpUpdateDefFormation(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_ALL_DEF_FORMATION:
                union.onPvpAllDefFormation(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_FIGHT_POWER_RANK:
                union.onPvpFightPowerRank(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_GROW_VALUE_RANK:
                union.onPvpGrowValueRank(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_FIGHT_RANK:
                union.onPvpFightRank(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_BRIEF_FIGHT_POWER:
                union.onPvpUnionBriefFightPower(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_BRIEF_GROW_VALUE:
                union.onPvpUnionBriefGrowValue(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_FIGHT_RECORD:
                union.onPvpFightRecord(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_MY_DEF_POINT_BRIEF:
                union.onPvpMyDefPointBrief(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_TARGET_DEF_POINT_BRIEF:
                union.onPvpTargetDefPointBrief(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_LEFT_DEF_FORMATION:
                union.onPvpLeftDefFormation(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_DEF_POINT_DETAIL:
                union.onPvpDefPointDetail(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_FIGHT_FORMATION:
                union.onPvpFightFormation(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_POINT_IS_BE_ATTACKED:
                // 1565 客户端从不发送（APK 全产物无发送点），保留空路由仅为常量对称。
                // 其回包常量 S2C 1962 已按 §6-9 删除（两侧皆死）；本条 C2S 有意保留。
                break;
            case MsgIds.C2S_UNION_PVP_WJ_HP:
                union.onPvpWjHp(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_FIGHT_RESULT:
                union.onPvpFightResult(session, pkt);
                break;
            case MsgIds.C2S_UNION_PVP_FIGHT_RECORD_LIST:
                union.onPvpFightRecordList(session, pkt);
                break;
            case MsgIds.C2S_DESTROY_UNION:
                union.onDestroy(session, pkt);
                break;
            case MsgIds.C2S_QUIT_UNION:
                union.onQuit(session, pkt);
                break;
            case MsgIds.C2S_UNION_PLAYER_RES:
                union.onPlayerRes(session, pkt);
                break;
            case MsgIds.C2S_UNION_DONATE:
                union.onDonate(session, pkt);
                break;
            case MsgIds.C2S_UNION_EMPLOYERS:
                union.onEmployers(session, pkt);
                break;
            case MsgIds.C2S_DRAW_BUILDING_PROFIT:
                union.onDrawProfit(session, pkt);
                break;
            case MsgIds.C2S_UNION_BUILDINGS:
                union.onBuildings(session, pkt);
                break;
            case MsgIds.C2S_UNION_BUILDING_LEVEL_UP:
                union.onBuildingLevelUp(session, pkt);
                break;
            case MsgIds.C2S_UNION_BOSS_INFO:
                union.onBossInfo(session, pkt);
                break;
            case MsgIds.C2S_UNION_BOSS_FIGHT:
                union.onBossFight(session, pkt);
                break;
            case MsgIds.C2S_UNION_BOSS_RESULT:
                union.onBossResult(session, pkt);
                break;
            case MsgIds.C2S_MAJIU_INFO:
                union.onMajiuInfo(session, pkt);
                break;
            case MsgIds.C2S_MAJIU_MY_BIAO:
                union.onMajiuMyBiao(session, pkt);
                break;
            case MsgIds.C2S_JJC_INFO:
                arena.onInfo(session, pkt);
                break;
            case MsgIds.C2S_JJC_REFRESH:
                arena.onRefresh(session, pkt);
                break;
            case MsgIds.C2S_JJC_TARGET_DETAIL:
                arena.onTargetDetail(session, pkt);
                break;
            case MsgIds.C2S_JJC_FIGHT:
                fightWire.start(session, "C2S_JJC_FIGHT");
                fightWire.onC2S(session, pkt);
                fightSync.clear(session);
                if (arena.onFight(session, pkt) && session.player() != null) {
                    fightSync.beginBattle(session, session.player().arena.lastTargetGuid,
                            PlayerRecord.FORMATION_JJC_ATK);
                } else {
                    fightWire.stop(session, "C2S_JJC_FIGHT_REFUSE");
                }
                break;
            case MsgIds.C2S_JJC_RESULT:
                arena.onResult(session, pkt);
                fightSync.clear(session);
                fightWire.stop(session, "C2S_JJC_RESULT");
                break;
            case MsgIds.C2S_JJC_CLEAR_CD:
                arena.onClearCd(session, pkt);
                break;
            case MsgIds.C2S_JJC_RESET_TIMES:
                arena.onResetTimes(session, pkt);
                break;
            case MsgIds.C2S_JJC_RANK:
                arena.onRank(session, pkt);
                break;
            case MsgIds.C2S_JJC_RANK_DETAIL:
                arena.onRankDetail(session, pkt);
                break;
            case MsgIds.C2S_JJC_FIGHT_RECORD:
                arena.onFightRecord(session, pkt);
                break;
            case MsgIds.C2S_JJC_FIGHT_RECORD_DETAIL:
                arena.onFightRecordDetail(session, pkt);
                break;
            case MsgIds.C2S_JJC_MY_RANK:
                arena.onMyRank(session, pkt);
                break;
            case MsgIds.C2S_JJC_QIECUO:
                arena.onQieCuo(session, pkt);
                break;
            case MsgIds.C2S_JJC_QIECUO_RESULT:
                arena.onQieCuoResult(session, pkt);
                break;
            case MsgIds.C2S_FIGHT_POWER_RANK:
                arena.onFightPowerRank(session, pkt);
                break;
            case MsgIds.C2S_REMOTE_PLAYER_BRIEF:
                arena.onRemotePlayerBrief(session, pkt);
                break;
            case MsgIds.C2S_REMOTE_PLAYER_WJ_DETAIL:
                arena.onRemotePlayerWjDetail(session, pkt);
                break;
            case MsgIds.C2S_REMOTE_PLAYER_BRIEF_CLICK:
                arena.onRemotePlayerBriefByClick(session, pkt);
                break;
            case MsgIds.C2S_REMOTE_PLAYER_QIECUO_CLICK:
                arena.onQieCuoInfoByClick(session, pkt);
                break;
            case MsgIds.C2S_BOB_SELF_INFO:
                bob.onSelfInfo(session, pkt);
                break;
            case MsgIds.C2S_BOB_TARGETS:
                bob.onTargets(session, pkt);
                break;
            case MsgIds.C2S_BOB_ALL_HP:
                bob.onAllHp(session, pkt);
                break;
            case MsgIds.C2S_BOB_TARGET_BRIEF:
                bob.onTargetBrief(session, pkt);
                break;
            case MsgIds.C2S_BOB_FIGHT:
                bob.onFight(session, pkt);
                fightSync.clear(session);
                // BOB 客户端 IsServerCal=false，本地结算；仍清状态避免串场
                break;
            case MsgIds.C2S_BOB_RESULT:
                bob.onResult(session, pkt);
                fightSync.clear(session);
                break;
            case MsgIds.C2S_BOB_RESET:
                bob.onReset(session, pkt);
                break;
            case MsgIds.C2S_BOB_PRIZE_CUP:
                bob.onPrizeCup(session, pkt);
                break;
            case MsgIds.C2S_BOB_FORCE_EXIT:
                bob.onForceExit(session, pkt);
                break;
            case MsgIds.C2S_ZBZ_MATCH_PLAYER:
                zbz.onMatchPlayer(session, pkt);
                break;
            case MsgIds.C2S_ZBZ_GAMBLE:
                zbz.onGamble(session, pkt);
                break;
            case MsgIds.C2S_ZBZ_FIGHT:
                fightSync.clear(session);
                if (zbz.onFight(session, pkt) && session.player() != null) {
                    PlayerRecord zbzRec = session.player();
                    int foe = zbzRec.zbz.curTargetPlayerId;
                    int round = Math.max(1, zbzRec.zbz.matchCurRound);
                    PlayerRecord foeRec = playerStore.findByPlayerId(foe);
                    if (foeRec != null && foeRec.npcPassive) {
                        fightSync.beginBattleZbzVsPlayer(session, foeRec, round, foeRec.playerId);
                    } else {
                        fightSync.beginBattleZbz(session, foe, round, foe + ZbzService.FIGHT_GUID_OFFSET);
                    }
                }
                break;
            case MsgIds.C2S_ZBZ_CUR_FIGHTER:
                zbz.onCurFighter(session, pkt);
                break;
            case MsgIds.C2S_ZBZ_CHANGE_DEFENSE:
                zbz.onChangeDefense(session, pkt);
                break;
            case MsgIds.C2S_ZBZ_RESULT:
                zbz.onResult(session, pkt);
                fightSync.clear(session);
                break;
            case MsgIds.C2S_ZBZ_RANK:
                zbz.onRank(session, pkt);
                break;
            case MsgIds.C2S_ZBZ_JUEZHAN_AWARD:
                zbz.onJueZhanAward(session, pkt);
                break;
            case MsgIds.C2S_KFZ_ZHAN_KUANG:
                kfz.onZhanKuang(session, pkt);
                break;
            case MsgIds.C2S_KFZ_XIANGXI:
                kfz.onXiangXi(session, pkt);
                break;
            case MsgIds.C2S_KFZ_MINGREN:
                kfz.onMingRen(session, pkt);
                break;
            case MsgIds.C2S_KFZ_SAICHENG:
                kfz.onSaiCheng(session, pkt);
                break;
            case MsgIds.C2S_KFZ_DUIZHAN:
                kfz.onDuiZhan(session, pkt);
                break;
            case MsgIds.C2S_KFZ_OFFENCE_BUZHEN:
                kfz.onOffenceBuZhen(session, pkt);
                break;
            case MsgIds.C2S_KFZ_PYS_FIGHT:
                fightSync.clear(session);
                kfz.onPysFight(session, pkt);
                break;
            case MsgIds.C2S_KFZ_PYS_RESULT:
                kfz.onPysResult(session, pkt);
                fightSync.clear(session);
                break;
            case MsgIds.C2S_KFZ_DFS_FIGHT:
                fightSync.clear(session);
                kfz.onDfsFight(session, pkt);
                break;
            case MsgIds.C2S_KFZ_DFS_RESULT:
                kfz.onDfsResult(session, pkt);
                fightSync.clear(session);
                break;
            case MsgIds.C2S_KFZ_RANK:
                kfz.onRank(session, pkt);
                break;
            case MsgIds.C2S_KFZ_PAIWEI_RANK:
                kfz.onPaiWeiRank(session, pkt);
                break;
            case MsgIds.C2S_KFZ_WORSHIP:
                kfz.onWorship(session, pkt);
                break;
            case MsgIds.C2S_DRAW_GOLD:
                draw.onGold(session, pkt);
                break;
            case MsgIds.C2S_DRAW_DIAMOND:
                draw.onDiamond(session, pkt);
                break;
            case MsgIds.C2S_CHAPTER_CHEST:
                dungeon.onChapterChest(session, pkt);
                break;
            case MsgIds.C2S_ENTER_MIRROR:
                dungeon.onEnterResourceLike(session, pkt, MsgIds.S2C_ENTER_MIRROR, DungeonService.RT_MIRROR);
                break;
            case MsgIds.C2S_RESULT_MIRROR:
            case MsgIds.C2S_RESULT_DEADLY:
                dungeon.onResultFb(session, pkt);
                break;
            case MsgIds.C2S_ENTER_DEADLY:
                dungeon.onEnterResourceLike(session, pkt, MsgIds.S2C_ENTER_DEADLY, DungeonService.RT_DEADLY);
                break;
            case MsgIds.C2S_BUY_FB_TIME:
                dungeon.onBuyFbTimes(session, pkt);
                break;
            case MsgIds.C2S_SELL_GOODS:
                dungeon.onSellGoods(session, pkt);
                break;
            // ⚠️ §6-9：下面 1109/1110/1111 三个 C2S 在 APK 全产物里零发送点（客户端洗炼已改走
            // 1116 开淬炼 / 1117 淬炼部位）⇒ 这三条路由与它们回的 1402/1403/1404 两向皆死。
            // **有意保留未删**：见 MsgIds 1109 处的说明（删了会连带废掉 EquipmentXiLian 表解析）。
            case MsgIds.C2S_XILIAN_LAST:
                cultivate.onLastXiLian(session, pkt);
                break;
            case MsgIds.C2S_XILIAN:
                cultivate.onXiLian(session, pkt);
                break;
            case MsgIds.C2S_XILIAN_CONFIRM:
                cultivate.onConfirmXiLian(session, pkt);
                break;
            // ⚠️ §6-9：1115 客户端从不发送；onDownStar 只是 onResetEquip(true) 的薄封装，
            // 且 1412 客户端确有注册（CultivateService.java:1014 在发）⇒ 保留。
            case MsgIds.C2S_EQUIP_DOWN_STAR:
                cultivate.onDownStar(session, pkt);
                break;
            case MsgIds.C2S_SHOP_INFO:
                shop.onShopInfo(session, pkt);
                break;
            case MsgIds.C2S_SHOP_BUY:
                shop.onBuyShop(session, pkt);
                break;
            case MsgIds.C2S_SHOP_REFRESH:
                shop.onRefreshShop(session, pkt);
                break;
            case MsgIds.C2S_AUCTION_INFO:
                union.onAuctionInfo(session, pkt);
                break;
            case MsgIds.C2S_JINGPAI:
                union.onJingPai(session, pkt);
                break;
            case MsgIds.C2S_BOSS_FINISH:
                union.onBossFinish(session, pkt);
                break;
            case MsgIds.C2S_REST_BOSS:
                union.onRestBoss(session, pkt);
                break;
            case MsgIds.C2S_HOSPITAL:
                union.onHospital(session, pkt);
                break;
            case MsgIds.C2S_HOSPITAL_EMPLOY:
                union.onHospitalEmploy(session, pkt);
                break;
            case MsgIds.C2S_EMPLOY_DETAIL:
                union.onEmployDetail(session, pkt);
                break;
            case MsgIds.C2S_KITCHEN:
                union.onKitchen(session, pkt);
                break;
            case MsgIds.C2S_TRAIN_START:
                union.onTrainStart(session, pkt);
                break;
            case MsgIds.C2S_TRAIN_INFO:
                union.onTrainInfo(session, pkt);
                break;
            case MsgIds.C2S_TRAIN_CANCEL:
                union.onTrainCancel(session, pkt);
                break;
            case MsgIds.C2S_SEND_CART:
                union.onSendCart(session, pkt);
                break;
            case MsgIds.C2S_RAID_CART:
                union.onRaidCart(session, pkt);
                break;
            case MsgIds.C2S_RAID_RESULT:
                union.onRaidResult(session, pkt);
                break;
            case MsgIds.C2S_RAID_RANK:
                union.onRaidRank(session, pkt);
                break;
            case MsgIds.C2S_REFRESH_RAID:
                union.onRefreshRaid(session, pkt);
                break;
            case MsgIds.C2S_YUNBIAO_AWARD:
                union.onYunBiaoAward(session, pkt);
                break;
            case MsgIds.C2S_JIBAN_UPDATE:
                side.onJiBanUpdate(session, pkt);
                break;
            case MsgIds.C2S_JIBAN_REFRESH:
                side.onJiBanRefresh(session, pkt);
                break;
            case MsgIds.C2S_JIBAN_CLOSE:
            case MsgIds.C2S_JIBAN_APPLY:
                side.onJiBanApply(session, pkt);
                break;
            case MsgIds.C2S_JIBAN_LOCK:
                side.onJiBanLock(session, pkt);
                break;
            case MsgIds.C2S_KUANG_PAGE:
                mine.onPage(session, pkt);
                break;
            case MsgIds.C2S_KUANG_DEAD:
                mine.onDeadWj(session, pkt);
                break;
            case MsgIds.C2S_KUANG_DETAIL:
                mine.onDetail(session, pkt);
                break;
            case MsgIds.C2S_KUANG_HOLD:
                mine.onHold(session, pkt);
                break;
            case MsgIds.C2S_KUANG_CHANGE_DEF:
                mine.onChangeDef(session, pkt);
                break;
            case MsgIds.C2S_KUANG_LEAVE:
                mine.onLeave(session, pkt);
                break;
            case MsgIds.C2S_KUANG_FIGHT:
                fightSync.clear(session);
                if (mine.onFight(session, pkt)) {
                    MineService.FightLaunch launch = mine.takeLaunch(session.account());
                    if (launch != null && launch.foe != null) {
                        fightSync.beginKuangBattle(session, launch.foe, launch.foeSlots);
                    }
                }
                break;
            case MsgIds.C2S_KUANG_FIGHT_RESULT:
                mine.onFightResult(session, pkt);
                fightSync.clear(session);
                break;
            case MsgIds.C2S_KUANG_RES:
                mine.onGetResource(session, pkt);
                break;
            case MsgIds.C2S_KUANG_RES_OK:
                mine.onConfirmResource(session, pkt);
                break;
            case MsgIds.C2S_KUANG_MY:
                mine.onMyMines(session, pkt);
                break;
            case MsgIds.C2S_KUANG_BUY_ZZ:
                mine.onBuyZz(session, pkt);
                break;
            case MsgIds.C2S_KUANG_RELIVE:
                mine.onBuyRelive(session, pkt);
                break;
            case MsgIds.C2S_KUANG_DEF_WJ:
                mine.onDefWj(session, pkt);
                break;
            // §6-9：C2S 1913 客户端零发送点（空矿数改由 S2C 2124 等推送落地）。handler 有意保留。
            case MsgIds.C2S_KUANG_EMPTY:
                mine.onEmptyCnt(session, pkt);
                break;
            case MsgIds.C2S_KUANG_HOLDER:
                mine.onHolder(session, pkt);
                break;
            case MsgIds.C2S_KUANG_RECORD:
                mine.onFightRecord(session, pkt);
                break;
            case MsgIds.C2S_KUANG_RECORD_DETAIL:
                mine.onFightRecordDetail(session, pkt);
                break;
            case MsgIds.C2S_KUANG_CO:
                mine.onMyCoWj(session, pkt);
                break;
            case MsgIds.C2S_KUANG_CO_UPDATE:
                mine.onCoUpdate(session, pkt);
                break;
            case MsgIds.C2S_KUANG_INVITE:
                mine.onInvite(session, pkt);
                break;
            case MsgIds.C2S_KUANG_ENEMY:
                mine.onEnemy(session, pkt);
                break;
            case MsgIds.C2S_BUY_TILI:
                shop.onBuyTiLi(session, pkt);
                break;
            case MsgIds.C2S_BUY_JINBI:
                shop.onBuyJinBi(session, pkt);
                break;
            case MsgIds.C2S_VIP_LEVEL_AWARD:
                pay.onVipLevelAward(session, pkt);
                break;
            case MsgIds.C2S_TIME_STONE_OFF:
                side.onTimeStoneOff(session, pkt);
                break;
            case MsgIds.C2S_TIME_STONE_SET:
                side.onTimeStoneSet(session, pkt);
                break;
            case MsgIds.C2S_SDK_PAY_ID:
                pay.onSdkPayId(session, pkt);
                break;
            // §6-9：C2S 3002 客户端零发送点（充值校验由 SDK 3001 直接下发结果）。handler 有意保留。
            case MsgIds.C2S_SDK_PAY_CHECK:
                pay.onSdkPayCheck(session, pkt);
                break;
            case MsgIds.C2S_EXCHANGE_SHOP:
                shop.onExchangeBuy(session, pkt);
                break;
            case MsgIds.C2S_TIME_STONE_COMPOSE:
                side.onTimeStoneCompose(session, pkt);
                break;
            case MsgIds.C2S_OPEN_BAOXIANG:
                dungeon.onOpenBaoXiang(session, pkt);
                break;
            case MsgIds.C2S_OPEN_CHOICE_BAOXIANG:
                dungeon.onOpenChoiceBaoXiang(session, pkt);
                break;
            case MsgIds.C2S_TEQUAN_DAILY:
                pay.onTeQuanDaily(session, pkt, false);
                break;
            case MsgIds.C2S_ZHIZUN_DAILY:
                pay.onTeQuanDaily(session, pkt, true);
                break;
            case MsgIds.C2S_PRE_CHOICE_BAOXIANG:
                dungeon.onPreChoiceBaoXiang(session, pkt);
                break;
            case MsgIds.C2S_TOWER_INVITE:
                dungeon.onTowerInvite(session, pkt);
                break;
            case MsgIds.C2S_TOWER_ACCEPT:
                dungeon.onTowerAccept(session, pkt);
                break;
            case MsgIds.C2S_TOWER_INFO:
                dungeon.onTowerInfo(session, pkt);
                break;
            case MsgIds.C2S_TOWER_UNTIE:
                dungeon.onTowerUntie(session, pkt);
                break;
            case MsgIds.C2S_TOWER_FIGHT:
                if (dungeon.onTowerFight(session, pkt)) {
                    fightSync.beginBattle(session, 0, PlayerRecord.FORMATION_BCT);
                }
                break;
            case MsgIds.C2S_TOWER_BUY_TIMES:
                dungeon.onTowerBuyTimes(session, pkt);
                break;
            case MsgIds.C2S_TOWER_RANK:
                dungeon.onTowerRank(session, pkt);
                break;
            case MsgIds.C2S_QQ_PAY:
                pay.onQqPay(session, pkt);
                break;
            // §6-9：C2S 3401 客户端零发送点（Android 包无 Apple 支付入口）。handler 有意保留，
            // 内部只打一行 ignored 日志；回包常量 S2C 3801 方向记反，已按 §6-9 删除。
            case MsgIds.C2S_APPLE_PAY:
                pay.onApplePay(session, pkt);
                break;
            case MsgIds.C2S_SOUL_COMPOSE:
                side.onSoulCompose(session, pkt);
                break;
            case MsgIds.C2S_SOUL_EXP:
                side.onSoulExp(session, pkt);
                break;
            case MsgIds.C2S_SOUL_JINJIE:
                side.onSoulJinJie(session, pkt);
                break;
            case MsgIds.C2S_TIME_STONE_COLOR:
                side.onTimeStoneColor(session, pkt);
                break;
            case MsgIds.C2S_EXCHANGE_INFO:
                shop.onExchangeInfo(session, pkt);
                break;
            case MsgIds.C2S_EXCHANGE_DO:
                shop.onExchangeDo(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_TRANSFORM:
                cultivate.onTransform(session, pkt);
                break;
            case MsgIds.C2S_JINGLIAN_EXP:
                cultivate.onJingLianExp(session, pkt);
                break;
            case MsgIds.C2S_JINGLIAN_FEIYUE:
                cultivate.onJingLianFeiYue(session, pkt);
                break;
            case MsgIds.C2S_EQUIP_RESET:
                cultivate.onResetEquip(session, pkt);
                break;
            case MsgIds.C2S_CLONE_BASE:
                clone.onBase(session, pkt);
                break;
            case MsgIds.C2S_CLONE_CREATE:
                clone.onCreate(session, pkt);
                break;
            case MsgIds.C2S_CLONE_QUICK_JOIN:
                clone.onQuickJoin(session, pkt);
                break;
            case MsgIds.C2S_CLONE_LEAVE:
                clone.onLeave(session, pkt);
                break;
            case MsgIds.C2S_CLONE_FIGHT:
                clone.onFight(session, pkt);
                break;
            case MsgIds.C2S_CLONE_RESULT:
                clone.onResult(session, pkt);
                fightSync.clear(session);
                break;
            case MsgIds.C2S_CLONE_CHANGE_WJ:
                clone.onChangeWj(session, pkt);
                break;
            case MsgIds.C2S_CLONE_CHANGE_QUICK:
                clone.onChangeQuickJoin(session, pkt);
                break;
            case MsgIds.C2S_CLONE_JOIN_BY_ID:
                clone.onJoinById(session, pkt);
                break;
            case MsgIds.C2S_CLONE_UNION_INVITE:
            case MsgIds.C2S_CLONE_WORLD_INVITE:
                clone.onInvite(session, pkt);
                break;
            case MsgIds.C2S_LEVEL_LOAD_FINISH:
                // 104 有意不回（客户端已按本地 ᝁ.ᜂ 载入场景，回 107 会造成重载环），
                // 见 docs/PROTOCOL_GAP_REPORT.md §3.A。禁止顺手给它加逻辑。
                break;
            case MsgIds.C2S_KF_HAPPY_7:
                kfHappy.onSevenDays(session, pkt);
                break;
            case MsgIds.C2S_KF_HAPPY_15:
                // 名字里的「15」是 legacy 误名：这是 HalfMonthActivity，day ∈ [8,14]。
                kfHappy.onHalfMonth(session, pkt);
                break;
            case MsgIds.C2S_KF_HAPPY_GET:
                kfHappy.onGetAward(session, pkt);
                break;
            case MsgIds.C2S_ZHUANPAN_BASE:
            case MsgIds.C2S_ZHUANPAN_POOL_INFO:
                // 4601 面板首次打开 / 4605 已有缓存，共用 S2C 5101。
                zhuanPan.onBaseInfo(session, pkt);
                break;
            case MsgIds.C2S_ZHUANPAN_DRAW_ONE:
                zhuanPan.onDrawOne(session, pkt);
                break;
            case MsgIds.C2S_ZHUANPAN_DRAW_TEN:
                zhuanPan.onDrawTen(session, pkt);
                break;
            case MsgIds.C2S_ZHUANPAN_RANK:
                zhuanPan.onRank(session, pkt);
                break;
            // ------------------------------------------- 聊天 / 黑名单（§6-5）
            case MsgIds.C2S_CHAT_INFO:
                chat.onChatInfo(session, pkt);
                break;
            case MsgIds.C2S_CHAT_TO_SVR:
                chat.onChatToSvr(session, pkt);
                break;
            case MsgIds.C2S_ADD_BLACK_LIST:
                chat.onAddBlackList(session, pkt);
                break;
            case MsgIds.C2S_DEL_BLACK_LIST:
                chat.onDelBlackList(session, pkt);
                break;
            // ------------------------------------------------- 喇叭（§6-5）
            case MsgIds.C2S_OPEN_SMALL_LABA:
                laBa.onOpenSmall(session, pkt);
                break;
            case MsgIds.C2S_OPEN_BIG_LABA:
                laBa.onOpenBig(session, pkt);
                break;
            // -------------------------------------------- 龙腾兑换（§6-5）
            case MsgIds.C2S_LT_EXCHANGE_LOTTERY:
                ltExchange.onLottery(session, pkt);
                break;
            case MsgIds.C2S_LT_EXCHANGE_GOODS:
                ltExchange.onExchangeGoods(session, pkt);
                break;
            case MsgIds.C2S_LT_EXCHANGE_RANK:
                ltExchange.onRank(session, pkt);
                break;
            // ---------------------------------------------- 好友整链（§6-5）
            case MsgIds.C2S_FRIEND_LIST:
                friend.onFriendList(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_DELETE:
                friend.onDeleteFriend(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_GIVE_POWER:
                friend.onGiveFriendPower(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_ACCEPT_POWER:
                friend.onAcceptFriendPower(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_ADD_BY_GUID:
                friend.onAddFriendByGuid(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_ADD_BY_NAME:
                friend.onAddFriendByName(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_PUSH_APPLY:
                friend.onPushApplyList(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_PUSH_APPLY_NEXT:
                friend.onPushApplyListNext(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_INVITE_ME:
                friend.onInviteMe(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_INVITE_COUNT:
                friend.onInviteCount(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_INVITE_REWARD:
                friend.onInviteReward(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_INVITE_MY_REWARD:
                friend.onInviteMyReward(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_APPLY_LIST:
                friend.onApplyList(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_APPLY_DEAL:
                friend.onApplyDeal(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_BRIEF:
                friend.onFriendBrief(session, pkt);
                break;
            case MsgIds.C2S_FRIEND_RED_POINT:
                friend.onRedPoint(session, pkt);
                break;
            case MsgIds.C2S_FIGHT_SYNC_PACK:
                fightSync.onC2SSync(session, pkt);
                break;
            case MsgIds.C2S_START_ONE_NEW_BATTLE:
                if (mine.onStartNextBattle(session, pkt)) {
                    MineService.FightLaunch launch = mine.takeLaunch(session.account());
                    if (launch != null && launch.foe != null) {
                        fightSync.beginKuangBattle(session, launch.foe, launch.foeSlots);
                    }
                } else {
                    session.send(MsgIds.S2C_START_ONE_NEW_BATTLE_RET, pkt, new byte[0]);
                }
                break;
            default:
                log.debug("ignore C2S {} body={}B", pkt.msgId, pkt.body.length);
                break;
        }
    }
}
