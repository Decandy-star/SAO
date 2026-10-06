package com.sao.fakeserver.netty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SaoCrc32Test {
    @Test
    void emptyIsZero() {
        assertEquals(0, SaoCrc32.of(new byte[0]));
    }

    @Test
    void knownBytes() {
        byte[] body = {1, 2, 3, 4};
        java.util.zip.CRC32 jdk = new java.util.zip.CRC32();
        jdk.update(body);
        assertEquals((int) jdk.getValue(), SaoCrc32.of(body));
    }
}
