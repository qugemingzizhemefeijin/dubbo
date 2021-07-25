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
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;

import java.util.Map;

import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;

/**
 * Perform check whether given provider token is matching with remote token or not. If it does not match
 * it will not allow to invoke remote method. <br><br>
 *
 * 执行检查给定的提供者令牌是否与远程令牌匹配。如果不匹配，则不允许调用远程方法。url中配置了token了才被激活。
 *
 * <a href="https://dubbo.apache.org/zh/docs/v2.7/user/examples/token-authorization/">https://dubbo.apache.org/zh/docs/v2.7/user/examples/token-authorization/</a>
 *
 * <p>
 * 通过令牌验证在注册中心控制权限，以决定要不要下发令牌给消费者，可以防止消费者绕过注册中心访问提供者，另外通过注册中心可灵活改变授权方式，而不需修改或升级提供者。
 *
 * <pre>
 * //可以全局设置开启令牌验证：
 * &lt;!--随机token令牌，使用UUID生成&gt;
 * &lt;dubbo:provider interface="com.foo.BarService" token="true" /&gt;
 * &lt;!--固定token令牌，相当于密码&gt;
 * &lt;dubbo:provider interface="com.foo.BarService" token="123456" /&gt;
 *
 * //也可在服务级别设置：
 * &lt;!--随机token令牌，使用UUID生成--&gt;
 * &lt;dubbo:service interface="com.foo.BarService" token="true" /&gt;
 * &lt;!--固定token令牌，相当于密码--&gt;
 * &lt;dubbo:service interface="com.foo.BarService" token="123456" /&gt;
 *
 * </pre>
 *
 * token服务是在此方法中设置的{@link org.apache.dubbo.config.ServiceConfig#doExportUrlsFor1Protocol}
 *
 * @see Filter
 */
@Activate(group = CommonConstants.PROVIDER, value = TOKEN_KEY)
public class TokenFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv)
            throws RpcException {
        // 获得服务提供者配置的 Token 值
        String token = invoker.getUrl().getParameter(TOKEN_KEY);
        if (ConfigUtils.isNotEmpty(token)) {
            Class<?> serviceType = invoker.getInterface();
            Map<String, Object> attachments = inv.getObjectAttachments();
            // 获取消费者传入的token
            String remoteToken = (attachments == null ? null : (String) attachments.get(TOKEN_KEY));
            // 进行校验
            if (!token.equals(remoteToken)) {
                throw new RpcException("Invalid token! Forbid invoke remote service " + serviceType + " method " + inv.getMethodName()
                        + "() from consumer " + RpcContext.getContext().getRemoteHost() + " to provider " + RpcContext.getContext().getLocalHost()
                        + ", consumer incorrect token is " + remoteToken);
            }
        }
        return invoker.invoke(inv);
    }

}
