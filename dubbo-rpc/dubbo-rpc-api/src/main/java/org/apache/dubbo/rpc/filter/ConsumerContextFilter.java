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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.TimeoutCountDown;

import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIME_COUNTDOWN_KEY;

/**
 * ConsumerContextFilter set current RpcContext with invoker,invocation, local host, remote host and port
 * for consumer invoker.It does it to make the requires info available to execution thread's RpcContext.
 * <br><br>
 * <p>
 * ConsumerContextFilter 是一个非常简单的 Consumer 端 Filter 实现，它会在当前的 RpcContext 中记录本地调用的一些状态信息（会记录到 LOCAL 对应的 RpcContext 中），
 * 例如，调用相关的 Invoker、Invocation 以及调用的本地地址、远端地址信息。
 *
 * @see org.apache.dubbo.rpc.Filter
 * @see RpcContext
 */
@Activate(group = CONSUMER, order = -10000)
public class ConsumerContextFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // invoker = AsyncRpcResult.class
        // dubbo://127.0.0.1:20801/com.service.DemoService?
        // anyhost=true&application=demo-consumer&check=false&connect.timeout=50000&deprecated=false&dubbo=2.0.2&
        // dynamic=true&generic=false&init=false&interface=com.service.DemoService&
        // loadbalance=roundrobin&metadata-type=remote&methods=sayHello&pid=16772&
        // qos.enable=false&register.ip=192.168.1.1&release=2.7.7&remote.application=demo-provider&
        // retries=0&revision=3.0.0&saveOtcConfig.timeout=3600000&side=consumer&sticky=false&timeout=600000&
        // timestamp=1626868593888&version=3.0.0
        RpcContext context = RpcContext.getContext();
        // 记录Invoker
        context.setInvoker(invoker)
                // 记录Invocation
                .setInvocation(invocation)
                // 记录本地地址以及远端地址
                .setLocalAddress(NetUtils.getLocalHost(), 0)
                .setRemoteAddress(invoker.getUrl().getHost(), invoker.getUrl().getPort())
                // 记录远端应用名称等信息
                .setRemoteApplicationName(invoker.getUrl().getParameter(REMOTE_APPLICATION_KEY))
                .setAttachment(REMOTE_APPLICATION_KEY, invoker.getUrl().getParameter(APPLICATION_KEY));
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(invoker);
        }

        // pass default timeout set by end user (ReferenceConfig)
        // 检测是否超时
        Object countDown = context.get(TIME_COUNTDOWN_KEY);
        if (countDown != null) {
            TimeoutCountDown timeoutCountDown = (TimeoutCountDown) countDown;
            // 如果请求超时，则不再发起远程调用，直接让 AsyncRpcResult 异常结束。
            if (timeoutCountDown.isExpired()) {
                return AsyncRpcResult.newDefaultAsyncResult(new RpcException(RpcException.TIMEOUT_TERMINATE,
                        "No time left for making the following call: " + invocation.getServiceName() + "."
                                + invocation.getMethodName() + ", terminate directly."), invocation);
            }
        }
        return invoker.invoke(invocation);
    }

}
