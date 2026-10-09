package com.tirnue.auth.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import javax.crypto.Cipher;
import java.util.List;

/**
 * Netty inbound decoder that decrypts raw bytes using AES/CFB8/NoPadding.
 */
public class AesCfb8Decoder extends MessageToMessageDecoder<ByteBuf> {
    private final Cipher cipher;

    public AesCfb8Decoder(Cipher cipher) {
        this.cipher = cipher;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        byte[] input = new byte[msg.readableBytes()];
        msg.readBytes(input);
        out.add(Unpooled.wrappedBuffer(this.cipher.update(input)));
    }
}
