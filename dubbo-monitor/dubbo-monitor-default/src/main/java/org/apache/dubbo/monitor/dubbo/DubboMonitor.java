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
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.monitor.Monitor;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.rpc.Invoker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_PROTOCOL;

/**
 * DubboMonitor<br><br>
 *
 * DubboMonitor的collect方法只是将统计信息保存在了statisticsMap中，也就是保存在本地内存中。<br><br>
 * statisticsMap的value使用的是AtomicReference对象，使用原子类可以避免并发访问时加锁。<br><br>
 * 在DubboMonitor的构造方法中创建了定时任务，每过一段时间，会将statisticsMap的内容发送到监控中心。定时任务调用的是send方法。<br><br>
 *
 */
public class DubboMonitor implements Monitor {

    private static final Logger logger = LoggerFactory.getLogger(DubboMonitor.class);

    /**
     * The length of the array which is a container of the statistics
     */
    private static final int LENGTH = 10;

    /**
     * The timer for sending statistics
     */
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(3, new NamedThreadFactory("DubboMonitorSendTimer", true));

    /**
     * The future that can cancel the <b>scheduledExecutorService</b>
     */
    private final ScheduledFuture<?> sendFuture;

    private final Invoker<MonitorService> monitorInvoker;

    private final MonitorService monitorService;

    private final ConcurrentMap<Statistics, AtomicReference<long[]>> statisticsMap = new ConcurrentHashMap<Statistics, AtomicReference<long[]>>();

    // monitorInvoker可以简单理解为监控中心的客户端，这一版dubbo里面使用
    // monitorService是monitorInvoker的代理对象
    // 这两个对象本质是一样，monitorService最终也是访问monitorInvoker。
    // 在DubboMonitor中访问监控中心使用的是monitorService
    public DubboMonitor(Invoker<MonitorService> monitorInvoker, MonitorService monitorService) {
        this.monitorInvoker = monitorInvoker;
        this.monitorService = monitorService;
        // The time interval for timer <b>scheduledExecutorService</b> to send data
        // monitorInterval表示monitorInvoker每多长时间访问一次监控中心，默认是1分钟
        // 该值可以通过dubbo.monitor.interval修改
        final long monitorInterval = monitorInvoker.getUrl().getPositiveParameter("interval", 60000);
        // collect timer for collecting statistics data
        // 创建定时任务执行器，这个定时器没有使用时间轮算法，而是使用java的ScheduledExecutorService
        // 任务执行间隔是monitorInterval指定的
        sendFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                // collect data
                send(); // 将统计的数据发送到监控中心
            } catch (Throwable t) {
                logger.error("Unexpected error occur at send statistic, cause: " + t.getMessage(), t);
            }
        }, monitorInterval, monitorInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * send方法做了三件事：
     * <ul>
     *     <li>1.将统计信息构造为URL对象；</li>
     *     <li>2.将URL对象发送给监控中心；</li>
     *     <li>3.将部分统计数据清零；</li>
     * </ul>
     */
    public void send() {
        if (logger.isDebugEnabled()) {
            logger.debug("Send statistics to monitor " + getUrl());
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        // 遍历statisticsMap
        for (Map.Entry<Statistics, AtomicReference<long[]>> entry : statisticsMap.entrySet()) {
            // get statistics data
            Statistics statistics = entry.getKey();
            AtomicReference<long[]> reference = entry.getValue();
            long[] numbers = reference.get();
            long success = numbers[0];
            long failure = numbers[1];
            long input = numbers[2];
            long output = numbers[3];
            long elapsed = numbers[4];
            long concurrent = numbers[5];
            long maxInput = numbers[6];
            long maxOutput = numbers[7];
            long maxElapsed = numbers[8];
            long maxConcurrent = numbers[9];
            String protocol = getUrl().getParameter(DEFAULT_PROTOCOL);

            // send statistics data
            // 将10项统计信息构造为URL对象
            URL url = statistics.getUrl()
                    .addParameters(MonitorService.TIMESTAMP, timestamp,
                            MonitorService.SUCCESS, String.valueOf(success),
                            MonitorService.FAILURE, String.valueOf(failure),
                            MonitorService.INPUT, String.valueOf(input),
                            MonitorService.OUTPUT, String.valueOf(output),
                            MonitorService.ELAPSED, String.valueOf(elapsed),
                            MonitorService.CONCURRENT, String.valueOf(concurrent),
                            MonitorService.MAX_INPUT, String.valueOf(maxInput),
                            MonitorService.MAX_OUTPUT, String.valueOf(maxOutput),
                            MonitorService.MAX_ELAPSED, String.valueOf(maxElapsed),
                            MonitorService.MAX_CONCURRENT, String.valueOf(maxConcurrent),
                            DEFAULT_PROTOCOL, protocol
                    );
            // 将统计信息发送到监控中心，monitorService可以认为是监控中心的客户端，
            // 是监控中心在本地的代理对象
            // monitorService内部会访问网络，将数据使用dubbo协议传送给监控中心
            monitorService.collect(url);

            // 重置6项数据，这六项数据的统计时间范围是上次发送给监控中心
            // 到这次发送给监控中心为止，每发送给监控中心后，这六项数据清零
            // 6项数据为：
            // 1、服务成功次数；
            // 2、服务失败次数；
            // 3、服务端收到请求信息总大小；
            // 4、客户端收到返回信息的总大小；
            // 5、服务耗时总时间；
            // 6、作用未知
            long[] current;
            long[] update = new long[LENGTH];
            do {
                current = reference.get();
                if (current == null) {
                    update[0] = 0;
                    update[1] = 0;
                    update[2] = 0;
                    update[3] = 0;
                    update[4] = 0;
                    update[5] = 0;
                } else {
                    update[0] = current[0] - success;
                    update[1] = current[1] - failure;
                    update[2] = current[2] - input;
                    update[3] = current[3] - output;
                    update[4] = current[4] - elapsed;
                    update[5] = current[5] - concurrent;
                }
            } while (!reference.compareAndSet(current, update));
        }
    }

    @Override
    public void collect(URL url) {
        // 从url中获取一些统计信息
        // data to collect from url
        int success = url.getParameter(MonitorService.SUCCESS, 0); // 服务是否成功，用于统计成功次数
        int failure = url.getParameter(MonitorService.FAILURE, 0); // 服务是否失败，用于统计失败次数
        int input = url.getParameter(MonitorService.INPUT, 0); // 服务端收到请求信息的大小，以字节为单位
        int output = url.getParameter(MonitorService.OUTPUT, 0); // 客户端收到返回信息的大小，以字节为单位
        int elapsed = url.getParameter(MonitorService.ELAPSED, 0); // 访问服务耗时
        int concurrent = url.getParameter(MonitorService.CONCURRENT, 0); // 访问服务的总次数
        // init atomic reference
        // 创建Statistics对象，该对象作为statisticsMap的key
        // statisticsMap是DubboMonitor的属性，类型是ConcurrentHashMap<Statistics, AtomicReference<long[]>>
        Statistics statistics = new Statistics(url);
        // reference保存各个统计信息，使用long数组存储，存储了10项信息
        AtomicReference<long[]> reference = statisticsMap.computeIfAbsent(statistics, k -> new AtomicReference<>());
        // use CompareAndSet to sum
        long[] current;
        long[] update = new long[LENGTH];
        // 下面的代码为更新reference数组的统计信息，使用循环，直到更新成功为止
        do {
            current = reference.get();
            if (current == null) {
                update[0] = success;
                update[1] = failure;
                update[2] = input;
                update[3] = output;
                update[4] = elapsed;
                update[5] = concurrent;
                update[6] = input;
                update[7] = output;
                update[8] = elapsed;
                update[9] = concurrent;
            } else {
                update[0] = current[0] + success;
                update[1] = current[1] + failure;
                update[2] = current[2] + input;
                update[3] = current[3] + output;
                update[4] = current[4] + elapsed;
                update[5] = (current[5] + concurrent) / 2;
                // 下面四项信息，只统计最大值
                update[6] = current[6] > input ? current[6] : input;
                update[7] = current[7] > output ? current[7] : output;
                update[8] = current[8] > elapsed ? current[8] : elapsed;
                update[9] = current[9] > concurrent ? current[9] : concurrent;
            }
        } while (!reference.compareAndSet(current, update));
    }

    @Override
    public List<URL> lookup(URL query) {
        return monitorService.lookup(query);
    }

    @Override
    public URL getUrl() {
        return monitorInvoker.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return monitorInvoker.isAvailable();
    }

    @Override
    public void destroy() {
        try {
            ExecutorUtil.cancelScheduledFuture(sendFuture);
        } catch (Throwable t) {
            logger.error("Unexpected error occur at cancel sender timer, cause: " + t.getMessage(), t);
        }
        monitorInvoker.destroy();
    }

}
