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
package org.apache.dubbo.rpc.filter.tps;

import java.util.concurrent.atomic.LongAdder;

/**
 * Judge whether a particular invocation of service provider method should be allowed within a configured time interval.
 * As a state it contain name of key ( e.g. method), last invocation time, interval and rate count.
 * <br><br>
 * <p>判断在配置的时间间隔内是否应该允许特定的服务提供者方法调用。作为状态，它包含键名（例如方法）、上次调用时间、间隔和速率计数。
 * <br><br>
 * 此限流器不是时间窗口类型的，而且给定一个固定时间段，在此段内一共只能访问N次，超过则错误。
 * 所以如果在此时间段一开始有一次流量峰值，则此峰值会一共存在interval时间单位。
 * 而如果是时间窗模式，则此峰值会很快被抛弃掉，重新计算流量阈值。
 */
class StatItem {

    /**
     * 对应的 ServiceKey
     */
    private String name;

    /**
     * 记录上次重置的时间，默认一开始初始化为当前时间
     */
    private long lastResetTime;

    /**
     * 重置 token 值的时间周期，这样就实现了在 interval 时间段内能够通过 rate 个请求的效果
     */
    private long interval;

    /**
     * 初始值为 rate 值，每通过一个请求 token 递减一，当减为 0 时，不再通过任何请求，实现限流的作用
     */
    private LongAdder token;

    /**
     * 一段时间内能通过的 TPS 上限
     */
    private int rate;

    /**
     * 构造StatItem对象
     * @param name     serviceKey
     * @param rate     限流数
     * @param interval 单位时间，毫秒数
     */
    StatItem(String name, int rate, long interval) {
        this.name = name;
        this.rate = rate;
        this.interval = interval;
        this.lastResetTime = System.currentTimeMillis();
        this.token = buildLongAdder(rate);
    }

    public boolean isAllowable() {
        long now = System.currentTimeMillis();
        // 周期性重置token
        if (now > lastResetTime + interval) {
            token = buildLongAdder(rate);
            // 记录最近一次重置token的时间戳
            lastResetTime = now;
        }
        // 请求限流
        if (token.sum() <= 0) {
            return false;
        }
        // 请求正常通过
        token.decrement();
        return true;
    }

    public long getInterval() {
        return interval;
    }


    public int getRate() {
        return rate;
    }


    long getLastResetTime() {
        return lastResetTime;
    }

    long getToken() {
        return token.sum();
    }

    @Override
    public String toString() {
        return new StringBuilder(32).append("StatItem ")
                .append("[name=").append(name).append(", ")
                .append("rate = ").append(rate).append(", ")
                .append("interval = ").append(interval).append("]")
                .toString();
    }

    /**
     * 创建一个计数器，默认为rate值，每次请求成功后，将被-1
     * @param rate 限制值
     * @return LongAdder
     */
    private LongAdder buildLongAdder(int rate) {
        LongAdder adder = new LongAdder();
        adder.add(rate);
        return adder;
    }

}
