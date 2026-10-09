package com.tirnue.auth.packet;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import javax.crypto.Cipher;

/**
 * Netty outbound encoder that encrypts raw bytes using AES/CFB8/NoPadding.
 */
public class AesCfb8Encoder extends MessageToByteEncoder<ByteBuf> {
    private final Cipher cipher;

    public AesCfb8Encoder(Cipher cipher) {
        this.cipher = cipher;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) {
        byte[] input = new byte[in.readableBytes()];
        in.readBytes(input);
        out.writeBytes(this.cipher.update(input));
    }
}
