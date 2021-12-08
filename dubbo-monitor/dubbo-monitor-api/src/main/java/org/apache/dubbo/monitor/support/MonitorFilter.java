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
package org.apache.dubbo.monitor.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.monitor.Monitor;
import org.apache.dubbo.monitor.MonitorFactory;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.monitor.Constants.COUNT_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.INPUT_KEY;
import static org.apache.dubbo.rpc.Constants.OUTPUT_KEY;

/**
 * MonitorFilter. (SPI, Singleton, ThreadSafe) <br><br>
 *
 * dubbo对服务端和客户端都进行了监控，监控内容主要是包含以下四大类信息：
 * <ul>
 *     <li>1.服务调用或者服务请求的基本信息，包括服务地址，端口，服务名，方法名等；</li>
 *     <li>2.服务调用消耗时间；</li>
 *     <li>3.服务请求数据大小或者服务返回值大小；</li>
 *     <li>4.服务请求的结果，成功或者失败；</li>
 *     <li>5.服务方法的总调用次数；</li>
 * </ul>
 *
 * 监控功能主要由MonitorFilter实现，该类实现了Filter接口。在dubbo架构中，MonitorFilter可以拦截所有客户端发出请求或者服务端处理请求。<br><br>
 *
 * MonitorFilter实现了Filter和Filter.Listener接口，该类即是一个过滤器也是一个监听器。<br><br>
 *
 * 该类有注解@Activate，说明该类需要由ExtensionLoader.getActivateExtension方法加载，group既有PROVIDER又有CONSUMER，
 * 说明该类既可以在服务端使用，也可以在客户端使用。@Activate没有设置value，会被ExtensionLoader.getActivateExtension搜索到并加载。<br><br>
 *
 * MonitorFilter是在ProtocolFilterWrapper中被创建并被应用到过滤器链中的。ProtocolFilterWrapper作为包装类，会对DubboProtocol封装，
 * 所以要调用DubboProtocol的export方法，首先调用ProtocolFilterWrapper的export方法。<br><br>
 *
 * buildInvokerChain的返回值是Invoker对象，当收到客户端请求时，便会调用这个Invoker对象的invoke方法，最终调用到入参invoker对象上，入参invoker对象便是最终提供服务的对象。
 * 每次服务端收到请求时，先被MonitorFilter拦截，收集信息后，才会调用后面的过滤器。<br><br>
 *
 * 客户端将MonitorFilter加入到过滤器链中，也是使用buildInvokerChain完成的，与服务端的区别是：<br><br>
 *
 * 1.客户端启动时，要创建远程服务代理（服务代理是在ReferenceConfig的createProxy方法完成的），此时需要调用Protocol$Adaptive的refer方法，
 * 进而调用到ProtocolFilterWrapper的refer方法，在refer方法中调用buildInvokerChain。
 * buildInvokerChain中使用过滤器将真正通过网络访问服务端的Invoker对象封装。<br><br>
 *
 * 2.因为buildInvokerChain使用过滤器对Invoker对象的封装，客户端是在访问远程服务时，都会被MonitorFilter拦截。<br><br>
 *
 */
@Activate(group = {PROVIDER, CONSUMER})
public class MonitorFilter implements Filter, Filter.Listener {

    private static final Logger logger = LoggerFactory.getLogger(MonitorFilter.class);
    private static final String MONITOR_FILTER_START_TIME = "monitor_filter_start_time";
    private static final String MONITOR_REMOTE_HOST_STORE = "monitor_remote_host_store";

    /**
     * The Concurrent counter
     */
    private final ConcurrentMap<String, AtomicInteger> concurrents = new ConcurrentHashMap<String, AtomicInteger>();

    /**
     * The MonitorFactory
     */
    private MonitorFactory monitorFactory;

    public void setMonitorFactory(MonitorFactory monitorFactory) {
        this.monitorFactory = monitorFactory;
    }


    /**
     * The invocation interceptor,it will collect the invoke data about this invocation and send it to monitor center <br><br>
     *
     * 当客户端访问远程服务或者服务端收到请求时，都会调用MonitorFilter的invoke方法，该方法比较简单，只是收集两个信息：服务调用开始时间和服务方法调用次数。
     *
     * @param invoker    service
     * @param invocation invocation.
     * @return {@link Result} the invoke result
     * @throws RpcException
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 检查是否配置monitor参数，如果使用监控功能，必须有如下配置，否则上面对“monitor”检查会失败：
        // dubbo.monitor.address=dubbo://127.0.0.1:30004 (分号前面的内容表示使用协议名)  或
        // dubbo.monitor.protocol=registry (必须是registry，此时监控中心的ip地址端口与注册中心保持一致，如果两个参数都配置，则以dubbo.monitor.address为准)
        if (invoker.getUrl().hasParameter(MONITOR_KEY)) {
            // 将开始时间插入到invocation中，为后面统计服务耗时使用
            // 客户端和服务端对服务耗时的统计有区别：客户端统计包括了网络通讯时间，
            // 服务端统计时间仅仅是服务的运行时间，两者相减就是网络通讯时间
            invocation.put(MONITOR_FILTER_START_TIME, System.currentTimeMillis());
            invocation.put(MONITOR_REMOTE_HOST_STORE, RpcContext.getContext().getRemoteHost());
            // 以接口+方法名为key，访问服务时计数器加一，服务返回后减一，用于统计当前有多少个客户端在访问同一个服务方法
            getConcurrent(invoker, invocation).incrementAndGet(); // count up
        }
        // 下面是调用后续的过滤器或者访问远程服务
        return invoker.invoke(invocation); // proceed invocation chain
    }

    // concurrent counter
    private AtomicInteger getConcurrent(Invoker<?> invoker, Invocation invocation) {
        // 以接口+方法名为key
        String key = invoker.getInterface().getName() + "." + invocation.getMethodName();
        // 获取计数器
        return concurrents.computeIfAbsent(key, k -> new AtomicInteger());
    }

    @Override
    public void onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
        if (invoker.getUrl().hasParameter(MONITOR_KEY)) {
            // 收集信息
            collect(invoker, invocation, result, (String) invocation.get(MONITOR_REMOTE_HOST_STORE), (long) invocation.get(MONITOR_FILTER_START_TIME), false);
            // 计数器减一
            getConcurrent(invoker, invocation).decrementAndGet(); // count down
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        if (invoker.getUrl().hasParameter(MONITOR_KEY)) {
            collect(invoker, invocation, null, (String) invocation.get(MONITOR_REMOTE_HOST_STORE), (long) invocation.get(MONITOR_FILTER_START_TIME), true);
            getConcurrent(invoker, invocation).decrementAndGet(); // count down
        }
    }

    /**
     * The collector logic, it will be handled by the default monitor <br><br>
     *
     * collect方法的最后一个参数error用于区分是onMessage调用还是onError调用。
     *
     * @param invoker
     * @param invocation
     * @param result     the invoke result
     * @param remoteHost the remote host address
     * @param start      the timestamp the invoke begin
     * @param error      if there is an error on the invoke
     */
    private void collect(Invoker<?> invoker, Invocation invocation, Result result, String remoteHost, long start, boolean error) {
        try {
            // 获取配置的监控中心url
            URL monitorUrl = invoker.getUrl().getUrlParameter(MONITOR_KEY);
            // 根据url获取对应的监控中心对象
            // getMonitor方法的原理是异步线程创建Monitor对象，调用的是
            // DubboMonitorFactory.createMonitor方法，因为是异步，所以第一次调用时，
            // Monitor对象可能是null，这种情况下，调用信息不在统计
            // Monitor对象的创建属于懒加载，只有在使用的时候才会创建。
            Monitor monitor = monitorFactory.getMonitor(monitorUrl);
            if (monitor == null) {
                return;
            }
            // 统计信息
            URL statisticsURL = createStatisticsUrl(invoker, invocation, result, remoteHost, start, error);
            // 收集信息
            monitor.collect(statisticsURL);
        } catch (Throwable t) {
            // 处理监控信息过程中失败，不影响使用
            logger.warn("Failed to monitor count service " + invoker.getUrl() + ", cause: " + t.getMessage(), t);
        }
    }

    /**
     * Create statistics url
     *
     * @param invoker
     * @param invocation
     * @param result
     * @param remoteHost
     * @param start
     * @param error
     * @return
     */
    private URL createStatisticsUrl(Invoker<?> invoker, Invocation invocation, Result result, String remoteHost, long start, boolean error) {
        // ---- service statistics ----
        // 服务访问耗时
        long elapsed = System.currentTimeMillis() - start; // invocation cost
        // 服务方法被调用次数
        int concurrent = getConcurrent(invoker, invocation).get(); // current concurrent count
        String application = invoker.getUrl().getParameter(APPLICATION_KEY);
        String service = invoker.getInterface().getName(); // service name
        String method = RpcUtils.getMethodName(invocation); // method name
        String group = invoker.getUrl().getParameter(GROUP_KEY);
        String version = invoker.getUrl().getParameter(VERSION_KEY);

        int localPort;
        String remoteKey, remoteValue;
        if (CONSUMER_SIDE.equals(invoker.getUrl().getParameter(SIDE_KEY))) {
            // 统计客户端信息
            // ---- for service consumer ----
            localPort = 0;
            remoteKey = MonitorService.PROVIDER;
            remoteValue = invoker.getUrl().getAddress();
        } else {
            // 统计服务端信息
            // ---- for service provider ----
            localPort = invoker.getUrl().getPort();
            remoteKey = MonitorService.CONSUMER;
            remoteValue = remoteHost;
        }
        String input = "", output = "";
        if (invocation.getAttachment(INPUT_KEY) != null) {
            // 服务端收到请求信息的大小，以字节为单位，服务端统计
            input = invocation.getAttachment(INPUT_KEY);
        }
        if (result != null && result.getAttachment(OUTPUT_KEY) != null) {
            // 客户端收到返回信息的大小，以字节为单位，客户端统计
            output = result.getAttachment(OUTPUT_KEY);
        }

        // 最后的统计信息以URL对象表示
        return new URL(COUNT_PROTOCOL, NetUtils.getLocalHost(), localPort, service + PATH_SEPARATOR + method, MonitorService.APPLICATION, application, MonitorService.INTERFACE, service, MonitorService.METHOD, method, remoteKey, remoteValue, error ? MonitorService.FAILURE : MonitorService.SUCCESS, "1", MonitorService.ELAPSED, String.valueOf(elapsed), MonitorService.CONCURRENT, String.valueOf(concurrent), INPUT_KEY, input, OUTPUT_KEY, output, GROUP_KEY, group, VERSION_KEY, version);
    }


}
