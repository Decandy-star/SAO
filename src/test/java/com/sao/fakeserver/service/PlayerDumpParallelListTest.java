package com.sao.fakeserver.service;

import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;
import java.util.List;

/**
 * 并行 repeated 列表的写侧编码回归。
 *
 * <p>同一消息里「多条 repeated 按同一批下标配对」时，真服 protobuf-net 写 repeated 元素
 * <b>不省略默认值</b>；假服若用跳过型的 {@code Pb.int32}/{@code Pb.string}/{@code Pb.bool}，
 * 值为 0/空串的那一项会让该列短一格，而客户端是以其中一列的 {@code Count} 为界同下标取值
 * → {@code ArgumentOutOfRangeException}。
 *
 * <p>模板来自 D5 九轮 #30（首充 2618 四条并行列表等长）；本类钉的是全仓 sweep 发现的另外三处
 * （#37 登录 dump 104/105、#38 S2C 2201、#39 S2C 2121）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.data-dir=target/test-data/players-dump-parallel",
        "sao.world-dir=target/test-data/world-dump-parallel",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class PlayerDumpParallelListTest {
    private static final String ACCOUNT = "test-dump-parallel";

    @Autowired
    private PlayerStore store;
    @Autowired
    private PlayerDumpService dump;

    /**
     * 登录大 dump 的嵌套 detail（{@code mainPlayer} 的 field 4）里的 104/105
     * （{@code FBCapterBaoXiangPrizeCapterID} / {@code ...PrizeStatus}）。
     * 客户端 {@code MainPlayer.cs:379-381} 以 104 的 Count 为界取 {@code 105[j]}，status==0 的档不能让 105 短一格。
     */
    @Test
    public void chapterChestDetailKeepsParallelListsAligned() {
        PlayerRecord rec = store.get(ACCOUNT);
        if (rec == null) {
            rec = dump.newPlayer(ACCOUNT, store.nextPlayerId(), 1, 1, ACCOUNT);
        }
        rec.ensureCollections();
        rec.economy.chapterChests.clear();
        rec.economy.chapterChests.put(Integer.valueOf(1), Integer.valueOf(0));
        rec.economy.chapterChests.put(Integer.valueOf(2), Integer.valueOf(3));

        Pb.Fields detail = Pb.read(dump.detail(rec));
        List<Integer> chapters = detail.getInts(104);
        List<Integer> status = detail.getInts(105);
        Assertions.assertEquals(2, chapters.size(), "104 必须逐档写");
        Assertions.assertEquals(chapters.size(), status.size(),
                "客户端以 104 的 Count 为界取 105[j]，两列必须等长");
        Assertions.assertEquals(Integer.valueOf(0), status.get(0), "status==0 也必须写出");
    }

    /**
     * S2C 2201 {@code CCMsgTiaoZhanSaiTargetsNameAndLevelAndRes}：客户端
     * {@code BOBChallengeInfo.cs:233-239} 以 {@code TargetResID.Count} 为界取 TargetName[i]/TargetLevel[i]。
     * 空名 / 0 级 / 0 资源 ID 都不能让某一列短一格。
     */
    @Test
    public void bobTargetsKeepParallelListsAligned() {
        BobService.BobTargetEntry entry = new BobService.BobTargetEntry(7, "", 0, 0, null, null);
        Pb.Fields f = Pb.read(dump.bobTargetsFromEntries(Arrays.asList(entry)));
        Assertions.assertEquals(1, f.getStrings(1).size(), "TargetName 必须逐目标写（空名也写）");
        Assertions.assertEquals(1, f.getInts(2).size(), "TargetLevel 必须逐目标写（0 也写）");
        Assertions.assertEquals(1, f.getInts(3).size(), "TargetResID 必须逐目标写（0 也写）");
        Assertions.assertEquals("", f.getStrings(1).get(0));
    }

    /**
     * S2C 2121 {@code CCMsgQKCoWJs}：客户端 {@code PlayGameState.cs:6182-6188} 以 {@code WJGuid.Count}
     * 为界取 {@code KuangID[i]}/{@code job[i]}。空 guid 与 {@code KuangID==0} 不能让三列错位。
     */
    @Test
    public void kuangMyCoWjsKeepsParallelListsAligned() {
        Pb.Fields f = Pb.read(dump.kuangMyCoWjs(
                Arrays.asList("g1", ""), Arrays.asList(1, 2), Arrays.asList(7, 0)));
        Assertions.assertEquals(2, f.getStrings(1).size(), "WJGuid 必须逐条写（空串也写）");
        Assertions.assertEquals(2, f.getInts(2).size(), "job 必须逐条写");
        Assertions.assertEquals(2, f.getInts(3).size(), "KuangID 必须逐条写（0 也写）");
        Assertions.assertEquals(Integer.valueOf(0), f.getInts(3).get(1));
    }

    /**
     * S2C 4705 {@code CCMsgKFZRequestDuiZhanList_Ret} 的逐局胜负：客户端
     * {@code KFZ_DuiZhanLieBiao.cs:229-241} 按 {@code winOrLose[i-1]} 显示第 i 局的
     * {@code ShengBai{i}/{status}}，位置就是局号，0（未打/未胜）不能被跳过。
     */
    @Test
    public void kfzDuiZhanKeepsPerRoundStatusPositional() {
        List<Integer> mine = Arrays.asList(2, 0, 3, 0);
        List<Integer> foe = Arrays.asList(0, 2, 0, 3);
        Pb.Fields f = Pb.read(dump.kfzDuiZhan(null, null, null, mine, foe));
        Assertions.assertEquals(mine, f.getInts(2), "myWinStatus 必须逐局写（0 也写）");
        Assertions.assertEquals(foe, f.getInts(3), "targetWinStatus 必须逐局写（0 也写）");
    }
}
