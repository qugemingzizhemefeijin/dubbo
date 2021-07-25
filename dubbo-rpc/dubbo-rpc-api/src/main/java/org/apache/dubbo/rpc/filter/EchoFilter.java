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

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import static org.apache.dubbo.rpc.Constants.$ECHO;

/**
 * Dubbo provided default Echo echo service, which is available for all dubbo provider service interface.
 * <br><br>
 * EchoFilter在dubbo中用于提供回声测试功能，也就是检测服务是否可用。<br>
 * 回声测试用于检测服务是否可用，回声测试按照正常请求流程执行，能够测试整个调用是否通畅，可用于监控。<br>
 * 所有服务自动实现 EchoService 接口，只需将任意服务引用强制转型为 EchoService，即可使用。<br>
 *
 * <p>
 * <pre>
 * // 远程服务引用
 * MemberService memberService = ctx.getBean("memberService");
 * EchoService echoService = (EchoService) memberService; // 强制转型为EchoService
 * // 回声测试可用性
 * String status = echoService.$echo("OK");
 * assert(status.equals("OK"));
 *
 * </pre>
 */
@Activate(group = CommonConstants.PROVIDER, order = -110000)
public class EchoFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        // 方法名字是$echo 同时参数为1 表示是回声测试 只返回入参
        if (inv.getMethodName().equals($ECHO) && inv.getArguments() != null && inv.getArguments().length == 1) {
            return AsyncRpcResult.newDefaultAsyncResult(inv.getArguments()[0], inv);
        }
        return invoker.invoke(inv);
    }

}
