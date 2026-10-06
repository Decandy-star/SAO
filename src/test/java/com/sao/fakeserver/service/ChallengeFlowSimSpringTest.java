package com.sao.fakeserver.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 需要时才跑：断言 BOB/ZBZ/KFZ {@link ChallengeFlowSimRunner#verifyAll()} 全过。
 * 日常启服不执行冒烟。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "sao.kfz-npc-count=220",
        "sao.data-dir=./data/players-test-challenge-sim",
        "sao.world-dir=./data/world-test-challenge-sim",
        "sao.game-port=0",
        "sao.enforce-login=false"
})
public class ChallengeFlowSimSpringTest {
    @Autowired
    private ChallengeFlowSimRunner challengeFlow;

    @Test
    public void bobZbzKfzSmokePass() {
        Assertions.assertNotNull(challengeFlow);
        String err = challengeFlow.verifyAll();
        Assertions.assertNull(err, () -> "challenge smoke FAIL: " + err);
    }
}
