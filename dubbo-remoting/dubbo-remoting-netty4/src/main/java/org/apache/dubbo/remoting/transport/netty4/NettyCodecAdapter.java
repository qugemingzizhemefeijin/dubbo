/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.transport.netty4;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.IOException;
import java.util.List;

/**
 * NettyCodecAdapter.
 */
final public class NettyCodecAdapter {

    /**
     * 编码器
     */
    private final ChannelHandler encoder = new InternalEncoder();

    /**
     * 解码器
     */
    private final ChannelHandler decoder = new InternalDecoder();

    /**
     * 编解码器，这个是编解码Dubbo的通信协议的
     */
    private final Codec2 codec;

    /**
     * URL
     */
    private final URL url;

    /**
     * Channel处理器
     */
    private final org.apache.dubbo.remoting.ChannelHandler handler;

    public NettyCodecAdapter(Codec2 codec, URL url, org.apache.dubbo.remoting.ChannelHandler handler) {
        this.codec = codec;
        this.url = url;
        this.handler = handler;
    }

    public ChannelHandler getEncoder() {
        return encoder;
    }

    public ChannelHandler getDecoder() {
        return decoder;
    }

    /**
     * 继承Netty的编码器
     */
    private class InternalEncoder extends MessageToByteEncoder {

        @Override
        protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
            // 创建缓冲区
            ChannelBuffer buffer = new NettyBackedChannelBuffer(out);
            // 获得通道
            Channel ch = ctx.channel();
            // 获取Dubbo Channel
            NettyChannel channel = NettyChannel.getOrAddChannel(ch, url, handler);
            // 对输出数据进行Dubbo编码
            codec.encode(channel, buffer, msg);
        }
    }

    /**
     * 继承Netty的解码器
     */
    private class InternalDecoder extends ByteToMessageDecoder {

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf input, List<Object> out) throws Exception {
            // 创建缓冲区
            ChannelBuffer message = new NettyBackedChannelBuffer(input);
            // 获取Dubbo Channel
            NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);

            // decode object.
            do {
                // 记录读索引
                int saveReaderIndex = message.readerIndex();
                // 解码
                Object msg = codec.decode(channel, message);
                // 拆包
                if (msg == Codec2.DecodeResult.NEED_MORE_INPUT) {
                    message.readerIndex(saveReaderIndex);
                    break;
                } else {
                    //is it possible to go here ?
                    if (saveReaderIndex == message.readerIndex()) {
                        throw new IOException("Decode without read data.");
                    }
                    // 读取数据
                    if (msg != null) {
                        out.add(msg);
                    }
                }
            } while (message.readable());
        }
    }
}
