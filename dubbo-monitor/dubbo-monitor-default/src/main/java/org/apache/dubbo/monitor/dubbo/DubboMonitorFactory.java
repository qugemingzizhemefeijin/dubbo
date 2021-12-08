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
package org.apache.dubbo.monitor.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.monitor.Monitor;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.monitor.support.AbstractMonitorFactory;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;

import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_PROTOCOL;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REFERENCE_FILTER_KEY;
import static org.apache.dubbo.remoting.Constants.CHECK_KEY;

/**
 * DefaultMonitorFactory
 */
public class DubboMonitorFactory extends AbstractMonitorFactory {

    private Protocol protocol;

    private ProxyFactory proxyFactory;

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    // 这个方法是模板方法，在AbstractMonitorFactory中会调用
    @Override
    protected Monitor createMonitor(URL url) {
        // 这里的url是在客户端启动的时候调用loadMonitor方法构造的
        // 例如：dubbo://127.0.0.1:2181/org.apache.dubbo.monitor.MonitorService?application=consumer&dubbo=2.0.2&interface=org.apache.dubbo.monitor.MonitorService&pid=108660&qos.enable=false&register.ip=192.168.56.1&release=2.7.5&timestamp=1589616815635
        // 要了解这段url的组成，可以看一下ConfigValidationUtils.loadMonitor方法，url里面的ip地址、端口是使用dubbo.monitor.address设置的
        URLBuilder urlBuilder = URLBuilder.from(url);
        // 设置协议，默认是dubbo
        urlBuilder.setProtocol(url.getParameter(PROTOCOL_KEY, DUBBO_PROTOCOL));
        if (StringUtils.isEmpty(url.getPath())) {
            urlBuilder.setPath(MonitorService.class.getName());
        }
        // filter表示访问监控中心需要经过的过滤器，默认没有过滤器
        String filter = url.getParameter(REFERENCE_FILTER_KEY);
        if (StringUtils.isEmpty(filter)) {
            filter = "";
        } else {
            filter = filter + ",";
        }
        urlBuilder.addParameters(CHECK_KEY, String.valueOf(false),
                REFERENCE_FILTER_KEY, filter + "-monitor");
        // 引用远程服务，也就是远程的监控中心的服务，monitorInvoker相当于客户端，所有访问监控中心都是通过monitorInvoker完成。
        // 这里也可以看到监控中心暴露的服务是MonitorService，所以入参url的path是org.apache.dubbo.monitor.MonitorService。
        // refer方法以后在介绍客户端启动的时候说明
        Invoker<MonitorService> monitorInvoker = protocol.refer(MonitorService.class, urlBuilder.build());
        // 创建monitorInvoker的代理
        MonitorService monitorService = proxyFactory.getProxy(monitorInvoker);
        // 创建DubboMonitor对象
        return new DubboMonitor(monitorInvoker, monitorService);
    }

}
