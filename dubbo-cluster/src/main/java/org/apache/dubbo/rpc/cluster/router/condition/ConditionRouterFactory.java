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
package org.apache.dubbo.rpc.cluster.router.condition;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterFactory;

/**
 * ConditionRouterFactory
 * Load when "override://" is configured {@link ConditionRouter}
 *
 * <p>
 * ConditionRouter 是基于条件表达式的路由实现类，下面就是一条基于条件表达式的路由规则：
 * host = 192.168.1.1 => host = 192.168.1.2
 *
 * <p>
 * 在上述规则中，=>之前的为 Consumer 匹配的条件，该条件中的所有参数会与 Consumer 的 URL 进行对比，
 * 当 Consumer 满足匹配条件时，会对该 Consumer 的此次调用执行 => 后面的过滤规则。
 *
 * <p>
 * => 之后为 Provider 地址列表的过滤条件，该条件中的所有参数会和 Provider 的 URL 进行对比，Consumer 最终只拿到过滤后的地址列表。
 *
 * <p>
 * 如果 Consumer 匹配条件为空，表示 => 之后的过滤条件对所有 Consumer 生效，
 * 例如：=> host != 192.168.1.2，含义是所有 Consumer 都不能请求 192.168.1.2 这个 Provider 节点。
 *
 * <p>
 * 如果 Provider 过滤条件为空，表示禁止访问所有 Provider，
 * 例如：host = 192.168.1.1 =>，含义是 192.168.1.1 这个 Consumer 不能访问任何 Provider 节点。
 */
public class ConditionRouterFactory implements RouterFactory {

    public static final String NAME = "condition";

    @Override
    public Router getRouter(URL url) {
        return new ConditionRouter(url);
    }

}
