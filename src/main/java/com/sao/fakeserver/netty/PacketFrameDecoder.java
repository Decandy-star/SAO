package com.sao.fakeserver.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 客户端 TcpClientSession：先读 4 字节小端 length（整包长度），再凑齐剩余字节。
 */
public class PacketFrameDecoder extends ByteToMessageDecoder {
    private static final int MAX = 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return;
        }
        in.markReaderIndex();
        int length = in.readIntLE();
        if (length < GamePacket.HEADER_SIZE || length > MAX) {
            ctx.close();
            return;
        }
        if (in.readableBytes() < length - 4) {
            in.resetReaderIndex();
            return;
        }
        int msgId = in.readUnsignedShortLE();
        int crc = in.readIntLE();
        int serial = in.readUnsignedShortLE();
        byte[] body = new byte[length - GamePacket.HEADER_SIZE];
        in.readBytes(body);
        int actual = SaoCrc32.of(body);
        if (actual != crc) {
            ctx.close();
            return;
        }
        out.add(new GamePacket(length, msgId, crc, serial, body));
    }
}
