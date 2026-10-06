package com.sao.fakeserver.service;

import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.store.WorldStore;
import com.sao.fakeserver.table.GameTables;
import com.sao.fakeserver.util.GameTime;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 三条「多人环境」回归（用户 2026-10-06 提出）：
 *
 * <ol>
 *   <li><b>武将等级不能超过账号等级</b>：客户端 {@code MainPlayer.cs:1746-1758 WuJiangCanLevelUp}
 *       的判据是 {@code wujiang.Level >= Attribute.mLevel ⇒ false}；服务端
 *       {@link ProgressService#addWjExp(PlayerRecord, PlayerRecord.Hero, int)} 改前只封顶到
 *       表上限（80），账号 10 级也能把武将喂到 80。</li>
 *   <li><b>技能等级上限用武将等级</b>：客户端 {@code MainPlayer.cs:1879/1905/1931/1957}
 *       是 {@code wujiang.Level > skillLevel + <XxxMinuLevel>}，与账号等级无关；
 *       {@link CultivateService#onSkillUp} 改前传的是 {@code rec.level}。</li>
 *   <li><b>开服时间必须是全服唯一锚点</b>：7 日狂欢 / 半月庆典的「第几天」改前按每个账号的
 *       {@code createdAt} 各算一套（{@code KfHappyService.currentDay}），多人环境下同一时间
 *       不同账号看到不同天。现在一律用 {@link WorldStore#openDay()}。</li>
 * </ol>
 *
 * <p>每个断言都钉住客户端侧的对应代码位置，改行为时这个测试必须一起动。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.data-dir=target/test-data/players-hero-growth",
        "sao.world-dir=target/test-data/world-hero-growth",
        "sao.game-port=0",
        "sao.enforce-login=false",
        // 留空：走「文件 → 首次启动」分支，测试里再显式 set 配置验证优先级。
        "sao.open-server-time="
})
public class HeroGrowthAndOpenTimeTest {
    private static final String TIME = "yyyy-MM-dd HH:mm:ss";

    @Autowired
    private PlayerStore store;
    @Autowired
    private PlayerDumpService dump;
    @Autowired
    private ProgressService progress;
    @Autowired
    private CultivateService cultivate;
    @Autowired
    private KfHappyService kfHappy;
    @Autowired
    private GameTables tables;
    @Autowired
    private WorldStore world;
    @Autowired
    private SaoProperties props;
    @Autowired
    private SessionHub hub;

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @AfterEach
    public void cleanup() {
        for (EmbeddedChannel ch : channels) {
            ch.finishAndReleaseAll();
        }
        channels.clear();
    }

    /** 账号 10 级时，武将最多 10 级；账号升级后武将才能继续升。 */
    @Test
    public void heroLevelIsCappedByAccountLevel() {
        PlayerRecord rec = player("hero-cap");
        rec.level = 10;
        PlayerRecord.Hero wj = rec.heroes.get(0);
        wj.level = 1;
        wj.exp = 0;
        store.save(rec);

        progress.addWjExp(rec, wj, 10_000_000);
        Assertions.assertEquals(10, wj.level,
                "账号 10 级时武将不得升过 10 级（客户端 MainPlayer.cs:1746-1758 的不变式）");
        int need = tables.nextWjExp(10);
        if (need > 0) {
            Assertions.assertTrue(wj.exp <= need,
                    "到顶后经验最多存满一条（客户端 WuJiangDetailSystem.cs:88-135 只在校验 Curexp == mNextLevelExp 时拦住发 902）");
        }

        // 账号升级 → 上限跟着放宽，继续喂经验必须能升
        rec.level = 20;
        progress.addWjExp(rec, wj, need > 0 ? need : 10_000_000);
        Assertions.assertTrue(wj.level > 10, "账号等级放宽后武将必须能继续升级");
        Assertions.assertTrue(wj.level <= 20, "仍然不得超过账号等级");
    }

    /** 技能上限用武将等级：账号 80 级、武将 1 级时不能升；武将升到 80 级后才能升。 */
    @Test
    public void skillUpUsesHeroLevelNotAccountLevel() {
        PlayerRecord rec = player("skill-cap");
        rec.level = 80;
        rec.skillPoints = 5;
        rec.gold = 10_000_000;
        PlayerRecord.Hero wj = rec.heroes.get(0);
        wj.level = 1;
        wj.stage = 10;
        wj.setSkill(1, 1);
        store.save(rec);

        GameSession session = bind(rec);
        byte[] body = Pb.write(out -> {
            Pb.string(out, 1, wj.id);
            Pb.int32(out, 2, 1);
        });

        cultivate.onSkillUp(session, new GamePacket(MsgIds.C2S_WUJIANG_SKILL_UP, 21, body));
        Assertions.assertEquals(1, wj.skill(1),
                "武将 1 级时技能不得升级：上限是 wj.level - minu（客户端 MainPlayer.cs:1879），不是账号等级");

        wj.level = 80;
        cultivate.onSkillUp(session, new GamePacket(MsgIds.C2S_WUJIANG_SKILL_UP, 22, body));
        Assertions.assertEquals(2, wj.skill(1), "武将等级够时必须能升（改前用 rec.level 会误放行上面那次）");
    }

    /**
     * 开服时间是全服唯一锚点：配置优先 → 落盘 → 配置清空后从文件沿用；
     * 两个建号日相差 30 天的账号，看到的 6201 {@code ActivityEndTime} 必须完全一致。
     */
    @Test
    public void openServerTimeIsServerWideAnchor() {
        // ① 没配 open-server-time 时：取文件/首次启动，并落盘
        String boot = world.openServerTime();
        Assertions.assertTrue(boot != null && boot.length() >= 10, "开服时间必须可用");
        Path serverJson = Paths.get(props.getWorldDir()).toAbsolutePath().resolve("server.json");
        Assertions.assertTrue(Files.isRegularFile(serverJson),
                "首次取值必须写入 data/world/server.json（全服锚点要能跨重启沿用）: " + serverJson
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        // ② 配置优先
        String configured = GameTime.today().minusDays(4).atTime(9, 30, 0).format(DateTimeFormatter.ofPattern(TIME));
        props.setOpenServerTime(configured);
        Assertions.assertEquals(configured, world.openServerTime(), "sao.open-server-time 必须优先于文件值");
        Assertions.assertEquals(LocalDate.parse(configured.substring(0, 10)), world.openServerDate());
        long expectDay = ChronoUnit.DAYS.between(LocalDate.parse(configured.substring(0, 10)), GameTime.today()) + 1;
        Assertions.assertEquals((int) Math.max(1L, expectDay), world.openDay(),
                "开服当天算第 1 天（4 天前开服 ⇒ 第 5 天）");

        // ③ 配置清空后从文件沿用（等价于「重启后不带配置」）
        props.setOpenServerTime("");
        Assertions.assertEquals(configured, world.openServerTime(),
                "配置清空后必须从 data/world/server.json 沿用同一个开服时间，不能重新按当前时刻开服");

        // ④ 两个建号日不同的账号，开服狂欢结束时间必须相同（这就是「多人不能各算一套」的判据）
        PlayerRecord a = player("open-anchor-a");
        PlayerRecord b = player("open-anchor-b");
        a.createdAt = PlayerDumpService.now();
        b.createdAt = GameTime.now().minusDays(30).format(DateTimeFormatter.ofPattern(TIME));
        store.save(a);
        store.save(b);

        String expectedEnd = world.openServerDate().plusDays(13).atTime(23, 59, 59)
                .format(DateTimeFormatter.ofPattern(TIME));
        Assertions.assertEquals(expectedEnd, sevenDayEndTime(a), "账号 A 的 7 日狂欢结束时间 = 开服日 + 13 天 23:59:59");
        Assertions.assertEquals(expectedEnd, sevenDayEndTime(b),
                "账号 B 建号早 30 天，但结束时间必须与 A 一致 —— 否则每个账号各算一套活动进度");
        Assertions.assertTrue(expectedEnd.compareTo(a.createdAt) != 0 || expectedEnd.compareTo(b.createdAt) != 0,
                "结束时间必须来自开服日，而不是账号 createdAt");
    }

    /* ---------------- 工具 ---------------- */

    /** 6201 第 1 个 day 包的 tag2 = ActivityEndTime。 */
    private String sevenDayEndTime(PlayerRecord rec) {
        GameSession session = bind(rec);
        kfHappy.onSevenDays(session, new GamePacket(MsgIds.C2S_KF_HAPPY_7, 31, new byte[0]));
        EmbeddedChannel ch = channels.get(channels.size() - 1);
        GamePacket p;
        int guard = 0;
        while ((p = ch.readOutbound()) != null && guard++ < 64) {
            if (p.msgId == MsgIds.S2C_KF_HAPPY_7_RET) {
                List<byte[]> days = Pb.read(p.body).getBytesList(1);
                Assertions.assertEquals(7, days.size(), "5401 必须回满 7 条（KfHappyCfg 装配硬约束）");
                return Pb.read(days.get(0)).getString(2);
            }
        }
        Assertions.fail("5401 必须回 6201（客户端 ActivityPropertyMgr.cs:1159 靠它把按钮显出来）");
        return null;
    }

    private PlayerRecord player(String account) {
        PlayerRecord rec = store.get(account);
        if (rec == null) {
            rec = dump.newPlayer(account, store.nextPlayerId(), props.getDefaultWujiangIndex(), 1, account);
        }
        rec.ensureCollections();
        Assertions.assertFalse(rec.heroes.isEmpty(), "测试账号必须有武将：" + account);
        return rec;
    }

    private GameSession bind(PlayerRecord rec) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);
        GameSession session = new GameSession(channel);
        session.setAccount(rec.account);
        session.setPlayer(rec);
        hub.bind(session);
        return session;
    }
}
