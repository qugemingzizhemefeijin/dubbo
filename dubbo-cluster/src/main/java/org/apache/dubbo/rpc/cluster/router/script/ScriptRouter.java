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
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_SCRIPT_TYPE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.FORCE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.PRIORITY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RULE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RUNTIME_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.TYPE_KEY;

/**
 * ScriptRouter
 */
public class ScriptRouter extends AbstractRouter {

    public static final String NAME = "SCRIPT_ROUTER";
    private static final int SCRIPT_ROUTER_DEFAULT_PRIORITY = 0;
    private static final Logger logger = LoggerFactory.getLogger(ScriptRouter.class);

    /**
     * 这是一个 static 集合，其中的 Key 是脚本语言的名称，Value 是对应的 ScriptEngine 对象。这里会按照脚本语言的类型复用 ScriptEngine 对象。
     */
    private static final Map<String, ScriptEngine> ENGINES = new ConcurrentHashMap<>();

    /**
     * 当前 ScriptRouter 使用的 ScriptEngine 对象
     */
    private final ScriptEngine engine;

    /**
     * 当前 ScriptRouter 使用的具体脚本内容
     */
    private final String rule;

    /**
     * 根据 rule 这个具体脚本内容编译得到
     */
    private CompiledScript function;

    private AccessControlContext accessControlContext;

    {
        //Just give permission of reflect to access member.
        Permissions perms = new Permissions();
        perms.add(new RuntimePermission("accessDeclaredMembers"));
        // Cast to Certificate[] required because of ambiguity:
        ProtectionDomain domain = new ProtectionDomain(
                new CodeSource(null, (Certificate[]) null), perms);
        accessControlContext = new AccessControlContext(
                new ProtectionDomain[]{domain});
    }

    /**
     * 首先会初始化 url 字段以及 priority 字段（用于排序），然后根据 URL 中的 type 参数初始化 engine、rule 和 function 三个核心字段
     * @param url consumer消费方URL
     */
    public ScriptRouter(URL url) {
        this.url = url;
        this.priority = url.getParameter(PRIORITY_KEY, SCRIPT_ROUTER_DEFAULT_PRIORITY);

        // 根据URL中的type参数值，从ENGINES集合中获取对应的ScriptEngine对象
        engine = getEngine(url);
        // 获取URL中的rule参数值，即为具体的脚本
        rule = getRule(url);
        try {
            Compilable compilable = (Compilable) engine;
            // 编译rule字段中的脚本，得到function字段
            function = compilable.compile(rule);
        } catch (ScriptException e) {
            logger.error("route error, rule has been ignored. rule: " + rule +
                    ", url: " + RpcContext.getContext().getUrl(), e);
        }
    }

    /**
     * get rule from url parameters.
     */
    private String getRule(URL url) {
        String vRule = url.getParameterAndDecoded(RULE_KEY);
        if (StringUtils.isEmpty(vRule)) {
            throw new IllegalStateException("route rule can not be empty.");
        }
        return vRule;
    }

    /**
     * create ScriptEngine instance by type from url parameters, then cache it
     */
    private ScriptEngine getEngine(URL url) {
        String type = url.getParameter(TYPE_KEY, DEFAULT_SCRIPT_TYPE_KEY);

        return ENGINES.computeIfAbsent(type, t -> {
            ScriptEngine scriptEngine = new ScriptEngineManager().getEngineByName(type);
            if (scriptEngine == null) {
                throw new IllegalStateException("unsupported route engine type: " + type);
            }
            return scriptEngine;
        });
    }

    /**
     * 首先会创建调用 function 函数所需的入参，也就是 Bindings 对象，然后调用 function 函数得到过滤后的 Invoker 集合，
     * 最后通过 getRoutedInvokers() 方法整理 Invoker 集合得到最终的返回值。
     * @param invokers   要过滤的服务列表
     * @param url        消费者URL
     * @param invocation 调用的接口和方法描述
     * @return List<Invoker<T>>
     * @throws RpcException
     */
    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        if (engine == null || function == null) {
            return invokers;
        }
        // 创建Bindings对象作为function函数的入参
        Bindings bindings = createBindings(invokers, invocation);
        // 调用function函数，并在getRoutedInvokers()方法中整理得到的Invoker集合
        return getRoutedInvokers(AccessController.doPrivileged(new PrivilegedAction() {
            @Override
            public Object run() {
                try {
                    return function.eval(bindings);
                } catch (ScriptException e) {
                    logger.error("route error, rule has been ignored. rule: " + rule + ", method:" +
                            invocation.getMethodName() + ", url: " + RpcContext.getContext().getUrl(), e);
                    return invokers;
                }
            }
        }, accessControlContext));

    }

    /**
     * get routed invokers from result of script rule evaluation
     */
    @SuppressWarnings("unchecked")
    protected <T> List<Invoker<T>> getRoutedInvokers(Object obj) {
        if (obj instanceof Invoker[]) {
            return Arrays.asList((Invoker<T>[]) obj);
        } else if (obj instanceof Object[]) {
            return Arrays.stream((Object[]) obj).map(item -> (Invoker<T>) item).collect(Collectors.toList());
        } else {
            return (List<Invoker<T>>) obj;
        }
    }

    /**
     * 创建脚本引擎的参数绑定
     * @param invokers   待过滤的服务列表
     * @param invocation 调用的接口和方法描述
     * @return Bindings
     */
    private <T> Bindings createBindings(List<Invoker<T>> invokers, Invocation invocation) {
        Bindings bindings = engine.createBindings();
        // create a new List of invokers
        // 与ScriptRouterFactory的示例脚本结合，我们可以看到这里在Bindings中为脚本中的route()函数提供了invokers、Invocation、context三个参数
        bindings.put("invokers", new ArrayList<>(invokers));
        bindings.put("invocation", invocation);
        bindings.put("context", RpcContext.getContext());
        return bindings;
    }

    @Override
    public boolean isRuntime() {
        return this.url.getParameter(RUNTIME_KEY, false);
    }

    @Override
    public boolean isForce() {
        return url.getParameter(FORCE_KEY, false);
    }

}
