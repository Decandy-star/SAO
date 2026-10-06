package com.sao.fakeserver.netty;

/**
 * 12 字节小端包头 + protobuf body。
 * length 含包头；CRC 只算 body。
 */
public final class GamePacket {
    public static final int HEADER_SIZE = 12;

    public final int length;
    public final int msgId;
    public final int crc;
    public final int serial;
    public final byte[] body;

    public GamePacket(int msgId, int serial, byte[] body) {
        this.body = body == null ? new byte[0] : body;
        this.msgId = msgId & 0xFFFF;
        this.serial = serial & 0xFFFF;
        this.crc = SaoCrc32.of(this.body);
        this.length = HEADER_SIZE + this.body.length;
    }

    public GamePacket(int length, int msgId, int crc, int serial, byte[] body) {
        this.length = length;
        this.msgId = msgId;
        this.crc = crc;
        this.serial = serial;
        this.body = body == null ? new byte[0] : body;
    }
}
