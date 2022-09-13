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
package org.apache.dubbo.rpc.proxy.javassist;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.bytecode.Proxy;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;

/**
 * JavassistRpcProxyFactory ，默认代理类 "javassist"，
 */
public class JavassistProxyFactory extends AbstractProxyFactory {

    // 生成代理对象的时候，会先调用getInvoker方法再调用getProxy方法。

    // 下面两个方法 ServiceConfig 也会调用到 export() 方法

    // org.apache.dubbo.config.ReferenceConfig.createProxy 创建代理对象 PROXY_FACTORY.getProxy 方法；
    // StubProxyFactoryWrapper.getProxy 包装类掉入；
    // AbstractProxyFactory.getProxy 方法掉入
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        /**
         * {@link org.apache.dubbo.config.ReferenceConfig#init() }
         */
        // 创建代理对象
        return (T) Proxy.getProxy(interfaces).newInstance(new InvokerInvocationHandler(invoker));
    }

    // org.apache.dubbo.config.ReferenceConfig.createProxy 创建代理对象 REF_PROTOCOL.refer 方法；
    // 从org.apache.dubbo.rpc.protocol.AbstractProxyProtocol.protocolBindingRefer 方法掉入；
    // StubProxyFactoryWrapper 实际是ProxyFactory 的包装类，默认自动填充入 JavassistProxyFactory 对象；
    // 调用进入 StubProxyFactoryWrapper.getInvoker方法；
    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        // TODO Wrapper cannot handle this scenario correctly: the classname contains '$'
        final Wrapper wrapper = Wrapper.getWrapper(proxy.getClass().getName().indexOf('$') < 0 ? proxy.getClass() : type);
        // proxy 实际就是我们的接口实现类，如 com.pv.service.YYYService 的实现类 com.pv.service.YYYServiceImpl
        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                // 这里就是通过 Wrapper.getWrapper 生成的代理类来调用相关的方法。这里性能会比较高是因为不是使用的反射，详细看 Wrapper.getWrapper 方法的注释。
                return wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments);
            }
        };
    }

}
