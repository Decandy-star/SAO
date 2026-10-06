package com.sao.fakeserver.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class PacketEncoder extends MessageToByteEncoder<GamePacket> {
    @Override
    protected void encode(ChannelHandlerContext ctx, GamePacket msg, ByteBuf out) {
        out.writeIntLE(msg.length);
        out.writeShortLE(msg.msgId);
        out.writeIntLE(msg.crc);
        out.writeShortLE(msg.serial);
        out.writeBytes(msg.body);
    }
}
