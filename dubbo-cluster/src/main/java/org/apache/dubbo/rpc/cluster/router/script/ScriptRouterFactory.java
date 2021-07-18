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
package org.apache.dubbo.rpc.cluster.router.script;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterFactory;

/**
 * ScriptRouterFactory
 * <p>
 * Example URLS used by Script Router Factory：
 * <ol>
 * <li> script://registryAddress?type=js&rule=xxxx
 * <li> script:///path/to/routerfile.js?type=js&rule=xxxx
 * <li> script://D:\path\to\routerfile.js?type=js&rule=xxxx
 * <li> script://C:/path/to/routerfile.js?type=js&rule=xxxx
 * </ol>
 *
 * <p>The host value in URL points out the address of the source content of the Script Router，Registry、File etc
 *
 * <p>ScriptRouter 支持 JDK 脚本引擎的所有脚本，例如，JavaScript、JRuby、Groovy 等，通过 type=javascript 参数设置脚本类型，缺省为 javascript。
 *
 * <pre>
 * function route(invokers, invocation, context){
 *     var result = new java.util.ArrayList(invokers.size());
 *     var targetHost = new java.util.ArrayList();
 *     targetHost.add("10.134.108.2");
 *     for (var i = 0; i < invokers.length; i) {  // 遍历Invoker集合
 *         // 判断Invoker的host是否符合条件
 *         if(targetHost.contains(invokers[i].getUrl().getHost())){
 *             result.add(invokers[i]);
 *         }
 *     }
 *     return result;
 * }
 * route(invokers, invocation, context)  // 立即执行route()函数
 * </pre>
 *
 * 我们可以将上面这段代码进行编码并作为 rule 参数的值添加到 URL 中，在这个 URL 传入 ScriptRouter 的构造函数时，即可被 ScriptRouter 解析。
 *
 */
public class ScriptRouterFactory implements RouterFactory {

    public static final String NAME = "script";

    @Override
    public Router getRouter(URL url) {
        return new ScriptRouter(url);
    }

}
