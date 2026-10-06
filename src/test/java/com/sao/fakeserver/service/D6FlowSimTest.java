package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.store.WorldStore;
import com.sao.fakeserver.table.UnionCfg;
import com.sao.fakeserver.util.GameTime;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * D6（公会）按 APK 真实序列走一遍的回归测试。
 *
 * <p>钉的是「客户端按 APK 的调用序列发过来时，服务端回的包/改的档是不是客户端期待的那个」，
 * 每条断言注释写清 APK 侧对应代码位置（proto 字段号 / 客户端消费点）。
 *
 * <p>对照的 APK 来源：proto {@code F:\workspace\daojian\SAO\pyfoot\tmp_msgdll\NetProto\}、
 * 客户端 {@code F:\workspace\daojian\SAO\decompiled\client-src\}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.data-dir=target/test-data/players-d6-flow",
        "sao.world-dir=target/test-data/world-d6-flow",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class D6FlowSimTest {
    private static final String OWNER = "test-d6-owner";
    private static final String MEMBER = "test-d6-member";
    /** 跨公会 PvP 用例里的第二个公会会长（另一个账号）。 */
    private static final String RIVAL = "test-d6-rival";
    /** 随机配对用例里的第三个公会会长（用来验证「不再恒取存档第一个」）。 */
    private static final String RIVAL2 = "test-d6-rival2";

    @Autowired
    private PlayerStore store;
    @Autowired
    private PlayerDumpService dump;
    @Autowired
    private UnionService union;
    @Autowired
    private WorldStore world;
    @Autowired
    private SessionHub hub;
    @Autowired
    private UnionCfg unionCfg;
    @Autowired
    private com.sao.fakeserver.table.EconomyTables economy;
    @Autowired
    private ShopService shop;
    @Autowired
    private ProgressService progress;
    /** 存档目录必须走 SaoDirs 解析后的真实路径：{@code target/test-data/…} 在 cwd 侧不存在时
     *  {@link com.sao.fakeserver.config.SaoDirs#resolve(String)} 会落到 jar 旁边
     *  （即 {@code target/classes/target/test-data/…}），硬编码相对路径会 NoSuchFileException。 */
    @Autowired
    private com.sao.fakeserver.config.SaoProperties props;

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @AfterEach
    public void cleanup() {
        for (EmbeddedChannel c : channels) {
            c.finishAndReleaseAll();
        }
        channels.clear();
    }

    /** 创建公会：等级/钻石不足时拒绝，够了才扣 Union.txt「创建公会RMB消耗」。 */
    @Test
    public void createUnionGatesLevelAndDiamond() {
        resetWorld();
        PlayerRecord rec = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);

        rec.level = 1;
        store.save(rec);
        union.onCreate(session, new GamePacket(MsgIds.C2S_CREATE_UNION, 1, Pb.write(o -> {
            Pb.stringAlways(o, 1, "测试公会");
            Pb.stringAlways(o, 2, "icon1");
        })));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_CREATE_UNION_RET);
        Assertions.assertNotNull(ret);
        Assertions.assertFalse(Pb.read(ret.body).getBool(1), "等级不足必须拒绝");

        rec.level = 40;
        rec.diamond = 5000;
        store.save(rec);
        union.onCreate(session, new GamePacket(MsgIds.C2S_CREATE_UNION, 2, Pb.write(o -> {
            Pb.stringAlways(o, 1, "测试公会");
            Pb.stringAlways(o, 2, "icon1");
        })));
        ret = pick(drain(ch), MsgIds.S2C_CREATE_UNION_RET);
        Assertions.assertNotNull(ret);
        Assertions.assertTrue(Pb.read(ret.body).getBool(1), "等级/钻石足够必须成功");
        Assertions.assertEquals("测试公会", Pb.read(ret.body).getString(2));
        Assertions.assertEquals(5000 - unionCfg.createRmb(), rec.diamond);
        Assertions.assertFalse(rec.guild.id.isEmpty(), "入档公会 id");
    }

    /**
     * joinType=direct 时 1503 直接入会；1906 详情要带真实在线标记。
     * APK：{@code CUnionMemberInfo.7 IsOnline} / {@code .8 OfflineTime}。
     */
    @Test
    public void directJoinAddsMemberAndDetailReportsOnline() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);

        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "直属会", "direct");

        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        GamePacket join = pick(drain(memberCh), MsgIds.S2C_JOIN_UNION_RET);
        Assertions.assertNotNull(join);
        Assertions.assertTrue(Pb.read(join.body).getBool(1), "direct 公会应直接入会");
        Assertions.assertEquals(u.id, member.guild.id);
        Assertions.assertEquals(1, PlayerRecord.jobCode(member.guild.job), "普通成员 job=member");

        union.onDetail(ownerSession, new GamePacket(MsgIds.C2S_UNION_DETAIL, 2,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        GamePacket detail = pick(drain(ownerCh), MsgIds.S2C_UNION_DETAIL_RET);
        Assertions.assertNotNull(detail);
        Pb.Fields f = Pb.read(detail.body);
        Assertions.assertEquals(2, f.getInt(4, 0), "MemberCount 应为 2");
        List<byte[]> members = Pb.read(f.getBytes(14)).getBytesList(1);
        Assertions.assertEquals(2, members.size());
        int online = 0;
        for (byte[] m : members) {
            if (Pb.read(m).getBool(7)) {
                online++;
            }
        }
        Assertions.assertEquals(2, online, "两个会话都在线");
    }

    /**
     * joinType=verify：入会先落申请列表（1907 给会长，f4 = 战力），1506 同意后申请人收 1904+1908。
     * APK：{@code CMsgUnionRequester{1 Guid,2 Name,3 Level,4 FightPower}}。
     */
    @Test
    public void verifyJoinFillsRequesterListAndApprovalAddsMember() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);

        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "审批会", "verify");

        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        Pb.Fields submitted = Pb.read(pick(drain(memberCh), MsgIds.S2C_JOIN_UNION_RET).body);
        Assertions.assertFalse(submitted.getBool(1), "审批制不能直接入会");
        // f3 必须显式写 0：proto 侧缺字段会回落到 EFJIUR_ALREADYHAVEUNION(1)，
        // 客户端 UnionCreateAndJoinSystem.cs:185-187 就会弹 100708「已经有了公会」。
        Assertions.assertEquals(0, submitted.getInt(3, -1),
                "1904 f3 必须显式写 0（省略会回落成枚举值 1 = 已经有了公会）");

        List<GamePacket> ownerOut = drain(ownerCh);
        GamePacket req = pick(ownerOut, MsgIds.S2C_UNION_REQUESTERS_RET);
        Assertions.assertNotNull(req, "会长应收到申请列表推送");
        Assertions.assertTrue(hasIn(ownerOut, MsgIds.S2C_UNION_JOIN_REQUEST_TIPS), "1912 红点");
        List<byte[]> requesters = Pb.read(req.body).getBytesList(1);
        Assertions.assertEquals(1, requesters.size());
        Pb.Fields r0 = Pb.read(requesters.get(0));
        Assertions.assertEquals(member.playerId, r0.getInt(1, 0));
        Assertions.assertEquals(member.roleName, r0.getString(2));
        Assertions.assertEquals(1000, r0.getInt(4, 0), "f4 必须是战力（CMsgUnionRequester.FightPower）");

        union.onHandleRequester(ownerSession, new GamePacket(MsgIds.C2S_HANDLE_UNION_REQUESTER, 2,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, member.playerId);
                    Pb.boolAlways(o, 2, true);
                })));
        Pb.Fields h = Pb.read(pick(drain(ownerCh), MsgIds.S2C_HANDLE_REQUESTER_RET).body);
        Assertions.assertTrue(h.getBool(1), "1905 Success");
        Assertions.assertEquals(member.playerId, Pb.read(h.getBytes(3)).getInt(1, 0), "1905 f3 newMember");

        List<GamePacket> memberOut = drain(memberCh);
        Assertions.assertTrue(hasIn(memberOut, MsgIds.S2C_JOIN_UNION_RET), "申请人收 1904");
        Assertions.assertTrue(hasIn(memberOut, MsgIds.S2C_UNION_JOB_UPDATE), "申请人收 1908");
        Assertions.assertEquals(u.id, member.guild.id);
        Assertions.assertTrue(u.requesters.isEmpty(), "同意后申请列表清空");
    }

    /**
     * 1506 Agree=false：APK 客户端拒绝路径 {@code UnionManagerSystem.cs:773-787 OnClickRefuseApply}
     * 在本地 {@code DestroyImmediate} 删掉申请行、不依赖任何回包；而 1905 两条路都走不通 ——
     * {@code Success=true} 且 f3 {@code newMember} 缺失时 protobuf-net 给 null，
     * {@code UnionManagerSystem.cs:478 unionMemberInfo.Guid = …newMember.Guid} 直接 NRE；
     * {@code Success=false} 只能走 {@code GetJoinFailReasonMsg}（{@code :1207-1219} 只映射
     * 1/2/3，枚举 {@code EFailJoinInUnionReason} 里没有「被会长拒绝」）。
     * ⇒ 服务端拒绝时**不回 1905**，只推 1904（申请人）+ 1907（会长/长老刷新申请列表）。
     */
    @Test
    public void refusedJoinRequestIsNotAnsweredWith1905() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);

        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "审批会", "verify");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(memberCh);
        drain(ownerCh);

        union.onHandleRequester(ownerSession, new GamePacket(MsgIds.C2S_HANDLE_UNION_REQUESTER, 2,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, member.playerId);
                    Pb.boolAlways(o, 2, false);
                })));

        List<GamePacket> ownerOut = drain(ownerCh);
        Assertions.assertFalse(hasIn(ownerOut, MsgIds.S2C_HANDLE_REQUESTER_RET),
                "拒绝时不能回 1905（Success=true 无 f3 会 NRE，Success=false 没有对应文案）");
        Assertions.assertTrue(hasIn(ownerOut, MsgIds.S2C_UNION_REQUESTERS_RET), "拒绝后会长仍收 1907 刷新");
        Assertions.assertTrue(Pb.read(pick(ownerOut, MsgIds.S2C_UNION_REQUESTERS_RET).body).getBytesList(1).isEmpty(),
                "拒绝后申请队列为空");

        Pb.Fields notified = Pb.read(pick(drain(memberCh), MsgIds.S2C_JOIN_UNION_RET).body);
        Assertions.assertFalse(notified.getBool(1), "申请人收到失败通知");
        Assertions.assertEquals(2, notified.getInt(3, -1), "1904 f3 = 2（EFJIUR_NOTPERMITEJOIN）");
        Assertions.assertNotEquals(u.id, member.guild.id, "拒绝后未入会");
        Assertions.assertTrue(u.requesters.isEmpty(), "拒绝后申请列表移除该申请");
    }

    /**
     * 1506 同意入会必须校验申请人此刻是否已在别的公会：申请期间申请人可以直接加入
     * {@code joinType=all} 的公会（1503 只在 {@code rec.guild.id} 为空时才拦），若这里不查，
     * {@code addMember} 会让他同时出现在两个公会的成员列表里（幽灵成员、原会 memberMax 虚高）。
     *
     * <p>APK：{@code EFailJoinInUnionReason.EFJIUR_ALREADYHAVEUNION(1)}；客户端
     * {@code UnionManagerSystem.cs:482-487 → :1164-1177} 的 1→100703「申请者已经有了公会」，
     * 正是真服为这条校验准备的文案。
     */
    @Test
    public void approvingApplicantWhoJoinedAnotherUnionIsRefused() {
        resetWorld();
        PlayerRecord ownerA = fresh(OWNER, "甲会会长");
        PlayerRecord ownerB = fresh("test-d6-owner-b", "乙会会长");
        PlayerRecord applicant = fresh("test-d6-applicant", "申请者");
        EmbeddedChannel chA = newChannel();
        EmbeddedChannel chB = newChannel();
        EmbeddedChannel chP = newChannel();
        GameSession sessionA = bind(ownerA, chA);
        GameSession sessionB = bind(ownerB, chB);
        GameSession sessionP = bind(applicant, chP);

        WorldStore.UnionRecord a = createUnion(sessionA, chA, "甲会", "verify");
        WorldStore.UnionRecord b = createUnion(sessionB, chB, "乙会", "direct");

        // 1) 申请者先对审批制公会甲发 1503：只落申请，不入会。
        union.onJoin(sessionP, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, a.id))));
        Pb.Fields applied = Pb.read(pick(drain(chP), MsgIds.S2C_JOIN_UNION_RET).body);
        Assertions.assertFalse(applied.getBool(1), "审批制不能直接入会");
        Assertions.assertEquals(0, applied.getInt(3, -1), "1904 f3 = 0（已提交待审批）");
        Assertions.assertEquals(1, a.requesters.size(), "甲会申请列表有 1 条");
        drain(chA);

        // 2) 申请期间再对 joinType=all 的乙会发 1503：服务端允许（rec.guild.id 仍为空）。
        union.onJoin(sessionP, new GamePacket(MsgIds.C2S_JOIN_UNION, 2,
                Pb.write(o -> Pb.stringAlways(o, 1, b.id))));
        Assertions.assertTrue(Pb.read(pick(drain(chP), MsgIds.S2C_JOIN_UNION_RET).body).getBool(1),
                "直接入会成功");
        Assertions.assertEquals(b.id, applicant.guild.id);
        int membersInB = b.members.size();
        drain(chB);

        // 3) 甲会会长同意该申请：必须拒绝，否则申请人会同时留在甲乙两会。
        union.onHandleRequester(sessionA, new GamePacket(MsgIds.C2S_HANDLE_UNION_REQUESTER, 3,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, applicant.playerId);
                    Pb.boolAlways(o, 2, true);
                })));
        List<GamePacket> outA = drain(chA);
        Pb.Fields h = Pb.read(pick(outA, MsgIds.S2C_HANDLE_REQUESTER_RET).body);
        Assertions.assertFalse(h.getBool(1), "已入别会的申请不能被同意");
        Assertions.assertEquals(1, h.getInt(2, -1),
                "1905 f2 = 1（EFJIUR_ALREADYHAVEUNION → 客户端 100703「申请者已经有了公会」）");
        Assertions.assertTrue(hasIn(outA, MsgIds.S2C_UNION_REQUESTERS_RET), "拒绝后会长仍收 1907 刷新");
        Assertions.assertTrue(Pb.read(pick(outA, MsgIds.S2C_UNION_REQUESTERS_RET).body)
                .getBytesList(1).isEmpty(), "甲会申请队列清空");
        Assertions.assertTrue(a.members.stream().noneMatch(m -> m.playerId == applicant.playerId),
                "甲会成员列表不得出现幽灵成员");
        Assertions.assertEquals(b.id, applicant.guild.id, "申请人仍在乙会");
        Assertions.assertEquals(membersInB, b.members.size(), "乙会成员数不变");
        Assertions.assertEquals(1, b.members.stream().filter(m -> m.playerId == applicant.playerId).count(),
                "申请人在乙会只有一条成员记录");
    }

    /**
     * 1509/1510/1511/1512 改公告/图标/加入类型/加入等级：改完必须推 1906
     * （原实现只本地乐观更新，重开面板即回滚）。APK：{@code UnionManagerSystem.cs:745/902/906/910}。
     */
    @Test
    public void managementEditsPushUnionDetail() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ownerCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "管理会", "direct");

        union.onModifyNotice(ownerSession, new GamePacket(MsgIds.C2S_MODIFY_UNION_NOTICE, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, "新公告"))));
        Pb.Fields f = Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_DETAIL_RET).body);
        Assertions.assertEquals("新公告", f.getString(5), "1906 f5 Notice");

        union.onModifyIcon(ownerSession, new GamePacket(MsgIds.C2S_MODIFY_UNION_ICON, 2,
                Pb.write(o -> Pb.stringAlways(o, 1, "WuPinTuBiao/icon9"))));
        f = Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_DETAIL_RET).body);
        Assertions.assertEquals("WuPinTuBiao/icon9", f.getString(2), "1906 f2 Icon");

        // 缺 '/' 的图标会让客户端 Split('/')[1] 越界 ⇒ 服务端必须兜底成默认图标。
        union.onModifyIcon(ownerSession, new GamePacket(MsgIds.C2S_MODIFY_UNION_ICON, 22,
                Pb.write(o -> Pb.stringAlways(o, 1, "icon9"))));
        f = Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_DETAIL_RET).body);
        Assertions.assertEquals(PlayerDumpService.DEFAULT_UNION_ICON, f.getString(2),
                "非法图标（无 '/'）必须兜底成默认图标");
        Assertions.assertEquals(PlayerDumpService.DEFAULT_UNION_ICON, u.icon);

        union.onModifyJoinType(ownerSession, new GamePacket(MsgIds.C2S_MODIFY_UNION_JOIN_TYPE, 3,
                Pb.write(o -> Pb.int32Always(o, 1, 0))));
        f = Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_DETAIL_RET).body);
        Assertions.assertEquals(0, f.getInt(7, 0), "1906 f7 JoinType=EUJT_Verify（0 是默认值，省略即 0）");
        Assertions.assertEquals("verify", u.joinType);

        union.onModifyJoinLevel(ownerSession, new GamePacket(MsgIds.C2S_MODIFY_UNION_JOIN_LEVEL, 4,
                Pb.write(o -> Pb.int32Always(o, 1, 33))));
        f = Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_DETAIL_RET).body);
        Assertions.assertEquals(33, f.getInt(6, 0), "1906 f6 JoinLevelLimit");
        Assertions.assertEquals(33, u.joinLevel);
    }

    /**
     * 1510/1511/1512 的职位门 = 会长/长老：APK 里「公会管理按钮」
     * {@code YiShiTingMianBan/XianShi_5_20/GongHuiGuanLiButton} 的可见性就是
     * {@code mEUnionJob != 1}（{@code UnionManagerSystem.cs:182}），点进去的
     * {@code GongHuiSheZhiMianBan/XianShi_5_20/QueDingXiuGai}（:1539 → {@code ᜉ} :867）
     * 无条件连发 1510/1511/1512 ⇒ 长老也改得动，成员看不到按钮（服务端也必须拒）。
     */
    @Test
    public void unionSettingsEditableByOwnerAndElderOnly() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord elder = fresh(MEMBER, "长老");
        PlayerRecord member = fresh("test-d6-member2", "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel elderCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession elderSession = bind(elder, elderCh);
        GameSession memberSession = bind(member, memberCh);

        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "职位会", "direct");
        union.onJoin(elderSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        union.onAppointElder(ownerSession, new GamePacket(MsgIds.C2S_APPOINT_ELDER, 2,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, elder.playerId);
                    Pb.boolAlways(o, 2, true);
                })));
        drain(ownerCh);
        drain(elderCh);
        drain(memberCh);
        Assertions.assertEquals(2, elder.guildJobCode(), "任命后 job=2（长老）");

        union.onModifyJoinLevel(elderSession, new GamePacket(MsgIds.C2S_MODIFY_UNION_JOIN_LEVEL, 3,
                Pb.write(o -> Pb.int32Always(o, 1, 40))));
        Assertions.assertEquals(40, u.joinLevel, "长老可以改入会等级（客户端按钮可见）");
        Pb.Fields f = Pb.read(pick(drain(elderCh), MsgIds.S2C_UNION_DETAIL_RET).body);
        Assertions.assertEquals(40, f.getInt(6, 0), "1906 f6 JoinLevelLimit = 40");

        union.onModifyJoinType(elderSession, new GamePacket(MsgIds.C2S_MODIFY_UNION_JOIN_TYPE, 4,
                Pb.write(o -> Pb.int32Always(o, 1, 1))));
        Assertions.assertEquals("direct", u.joinType, "长老可以改加入类型");
        drain(elderCh);

        union.onModifyIcon(elderSession, new GamePacket(MsgIds.C2S_MODIFY_UNION_ICON, 5,
                Pb.write(o -> Pb.stringAlways(o, 1, "WuPinTuBiao/icon7"))));
        Assertions.assertEquals("WuPinTuBiao/icon7", u.icon, "长老可以改图标");
        drain(elderCh);

        // 成员：客户端按钮隐藏（mEUnionJob == 1），服务端必须同样拒绝并回现状。
        union.onModifyJoinLevel(memberSession, new GamePacket(MsgIds.C2S_MODIFY_UNION_JOIN_LEVEL, 6,
                Pb.write(o -> Pb.int32Always(o, 1, 50))));
        Assertions.assertEquals(40, u.joinLevel, "普通成员改不动入会等级");
        f = Pb.read(pick(drain(memberCh), MsgIds.S2C_UNION_DETAIL_RET).body);
        Assertions.assertEquals(40, f.getInt(6, 0), "成员收到的是现状回滚（1906）");
        union.onModifyJoinType(memberSession, new GamePacket(MsgIds.C2S_MODIFY_UNION_JOIN_TYPE, 7,
                Pb.write(o -> Pb.int32Always(o, 1, 0))));
        Assertions.assertEquals("direct", u.joinType, "普通成员改不动加入类型");
        union.onModifyIcon(memberSession, new GamePacket(MsgIds.C2S_MODIFY_UNION_ICON, 8,
                Pb.write(o -> Pb.stringAlways(o, 1, "WuPinTuBiao/icon1"))));
        Assertions.assertEquals("WuPinTuBiao/icon7", u.icon, "普通成员改不动图标");
    }

    /** 1507 任命长老 → 1910 + 1908；1515 踢人 → 被踢者收 1909 且写退会 CD。 */
    @Test
    public void appointElderThenKick() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);

        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "任命会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(memberCh);

        union.onAppointElder(ownerSession, new GamePacket(MsgIds.C2S_APPOINT_ELDER, 2,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, member.playerId);
                    Pb.boolAlways(o, 2, true);
                })));
        Pb.Fields f = Pb.read(pick(drain(ownerCh), MsgIds.S2C_APPOINT_ELDER_RET).body);
        Assertions.assertTrue(f.getBool(1), "1910 Success");
        Assertions.assertTrue(f.getBool(2), "1910 AppointOrNot");
        Assertions.assertEquals(member.playerId, f.getInt(3, 0), "1910 ElderGuid");
        Assertions.assertEquals(2, PlayerRecord.jobCode(member.guild.job), "职位同步为 elder");
        Assertions.assertTrue(hasIn(drain(memberCh), MsgIds.S2C_UNION_JOB_UPDATE), "1908 职位变更");

        union.onKickMember(ownerSession, new GamePacket(MsgIds.C2S_KICK_UNION_MEMBER, 3,
                Pb.write(o -> Pb.int32Always(o, 1, member.playerId))));
        Assertions.assertTrue(hasIn(drain(memberCh), MsgIds.S2C_QUIT_UNION_RET), "被踢者收 1909");
        Assertions.assertTrue(member.guild.id.isEmpty(), "被踢后清公会");
        Assertions.assertTrue(member.guild.quitUnionAt > System.currentTimeMillis(),
                "Union.txt 踢出公会→新公会CD 7200s");
    }

    /**
     * 1508 转让会长 → 1911（改成员列表职位）+ 1906，**不推 1968**。
     *
     * <p>1968 的载荷 {@code CCMsgUnionNotifyOwnerChangeOnce.union_owner_name} 会被客户端
     * {@code PlayGameState.cs:9051-9069 OnNET_CCMsgUnionPresidentChange_Ret} **无条件**弹成模态框
     * StrTable 101282「由于你一周未登录游戏,会长职务由{0}接任」——主动转让时对全公会刷屏且文案不实。
     * 只有「会长长期未登录被自动让位」才该发，且只发给被让位者（见
     * {@code inactiveOwnerHandsOverAndOldOwnerGetsNotifyOnLogin}）。
     */
    @Test
    public void voluntaryTransferPushesOwnerJobButNotOwnerChangeModal() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);

        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "转让会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(memberCh);

        union.onChangeOwner(ownerSession, new GamePacket(MsgIds.C2S_CHANGE_UNION_OWNER, 2,
                Pb.write(o -> Pb.int32Always(o, 1, member.playerId))));
        List<GamePacket> memberOut = drain(memberCh);
        Assertions.assertTrue(hasIn(memberOut, MsgIds.S2C_NOTIFY_UNION_OWNER), "1911 新会长");
        Assertions.assertFalse(hasIn(memberOut, MsgIds.S2C_NOTIFY_OWNER_CHANGE),
                "主动转让不得广播 1968（客户端会弹「你一周未登录被让位」模态框）");
        Assertions.assertTrue(hasIn(memberOut, MsgIds.S2C_UNION_DETAIL_RET), "1906 全量刷新");
        Assertions.assertEquals(3, PlayerRecord.jobCode(member.guild.job), "新会长 job=owner");
        Assertions.assertEquals(1, PlayerRecord.jobCode(owner.guild.job), "原会长降为 member");
    }

    /**
     * 1517 失败时不得回 1966：客户端 {@code PlayGameState.cs:6773-6779} 收到 1966 就无条件弹
     * StrTable 100739「捐赠成功，好人一生平安」，不解析 body ⇒ 失败回 1966 是假成功。
     * 失败只回 1913（unionPlayerRes）让面板自纠。
     */
    @Test
    public void donateFailureDoesNotClaimSuccess() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "捐献会", "direct");
        drain(ch);

        owner.guild.donateLeft = 0;
        store.save(owner);
        union.onDonate(session, new GamePacket(MsgIds.C2S_UNION_DONATE, 1, new byte[0]));
        List<GamePacket> out = drain(ch);
        Assertions.assertFalse(hasIn(out, MsgIds.S2C_UNION_DONATE_RET),
                "次数用尽时不能回 1966（客户端会弹「捐赠成功」）");
        Assertions.assertTrue(hasIn(out, MsgIds.S2C_UNION_PLAYER_RES), "1913 面板自纠");

        owner.guild.donateLeft = 10;
        owner.gold = 0;
        store.save(owner);
        union.onDonate(session, new GamePacket(MsgIds.C2S_UNION_DONATE, 2, new byte[0]));
        Assertions.assertFalse(hasIn(drain(ch), MsgIds.S2C_UNION_DONATE_RET), "金币不足时同样不回 1966");

        owner.gold = 1000000;
        store.save(owner);
        union.onDonate(session, new GamePacket(MsgIds.C2S_UNION_DONATE, 3, new byte[0]));
        Assertions.assertTrue(hasIn(drain(ch), MsgIds.S2C_UNION_DONATE_RET), "成功才回 1966");
    }

    /**
     * 最后一名成员退会：公会必须从内存**和**磁盘一起消失。
     * 只 remove 不 saveUnions 会让 unions.json 保留旧内容，重启后空公会「复活」，
     * 而原成员的 {@code guild.id} 已清空 ⇒ 幽灵公会。
     */
    @Test
    public void lastMemberQuitRemovesUnionFromDisk() throws Exception {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "独苗会", "direct");
        String id = u.id;
        drain(ch);

        union.onQuit(session, new GamePacket(MsgIds.C2S_QUIT_UNION, 1, new byte[0]));
        Assertions.assertTrue(hasIn(drain(ch), MsgIds.S2C_QUIT_UNION_RET), "1909");
        Assertions.assertNull(world.findUnion(id), "内存里必须已移除");
        String json = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(props.getWorldDir(), "unions.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        Assertions.assertFalse(json.contains("独苗会"), "unions.json 不得残留空公会：" + json);
    }

    /** 公会不存在时的 1906 兜底包必须写 f11=9999，否则客户端显示「公会即将解散」两行提示。 */
    @Test
    public void emptyUnionDetailHidesDismissCountdown() {
        byte[] body = dump.unionDetailEmpty();
        Assertions.assertEquals(9999, Pb.read(body).getInt(11, 0), "f11 解散阈值 9999=不启用");
        Assertions.assertTrue(Pb.read(body).getBytes(14) != null, "f14 必须存在（客户端 MemberInfo 无初值）");
    }

    /**
     * 1523 升级：扣晶石并写入升级结束时刻（1919 f3 leftLevelUpTime > 0）；
     * 1524 取消：按 Union.txt「公会取消升级返还晶石比例」退晶石。
     * APK：{@code CMsgOneUnionBuildingLevelUpInfo{1 buildingType,2 level,3 leftLevelUpTime}}。
     */
    @Test
    public void buildingLevelUpStartsTimerAndCancelRefunds() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ownerCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "建筑会", "direct");
        u.crystal = 100000;
        u.growth = 10000000;
        u.buildings.put(Integer.valueOf(1), Integer.valueOf(15));
        u.buildings.put(Integer.valueOf(2), Integer.valueOf(1));
        world.saveUnions();
        drain(ownerCh);

        int before = u.crystal;
        UnionCfg.BuildingRow row = unionCfg.building(2, 1);
        union.onBuildingLevelUp(ownerSession, new GamePacket(MsgIds.C2S_UNION_BUILDING_LEVEL_UP, 1,
                Pb.write(o -> Pb.int32Always(o, 1, 2))));
        List<GamePacket> out = drain(ownerCh);
        Pb.Fields f = Pb.read(pick(out, MsgIds.S2C_UNION_BUILDING_LEVEL_UP).body);
        Assertions.assertEquals(1, f.getInt(2, -1), "1920 retType=1 SUCCESS");
        Assertions.assertEquals(before - row.crystal, u.crystal, "扣 UnionBuildingLevelUp 晶石");
        Assertions.assertTrue(u.isUpgrading(2, System.currentTimeMillis()), "进入升级中");

        GamePacket buildings = pick(out, MsgIds.S2C_UNION_BUILDINGS);
        Assertions.assertNotNull(buildings, "升级后必须推 1919");
        int left = -1;
        for (byte[] b : Pb.read(buildings.body).getBytesList(1)) {
            Pb.Fields bf = Pb.read(b);
            if (bf.getInt(1, 0) == 2) {
                left = bf.getInt(3, 0);
            }
        }
        Assertions.assertTrue(left > 0, "1919 f3 leftLevelUpTime 必须是剩余秒（原实现恒 0）");

        union.onCancelLevelUp(ownerSession, new GamePacket(MsgIds.C2S_CANCEL_BUILDING_LEVEL_UP, 2,
                Pb.write(o -> Pb.int32Always(o, 1, 2))));
        Pb.Fields c = Pb.read(pick(drain(ownerCh), MsgIds.S2C_CANCEL_BUILDING_LEVEL_UP_RET).body);
        Assertions.assertEquals(2, c.getInt(1, 0), "1921 f1 buildingType");
        Assertions.assertTrue(c.getBool(2), "1921 f2 isSuccess");
        Assertions.assertFalse(u.isUpgrading(2, System.currentTimeMillis()), "取消后不再升级中");
        Assertions.assertTrue(u.crystal > before - row.crystal, "按 0.5 比例退还晶石");
    }

    /**
     * 公会等级 = 议事厅等级：议事厅升级到点后 {@code u.level} 必须同步，并推 1914
     * {@code EUnionAttribute=4}（客户端建筑面板用它门控非议事厅建筑的升级按钮）。
     *
     * <p>APK 依据：{@code UnionBuildingLevelUp.txt:4} 表头「到下一级需要的议事厅等级」列 ——
     * 议事厅自身每级 hallNeed 恒等于当前等级、其他建筑要求议事厅等级，且议事厅各级描述
     * 「厨房可提升为2级建筑」「训练场可提升为2级建筑」与之逐条吻合；
     * {@code MainPlayer.cs:1009-1036} 用 attri=4 更新 {@code mUnionDetailInfo.Level}，
     * {@code BuildingItem_InJianZhuMianBan.cs:167-187} 用它决定升级按钮显隐。</p>
     */
    @Test
    public void unionLevelFollowsHallLevelAndPushesAttri4() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ownerCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "议事会", "direct");
        u.crystal = 100000;
        u.growth = 10000000;
        u.buildings.put(Integer.valueOf(1), Integer.valueOf(1));
        u.level = 1;
        world.saveUnions();
        drain(ownerCh);

        union.onBuildingLevelUp(ownerSession, new GamePacket(MsgIds.C2S_UNION_BUILDING_LEVEL_UP, 1,
                Pb.write(o -> Pb.int32Always(o, 1, 1))));
        Pb.Fields ret = Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_BUILDING_LEVEL_UP).body);
        Assertions.assertEquals(1, ret.getInt(2, -1), "1920 retType=1 SUCCESS");
        Assertions.assertTrue(u.isUpgrading(1, System.currentTimeMillis()), "议事厅进入升级中");

        // 把倒计时拨到过去，用 1522 触发到点结算
        u.buildingUpgradeEnd.put(Integer.valueOf(1), Long.valueOf(System.currentTimeMillis() - 1000L));
        union.onBuildings(ownerSession, new GamePacket(MsgIds.C2S_UNION_BUILDINGS, 2, new byte[0]));
        List<GamePacket> out = drain(ownerCh);
        Assertions.assertEquals(2, u.buildings.get(Integer.valueOf(1)).intValue(), "议事厅到点升到 2 级");
        Assertions.assertEquals(2, u.level, "公会等级必须跟议事厅等级（改前恒为 1）");

        boolean attri4 = false;
        for (GamePacket p : out) {
            if (p.msgId == MsgIds.S2C_UNION_ATTRI_UPDATE) {
                Pb.Fields af = Pb.read(p.body);
                if (af.getInt(1, 0) == 4 && af.getInt(2, 0) == 2) {
                    attri4 = true;
                }
            }
        }
        Assertions.assertTrue(attri4, "议事厅升级到点必须推 1914 type=4 公会等级");

        union.onDetail(ownerSession, new GamePacket(MsgIds.C2S_UNION_DETAIL, 3,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        Assertions.assertEquals(2,
                Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_DETAIL_RET).body).getInt(8, -1),
                "1906 f8 公会等级必须已同步");
    }

    /**
     * Boss 主路径：1526 详情（f2 myEmploy / f3 章节含 hurtRankList）→ 1528 出战（f2 severCheck）→
     * 1529 结算（1926 必须写全 f1/f3/f4/f5/f6/f7/f8/f9）→ 1532 通关结算回 1929。
     * APK：{@code UnionZhanDouJieSuan.cs:126-260} 按 >0 决定是否显示每一行奖励。
     */
    @Test
    public void bossInfoFightResultAndFinish() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ownerCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "Boss会", "direct");
        u.crystal = 100000;
        u.buildings.put(Integer.valueOf(6), Integer.valueOf(5));
        u.addDamage(1, owner.playerId, owner.account, owner.roleName, 18, 40, 12345);
        world.saveUnions();
        drain(ownerCh);

        union.onBossInfo(ownerSession, new GamePacket(MsgIds.C2S_UNION_BOSS_INFO, 1, new byte[0]));
        GamePacket info = pick(drain(ownerCh), MsgIds.S2C_UNION_BOSS_INFO);
        Assertions.assertNotNull(info);
        Pb.Fields f = Pb.read(info.body);
        Assertions.assertTrue(f.fieldKeys().contains(Integer.valueOf(1)), "1923 f1 playTime 列表");
        Assertions.assertTrue(f.fieldKeys().contains(Integer.valueOf(2)), "1923 f2 myEmploy");
        List<byte[]> bosses = f.getBytesList(3);
        Assertions.assertFalse(bosses.isEmpty(), "1923 f3 至少一个章节");
        Pb.Fields first = Pb.read(bosses.get(0));
        Assertions.assertEquals(1, first.getInt(1, 0), "f1 chapterID");
        Assertions.assertEquals("UnionBoss1", first.getString(2), "f2 bossOriName");
        Assertions.assertTrue(first.getInt(3, 0) > 0, "f3 curHP");
        List<byte[]> hurt = first.getBytesList(4);
        Assertions.assertEquals(1, hurt.size(), "1923 f4 hurtRankList 必须带伤害榜（原实现整段缺失）");
        Pb.Fields h0 = Pb.read(hurt.get(0));
        Assertions.assertEquals(owner.roleName, h0.getString(1), "f4 f1 name");
        Assertions.assertEquals(12345, h0.getInt(4, 0), "f4 f4 damageContri");

        union.onBossFight(ownerSession, new GamePacket(MsgIds.C2S_UNION_BOSS_FIGHT, 2,
                Pb.write(o -> Pb.int32Always(o, 1, 1))));
        List<GamePacket> fightOut = drain(ownerCh);
        GamePacket fight = pick(fightOut, MsgIds.S2C_UNION_BOSS_FIGHT_RET);
        Assertions.assertNotNull(fight);
        Pb.Fields ff2 = Pb.read(fight.body);
        Assertions.assertTrue(ff2.fieldKeys().contains(Integer.valueOf(1)), "1925 f1 bossInfo");
        Assertions.assertEquals(1, Pb.read(ff2.getBytes(1)).getInt(1, 0), "bossInfo.chapterID");
        Assertions.assertFalse(ff2.getBool(2),
                "severCheck=[DefaultValue(false)]，真服同样省略该字段，客户端读到 false");
        Assertions.assertTrue(hasIn(fightOut, MsgIds.S2C_UNION_BOSS_PLAY_TIME), "1967 次数更新");

        // 未击杀：1926 也必须把 1/3/4/5/6/7/8 全写上（UnionZhanDouJieSuan.cs:205-259 按 >0 决定整行显隐）；
        // f9 是 repeated，没掉落就没有 wire 项，客户端遍历空列表即无掉落行。
        union.onBossResult(ownerSession, new GamePacket(MsgIds.C2S_UNION_BOSS_RESULT, 3,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 1);
                    Pb.int32Always(o, 2, 0);
                    Pb.int32Always(o, 3, 5000);
                })));
        Pb.Fields rf = Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_BOSS_RESULT_RET).body);
        for (int field : new int[]{1, 3, 4, 5, 6, 7, 8}) {
            Assertions.assertTrue(rf.fieldKeys().contains(Integer.valueOf(field)),
                    "1926 f" + field + " 必须写（否则结算面板整行隐藏）");
        }
        Assertions.assertTrue(rf.getInt(3, 0) > 0, "金币按总金币池 × 伤害占比");
        Assertions.assertEquals(1, u.bossChapter, "未击杀不换章");

        // 击杀：伤害按剩余血量截断 → 掉落进 goods 与作战室拍卖货架，并主动推 1929
        int hpBefore = u.bossHpOf(1, 1);
        owner.mails.clear();
        // 每次 1529 都必须有一次对应的 1528：服务端在 1528 里写 bossPending 凭证，
        // 1529 无凭证即拒（防伪造伤害包直接结算）。Union.txt 每日次数 2 ⇒ 本章第 2 次挑战合法。
        union.onBossFight(ownerSession, new GamePacket(MsgIds.C2S_UNION_BOSS_FIGHT, 7,
                Pb.write(o -> Pb.int32Always(o, 1, 1))));
        drain(ownerCh);
        union.onBossResult(ownerSession, new GamePacket(MsgIds.C2S_UNION_BOSS_RESULT, 4,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 1);
                    Pb.int32Always(o, 2, 0);
                    Pb.int32Always(o, 3, 999999999);
                })));
        List<GamePacket> killOut = drain(ownerCh);
        Pb.Fields kill = Pb.read(pick(killOut, MsgIds.S2C_UNION_BOSS_RESULT_RET).body);
        Assertions.assertFalse(kill.getBytesList(9).isEmpty(), "1926 f9 击杀掉落列表");
        Assertions.assertTrue(kill.getInt(3, 0) <= unionCfg.boss(1).gold,
                "金币不得超过该章总金币池（伤害按剩余血量截断）");
        Assertions.assertTrue(hpBefore > 0, "击杀前 boss 有血量");
        Assertions.assertTrue(hasIn(killOut, MsgIds.S2C_BOSS_FINISH_INFO), "击杀后主动推 1929");
        Assertions.assertEquals(2, u.bossChapter, "击杀后进入下一章");
        Assertions.assertFalse(u.auctionLots.isEmpty(), "掉落进作战室拍卖货架");
        Assertions.assertTrue(u.crystal >= 100000, "结算晶石入公会");

        // 名次奖不再内联发放（原实现直接 p.guild.brotherCoin += 名次兄弟币），
        // 改走 mailType 3（Sys_MailConfig 第3行「额外获得了以下奖励：」= 附件），
        // 与 JJC/争霸排名邮同一范式；内联+邮件双发是这次要修掉的。
        Assertions.assertEquals(unionCfg.boss(1).brotherCoin * 2, owner.guild.brotherCoin,
                "只有每场 15 兄弟币 × 2 场，没有内联名次奖");
        Assertions.assertEquals(unionCfg.boss(1).courageCoin * 2, owner.guild.courageCoin,
                "只有每场 25 勇气币 × 2 场，没有内联名次奖");
        Assertions.assertEquals(1, owner.mails.size(), "击杀后给参战者发一封 boss 名次奖邮");
        PlayerRecord.Mail bossMail = owner.mails.get(0);
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_BOSS, bossMail.mailType,
                "mailType=3 = ESysMailTypeID.EMTID_UNION_BOSS_XDB");
        Assertions.assertEquals(3, bossMail.paras.size(), "正文占位符 {0}boss 名 {1}伤害% {2}名次");
        Assertions.assertEquals(unionCfg.boss(1).name, bossMail.paras.get(0));
        Assertions.assertEquals("100", bossMail.paras.get(1), "单人独打 100% 伤害");
        Assertions.assertEquals("1", bossMail.paras.get(2), "伤害榜第 1");
        Assertions.assertEquals(UnionCfg.rankAward(unionCfg.boss(1).settleBrother, 1),
                bossMail.brotherCoin, "名次兄弟币走邮件附件");
        Assertions.assertEquals(UnionCfg.rankAward(unionCfg.boss(1).settleCourage, 1),
                bossMail.courageCoin, "名次勇气币走邮件附件");

        // 击杀后再报同一章：不能重复结算（否则总金币/成长/晶石可无限刷，还会重复发名次奖邮）。
        // 这里刻意**不补 1528** ⇒ 服务端在凭证校验就拒掉（bossPending 已用尽），回零结算。
        int crystalAfterKill = u.crystal;
        int goldAfterKill = owner.gold;
        union.onBossResult(ownerSession, new GamePacket(MsgIds.C2S_UNION_BOSS_RESULT, 6,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 1);
                    Pb.int32Always(o, 2, 0);
                    Pb.int32Always(o, 3, 999999999);
                })));
        Pb.Fields again = Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_BOSS_RESULT_RET).body);
        Assertions.assertEquals(0, again.getInt(3, 0), "已死 boss 的重复上报回零结算");
        Assertions.assertEquals(goldAfterKill, owner.gold, "不能重复拿单场金币");
        Assertions.assertEquals(crystalAfterKill, u.crystal, "不能重复加结算晶石");
        Assertions.assertEquals(2, u.bossChapter, "不能重复换章");
        Assertions.assertEquals(1, owner.mails.size(), "不能重复发名次奖邮");

        union.onBossFinish(ownerSession, new GamePacket(MsgIds.C2S_BOSS_FINISH, 5,
                Pb.write(o -> Pb.int32Always(o, 1, 1))));
        GamePacket finish = pick(drain(ownerCh), MsgIds.S2C_BOSS_FINISH_INFO);
        Assertions.assertNotNull(finish, "1532 必须回 1929（原实现错回 1927，打完 boss 看不到结算页）");
        Pb.Fields ff = Pb.read(finish.body);
        Assertions.assertTrue(ff.fieldKeys().contains(Integer.valueOf(1)), "1929 f1 bossInfo");
        Assertions.assertFalse(ff.getBytesList(2).isEmpty(), "1929 f2 goods");
    }

    /** 1534 医院：1931 f2 用本地阵亡武将列表填病人（原实现只写 CureType，列表永远空白）。 */
    @Test
    public void hospitalListsDeadWjWithBrief() {
        resetWorld();
        PlayerRecord rec = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        createUnion(session, ch, "医院会", "direct");
        drain(ch);

        PlayerRecord.QkDeadWj dead = new PlayerRecord.QkDeadWj();
        dead.wjIndex = 18;
        dead.reliveUntilMs = System.currentTimeMillis() + 600_000L;
        rec.qkDeadWjs.add(dead);
        store.save(rec);

        union.onHospital(session, new GamePacket(MsgIds.C2S_HOSPITAL, 1,
                Pb.write(o -> Pb.int32Always(o, 1, 1))));
        Pb.Fields f = Pb.read(pick(drain(ch), MsgIds.S2C_HOSPITAL).body);
        Assertions.assertEquals(1, f.getInt(1, 0), "1931 f1 CureType");
        List<byte[]> infos = f.getBytesList(2);
        Assertions.assertEquals(1, infos.size(), "1931 f2 病人列表");
        Pb.Fields d = Pb.read(infos.get(0));
        Assertions.assertEquals(18, Pb.read(d.getBytes(1)).getInt(1, 0), "f1 wjBriefInfo.index");
        Assertions.assertTrue(d.getInt(2, 0) > 0, "f2 ReliveLeftSeconds");
    }

    /**
     * 1535 义园：一个病人只能被医师治疗一次 —— 1931 f3 {@code IsEmergencyTreatme} 必须在治疗成功后置 true。
     *
     * <p>客户端 {@code BingRen.cs:60-71}（APK 权威反编译）收到 true 就隐藏「可资料」、显示「已资料」并
     * **禁用资料按钮的 Collider**；改前服务端 f3 恒 false 且无「已治疗」闸门 ⇒ 按钮永远可点，
     * 同一病人能被反复治疗、反复扣医师酬劳，把复活倒计时一路叠到 0。
     */
    @Test
    public void hospitalTreatsEachPatientOnlyOnce() {
        resetWorld();
        PlayerRecord rec = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        WorldStore.UnionRecord u = withHall(createUnion(session, ch, "医院会", "direct"), 4);
        drain(ch);

        // 病人：武将 18 阵亡、剩 24 小时；医师登记进医院格位（战力 90000 ⇒ 医师 4 级：属性 3600s / 酬劳 20000）
        PlayerRecord.QkDeadWj dead = new PlayerRecord.QkDeadWj();
        dead.wjIndex = 18;
        dead.reliveUntilMs = System.currentTimeMillis() + 86_400_000L;
        rec.qkDeadWjs.add(dead);
        WorldStore.Employer medic = new WorldStore.Employer();
        medic.playerId = rec.playerId;
        medic.wjIndex = 18;
        medic.fightPower = 90000;
        u.employersOf(7).add(medic);
        store.save(rec);

        // 第一次治疗：缩短复活时间 + 扣酬劳 + 1931 f3 置 true
        union.onHospitalEmploy(session, new GamePacket(MsgIds.C2S_HOSPITAL_EMPLOY, 1, Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, rec.playerId);
            Pb.int32Always(o, 3, 18);
            Pb.int32Always(o, 4, 18);
        })));
        Pb.Fields first = Pb.read(pick(drain(ch), MsgIds.S2C_HOSPITAL).body);
        Pb.Fields p0 = Pb.read(first.getBytesList(2).get(0));
        Assertions.assertTrue(p0.getBool(3), "治疗成功后 1931 f3 IsEmergencyTreatme 必须为 true");
        long leftAfterFirst = dead.reliveUntilMs;
        Assertions.assertTrue(leftAfterFirst < System.currentTimeMillis() + 86_400_000L, "第一次治疗必须缩短复活时间");
        long goldAfterFirst = rec.gold;
        Assertions.assertEquals(1_000_000L - 20000L, goldAfterFirst, "第一次治疗按医师 4 级酬劳 20000 扣费");

        // 第二次治疗：必须被拒 —— 倒计时与金币都不动，f3 仍为 true
        union.onHospitalEmploy(session, new GamePacket(MsgIds.C2S_HOSPITAL_EMPLOY, 2, Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, rec.playerId);
            Pb.int32Always(o, 3, 18);
            Pb.int32Always(o, 4, 18);
        })));
        Pb.Fields second = Pb.read(pick(drain(ch), MsgIds.S2C_HOSPITAL).body);
        Assertions.assertEquals(leftAfterFirst, dead.reliveUntilMs, "已治疗过的病人不能再次缩短复活时间");
        Assertions.assertEquals(goldAfterFirst, rec.gold, "被拒的重复治疗不能扣金币");
        Assertions.assertTrue(Pb.read(second.getBytesList(2).get(0)).getBool(3), "重复治疗后 1931 f3 仍为 true");
    }

    /**
     * 1538 训练：训练时长走 UnionXunLian.txt（28800s，原实现写死 60s）；
     * 1934 必须按坑位发满，且非空坑的 f5 employerWJInfo 必须存在
     * （客户端 {@code UnionTrainRoomInfo.cs:40} 无条件解引用 → 缺了就 NRE 打不开界面）。
     */
    @Test
    public void trainInfoSendsOneSlotPerPitWithEmployerBrief() {
        resetWorld();
        PlayerRecord rec = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "训练会", "direct");
        u.buildings.put(Integer.valueOf(3), Integer.valueOf(2));
        withHall(u, 3);
        world.saveUnions();
        drain(ch);

        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 1, Pb.write(o -> {
            Pb.int32Always(o, 1, 18);
            Pb.int32Always(o, 2, 2);
            Pb.int32Always(o, 3, 0);
            Pb.int32Always(o, 4, 0);
        })));
        List<GamePacket> out = drain(ch);
        Assertions.assertTrue(hasIn(out, MsgIds.S2C_TRAIN_START), "1944");
        GamePacket info = pick(out, MsgIds.S2C_TRAIN_INFO);
        Assertions.assertNotNull(info, "1934");
        Assertions.assertEquals(unionCfg.trainSec(), rec.economy.trainSlots.get(0).leftSec,
                "训练时长按 UnionXunLian.txt（28800）");
        List<byte[]> slots = Pb.read(info.body).getBytesList(1);
        Assertions.assertEquals(unionCfg.trainPits(2), slots.size(), "坑位数按 UnionXunLianPits");
        Pb.Fields s0 = Pb.read(slots.get(0));
        Assertions.assertEquals(18, s0.getInt(1, 0), "f1 wjIndex");
        Assertions.assertEquals(2, s0.getInt(3, 0), "f3 TrainingType");
        Assertions.assertEquals(unionCfg.trainSec(), s0.getInt(4, 0), "f4 leftTime");
        Assertions.assertTrue(s0.fieldKeys().contains(Integer.valueOf(5)), "f5 employerWJInfo 必须存在");
        Assertions.assertEquals(0, Pb.read(slots.get(1)).getInt(1, 0), "空坑 wjIndex=0");
    }

    /**
     * 多坑（{@code UnionXunLianPits.txt}：训练场 1 级 2 坑 … 7 级 8 坑）按 **APK 流程**走一遍：
     * 1539 空体 → 1934 铺满坑位（空坑 wjIndex=0）；1538 开两个训练 → 1934 前两坑各有 wjIndex；
     * 第三个被容量挡回 **1944 f1=0**；同一武将重复开训同样被拒；1540 带 f1 {@code wjIndex}
     * 只取消指定坑 → 1934 仍留另一个。
     *
     * <p>依据：{@code UnionTrainRoomInfo.cs:18-19}（坑位数取训练场建筑等级）、
     * {@code :27-47}（1934 按坑位顺序覆盖 {@code listTrainInfo[j]}）、{@code :52-61}
     * （1944 的 {@code BeginTrainSuccess(wjIndex)} 按 wjIndex 找坑 ⇒ 拒绝必须回 0）、
     * {@code UnionTrainRoom.cs:723-729}（1540 带 wjIndex）。</p>
     */
    @Test
    public void trainRoomRunsMultiplePitsPerApkFlow() {
        resetWorld();
        PlayerRecord rec = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "多坑会", "direct");
        u.buildings.put(Integer.valueOf(3), Integer.valueOf(1));
        withHall(u, 3);
        world.saveUnions();
        drain(ch);

        int pits = unionCfg.trainPits(1);
        Assertions.assertEquals(2, pits, "UnionXunLianPits.txt：训练场 1 级 = 2 个坑");

        // 1539（空体）→ 1934 铺满 2 个空坑
        union.onTrainInfo(session, new GamePacket(MsgIds.C2S_TRAIN_INFO, 1, new byte[0]));
        List<byte[]> empty = Pb.read(pick(drain(ch), MsgIds.S2C_TRAIN_INFO).body).getBytesList(1);
        Assertions.assertEquals(pits, empty.size(), "1934 坑位数 = 训练场等级档位");
        Assertions.assertEquals(0, Pb.read(empty.get(0)).getInt(1, 0), "空坑 wjIndex=0");

        // 1538 ×2：两个武将同时开练
        List<GamePacket> out = null;
        for (int wj : new int[]{18, 19}) {
            final int w = wj;
            union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, w, Pb.write(o -> {
                Pb.int32Always(o, 1, w);
                Pb.int32Always(o, 2, 0);
                Pb.int32Always(o, 3, 0);
                Pb.int32Always(o, 4, 0);
            })));
            out = drain(ch);
            GamePacket ret = pick(out, MsgIds.S2C_TRAIN_START);
            Assertions.assertNotNull(ret, "1944");
            Assertions.assertEquals(wj, Pb.read(ret.body).getInt(1, 0), "1944 f1 = 受训武将");
        }
        Assertions.assertEquals(2, rec.economy.trainSlots.size(), "两坑同时训练");
        List<byte[]> info = Pb.read(pick(out, MsgIds.S2C_TRAIN_INFO).body).getBytesList(1);
        Assertions.assertEquals(18, Pb.read(info.get(0)).getInt(1, 0), "1934 坑 0 = 18");
        Assertions.assertEquals(19, Pb.read(info.get(1)).getInt(1, 0), "1934 坑 1 = 19");

        // 第 3 个武将：坑位满 ⇒ 1944 f1 = **被拒武将自己的 wjIndex**（不是 0）。
        // 回 0 会被客户端 `UnionTrainRoomInfo.cs:56 Find(x => x.wjIndex == wjIndex)` 命中第一个
        // 全零空坑并置 beginTrain=true ⇒ 空坑显示成「训练中」。
        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 4, Pb.write(o -> {
            Pb.int32Always(o, 1, 20);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, 0);
            Pb.int32Always(o, 4, 0);
        })));
        GamePacket full = pick(drain(ch), MsgIds.S2C_TRAIN_START);
        Assertions.assertNotNull(full, "坑位满也要回 1944");
        Assertions.assertEquals(20, Pb.read(full.body).getInt(1, 0), "坑位满 1944 f1 = 被拒武将（不能回 0）");
        Assertions.assertEquals(2, rec.economy.trainSlots.size(), "拒绝后不占坑");

        // 同一武将重复开训同样被拒（否则 1934 会出现两个同 wjIndex 的坑）
        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 5, Pb.write(o -> {
            Pb.int32Always(o, 1, 18);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, 0);
            Pb.int32Always(o, 4, 0);
        })));
        Assertions.assertEquals(18, Pb.read(pick(drain(ch), MsgIds.S2C_TRAIN_START).body).getInt(1, 0),
                "同一武将不得重复占坑（回其自身 wjIndex，不能回 0）");

        // 1540 f1=18：只取消指定坑，另一个继续练
        union.onTrainCancel(session, new GamePacket(MsgIds.C2S_TRAIN_CANCEL, 6, Pb.write(o -> {
            Pb.int32Always(o, 1, 18);
        })));
        List<GamePacket> cancelOut = drain(ch);
        Assertions.assertEquals(1, rec.economy.trainSlots.size(), "只取消指定坑");
        Assertions.assertEquals(19, rec.economy.trainSlots.get(0).wjIndex, "另一个坑继续训练");
        List<byte[]> left = Pb.read(pick(cancelOut, MsgIds.S2C_TRAIN_INFO).body).getBytesList(1);
        Assertions.assertEquals(19, Pb.read(left.get(0)).getInt(1, 0), "1934 仍留另一个坑");
        Assertions.assertEquals(0, Pb.read(left.get(1)).getInt(1, 0), "被取消的坑空出");
    }

    /**
     * 1538 训练消耗与经验按 APK 客户端口径：每小时经验 = {@code A + B*玩家等级^C}（{@code TrainInfo.cs:29}，
     * 幂只作用玩家等级、不是武将等级），{@code totalExp = 每小时*时长/3600*(训练室倍率 + 教练加成)}
     * （{@code :30} 教练加成**相加**），消耗 = {@code totalExp / 消耗系数}（{@code UnionXunLian.txt} 1.25/1/1000/800
     * 是除数）：普通/白银扣金币、黄金/铂金扣钻石（{@code SelectTrainRoom.cs:114/124/134/151}），
     * 挂教练另付 {@code UnionWJSubsidiary.txt} 职业 1「雇佣酬劳」金币工资，其中 75% 转给教练主人。
     */
    @Test
    public void trainCostAndExpFollowClientFormula() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ch = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession session = bind(owner, ch);
        GameSession memberSession = bind(member, memberCh);
        WorldStore.UnionRecord u = createUnion(session, ch, "训练会", "direct");
        // 训练场 4 级 = 5 个坑（UnionXunLianPits.txt），本例最多占 3 个 ⇒ 余额不足那一发是
        // 真的走「钱不够」分支，而不是被坑位容量先挡掉。
        u.buildings.put(Integer.valueOf(3), Integer.valueOf(4));
        withHall(u, 3);
        world.saveUnions();
        drain(ch);
        drain(memberCh);
        Assertions.assertNotNull(memberSession);

        Assertions.assertEquals(300d + 20d * Math.pow(40d, 1.3d), unionCfg.trainHourlyExp(40), 1e-6,
                "UnionXunLian.txt：每小时经验 = A + B*玩家等级^C");

        // 普通房（type 0）：扣金币 = totalExp / 1.25，钻石不动
        int goldBefore = owner.gold;
        int diamondBefore = owner.diamond;
        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 1, Pb.write(o -> {
            Pb.int32Always(o, 1, 18);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, 0);
            Pb.int32Always(o, 4, 0);
        })));
        List<GamePacket> out = drain(ch);
        GamePacket start = pick(out, MsgIds.S2C_TRAIN_START);
        Assertions.assertNotNull(start, "1538 必须有 1933");
        Assertions.assertEquals(18, Pb.read(start.body).getInt(1, 0), "1933 f1 wjIndex");
        long exp0 = unionCfg.trainTotalExp(40, 0, 0d, unionCfg.trainSec());
        Assertions.assertEquals(goldBefore - (int) Math.floor(exp0 / 1.25d), owner.gold,
                "普通房扣金币 = totalExp / 1.25");
        Assertions.assertEquals(diamondBefore, owner.diamond, "普通房不扣钻石");
        GamePacket info = pick(out, MsgIds.S2C_TRAIN_INFO);
        Assertions.assertNotNull(info, "1934");
        Pb.Fields slot0 = Pb.read(Pb.read(info.body).getBytesList(1).get(0));
        Assertions.assertEquals((int) exp0, slot0.getInt(2, 0), "1934 f2 = 面板显示的总经验");

        // 黄金房（type 2）：扣钻石 = totalExp / 1000，金币不动
        int goldBefore2 = owner.gold;
        int diamondBefore2 = owner.diamond;
        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 2, Pb.write(o -> {
            Pb.int32Always(o, 1, 19);
            Pb.int32Always(o, 2, 2);
            Pb.int32Always(o, 3, 0);
            Pb.int32Always(o, 4, 0);
        })));
        drain(ch);
        long exp2 = unionCfg.trainTotalExp(40, 2, 0d, unionCfg.trainSec());
        Assertions.assertEquals(diamondBefore2 - (int) Math.floor(exp2 / 1000d), owner.diamond,
                "黄金房扣钻石 = totalExp / 1000");
        Assertions.assertEquals(goldBefore2, owner.gold, "黄金房不扣金币");

        // 教练：加成与训练室倍率相加（TrainInfo.cs:30），工资走 UnionWJSubsidiary 职业 1「雇佣酬劳」
        WorldStore.Employer trainer = new WorldStore.Employer();
        trainer.playerId = member.playerId;
        trainer.account = member.account;
        trainer.name = member.roleName;
        trainer.wjIndex = 18;
        trainer.fightPower = 1000;
        trainer.hiredBy = owner.playerId;
        u.employersOf(3).add(trainer);
        world.saveUnions();
        float ratio = union.trainerRatioOf(u, member.playerId, 18);
        Assertions.assertEquals(0.1f, ratio, 1e-6f, "教练属性参数 1000 万分比 = 0.1");

        double num2 = unionCfg.trainHourlyExp(40) * unionCfg.trainSec() / 3600d;
        long exp3 = (long) (num2 * (unionCfg.trainExpRatio(0) + (double) ratio));
        int goldBefore3 = owner.gold;
        int memberGoldBefore = member.gold;
        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 3, Pb.write(o -> {
            Pb.int32Always(o, 1, 20);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, member.playerId);
            Pb.int32Always(o, 4, 18);
        })));
        drain(ch);
        Assertions.assertEquals(20, owner.economy.trainSlots.get(2).wjIndex, "挂了教练也要开始训练");
        Assertions.assertEquals(goldBefore3 - (int) Math.floor(exp3 / 1.25d) - 10000, owner.gold,
                "普通房训练费 + 教练酬劳 10000");
        Assertions.assertEquals(memberGoldBefore + 7500, member.gold, "教练主人拿酬劳 * 0.75");
        Assertions.assertTrue(trainer.cdEnd > System.currentTimeMillis(), "教练进入 7200s 冷却");

        // 余额不足：不扣费、不覆盖上一次训练、1944 f1 = 被拒武将自己的 wjIndex（不能回 0）
        owner.gold = 0;
        owner.diamond = 0;
        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 4, Pb.write(o -> {
            Pb.int32Always(o, 1, 21);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, 0);
            Pb.int32Always(o, 4, 0);
        })));
        GamePacket fail = pick(drain(ch), MsgIds.S2C_TRAIN_START);
        Assertions.assertNotNull(fail, "余额不足也必须回 1944");
        Assertions.assertEquals(21, Pb.read(fail.body).getInt(1, 0),
                "余额不足 1944 f1 = 被拒武将（回 0 会让客户端把第一个空坑显示成训练中）");
        Assertions.assertEquals(3, owner.economy.trainSlots.size(), "余额不足不覆盖上一次训练");
        Assertions.assertEquals(20, owner.economy.trainSlots.get(2).wjIndex, "上一次训练仍在坑里");
    }

    /**
     * 1540 取消训练：文本 {@code Code.txt 100718}「取消训练将按时间比例获得收益，金币或钻石不会返还」
     * ⇒ 按已练时长比例入账经验、不退费，并推 1935 让客户端显示到手经验。
     */
    @Test
    public void trainCancelGrantsProportionalExpWithoutRefund() {
        resetWorld();
        PlayerRecord rec = fresh(OWNER, "会长");
        // 武将钉到 40 级：WuJiangLevelInfo 第 40 级「到下级经验」= 69250 > 本次按比例入账的 ~13600，
        // 这样 addWjExp 不会触发升级循环，经验断言才是精确值。
        PlayerRecord.Hero hero = rec.findHeroByIndex(18);
        hero.level = 40;
        hero.exp = 0;
        store.save(rec);
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "取消会", "direct");
        u.buildings.put(Integer.valueOf(3), Integer.valueOf(2));
        withHall(u, 3);
        world.saveUnions();
        drain(ch);

        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 1, Pb.write(o -> {
            Pb.int32Always(o, 1, 18);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, 0);
            Pb.int32Always(o, 4, 0);
        })));
        drain(ch);
        int goldAfterStart = rec.gold;
        int expBefore = hero.exp;
        int levelBefore = hero.level;
        // 已练 1/4 时长（28800s 的 7200s）
        rec.economy.trainSlots.get(0).startedAt = System.currentTimeMillis() - 7200_000L;
        store.save(rec);

        union.onTrainCancel(session, new GamePacket(MsgIds.C2S_TRAIN_CANCEL, 2, Pb.write(o -> {
            Pb.int32Always(o, 1, 18);
        })));
        List<GamePacket> out = drain(ch);
        long total = unionCfg.trainTotalExp(40, 0, 0d, unionCfg.trainSec());
        int granted = (int) (total * 7200L / unionCfg.trainSec());
        Assertions.assertEquals(levelBefore, hero.level, "69250 > granted ⇒ 不触发升级");
        Assertions.assertEquals(granted, hero.exp - expBefore,
                "取消按时间比例入账经验（约 1/4）");
        Assertions.assertEquals(goldAfterStart, rec.gold, "取消不退款（100718）");
        Assertions.assertTrue(rec.economy.trainSlots.isEmpty(), "状态已清空");
        GamePacket finish = pick(out, MsgIds.S2C_TRAIN_FINISH);
        Assertions.assertNotNull(finish, "取消也推 1935");
        List<byte[]> results = Pb.read(finish.body).getBytesList(1);
        Assertions.assertFalse(results.isEmpty(), "1935 f1 wjXLResult 非空");
        Assertions.assertEquals(18, Pb.read(results.get(0)).getInt(1, 0), "1935 f1 wjIndex");
        Assertions.assertEquals(granted, Pb.read(results.get(0)).getInt(4, 0), "1935 f4 totalExp");
        Assertions.assertNotNull(pick(out, MsgIds.S2C_TRAIN_INFO), "1934");
    }

    /**
     * 教官按战力缩短训练时长（用户 m20090 #2）：{@code 5% + 45% × min(1, 战力/90000)}，
     * 下限 5%、上限 50%；锚点落在 APK 教练四档的战力边界上
     * （{@code UnionWJSubsidiary.txt} 教练「该级战力上限」= 29999 / 59999 / 89999 / 999999）。
     */
    @Test
    public void coachShortenPercentFollowsPowerBands() {
        resetWorld();
        Assertions.assertEquals(5, UnionService.coachShortenPercent(0), "无战力也保底 5%");
        Assertions.assertEquals(5, UnionService.coachShortenPercent(-100), "负战力按 0 处理");
        Assertions.assertEquals(20, UnionService.coachShortenPercent(29999), "教练第 1 档上限 → 20%");
        Assertions.assertEquals(35, UnionService.coachShortenPercent(59999), "教练第 2 档上限 → 35%");
        Assertions.assertEquals(50, UnionService.coachShortenPercent(89999), "教练第 3 档上限 → 50%");
        Assertions.assertEquals(50, UnionService.coachShortenPercent(200000), "超过最高档封顶 50%");
        Assertions.assertTrue(UnionService.coachShortenPercent(50000) > UnionService.coachShortenPercent(10000),
                "战力越高缩得越多");
    }

    /**
     * 挂了教官的训练坑：{@code totalSec} 按教官战力缩短、1934 f4 {@code leftTime} 同步缩短，
     * 取消时按**缩短后的时长**算比例；经验与费用仍按整场表值（客户端 {@code TrainInfo.cs:28} 用
     * 表值 {@code trainTimeLong} 自算面板总经验，经验跟着缩会与面板显示不一致）。
     */
    @Test
    public void trainSlotCountdownShrinksWithCoach() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        // 被练的武将 = 会长主将（index 18，创号自带）；没请教官那坑用另一个 index，不需要真实武将。
        PlayerRecord.Hero trained = owner.findHeroByIndex(18);
        trained.level = 40;
        trained.exp = 0;
        owner.gold = 10000000;
        owner.diamond = 1000000;
        store.save(owner);
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "教官会", "direct");
        u.buildings.put(Integer.valueOf(3), Integer.valueOf(4));
        withHall(u, 3);
        WorldStore.Employer trainer = new WorldStore.Employer();
        trainer.playerId = member.playerId;
        trainer.account = member.account;
        trainer.name = member.roleName;
        trainer.wjIndex = 18;
        trainer.fightPower = 90000; // 满额档 ⇒ 缩短 50%
        trainer.hiredBy = owner.playerId;
        u.employersOf(3).add(trainer);
        world.saveUnions();
        drain(ch);

        int full = unionCfg.trainSec();
        // 没请教官：整场时长。
        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 1, Pb.write(o -> {
            Pb.int32Always(o, 1, 19);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, 0);
            Pb.int32Always(o, 4, 0);
        })));
        List<GamePacket> out = drain(ch);
        PlayerRecord.Economy.TrainSlot plain = owner.economy.trainSlots.get(0);
        Assertions.assertEquals(full, plain.totalSec, "没请教官 = 整场表值");
        Assertions.assertEquals(full, Pb.read(Pb.read(pick(out, MsgIds.S2C_TRAIN_INFO).body)
                .getBytesList(1).get(0)).getInt(4, 0), "1934 f4 = 整场");

        // 挂 90000 战力的教官：缩到一半。
        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 2, Pb.write(o -> {
            Pb.int32Always(o, 1, 18);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, member.playerId);
            Pb.int32Always(o, 4, 18);
        })));
        out = drain(ch);
        PlayerRecord.Economy.TrainSlot coached = owner.economy.trainSlots.get(1);
        Assertions.assertEquals(full / 2, coached.totalSec, "90000 战力教官缩 50%");
        Assertions.assertEquals(full / 2, coached.leftSec, "leftSec 也按缩短后的时长");
        Pb.Fields slotRow = Pb.read(Pb.read(pick(out, MsgIds.S2C_TRAIN_INFO).body).getBytesList(1).get(1));
        Assertions.assertEquals(18, slotRow.getInt(1, 0), "1934 f1 wjIndex");
        Assertions.assertEquals(full / 2, slotRow.getInt(4, 0), "1934 f4 leftTime 必须跟着缩");

        // 取消：比例的分母是缩短后的时长，分子仍按整场经验算。
        long total = unionCfg.trainTotalExp(40, 0, union.trainerRatioOf(u, member.playerId, 18), full);
        int expBefore = trained.exp;
        coached.startedAt = System.currentTimeMillis() - (full / 4) * 1000L; // 已练缩短后时长的一半
        store.save(owner);
        union.onTrainCancel(session, new GamePacket(MsgIds.C2S_TRAIN_CANCEL, 3, Pb.write(o -> {
            Pb.int32Always(o, 1, 18);
        })));
        int granted = (int) (total * (full / 4) / Math.max(1, full / 2));
        Assertions.assertEquals(granted, trained.exp - expBefore, "取消按缩短后时长的比例入账");
    }

    /**
     * 相位映射必须与客户端按钮/文案一致：发镖窗口 = phase 1（「前往派遣」）、劫镖窗口 = phase 2
     * （「查看镖车」+「派遣截止」+ 允许进拦截场景）、其余（含活动开始前的等待期）= phase 3（结算领奖）。
     * 依据 `MaJiuEntryUI.cs:127-137` / `:158-195`。用纯函数绕开真实时钟。
     */
    @Test
    public void majiuPhaseFollowsApkTwoWindows() {
        resetWorld();
        int start = unionCfg.majiuStartHour() * 3600 + unionCfg.majiuStartMinute() * 60;
        int sendEnd = start + unionCfg.majiuSendSec();
        int raidEnd = sendEnd + unionCfg.majiuRaidSec();

        Assertions.assertEquals(3, union.majiuPhaseAt(start - 1), "活动开始前 = 结算/等待 phase 3");
        Assertions.assertEquals(1, union.majiuPhaseAt(start), "发镖窗口起点 = phase 1");
        Assertions.assertEquals(1, union.majiuPhaseAt(sendEnd - 1), "发镖窗口终点前 = phase 1");
        Assertions.assertEquals(2, union.majiuPhaseAt(sendEnd), "劫镖窗口起点 = phase 2");
        Assertions.assertEquals(2, union.majiuPhaseAt(raidEnd - 1), "劫镖窗口终点前 = phase 2");
        Assertions.assertEquals(3, union.majiuPhaseAt(raidEnd), "劫镖结束 = phase 3");
        Assertions.assertEquals(3, union.majiuPhaseAt(0), "凌晨 = phase 3（100803「运镖活动尚未开始！」）");
    }

    /** 1541 马厩：1936 必须带 f1/f2/f3/f5/f8/f9/f10，且 f2 不再是写死的 86400。 */
    @Test
    public void majiuInfoCarriesEveryField() {
        resetWorld();
        PlayerRecord rec = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(rec, ch);
        createUnion(session, ch, "马厩会", "direct");
        drain(ch);

        union.onMajiuInfo(session, new GamePacket(MsgIds.C2S_MAJIU_INFO, 1, new byte[0]));
        Pb.Fields f = Pb.read(pick(drain(ch), MsgIds.S2C_MAJIU_INFO).body);
        for (int field : new int[]{1, 2, 3, 5, 8, 9, 10}) {
            Assertions.assertTrue(f.fieldKeys().contains(Integer.valueOf(field)), "1936 f" + field);
        }
        int phase = f.getInt(1, -1);
        Assertions.assertTrue(phase >= 1 && phase <= 3, "UnionYunBiaoPhase 1/2/3");
        int left = f.getInt(2, 0);
        Assertions.assertTrue(left > 0 && left <= 86400, "f2 curPhaseLeftTime 必须是真实倒计时");
    }

    /**
     * 1938/1939 结构：1938 永不空包、1939 f1 ret=0 且 f4 带战斗目标
     * （原实现 f1 写成 bool → 客户端读成 ret=1，永不进战斗）。
     */
    @Test
    public void escortPacketShapesMatchProto() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel memberCh = newChannel();
        GameSession memberSession = bind(member, memberCh);

        PlayerRecord.Economy.EscortCart cart1 = new PlayerRecord.Economy.EscortCart();
        cart1.cartId = "cart-1";
        cart1.targetId = 3;
        cart1.sentAt = System.currentTimeMillis();
        owner.economy.escortCarts.add(cart1);
        // 镖车是「今天」的：否则 onRaidResult 入口的 ensureMajiuDay 会先把这辆车跨日结算掉。
        owner.economy.majiuDay = PlayerDumpService.now().substring(0, 10);
        owner.gold = 1000000;
        store.save(owner);

        byte[] sendBody = dump.escortSendRet(owner, cart1);
        Assertions.assertFalse(Pb.read(sendBody).getBytesList(1).isEmpty(),
                "1938 CCMsgUnionBiaoCheInfo 不能空（客户端 MaJiuBasePaiQianUI.cs:123 直接取 [0]）");

        byte[] raid = dump.escortRaidRet(0, 0, null, dump.jjcFightTargetDetailSelf(owner));
        Pb.Fields rf = Pb.read(raid);
        Assertions.assertEquals(0, rf.getInt(1, -1), "1939 f1 ret 必须读成 0");
        Assertions.assertTrue(rf.fieldKeys().contains(Integer.valueOf(4)), "1939 f4 beRaidFormation");
        Assertions.assertTrue(rf.fieldKeys().contains(Integer.valueOf(5)), "1939 f5 needServerCheck");

        int before = owner.gold;
        member.economy.raidTargetId = String.valueOf(owner.playerId);
        member.economy.raidTargetCartId = cart1.cartId;
        store.save(member);
        union.onRaidResult(memberSession, new GamePacket(MsgIds.C2S_RAID_RESULT, 1,
                Pb.write(o -> Pb.boolAlways(o, 1, true))));
        Pb.Fields res = Pb.read(pick(drain(memberCh), MsgIds.S2C_RAID_RESULT).body);
        Assertions.assertTrue(res.getInt(1, 0) > 0, "1940 f1 XDB");
        Assertions.assertTrue(res.getInt(2, 0) > 0, "1940 f2 JinShi（劫掠资金兑换公会晶石）");
        Assertions.assertTrue(res.getInt(3, 0) > 0, "1940 f3 jinbi 按线路金币 × 掠夺比例");
        Assertions.assertTrue(owner.gold < before, "被劫方按 0.2 扣金币");

        // 1937 f6 beRaidTime 是**被掠夺次数**而不是时间（改前写的是 unix 秒，
        // 客户端 MaJiuDuiWuLieBiaoUI.cs:113-115 会渲染成「（被掠夺1755432000次）」）；
        // 1936 f5 / 1939 f2 myRaidSucTime 是**我成功劫镖次数**（改前误用被劫次数）。
        Assertions.assertEquals(1, cart1.beRaidCnt, "这辆车被掠夺次数");
        Assertions.assertEquals(1, member.economy.escortRaidSucTimes, "劫镖方成功次数");
        Assertions.assertEquals(2, member.economy.escortRaidLeft, "劫镖场次在成功结算时才扣");
        Pb.Fields cart = Pb.read(dump.playerBiaoChe(cart1.cartId, cart1.targetId, owner, cart1.beRaidCnt));
        Assertions.assertEquals(1, cart.getInt(6, 0), "CCMsgPlayerBiaoCheInfo f6 必须是小次数");

        union.onRaidRank(memberSession, new GamePacket(MsgIds.C2S_RAID_RANK, 2, new byte[0]));
        List<byte[]> rows = Pb.read(pick(drain(memberCh), MsgIds.S2C_RAID_RANK).body).getBytesList(1);
        Assertions.assertFalse(rows.isEmpty(), "1941 掠夺排行榜不能空");
        Pb.Fields row = Pb.read(rows.get(0));
        Assertions.assertEquals(member.playerId, row.getInt(1, 0));
        Assertions.assertTrue(row.getInt(7, 0) > 0, "f7 raidJinbi");
    }

    /**
     * 拦截失败（= 被劫方防守成功）：被劫方按「线路金币 × UnionMaJiuBase.txt 第 12 行
     * defenceSucAwardRate」拿奖金，并在 1943 f6 defenceAward 里带**劫掠方**公会名
     * （客户端 MaJiuGetAwardUI.cs:159-169 渲染，标题 100806「…来自 {0}工会 的拦截…」）。
     */
    @Test
    public void defenceSuccessPaysLineGoldRatioToCartOwner() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        createUnion(ownerSession, ownerCh, "被劫会", "direct");
        createUnion(memberSession, memberCh, "劫镖会", "direct");
        drain(ownerCh);
        drain(memberCh);

        PlayerRecord.Economy.EscortCart cartDef = new PlayerRecord.Economy.EscortCart();
        cartDef.cartId = "cart-def";
        cartDef.targetId = 3;
        cartDef.sentAt = System.currentTimeMillis();
        owner.economy.escortCarts.add(cartDef);
        owner.economy.majiuDay = PlayerDumpService.now().substring(0, 10);
        owner.economy.escortDefendGold = 0;
        owner.economy.escortDefendUnion = "";
        owner.gold = 1000000;
        store.save(owner);

        int lineGold = unionCfg.majiuTarget(3).gold;
        int expect = (int) Math.floor(lineGold * unionCfg.defendGoldRatio());
        Assertions.assertTrue(expect > 0,
                "UnionMaJiuBase.txt 第 12 行 defenceSucAwardRate 必须 > 0（真服 10%）");

        member.economy.raidTargetId = String.valueOf(owner.playerId);
        member.economy.raidTargetCartId = cartDef.cartId;
        store.save(member);
        union.onRaidResult(memberSession, new GamePacket(MsgIds.C2S_RAID_RESULT, 1,
                Pb.write(o -> Pb.boolAlways(o, 1, false))));

        Assertions.assertEquals(1000000 + expect, owner.gold, "防守成功拿「线路金币 × 比例」奖金");
        Assertions.assertEquals(expect, owner.economy.escortDefendGold);
        Assertions.assertEquals("劫镖会", owner.economy.escortDefendUnion,
                "defendedUnion 是劫掠方公会名，不是自己的");

        union.onYunBiaoAward(ownerSession, new GamePacket(MsgIds.C2S_YUNBIAO_AWARD, 2, new byte[0]));
        List<byte[]> defs = Pb.read(pick(drain(ownerCh), MsgIds.S2C_YUNBIAO_AWARD).body).getBytesList(6);
        Assertions.assertEquals(1, defs.size(), "1943 f6 defenceAward 必须有一条");
        Pb.Fields d = Pb.read(defs.get(0));
        Assertions.assertEquals("劫镖会", d.getString(1));
        Assertions.assertEquals(expect, d.getInt(2, -1), "f2 jinBi 必须 > 0（客户端只显示 >0 的行）");
    }

    /**
     * 押镖统一结算：当天发过车、没点 1548 领奖的收益不能过夜丢掉。
     * 离线玩家按 mailType 7「运镖收益」（{@code ESysMailTypeID.EMTID_UNION_MJ_AWARD=7}，
     * Sys_MailConfig 第7行「你在公会运镖活动中有收益没有及时领取，邮寄给你啦：」）邮寄：
     * 金币 = 线路金币（UnionMaJiuTarget.txt），兄弟币 = 发镖参与奖 100
     * （UnionMaJiuBase.txt「发镖参与的兄弟币奖励（发镖参与奖）」），
     * 同时公会成长值按第 17 行 +200，镖车清零。
     */
    @Test
    public void unclaimedCartIsMailedAsMajiuAwardOnDayRollover() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "被劫会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(ownerCh);
        drain(memberCh);

        // 会长在途镖车 + 过期 majiuDay（制造跨日）；随后把会长下线 → 结算只能走邮件
        PlayerRecord.Economy.EscortCart cartMail = new PlayerRecord.Economy.EscortCart();
        cartMail.cartId = "cart-mail";
        cartMail.targetId = 3;
        cartMail.sentAt = System.currentTimeMillis();
        owner.economy.escortCarts.add(cartMail);
        owner.economy.majiuDay = "2000-01-01";
        owner.gold = 1000000;
        owner.guild.brotherCoin = 0;
        owner.mails.clear();
        store.save(owner);
        hub.unbind(ownerSession);

        int lineGold = unionCfg.majiuTarget(3).gold;
        int growBefore = u.growth;
        // 成员发 1544 劫镖：服务端在时间窗校验之前就会 ensureMajiuDay(target) → 触发会长跨日结算
        union.onRaidCart(memberSession, new GamePacket(MsgIds.C2S_RAID_CART, 2, Pb.write(o -> {
            Pb.int32Always(o, 1, 3);
            Pb.int32Always(o, 2, owner.playerId);
            Pb.stringAlways(o, 3, cartMail.cartId);
        })));
        drain(memberCh);

        Assertions.assertEquals(1000000 + lineGold, owner.gold, "线路金币入袋");
        Assertions.assertEquals(economy.escortSendXdb(), owner.guild.brotherCoin, "发镖参与奖兄弟币");
        Assertions.assertEquals(growBefore + unionCfg.sendGrow(), u.growth,
                "单个运镖成功公会成长值增加（UnionMaJiuBase.txt 第 17 行）");
        Assertions.assertTrue(owner.economy.escortCarts.isEmpty(), "跨日把昨天（含已领奖）的车整体清掉");
        Assertions.assertEquals(1, owner.mails.size(), "离线结算必须发一封运镖收益邮");
        PlayerRecord.Mail m = owner.mails.get(0);
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_MJ_AWARD, m.mailType,
                "mailType 7 = ESysMailTypeID.EMTID_UNION_MJ_AWARD");
        Assertions.assertEquals(lineGold, m.gold, "邮件金币 = 线路金币");
        Assertions.assertEquals(economy.escortSendXdb(), m.brotherCoin, "邮件兄弟币 = 发镖参与奖");
    }

    /**
     * 1543 发镖额度 = **同时在途镖车数上限**（`VipCfg.txt` 第 27 列「发镖数量」，VIP11+ = 2），
     * 不是「每日总次数」。依据是客户端唯一的门控
     * `EmBattleSystem.cs:2653 myBiaoCheInfo.Count >= VipManager.FaBiaoCount`，
     * 而 1936/1942 f7 `myBiaoCheInfo` 就是「我在途镖车 GUID 列表」（客户端从不 Clear/Remove，
     * 只能靠服务端快照整体替换）。改前服务端把它做成了每日计数器（`escortSendLeft`）
     * ⇒ VIP11+ 的 UI 显示 2/2 却发不出第二辆。
     */
    @Test
    public void vipHighLevelRunsTwoCartsConcurrently() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "双镖");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "双镖会", "direct");
        // 马厩要求议事厅 2 级（Union.txt）。
        withHall(world.findUnionByName("双镖会"), 2);
        drain(ch);

        PlayerRecord.Hero main = owner.findHeroByIndex(owner.mainHeroIndex);
        main.fightPower = 50000; // 目的地 1（迷茫沼泽）需 40000
        owner.economy.chargedDiamond = 45000; // VIP11（VipCfg.txt 第 12 行）
        store.save(owner);
        Assertions.assertEquals(2, economy.faBiaoCount(owner.economy.chargedDiamond),
                "VipCfg.txt 第 27 列：VIP11 发镖数量 = 2");

        byte[] body = Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.bytesAlways(o, 2, Pb.write(f -> {
                Pb.int32Always(f, 1, PlayerRecord.FORMATION_MAJIU);
                Pb.stringAlways(f, 2, main.id);
            }));
        });

        pinClock(18 * 3600 + 600); // 发镖时段 18:00-20:00（UnionMaJiuTime.txt 18:00 起 7200s）
        union.onSendCart(session, new GamePacket(MsgIds.C2S_SEND_CART, 1, body));
        Assertions.assertNotNull(pick(drain(ch), MsgIds.S2C_SEND_CART), "第 1 辆必须成功");

        union.onSendCart(session, new GamePacket(MsgIds.C2S_SEND_CART, 2, body));
        List<GamePacket> second = drain(ch);
        Assertions.assertEquals(2, owner.economy.escortCarts.size(), "VIP11 可以同时在途 2 辆");
        PlayerRecord.Economy.EscortCart a = owner.economy.escortCarts.get(0);
        PlayerRecord.Economy.EscortCart b = owner.economy.escortCarts.get(1);
        Assertions.assertNotEquals(a.cartId, b.cartId, "每辆车必须有唯一 GUID（1544 f3 靠它认车）");
        byte[] sendBody = pick(second, MsgIds.S2C_SEND_CART).body;
        Assertions.assertEquals(b.cartId, Pb.read(Pb.read(sendBody).getBytesList(1).get(0)).getString(1),
                "1938 只放刚发的那辆（客户端 MaJiuBasePaiQianUI.cs:123 只读 biaoCheInfo[0]）");

        GamePacket info = pick(second, MsgIds.S2C_MAJIU_INFO);
        Assertions.assertNotNull(info, "1543 后必须回 1936 状态包");
        List<String> mine = Pb.read(info.body).getStrings(7);
        Assertions.assertEquals(2, mine.size(), "1936 f7 myBiaoCheInfo = 我在途镖车 GUID 列表");
        Assertions.assertTrue(mine.contains(a.cartId) && mine.contains(b.cartId), "两辆都在 f7 里");

        // 第 3 辆：超并发上限 ⇒ 不回 1938，车数不变
        union.onSendCart(session, new GamePacket(MsgIds.C2S_SEND_CART, 3, body));
        List<GamePacket> third = drain(ch);
        Assertions.assertNull(pick(third, MsgIds.S2C_SEND_CART), "超过并发上限不得回 1938");
        Assertions.assertEquals(2, owner.economy.escortCarts.size(), "超限不得再发");
    }

    /**
     * 1544 f3 `targetBiaoCheGUID` 必须**按车认车**：同一玩家可以同时在途多辆，
     * 打劫 A 车不能让 B 车也变成「已被打劫」（改前服务端只按玩家找唯一那辆车）。
     * 客户端三个字段全部取自车级对象 `MaJiuLanJieDuiWuUI.TargetInfo`（`CCMsgPlayerBiaoCheInfo`），
     * 发送点 `EmBattleSystem.cs:2686-2690`。
     */
    @Test
    public void raidPicksTheExactCartByGuid() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "车主");
        PlayerRecord member = fresh(MEMBER, "劫匪");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        createUnion(ownerSession, ownerCh, "车主会", "direct");
        createUnion(memberSession, memberCh, "劫匪会", "direct");
        drain(ownerCh);
        drain(memberCh);

        PlayerRecord.Economy.EscortCart cartX = cartOf(owner, "cart-X", 1);
        PlayerRecord.Economy.EscortCart cartY = cartOf(owner, "cart-Y", 3);
        owner.economy.majiuDay = PlayerDumpService.now().substring(0, 10);
        owner.gold = 1000000;
        store.save(owner);

        byte[] raidY = Pb.write(o -> {
            Pb.int32Always(o, 1, 3);
            Pb.int32Always(o, 2, owner.playerId);
            Pb.stringAlways(o, 3, cartY.cartId);
        });

        pinClock(20 * 3600 + 600); // 劫镖时段 20:00-22:00
        union.onRaidCart(memberSession, new GamePacket(MsgIds.C2S_RAID_CART, 1, raidY));
        Pb.Fields ret = Pb.read(pick(drain(memberCh), MsgIds.S2C_RAID_CART).body);
        Assertions.assertEquals(0, ret.getInt(1, -1), "选中的车存在 ⇒ ret=0 进战斗");
        Assertions.assertTrue(ret.fieldKeys().contains(Integer.valueOf(4)), "1939 f4 beRaidFormation");
        Pb.Fields upd = Pb.read(ret.getBytes(3));
        Assertions.assertEquals("劫匪", upd.getString(2), "1939 f3 msgUpdate 的 raidingPlayerName");

        Assertions.assertEquals("劫匪", cartY.raiderName, "被选中的车进入「掠夺中」");
        Assertions.assertEquals(0, cartY.beRaidCnt, "还没结算，被劫次数不变");
        Assertions.assertEquals("", cartX.raiderName, "没被选中的车必须原样不动");

        // 1545 结算成功 → 只有 cartY 变成「已被打劫」
        union.onRaidResult(memberSession, new GamePacket(MsgIds.C2S_RAID_RESULT, 2,
                Pb.write(o -> Pb.boolAlways(o, 1, true))));
        drain(memberCh);
        Assertions.assertTrue(cartY.hasBeenRaid, "被劫的那辆标记已打劫");
        Assertions.assertEquals(1, cartY.beRaidCnt);
        Assertions.assertFalse(cartX.hasBeenRaid, "同一玩家的另一辆车不受影响");
        Assertions.assertEquals(0, cartX.beRaidCnt);

        // 再劫同一辆 → ret=2（100793「他已经被别人打劫过了」）
        union.onRaidCart(memberSession, new GamePacket(MsgIds.C2S_RAID_CART, 3, raidY));
        Assertions.assertEquals(2, raidRet(drain(memberCh)), "同一辆车不能被二次打劫");

        // 另一辆仍可被劫（每辆车各一次）
        union.onRaidCart(memberSession, new GamePacket(MsgIds.C2S_RAID_CART, 4, Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, owner.playerId);
            Pb.stringAlways(o, 3, cartX.cartId);
        })));
        Assertions.assertEquals(0, raidRet(drain(memberCh)), "同一玩家的另一辆车仍可被劫");
        Assertions.assertEquals("劫匪", cartX.raiderName);
    }

    /**
     * 1939 ret 不是 0/1：客户端 `MaJiuBaseLanJieUI.cs:135-158` 对 4/3/2/1 各弹一条 StrTable
     * （4→100790「干了一票就知足了吧…」额度用完 / 3→100791「劫镖活动已结束！」/
     * 2→100793「啊哦~他已经被别人打劫过了…」/ 1→100794「靠！有人正在打劫他…」）。
     * 改前服务端所有失败都回 ret=1 ⇒ 四种原因同一句提示。
     */
    @Test
    public void raidRejectRetCodesDistinguishReasons() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "车主");
        PlayerRecord member = fresh(MEMBER, "劫匪");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        createUnion(ownerSession, ownerCh, "车主会", "direct");
        createUnion(memberSession, memberCh, "劫匪会", "direct");
        drain(ownerCh);
        drain(memberCh);

        PlayerRecord.Economy.EscortCart cart = cartOf(owner, "cart-1", 1);
        owner.economy.majiuDay = PlayerDumpService.now().substring(0, 10);
        owner.gold = 1000000;
        store.save(owner);
        member.economy.majiuDay = owner.economy.majiuDay;
        store.save(member);

        byte[] req = Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, owner.playerId);
            Pb.stringAlways(o, 3, cart.cartId);
        });

        // 3 = 非劫镖时段（100791）
        pinClock(10 * 3600);
        union.onRaidCart(memberSession, new GamePacket(MsgIds.C2S_RAID_CART, 1, req));
        Assertions.assertEquals(3, raidRet(drain(memberCh)), "时段外 ret=3");

        // 4 = 场次用完（100790）
        pinClock(20 * 3600 + 600);
        member.economy.escortRaidLeft = 0;
        store.save(member);
        union.onRaidCart(memberSession, new GamePacket(MsgIds.C2S_RAID_CART, 2, req));
        Assertions.assertEquals(4, raidRet(drain(memberCh)), "场次用完 ret=4");

        // 1 = 有人正在打劫（100794）
        member.economy.escortRaidLeft = 3;
        store.save(member);
        cart.raiderName = "路人";
        union.onRaidCart(memberSession, new GamePacket(MsgIds.C2S_RAID_CART, 3, req));
        Assertions.assertEquals(1, raidRet(drain(memberCh)), "拦截进行中 ret=1");
        Assertions.assertEquals("路人", cart.raiderName, "被拒时不得抢占别人的拦截");
        cart.raiderName = "";

        // 2 = 已被打劫（100793）
        cart.hasBeenRaid = true;
        union.onRaidCart(memberSession, new GamePacket(MsgIds.C2S_RAID_CART, 4, req));
        Assertions.assertEquals(2, raidRet(drain(memberCh)), "已被打劫 ret=2");
        cart.hasBeenRaid = false;

        // 2 = 车 GUID 过期：不能顺手改打别的车
        union.onRaidCart(memberSession, new GamePacket(MsgIds.C2S_RAID_CART, 5, Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, owner.playerId);
            Pb.stringAlways(o, 3, "no-such-cart");
        })));
        Assertions.assertEquals(2, raidRet(drain(memberCh)), "匹配不到的车 GUID 也是 ret=2");
        Assertions.assertEquals("", cart.raiderName, "匹配不到时不得动到别的车");

        // 0 = 进战斗，且 f3 msgUpdate 非空
        union.onRaidCart(memberSession, new GamePacket(MsgIds.C2S_RAID_CART, 6, req));
        Pb.Fields ok = Pb.read(pick(drain(memberCh), MsgIds.S2C_RAID_CART).body);
        Assertions.assertEquals(0, ok.getInt(1, -1), "正常路径 ret=0");
        Assertions.assertTrue(ok.getBytes(3).length > 0, "1939 f3 msgUpdate 不能为空");
    }

    /**
     * 出手额度 = {@code UnionMaJiuBase.txt}「单次活动有效掠夺场次（不管成功还是失败） 3」。
     *
     * <p>它与「成功额度」（{@code VipCfg.txt} 第 29 列「劫镖次数」，客户端
     * {@code MaJiuLanJieDuiWuUI.cs:264} 用 {@code myRaidSucTime >= JieBiaoCount} 门控）
     * 是两套独立上限：失败**不占**成功额度，但要占出手额度。客户端没有任何「已出手次数」
     * 字段（1936/1943 只带成功次数）⇒ 这条只能服务端拦，拦到时回 1939 ret=4
     * （与「成功额度用完」同一档，客户端文案 100790「今日劫镖次数已用完」）。
     */
    @Test
    public void raidAttemptsAreCappedRegardlessOfOutcome() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "车主");
        PlayerRecord raider = fresh(MEMBER, "劫匪");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel raiderCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession raiderSession = bind(raider, raiderCh);
        createUnion(ownerSession, ownerCh, "车主会", "direct");
        createUnion(raiderSession, raiderCh, "劫匪会", "direct");
        drain(ownerCh);
        drain(raiderCh);

        PlayerRecord.Economy.EscortCart cart = cartOf(owner, "cart-att", 1);
        owner.economy.majiuDay = PlayerDumpService.now().substring(0, 10);
        owner.gold = 1000000;
        store.save(owner);
        raider.economy.majiuDay = owner.economy.majiuDay;
        raider.economy.escortRaidLeft = 3;
        store.save(raider);

        byte[] req = Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, owner.playerId);
            Pb.stringAlways(o, 3, cart.cartId);
        });
        pinClock(20 * 3600 + 600);

        // 出手额度用完（3 次）⇒ ret=4，即使成功额度还剩 3 次
        raider.economy.escortRaidTimes = 3;
        store.save(raider);
        union.onRaidCart(raiderSession, new GamePacket(MsgIds.C2S_RAID_CART, 1, req));
        Assertions.assertEquals(4, raidRet(drain(raiderCh)), "出手额度用完也要 ret=4");

        // 只剩 1 次出手：发起 → 打输 ⇒ 出手额度记 1 次，成功额度不动
        raider.economy.escortRaidTimes = 2;
        store.save(raider);
        union.onRaidCart(raiderSession, new GamePacket(MsgIds.C2S_RAID_CART, 2, req));
        Assertions.assertEquals(0, raidRet(drain(raiderCh)), "还有出手额度时 ret=0");
        union.onRaidResult(raiderSession, new GamePacket(MsgIds.C2S_RAID_RESULT, 3,
                Pb.write(o -> Pb.bool(o, 1, false))));
        drain(raiderCh);
        Assertions.assertEquals(3, raider.economy.escortRaidTimes, "失败也要占一次出手额度");
        Assertions.assertEquals(0, raider.economy.escortRaidSucTimes, "失败不占成功次数");
        Assertions.assertEquals(3, raider.economy.escortRaidLeft, "失败不占成功额度");

        // 出手额度已满 ⇒ 再发起就是 ret=4
        union.onRaidCart(raiderSession, new GamePacket(MsgIds.C2S_RAID_CART, 4, req));
        Assertions.assertEquals(4, raidRet(drain(raiderCh)), "第 4 次出手必须被拦");

        // 跨活动日重置出手额度
        raider.economy.majiuDay = "2000-01-01";
        store.save(raider);
        union.onRaidCart(raiderSession, new GamePacket(MsgIds.C2S_RAID_CART, 5, req));
        Assertions.assertEquals(0, raidRet(drain(raiderCh)), "跨活动日出手额度归零");
        Assertions.assertEquals(0, raider.economy.escortRaidTimes, "出手额度在 1545 结算时才记，1544 只发起");
    }

    /**
     * 「正在拦截」标记的服务端兜底清理（{@code UnionMaJiuBase.txt}「单场掠夺超时时间（秒）」300s + 60s 宽限）。
     *
     * <p>玩家在拦截战斗里掉线时永远不发 1545 ⇒ 那辆车的 {@code raiderName} 会挂一整天：
     * 对所有人都是 1939 ret=1、被劫方整天显示「掠夺中」。服务端必须在超时后清掉标记，
     * 且 1936/1942 f4 列表不能再显示这个幽灵拦截者。
     */
    @Test
    public void staleRaidMarkerIsClearedAfterTimeout() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "车主");
        PlayerRecord raider = fresh(MEMBER, "劫匪");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel raiderCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession raiderSession = bind(raider, raiderCh);
        createUnion(ownerSession, ownerCh, "车主会", "direct");
        createUnion(raiderSession, raiderCh, "劫匪会", "direct");
        drain(ownerCh);
        drain(raiderCh);

        PlayerRecord.Economy.EscortCart cart = cartOf(owner, "cart-stale", 1);
        owner.economy.majiuDay = PlayerDumpService.now().substring(0, 10);
        owner.gold = 1000000;
        store.save(owner);
        raider.economy.majiuDay = owner.economy.majiuDay;
        raider.economy.escortRaidLeft = 3;
        store.save(raider);

        byte[] req = Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, owner.playerId);
            Pb.stringAlways(o, 3, cart.cartId);
        });
        pinClock(20 * 3600 + 600);

        // 刚发起（时间戳是现在）⇒ 仍在拦截中，ret=1
        cart.raiderName = "路人";
        cart.raidStartedAt = System.currentTimeMillis();
        union.onRaidCart(raiderSession, new GamePacket(MsgIds.C2S_RAID_CART, 1, req));
        Assertions.assertEquals(1, raidRet(drain(raiderCh)), "未超时不能被抢");
        Assertions.assertEquals("路人", cart.raiderName, "未超时不得清标记");

        // 超时（300s + 60s 宽限之外）⇒ 清标记，允许接手
        cart.raiderName = "路人";
        cart.raidStartedAt = System.currentTimeMillis() - 400_000L;
        union.onRaidCart(raiderSession, new GamePacket(MsgIds.C2S_RAID_CART, 2, req));
        Assertions.assertEquals(0, raidRet(drain(raiderCh)), "超时后可以接手");
        Assertions.assertEquals("劫匪", cart.raiderName, "超时标记被清后换成新拦截者");

        // f4 列表也不能再显示幽灵拦截者
        cart.raiderName = "路人";
        cart.raidStartedAt = System.currentTimeMillis() - 400_000L;
        store.save(owner);
        union.onMajiuInfo(raiderSession, new GamePacket(MsgIds.C2S_MAJIU_INFO, 3, new byte[0]));
        List<byte[]> rows = Pb.read(pick(drain(raiderCh), MsgIds.S2C_MAJIU_INFO).body).getBytesList(4);
        Assertions.assertEquals(1, rows.size(), "本车仍在可劫列表里");
        Assertions.assertEquals("", Pb.read(rows.get(0)).getString(2), "超时的拦截者不出现在 f4");
    }

    /**
     * 1547 刷新可劫列表的两条规则（用户 m19006 #11 + m19522 #2）：
     * ①**职位门** —— 客户端 `MaJiuShuaXinUI.cs:83-88 OnQueDingClicked` 在发包之前就要求
     * {@code mEUnionJob >= 2}（否则弹 100813），服务端必须同样只认会长/副会长；
     * 判别只看 `rec.guild.job`（`onCreate` 同时写 `rec.guild.job = "owner"` 与成员条目，
     * 按成员条目判会把刚建会的会长挡在门外）。
     * ②**消耗跟表 = 0（免费）** —— `UnionMaJiuBase.txt` 的「等级N刷新所需公会晶石」全为 0，
     * 客户端面板按同一列显示 0 ⇒ 服务端也按表扣 0（用户 m19522 #2 拍板，撤销 m19006 #11 的
     * 10/20/40 兜底，避免「显示 0、实扣 10/20/40」的不一致）；次数上限仍取表值 2。
     */
    @Test
    public void refreshRaidNeedsOwnerOrElderAndFollowsTableCost() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "会员");
        EmbeddedChannel chO = newChannel();
        EmbeddedChannel chM = newChannel();
        GameSession sO = bind(owner, chO);
        GameSession sM = bind(member, chM);
        WorldStore.UnionRecord u = createUnion(sO, chO, "刷新会", "direct");
        union.onJoin(sM, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        u.crystal = 1000;
        world.saveUnions();
        drain(chO);
        drain(chM);

        // 普通会员：不生效，但**仍回 1942 现状快照**（该协议没有独立错误包，客户端只重画面板）。
        // 响应 msgId 必须是 1942（`ᝁ.cs:198` 把 1942 绑到 OnRequestRefreshRaidRet，1936 绑的是
        // OnRequestMaJiuInfo_Ret；两者载荷同为 CCMsgRequestMaJiuInfoRet，但事件名不同，
        // 可劫列表 `MaJiuBaseLanJieUI.cs:111-118` 只听 EN_RequestRefreshRaidRet）。
        union.onRefreshRaid(sM, new GamePacket(MsgIds.C2S_REFRESH_RAID, 2, new byte[0]));
        Assertions.assertNotNull(pick(drain(chM), MsgIds.S2C_REFRESH_RAID), "被拒绝也必须回 1942");
        Assertions.assertEquals(0, member.economy.majiuResetTimes, "会员不得计次");
        Assertions.assertEquals(1000, u.crystal, "会员不得扣公会晶石");

        union.onRefreshRaid(sO, new GamePacket(MsgIds.C2S_REFRESH_RAID, 3, new byte[0]));
        drain(chO);
        Assertions.assertEquals(1, owner.economy.majiuResetTimes, "会长第 1 次刷新");
        Assertions.assertEquals(1000, u.crystal, "表值为 0 ⇒ 刷新免费，不得扣公会晶石");

        union.onRefreshRaid(sO, new GamePacket(MsgIds.C2S_REFRESH_RAID, 4, new byte[0]));
        drain(chO);
        Assertions.assertEquals(2, owner.economy.majiuResetTimes, "会长第 2 次刷新");
        Assertions.assertEquals(1000, u.crystal, "第 2 次刷新同样免费");

        // 上限 = `UnionMaJiuBase.txt`「单次活动刷新次数」2 次。
        union.onRefreshRaid(sO, new GamePacket(MsgIds.C2S_REFRESH_RAID, 5, new byte[0]));
        drain(chO);
        Assertions.assertEquals(2, owner.economy.majiuResetTimes, "超过上限不得计次");
        Assertions.assertEquals(1000, u.crystal, "超过上限不得扣晶石");
    }

    /**
     * 1936 f4 可劫列表**只能跨公会**（用户 m19006 #11：可以跨服、不能劫自己公会）。
     * 同公会成员的镖车不进列表；1544 伪造包直接点名同公会的车也按 ret=2（与「车不存在/已被劫」同档）
     * 拒绝，且不得把车标记成「掠夺中」。
     */
    @Test
    public void raidTargetsAreCrossUnionOnly() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长甲");
        PlayerRecord mate = fresh(MEMBER, "同会会员");
        PlayerRecord outsider = fresh(RIVAL, "外会玩家");
        EmbeddedChannel chA = newChannel();
        EmbeddedChannel chO = newChannel();
        GameSession sA = bind(owner, chA);
        GameSession sO = bind(outsider, chO);
        WorldStore.UnionRecord ua = createUnion(sA, chA, "甲会", "direct");
        createUnion(sO, chO, "乙会", "direct");

        // 同会会员（不经过 onJoin，直接补成员条目即可）。
        mate.guild.id = ua.id;
        mate.guild.name = ua.name;
        mate.guild.job = "member";
        WorldStore.Member m = new WorldStore.Member();
        m.playerId = mate.playerId;
        m.account = mate.account;
        m.name = mate.roleName;
        m.job = "member";
        ua.members.add(m);

        String today = PlayerDumpService.now().substring(0, 10);
        owner.economy.majiuDay = today;
        mate.economy.majiuDay = today;
        outsider.economy.majiuDay = today;
        cartOf(owner, "cart-self", 1);
        PlayerRecord.Economy.EscortCart mateCart = cartOf(mate, "cart-mate", 1);
        cartOf(outsider, "cart-out", 1);
        store.save(owner);
        store.save(mate);
        store.save(outsider);
        world.saveUnions();
        drain(chA);
        drain(chO);

        union.onMajiuInfo(sA, new GamePacket(MsgIds.C2S_MAJIU_INFO, 1, new byte[0]));
        Pb.Fields info = Pb.read(pick(drain(chA), MsgIds.S2C_MAJIU_INFO).body);
        List<byte[]> raidCarts = info.getBytesList(4);
        Assertions.assertEquals(1, raidCarts.size(), "只应有外会那辆车（自己 + 同会都不进列表）");
        Assertions.assertEquals("cart-out", Pb.read(Pb.read(raidCarts.get(0)).getBytes(3)).getString(1),
                "可劫目标必须是外会的车");

        // 伪造包点名同会会员的车 → ret 2，且不得标记掠夺中。
        pinClock(20 * 3600 + 600);
        union.onRaidCart(sA, new GamePacket(MsgIds.C2S_RAID_CART, 2, Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.int32Always(o, 2, mate.playerId);
            Pb.stringAlways(o, 3, mateCart.cartId);
        })));
        Assertions.assertEquals(2, raidRet(drain(chA)), "同公会的车不能被劫（ret=2）");
        Assertions.assertEquals("", mateCart.raiderName, "被拒时不得标记掠夺中");
    }

    /**
     * 1936/1942 f4 可劫列表**按目的地随机抽样**（用户 m19393 #2）：同一目的地候选
     * 超过 {@code RAID_LIST_PER_TARGET}(10) 时只下发 10 辆；另一个目的地只有 1 辆候选
     * ⇒ 它仍必须能看到（抽样按目的地分组，避免冷门目的地整片空白）。
     */
    @Test
    public void raidListIsSampledPerTarget() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "甲会会长");
        PlayerRecord rival = fresh("test-d6-rival", "乙会车主");
        EmbeddedChannel chA = newChannel();
        EmbeddedChannel chR = newChannel();
        GameSession sA = bind(owner, chA);
        GameSession sR = bind(rival, chR);
        createUnion(sA, chA, "甲会", "direct");
        createUnion(sR, chR, "乙会", "direct");

        String today = PlayerDumpService.now().substring(0, 10);
        rival.economy.majiuDay = today;
        for (int i = 0; i < 12; i++) {
            cartOf(rival, "cart-" + i, 1);
        }
        cartOf(rival, "cart-other", 3);
        store.save(rival);
        world.saveUnions();
        drain(chA);
        drain(chR);

        union.onMajiuInfo(sA, new GamePacket(MsgIds.C2S_MAJIU_INFO, 1, new byte[0]));
        List<byte[]> rows = Pb.read(pick(drain(chA), MsgIds.S2C_MAJIU_INFO).body).getBytesList(4);
        int target1 = 0;
        int target3 = 0;
        for (byte[] row : rows) {
            int targetId = Pb.read(Pb.read(row).getBytes(3)).getInt(2, 0);
            if (targetId == 1) {
                target1++;
            } else if (targetId == 3) {
                target3++;
            }
        }
        Assertions.assertEquals(10, target1, "目的地 1 有 12 辆候选 ⇒ 抽样上限 10");
        Assertions.assertEquals(1, target3, "目的地 3 只有 1 辆候选 ⇒ 仍能看到（按目的地分组抽样）");
    }

    /** 1520 单建筑全部佣兵（1917）与 1527 作战室雇佣（1924）/1521 替换（1918）。 */
    @Test
    public void allEmployersAndZzsEmploy() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);

        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "佣兵会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        u.crystal = 100000;
        world.saveUnions();
        drain(ownerCh);

        union.onBuildingAllEmployers(ownerSession, new GamePacket(MsgIds.C2S_BUILDING_ALL_EMPLOYERS, 2,
                Pb.write(o -> Pb.int32Always(o, 1, 6))));
        GamePacket all = pick(drain(ownerCh), MsgIds.S2C_BUILDING_ALL_EMPLOYERS);
        Assertions.assertNotNull(all, "1520 → 1917（原实现完全没有 handler）");
        Assertions.assertEquals(6, Pb.read(all.body).getInt(1, 0), "f1 buildingType");

        union.onEmployWjZzs(ownerSession, new GamePacket(MsgIds.C2S_EMPLOY_WJ_ZZS, 3, Pb.write(o -> {
            Pb.int32Always(o, 1, member.playerId);
            Pb.int32Always(o, 2, 18);
        })));
        List<GamePacket> employOut = drain(ownerCh);
        Pb.Fields e = Pb.read(pick(employOut, MsgIds.S2C_EMPLOY_WJ_ZZS_RET).body);
        Assertions.assertTrue(e.getBool(1), "1924 suc");
        Assertions.assertEquals(member.playerId, Pb.read(e.getBytes(2)).getInt(1, 0),
                "f2 employInfo.playerGuid");
        Assertions.assertTrue(e.getInt(4, 0) > 0, "f4 EmployPrice");
        Assertions.assertFalse(u.employersOf(6).isEmpty(), "佣兵入档");
        Assertions.assertTrue(hasIn(employOut, MsgIds.S2C_BUILDING_ALL_EMPLOYERS), "雇佣后推 1917");

        union.onReplaceEmployer(ownerSession, new GamePacket(MsgIds.C2S_REPLACE_BUILDING_EMPLOYER, 4,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 6);
                    Pb.int32Always(o, 2, 0);
                    Pb.int32Always(o, 3, 18);
                })));
        Pb.Fields rep = Pb.read(pick(drain(ownerCh), MsgIds.S2C_REPLACE_EMPLOYER_RET).body);
        Assertions.assertTrue(rep.getBool(1), "1918 f1 result");
    }

    /**
     * 第二轮复核：佣兵格位是**每个玩家自己的**（1915 的类名 {@code CCMsgOnePlayeAllBuildingEmployerInfo}，
     * 客户端 {@code UnionManagerSystem.cs:298-327} 把 wjIndex[] 填进「我的建筑」格位），
     * 1917 是**候选**列表；首次雇佣（空表点第 0 格）必须成功，且受 {@code VipCfg} 公会佣兵数量上限约束。
     */
    @Test
    public void employerSlotsArePerPlayerAndCandidatesComeFromMembers() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        owner.economy.chargedDiamond = 0;
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);

        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "格位会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(ownerCh);
        drain(memberCh);

        // 空表 + 第 0 格：改前 `index < list.size()` 守卫会直接拒掉 ⇒ 首次雇佣永远失败。
        union.onReplaceEmployer(ownerSession, new GamePacket(MsgIds.C2S_REPLACE_BUILDING_EMPLOYER, 1,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 2);
                    Pb.int32Always(o, 2, 0);
                    Pb.int32Always(o, 3, owner.mainHeroIndex);
                })));
        Assertions.assertTrue(Pb.read(pick(drain(ownerCh), MsgIds.S2C_REPLACE_EMPLOYER_RET).body).getBool(1),
                "空表时第 0 格必须能雇佣（改前 1918 false）");

        Assertions.assertEquals(owner.mainHeroIndex, slotWj(ownerSession, ownerCh, 2, 0),
                "会长 1915 的格位 0 是自己雇的武将");
        Assertions.assertEquals(0, slotWj(memberSession, memberCh, 2, 0),
                "格位按雇佣者分开：成员不能在自己的建筑里看到会长的佣兵");

        // 1917：候选 = 公会全体成员的武将（厨房/训练场/医院面板用它选人）。
        union.onBuildingAllEmployers(memberSession, new GamePacket(MsgIds.C2S_BUILDING_ALL_EMPLOYERS, 2,
                Pb.write(o -> Pb.int32Always(o, 1, 2))));
        Pb.Fields cand = Pb.read(pick(drain(memberCh), MsgIds.S2C_BUILDING_ALL_EMPLOYERS).body);
        List<Integer> candOwners = new ArrayList<>();
        for (byte[] one : cand.getBytesList(2)) {
            candOwners.add(Integer.valueOf(Pb.read(one).getInt(1, 0)));
        }
        Assertions.assertTrue(candOwners.contains(Integer.valueOf(owner.playerId))
                && candOwners.contains(Integer.valueOf(member.playerId)),
                "1917 候选覆盖公会全体成员的武将，而不是「已上岗的格位」");

        // VipCfg「公会佣兵数量」VIP0 = 2：第 2 格必须被拒（客户端给这些格位挂锁）。
        union.onReplaceEmployer(ownerSession, new GamePacket(MsgIds.C2S_REPLACE_BUILDING_EMPLOYER, 3,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 2);
                    Pb.int32Always(o, 2, 2);
                    Pb.int32Always(o, 3, owner.mainHeroIndex);
                })));
        Assertions.assertFalse(Pb.read(pick(drain(ownerCh), MsgIds.S2C_REPLACE_EMPLOYER_RET).body).getBool(1),
                "超出 VipCfg 公会佣兵数量(2) 的格位必须被拒");
    }

    /**
     * 1543 目的地门槛（马厩等级 + 阵容总战力）—— 时间窗用真实时钟、测试无法进 18:00–20:00，
     * 故直接断言包级可见的门槛与战力解析两半。
     */
    @Test
    public void sendCartChecksMajiuLevelAndFormationPower() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "押镖门槛");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "门槛会", "direct");
        drain(ch);

        PlayerRecord.Hero main = owner.findHeroByIndex(owner.mainHeroIndex);
        byte[] formation = Pb.write(o -> {
            Pb.int32Always(o, 1, PlayerRecord.FORMATION_MAJIU);
            Pb.stringAlways(o, 2, main.id);
        });
        // 目的地 1 = 迷茫沼泽：需马厩 1 级 / 战力 40000（UnionMaJiuTarget.txt 第 2 行）
        Assertions.assertEquals(1000, union.formationFightPower(owner, formation),
                "阵容总战力 = 上阵武将战力之和（客户端 EmBattleSystem.GetTotalFightPower 同口径）");
        Assertions.assertFalse(union.canSendCart(owner, 1, union.formationFightPower(owner, formation)),
                "战力 1000 < 40000：必须拒发");
        Assertions.assertTrue(union.canSendCart(owner, 1, 40000), "马厩 1 级 + 战力 40000：放行");
        // 目的地 7 = 翡翠林地：需马厩 7 级 / 战力 450000 —— 马厩等级不足时必须拒
        Assertions.assertFalse(union.canSendCart(owner, 7, 450000), "马厩 1 级 < 7 级：必须拒发");
        u.buildings.put(Integer.valueOf(5), Integer.valueOf(7));
        Assertions.assertTrue(union.canSendCart(owner, 7, 450000), "马厩 7 级 + 战力 450000：放行");
        Assertions.assertFalse(union.canSendCart(owner, 7, 449999), "战力差 1 点也必须拒发");
        Assertions.assertFalse(union.canSendCart(owner, 99, 999999), "不存在的目的地必须拒发");
        Assertions.assertEquals(0, union.formationFightPower(owner, new byte[0]), "空阵容战力 = 0");
    }

    /**
     * 建筑「使用条件：攻略组等级达到 Lv.N」（{@code tables\Union.txt:21-32}）：厨房 15 / 训练场 28 /
     * 商城 20 / 马厩 25 / 作战室 15 / 医院 28，议事厅无要求。客户端 {@code UnionBaseSceneManager.cs:390-536}
     * 与 {@code PlayGameState.cs:7600-7601} 拿**玩家自身等级**比这个值（不满足弹 {@code Code.txt} 100687），
     * 只约束「使用」不约束解锁；服务端原为零消费，任何等级都能用。
     */
    @Test
    public void buildingUseRequiresPlayerLevel() {
        resetWorld();
        Assertions.assertEquals(15, unionCfg.usePlayerLevel(2), "厨房");
        Assertions.assertEquals(28, unionCfg.usePlayerLevel(3), "训练场");
        Assertions.assertEquals(20, unionCfg.usePlayerLevel(4), "商城");
        Assertions.assertEquals(25, unionCfg.usePlayerLevel(5), "马厩");
        Assertions.assertEquals(15, unionCfg.usePlayerLevel(6), "作战室");
        Assertions.assertEquals(28, unionCfg.usePlayerLevel(7), "医院");
        Assertions.assertEquals(0, unionCfg.usePlayerLevel(1), "议事厅无使用等级要求");

        PlayerRecord owner = fresh(OWNER, "等级门槛");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "等级会", "direct");
        // 医院要求议事厅 4 级（Union.txt），本例要用 40 级验医院，先把议事厅拉到位。
        withHall(world.findUnionByName("等级会"), 4);
        drain(ch);
        owner.level = 14;
        Assertions.assertFalse(union.canUseBuilding(owner, 2), "14 级不得使用厨房(15)");
        Assertions.assertFalse(union.canUseBuilding(owner, 6), "14 级不得使用作战室(15)");
        Assertions.assertTrue(union.canUseBuilding(owner, 1), "议事厅无门槛");
        owner.level = 15;
        Assertions.assertTrue(union.canUseBuilding(owner, 2));
        Assertions.assertFalse(union.canUseBuilding(owner, 5), "15 级不得使用马厩(25)");
        owner.level = 40;
        Assertions.assertTrue(union.canUseBuilding(owner, 7), "40 级可用医院(28)");
        Assertions.assertFalse(union.canUseBuilding(null, 2), "无玩家记录直接拒绝");

        // 闸门确实接在「使用建筑」的入口上：1537 领厨房体力。
        owner.level = 14;
        owner.stamina = 0;
        owner.economy.kitchenStaminaLeft = 1;
        union.onKitchen(session, new GamePacket(MsgIds.C2S_KITCHEN, 1, new byte[0]));
        Assertions.assertEquals(0, owner.stamina, "等级不足不得领厨房体力");
        Assertions.assertEquals(1, owner.economy.kitchenStaminaLeft, "被拒时不消耗次数");
        // 1945 是**纯成功回执**：客户端 handler（ᝁ.cs:5536-5540）不反序列化 body，直接
        // UnionCookHouse.SeverReturnSccess() → 弹 100701「获得体力」并把本地
        // Attribute.mLastUnionChuFangGainTime 写成今天（按钮因此当天点不动）。
        // 所以被拒时绝不能回 1945，只能推 1913（f4 = lastKitchenStaminaAt）让客户端时间戳自纠。
        List<GamePacket> refused = drain(ch);
        Assertions.assertNull(pick(refused, MsgIds.S2C_KITCHEN), "被拒不得回 1945（否则客户端假成功弹 100701）");
        Assertions.assertNotNull(pick(refused, MsgIds.S2C_UNION_PLAYER_RES), "被拒要推 1913 让客户端自纠");
        owner.level = 40;
        union.onKitchen(session, new GamePacket(MsgIds.C2S_KITCHEN, 2, new byte[0]));
        Assertions.assertEquals(unionCfg.kitchenStamina(1), owner.stamina, "40 级可领厨房体力");
        Assertions.assertEquals(0, owner.economy.kitchenStaminaLeft, "成功才消耗次数");
        Assertions.assertNotNull(pick(drain(ch), MsgIds.S2C_KITCHEN), "成功必须回 1945");
    }

    /**
     * 本轮新落地：{@code tables\ShopCommom.txt} 公会商店行（物理第 9 行）第 16 列
     * 「重置每日免费刷新次数时间」= 5（时）。改前服务端把它挂在 **0 点**日历日块里
     * （{@code ProgressService.ensureDaily} 的 dailyKey 分支），0:00–5:00 这一段玩家会提前拿回当天次数。
     * 客户端 {@code out2\Client\MobileGameDemo\ShopPropertyCfg.cs:26} 解析了该列但全 APK 无消费方
     * （免费次数只认服端 1702 f4「剩余次数」），所以重置时刻纯服务端口径。
     *
     * <p>m22417 第 2 轮：该小时改为**读表**（{@code EconomyTables.shopFreeRefreshResetHour}，
     * 见 {@code parseShop} 读 0 基下标 15 = 物理第 16 列），不再在 {@code gachaGameDay} 里写死 5；
     * 金币抽卡的日界仍是自己的 5 点，两者不再互相绑死。
     */
    @Test
    public void shopFreeRefreshResetsAtFiveOClock() {
        resetWorld();
        PlayerRecord rec = fresh(OWNER, "会长");
        PlayerRecord.ShopState shop = new PlayerRecord.ShopState();
        shop.type = 4;
        shop.freeRefresh = 3;
        shop.payRefresh = 2;
        shop.freeRefreshDay = "2026-10-04";
        rec.shops.put(4, shop);

        // 重置小时读表（ShopCommom 第 16 列「重置每日免费刷新次数时间」，五行均为 5），不再写死
        Assertions.assertEquals(5, economy.shopFreeRefreshResetHour(4), "公会商店行的重置小时读表");
        Assertions.assertEquals(LocalDate.of(2026, 10, 4),
                ProgressService.gameDayOf(LocalDateTime.of(2026, 10, 5, 4, 59), 5), "5 点界：4:59 算前一天");
        Assertions.assertEquals(LocalDate.of(2026, 10, 5),
                ProgressService.gameDayOf(LocalDateTime.of(2026, 10, 5, 4, 59), 0), "0 点界：4:59 算当天");

        // 04:59 仍算前一个商店游戏日：键不变、次数不动
        Assertions.assertFalse(progress.resetShopFreeRefreshDaily(rec, LocalDateTime.of(2026, 10, 5, 4, 59)),
                "5 点前不重置");
        Assertions.assertEquals(3, shop.freeRefresh, "5 点前免费次数不动");
        Assertions.assertEquals(2, shop.payRefresh, "5 点前付费次数不动");
        Assertions.assertEquals("2026-10-04", shop.freeRefreshDay);

        // 05:00 起算新的商店游戏日
        Assertions.assertTrue(progress.resetShopFreeRefreshDaily(rec, LocalDateTime.of(2026, 10, 5, 5, 0)),
                "5 点整重置");
        Assertions.assertEquals(0, shop.freeRefresh, "免费刷新已用次数归零");
        Assertions.assertEquals(0, shop.payRefresh, "付费刷新已用次数归零");
        Assertions.assertEquals("2026-10-05", shop.freeRefreshDay);

        // 同一天内重复调用幂等（ensureDaily 每次进号/开商店都会跑）
        Assertions.assertFalse(progress.resetShopFreeRefreshDaily(rec, LocalDateTime.of(2026, 10, 5, 23, 59)),
                "同一天内幂等");
        Assertions.assertEquals(0, shop.freeRefresh);
    }

    /**
     * 本轮新落地：{@code tables\Union.txt} 的六键「XX解锁所需议事厅等级」→
     * {@code UnionCfg.unlockHallLevel}（出厂 厨房1/训练场3/商城1/马厩2/作战室1/医院4）
     * 改前**没有任何调用点** —— 议事厅 1 级的公会也能用训练场/马厩/医院。
     * APK 同一闸门：{@code out2\Client\MobileGameDemo\UnionProperty.cs:59-70} 解析
     * {@code mYiShiTingLevelRequire}/{@code mGongLueZuLevelRequire}，
     * 执行点 {@code out2\Client\᝝.cs:363-503}（议事厅等级不足 → MsgBox Str 100687）。
     */
    @Test
    public void buildingUnlockRequiresHallLevel() {
        resetWorld();
        Assertions.assertEquals(0, unionCfg.unlockHallLevel(1), "议事厅自身无解锁要求");
        Assertions.assertEquals(1, unionCfg.unlockHallLevel(2), "厨房");
        Assertions.assertEquals(3, unionCfg.unlockHallLevel(3), "训练场");
        Assertions.assertEquals(1, unionCfg.unlockHallLevel(4), "商城");
        Assertions.assertEquals(2, unionCfg.unlockHallLevel(5), "马厩");
        Assertions.assertEquals(1, unionCfg.unlockHallLevel(6), "作战室");
        Assertions.assertEquals(4, unionCfg.unlockHallLevel(7), "医院");

        PlayerRecord owner = fresh(OWNER, "解锁门槛");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "解锁会", "direct");
        drain(ch);
        owner.level = 40; // 攻略组等级（玩家等级）已达标，只差议事厅
        owner.gold = 10000000;
        owner.diamond = 1000000;
        store.save(owner);

        Assertions.assertTrue(union.canUseBuilding(owner, 2), "议事厅 1 级可用厨房(1)");
        Assertions.assertTrue(union.canUseBuilding(owner, 4), "议事厅 1 级可用商城(1)");
        Assertions.assertTrue(union.canUseBuilding(owner, 6), "议事厅 1 级可用作战室(1)");
        Assertions.assertFalse(union.canUseBuilding(owner, 3), "议事厅 1 级不得用训练场(3)");
        Assertions.assertFalse(union.canUseBuilding(owner, 5), "议事厅 1 级不得用马厩(2)");
        Assertions.assertFalse(union.canUseBuilding(owner, 7), "议事厅 1 级不得用医院(4)");

        // 闸门确实接在流程入口：议事厅 1 级时 1538 不建坑、不扣训练费
        // （1944 只回被拒武将的 wjIndex，客户端按 wjIndex 找不到坑 ⇒ 不会误显示「训练中」）。
        int goldBefore = owner.gold;
        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 1, Pb.write(o -> {
            Pb.int32Always(o, 1, 18);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, 0);
            Pb.int32Always(o, 4, 0);
        })));
        drain(ch);
        Assertions.assertTrue(owner.economy.trainSlots.isEmpty(), "议事厅 1 级不得开始训练");
        Assertions.assertEquals(goldBefore, owner.gold, "被拒时不扣训练费");

        u.buildings.put(Integer.valueOf(3), Integer.valueOf(1));
        withHall(u, 4);
        union.onTrainStart(session, new GamePacket(MsgIds.C2S_TRAIN_START, 2, Pb.write(o -> {
            Pb.int32Always(o, 1, 18);
            Pb.int32Always(o, 2, 0);
            Pb.int32Always(o, 3, 0);
            Pb.int32Always(o, 4, 0);
        })));
        Assertions.assertEquals(1, owner.economy.trainSlots.size(), "议事厅 4 级可开始训练");
    }

    /**
     * 1519 建筑收益：基线只按整小时推进（不足 1 小时的零头保留给下次），且
     * {@code tables\Union.txt:16}「单个建筑玩家**总**收益上限」= 3000000 按**累计**理解 ——
     * 3000000 ÷ 1500/小时（{@code UnionBuildingLevelUp.txt} 第 8 列全表最大）= 2000 小时 ≈ 83 天，
     * 作单次领取上限实际不可达（改前只按单次封顶，等于没有上限）。
     */
    @Test
    public void buildingProfitKeepsRemainderAndHonoursTotalCap() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "收益");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "收益会", "direct");
        drain(ch);
        int choulao = economy.buildingChoulao(2, 1);
        Assertions.assertTrue(choulao > 0, "厨房 L1 的酬劳必须表驱动");
        Integer key = Integer.valueOf(2);
        owner.economy.buildingProfitTotal.clear();

        // 1.5 小时：只结 1 小时，零头（0.5h）留给下次
        owner.economy.buildingProfitAt.put(key, Long.valueOf(System.currentTimeMillis() - 90 * 60000L));
        union.onDrawProfit(session, new GamePacket(MsgIds.C2S_DRAW_BUILDING_PROFIT, 1,
                Pb.write(o -> Pb.int32Always(o, 1, 2))));
        GamePacket first = pick(drain(ch), MsgIds.S2C_DRAW_BUILDING_PROFIT);
        Assertions.assertNotNull(first);
        Assertions.assertEquals(choulao, Pb.read(first.body).getInt(2, 0), "1.5 小时只结 1 小时");
        Assertions.assertEquals(choulao, owner.economy.buildingProfitTotal.get(key).intValue(),
                "累计已领收益必须记账");

        // 累计到上限后只补差额：5 小时本该 3000，但只剩 100 额度
        int cap = unionCfg.buildingProfitCap();
        owner.economy.buildingProfitTotal.put(key, Long.valueOf(cap - 100L));
        owner.economy.buildingProfitAt.put(key, Long.valueOf(System.currentTimeMillis() - 5 * 3600000L));
        union.onDrawProfit(session, new GamePacket(MsgIds.C2S_DRAW_BUILDING_PROFIT, 2,
                Pb.write(o -> Pb.int32Always(o, 1, 2))));
        GamePacket second = pick(drain(ch), MsgIds.S2C_DRAW_BUILDING_PROFIT);
        Assertions.assertNotNull(second);
        Assertions.assertEquals(100, Pb.read(second.body).getInt(2, 0), "累计到上限只补差额");
        Assertions.assertEquals(cap, owner.economy.buildingProfitTotal.get(key).intValue());

        // 领满后再领必须是 0
        owner.economy.buildingProfitAt.put(key, Long.valueOf(System.currentTimeMillis() - 3 * 3600000L));
        union.onDrawProfit(session, new GamePacket(MsgIds.C2S_DRAW_BUILDING_PROFIT, 3,
                Pb.write(o -> Pb.int32Always(o, 1, 2))));
        GamePacket third = pick(drain(ch), MsgIds.S2C_DRAW_BUILDING_PROFIT);
        Assertions.assertNotNull(third);
        Assertions.assertEquals(0, Pb.read(third.body).getInt(2, 0), "累计满上限后不再发");
    }

    /**
     * 1504 之后必须**主动兜底推一条 1915**（用户 m19522 #1）。
     *
     * <p>APK：客户端进公会详情后「佣兵」页签若是默认选中态，`UIToggle.onChange` 不一定触发，
     * 面板就不会自己发 1518（1518 的唯一发送点是进页签时）⇒ 收益/入驻武将区会一直空到玩家
     * 手点一下页签。客户端对主动推 1915 完全兼容（`ᝁ.cs:184` 注册了 1915 的 handler），
     * 故在 1504 的回包之后补一条。只在请求的是**自己公会**时推（佣兵面板属于自己公会）。
     */
    @Test
    public void unionDetailAlsoPushesEmployersPanel() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord other = fresh("test-d6-other", "别家会长");
        EmbeddedChannel ch = newChannel();
        EmbeddedChannel otherCh = newChannel();
        GameSession session = bind(owner, ch);
        GameSession otherSession = bind(other, otherCh);
        WorldStore.UnionRecord mine = createUnion(session, ch, "自己会", "direct");
        WorldStore.UnionRecord foreign = createUnion(otherSession, otherCh, "别家会", "direct");
        drain(ch);
        drain(otherCh);

        // 看自己公会详情：1906 + 主动推的 1915
        union.onDetail(session, new GamePacket(MsgIds.C2S_UNION_DETAIL, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, mine.id))));
        List<GamePacket> own = drain(ch);
        Assertions.assertNotNull(pick(own, MsgIds.S2C_UNION_DETAIL_RET), "1504 → 1906");
        GamePacket push = pick(own, MsgIds.S2C_UNION_EMPLOYERS);
        Assertions.assertNotNull(push, "1504 之后必须主动兜底推 1915（用户 m19522 #1）");
        Assertions.assertEquals(7, Pb.read(push.body).getBytesList(1).size(),
                "1915 f1 = 逐建筑 7 行（客户端只渲染 2/3/6/7，多推无害）");

        // 看别家公会详情：只回 1906，不推 1915（佣兵面板属于自己公会）
        union.onDetail(session, new GamePacket(MsgIds.C2S_UNION_DETAIL, 2,
                Pb.write(o -> Pb.stringAlways(o, 1, foreign.id))));
        List<GamePacket> others = drain(ch);
        Assertions.assertNotNull(pick(others, MsgIds.S2C_UNION_DETAIL_RET), "1504 → 1906");
        Assertions.assertNull(pick(others, MsgIds.S2C_UNION_EMPLOYERS),
                "看别家公会详情不得推 1915");
    }

    /** 1518 → 1915 里某个建筑第 index 个格位的 wjIndex（空格位为 0）。 */
    private int slotWj(GameSession session, EmbeddedChannel ch, int buildingType, int index) {
        union.onEmployers(session, new GamePacket(MsgIds.C2S_UNION_EMPLOYERS, 1, new byte[0]));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_UNION_EMPLOYERS);
        Assertions.assertNotNull(ret, "1518 → 1915");
        for (byte[] one : Pb.read(ret.body).getBytesList(1)) {
            Pb.Fields f = Pb.read(one);
            if (f.getInt(1, 0) == buildingType) {
                List<Integer> wjs = f.getInts(2);
                return index < wjs.size() ? wjs.get(index).intValue() : 0;
            }
        }
        return 0;
    }

    @Test
    public void memberInfoAndDonate() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);

        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "捐献会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(ownerCh);

        union.onMemberInfo(ownerSession, new GamePacket(MsgIds.C2S_UNION_MEMBER_INFO, 2, new byte[0]));
        List<byte[]> rows = Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_MEMBER_INFO_RET).body)
                .getBytesList(1);
        Assertions.assertEquals(2, rows.size(), "1525 → 1922（原实现完全没有 handler）");
        Assertions.assertEquals(owner.playerId, Pb.read(rows.get(0)).getInt(1, 0), "CUnionMemberInfo.Guid");

        int goldBefore = member.gold;
        int crystalBefore = u.crystal;
        union.onDonate(memberSession, new GamePacket(MsgIds.C2S_UNION_DONATE, 3, new byte[0]));
        List<GamePacket> memberOut = drain(memberCh);
        Assertions.assertTrue(hasIn(memberOut, MsgIds.S2C_UNION_DONATE_RET), "1966");
        Assertions.assertTrue(hasIn(memberOut, MsgIds.S2C_UNION_PLAYER_RES), "1913 资源刷新");
        Assertions.assertEquals(goldBefore - unionCfg.donateGold(), member.gold, "扣单次捐赠金币");
        Assertions.assertEquals(unionCfg.donateBrotherCoin(), member.guild.brotherCoin);
        Assertions.assertEquals(crystalBefore + unionCfg.donateCrystal(), u.crystal, "公会晶石 +50");
        Assertions.assertTrue(hasIn(drain(ownerCh), MsgIds.S2C_UNION_ATTRI_UPDATE),
                "1914 公会属性更新必须推给会员");
    }

    /**
     * 体力 → 公会成长值 → 个人贡献（Union.txt:11 每点体力 1 成长值 / Union.txt:17 30 成长值换 1 贡献）。
     *
     * <p>两个系数在客户端只有解析、零消费（{@code UnionProperty.cs:50/:56}），此前服务端也零调用点
     * ⇒ 消耗体力完全不产生公会成长值。同时验证 {@code WorldStore.Member.contribution} 的同步：
     * 1906 成员列表 f6 读的是成员表字段，此前生产代码零写入，客户端成员列表贡献恒 0。</p>
     */
    @Test
    public void staminaSpendGrowsUnionAndAccumulatesContribution() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ownerCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "成长会", "direct");
        drain(ownerCh);

        int growth0 = u.growth;
        int per = unionCfg.staminaGrow();
        owner.stamina = 100;
        Assertions.assertEquals(10, progress.spendStamina(owner, 10), "返回实际扣除的体力");
        Assertions.assertEquals(growth0 + 10 * per, u.growth, "每消耗 1 点体力给公会 1 成长值");
        Assertions.assertEquals(0, owner.guild.contribution, "10 成长值不足 30，暂不折算贡献");
        Assertions.assertEquals(10, owner.guild.contributionGrowRemainder, "不足一次的余数必须留存");
        Assertions.assertTrue(hasIn(drain(ownerCh), MsgIds.S2C_UNION_ATTRI_UPDATE), "1914 成长值推送");

        progress.spendStamina(owner, 20);
        Assertions.assertEquals(1, owner.guild.contribution, "10+20=30 成长值换 1 点贡献");
        Assertions.assertEquals(0, owner.guild.contributionGrowRemainder);
        Assertions.assertEquals(1, u.findMember(owner.playerId).contribution,
                "1906 成员列表 f6 读成员表贡献，必须与玩家身上的贡献同步");

        // 体力不够时按实际扣除记成长值（原实现 Math.max(0, stamina - cost) 会凭空生成长值）
        owner.stamina = 3;
        Assertions.assertEquals(3, progress.spendStamina(owner, 30));
        Assertions.assertEquals(0, owner.stamina);
        Assertions.assertEquals(growth0 + 33 * per, u.growth, "只按实际消耗的 3 点记成长值");
    }

    /** 1517 捐献后贡献变化同样要同步进成员表（晶石→贡献，Union.txt 贡献系数）。 */
    @Test
    public void donateSyncsMemberContribution() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "贡献会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(ownerCh);
        drain(memberCh);

        union.onDonate(memberSession, new GamePacket(MsgIds.C2S_UNION_DONATE, 2, new byte[0]));
        Assertions.assertTrue(member.guild.contribution > 0, "捐献必须给贡献");
        Assertions.assertEquals(member.guild.contribution, u.findMember(member.playerId).contribution,
                "1906 成员列表 f6 必须反映刚增加的贡献");
    }

    /**
     * 会长连续一周未登录 → 自动让位（StrTable 101282「由于你一周未登录游戏,会长职务由{0}接任」）。
     *
     * <p>让位当时广播 1911 {@code CCMsgNotifyUnionOwner}（客户端 UnionManagerSystem.cs 收 1911 后把该
     * Guid 的 Job 置 3 并刷新成员列表）+ 1908 给继任者 + 1906 刷成员列表；前会长的 1968 只能等他再登录
     * 时补推（PlayGameState.cs:9051 OnNET_CCMsgUnionPresidentChange_Ret）。
     */
    @Test
    public void inactiveOwnerHandsOverAndOldOwnerGetsNotifyOnLogin() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "让位会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(memberCh);
        drain(ownerCh);

        long now = System.currentTimeMillis();
        u.findMember(owner.playerId).lastOnlineAt = now - 8L * 24 * 60 * 60 * 1000;
        u.findMember(member.playerId).lastOnlineAt = now - 60L * 60 * 1000;
        world.saveUnions();

        union.handoverInactiveOwners();

        Assertions.assertEquals("member", u.findMember(owner.playerId).job, "久未登录的会长降为成员");
        Assertions.assertEquals("owner", u.findMember(member.playerId).job, "最近在线的成员继任");
        List<GamePacket> ownerGot = drain(ownerCh);
        GamePacket notify = pick(ownerGot, MsgIds.S2C_NOTIFY_UNION_OWNER);
        Assertions.assertNotNull(notify, "让位后必须广播 1911 新会长");
        Assertions.assertEquals(member.playerId, Pb.read(notify.body).getInt(1, 0));
        Assertions.assertNotNull(pick(ownerGot, MsgIds.S2C_UNION_JOB_UPDATE), "1908 通知继任者职位");
        Assertions.assertNotNull(pick(ownerGot, MsgIds.S2C_UNION_DETAIL_RET), "1906 刷新成员列表");
        Assertions.assertNull(pick(ownerGot, MsgIds.S2C_NOTIFY_OWNER_CHANGE), "1968 不在让位当时发");

        union.touchLogin(owner);
        GamePacket change = pick(drain(ownerCh), MsgIds.S2C_NOTIFY_OWNER_CHANGE);
        Assertions.assertNotNull(change, "前会长登录应收到 1968");
        Assertions.assertEquals("成员", Pb.read(change.body).getString(1));
        Assertions.assertEquals(0, u.pendingOwnerChangeFor, "1968 只补推一次");
    }

    /** 公会里只有会长一人时不让位、也不发 1968（没有可接任的人）。 */
    @Test
    public void singleMemberUnionKeepsOwner() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "独狼会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "单人公会", "direct");
        drain(ch);
        u.findMember(owner.playerId).lastOnlineAt = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
        world.saveUnions();

        union.handoverInactiveOwners();

        Assertions.assertEquals("owner", u.findMember(owner.playerId).job, "单人公会不让位");
        Assertions.assertEquals(0, u.pendingOwnerChangeFor);
        Assertions.assertFalse(hasIn(drain(ch), MsgIds.S2C_NOTIFY_OWNER_CHANGE), "单人公会不发 1968");
    }

    // ------------------------------------------------------------------ 公会战 PvP

    /**
     * 1549 报名 → 1946 纯空包（APK 无 {@code CCMsgRequestEnrollUnionPvP_Ret} 类，handler 不反序列化）；
     * 1550 → 1947 IsEnRolled。报名扣 UnionPvP.txt「报名消耗的公会晶石」。
     */
    @Test
    public void pvpEnrollChargesCrystalAndAnswersEmptyBody() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "战会", "direct");
        u.crystal = 2000;
        world.saveUnions();
        pinClock(4 * 3600); // 报名窗口外（[19:00,21:00) 关闭），否则用例在 19–21 点跑会随机失败

        union.onPvpEnroll(session, new GamePacket(MsgIds.C2S_UNION_PVP_ENROLL, 1, new byte[0]));
        GamePacket enroll = pick(drain(ch), MsgIds.S2C_UNION_PVP_ENROLL_RET);
        Assertions.assertNotNull(enroll, "1949 之外 1549 也必须回 1946");
        Assertions.assertEquals(0, enroll.body.length, "1946 必须是纯空包");
        Assertions.assertTrue(u.pvpEnrolled, "报名成功");
        Assertions.assertEquals(2000 - unionCfg.pvpEnrollCrystal(), u.crystal);

        union.onPvpIsEnroll(session, new GamePacket(MsgIds.C2S_UNION_PVP_IS_ENROLL, 2, new byte[0]));
        GamePacket isEnroll = pick(drain(ch), MsgIds.S2C_UNION_PVP_IS_ENROLL_RET);
        Assertions.assertNotNull(isEnroll);
        Assertions.assertTrue(Pb.read(isEnroll.body).getBool(1), "1947 IsEnRolled");
    }

    /**
     * 1549 报名窗口必须与 APK 客户端相位机**逐条对齐**（`docs\PROTOCOL_FIELD_AUDIT.md` 再核轮补充 6）：
     *
     * <p>客户端 `GongHuiZhanJoinUI.OnQueDing`（APK `Client\MobileGameDemo\GongHuiZhanJoinUI.cs:117/124/131`）
     * 只拒绝 InProgress（100774）与 JoinLimit（100773）；`UnionWarRoomEnter.OnClickUnionWarJoin`
     * （同目录 `:157/161`）也只用 100732/100726 拦这两相。相位定义（APK `Client\᝴.cs:34-91`）：
     * 战斗日 00:00–19:00 Preparing、19:00–20:00 InProgress、20:00–21:00 JoinLimit、21:00–24:00 Idle；
     * **非战斗日全天 Preparing**（`UnionPvPTimeInfoMgr.GetNearestUpperInfo` 只遍历启用行）。
     * ⇒ 战斗日关闭区间 [19:00,21:00)，非战斗日全天开放。
     */
    @Test
    public void pvpEnrollRespectsEnrollWindow() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "报名窗");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "窗会", "direct");
        u.crystal = 2000;
        world.saveUnions();
        drain(ch);

        // 日期用 isPvpBattleDay 现算，不依赖跑测试当天是周几（表是周一/三/六）。
        java.time.LocalDate battle = GameTime.today();
        while (!union.isPvpBattleDay(battle)) {
            battle = battle.plusDays(1);
        }
        java.time.LocalDate off = battle.plusDays(1);
        while (union.isPvpBattleDay(off)) {
            off = off.plusDays(1);
        }

        Assertions.assertTrue(union.inPvpEnrollWindow(0, battle), "战斗日 00:00 可报名");
        Assertions.assertTrue(union.inPvpEnrollWindow(18 * 3600 + 3599, battle), "战斗日 18:59:59 可报名");
        Assertions.assertFalse(union.inPvpEnrollWindow(19 * 3600, battle), "战斗日 19:00 开战即关闭");
        Assertions.assertFalse(union.inPvpEnrollWindow(20 * 3600 + 3599, battle),
                "战斗日 20:59:59 仍在 JoinLimit 关闭区间（结束后 3600s 才开放）");
        Assertions.assertTrue(union.inPvpEnrollWindow(21 * 3600, battle), "战斗日 21:00 进入 Idle，重新开放");
        Assertions.assertTrue(union.inPvpEnrollWindow(19 * 3600 + 1800, off),
                "非战斗日 19:30 客户端相位是 Preparing，必须可报名（改前会静默吞掉报名）");
        Assertions.assertTrue(union.inPvpEnrollWindow(23 * 3600, off), "非战斗日全天开放");
    }

    /**
     * 1549 端到端：报名窗口按**今天是不是战斗日**分档，客户端允许的集合服务端必须一致；
     * 并且报名要记下「冲着哪一场战斗日去的」（客户端 100727 已向玩家显示该日期）。
     */
    @Test
    public void pvpEnrollEndToEndHonoursTodayWindow() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "报名日");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "日会", "direct");
        u.crystal = 2000;
        world.saveUnions();
        drain(ch);

        boolean battleDay = union.isPvpBattleDay(GameTime.today());
        // 19:30：战斗日落在 InProgress 关闭区间；非战斗日相位是 Preparing、可报名。
        pinClock(19 * 3600 + 1800);
        union.onPvpEnroll(session, new GamePacket(MsgIds.C2S_UNION_PVP_ENROLL, 1, new byte[0]));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_UNION_PVP_ENROLL_RET);
        Assertions.assertNotNull(ret, "无论成败都必须回 1946 空包");
        Assertions.assertEquals(0, ret.body.length, "1946 是纯空包");
        Assertions.assertEquals(!battleDay, u.pvpEnrolled,
                battleDay ? "战斗日 19:30 在关闭区间内，不得报名" : "非战斗日 19:30 必须能报名");
        Assertions.assertEquals(battleDay ? 2000 : 2000 - unionCfg.pvpEnrollCrystal(), u.crystal,
                "报名成功才扣报名晶石");
        if (!battleDay) {
            Assertions.assertEquals(union.targetPvpBattleDay(19 * 3600 + 1800), u.pvpEnrollDay,
                    "报名必须记下目标战斗日（客户端 100727 显示的就是它）");
            Assertions.assertFalse(u.pvpEnrollDay.isEmpty(), "非战斗日报名冲着下一场战斗日");
        }
    }

    /**
     * 1543 端到端：时钟钉在发镖窗口内（18:30）且目的地门槛达标才真的生成镖车；
     * 窗口外（10:00）门槛达标也不发车、不扣发镖次数。`inSendWindow()` 原来只能读真实时钟，端到端测不到。
     */
    @Test
    public void sendCartEndToEndHonoursWindow() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "发镖窗");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "镖会", "direct");
        // 马厩要求议事厅 2 级（Union.txt）。
        withHall(world.findUnionByName("镖会"), 2);
        drain(ch);

        PlayerRecord.Hero main = owner.findHeroByIndex(owner.mainHeroIndex);
        main.fightPower = 50000; // 目的地 1（迷茫沼泽）需 40000
        store.save(owner);
        byte[] body = Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.bytesAlways(o, 2, Pb.write(f -> {
                Pb.int32Always(f, 1, PlayerRecord.FORMATION_MAJIU);
                Pb.stringAlways(f, 2, main.id);
            }));
        });

        pinClock(10 * 3600); // 发镖窗口外
        union.onSendCart(session, new GamePacket(MsgIds.C2S_SEND_CART, 1, body));
        List<GamePacket> rejected = drain(ch);
        // 被拒时**不能**回 1938：客户端 MaJiuBasePaiQianUI.cs:117-129 无条件取 biaoCheInfo[0]，
        // 空镖车包会 IndexOutOfRange；只回 1936 状态包即可（面板靠自己的日期字段兜底）。
        Assertions.assertNull(pick(rejected, MsgIds.S2C_SEND_CART), "窗口外不得回 1938（空镖车包会越界）");
        Assertions.assertNotNull(pick(rejected, MsgIds.S2C_MAJIU_INFO), "窗口外仍必须回 1936 状态");
        Assertions.assertTrue(owner.economy.escortCarts.isEmpty(), "窗口外不得发车");

        pinClock(18 * 3600 + 1800); // 发镖窗口内（UnionMaJiuTime.txt 18:00 起 7200s）
        union.onSendCart(session, new GamePacket(MsgIds.C2S_SEND_CART, 2, body));
        Assertions.assertNotNull(pick(drain(ch), MsgIds.S2C_SEND_CART), "1543 成功必须回 1938");
        Assertions.assertEquals(1, owner.economy.escortCarts.size(), "窗口内 + 门槛达标必须发车");
        Assertions.assertEquals(1, owner.economy.escortCarts.get(0).targetId, "发车目的地");
    }

    /**
     * 1543 在**劫镖段（phase 2）必须被拒**（用户 m21142 复核，推翻第 2 轮 m20482 的放宽）。
     *
     * <p>客户端真正的发镖闸门不在 {@code MaJiuEntryUI.OnPaiQian}（它只挡 phase null/3），
     * 而在**点目的地告示牌**那一步：APK {@code Client\᝕.cs:78-98 OnClicked()}（{@code \u1755} =
     * 目的地节点）—— 派遣模式下 {@code if (curPhase == 1)} 才开 {@code EN_OPEN_MAJIUPAIQIAN_UI}
     * （派遣面板），否则只弹 {@code StrTable.getStr(100741)}「当前已过发镖时间！」。phase 2 时
     * 左栏派遣按钮文案已变 100801「查看镖车」、派遣基地额度标签显示 100795「已过派遣期」
     * （`MaJiuBasePaiQianUI.cs:79-91`）⇒ 客户端在 phase 2 进不了派遣链，1543 不该被接受。
     * 拒绝时保持「不回 1938、不建车、只回 1936」，与窗口外一致。
     */
    @Test
    public void sendCartIsRejectedAfterDispatchPhase() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "劫镖段发镖");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "后段镖会", "direct");
        withHall(world.findUnionByName("后段镖会"), 2);
        drain(ch);

        PlayerRecord.Hero main = owner.findHeroByIndex(owner.mainHeroIndex);
        main.fightPower = 50000;
        store.save(owner);
        byte[] body = Pb.write(o -> {
            Pb.int32Always(o, 1, 1);
            Pb.bytesAlways(o, 2, Pb.write(f -> {
                Pb.int32Always(f, 1, PlayerRecord.FORMATION_MAJIU);
                Pb.stringAlways(f, 2, main.id);
            }));
        });

        // 18:00 + 发镖 7200s = 20:00 起进入劫镖段；20:10 属于 phase 2。
        pinClock(20 * 3600 + 600);
        Assertions.assertEquals(2, union.majiuPhase(), "20:10 必须是劫镖段");
        union.onSendCart(session, new GamePacket(MsgIds.C2S_SEND_CART, 1, body));
        List<GamePacket> out = drain(ch);
        Assertions.assertNull(pick(out, MsgIds.S2C_SEND_CART),
                "phase 2 点目的地只会弹 100741「当前已过发镖时间！」，不得回 1938");
        Assertions.assertNotNull(pick(out, MsgIds.S2C_MAJIU_INFO), "被拒仍必须回 1936 状态");
        Assertions.assertTrue(owner.economy.escortCarts.isEmpty(), "劫镖段不得建车");
    }

    /**
     * 1533 重置公会 Boss 必须「该章 Boss 已死」（用户 m21142 复核；子代理判定 REACHABLE=yes）。
     *
     * <p>客户端只有该章 Boss 已死才把 Reset 按钮显出来（`WarRoomFinish.Refresh:261-267` 要求
     * {@code mEUnionJob == 3 && info.bossInfo.curHP <= 0}），但发送点 `OnClickReset:78` 只查公会晶石、
     * 目标章节取「当前点击章节」（`UnionBossWarInfo.cs:144-147`），且已打开的面板不再 Refresh
     * （`ᝁ.cs:5402-5412`）⇒ 陈旧面板 / 点错章节 / 重放都能对**活着的** Boss 发 1533。
     * 改前服务端只查会长 + 晶石，活 Boss 也会被重置（回满血 + 清伤害榜）。
     */
    @Test
    public void restBossRequiresDeadBoss() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "重置Boss");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "重置会", "direct");
        drain(ch);

        int chapter = 1;
        UnionCfg.BossRow row = unionCfg.boss(chapter);
        Assertions.assertNotNull(row, "UnionBoss.txt 必须配了第 1 章");
        Assertions.assertTrue(row.refreshCrystal > 0, "刷新消耗必须表驱动");
        u.crystal = 100000;
        u.setBossHp(chapter, 777777); // 活着的 Boss
        world.saveUnions();

        union.onRestBoss(session, new GamePacket(MsgIds.C2S_REST_BOSS, 1,
                Pb.write(o -> Pb.int32Always(o, 1, chapter))));
        List<GamePacket> out = drain(ch);
        Assertions.assertTrue(hasIn(out, MsgIds.S2C_REST_BOSS), "被拒也要回 1930 让面板保持同步");
        Assertions.assertEquals(100000, u.crystal, "Boss 没死不得扣公会晶石");
        Assertions.assertEquals(777777, u.bossHpOf(chapter, 777777), "Boss 没死不得重置血量");

        u.setBossHp(chapter, 0); // 打死了
        world.saveUnions();
        union.onRestBoss(session, new GamePacket(MsgIds.C2S_REST_BOSS, 2,
                Pb.write(o -> Pb.int32Always(o, 1, chapter))));
        drain(ch);
        Assertions.assertEquals(100000 - row.refreshCrystal, u.crystal, "打完才能花晶石重置");
        Assertions.assertTrue(u.bossHpOf(chapter, 1) > 0, "重置必须回满血");
    }

    /**
     * 1533 重置必须广播 1923（子代理 ce83f80c「该推不推」审计第 3 条）。
     *
     * <p>重置改的是**公会级**状态（该章 Boss 血量全公会共享）。改前 `onRestBoss` 只把 1923/1930
     * 用 `session.send` 回给操作者本人，全局 `pushBossInfo` 调用点只有 onBossResult 的两处；
     * 而客户端的血量条只由 1923 驱动（`UnionBossWarInfo.cs:35-67` 先 `mBossInfo.Clear()` 再整表重建）
     * ⇒ 其他成员的作战室一直显示「已击杀 / 0 血」，直到自己重开面板（1526）才自纠。
     */
    @Test
    public void bossResetBroadcastsBossInfoToOtherMembers() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "重置会长");
        PlayerRecord member = fresh(MEMBER, "重置成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "重置广播会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(ownerCh);
        drain(memberCh);

        int chapter = 1;
        UnionCfg.BossRow row = unionCfg.boss(chapter);
        Assertions.assertNotNull(row);
        u.crystal = 100000;
        u.setBossHp(chapter, 0); // 已击杀才允许重置
        world.saveUnions();

        union.onRestBoss(ownerSession, new GamePacket(MsgIds.C2S_REST_BOSS, 2,
                Pb.write(o -> Pb.int32Always(o, 1, chapter))));
        drain(ownerCh);
        Assertions.assertEquals(100000 - row.refreshCrystal, u.crystal);
        Assertions.assertTrue(u.bossHpOf(chapter, 1) > 0, "重置必须回满血");

        GamePacket push = pick(drain(memberCh), MsgIds.S2C_UNION_BOSS_INFO);
        Assertions.assertNotNull(push, "重置必须广播 1923，否则其他成员血量条停在「已击杀」");
        boolean full = false;
        for (byte[] one : Pb.read(push.body).getBytesList(3)) {
            Pb.Fields bf = Pb.read(one);
            if (bf.getInt(1, 0) == chapter) {
                full = bf.getInt(3, 0) > 0;
            }
        }
        Assertions.assertTrue(full, "广播的该章 curHP 必须已回满");
    }

    /**
     * 1504 补推入会申请红点 1912（子代理 ce83f80c「该推不推」审计第 1 条）。
     *
     * <p>申请若在会长/长老离线时到达，当次 push（`UnionService.java:257/258`）被丢弃就再没人补；
     * 客户端 `mUnionJoinRequest` 是内存字段（`MainPlayerAttribute.cs:349`），重登即 false，
     * 唯一置 true 处是 1912 handler（`ᝁ.cs:6542`）与 1907 回包（`UnionManagerSystem.cs:284`），
     * 而 1505 只由「入会申请」入口按钮发（`UM:711`）⇒ 不补推就要盲点该页才看得到积压申请。
     * 补推点选 1504（开管理面板 `UM:80` / 进公会基地场景 `᝝:92` 的必发包）；申请为空时不得推。
     */
    @Test
    public void openingUnionDetailRepushesJoinRequestTip() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "红点会长");
        PlayerRecord member = fresh(MEMBER, "红点成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "红点会", "verify");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(ownerCh); // 丢弃当次在线推送，模拟「申请在离线时到达」
        drain(memberCh);
        Assertions.assertEquals(1, u.requesters.size());

        union.onDetail(ownerSession, new GamePacket(MsgIds.C2S_UNION_DETAIL, 2,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        Assertions.assertTrue(hasIn(drain(ownerCh), MsgIds.S2C_UNION_JOIN_REQUEST_TIPS),
                "会长发 1504 必须补推 1912 红点");

        union.onDetail(memberSession, new GamePacket(MsgIds.C2S_UNION_DETAIL, 3,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        Assertions.assertFalse(hasIn(drain(memberCh), MsgIds.S2C_UNION_JOIN_REQUEST_TIPS),
                "普通成员看不到申请列表，不得收到 1912");

        u.requesters.clear();
        world.saveUnions();
        union.onDetail(ownerSession, new GamePacket(MsgIds.C2S_UNION_DETAIL, 4,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        Assertions.assertFalse(hasIn(drain(ownerCh), MsgIds.S2C_UNION_JOIN_REQUEST_TIPS),
                "无申请时不得推 1912（否则是假红点）");
    }

    /**
     * 任何建筑到点结算都要广播 1919（子代理 ce83f80c「该推不推」审计的弱项）。
     *
     * <p>改前只在议事厅（type=1）到点时推：客户端 `UM:346-371` 逐建筑 `SetDengJi`、
     * `᝺:54-91` 刷据点牌子 Lv ⇒ 非议事厅建筑到点后，停在公会基地场景内的其他成员看到的牌子
     * 等级一直是旧值，必须出/进场或自己开建筑面板（`BI:278` 倒计时归零自动发 1522）才自纠。
     */
    @Test
    public void buildingUpgradeCompletionBroadcastsToOtherMembers() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "升级会长");
        PlayerRecord member = fresh(MEMBER, "升级成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "升级广播会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(ownerCh);
        drain(memberCh);

        int type = 3; // 训练场：非议事厅
        u.buildings.put(Integer.valueOf(type), Integer.valueOf(1));
        u.buildingUpgradeEnd.put(Integer.valueOf(type),
                Long.valueOf(System.currentTimeMillis() - 1000L));
        world.saveUnions();

        union.onBuildings(ownerSession, new GamePacket(MsgIds.C2S_UNION_BUILDINGS, 2, new byte[0]));
        drain(ownerCh);
        Assertions.assertEquals(2, u.buildings.get(Integer.valueOf(type)).intValue(),
                "训练场到点升到 2 级");

        GamePacket b = pick(drain(memberCh), MsgIds.S2C_UNION_BUILDINGS);
        Assertions.assertNotNull(b, "非议事厅建筑到点也必须广播 1919");
        boolean lv2 = false;
        for (byte[] one : Pb.read(b.body).getBytesList(1)) {
            Pb.Fields bf = Pb.read(one);
            if (bf.getInt(1, 0) == type && bf.getInt(2, 0) == 2) {
                lv2 = true;
            }
        }
        Assertions.assertTrue(lv2, "1919 里训练场等级必须已是 2");
    }

    /**
     * 1549 公会战报名限长老/会长（用户 m21142 复核）。
     *
     * <p>客户端两道硬闸门都要求 {@code mEUnionJob >= 2}：房间入口 `UnionWarRoomEnter.cs:146`、
     * 确认钮 `GongHuiZhanJoinUI.cs:103`（不足弹 100765「公会会长和公会长老才有此操作权限」）。
     * 原版客户端里普通成员发不出 1549；改包可发，而 1946 是纯空包、客户端察觉不到
     * （`ᝁ.cs` 的 handler 不反序列化）⇒ 服务端必须补同一道授权门，否则 member 能花公会晶石
     * 报名并钉住对手。
     */
    @Test
    public void pvpEnrollRequiresElderOrOwner() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "报名会长");
        PlayerRecord member = fresh(MEMBER, "报名成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "报名会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(ownerCh);
        drain(memberCh);
        u.crystal = 10000;
        world.saveUnions();
        Assertions.assertEquals(1, PlayerRecord.jobCode(member.guild.job), "新成员 = member");

        pinClock(4 * 3600); // 报名窗口内（见既有用例注释：非战斗日全天 / 战斗日 00:00–19:00）
        union.onPvpEnroll(memberSession, new GamePacket(MsgIds.C2S_UNION_PVP_ENROLL, 1, new byte[0]));
        Assertions.assertFalse(u.pvpEnrolled, "普通成员不得报名（客户端 mEUnionJob < 2 直接挡）");
        Assertions.assertEquals(10000, u.crystal, "被拒不得扣公会晶石");

        union.onAppointElder(ownerSession, new GamePacket(MsgIds.C2S_APPOINT_ELDER, 2, Pb.write(o -> {
            Pb.int32Always(o, 1, member.playerId);
            Pb.boolAlways(o, 2, true);
        })));
        drain(ownerCh);
        drain(memberCh);
        Assertions.assertEquals(2, PlayerRecord.jobCode(member.guild.job), "升为长老");

        union.onPvpEnroll(memberSession, new GamePacket(MsgIds.C2S_UNION_PVP_ENROLL, 2, new byte[0]));
        drain(memberCh);
        Assertions.assertTrue(u.pvpEnrolled, "长老可以报名");
        Assertions.assertEquals(10000 - unionCfg.pvpEnrollCrystal(), u.crystal, "报名扣公会晶石");
    }

    /**
     * 1519 领建筑收益不能被「建筑使用闸门」拦住（第 2 轮闸门清扫，用户 m20482）。
     *
     * <p>客户端 {@code MyBuildingWithYongBingItem_InYongBingMianBan.cs:83-89} 的「领取」按钮
     * 零前置（profit≠0 才显示、点后立刻 {@code SetProfit(0,0)} 本地清零），而 1915
     * {@code employersBody} 会把**未解锁建筑**的 pendingBuildingGold 一起下发 ⇒ 攻略组等级
     * 不足的成员点领取若被服务端静默拒成 +0，界面已本地清零、收益再也拿不到。收益按建筑等级
     * 累计（{@code pendingBuildingGold} 不看入驻），与该玩家能否使用该建筑无关。
     */
    @Test
    public void buildingProfitIsPaidRegardlessOfBuildingUseLevel() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "低等级领收益");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "低等级会", "direct");
        drain(ch);
        owner.level = 14; // 厨房（type 2）使用等级 = 15（Union.txt「厨房使用等级」）
        store.save(owner);
        Assertions.assertFalse(union.canUseBuilding(owner, 2),
                "14 级玩家不该能用厨房（前置条件成立才说明闸门真会拦）");

        int choulao = economy.buildingChoulao(2, 1);
        Assertions.assertTrue(choulao > 0, "厨房 L1 酬劳必须表驱动");
        Integer key = Integer.valueOf(2);
        owner.economy.buildingProfitTotal.clear();
        owner.economy.buildingProfitAt.put(key, Long.valueOf(System.currentTimeMillis() - 2 * 3600000L));
        owner.gold = 1000;
        store.save(owner);

        union.onDrawProfit(session, new GamePacket(MsgIds.C2S_DRAW_BUILDING_PROFIT, 1,
                Pb.write(o -> Pb.int32Always(o, 1, 2))));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_DRAW_BUILDING_PROFIT);
        Assertions.assertNotNull(ret);
        Assertions.assertEquals(2 * choulao, Pb.read(ret.body).getInt(2, 0),
                "使用等级不足也必须照发（客户端已本地清零，拒付等于收益永久丢失）");
        Assertions.assertEquals(1000 + 2 * choulao, owner.gold);
    }

    /**
     * 1943 f1–f4 是**劫镖（掠夺）红利**，不是发镖收益（第 2 轮闸门清扫，用户 m20482）。
     *
     * <p>{@code CCMsgRequestGetYunBiaoAwardRet} = f1 raidRank / f2 raidJinBi / f3 raidXDB /
     * f4 raidJinShi；客户端 {@code MaJiuGetAwardUI.cs:113-133} 第 1 行显示的就是 f3/f2，
     * 名次文案 Str 100805「…排名{0}，获得丰厚的**掠夺红利**！」，且 `:74-83` 只在
     * {@code raidRank != 0} 时才把该行留下。发镖收益走 f5 {@code yunBiaoAward}（一车一行）。
     * 改前成功分支把发镖金币/发镖兄弟币写进 f2/f3，客户端会把发镖收益当掠夺红利显示。
     */
    @Test
    public void yunbiaoAwardReportsRaidDividendNotSendGold() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "红利");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "红利会", "direct");
        drain(ch);

        PlayerRecord.Economy.EscortCart cart = new PlayerRecord.Economy.EscortCart();
        cart.cartId = "cart-award";
        cart.targetId = 3;
        cart.sentAt = System.currentTimeMillis();
        owner.economy.escortCarts.add(cart);
        owner.economy.majiuDay = PlayerDumpService.now().substring(0, 10);
        owner.economy.raidJinbiTotal = 12345;
        owner.economy.raidXdbTotal = 678;
        owner.economy.raidJinShiTotal = 9;
        owner.economy.majiuAwarded = false;
        owner.gold = 1000;
        store.save(owner);

        int lineGold = unionCfg.majiuTarget(3).gold;
        pinClock(22 * 3600 + 1800); // 活动结束（phase 3）
        union.onYunBiaoAward(session, new GamePacket(MsgIds.C2S_YUNBIAO_AWARD, 1, new byte[0]));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_YUNBIAO_AWARD);
        Assertions.assertNotNull(ret);
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertEquals(1, f.getInt(1, -1), "当天劫过镖必须给名次（0 会让客户端整行销毁）");
        Assertions.assertEquals(12345, f.getInt(2, -1), "f2 raidJinBi 必须是劫镖红利，不是发镖金币");
        Assertions.assertEquals(678, f.getInt(3, -1), "f3 raidXDB 必须是劫镖兄弟币");
        Assertions.assertEquals(9, f.getInt(4, -1), "f4 raidJinShi 是劫镖晶石");
        Assertions.assertEquals(1000 + lineGold, owner.gold, "发镖收益仍按 f5 付款");
        List<byte[]> rows = f.getBytesList(5);
        Assertions.assertEquals(1, rows.size(), "f5 yunBiaoAward 一车一行");
        Assertions.assertEquals(lineGold, Pb.read(rows.get(0)).getInt(3, -1), "f5 f3 jinBi = 线路金币");
    }

    /** 1943 f1：当天没劫过镖的玩家必须给 0（客户端只在 raidRank != 0 时留「掠夺红利」行）。 */
    @Test
    public void yunbiaoAwardOmitsRaidRowForPlayersWhoNeverRaided() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "没劫过镖");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "无镖会", "direct");
        drain(ch);
        owner.economy.majiuDay = PlayerDumpService.now().substring(0, 10);
        store.save(owner);

        pinClock(22 * 3600 + 1800);
        union.onYunBiaoAward(session, new GamePacket(MsgIds.C2S_YUNBIAO_AWARD, 1, new byte[0]));
        Pb.Fields f = Pb.read(pick(drain(ch), MsgIds.S2C_YUNBIAO_AWARD).body);
        Assertions.assertEquals(0, f.getInt(1, -1), "没劫过镖 raidRank 必须为 0");
        Assertions.assertEquals(0, f.getInt(2, -1));
        Assertions.assertEquals(0, f.getInt(3, -1));
    }

    /** 1552 上阵 → 1551 读回 1948；1553 → 1950；1560/1562/1563 → 1957/1959/1960。 */
    @Test
    public void pvpDefFormationRoundTrip() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "布阵会", "direct");

        // 真服流程：先在「我的队伍」面板保存防守预设 1（C2S 202 → formationsByType[24]），
        // 之后「添加队伍」面板（1553 → 1950）才有这条候选可选（用户 m20090 #1）。
        List<String> preset1 = new ArrayList<>();
        preset1.add(owner.findHeroByIndex(owner.mainHeroIndex).id);
        owner.setFormationSlots(24, preset1);
        store.save(owner);

        union.onPvpUpdateDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_UPDATE_DEF_FORMATION, 1,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 2);
                    Pb.int32Always(o, 2, 1);
                    Pb.int32Always(o, 3, owner.playerId);
                    Pb.int32Always(o, 4, 24);
                })));
        GamePacket up = pick(drain(ch), MsgIds.S2C_UNION_PVP_UPDATE_DEF_FORMATION_RET);
        Assertions.assertNotNull(up);
        Assertions.assertEquals(0, up.body.length, "1949 必须是纯空包");
        Assertions.assertEquals(1, u.pvpFormationsOf(2).size(), "据点 2 上阵 1 个阵容");

        union.onPvpDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_DEF_FORMATION, 2,
                Pb.write(o -> Pb.int32Always(o, 1, 2))));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_UNION_PVP_DEF_FORMATION_RET);
        Assertions.assertNotNull(ret);
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertEquals(2, f.getInt(1, 0), "1948 f1 DefPointIndex");
        List<byte[]> formations = f.getBytesList(2);
        Assertions.assertEquals(1, formations.size());
        Pb.Fields one = Pb.read(formations.get(0));
        Assertions.assertEquals(1, one.getInt(1, 0), "CMsgDefPointDefFormation.1 FormationIndex");
        Assertions.assertTrue(one.getInt(2, 0) > 0, "阵容总战力");
        Assertions.assertEquals("会长", one.getString(3), "CMsgDefPointDefFormation.3 FormationFromPlayerName");
        List<byte[]> wjs = one.getBytesList(4);
        Assertions.assertFalse(wjs.isEmpty(), "4 号位是 CMsgWuJiangJobAndBriefInfo 列表");
        Pb.Fields wj = Pb.read(wjs.get(0));
        Assertions.assertEquals(1, wj.getInt(1, 0), "1 号位 job");
        Assertions.assertEquals(owner.mainHeroIndex, Pb.read(wj.getBytes(2)).getInt(1, 0), "wjBriefInfo.index");

        union.onPvpAllDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_ALL_DEF_FORMATION, 3, new byte[0]));
        GamePacket all = pick(drain(ch), MsgIds.S2C_UNION_PVP_ALL_DEF_FORMATION_RET);
        Assertions.assertNotNull(all);
        Pb.Fields allF = Pb.read(all.body);
        List<byte[]> allRows = allF.getBytesList(1);
        Assertions.assertEquals(1, allRows.size());
        Assertions.assertEquals(2, Pb.read(allRows.get(0)).getInt(7, 0), "CMsgOneUnionPvPDefFormation.7 DefPointsIndex");

        union.onPvpMyDefPointBrief(session, new GamePacket(MsgIds.C2S_UNION_PVP_MY_DEF_POINT_BRIEF, 4, new byte[0]));
        GamePacket brief = pick(drain(ch), MsgIds.S2C_UNION_PVP_MY_DEF_POINT_BRIEF_RET);
        Assertions.assertNotNull(brief);
        List<byte[]> points = Pb.read(brief.body).getBytesList(1);
        Assertions.assertEquals(unionCfg.pvpPointIds().size(), points.size(), "1957 每个据点一行");
        Pb.Fields p2 = Pb.read(points.get(2));
        Assertions.assertEquals(2, p2.getInt(1, 0));
        Assertions.assertTrue(p2.getInt(2, 0) > 0, "据点 2 有我方阵容战力");
        Assertions.assertTrue(p2.getBool(3), "IsRequesterDefThisPoint");

        union.onPvpLeftDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_LEFT_DEF_FORMATION, 5, new byte[0]));
        GamePacket left = pick(drain(ch), MsgIds.S2C_UNION_PVP_LEFT_DEF_FORMATION_RET);
        Assertions.assertNotNull(left);
        List<byte[]> leftRows = Pb.read(left.body).getBytesList(3);
        Assertions.assertEquals(unionCfg.pvpPointIds().size(), leftRows.size());

        union.onPvpDefPointDetail(session, new GamePacket(MsgIds.C2S_UNION_PVP_DEF_POINT_DETAIL, 6,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 2);
                    Pb.bool(o, 2, true);
                })));
        GamePacket detail = pick(drain(ch), MsgIds.S2C_UNION_PVP_DEF_POINT_DETAIL_RET);
        Assertions.assertNotNull(detail);
        Pb.Fields df = Pb.read(detail.body);
        Assertions.assertEquals(2, df.getInt(1, 0));
        Assertions.assertTrue(df.getBool(2));
        Assertions.assertEquals(1, df.getBytesList(3).size(), "1960 f3 FormationBriefInfo");
    }

    /**
     * 1552 的首格索引是 **0**（客户端 {@code GongHuiZhanJuDianBuFangUI.cs:196/257-276} 从 {0,1,2} 取空位），
     * 且每个据点最多 **2** 支队伍（同文件 :373 {@code MAX_FORMATION_COUNT = 2;}，超限弹
     * {@code Code.txt:761} 100780「每个据点最多布防{0}支队伍」）。1959 的 {@code leftFormationCnt}
     * 是「该据点当前防守队伍数」：客户端 {@code GongHuiZhanJuDianBoard.cs:242-250} 在 0 时显示无队伍。
     */
    @Test
    public void pvpDefFormationAllowsIndexZeroAndCapsTwoTeams() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "布阵会", "direct");

        // 改前 `formationIndex > 0` 会把玩家加第一支队伍的操作静默丢掉（却仍回 1949 成功）。
        defFormation(session, ch, 3, 0, owner.playerId);
        Assertions.assertEquals(1, u.pvpFormationsOf(3).size(), "formationIndex=0 必须上阵成功");
        Assertions.assertNotNull(u.pvpFindFormation(3, 0));

        // 第二支队伍用**另一套预设**（type 25）：同一套预设换格位是「移动」（见
        // deployingSamePresetOnAnotherPointMovesIt），只有不同预设才算两支不同的队伍。
        defFormation(session, ch, 3, 1, owner.playerId, 25);
        Assertions.assertEquals(2, u.pvpFormationsOf(3).size(), "第二支队伍允许");

        // 第三支必须被拒：客户端只本地拦 MAX=2，服务端不拦就能塞进任意多支。
        defFormation(session, ch, 3, 2, owner.playerId, 26);
        Assertions.assertEquals(2, u.pvpFormationsOf(3).size(), "每据点上限 2 支");
        Assertions.assertNull(u.pvpFindFormation(3, 2), "被拒的索引不得落盘");

        // 下阵（playerGuid=0）仍按 formationIndex 定位，且索引 0 也要能下。
        defFormation(session, ch, 3, 0, 0);
        Assertions.assertEquals(1, u.pvpFormationsOf(3).size(), "索引 0 的阵容必须能下阵");

        // 1959：leftFormationCnt = 当前防守队伍数（改前写 5-size，空据点会显示「5」）。
        union.onPvpLeftDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_LEFT_DEF_FORMATION, 9, new byte[0]));
        GamePacket left = pick(drain(ch), MsgIds.S2C_UNION_PVP_LEFT_DEF_FORMATION_RET);
        Assertions.assertNotNull(left);
        List<byte[]> rows = Pb.read(left.body).getBytesList(3);
        Assertions.assertEquals(unionCfg.pvpPointIds().size(), rows.size());
        for (byte[] row : rows) {
            Pb.Fields rf = Pb.read(row);
            int point = rf.getInt(1, 0);
            Assertions.assertEquals(point == 3 ? 1 : 0, rf.getInt(2, 0),
                    "据点 " + point + " 的 leftFormationCnt = 当前防守队伍数");
        }
    }

    /**
     * 1553 → 1950 必须下发**候选**队伍（未上阵的 {@code DefPointsIndex = -1}），而不是「只发已上阵的队伍」。
     *
     * <p>客户端「添加队伍」面板（{@code GongHuiZhanJuDianAddTeamUI.cs}）是**唯一**的上阵入口
     * （{@code GongHuiZhanJuDianBuFangUI.cs:163-182 OnAddClick} → 本面板 → 1552），它专门为
     * {@code DefPointsIndex == -1} 实现了「驻守域写 '-'」（{@code :127-132}）与「-1 排最前」
     * （{@code :159-185}）两个分支 ⇒ 真服下发的是全部候选。改前只发已上阵队伍，战斗日刚开始时
     * 列表为空、会长无法给任何据点布防（1552 永远发不出去）。
     *
     * <p>候选 = 成员**真正保存过**的预设（用户 m20090 #1：没布防的成员就是没上场，不会被打）：
     * 一套预设都没存过时列表就是空的（改前会为未配置成员造一条「预设 1」兜底，等于让没布防的人
     * 也能被会长拉上场）。
     */
    @Test
    public void pvpAllDefFormationListsUnplacedCandidatesWithMinusOnePoint() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "候选会", "direct");

        // 还没有任何成员保存过防守预设：没有候选（用户 m20090 #1）。
        union.onPvpAllDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_ALL_DEF_FORMATION, 1, new byte[0]));
        GamePacket all = pick(drain(ch), MsgIds.S2C_UNION_PVP_ALL_DEF_FORMATION_RET);
        Assertions.assertNotNull(all);
        Assertions.assertTrue(Pb.read(all.body).getBytesList(1).isEmpty(),
                "没保存过预设的成员不算候选（没布防就是没上场）");

        // 保存防守预设 1（客户端 C2S 202 → formationsByType[24]）后出现一行候选。
        List<String> preset1 = new ArrayList<>();
        preset1.add(owner.findHeroByIndex(owner.mainHeroIndex).id);
        owner.setFormationSlots(24, preset1);
        store.save(owner);

        union.onPvpAllDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_ALL_DEF_FORMATION, 2, new byte[0]));
        List<byte[]> rows = Pb.read(pick(drain(ch), MsgIds.S2C_UNION_PVP_ALL_DEF_FORMATION_RET).body)
                .getBytesList(1);
        Assertions.assertEquals(1, rows.size(), "保存过的预设给一条候选");
        Pb.Fields row = Pb.read(rows.get(0));
        Assertions.assertEquals(-1, row.getInt(7, 0), "未上阵的 DefPointsIndex 必须是 -1（客户端驻守域显示 '-'）");
        Assertions.assertEquals(24, row.getInt(2, 0), "候选 = 防守预设 1（type 24）");
        Assertions.assertEquals(owner.playerId, row.getInt(1, 0), "f1 PlayerGuid");
        Assertions.assertEquals("会长", row.getString(3), "f3 PlayerName");
        Assertions.assertFalse(row.getBytesList(8).isEmpty(),
                "f8 武将简报不能空（客户端按 job 1..5 画五个武将位）");
        Assertions.assertTrue(row.getInt(6, 0) > 0, "f6 FightPower 必须非 0（否则面板显示空战力）");

        // 上阵后同一个候选的驻守域变成据点号（不再是 -1）。
        defFormation(session, ch, 3, 0, owner.playerId);
        union.onPvpAllDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_ALL_DEF_FORMATION, 3, new byte[0]));
        rows = Pb.read(pick(drain(ch), MsgIds.S2C_UNION_PVP_ALL_DEF_FORMATION_RET).body).getBytesList(1);
        Assertions.assertEquals(1, rows.size(), "上阵不改变候选条数（同一支队仍是那一行）");
        Assertions.assertEquals(3, Pb.read(rows.get(0)).getInt(7, 0), "上阵后驻守域 = 据点 3");
    }

    /**
     * 没保存过预设的成员不进候选，保存过之后才进（用户 m20090 #1：没布防的成员就是没上场，
     * 这样不会被打，只有布防的才会被打）。
     *
     * <p>改前对「一套预设都没存过」的成员造一条「预设 1」兜底候选（其武将是
     * {@code formationSlots} 回落的 PVE 阵容）⇒ 会长可以把一个从没布防的成员拉上场。
     */
    @Test
    public void pvpCandidatesOnlyIncludeSavedPresets() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "候选会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(ownerCh);
        drain(memberCh);

        List<String> ownerPreset = new ArrayList<>();
        ownerPreset.add(owner.findHeroByIndex(owner.mainHeroIndex).id);
        owner.setFormationSlots(24, ownerPreset);
        store.save(owner);

        union.onPvpAllDefFormation(ownerSession, new GamePacket(MsgIds.C2S_UNION_PVP_ALL_DEF_FORMATION, 1, new byte[0]));
        List<byte[]> rows = Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_PVP_ALL_DEF_FORMATION_RET).body)
                .getBytesList(1);
        Assertions.assertEquals(1, rows.size(), "只有保存过预设的会长进候选，会员不进");
        Assertions.assertEquals(owner.playerId, Pb.read(rows.get(0)).getInt(1, 0));

        // 会员保存预设 2（type 25）后才进候选，且带着他自己的预设号。
        List<String> memberPreset = new ArrayList<>();
        memberPreset.add(member.findHeroByIndex(member.mainHeroIndex).id);
        member.setFormationSlots(25, memberPreset);
        store.save(member);

        union.onPvpAllDefFormation(ownerSession, new GamePacket(MsgIds.C2S_UNION_PVP_ALL_DEF_FORMATION, 2, new byte[0]));
        rows = Pb.read(pick(drain(ownerCh), MsgIds.S2C_UNION_PVP_ALL_DEF_FORMATION_RET).body).getBytesList(1);
        Assertions.assertEquals(2, rows.size(), "会员保存预设后进候选");
        Pb.Fields memberRow = Pb.read(rows.get(1));
        Assertions.assertEquals(member.playerId, memberRow.getInt(1, 0));
        Assertions.assertEquals(25, memberRow.getInt(2, 0), "候选带成员自己的预设号（type 25）");
        Assertions.assertEquals(-1, memberRow.getInt(7, 0), "会员未上阵 ⇒ DefPointsIndex = -1");
    }

    /**
     * 1552 的「下阵」只由 {@code DefPlayerGuid = 0} 标记，与 {@code DefFormationType} 无关。
     *
     * <p>客户端下阵固定发 {@code DefFormationType = 24} + {@code DefPlayerGuid = 0}
     * （{@code GongHuiZhanJuDianBuFangUI.cs:207-209}），而「用预设 1 上阵」同样是 type 24、只是
     * playerGuid 是真实成员 ⇒ 改前用 {@code formationType == 24} 当下阵判据，会把**部署预设 1**
     * 当成下阵删掉（还回 1949 成功），玩家永远布不上第一支队。
     */
    @Test
    public void deployingDefensePresetOneIsNotTreatedAsRemoval() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "预设一会", "direct");

        defFormation(session, ch, 3, 0, owner.playerId);
        WorldStore.PvpFormation placed = u.pvpFindFormation(3, 0);
        Assertions.assertNotNull(placed, "type 24 + 真实 playerGuid 是上阵，不是下阵");
        Assertions.assertEquals(24, placed.formationType);
        Assertions.assertEquals(owner.playerId, placed.playerId);

        // 真正的下阵（playerGuid = 0）仍按 formationIndex 定位。
        defFormation(session, ch, 3, 0, 0);
        Assertions.assertNull(u.pvpFindFormation(3, 0), "playerGuid=0 才是下阵");
    }

    /**
     * 上阵后 1948/1950/1960 的武将与战力必须来自**请求里选中的那套预设**
     * （{@code DefFormationType} 24..28），而不是「主将 + 武将表前几个」。
     *
     * <p>改前 1552 完全忽略 {@code DefFormationType}，用 {@code pvpWjBriefs(owner)} 拼队伍 ⇒
     * 会长在「我的队伍」里选第 2 套上阵，据点里出现的却是另一套（面板显示与实战队伍全错）。
     */
    @Test
    public void deployedFormationUsesSelectedPresetWuJiangAndFightPower() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "预设会", "direct");

        PlayerRecord.Hero second = new PlayerRecord.Hero();
        second.heroIndex = owner.mainHeroIndex + 7;
        second.id = PlayerDumpService.guidOf(OWNER, second.heroIndex);
        second.level = 40;
        second.fightPower = 777;
        owner.heroes.add(second);

        List<String> preset1 = new ArrayList<>();
        preset1.add(owner.findHeroByIndex(owner.mainHeroIndex).id);
        owner.setFormationSlots(24, preset1);
        List<String> preset2 = new ArrayList<>();
        preset2.add(second.id);
        owner.setFormationSlots(25, preset2);
        store.save(owner);

        // 用「预设 2」（type 25）上阵。
        union.onPvpUpdateDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_UPDATE_DEF_FORMATION, 1,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 3);
                    Pb.int32Always(o, 2, 0);
                    Pb.int32Always(o, 3, owner.playerId);
                    Pb.int32Always(o, 4, 25);
                })));
        Assertions.assertNotNull(pick(drain(ch), MsgIds.S2C_UNION_PVP_UPDATE_DEF_FORMATION_RET));

        WorldStore.PvpFormation placed = u.pvpFindFormation(3, 0);
        Assertions.assertNotNull(placed);
        Assertions.assertEquals(25, placed.formationType, "落档的 type 必须是请求里选的那套预设");
        Assertions.assertEquals(1, placed.wjs.size(), "只带预设 2 里的那一个武将");
        Assertions.assertEquals(second.heroIndex, placed.wjs.get(0).index);
        Assertions.assertEquals(777, placed.fightPower, "战力 = 所选预设的武将合计");

        // 1948 的 f4 也按该预设下发（客户端用它画据点上的五个武将位）。
        union.onPvpDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_DEF_FORMATION, 2,
                Pb.write(o -> Pb.int32Always(o, 1, 3))));
        Pb.Fields one = Pb.read(Pb.read(pick(drain(ch), MsgIds.S2C_UNION_PVP_DEF_FORMATION_RET).body)
                .getBytesList(2).get(0));
        Assertions.assertEquals(second.heroIndex,
                Pb.read(Pb.read(one.getBytesList(4).get(0)).getBytes(2)).getInt(1, 0),
                "CMsgWuJiangJobAndBriefInfo.2.wjBriefInfo.index = 所选预设的武将");
    }

    /**
     * 同一成员的同一套预设换据点 = **移动**：旧落点必须摘掉。
     *
     * <p>依据：1950 里每个预设只有一行、其 {@code DefPointsIndex} 是单值，客户端「添加队伍」面板的
     * 驻守域列也只显示一个据点名（{@code GongHuiZhanJuDianAddTeamUI.cs:127-132}）⇒ 同一支队不能
     * 同时出现在两个据点（否则 1959 剩余队伍数与 1960 布防表都会重复计数）。
     */
    @Test
    public void deployingSamePresetOnAnotherPointMovesIt() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "移动会", "direct");

        // 先保存防守预设 1（客户端 C2S 202），它才会出现在 1950 候选里。
        List<String> preset1 = new ArrayList<>();
        preset1.add(owner.findHeroByIndex(owner.mainHeroIndex).id);
        owner.setFormationSlots(24, preset1);
        store.save(owner);

        defFormation(session, ch, 3, 0, owner.playerId);
        Assertions.assertNotNull(u.pvpFindFormation(3, 0));

        defFormation(session, ch, 4, 0, owner.playerId);
        Assertions.assertNotNull(u.pvpFindFormation(4, 0), "新据点必须上阵成功");
        Assertions.assertNull(u.pvpFindFormation(3, 0), "同一预设换据点必须摘掉旧落点");
        Assertions.assertTrue(u.pvpFormationsOf(3).isEmpty(), "旧据点不得残留同一支队");

        // 1950 里该预设仍只有一行，驻守域是新的据点号。
        union.onPvpAllDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_ALL_DEF_FORMATION, 9, new byte[0]));
        List<byte[]> rows = Pb.read(pick(drain(ch), MsgIds.S2C_UNION_PVP_ALL_DEF_FORMATION_RET).body).getBytesList(1);
        Assertions.assertEquals(1, rows.size(), "同一套预设只占一行候选");
        Assertions.assertEquals(4, Pb.read(rows.get(0)).getInt(7, 0));
    }

    /**
     * 1564 → 1961 的**己方**队伍取「公会战进攻阵容」（eFormationType 29 =
     * {@code FORMATION_TYPE_UNION_PVP_OFFENSE}），不是「主将 + 武将表前几个」。
     */
    @Test
    public void pvpFightFormationSelfTeamComesFromOffensePreset() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "攻阵会", "direct");

        PlayerRecord.Hero second = new PlayerRecord.Hero();
        second.heroIndex = owner.mainHeroIndex + 3;
        second.id = PlayerDumpService.guidOf(OWNER, second.heroIndex);
        second.level = 40;
        second.fightPower = 555;
        owner.heroes.add(second);
        List<String> offense = new ArrayList<>();
        offense.add(second.id);
        owner.setFormationSlots(PlayerRecord.FORMATION_UNION_PVP_OFFENSE, offense);
        store.save(owner);
        // 1564 的闸门（用户 m24587 #4）要求世界里有另一个公会、据点是可攻占的、且上面有活着的防守队。
        deployRival(0, 1);

        union.onPvpFightFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_FORMATION, 1,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 0);
                    Pb.int32Always(o, 2, 1);
                })));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_UNION_PVP_TARGET_POINT_FORMATION);
        Assertions.assertNotNull(ret);
        Pb.Fields myTeam = Pb.read(Pb.read(ret.body).getBytes(1));
        List<byte[]> myWjs = myTeam.getBytesList(2);
        Assertions.assertEquals(1, myWjs.size(), "己方队伍 = 进攻阵容（type 29）里的那一支");
        Assertions.assertEquals(second.heroIndex, Pb.read(myWjs.get(0)).getInt(3, 0),
                "CCMsgWuJiangAllInfoAndJobAndHp.3 index");
    }

    /**
     * 1564 → 1961 {@code CCMsgUnionPvPRealTeamDetailInfo}：两个子消息都必须存在，
     * 否则客户端 MatchPlayer.UnPackUnionPVP(null) 等三处 NRE（APK Client\ᝁ.cs:6404-6406）。
     * 字段号按公会战版（3 BuddiesIndex / 4 JiBanSlotInfo / 5 FightPowerReturn），与 JJC 版不同。
     *
     * <p>用户 m24587 #4 后 1564 有闸门 ⇒ {@code targetTeam} 一定是**防守方**。改前「对手数据缺失时
     * 用自己顶位」那条分支已按 APK 口径删掉：客户端只对 {@code isCanAttack} 的据点显示进攻入口
     * （{@code ᜋ.cs:174/183}），不会打到没有防守队的据点。
     */
    @Test
    public void pvpFightFormationCarriesBothTeams() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "甲会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "甲会", "direct");
        WorldStore.UnionRecord uR = deployRival(0, 1);

        union.onPvpFightFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_FORMATION, 1,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 0);
                    Pb.int32Always(o, 2, 1);
                })));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_UNION_PVP_TARGET_POINT_FORMATION);
        Assertions.assertNotNull(ret);
        Pb.Fields f = Pb.read(ret.body);
        Pb.Fields myTeam = Pb.read(f.getBytes(1));
        Pb.Fields targetTeam = Pb.read(f.getBytes(2));
        Assertions.assertEquals(owner.playerId, myTeam.getInt(1, 0), "myTeam.PlayerGuid");
        Assertions.assertEquals(uR.pvpFormationsOf(0).get(0).playerId, targetTeam.getInt(1, 0),
                "targetTeam 是防守方账号，不是自己");
        Assertions.assertEquals(10, myTeam.getInts(3).size(), "3 号位 BuddiesIndex 满孔下发");
        Assertions.assertEquals(3, myTeam.getInts(5).size(), "5 号位 FightPowerReturn 固定 3 个");
        Assertions.assertFalse(myTeam.getBytesList(4).isEmpty(), "4 号位 JiBanSlotInfo 不能空发");
    }

    /**
     * 1961 的武将子消息必须带全 f7–f18（用户 m24587 #2）。改前只写 f1–f6 ⇒ 客户端
     * {@code MatchPlayer.UnPackUnionPVP} 建出的对手没有装备/器魂/套装，{@code WuJiang.cs:860}
     * 的进阶参数与 {@code :318-322} 的套装被动全 0 ⇒ 本地战斗里对手是个空壳、玩家必赢。
     *
     * <p>字段号按 {@code CCMsgWuJiangAllInfoAndJobAndHp}：7-10 进阶四参数、11-14 技能等级、
     * 15 时光石、16 装备 Brief、17 器魂、18 套装被动 id（比 {@code CCMsgWuJiangAllInfoAndJob}
     * 整体后移一位）。
     */
    @Test
    public void pvpFightFormationCarriesTargetGear() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "甲会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "甲会", "direct");
        WorldStore.UnionRecord uR = deployRival(0, 1);

        // 防守方主将：进阶参数 + 技能等级 + 两件 1 号套（EQ0080/EQ0081 ⇒ 2 件档 beiDong 500101）
        // + 已合成的器魂。
        PlayerRecord rival = store.findByPlayerId(uR.pvpFormationsOf(0).get(0).playerId);
        Assertions.assertNotNull(rival, "防守方存档必须存在（否则 1961 只能退回空壳）");
        PlayerRecord.Hero rwj = rival.findHeroByIndex(rival.mainHeroIndex);
        rwj.stagePara1 = 7;
        rwj.skill4 = 33;
        equipOn(rival, "EQ0080", rwj.id);
        equipOn(rival, "EQ0081", rwj.id);
        PlayerRecord.Soul soul = new PlayerRecord.Soul();
        soul.composed = true;
        soul.stage = 3;
        soul.exp = 11;
        rival.souls.put(Integer.valueOf(rwj.heroIndex), soul);
        store.save(rival);

        union.onPvpFightFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_FORMATION, 1,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 0);
                    Pb.int32Always(o, 2, 1);
                })));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_UNION_PVP_TARGET_POINT_FORMATION);
        Assertions.assertNotNull(ret);
        Pb.Fields targetTeam = Pb.read(Pb.read(ret.body).getBytes(2));
        List<byte[]> wjs = targetTeam.getBytesList(2);
        Assertions.assertEquals(1, wjs.size(), "防守方一支队伍一个武将");
        Pb.Fields wj = Pb.read(wjs.get(0));
        Assertions.assertEquals(7, wj.getInt(7, 0), "f7 jieduan_type_1_para");
        Assertions.assertEquals(33, wj.getInt(14, 0), "f14 skillindex_4_level");
        Assertions.assertEquals(7, wj.getBytesList(15).size(), "f15 timestoneinfo 七孔");
        List<byte[]> eqs = wj.getBytesList(16);
        List<String> oris = new ArrayList<>();
        for (byte[] e : eqs) {
            oris.add(Pb.read(e).getString(1));
        }
        Assertions.assertTrue(oris.contains("EQ0080"), "f16 equipments 带防守方装备 EQ0080");
        Assertions.assertTrue(oris.contains("EQ0081"), "f16 equipments 带防守方装备 EQ0081");
        Assertions.assertEquals(3, Pb.read(wj.getBytes(17)).getInt(2, 0), "f17 equipSoul.jieduan");
        Assertions.assertTrue(wj.getInts(18).contains(Integer.valueOf(500101)), "f18 suiteffectid 2 件档");
    }

    /**
     * 1552 职位闸门（用户 m24587 #3）：APK 的布防界面只对会长/副会长开放（{@code mEUnionJob != 1}），
     * 普通成员只能动**自己的**阵容 —— 不能替别人布防，也不能把别人的队伍下阵；会长/长老不受限。
     */
    @Test
    public void pvpDefFormationOnlyOwnerOrElderTouchesOthers() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "布防会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(ownerCh);
        drain(memberCh);

        // 会长先占 3 号据点 1 号位。
        defFormation(ownerSession, ownerCh, 3, 1, owner.playerId);
        Assertions.assertEquals(1, u.pvpFormationsOf(3).size(), "会长布防成功");

        // 成员布自己的队伍：放行。
        defFormation(memberSession, memberCh, 3, 0, member.playerId);
        Assertions.assertEquals(2, u.pvpFormationsOf(3).size(), "成员可以布自己的队伍");

        // 成员替会长布防：拒（不落档）。
        defFormation(memberSession, memberCh, 3, 2, owner.playerId);
        Assertions.assertEquals(2, u.pvpFormationsOf(3).size(), "成员不得替会长布防");

        // 成员下阵会长的队伍（guid 0 + 会长所在格位）：拒。
        defFormation(memberSession, memberCh, 3, 1, 0);
        Assertions.assertEquals(owner.playerId, u.pvpFindFormation(3, 1).playerId, "成员不得下阵别人的队伍");

        // 成员下阵自己的队伍：放行。
        defFormation(memberSession, memberCh, 3, 0, 0);
        Assertions.assertNull(u.pvpFindFormation(3, 0), "成员可以撤自己的队伍");

        // 长老替会长布防：放行。
        union.onAppointElder(ownerSession, new GamePacket(MsgIds.C2S_APPOINT_ELDER, 2,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, member.playerId);
                    Pb.boolAlways(o, 2, true);
                })));
        drain(ownerCh);
        drain(memberCh);
        Assertions.assertEquals(2, member.guildJobCode(), "任命后 job=2（长老）");
        defFormation(memberSession, memberCh, 3, 2, owner.playerId);
        Assertions.assertEquals(owner.playerId, u.pvpFindFormation(3, 2).playerId, "长老可以替成员布防");
    }

    /**
     * 1563 必须校验据点号（用户 m24587 #6）：改前任意 point 都照发 1960，
     * 客户端会渲染出一个不存在的据点面板。
     */
    @Test
    public void pvpDefPointDetailRejectsUnknownPoint() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "据点会", "direct");

        union.onPvpDefPointDetail(session, new GamePacket(MsgIds.C2S_UNION_PVP_DEF_POINT_DETAIL, 1,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 99);
                    Pb.boolAlways(o, 2, true);
                })));
        Assertions.assertNull(pick(drain(ch), MsgIds.S2C_UNION_PVP_DEF_POINT_DETAIL_RET), "未知据点不得回 1960");

        union.onPvpDefPointDetail(session, new GamePacket(MsgIds.C2S_UNION_PVP_DEF_POINT_DETAIL, 2,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 0);
                    Pb.boolAlways(o, 2, true);
                })));
        Assertions.assertNotNull(pick(drain(ch), MsgIds.S2C_UNION_PVP_DEF_POINT_DETAIL_RET), "真实据点照常回 1960");
    }

    /**
     * 1564 闸门（用户 m24587 #4，按 APK 推断）：只能打「可攻占（前置已占且未被己方占）」且
     * 「防守队存在且存活」的据点。1961 没有失败位（{@code CCMsgUnionPvPRealTeamDetailInfo}
     * 只有 myTeam/targetTeam），所以不合法请求只能静默不回。
     */
    @Test
    public void pvpFightFormationRejectsUnattackablePointAndDeadFormation() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "甲会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        createUnion(session, ch, "甲会", "direct");
        WorldStore.UnionRecord uR = deployRival(1, 1); // 据点 1 有防守队，但前置（据点 0）不在甲会手里
        uR.pvpFormationsOf(0).add(formation(store.get(RIVAL), 0, 1)); // 据点 0 也有防守队（始终可攻）
        world.saveUnions();

        // 不可攻占据点：据点 1 的前置据点 0 还在乙会手里。
        union.onPvpFightFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_FORMATION, 1,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 1);
                    Pb.int32Always(o, 2, 1);
                })));
        Assertions.assertNull(pick(drain(ch), MsgIds.S2C_UNION_PVP_TARGET_POINT_FORMATION),
                "前置未占 ⇒ 不得回 1961");
        Assertions.assertEquals(0, uR.pvpAttackerCnt, "被拒的请求不能刷 1963 攻击计数");

        // 可攻占据点 0，但格位 2 上没有队伍。
        union.onPvpFightFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_FORMATION, 2,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 0);
                    Pb.int32Always(o, 2, 2);
                })));
        Assertions.assertNull(pick(drain(ch), MsgIds.S2C_UNION_PVP_TARGET_POINT_FORMATION),
                "不存在的防守队 ⇒ 不得回 1961");

        // 可攻占据点 0 + 存在的防守队 ⇒ 正常回 1961。
        union.onPvpFightFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_FORMATION, 3,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 0);
                    Pb.int32Always(o, 2, 1);
                })));
        Assertions.assertNotNull(pick(drain(ch), MsgIds.S2C_UNION_PVP_TARGET_POINT_FORMATION), "正常进攻回 1961");
    }

    /**
     * 1567 → 1965 + **单场遭遇战**奖励；1566 → 1964 两个 repeated 必须等长。
     *
     * <p>奖励分两层（见 {@code UnionService#onPvpFightResult} 的 javadoc）：本方法只发
     * 「个人打下单个部队数获得兄弟币 50 / 物品 UFTS1」；「胜利获得的公会成长值 50000 / 公会晶石 1500」
     * 与「兄弟币**基础**奖励 1000 / UFTS2」是整场奖励，20:00 结算时才发（{@link #pvpSettlementMailsAndUnionRewards}）。
     */
    @Test
    public void pvpFightResultRewardsAndWjHp() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "结算会", "direct");
        u.pvpFormationsOf(1).add(formation(owner, 1, 1));
        world.saveUnions();
        int brotherBefore = owner.guild.brotherCoin;
        int growthBefore = u.growth;
        int crystalBefore = u.crystal;

        union.onPvpFightResult(session, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_RESULT, 1,
                Pb.write(o -> Pb.bool(o, 1, true))));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_UNION_PVP_FIGHT_RECORD_PUSH);
        Assertions.assertNotNull(ret);
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertTrue(f.getBool(1), "1965 IsWin");
        Assertions.assertEquals(growthBefore, f.getInt(2, 0), "1965 f2 = 公会当前成长值（整场奖励未结算）");
        Assertions.assertEquals(crystalBefore, f.getInt(3, 0), "1965 f3 = 公会当前晶石");
        Assertions.assertEquals(growthBefore, u.growth, "遭遇战不再直接加公会成长值（移到 20:00 结算）");
        Assertions.assertEquals(crystalBefore, u.crystal, "遭遇战不再直接加公会晶石");
        Assertions.assertEquals(1, u.pvpWinTimes);
        Assertions.assertEquals(1, u.pvpCapturedPoints.size(), "胜一场推进一个攻占点");
        Assertions.assertEquals(1, u.pvpRecords.size());
        Assertions.assertEquals(brotherBefore + unionCfg.pvpUnitBrother(), owner.guild.brotherCoin,
                "单场只发「个人打下单个部队数获得兄弟币」");

        union.onPvpWjHp(session, new GamePacket(MsgIds.C2S_UNION_PVP_WJ_HP, 2, new byte[0]));
        GamePacket hp = pick(drain(ch), MsgIds.S2C_UNION_PVP_WJ_HP_RET);
        Assertions.assertNotNull(hp);
        Pb.Fields hf = Pb.read(hp.body);
        Assertions.assertEquals(hf.getInts(1).size(), hf.getInts(2).size(),
                "1964 两个 repeated 长度必须相等，否则客户端按序号取值越界");
        Assertions.assertFalse(hf.getInts(1).isEmpty());
    }

    /**
     * 真服配对规则 = **随机**（用户 m19006 #9）：世界上有第三个公会时，报名钉住的对手不再固定为
     * 「存档里第一个不是自己的公会」。
     *
     * <p>做法：清掉甲会的 {@code pvpEnrolled/pvpRivalId} 后重复报名 30 次，统计钉到的对手 id 集合。
     * 旧实现（恒取列表第一个）只会得到一个元素 ⇒ 「乙、丙都出现过」必然失败；新实现每次 1/2 概率
     * 命中丙会，30 次全落乙会的概率约 1e-9。
     */
    @Test
    public void pvpRivalPairingIsRandom() {
        resetWorld();
        PlayerRecord ownerA = fresh(OWNER, "甲会长");
        EmbeddedChannel chA = newChannel();
        GameSession sA = bind(ownerA, chA);
        WorldStore.UnionRecord uA = createUnion(sA, chA, "甲会", "direct");
        drain(chA);

        PlayerRecord ownerB = fresh(RIVAL, "乙会长");
        EmbeddedChannel chB = newChannel();
        GameSession sB = bind(ownerB, chB);
        WorldStore.UnionRecord uB = createUnion(sB, chB, "乙会", "direct");
        drain(chB);

        PlayerRecord ownerC = fresh(RIVAL2, "丙会长");
        EmbeddedChannel chC = newChannel();
        GameSession sC = bind(ownerC, chC);
        WorldStore.UnionRecord uC = createUnion(sC, chC, "丙会", "direct");
        drain(chC);

        uA.crystal = 100000;
        world.saveUnions();
        pinClock(4 * 3600); // 报名窗口内（非战斗日全天 / 战斗日 00:00–19:00）

        Set<String> pinned = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            uA.pvpEnrolled = false;
            uA.pvpRivalId = "";
            world.saveUnions();
            union.onPvpEnroll(sA, new GamePacket(MsgIds.C2S_UNION_PVP_ENROLL, i + 1, new byte[0]));
            drain(chA);
            pinned.add(uA.pvpRivalId);
        }
        Assertions.assertTrue(pinned.contains(uB.id) && pinned.contains(uC.id),
                "配对必须随机（用户 m19006 #9）：30 次报名应同时出现过乙会与丙会，实际 = " + pinned);
    }

    /**
     * 跨公会双账号：乙会布好阵容后，甲会会长 1564 打乙会据点 → 1961 的 targetTeam 必须是**乙会那个账号**。
     *
     * <p>改前 {@code findByAccount} 查不到目标账号时把 target 退化成自己（自己打自己），
     * 且 1946 报名不钉对手 ⇒ 同日对手会随存档顺序漂移。本用例钉住这两点（本用例只有两个公会，
     * 随机配对（用户 m19006 #9）在单候选下等价于确定配对）。
     */
    @Test
    public void pvpCrossUnionFightTargetsRivalFormation() {
        resetWorld();
        PlayerRecord ownerA = fresh(OWNER, "甲会长");
        EmbeddedChannel chA = newChannel();
        GameSession sA = bind(ownerA, chA);
        WorldStore.UnionRecord uA = createUnion(sA, chA, "甲会", "direct");
        drain(chA);

        PlayerRecord ownerB = fresh(RIVAL, "乙会长");
        EmbeddedChannel chB = newChannel();
        GameSession sB = bind(ownerB, chB);
        WorldStore.UnionRecord uB = createUnion(sB, chB, "乙会", "direct");
        drain(chB);

        uA.crystal = 2000;
        uB.pvpFormationsOf(1).add(formation(ownerB, 1, 1));
        // 据点 0 是唯一「无前置、始终可攻」的据点（UnionPvPDefPointsInfo.txt 第 3 列反查前置），
        // 后面要用它走完整的「打掉防守队 → 攻陷」链路来验证 1959 f3 的方向。
        uB.pvpFormationsOf(0).add(formation(ownerB, 0, 1));
        world.saveUnions();
        pinClock(4 * 3600); // 报名窗口外，避免用例在 19–21 点跑被拒

        union.onPvpEnroll(sA, new GamePacket(MsgIds.C2S_UNION_PVP_ENROLL, 1, new byte[0]));
        drain(chA);
        Assertions.assertEquals(uB.id, uA.pvpRivalId, "报名即钉住当天对手");

        // ---- 1564 闸门（用户 m24587 #4）：据点 1 的前置（据点 0）还在乙会手里 ⇒ 不可攻占。
        // 客户端也只对 isCanAttack 的据点显示进攻入口（ᜋ.cs:174/183），所以服务端必须同样拒掉，
        // 且 1961 没有失败位 ⇒ 静默不回、不刷攻击计数。
        union.onPvpFightFormation(sA, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_FORMATION, 2,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 1);
                    Pb.int32Always(o, 2, 1);
                })));
        Assertions.assertNull(pick(drain(chA), MsgIds.S2C_UNION_PVP_TARGET_POINT_FORMATION),
                "前置未占 ⇒ 不可攻占据点不得回 1961");
        Assertions.assertEquals(0, uB.pvpAttackerCnt, "被拒的请求不能刷攻击计数");

        // ---- 据点 0 是唯一「无前置、始终可攻」的据点（UnionPvPDefPointsInfo.txt 第 3 列反查前置）。
        union.onPvpFightFormation(sA, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_FORMATION, 3,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 0);
                    Pb.int32Always(o, 2, 1);
                })));
        GamePacket ret = pick(drain(chA), MsgIds.S2C_UNION_PVP_TARGET_POINT_FORMATION);
        Assertions.assertNotNull(ret);
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertEquals(ownerA.playerId, Pb.read(f.getBytes(1)).getInt(1, 0), "myTeam 是自己");
        Assertions.assertEquals(ownerB.playerId, Pb.read(f.getBytes(2)).getInt(1, 0),
                "targetTeam 是乙会那个账号，不是自己");
        Assertions.assertEquals(1, uB.pvpAttackerCnt, "乙会据点被攻击计数 +1");
        Assertions.assertTrue(hasIn(drain(chB), MsgIds.S2C_UNION_PVP_POINT_ATTACKERS),
                "1963 推给乙会在线成员");

        // ---- 走完整攻陷链路：1564 打乙会据点 0（该据点唯一防守队）→ 1567 上报胜 → 全灭即攻陷。
        union.onPvpFightFormation(sA, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_FORMATION, 4,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 0);
                    Pb.int32Always(o, 2, 1);
                })));
        drain(chA);
        drain(chB);
        union.onPvpFightResult(sA, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_RESULT, 5,
                Pb.write(o -> Pb.boolAlways(o, 1, true))));
        drain(chA);
        Assertions.assertTrue(uA.pvpCapturedPoints.contains(Integer.valueOf(0)), "甲会攻下乙会据点 0");
        Assertions.assertFalse(uB.pvpCapturedPoints.contains(Integer.valueOf(0)), "攻陷后从乙会列表摘除");

        // ---- 1959 f3 IsBeAttacked 的方向：1562 是「我的据点」面板（GongHuiZhanJuDianBoard.cs:240-255
        // 用它切 LabelAllDead「全员阵亡」），语义是「我方该据点已被敌方攻陷」⇒ 必须读**对手**的已攻占列表。
        // 改前错读 mine.pvpCapturedPoints：甲会刚攻下乙会据点 0，会让**甲会自己**的据点 0 显示「全员阵亡」，
        // 而真正失守的乙会据点 0 因 left==0 只显示「无队伍」。
        for (Pb.Fields row : leftBoard(sA, chA, 6)) {
            Assertions.assertFalse(row.getBool(3),
                    "甲会自己的据点 " + row.getInt(1, 0) + " 没被敌方攻陷，不能显示「全员阵亡」");
        }
        boolean sawCaptured = false;
        for (Pb.Fields row : leftBoard(sB, chB, 6)) {
            int point = row.getInt(1, 0);
            if (point == 0) {
                sawCaptured = true;
                Assertions.assertTrue(row.getBool(3), "乙会据点 0 已被甲会攻陷 ⇒ IsBeAttacked=true");
                Assertions.assertEquals(0, row.getInt(2, 0), "被攻陷据点剩余防守队伍数 = 0");
            } else {
                Assertions.assertFalse(row.getBool(3), "乙会据点 " + point + " 未失守");
            }
        }
        Assertions.assertTrue(sawCaptured, "1959 必须包含据点 0");
    }

    /**
     * 公会战结算（20:00 分钟钩子 / 跨日兜底）：按据点判邮件模板 10–16，
     * 公会成长值·晶石直接加到公会，每个成员收到一封对应模板的系统邮件。
     */
    @Test
    public void pvpSettlementMailsAndUnionRewards() {
        resetWorld();
        PlayerRecord ownerA = fresh(OWNER, "甲会长");
        EmbeddedChannel chA = newChannel();
        GameSession sA = bind(ownerA, chA);
        WorldStore.UnionRecord uA = createUnion(sA, chA, "甲会", "direct");
        drain(chA);
        PlayerRecord ownerB = fresh(RIVAL, "乙会长");
        EmbeddedChannel chB = newChannel();
        GameSession sB = bind(ownerB, chB);
        WorldStore.UnionRecord uB = createUnion(sB, chB, "乙会", "direct");
        drain(chB);

        String day = PlayerDumpService.now().substring(0, 10);
        uA.pvpDay = day;
        uA.pvpEnrolled = true;
        uA.pvpRivalId = uB.id;
        uB.pvpDay = day;
        uB.pvpEnrolled = true;
        uA.growth = 100;
        uA.crystal = 500;
        uB.growth = 100;
        uB.crystal = 500;
        List<Integer> points = unionCfg.pvpPointIds();
        uA.pvpCapturedPoints.add(points.get(points.size() - 1)); // 最终据点 9 不灭堡垒 ⇒ 攻陷基地
        uB.pvpCapturedPoints.add(points.get(0));
        ownerA.mails.clear();
        ownerB.mails.clear();
        world.saveUnions();

        union.settlePvpBattle(uA, day, "test");
        union.settlePvpBattle(uB, day, "test");

        Assertions.assertEquals(100 + unionCfg.pvpWinGrow(), uA.growth, "胜方公会成长值");
        Assertions.assertEquals(500 + unionCfg.pvpWinCrystal(), uA.crystal, "胜方公会晶石");
        Assertions.assertEquals(1, ownerA.mails.size(), "胜方每个成员一封");
        PlayerRecord.Mail mailA = ownerA.mails.get(0);
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_PVP_BASE_WIN, mailA.mailType,
                "10 攻陷对方基地");
        Assertions.assertEquals(unionCfg.pvpWinBrother(), mailA.brotherCoin);
        Assertions.assertEquals("乙会", mailA.paras.get(0), "正文 {0}=对手公会名");

        Assertions.assertEquals(100 + unionCfg.pvpLoseGrow(), uB.growth, "败方公会成长值");
        Assertions.assertEquals(1, ownerB.mails.size());
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_PVP_BASE_LOSE, ownerB.mails.get(0).mailType,
                "11 基地失守");
        Assertions.assertEquals(unionCfg.pvpLoseBrother(), ownerB.mails.get(0).brotherCoin);

        // 重复调用不再结算（pvpSettledDay 去重）
        ownerA.mails.clear();
        union.settlePvpBattle(uA, day, "test");
        Assertions.assertTrue(ownerA.mails.isEmpty(), "同一战斗日只结算一次");
    }

    /**
     * 调度入口 {@code settlePvpBattles(why)}（{@code GlobalServerScheduler.unionSettleClock} 分钟级
     * + {@code onServerReady} 启动补跑）本身也要能被验证：它被 {@code pvpWindowOver()} 门控，
     * 而该判定现已走 {@code secOfDay()}（可被 {@code clockSecOverride} 钉住），
     * 因此这里钉 19:00（开战时刻、窗口未结束）与 20:00（窗口结束）两处时钟，
     * 断言「不到点不结算 / 到点结算 / 同日只结算一次」。
     */
    @Test
    public void pvpSettleClockEntryRunsAfterWindowAndDedupsPerDay() {
        resetWorld();
        PlayerRecord ownerA = fresh(OWNER, "甲会长");
        EmbeddedChannel chA = newChannel();
        GameSession sA = bind(ownerA, chA);
        WorldStore.UnionRecord uA = createUnion(sA, chA, "甲会", "direct");
        drain(chA);
        PlayerRecord ownerB = fresh(RIVAL, "乙会长");
        EmbeddedChannel chB = newChannel();
        GameSession sB = bind(ownerB, chB);
        WorldStore.UnionRecord uB = createUnion(sB, chB, "乙会", "direct");
        drain(chB);

        String day = PlayerDumpService.now().substring(0, 10);
        uA.pvpDay = day;
        uA.pvpEnrolled = true;
        uA.pvpRivalId = uB.id;
        uB.pvpDay = day;
        uB.pvpEnrolled = true;
        List<Integer> points = unionCfg.pvpPointIds();
        uA.pvpCapturedPoints.add(points.get(points.size() - 1));
        uB.pvpCapturedPoints.add(points.get(0));
        ownerA.mails.clear();
        ownerB.mails.clear();
        world.saveUnions();

        pinClock(unionCfg.pvpStartHour() * 3600 + unionCfg.pvpStartMinute() * 60);
        union.settlePvpBattles("clock");
        Assertions.assertTrue(ownerA.mails.isEmpty(), "开战时刻（19:00）窗口未结束，不结算");
        Assertions.assertNotEquals(day, uA.pvpSettledDay, "未结算不得标记 pvpSettledDay");

        pinClock(unionCfg.pvpStartHour() * 3600 + unionCfg.pvpStartMinute() * 60
                + unionCfg.pvpDurationSec());
        union.settlePvpBattles("clock");
        // 分钟钩子还要过「今天是不是战斗日」这道闸（客户端非战斗日相位恒 Preparing，压根没有战事）。
        // 两个分支都断言，避免用例只在周一/三/六有效。
        if (union.isPvpBattleDay(GameTime.today())) {
            Assertions.assertEquals(1, ownerA.mails.size(), "战斗日 20:00 分钟钩子结算胜方");
            Assertions.assertEquals(MailService.MAIL_TYPE_UNION_PVP_BASE_WIN, ownerA.mails.get(0).mailType);
            Assertions.assertEquals(1, ownerB.mails.size(), "同一场对手也结算");
            Assertions.assertEquals(day, uA.pvpSettledDay, "结算后标记当日");

            ownerA.mails.clear();
            union.settlePvpBattles("clock");
            Assertions.assertTrue(ownerA.mails.isEmpty(), "同一战斗日重复触发只结算一次");
        } else {
            Assertions.assertTrue(ownerA.mails.isEmpty(), "非战斗日 20:00 不得结算");
            Assertions.assertTrue(ownerB.mails.isEmpty(), "非战斗日 20:00 不得结算");
            Assertions.assertNotEquals(day, uA.pvpSettledDay, "非战斗日不得标记已结算");
        }
    }

    /** 结算的边界档：轮空（16，全服只有自己一个公会）与平局（14，据点相同 → 表无平局档，暂按败方档）。 */
    @Test
    public void pvpSettlementByeAndDrawMails() {
        resetWorld();
        PlayerRecord ownerA = fresh(OWNER, "甲会长");
        EmbeddedChannel chA = newChannel();
        GameSession sA = bind(ownerA, chA);
        WorldStore.UnionRecord uA = createUnion(sA, chA, "独会", "direct");
        drain(chA);
        String day = PlayerDumpService.now().substring(0, 10);
        uA.pvpDay = day;
        uA.pvpEnrolled = true;
        ownerA.mails.clear();
        world.saveUnions();

        union.settlePvpBattle(uA, day, "test");
        Assertions.assertEquals(1, ownerA.mails.size());
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_PVP_BYE, ownerA.mails.get(0).mailType,
                "16 幸运轮空");
        Assertions.assertTrue(ownerA.mails.get(0).paras.isEmpty(), "第16行没有 {0}");

        // 再来一个公会：双方都 0 个攻占点 ⇒ 14 平局
        PlayerRecord ownerB = fresh(RIVAL, "乙会长");
        EmbeddedChannel chB = newChannel();
        GameSession sB = bind(ownerB, chB);
        WorldStore.UnionRecord uB = createUnion(sB, chB, "乙会", "direct");
        drain(chB);
        ownerA.mails.clear();
        ownerB.mails.clear();
        uA.pvpSettledDay = "";
        uB.pvpDay = day + "-2";
        uB.pvpEnrolled = true;
        uA.pvpDay = day + "-2";
        // 上一场结算时服务端会主动解除报名（`settlePvpBattle` 末尾），这里要为第二场重新报名。
        uA.pvpEnrolled = true;
        world.saveUnions();

        union.settlePvpBattle(uA, day + "-2", "test");
        union.settlePvpBattle(uB, day + "-2", "test");
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_PVP_DRAW, ownerA.mails.get(0).mailType,
                "14 据点相同");
        Assertions.assertEquals(unionCfg.pvpLoseBrother(), ownerA.mails.get(0).brotherCoin,
                "表无平局档 ⇒ 暂按败方档");
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_PVP_DRAW, ownerB.mails.get(0).mailType);
    }

    /**
     * **非战斗日报名不得在午夜被「幻影结算」**，且必须跨日延续到它真正报的那一场
     * （`docs\PROTOCOL_FIELD_AUDIT.md` 再核轮补充 6 的缺陷②③）。
     *
     * <p>客户端在非战斗日（如周二）相位是 Preparing、面板可点，1549 会发出去，服务端接受后
     * `pvpEnrollDay` = 下一个启用战斗日。改前 `ensurePvpDay` 跨日时只看「昨天没结算过」就
     * `settlePvpBattle(u, 昨天)`，于是白送一整套公会战奖励（成长值/晶石/兄弟币/物品 + 邮件 10–16），
     * 顺手还把报名清掉，玩家真正报的那一场反而变成未报名。
     */
    @Test
    public void nonBattleDayEnrollIsNotPhantomSettledAndCarriesOver() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "幻影");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "影会", "direct");
        drain(ch);

        java.time.LocalDate battle = GameTime.today();
        while (!union.isPvpBattleDay(battle)) {
            battle = battle.plusDays(1);
        }
        // 报名发生的那个非战斗日必须**严格早于今天**，否则 ensurePvpDay 会因为「还是同一天」直接返回，
        // 用例就白跑了（曾经踩过：today 是战斗日时 off.minusDays(1) 恰好等于 today）。
        java.time.LocalDate off = GameTime.today().minusDays(1);
        while (union.isPvpBattleDay(off)) {
            off = off.minusDays(1);
        }
        // 那天（非战斗日）没有战事，报名冲着下一个战斗日 battle。
        u.pvpDay = off.toString();
        u.pvpEnrolled = true;
        u.pvpEnrollDay = battle.toString();
        u.growth = 100;
        u.crystal = 500;
        owner.mails.clear();
        world.saveUnions();

        union.rolloverPvpDays();

        Assertions.assertTrue(owner.mails.isEmpty(), "非战斗日没有战事，绝不能补结算发奖");
        Assertions.assertEquals(100, u.growth, "不得白送公会成长值");
        Assertions.assertEquals(500, u.crystal, "不得白送公会晶石");
        Assertions.assertNotEquals(u.pvpDay, u.pvpSettledDay, "不得把非战斗日标记成已结算");
        Assertions.assertTrue(u.pvpEnrolled, "报名必须延续到它真正报的那一场");
        Assertions.assertEquals(battle.toString(), u.pvpEnrollDay, "目标战斗日不变");
    }

    /**
     * 结算完一场必须**立刻解除报名状态**（缺陷④）：客户端 `UnionWarRoomEnter.cs:161` 用
     * 「已报名」标志把报名入口藏起来（100732），不清的话下一场永远报不上名。
     */
    @Test
    public void settleClearsEnrollmentSoNextBattleCanEnroll() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "结算清");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "清会", "direct");
        drain(ch);
        String day = PlayerDumpService.now().substring(0, 10);
        u.pvpDay = day;
        u.pvpEnrolled = true;
        u.pvpEnrollDay = day;
        u.growth = 100;
        u.crystal = 500;
        owner.mails.clear();
        world.saveUnions();

        union.settlePvpBattle(u, day, "test");
        Assertions.assertEquals(1, owner.mails.size(), "结算必须发生");
        Assertions.assertFalse(u.pvpEnrolled, "结算后必须解除报名，否则下一场报不上名");
        Assertions.assertTrue(u.pvpEnrollDay.isEmpty(), "结算后目标战斗日必须清空");
        Assertions.assertEquals(day, u.pvpSettledDay, "当日结算标记保留（去重靠它）");
    }

    /** 报名冲着别的战斗日时，这一天不得结算（`settlePvpBattle` 的 `pvpEnrollDay` 闸门）。 */
    @Test
    public void settleSkippedWhenEnrollTargetsAnotherDay() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "错日");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "错会", "direct");
        drain(ch);
        String day = PlayerDumpService.now().substring(0, 10);
        u.pvpDay = day;
        u.pvpEnrolled = true;
        u.pvpEnrollDay = day + "-next";
        u.growth = 100;
        u.crystal = 500;
        owner.mails.clear();
        world.saveUnions();

        union.settlePvpBattle(u, day, "test");
        Assertions.assertTrue(owner.mails.isEmpty(), "报名不是冲今天来的，今天不得结算");
        Assertions.assertEquals(100, u.growth);
        Assertions.assertEquals(500, u.crystal);
        Assertions.assertTrue(u.pvpSettledDay.isEmpty(), "被闸门拦下时不得写当日结算标记");
        Assertions.assertTrue(u.pvpEnrolled, "报名保持，等它真正报的那一场");
    }

    /** 1554/1556/1557 → 1951/1953/1954：排行榜与公会简报。 */
    @Test
    public void pvpRankAndBriefLists() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "榜会", "direct");

        union.onPvpFightPowerRank(session, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_POWER_RANK, 1, new byte[0]));
        GamePacket power = pick(drain(ch), MsgIds.S2C_UNION_PVP_FIGHT_POWER_RANK_RET);
        Assertions.assertNotNull(power);
        Pb.Fields pf = Pb.read(power.body);
        Assertions.assertEquals(1, pf.getInt(1, 0), "只有自己的公会，名次 1");
        Assertions.assertTrue(pf.getInt(2, 0) > 0, "myUnionValue");
        Assertions.assertEquals(1, pf.getBytesList(3).size());
        Pb.Fields row = Pb.read(pf.getBytesList(3).get(0));
        Assertions.assertEquals(u.id, row.getString(1), "CCMsgUnionRankListItem.1 guid");

        u.pvpWinTimes = 2;
        u.pvpFailTimes = 1;
        union.onPvpFightRank(session, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_RANK, 2, new byte[0]));
        GamePacket fight = pick(drain(ch), MsgIds.S2C_UNION_PVP_FIGHT_RANK_RET);
        Assertions.assertNotNull(fight);
        Pb.Fields ff = Pb.read(fight.body);
        Assertions.assertEquals(1, ff.getInt(1, 0), "myUnionRank");
        Assertions.assertEquals(2, ff.getInt(2, 0), "mywinTimes");
        Assertions.assertEquals(1, ff.getInt(3, 0), "myfailTimes");
        Assertions.assertEquals(1, ff.getBytesList(4).size(), "1953 f4 rankList");

        union.onPvpUnionBriefFightPower(session, new GamePacket(MsgIds.C2S_UNION_PVP_BRIEF_FIGHT_POWER, 3,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        GamePacket brief = pick(drain(ch), MsgIds.S2C_UNION_PVP_BRIEF_FIGHT_POWER_RET);
        Assertions.assertNotNull(brief);
        Pb.Fields bf = Pb.read(brief.body);
        Assertions.assertEquals(u.id, Pb.read(bf.getBytes(1)).getString(1), "1954 f1 rankItem.guid");
        Assertions.assertEquals("会长", bf.getString(2), "1954 f2 owner");
        Assertions.assertEquals(1, bf.getInt(3, 0), "1954 f3 memberCnt");
    }

    /**
     * 1557/1558 的「找不到公会」分支**必须仍写 f1 `rankItem`**。
     *
     * <p>proto 侧 {@code CCMsgUnionBriefInfo.rankItem} 是 {@code IsRequired = false} +
     * {@code [DefaultValue(null)]} 且无惰性初始化 ⇒ 缺字段时 getter 返回 null；客户端
     * {@code UnionInfoTips.cs:74} 第一句就是 {@code info.rankItem.icon.Split(' ', '\t', '/')}，
     * {@code :74-101} 共 14 处 {@code info.rankItem.*} 全无 null 检查 ⇒ 不写 f1 必 NRE、
     * 公会信息面板打不开（1954/1955 共用该 handler）。</p>
     *
     * <p>可达路径：客户端 {@code RankListMainDialog.cs:1407}（战力榜）/ {@code :1421}（成长值榜）
     * 的越界保护写成 {@code listRankInfo.Count < currentClick}（应为 {@code <=}），
     * {@code IndexOf} 返回 -1 时**不** return，会把空 guid 发出来；假服重启清空
     * {@code world.unions()} 后客户端缓存的排名列表也会带脏 guid。</p>
     */
    @Test
    public void pvpBriefOnUnknownUnionStillWritesRankItem() {
        resetWorld();
        PlayerRecord solo = fresh(OWNER, "无会玩家");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(solo, ch);
        Assertions.assertEquals("", solo.guild.id, "前置：该号不属于任何公会");

        union.onPvpUnionBriefFightPower(session, new GamePacket(
                MsgIds.C2S_UNION_PVP_BRIEF_FIGHT_POWER, 1, new byte[0]));
        GamePacket brief = pick(drain(ch), MsgIds.S2C_UNION_PVP_BRIEF_FIGHT_POWER_RET);
        Assertions.assertNotNull(brief, "必须回 1954（客户端点击后只靠回包开面板）");
        Pb.Fields bf = Pb.read(brief.body);
        // 注意：Pb.Fields.getBytes 在字段缺失时返回 new byte[0]（不是 null）⇒ 用 getBytesList 判「写没写」。
        Assertions.assertEquals(1, bf.getBytesList(1).size(),
                "1954 f1 rankItem 必须写：客户端 UnionInfoTips.cs:74 无 null 检查，缺字段会 NRE");
        Pb.Fields ri = Pb.read(bf.getBytesList(1).get(0));
        Assertions.assertEquals(0, ri.getInt(2, -1), "未知公会 rank=0");
        String icon = ri.getString(4);
        Assertions.assertTrue(icon.split("[ \t/]").length >= 2,
                "图标必须能被 UnionInfoTips.cs:74 切成 ≥2 段供 :76 SetIcon(array2[0], array2[1])，实际=" + icon);

        union.onPvpUnionBriefGrowValue(session, new GamePacket(
                MsgIds.C2S_UNION_PVP_BRIEF_GROW_VALUE, 2, new byte[0]));
        GamePacket brief2 = pick(drain(ch), MsgIds.S2C_UNION_PVP_BRIEF_GROW_VALUE_RET);
        Assertions.assertNotNull(brief2, "必须回 1955");
        Assertions.assertEquals(1, Pb.read(brief2.body).getBytesList(1).size(),
                "1955 f1 rankItem 同样必写（与 1954 共用 UnionInfoTips handler）");
    }

    /**
     * 1953 的客户端契约（{@code RankListMainDialog.cs}）：{@code myUnionRank == 0} 显示 100462「未上榜」，
     * {@code rank} 是独立字段且允许并列/跳号（:1064 只在相邻差 > 1 时画分隔线），列表必须按 rank 非降序
     * 且前 3 行真是第 1/2/3 名（:1083-1088 按**下标**取奖杯）。
     *
     * <p>排序规则（用户 m19006 #4 拍板）：**胜场降序 → 公会总战力降序 → guid**。
     * 本用例故意让「败场少」与「战力高」互相矛盾（甲会 0 败但战力低、乙会 2 败但战力高）：
     * 按战力排是乙会在前，按旧的「败场升序」排则是甲会在前，可判别。无战绩公会不入榜。</p>
     */
    @Test
    public void pvpFightRankOrdersTiesAndExcludesZeroRecordUnions() {
        resetWorld();
        PlayerRecord a = fresh(OWNER, "会长甲");
        PlayerRecord b = fresh(MEMBER, "会长乙");
        PlayerRecord c = fresh(RIVAL, "会长丙");
        EmbeddedChannel chA = newChannel();
        EmbeddedChannel chB = newChannel();
        EmbeddedChannel chC = newChannel();
        GameSession sA = bind(a, chA);
        GameSession sB = bind(b, chB);
        GameSession sC = bind(c, chC);
        WorldStore.UnionRecord ua = createUnion(sA, chA, "甲会", "direct");
        WorldStore.UnionRecord ub = createUnion(sB, chB, "乙会", "direct");
        WorldStore.UnionRecord uc = createUnion(sC, chC, "丙会", "direct");
        Assertions.assertNotEquals(ua.id, ub.id);
        Assertions.assertNotEquals(ub.id, uc.id);
        ua.pvpWinTimes = 3;
        ua.pvpFailTimes = 0;
        ub.pvpWinTimes = 3;
        ub.pvpFailTimes = 2;
        // tie-break = 公会总战力（`unionRankValue` 求成员 fightPower 之和，与 1951/1554 同口径）。
        ua.members.get(0).fightPower = 1000;
        ub.members.get(0).fightPower = 2000;
        world.saveUnions();

        union.onPvpFightRank(sA, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_RANK, 1, new byte[0]));
        GamePacket ret = pick(drain(chA), MsgIds.S2C_UNION_PVP_FIGHT_RANK_RET);
        Assertions.assertNotNull(ret);
        Pb.Fields f = Pb.read(ret.body);
        Assertions.assertEquals(2, f.getInt(1, 0), "同胜场按公会总战力排 ⇒ 甲会第 2");
        Assertions.assertEquals(3, f.getInt(2, 0), "mywinTimes");
        Assertions.assertEquals(0, f.getInt(3, 0), "myfailTimes");
        List<byte[]> rows = f.getBytesList(4);
        Assertions.assertEquals(2, rows.size(), "无战绩的丙会不得入榜");
        Pb.Fields r0 = Pb.read(rows.get(0));
        Pb.Fields r1 = Pb.read(rows.get(1));
        Assertions.assertEquals(ub.id, r0.getString(1), "战力高的乙会第一（败场多也照样在前）");
        Assertions.assertEquals(ua.id, r1.getString(1), "甲会第二");
        Assertions.assertEquals(1, r0.getInt(2, 0), "rank 是独立字段");
        Assertions.assertEquals(2, r1.getInt(2, 0));
        Assertions.assertTrue(r0.getInt(2, 0) <= r1.getInt(2, 0), "列表按 rank 非降序");

        // 无战绩公会查榜：myUnionRank 必须是 0（客户端才会显示 100462「未上榜」）。
        union.onPvpFightRank(sC, new GamePacket(MsgIds.C2S_UNION_PVP_FIGHT_RANK, 2, new byte[0]));
        GamePacket retC = pick(drain(chC), MsgIds.S2C_UNION_PVP_FIGHT_RANK_RET);
        Assertions.assertNotNull(retC);
        Pb.Fields cf = Pb.read(retC.body);
        Assertions.assertEquals(0, cf.getInt(1, 0), "无战绩 ⇒ myUnionRank 0");
        Assertions.assertEquals(2, cf.getBytesList(4).size());
    }

    /**
     * 作战室拍卖三封邮件：4 拍中（结算时发，附件 = 战利品）/ 5 被顶价（顶价当下即时退勇气币）/
     * 6 公会解散时未结算的竞拍款项退还（`Sys_MailConfig` 第 6 行「你在公会拍卖中竞拍的款项退还如下。」无 `{0}`）。
     */
    @Test
    public void auctionMailsForWinOutbidAndDestroy() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);
        WorldStore.UnionRecord u = createUnion(session, ch, "拍卖会", "direct");
        PlayerRecord member = fresh(MEMBER, "会员");
        WorldStore.Member mem = new WorldStore.Member();
        mem.playerId = member.playerId;
        mem.account = member.account;
        mem.name = member.roleName;
        mem.job = "member";
        u.members.add(mem);
        member.guild.id = u.id;
        member.guild.name = u.name;
        member.guild.job = "member";

        WorldStore.AuctionLot lot = new WorldStore.AuctionLot();
        lot.dropId = 9001;
        lot.ori = "TS102";
        lot.count = 3;
        lot.price = 0;
        u.auctionLots.add(lot);
        // ensureAuction 首次调用会按日切清空货架（`auctionDay` 为空即视为跨日）⇒ 先把日戳打上
        u.auctionDay = PlayerDumpService.now().substring(0, 10);
        owner.guild.courageCoin = 5000;
        member.guild.courageCoin = 5000;
        owner.mails.clear();
        member.mails.clear();
        world.saveUnions();

        // 会员出价 100 → 会长出价 200 顶掉 → 会员当场收到模板 5 + 退还 100 勇气币
        EmbeddedChannel chM = newChannel();
        GameSession sessionM = bind(member, chM);
        union.onJingPai(sessionM, new GamePacket(MsgIds.C2S_JINGPAI, 1,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 9001);
                    Pb.int32Always(o, 2, 100);
                })));
        drain(chM);
        Assertions.assertEquals(4900, member.guild.courageCoin, "出价扣勇气币");
        Assertions.assertTrue(member.mails.isEmpty(), "首次出价没有被顶，不发退款邮");

        union.onJingPai(session, new GamePacket(MsgIds.C2S_JINGPAI, 2,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, 9001);
                    Pb.int32Always(o, 2, 200);
                })));
        drain(ch);
        // 被顶价不再内联退款（改走邮件附件，MailService.claim 时才入账；内联 + 附件 = 双倍到账，
        // 两个号互抬价即可凭空印勇气币）
        Assertions.assertEquals(4900, member.guild.courageCoin, "被顶价不内联退款");
        Assertions.assertEquals(1, member.mails.size(), "被顶价发一封模板 5");
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_AUCTION_REFUND, member.mails.get(0).mailType,
                "5 竞标失败退款");
        // 正文 {0} = 战利品显示名（GoodsList col2「红色时光石Ⅱ」）+ 数量，不再是 ori
        Assertions.assertEquals(economy.goodsDisplayName("TS102") + "x3",
                member.mails.get(0).paras.get(0), "正文 {0}=战利品显示名");
        Assertions.assertEquals(100, member.mails.get(0).courageCoin,
                "退款 100 走邮件附件（领取时才入账）");
        Assertions.assertEquals(4800, owner.guild.courageCoin, "会长扣 200");
        Assertions.assertEquals(200, lot.price);
        Assertions.assertEquals(owner.playerId, lot.topBidderId);

        // 结算 → 会长收到模板 4（附件 = 战利品），货架清空
        owner.mails.clear();
        union.settleAuctionLots(u, "test");
        Assertions.assertTrue(u.auctionLots.isEmpty(), "结算后清空货架");
        Assertions.assertEquals(1, owner.mails.size());
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_AUCTION_WIN, owner.mails.get(0).mailType,
                "4 战利品拍卖胜出");
        Assertions.assertFalse(owner.mails.get(0).items.isEmpty(), "附件 = 拍中的战利品");

        // 解散公会：未结算的竞拍款项退还给出价者（模板 6）
        WorldStore.AuctionLot pending = new WorldStore.AuctionLot();
        pending.dropId = 9002;
        pending.ori = "TS103";
        pending.count = 1;
        pending.price = 300;
        pending.topBidder = member.roleName;
        pending.topBidderId = member.playerId;
        u.auctionLots.add(pending);
        member.mails.clear();
        world.saveUnions();
        union.onDestroy(session, new GamePacket(MsgIds.C2S_DESTROY_UNION, 3, new byte[0]));
        drain(ch);
        Assertions.assertEquals(1, member.mails.size(), "解散时退还未结算的竞拍款项");
        Assertions.assertEquals(MailService.MAIL_TYPE_UNION_REFUND, member.mails.get(0).mailType, "6 公会退款");
        Assertions.assertEquals(300, member.mails.get(0).courageCoin);
        Assertions.assertTrue(member.mails.get(0).paras.isEmpty(), "第6行没有 {0}");
        Assertions.assertTrue(world.findUnion(u.id) == null, "公会已解散");
    }

    /**
     * 公会店（{@code EShopType 4}）走普通商店协议：货架来自 {@code GoodsList} col10「产出位置 = 4」+
     * col9「显示位置 = 4 进阶」，售价用 col8「公会拍卖基础价格（勇气币）」当兄弟币价，
     * 购买扣的是个人兄弟币（{@code rec.guild.brotherCoin}），不是钻石/金币。
     */
    @Test
    void guildShopSellsAtGoodsListPrice() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        owner.guild.brotherCoin = 100000;
        // target/test-data 跨轮持久：上一次用例买过的格子会留 sellOut=true，必须清掉货架重开
        owner.shops.clear();
        store.save(owner);
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(owner, ch);

        shop.onShopInfo(session, new GamePacket(MsgIds.C2S_SHOP_INFO, 0,
                Pb.write(o -> Pb.int32(o, 1, 4))));
        GamePacket info = pick(drain(ch), MsgIds.S2C_SHOP_INFO);
        Assertions.assertNotNull(info, "公会店必须回 1702");
        Pb.Fields f = Pb.read(info.body);
        Assertions.assertEquals(4, f.getInt(1, 0), "1702 f1 是商店类型");

        // 货架 8 格（ShopCommom.txt 公会商店栏位 8），逐格核对「价 = GoodsList col8」
        List<byte[]> fields = f.getBytesList(3);
        Assertions.assertEquals(8, fields.size(), "公会商店栏位 8");
        int slotIndex = -1;
        String slotOri = "";
        int slotPrice = 0;
        int slotCount = 0;
        for (int i = 0; i < fields.size(); i++) {
            Pb.Fields one = Pb.read(fields.get(i));
            String ori = one.getString(1);
            int base = economy.auctionPrice(ori);
            if (base > 0) {
                Assertions.assertEquals(base, one.getInt(3, 0), ori + " 货架价必须等于 GoodsList col8");
            } else {
                // col8 没填基价的行（少数老货单）才允许退回「品质*12 + 金币价/120」推算
                Assertions.assertTrue(one.getInt(3, 0) > 0, ori + " 无 col8 时也必须算出正价");
            }
            if (slotIndex < 0 && !one.getBool(5)) {
                slotIndex = i;
                slotOri = ori;
                slotPrice = one.getInt(3, 0);
                slotCount = one.getInt(2, 1);
            }
        }
        Assertions.assertTrue(slotIndex >= 0, "公会店必须有货");
        // 池子里必须有 col10「产出位置 = 4」那 4 件（真服玩家点名的限定英雄碎片/翅膀碎片）
        List<com.sao.fakeserver.table.EconomyTables.ShopOffer> pool = economy.shopOffers(4);
        boolean hasKen = false;
        for (com.sao.fakeserver.table.EconomyTables.ShopOffer o : pool) {
            if ("GOODS118".equals(o.ori)) {
                hasKen = true;
            }
        }
        Assertions.assertTrue(hasKen, "公会店池子必须含产出位置=4 的雷肯碎片");

        int before = owner.guild.brotherCoin;
        int goodsBefore = goodsCount(owner, slotOri);
        int idx = slotIndex;
        shop.onBuyShop(session, new GamePacket(MsgIds.C2S_SHOP_BUY, 0, Pb.write(o -> {
            Pb.int32(o, 1, 4);
            Pb.int32(o, 2, idx);
        })));
        drain(ch);
        Assertions.assertEquals(before - slotPrice, owner.guild.brotherCoin,
                "公会店扣兄弟币，扣量 = 货架价");
        Assertions.assertEquals(goodsBefore + Math.max(1, slotCount), goodsCount(owner, slotOri),
                "买到的 " + slotOri + " 必须入包");
    }

    /**
     * 第二轮 D6 人工复核落地的修复：1537 厨师属性叠加、1935 训练完成包体
     * （{@code CCMsgNotifyWJXunLiangFinishResult} 而非 1934 形体）、1547 刷新次数上限、
     * 1552 非法据点号、1958 攻占据点数（不再读恒 0 的计数器）。
     */
    @Test
    public void reauditFixesKitchenTrainRefreshAndPvpCounters() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        PlayerRecord rivalRec = fresh(RIVAL, "对手");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        EmbeddedChannel rivalCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        GameSession rivalSession = bind(rivalRec, rivalCh);

        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "复核会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        WorldStore.UnionRecord rival = createUnion(rivalSession, rivalCh, "对手会", "direct");
        drain(ownerCh);
        drain(memberCh);
        drain(rivalCh);

        // ---- 1537：厨师（职业 3）属性 15 点体力必须叠加到厨房等级档位上
        // 建筑类型 2 = 厨房（EUnionBuildingType），职业 3 = 厨师（UnionWJSubsidiary 职业类别）——
        // 两套编号不同，登记必须放在 employersOf(2) 才会被查职业 3 的属性表。
        WorldStore.Employer chef = new WorldStore.Employer();
        chef.playerId = member.playerId;
        chef.account = member.account;
        chef.name = member.roleName;
        chef.wjIndex = 18;
        chef.wjLevel = 40;
        chef.fightPower = 1000;
        chef.hiredBy = owner.playerId;
        u.employersOf(2).add(chef);
        world.saveUnions();
        owner.stamina = 0;
        owner.economy.kitchenStaminaLeft = 1;
        store.save(owner);
        union.onKitchen(ownerSession, new GamePacket(MsgIds.C2S_KITCHEN, 2, Pb.write(o -> {
            Pb.int32Always(o, 1, member.playerId);
            Pb.int32Always(o, 2, 18);
        })));
        Assertions.assertEquals(unionCfg.kitchenStamina(1) + 15, owner.stamina,
                "1537 体力 = 厨房等级档位 + 厨师属性(UnionWJSubsidiary 第7列 15)");
        drain(ownerCh);

        // ---- 1935：训练到点必须发 CWJXunLianResult 形体（f1 嵌套消息）
        // 建筑类型 3 = 训练场，职业 1 = 教练。
        WorldStore.Employer trainer = new WorldStore.Employer();
        trainer.playerId = member.playerId;
        trainer.account = member.account;
        trainer.wjIndex = 18;
        trainer.fightPower = 1000;
        trainer.hiredBy = owner.playerId;
        u.employersOf(3).add(trainer);
        world.saveUnions();
        PlayerRecord.Economy.TrainSlot slot = new PlayerRecord.Economy.TrainSlot();
        slot.wjIndex = owner.mainHeroIndex;
        slot.type = 0;
        slot.employerGuid = member.playerId;
        slot.employerWj = 18;
        slot.leftSec = 1;
        slot.startedAt = System.currentTimeMillis() - (unionCfg.trainSec() + 5L) * 1000L;
        owner.economy.trainSlots.add(slot);
        store.save(owner);
        union.onTrainInfo(ownerSession, new GamePacket(MsgIds.C2S_TRAIN_INFO, 3, new byte[0]));
        List<GamePacket> trainOut = drain(ownerCh);
        GamePacket fin = pick(trainOut, MsgIds.S2C_TRAIN_FINISH);
        Assertions.assertNotNull(fin, "训练到点必须推 1935");
        Pb.Fields result = Pb.read(Pb.read(fin.body).getBytes(1));
        Assertions.assertEquals(owner.mainHeroIndex, result.getInt(1, 0), "f1 wjIndex");
        Assertions.assertTrue(result.getInt(4, 0) > 0, "f1 totalExp > 0");
        Assertions.assertTrue(owner.economy.trainSlots.isEmpty(), "训练结束清坑");
        // 601 必须补推：客户端经验/等级只认 601（`MainPlayer.cs:1012-1027`），1935 只当弹窗文案。
        GamePacket exp601 = pick(trainOut, MsgIds.S2C_WUJIANG_ATTRI_UPDATE);
        Assertions.assertNotNull(exp601, "训练到点必须推 601（否则武将面板经验条不刷新）");
        Pb.Fields wj601 = Pb.read(exp601.body);
        Assertions.assertEquals(1, wj601.getInt(2, 0), "601 f2 type = 1（CUREXP）");
        Assertions.assertTrue(wj601.getInt(3, 0) > 0, "601 f3 新经验值");

        // ---- 1547：刷新次数上限（UnionMaJiuBase.txt「单次活动刷新次数」= 2）+ 消耗跟表 = 0（用户 m19522 #2）
        // 注意 ensureMajiuDay 跨日会把 majiuResetTimes 清零，所以先刷到上限再刷第三次。
        u.crystal = 1000;
        world.saveUnions();
        int cap = unionCfg.majiuResetTimes();
        for (int i = 0; i < cap; i++) {
            union.onRefreshRaid(ownerSession, new GamePacket(MsgIds.C2S_REFRESH_RAID, 4, new byte[0]));
        }
        Assertions.assertEquals(cap, owner.economy.majiuResetTimes, "刷满上限");
        Assertions.assertEquals(1000, u.crystal, "表值为 0 ⇒ 刷新免费，公会晶石不变");
        union.onRefreshRaid(ownerSession, new GamePacket(MsgIds.C2S_REFRESH_RAID, 4, new byte[0]));
        Assertions.assertEquals(cap, owner.economy.majiuResetTimes, "超过刷新次数上限不得再计次");
        Assertions.assertEquals(1000, u.crystal, "超过刷新次数上限不得再扣晶石");
        drain(ownerCh);

        // ---- 1552：不存在的据点号不得建阵容（否则脏据点会被 1950/1959/1960 当真实据点渲染）
        union.onPvpUpdateDefFormation(ownerSession,
                new GamePacket(MsgIds.C2S_UNION_PVP_UPDATE_DEF_FORMATION, 5, Pb.write(o -> {
                    Pb.int32Always(o, 1, 99);
                    Pb.int32Always(o, 2, 1);
                    Pb.int32Always(o, 3, owner.playerId);
                    Pb.int32Always(o, 4, 1);
                })));
        Assertions.assertTrue(u.pvpFormationsOf(99).isEmpty(), "非法据点号不得写存档");
        drain(ownerCh);

        // ---- 1958/1959：f1 selfAttackedDefPoints = **我方**攻陷数、f2 targetAttackedDefPoints = 敌方攻陷数
        // （`CCMsgRequestMyUnionPvPDefPointBriefInfo_Ret`/`...LeftFormationCnt_Ret` 字段名；客户端
        //  `GongHuiZhanBaseUI.cs:219/228` 把 f1 拼进 Code.txt 100760「我方攻陷： 」、f2 拼进 100761「敌方攻陷： 」）
        u.pvpCapturedPoints.clear();
        u.pvpCapturedPoints.add(Integer.valueOf(1));
        u.pvpCapturedPoints.add(Integer.valueOf(2));
        u.pvpDay = PlayerDumpService.now().substring(0, 10);
        u.pvpRivalId = rival.id;
        rival.pvpCapturedPoints.clear();
        rival.pvpCapturedPoints.add(Integer.valueOf(3));
        world.saveUnions();
        union.onPvpTargetDefPointBrief(ownerSession,
                new GamePacket(MsgIds.C2S_UNION_PVP_TARGET_DEF_POINT_BRIEF, 6, new byte[0]));
        Pb.Fields brief = Pb.read(pick(drain(ownerCh),
                MsgIds.S2C_UNION_PVP_TARGET_DEF_POINT_BRIEF_RET).body);
        Assertions.assertEquals(2, brief.getInt(1, -1), "f1 selfAttackedDefPoints = 我方攻占 1、2");
        Assertions.assertEquals(1, brief.getInt(2, -1), "f2 targetAttackedDefPoints = 敌方攻占 3");
        Assertions.assertEquals("对手会", brief.getString(4), "f4 targetUnionName");
    }

    /** 背包里某 ori 的数量（找不到 0）。 */
    private static int goodsCount(PlayerRecord rec, String ori) {
        if (rec.bag == null) {
            return 0;
        }
        Integer n = rec.bag.get(ori);
        return n == null ? 0 : n.intValue();
    }

    /** 发 1552：默认用防守预设 1（type 24）；{@code playerGuid=0} 表示下阵。 */
    private void defFormation(GameSession session, EmbeddedChannel ch, int point, int index, int playerGuid) {
        defFormation(session, ch, point, index, playerGuid, 24);
    }

    /** 发 1552，显式指定 {@code DefFormationType}（24..28 = 防守预设 1..5，下阵时客户端固定发 24）。 */
    private void defFormation(GameSession session, EmbeddedChannel ch, int point, int index, int playerGuid,
                              int formationType) {
        union.onPvpUpdateDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_UPDATE_DEF_FORMATION, 1,
                Pb.write(o -> {
                    Pb.int32Always(o, 1, point);
                    Pb.int32Always(o, 2, index);
                    Pb.int32Always(o, 3, playerGuid);
                    Pb.int32Always(o, 4, formationType);
                })));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_UNION_PVP_UPDATE_DEF_FORMATION_RET);
        Assertions.assertNotNull(ret, "1552 必须回 1949");
        Assertions.assertEquals(0, ret.body.length, "1949 是纯空包");
    }

    /**
     * 发 1562（空体）→ 1959，返回每个据点的 {@code CMsgOnePointLeftDefFormationCnt} 行
     * （f1 defPointIndex / f2 leftFormationCnt / f3 isBeAttacked）。
     */
    private List<Pb.Fields> leftBoard(GameSession session, EmbeddedChannel ch, int seq) {
        union.onPvpLeftDefFormation(session, new GamePacket(MsgIds.C2S_UNION_PVP_LEFT_DEF_FORMATION, seq,
                new byte[0]));
        GamePacket left = pick(drain(ch), MsgIds.S2C_UNION_PVP_LEFT_DEF_FORMATION_RET);
        Assertions.assertNotNull(left, "1562 必须回 1959");
        List<Pb.Fields> rows = new ArrayList<>();
        for (byte[] row : Pb.read(left.body).getBytesList(3)) {
            rows.add(Pb.read(row));
        }
        Assertions.assertFalse(rows.isEmpty(), "1959 f3 leftCnt 不能为空");
        return rows;
    }

    /**
     * 1564 闸门（用户 m24587 #4）要求世界里有**另一个**公会（{@code rivalUnion} 才有候选）、
     * 目标据点可攻占（只有据点 0 无前置）、且该据点上有活着的防守队。返回对手公会。
     */
    private WorldStore.UnionRecord deployRival(int point, int index) {
        PlayerRecord rival = fresh(RIVAL, "乙会长");
        EmbeddedChannel chR = newChannel();
        GameSession sR = bind(rival, chR);
        WorldStore.UnionRecord uR = createUnion(sR, chR, "守阵会" + point + "-" + index, "direct");
        uR.pvpFormationsOf(point).add(formation(rival, point, index));
        world.saveUnions();
        return uR;
    }

    /** 给武将穿一件装备（1961 f16 的来源）。 */
    private static void equipOn(PlayerRecord rec, String ori, String heroGuid) {
        PlayerRecord.Equipment eq = new PlayerRecord.Equipment();
        eq.id = "test-" + ori;
        eq.ori = ori;
        eq.owner = heroGuid;
        eq.level = 1;
        rec.equipments.add(eq);
    }

    private static WorldStore.PvpFormation formation(PlayerRecord rec, int point, int index) {
        WorldStore.PvpFormation f = new WorldStore.PvpFormation();
        f.point = point;
        f.formationIndex = index;
        f.playerId = rec.playerId;
        f.account = rec.account;
        f.playerName = rec.roleName;
        f.playerLevel = rec.level;
        f.playerResId = rec.mainHeroIndex;
        f.formationType = 1;
        f.fightPower = 1000;
        WorldStore.PvpWjBrief w = new WorldStore.PvpWjBrief();
        w.job = 1;
        w.index = rec.mainHeroIndex;
        w.level = 40;
        f.wjs.add(w);
        return f;
    }

    /**
     * 拍卖 20 点结算的去重键必须**落档**（{@code UnionRecord.auctionSettledDay}）。
     *
     * <p>改前是 {@code UnionService} 的进程内存 {@code volatile String auctionSettledDay}：
     * 21:00 击杀入架 → 21:10 重启 → 21:11 分钟钩子就把货架结算掉（还没人出价，直接流拍），
     * 掉落凭空消失。现在按公会落档，且**空货架也要打标记** —— 不标记的话，重启后当天
     * 新入架的掉落照样会被提前结算。
     */
    @Test
    public void auctionSettleDayIsPersistedPerUnionAndMarksEmptyShelf() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord second = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel secondCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession secondSession = bind(second, secondCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "拍卖落档会", "direct");
        WorldStore.UnionRecord u2 = createUnion(secondSession, secondCh, "空货架会", "direct");
        owner.mails.clear();
        store.save(owner);

        // Union.txt「作战室拍卖结算时间（24小时制小时）」= 20 点。
        pinClock(19 * 3600);
        union.settleAuctions("test");
        Assertions.assertTrue(u.auctionSettledDay.isEmpty(), "未到 20 点不得结算");

        WorldStore.AuctionLot lot = new WorldStore.AuctionLot();
        lot.dropId = 1;
        lot.ori = "GOODS118";
        lot.count = 1;
        lot.price = 40;
        lot.topBidder = owner.roleName;
        lot.topBidderId = owner.playerId;
        u.auctionLots.add(lot);
        world.saveUnions();

        pinClock(20 * 3600);
        union.settleAuctions("test");
        String today = PlayerDumpService.now().substring(0, 10);
        Assertions.assertEquals(today, u.auctionSettledDay, "结算日必须落档");
        Assertions.assertTrue(u.auctionLots.isEmpty(), "20 点结算清空货架");
        Assertions.assertEquals(1, owner.mails.size(), "拍中者收 mailType 4");
        Assertions.assertEquals(today, u2.auctionSettledDay,
                "空货架的公会也要打标记，否则重启后当天新入架的掉落会被提前结算");
        Assertions.assertTrue(u2.auctionLots.isEmpty());

        // 21:00 又击杀入架，21:10 重启后分钟钩子再跑：同一公会当天不得二次结算。
        WorldStore.AuctionLot lot2 = new WorldStore.AuctionLot();
        lot2.dropId = 2;
        lot2.ori = "GOODS118";
        lot2.count = 1;
        lot2.price = 40;
        lot2.topBidder = owner.roleName;
        lot2.topBidderId = owner.playerId;
        u.auctionLots.add(lot2);
        world.saveUnions();
        union.settleAuctions("test");
        Assertions.assertFalse(u.auctionLots.isEmpty(), "当天已结算过的公会不得再结算");
        Assertions.assertEquals(1, owner.mails.size(), "不得多发一份 20 点结算邮件");
    }

    /**
     * 1546 劫镖排行榜（以及 1545/1548 的名次档）只能算**同一次活动日**的玩家。
     *
     * <p>改前 {@code raidRankOf} 与 1546 直接拿全服 {@code raidJinbiTotal} 排序、不按
     * {@code economy.majiuDay} 过滤：昨天劫过镖的玩家还挂着昨天的数据，榜上全是昨天的人，
     * 名次档（{@code UnionMaJiuBase.txt} 第 13 行 `1:400_2:300_3:200_10:150_40:100`）也被挤歪。
     * 另外 1546 改前根本不滚天（{@code ensureMajiuDay} 只在其他马厩入口调），请求者自己都是昨天的。
     */
    @Test
    public void raidRankOnlyCountsSameMajiuDay() {
        resetWorld();
        PlayerRecord me = fresh(OWNER, "会长");
        PlayerRecord yesterday = fresh(MEMBER, "昨天的人");
        EmbeddedChannel ch = newChannel();
        GameSession session = bind(me, ch);
        String today = PlayerDumpService.now().substring(0, 10);
        // 其他账号可能带着别的用例留下的当天数据：统一推到「很久以前」。
        for (PlayerRecord p : store.all()) {
            if (p.playerId == me.playerId) {
                continue;
            }
            p.economy.majiuDay = "1999-01-01";
            p.economy.raidJinbiTotal = 0;
            p.economy.raidXdbTotal = 0;
            p.economy.raidJinShiTotal = 0;
            store.save(p);
        }
        yesterday.economy.majiuDay = "2000-01-01";
        yesterday.economy.raidJinbiTotal = 9999999;
        store.save(yesterday);
        me.economy.majiuDay = today;
        me.economy.raidJinbiTotal = 100;
        store.save(me);

        union.onRaidRank(session, new GamePacket(MsgIds.C2S_RAID_RANK, 1, new byte[0]));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_RAID_RANK);
        Assertions.assertNotNull(ret);
        List<byte[]> rows = Pb.read(ret.body).getBytesList(1);
        boolean hasYesterday = false;
        boolean hasMe = false;
        for (byte[] r : rows) {
            int guid = Pb.read(r).getInt(1, 0);
            if (guid == yesterday.playerId) {
                hasYesterday = true;
            }
            if (guid == me.playerId) {
                hasMe = true;
            }
        }
        Assertions.assertFalse(hasYesterday, "昨天活动日的玩家不得进今天的榜");
        Assertions.assertTrue(hasMe, "请求者本人必须在榜上");
    }

    /**
     * 踢人 / 退会 / 解散都必须清掉指向离会者的残留引用。
     *
     * <p>三处原先只清成员表与玩家档的 {@code guild.*}：
     * <ul>
     *   <li>{@code u.employers} 里 {@code hiredBy == 离会者} 的条目仍在 —— 他继续占着建筑格位领工资，
     *       1520/1917 还列着他（训练场教练本人那条也是 {@code hiredBy = 教练本人}）；</li>
     *   <li>其他成员训练坑位的教练绑定 {@code employerGuid == 离会者} 仍在；</li>
     *   <li>他自己的镖车状态还在 —— {@code majiuView} 仍把他列成可拦截目标。</li>
     * </ul>
     */
    @Test
    public void leavingMemberArtifactsAreCleanedOnKick() {
        resetWorld();
        PlayerRecord owner = fresh(OWNER, "会长");
        PlayerRecord member = fresh(MEMBER, "成员");
        EmbeddedChannel ownerCh = newChannel();
        EmbeddedChannel memberCh = newChannel();
        GameSession ownerSession = bind(owner, ownerCh);
        GameSession memberSession = bind(member, memberCh);
        WorldStore.UnionRecord u = createUnion(ownerSession, ownerCh, "清理会", "direct");
        union.onJoin(memberSession, new GamePacket(MsgIds.C2S_JOIN_UNION, 1,
                Pb.write(o -> Pb.stringAlways(o, 1, u.id))));
        drain(memberCh);

        WorldStore.Employer e = new WorldStore.Employer();
        e.playerId = member.playerId;
        e.account = MEMBER;
        e.name = "成员";
        e.wjIndex = 5;
        e.hiredBy = member.playerId;
        e.slotIndex = 0;
        u.employers.computeIfAbsent(6, k -> new ArrayList<>()).add(e);
        PlayerRecord.Economy.TrainSlot slot = new PlayerRecord.Economy.TrainSlot();
        slot.wjIndex = 3;
        slot.employerGuid = member.playerId;
        slot.employerWj = 5;
        slot.startedAt = System.currentTimeMillis();
        owner.economy.trainSlots.add(slot);
        PlayerRecord.Economy.EscortCart leaverCart = new PlayerRecord.Economy.EscortCart();
        leaverCart.cartId = "cart-1";
        leaverCart.targetId = 3;
        leaverCart.sentAt = System.currentTimeMillis();
        member.economy.escortCarts.add(leaverCart);
        member.economy.raidTargetCartId = leaverCart.cartId;
        store.save(owner);
        store.save(member);
        world.saveUnions();

        union.onKickMember(ownerSession, new GamePacket(MsgIds.C2S_KICK_UNION_MEMBER, 2,
                Pb.write(o -> Pb.int32Always(o, 1, member.playerId))));
        drain(ownerCh);
        drain(memberCh);

        for (List<WorldStore.Employer> list : u.employers.values()) {
            for (WorldStore.Employer x : list) {
                Assertions.assertNotEquals(member.playerId, x.hiredBy, "离会者的佣兵/教练格位必须清掉");
            }
        }
        Assertions.assertEquals(0, owner.economy.trainSlots.get(0).employerGuid, "教练绑定必须解除");
        Assertions.assertEquals(3, owner.economy.trainSlots.get(0).wjIndex, "训练本身不取消，只解绑教练");
        Assertions.assertTrue(member.economy.escortCarts.isEmpty(), "离会者的在途镖车必须清掉");
        Assertions.assertEquals("", member.economy.raidTargetCartId, "离会者的劫镖目标车必须清掉");
    }

    // ------------------------------------------------------------------ 脚手架

    private void resetWorld() {
        world.unions().clear();
        world.saveUnions();
        // 时钟锚点是单例服务上的字段，用例之间必须复位，否则上一个用例钉的窗口会污染本用例。
        union.clockSecOverride = -1;
    }

    /** 把「当日秒数」钉住，用于验证时间窗（发镖/劫镖/公会战报名）。 */
    private void pinClock(int secOfDay) {
        union.clockSecOverride = secOfDay;
    }

    private EmbeddedChannel newChannel() {
        EmbeddedChannel ch = new EmbeddedChannel();
        channels.add(ch);
        return ch;
    }

    private GameSession bind(PlayerRecord rec, EmbeddedChannel ch) {
        GameSession session = new GameSession(ch);
        session.setAccount(rec.account);
        session.setPlayer(rec);
        hub.bind(session);
        return session;
    }

    private WorldStore.UnionRecord createUnion(GameSession session, EmbeddedChannel ch, String name,
                                               String joinType) {
        union.onCreate(session, new GamePacket(MsgIds.C2S_CREATE_UNION, 0, Pb.write(o -> {
            Pb.stringAlways(o, 1, name);
            Pb.stringAlways(o, 2, "icon1");
        })));
        GamePacket ret = pick(drain(ch), MsgIds.S2C_CREATE_UNION_RET);
        Assertions.assertNotNull(ret, "创建公会必须有 1901");
        Assertions.assertTrue(Pb.read(ret.body).getBool(1), "创建公会失败");
        WorldStore.UnionRecord u = world.findUnionByName(name);
        Assertions.assertNotNull(u);
        u.joinType = joinType;
        world.saveUnions();
        return u;
    }

    /**
     * 本轮新落地的「解锁所需议事厅等级」闸门（{@code tables\Union.txt} 六键 → {@code UnionCfg.unlockHallLevel}，
     * 出厂 厨房1/训练场3/商城1/马厩2/作战室1/医院4）。需要用到某建筑的用例，
     * 必须先把议事厅拉到该建筑要求的等级，否则闸门会先拒掉。
     */
    private WorldStore.UnionRecord withHall(WorldStore.UnionRecord u, int level) {
        u.buildings.put(Integer.valueOf(1), Integer.valueOf(level));
        world.saveUnions();
        return u;
    }

    private PlayerRecord fresh(String account, String roleName) {
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            rec = dump.newPlayer(account, store.nextPlayerId(), 18, 1, roleName);
        }
        rec.ensureCollections();
        rec.roleName = roleName;
        rec.level = 40;
        rec.diamond = 5000;
        rec.gold = 1000000;
        PlayerRecord.Hero main = rec.findHeroByIndex(rec.mainHeroIndex);
        if (main != null) {
            main.fightPower = 1000;
        }
        rec.guild.id = "";
        rec.guild.name = "";
        rec.guild.job = "none";
        rec.guild.donateLeft = 10;
        rec.guild.contribution = 0;
        rec.guild.contributionGrowRemainder = 0;
        rec.guild.brotherCoin = 0;
        rec.guild.courageCoin = 0;
        rec.guild.quitUnionAt = 0L;
        rec.guild.bossPlayTimes.clear();
        rec.qkDeadWjs.clear();
        // 1564/1551 选点会写这两个字段（1567 靠它们决定打哪个据点），跨用例必须复位，
        // 否则上一个用例选的据点会把本用例的攻占判定带偏。
        rec.economy.lastPvpPoint = -1;
        rec.economy.lastPvpFormation = -1;
        // 公会战阵容（24..28 防守预设、29 进攻阵容）跨用例必须复位：1950 的候选条数 =
        // 该成员存过几套预设，残留会让「单成员公会只有一条候选」之类的断言随机失败。
        for (int t = PlayerRecord.FORMATION_UNION_PVP_DEF1;
             t <= PlayerRecord.FORMATION_UNION_PVP_DEF1 + 4; t++) {
            rec.formationsByType.remove(Integer.valueOf(t));
        }
        rec.formationsByType.remove(Integer.valueOf(PlayerRecord.FORMATION_UNION_PVP_OFFENSE));
        rec.economy.trainSlots.clear();
        rec.economy.escortCarts.clear();
        rec.economy.raidTargetCartId = "";
        rec.economy.escortRaidLeft = 3;
        rec.economy.raidTargetId = "";
        rec.economy.escortRaidSucTimes = 0;
        rec.economy.escortDefendGold = 0;
        rec.economy.escortDefendUnion = "";
        rec.economy.majiuDay = "";
        rec.economy.majiuResetTimes = 0;
        rec.economy.majiuAwarded = false;
        rec.economy.raidJinbiTotal = 0;
        rec.economy.raidXdbTotal = 0;
        rec.economy.raidJinShiTotal = 0;
        // 建筑收益的基线/累计额跨用例必须复位，否则上个用例领过的钱会把上限判定带偏。
        rec.economy.buildingProfitAt.clear();
        rec.economy.buildingProfitTotal.clear();
        store.save(rec);
        return rec;
    }

    /**
     * 造一辆「今天发出、未领奖」的在途镖车。1543 无车 id，车身份由服务端在 1938 下发；
     * 同一玩家可以同时有多辆（VipCfg.txt 第 27 列「发镖数量」），故 GUID 必须逐车唯一。
     */
    private PlayerRecord.Economy.EscortCart cartOf(PlayerRecord rec, String cartId, int targetId) {
        PlayerRecord.Economy.EscortCart c = new PlayerRecord.Economy.EscortCart();
        c.cartId = cartId;
        c.targetId = targetId;
        c.sentAt = System.currentTimeMillis();
        rec.economy.escortCarts.add(c);
        return c;
    }

    /** 读 1939 f1 ret（客户端 MaJiuBaseLanJieUI.cs:135-158 按它分档弹串）。 */
    private static int raidRet(List<GamePacket> out) {
        GamePacket p = pick(out, MsgIds.S2C_RAID_CART);
        Assertions.assertNotNull(p, "1544 必须回 1939");
        return Pb.read(p.body).getInt(1, -1);
    }

    private static List<GamePacket> drain(EmbeddedChannel ch) {
        List<GamePacket> out = new ArrayList<>();
        GamePacket p;
        int guard = 0;
        while ((p = ch.readOutbound()) != null && guard++ < 64) {
            out.add(p);
        }
        return out;
    }

    private static boolean hasIn(List<GamePacket> list, int msgId) {
        for (GamePacket p : list) {
            if (p.msgId == msgId) {
                return true;
            }
        }
        return false;
    }

    private static GamePacket pick(List<GamePacket> list, int msgId) {
        for (GamePacket p : list) {
            if (p.msgId == msgId) {
                return p;
            }
        }
        return null;
    }
}
