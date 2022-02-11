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
package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.ExecutionException;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.transport.ChannelHandlerDelegate;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletionStage;

import static org.apache.dubbo.common.constants.CommonConstants.READONLY_EVENT;

/**
 * ExchangeReceiver
 *
 * HeaderExchangeHandler是在信息交换层的一个channel handler，主要是实现了数据接收过来数据的分发，能够根据接收过来的数据类型不同作出不同的处理。
 *
 */
public class HeaderExchangeHandler implements ChannelHandlerDelegate {

    protected static final Logger logger = LoggerFactory.getLogger(HeaderExchangeHandler.class);

    private final ExchangeHandler handler;

    public HeaderExchangeHandler(ExchangeHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.handler = handler;
    }

    // handleResponse方法是处理响应的，如果response不是null并且不是心跳，说明这是个正常请求的响应，
    // 然后交给DefaultFuture.received的方法来处理响应（其实就是设置异步请求的响应结果，并唤醒相关线程）
    static void handleResponse(Channel channel, Response response) throws RemotingException {
        if (response != null && !response.isHeartbeat()) {
            DefaultFuture.received(channel, response);
        }
    }

    private static boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(url.getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    // handlerEvent 方法是个处理事件的，如果收到的内容是R ，也就是服务端关闭的时候向客户端们发送事件通知，然后channel就设置channel.readonly 属性是true，表示不再向服务端发送请求。
    void handlerEvent(Channel channel, Request req) throws RemotingException {
        if (req.getData() != null && req.getData().equals(READONLY_EVENT)) {
            // 客户端收到 READONLY_EVENT 事件请求，记录到通道。后续，不再向该服务器发送新的请求。
            channel.setAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY, Boolean.TRUE);
        }
    }

    // handleRequest是处理正常请求的，判断如果请求是个bad request ，就直接封装个bad request的响应。否则，获取请求的data，
    // 这个data其实就是Invocation，调用handler的reply方法进行执行，如果没有出现异常，将执行结果塞到Response中，并设置Response的状态是OK，
    // 出现异常就将状态设置服务端异常，将异常信息塞到error message中，最后返回response。
    void handleRequest(final ExchangeChannel channel, Request req) throws RemotingException {
        Response res = new Response(req.getId(), req.getVersion()); // 封装Response
        if (req.isBroken()) { // 设置bad request
            Object data = req.getData();

            String msg;
            if (data == null) {
                msg = null;
            } else if (data instanceof Throwable) {
                msg = StringUtils.toString((Throwable) data);
            } else {
                msg = data.toString();
            }
            res.setErrorMessage("Fail to decode request due to: " + msg);
            res.setStatus(Response.BAD_REQUEST);

            channel.send(res);
            return;
        }
        // 使用 ExchangeHandler处理，并返回响应
        // find handler by message class.
        Object msg = req.getData();
        try {
            CompletionStage<Object> future = handler.reply(channel, msg);
            future.whenComplete((appResult, t) -> {
                try {
                    if (t == null) {
                        res.setStatus(Response.OK);
                        res.setResult(appResult);
                    } else {
                        res.setStatus(Response.SERVICE_ERROR);
                        res.setErrorMessage(StringUtils.toString(t));
                    }
                    channel.send(res);
                } catch (RemotingException e) {
                    logger.warn("Send result to consumer failed, channel is " + channel + ", msg is " + e);
                }
            });
        } catch (Throwable e) { // 调用异常的话，设置响应的Status为 SERVICE_ERROR ，并设置错误信息
            res.setStatus(Response.SERVICE_ERROR);
            res.setErrorMessage(StringUtils.toString(e));
            channel.send(res);
        }
    }

    // connected 与disconnected 方法都会设置read 与write时间戳。然后调用下一个handler进行相应的处理，disconnected 最后会调用DefaultFuture.closeChannel方法来关闭channel，
    // 其实就是把那些与该channel有关没有处理完的异步任务响应一个Channel关闭的Response。

    // 当连接的时候会调用connected方法
    @Override
    public void connected(Channel channel) throws RemotingException {
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        handler.connected(exchangeChannel);
    }

    // 断开连接的时候会调用disconnected方法。
    @Override
    public void disconnected(Channel channel) throws RemotingException {
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            handler.disconnected(exchangeChannel);
        } finally {
            DefaultFuture.closeChannel(channel);
            HeaderExchangeChannel.removeChannel(channel);
        }
    }

    // 这个sent方法并不是真正发送数据的，而是发送完数据之后反过来调用的
    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        Throwable exception = null;
        try {
            ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
            handler.sent(exchangeChannel, message);
        } catch (Throwable t) {
            exception = t;
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
        // 如果这个message是Request类型，也就是本次发送数据是请求数据，
        // 然后调用 DefaultFuture.sent(channel, request);
        // 记录一下发送时间，最后的就是处理异常了。
        if (message instanceof Request) {
            Request request = (Request) message;
            // 这里只是记录一下发送时间
            DefaultFuture.sent(channel, request);
        }
        if (exception != null) {
            if (exception instanceof RuntimeException) {
                throw (RuntimeException) exception;
            } else if (exception instanceof RemotingException) {
                throw (RemotingException) exception;
            } else {
                throw new RemotingException(channel.getLocalAddress(), channel.getRemoteAddress(),
                        exception.getMessage(), exception);
            }
        }
    }

    // received 方法是正儿八经的接收数据的方法，通过channel获取HeaderExchangeChannel。

    // 然后如果数据是Request类型，也就是请求，如果是事件，交给handlerEvent方法处理，不是事件类型，如果是双向的（也就是需要回复的）交给handleRequest 方法处理，
    // 并把处理结果Response写回到channel中，其实这里就是正儿八经的远程调用，也就是用户的请求，如果是单向的，就交给其他handler的received 方法处理。

    // 如果数据是响应实体类型，也就是Response类型，说明这是个响应，就交给handleResponse 处理。

    // 如果数据是字符串类型，判断channel是哪端，如果是客户端的话，就抛出异常，因为客户端不接收字符串请求，如果是服务端，说明是个telnet请求，交给handler的telnet来处理，并将返回结果，写回到channel中。
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 创建ExchangeChannel对象
        final ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        if (message instanceof Request) {
            // handle request.
            Request request = (Request) message;
            if (request.isEvent()) { // 请求的是事件
                handlerEvent(channel, request);
            } else { // 处理普通需求
                if (request.isTwoWay()) { // 正常的请求响应类型
                    handleRequest(exchangeChannel, request);
                } else { // 提交给其他Handler处理
                    handler.received(exchangeChannel, request.getData());
                }
            }
        } else if (message instanceof Response) {
            // 处理响应
            handleResponse(channel, (Response) message);
        } else if (message instanceof String) {
            // 客户端不支持处理字符串
            if (isClientSide(channel)) {
                Exception e = new Exception("Dubbo client can not supported string message: " + message + " in channel: " + channel + ", url: " + channel.getUrl());
                logger.error(e.getMessage(), e);
            } else {
                // telnet处理
                String echo = handler.telnet(channel, (String) message);
                if (echo != null && echo.length() > 0) {
                    channel.send(echo);
                }
            }
        } else {
            // 其他的Handler处理
            handler.received(exchangeChannel, message);
        }
    }

    // 如果异常是执行异常，还是个需要回复的请求，并且不是心跳（这其实就是普通远程调用），就封装一个Respose响应，设置状态为服务端错误，然后发送回去。否则 交给下一个handler处理。
    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {
        if (exception instanceof ExecutionException) { // 处理异常
            ExecutionException e = (ExecutionException) exception;
            Object msg = e.getRequest();
            if (msg instanceof Request) {
                Request req = (Request) msg;
                if (req.isTwoWay() && !req.isHeartbeat()) {
                    Response res = new Response(req.getId(), req.getVersion());
                    res.setStatus(Response.SERVER_ERROR);
                    res.setErrorMessage(StringUtils.toString(e));
                    channel.send(res);
                    return;
                }
            }
        }
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            handler.caught(exchangeChannel, exception);
        } finally {
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    @Override
    public ChannelHandler getHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }
}
