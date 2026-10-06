package com.sao.fakeserver.proto;

/**
 * 消息号对照 Msg.dll / NET_MSG_C2S.cs / NET_MSG_S2C.cs。
 * 同一数字在 C2S 与 S2C 上往往不是同一条业务。
 */
public final class MsgIds {
    private MsgIds() {
    }

    public static final int C2S_INIT = 1;
    public static final int S2C_INIT = 1;
    public static final int C2S_HEARTBEAT = 3;
    public static final int S2C_HEARTBEAT = 3;

    /** C2S 20 礼包码兑换 {@code CMsgRequestGiftPack{key}}；回 S2C 20 {@code CMsgGiftPackRet{ret}}。 */
    public static final int C2S_GIFT_PACK = 20;
    public static final int S2C_GIFT_PACK_RET = 20;
    /**
     * C2S 30 激活码校验 {@code CCMsg_Account_Check_JiHuoMa{account,jihuoma,deviceid}}。
     * <p>S2C 21 是反方向的服务端推送（通知客户端打开激活码输入 UI），不是本包的应答。
     */
    public static final int C2S_ACTIVATION_CODE = 30;
    /** S2C 21 服务端通知客户端弹激活码 UI {@code CCMsg_NotifyClient_Check_JiHuoMa{account}}。 */
    public static final int S2C_ACTIVATION_NOTIFY = 21;
    /** S2C 22 激活码失败 {@code CMsg_Account_Check_JiHuoMa_Ret{ret}}：1=无效 2=已被使用。 */
    public static final int S2C_ACTIVATION_FAIL = 22;
    /** S2C 30 系统公告/走马灯 {@code CCMsg_SysAnnouncement{type,regionType,content,level,contentkey}}。 */
    public static final int S2C_SYS_ANNOUNCEMENT = 30;

    public static final int C2S_ACCOUNT_ENTER = 101;
    public static final int S2C_ACCOUNT_ENTER_RET = 101;
    public static final int C2S_FIRST_ENTER_REGION = 102;
    public static final int S2C_FIRST_ENTER_REGION_RET = 102;
    public static final int S2C_SET_MAIN_CITY_BORN = 103;
    /** C2S 103：大厅坐标同步 CCMsgEntityPhyInfo。与 S2C 103 出生点同号不同包。 */
    public static final int C2S_ENTITY_PHY_UPDATE = 103;
    public static final int C2S_LEVEL_LOAD_FINISH = 104;
    public static final int S2C_CHANGE_REGION_RET = 107;

    public static final int C2S_REQUEST_FORMATION = 201;
    public static final int S2C_FORMATION = 201;
    public static final int C2S_CHANGE_FORMATION = 202;

    public static final int C2S_ENTER_FB = 301;
    public static final int S2C_UPDATE_GOODS = 301;
    public static final int C2S_RESULT_FB = 302;
    public static final int C2S_BACK_MAIN_CITY = 304;
    public static final int C2S_SAO_DANG = 310;
    public static final int C2S_ENTER_UNTOUCHABLE = 325;
    public static final int C2S_RESULT_UNTOUCHABLE = 326;
    public static final int C2S_ENTER_SANTA = 329;
    public static final int C2S_RESULT_SANTA = 330;
    public static final int C2S_ENTER_SCYTHE = 331;
    public static final int C2S_RESULT_SCYTHE = 332;
    public static final int C2S_SAO_DANG_RESOURCE = 334;

    public static final int S2C_SAO_DANG_RET = 410;
    public static final int S2C_SAO_DANG_RESOURCE_RET = 452;
    public static final int S2C_ATTRI_UPDATE = 501;
    public static final int C2S_RECONNECT = 601;
    public static final int S2C_WUJIANG_ATTRI_UPDATE = 601;

    public static final int C2S_DAILY_TASK = 701;
    public static final int C2S_PRIZE_DAILY_TASK = 702;
    public static final int C2S_ONCE_TASK = 703;
    public static final int C2S_PRIZE_ONCE_TASK = 704;
    public static final int C2S_REQUEST_MAIL = 801;
    /** S2C 801 {@code CCMsgChatToCli}：与 C2S 邮件同号不同向。 */
    public static final int S2C_CHAT_TO_CLI = 801;
    public static final int C2S_HANDLE_MAIL = 802;
    public static final int S2C_RECONNECT_RET = 901;
    public static final int C2S_COMPOSE_WUJIANG = 901;
    public static final int C2S_USE_GOODS_ADD_WJ_EXP = 902;
    public static final int C2S_UP_WUJIANG_STAR = 903;
    public static final int C2S_WUJIANG_JINJIE_SLOT = 904;
    public static final int C2S_WUJIANG_JINJIE = 905;
    public static final int C2S_WUJIANG_SKILL_UP = 906;
    public static final int C2S_JINJIE_BOOK_COMPOSE = 907;
    public static final int C2S_BUY_SKILL_POINTS = 908;

    public static final int C2S_COMPOSE_EQUIP = 1101;
    public static final int C2S_EQUIP_PUT_ON = 1102;
    public static final int C2S_EQUIP_PUT_OFF = 1103;
    public static final int C2S_EQUIP_AUTO_PUT_ON = 1104;
    public static final int C2S_EQUIP_AUTO_PUT_OFF = 1105;
    public static final int C2S_EQUIP_LEVEL_UP = 1106;
    public static final int C2S_EQUIP_AUTO_LEVEL_UP = 1107;
    public static final int C2S_EQUIP_STAR_UP = 1108;
    public static final int C2S_EQUIP_DECOMPOSE = 1112;
    public static final int C2S_EQUIP_ONE_KEY_LEVEL_UP = 1113;
    public static final int C2S_EQUIP_GUHUA = 1114;
    public static final int C2S_EQUIP_OPEN_CUILIAN = 1116;
    public static final int C2S_EQUIP_CUILIAN_PART = 1117;
    public static final int C2S_EQUIP_FEIYUE = 1118;
    public static final int C2S_CREATE_ROLE = 1001;
    public static final int S2C_RESULT_FB_RET = 1001;
    public static final int S2C_DAILY_TASK_RET = 1101;
    public static final int S2C_DAILY_TASK_UPDATE = 1102;
    public static final int S2C_ONCE_TASK_RET = 1103;
    public static final int S2C_ONCE_TASK_UPDATE = 1104;
    public static final int S2C_NOTIFY_CREATE_ROLE = 1301;
    public static final int S2C_CREATE_ROLE_RET = 1302;

    public static final int S2C_MAIL_LIST = 1201;
    /** C2S 1201：更换主角形象 CCMsgWuJiangGuid；与 S2C 邮件列表同号不同向。 */
    public static final int C2S_CHANGE_AVATAR = 1201;
    public static final int S2C_MAIL_UPDATE = 1202;
    public static final int S2C_MAIL_DEL = 1203;
    public static final int S2C_ADD_WUJIANG = 1204;
    public static final int S2C_MAIL_NOTIFY = 1205;

    public static final int S2C_EQUIP_ATTRI_UPDATE = 1401;
    public static final int S2C_REMOVE_EQUIP = 1405;
    public static final int S2C_ADD_EQUIP = 1406;
    public static final int S2C_COMPOSE_EQUIP_RET = 1407;
    public static final int S2C_DECOMPOSE_EQUIP_RET = 1408;
    public static final int S2C_EQUIP_ONE_KEY_LEVEL_UP_RET = 1409;
    public static final int S2C_EQUIP_OPEN_CUILIAN_RET = 1413;
    public static final int S2C_EQUIP_CUILIAN_PART_RET = 1414;
    public static final int S2C_EQUIP_FEIYUE_RET = 1415;
    public static final int S2C_JINJIE_BOOK_COMPOSE_RET = 2801;

    /** 战斗同步：C2S 2801 与 S2C 进阶书同号不同向；S2C 3202 与 C2S 百层接受同号不同向。 */
    public static final int C2S_FIGHT_SYNC_PACK = 2801;
    public static final int S2C_FIGHT_SYNC_PACK = 3202;
    public static final int C2S_START_ONE_NEW_BATTLE = 2802;
    public static final int S2C_START_ONE_NEW_BATTLE_RET = 3203;

    public static final int C2S_CREATE_UNION = 1501;
    public static final int C2S_UNION_LIST = 1502;
    public static final int C2S_JOIN_UNION = 1503;
    public static final int C2S_UNION_DETAIL = 1504;
    public static final int C2S_UNION_REQUESTERS = 1505;
    /** C2S 1506 CCMsgHandleUnionRequester{RequesterGuid, Agree}；客户端 UnionManagerSystem.cs:760/778。 */
    public static final int C2S_HANDLE_UNION_REQUESTER = 1506;
    /** C2S 1507 CCMsgAppointElder{MemberGuid, AppointOrNot}；客户端 UnionManagerSystem.cs:997/1074。 */
    public static final int C2S_APPOINT_ELDER = 1507;
    /** C2S 1508 转让会长（CCMsgPlayerGuid，f1 是 int playerGuid）；客户端 UnionManagerSystem.cs:1325。 */
    public static final int C2S_CHANGE_UNION_OWNER = 1508;
    /** C2S 1509 CCMsgModifyUnionNotice{NewNotice}；客户端 UnionManagerSystem.cs:745。 */
    public static final int C2S_MODIFY_UNION_NOTICE = 1509;
    /** C2S 1510 CCMsgModifyUnionIcon{UnionIcon}；客户端 UnionManagerSystem.cs:902。 */
    public static final int C2S_MODIFY_UNION_ICON = 1510;
    /** C2S 1511 CCMsgModifyUnionJoinType{JoinType}（EUnionJoinType）；客户端 UnionManagerSystem.cs:906。 */
    public static final int C2S_MODIFY_UNION_JOIN_TYPE = 1511;
    /** C2S 1512 CCMsgModifyUnionJoinLevel{JoinLevel}；客户端 UnionManagerSystem.cs:910。 */
    public static final int C2S_MODIFY_UNION_JOIN_LEVEL = 1512;
    public static final int C2S_DESTROY_UNION = 1513;
    public static final int C2S_QUIT_UNION = 1514;
    /** C2S 1515 踢人（CMsgUnionGuid）；客户端 UnionManagerSystem.cs:1362。 */
    public static final int C2S_KICK_UNION_MEMBER = 1515;
    public static final int C2S_UNION_PLAYER_RES = 1516;
    public static final int C2S_UNION_DONATE = 1517;
    public static final int C2S_UNION_EMPLOYERS = 1518;
    public static final int C2S_DRAW_BUILDING_PROFIT = 1519;
    /** C2S 1520 单建筑全部雇佣位（CCMsgUnionBuildingType{buildingType}）；客户端 CookHouseAllEmployer.cs:46 等 5 处。 */
    public static final int C2S_BUILDING_ALL_EMPLOYERS = 1520;
    /** C2S 1521 替换某雇佣位（WuJiangContainerSystemForYongBing.cs:445/639）。 */
    public static final int C2S_REPLACE_BUILDING_EMPLOYER = 1521;
    public static final int C2S_UNION_BUILDINGS = 1522;
    public static final int C2S_UNION_BUILDING_LEVEL_UP = 1523;
    /** C2S 1524 取消建筑升级（CCMsgUnionBuildingType）；客户端 BuildingItem_InJianZhuMianBan.cs:344。 */
    public static final int C2S_CANCEL_BUILDING_LEVEL_UP = 1524;
    /** C2S 1525 公会成员信息（CUnionMembersInfo）；客户端 BCTInviteUI.cs:77、InviteDesfenseUISystem.cs:80。 */
    public static final int C2S_UNION_MEMBER_INFO = 1525;
    public static final int C2S_UNION_BOSS_INFO = 1526;
    /** C2S 1527 作战室雇佣武将（CMsgEmployInofo{playerGUID,wujiangIndex}）；客户端 EmployConfirmUI.cs:104。 */
    public static final int C2S_EMPLOY_WJ_ZZS = 1527;
    public static final int C2S_UNION_BOSS_FIGHT = 1528;
    public static final int C2S_UNION_BOSS_RESULT = 1529;
    public static final int C2S_MAJIU_INFO = 1541;
    public static final int C2S_MAJIU_MY_BIAO = 1542;
    public static final int S2C_CREATE_UNION_RET = 1901;
    public static final int S2C_DESTROY_UNION_RET = 1902;
    public static final int S2C_UNION_LIST_RET = 1903;
    public static final int S2C_JOIN_UNION_RET = 1904;
    /** S2C 1905 申请被处理（CCMsgNotifyUnionOwnerOrElderRequesterJoinInUnion_Ret{Success,FailReason,newMember}）。 */
    public static final int S2C_HANDLE_REQUESTER_RET = 1905;
    public static final int S2C_UNION_DETAIL_RET = 1906;
    public static final int S2C_UNION_REQUESTERS_RET = 1907;
    /** S2C 1908 CMsgUnionJob{newJob}：某成员职位变更。 */
    public static final int S2C_UNION_JOB_UPDATE = 1908;
    public static final int S2C_QUIT_UNION_RET = 1909;
    /** S2C 1910 CCMsgAppointElder_Ret{Success,AppointOrNot,ElderGuid}。 */
    public static final int S2C_APPOINT_ELDER_RET = 1910;
    /** S2C 1911 CCMsgPlayerGuid：会长变更通知（OnNotifyUnionOwner:4209）。 */
    public static final int S2C_NOTIFY_UNION_OWNER = 1911;
    /** S2C 1912 CCMsgUnionJoinRequest 红点；客户端 OnNotifyUnionTips:6796 不读载荷。 */
    public static final int S2C_UNION_JOIN_REQUEST_TIPS = 1912;
    public static final int S2C_UNION_PLAYER_RES = 1913;
    /** S2C 1914 CCMsgUnionAttriUpdate{attribute,iValue,strValue}（EUnionAttribute）。 */
    public static final int S2C_UNION_ATTRI_UPDATE = 1914;
    public static final int S2C_UNION_EMPLOYERS = 1915;
    public static final int S2C_DRAW_BUILDING_PROFIT = 1916;
    /** S2C 1917 CCMsgRequestOneUnionBuildingAllEmployers_Ret{buildingType,employer[]}。 */
    public static final int S2C_BUILDING_ALL_EMPLOYERS = 1917;
    /** S2C 1918 CCMsgReplaceOneEmployersByPlayer_Ret{result}。 */
    public static final int S2C_REPLACE_EMPLOYER_RET = 1918;
    public static final int S2C_UNION_BUILDINGS = 1919;
    public static final int S2C_UNION_BUILDING_LEVEL_UP = 1920;
    /** S2C 1921 CCMsgCancelLevelUpOneUnionBuilding_Ret{buildingType,isSuccess}。 */
    public static final int S2C_CANCEL_BUILDING_LEVEL_UP_RET = 1921;
    /** S2C 1922 CUnionMembersInfo{MemberInfo[]}。 */
    public static final int S2C_UNION_MEMBER_INFO_RET = 1922;
    public static final int S2C_UNION_BOSS_INFO = 1923;
    /** S2C 1924 CCMsgRequestEmployAWuJiang_ZZS_Ret{suc,employInfo,CDTimeLeft,EmployPrice}。 */
    public static final int S2C_EMPLOY_WJ_ZZS_RET = 1924;
    public static final int S2C_UNION_BOSS_FIGHT_RET = 1925;
    public static final int S2C_UNION_BOSS_RESULT_RET = 1926;
    public static final int S2C_REST_BOSS = 1930;
    /** S2C 1929 CCMsgFightBossFinishInfo{bossInfo,goods[]}：C2S 1532 通关结算回包（开 WarRoomFinish）。 */
    public static final int S2C_BOSS_FINISH_INFO = 1929;
    public static final int S2C_MAJIU_INFO = 1936;
    public static final int S2C_MAJIU_MY_BIAO = 1937;
    public static final int S2C_UNION_DONATE_RET = 1966;
    public static final int S2C_UNION_BOSS_PLAY_TIME = 1967;

    public static final int C2S_JJC_INFO = 1701;
    public static final int C2S_JJC_REFRESH = 1702;
    public static final int C2S_JJC_TARGET_DETAIL = 1703;
    public static final int C2S_JJC_FIGHT = 1704;
    public static final int C2S_JJC_RESULT = 1705;
    public static final int C2S_JJC_CLEAR_CD = 1706;
    public static final int C2S_JJC_RESET_TIMES = 1707;
    public static final int C2S_JJC_RANK = 1708;
    public static final int C2S_JJC_RANK_DETAIL = 1709;
    public static final int C2S_JJC_FIGHT_RECORD = 1710;
    public static final int C2S_JJC_FIGHT_RECORD_DETAIL = 1711;
    public static final int C2S_JJC_MY_RANK = 1712;
    public static final int C2S_JJC_QIECUO = 1713;
    public static final int C2S_JJC_QIECUO_RESULT = 1714;
    public static final int S2C_JJC_INFO_RET = 2001;
    public static final int S2C_JJC_REFRESH_RET = 2002;
    public static final int S2C_JJC_TARGET_DETAIL_RET = 2003;
    public static final int S2C_JJC_FIGHT_TEAMS = 2004;
    public static final int S2C_JJC_FIGHT_RECORD_TIP = 2005;
    public static final int S2C_JJC_LAST_CHALLENGE = 2007;
    public static final int S2C_JJC_RESET_TIMES = 2008;
    public static final int S2C_JJC_RANK_RET = 2009;
    public static final int S2C_JJC_RANK_DETAIL_RET = 2010;
    public static final int S2C_JJC_FIGHT_RECORD_RET = 2011;
    public static final int S2C_JJC_FIGHT_RECORD_DETAIL_RET = 2012;
    public static final int S2C_JJC_MY_RANK_RET = 2013;
    public static final int S2C_JJC_QIECUO_RET = 2014;
    public static final int S2C_JJC_QIECUO_FIGHT = 2015;

    public static final int C2S_DRAW_GOLD = 2201;
    public static final int C2S_DRAW_DIAMOND = 2202;
    public static final int S2C_DRAW_RET = 2401;
    public static final int S2C_DRAW_UPDATE = 2402;
    public static final int S2C_ZS10_PRICE = 2403;

    public static final int C2S_RESULT_HUNDRED_TOWER = 3206;
    public static final int C2S_SAO_DANG_HUNDRED_TOWER = 3208;
    public static final int S2C_SAO_DANG_HUNDRED_TOWER = 3607;

    /**
     * 开服狂欢（7 日狂欢 / 半月庆典）。
     * <p>C2S 5401/5402 的 protoObj 传 null ⇒ <b>包体 0 字节</b>，假服不必解析；
     * 5403 带 {@code day/type/ID} 三个 uint。
     * <p>⚠️ {@code C2S_KF_HAPPY_15} 是 legacy 误名，它对应的是 HalfMonthActivity，
     * 半月档 {@code day ∈ [8,14]}（客户端 {@code KFHappyMainUI.cs:231-270} 只建 8..14 的键，
     * {@code :362} 只允许点当前天或 14）⇒ <b>不要发 day=15</b>，越界会 KeyNotFoundException。
     * <p>⚠️ S2C 侧 5401 已被 {@code S2C_EQUIP_TRANSFORM_RET} 占用（装备转换回包），
     * 与开服狂欢无关，不要混用。
     */
    public static final int C2S_KF_HAPPY_7 = 5401;
    public static final int C2S_KF_HAPPY_15 = 5402;
    public static final int C2S_KF_HAPPY_GET = 5403;
    public static final int S2C_KF_HAPPY_7_RET = 6201;
    public static final int S2C_KF_HAPPY_15_RET = 6202;
    public static final int S2C_KF_HAPPY_GET_RET = 6203;

    public static final int C2S_CHAPTER_CHEST = 303;
    public static final int S2C_CHAPTER_CHEST = 402;
    public static final int C2S_ENTER_MIRROR = 320;
    public static final int C2S_RESULT_MIRROR = 321;
    // ⚠️ §2.3 已定级：420/422 客户端**未注册**（415-460 只注册 450/451/452）⇒ 假服发了会被
    // 静默丢弃。**但有意保留发送**：进图效果由同帧的 107 切场景 + 401 FbInfo + 501 体力兜底，
    // 删除零收益却有回归风险。C2S 320/327 客户端确实会发。
    public static final int S2C_ENTER_MIRROR = 420;
    public static final int C2S_ENTER_DEADLY = 327;
    public static final int C2S_RESULT_DEADLY = 328;
    public static final int S2C_ENTER_DEADLY = 422;
    public static final int C2S_BUY_FB_TIME = 333;
    /** S2C 1002：主线/精英受限关剩余次数+当日已买次数（与 detail 红点 field 1002 同号不同向）。 */
    public static final int S2C_UPDATE_FB_PLAY_TIME = 1002;
    public static final int S2C_FB_INFO = 401;
    public static final int S2C_RESOURCE_FB_UPDATE = 450;
    public static final int S2C_RESOURCE_FB_EXT_AWARDS = 451;
    public static final int C2S_SELL_GOODS = 401;
    // ⚠️ §2.3：701 客户端未注册（695-715 区间零注册）⇒ 出售回包被静默丢弃；
    // 真实效果由同函数内的金币/物品推送落地。**有意保留发送**（C2S 401 客户端确实会发）。
    public static final int S2C_SELL_GOODS_RET = 701;

    public static final int C2S_QIANDAO_INFO = 1401;
    public static final int C2S_QIANDAO_PRIZE = 1402;
    public static final int S2C_QIANDAO_INFO = 1801;
    public static final int S2C_QIANDAO_TIPS = 1802;
    public static final int S2C_QIANDAO_PRIZE = 1803;

    /** C2S 1801：玩家变量 CMsgPlayerVaris（对话/引导已播标记）；与 S2C 签到 1801 同号不同向。 */
    public static final int C2S_UPDATE_PLAYER_VARIS = 1801;

    /** 次日登录领奖（body 空）；S2C 3001 与 C2S SDK_PAY 同号不同向。 */
    public static final int C2S_SECOND_LOGIN_DAY_PRIZE = 2601;
    public static final int S2C_SECOND_LOGIN_DAY_PRIZE_RET = 3001;

    /** 活动页：C2S 查询/七日/体力；S2C 状态/七日回包/体力回包（2601 与次日登录 C2S 同号不同向）。 */
    public static final int C2S_ACTIVITY_7DAY = 2301;
    public static final int C2S_ACTIVITY_VP = 2302;
    public static final int C2S_ACTIVITY_QUERY = 2318;
    public static final int S2C_ACTIVITY_STATUS = 2601;
    public static final int S2C_ACTIVITY_7DAY_RET = 2602;
    public static final int S2C_ACTIVITY_VP_RET = 2603;

    /**
     * 改名：C2S 2101 = {@code CCMsgPlayerName{PlayerName(1)}}（客户端 {@code MainPlayerSystem.cs:625}，
     * 与出站 {@link #S2C_KUANG_PAGE} 同号不同向）；S2C 2301 = {@code CCMsgModifyRoleName_Ret}、
     * 2302 = {@code CCMsgUpdatePlayerRoleName} 广播（与 C2S_ACTIVITY_7DAY/VP 同号不同向）。
     */
    public static final int C2S_MODIFY_ROLE_NAME = 2101;
    public static final int S2C_MODIFY_ROLE_NAME_RET = 2301;
    public static final int S2C_UPDATE_PLAYER_ROLE_NAME = 2302;

    /** 每日累计充值(type4)：C2S 2312 查询 / 2313 领奖；S2C 2611 回查 / 2612 领奖回。 */
    public static final int C2S_DAILY_CHONGZHI_QUERY = 2312;
    public static final int C2S_DAILY_CHONGZHI_AWARD = 2313;
    public static final int S2C_DAILY_CHONGZHI_QUERY_RET = 2611;
    public static final int S2C_DAILY_CHONGZHI_AWARD_RET = 2612;

    /** 当日耗钻(type6)：C2S 2314 查询 / 2315 领奖；S2C 2613 回查 / 2614 领奖回。 */
    public static final int C2S_DAILY_COST_QUERY = 2314;
    public static final int C2S_DAILY_COST_AWARD = 2315;
    public static final int S2C_DAILY_COST_QUERY_RET = 2613;
    public static final int S2C_DAILY_COST_AWARD_RET = 2614;

    /** 首冲大回馈(type2)：C2S 2316 查询 / 2317 领奖；S2C 2618 回查 / 2615 领奖回。 */
    public static final int C2S_FIRST_CHONGZHI_QUERY = 2316;
    public static final int C2S_FIRST_CHONGZHI_AWARD = 2317;
    public static final int S2C_FIRST_CHONGZHI_AWARD_RET = 2615;
    public static final int S2C_FIRST_CHONGZHI_QUERY_RET = 2618;

    /**
     * VIP 礼包（看板 type5「VIP特权礼包」）：C2S 2309 查档位 / 2310 查已购 / 2311 购买；
     * S2C 2617 回档位 / 2609 回已购串 / 2610 回购买结果。
     *
     * <p>客户端：2309 空包（{@code ActivityMainUI.cs:1577}、{@code ActivityStatusInfo.cs:555}）
     * → 2617 {@code CCMsgQueryVIPGiftConfigInfo_Ret.items[]} → {@code ActivityPropertyMgr.UpdateVIPGiftCfg}
     * （{@code :313-332}）填 {@code mVipBuy}；2310 空包**每次登录必发**（{@code MainPlayer.cs:471}）
     * → 2609 {@code CCMsgQueryVIPGiftInfo_Ret.curBoughtVIPGifts}（{@code "|id|id|"} 串）
     * → {@code ActivityMainUI.RefreshVIPBuyed}；2311 {@code CCMsgBuyVIPGift.vipGiftID}
     * （{@code VipBuyItem.cs:162-164}）→ 2610 {@code CCMsgBuyVIPGift_Ret.buyID}
     * → {@code PlayGameState.cs:247 RegisterMessageDelegate(2610, OnBuyVIPGift_Ret)}
     * （处理器 {@code :7054-7073}：用 buyID 在 {@code mVipBuy} 里查**配置**，命中冒字
     * {@code 100151}「获得 X xN」+ {@code RefreshVipTitle()}，未命中 {@code LogError}）。
     * 故 2610 是**成功提示**：拒绝路径一律不回，避免假报成功。
     */
    public static final int C2S_VIP_GIFT_CONFIG_QUERY = 2309;
    public static final int C2S_VIP_GIFT_BOUGHT_QUERY = 2310;
    public static final int C2S_VIP_GIFT_BUY = 2311;
    public static final int S2C_VIP_GIFT_BOUGHT_RET = 2609;
    public static final int S2C_VIP_GIFT_BUY_RET = 2610;
    public static final int S2C_VIP_GIFT_CONFIG_RET = 2617;

    /** 神域魔盒(type22)：C2S 4401 查询 / 4402 重置 / 4403 洗牌 / 4404 开奖；S2C 4901 更新 / 4902 开奖回 / 4903 洗牌回。 */
    public static final int C2S_MAGIC_BOX_INFO = 4401;
    public static final int C2S_MAGIC_BOX_RESET = 4402;
    public static final int C2S_MAGIC_BOX_XIPAI = 4403;
    public static final int C2S_MAGIC_BOX_GIVE_PRIZE = 4404;
    public static final int S2C_MAGIC_BOX_UPDATE = 4901;
    public static final int S2C_MAGIC_BOX_GIVE_PRIZE_RET = 4902;
    public static final int S2C_MAGIC_BOX_XIPAI_RET = 4903;

    // ⚠️ §6-9 判定为「两向皆死」但**有意保留**（未删）：1109/1110/1111 在 APK 全产物里
    // 没有任何发送点（客户端洗炼已改走 1116 开淬炼 / 1117 淬炼部位 → S2C 1413/1414），
    // 因此下面 1402/1403/1404 三个回包永远不会被发出。保留原因：这条链是完整自洽的实现，
    // 一旦删除就会连带废掉 EquipmentXiLian.txt 解析（CultivateTables.rollXiLianType）、
    // EquipmentXiLianCommon（EconomyTables.xiLian）以及 PlayerRecord.xiLianType/xiLianValue —
    // 而后两者仍被 PlayerDumpService 下发（装备 tag 9 / tag 5 的洗炼属性）。
    // 结论见 docs/PROTOCOL_GAP_REPORT.md §2.3 与 §6-9。
    public static final int C2S_XILIAN_LAST = 1109;
    public static final int C2S_XILIAN = 1110;
    public static final int C2S_XILIAN_CONFIRM = 1111;
    /** C2S 1115 客户端从不发送；保留仅供常量对称（真正的降星/重置走 C2S_EQUIP_RESET）。 */
    public static final int C2S_EQUIP_DOWN_STAR = 1115;
    public static final int S2C_XILIAN_LAST = 1402;
    public static final int S2C_XILIAN_RET = 1403;
    public static final int S2C_XILIAN_CONFIRM = 1404;
    /** S2C 1412 客户端**有注册**（降星回包），发送点 CultivateService.java:1014 —— 必须保留。 */
    public static final int S2C_EQUIP_DOWN_STAR_RET = 1412;

    public static final int C2S_SHOP_INFO = 1301;
    public static final int C2S_SHOP_BUY = 1302;
    public static final int C2S_SHOP_REFRESH = 1303;
    /** S2C 整点换货通知 CCMsgShopType；与 C2S 1701 JJC 同号反向。 */
    public static final int S2C_NOTIFY_SHOP_REFRESH = 1701;
    public static final int S2C_SHOP_INFO = 1702;
    public static final int S2C_SHOP_SELLOUT = 1703;
    /** S2C 免费刷新次数日清 CCMsgShopType；与 C2S 1704 竞技开战同号反向。 */
    public static final int S2C_RESET_SHOP_FREE_REFRESH = 1704;

    public static final int C2S_AUCTION_INFO = 1530;
    public static final int C2S_JINGPAI = 1531;
    public static final int C2S_BOSS_FINISH = 1532;
    public static final int C2S_REST_BOSS = 1533;
    public static final int C2S_HOSPITAL = 1534;
    public static final int C2S_HOSPITAL_EMPLOY = 1535;
    public static final int C2S_EMPLOY_DETAIL = 1536;
    public static final int C2S_KITCHEN = 1537;
    public static final int C2S_TRAIN_START = 1538;
    public static final int C2S_TRAIN_INFO = 1539;
    public static final int C2S_TRAIN_CANCEL = 1540;
    public static final int C2S_SEND_CART = 1543;
    public static final int C2S_RAID_CART = 1544;
    public static final int C2S_RAID_RESULT = 1545;
    public static final int C2S_RAID_RANK = 1546;
    public static final int C2S_REFRESH_RAID = 1547;
    public static final int C2S_YUNBIAO_AWARD = 1548;
    public static final int S2C_AUCTION_INFO = 1927;
    public static final int S2C_JINGPAI = 1928;
    public static final int S2C_HOSPITAL = 1931;
    // §6-9 死代码清理：原 S2C_HOSPITAL_EMPLOY = 1932 全工程零引用，且客户端也不注册
    // （见 docs/PROTOCOL_GAP_REPORT.md §2.3 的 12 个「客户端不注册的 S2C」表）⇒ 已删。
    public static final int S2C_EMPLOY_DETAIL = 1933;
    public static final int S2C_TRAIN_INFO = 1934;
    /** S2C 1935 CCMsgNotifyWJXunLianFinishResult：训练到点主动推送。 */
    public static final int S2C_TRAIN_FINISH = 1935;
    public static final int S2C_SEND_CART = 1938;
    public static final int S2C_RAID_CART = 1939;
    public static final int S2C_RAID_RESULT = 1940;
    public static final int S2C_RAID_RANK = 1941;
    public static final int S2C_REFRESH_RAID = 1942;
    public static final int S2C_YUNBIAO_AWARD = 1943;
    public static final int S2C_TRAIN_START = 1944;
    public static final int S2C_KITCHEN = 1945;
    /** S2C 1968 CCMsgUnionNotifyOwnerChangeOnce{union_owner_name}；客户端 OnNET_CCMsgUnionPresidentChange_Ret:9051。 */
    public static final int S2C_NOTIFY_OWNER_CHANGE = 1968;

    /**
     * 公会战 PvP（用户 m09418 #4：先把协议都实现，后续接多人）。
     *
     * <p>号段来自 APK {@code NetProto\NET_MSG_C2S.cs:285-323} / {@code NET_MSG_S2C.cs}，
     * 假服此前**整段未接**（无常量、无路由、无回包）。据点布防、时段、消耗等静态数据都在客户端本地表
     * （{@code GameData\UnionPvP.txt} / {@code UnionPvPDefPointsInfo.txt} / {@code UnionPvPTime.txt}），
     * 服务端只需回「谁在哪个据点放了什么阵容 / 排名 / 战报」这类真服数据。
     */
    public static final int C2S_UNION_PVP_ENROLL = 1549;
    public static final int C2S_UNION_PVP_IS_ENROLL = 1550;
    public static final int C2S_UNION_PVP_DEF_FORMATION = 1551;
    public static final int C2S_UNION_PVP_UPDATE_DEF_FORMATION = 1552;
    public static final int C2S_UNION_PVP_ALL_DEF_FORMATION = 1553;
    public static final int C2S_UNION_PVP_FIGHT_POWER_RANK = 1554;
    public static final int C2S_UNION_PVP_GROW_VALUE_RANK = 1555;
    public static final int C2S_UNION_PVP_FIGHT_RANK = 1556;
    public static final int C2S_UNION_PVP_BRIEF_FIGHT_POWER = 1557;
    public static final int C2S_UNION_PVP_BRIEF_GROW_VALUE = 1558;
    public static final int C2S_UNION_PVP_FIGHT_RECORD = 1559;
    public static final int C2S_UNION_PVP_MY_DEF_POINT_BRIEF = 1560;
    public static final int C2S_UNION_PVP_TARGET_DEF_POINT_BRIEF = 1561;
    public static final int C2S_UNION_PVP_LEFT_DEF_FORMATION = 1562;
    public static final int C2S_UNION_PVP_DEF_POINT_DETAIL = 1563;
    public static final int C2S_UNION_PVP_FIGHT_FORMATION = 1564;
    public static final int C2S_UNION_PVP_POINT_IS_BE_ATTACKED = 1565;
    public static final int C2S_UNION_PVP_WJ_HP = 1566;
    public static final int C2S_UNION_PVP_FIGHT_RESULT = 1567;
    public static final int C2S_UNION_PVP_FIGHT_RECORD_LIST = 1568;
    public static final int S2C_UNION_PVP_ENROLL_RET = 1946;
    public static final int S2C_UNION_PVP_IS_ENROLL_RET = 1947;
    public static final int S2C_UNION_PVP_DEF_FORMATION_RET = 1948;
    public static final int S2C_UNION_PVP_UPDATE_DEF_FORMATION_RET = 1949;
    public static final int S2C_UNION_PVP_ALL_DEF_FORMATION_RET = 1950;
    public static final int S2C_UNION_PVP_FIGHT_POWER_RANK_RET = 1951;
    public static final int S2C_UNION_PVP_GROW_VALUE_RANK_RET = 1952;
    public static final int S2C_UNION_PVP_FIGHT_RANK_RET = 1953;
    public static final int S2C_UNION_PVP_BRIEF_FIGHT_POWER_RET = 1954;
    public static final int S2C_UNION_PVP_BRIEF_GROW_VALUE_RET = 1955;
    public static final int S2C_UNION_PVP_FIGHT_RECORD_RET = 1956;
    public static final int S2C_UNION_PVP_MY_DEF_POINT_BRIEF_RET = 1957;
    public static final int S2C_UNION_PVP_TARGET_DEF_POINT_BRIEF_RET = 1958;
    public static final int S2C_UNION_PVP_LEFT_DEF_FORMATION_RET = 1959;
    public static final int S2C_UNION_PVP_DEF_POINT_DETAIL_RET = 1960;
    /** 1961 {@code CCMsgFightUnionPvPTargetPointDefFormationDetailInfo}；客户端无空判，空 body 会崩。 */
    public static final int S2C_UNION_PVP_TARGET_POINT_FORMATION = 1961;
    // §6-9 死代码清理：原 S2C_UNION_PVP_POINT_IS_BE_ATTACKED_RET = 1962 全工程零引用，
    // 客户端也不注册（§2.3）⇒ 已删。注意对应的 C2S 1565 路由仍在（有意保留，见 dispatcher）。
    public static final int S2C_UNION_PVP_POINT_ATTACKERS = 1963;
    /** 1964 {@code CCMsgRequestUnionPvPWJHP_Ret}；客户端无空判，空 body 会崩。 */
    public static final int S2C_UNION_PVP_WJ_HP_RET = 1964;
    public static final int S2C_UNION_PVP_FIGHT_RECORD_PUSH = 1965;

    public static final int C2S_JIBAN_UPDATE = 1601;
    /** S2C 1601：更换形象回包 CCMsgUpdateResID；与 C2S 羁绊更新同号不同向。 */
    public static final int S2C_UPDATE_RES_ID = 1601;
    public static final int C2S_JIBAN_REFRESH = 1602;
    public static final int C2S_JIBAN_CLOSE = 1603;
    public static final int C2S_JIBAN_APPLY = 1604;
    public static final int C2S_JIBAN_LOCK = 1605;
    public static final int S2C_JIBAN_REFRESH = 4001;

    public static final int C2S_KUANG_PAGE = 1901;
    public static final int C2S_KUANG_DEAD = 1902;
    public static final int C2S_KUANG_DETAIL = 1903;
    public static final int C2S_KUANG_HOLD = 1904;
    public static final int C2S_KUANG_CHANGE_DEF = 1905;
    public static final int C2S_KUANG_LEAVE = 1906;
    public static final int C2S_KUANG_FIGHT = 1907;
    public static final int C2S_KUANG_FIGHT_RESULT = 1908;
    public static final int C2S_KUANG_RES = 1909;
    public static final int C2S_KUANG_RES_OK = 1910;
    public static final int C2S_KUANG_MY = 1911;
    public static final int C2S_KUANG_DEF_WJ = 1912;
    public static final int C2S_KUANG_EMPTY = 1913;
    public static final int C2S_KUANG_RECORD = 1914;
    public static final int C2S_KUANG_RECORD_DETAIL = 1915;
    public static final int C2S_KUANG_CO = 1916;
    public static final int C2S_KUANG_CO_UPDATE = 1917;
    public static final int C2S_KUANG_INVITE = 1918;
    public static final int C2S_KUANG_BUY_ZZ = 1919;
    public static final int C2S_KUANG_RELIVE = 1920;
    public static final int C2S_KUANG_HOLDER = 1921;
    public static final int C2S_KUANG_ENEMY = 1922;
    public static final int S2C_KUANG_PAGE = 2101;
    public static final int S2C_KUANG_DEAD = 2102;
    public static final int S2C_KUANG_DETAIL_SELF = 2103;
    public static final int S2C_KUANG_DETAIL_OTHER = 2104;
    public static final int S2C_KUANG_HOLD = 2105;
    public static final int S2C_KUANG_EARNINGS = 2106;
    public static final int S2C_KUANG_FIGHT = 2107;
    public static final int S2C_KUANG_RES_TIPS = 2108;
    /** 空 tip：客户端置 qkRecourceTips=true（可领产出红点）；开着矿洞会再发 1909。 */
    /** 空 tip：客户端置 qkFightRecord=true（战报红点）。胜负同一包。 */
    public static final int S2C_KUANG_FIGHT_TIP = 2109;
    public static final int S2C_KUANG_RES = 2110;
    public static final int S2C_KUANG_MY = 2111;
    public static final int S2C_KUANG_DEF_WJ = 2112;
    public static final int S2C_KUANG_CLEAR = 2115;
    public static final int S2C_KUANG_EMPTY_CNT = 2116;
    /** 更新矿上进攻人数 {@code CCMsgUpdateKuangAttacker}。 */
    public static final int S2C_KUANG_ATTACKER = 2117;
    /** 空包：通知客户端重拉当前页矿列表。 */
    public static final int S2C_KUANG_REFRESH_PAGE = 2118;
    public static final int S2C_KUANG_RECORD = 2119;
    public static final int S2C_KUANG_RECORD_DETAIL = 2120;
    public static final int S2C_KUANG_CO_WJ = 2121;
    // ⚠️ §2.3：2123 客户端未注册（2105-2129 独缺 2116/2123）⇒ 回包被静默丢弃；
    // 真实效果由 2124 与货币推送落地。**有意保留发送**（C2S 1919 客户端确实会发）。
    public static final int S2C_KUANG_BUY_ZZ = 2123;
    public static final int S2C_KUANG_RELIVE = 2124;
    /** 空包：日清买复活次数（在线客户端清 QKBuyReliveTimes）。 */
    public static final int S2C_KUANG_RESET_RELIVE = 2125;
    public static final int S2C_KUANG_HOLDER = 2126;
    public static final int S2C_KUANG_CO_UPDATE = 2127;
    /** 空包：本局矿战结果作废（矿已换主/被清）。 */
    public static final int S2C_KUANG_INVALID = 2128;
    public static final int S2C_KUANG_ENEMY = 2129;

    public static final int C2S_BUY_TILI = 2401;
    public static final int C2S_BUY_JINBI = 2402;
    public static final int S2C_BUY_TILI_RET = 2701;
    /** 跨日清本地已买体力次数；空包。 */
    public static final int S2C_RESET_BUY_TILI_TODAY = 2702;
    /** 跨日清本地已买金币次数；空包。 */
    public static final int S2C_RESET_BUY_JINBI_TODAY = 2703;
    public static final int S2C_BUY_JINBI_RET = 2704;

    /** VIP 等级礼包领取；S2C 3101 与 C2S 开宝箱同号不同向。 */
    public static final int C2S_VIP_LEVEL_AWARD = 2701;
    public static final int S2C_VIP_LEVEL_AWARD_RET = 3101;

    public static final int C2S_TIME_STONE_OFF = 2901;
    public static final int C2S_TIME_STONE_SET = 2902;

    // 战力排行榜 / 查看他人 / 切磋面板（NET_MSG_C2S.cs:530-539、NET_MSG_S2C.cs:550-555）
    // 同号不同向：C2S 2501=请求战力榜，S2C 2501 是 RestoreVPByNormal(体力恢复)；C2S 2901/2902=时光石
    // 卸下/镶嵌，S2C 2901/2902/2903 才是战力榜/他人简讯/他人武将详情。假服出站从未用过 2901-2903。
    public static final int C2S_FIGHT_POWER_RANK = 2501;
    public static final int C2S_REMOTE_PLAYER_BRIEF = 2502;
    public static final int C2S_REMOTE_PLAYER_WJ_DETAIL = 2503;
    public static final int C2S_REMOTE_PLAYER_BRIEF_CLICK = 2504;
    public static final int C2S_REMOTE_PLAYER_QIECUO_CLICK = 2505;
    public static final int S2C_FIGHT_POWER_RANK_RET = 2901;
    public static final int S2C_REMOTE_PLAYER_BRIEF_RET = 2902;
    public static final int S2C_REMOTE_PLAYER_WJ_RET = 2903;
    public static final int C2S_SDK_PAY_ID = 3001;
    public static final int C2S_SDK_PAY_CHECK = 3002;
    public static final int C2S_EXCHANGE_SHOP = 3003;
    public static final int C2S_TIME_STONE_COMPOSE = 3004;
    public static final int C2S_OPEN_BAOXIANG = 3101;
    public static final int C2S_PRE_CHOICE_BAOXIANG = 3102;
    public static final int C2S_OPEN_CHOICE_BAOXIANG = 3103;
    public static final int C2S_TEQUAN_DAILY = 3501;
    public static final int C2S_ZHIZUN_DAILY = 3502;
    public static final int C2S_TOWER_INVITE = 3201;
    public static final int C2S_TOWER_ACCEPT = 3202;
    public static final int C2S_TOWER_INFO = 3203;
    public static final int C2S_TOWER_UNTIE = 3204;
    public static final int C2S_TOWER_FIGHT = 3205;
    public static final int C2S_TOWER_BUY_TIMES = 3207;
    public static final int C2S_TOWER_RANK = 3209;
    public static final int C2S_QQ_PAY = 3301;
    /** 限时神将：C2S 3302 查询 / 3303 单抽 / 3304 领奖(pos 0-3 宝箱,4 神将) / 3305 十连。 */
    public static final int C2S_LTSJ_INFO = 3302;
    public static final int C2S_LTSJ_DRAW_ONCE = 3303;
    public static final int C2S_LTSJ_GET_PRIZE = 3304;
    public static final int C2S_LTSJ_DRAW_TEN = 3305;
    public static final int C2S_APPLE_PAY = 3401;
    public static final int C2S_SOUL_COMPOSE = 3701;
    public static final int C2S_SOUL_EXP = 3702;
    public static final int C2S_SOUL_JINJIE = 3703;
    public static final int C2S_TIME_STONE_COLOR = 3801;
    public static final int C2S_EXCHANGE_INFO = 4501;
    public static final int C2S_EXCHANGE_DO = 4502;
    public static final int C2S_EQUIP_TRANSFORM = 4801;
    public static final int C2S_JINGLIAN_EXP = 4901;
    public static final int C2S_JINGLIAN_FEIYUE = 4902;
    public static final int C2S_EQUIP_RESET = 5001;

    public static final int S2C_TIME_STONE_OFF = 3301;
    public static final int S2C_TIME_STONE_SET = 3302;
    public static final int S2C_SDK_PAY_ID = 3401;
    public static final int S2C_SDK_PAY_CHECK = 3402;
    public static final int S2C_PAY_SUC = 3403;
    public static final int S2C_TIME_STONE_COMPOSE = 3404;
    /** 限次充值进度（与 C2S 3102 预选宝箱同号不同向）。 */
    public static final int S2C_USER_PAY_INFO = 3102;
    public static final int S2C_OPEN_BAOXIANG = 3501;
    public static final int S2C_PRE_CHOICE_BAOXIANG = 3502;
    public static final int S2C_TOWER_REGION = 3601;
    public static final int S2C_TOWER_INVITE = 3602;
    public static final int S2C_TOWER_INFO = 3603;
    public static final int S2C_TOWER_UNTIE = 3604;
    public static final int S2C_TOWER_TIMES = 3605;
    public static final int S2C_TOWER_BUDDY_LAYER = 3606;
    public static final int S2C_TOWER_RANK = 3608;
    public static final int S2C_QQ_PAY_ASK = 3701;
    /** 限时神将：S2C 3702 全量信息 / 3703 抽卡奖励 / 3704 文字经验特效 / 3705 宝箱与神将状态。 */
    public static final int S2C_LTSJ_INFO = 3702;
    public static final int S2C_LTSJ_PRIZE = 3703;
    public static final int S2C_LTSJ_WENZI_EXP = 3704;
    public static final int S2C_LTSJ_BOX_STATE = 3705;
    // §6-9 死代码清理：原 S2C_APPLE_PAY_RET = 3801 全工程零引用。注意客户端 3801 其实是
    // C2S 方向的 CCMsgTimeStoneChangeColour（BaoShiHeChengUI.cs:385-387），原常量方向记反 ⇒ 已删。
    public static final int S2C_SOUL_COMPOSE = 4301;
    public static final int S2C_SOUL_EXP = 4302;
    public static final int S2C_SOUL_JINJIE = 4303;
    public static final int S2C_TIME_STONE_COLOR = 4401;
    public static final int S2C_TEQUAN_INFO = 3901;
    public static final int S2C_TEQUAN_BUY = 3902;
    public static final int S2C_TEQUAN_DAILY = 3903;
    public static final int S2C_EXCHANGE_GOODS_INFO = 5001;
    public static final int S2C_EXCHANGE_GOODS_DO = 5002;
    public static final int S2C_EQUIP_TRANSFORM_RET = 5401;
    public static final int S2C_EQUIP_RESET_RET = 5601;
    public static final int S2C_JINGLIAN_EXP_RET = 5701;
    /** 穿脱装备后刷新套装被动技能 id 列表 */
    public static final int S2C_UPDATE_SUIT_EFFECT = 5901;
    /** 批量刷新武将 fightpower/addfightpower（穿脱装后） */
    public static final int S2C_UPDATE_ALL_WUJIANG_FIGHT_POWER = 4002;

    public static final int C2S_CLONE_BASE = 5101;
    public static final int C2S_CLONE_CREATE = 5102;
    public static final int C2S_CLONE_QUICK_JOIN = 5103;
    public static final int C2S_CLONE_LEAVE = 5104;
    public static final int C2S_CLONE_FIGHT = 5105;
    public static final int C2S_CLONE_RESULT = 5106;
    public static final int C2S_CLONE_CHANGE_WJ = 5107;
    public static final int C2S_CLONE_CHANGE_QUICK = 5108;
    public static final int C2S_CLONE_JOIN_BY_ID = 5109;
    public static final int C2S_CLONE_UNION_INVITE = 5110;
    public static final int C2S_CLONE_WORLD_INVITE = 5111;
    public static final int S2C_CLONE_BASE = 5801;
    public static final int S2C_CLONE_FIGHT_TEAMS = 5802;
    public static final int S2C_CLONE_RESULT = 5803;
    public static final int S2C_CLONE_QUICK_JOIN = 5804;
    public static final int S2C_CLONE_QUICK_FAIL = 5805;
    public static final int S2C_CLONE_JOIN_BY_ID = 5806;
    public static final int S2C_CLONE_ROOM_TEAM = 5807;
    public static final int S2C_CLONE_TEAM_CHANGED = 5808;

    /**
     * 挑战赛 BOB。注意：C2S 2001 = 要 BOB 自己信息；S2C 2001 = JJC 信息回包（同号不同向）。
     * C2S 2201/2202 仍是抽卡，不是 BOB。
     */
    public static final int C2S_BOB_SELF_INFO = 2001;
    public static final int C2S_BOB_TARGETS = 2002;
    public static final int C2S_BOB_ALL_HP = 2003;
    public static final int C2S_BOB_TARGET_BRIEF = 2004;
    public static final int C2S_BOB_FIGHT = 2005;
    public static final int C2S_BOB_RESULT = 2006;
    public static final int C2S_BOB_RESET = 2007;
    public static final int C2S_BOB_PRIZE_CUP = 2008;
    public static final int C2S_BOB_FORCE_EXIT = 2009;
    public static final int S2C_BOB_TARGETS = 2201;
    public static final int S2C_BOB_ALL_HP = 2202;
    public static final int S2C_BOB_TARGET_BRIEF = 2203;
    public static final int S2C_BOB_TARGET_DETAIL = 2204;
    public static final int S2C_BOB_RESET_TIMES = 2205;
    public static final int S2C_BOB_RESET_RET = 2206;
    public static final int S2C_BOB_PRIZE_INFO = 2207;
    public static final int S2C_BOB_PRIZE_CUP = 2208;
    public static final int S2C_BOB_SELF_INFO = 2209;

    /** 争霸战 ZBZ */
    public static final int C2S_ZBZ_MATCH_PLAYER = 4101;
    public static final int C2S_ZBZ_GAMBLE = 4102;
    public static final int C2S_ZBZ_FIGHT = 4103;
    public static final int C2S_ZBZ_CUR_FIGHTER = 4104;
    public static final int C2S_ZBZ_CHANGE_DEFENSE = 4105;
    public static final int C2S_ZBZ_RESULT = 4106;
    public static final int C2S_ZBZ_RANK = 4107;
    public static final int C2S_ZBZ_JUEZHAN_AWARD = 4108;
    // §6-9 死代码清理：原 S2C_ZBZ_MATCH_PLAYER = 4601 全工程零引用，客户端也不注册
    // （实发的是 4602 CCMsgZBZInfo，见 ZbzService.java:96）⇒ 已删。
    public static final int S2C_ZBZ_INFO = 4602;
    public static final int S2C_ZBZ_FIGHT_RET = 4603;
    public static final int S2C_ZBZ_CUR_FIGHTER = 4604;
    public static final int S2C_ZBZ_STATE_CHANGE = 4605;
    public static final int S2C_ZBZ_RANK = 4606;
    public static final int S2C_ZBZ_JUEZHAN_AWARD = 4607;

    /**
     * 大厅转盘（梦幻转盘）。C2S 4601-4605 与上面的 S2C_ZBZ_* 4601-4605 <b>同号不同向</b>：
     * 客户端 C2S 4601-4605 = 转盘（NET_MSG_C2S.cs:670-679），S2C 4601-4605 = 争霸战 ZBZ。
     * MessageDispatcher 只按入站 pkt.msgId 分派，故新增 case 不会影响 ZBZ 出站。
     * 转盘回包是 S2C 5101-5106；注意 C2S 5101-5106 已被 C2S_CLONE_* 占用（同样只是号段复用）。
     */
    public static final int C2S_ZHUANPAN_BASE = 4601;
    public static final int C2S_ZHUANPAN_DRAW_ONE = 4602;
    public static final int C2S_ZHUANPAN_DRAW_TEN = 4603;
    public static final int C2S_ZHUANPAN_RANK = 4604;
    public static final int C2S_ZHUANPAN_POOL_INFO = 4605;
    public static final int S2C_ZHUANPAN_BASE_INFO = 5101;
    public static final int S2C_ZHUANPAN_STATUS = 5102;
    public static final int S2C_ZHUANPAN_DRAW_ONE_RET = 5103;
    public static final int S2C_ZHUANPAN_DRAW_TEN_RET = 5104;
    public static final int S2C_ZHUANPAN_RANK_RET = 5105;
    public static final int S2C_ZHUANPAN_BIG_AWARD_ADD = 5106;

    /** 跨服战 KFZ（本服假跨服） */
    public static final int C2S_KFZ_ZHAN_KUANG = 4201;
    public static final int C2S_KFZ_XIANGXI = 4202;
    public static final int C2S_KFZ_MINGREN = 4203;
    public static final int C2S_KFZ_SAICHENG = 4204;
    public static final int C2S_KFZ_DUIZHAN = 4205;
    public static final int C2S_KFZ_OFFENCE_BUZHEN = 4206;
    public static final int C2S_KFZ_PYS_FIGHT = 4207;
    public static final int C2S_KFZ_PYS_RESULT = 4208;
    public static final int C2S_KFZ_DFS_FIGHT = 4209;
    public static final int C2S_KFZ_DFS_RESULT = 4210;
    public static final int C2S_KFZ_RANK = 4211;
    public static final int C2S_KFZ_PAIWEI_RANK = 4212;
    public static final int C2S_KFZ_WORSHIP = 4213;
    public static final int S2C_KFZ_ZHAN_KUANG = 4701;
    public static final int S2C_KFZ_XIANGXI = 4702;
    public static final int S2C_KFZ_MINGREN = 4703;
    public static final int S2C_KFZ_PYS_SAICHENG = 4704;
    public static final int S2C_KFZ_DUIZHAN = 4705;
    public static final int S2C_KFZ_TOP3 = 4706;
    public static final int S2C_KFZ_OFFENCE_BUZHEN = 4707;
    public static final int S2C_KFZ_FIGHT_TEAMS = 4708;
    public static final int S2C_KFZ_RANK = 4709;
    public static final int S2C_KFZ_PAIWEI_RANK = 4710;
    public static final int S2C_KFZ_DFZ_SAICHENG = 4711;
    public static final int S2C_KFZ_PHASE = 4712;
    public static final int S2C_KFZ_WORSHIP_RET = 4713;
    public static final int S2C_KFZ_WORSHIP_STATUS = 4714;
    public static final int S2C_KFZ_PLAYER_STATUS = 4715;

    // ============================================================================================
    // 聊天 / 黑名单（2026-02 补：docs/PROTOCOL_GAP_REPORT.md §6 第 5 条）
    // ============================================================================================

    /** C2S 501 {@code NET_CCMsgRequestChatInfo}：无 body，展开聊天面板时只发一次。 */
    public static final int C2S_CHAT_INFO = 501;
    /** C2S 502 {@code NET_CCMsgChatToSvr}：type 1 世界 / 2 公会 / 3 私聊 / 4 DEBUG。 */
    public static final int C2S_CHAT_TO_SVR = 502;
    /** C2S 503 {@code NET_CCMsgAddIntoBlackList}：体是 {@code CPlayerGuidAndNameAndResIDAndLevel}。 */
    public static final int C2S_ADD_BLACK_LIST = 503;
    /** C2S 504 {@code NET_CCMsgRemoveFromBlackList}：体同 503。 */
    public static final int C2S_DEL_BLACK_LIST = 504;

    /**
     * S2C 802 {@code NET_CCMsgUpdateBlackList}：{@code {1 IsAdd, 2 CPlayerGuidAndNameAndResIDAndLevel}}。
     * <p>注意 {@code C2S_HANDLE_MAIL = 802} 是<b>反方向</b>占用同一数字（收邮件），不要混用。
     */
    public static final int S2C_UPDATE_BLACK_LIST = 802;

    // ============================================================================================
    // 喇叭（雪花道具）（同 §6 第 5 条）
    // ============================================================================================

    /**
     * C2S 4701 / 4702 = 小 / 大喇叭（{@code NET_CCMsgRequestOpenSmallLaBa} /
     * {@code ...OpenBigLaBa}）。
     * <p>⚠ {@code S2C_KFZ_ZHAN_KUANG = 4701} / {@code S2C_KFZ_XIANGXI = 4702} 是<b>反方向</b>的
     * 跨服战包；本对常量只用于入站分派，出站走 5201/5202。
     */
    public static final int C2S_OPEN_SMALL_LABA = 4701;
    /** 见 {@link #C2S_OPEN_SMALL_LABA}。 */
    public static final int C2S_OPEN_BIG_LABA = 4702;
    /**
     * S2C 5201 {@code NET_CCMsgRequestOpenLaBa_Ret}。
     * <p>客户端 handler {@code ᝁ.cs:8381-8384} 实际反序列化的是 {@code CCMsgOpenBaoXiang_Ret}
     * （复制粘贴的开宝箱 handler），喇叭字段一个都没用 ⇒ <b>发空包体即可</b>。
     */
    public static final int S2C_LABA_OPEN_RET = 5201;
    /**
     * S2C 5202 {@code NET_CCMsgRequestBroadCastLaBa}，体 = {@code CCMsgLaBaOpenMessage}
     * {@code {1 words, 2 oriName, 3 dynID, 4 playerName, 5 playerGuid, 6 serverID}}。
     * <p>客户端 {@code ᝁ.cs:8490-8506}：{@code GetPropertyCfg(oriName)} 后按
     * {@code mCustomParam2} 第 2/3 段开场景天气与冒头单位头顶特效 ⇒ oriName 必须是 4701/4702 那两个
     * 喇叭道具之一，dynID 用 0（客户端对取不到的 unit 有 null 守卫）。
     */
    public static final int S2C_LABA_BROADCAST = 5202;

    // ============================================================================================
    // 龙腾 / 限时兑换（大厅独立入口，与看板 type23「圣诞/新年兑换」不是一套）（同 §6 第 5 条）
    // ============================================================================================

    public static final int C2S_LT_EXCHANGE_LOTTERY = 3601;
    public static final int C2S_LT_EXCHANGE_GOODS = 3602;
    public static final int C2S_LT_EXCHANGE_RANK = 3603;
    /**
     * S2C 4101 {@code CMsgUpdateLTExchangeInfo}（⚠ 前缀是 {@code CMsg} 不是 {@code CCMsg}）。
     * <p><b>没有对应的 C2S</b>，客户端数据全靠它 push；不发则抽奖/商品/排行三块全空，
     * 而且 {@code ExchangeGoods.cs:176} 会因 {@code xunzhangName == null} 直接 NRE。
     * 所以进大厅就必须推一次。
     */
    public static final int S2C_LT_EXCHANGE_INFO = 4101;
    public static final int S2C_LT_EXCHANGE_PRIZE = 4102;
    public static final int S2C_LT_EXCHANGE_RANK_RET = 4103;
    public static final int S2C_LT_EXCHANGE_GOODS_RET = 4104;

    // ============================================================================================
    // 好友（同 §6 第 5 条）
    // ============================================================================================

    /**
     * S2C 1303 {@code NET_CCMsgRequestFriendsList_ret}，体 {@code CFriendsInfo}。
     * <p>⚠ 与 {@code C2S_SHOP_REFRESH = 1303} <b>同号反向</b>（客户端 C2S 1303 是商店手动刷新）——
     * 出站务必用本常量名，不要复用 {@code C2S_SHOP_REFRESH}。
     */
    public static final int S2C_FRIEND_LIST_RET = 1303;
    /** S2C 1304 {@code NET_CCMsgResponseDeleteFriend}，体 {@code CFriendGuid{1 Guid}}。 */
    public static final int S2C_FRIEND_DELETE_RET = 1304;
    /** S2C 1305 {@code NET_CCMsgRequestGiveFriendPower_Ret}，体 {@code CFriendGuid}。 */
    public static final int S2C_FRIEND_GIVE_RET = 1305;
    /** S2C 1306 {@code NET_CCMsgResponseGiveFriendPower}：纯推送（无 C2S 对偶），体 {@code CFriendGuid}。 */
    public static final int S2C_FRIEND_GIVE_PUSH = 1306;
    /** S2C 1307 {@code NET_CCMsgRequestAcceptFriendPower_Ret}，体 {@code CFriendAccPowerRet{1 guid,2 count}}。 */
    public static final int S2C_FRIEND_ACCEPT_POWER_RET = 1307;
    /** S2C 1308 {@code NET_CCMsgRequestAddFriend_ret}，体 {@code CAddFriendRet{1 success,2 type,3 guid,4 errorcode}}。 */
    public static final int S2C_FRIEND_ADD_RET = 1308;
    /** S2C 1309 {@code NET_CCMsgResponseAddFriend}：纯推送，<b>客户端完全不解析包体</b>，发空即可（只会回发 1418）。 */
    public static final int S2C_FRIEND_ADD_PUSH = 1309;
    /** S2C 1310 {@code NET_CCMsgRequestPushApplyList_ret}，体 {@code CFriendsBase{1 repeated CFriendBase}}。 */
    public static final int S2C_FRIEND_PUSH_APPLY_RET = 1310;
    /** S2C 1311 {@code NET_CCMsgRequestInviteMe_ret}：复用 {@code CAddFriendRet} 体。 */
    public static final int S2C_FRIEND_INVITE_ME_RET = 1311;
    /** S2C 1312 {@code NET_CCMsgResponseInviteCount}，体 {@code CInviteCount{1 count,2 realcount}}（客户端只读 realcount）。 */
    public static final int S2C_FRIEND_INVITE_COUNT_RET = 1312;
    /** S2C 1313 {@code NET_CCMsgResponseInviteReward}，体 {@code CInviteReward{1 type,2 success,3 index}}。 */
    public static final int S2C_FRIEND_INVITE_REWARD_RET = 1313;
    /** S2C 1314 {@code NET_CCMsgRequestFriendApplyList_ret}，体 {@code CFriendsBase}。 */
    public static final int S2C_FRIEND_APPLY_LIST_RET = 1314;
    /** S2C 1315 {@code NET_CCMsgRequestFriendApplyDeal_ret}，体 {@code CAgreeFriendRet{1 success,2 type,3 guid,4 errorcode}}。 */
    public static final int S2C_FRIEND_APPLY_DEAL_RET = 1315;
    /** S2C 1316 {@code NET_CCMsgRemotePlayerBreifInfo_ret}：<b>包体与 S2C 2902 完全相同</b>，直接复用。 */
    public static final int S2C_FRIEND_BRIEF_RET = 1316;
    /** S2C 1317 {@code NET_CCMsgResponseInviteRewards}：纯推送，体 {@code CInviteRewards{1 repeated id}}。 */
    public static final int S2C_FRIEND_INVITE_REWARDS = 1317;
    /** S2C 1318 {@code NET_CCMsgResponseInviteMyInfo}，体 {@code CInviteMyInfo{1 guid,2 rewards}}（guid==0 表示尚未被邀请）。 */
    public static final int S2C_FRIEND_INVITE_MY_INFO = 1318;
    /** S2C 1319 {@code NET_CCMsgResponseAgreeFriend}：纯推送，客户端解析后丢弃包体。 */
    public static final int S2C_FRIEND_AGREE_PUSH = 1319;
    /**
     * S2C 1320 {@code NET_CCMsgResponseRedPoint}，体 {@code CFriendRedPoint{1 rewards,2 applies,3 flushtime}}。
     * <p><b>最关键的一个包</b>：{@code FriendSystemServerData.isCanGetRewards/applyCount} 全工程唯一
     * 写点就是它的 handler（{@code FriendSystem.cs:998-999}），不回则大厅社交红点与好友面板红点
     * <b>永不出现</b>。{@code flushtime} 同时是加好友推荐页的倒计时（{@code :1001}）。
     */
    public static final int S2C_FRIEND_RED_POINT = 1320;

    public static final int C2S_FRIEND_LIST = 1403;
    public static final int C2S_FRIEND_DELETE = 1404;
    public static final int C2S_FRIEND_GIVE_POWER = 1405;
    public static final int C2S_FRIEND_ACCEPT_POWER = 1406;
    public static final int C2S_FRIEND_ADD_BY_GUID = 1407;
    public static final int C2S_FRIEND_ADD_BY_NAME = 1408;
    public static final int C2S_FRIEND_PUSH_APPLY = 1409;
    public static final int C2S_FRIEND_PUSH_APPLY_NEXT = 1410;
    /** 输入邀请码；包体 {@code CFriendGuid} 里装的是邀请码，不是玩家 guid。 */
    public static final int C2S_FRIEND_INVITE_ME = 1411;
    public static final int C2S_FRIEND_INVITE_COUNT = 1412;
    /** 领邀请档位奖；包体 {@code CFriendGuid.Guid} 装的是档位人数（3/10/20/30/40）。 */
    public static final int C2S_FRIEND_INVITE_REWARD = 1413;
    public static final int C2S_FRIEND_INVITE_MY_REWARD = 1414;
    public static final int C2S_FRIEND_APPLY_LIST = 1415;
    /** 同意/拒绝同一号，靠 tag2 {@code agree} 区分。 */
    public static final int C2S_FRIEND_APPLY_DEAL = 1416;
    public static final int C2S_FRIEND_BRIEF = 1417;
    /** 红点查询（无 body）；也是各类好友回包后客户端自触发的重查。 */
    public static final int C2S_FRIEND_RED_POINT = 1418;
}
