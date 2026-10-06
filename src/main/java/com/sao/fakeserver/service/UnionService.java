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
import com.sao.fakeserver.table.FightConfigTables;
import com.sao.fakeserver.table.UnionCfg;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 公会域（D6）：入会/管理、建筑与佣兵、作战室 Boss 与拍卖、医院、厨房、训练场、马厩押镖劫镖。
 *
 * <p>数据口径一律以 APK 客户端散表为准（{@code tables/Union*.txt}，由 {@link UnionCfg} 解析），
 * 回包字段与嵌套结构对齐 {@code NetProto} 的 proto 定义。
 */
@Service
public class UnionService {
    private static final Logger log = LoggerFactory.getLogger(UnionService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 表缺失时的 Boss 满血兜底。 */
    private static final int BOSS_HP_FALLBACK = 1000000;
    // 「单场掠夺超时时间（秒）」不再硬编码：改读表 tables\UnionMaJiuBase.txt
    // （UnionCfg.raidTimeoutSec()，缺表/≤0 回退 300）。改前是 RAID_TIMEOUT_SEC = 300 的副本，
    // 表值改成 600 不生效（用户本轮「表键消费清扫」发现）。
    /**
     * 「正在拦截」标记的服务端宽限（秒）：客户端战斗到点（读同一张表的超时值）后自己发 1545 结算，
     * 但掉线/杀进程就永远不发 ⇒ 服务端在 表值 + 宽限后清掉那辆车的 {@code raiderName}，
     * 否则该车当天对所有人都是 1939 ret=1、被劫方整天显示「掠夺中」（死锁一整天）。
     */
    private static final int RAID_TIMEOUT_GRACE_SEC = 60;
    /**
     * 可劫镖车列表（1936/1942 f4）**每个目的地**最多下发几辆：10（假服自定）。
     *
     * <p>依据与取舍：客户端 {@code MaJiuLanJieDuiWuUI.cs:88-99} 把 f4 当权威全量列表，
     * 按选中目的地过滤渲染；{@code CCMsgRequestMaJiuInfoRet} 里**没有**容量/分页/抽签字段，
     * {@code tables\UnionMaJiuBase.txt} 也没有目标数量键 ⇒ 抽样规模只能由服务端定。
     * 用户 m19393 #2 拍板「可劫列表随机选」；按目的地分组抽样是为了保证每个目的地
     * 都仍能看到车（全局抽 10 辆会让冷门目的地整片空白）。
     * 每次构造都重新洗牌 ⇒ 1547 刷新后面板内容确实会变（否则刷新没有视觉反馈）。
     */
    private static final int RAID_LIST_PER_TARGET = 10;
    /** 会长连续未登录多久自动让位：一周（StrTable 101282「由于你一周未登录游戏,会长职务由{0}接任」）。 */
    private static final long OWNER_INACTIVE_MS = 7L * 24 * 60 * 60 * 1000;
    /**
     * 单支防守队伍内的武将槽数（1..5）。客户端 {@code GongHuiZhanJuDianBuFangUI.cs:163}
     * {@code for (int i = 1; i <= 5; i++)} 出 5 个武将槽——这是**队伍内**的槽数，与据点能放几支队伍无关。
     */
    private static final int PVP_FORMATION_SLOTS = 5;
    /**
     * 每个据点的防守队伍上限 = 2。依据：客户端 {@code MobileGameDemo\GongHuiZhanJuDianBuFangUI.cs:373}
     * {@code private static int MAX_FORMATION_COUNT = 2;}（:93 未满才允许添加、:189-194 超限弹
     * {@code Code.txt:761} 100780「每个据点最多布防{0}支队伍」）；出厂表
     * {@code tables\UnionPvPDefPointsInfo.txt} 没有这一列，故取客户端常量。
     * 原实现误把 {@link #PVP_FORMATION_SLOTS}(5) 当队伍数上限，且 1552 丢弃客户端的合法首格索引 0。
     */
    private static final int PVP_DEF_FORMATION_MAX = 2;
    /**
     * 公会战防守预设的 type 基号 = 24。{@code pyfoot\tmp_msgdll\NetProto\eFormationType.cs} 里
     * {@code FORMATION_TYPE_UNION_PVP_DEFENSE_1..5 = 24..28}，即「我的队伍」面板的 5 套预设；
     * 该面板用通用 201/202 接口按 type 存读（{@code GongHuiZhanMyTeamUI.cs:102-104/225-231}，
     * 收包在 {@code ᝁ.cs:3374-3418} 的 switch case 24..28）。
     */
    private static final int PVP_DEF_FORMATION_TYPE_BASE = 24;
    /**
     * 公会战战报保留条数上限 = 3。客户端 {@code MobileGameDemo\UnionBattleRecordTips.cs:74/81/88}
     * 只写了 count 1/2/3 三个分支，{@code :99 Transform transform2 = transform.FindChild(...)}
     * 之后直接解引用；第 4 条起 FindChild 返回 null ⇒ NullReferenceException（战报列表打不开）。
     * {@code PlayGameState.cs:5017} 只挡 {@code FightRecord.Count == 0}，不挡上限。
     */
    private static final int PVP_RECORD_MAX = 3;
    /**
     * 马厩的建筑类型号（APK {@code EUnionBuildingType.EUBT_MAJIU = 5}，见
     * {@code pyfoot\tmp_msgdll\NetProto\EUnionBuildingType.cs:18-19}）。押镖目的地门槛
     * （{@code UnionMaJiuTarget.txt} 第 2 列「所需马厩等级」）比的就是这个建筑的等级。
     */
    private static final int MAJIU_BUILDING = 5;
    /**
     * 其余建筑类型号（APK {@code EUnionBuildingType}）：厨房 2、训练场 3、作战室 6、医院 7。
     * 用于 {@link #canUseBuilding} 的「使用条件：攻略组等级」闸门。
     */
    private static final int KITCHEN_BUILDING = 2;
    private static final int TRAIN_BUILDING = 3;
    private static final int WARROOM_BUILDING = 6;
    private static final int HOSPITAL_BUILDING = 7;
    /** 当天是否已做过押镖统一结算（分钟级钩子在结算时段内不必反复扫号）。 */
    private volatile String majiuSettledDay = "";

    private final PlayerStore players;
    private final WorldStore world;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final EconomyTables economy;
    private final UnionCfg unionCfg;
    private final FightConfigTables fight;
    private final SessionHub sessions;
    private final MailService mail;
    private final CultivateTables cultivate;
    private final AtomicInteger unionSeq = new AtomicInteger(1);

    public UnionService(PlayerStore players, WorldStore world, PlayerDumpService dump,
                        @Lazy ProgressService progress, EconomyTables economy, UnionCfg unionCfg,
                        FightConfigTables fight, SessionHub sessions, @Lazy MailService mail,
                        CultivateTables cultivate) {
        this.players = players;
        this.world = world;
        this.dump = dump;
        this.progress = progress;
        this.economy = economy;
        this.unionCfg = unionCfg;
        this.fight = fight;
        this.sessions = sessions;
        this.mail = mail;
        this.cultivate = cultivate;
        int max = 0;
        for (WorldStore.UnionRecord u : world.unions()) {
            try {
                if (u.id != null && u.id.startsWith("u-")) {
                    max = Math.max(max, Integer.parseInt(u.id.substring(2)));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        unionSeq.set(max + 1);
    }

    // ------------------------------------------------------------------ 入会 / 创建

    public void onCreate(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        if (rec.guild.id != null && !rec.guild.id.isEmpty()) {
            session.send(MsgIds.S2C_CREATE_UNION_RET, pkt, dump.unionCreateRet(false, rec.guild.name));
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        String name = f.getString(1);
        String icon = f.getString(2);
        if (name == null || name.trim().isEmpty()) {
            name = rec.roleName + "公会";
        }
        name = name.trim();
        // 公会名长度上限 7：客户端 UnionCreateAndJoinSystem.cs:338-344 本地拦「> 7 弹 100705」，
        // 服务端不拦则伪造包可建超长名（列表/排行 UI 溢出）。
        if (name.isEmpty() || name.length() > 7) {
            session.send(MsgIds.S2C_CREATE_UNION_RET, pkt, dump.unionCreateRet(false, name));
            return;
        }
        if (rec.level < unionCfg.openLevel() || rec.diamond < unionCfg.createRmb()
                || world.findUnionByName(name) != null) {
            session.send(MsgIds.S2C_CREATE_UNION_RET, pkt, dump.unionCreateRet(false, name));
            return;
        }
        rec.diamond -= unionCfg.createRmb();
        WorldStore.UnionRecord u = new WorldStore.UnionRecord();
        u.id = "u-" + unionSeq.getAndIncrement();
        u.name = name;
        u.icon = PlayerDumpService.unionIcon(icon);
        u.joinType = "direct";
        u.ensure();
        u.members.add(memberOf(rec, "owner"));
        world.unions().add(u);
        world.saveUnions();
        rec.guild.id = u.id;
        rec.guild.name = u.name;
        rec.guild.job = "owner";
        players.save(rec);
        session.send(MsgIds.S2C_CREATE_UNION_RET, pkt, dump.unionCreateRet(true, u.name));
        progress.pushDiamond(session, pkt, rec);
        log.info("{} created union {} cost={}", rec.account, u.name, unionCfg.createRmb());
    }

    /**
     * 1502 UnionList。请求体 {@code CCMsgUnionFuzzyName.UnionName}(f1) 是模糊搜索名：
     * 客户端 {@code UnionCreateAndJoinSystem.cs:399-401} 发搜索词、{@code :236} 收到后**全量渲染
     * 不做本地过滤** ⇒ 服务端必须自己筛，否则搜索框形同虚设。
     */
    public void onList(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        String fuzzy = Pb.read(pkt.body).getString(1);
        List<WorldStore.UnionRecord> list = new ArrayList<>();
        for (WorldStore.UnionRecord u : world.unions()) {
            if (fuzzy != null && !fuzzy.isEmpty() && (u.name == null || !u.name.contains(fuzzy))) {
                continue;
            }
            list.add(u);
        }
        session.send(MsgIds.S2C_UNION_LIST_RET, pkt,
                dump.unionList(list, rec == null ? 0 : rec.playerId));
    }

    /**
     * 1503 RequestJoinInUnion。按公会 joinType 分流：
     * EUJT_ALL(1) 直接入会、EUJT_Verify(0) 进申请列表并推 1907/1912、EUJT_NONE(2) 拒绝。
     */
    public void onJoin(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        String id = Pb.read(pkt.body).getString(1);
        WorldStore.UnionRecord u = world.findUnion(id);
        if (u == null || (rec.guild.id != null && !rec.guild.id.isEmpty())) {
            session.send(MsgIds.S2C_JOIN_UNION_RET, pkt, dump.unionJoinRet(false, rec.guild.name, 1));
            return;
        }
        u.ensure();
        if (rec.level < u.joinLevel) {
            session.send(MsgIds.S2C_JOIN_UNION_RET, pkt, dump.unionJoinRet(false, u.name, 3));
            return;
        }
        if (u.members.size() >= unionCfg.memberMax()) {
            session.send(MsgIds.S2C_JOIN_UNION_RET, pkt, dump.unionJoinRet(false, u.name, 4));
            return;
        }
        long now = System.currentTimeMillis();
        if (rec.guild.quitUnionAt > now) {
            session.send(MsgIds.S2C_JOIN_UNION_RET, pkt, dump.unionJoinRet(false, u.name, 5));
            return;
        }
        if ("deny".equals(u.joinType)) {
            session.send(MsgIds.S2C_JOIN_UNION_RET, pkt, dump.unionJoinRet(false, u.name, 2));
            return;
        }
        if ("verify".equals(u.joinType)) {
            WorldStore.Member exist = findRequester(u, rec.playerId);
            if (exist == null) {
                if (u.requesters.size() < unionCfg.requestMax()) {
                    u.requesters.add(memberOf(rec, "member"));
                }
                world.saveUnions();
                pushOwnerAndElders(u, MsgIds.S2C_UNION_REQUESTERS_RET, dump.unionRequesters(u.requesters));
                pushOwnerAndElders(u, MsgIds.S2C_UNION_JOIN_REQUEST_TIPS, new byte[0]);
            }
            session.send(MsgIds.S2C_JOIN_UNION_RET, pkt, dump.unionJoinRet(false, u.name, 0));
            log.info("{} applied to union {}", rec.account, u.name);
            return;
        }
        addMember(u, rec);
        session.send(MsgIds.S2C_JOIN_UNION_RET, pkt, dump.unionJoinRet(true, u.name, 0));
        pushOwnerAndElders(u, MsgIds.S2C_UNION_REQUESTERS_RET, dump.unionRequesters(u.requesters));
        log.info("{} joined union {}", rec.account, u.name);
    }

    public void onDetail(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        String id = Pb.read(pkt.body).getString(1);
        if (id == null || id.isEmpty()) {
            id = rec.guild.id;
        }
        WorldStore.UnionRecord u = world.findUnion(id);
        if (u == null) {
            // 不能回空 body：客户端 PlayGameState.cs:4070 会 MemberInfo.Clear()、:4086 读
            // MemberInfo.MemberInfo.Count ⇒ 空流反序列化后 MemberInfo=null 直接 NRE。
            session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, dump.unionDetailEmpty());
            return;
        }
        session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, dump.unionDetail(u, syncMembers(u)));
        // 兜底推 1915（用户 m19522 #1）：公会详情打开后客户端可能直接落在「佣兵」页签上，
        // 而 UIToggle.onChange 不一定触发 ⇒ 不主动推的话收益/入驻武将面板会空到玩家手点页签。
        // 只在看**自己公会**时推（佣兵面板属于自己公会；看别家详情时推过去也没有对应 UI）。
        if (rec.guild.id != null && rec.guild.id.equals(u.id)) {
            session.send(MsgIds.S2C_UNION_EMPLOYERS, 0, employersBody(rec));
        }
        // 入会申请红点补推（子代理 ce83f80c 审计出的「该推不推」）：
        // 客户端 mUnionJoinRequest 是内存字段（MainPlayerAttribute.cs:349），重登即 false；唯一置 true
        // 处是 1912 handler（ᝁ.cs:6542）与 1907 回包（UnionManagerSystem.cs:284）。申请若在会长/长老
        // 离线时到达，那次 push（:257/:258）被丢弃后就再没人补 ⇒ 大厅提示（DA:801-807）、据点议事厅
        // 红点（᝺:42/88）、管理面板红点（UM:162/506）三处全不亮，玩家必须盲点「入会申请」页
        // （UM:711 是全客户端唯一发 1505 处）才看得到积压申请。
        // 1504 是开管理面板（UM:80）与进公会基地场景（᝝:92）的必发包，在此补推空体标志包；
        // 申请列表为空时不推，否则会点亮一个假红点。
        if (rec.guild.id != null && rec.guild.id.equals(u.id) && isOwnerOrElder(rec)) {
            u.ensure();
            if (!u.requesters.isEmpty()) {
                session.send(MsgIds.S2C_UNION_JOIN_REQUEST_TIPS, 0, new byte[0]);
            }
        }
    }

    /** 1505 GetUnionRequestPlayers：真实申请列表（原为空包）。仅会长/长老可看（申请列表属管理面板）。 */
    public void onRequesters(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        WorldStore.UnionRecord u = rec == null ? null : world.findUnion(rec.guild.id);
        if (u == null || !isOwnerOrElder(rec)) {
            session.send(MsgIds.S2C_UNION_REQUESTERS_RET, pkt, dump.unionRequesters(null));
            return;
        }
        u.ensure();
        session.send(MsgIds.S2C_UNION_REQUESTERS_RET, pkt, dump.unionRequesters(u.requesters));
    }

    /** 1506 HandleUnionRequester：同意/拒绝入会申请。 */
    public void onHandleRequester(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null || !isOwnerOrElder(rec)) {
            session.send(MsgIds.S2C_HANDLE_REQUESTER_RET, pkt, dump.handleRequesterRet(false, 6, null, false));
            return;
        }
        u.ensure();
        Pb.Fields f = Pb.read(pkt.body);
        int guid = f.getInt(1, 0);
        boolean agree = f.getBool(2);
        WorldStore.Member r = findRequester(u, guid);
        if (r == null) {
            session.send(MsgIds.S2C_HANDLE_REQUESTER_RET, pkt, dump.handleRequesterRet(false, 1, null, false));
            return;
        }
        if (agree && u.members.size() >= unionCfg.memberMax()) {
            // 满员必须在移除申请之前判：改前先 remove 再 return，申请人既没进会也从申请列表消失。
            // 1905 的 failReason 客户端只映射 1→100703 / 2→100179 / 3→100182
            // （UnionManagerSystem.cs:1207-1220 GetJoinFailReasonMsg，default 返回空串 ⇒ 弹空提示），
            // 4 只在 1904 路径有映射，故这里用 2「成员已满」。
            session.send(MsgIds.S2C_HANDLE_REQUESTER_RET, pkt, dump.handleRequesterRet(false, 2, null, false));
            return;
        }
        u.requesters.remove(r);
        if (!agree) {
            world.saveUnions();
            // 拒绝时**不回 1905**：1905 的客户端消费点 UnionManagerSystem.cs:475-490 是
            // `if (Success) { ... 连读 newMember.Guid/ResId/Level/Name/Job/IsOnline/OfflineTime ... }`
            // —— Success=true 且 f3 `newMember` 缺失时 protobuf-net 给出 null ⇒ :478 NRE，
            // 成员列表/管理面板都不刷新；而 Success=false 会走 :493-495 的
            // GetJoinFailReasonMsg（:1207-1219 只映射 1→100703 / 2→100179 / 3→100182，default 返回 ""）
            // —— 枚举 EFailJoinInUnionReason 里没有任何值表示「被会长拒绝」，随便挑一个都是假提示、
            // 挑 0 又是空提示气泡。客户端点「拒绝」时已在本地删掉申请行
            // （UnionManagerSystem.cs:773-787 OnClickRefuseApply 里 DestroyImmediate），
            // 不依赖任何回包，故这里只做：申请人收 1904（failReason 2）+ 会长/长老收 1907 刷新申请列表。
            push(r.account, MsgIds.S2C_JOIN_UNION_RET, dump.unionJoinRet(false, u.name, 2));
            pushOwnerAndElders(u, MsgIds.S2C_UNION_REQUESTERS_RET, dump.unionRequesters(u.requesters));
            log.info("{} refused {} join request of union {}", rec.account, r.account, u.name);
            return;
        }
        PlayerRecord target = players.get(r.account);
        if (target == null) {
            session.send(MsgIds.S2C_HANDLE_REQUESTER_RET, pkt, dump.handleRequesterRet(false, 1, null, false));
            return;
        }
        if (target.guild.id != null && !target.guild.id.isEmpty()) {
            // 申请者已被别的公会收走：申请期间他可以**直接**加入 joinType=all 的公会（1503 只查
            // 「自己有没有公会」，不查「有没有在别处挂着申请」），而本方法改前不查 target 的公会归属
            // ⇒ addMember 把他塞进本会、只改 rec.guild.id，原公会的成员列表里会留下一份幽灵成员
            // （两份 1906 都含他、原会 memberMax 计数虚高）。
            // EFailJoinInUnionReason 1 = EFJIUR_ALREADYHAVEUNION，客户端 1905 失败分支映射
            // 100703「申请者已经有了公会」（UnionManagerSystem.cs:485 → :1164-1177 ᜀ(int)），
            // 正是真服为这条校验准备的文案。申请行已在上面 remove，这里补推 1907 让管理面板掉行
            // （客户端失败分支只弹提示、不本地删行）。
            session.send(MsgIds.S2C_HANDLE_REQUESTER_RET, pkt, dump.handleRequesterRet(false, 1, null, false));
            world.saveUnions();
            pushOwnerAndElders(u, MsgIds.S2C_UNION_REQUESTERS_RET, dump.unionRequesters(u.requesters));
            log.info("{} approve refused: {} already in union {}", rec.account, r.account, target.guild.id);
            return;
        }
        WorldStore.Member m = addMember(u, target);
        world.saveUnions();
        session.send(MsgIds.S2C_HANDLE_REQUESTER_RET, pkt, dump.handleRequesterRet(true, 0, m, isOnline(m.playerId)));
        // 排除操作者：1905 是单条成员记录，客户端 MemberInfo.Add 不去重，重复收会把新成员渲染成两行。
        pushOwnerAndElders(u, MsgIds.S2C_HANDLE_REQUESTER_RET, dump.handleRequesterRet(true, 0, m, isOnline(m.playerId)), rec.playerId);
        pushOwnerAndElders(u, MsgIds.S2C_UNION_REQUESTERS_RET, dump.unionRequesters(u.requesters));
        push(m.account, MsgIds.S2C_JOIN_UNION_RET, dump.unionJoinRet(true, u.name, 0));
        push(m.account, MsgIds.S2C_UNION_JOB_UPDATE, dump.unionJobUpdate(m.job));
        log.info("{} accepted {} into union {}", rec.account, m.account, u.name);
    }

    /**
     * 1906 兜底：公会不存在时发最小合法包，否则回真实详情。
     *
     * <p>两者都**不能发 {@code new byte[0]}** —— 客户端 {@code PlayGameState.cs:4070} 拿到 1906 后
     * 直接 {@code MemberInfo.Clear()}、{@code :4086} 读 {@code MemberInfo.MemberInfo.Count}，
     * 空流反序列化后 {@code MemberInfo} 为 null 会 NRE，并把本地公会详情清成默认值。</p>
     */
    private byte[] unionDetailOrEmpty(WorldStore.UnionRecord u) {
        return u == null ? dump.unionDetailEmpty() : dump.unionDetail(u, syncMembers(u));
    }

    /** 1507 AppointElder：任命/罢免长老（长老人数上限按 Union.txt）。 */
    public void onAppointElder(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null || rec.guildJobCode() != 3) {
            session.send(MsgIds.S2C_APPOINT_ELDER_RET, pkt, dump.appointElderRet(false, false, 0));
            return;
        }
        u.ensure();
        Pb.Fields f = Pb.read(pkt.body);
        int guid = f.getInt(1, 0);
        boolean appoint = f.getBool(2);
        WorldStore.Member m = u.findMember(guid);
        if (m == null || m.playerId == rec.playerId) {
            session.send(MsgIds.S2C_APPOINT_ELDER_RET, pkt, dump.appointElderRet(false, appoint, guid));
            return;
        }
        if (appoint) {
            int elders = 0;
            for (WorldStore.Member x : u.members) {
                if ("elder".equals(x.job)) {
                    elders++;
                }
            }
            if (elders >= unionCfg.elderMax()) {
                session.send(MsgIds.S2C_APPOINT_ELDER_RET, pkt, dump.appointElderRet(false, true, guid));
                return;
            }
            m.job = "elder";
        } else {
            m.job = "member";
        }
        PlayerRecord other = players.get(m.account);
        if (other != null) {
            other.guild.job = m.job;
            players.save(other);
        }
        world.saveUnions();
        session.send(MsgIds.S2C_APPOINT_ELDER_RET, pkt, dump.appointElderRet(true, appoint, guid));
        push(m.account, MsgIds.S2C_UNION_JOB_UPDATE, dump.unionJobUpdate(m.job));
        pushUnion(u, MsgIds.S2C_UNION_DETAIL_RET, dump.unionDetail(u, syncMembers(u)), 0);
        log.info("{} appoint={} elder guid={} union={}", rec.account, appoint, guid, u.name);
    }

    /** 1508 ChangeUnionOwner：转让会长。 */
    public void onChangeOwner(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null || rec.guildJobCode() != 3) {
            session.send(MsgIds.S2C_NOTIFY_UNION_OWNER, pkt, dump.notifyUnionOwner(0));
            return;
        }
        u.ensure();
        int guid = Pb.read(pkt.body).getInt(1, 0);
        WorldStore.Member m = u.findMember(guid);
        if (m == null || m.playerId == rec.playerId) {
            session.send(MsgIds.S2C_NOTIFY_UNION_OWNER, pkt, dump.notifyUnionOwner(0));
            return;
        }
        WorldStore.Member self = u.findMember(rec.playerId);
        if (self != null) {
            self.job = "member";
        }
        m.job = "owner";
        rec.guild.job = "member";
        players.save(rec);
        PlayerRecord other = players.get(m.account);
        if (other != null) {
            other.guild.job = "owner";
            players.save(other);
            push(other.account, MsgIds.S2C_UNION_JOB_UPDATE, dump.unionJobUpdate("owner"));
        }
        world.saveUnions();
        // 1911 是 Notify：会长变更要让每个成员把成员列表里的职位改掉
        // （客户端 PlayGameState.OnNotifyUnionOwner → EN_UNION_NEWOWNER →
        //  UnionManagerSystem.cs:499-512 把该 Guid 的 Job 置 3 并刷新列表）。
        pushUnion(u, MsgIds.S2C_NOTIFY_UNION_OWNER, dump.notifyUnionOwner(m.playerId), 0);
        session.send(MsgIds.S2C_UNION_JOB_UPDATE, pkt, dump.unionJobUpdate("member"));
        // 1968（CCMsgUnionNotifyOwnerChangeOnce）**不能**在这里广播：客户端
        // PlayGameState.cs:9051-9069 OnNET_CCMsgUnionPresidentChange_Ret 无条件弹模态框
        // StrTable 101282「由于你一周未登录游戏,会长职务由{0}接任」——文案只对「会长长期未登录被
        // 自动让位」成立，主动转让时对全公会刷屏且内容不实。主动转让靠 1911（改成员列表职位）+
        // 1906（全量刷新）就够了；真正的自动让位在 handoverInactiveOwners 里记 pendingOwnerChangeFor，
        // 等前会长登录时由 touchLogin 单独补推（见 :3351）。
        pushUnion(u, MsgIds.S2C_UNION_DETAIL_RET, dump.unionDetail(u, syncMembers(u)), 0);
        log.info("{} passed union {} to {}", rec.account, u.name, m.account);
    }

    /** 1509 ModifyUnionNotice。 */
    public void onModifyNotice(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        WorldStore.UnionRecord u = rec == null ? null : world.findUnion(rec.guild.id);
        if (u == null || !isOwnerOrElder(rec)) {
            session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, unionDetailOrEmpty(u));
            return;
        }
        String notice = Pb.read(pkt.body).getString(1);
        u.notice = notice == null ? "" : notice;
        world.saveUnions();
        pushUnion(u, MsgIds.S2C_UNION_DETAIL_RET, dump.unionDetail(u, syncMembers(u)), 0);
        session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, dump.unionDetail(u, syncMembers(u)));
    }

    /** 1510 ModifyUnionIcon。 */
    public void onModifyIcon(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        WorldStore.UnionRecord u = rec == null ? null : world.findUnion(rec.guild.id);
        // 职位门 = 会长/长老：APK 里「公会管理按钮」YiShiTingMianBan/XianShi_5_20/GongHuiGuanLiButton
        // 的可见性就是 mEUnionJob != 1（UnionManagerSystem.cs:182），点进去的
        // GongHuiSheZhiMianBan/XianShi_5_20/QueDingXiuGai（:1539 → ᜉ :867）无条件连发
        // 1510/1511/1512 ⇒ 长老点得动；改前只放会长会把长老的合法修改静默回滚（1906）。
        if (u == null || !isOwnerOrElder(rec)) {
            session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, unionDetailOrEmpty(u));
            return;
        }
        String icon = Pb.read(pkt.body).getString(1);
        u.icon = PlayerDumpService.unionIcon(icon);
        world.saveUnions();
        pushUnion(u, MsgIds.S2C_UNION_DETAIL_RET, dump.unionDetail(u, syncMembers(u)), 0);
        session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, dump.unionDetail(u, syncMembers(u)));
    }

    /** 1511 ModifyUnionJoinType（EUnionJoinType：0 需审批 / 1 直接加入 / 2 不允许）。 */
    public void onModifyJoinType(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        WorldStore.UnionRecord u = rec == null ? null : world.findUnion(rec.guild.id);
        if (u == null || !isOwnerOrElder(rec)) {
            session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, unionDetailOrEmpty(u));
            return;
        }
        // 缺省必须是 0（EUJT_Verify 需审批）：APK 里 CCMsgModifyUnionJoinType.JoinType 的
        // [DefaultValue(EUnionJoinType.EUJT_Verify)] 就是 0 且 IsRequired=false ⇒ 客户端选
        // 「需要验证才可加入」(StrTable 100140) 时 protobuf-net **不写该字段**（UnionManagerSystem.cs:903-906），
        // 缺省取 1 会让「需审批」变成任何人可直入。
        int code = Pb.read(pkt.body).getInt(1, 0);
        u.joinType = code == 0 ? "verify" : (code == 2 ? "deny" : "direct");
        world.saveUnions();
        pushUnion(u, MsgIds.S2C_UNION_DETAIL_RET, dump.unionDetail(u, syncMembers(u)), 0);
        session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, dump.unionDetail(u, syncMembers(u)));
    }

    /** 1512 ModifyUnionJoinLevel。 */
    public void onModifyJoinLevel(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        WorldStore.UnionRecord u = rec == null ? null : world.findUnion(rec.guild.id);
        if (u == null || !isOwnerOrElder(rec)) {
            session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, unionDetailOrEmpty(u));
            return;
        }
        u.joinLevel = Math.max(1, Pb.read(pkt.body).getInt(1, 1));
        world.saveUnions();
        pushUnion(u, MsgIds.S2C_UNION_DETAIL_RET, dump.unionDetail(u, syncMembers(u)), 0);
        session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, dump.unionDetail(u, syncMembers(u)));
    }

    /** 1515 KickMemberOutUnion：踢人并写退会 CD（Union.txt 踢出 CD 7200s）。 */
    public void onKickMember(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null || !isOwnerOrElder(rec)) {
            session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, unionDetailOrEmpty(u));
            return;
        }
        u.ensure();
        int guid = Pb.read(pkt.body).getInt(1, 0);
        WorldStore.Member m = u.findMember(guid);
        if (m == null || m.playerId == rec.playerId || "owner".equals(m.job)) {
            session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, dump.unionDetail(u, syncMembers(u)));
            return;
        }
        u.members.remove(m);
        PlayerRecord other = players.get(m.account);
        if (other != null) {
            other.guild.id = "";
            other.guild.name = "";
            other.guild.job = "none";
            other.guild.quitUnionAt = System.currentTimeMillis() + unionCfg.kickCdSec() * 1000L;
            players.save(other);
        }
        dropMemberArtifacts(u, m);
        world.saveUnions();
        push(m.account, MsgIds.S2C_QUIT_UNION_RET, new byte[0]);
        pushUnion(u, MsgIds.S2C_UNION_DETAIL_RET, dump.unionDetail(u, syncMembers(u)), 0);
        session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, dump.unionDetail(u, syncMembers(u)));
        log.info("{} kicked {} from union {}", rec.account, m.account, u.name);
    }

    /** 1525 RequestUnionMemberInfo：成员列表（1922）。 */
    public void onMemberInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        WorldStore.UnionRecord u = rec == null ? null : world.findUnion(rec.guild.id);
        if (u == null) {
            session.send(MsgIds.S2C_UNION_MEMBER_INFO_RET, pkt, dump.unionMemberInfo(null, null));
            return;
        }
        u.ensure();
        session.send(MsgIds.S2C_UNION_MEMBER_INFO_RET, pkt, dump.unionMemberInfo(u.members, syncMembers(u)));
    }

    public void onQuit(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        WorldStore.Member successor = null;
        WorldStore.Member self = null;
        if (u != null) {
            self = u.findMember(rec.playerId);
            if (self != null && "owner".equals(self.job) && u.members.size() > 1) {
                for (WorldStore.Member m : u.members) {
                    if (m.playerId != rec.playerId) {
                        m.job = "owner";
                        PlayerRecord other = players.get(m.account);
                        if (other != null) {
                            other.guild.job = "owner";
                            players.save(other);
                            push(other.account, MsgIds.S2C_UNION_JOB_UPDATE, dump.unionJobUpdate("owner"));
                        }
                        successor = m;
                        break;
                    }
                }
            }
            u.members.removeIf(m -> m.playerId == rec.playerId);
            dropMemberArtifacts(u, self);
            if (u.members.isEmpty()) {
                world.unions().remove(u);
                // 必须落盘：否则内存里公会已消失、unions.json 仍是旧内容，重启后空公会「复活」，
                // 而原成员的 guild.id 已被清空（幽灵公会）。与 onDestroy 的移除路径一致。
                world.saveUnions();
            } else {
                world.saveUnions();
            }
        }
        rec.guild.id = "";
        rec.guild.name = "";
        rec.guild.job = "none";
        rec.guild.quitUnionAt = System.currentTimeMillis() + unionCfg.quitCdSec() * 1000L;
        players.save(rec);
        session.send(MsgIds.S2C_QUIT_UNION_RET, pkt, new byte[0]);
        if (successor != null) {
            // 会长主动退会（非 1508 转让）的继任者必须让全公会知道，否则客户端靠下一次 1906 的
            // Job 被动纠正、继任者毫无提示。与 onChangeOwner 同一套推送：1911 改职位 + 1906 刷新。
            // 1968 不在这里发（客户端会弹「你一周未登录被让位」的模态框，见 onChangeOwner 处的注释）。
            pushUnion(u, MsgIds.S2C_NOTIFY_UNION_OWNER, dump.notifyUnionOwner(successor.playerId), 0);
            log.info("{} quit union {} and passed to {}", rec.account, u.name, successor.account);
        }
        if (u != null && !u.members.isEmpty()) {
            pushUnion(u, MsgIds.S2C_UNION_DETAIL_RET, dump.unionDetail(u, syncMembers(u)), 0);
        }
    }

    public void onDestroy(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null || rec.guildJobCode() != 3) {
            // 非会长回空 1902 是**假成功**：客户端 PlayGameState.cs:4164-4170 的 OnDestroyUnion_Ret
            // 根本不解析 body，直接 mHaveUnion=false + 弹 100738「公会已解散」⇒ 会把本地公会清掉。
            // 改推真实 1906（客户端按 msgId 走详情刷新，无副作用）。
            session.send(MsgIds.S2C_UNION_DETAIL_RET, pkt, unionDetailOrEmpty(u));
            return;
        }
        for (WorldStore.Member m : u.members) {
            PlayerRecord p = players.get(m.account);
            if (p != null) {
                p.guild.id = "";
                p.guild.name = "";
                p.guild.job = "none";
                players.save(p);
                push(m.account, MsgIds.S2C_DESTROY_UNION_RET, new byte[0]);
            }
            // 解散同样要清残留（佣兵格位 / 教练绑定 / 在训坑位 / 镖车状态）：否则退役公会的成员档里
            // 仍留着指向彼此的引用，trainClock 还会继续给已经不在任何公会的玩家结算训练经验。
            dropMemberArtifacts(u, m);
        }
        // 解散时把作战室里**未结算的竞拍款项**退还给出价者（Sys_MailConfig 第 6 行「公会退款」
        // 正文「你在公会拍卖中竞拍的款项退还如下。」没有 {0} 占位符 —— 与第 5 行「竞标失败退款」
        // 的带物品名版本区分：第 5 行是「被别人顶价」，第 6 行是「整场拍卖作废/公会解散」）。
        for (WorldStore.AuctionLot lot : u.auctionLots) {
            if (lot.topBidder == null || lot.topBidder.isEmpty() || lot.topBidderId <= 0) {
                continue;
            }
            PlayerRecord bidder = players.findByPlayerId(lot.topBidderId);
            if (bidder != null) {
                mail.sendUnionRefundMail(bidder.account, lot.price);
            }
        }
        world.unions().remove(u);
        world.saveUnions();
        rec.guild.id = "";
        rec.guild.name = "";
        rec.guild.job = "none";
        players.save(rec);
        session.send(MsgIds.S2C_DESTROY_UNION_RET, pkt, new byte[0]);
    }

    public void onPlayerRes(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        session.send(MsgIds.S2C_UNION_PLAYER_RES, pkt, dump.unionPlayerRes(rec));
    }

    /** 1517 RequestUnionDonate：数值全部走 Union.txt（30000 金币 / 30 兄弟币 / 50 晶石 / 贡献系数）。 */
    public void onDonate(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int gold = unionCfg.donateGold();
        int brother = unionCfg.donateBrotherCoin();
        int crystal = unionCfg.donateCrystal();
        // 每日捐献次数在 progress.ensureDaily 里重置；改前这里不调用它 ⇒ 跨日后第一次进公会
        // 直接发 1517 会拿昨天的剩余次数（次数已用尽时表现为「捐献按钮点了没反应」）。
        progress.ensureDaily(rec);
        boolean ok = rec.guild.donateLeft > 0 && rec.gold >= gold;
        if (ok) {
            rec.guild.donateLeft--;
            // Union.txt：玩家提供的公会晶石转换为贡献系数（crystalToContri）
            rec.guild.contribution += Math.max(1, crystal / Math.max(1, unionCfg.crystalToContri()));
            rec.guild.brotherCoin += brother;
            rec.gold -= gold;
            // 1906 成员列表 f6 读的是 WorldStore.Member.contribution，贡献变了必须同步（否则列表恒 0）。
            progress.syncMemberContribution(rec);
            WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
            if (u != null) {
                u.ensure();
                u.crystal += crystal;
                world.saveUnions();
            }
            players.save(rec);
        }
        // 1966（CCMsgRequestUnionDonate_Ret）是**空体的纯成功回执**：客户端
        // PlayGameState.cs:6773-6779 OnUnionDonateRet 不解析 body、无条件弹 StrTable 100739
        // 「捐赠成功，好人一生平安」⇒ 失败时发它就是假成功（金币/次数不够也报成功）。
        // 失败只回 1913 让面板自纠（客户端本地闸门 UnionManagerSystem.cs:693/700 覆盖了
        // 次数与金币两种情况，正常 UI 走不到失败分支）。
        if (ok) {
            session.send(MsgIds.S2C_UNION_DONATE_RET, pkt, new byte[0]);
        }
        session.send(MsgIds.S2C_UNION_PLAYER_RES, pkt, dump.unionPlayerRes(rec));
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(3, rec.gold));
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (ok && u != null) {
            pushUnion(u, MsgIds.S2C_UNION_ATTRI_UPDATE, dump.unionAttriUpdate(5, u.crystal, ""), 0);
            pushUnion(u, MsgIds.S2C_UNION_ATTRI_UPDATE, dump.unionAttriUpdate(6, u.growth, ""), 0);
        }
    }

    // ------------------------------------------------------------------ 建筑 / 佣兵

    public void onBuildings(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        WorldStore.UnionRecord u = rec == null ? null : world.findUnion(rec.guild.id);
        if (u != null) {
            settleUpgrades(u);
        }
        session.send(MsgIds.S2C_UNION_BUILDINGS, pkt,
                dump.unionBuildings(u == null ? null : u.buildings, u == null ? null : u.buildingUpgradeEnd));
    }

    /** 1523 LevelUpOneUnionBuilding：按 UnionBuildingLevelUp.txt 校验晶石/成长/议事厅等级，并记入升级中。 */
    public void onBuildingLevelUp(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int type = Pb.read(pkt.body).getInt(1, 1);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null) {
            session.send(MsgIds.S2C_UNION_BUILDING_LEVEL_UP, pkt, dump.unionLevelUp(type, 6, 0));
            return;
        }
        u.ensure();
        settleUpgrades(u);
        int lv = buildingLevel(u, type);
        int max = unionCfg.maxBuildingLevel(type);
        if (lv >= max) {
            session.send(MsgIds.S2C_UNION_BUILDING_LEVEL_UP, pkt, dump.unionLevelUp(type, 7, lv));
            return;
        }
        if (u.isUpgrading(type, System.currentTimeMillis())) {
            session.send(MsgIds.S2C_UNION_BUILDING_LEVEL_UP, pkt, dump.unionLevelUp(type, 2, lv));
            return;
        }
        UnionCfg.BuildingRow row = unionCfg.building(type, lv);
        if (row == null) {
            session.send(MsgIds.S2C_UNION_BUILDING_LEVEL_UP, pkt, dump.unionLevelUp(type, 1, lv));
            return;
        }
        if (u.crystal < row.crystal) {
            session.send(MsgIds.S2C_UNION_BUILDING_LEVEL_UP, pkt, dump.unionLevelUp(type, 3, lv));
            return;
        }
        if (u.growth < row.growNeed) {
            session.send(MsgIds.S2C_UNION_BUILDING_LEVEL_UP, pkt, dump.unionLevelUp(type, 4, lv));
            return;
        }
        if (buildingLevel(u, 1) < row.hallNeed) {
            session.send(MsgIds.S2C_UNION_BUILDING_LEVEL_UP, pkt, dump.unionLevelUp(type, 5, lv));
            return;
        }
        if (rec.guildJobCode() != 3 && rec.guildJobCode() != 2) {
            session.send(MsgIds.S2C_UNION_BUILDING_LEVEL_UP, pkt, dump.unionLevelUp(type, 6, lv));
            return;
        }
        u.crystal -= row.crystal;
        u.buildingUpgradeEnd.put(Integer.valueOf(type),
                Long.valueOf(System.currentTimeMillis() + Math.max(1, row.timeSec) * 1000L));
        world.saveUnions();
        session.send(MsgIds.S2C_UNION_BUILDING_LEVEL_UP, pkt, dump.unionLevelUp(type, 1, lv));
        pushUnion(u, MsgIds.S2C_UNION_BUILDINGS, dump.unionBuildings(u.buildings, u.buildingUpgradeEnd), 0);
        pushUnion(u, MsgIds.S2C_UNION_ATTRI_UPDATE, dump.unionAttriUpdate(5, u.crystal, ""), 0);
        log.info("{} union building {} -> {} start", rec.account, type, lv + 1);
    }

    /** 1524 CancelLevelUpOneUnionBuilding：退回晶石（按 Union.txt 取消返还比例）。 */
    public void onCancelLevelUp(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int type = Pb.read(pkt.body).getInt(1, 1);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null || (!isOwner(rec))) {
            session.send(MsgIds.S2C_CANCEL_BUILDING_LEVEL_UP_RET, pkt, dump.unionCancelLevelUp(type, false));
            return;
        }
        u.ensure();
        long end = u.upgradeEndOf(type);
        if (end <= 0L) {
            session.send(MsgIds.S2C_CANCEL_BUILDING_LEVEL_UP_RET, pkt, dump.unionCancelLevelUp(type, false));
            return;
        }
        int lv = buildingLevel(u, type);
        UnionCfg.BuildingRow row = unionCfg.building(type, lv);
        int refund = row == null ? 0 : (int) Math.floor(row.crystal * unionCfg.cancelRefundRatio());
        u.crystal += refund;
        u.buildingUpgradeEnd.remove(Integer.valueOf(type));
        world.saveUnions();
        session.send(MsgIds.S2C_CANCEL_BUILDING_LEVEL_UP_RET, pkt, dump.unionCancelLevelUp(type, true));
        pushUnion(u, MsgIds.S2C_UNION_BUILDINGS, dump.unionBuildings(u.buildings, u.buildingUpgradeEnd), 0);
        // 退回晶石后必须推 1914 attr=5，否则其他成员的公会晶石显示停在旧值（onBuildingLevelUp 有推）。
        pushUnion(u, MsgIds.S2C_UNION_ATTRI_UPDATE, dump.unionAttriUpdate(5, u.crystal, ""), 0);
        log.info("{} cancelled union building {} upgrade refund={}", rec.account, type, refund);
    }

    public void onDrawProfit(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        int type = Pb.read(pkt.body).getInt(1, 1);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        // 无公会不能领：u==null 时 buildingLevel 兜底返回 1，pendingBuildingGold 仍按
        // choulao(type,1) 计收益（厨房/作战室 L1=600/小时）⇒ 无公会玩家发 1519 就能凭空刷金币。
        //
        // **不再查 canUseBuilding**（第 2 轮闸门清扫，用户 m20482）：客户端「领取」按钮零前置
        // （MyBuildingWithYongBingItem_InYongBingMianBan.cs:83-89 —— profit≠0 才显示按钮，点后
        // 立刻 SetProfit(0,0) 本地清零），而 1915 employersBody 又把**未解锁建筑**的
        // pendingBuildingGold 一起下发 ⇒ 议事厅/攻略组等级不足的成员点「领取」会被静默拒成
        // +0、界面已本地清零，收益再也拿不到。收益按建筑等级累计、与该玩家能否使用该建筑无关
        // （pendingBuildingGold 不看入驻），所以使用闸门只留在 1521 入驻 / 1538 训练等处。
        if (u == null) {
            session.send(MsgIds.S2C_DRAW_BUILDING_PROFIT, pkt, dump.unionProfit(type, 0));
            return;
        }
        int gold = collectBuildingGold(rec, u, type);
        if (gold > 0) {
            rec.gold += gold;
        }
        players.save(rec);
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(3, rec.gold));
        session.send(MsgIds.S2C_DRAW_BUILDING_PROFIT, pkt, dump.unionProfit(type, gold));
        log.info("{} union profit type={} gold={}", rec.account, type, gold);
    }

    /** 1518 RequestOnePlayerAllUnionBuildingEmployersInfo（1915）。 */
    public void onEmployers(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        session.send(MsgIds.S2C_UNION_EMPLOYERS, pkt, employersBody(rec));
    }

    /**
     * 1915 的 body（{@code CCMsgOnePlayeAllBuildingEmployerInfo}）：逐建筑列出**请求者自己**
     * 入驻的武将格位、其剩余 CD、建筑自身累计产出（f4 {@code baseProfit}）与佣兵加成
     * （f5 {@code employProfit}，假服恒 0 —— 客户端只做加法、表里无产出列）。
     *
     * <p>1518 请求时回，也在 **1504 公会详情之后主动兜底推一条**（用户 m19522 #1）：客户端
     * 「佣兵」页签若是默认选中态，{@code UIToggle.onChange} 可能不触发 ⇒ 不推的话面板会空到
     * 玩家手点一下页签。客户端对主动推 1915 完全兼容（{@code ᝁ.cs:184} 注册了 1915 的 handler）。
     * 只在请求的是**自己公会**时推（佣兵面板属于自己公会）。
     */
    private byte[] employersBody(PlayerRecord rec) {
        rec.ensureCollections();
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        int cap = mercenaryCap(rec);
        List<byte[]> items = new ArrayList<>();
        for (int type = 1; type <= 7; type++) {
            items.add(dump.buildingEmployer(type, pendingBuildingGold(rec, u, type), 0,
                    slotView(u, type, rec.playerId, cap)));
        }
        return dump.buildingEmployers(items);
    }

    /** 1520 RequestOneUnionBuildingAllEmployers：单个建筑的**候选**佣兵（1917）。 */
    public void onBuildingAllEmployers(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        int type = Pb.read(pkt.body).getInt(1, 1);
        WorldStore.UnionRecord u = rec == null ? null : world.findUnion(rec.guild.id);
        session.send(MsgIds.S2C_BUILDING_ALL_EMPLOYERS, pkt,
                dump.buildingAllEmployers(type, candidates(u, type)));
    }

    /**
     * 某个建筑的候选佣兵列表：公会全体成员的武将（1917 {@code CCMsgWJInfoForOneUnionBuildingAllEmployers}）。
     *
     * <p>客户端把 1917 当**候选列表**用：厨房/训练场/医院/作战室的「查看佣兵」面板渲染它
     * （{@code CookHouseAllEmployer.cs:44-46/224-230}、{@code EmployerSystem.cs:126/164/196/228}），
     * 按等级/战力排序、{@code leftCDTime > 0} 置灰，点条目后由 1527/1535/1537/1538 真正雇佣
     * （客户端 {@code EmployConfirmUI.cs:101-104} 发的就是 1917 里选中的那条）。
     * 原实现回的是「已雇佣的格位」，等于候选列表永远只有已上岗的人。</p>
     */
    private List<WorldStore.Employer> candidates(WorldStore.UnionRecord u, int buildingType) {
        List<WorldStore.Employer> out = new ArrayList<>();
        if (u == null) {
            return out;
        }
        int profession = professionOfBuilding(buildingType);
        for (PlayerRecord p : players.all()) {
            if (p == null || p.guild == null || !u.id.equals(p.guild.id) || p.heroes == null) {
                continue;
            }
            for (PlayerRecord.Hero h : p.heroes) {
                if (h == null || h.heroIndex <= 0) {
                    continue;
                }
                WorldStore.Employer e = new WorldStore.Employer();
                e.playerId = p.playerId;
                e.account = p.account;
                e.name = p.roleName;
                e.wjIndex = h.heroIndex;
                e.wjLevel = h.level;
                e.wjStage = h.stage;
                e.wjStars = h.stars;
                e.fightPower = h.fightPower;
                // 正在别处上岗（或被替换后冷却）的候选带着冷却一起下发，客户端会置灰。
                WorldStore.Employer employed = employedAnywhere(u, p.playerId, h.heroIndex);
                e.cdEnd = employed == null ? 0L : employed.cdEnd;
                // 1917 的 price 是客户端确认框比价用的「雇佣酬劳」：作战室走
                // UnionWJSubsidiary 职业 0 的酬劳战力系数公式（与 1527 实扣同式），
                // 教练/医师/厨师直接取职业行的「雇佣酬劳」列（UnionWJSubsidiary.txt 第 4 列）。
                // 改前这里不写 price ⇒ 客户端显示 0、EmployConfirmUI.cs:82 的余额门控形同虚设。
                int level = subsidiaryLevel(profession, h.fightPower);
                UnionCfg.SubsidiaryRow row = unionCfg.subsidiary(profession, level);
                e.price = profession == 0
                        ? unionCfg.zzsEmployPrice(h.fightPower, level)
                        : (row == null ? 0 : row.price);
                out.add(e);
            }
        }
        return out;
    }

    /** 该武将在任意建筑上岗的登记（候选列表用它带出冷却）。 */
    private WorldStore.Employer employedAnywhere(WorldStore.UnionRecord u, int playerId, int wjIndex) {
        if (u == null || playerId <= 0 || wjIndex <= 0) {
            return null;
        }
        for (int type = 1; type <= 7; type++) {
            for (WorldStore.Employer e : u.employersOf(type)) {
                if (e.playerId == playerId && e.wjIndex == wjIndex) {
                    return e;
                }
            }
        }
        return null;
    }

    /** 1521 ReplaceOneEmployersByPlayer：替换某个格位的佣兵（1918）。 */
    public void onReplaceEmployer(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int type = f.getInt(1, 6);
        int index = f.getInt(2, 0);
        int newWj = f.getInt(3, 0);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        boolean ok = false;
        if (u != null && canUseBuilding(rec, type)) {
            // 格位是 (雇佣者=本人, slotIndex) 的：原实现用公会整表下标 + index < list.size() 守卫，
            // 空表时点第 0 格就被拒（首次雇佣永远失败），卸下时 remove 还会移动后续格位下标。
            int cap = mercenaryCap(rec);
            if (index >= 0 && index < cap && newWj >= 0) {
                WorldStore.Employer slot = slotOf(u, type, rec.playerId, index);
                if (newWj == 0) {
                    // 卸下：**清空该格位**而不是删记录（保持 (hiredBy, slotIndex) 身份稳定）。
                    slot.playerId = 0;
                    slot.account = "";
                    slot.name = "";
                    slot.wjIndex = 0;
                    slot.fightPower = 0;
                    slot.cdEnd = 0L;
                } else {
                    PlayerRecord.Hero h = rec.findHeroByIndex(newWj);
                    slot.playerId = rec.playerId;
                    slot.account = rec.account;
                    slot.name = rec.roleName;
                    slot.wjIndex = newWj;
                    if (h != null) {
                        slot.wjLevel = h.level;
                        slot.wjStage = h.stage;
                        slot.wjStars = h.stars;
                        slot.fightPower = h.fightPower;
                    }
                    // 上岗冷却（UnionWJSubsidiary 该职业的冷却时间）：客户端在冷却期内
                    // 点这个格位会弹 100689（MyBuildingWithYongBingItem_InYongBingMianBan.cs:106-112）。
                    UnionCfg.SubsidiaryRow row = unionCfg.subsidiary(professionOfBuilding(type), 1);
                    slot.cdEnd = row == null || row.cdSec <= 0
                            ? 0L : System.currentTimeMillis() + row.cdSec * 1000L;
                }
                world.saveUnions();
                ok = true;
            }
        }
        session.send(MsgIds.S2C_REPLACE_EMPLOYER_RET, pkt, dump.replaceEmployerRet(ok ? 1 : 0));
        if (ok) {
            // 候选列表（1917）是全公会共享的：只回操作者会让其他成员的候选冷却陈旧。
            byte[] all = dump.buildingAllEmployers(type, candidates(u, type));
            session.send(MsgIds.S2C_BUILDING_ALL_EMPLOYERS, pkt, all);
            pushUnion(u, MsgIds.S2C_BUILDING_ALL_EMPLOYERS, all, rec.playerId);
        }
    }

    /** 1527 RequestEmployAWuJiang_ZZS：作战室雇佣武将（1924）。 */
    public void onEmployWjZzs(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int playerGuid = f.getInt(1, 0);
        int wjIndex = f.getInt(2, 0);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null || playerGuid <= 0 || !canUseBuilding(rec, WARROOM_BUILDING)) {
            session.send(MsgIds.S2C_EMPLOY_WJ_ZZS_RET, pkt, dump.employWjZzsRet(false, playerGuid, 0, 0));
            return;
        }
        PlayerRecord owner = players.findByPlayerId(playerGuid);
        PlayerRecord.Hero hero = owner == null ? null : owner.findHeroByIndex(wjIndex);
        if (owner == null || hero == null) {
            session.send(MsgIds.S2C_EMPLOY_WJ_ZZS_RET, pkt, dump.employWjZzsRet(false, playerGuid, 0, 0));
            return;
        }
        u.ensure();
        int level = subsidiaryLevel(0, hero.fightPower);
        int price = unionCfg.zzsEmployPrice(hero.fightPower, level);
        // 支付货币是**雇佣者本人的金币**，不是公会晶石：客户端 EmployConfirmUI.cs:82 用
        // msMainPlayer.Attribute.mCurJinBi 与 employer.price 比价，:156-161 也把 price 当金币消耗展示；
        // 厨师/教练/医师的确认框同样（EmployConfirmCookHouse.cs:73,127、EmployConfirmTrain.cs:73,128、
        // UnionHospital.cs:175,246）。扣公会晶石会让「金币够而晶石不够」被拒、且凭空消耗公会资源。
        if (rec.gold < price) {
            session.send(MsgIds.S2C_EMPLOY_WJ_ZZS_RET, pkt, dump.employWjZzsRet(false, playerGuid, 0, price));
            return;
        }
        UnionCfg.SubsidiaryRow row = unionCfg.subsidiary(0, level);
        // VIP 格位上限（VipCfg「公会佣兵数量」cols[26]：VIP0–4=2、VIP5–13=3、VIP14–15=4；客户端
        // MyBuildingWithYongBingItem_InYongBingMianBan.cs:56-65 按同一列给第 cap 个之后的格位挂锁）。
        // 原实现直接 add() 追加 ⇒ 格位无限、且 1915 的 wjIndex[] 顺序与客户端格位下标脱钩。
        int cap = mercenaryCap(rec);
        int slot = firstFreeSlot(u, 6, rec.playerId, cap);
        if (slot < 0) {
            session.send(MsgIds.S2C_EMPLOY_WJ_ZZS_RET, pkt, dump.employWjZzsRet(false, playerGuid, 0, price));
            return;
        }
        rec.gold -= price;
        // 受雇者获得「雇佣酬劳 × 佣金获取系数」（UnionWJSubsidiary.txt 说明第 4 条，系数 0.75）。
        int gain = (int) Math.floor(price * (row == null ? 0d : row.gainCoef));
        if (gain > 0) {
            owner.gold += gain;
            players.save(owner);
            push(owner.account, MsgIds.S2C_ATTRI_UPDATE, dump.attri(3, owner.gold));
        }
        WorldStore.Employer e = slotOf(u, 6, rec.playerId, slot);
        e.hiredBy = rec.playerId;
        e.slotIndex = slot;
        e.playerId = owner.playerId;
        e.account = owner.account;
        e.name = owner.roleName;
        e.wjIndex = wjIndex;
        e.wjLevel = hero.level;
        e.wjStage = hero.stage;
        e.wjStars = hero.stars;
        e.fightPower = hero.fightPower;
        e.price = price;
        e.cdEnd = System.currentTimeMillis() + (row == null ? 86400 : row.cdSec) * 1000L;
        world.saveUnions();
        players.save(rec);
        session.send(MsgIds.S2C_EMPLOY_WJ_ZZS_RET, pkt, dump.employWjZzsRet(true, playerGuid, wjIndex,
                hero.level, hero.stage, hero.stars, hero.fightPower,
                (int) Math.max(0L, (e.cdEnd - System.currentTimeMillis()) / 1000L), price));
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(3, rec.gold));
        pushUnion(u, MsgIds.S2C_BUILDING_ALL_EMPLOYERS, dump.buildingAllEmployers(6, candidates(u, 6)), 0);
        log.info("{} employed wj {} from {} price={} gain={}", rec.account, wjIndex, owner.account, price, gain);
    }

    // ------------------------------------------------------------------ 作战室 Boss

    public void onBossInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        progress.ensureDaily(rec);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u != null) {
            settleUpgrades(u);
        }
        session.send(MsgIds.S2C_UNION_BOSS_INFO, pkt, dump.unionBossInfo(bossViews(u),
                rec.playerId, myEmployWj(u, rec.playerId), rec.guild.bossPlayTimes));
    }

    /** 1528 RequestFightBoss：扣挑战次数并回 1925 + 1967 + 1923。 */
    public void onBossFight(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        progress.ensureDaily(rec);
        Pb.Fields f = Pb.read(pkt.body);
        int chapter = f.getInt(1, 1);
        if (chapter <= 0) {
            chapter = 1;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        // 攻略组（作战室）等级门：客户端只在场景入口校验一次（UnionBaseSceneManager.cs:505-511），
        // WarRoomMainDialog.Open() 与 EmBattleSystem.cs:2085-2090 都不再校验 ⇒ 服务端必须挡。
        if (u == null || !canUseBuilding(rec, WARROOM_BUILDING)) {
            session.send(MsgIds.S2C_UNION_BOSS_FIGHT_RET, pkt, dump.unionBossFightRet(null));
            return;
        }
        settleUpgrades(u);
        int used = rec.guild.bossPlayTimes.getOrDefault(Integer.valueOf(chapter), Integer.valueOf(0)).intValue();
        if (used >= unionCfg.bossMaxTimes()) {
            session.send(MsgIds.S2C_UNION_BOSS_FIGHT_RET, pkt, dump.unionBossFightRet(null));
            return;
        }
        rec.guild.bossPlayTimes.put(Integer.valueOf(chapter), Integer.valueOf(used + 1));
        // 开打凭据：1529 必须凭一次 1528 才能结算（见 onBossResult 的校验）。bossPlayTimes 是
        // 客户端「已打次数」显示用的（UnionBossWarInfo.cs:153 / WarRoomMainDialog.cs:228 用
        // mBossMaxCiShu - playedTimes 判按钮），**不能**在 1529 里减回去，故另开一个计数。
        rec.guild.bossPending.merge(Integer.valueOf(chapter), Integer.valueOf(1), Integer::sum);
        players.save(rec);
        PlayerDumpService.BossView view = bossView(u, chapter);
        session.send(MsgIds.S2C_UNION_BOSS_FIGHT_RET, pkt, dump.unionBossFightRet(view));
        session.send(MsgIds.S2C_UNION_BOSS_PLAY_TIME, pkt,
                dump.unionBossPlayTime(chapter, used + 1));
        session.send(MsgIds.S2C_UNION_BOSS_INFO, pkt, dump.unionBossInfo(bossViews(u),
                rec.playerId, myEmployWj(u, rec.playerId), rec.guild.bossPlayTimes));
        log.info("{} union boss fight chapter={} times={}", rec.account, chapter, used + 1);
    }

    /**
     * 1529 ResultFightBoss。请求体 CCMsgUnionBossInfo 的 curHP 就是本场伤害
     * （UnionBattleController.cs:1205 mInitHp-mCurHp；放弃时 0）。
     */
    public void onBossResult(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        Pb.Fields f = Pb.read(pkt.body);
        int chapter = Math.max(1, f.getInt(1, 1));
        int damage = Math.max(0, f.getInt(3, 0));
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null) {
            session.send(MsgIds.S2C_UNION_BOSS_RESULT_RET, pkt,
                    dump.unionBossResultRet(0, 0, 0, 0, 0, 0, 0, 0, null, null));
            return;
        }
        settleUpgrades(u);
        UnionCfg.BossRow row = unionCfg.boss(chapter);
        String ori = row == null ? u.bossOri : row.ori;
        int maxHp = bossMaxHp(ori);
        int hpBefore = u.bossHpOf(chapter, maxHp);
        if (hpBefore <= 0) {
            // 该章 boss 已结算（已死）：不再发放单场奖励并回零结算。
            // 否则同一章重复上报 1529（客户端击杀后不会发，但包可以伪造）会无限拿
            // 总金币/成长值/兄弟币/勇气币，并重复加晶石、重复发名次奖邮、重复换章。
            session.send(MsgIds.S2C_UNION_BOSS_RESULT_RET, pkt,
                    dump.unionBossResultRet(0, 0, 0, 0, 0, 0, 0, 0, null, bossView(u, chapter)));
            return;
        }
        int hp = hpBefore;
        // 客户端 f3 报的是本场实际打掉的伤害（UnionBattleController.cs:1211 = mInitHp - mCurHp），
        // 但不能超过该章剩余血量，否则金币/名次会按虚高伤害结算。
        int dealt = Math.min(Math.max(0, damage), Math.max(0, hp));
        // 凭一次 1528 才能结算：改前 1529 完全无门控（bossMaxTimes 只在 1528 里查），
        // 直发 1529 就能每包拿全额金币/兄弟币/勇气币/成长值（伤害只要 >0，Boss 没死就能一直发）。
        int pending = rec.guild.bossPending
                .getOrDefault(Integer.valueOf(chapter), Integer.valueOf(0)).intValue();
        if (pending <= 0) {
            session.send(MsgIds.S2C_UNION_BOSS_RESULT_RET, pkt,
                    dump.unionBossResultRet(0, 0, 0, 0, 0, 0, 0, 0, null, bossView(u, chapter)));
            log.info("{} union boss result rejected: no 1528 for chapter={}", rec.account, chapter);
            return;
        }
        rec.guild.bossPending.put(Integer.valueOf(chapter), Integer.valueOf(pending - 1));
        if (dealt <= 0) {
            // 放弃战斗：客户端发 f3=0（MainSuspendSystem.cs:262-267）。次数已消耗、奖励一律不发。
            world.saveUnions();
            players.save(rec);
            session.send(MsgIds.S2C_UNION_BOSS_RESULT_RET, pkt,
                    dump.unionBossResultRet(0, 0, 0, 0, 0, 0, 0, 0, null, bossView(u, chapter)));
            session.send(MsgIds.S2C_UNION_BOSS_INFO, pkt, dump.unionBossInfo(bossViews(u),
                    rec.playerId, myEmployWj(u, rec.playerId), rec.guild.bossPlayTimes));
            log.info("{} union boss no-damage chapter={} (attempt consumed)", rec.account, chapter);
            return;
        }
        u.addDamage(chapter, rec.playerId, rec.account, rec.roleName, rec.mainHeroIndex,
                rec.level, dealt);
        hp = Math.max(0, hp - dealt);
        damage = dealt;
        u.setBossHp(chapter, hp);
        int brother = row == null ? 15 : row.brotherCoin;
        int courage = row == null ? 25 : row.courageCoin;
        int grow = row == null ? 150 : row.growValue;
        // 用户拍板（m09418 #2）：不用伤害分摊公式，打一次就发 UnionBoss.txt 的「总金币」全额。
        int gold = row == null ? 0 : row.gold;
        rec.guild.brotherCoin += brother;
        rec.guild.courageCoin += courage;
        rec.gold += gold;
        u.growth = Math.min(unionCfg.growCap(), u.growth + grow);
        // 1926 f4 {@code unionContri} 是**个人贡献度**（客户端 UnionZhanDouJieSuan.cs:205-210 显示
        // 「贡献度 +N」，与 f8 的公会成长值 {@code unionGrowValue} 是两个独立槽位）。改前 f4 也填了
        // grow ⇒ 贡献度与成长值同数字、且个人贡献**从未入账**。按 Union.txt:17 的换算系数
        // （growToContri=30）折算，余数留在 contributionGrowRemainder，与
        // ProgressService.spendStamina 同一套账。
        int contri = progress.creditUnionGrowth(rec, grow);
        List<byte[]> goods = new ArrayList<>();
        PlayerDumpService.BossView view;
        if (hp <= 0) {
            // 击杀：结算晶石，名次奖（兄弟币/勇气币）走 mailType 3 系统邮，掉落进作战室拍卖货架。
            if (row != null) {
                u.crystal += row.settleCrystal;
                // 击杀加晶石后必须推 1914 attr5：会长在结算面板上的「重置 Boss」按钮按本地滞留
                // 晶石数判定（WarRoomFinish.cs:88 用 resetSpend 比 mUnionDetailInfo.JinShi），
                // 不推的话刚拿到的晶石在本面板里看不见、按钮灰着。
                pushUnion(u, MsgIds.S2C_UNION_ATTRI_UPDATE, dump.unionAttriUpdate(5, u.crystal, ""), 0);
                // 必须用**副本**遍历：u.damageList(chapter) 返回的是存档里同一个 ArrayList，且内部
                // 每次调用都当场 sort（WorldStore.java:493-507）。原实现在循环里再调 rankOf →
                // 再 damageList → 再 sort ⇒ modCount+1 ⇒ 参战者≥2 时必抛
                // ConcurrentModificationException；MessageDispatcher 无 try/catch，
                // GameChannelHandler.exceptionCaught 直接 ctx.close() ⇒ 断连且整场结算丢失。
                // 名次 = 伤害降序下标+1（与 rankOf 同语义，列表已按伤害降序）。
                List<WorldStore.DamageEntry> damageRank = new ArrayList<>(u.damageList(chapter));
                int totalDamage = 0;
                for (WorldStore.DamageEntry e : damageRank) {
                    totalDamage += e.damage;
                }
                for (int i = 0; i < damageRank.size(); i++) {
                    WorldStore.DamageEntry e = damageRank.get(i);
                    int rank = i + 1;
                    int coin = UnionCfg.rankAward(row.settleBrother, rank);
                    int yqb = UnionCfg.rankAward(row.settleCourage, rank);
                    // 「贡献了{1}%的伤害」= 本人伤害 / 全章总伤害；无伤害时按 0 发（避免除零）。
                    int pct = totalDamage <= 0 ? 0 : (int) Math.floor(e.damage * 100.0 / totalDamage);
                    PlayerRecord p = players.findByPlayerId(e.playerId);
                    if (p != null) {
                        // 名次奖不再内联发放（Sys_MailConfig 第3行「额外获得了以下奖励」= 附件），
                        // 与 JJC/争霸排名邮一致：只发邮件，避免内联+邮件双发。
                        // grantKey 传 null：onRestBoss(1533) 会把血量重置回满，同一章会再被杀一次，
                        // 那次必须再发一封；「同一章只结算一次」由上面的 hpBefore<=0 拦截保证。
                        mail.sendUnionBossRankMail(p.account, row.name, pct, rank, coin, yqb, null);
                    }
                }
                for (UnionCfg.Drop d : row.drops) {
                    goods.add(dump.goodsItem(d.ori, d.count));
                }
                // 必须先 ensureAuction：它负责跨日重置（先结算昨天货架再清空）。若不先跑，
                // 当天第一次开拍卖页（1530/1531）的 ensureAuction 会把刚 seed 的本场掉落当
                // 「昨天的过期货架」整批 clear 掉 ⇒ 击杀奖励凭空消失（D6BattleE2ETest 特征化
                // 用例 auctionShelfIsWipedByFirstOpenAfterKill 钉住过该缺陷）。
                ensureAuction(u);
                seedAuction(u, row);
                // 击杀后货架多了本场的掉落，立刻推 1927 让作战室拍卖页看到新货（改前只在
                // 客户端主动发 1530 时才回，击杀瞬间的货架是空的）。
                pushUnion(u, MsgIds.S2C_AUCTION_INFO, dump.auctionList(auctionItems(u)), 0);
                if (chapter + 1 <= unionCfg.bosses().size()) {
                    u.bossChapter = chapter + 1;
                    UnionCfg.BossRow next = unionCfg.boss(chapter + 1);
                    if (next != null) {
                        u.bossOri = next.ori;
                    }
                }
            }
            view = bossView(u, chapter);
            pushUnion(u, MsgIds.S2C_BOSS_FINISH_INFO, dump.bossFinishInfo(view, goods), 0);
            // 逐成员推 1923（f2 myEmploy / f3 curPlayTime 都是按玩家的）；请求者自己的那份在
            // 方法末尾单发，故排除。
            pushBossInfo(u, rec.playerId);
            log.info("{} killed union boss chapter={} union={}", rec.account, chapter, u.name);
        } else {
            view = bossView(u, chapter);
            // 非击杀掉血同样要把新血量推给全公会：作战室的 Boss 血条与伤害榜是共享状态，
            // 改前只有击杀才广播 ⇒ 其他成员的血条停在进场景时的旧值（自己那份在末尾单发）。
            pushBossInfo(u, rec.playerId);
        }
        world.saveUnions();
        players.save(rec);
        session.send(MsgIds.S2C_UNION_PLAYER_RES, pkt, dump.unionPlayerRes(rec));
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(3, rec.gold));
        session.send(MsgIds.S2C_UNION_BOSS_RESULT_RET, pkt, dump.unionBossResultRet(
                row == null ? 0 : row.expBattle, row == null ? 0 : row.expWj, gold,
                contri, brother, courage, row == null ? 0 : row.settleCrystal, grow, goods, view));
        session.send(MsgIds.S2C_UNION_BOSS_INFO, pkt, dump.unionBossInfo(bossViews(u),
                rec.playerId, myEmployWj(u, rec.playerId), rec.guild.bossPlayTimes));
        session.send(MsgIds.S2C_UNION_BOSS_PLAY_TIME, pkt, dump.unionBossPlayTime(chapter,
                rec.guild.bossPlayTimes.getOrDefault(Integer.valueOf(chapter), Integer.valueOf(0)).intValue()));
        log.info("{} union boss dmg={} hp={}", rec.account, damage, hp);
    }

    /** 1532 FightBossFinishInfo：结算面板（原错回 1927）。 */
    public void onBossFinish(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        // 请求体是 CCMsgFightBossFinishInfo：f1 = **嵌套消息** CCMsgUnionBossInfo（ProtoMember(1,
        // Name="bossInfo")），chapterID 在嵌套里（其 f1）。客户端 WarRoomMainDialog.cs:221-224 就是这么发的。
        // 原实现 getInt(1,1) 对嵌套的 length-delimited 字段拿不到整数 ⇒ 恒取默认值 1 ⇒ 非第 1 章
        // 的结算面板永远显示第 1 章。
        Pb.Fields f = Pb.read(pkt.body);
        byte[] nested = f.getBytes(1);
        int chapter = 1;
        if (nested != null && nested.length > 0) {
            chapter = Math.max(1, Pb.read(nested).getInt(1, 1));
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        UnionCfg.BossRow row = unionCfg.boss(chapter);
        List<byte[]> goods = new ArrayList<>();
        // 只有该章 Boss **确实已死**（血量被结算到 0）才把掉落列出来：改前无条件回
        // UnionBoss.txt 的掉落表，Boss 还活着时发 1532（伪造包或结算面板残留）也能看到
        // 「本场获得」的奖励清单，与 1926 的零奖励自相矛盾。
        boolean dead = false;
        if (u != null) {
            String ori = row == null ? u.bossOri : row.ori;
            dead = u.bossHpOf(chapter, bossMaxHp(ori)) <= 0;
        }
        if (row != null && dead) {
            for (UnionCfg.Drop d : row.drops) {
                goods.add(dump.goodsItem(d.ori, d.count));
            }
        }
        session.send(MsgIds.S2C_BOSS_FINISH_INFO, pkt, dump.bossFinishInfo(bossView(u, chapter), goods));
    }

    /** 1533 RestFightBoss：会长重置章节 Boss 血量。 */
    public void onRestBoss(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int chapter = Math.max(1, Pb.read(pkt.body).getInt(1, 1));
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u != null && "owner".equals(rec.guild.job)) {
            u.ensure();
            UnionCfg.BossRow row = unionCfg.boss(chapter);
            // UnionBoss.txt 第 25 列「刷新消耗（公会晶石）」= 3000/6000/9000/12000/12000。
            // 改前该值被读入却**无任何调用点** ⇒ 重置免费、可无限刷同一章拿总金币。
            // 客户端 WarRoomFinish.cs:88 只按 resetSpend 做本地拦截（可绕），服务端必须真扣。
            int cost = row == null ? 0 : row.refreshCrystal;
            // 两道补上的闸门（用户 m21142 复核）：
            // ①章节必须存在 —— row == null 时原实现 cost=0「免费重置」，还会凭空写一条 bossHp；
            // ②该章 Boss 必须已死 —— 客户端只有 curHP <= 0 才把 Reset 按钮显出来
            //   （WarRoomFinish.Refresh :261-267），1533 的语义就是「打完一章后重置」；
            //   但发送点 OnClickReset(:78) 只查晶石、按钮可见性取自上一包 1929 的 curHP，
            //   且已打开的面板不再 Refresh（ᝁ.cs:5402-5412）⇒ 陈旧面板 / 点错章节 / 重放
            //   都能对**活着的** Boss 发 1533（子代理判定 REACHABLE=yes）。
            boolean bossAlive = row != null && u.bossHpOf(chapter, bossMaxHp(row.ori)) > 0;
            if (row == null || bossAlive || (cost > 0 && u.crystal < cost)) {
                // 不重置、不扣费，只回当前状态。
                session.send(MsgIds.S2C_UNION_BOSS_INFO, pkt, dump.unionBossInfo(bossViews(u),
                        rec.playerId, myEmployWj(u, rec.playerId), rec.guild.bossPlayTimes));
                session.send(MsgIds.S2C_REST_BOSS, pkt, dump.unionBossFlat(bossView(u, chapter)));
                return;
            }
            u.crystal -= cost;
            u.setBossHp(chapter, bossMaxHp(row.ori));
            // 重置必须清该章伤害榜：否则重打后的名次奖会沿用上一轮的虚高伤害（贡献% 也被摊薄）。
            u.damageList(chapter).clear();
            world.saveUnions();
            // 必须是 1914 S2C_UNION_ATTRI_UPDATE：CCSMsgAttriUpdate(501) 与 CCMsgUnionAttriUpdate(1914)
            // 线格式相同但语义不同，501 的 type=5 是 EAT_WORLDCHAT_FREETIME，客户端 MainPlayer.cs:849-851
            // 会把「世界聊天免费次数」写成公会晶石数，且公会晶石面板不会刷新。
            pushUnion(u, MsgIds.S2C_UNION_ATTRI_UPDATE, dump.unionAttriUpdate(5, u.crystal, ""), 0);
            // 重置是公会级状态（该章 Boss 血量全公会共享，客户端 UBW:35-67 用 1923 整表重建血量条）
            // ⇒ 必须广播，否则其他成员的作战室仍显示「已击杀 / 0 血」，直到自己重开面板（1526）才自纠。
            // 排除操作者本人：他随后会收到 :1414/:1416 的定向回包（与 onBossResult 的排除语义一致）。
            pushBossInfo(u, rec.playerId);
        }
        session.send(MsgIds.S2C_UNION_BOSS_INFO, pkt, dump.unionBossInfo(bossViews(u),
                rec.playerId, myEmployWj(u, rec.playerId), rec.guild.bossPlayTimes));
        session.send(MsgIds.S2C_REST_BOSS, pkt, dump.unionBossFlat(bossView(u, chapter)));
    }

    /** 1530 RequestZuoZhanShiPaiMaiInfo：真实货架（由 Boss 掉落生成）。 */
    public void onAuctionInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        WorldStore.UnionRecord u = rec == null ? null : world.findUnion(rec.guild.id);
        if (u != null) {
            ensureAuction(u);
        }
        session.send(MsgIds.S2C_AUCTION_INFO, pkt, dump.auctionList(auctionItems(u)));
    }

    /** 作战室拍卖货架 → 1927 的 repeated 子消息列表。 */
    private List<byte[]> auctionItems(WorldStore.UnionRecord u) {
        List<byte[]> items = new ArrayList<>();
        if (u != null) {
            for (WorldStore.AuctionLot lot : u.auctionLots) {
                items.add(dump.auctionItem(lot.dropId, lot.ori, lot.count, lot.topBidder, lot.price));
            }
        }
        return items;
    }

    /** 1531 RequestJingPaiItem：校验出价高于当前价、退回前一位出价者。 */
    public void onJingPai(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        // 出价也走作战室的攻略组等级门（客户端只在场景入口校验一次，见 onBossFight 的说明）。
        if (u == null || !canUseBuilding(rec, WARROOM_BUILDING)) {
            session.send(MsgIds.S2C_JINGPAI, pkt, dump.auctionList(null));
            return;
        }
        ensureAuction(u);
        Pb.Fields f = Pb.read(pkt.body);
        int dropId = f.getInt(1, 0);
        int yqb = Math.max(0, f.getInt(2, 0));
        WorldStore.AuctionLot lot = findLot(u, dropId);
        if (lot == null || yqb <= lot.price || rec.guild.courageCoin < yqb) {
            session.send(MsgIds.S2C_JINGPAI, pkt, dump.auctionItem(dropId, lot == null ? "" : lot.ori,
                    lot == null ? 0 : lot.count, lot == null ? "" : lot.topBidder, lot == null ? 0 : lot.price));
            return;
        }
        if (lot.topBidderId > 0) {
            PlayerRecord prev = players.findByPlayerId(lot.topBidderId);
            if (prev != null) {
                if (prev.playerId == rec.playerId) {
                    // 自己顶自己的价：改前把这条路排除，自己抬价会被双扣。此处不做任何退款动作
                    // —— 下面 rec.guild.courageCoin -= yqb 只扣「新价与旧价之差」的那部分语义由
                    // 旧款已退 + 新价全扣等价实现，无需额外处理。
                } else {
                    // **只发退款邮件，不内联加币**：mailType 5（Sys_MailConfig 第5行「…的拍卖中
                    // 竞标失败，退还如下款项。」）的附件就是退还的勇气币，领取时由
                    // MailService.claim → rec.guild.courageCoin += mail.courageCoin 入账。
                    // 改前既内联 prev.guild.courageCoin += lot.price 又发带附件的邮件 ⇒ 被顶价者
                    // 领邮后**双倍**到账，两个号互抬价即可凭空印勇气币。
                    mail.sendAuctionRefundMail(prev.account, lootNameOf(lot), lot.price);
                }
            }
        }
        rec.guild.courageCoin -= yqb;
        lot.price = yqb;
        lot.topBidder = rec.roleName;
        lot.topBidderId = rec.playerId;
        world.saveUnions();
        players.save(rec);
        // 1928 **只回出价者**：客户端 OnZuoZhanShiPaiMaiItemInfo 把包体写进
        // `mDealOutInfo[mCurClickIndex]`（当前选中行），完全忽略包里的 dropID
        // （APK PlayGameState.cs:5551-5561），广播会污染其它成员正在看的那一行。
        // 其它成员刷新列表走 1530。
        session.send(MsgIds.S2C_JINGPAI, pkt, dump.auctionItem(lot.dropId, lot.ori, lot.count, lot.topBidder, lot.price));
        session.send(MsgIds.S2C_UNION_PLAYER_RES, pkt, dump.unionPlayerRes(rec));
        log.info("{} bid {} on drop {}", rec.account, yqb, dropId);
    }

    // ------------------------------------------------------------------ 医院 / 厨房 / 训练场

    /** 1534 RequestYiYuanDeadWJInfo：真实阵亡武将列表。 */
    public void onHospital(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        int cure = Pb.read(pkt.body).getInt(1, 0);
        session.send(MsgIds.S2C_HOSPITAL, pkt, dump.hospital(cure, rec));
    }

    /**
     * 1535 RequestEmployAWJ_YiYuan：雇佣医生缩短复活时间（APK 无 1932，雇佣后主动推 1931）。
     *
     * <p>回包必须是 **1931** 且 f1 {@code CureType} 回显请求值 1：客户端只注册 1931
     * （{@code PlayGameState.cs:206}）且 {@code :5894} 要求 {@code cureType == 1} 才
     * {@code RefreshBingRen} ⇒ 回 0（默认值会被 protobuf-net 省略）时整段 no-op：义园列表不刷新、
     * 复活时间不缩短、已复活项不移除，而金币/CD 已经生效（用户零反馈）。
     * 1932 {@code NET_CCMsgRequestEmployAWJ_YiYuan_Ret} 客户端既无注册也无消息类，**不能**用它。
     */
    public void onHospitalEmploy(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int cureType = f.getInt(1, 1);
        int playerGuid = f.getInt(2, 0);
        int wjIndex = f.getInt(3, 0);
        int selfWj = f.getInt(4, 0);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        int hospitalLevel = u == null ? 1 : buildingLevel(u, 7);
        // 医院等级基础缩短 + 雇佣医师(职业2)属性叠加（客户端 UnionHospital.cs:155 用
        // GetZhiLiaoReduceTime(fightPower) 展示的正是这两段之和）。
        int shorten = unionCfg.hospitalReliveSec(hospitalLevel);
        int cost = 0;
        // 医师可以是「候选直选」（1535 请求体带 playerGUID+wujiangIndex），也可以是医院格位里的登记；
        // 两条路径都取得到战力，否则直选候选的属性加成与费用都是 0。
        int medicPower = employerFightPower(u, 7, playerGuid, wjIndex);
        WorldStore.Employer medic = findEmployer(u, 7, playerGuid, wjIndex);
        int medicLevel = 0;
        if (medicPower > 0) {
            medicLevel = subsidiaryLevel(2, medicPower);
            UnionCfg.SubsidiaryRow mr = unionCfg.subsidiary(2, medicLevel);
            if (mr != null) {
                shorten += mr.attr;
                // 费用必须与 1917 f6 展示的价一致（UnionWJSubsidiary.txt 职业 2 的「雇佣酬劳」
                // 5000/10000/15000/20000）：客户端确认框只按展示价门控（UnionHospital.cs:240），
                // 改前用 zzsEmployPrice = 战力×0.325，医师 2 级（战力 29999–59999）实扣约
                // 9750–19500 而展示 10000，最高超收近 1 倍。
                cost = Math.max(0, mr.price);
            }
        }
        // 目标武将必须明确：原实现在 selfWj==0 时守卫失效，会把**所有**阵亡武将一起秒复活。
        if (selfWj <= 0 || !canUseBuilding(rec, HOSPITAL_BUILDING)) {
            session.send(MsgIds.S2C_HOSPITAL, pkt, dump.hospital(cureType, rec));
            return;
        }
        if (cost > 0 && rec.gold < cost) {
            // 金币不足（客户端 UnionHospital.cs:246 有同样的本地门控）：不缩短、不扣费。
            session.send(MsgIds.S2C_HOSPITAL, pkt, dump.hospital(cureType, rec));
            return;
        }
        // 目标病人：一个病人只能被医师治疗一次（S2C 1931 f3 IsEmergencyTreatme；客户端
        // BingRen.cs:60-71 收到 true 即禁用「资料」按钮）。改前 f3 恒 false 且无此闸门，
        // 客户端按钮永远可点 ⇒ 同一病人可被反复治疗、反复扣金币把倒计时叠到 0。
        PlayerRecord.QkDeadWj patient = null;
        if (rec.qkDeadWjs != null) {
            for (PlayerRecord.QkDeadWj d : rec.qkDeadWjs) {
                if (d != null && d.wjIndex == selfWj) {
                    patient = d;
                    break;
                }
            }
        }
        if (patient != null && patient.emergencyTreated) {
            // 已治疗过：不扣金币、不进 CD，只回现状（客户端按钮已禁用，这里是伪造包兜底）。
            session.send(MsgIds.S2C_HOSPITAL, pkt, dump.hospital(cureType, rec));
            return;
        }
        if (cost > 0) {
            rec.gold -= cost;
        }
        // 医师冷却（UnionWJSubsidiary 职业2 冷却 5400s）：上岗登记的那位进入冷却，
        // 客户端 1917 候选列表会用 leftCDTime 把它置灰（EmployerSystem 排序时排到末尾）。
        if (medic != null && medicLevel > 0) {
            UnionCfg.SubsidiaryRow mr = unionCfg.subsidiary(2, medicLevel);
            if (mr != null && mr.cdSec > 0) {
                medic.cdEnd = System.currentTimeMillis() + mr.cdSec * 1000L;
            }
        }
        if (patient != null) {
            patient.reliveUntilMs = Math.max(System.currentTimeMillis(),
                    patient.reliveUntilMs - shorten * 1000L);
            // 标记「已资料」：1931 f3 随本次回包置 true，客户端随即禁用资料按钮。
            patient.emergencyTreated = true;
        }
        players.save(rec);
        session.send(MsgIds.S2C_HOSPITAL, pkt, dump.hospital(cureType, rec));
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(3, rec.gold));
        log.info("{} hospital employ selfWj={} medic={} shorten={} cost={}", rec.account, selfWj, playerGuid,
                shorten, cost);
    }

    /**
     * 1536 RequestEmployDetailInfo：雇佣武将详细信息（1933）。
     *
     * <p>请求体用的就是回复类 {@code CCMsgRequestEmployDetailInfo_Ret} 本身 —— 客户端
     * EmployerSystem.cs:35-41 只填 buildingType + wjDetail.playerGuid + wjDetail.detailInfo.index。
     * 改前原样回显（echo）⇒ 回包里 playerName 为空、detailInfo 只剩 index，详情面板的
     * 等级/星级/阶段/战力全为 0。这里按佣兵登记信息补全（详见
     * PlayerDumpService.employDetail 的字段说明）。</p>
     */
    public void onEmployDetail(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int type = f.getInt(1, 6);
        int playerGuid = 0;
        int wjIndex = 0;
        byte[] wjDetail = f.getBytes(2);
        if (wjDetail != null) {
            Pb.Fields wj = Pb.read(wjDetail);
            playerGuid = wj.getInt(1, 0);
            byte[] detail = wj.getBytes(2);
            if (detail != null) {
                wjIndex = Pb.read(detail).getInt(2, 0);
            }
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        WorldStore.Employer e = findEmployer(u, type, playerGuid, wjIndex);
        String name = e == null ? "" : e.name;
        int level = e == null ? 0 : e.wjLevel;
        int stage = e == null ? 0 : e.wjStage;
        int stars = e == null ? 0 : e.wjStars;
        int fightPower = e == null ? 0 : e.fightPower;
        int price = e == null ? 0 : e.price;
        if (e == null) {
            // 佣兵列表里查不到时退回玩家档案：自己雇佣自己、或列表已被替换过的场景。
            for (PlayerRecord other : players.all()) {
                if (other == null || other.playerId != playerGuid) {
                    continue;
                }
                PlayerRecord.Hero h = other.findHeroByIndex(wjIndex);
                name = other.roleName == null ? "" : other.roleName;
                if (h != null) {
                    level = h.level;
                    stage = h.stage;
                    stars = h.stars;
                    fightPower = h.fightPower;
                }
                break;
            }
        }
        session.send(MsgIds.S2C_EMPLOY_DETAIL, pkt,
                dump.employDetail(type, playerGuid, wjIndex, name, level, stage, stars, fightPower, price));
        log.info("{} union employ detail type={} guid={} wj={} name={}", rec.account, type, playerGuid, wjIndex, name);
    }

    /**
     * 建筑类型 → {@code UnionWJSubsidiary.txt} 的职业类别。
     *
     * <p>两套编号**不同**：{@code EUnionBuildingType} 6=作战室、3=训练场、2=厨房、7=医院
     * （客户端 {@code EmployerSystem.cs:126/164/196/228} 按 buildingType 把同一份 1917 列表分发到
     * mEmploerList/mTrainEmployerList/mCookHouseEmployerList/mYiHuanEmployerList；{@code UnionCookHouse.cs:221}
     * 又用 type 2 取厨房等级），而 {@code UnionWJSubsidiary.txt:2} 的职业是 0=无（作战室）、1=教练、2=医师、3=厨师。
     * 原实现把两者当同一个数用（厨师查 employersOf(3) = 训练场），属性加成永远取不到。</p>
     */
    private static int professionOfBuilding(int buildingType) {
        switch (buildingType) {
            case 2:
                return 3;
            case 3:
                return 1;
            case 7:
                return 2;
            default:
                return 0;
        }
    }

    /**
     * 某个玩家在某个建筑的第 {@code index} 个格位记录（不存在则新建）。
     *
     * <p>格位是**按雇佣者分开**的：1915 的类名是 {@code CCMsgOnePlayeAllBuildingEmployerInfo}，
     * 客户端把 wjIndex[] 直接填进「我的建筑」格位（{@code UnionManagerSystem.cs:298-327}），
     * 所以 (hiredBy, slotIndex) 才是格位身份；原实现用公会整表的下标当格位，
     * 空表时首次雇佣被拒、卸下时 remove 还会移动别人的格位。</p>
     */
    private WorldStore.Employer slotOf(WorldStore.UnionRecord u, int type, int hirer, int index) {
        List<WorldStore.Employer> list = u.employersOf(type);
        for (WorldStore.Employer e : list) {
            if (e.hiredBy == hirer && e.slotIndex == index) {
                return e;
            }
        }
        WorldStore.Employer e = new WorldStore.Employer();
        e.hiredBy = hirer;
        e.slotIndex = index;
        list.add(e);
        return e;
    }

    /** 某个玩家在某个建筑里第一个空格位（受 VIP 格位上限约束）；满则 -1。 */
    private int firstFreeSlot(WorldStore.UnionRecord u, int type, int hirer, int cap) {
        for (int i = 0; i < cap; i++) {
            WorldStore.Employer e = null;
            for (WorldStore.Employer one : u.employersOf(type)) {
                if (one.hiredBy == hirer && one.slotIndex == i) {
                    e = one;
                    break;
                }
            }
            if (e == null || e.wjIndex <= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 某个玩家某个建筑的格位视图，长度 = max(1, cap, 已用到的最大下标 + 1)，下标即格位号。
     * 空格位用零值 {@link WorldStore.Employer} 占位 —— {@code buildingEmployer} 的两列是并行
     * repeated（客户端按 wjIndex.Count 同时取 wjIndex/cdTime），必须等长且不省略 0。
     */
    private List<WorldStore.Employer> slotView(WorldStore.UnionRecord u, int type, int hirer, int cap) {
        List<WorldStore.Employer> all = u == null ? new ArrayList<WorldStore.Employer>() : u.employersOf(type);
        int size = Math.max(1, cap);
        for (WorldStore.Employer e : all) {
            if (e.hiredBy == hirer && e.slotIndex + 1 > size) {
                size = e.slotIndex + 1;
            }
        }
        List<WorldStore.Employer> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            WorldStore.Employer found = null;
            for (WorldStore.Employer e : all) {
                if (e.hiredBy == hirer && e.slotIndex == i) {
                    found = e;
                    break;
                }
            }
            out.add(found == null ? new WorldStore.Employer() : found);
        }
        return out;
    }

    /** VIP 格位上限（{@code VipCfg} 公会佣兵数量）。 */
    private int mercenaryCap(PlayerRecord rec) {
        return Math.max(1, economy.unionMercenaryCount(rec.economy.chargedDiamond));
    }

    /** 在某个建筑类型的佣兵列表里按 playerId（+ 武将序号）找登记记录。 */
    private WorldStore.Employer findEmployer(WorldStore.UnionRecord u, int type, int playerGuid, int wjIndex) {
        if (u == null || playerGuid <= 0) {
            return null;
        }
        WorldStore.Employer loose = null;
        for (WorldStore.Employer e : u.employersOf(type)) {
            if (e.playerId != playerGuid) {
                continue;
            }
            if (e.wjIndex == wjIndex) {
                return e;
            }
            if (loose == null) {
                loose = e;
            }
        }
        return loose;
    }

    /**
     * 佣兵「属性参数」（{@code UnionWJSubsidiary.txt} 第 7 列）：厨师=体力、教练=经验提速**万分比**、
     * 医师=复活缩短**秒**（表头第 6 行）。客户端同源：{@code UnionWJSubsidiaryProperty.cs:120}
     * 教练 {@code propertyParam/10000f}、{@code :168} 医师 {@code propertyParam/60f}（分钟显示）。
     */
    private int employerAttr(WorldStore.UnionRecord u, int buildingType, int profession,
                             int playerGuid, int wjIndex) {
        int fightPower = employerFightPower(u, buildingType, playerGuid, wjIndex);
        if (fightPower <= 0) {
            return 0;
        }
        UnionCfg.SubsidiaryRow row = unionCfg.subsidiary(profession,
                subsidiaryLevel(profession, fightPower));
        return row == null ? 0 : row.attr;
    }

    /**
     * 佣兵战力：先查该建筑格位里的登记，再回落到该玩家自己的武将。
     *
     * <p>1535/1537/1538 的请求体带的是**候选**（playerGUID+wujiangIndex，客户端 1537
     * {@code UnionCookHouse.cs:112-126} 直接把 1917 列表里选中的那条发回来），候选未必已经占着格位；
     * 只查格位会让「直选候选」的属性加成恒 0。格位记录仍是第一优先（真实受雇关系）。</p>
     */
    private int employerFightPower(WorldStore.UnionRecord u, int buildingType, int playerGuid, int wjIndex) {
        WorldStore.Employer e = findEmployer(u, buildingType, playerGuid, wjIndex);
        if (e != null && e.fightPower > 0) {
            return e.fightPower;
        }
        PlayerRecord owner = players.findByPlayerId(playerGuid);
        PlayerRecord.Hero hero = owner == null ? null : owner.findHeroByIndex(wjIndex);
        return hero == null ? 0 : hero.fightPower;
    }

    /**
     * 1537 DrawUnionChuFangTiLi：体力 = 厨房等级档位 + 厨师属性（请求体带受雇厨师）。
     *
     * <p>1945 是**纯成功回执**：客户端 handler（{@code out2\Client\ᝁ.cs:5536-5540}）不反序列化 body，
     * 直接 {@code UnionCookHouse.SeverReturnSccess()} → {@code UnionCookHouse.cs:119-129}
     * 置 {@code SetCookHouseState(3)}、把本地 {@code Attribute.mLastUnionChuFangGainTime} 写成今天、
     * 弹 100701「获得体力」；按钮可见性 {@code UnionCookHouse.cs:216-236 Refresh()} 只比这个本地时间戳。
     * 所以**失败时绝不能回 1945**（否则是假成功，还烧掉当天额度），改为推 1913
     * （f4 {@code LastUnionChuFangDrawTiLiTime} = {@code rec.guild.lastKitchenStaminaAt}）让客户端自纠。
     */
    public void onKitchen(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        int level = u == null ? 1 : buildingLevel(u, 2);
        // 请求体 CMsgEmployInofo{1 playerGUID,2 wujiangIndex}（UnionCookHouse.cs:114-125）：
        // 厨师属性 15/30/45/60 直接加体力；改前完全没读请求体、也没算厨师加成。
        Pb.Fields f = Pb.read(pkt.body);
        int chefBonus = employerAttr(u, 2, 3, f.getInt(1, 0), f.getInt(2, 0));
        int stamina = unionCfg.kitchenStamina(level) + chefBonus;
        if (canUseBuilding(rec, KITCHEN_BUILDING) && rec.economy.kitchenStaminaLeft > 0 && stamina > 0) {
            rec.stamina += stamina;
            rec.economy.kitchenStaminaLeft--;
            rec.guild.lastKitchenStaminaAt = PlayerDumpService.now();
            players.save(rec);
            progress.pushPlayerProgress(session, pkt, rec, false, false, true, false, false);
            session.send(MsgIds.S2C_KITCHEN, pkt, dump.kitchenRet());
        } else {
            // 拒绝：不回 1945（见方法 javadoc），只推 1913 让客户端把「今天已领」的本地标记改回来。
            log.info("{} kitchen refuse level={} left={} stamina={} canUse={}",
                    rec.roleName, Integer.valueOf(rec.level),
                    Integer.valueOf(rec.economy.kitchenStaminaLeft), Integer.valueOf(stamina),
                    Boolean.valueOf(canUseBuilding(rec, KITCHEN_BUILDING)));
            session.send(MsgIds.S2C_UNION_PLAYER_RES, pkt, dump.unionPlayerRes(rec));
        }
    }

    /**
     * 1538 StartTrainingInUnion：训练时长走 UnionXunLian.txt（28800s），并记训练类型/雇主。
     *
     * <p>消耗按客户端口径：{@code UnionXunLian.txt} 的「消耗系数」是**每 1 货币换多少经验**，
     * 金币房（普通 1.25 / 白银 1）扣 {@code totalExp / 系数} 金币，钻石房（黄金 1000 / 铂金 800）
     * 扣同样数量的**钻石**（{@code SelectTrainRoom.cs:114/124/134/151} 分别与 {@code mCurJinBi}
     * / {@code mCurRMB} 比价）；挂了教练还要额外付 {@code UnionWJSubsidiary.txt} 职业 1 的
     * 「雇佣酬劳」（10000/20000/30000/40000，同处 {@code num2}）金币工资，
     * 其中 75%（{@code UnionWJSubsidiary.txt} 佣金获取系数）转给教练的主人。</p>
     */
    public void onTrainStart(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int wjIndex = f.getInt(1, 0);
        int trainType = Math.max(0, Math.min(3, f.getInt(2, 0)));
        int employerGuid = f.getInt(3, 0);
        int employerWj = f.getInt(4, 0);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (wjIndex <= 0) {
            session.send(MsgIds.S2C_TRAIN_START, pkt, dump.trainStart(0));
            return;
        }
        if (!canUseBuilding(rec, TRAIN_BUILDING)) {
            session.send(MsgIds.S2C_TRAIN_START, pkt, dump.trainStart(wjIndex));
            return;
        }
        // 坑位容量（UnionXunLianPits.txt「训练场等级→最大坑位」2..8）：满了或同一武将已在训就拒。
        // 1944 f1 只有 wjIndex、没有错误码，客户端 BeginTrainSuccess(wjIndex) 是按 wjIndex 找坑
        // （UnionTrainRoomInfo.cs:52-61）。**回 0 是错的**：空坑是 wjIndex==0 的全零条目
        // （UnionTrainRoomInfo.cs:23-25），Find(0) 命中第一个空坑并置 beginTrain=true ⇒ 每次失败都
        // 把空坑显示成「训练中」；回被拒武将自己的 wjIndex 反而安全（它不在任何坑里，Find 落空）。
        int pits = trainPitsOf(u);
        if (rec.economy.trainSlots.size() >= pits || findTrainSlot(rec, wjIndex) != null) {
            session.send(MsgIds.S2C_TRAIN_START, pkt, dump.trainStart(wjIndex));
            log.info("{} train start rejected wj={} pits={}/{}", rec.account, wjIndex,
                    rec.economy.trainSlots.size(), pits);
            return;
        }
        double trainerRatio = trainerRatioOf(u, employerGuid, employerWj);
        int cost = unionCfg.trainCost(rec.level, trainType, trainerRatio, unionCfg.trainSec());
        boolean diamond = unionCfg.trainCostInDiamond(trainType);
        WorldStore.Employer trainer = findEmployer(u, TRAIN_BUILDING, employerGuid, employerWj);
        // 教练酬劳取 UnionWJSubsidiary.txt 职业 1「雇佣酬劳」列（10000/20000/30000/40000，按教练战力分档），
        // **不能**依赖格位记录里的 price：训练教练来自 1917 候选（未上岗），findEmployer 恒 null（改前 wage 恒 0）。
        int trainerLevel = subsidiaryLevel(1, trainer != null ? trainer.fightPower
                : employerFightPower(u, TRAIN_BUILDING, employerGuid, employerWj));
        UnionCfg.SubsidiaryRow trainerRow = unionCfg.subsidiary(1, trainerLevel);
        int wage = (employerGuid <= 0 || employerWj <= 0 || trainerRow == null)
                ? 0 : Math.max(0, trainerRow.price);
        // 钻石房的训练费走钻石、教练工资仍走金币（客户端 :134 查 mCurRMB、:141 查 mCurJinBi）。
        if (diamond ? (rec.diamond < cost || rec.gold < wage) : (rec.gold < cost + wage)) {
            session.send(MsgIds.S2C_TRAIN_START, pkt, dump.trainStart(wjIndex));
            return;
        }
        if (diamond) {
            rec.diamond -= cost;
        } else {
            rec.gold -= cost;
        }
        if (wage > 0) {
            rec.gold -= wage;
            PlayerRecord trainerOwner = players.findByPlayerId(employerGuid);
            int gain = (int) Math.floor(wage * (trainerRow == null ? 0d : trainerRow.gainCoef));
            if (trainerOwner != null && gain > 0) {
                trainerOwner.gold += gain;
                players.save(trainerOwner);
                push(trainerOwner.account, MsgIds.S2C_ATTRI_UPDATE, dump.attri(3, trainerOwner.gold));
            }
            // 教练上岗后进入 UnionWJSubsidiary.txt 的「冷却时间/秒」(7200s)：客户端
            // TrainRoomAllEmployer.cs:133-146 用 1917 的 leftCDTime 置灰，而它取自
            // employedAnywhere(u, playerId, wjIndex).cdEnd ⇒ 已有上岗登记就写它的冷却，
            // 否则补一条（hiredBy = 教练本人，不占用雇佣者自己的格位）。
            WorldStore.Employer onDuty = employedAnywhere(u, employerGuid, employerWj);
            if (onDuty == null) {
                onDuty = slotOf(u, TRAIN_BUILDING, employerGuid, 0);
                onDuty.playerId = employerGuid;
                onDuty.account = trainerOwner == null ? "" : trainerOwner.account;
                onDuty.name = trainerOwner == null ? "" : trainerOwner.roleName;
                onDuty.wjIndex = employerWj;
                onDuty.fightPower = employerFightPower(u, TRAIN_BUILDING, employerGuid, employerWj);
                onDuty.price = wage;
            }
            onDuty.cdEnd = System.currentTimeMillis() + Math.max(0, trainerRow.cdSec) * 1000L;
            world.saveUnions();
        }
        PlayerRecord.Economy.TrainSlot slot = new PlayerRecord.Economy.TrainSlot();
        slot.wjIndex = wjIndex;
        slot.type = trainType;
        slot.employerGuid = employerGuid;
        slot.employerWj = employerWj;
        slot.totalExp = (int) unionCfg.trainTotalExp(rec.level, trainType, trainerRatio,
                unionCfg.trainSec());
        slot.startedAt = System.currentTimeMillis();
        // 教官按战力缩短倒计时（用户 m20090 #2）：经验/费用仍按整场算，只提前结束。
        slot.totalSec = trainSecWithCoach(u, employerGuid, employerWj);
        slot.leftSec = slot.totalSec;
        rec.economy.trainSlots.add(slot);
        players.save(rec);
        session.send(MsgIds.S2C_TRAIN_START, pkt, dump.trainStart(wjIndex));
        session.send(MsgIds.S2C_ATTRI_UPDATE, pkt,
                dump.attri(diamond ? 4 : 3, diamond ? rec.diamond : rec.gold));
        if (wage > 0) {
            session.send(MsgIds.S2C_ATTRI_UPDATE, pkt, dump.attri(3, rec.gold));
        }
        session.send(MsgIds.S2C_TRAIN_INFO, pkt, dump.trainInfo(trainSlots(rec)));
        log.info("{} train start wj={} type={} cost={}{} wage={} pits={}/{} left={}", rec.account, wjIndex,
                trainType, cost, diamond ? "钻石" : "金币", wage, rec.economy.trainSlots.size(), pits,
                slot.leftSec);
    }

    /** 1539 RequestUnionXunLianChangWJInfo：按坑位顺序发满 CXunLianWJInfo。 */
    public void onTrainInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        tickTrain(rec);
        session.send(MsgIds.S2C_TRAIN_INFO, pkt, dump.trainInfo(trainSlots(rec)));
    }

    /**
     * 分钟级钩子：对在线号推进训练倒计时并推送 1935。
     *
     * <p>{@link #tickTrain} 原先只被 1539（{@link #onTrainInfo}）调用，而客户端只在打开训练场面板
     * （{@code UnionTrainRoom.cs:53}）与面板内本地倒计时归零时自轮询（{@code :484}）才发 1539 ⇒
     * 玩家关掉面板后训练到点不结算、1935 永不弹。这里按分钟扫在线号，与真服的「到点主动推」对齐。
     */
    public void trainClock() {
        for (GameSession s : sessions.onlineSnapshot()) {
            PlayerRecord rec = s.player();
            if (rec != null && !rec.economy.trainSlots.isEmpty()) {
                tickTrain(rec);
            }
        }
    }

    /**
     * 1540 CancelXunLianWJ：APK 无独立取消回包（回 1934），但文本 {@code Code.txt 100718}
     * 「取消训练将按时间比例获得收益，金币或钻石不会返还」⇒ 按已练时长比例入账经验、**不退费**。
     * 请求体带 f1 {@code wjIndex}（客户端 {@code UnionTrainRoom.cs:683-689}，此前记录「无字段」有误）。
     * 另推 1935 让客户端弹 {@code UnionTrainFinish} 显示按比例到手的经验（推证：客户端 1540 无专属回包，
     * 只有 1934/1935 两条路，与 100718 文案一致）。
     */
    public void onTrainCancel(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int reqWj = f.getInt(1, 0);
        // 多坑后必须按 f1 wjIndex 认坑（1538/1540 都只带 wjIndex，不带坑号）；空体时只有单坑可推断。
        PlayerRecord.Economy.TrainSlot slot = findTrainSlot(rec, reqWj);
        if (slot == null) {
            session.send(MsgIds.S2C_TRAIN_INFO, pkt, dump.trainInfo(trainSlots(rec)));
            return;
        }
        int wjIndex = slot.wjIndex;
        PlayerRecord.Hero h = rec.findHeroByIndex(wjIndex);
        int preLevel = h == null ? 0 : h.level;
        int granted = 0;
        if (h != null && slot.startedAt > 0) {
            int dur = slot.totalSec > 0 ? slot.totalSec : unionCfg.trainSec();
            long elapsed = Math.max(0L, (System.currentTimeMillis() - slot.startedAt) / 1000L);
            elapsed = Math.min(elapsed, dur);
            float ratio = trainerRatioOf(world.findUnion(rec.guild.id), slot.employerGuid, slot.employerWj);
            int total = (int) unionCfg.trainTotalExp(rec.level, slot.type, ratio, unionCfg.trainSec());
            granted = (int) (total * elapsed / Math.max(1, dur));
            progress.addWjExp(rec, h, granted);
        }
        rec.economy.trainSlots.remove(slot);
        players.save(rec);
        if (h != null && granted > 0) {
            session.send(MsgIds.S2C_TRAIN_FINISH, pkt,
                    dump.trainFinish(wjIndex, preLevel, h.level, granted));
        }
        session.send(MsgIds.S2C_TRAIN_INFO, pkt, dump.trainInfo(trainSlots(rec)));
        log.info("{} train cancel wj={} granted={} pits={}", rec.account, wjIndex, granted,
                rec.economy.trainSlots.size());
    }

    // ------------------------------------------------------------------ 马厩：押镖 / 劫镖

    /** 1541 RequestMaJiuInfo（1936）。 */
    public void onMajiuInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        progress.ensureDaily(rec);
        ensureMajiuDay(rec);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        session.send(MsgIds.S2C_MAJIU_INFO, pkt, dump.majiuInfo(majiuView(rec, u)));
    }

    /**
     * 1542 RequestMyUnionBiaoCheInfo（1937）：**本公会**的镖车列表，一行一车
     * （{@code CCMsgUnionBiaoCheInfo} 只有一个 repeated {@code CCMsgPlayerBiaoCheInfo}）。
     * 客户端 `MaJiuDuiWuLieBiaoUI.cs:87-98` 逐行生成条目、只按目的地过滤、
     * **不按 playerGUID 去重** ⇒ 同一成员的多辆车各占一行。
     */
    public void onMajiuMyBiao(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        List<byte[]> rows = new ArrayList<>();
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u != null) {
            u.ensure();
            String today = PlayerDumpService.now().substring(0, 10);
            for (WorldStore.Member m : u.members) {
                PlayerRecord p = players.get(m.account);
                if (p == null || p.economy.majiuDay == null || !p.economy.majiuDay.equals(today)) {
                    continue;
                }
                for (PlayerRecord.Economy.EscortCart c : pendingCarts(p)) {
                    rows.add(dump.playerBiaoChe(c.cartId, c.targetId, p, c.beRaidCnt));
                }
            }
        }
        session.send(MsgIds.S2C_MAJIU_MY_BIAO, pkt, dump.escortCarts(rows));
    }

    /**
     * 1543 SendABiaoChe{1 targetID, 2 formation{1 type, 2 repeated wj(GUID 串)}}：在发镖时段发车。
     *
     * <p>1938 <b>只在真的发车成功时回</b>：客户端 {@code MaJiuBasePaiQianUI.cs:116-126} 收到
     * {@code EN_SendABiaoCheRet} 后无条件弹 100796「派遣成功」并直接取 {@code biaoCheInfo[0]}。
     * 失败时若回 0 字节 body ⇒ 空列表越界异常（{@code NetMsgGlobalHandler.OnRecvMsg} 无 try/catch，
     * 会打断该次派发）；若回一个空条目 ⇒ 客户端往 {@code myBiaoCheInfo} 里塞一辆
     * {@code biaoCheGUID=""} 的假车。客户端 1543 不等待回包（{@code EmBattleSystem.cs:2677} 发完即
     * {@code Close()}），故失败静默不回是安全且唯一不产生错误状态的选择。
     *
     * <p>目的地门槛按 {@code UnionMaJiuTarget.txt} 校验（客户端同样拦两道，服务端不校验就能靠伪造
     * 包选最高档线路拿 450000 战力的金币奖励）：①马厩等级 ≥ 第 2 列所需马厩等级 —— 客户端
     * {@code MaJiuPaiQianUI.cs:48} 用 {@code GetBuildingLevel(EUBT_MAJIU=5) >= uMaJiuLevelRequired} 判锁定；
     * ②本次**上阵阵容总战力** ≥ 第 3 列所需战力 —— {@code EmBattleSystem.cs:2660} 在发 1543 之前用
     * {@code GetTotalFightPower() < uFightPower} 弹 100750 并 return。战力取请求体 f2 里 5 个武将
     * GUID（{@code CMsgFormation.wj} 是 repeated string）对应的武将战力之和。
     */
    public void onSendCart(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        ensureMajiuDay(rec);
        Pb.Fields f = Pb.read(pkt.body);
        int targetId = f.getInt(1, 0);
        boolean targetOk = canSendCart(rec, targetId, formationFightPower(rec, f.getBytes(2)))
                && canUseBuilding(rec, MAJIU_BUILDING);
        // 发镖额度 = **同时在途镖车数上限**，取 VipCfg.txt 第 27 列「发镖数量」
        // （VIP0-10 = 1、VIP11+ = 2）。依据是客户端自己的门控写法
        // `EmBattleSystem.cs:2653 if (GetMaJiuInfo().myBiaoCheInfo.Count >= VipManager.FaBiaoCount)`
        // —— 1942 f7 myBiaoCheInfo 就是「我在途镖车 GUID 列表」，没有「当日已发次数」字段。
        int quota = Math.max(1, economy.faBiaoCount(rec.economy.chargedDiamond));
        int inFlight = pendingCarts(rec).size();
        if (targetOk && inSendWindow() && inFlight < quota) {
            PlayerRecord.Economy.EscortCart cart = new PlayerRecord.Economy.EscortCart();
            cart.targetId = targetId;
            cart.sentAt = System.currentTimeMillis();
            // GUID 必须逐车唯一：1544 f3 targetBiaoCheGUID 靠它定位「打劫哪一辆」，
            // 同一毫秒连发两辆也不能撞（故拼上发车序号）。
            cart.cartId = rec.account + "-cart-" + cart.sentAt + "-" + (rec.economy.escortCarts.size() + 1);
            rec.economy.escortCarts.add(cart);
            players.save(rec);
            WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
            if (u != null) {
                pushUnion(u, MsgIds.S2C_MAJIU_INFO, dump.majiuInfo(majiuView(rec, u)), rec.playerId);
            }
            session.send(MsgIds.S2C_SEND_CART, pkt, dump.escortSendRet(rec, cart));
        } else if (targetOk && inSendWindow() && inFlight >= quota) {
            log.info("{} send cart rejected: {} carts in flight (quota {})", rec.account, inFlight, quota);
        }
        session.send(MsgIds.S2C_MAJIU_INFO, pkt,
                dump.majiuInfo(majiuView(rec, world.findUnion(rec.guild.id))));
    }

    /**
     * 1544 RequestRaidABiaoChe：校验场次/时段/目标车，回 1939 ret + 被劫方阵容
     * （客户端只在 ret==0 时 UnPackData(beRaidFormation) 进战斗）。
     *
     * <p>请求体 {@code CCMsgRequestRaidABiaoChe}（{@code NetProto\CCMsgRequestRaidABiaoChe.cs}）：
     * f1 {@code targetID} = 目的地 id（客户端填 {@code TargetInfo.targetID}）、
     * f2 {@code raidPlayerGUID} = **被劫玩家** GUID、f3 {@code targetBiaoCheGUID}
     * = **被劫的那一辆车**的 GUID（三个字段全部取自车级对象 {@code MaJiuLanJieDuiWuUI.TargetInfo}，
     * 见 {@code EmBattleSystem.cs:2686-2690}）。一个玩家可以同时有多辆
     * 在途镖车，所以必须按 f3 认车，不能只按玩家。
     *
     * <p>ret 不是只有 0/1：客户端 {@code MaJiuBaseLanJieUI.cs:135-158} 对 1/2/3/4 各弹一条
     * StrTable（100794 有人正在打劫他 / 100793 已经被打劫过了 / 100791 劫镖活动已结束 /
     * 100790 额度用完），服务端必须按真实原因回对应码。
     */
    public void onRaidCart(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        ensureMajiuDay(rec);
        Pb.Fields f = Pb.read(pkt.body);
        int raidPlayerGuid = f.getInt(2, 0);
        String cartGuid = f.getString(3);
        PlayerRecord target = raidPlayerGuid > 0 ? players.findByPlayerId(raidPlayerGuid) : null;
        if (target != null) {
            ensureMajiuDay(target);
        }
        int mySuc = rec.economy.escortRaidSucTimes;
        if (!inRaidWindow()) {
            session.send(MsgIds.S2C_RAID_CART, pkt, dump.escortRaidRet(3, mySuc, null, null));
            return;
        }
        // 两套额度分别判：成功额度（VipCfg 第 29 列「劫镖次数」，客户端也用它门控）与
        // 出手额度（UnionMaJiuBase.txt「单次活动有效掠夺场次（不管成功还是失败）」3 次，
        // 客户端无对应字段 ⇒ 只能服务端拦）。都用 ret=4（客户端文案 = 次数已用完）。
        if (rec.economy.escortRaidLeft <= 0 || rec.economy.escortRaidTimes >= unionCfg.raidTimes()) {
            session.send(MsgIds.S2C_RAID_CART, pkt, dump.escortRaidRet(4, mySuc, null, null));
            return;
        }
        PlayerRecord.Economy.EscortCart cart = target == null ? null : pickRaidCart(target, cartGuid);
        if (cart == null || cart.hasBeenRaid) {
            session.send(MsgIds.S2C_RAID_CART, pkt, dump.escortRaidRet(2, mySuc, null, null));
            return;
        }
        // 可劫目标**只能跨公会**（用户 m19006 #11）：同公会成员的镖车根本不进 1936 f4 列表，
        // 伪造包直接点名打同公会也按「车不存在」拒绝（ret 2，与过期车同档）。
        // 合法客户端到不了这里（列表里没有同公会的车）。跨服可劫，无需额外过滤。
        if (target != null && target.guild != null && rec.guild != null
                && target.guild.id != null && target.guild.id.equals(rec.guild.id)) {
            session.send(MsgIds.S2C_RAID_CART, pkt, dump.escortRaidRet(2, mySuc, null, null));
            return;
        }
        // 「有人正在打劫」判定前先清超时标记（掉线留下的幽灵拦截，见 clearStaleRaid）。
        clearStaleRaid(cart);
        if (cart.raiderName != null && !cart.raiderName.isEmpty()) {
            session.send(MsgIds.S2C_RAID_CART, pkt, dump.escortRaidRet(1, mySuc, null, null));
            return;
        }
        // 两套额度都在 1545 结算时才记（见 onRaidResult）：成功额度只记成功（客户端门控用的是
        // myRaidSucTime 成功次数与 VipManager.JieBiaoCount 比较，失败不该占成功额度）；
        // 出手额度（escortRaidTimes）**成功与失败都记**，与 UnionMaJiuBase.txt 的「不管成功还是失败」一致。
        rec.economy.raidTargetId = String.valueOf(raidPlayerGuid);
        rec.economy.raidTargetCartId = cart.cartId;
        cart.raiderName = rec.roleName;
        cart.raidStartedAt = System.currentTimeMillis();
        players.save(rec);
        players.save(target);
        // 1939 f3 msgUpdate（CCMsgRaidBiaoCheInfo）= 本次拦截后这辆车的状态行，
        // 客户端用它更新拦截列表里的「掠夺中」标记。
        byte[] msgUpdate = dump.raidBiaoChe(cart.hasBeenRaid, cart.raiderName,
                dump.playerBiaoChe(cart.cartId, cart.targetId, target, cart.beRaidCnt));
        session.send(MsgIds.S2C_RAID_CART, pkt,
                dump.escortRaidRet(0, mySuc, msgUpdate, dump.jjcFightTargetDetailSelf(target)));
        session.send(MsgIds.S2C_CHANGE_REGION_RET, pkt, dump.changeRegion(1));
        push(target.account, MsgIds.S2C_MAJIU_INFO,
                dump.majiuInfo(majiuView(target, world.findUnion(target.guild.id))));
        log.info("{} raiding {} cart={}", rec.account, target.account, cart.cartId);
    }

    /**
     * 清理「正在拦截」超时标记（{@code UnionMaJiuBase.txt}「单场掠夺超时时间」300s + 宽限）：
     * 玩家在拦截战斗里掉线时永远不发 1545，不清理就会把那一辆车锁死一整天。
     * 调用点：{@link #majiuView}（f4 列表不显示幽灵「掠夺中」）与 {@link #onRaidCart}（ret=1 判定前）。
     */
    private void clearStaleRaid(PlayerRecord.Economy.EscortCart c) {
        if (c.raiderName == null || c.raiderName.isEmpty()) {
            c.raidStartedAt = 0L;
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (c.raidStartedAt <= 0L) {
            // 旧档没有时间戳：从现在起算，不立刻清（避免误清真正在打的战斗）。
            c.raidStartedAt = nowMs;
            return;
        }
        int timeoutSec = unionCfg.raidTimeoutSec();
        if (nowMs - c.raidStartedAt > (timeoutSec + RAID_TIMEOUT_GRACE_SEC) * 1000L) {
            c.raiderName = "";
            c.raidStartedAt = 0L;
        }
    }

    /**
     * 1544 选中要打劫的那一辆车：按 f3 {@code targetBiaoCheGUID} 精确匹配在途镖车。
     * GUID 为空时退回「第一辆可劫的车」（兼容不带 f3 的调用方）；给了 GUID 却匹配不到
     * 说明请求已过期（那辆车已结算/被跨日清掉），返回 null 让调用方回 1939 ret=2，
     * **不能**顺手改打另一辆。
     */
    private PlayerRecord.Economy.EscortCart pickRaidCart(PlayerRecord target, String cartGuid) {
        List<PlayerRecord.Economy.EscortCart> carts = pendingCarts(target);
        if (cartGuid != null && !cartGuid.isEmpty()) {
            for (PlayerRecord.Economy.EscortCart c : carts) {
                if (cartGuid.equals(c.cartId)) {
                    return c;
                }
            }
            return null;
        }
        return carts.isEmpty() ? null : carts.get(0);
    }

    /**
     * 1545 ResultRaidABiaoChe{suc}：掠夺金币按「线路金币奖励 × 掠夺比例」，公会晶石按资金兑换系数。
     *
     * <p>入口先滚天（{@code progress.ensureDaily} + {@link #ensureMajiuDay}）：改前这里只给**被劫方**
     * 滚天，劫镖者自己的 {@code majiuDay} 还是昨天（甚至空串）⇒ 他刚打到的 {@code raidJinbiTotal}
     * 会被记在昨天的活动日上，1546 排行榜与 {@link #raidRankOf} 的名次档都算不到他。
     */
    public void onRaidResult(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        ensureMajiuDay(rec);
        boolean suc = Pb.read(pkt.body).getBool(1);
        int targetId = parseInt(rec.economy.raidTargetId, 0);
        int gold = 0;
        int crystal = 0;
        int xdb = 0;
        PlayerRecord target = targetId > 0 ? players.findByPlayerId(targetId) : null;
        PlayerRecord.Economy.EscortCart cart = null;
        if (target != null) {
            ensureMajiuDay(target);
            // 1544 f3 targetBiaoCheGUID 认车：一个玩家可以同时有多辆在途镖车
            // （VipCfg.txt 第 27 列「发镖数量」VIP11+ = 2），结算必须回到**那一辆**，
            // 不能再用「被劫方唯一的那辆车」。
            cart = findCart(target, rec.economy.raidTargetCartId);
            if (cart != null && cart.awarded) {
                // 这辆车在 1544→1545 之间被跨日/离线结算掉了（ensureMajiuDay 刚补发过），
                // 不能再按它发一次战果。
                cart = null;
            }
        }
        if (suc && cart != null) {
            int lineGold = lineGoldOf(cart.targetId);
            gold = (int) Math.floor(lineGold * unionCfg.raidGainRatio());
            crystal = (int) Math.floor(gold * unionCfg.raidXdbRatio());
            // hasBeenRaid = 本车已被打劫成功、不可再劫（MaJiuLanJieDuiWuUI.SetItem:117-142
            // 据此隐藏战斗按钮并给守方画阵亡；MaJiuBaseLanJieUI.cs:79-86 用 count(!hasBeenRaid)
            // 当「镖车数」）。改前它只写在被劫玩家的单值字段上，且没有「不可再劫」的拦截。
            cart.hasBeenRaid = true;
            cart.beRaidCnt++;
            cart.raiderName = "";
            cart.raidStartedAt = 0L;
            target.gold = Math.max(0, target.gold - (int) Math.floor(lineGold * unionCfg.beRaidLoseRatio()));
            players.save(target);
            push(target.account, MsgIds.S2C_ATTRI_UPDATE, dump.attri(3, target.gold));
            push(target.account, MsgIds.S2C_MAJIU_INFO,
                    dump.majiuInfo(majiuView(target, world.findUnion(target.guild.id))));
            rec.economy.escortRaidSucTimes++;
            rec.economy.escortRaidLeft = Math.max(0, rec.economy.escortRaidLeft - 1);
            WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
            if (u != null) {
                u.growth = Math.min(unionCfg.growCap(), u.growth + unionCfg.raidGrow());
                u.crystal += crystal;
                world.saveUnions();
            }
            // 兄弟币按**劫镖排行榜名次**取档（UnionMaJiuBase.txt 排行兄弟币 1:400_2:300_3:200_10:150_40:100）；
            // 改前硬编码 raidRankBrother(1) ⇒ 人人都是第一档 400。
            xdb = unionCfg.raidRankBrother(raidRankOf(rec));
        } else if (cart != null) {
            // 拦截失败 = 被劫方「成功防守」：按 UnionMaJiuBase.txt 第 12 行
            // defenceSucAwardRate（真服 10%，见 docs\PROTOCOL_FIELD_AUDIT.md 的 D6 轮记录）
            // 给被劫方一笔「线路金币 × 比例」奖金，累计进 1943 f6 defenceAward。
            // 客户端只解析不消费该比例（UnionMaJiuBaseProperty.cs:16-63），
            // 渲染在 MaJiuGetAwardUI.cs:159-169，标题 100806。
            int lineGold = lineGoldOf(cart.targetId);
            int defendGold = (int) Math.floor(lineGold * unionCfg.defendGoldRatio());
            target.gold += defendGold;
            target.economy.escortDefendGold += defendGold;
            target.economy.escortDefendUnion = rec.guild.name == null ? "" : rec.guild.name;
            // 同上：防守成功也算本次拦截已结算，清「正在被拦截」标记。
            cart.raiderName = "";
            cart.raidStartedAt = 0L;
            players.save(target);
            push(target.account, MsgIds.S2C_ATTRI_UPDATE, dump.attri(3, target.gold));
            push(target.account, MsgIds.S2C_MAJIU_INFO,
                    dump.majiuInfo(majiuView(target, world.findUnion(target.guild.id))));
        }
        // 本次拦截已结算：清「我正在打劫哪一辆」，同一辆车不会被重复结算（重复 1545 找不到车）。
        // 出手额度（UnionMaJiuBase.txt「有效掠夺场次（不管成功还是失败）」3 次）在这里记一次：
        // 只有真的结算到一辆车才算「有效场次」（cart == null 说明这辆车已作废/跨日结算过，
        // 本次没有产生战斗，不该占额度）。
        if (cart != null) {
            rec.economy.escortRaidTimes++;
        }
        rec.economy.raidTargetCartId = "";
        rec.gold += gold;
        rec.economy.raidJinbiTotal += gold;
        rec.economy.raidJinShiTotal += crystal;
        rec.economy.raidXdbTotal += xdb;
        if (xdb > 0) {
            // 劫镖名次兄弟币必须真的入账：1940 f1 只是「本次获得」展示值，改前只累加
            // raidXdbTotal 而从不加进公会兄弟币（MaJiuGetAwardUI 把 f1 当本次收益显示，
            // 玩家看到 +400 却永远拿不到）。1914 attr2 = MainPlayer.mUnionXiongDiBi。
            rec.guild.brotherCoin += xdb;
            push(rec.account, MsgIds.S2C_UNION_ATTRI_UPDATE,
                    dump.unionAttriUpdate(2, rec.guild.brotherCoin, ""));
        }
        players.save(rec);
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_RAID_RESULT, pkt, dump.escortRaidResult(xdb, crystal, gold));
        log.info("{} raid result suc={} gold={} crystal={}", rec.account, suc, gold, crystal);
    }

    /**
     * 1546 RequestRaidRankList（1941）：按**当日**劫镖总收益排名。
     *
     * <p>入口必须先滚天（{@code progress.ensureDaily} + {@link #ensureMajiuDay}）：改前 1546
     * 根本不调，请求者自己还挂着昨天的 {@code majiuDay} 与统计，整张榜都是昨天的人。
     */
    public void onRaidRank(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        ensureMajiuDay(rec);
        List<byte[]> rows = new ArrayList<>();
        for (PlayerRecord p : raidRanked(rec)) {
            if (p.economy.raidJinbiTotal <= 0 && p.economy.raidXdbTotal <= 0) {
                continue;
            }
            rows.add(dump.raidRankRow(p.playerId, p.roleName, p.mainHeroIndex, p.level,
                    p.economy.raidXdbTotal, p.economy.raidJinShiTotal, p.economy.raidJinbiTotal));
        }
        session.send(MsgIds.S2C_RAID_RANK, pkt, dump.escortRaidRank(rows));
    }

    /** 1547 RequestRefreshRaid（1942）：客户端按 CCMsgRequestMaJiuInfoRet 解，必须回 1942 快照。 */
    public void onRefreshRaid(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        ensureMajiuDay(rec);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        // 职位门（用户 m19006 #2）：客户端 MaJiuShuaXinUI.cs:83-88 在发 1547 之前就要求
        // mEUnionJob >= 2（会长/副会长），否则本地弹 100813；服务端改前谁都能刷。
        // 判别只看 rec.guild.job（guildJobCode()）、**不查成员列表**：新建公会时 onCreate
        // 只写 rec.guild.job = "owner"（成员条目若因存档/迁移缺失，查列表会把会长自己挡在门外）。
        // 拒绝时仍回 1942 现状快照（1547 的响应 msgId 就是 1942，无独立错误包；合法客户端到不了这里）。
        if (u != null && !isOwnerOrElder(rec)) {
            log.info("{} refresh majiu denied: job={} 不是会长/副会长", rec.account, rec.guildJobCode());
        } else if (u != null && rec.economy.majiuResetTimes < unionCfg.majiuResetTimes()) {
            // 「单次活动刷新次数」上限 = 2（UnionMaJiuBase.txt）：改前没有上限，可以无限刷目的地。
            int cost = refreshRaidCost(rec, u);
            if (u.crystal >= cost) {
                if (cost > 0) {
                    u.crystal -= cost;
                    pushUnion(u, MsgIds.S2C_UNION_ATTRI_UPDATE, dump.unionAttriUpdate(5, u.crystal, ""), 0);
                }
                rec.economy.majiuResetTimes++;
                world.saveUnions();
            }
        }
        players.save(rec);
        session.send(MsgIds.S2C_REFRESH_RAID, pkt, dump.majiuInfo(majiuView(rec, u)));
        log.info("{} refresh majiu times={}", rec.account, rec.economy.majiuResetTimes);
    }

    /**
     * 1547 单次刷新消耗的公会晶石：**一律以散表为准**（用户 m19522 #2 拍板）。
     *
     * <p>取 {@code UnionMaJiuBase.txt}「等级N刷新所需公会晶石」按当前马厩等级（建筑 5）的行值；
     * 出厂表 7 个等级**全 0** ⇒ 实际**免费刷新**，与客户端面板显示一致
     * （客户端消耗值取自它自己那份散表 {@code MaJiuShuaXinUI.cs:64-69}，同样显示 0；
     * 原按用户 m19006 #11 兜底的 10/20/40 会造成「面板显示 0、服务端实扣」的显示不一致，
     * 用户 m19522 #2 决定跟表改 0）。表里若出现非 0 值即按表扣公会晶石。
     */
    private int refreshRaidCost(PlayerRecord rec, WorldStore.UnionRecord u) {
        return Math.max(0, unionCfg.majiuResetCrystal(buildingLevel(u, MAJIU_BUILDING)));
    }

    /**
     * 1548 RequestGetYunBiaoAward（1943）：真实名次 + 线路奖励 + 防守奖励。
     *
     * <p>**一次领全部**：1548 无请求体（客户端 {@code MaJiuEntryUI.cs:223}
     * {@code Send<IExtensible>(1548, null, ...)}），1943 f5 {@code yunBiaoAward}
     * 是「一车一条」的列表（{@code CCMsgYunBiaoAwardItem} f1 {@code targeID} = 目的地 id、
     * f2 {@code beRaidCnt}、f3 {@code jinBi}、f4 {@code XDB}、f5 {@code jinShi}），
     * 客户端 {@code MaJiuGetAwardUI.cs:107-115/173-200} 逐条渲染
     * （100808 运往{0}顺利抵达 / 100809 运往{0}被掠夺{1}次）。所以这里要把**当天所有**
     * 未领奖的镖车一起结算，而不是只结一辆。
     */
    public void onYunBiaoAward(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        progress.ensureDaily(rec);
        ensureMajiuDay(rec);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        List<PlayerRecord.Economy.EscortCart> carts = pendingCarts(rec);
        if (majiuPhase() != 3 || carts.isEmpty() || rec.economy.majiuAwarded) {
            session.send(MsgIds.S2C_YUNBIAO_AWARD, pkt, raidAwardBody(rec, awardItems(rec), defenceAwards(rec)));
            return;
        }
        int gold = 0;
        for (PlayerRecord.Economy.EscortCart c : carts) {
            gold += lineGoldOf(c.targetId);
        }
        int xdb = economy.escortSendXdb() * carts.size();
        int rank = raidAwardRank(rec);
        List<byte[]> items = awardItems(rec);
        List<byte[]> defends = defenceAwards(rec);
        rec.gold += gold;
        rec.guild.brotherCoin += xdb;
        markCartsAwarded(rec);
        rec.economy.majiuAwarded = true;
        players.save(rec);
        if (u != null) {
            u.growth = Math.min(unionCfg.growCap(), u.growth + unionCfg.sendGrow() * carts.size());
            world.saveUnions();
        }
        progress.pushGold(session, pkt, rec);
        session.send(MsgIds.S2C_UNION_PLAYER_RES, pkt, dump.unionPlayerRes(rec));
        session.send(MsgIds.S2C_YUNBIAO_AWARD, pkt, raidAwardBody(rec, items, defends));
        session.send(MsgIds.S2C_MAJIU_INFO, pkt, dump.majiuInfo(majiuView(rec, u)));
        log.info("{} majiu award rank={} carts={} gold={} xdb={}", rec.account, rank, carts.size(),
                gold, xdb);
    }

    /**
     * 押镖统一结算（真服「当天押镖都结束后由服务端结算下发」）：APK 时间窗一结束
     * （{@link #majiuPhase()} == 3），把当天发过车、还没点 1548 领奖的收益一次性发掉——
     * 在线推 1943（客户端 {@code PlayGameState.cs:6024-6031} 收到即开 MaJiuGetAwardUI 结算弹窗，
     * 不需要玩家先发 1548），离线按 mailType 7「运镖收益」
     * （{@code ESysMailTypeID.EMTID_UNION_MJ_AWARD=7}，Sys_MailConfig 第7行
     * 「你在公会运镖活动中有收益没有及时领取，邮寄给你啦：」）邮寄。
     *
     * <p>由 {@code GlobalServerScheduler} 在 UnionMaJiuTime.txt 窗口结束时
     * （18:00 + 发镖 7200s + 劫镖 7200s = 22:00）调用；服务器启动补跑走同一入口。
     * 窗口未结束直接返回，不动在途镖车。
     */
    public void settleMajiuAwards(String why) {
        if (majiuPhase() != 3) {
            return;
        }
        String day = PlayerDumpService.now().substring(0, 10);
        if (day.equals(majiuSettledDay)) {
            return;
        }
        majiuSettledDay = day;
        int n = 0;
        for (PlayerRecord rec : players.all()) {
            if (!hasPendingCart(rec)) {
                continue;
            }
            settlePendingCart(rec, why);
            n++;
        }
        if (n > 0) {
            log.info("majiu settle {} players={}", why, n);
        }
    }

    /** 有在途、未领奖的镖车。 */
    private boolean hasPendingCart(PlayerRecord rec) {
        return !pendingCarts(rec).isEmpty();
    }

    /** 未结算（未领奖）的在途镖车；发镖额度、1936 f7、1943 f5 都以它为口径。 */
    private List<PlayerRecord.Economy.EscortCart> pendingCarts(PlayerRecord rec) {
        List<PlayerRecord.Economy.EscortCart> out = new ArrayList<>();
        if (rec == null || rec.economy == null || rec.economy.escortCarts == null) {
            return out;
        }
        for (PlayerRecord.Economy.EscortCart c : rec.economy.escortCarts) {
            if (c != null && !c.awarded) {
                out.add(c);
            }
        }
        return out;
    }

    /** 按 1938 下发的 {@code biaoCheGUID} 找车（1544 f3 / 1545 结算都靠它认车）。 */
    private PlayerRecord.Economy.EscortCart findCart(PlayerRecord rec, String cartId) {
        if (rec == null || cartId == null || cartId.isEmpty() || rec.economy.escortCarts == null) {
            return null;
        }
        for (PlayerRecord.Economy.EscortCart c : rec.economy.escortCarts) {
            if (c != null && cartId.equals(c.cartId)) {
                return c;
            }
        }
        return null;
    }

    /** 标记当天全部未领奖镖车已结算（1548 一次领全部，{@code hasGetAward} 也是单标记）。 */
    private void markCartsAwarded(PlayerRecord rec) {
        for (PlayerRecord.Economy.EscortCart c : pendingCarts(rec)) {
            c.awarded = true;
        }
    }

    /**
     * 结算当天全部未领取的镖车：在线推 1943（弹结算窗）+ 1913 + 1936 + 金币 attri，
     * 离线发 mailType 7「运镖收益」。跨日兜底（{@link #ensureMajiuDay}）也走这里，
     * 避免过夜镖车被静默丢掉。
     */
    private void settlePendingCart(PlayerRecord rec, String why) {
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        List<PlayerRecord.Economy.EscortCart> carts = pendingCarts(rec);
        int gold = 0;
        for (PlayerRecord.Economy.EscortCart c : carts) {
            gold += lineGoldOf(c.targetId);
        }
        int xdb = economy.escortSendXdb() * carts.size();
        int rank = raidAwardRank(rec);
        List<byte[]> items = awardItems(rec);
        List<byte[]> defends = defenceAwards(rec);
        rec.gold += gold;
        rec.guild.brotherCoin += xdb;
        markCartsAwarded(rec);
        rec.economy.majiuAwarded = true;
        if (u != null) {
            u.growth = Math.min(unionCfg.growCap(), u.growth + unionCfg.sendGrow() * carts.size());
        }
        players.save(rec);
        if (u != null) {
            world.saveUnions();
        }
        GameSession s = sessions.get(rec.account);
        boolean online = s != null && s.channel() != null && s.channel().isActive();
        if (online) {
            s.send(MsgIds.S2C_ATTRI_UPDATE, 0, dump.attri(3, rec.gold));
            s.send(MsgIds.S2C_UNION_PLAYER_RES, 0, dump.unionPlayerRes(rec));
            s.send(MsgIds.S2C_YUNBIAO_AWARD, 0, raidAwardBody(rec, items, defends));
            s.send(MsgIds.S2C_MAJIU_INFO, 0, dump.majiuInfo(majiuView(rec, u)));
        } else {
            mail.sendMajiuAwardMail(rec.account, gold, xdb);
        }
        log.info("{} majiu settle {} rank={} carts={} gold={} xdb={} online={}", rec.account, why,
                rank, carts.size(), gold, xdb, Boolean.valueOf(online));
    }

    // ------------------------------------------------------------------ 公会战 PvP（1549–1568 → 1946–1965）

    /**
     * 公会战报名窗口是否开放。口径与 APK 客户端相位机**逐条对齐**：
     *
     * <p>客户端 {@code GongHuiZhanJoinUI.OnQueDing}（APK {@code Client\MobileGameDemo\GongHuiZhanJoinUI.cs:100-133}）
     * 只拒绝两个相位：{@code ᜃ} JoinLimit → 100773「报名暂未开始」、{@code ᜂ} InProgress → 100774
     * 「公会战报名已截止」；{@code UnionWarRoomEnter.OnClickUnionWarJoin}（同目录
     * {@code UnionWarRoomEnter.cs:143-175}）额外用 100726「公会战报名暂未开始」拦 JoinLimit。
     * 相位定义见 APK {@code Client\᝴.cs:34-91}（{@code ᜀ()}）：战斗日
     * 00:00–19:00 Preparing、19:00–20:00 InProgress、20:00–21:00 JoinLimit、21:00–24:00 Idle；
     * **非战斗日全天 Preparing**（{@code GetNearestUpperInfo} 只遍历启用行，周二取到的是周三那一行）。
     *
     * <p>⇒ 可报名集合 = 「非战斗日全天」∪「战斗日 [00:00,19:00) ∪ [21:00,24:00)」。
     * 出厂表（{@code tables\UnionPvPTime.txt}：周一/三/六 19:00 / 3600s / +3600s）
     * 即战斗日 [19:00, 21:00) 关闭。
     *
     * <p>改前（10-05 之前）漏了「非战斗日全天开放」这一半：非战斗日的 19:00–21:00 服务端会拒绝，
     * 而客户端此时相位是 Preparing、面板照常可点，报名被静默吞掉（客户端 1549 发完即
     * {@code Close()}，失败只能靠 1550→1947 才发现）。
     */
    boolean inPvpEnrollWindow(int sec) {
        return inPvpEnrollWindow(sec, GameTime.today());
    }

    /** {@link #inPvpEnrollWindow(int)} 的指定日期版本（测试用：可分别验证战斗日与非战斗日）。 */
    boolean inPvpEnrollWindow(int sec, java.time.LocalDate date) {
        if (!isPvpBattleDay(date)) {
            // 非战斗日：客户端相位恒 Preparing，全天可报名（为下一场战斗日报名）。
            return true;
        }
        int start = unionCfg.pvpStartHour() * 3600 + unionCfg.pvpStartMinute() * 60;
        int close = start + unionCfg.pvpDurationSec() + unionCfg.pvpEnrollOpenAfterSec();
        if (close >= 86400) {
            int wrapped = close - 86400;
            return sec < start && sec >= wrapped;
        }
        return sec < start || sec >= close;
    }

    /**
     * 1549 RequestEnrollUnionPvP（空体）→ 1946。
     *
     * <p>1946 的 {@code CCMsgRequestEnrollUnionPvP_Ret} 在 APK 里**不存在**，客户端 handler
     * （APK Client\ᝁ.cs:6295）也完全不反序列化，只能回纯空包；报名成败没有回包可表达，
     * 客户端 GongHuiZhanJoinUI.cs:132 发完立即 Close()（乐观关闭），失败只体现在下次 1550 的 1947。
     */
    public void onPvpEnroll(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        // 客户端两道硬闸门都要求 job >= 2（长老/会长）：房间入口 UnionWarRoomEnter.cs:146、
        // 确认钮 GongHuiZhanJoinUI.cs:103（不足弹 100765）⇒ 原版客户端里普通成员发不出 1549。
        // 改包可发，而 1946 是纯空包、客户端察觉不到 ⇒ 服务端必须补同一道授权门，
        // 否则 member 能花公会晶石报名并钉住对手（用户 m21142 复核）。
        boolean canEnroll = u != null && rec.guildJobCode() >= 2;
        if (canEnroll && inPvpEnrollWindow(secOfDay())) {
            ensurePvpDay(u);
            int cost = unionCfg.pvpEnrollCrystal();
            if (!u.pvpEnrolled && u.crystal >= cost) {
                u.crystal -= cost;
                u.pvpEnrolled = true;
                // 报名目标战斗日：客户端 100727 显示的就是它（见 pvpEnrollDay 的说明）。
                u.pvpEnrollDay = targetPvpBattleDay(secOfDay());
                // 报名即钉住对手（用户 m19006 #9：配对规则按「随机」；见 rivalUnion 的说明），
                // 当天不再漂移，结算时该对手解散才能判成 15「对方公会解散」。
                WorldStore.UnionRecord rival = rivalUnion(u);
                u.pvpRivalId = rival == null ? "" : rival.id;
                world.saveUnions();
                pushUnion(u, MsgIds.S2C_UNION_ATTRI_UPDATE, dump.unionAttriUpdate(5, u.crystal, ""), 0);
                log.info("union {} pvp enroll cost={} target={} rival={}", u.id, cost, u.pvpEnrollDay,
                        u.pvpRivalId);
            }
        } else if (u != null) {
            if (rec.guildJobCode() < 2) {
                log.info("union {} pvp enroll rejected: job={} < 2（只有长老/会长可报名）", u.id,
                        Integer.valueOf(rec.guildJobCode()));
            } else {
                log.info("union {} pvp enroll rejected: outside enroll window (secOfDay={})", u.id,
                        Integer.valueOf(secOfDay()));
            }
        }
        session.send(MsgIds.S2C_UNION_PVP_ENROLL_RET, pkt, dump.pvpEmptyRet());
    }

    /** 1550 RequestIsEnrollUnionPvP（空体）→ 1947 CCMsgRequestIsEnrollUnionPvP_Ret{1 IsEnRolled}。 */
    public void onPvpIsEnroll(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u != null) {
            ensurePvpDay(u);
        }
        session.send(MsgIds.S2C_UNION_PVP_IS_ENROLL_RET, pkt,
                dump.pvpIsEnrollRet(u != null && u.pvpEnrolled));
    }

    /** 1551 RequestDefPointDefFormation{1 DefPointIndex} → 1948。 */
    public void onPvpDefFormation(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int point = Pb.read(pkt.body).getInt(1, 0);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        session.send(MsgIds.S2C_UNION_PVP_DEF_FORMATION_RET, pkt,
                dump.pvpDefPointFormation(point, formationsOf(u, point)));
    }

    /**
     * 1552 UpdateOnePointDefFormation{1 DefPointIndex,2 DefFormationIndex,3 DefPlayerGuid,4 DefFormationType}
     * → 1949（空体）。下阵由客户端发 {@code DefPlayerGuid = 0}（{@code GongHuiZhanJuDianBuFangUI.cs:207}
     * 写死 type 24 + guid 0，见 {@code :185-210 OnDelClick}）；上阵时请求体不带武将列表，
     * 服务端按 {@code DefPlayerGuid} 找到该成员，再用 {@code DefFormationType}(24..28) 取他那套
     * 防守预设的武将组装 {@code wjJobAndBriefInfo}。首格索引为 0，每个据点最多
     * {@link #PVP_DEF_FORMATION_MAX} 支队伍。
     */
    public void onPvpUpdateDefFormation(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int point = f.getInt(1, 0);
        int formationIndex = f.getInt(2, 0);
        int playerGuid = f.getInt(3, 0);
        int formationType = f.getInt(4, 0);
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        // 只接受表里真实存在的据点号：原实现允许任意 point 建阵容（WorldStore.pvpFormationsOf 缺则新建并 put），
        // 会往存档里塞脏据点，之后 1950/1959/1960 全量遍历时把它当真实据点渲染。
        // formationIndex 必须允许 0：客户端从 {0,1,2} 里取空位（GongHuiZhanJuDianBuFangUI.cs:196/257-276），
        // 首个可用索引就是 0，原实现 `> 0` 会把玩家加第一支队伍的操作静默丢掉（却仍回 1949 成功）。
        if (u != null && unionCfg.pvpPointIds().contains(Integer.valueOf(point))
                && formationIndex >= 0) {
            List<WorldStore.PvpFormation> list = u.pvpFormationsOf(point);
            WorldStore.PvpFormation target = u.pvpFindFormation(point, formationIndex);
            // 职位闸门（用户 m24587 #3）：APK 的布防界面只对会长/副会长开放，普通成员只能动**自己**的
            // 阵容 —— 上阵只能放自己的预设，下阵只能撤自己的队伍。改前任意成员可移动/删除任意据点的
            // 任意人的防守阵容（`detachPresetFromOtherPoints` 还能把别人的预设整体搬走）。
            boolean selfOnly = !isOwnerOrElder(rec);
            if (selfOnly && (playerGuid > 0
                    ? playerGuid != rec.playerId
                    : (target == null || target.playerId != rec.playerId))) {
                log.info("{} union pvp def formation rejected: job {} cannot touch other member's formation",
                        rec.account, rec.guildJobCode());
            } else if (playerGuid <= 0) {
                // 下阵的唯一判据是 DefPlayerGuid <= 0。原实现把 `formationType == 24` 也当下阵，
                // 而上阵时 DefFormationType 恰恰可以是 24（部署「我的队伍 1」）⇒ 部署预设 1 会被当成
                // 下阵删掉，还回 1949 成功。客户端下阵写死 guid 0，上阵写真实 playerId。
                if (target != null) {
                    list.remove(target);
                }
            } else if (target == null && aliveFormations(list).size() >= PVP_DEF_FORMATION_MAX) {
                // 每据点最多 2 支防守队伍：客户端只本地拦（MAX_FORMATION_COUNT=2 + 100780），
                // 服务端不拦就能塞进任意多支，1960/1961 会照单渲染。上限按**存活**队伍数算，
                // 否则打掉 2 支后该据点再也补不上人。
                log.info("{} union pvp def formation rejected: point {} already has {} formations",
                        rec.account, point, list.size());
            } else {
                // 换点部署 = 移动该预设：先把它在别处的落点摘掉，否则同一支队会同时出现在两个据点。
                detachPresetFromOtherPoints(u, playerGuid, formationType, point, formationIndex);
                if (target == null) {
                    target = new WorldStore.PvpFormation();
                    target.point = point;
                    target.formationIndex = formationIndex;
                    list.add(target);
                }
                // 复用被打掉的格位时要把骷髅状态清掉，否则重新上阵的队伍在 1960 里仍是「已阵亡」。
                target.killerName = "";
                target.isFighting = false;
                PlayerRecord owner = players.findByPlayerId(playerGuid);
                target.playerId = playerGuid;
                target.account = owner == null ? "" : owner.account;
                target.playerName = owner == null ? "" : owner.roleName;
                target.playerLevel = owner == null ? 1 : owner.level;
                target.playerResId = owner == null ? 0 : owner.mainHeroIndex;
                target.formationType = formationType;
                // 必须按请求里的 DefFormationType 取该成员那一套预设：原实现忽略它、直接用
                // 「主将 + 前几个英雄」，于是会长选了「我的队伍 3」也会下发成另一支队，
                // 1948/1950/1960/1961 的武将与战力全错。
                target.wjs = presetWjBriefs(owner, formationType);
                target.fightPower = fightPowerOf(owner, target.wjs);
            }
            world.saveUnions();
        }
        session.send(MsgIds.S2C_UNION_PVP_UPDATE_DEF_FORMATION_RET, pkt, dump.pvpEmptyRet());
    }

    /**
     * 1553 RequestAllUnionPvPDefFormation（空体）→ 1950 CCMsgRequestAllUnionPvPDefFormation_Ret{1 defFormation}。
     * 下发的是**候选**队伍（全公会成员的防守预设，未上阵的 DefPointsIndex = -1），见 {@link #pvpCandidates}。
     */
    public void onPvpAllDefFormation(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        session.send(MsgIds.S2C_UNION_PVP_ALL_DEF_FORMATION_RET, pkt, dump.pvpAllDefFormations(pvpCandidates(u)));
    }

    /** 1554 RequestUnionFightPowerRankList（空体）→ 1951 CCMsgUnionRankList（按公会总战力）。 */
    public void onPvpFightPowerRank(GameSession session, GamePacket pkt) {
        sendUnionRank(session, pkt, MsgIds.S2C_UNION_PVP_FIGHT_POWER_RANK_RET, false);
    }

    /** 1555 RequestUnionGrowValueRankList（空体）→ 1952 CCMsgUnionRankList（按公会成长值）。 */
    public void onPvpGrowValueRank(GameSession session, GamePacket pkt) {
        sendUnionRank(session, pkt, MsgIds.S2C_UNION_PVP_GROW_VALUE_RANK_RET, true);
    }

    /**
     * 1556 RequestUnionPvPFightRankList（空体）→ 1953 CCMsgUnionPvPFightRankList（按胜场）。
     *
     * <p>客户端契约（{@code RankListMainDialog.cs}）：{@code myUnionRank == 0} 显示 Str 100462
     * 「未上榜」、{@code != 0} 显示 100464「{0}胜{1}负」（:1041-1052，:1053-1059 名次标签只在
     * {@code != 0} 时填数字）；{@code rank} 是**独立字段**，允许并列/跳号（:1064 只在相邻 rank
     * 差值 > 1 时才画分隔线）；前 3 行按**下标**取奖杯（:1083-1088 {@code if (k < 3)}）⇒ 列表必须按
     * rank 非降序且前 3 行真是第 1/2/3 名。原实现只按胜场降序（同胜场用存档序，无意义）、rank 恒
     * {@code i+1}、且无战绩公会也上榜 ⇒ {@code myUnionRank} 永不可能为 0。
     *
     * <p>排序规则（用户 m19006 #4 拍板）：**胜场数降序 → 公会总战力降序 → guid**。
     * 表里没有排序规则；总战力口径与 1951/1554 一致（{@link #unionRankValue}(u,false) =
     * 成员 {@code fightPower} 求和），同胜场同战力时按 guid 保证输出稳定（名次允许并列/跳号，
     * 客户端 {@code RankListMainDialog.cs:1064} 只在相邻 rank 差值 > 1 时画分隔线）。
     */
    public void onPvpFightRank(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        WorldStore.UnionRecord mine = rec == null ? null : world.findUnion(rec.guild.id);
        List<WorldStore.UnionRecord> unions = new ArrayList<>();
        for (WorldStore.UnionRecord x : world.unions()) {
            if (x.pvpWinTimes + x.pvpFailTimes > 0) {
                unions.add(x);
            }
        }
        unions.sort(Comparator.comparingInt((WorldStore.UnionRecord x) -> -x.pvpWinTimes)
                .thenComparingInt(x -> -unionRankValue(x, false))
                .thenComparing(x -> x.id));
        List<byte[]> items = new ArrayList<>();
        int myRank = 0;
        for (int i = 0; i < unions.size(); i++) {
            WorldStore.UnionRecord u = unions.get(i);
            items.add(dump.pvpFightRankItem(u.id, i + 1, u.name, u.icon, u.level, u.pvpWinTimes, u.pvpFailTimes));
            if (mine != null && u == mine) {
                myRank = i + 1;
            }
        }
        session.send(MsgIds.S2C_UNION_PVP_FIGHT_RANK_RET, pkt,
                dump.pvpFightRankList(myRank, mine == null ? 0 : mine.pvpWinTimes,
                        mine == null ? 0 : mine.pvpFailTimes, items));
    }

    /** 1557 UnionBriefInfo_FightPower{1 guid} → 1954 CCMsgUnionBriefInfo。 */
    public void onPvpUnionBriefFightPower(GameSession session, GamePacket pkt) {
        sendUnionBrief(session, pkt, MsgIds.S2C_UNION_PVP_BRIEF_FIGHT_POWER_RET, false);
    }

    /** 1558 UnionBriefInfo_GrowValue{1 guid} → 1955 CCMsgUnionBriefInfo。 */
    public void onPvpUnionBriefGrowValue(GameSession session, GamePacket pkt) {
        sendUnionBrief(session, pkt, MsgIds.S2C_UNION_PVP_BRIEF_GROW_VALUE_RET, true);
    }

    /**
     * 1559 UnionPvPFightRank_FightRecord{1 UnionGuid} → 1956。客户端只在
     * {@code FightRecord.Count != 0} 时开战报 Tips（APK PlayGameState.cs:5017），无记录时发空列表即可。
     */
    public void onPvpFightRecord(GameSession session, GamePacket pkt) {
        String guid = Pb.read(pkt.body).getString(1);
        WorldStore.UnionRecord u = guid == null || guid.isEmpty() ? null : world.findUnion(guid);
        session.send(MsgIds.S2C_UNION_PVP_FIGHT_RECORD_RET, pkt,
                dump.pvpFightRecords(u == null ? new ArrayList<WorldStore.PvpFightRecord>() : u.pvpRecords));
    }

    /** 1560 MyUnionPvPDefPointBriefInfo（空体）→ 1957 CCMsgRequestMyUnionPvPDefPointBriefInfo_Ret{1 onePointTotalFightPower}。 */
    public void onPvpMyDefPointBrief(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        List<byte[]> points = new ArrayList<>();
        for (Integer id : unionCfg.pvpPointIds()) {
            int point = id.intValue();
            int power = 0;
            boolean mine = false;
            for (WorldStore.PvpFormation f : formationsOf(u, point)) {
                if (f.playerId == rec.playerId) {
                    mine = true;
                }
                // 战力只算存活阵容（打掉的留在列表里只为画骷髅，不参与战力/可攻判定）。
                if (isAlive(f)) {
                    power += f.fightPower;
                }
            }
            points.add(dump.pvpPointTotalFightPower(point, power, mine));
        }
        session.send(MsgIds.S2C_UNION_PVP_MY_DEF_POINT_BRIEF_RET, pkt, dump.pvpMyDefPointBrief(points));
    }

    /**
     * 1561 FightTargetUnionDefPointBriefInfo（空体）→ 1958。无对手时 {@code targetUnionName=""}，
     * 客户端会回发 1562（APK PlayGameState.cs:6359）。
     */
    public void onPvpTargetDefPointBrief(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord mine = world.findUnion(rec.guild.id);
        WorldStore.UnionRecord rival = rivalUnion(mine);
        List<byte[]> points = new ArrayList<>();
        for (Integer id : unionCfg.pvpPointIds()) {
            int point = id.intValue();
            int power = 0;
            for (WorldStore.PvpFormation f : formationsOf(rival, point)) {
                if (isAlive(f)) {
                    power += f.fightPower;
                }
            }
            boolean attacked = mine != null && mine.pvpCapturedPoints.contains(id);
            boolean canAttack = !attacked && canAttackPoint(mine, point);
            points.add(dump.pvpTargetPointBrief(point, attacked, power, canAttack));
        }
        session.send(MsgIds.S2C_UNION_PVP_TARGET_DEF_POINT_BRIEF_RET, pkt,
                dump.pvpTargetDefPointBrief(capturedCount(mine),
                        selfAttackedPoints(mine, rival), points,
                        rival == null ? "" : rival.name));
    }

    /** 1562 MyUnionPvPFightLeftDefFormation（空体）→ 1959 CCMsgRequestMyUnionPvPDefPointLeftFormationCnt_Ret。 */
    public void onPvpLeftDefFormation(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord mine = world.findUnion(rec.guild.id);
        WorldStore.UnionRecord rival = rivalUnion(mine);
        List<byte[]> points = new ArrayList<>();
        for (Integer id : unionCfg.pvpPointIds()) {
            int point = id.intValue();
            // leftFormationCnt = 该据点**当前存活**的防守队伍数：客户端 GongHuiZhanJuDianBoard.cs:242-250
            // 在 ==0 时显示 LabelNoTeam（无队伍）、>0 时显示数字。打掉的阵容留在列表里（1960 画骷髅），
            // 故这里必须按存活数算，否则全灭据点仍显示「1」。
            int left = aliveFormations(formationsOf(mine, point)).size();
            // 1959 f3 IsBeAttacked = **我方**该据点已被敌方攻陷（防守队全灭），故必须读**对手**的已攻占列表。
            // 客户端唯一消费点 GongHuiZhanJuDianBoard.cs:240-255（1562→1959 是「我的据点」面板）：
            //   if (!isBeAttacked) left==0 → LabelNoTeam（无队伍）; left>0 → 显示剩余队伍数;
            //   else → LabelAllDead（全员阵亡）。
            // 改前错用 mine.pvpCapturedPoints（那是「我方攻下的对手据点」，1561→1958 的对手面板才用它，
            // 见上面 :2538，那处是对的）⇒ ①自己攻下对手 0 号点后，自己的 0 号点显示「全员阵亡」；
            // ②真正被对手打下的据点因 left==0 只显示「无队伍」，玩家看不到失守。
            boolean attacked = rival != null && rival.pvpCapturedPoints.contains(id);
            points.add(dump.pvpPointLeftFormation(point, left, attacked));
        }
        session.send(MsgIds.S2C_UNION_PVP_LEFT_DEF_FORMATION_RET, pkt,
                dump.pvpLeftDefFormation(capturedCount(mine),
                        selfAttackedPoints(mine, rival), points));
    }

    /** 1563 FightDefPointDetailInfoInWarStart{1 PointIndex,2 IsMySelf} → 1960。 */
    public void onPvpDefPointDetail(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int point = f.getInt(1, 0);
        boolean mine = f.getBool(2);
        // 用户 m24587 #6：补 point 校验。改前任意 point 都会被 `formationsOf` 当成「查不到就空列表」，
        // 1960 会照发一个不存在的据点号；只接受 UnionPvPDefPointsInfo.txt 里真实存在的据点。
        if (!unionCfg.pvpPointIds().contains(Integer.valueOf(point))) {
            log.info("{} union pvp def point detail rejected: unknown point {}", rec.account, point);
            return;
        }
        WorldStore.UnionRecord self = world.findUnion(rec.guild.id);
        WorldStore.UnionRecord u = mine ? self : rivalUnion(self);
        session.send(MsgIds.S2C_UNION_PVP_DEF_POINT_DETAIL_RET, pkt,
                dump.pvpDefPointDetail(point, mine, formationsOf(u, point)));
    }

    /**
     * 1564 FightUnionPvPFormation{1 PointIndex,2 FormationIndex} → 1961
     * {@code CCMsgUnionPvPRealTeamDetailInfo}。
     *
     * <p>**必须带 myTeam + targetTeam 两个子消息**：客户端 MatchPlayer.UnPackUnionPVP(null) /
     * MainPlayer.UnPackDataForMyTeam(null) / GongHuiZhanPvPController.UnPackExtraInfo 三处都直接取字段
     * （APK Client\ᝁ.cs:6404-6406），缺一个就 NRE。
     *
     * <p>用户 m24587 #4「1564 按 APK 逻辑推测真服逻辑」：APK 侧 1564 的唯一发送点是战斗开始时
     * （{@code EmBattleSystem.cs:2683-2687}，mType 29 公会战进攻），请求体就是「打哪个据点的哪支队伍」；
     * 客户端只对**自己的队伍**和**已阵亡**的队伍隐藏进攻按钮（{@code GongHuiZhanJuDianAttackUI.cs:150-153}
     * {@code IsMySelf || KillerName != ""}），可攻击的据点由服务端经 1958 f5 {@code isCanAttack}
     * 下发（本类 {@link #onPvpTargetDefPointBrief}：{@code !attacked && canAttackPoint}）。据此真服逻辑 =
     * 「校验据点可攻占 + 该防守队伍存在且存活 → 标记攻击中、记下本次进攻目标、回双方真实队伍」；
     * 1961 没有失败位（{@code CCMsgUnionPvPRealTeamDetailInfo} 只有 f1 myTeam/f2 targetTeam），
     * 不合法请求只能**静默不回**。改前不校验据点/队伍，{@code tf == null} 时还拿自己顶位回包 ⇒
     * 伪造包可打不可攻占的据点、打已阵亡的队伍，并刷 1963 的 attackerCnt。
     */
    public void onPvpFightFormation(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int point = f.getInt(1, 0);
        int formationIndex = f.getInt(2, 0);
        WorldStore.UnionRecord mine = world.findUnion(rec.guild.id);
        WorldStore.UnionRecord rival = rivalUnion(mine);
        if (mine == null || rival == null
                || mine.pvpCapturedPoints.contains(Integer.valueOf(point))
                || !canAttackPoint(mine, point)) {
            log.info("{} union pvp fight rejected: point {} not attackable", rec.account, point);
            return;
        }
        WorldStore.PvpFormation tf = rival.pvpFindFormation(point, formationIndex);
        if (tf == null || !isAlive(tf)) {
            log.info("{} union pvp fight rejected: point {} formation {} missing or dead",
                    rec.account, point, formationIndex);
            return;
        }
        // 1567 的请求体只有 IsWin，攻占判定必须靠这里记下的「打的是哪个据点的哪支防守部队」。
        rec.economy.lastPvpPoint = point;
        rec.economy.lastPvpFormation = formationIndex;
        // 1960 f8 IsFighting：客户端 GongHuiZhanJuDianAttackUI.cs:149/153 用它显示「攻击中」标记。
        // APK 只显示标记、**不禁**同队并发点击（1963 的 AttackerCnt 就是多人同时进攻的计数）⇒ 不拦 isFighting。
        tf.isFighting = true;
        PlayerRecord target = findByAccount(tf.account);
        // 防守方存档查不到时仍要回包（两个子消息都必须存在），用攻方存档顶位保证外层字段可解析；
        // 对手武将的养成/装备只在 target != null 时写（否则会把攻方装备错当对手装备）。
        PlayerRecord targetSkeleton = target != null ? target : rec;
        // f2 WJ 必须带：客户端按它建对手全队、按 job 给防守方五个 GUID 赋值（MatchPlayer.cs:192/219-236），
        // 只发 playerGuid 会让防守方 GUID 全空、开战建不出将。己方也带上，MainPlayer 用它同步武将属性。
        // 己方队伍取**公会战进攻阵容**（eFormationType 29 = FORMATION_TYPE_UNION_PVP_OFFENSE）：客户端进
        // 公会战场景时用的就是这套出战阵容（旧实现取「主将 + 武将表前几个」，与玩家实际出战队伍无关）。
        List<WorldStore.PvpWjBrief> selfWjs = presetWjBriefs(rec, PlayerRecord.FORMATION_UNION_PVP_OFFENSE);
        List<Integer> selfHps = new ArrayList<>();
        for (WorldStore.PvpWjBrief w : selfWjs) {
            selfHps.add(Integer.valueOf(deadWjHp(rec, w.index)));
        }
        List<WorldStore.PvpWjBrief> targetWjs = tf.wjs == null
                ? new ArrayList<WorldStore.PvpWjBrief>() : tf.wjs;
        List<Integer> targetHps = new ArrayList<>();
        for (WorldStore.PvpWjBrief w : targetWjs) {
            targetHps.add(Integer.valueOf(target == null ? 1 : deadWjHp(target, w.index)));
        }
        session.send(MsgIds.S2C_UNION_PVP_TARGET_POINT_FORMATION, pkt,
                dump.pvpRealTeamDetail(rec, targetSkeleton, selfWjs, selfHps, targetWjs, targetHps, target));
        rival.pvpAttackerCnt++;
        world.saveUnions();
        pushUnion(rival, MsgIds.S2C_UNION_PVP_POINT_ATTACKERS, dump.pvpPointAttackers(rival.pvpAttackerCnt), 0);
    }

    /**
     * 1566 RequestUnionPvPWJHP（空体）→ 1964 {@code CCMsgRequestUnionPvPDeadWJ_Ret{1 WJIndex,2 WJHP}}。
     * 两个 repeated **必须等长**（客户端按序号取值，长度不等会 ArgumentOutOfRangeException，APK Client\ᝁ.cs:6417）。
     */
    public void onPvpWjHp(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        List<Integer> indexes = new ArrayList<>();
        List<Integer> hps = new ArrayList<>();
        for (Integer id : unionCfg.pvpPointIds()) {
            for (WorldStore.PvpFormation f : formationsOf(u, id.intValue())) {
                for (WorldStore.PvpWjBrief w : f.wjs) {
                    if (indexes.contains(Integer.valueOf(w.index))) {
                        continue;
                    }
                    indexes.add(Integer.valueOf(w.index));
                    hps.add(Integer.valueOf(deadWjHp(rec, w.index)));
                }
            }
        }
        session.send(MsgIds.S2C_UNION_PVP_WJ_HP_RET, pkt, dump.pvpDeadWj(indexes, hps));
    }

    /**
     * 据点 {@code point} 是否**当前可攻占**：自身未攻占，且前置据点已被本公会攻占
     * （根据点 0 无前置，始终可攻）。前置关系来自 {@code UnionPvPDefPointsInfo.txt} 第 3 列
     * 「下一个据点」的反查，与客户端 {@code UnionPvPDefPointsInfoMgr.GetPrevPointIndex} 同源。
     *
     * <p>改前只把「表序第一个未攻占点」标为可攻占，客户端在既非已攻占又不可攻占时会弹
     * 100744「先攻占 {0}」（{@code GongHuiZhanJuDianBoard.cs:77-88}），分叉点（3/4、5/6、7/8）
     * 永远显示成灰色锁定。
     */
    private boolean canAttackPoint(WorldStore.UnionRecord u, int point) {
        if (u == null) {
            return false;
        }
        Integer prev = unionCfg.pvpPrevPoint(point);
        return prev == null || u.pvpCapturedPoints.contains(prev);
    }

    /**
     * 1567 FightUnionPvPResult{1 IsWin} → 1965 + **单场遭遇战**奖励。
     *
     * <p>奖励口径按 {@code UnionPvP.txt} 的措辞分两层：「个人打下单个部队数获得兄弟币 50 / 物品 UFTS1」
     * 是**每打掉一支防守部队**就发的（本方法）；而「胜利个人获得的兄弟币**基础**奖励 1000 / 物品 UFTS2」
     * 与「胜利获得的公会成长值 50000 / 公会晶石 1500」是**整场公会战**的奖励，由
     * {@link #settlePvpBattle} 在时段结束后结算（个人部分走系统邮件 10–16，公会成长值/晶石直接加到公会）。
     * 若把 50000 成长值按遭遇战发，40 人公会打一晚就顶到成长值上限，显然不是 APK 口径。
     *
     * <p>攻占判定：1567 的请求体只有 {@code IsWin}，所以用 1564 记下的「本次进攻据点/阵容」
     * （{@code rec.economy.lastPvpPoint/lastPvpFormation}）。胜利即打掉对手在该据点的一支防守部队；
     * 该据点的防守部队被清空时**才**把据点记为我方攻占，并从对手的已攻占列表里摘除。
     * 改前「赢一场就按表序占下一个据点」，连刷可 0→9 全占，且与客户端高亮的可攻占点不一致。
     *
     * <p>1965 的 f2/f3（UnionGrow/UnionJinShi）客户端 `GongHuiZhanEndUI.cs:73-74` 直接 ToString 显示、
     * 不加 "+"，故下发公会**当前**成长值/晶石；f4 `SelfAttackedDefPoints` = **我方**已攻占据点数
     * （`capturedCount(mine)`，客户端 `GongHuiZhanEndUI.cs:63` 拼 `Code.txt:100760`「我方攻陷： 」），
     * f5 `TargetAttackedDefPoints` = **敌方**已攻占据点数（`capturedCount(rival)`，`:64` 拼 100761「敌方攻陷： 」）。
     */
    public void onPvpFightResult(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        boolean win = Pb.read(pkt.body).getBool(1);
        WorldStore.UnionRecord mine = world.findUnion(rec.guild.id);
        WorldStore.UnionRecord rival = rivalUnion(mine);
        // 本场结束：清掉被打阵容的「攻击中」标记（1960 f8）。胜方走 captureOnWin 也会清一次。
        if (rival != null && rec.economy.lastPvpFormation >= 0) {
            WorldStore.PvpFormation fought = rival.pvpFindFormation(rec.economy.lastPvpPoint,
                    rec.economy.lastPvpFormation);
            if (fought != null) {
                fought.isFighting = false;
            }
        }
        int brother = win ? unionCfg.pvpUnitBrother() : 0;
        String goods = win ? unionCfg.pvpUnitGoods() : null;
        if (mine != null) {
            ensurePvpDay(mine);
            if (win) {
                mine.pvpWinTimes++;
                captureOnWin(mine, rival, rec);
            } else {
                mine.pvpFailTimes++;
            }
            WorldStore.PvpFightRecord row = new WorldStore.PvpFightRecord();
            row.myName = mine.name;
            row.myIcon = mine.icon;
            row.myLevel = mine.level;
            row.targetName = rival == null ? "" : rival.name;
            row.targetIcon = rival == null ? "" : rival.icon;
            row.targetLevel = rival == null ? 0 : rival.level;
            row.win = win;
            mine.pvpRecords.add(row);
            // 战报只留最新 3 条：客户端 UnionBattleRecordTips.cs:74/81/88 只实现了 count 1/2/3
            // 三个分支，:99 直接 transform.FindChild(第 count 条子节点)，第 4 条起 transform 为 null
            // ⇒ NullReferenceException；PlayGameState.cs:5017 只挡 Count == 0。
            while (mine.pvpRecords.size() > PVP_RECORD_MAX) {
                mine.pvpRecords.remove(0);
            }
            world.saveUnions();
        }
        if (brother > 0) {
            rec.guild.brotherCoin += brother;
        }
        if (goods != null && !goods.isEmpty()) {
            progress.addGoods(rec, goods, 1);
        }
        players.save(rec);
        session.send(MsgIds.S2C_UNION_PLAYER_RES, pkt, dump.unionPlayerRes(rec));
        session.send(MsgIds.S2C_UNION_PVP_FIGHT_RECORD_PUSH, pkt,
                dump.pvpFightRecordToClient(win,
                        mine == null ? 0 : mine.growth,
                        mine == null ? 0 : mine.crystal,
                        mine == null ? 0 : capturedCount(mine),
                        mine == null ? 0 : selfAttackedPoints(mine, rival),
                        rival == null ? "" : rival.name));
        log.info("{} union pvp fight win={} unitBrother={} unitGoods={}", rec.account, win, brother, goods);
    }

    /** 1568 UnionPvPFightRecord（空体）→ 1965（客户端只在 phase==JoinLimit 分支里拉战报）。 */
    public void onPvpFightRecordList(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord mine = world.findUnion(rec.guild.id);
        WorldStore.UnionRecord rival = rivalUnion(mine);
        // 整场胜负按「谁占的据点更多」判定（据点攻防的天然胜负条件）；改前硬编码 false，
        // 客户端 GongHuiZhanEndUI.cs:59-60 用 IsWin 切胜负贴图，永远显示失败。
        boolean win = capturedCount(mine) > capturedCount(rival);
        session.send(MsgIds.S2C_UNION_PVP_FIGHT_RECORD_PUSH, pkt,
                dump.pvpFightRecordToClient(win,
                        mine == null ? 0 : mine.growth,
                        mine == null ? 0 : mine.crystal,
                        mine == null ? 0 : capturedCount(mine),
                        mine == null ? 0 : selfAttackedPoints(mine, rival),
                        rival == null ? "" : rival.name));
    }

    // ------------------------------------------------------------------ 公会战内部

    /**
     * 跨日：**先**把昨天那一场公会战结算掉（服务器 20:00 没跑 / 整夜停机时的兜底，顺序不能反），
     * 再滚天清当天统计。改前只清 {@code pvpEnrolled}/{@code pvpAttackerCnt}，
     * 攻占点与胜负场次会跨天累积（真 bug）。
     *
     * <p>两个判定是 10-05 补的（此前会白送一整份公会战奖励，见 docs「再核轮补充 6」）：
     * ①**只有「报名正是冲着昨天去的」那一场才补结算** —— 非战斗日（如周二）报名是给下一场
     * 战斗日（周三）报的，昨天压根没打过；战斗日 21:00 后的报名同理。
     * ②滚天后若 {@link WorldStore.UnionRecord#pvpEnrollDay} 仍在未来，**报名延续**到那一场
     * （客户端 100727 已向玩家承诺「公会战将于 周三19:00 开启」），并重钉该场对手。
     */
    private void ensurePvpDay(WorldStore.UnionRecord u) {
        u.ensure();
        String day = PlayerDumpService.now().substring(0, 10);
        if (day.equals(u.pvpDay)) {
            return;
        }
        if (!u.pvpDay.isEmpty() && !u.pvpDay.equals(u.pvpSettledDay) && u.pvpEnrolled
                && u.pvpDay.equals(u.pvpEnrollDay)) {
            settlePvpBattle(u, u.pvpDay, "rollover");
        }
        u.pvpDay = day;
        boolean carry = u.pvpEnrolled && u.pvpEnrollDay != null && !u.pvpEnrollDay.isEmpty()
                && u.pvpEnrollDay.compareTo(day) >= 0;
        u.pvpEnrolled = carry;
        u.pvpAttackerCnt = 0;
        u.pvpSelfAttackedPoints = 0;
        u.pvpTargetAttackedPoints = 0;
        u.pvpWinTimes = 0;
        u.pvpFailTimes = 0;
        u.pvpRivalId = "";
        if (!carry) {
            u.pvpEnrollDay = "";
        } else {
            // 报名跨到了新的一天：对手按新的一天重新随机钉住（用户 m19006 #9；结算的
            // 15「对方公会解散」看的就是这个 id）。
            WorldStore.UnionRecord rival = rivalUnion(u);
            u.pvpRivalId = rival == null ? "" : rival.id;
        }
        if (u.pvpCapturedPoints != null) {
            u.pvpCapturedPoints.clear();
        }
        if (u.pvpRecords != null) {
            u.pvpRecords.clear();
        }
        world.saveUnions();
    }

    /**
     * 全服跨日：把每个公会的公会战状态滚到当天（{@link #ensurePvpDay}）。
     *
     * <p>跨日刷新原先只挂在 1549/1550/1567 三个入口上，而客户端进入公会战场景先发的是
     * 1560/1561/1562/1568（{@code GongHuiZhanBaseSystem.cs:198/202/206}），其回包
     * 1957/1958/1959 直接渲染 {@code pvpCapturedPoints}/{@code pvpWinTimes} ⇒ 次日棋盘与排行
     * 仍是昨天的数据（昨天那场也要等有人发 1549/1550/1567 才补结算）。这里由 0 点任务与启动
     * 钩子统一滚天，任何入口读到的都是当天状态。
     */
    public void rolloverPvpDays() {
        for (WorldStore.UnionRecord u : world.unions()) {
            ensurePvpDay(u);
        }
    }

    /**
     * 分钟级钩子：公会战时段（{@code UnionPvPTime.txt} 每天 19:00 + 3600s = 20:00）结束后，
     * 结算当天已报名的公会。按 {@code u.pvpSettledDay} 去重，只结算 {@code pvpDay == 今天} 的公会。
     */
    public void settlePvpBattles(String why) {
        if (!pvpBattleDay() || !pvpWindowOver()) {
            return;
        }
        String day = PlayerDumpService.now().substring(0, 10);
        for (WorldStore.UnionRecord u : world.unions()) {
            if (day.equals(u.pvpSettledDay) || !day.equals(u.pvpDay)) {
                continue;
            }
            settlePvpBattle(u, day, why);
        }
    }

    /**
     * 公会战时段是否已结束：当日秒数 ≥ {@code UnionPvPTime.txt} 的开始时刻 + 持续时间。
     * 走 {@link #secOfDay()}（而不是直接读 {@code LocalTime.now}），这样与押镖/报名窗口
     * 一样能被 {@code clockSecOverride} 钉住、调度入口 {@link #settlePvpBattles(String)}
     * 可测；生产环境无 override 时等价于本地时钟。
     */
    private boolean pvpWindowOver() {
        int startSec = unionCfg.pvpStartHour() * 3600 + unionCfg.pvpStartMinute() * 60;
        return secOfDay() >= startSec + unionCfg.pvpDurationSec();
    }

    /**
     * 今天是否 {@code UnionPvPTime.txt} 配置的开战日（第 1 列星期，**0=周日**）。
     *
     * <p>只有首列为 {@code #} 的行生效（客户端 {@code UnionPvPTimeInfoMgr.cs:25} 的启用开关），
     * 出厂表是 1/3/6 = 周一/三/六。改前把 7 行全收，导致本方法恒 true、公会战每天都结算。
     * {@code pvpDays} 为空（表缺失）时返回 true，保持「没有配置就不限制」的旧兜底语义。
     */
    private boolean pvpBattleDay() {
        return isPvpBattleDay(GameTime.today());
    }

    /** {@link #pvpBattleDay()} 的指定日期版本（跨日判定要问「昨天/明天是不是战斗日」）。 */
    boolean isPvpBattleDay(java.time.LocalDate date) {
        Set<Integer> days = unionCfg.pvpDays();
        if (days.isEmpty()) {
            return true;
        }
        // java.time：周一=1..周日=7；表：周日=0..周六=6
        return days.contains(Integer.valueOf(date.getDayOfWeek().getValue() % 7));
    }

    /**
     * 本次报名冲着哪一场战斗日去的（yyyy-MM-dd），对齐客户端 100727 的显示口径：
     * 客户端把 {@code UnionPvPTimeInfoMgr.GetNearestUpperInfo(now)} 那一行（APK
     * {@code UnionPvPTimeInfoMgr.cs:56-74}，只遍历首列为 {@code #} 的启用行、取星期差最小者，
     * 当天算 0 所以**当天优先**）当成「下一场」并显示它的周几与开战时刻。
     *
     * <p>⇒ 战斗日 19:00 前报名 = 报当天；其余（非战斗日全天、战斗日 21:00 后进入 Idle）
     * = 报下一个启用战斗日。{@code pvpDays} 为空时每天都算战斗日，退化成「19:00 前报当天、
     * 之后报次日」。
     */
    String targetPvpBattleDay(int sec) {
        java.time.LocalDate today = GameTime.today();
        int start = unionCfg.pvpStartHour() * 3600 + unionCfg.pvpStartMinute() * 60;
        if (isPvpBattleDay(today) && sec < start) {
            return today.toString();
        }
        for (int i = 1; i <= 7; i++) {
            java.time.LocalDate next = today.plusDays(i);
            if (isPvpBattleDay(next)) {
                return next.toString();
            }
        }
        return today.toString();
    }

    /**
     * 结算一场公会战：判定邮件模板 10–16（攻陷对方基地胜 / 基地失守败 / 据点更多胜 / 更少败 /
     * 据点相同平 / 对方公会解散胜 / 幸运轮空胜），公会成长值与晶石**直接加到公会**
     * （邮件只能带个人奖励），每个成员的兄弟币与物品走系统邮件。
     *
     * <p>{@code UnionPvP.txt} 没有平局档位（第 14 行正文说「无人获得本次公会战的胜利」但仍有奖励），
     * 故平局暂按**败方**档发放，已记入 docs 待拍板。
     *
     * <p>包级可见：由 {@link #settlePvpBattles}（20:00 分钟钩子）与 {@link #ensurePvpDay}
     * （跨日兜底）调用，测试按「战斗日」直接调用以绕开真实时钟。
     */
    void settlePvpBattle(WorldStore.UnionRecord u, String battleDay, String why) {
        if (u == null || battleDay == null || battleDay.isEmpty() || battleDay.equals(u.pvpSettledDay)) {
            return;
        }
        u.ensure();
        // 报名不是冲着这一天去的（非战斗日/战斗日 21:00 后的报名都是给**下一场**报的）
        // ⇒ 这一天压根没打过，绝不能结算。空 pvpEnrollDay 视为旧存档无凭据，按旧口径放行。
        if (!u.pvpEnrollDay.isEmpty() && !battleDay.equals(u.pvpEnrollDay)) {
            return;
        }
        u.pvpSettledDay = battleDay;
        if (!u.pvpEnrolled) {
            world.saveUnions();
            return;
        }
        WorldStore.UnionRecord rival = rivalUnion(u);
        String rivalName = rival == null ? "" : rival.name;
        boolean win;
        int mailType;
        if (rival == null) {
            // 报名时钉住的对手已解散 → 15；本来就没人可打（全服只有自己一个公会）→ 16 轮空
            mailType = (u.pvpRivalId != null && !u.pvpRivalId.isEmpty())
                    ? MailService.MAIL_TYPE_UNION_PVP_RIVAL_DISMISS
                    : MailService.MAIL_TYPE_UNION_PVP_BYE;
            win = true;
        } else {
            Integer finalPoint = finalPvpPoint();
            boolean mineBase = finalPoint != null && u.pvpCapturedPoints.contains(finalPoint);
            boolean rivalBase = finalPoint != null && rival.pvpCapturedPoints.contains(finalPoint);
            if (mineBase) {
                mailType = MailService.MAIL_TYPE_UNION_PVP_BASE_WIN;
                win = true;
            } else if (rivalBase) {
                mailType = MailService.MAIL_TYPE_UNION_PVP_BASE_LOSE;
                win = false;
            } else if (u.pvpCapturedPoints.size() > rival.pvpCapturedPoints.size()) {
                mailType = MailService.MAIL_TYPE_UNION_PVP_POINT_WIN;
                win = true;
            } else if (u.pvpCapturedPoints.size() < rival.pvpCapturedPoints.size()) {
                mailType = MailService.MAIL_TYPE_UNION_PVP_POINT_LOSE;
                win = false;
            } else {
                // 平局 = 双方攻占据点数相同（邮件 14 文案「攻占的据点数相同」）。
                // UnionPvP.txt 没有平局奖励行；用户 m11483 #4「平局按败方发奖励好了」⇒ 沿用败方档。
                mailType = MailService.MAIL_TYPE_UNION_PVP_DRAW;
                win = false;
            }
        }
        int grow = win ? unionCfg.pvpWinGrow() : unionCfg.pvpLoseGrow();
        int crystal = win ? unionCfg.pvpWinCrystal() : unionCfg.pvpLoseCrystal();
        int brother = win ? unionCfg.pvpWinBrother() : unionCfg.pvpLoseBrother();
        String goods = win ? unionCfg.pvpWinGoods() : unionCfg.pvpLoseGoods();
        u.growth = Math.min(unionCfg.growCap(), u.growth + grow);
        u.crystal += crystal;
        world.saveUnions();
        pushUnion(u, MsgIds.S2C_UNION_ATTRI_UPDATE, dump.unionAttriUpdate(6, u.growth, ""), 0);
        pushUnion(u, MsgIds.S2C_UNION_ATTRI_UPDATE, dump.unionAttriUpdate(5, u.crystal, ""), 0);
        for (WorldStore.Member m : u.members) {
            mail.sendUnionPvpMail(m.account, mailType, rivalName, brother, goods, 1, null);
        }
        // 1965 是「公会战结束」面板（客户端 GongHuiZhanEndUI.cs:61-74，TargetUnionName 为空走
        // mNoWarWinPath「无战斗胜利」分支），在线成员一并推一份结算结果。
        for (WorldStore.Member m : u.members) {
            push(m.account, MsgIds.S2C_UNION_PVP_FIGHT_RECORD_PUSH,
                    dump.pvpFightRecordToClient(win, u.growth, u.crystal,
                            capturedCount(u), selfAttackedPoints(u, rival), rivalName));
        }
        log.info("{} union pvp settle union={} day={} mail={} win={} grow={} crystal={} members={}",
                why, u.id, battleDay, mailType, win, grow, crystal, u.members.size());
        // 这一场已经打完：报名状态必须立刻解除，否则客户端 100732（UnionWarRoomEnter.cs:161
        // 用已报名标志藏起报名入口）会把下一场的报名按钮一直藏着，玩家再也报不上名。
        u.pvpEnrolled = false;
        u.pvpEnrollDay = "";
        world.saveUnions();
    }

    /** 最终据点 id（{@code UnionPvPDefPointsInfo.txt} 最后一个据点 = 9 不灭堡垒，攻陷它即攻陷基地）。 */
    private Integer finalPvpPoint() {
        List<Integer> ids = unionCfg.pvpPointIds();
        return ids.isEmpty() ? null : ids.get(ids.size() - 1);
    }

    /**
     * 敌方攻陷的据点数 = 对手已攻占的据点数（f2/f5 {@code targetAttackedDefPoints}，
     * 客户端拼 100761「敌方攻陷」）。
     *
     * <p>口径（已按 APK 校正）：{@code pvpCapturedPoints} 存的是**该公会攻占下的**据点
     * （{@link #captureOnWin} 把点从对手列表摘除再加进自己列表），故
     * {@code capturedCount(mine)} = 我方攻陷（f1/f4）、{@code capturedCount(rival)} = 敌方攻陷（f2/f5）。
     * 改前 f1/f4 与 f2/f5 **填反了**：客户端 {@code GongHuiZhanBaseUI.cs:219/228} 把 f1 拼进
     * 100760「我方攻陷： 」、f2 拼进 100761「敌方攻陷： 」，1958 行内 {@code isAttacked} 又表示
     * 「该（对手的）据点已被我方攻下」，故记分牌必须 f1=我方、f2=敌方。
     *
     * <p>改前这里读的是 {@code u.pvpSelfAttackedPoints} 计数器 —— 全库只有清零没有自增，
     * 于是记分牌上「我方被打下」恒 0。据点数从「谁占了哪些点」推导，不需要额外计数器。
     */
    private int selfAttackedPoints(WorldStore.UnionRecord mine, WorldStore.UnionRecord rival) {
        return rival == null ? 0 : capturedCount(rival);
    }

    /** 该公会已攻占的据点数（f1/f4 {@code selfAttackedDefPoints}「我方攻陷」）。 */
    private static int capturedCount(WorldStore.UnionRecord u) {
        return u == null || u.pvpCapturedPoints == null ? 0 : u.pvpCapturedPoints.size();
    }

    /** 该阵容是否仍存活（1960 f7 {@code KillerName} 为空即未被打掉）。 */
    private static boolean isAlive(WorldStore.PvpFormation f) {
        return f != null && (f.killerName == null || f.killerName.isEmpty());
    }

    /**
     * 只保留存活阵容。打掉的阵容必须留在 {@code pvpDefFormations} 里（1960 靠它画骷髅），
     * 但**战力统计（1957/1958）、剩余队伍数（1959）、可攻打目标（1564）与攻占判定**都只认存活。
     */
    private static List<WorldStore.PvpFormation> aliveFormations(List<WorldStore.PvpFormation> list) {
        List<WorldStore.PvpFormation> alive = new ArrayList<>();
        if (list != null) {
            for (WorldStore.PvpFormation f : list) {
                if (isAlive(f)) {
                    alive.add(f);
                }
            }
        }
        return alive;
    }

    /** 据点 {@code point} 的防守阵容（只读，不往存档里塞空列表）。 */
    private List<WorldStore.PvpFormation> formationsOf(WorldStore.UnionRecord u, int point) {
        if (u == null || u.pvpDefFormations == null) {
            return new ArrayList<>();
        }
        List<WorldStore.PvpFormation> list = u.pvpDefFormations.get(Integer.valueOf(point));
        return list == null ? new ArrayList<WorldStore.PvpFormation>() : list;
    }

    /**
     * 对手公会：真服按赛程配对，而 {@code UnionPvP.txt} / {@code UnionPvPTime.txt} 只给消耗与时段、
     * 没有配对表（真服数据缺失）。**用户 m19006 #9 拍板：真服配对规则按「随机」**⇒ 假服从
     * 「除自己以外的全部公会」里随机挑一个，1549 报名时钉进
     * {@link WorldStore.UnionRecord#pvpRivalId}，当天稳定不变（后续请求/重连都走钉住的对手）；
     * 没有钉住时（离线号首次拉面板、跨日重配）同样随机挑一个。
     *
     * <p>注意：随机配对**不保证互斥**（甲可能挑乙、乙同时挑丙），这是随机配对的固有语义，
     * 真服是否互斥无包可查（用户已明确按随机处理）。
     */
    private WorldStore.UnionRecord rivalUnion(WorldStore.UnionRecord mine) {
        if (mine != null && mine.pvpRivalId != null && !mine.pvpRivalId.isEmpty()) {
            WorldStore.UnionRecord pinned = world.findUnion(mine.pvpRivalId);
            if (pinned != null) {
                return pinned;
            }
        }
        List<WorldStore.UnionRecord> candidates = new ArrayList<>();
        for (WorldStore.UnionRecord u : world.unions()) {
            if (mine == null || !u.id.equals(mine.id)) {
                candidates.add(u);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Collections.shuffle(candidates);
        return candidates.get(0);
    }

    private PlayerRecord findByAccount(String account) {
        if (account == null || account.isEmpty()) {
            return null;
        }
        for (PlayerRecord p : players.all()) {
            if (account.equals(p.account)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 1567 胜利后的攻占判定（见 {@link #onPvpFightResult} 的说明）。
     *
     * <p>模型（APK 没有下发攻占结果的消息，只能由服务端裁定）：
     * ①据点必须是**可攻占**的（前置据点已攻占，见 {@link #canAttackPoint}），否则本场胜利只算击杀；
     * ②胜利 = 打掉对手在该据点的一支防守部队（用 1564 记下的阵容下标，找不到就取第一支）；
     * ③对手在该据点的防守部队被清空时，据点归我方，并从对手的已攻占列表里摘除。
     *
     * <p>改前是「赢一场就按 {@code UnionPvPDefPointsInfo.txt} 表序占下一个未攻占点」，
     * 与客户端按前置关系高亮的可攻占点完全脱节，连刷可 0→9 全占。
     */
    private void captureOnWin(WorldStore.UnionRecord mine, WorldStore.UnionRecord rival, PlayerRecord rec) {
        int point = rec.economy.lastPvpPoint;
        if (point < 0) {
            // 1567 的请求体只有 IsWin（CCMsgFightResultInfo），据点来自上一次 1564/1551 的选择；
            // 没有选择记录时（旧存档、或客户端跳过了选点）回落到第一个可攻占据点，而不是丢弃攻占。
            point = firstAttackablePoint(mine);
        }
        if (point < 0 || mine.pvpCapturedPoints.contains(Integer.valueOf(point))) {
            return;
        }
        if (!canAttackPoint(mine, point)) {
            return;
        }
        if (rival == null) {
            mine.pvpCapturedPoints.add(Integer.valueOf(point));
            return;
        }
        int idx = rec.economy.lastPvpFormation;
        WorldStore.PvpFormation target = idx < 0 ? null : rival.pvpFindFormation(point, idx);
        if (target != null && !isAlive(target)) {
            target = null;
        }
        List<WorldStore.PvpFormation> defenders = rival.pvpFormationsOf(point);
        List<WorldStore.PvpFormation> alive = aliveFormations(defenders);
        if (target == null && !alive.isEmpty()) {
            target = alive.get(0);
        }
        if (target != null) {
            // 打掉后**不**从列表里删：1960 f7 KillerName 需要它留在列表里，
            // 客户端据此把整行变暗、显示骷髅与击杀者名并隐藏「攻击」按钮
            // （GongHuiZhanJuDianAttackUI.cs:120/133/148-157）。战力/剩余队伍数按存活算。
            target.killerName = rec.roleName == null ? "" : rec.roleName;
            target.isFighting = false;
        }
        if (!aliveFormations(defenders).isEmpty()) {
            return;
        }
        rival.pvpCapturedPoints.remove(Integer.valueOf(point));
        mine.pvpCapturedPoints.add(Integer.valueOf(point));
    }

    /** 第一个「未攻占且前置已满足」的据点（表序）；没有则 -1。 */
    private int firstAttackablePoint(WorldStore.UnionRecord u) {
        for (Integer p : unionCfg.pvpPointIds()) {
            if (p == null || unionCfg.pvpPointName(p.intValue()).isEmpty()) {
                continue;
            }
            if (!u.pvpCapturedPoints.contains(p) && canAttackPoint(u, p.intValue())) {
                return p.intValue();
            }
        }
        return -1;
    }

    private void sendUnionRank(GameSession session, GamePacket pkt, int msgId, boolean byGrowth) {
        PlayerRecord rec = session.player();
        WorldStore.UnionRecord mine = rec == null ? null : world.findUnion(rec.guild.id);
        List<WorldStore.UnionRecord> unions = new ArrayList<>(world.unions());
        final boolean growth = byGrowth;
        unions.sort((a, b) -> Integer.compare(unionRankValue(b, growth), unionRankValue(a, growth)));
        List<byte[]> items = new ArrayList<>();
        int myRank = 0;
        int myValue = 0;
        for (int i = 0; i < unions.size(); i++) {
            WorldStore.UnionRecord u = unions.get(i);
            int value = unionRankValue(u, growth);
            items.add(dump.unionRankListItem(u.id, i + 1, u.name, u.icon, u.level, value));
            if (mine != null && u == mine) {
                myRank = i + 1;
                myValue = value;
            }
        }
        session.send(msgId, pkt, dump.unionRankList(myRank, myValue, items));
    }

    private void sendUnionBrief(GameSession session, GamePacket pkt, int msgId, boolean byGrowth) {
        String guid = Pb.read(pkt.body).getString(1);
        WorldStore.UnionRecord u = guid == null || guid.isEmpty() ? null : world.findUnion(guid);
        if (u == null) {
            PlayerRecord rec = session.player();
            u = rec == null ? null : world.findUnion(rec.guild.id);
        }
        if (u == null) {
            // 找不到公会（客户端 RankListMainDialog.cs:1407/1421 的越界保护写成
            // `Count < currentClick` 而非 `<=`，IndexOf 返回 -1 时仍会把**空 guid** 发出来；
            // 假服重启清空 world.unions() 后客户端缓存的排名列表也会带脏 guid），且请求者本人
            // 也没有公会 ⇒ 必须仍写 f1 `rankItem`：proto 侧它是 `[DefaultValue(null)]` 且无惰性
            // 初始化，客户端 UnionInfoTips.cs:74 第一句就是 `info.rankItem.icon.Split(...)`
            // 且 :74-101 共 14 处解引用都无 null 检查 ⇒ 不写 f1 会 NRE、公会信息面板打不开。
            // 这里用全零 rankItem（图标走 unionIcon 的合法兜底），面板可正常打开。
            session.send(msgId, pkt, dump.unionBriefInfo(
                    dump.unionRankListItem("", 0, "", "", 0, 0), "", 0, ""));
            return;
        }
        int rank = unionRankOf(u, byGrowth);
        byte[] rankItem = dump.unionRankListItem(u.id, rank, u.name, u.icon, u.level, unionRankValue(u, byGrowth));
        String owner = "";
        for (WorldStore.Member m : u.members) {
            if ("owner".equals(m.job)) {
                owner = m.name;
                break;
            }
        }
        session.send(msgId, pkt, dump.unionBriefInfo(rankItem, owner, u.members.size(), u.notice));
    }

    private int unionRankOf(WorldStore.UnionRecord target, boolean byGrowth) {
        int myValue = unionRankValue(target, byGrowth);
        int rank = 1;
        for (WorldStore.UnionRecord u : world.unions()) {
            if (u == target) {
                continue;
            }
            if (unionRankValue(u, byGrowth) > myValue) {
                rank++;
            }
        }
        return rank;
    }

    private static int unionRankValue(WorldStore.UnionRecord u, boolean byGrowth) {
        if (byGrowth) {
            return u.growth;
        }
        int sum = 0;
        for (WorldStore.Member m : u.members) {
            sum += m.fightPower;
        }
        return sum;
    }

    private static WorldStore.PvpWjBrief pvpWjBrief(int job, PlayerRecord.Hero h) {
        WorldStore.PvpWjBrief w = new WorldStore.PvpWjBrief();
        w.job = job;
        w.index = h.heroIndex;
        w.level = h.level;
        w.stage = h.stage;
        w.stars = h.stars;
        return w;
    }

    /**
     * 按公会战防守预设 type（24..28）取该成员的武将简报：槽位 i 的武将 = job i+1。
     *
     * <p>依据：客户端「我的队伍」面板用 201/202 通用阵容接口按 type 24..28 存读
     * （{@code GongHuiZhanMyTeamUI.cs:102-104} 读、{@code :225-231} 写，收包 {@code ᝁ.cs:3374-3418}
     * 存进 {@code UnionPvpDefense1..5}）；上阵后客户端按 job 1..5 取
     * {@code CMsgWuJiangJobAndBriefInfo} 画武将（{@code GongHuiZhanJuDianBuFangUI.cs:140-160} 己方、
     * {@code GongHuiZhanJuDianAttackUI.cs} 攻击方），故 job = 槽位 + 1。
     *
     * <p>该 type 从未被写过时 {@link PlayerRecord#formationSlots(int)} 会回落到 PVE 阵容——
     * 这与客户端面板里显示的队伍一致（面板对未配置的 type 拿到的也是同一份回落数据），
     * 因此不会出现「面板上有队伍、布防列表里却没有」的死局。
     */
    private static List<WorldStore.PvpWjBrief> presetWjBriefs(PlayerRecord owner, int formationType) {
        List<WorldStore.PvpWjBrief> list = new ArrayList<>();
        if (owner == null) {
            return list;
        }
        List<String> slots = owner.formationSlots(formationType);
        for (int i = 0; i < slots.size() && list.size() < PVP_FORMATION_SLOTS; i++) {
            PlayerRecord.Hero h = owner.findHero(slots.get(i));
            if (h != null) {
                list.add(pvpWjBrief(i + 1, h));
            }
        }
        return list;
    }

    /** 一组武将简报的战力合计（按 heroIndex 反查，取不到的不计）。 */
    private static int fightPowerOf(PlayerRecord owner, List<WorldStore.PvpWjBrief> briefs) {
        if (owner == null || briefs == null) {
            return 0;
        }
        int sum = 0;
        for (WorldStore.PvpWjBrief w : briefs) {
            PlayerRecord.Hero h = owner.findHeroByIndex(w.index);
            if (h != null) {
                sum += h.fightPower;
            }
        }
        return sum;
    }

    /**
     * 该成员某预设当前驻守的据点号；未上阵返回 <b>-1</b>。
     *
     * <p>1950 的 {@code CMsgOneUnionPvPDefFormation.7 DefPointsIndex} 是**单个**据点号，
     * 客户端「添加队伍」面板专门为 -1 显示驻守域「-」、非 -1 显示据点名
     * （{@code GongHuiZhanJuDianAddTeamUI.cs:127-132}），排序时把 -1 排在最前
     * （{@code :159-185}）⇒ 未上阵的候选队伍必须以 -1 下发，否则面板列表里根本看不到它们。
     */
    private static int placementPointOf(WorldStore.UnionRecord u, int playerId, int formationType) {
        if (u == null || u.pvpDefFormations == null) {
            return -1;
        }
        for (Map.Entry<Integer, List<WorldStore.PvpFormation>> e : u.pvpDefFormations.entrySet()) {
            List<WorldStore.PvpFormation> list = e.getValue();
            if (list == null) {
                continue;
            }
            for (WorldStore.PvpFormation f : list) {
                if (f != null && f.playerId == playerId && f.formationType == formationType) {
                    return e.getKey().intValue();
                }
            }
        }
        return -1;
    }

    /** 该成员是否真的保存过这套预设（而非回落 PVE 的兜底）。 */
    private static boolean hasConfiguredPreset(PlayerRecord p, int formationType) {
        if (p == null || p.formationsByType == null) {
            return false;
        }
        List<String> slots = p.formationsByType.get(Integer.valueOf(formationType));
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

    /**
     * 1553 的候选队伍列表 = **全公会成员的防守预设**（type 24..28），未上阵的
     * {@code DefPointsIndex} 写 -1、已上阵写所在据点号。
     *
     * <p>原实现只下发 {@link #allFormations}(已上阵的队伍)，而「添加队伍」面板是**唯一**的上阵入口
     * （{@code GongHuiZhanJuDianBuFangUI.cs:163-182 OnAddClick} → 本面板 → 1552），
     * 于是战斗日刚开始、还没有任何队伍上阵时该列表为空 ⇒ 会长/副会长无法给任何据点布防。
     * 客户端为此专门实现了 {@code DefPointsIndex == -1} 的渲染与排序分支（见
     * {@link #placementPointOf}），说明真服下发的是「全部候选」而非「仅已上阵」。
     *
     * <p>候选 = 每个成员**真正保存过**的预设（type 24..28）。**没布防的成员就是没上场**
     * （用户 m20090 #1：不会被打，只有布防的才会被打）⇒ 不再为「一套预设都没存过」的成员
     * 造「预设 1」兜底候选：列表里没有他的队伍，他就没得上阵，也就不会出现在防守名单里。
     * 同一成员的**不同预设可以分别驻守不同据点**（同一预设只能在一个据点，
     * 见 {@link #detachPresetFromOtherPoints}）。
     */
    private List<WorldStore.PvpFormation> pvpCandidates(WorldStore.UnionRecord u) {
        List<WorldStore.PvpFormation> out = new ArrayList<>();
        if (u == null) {
            return out;
        }
        for (WorldStore.Member m : u.members) {
            PlayerRecord p = players.findByPlayerId(m.playerId);
            for (int t = PVP_DEF_FORMATION_TYPE_BASE;
                 t < PVP_DEF_FORMATION_TYPE_BASE + PVP_FORMATION_SLOTS; t++) {
                if (!hasConfiguredPreset(p, t)) {
                    // 该成员没保存过这套预设 ⇒ 没有这支队伍可上阵（用户 m20090 #1）。
                    continue;
                }
                WorldStore.PvpFormation c = new WorldStore.PvpFormation();
                c.playerId = m.playerId;
                c.account = m.account == null ? "" : m.account;
                c.playerName = m.name == null ? "" : m.name;
                c.playerLevel = m.level;
                c.playerResId = p == null ? 0 : p.mainHeroIndex;
                c.formationType = t;
                c.wjs = presetWjBriefs(p, t);
                c.fightPower = fightPowerOf(p, c.wjs);
                c.point = placementPointOf(u, m.playerId, t);
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 同一成员同一预设只能驻守一个据点：把该 (playerId, formationType) 在其它据点/其它格位的
     * 落点全部摘掉（保留 {@code keepPoint}/{@code keepIndex} 这一格，由调用方复用）。
     *
     * <p>依据：1950 里每个预设只有一行、其 {@code DefPointsIndex} 是单值，客户端「添加队伍」
     * 面板的驻守域列也只显示一个据点名（{@code GongHuiZhanJuDianAddTeamUI.cs:127-132}）
     * ⇒ 换点部署在客户端语义上是「移动这支队伍」，服务端必须同步摘掉旧落点，
     * 否则同一支队会同时出现在两个据点（1959 剩余队伍数、1960 布防表都会重复计数）。
     */
    private void detachPresetFromOtherPoints(WorldStore.UnionRecord u, int playerId, int formationType,
                                             int keepPoint, int keepIndex) {
        if (u == null || u.pvpDefFormations == null || playerId <= 0) {
            return;
        }
        for (Map.Entry<Integer, List<WorldStore.PvpFormation>> e : u.pvpDefFormations.entrySet()) {
            List<WorldStore.PvpFormation> list = e.getValue();
            if (list == null) {
                continue;
            }
            int point = e.getKey().intValue();
            for (Iterator<WorldStore.PvpFormation> it = list.iterator(); it.hasNext(); ) {
                WorldStore.PvpFormation f = it.next();
                if (f == null || f.playerId != playerId || f.formationType != formationType) {
                    continue;
                }
                if (point == keepPoint && f.formationIndex == keepIndex) {
                    continue;
                }
                it.remove();
            }
        }
    }

    /** 阵亡武将血量为 0（复用矿战/医院共用的 qkDeadWjs），其余按存活给 1（客户端只判 0/非 0）。 */
    private static int deadWjHp(PlayerRecord rec, int wjIndex) {
        if (rec.qkDeadWjs != null) {
            for (PlayerRecord.QkDeadWj d : rec.qkDeadWjs) {
                if (d.wjIndex == wjIndex) {
                    return 0;
                }
            }
        }
        return 1;
    }

    // ------------------------------------------------------------------ 押镖内部

    /** 表驱动的活动时间窗：UnionMaJiuTime.txt 发镖开始/持续、劫镖持续。 */
    private long[] majiuWindow() {
        int start = Math.max(0, unionCfg.majiuStartHour()) * 3600 + Math.max(0, unionCfg.majiuStartMinute()) * 60;
        int sendSec = Math.max(60, unionCfg.majiuSendSec());
        int raidSec = Math.max(60, unionCfg.majiuRaidSec());
        return new long[]{start, start + sendSec, start + sendSec + raidSec};
    }

    /**
     * UnionYunBiaoPhase：**1 Prepare = 发镖窗口**（客户端按钮「前往派遣」，StrTable 100800；
     * 此时点拦截会被 100804「当前还未进入劫镖时间！」挡掉）/ **2 Begin = 劫镖窗口**（客户端按钮
     * 「查看镖车」100801、左栏倒计时文案「派遣截止」100802，`MaJiuEntryUI.OnLanJie` 只允许
     * `curPhase == 2` 进拦截场景）/ **3 End = 结算领奖**（含次日活动开始前的等待期，客户端 100803
     * 「运镖活动尚未开始！」）。
     *
     * <p>依据：`decompiled\client-src\MobileGameDemo\MaJiuEntryUI.cs:127-137`（1/2 两窗口的按钮与文案）、
     * `:158-195`（`OnPaiQian` 允许 phase≠0/3、`OnLanJie` 只允许 phase==2）、
     * `MaJiuLanJieDuiWuUI.cs:278`（劫镖额度 `myRaidSucTime >= VipManager.JieBiaoCount`）。
     * 早先实现把 phase 1 当「活动前」、phase 2 当整个 18:00–22:00，会让客户端在发镖窗口显示
     * 「派遣截止」并放开拦截按钮、而服务端恰好相反，故按上表纠正。
     */
    int majiuPhaseAt(int secOfDay) {
        long[] w = majiuWindow();
        if (secOfDay >= w[0] && secOfDay < w[1]) {
            return 1;
        }
        return (secOfDay >= w[1] && secOfDay < w[2]) ? 2 : 3;
    }

    /**
     * 测试用时钟锚点：>= 0 时所有「游戏内时间窗」判定都以它当**当日秒数**，-1 = 用真实时钟。
     *
     * <p>押镖发车/劫镖、公会战报名这些窗口原来只能读真实时钟，而测试通常在凌晨跑，
     * 永远进不了窗口 ⇒ 端到端行为（1543 真发车、1549 窗口外被拒）无法验证。
     */
    int clockSecOverride = -1;

    /** 当日秒数（Asia/Shanghai）；{@link #clockSecOverride} 有效时优先。 */
    private int secOfDay() {
        return clockSecOverride >= 0 ? clockSecOverride : LocalTime.now(ZONE).toSecondOfDay();
    }

    int majiuPhase() {
        return majiuPhaseAt(secOfDay());
    }

    int majiuLeftSec() {
        long[] w = majiuWindow();
        int now = secOfDay();
        if (now < w[0]) {
            return (int) (w[0] - now);
        }
        if (now < w[1]) {
            return (int) (w[1] - now);
        }
        if (now < w[2]) {
            return (int) (w[2] - now);
        }
        return (int) (86400L - now + w[0]);
    }

    /**
     * 发镖窗口 = phase 1 {@code [w0, w1)}（18:00–20:00）。
     *
     * <p>用户 m21142 复核时推翻上一轮的放宽（上一轮误改成整个 {@code [w0, w2)}）：客户端真正的
     * 发镖闸门不在 {@code MaJiuEntryUI.OnPaiQian}，而在**点目的地告示牌**那一步 ——
     * APK {@code Client\᝕.cs:78-98 OnClicked()}（{@code \u1755} = 目的地告示牌）：派遣模式下
     * {@code if (curPhase == 1)} 才 {@code EN_OPEN_MAJIUPAIQIAN_UI}（派遣面板），
     * **否则只弹 {@code StrTable.getStr(100741)}「当前已过发镖时间！」**；派遣链
     * {@code MaJiuPaiQianUI}/{@code MaJiuBasePaiQianUI} 自身不查相位，所以相位闸门只有这一道。
     * 且客户端左栏派遣按钮在 phase 2 的文案是 100801「查看镖车」、派遣面板 phase 2 只显示
     * 100795「已过派遣期」（{@code MaJiuBasePaiQianUI.cs:79-91}）⇒ phase 2 不可能发出 1543。
     *
     * <p>**不判星期**：真服表只有周二/四/五三行带 {@code #}，但用户 m19006 #1 已拍板「假服押镖
     * 每天都开，{@code #} 星期过滤不实现、也不用改回去」（见 {@code UnionCfg.parseMaJiuTime} 注释）。
     */
    private boolean inSendWindow() {
        long[] w = majiuWindow();
        int now = secOfDay();
        return now >= w[0] && now < w[1];
    }

    private boolean inRaidWindow() {
        long[] w = majiuWindow();
        int now = secOfDay();
        return now >= w[1] && now < w[2];
    }

    /** 跨日重置押镖统计（每天 0 点后首次请求时执行）。 */
    private void ensureMajiuDay(PlayerRecord rec) {
        String day = PlayerDumpService.now().substring(0, 10);
        if (day.equals(rec.economy.majiuDay)) {
            return;
        }
        // 跨日兜底：昨天的镖车还没领奖（22:00 结算时服务器没跑、或玩家离线没收到邮）→
        // 按 mailType 7「运镖收益」补发再清零。**必须在更新 majiuDay 之前**做：
        // raidRankOf 只统计与请求者同一天（majiuDay）的玩家，此时请求者仍算「昨天」，
        // 名次才按昨天那批参与者排；先更新的话他会变成今天唯一的参与者（名次恒 1）。
        if (hasPendingCart(rec)) {
            settlePendingCart(rec, "rollover");
        }
        rec.economy.majiuDay = day;
        rec.economy.majiuResetTimes = 0;
        rec.economy.majiuAwarded = false;
        rec.economy.raidJinbiTotal = 0;
        rec.economy.raidXdbTotal = 0;
        rec.economy.raidJinShiTotal = 0;
        // 昨天的镖车（已领奖的也在内）整体作废：车级状态都在 escortCarts 里，
        // 发镖额度 = 未领奖车数，跨日必须清空，否则额度永远被昨天的车占着。
        rec.economy.escortCarts.clear();
        rec.economy.escortRaidSucTimes = 0;
        // 出手额度（UnionMaJiuBase.txt「有效掠夺场次（不管成功还是失败）」3 次）按活动日重置；
        // 成功额度 escortRaidLeft 由 ProgressService 日清按 VipCfg 第 29 列「劫镖次数」设值。
        rec.economy.escortRaidTimes = 0;
        rec.economy.escortDefendGold = 0;
        rec.economy.escortDefendUnion = "";
        players.save(rec);
    }

    private PlayerDumpService.MajiuView majiuView(PlayerRecord rec, WorldStore.UnionRecord u) {
        PlayerDumpService.MajiuView v = new PlayerDumpService.MajiuView();
        v.phase = majiuPhase();
        v.leftSec = majiuLeftSec();
        v.resetTime = rec.economy.majiuResetTimes;
        v.myRaidSucTime = rec.economy.escortRaidSucTimes;
        v.raidXDB = rec.economy.raidXdbTotal;
        v.raidJinShi = rec.economy.raidJinShiTotal;
        v.raidJinbi = rec.economy.raidJinbiTotal;
        v.hasGetAward = rec.economy.majiuAwarded;
        // f7 myBiaoCheInfo = 我**在途**（未领奖）的镖车 GUID 列表，**与相位无关**：
        // 客户端 `EmBattleSystem.cs:2653` / `MaJiuBasePaiQianUI.cs:93-94` 拿 `myBiaoCheInfo.Count`
        // 与 `VipManager.FaBiaoCount`（= `VipCfg.txt` 第 27 列「发镖数量」，VIP11+ = 2）比较做
        // 发镖额度门控 —— 该上限是**并发在途数**上限（客户端从不 Clear/Remove，
        // 只能靠新快照整体替换，见 `MaJiuBaseManager.cs:27-30`）。
        // `MaJiuEntryUI.cs:138` 又要求它在 phase 3 且未领奖时非空才会生成领奖宝箱
        // （`myBiaoCheInfo.Count > 0 || myRaidSucTime > 0`）。故只要有未结算的镖车就必须下发，
        // 不能限定 phase >= 2 —— 否则发镖窗口（phase 1）里客户端会以为我一辆都没派。
        for (PlayerRecord.Economy.EscortCart c : pendingCarts(rec)) {
            v.myCartIds.add(c.cartId);
        }
        // f4 biaoCheInfo：**一车一行**（`CCMsgRaidBiaoCheInfo{hasBeenRaid, raidingPlayerName, 车}`）。
        // 客户端 `MaJiuLanJieDuiWuUI.cs:88-99` 逐行生成可劫目标、只按目的地过滤，
        // **不按 playerGUID 去重** ⇒ 同一玩家的两辆车就是两个可劫目标，各带自己的
        // hasBeenRaid / 拦截者 / 被劫次数。
        // 抽样：先按目的地分组，各自洗牌后最多留 RAID_LIST_PER_TARGET 辆（用户 m19393 #2）。
        String today = PlayerDumpService.now().substring(0, 10);
        Map<Integer, List<byte[]>> raidByTarget = new LinkedHashMap<>();
        for (PlayerRecord other : players.all()) {
            if (other.playerId == rec.playerId) {
                continue;
            }
            // 可劫目标**只能跨公会**（用户 m19006 #11）：同公会成员的镖车不进 f4。
            // 「可以跨服」在假服无需额外处理（单进程 = 单服，列表里本来就是全服玩家）。
            if (other.guild != null && rec.guild != null
                    && other.guild.id != null && other.guild.id.equals(rec.guild.id)) {
                continue;
            }
            // 只要**今天**的镖车：离线号没被 ensureMajiuDay 扫到时会留着昨天的车，
            // 列进可拦截列表就是「幽灵镖车」（点进去 1939 ret=2，因为找不到车）。
            if (other.economy.majiuDay == null || !other.economy.majiuDay.equals(today)) {
                continue;
            }
            for (PlayerRecord.Economy.EscortCart c : pendingCarts(other)) {
                // 掉线留下的幽灵「掠夺中」不进列表（见 clearStaleRaid）。
                clearStaleRaid(c);
                raidByTarget.computeIfAbsent(c.targetId, k -> new ArrayList<>())
                        .add(dump.raidBiaoChe(c.hasBeenRaid, c.raiderName,
                                dump.playerBiaoChe(c.cartId, c.targetId, other, c.beRaidCnt)));
            }
        }
        for (List<byte[]> rows : raidByTarget.values()) {
            Collections.shuffle(rows);
            v.raidCarts.addAll(rows.subList(0, Math.min(RAID_LIST_PER_TARGET, rows.size())));
        }
        return v;
    }

    private int lineGoldOf(int targetId) {
        UnionCfg.MaJiuTargetRow row = unionCfg.majiuTarget(targetId);
        return row == null ? 0 : row.gold;
    }

    /**
     * 1543 的**目的地门槛**（与时间窗无关，便于单测）：马厩等级 ≥ {@code UnionMaJiuTarget.txt}
     * 第 2 列、阵容总战力 ≥ 第 3 列。包级可见供 {@code D6FlowSimTest} 直接断言
     * （{@code inSendWindow()} 读真实时钟，无法在测试里进 18:00–20:00 窗口）。
     */
    boolean canSendCart(PlayerRecord rec, int targetId, int formationFightPower) {
        UnionCfg.MaJiuTargetRow target = unionCfg.majiuTarget(targetId);
        if (target == null) {
            return false;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        int majiuLevel = u == null ? 0 : buildingLevel(u, MAJIU_BUILDING);
        return majiuLevel >= target.majiuLevel && formationFightPower >= target.fightPower;
    }

    /**
     * 请求体里一个嵌套 {@code CMsgFormation}（f2）的**上阵阵容总战力**：{@code CMsgFormation.wj}
     * 是 repeated string（武将 GUID 串，见 {@code pyfoot\tmp_msgdll\NetProto\CMsgFormation.cs:36-37}），
     * 与客户端 {@code EmBattleSystem.cs:2660 GetTotalFightPower()} 的口径一致。
     * 包体缺失/为空返回 0（调用方据此拒绝）。
     */
    int formationFightPower(PlayerRecord rec, byte[] formationBody) {
        if (rec == null || formationBody == null || formationBody.length == 0) {
            return 0;
        }
        int total = 0;
        for (String guid : Pb.read(formationBody).getStrings(2)) {
            PlayerRecord.Hero hero = guid == null || guid.isEmpty() ? null : rec.findHero(guid);
            if (hero != null) {
                total += hero.fightPower;
            }
        }
        return total;
    }

    /** 1943 f5 yunBiaoAward：**一车一条**（f1 targeID = 目的地 id、f2 beRaidCnt、f3 jinBi）。 */
    private List<byte[]> awardItems(PlayerRecord rec) {
        List<byte[]> items = new ArrayList<>();
        for (PlayerRecord.Economy.EscortCart c : pendingCarts(rec)) {
            items.add(dump.yunBiaoAwardItem(c.targetId, c.beRaidCnt, lineGoldOf(c.targetId),
                    economy.escortSendXdb(), 0));
        }
        return items;
    }

    private List<byte[]> defenceAwards(PlayerRecord rec) {
        List<byte[]> list = new ArrayList<>();
        if (rec.economy.escortDefendGold <= 0) {
            return list;
        }
        list.add(dump.defenceAward(rec.economy.escortDefendUnion, rec.economy.escortDefendGold));
        return list;
    }

    /**
     * 1943 的 f1–f4 全是**劫镖（掠夺）**语义，必须回本人当日劫镖累计 + 劫镖名次
     * （第 2 轮闸门清扫，用户 m20482）。
     *
     * <p>字段名与客户端消费点：{@code CCMsgRequestGetYunBiaoAwardRet} = f1 {@code raidRank} /
     * f2 {@code raidJinBi} / f3 {@code raidXDB} / f4 {@code raidJinShi}；
     * {@code MaJiuGetAwardUI.cs:113-133} 的第 1 行显示的就是 f3 与 f2，名次文案 Str 100805
     * 「你在此次公会运镖中[FFFF00]排名{0}[-]，获得丰厚的[FF0000]掠夺红利[-]！」。
     * **发镖收益走 f5 {@code yunBiaoAward}（一车一行，100808/100809）、防守奖金走 f6**，
     * 所以 f2/f3 绝不能拿来装发镖金币/发镖兄弟币（改前正是如此：成功分支把发镖金额塞进
     * f2/f3、拒绝分支写 0，客户端把发镖金币当「掠夺红利」显示）。
     */
    private byte[] raidAwardBody(PlayerRecord rec, List<byte[]> items, List<byte[]> defends) {
        return dump.escortAward(raidAwardRank(rec), rec.economy.raidJinbiTotal,
                rec.economy.raidXdbTotal, rec.economy.raidJinShiTotal, items, defends);
    }

    /**
     * 1943 f1 用值：只有**当天真劫过镖**（掠夺金币/兄弟币/晶石任一 > 0）的玩家才给名次，
     * 没劫过给 0。
     *
     * <p>客户端 {@code MaJiuGetAwardUI.cs:74-83} 只在 {@code raidRank != 0} 时才把「掠夺红利」
     * 那一行 {@code SetParent} 留下、否则 {@code DestroyImmediate} ⇒ 给没劫过镖的人塞名次
     * 会凭空多出一行 0 收益。判据与 {@link #raidRanked} 的当日过滤一致。
     */
    private int raidAwardRank(PlayerRecord rec) {
        PlayerRecord.Economy e = rec.economy;
        if (e.raidJinbiTotal <= 0 && e.raidJinShiTotal <= 0 && e.raidXdbTotal <= 0) {
            return 0;
        }
        return raidRankOf(rec);
    }

    /**
     * 劫镖收益名次（1 起）。**只统计与请求者同一次活动日（{@code economy.majiuDay}）的玩家**：
     * 改前直接拿全服 {@code raidJinbiTotal} 排序，没碰马厩的玩家还挂着昨天的数据 ⇒ 1545/1548 的
     * 「掠夺名次」档（{@code UnionMaJiuBase.txt} 第 13 行 `1:400_2:300_3:200_10:150_40:100`）与
     * 1546 的排行榜都被昨天的人挤歪。
     * 跨日兜底（{@link #ensureMajiuDay}）在**更新 {@code majiuDay} 之前**调用本方法，
     * 所以补发昨天那辆车时名次仍按昨天那批参与者算。
     */
    private int raidRankOf(PlayerRecord rec) {
        List<PlayerRecord> sorted = raidRanked(rec);
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).playerId == rec.playerId) {
                return i + 1;
            }
        }
        return sorted.size() + 1;
    }

    /**
     * 与 {@code rec} 同一次马厩活动日（{@code economy.majiuDay}）的玩家，按掠夺金币降序。
     * 不做零值过滤：调用方各自决定要不要列出来（{@link #raidRankOf} 必须能找到请求者本人）。
     */
    private List<PlayerRecord> raidRanked(PlayerRecord rec) {
        String day = rec.economy.majiuDay;
        List<PlayerRecord> sorted = new ArrayList<>();
        for (PlayerRecord p : players.all()) {
            if (day == null ? p.economy.majiuDay != null : !day.equals(p.economy.majiuDay)) {
                continue;
            }
            sorted.add(p);
        }
        sorted.sort((a, b) -> Integer.compare(b.economy.raidJinbiTotal, a.economy.raidJinbiTotal));
        return sorted;
    }

    // ------------------------------------------------------------------ 通用内部

    /**
     * 玩家离开公会（1515 踢人 / 1516 主动退会 / 1513 解散）后的残留清理。
     *
     * <p>三处原先只清成员表与玩家档的 {@code guild.*}，公会侧与**其他人**的档里仍留着指向他的引用：
     * <ul>
     *   <li>{@code u.employers} 里 {@code hiredBy == 离会者} 的条目：佣兵/教练仍占着建筑格位、
     *       他继续领工资，1520/1917 还列着他。训练场教练本人那条也是 {@code hiredBy = 教练本人}
     *       （{@code UnionService.java:1787} 的注释）。</li>
     *   <li>其他成员 {@code economy.trainSlots[].employerGuid == 离会者} 的教练绑定：人已不在公会，
     *       坑位仍按他的战力吃加成（{@code trainerRatioOf} 查不到会静默归零，但绑定本身就是脏数据）。</li>
     *   <li>离会者自己的训练坑位：训练场是公会的场地，离会后 {@code trainClock} 仍会给他结算经验
     *       ⇒ 这里按 1540（取消训练）的同一口径**按已训练时长折算发放**再清坑，
     *       既不吞掉他付过的金币/钻石，也不让他离会后白拿满额经验。</li>
     *   <li>离会者自己的镖车 {@code economy.escortCarts}：车还挂在他名下，
     *       {@code majiuView} 仍把他列成可拦截目标（押镖在 APK 里是公会活动）。</li>
     * </ul>
     */
    private void dropMemberArtifacts(WorldStore.UnionRecord u, WorldStore.Member m) {
        if (u == null || m == null) {
            return;
        }
        u.ensure();
        int before = 0;
        int after = 0;
        for (java.util.List<WorldStore.Employer> list : u.employers.values()) {
            if (list == null) {
                continue;
            }
            before += list.size();
            list.removeIf(e -> e != null && e.hiredBy == m.playerId);
            after += list.size();
        }
        if (after != before) {
            log.info("union {} dropped {} employer rows of leaving member {}", u.name,
                    before - after, m.name);
        }
        for (PlayerRecord p : players.all()) {
            if (p.economy.trainSlots == null || p.economy.trainSlots.isEmpty()) {
                continue;
            }
            boolean dirty = false;
            for (PlayerRecord.Economy.TrainSlot s : p.economy.trainSlots) {
                if (s != null && s.employerGuid == m.playerId) {
                    s.employerGuid = 0;
                    s.employerWj = 0;
                    dirty = true;
                }
            }
            if (dirty) {
                players.save(p);
            }
        }
        PlayerRecord leaver = players.get(m.account);
        if (leaver == null) {
            return;
        }
        cancelTrainingOnLeave(leaver);
        // 离会者当天的镖车整体作废（车级状态都在 escortCarts 里）：
        // 留着会让他在别的公会的 1936 f7 里继续占发镖额度。
        leaver.economy.escortCarts.clear();
        leaver.economy.raidTargetCartId = "";
        players.save(leaver);
    }

    /** 离会时按 1540（取消训练）的同一口径结算在训坑位：按已训练时长折算经验后清坑。 */
    private void cancelTrainingOnLeave(PlayerRecord leaver) {
        if (leaver.economy.trainSlots == null || leaver.economy.trainSlots.isEmpty()) {
            return;
        }
        int pits = leaver.economy.trainSlots.size();
        int granted = 0;
        for (PlayerRecord.Economy.TrainSlot slot : leaver.economy.trainSlots) {
            if (slot == null) {
                continue;
            }
            PlayerRecord.Hero h = leaver.findHeroByIndex(slot.wjIndex);
            if (h != null && slot.startedAt > 0) {
                int dur = slot.totalSec > 0 ? slot.totalSec : unionCfg.trainSec();
                long elapsed = Math.max(0L, (System.currentTimeMillis() - slot.startedAt) / 1000L);
                elapsed = Math.min(elapsed, dur);
                float ratio = trainerRatioOf(null, slot.employerGuid, slot.employerWj);
                int total = (int) unionCfg.trainTotalExp(leaver.level, slot.type, ratio, unionCfg.trainSec());
                int add = (int) (total * elapsed / Math.max(1, dur));
                progress.addWjExp(leaver, h, add);
                granted += add;
            }
        }
        leaver.economy.trainSlots.clear();
        log.info("{} left union: {} training pits settled, exp={}", leaver.account, pits, granted);
    }

    private WorldStore.Member addMember(WorldStore.UnionRecord u, PlayerRecord rec) {
        WorldStore.Member exist = u.findMember(rec.playerId);
        if (exist != null) {
            return exist;
        }
        WorldStore.Member m = memberOf(rec, "member");
        u.members.add(m);
        rec.guild.id = u.id;
        rec.guild.name = u.name;
        rec.guild.job = "member";
        players.save(rec);
        world.saveUnions();
        return m;
    }

    private static WorldStore.Member findRequester(WorldStore.UnionRecord u, int playerId) {
        for (WorldStore.Member m : u.requesters) {
            if (m.playerId == playerId) {
                return m;
            }
        }
        return null;
    }

    /** 只有会长（EUnionJob 3）能取消升级：客户端 BuildingItem_InJianZhuMianBan.cs:256 用
     * {@code mEUnionJob == (EUnionJob)3} 门控「停止升级」按钮，长老点了也不发 1524。 */
    private boolean isOwner(PlayerRecord rec) {
        return rec.guildJobCode() == 3;
    }

    private boolean isOwnerOrElder(PlayerRecord rec) {
        int job = rec.guildJobCode();
        return job == 3 || job == 2;
    }

    private static WorldStore.Member memberOf(PlayerRecord rec, String job) {
        WorldStore.Member m = new WorldStore.Member();
        m.playerId = rec.playerId;
        m.account = rec.account;
        m.heroIndex = rec.mainHeroIndex;
        m.level = rec.level;
        m.name = rec.roleName;
        m.job = job;
        // 1906 成员列表 f6 用：入会时带上已有贡献（此前恒 0）。
        m.contribution = rec.guild.contribution;
        PlayerRecord.Hero main = rec.findHeroByIndex(rec.mainHeroIndex);
        m.fightPower = main == null ? 0 : main.fightPower;
        m.lastOnlineAt = System.currentTimeMillis();
        return m;
    }

    private Set<Integer> onlineIds() {
        Set<Integer> ids = new HashSet<>();
        for (GameSession s : sessions.onlineSnapshot()) {
            PlayerRecord p = s.player();
            if (p != null) {
                ids.add(Integer.valueOf(p.playerId));
            }
        }
        return ids;
    }

    /**
     * 按玩家档刷新公会成员快照，返回在线 ID 集（供 1906 / 1922 出包）。
     *
     * <p>成员 f2 {@code ResId}（头像）/ f3 {@code Level} / f4 {@code Name} 来自 {@link #memberOf}，
     * 只在建会/申请/入会时写过一次，此后唯一同步是贡献度 ⇒ 换主将或升级后头像与等级陈旧，
     * 客户端 {@code BCTInviteUI.cs:127} 的 {@code Level >= BCTCommonInfoProperty.enableLevel}
     * 会把刚升级的成员误排除、名字也停在上次入会时。这两包都是「读」接口，出包前刷一次即可。
     */
    private Set<Integer> syncMembers(WorldStore.UnionRecord u) {
        if (u != null && u.members != null) {
            for (WorldStore.Member m : u.members) {
                PlayerRecord p = players.findByPlayerId(m.playerId);
                if (p == null) {
                    continue;
                }
                m.heroIndex = p.mainHeroIndex;
                m.level = p.level;
                m.name = p.roleName;
                m.contribution = p.guild.contribution;
            }
        }
        return onlineIds();
    }

    private boolean isOnline(int playerId) {
        return onlineIds().contains(Integer.valueOf(playerId));
    }

    private void push(String account, int msgId, byte[] body) {
        GameSession s = account == null ? null : sessions.get(account);
        if (s != null) {
            s.send(msgId, 0, body);
        }
    }

    private void pushUnion(WorldStore.UnionRecord u, int msgId, byte[] body, int excludePlayerId) {
        if (u == null) {
            return;
        }
        for (WorldStore.Member m : u.members) {
            if (excludePlayerId > 0 && m.playerId == excludePlayerId) {
                continue;
            }
            push(m.account, msgId, body);
        }
    }

    /**
     * 逐成员推 1923 {@code CCMsgRequestZuoZhanShiBossInfo_Ret}：包体**不能**共用。
     *
     * <p>f2 {@code myEmploy} 是「我自己雇的佣兵」、f3 {@code curPlayTime} 是「我自己各章的已打次数」
     * （客户端 {@code UnionBossWarInfo.cs:45-51} 用它铺 {@code mBossPlayedTimes}，
     * {@code :153} 再算 {@code mBossMaxCiShu - playedTimes} 决定挑战按钮）。改前用请求者的包体
     * 整包广播 ⇒ 其他成员看到的是别人的佣兵和别人的挑战次数（次数多的会被按钮判成「没次数了」）。</p>
     */
    private void pushBossInfo(WorldStore.UnionRecord u, int excludePlayerId) {
        if (u == null) {
            return;
        }
        List<PlayerDumpService.BossView> views = bossViews(u);
        for (WorldStore.Member m : u.members) {
            if (excludePlayerId > 0 && m.playerId == excludePlayerId) {
                continue;
            }
            GameSession s = m.account == null ? null : sessions.get(m.account);
            if (s == null) {
                continue;
            }
            PlayerRecord p = players.findByPlayerId(m.playerId);
            Map<Integer, Integer> played = p == null || p.guild == null
                    ? new LinkedHashMap<>() : p.guild.bossPlayTimes;
            s.send(MsgIds.S2C_UNION_BOSS_INFO, 0,
                    dump.unionBossInfo(views, m.playerId, myEmployWj(u, m.playerId), played));
        }
    }

    private void pushOwnerAndElders(WorldStore.UnionRecord u, int msgId, byte[] body) {
        pushOwnerAndElders(u, msgId, body, 0);
    }

    /**
     * 同 {@link #pushOwnerAndElders(WorldStore.UnionRecord, int, byte[])}，但排除某个 playerId。
     *
     * <p>用于「操作者已单独收到回包」的场景：1905 {@code CCMsgHandleUnionRequester_Ret} 是**单条**
     * 成员记录，客户端 {@code UnionManagerSystem.cs:478-489} 直接 {@code MemberInfo.Add(...)} 不去重
     * ⇒ 操作者收两遍会把新成员渲染成两行、{@code MemberInfo.Count} 变成 41/40。</p>
     */
    private void pushOwnerAndElders(WorldStore.UnionRecord u, int msgId, byte[] body, int excludePlayerId) {
        if (u == null) {
            return;
        }
        for (WorldStore.Member m : u.members) {
            if (excludePlayerId > 0 && m.playerId == excludePlayerId) {
                continue;
            }
            if ("owner".equals(m.job) || "elder".equals(m.job)) {
                push(m.account, msgId, body);
            }
        }
    }

    /**
     * 登录钩子：刷新公会成员列表里的「最后在线时刻」（1906 成员行 f8 OfflineTime 用它），
     * 并把积压的 1968「会长职务由 X 接任」补推给被让位的前会长。
     *
     * <p>1968 是 {@code CCMsgUnionNotifyOwnerChangeOnce}，客户端
     * {@code PlayGameState.cs:9051 OnNET_CCMsgUnionPresidentChange_Ret} 弹 StrTable 101282
     * 「由于你一周未登录游戏,会长职务由{0}接任」——收件人就是被让位的那位，所以只能等他上线再发。
     */
    public void touchLogin(PlayerRecord rec) {
        if (rec == null) {
            return;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        if (u == null) {
            return;
        }
        u.ensure();
        // 自愈：旧存档里 u.level 恒为 1，登录时按议事厅等级补正（不推包，避免登录握手中插队）。
        if (syncUnionLevel(u)) {
            world.saveUnions();
        }
        WorldStore.Member m = u.findMember(rec.playerId);
        if (m != null) {
            m.lastOnlineAt = System.currentTimeMillis();
            world.saveUnions();
        }
        if (u.pendingOwnerChangeFor == rec.playerId && !u.pendingOwnerChangeName.isEmpty()) {
            GameSession s = sessions.get(rec.account);
            if (s != null) {
                s.send(MsgIds.S2C_NOTIFY_OWNER_CHANGE, 0, dump.notifyOwnerChange(u.pendingOwnerChangeName));
            }
            u.pendingOwnerChangeFor = 0;
            u.pendingOwnerChangeName = "";
            world.saveUnions();
        }
    }

    /**
     * 每日 0 点：会长连续 {@value #OWNER_INACTIVE_MS} 未登录（对齐 StrTable 101282「一周未登录」）时自动让位。
     *
     * <p>让位对象 = 最近一次在线的成员（并列取贡献高者）；只有会长一人的公会不发、也不让位
     * （没有可接任的人）。让位后立刻给全公会推 1911 {@code CCMsgNotifyUnionOwner} + 1908
     * {@code CMsgUnionJob{newJob=owner}}（客户端 UnionManagerSystem.cs 收 1911 后把该 Guid 的
     * Job 置 3 并刷新成员列表），前会长的 1968 记在 {@code pendingOwnerChangeFor} 上、等他登录补推。
     */
    public void handoverInactiveOwners() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (WorldStore.UnionRecord u : world.unions()) {
            u.ensure();
            if (u.members.size() <= 1) {
                continue;
            }
            // 老存档的成员没有 lastOnlineAt：先补成 now，从本轮开始计时，避免一次性全体让位。
            boolean backfilled = false;
            for (WorldStore.Member m : u.members) {
                if (m.lastOnlineAt <= 0L) {
                    m.lastOnlineAt = now;
                    backfilled = true;
                }
            }
            if (backfilled) {
                changed = true;
                continue;
            }
            WorldStore.Member owner = null;
            for (WorldStore.Member m : u.members) {
                if ("owner".equals(m.job)) {
                    owner = m;
                    break;
                }
            }
            if (owner == null || now - owner.lastOnlineAt < OWNER_INACTIVE_MS) {
                continue;
            }
            WorldStore.Member next = null;
            for (WorldStore.Member m : u.members) {
                if (m == owner) {
                    continue;
                }
                if (next == null
                        || m.lastOnlineAt > next.lastOnlineAt
                        || (m.lastOnlineAt == next.lastOnlineAt && m.contribution > next.contribution)) {
                    next = m;
                }
            }
            if (next == null) {
                continue;
            }
            owner.job = "member";
            next.job = "owner";
            // 必须同时写玩家档：所有会长权限判定读的是 PlayerRecord.guild.job
            // （isOwner() = guildJobCode() == 3，isOwnerOrElder()），只改 WorldStore.Member.job
            // 会让新会长在服务端没有任何会长权限 —— 1508 转让、1513 解散、1507 任长老、1515 踢人、
            // 1523 升建筑、1533 重置 Boss 全被拒或静默无效；而客户端用 1906 成员表的 Job 覆盖
            // mEUnionJob（UnionManagerSystem.cs:182-190）照常显示「公会管理」按钮 ⇒ 点了没反应。
            // 旧会长档案也必须降级，否则他仍握着服务端权限（客户端却已藏起按钮）。
            PlayerRecord oldOwnerRec = players.get(owner.account);
            if (oldOwnerRec != null) {
                oldOwnerRec.guild.job = "member";
                players.save(oldOwnerRec);
            }
            PlayerRecord newOwnerRec = players.get(next.account);
            if (newOwnerRec != null) {
                newOwnerRec.guild.job = "owner";
                players.save(newOwnerRec);
            }
            u.pendingOwnerChangeFor = owner.playerId;
            u.pendingOwnerChangeName = next.name;
            pushUnion(u, MsgIds.S2C_NOTIFY_UNION_OWNER, dump.notifyUnionOwner(next.playerId), 0);
            pushUnion(u, MsgIds.S2C_UNION_JOB_UPDATE, dump.unionJobUpdate("owner"), next.playerId);
            pushUnion(u, MsgIds.S2C_UNION_DETAIL_RET, dump.unionDetail(u, syncMembers(u)), 0);
            // 系统邮件 24「公会队长转让通知」= 由于{0}一周未登陆游戏，会长职务由{1}接任。
            // 发件人「公会战管理员」、正文没有「你」⇒ 全公会广播（不只是新旧会长两人）。
            // 用户 m11483 #3「全公会广播 就行」已确认此范围。
            for (WorldStore.Member m : u.members) {
                mail.sendUnionOwnerChangeMail(m.account, owner.name, next.name, null);
            }
            changed = true;
            log.info("union {} owner {} inactive -> handover to {}", u.name, owner.name, next.name);
        }
        if (changed) {
            world.saveUnions();
        }
    }

    /** 建筑到点自动升级（服务端不依赖客户端倒计时）。 */
    private void settleUpgrades(WorldStore.UnionRecord u) {
        u.ensure();
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (int type = 1; type <= 7; type++) {
            long end = u.upgradeEndOf(type);
            if (end > 0L && end <= now) {
                int lv = buildingLevel(u, type);
                int max = unionCfg.maxBuildingLevel(type);
                u.buildings.put(Integer.valueOf(type), Integer.valueOf(Math.min(max, lv + 1)));
                u.buildingUpgradeEnd.remove(Integer.valueOf(type));
                changed = true;
            }
        }
        // 公会等级跟议事厅等级走（幂等，同时自愈旧存档里 u.level 恒为 1 的历史数据）。
        boolean levelChanged = syncUnionLevel(u);
        if (changed || levelChanged) {
            world.saveUnions();
        }
        if (levelChanged) {
            // 客户端 MainPlayer.cs:1009-1036 用 EUnionAttribute=4 更新 mUnionDetailInfo.Level；
            // 建筑面板 BuildingItem_InJianZhuMianBan.cs:167-187 用该值门控非议事厅建筑的升级按钮。
            pushUnion(u, MsgIds.S2C_UNION_ATTRI_UPDATE, dump.unionAttriUpdate(4, u.level, ""), 0);
        }
        if (changed) {
            // 任何建筑到点都要广播 1919（客户端 UM:346-371 逐建筑 SetDengJi、᝺:54-91 刷据点牌子 Lv）。
            // 改前只在议事厅(type=1)到点时推：其他建筑到点后，停在公会基地场景内的其他成员
            // 看到的牌子等级一直是旧值，必须出/进场或自己开建筑面板（BI:278 倒计时归零自动发 1522）才自纠。
            pushUnion(u, MsgIds.S2C_UNION_BUILDINGS, dump.unionBuildings(u.buildings, u.buildingUpgradeEnd), 0);
        }
    }

    /**
     * 公会等级 = 议事厅等级（幂等同步，返回是否发生变化）。
     *
     * <p>依据 <code>tables\UnionBuildingLevelUp.txt</code> 第 4 行表头「到下一级需要的议事厅等级」列：
     * 议事厅(type=1) 自身每级要求 hallNeed 恒等于自己当前等级（1→2 需 1、2→3 需 2…14→15 需 14），
     * 其他建筑则要求议事厅等级（厨房 1→2 需议事厅 2、训练场 1→2 需议事厅 5…），且议事厅各级描述
     * 「厨房可提升为2级建筑」「训练场可提升为2级建筑」与之逐条吻合 ⇒ 公会等级就是议事厅等级。</p>
     *
     * <p>客户端把 1906 的 f8 存进 <code>mUnionDetailInfo.Level</code>（PlayGameState.cs:4079），
     * 建筑面板 <code>BuildingItem_InJianZhuMianBan.cs:148-163</code> 用它显示「公会等级需求 Lv.N」红字、
     * <code>:167-187 RefreshShengJiButton</code> 用它门控非议事厅建筑的升级按钮；服务端升级门控用的却是
     * <code>buildingLevel(u,1)</code>（见 onBuildingLevelUp）—— 不同步会让议事厅已升到 N 级时其他建筑的
     * 升级按钮在客户端仍然隐藏。</p>
     */
    private boolean syncUnionLevel(WorldStore.UnionRecord u) {
        if (u == null) {
            return false;
        }
        int hall = buildingLevel(u, 1);
        if (u.level == hall) {
            return false;
        }
        u.level = hall;
        return true;
    }

    private int buildingLevel(WorldStore.UnionRecord u, int type) {
        if (u == null) {
            return 1;
        }
        u.ensure();
        Integer lv = u.buildings.get(Integer.valueOf(type));
        return lv == null || lv.intValue() <= 0 ? 1 : lv.intValue();
    }

    /**
     * 建筑「解锁 + 使用条件」闸门，两半都要满足：
     *
     * <ol>
     *   <li><b>解锁</b>：本公会议事厅（建筑 type 1）等级 ≥ 表「XX解锁所需议事厅等级」
     *       （出厂 {@code tables\Union.txt:21-32}：厨房 1 / 训练场 3 / 商城 1 / 马厩 2 / 作战室 1 /
     *       医院 4，议事厅自身 0）。客户端在点建筑时先比
     *       {@code UnionProperty.mBuildingLimits[type].mYiShiTingLevelRequire} 与本地议事厅等级
     *       （APK {@code out2\Client\᝝.cs:363-503}、{@code BuildingItem_InJianZhuMianBan.cs:50}），
     *       不足弹 {@code Code.txt} 100687/100688；假服此前**只**校验了第 2 条，
     *       {@code UnionCfg.unlockHallLevel} 解析后无调用点（用户本轮「表键消费清扫」发现）。</li>
     *   <li><b>使用</b>：玩家自身等级（= 攻略组等级）≥ 表「使用XX所需攻略组等级」
     *       （厨房 15 / 训练场 28 / 商城 20 / 马厩 25 / 作战室 15 / 医院 28）。客户端
     *       {@code MobileGameDemo\UnionBaseSceneManager.cs:390-392/423-429/449-455/475-481/505-511/
     *       530-536} 与 {@code PlayGameState.cs:7600-7601} 拿
     *       {@code PlayGameState.msMainPlayer.Attribute.mLevel} 比这个值 ⇒ 攻略组等级 = 玩家自身等级
     *       （{@code MainPlayerSystem.cs:203} 的 {@code "Lv." + mLevel} 佐证）。</li>
     * </ol>
     *
     * <p>假服没有通用飘字下发机制，闸门只能表现为拒绝/回空（客户端本地已先弹 100687）。
     */
    boolean canUseBuilding(PlayerRecord rec, int type) {
        if (rec == null || rec.level < unionCfg.usePlayerLevel(type)) {
            return false;
        }
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        return u != null && buildingLevel(u, 1) >= unionCfg.unlockHallLevel(type);
    }

    private int bossMaxHp(String ori) {
        if (fight != null && ori != null) {
            FightConfigTables.MonsterCfg m = fight.monsterByOri(ori);
            if (m != null && m.maxHp > 0) {
                return m.maxHp;
            }
        }
        return BOSS_HP_FALLBACK;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private List<PlayerDumpService.BossView> bossViews(WorldStore.UnionRecord u) {
        List<PlayerDumpService.BossView> list = new ArrayList<>();
        if (u == null) {
            return list;
        }
        u.ensure();
        // 解锁列名是「开启章节作战室等级需求」⇒ 看**作战室**(EUnionBuildingType 6)等级，
        // 不是议事厅(1)。同文件 myEmployWj 也用 employersOf(6)。
        int warRoom = buildingLevel(u, 6);
        for (UnionCfg.BossRow row : unionCfg.unlockedBosses(warRoom)) {
            list.add(bossView(u, row.chapter));
        }
        return list;
    }

    private PlayerDumpService.BossView bossView(WorldStore.UnionRecord u, int chapter) {
        PlayerDumpService.BossView v = new PlayerDumpService.BossView();
        UnionCfg.BossRow row = unionCfg.boss(chapter);
        v.chapter = chapter;
        v.ori = row == null ? "UnionBoss1" : row.ori;
        v.curHp = u == null ? bossMaxHp(v.ori) : u.bossHpOf(chapter, bossMaxHp(v.ori));
        v.hurt = u == null ? new ArrayList<>() : u.damageList(chapter);
        return v;
    }

    private int rankOf(WorldStore.UnionRecord u, int chapter, int playerId) {
        List<WorldStore.DamageEntry> list = u.damageList(chapter);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).playerId == playerId) {
                return i + 1;
            }
        }
        return list.size() + 1;
    }

    /**
     * 1923 f2 {@code myEmploy} = **我自己雇的那名佣兵**（playerGuid + wujiangIndex）。
     *
     * <p>客户端拿它拼 {@code employerGuid = playerGUID + wjIndex}（{@code UnionBossWarInfo.cs:49-51}），
     * 再用来（1）在佣兵列表里高亮「我正在用的这名」（{@code EmBattleSystem.cs:1321}）、
     * （2）把战斗阵容里的这个 GUID 换成佣兵本人（{@code EmBattleSystem.cs:1997-2074}）、
     * （3）结算面板按 GUID 找佣兵那一行（{@code GongHuiZhanDouJieSuan.cs:48}）。</p>
     *
     * <p>所以匹配的是**雇佣者**（{@code hiredBy}）而不是武将属主（{@code playerId}）：付费雇佣
     * （1527）时属主是别人，用 {@code playerId} 匹配会恒返回 0 ⇒ 客户端认为「我没带佣兵」，
     * 雇佣来的佣兵在战斗里根本不生效；反过来别人雇了我的武将时又会误判成我带了佣兵。
     * {@code hiredBy == 0} 是旧存档（{@code slotOf} 之前）的兜底。</p>
     */
    private int myEmployWj(WorldStore.UnionRecord u, int playerId) {
        if (u == null) {
            return 0;
        }
        for (WorldStore.Employer e : u.employersOf(6)) {
            if (e.hiredBy == playerId || (e.hiredBy == 0 && e.playerId == playerId)) {
                return e.wjIndex;
            }
        }
        return 0;
    }

    /** 佣兵档位：按战力上限落在 UnionWJSubsidiary.txt 的哪一档。 */
    private int subsidiaryLevel(int profession, int fightPower) {
        int level = 1;
        for (int i = 1; i <= 8; i++) {
            UnionCfg.SubsidiaryRow row = unionCfg.subsidiary(profession, i);
            if (row == null) {
                break;
            }
            level = i;
            if (fightPower <= row.fightPowerCap) {
                break;
            }
        }
        return level;
    }

    /**
     * 作战室拍卖跨日重置：**先结算昨天的货架**（服务器 20:00 没跑、或整夜停机时的兜底），
     * 再清空重挂。顺序不能反：先 clear 会让昨天拍中的战利品凭空消失。
     */
    private void ensureAuction(WorldStore.UnionRecord u) {
        u.ensure();
        String day = PlayerDumpService.now().substring(0, 10);
        if (!day.equals(u.auctionDay)) {
            settleAuctionLots(u, "rollover");
            u.auctionDay = day;
            u.auctionLots.clear();
            world.saveUnions();
        }
    }

    /**
     * 拍卖到点结算（{@code Union.txt}「作战室拍卖结算时间（24小时制小时）」= 20 点）：
     * 每件有最高出价者的战利品给拍中者发 **mailType 4**「战利品拍卖胜出」
     * （Sys_MailConfig 第4行，附件 = 战利品本体）——邮箱是唯一交付口，拍中的物品不再走
     * 任何内联发放。落败者的勇气币在出价被顶掉时就已经退了（mailType 5），这里不重复处理。
     *
     * <p>由 {@code GlobalServerScheduler} 分钟级调用；每个公会**同一天只结算一次**，去重键是
     * 落档的 {@link WorldStore.UnionRecord#auctionSettledDay}。改前用的是进程内存键
     * {@code auctionSettledDay}：20 点后重启会让当天 20 点之后新入架的掉落被提前结算
     * （21:00 击杀 → 21:10 重启 → 21:11 就发奖，货架没机会被别人出价）。
     * 注意**空货架也要打标记**，否则重启后当天新入架的掉落同样会被提前结算。
     * 结算时刻判定走 {@link #secOfDay()}（与押镖/公会战窗口同一口径），可被测试钉住。
     */
    public void settleAuctions(String why) {
        int hour = Math.max(0, Math.min(23, unionCfg.auctionSettleHour()));
        // 走 secOfDay() 而不是 GameTime.now().getHour()：与押镖/公会战窗口同一口径，
        // 能被 clockSecOverride 钉住（生产无 override 时等价），调度入口因此可被测试直接调用。
        if (secOfDay() < hour * 3600) {
            return;
        }
        String day = PlayerDumpService.now().substring(0, 10);
        int n = 0;
        boolean dirty = false;
        for (WorldStore.UnionRecord u : world.unions()) {
            u.ensure();
            if (day.equals(u.auctionSettledDay)) {
                continue;
            }
            u.auctionSettledDay = day;
            dirty = true;
            if (u.auctionLots.isEmpty()) {
                continue;
            }
            settleAuctionLots(u, why);
            n++;
        }
        if (dirty) {
            world.saveUnions();
        }
        if (n > 0) {
            log.info("auction settle {} unions={}", why, n);
        }
    }

    /**
     * 结算一个公会的货架：拍中者收 mailType 4（附件 = 战利品），随后清空。
     * 无人出价的条目直接流拍丢弃（真服是否保留到次日无表可依，见 docs 待拍板项）。
     */
    /** 结算一个公会的全部货架。包级可见，便于测试绕开 20 点时钟闸门（同 {@link #settlePvpBattle}）。 */
    void settleAuctionLots(WorldStore.UnionRecord u, String why) {
        u.ensure();
        int n = 0;
        for (WorldStore.AuctionLot lot : u.auctionLots) {
            if (lot.topBidder == null || lot.topBidder.isEmpty() || lot.topBidderId <= 0) {
                continue;
            }
            PlayerRecord win = players.findByPlayerId(lot.topBidderId);
            if (win == null) {
                // 玩家档已不在（删号/迁移）：退不了也发不了，只能记账留痕
                log.warn("auction settle {} lot={} winner={} player gone", why, lot.dropId,
                        lot.topBidder);
                continue;
            }
            mail.sendAuctionWinMail(win.account, lootNameOf(lot), lot.ori, lot.count, null);
            n++;
        }
        u.auctionLots.clear();
        world.saveUnions();
        if (n > 0) {
            log.info("union {} auction settle {} lots={}", u.id, why, n);
        }
    }

    /** 战利品展示名：显示名（GoodsList/EquipmentList col2）+ 数量；查不到才退回 ori。 */
    private String lootNameOf(WorldStore.AuctionLot lot) {
        if (lot == null || lot.ori == null || lot.ori.isEmpty()) {
            return "";
        }
        String name = economy.goodsDisplayName(lot.ori);
        if (name.isEmpty()) {
            name = cultivate.equipDisplayName(lot.ori);
        }
        if (name.isEmpty()) {
            name = lot.ori;
        }
        return lot.count > 1 ? name + "x" + lot.count : name;
    }

    private void seedAuction(WorldStore.UnionRecord u, UnionCfg.BossRow row) {
        u.ensure();
        // 改前货架非空就整批 return ⇒ 当天第二次击杀（1533 重置后再杀、或接着杀下一章）的掉落
        // **永远不入架**，只有第一次击杀的几件能被拍卖。改为追加，dropId 从现有最大值续号：
        // 1531 出价用 f1 dropId 定位行（客户端 DealOutInfo.dropID），必须货架内唯一。
        int dropId = 1;
        for (WorldStore.AuctionLot exist : u.auctionLots) {
            if (exist.dropId >= dropId) {
                dropId = exist.dropId + 1;
            }
        }
        for (UnionCfg.Drop d : row.drops) {
            WorldStore.AuctionLot lot = new WorldStore.AuctionLot();
            lot.dropId = dropId++;
            lot.ori = d.ori;
            lot.count = d.count;
            // 起拍价 = GoodsList/EquipmentList col8「公会拍卖基础价格（勇气币）」× 数量；
            // 两表都没填基价才退回 0（0 起拍 = 谁先出价谁得）。
            lot.price = auctionBasePrice(d.ori, d.count);
            u.auctionLots.add(lot);
        }
        world.saveUnions();
    }

    /**
     * 公会拍卖起拍价（勇气币）：{@code GoodsList.txt} col8；装备（EQ*）不在 GoodsList，
     * 走 {@code EquipmentList.txt} col8（EQ0031 稀有饰品 = 650）。0 = 两表都没基价。
     */
    private int auctionBasePrice(String ori, int count) {
        int unit = economy.auctionPrice(ori);
        if (unit <= 0) {
            unit = cultivate.equipAuctionPrice(ori);
        }
        if (unit <= 0) {
            return 0;
        }
        return unit * Math.max(1, count);
    }

    private WorldStore.AuctionLot findLot(WorldStore.UnionRecord u, int dropId) {
        for (WorldStore.AuctionLot lot : u.auctionLots) {
            if (lot.dropId == dropId) {
                return lot;
            }
        }
        return null;
    }

    /**
     * 训练场坑位数（{@code UnionXunLianPits.txt}「训练场等级 → 最大坑位」= 1 级 2 个 … 7 级 8 个）。
     * 客户端 {@code UnionTrainRoomInfo.cs:18-19} 用同一张表铺 {@code listTrainInfo}；表缺键时客户端
     * 得 0 个坑，服务端保底 1 个以免训练彻底不可用。
     */
    private int trainPitsOf(WorldStore.UnionRecord u) {
        int level = u == null ? 1 : buildingLevel(u, TRAIN_BUILDING);
        return Math.max(1, unionCfg.trainPits(level));
    }

    /** 按武将配置 ID 找训练坑位；{@code wjIndex<=0} 时仅在恰好一个坑位时返回它（兼容空请求体）。 */
    private PlayerRecord.Economy.TrainSlot findTrainSlot(PlayerRecord rec, int wjIndex) {
        if (rec.economy.trainSlots.isEmpty()) {
            return null;
        }
        if (wjIndex <= 0) {
            return rec.economy.trainSlots.size() == 1 ? rec.economy.trainSlots.get(0) : null;
        }
        for (PlayerRecord.Economy.TrainSlot s : rec.economy.trainSlots) {
            if (s.wjIndex == wjIndex) {
                return s;
            }
        }
        return null;
    }

    /** 坑位剩余秒数：由 startedAt 与**本次实际时长**（{@link PlayerRecord.Economy.TrainSlot#totalSec}）推算。 */
    private int leftSecOf(PlayerRecord.Economy.TrainSlot slot) {
        if (slot.startedAt <= 0) {
            return Math.max(0, slot.leftSec);
        }
        long elapsed = Math.max(0L, (System.currentTimeMillis() - slot.startedAt) / 1000L);
        int dur = slot.totalSec > 0 ? slot.totalSec : unionCfg.trainSec();
        return (int) Math.max(0L, dur - elapsed);
    }

    /** 教官满额缩短所需的战力：{@code UnionWJSubsidiary.txt} 教练最高档（战力上限 999999 那一档）的下限 89999。 */
    private static final int COACH_FULL_SHORTEN_POWER = 90000;

    /**
     * 教官按战力缩短训练时长的百分比（用户 m20090 #2 拍板）：
     * {@code 5% + 45% × min(1, 战力 / 90000)} ⇒ 下限 **5%**、上限 **50%**，战力越高缩得越多。
     *
     * <p>锚点正好落在 APK 教练四档的战力边界（{@code UnionWJSubsidiary.txt} 该级战力上限
     * 29999 / 59999 / 89999 / 999999）上：0 战力 = 5%、29999 = 20%、59999 = 35%、89999 及以上 = 50%。
     */
    static int coachShortenPercent(int fightPower) {
        double pct = 5d + 45d * Math.min(1d, Math.max(0, fightPower) / (double) COACH_FULL_SHORTEN_POWER);
        return (int) Math.round(pct);
    }

    /**
     * 本次训练的**实际时长**：没请教官（{@code employerGuid/employerWj} 任一 ≤ 0）= 表值整场；
     * 请了教官则按 {@link #coachShortenPercent} 缩短。**只缩倒计时**，经验与费用仍按整场表值算。
     */
    private int trainSecWithCoach(WorldStore.UnionRecord u, int employerGuid, int employerWj) {
        int full = unionCfg.trainSec();
        if (employerGuid <= 0 || employerWj <= 0) {
            return full;
        }
        int pct = coachShortenPercent(employerFightPower(u, TRAIN_BUILDING, employerGuid, employerWj));
        return Math.max(1, (int) Math.round(full * (100 - pct) / 100d));
    }

    /**
     * 训练经验：{@code UnionXunLian.txt} 的 {@code (A+B×玩家等级^C)×(房间倍率+教练加成)}。
     * 坑位数与顺序照 {@code UnionXunLianPits.txt}（客户端 {@code UnionTrainRoomInfo.cs:27-47} 按
     * {@code listTrainInfo[j] = wjInfos[j]} 逐坑覆盖，空坑 wjIndex=0）。
     */
    private List<PlayerDumpService.TrainSlot> trainSlots(PlayerRecord rec) {
        WorldStore.UnionRecord u = world.findUnion(rec.guild.id);
        int pits = trainPitsOf(u);
        List<PlayerDumpService.TrainSlot> slots = new ArrayList<>();
        for (int i = 0; i < pits; i++) {
            PlayerDumpService.TrainSlot s = new PlayerDumpService.TrainSlot();
            if (i < rec.economy.trainSlots.size()) {
                PlayerRecord.Economy.TrainSlot src = rec.economy.trainSlots.get(i);
                s.wjIndex = src.wjIndex;
                s.trainingType = src.type;
                s.leftTime = leftSecOf(src);
                s.employerWjIndex = src.employerWj;
                PlayerRecord.Hero h = rec.findHeroByIndex(src.employerWj);
                s.employerWjLevel = h == null ? 1 : h.level;
                s.employerWjStage = h == null ? 0 : h.stage;
                s.employerWjStars = h == null ? 0 : h.stars;
                s.employerRatio = trainerRatioOf(u, src.employerGuid, src.employerWj);
                // f2 totalExp 是客户端训练面板上显示的总经验（TrainInfo.cs:30 同式），
                // 不是「已完成」值 —— 改前直接写 rec.economy.trainTotalExp，训练中恒 0、面板显示 0 经验。
                s.totalExp = (int) unionCfg.trainTotalExp(rec.level, src.type, s.employerRatio,
                        unionCfg.trainSec());
            }
            slots.add(s);
        }
        return slots;
    }

    /**
     * 训练倒计时推进：遍历**所有坑位**，到点的坑把经验算给武将、清坑，并把本次所有完成项
     * 合成**一包** 1935（f1 是 repeated {@code CWJXunLianResult}）。
     */
    private void tickTrain(PlayerRecord rec) {
        if (rec.economy.trainSlots.isEmpty()) {
            return;
        }
        List<PlayerDumpService.TrainResult> done = new ArrayList<>();
        List<PlayerRecord.Hero> trained = new ArrayList<>();
        Iterator<PlayerRecord.Economy.TrainSlot> it = rec.economy.trainSlots.iterator();
        while (it.hasNext()) {
            PlayerRecord.Economy.TrainSlot slot = it.next();
            slot.leftSec = leftSecOf(slot);
            if (slot.leftSec > 0) {
                continue;
            }
            PlayerRecord.Hero h = rec.findHeroByIndex(slot.wjIndex);
            int preLevel = h == null ? 0 : h.level;
            // 教练「经验提速（万分比）」1000/2000/3000/4000 ⇒ 0.1/0.2/0.3/0.4
            // （客户端 UnionWJSubsidiaryProperty.cs:120 = propertyParam/10000f，UnionTrainRoomInfo.cs:119 显示同值）。
            // 它与训练室倍率**相加**：TrainInfo.cs:30 totalExp = num2 * (roomRatio + employerRatio)。
            float trainer = trainerRatioOf(world.findUnion(rec.guild.id), slot.employerGuid, slot.employerWj);
            int exp = (int) unionCfg.trainTotalExp(rec.level, slot.type, trainer, unionCfg.trainSec());
            if (h != null) {
                // 必须走 ProgressService.addWjExp（含升级循环）：改前 h.exp += exp 不升级，
                // 1935 的 preLevel/curLevel 恒相等，客户端「练后等级」预览与 From/To 显示同级。
                progress.addWjExp(rec, h, exp);
                trained.add(h);
            }
            done.add(new PlayerDumpService.TrainResult(slot.wjIndex, preLevel,
                    h == null ? 0 : h.level, exp));
            it.remove();
        }
        players.save(rec);
        if (!done.isEmpty()) {
            push(rec.account, MsgIds.S2C_TRAIN_FINISH, dump.trainFinish(done));
            // 训练完成必须推 601 刷武将面板（子代理 0282aae7 取证）：客户端把经验/等级**只**认 601
            // （`ᝁ.cs:69` → `MainPlayer.cs:1012-1027` 的 type1 CUREXP / type2 CURLEVEL），1935 的
            // preLevel/curLevel 只当弹窗文案 ⇒ 改前只发 1935，面板经验条停在训练前、升级也不刷战力。
            // 副本四条路径（DungeonService.java:393/451/519/550）都调 pushWjProgress，训练路径漏了。
            for (PlayerRecord.Hero h : trained) {
                push(rec.account, MsgIds.S2C_WUJIANG_ATTRI_UPDATE, dump.wuJiangAttri(h.id, 1, h.exp));
                push(rec.account, MsgIds.S2C_WUJIANG_ATTRI_UPDATE, dump.wuJiangAttri(h.id, 2, h.level));
                push(rec.account, MsgIds.S2C_UPDATE_ALL_WUJIANG_FIGHT_POWER,
                        dump.updateWuJiangFightPower(rec, h));
            }
            log.info("{} train finished count={} exp={}", rec.account, done.size(),
                    done.get(0).totalExp);
        }
    }

    /** 教练经验提速系数：按请求体/存档里的候选（playerGuid + wujiangIndex）查 {@code UnionWJSubsidiary} 教练档。 */
    float trainerRatioOf(WorldStore.UnionRecord u, int employerGuid, int employerWj) {
        int attr = employerAttr(u, TRAIN_BUILDING, 1, employerGuid, employerWj);
        return attr <= 0 ? 0f : attr / 10000f;
    }

    /**
     * 领取建筑收益并推进基线。基线只按**整小时**推进，零头留给下次累计 ——
     * 改前这里无条件 {@code put(type, now)}，而 {@link #pendingBuildingGold} 按整小时取整，
     * 于是每次领取都会把不足 1 小时的收益静默丢掉（频繁点领取的玩家几乎领不到钱）。
     */
    private int collectBuildingGold(PlayerRecord rec, WorldStore.UnionRecord u, int type) {
        int gold = pendingBuildingGold(rec, u, type);
        rec.ensureCollections();
        Integer key = Integer.valueOf(type);
        if (gold > 0) {
            // 累计已领收益：与 pendingBuildingGold 的「总收益上限」判定配对。
            long total = rec.economy.buildingProfitTotal.getOrDefault(key, Long.valueOf(0L)).longValue();
            rec.economy.buildingProfitTotal.put(key, Long.valueOf(total + gold));
        }
        Long last = rec.economy.buildingProfitAt.get(key);
        long now = System.currentTimeMillis();
        if (last == null || last.longValue() <= 0L) {
            rec.economy.buildingProfitAt.put(key, Long.valueOf(now));
            return gold;
        }
        long hours = Math.max(0L, (now - last.longValue()) / 3600000L);
        if (hours > 0L) {
            rec.economy.buildingProfitAt.put(key,
                    Long.valueOf(last.longValue() + hours * 3600000L));
        }
        return gold;
    }

    private int pendingBuildingGold(PlayerRecord rec, WorldStore.UnionRecord u, int type) {
        rec.ensureCollections();
        int level = buildingLevel(u, type);
        int choulao = economy.buildingChoulao(type, level);
        if (choulao <= 0) {
            rec.economy.buildingProfitAt.putIfAbsent(Integer.valueOf(type), Long.valueOf(System.currentTimeMillis()));
            return 0;
        }
        Long last = rec.economy.buildingProfitAt.get(Integer.valueOf(type));
        long now = System.currentTimeMillis();
        if (last == null || last.longValue() <= 0L) {
            rec.economy.buildingProfitAt.put(Integer.valueOf(type), Long.valueOf(now));
            return 0;
        }
        long hours = Math.max(0L, (now - last.longValue()) / 3600000L);
        if (hours <= 0L) {
            return 0;
        }
        long gold = hours * (long) choulao;
        // Union.txt「单个建筑玩家**总**收益上限」= 3000000：键名说的是总收益，且 3000000 ÷ 1500/小时
        // （UnionBuildingLevelUp.txt 第 8 列全表最大）= 2000 小时 ≈ 83 天，作单次领取上限不可达
        // ⇒ 按「该建筑累计已领收益的上限」处理（改前只按单次封顶，等于没有上限）。
        long cap = unionCfg.buildingProfitCap();
        long total = rec.economy.buildingProfitTotal.getOrDefault(Integer.valueOf(type), Long.valueOf(0L))
                .longValue();
        long remain = Math.max(0L, cap - total);
        if (gold > remain) {
            gold = remain;
        }
        return (int) gold;
    }
}
