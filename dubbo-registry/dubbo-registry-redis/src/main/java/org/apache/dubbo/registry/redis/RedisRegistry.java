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
package org.apache.dubbo.registry.redis;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.remoting.redis.RedisClient;
import org.apache.dubbo.remoting.redis.jedis.ClusterRedisClient;
import org.apache.dubbo.remoting.redis.jedis.MonoRedisClient;
import org.apache.dubbo.remoting.redis.jedis.SentinelRedisClient;
import org.apache.dubbo.rpc.RpcException;

import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_REDIS;
import static org.apache.dubbo.common.constants.CommonConstants.MONO_REDIS;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.REDIS_CLIENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SENTINEL_REDIS;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY_RECONNECT_PERIOD;
import static org.apache.dubbo.registry.Constants.DEFAULT_SESSION_TIMEOUT;
import static org.apache.dubbo.registry.Constants.REGISTER;
import static org.apache.dubbo.registry.Constants.REGISTRY_RECONNECT_PERIOD_KEY;
import static org.apache.dubbo.registry.Constants.SESSION_TIMEOUT_KEY;
import static org.apache.dubbo.registry.Constants.UNREGISTER;

/**
 * RedisRegistry<br><br>
 *
 * Redis订阅发布 使用的是 过期机制 和 publish/subscribe通道 <br><br>
 *
 * 服务提供者发布服务，首先会在Redis中创建一个key，然后 在通道中 发布一条register事件消息 <br><br>
 *
 * 但 服务的key写入Redis后，发布者 需要 周期性地刷新 key的过期时间，在RedisRegistry构造方法中，会启动一个expireExcutor-定时调度线程池，不断通过deferExpire方法 去延续key的超时时间<br><br>
 *
 * 如果服务提供者宕机，没有续期，key会因为超时被删除，服务也会被认定为下线 <br><br>
 *
 * 订阅方 首次连接上注册中心，会获取 全量数据 并 缓存到本地内存中。
 * 后续服务列表变化 是通过 publish/subscribe通道广播，当有服务提供者主动下线时，会在通道中 广播一条unregister事件消息，
 * 订阅方 收到后 则 从注册中心拉取数据，更新 本地缓存的 服务列表。新服务提供者上线 也是通过 通道事件 触发更新的。<br><br>
 *
 * 但是 Redis的key超时 是不会有动态消息推送的，如果使用Redis作为服务注册中心 ，还会依赖 服务治理中心，
 * 服务治理中心 定时调度，获取Redis上所有的key 并进行遍历，如果发现key已经超时，则删除。清除完之后，会在通道中 发起 对应key的unregister事件，
 * 其他消费者 监听到 取消注册时间后 会删除本地 对应服务的数据，从而 保持 数据的最终一致性。<br><br>
 *
 * Redis客户端 初始化的时候，需要先 初始化 Redis的连接池 jedisPools，如果 配置注册中心的集群模式为<dubbo:registry cluster=“replicate”>，
 * 则 服务提供者 在发布的时候 需要向Redis集群中所有节点都写入。但是读取还是从一个节点中读取。这种情况下，Redis集群可以不配置数据同步，一致性 由 客户端 的 多写来保证。
 * 如果设置为failover 或 不设置，则只会 读取 和 写入 任意一个Redis节点，失败的话 会尝试 下一个Redis节点，这种情况 需要 Redis自行配置 数据同步。<br><br>
 *
 */
public class RedisRegistry extends FailbackRegistry {

    private final static String DEFAULT_ROOT = "dubbo";

    // 初始化一个 定时调度线程池，主要任务是 延长key的过期时间 和 删除过期的key
    private final ScheduledExecutorService expireExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboRegistryExpireTimer", true));

    // ScheduledFuture就是在Future<V>基础上 集成了 Comparable<Delayed> 和 Delayed 的接口
    private final ScheduledFuture<?> expireFuture;

    /**
     * 如果没有设置group则为 /dubbo/，否则为/group/
     */
    private final String root;

    private RedisClient redisClient;

    /**
     * 如果一个服务被订阅，则会加入此中，key = 服务在zk的路径， value = Notifier，实际就是不断扫描来获取redis的消息，如注册，卸载等消息
     */
    private final ConcurrentMap<String, Notifier> notifiers = new ConcurrentHashMap<>();

    /**
     * 通知重连时间间隔，默认3秒
     */
    private final int reconnectPeriod;

    private final int expirePeriod;

    /**
     * 是否是如果是服务治理中心
     */
    private volatile boolean admin = false;

    public RedisRegistry(URL url) {
        super(url);
        String type = url.getParameter(REDIS_CLIENT_KEY, MONO_REDIS);
        if (SENTINEL_REDIS.equals(type)) {
            redisClient = new SentinelRedisClient(url);
        } else if (CLUSTER_REDIS.equals(type)) {
            redisClient = new ClusterRedisClient(url);
        } else {
            redisClient = new MonoRedisClient(url);
        }

        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }

        this.reconnectPeriod = url.getParameter(REGISTRY_RECONNECT_PERIOD_KEY, DEFAULT_REGISTRY_RECONNECT_PERIOD);
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        if (!group.endsWith(PATH_SEPARATOR)) {
            group = group + PATH_SEPARATOR;
        }
        this.root = group;

        this.expirePeriod = url.getParameter(SESSION_TIMEOUT_KEY, DEFAULT_SESSION_TIMEOUT);
        // scheduleWithFixedDelay
        // 初始延迟时间=expirePeriod / 2
        // 指定延迟时间=expirePeriod / 2
        this.expireFuture = expireExecutor.scheduleWithFixedDelay(() -> {
            try {
                deferExpired(); // Extend the expiration time
            } catch (Throwable t) { // Defensive fault tolerance
                logger.error("Unexpected exception occur at defer expire time, cause: " + t.getMessage(), t);
            }
        }, expirePeriod / 2, expirePeriod / 2, TimeUnit.MILLISECONDS);
    }

    /**
     * 过期Key处理
     */
    private void deferExpired() {
        for (URL url : new HashSet<>(getRegistered())) {
            if (url.getParameter(DYNAMIC_KEY, true)) {
                // 根据url获取key，续期key
                String key = toCategoryPath(url);
                if (redisClient.hset(key, url.toFullString(), String.valueOf(System.currentTimeMillis() + expirePeriod)) == 1) {
                    // expirePeriod 是在RedisRegistry构造方法中通过url的session参数设置的，
                    // 默认值是DEFAULT_SESSION_TIMEOUT = 60 * 1000
                    // 如果hset成功，则发一条REGISTER事件 消息
                    redisClient.publish(key, REGISTER);
                }
            }
        }
        if (admin) {
            // 如果是服务治理中心，清理key
            clean();
        }
    }

    private void clean() {
        // 扫描根路径下的所有key
        Set<String> keys = redisClient.scan(root + ANY_VALUE);
        if (CollectionUtils.isNotEmpty(keys)) {
            for (String key : keys) {
                // 循环key，得到value，如果value不为空
                // 循环value，也就是url，获取过期时间
                Map<String, String> values = redisClient.hgetAll(key);
                if (CollectionUtils.isNotEmptyMap(values)) {
                    boolean delete = false;
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        URL url = URL.valueOf(entry.getKey());
                        if (url.getParameter(DYNAMIC_KEY, true)) {
                            long expire = Long.parseLong(entry.getValue());
                            // 如果过期时间比当前时间小，则删除Key
                            if (expire < now) {
                                redisClient.hdel(key, entry.getKey());
                                delete = true;
                                if (logger.isWarnEnabled()) {
                                    logger.warn("Delete expired key: " + key + " -> value: " + entry.getKey() + ", expire: " + new Date(expire) + ", now: " + new Date(now));
                                }
                            }
                        }
                    }
                    // 删除成功就发布一条UNREGISTER事件消息
                    if (delete) {
                        redisClient.publish(key, UNREGISTER);
                    }
                }
            }
        }
    }

    @Override
    public boolean isAvailable() {
        // redis是否处于连接状态
        return redisClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            // 取消定时器
            expireFuture.cancel(true);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        // 先断开订阅，尝试关闭Redis链接
        try {
            for (Notifier notifier : notifiers.values()) {
                notifier.shutdown();
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        // 如果上面没关闭成功，则这里还会再关闭一次
        try {
            redisClient.destroy();
        } catch (Throwable t) {
            logger.warn("Failed to destroy the redis registry client. registry: " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
        }
        // 优雅的关闭线程池
        ExecutorUtil.gracefulShutdown(expireExecutor, expirePeriod);
    }

    @Override
    public void doRegister(URL url) {
        // key = /dubbo/com.aa.aa/providers
        String key = toCategoryPath(url);
        String value = url.toFullString();
        String expire = String.valueOf(System.currentTimeMillis() + expirePeriod);
        try {
            // 设置redis key，这里hash的field的value = 此节点的过期时间
            redisClient.hset(key, value, expire);
            // 发布到队列中，消息类型为注册
            redisClient.publish(key, REGISTER);
        } catch (Throwable t) {
            throw new RpcException("Failed to register service to redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doUnregister(URL url) {
        // 上同，只是反着来
        String key = toCategoryPath(url);
        String value = url.toFullString();
        try {
            redisClient.hdel(key, value);
            redisClient.publish(key, UNREGISTER);
        } catch (Throwable t) {
            throw new RpcException("Failed to unregister service to redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        // /dubbo/com.aa.bb
        String service = toServicePath(url);
        Notifier notifier = notifiers.get(service);
        if (notifier == null) {
            Notifier newNotifier = new Notifier(service);
            notifiers.putIfAbsent(service, newNotifier);
            notifier = notifiers.get(service);
            // 这里面的一段代码没看懂，意思是如果两个不相等的话，就不start了，因为start必定会在另一个线程中执行
            if (notifier == newNotifier) {
                notifier.start();
            }
        }
        try {
            // 是否是服务治理中心
            if (service.endsWith(ANY_VALUE)) {
                admin = true;
                // 扫描所有的，如consumer、provider、configurators、routers、metadata
                Set<String> keys = redisClient.scan(service);// 这里扫描的service后面加一个/字符，是不是会更好点，否则在doNotify中还得判断是否是同一个服务
                if (CollectionUtils.isNotEmpty(keys)) {
                    Map<String, Set<String>> serviceKeys = new HashMap<>();
                    // 这段代码实际就是组装成 服务 -> 服务子节点集合
                    for (String key : keys) {
                        String serviceKey = toServicePath(key);
                        Set<String> sk = serviceKeys.computeIfAbsent(serviceKey, k -> new HashSet<>());
                        sk.add(key);
                    }
                    for (Set<String> sk : serviceKeys.values()) {
                        doNotify(sk, url, Collections.singletonList(listener));
                    }
                }
            } else {
                doNotify(redisClient.scan(service + PATH_SEPARATOR + ANY_VALUE), url, Collections.singletonList(listener));
            }
        } catch (Throwable t) {
            throw new RpcException("Failed to subscribe service from redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
    }

    private void doNotify(String key) {
        for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<>(getSubscribed()).entrySet()) {
            doNotify(Collections.singletonList(key), entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }

    /**
     * 通知什么呢？
     * @param keys      /dubbo/com.aa.bb/providers，其实是服务的几个子节点
     * @param url       doSubscribe传入的URL，消费URL
     * @param listeners doSubscribe传入的NotifyListener
     */
    private void doNotify(Collection<String> keys, URL url, Collection<NotifyListener> listeners) {
        if (keys == null || keys.isEmpty()
                || listeners == null || listeners.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<URL> result = new ArrayList<>();
        // 获取url中的category参数值，如providers，consumers等
        List<String> categories = Arrays.asList(url.getParameter(CATEGORY_KEY, new String[0]));
        // 获取服务名称
        String consumerService = url.getServiceInterface();
        for (String key : keys) {
            // 非 * 的服务名称（*是什么时候才会有的呢？）
            if (!ANY_VALUE.equals(consumerService)) {
                String providerService = toServiceName(key);
                // 名称会有不一致的情况吗？可能存在如需要扫描/dubbo/com.aa.bb.cc这种，但是实际只是需要/dubbo/com.aa.bb，这样子名字就不一样了
                if (!providerService.equals(consumerService)) {
                    continue;
                }
            }
            // 如果URL中的类型不包含本次key的，则过滤掉
            String category = toCategoryName(key);
            if (!categories.contains(ANY_VALUE) && !categories.contains(category)) {
                continue;
            }
            // 获取key中保持的URL集合
            List<URL> urls = new ArrayList<>();
            Map<String, String> values = redisClient.hgetAll(key);
            if (CollectionUtils.isNotEmptyMap(values)) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    URL u = URL.valueOf(entry.getKey());
                    // 只获取非动态的，也即意思上的持久化节点，并且里面存储的值是超过当前时间
                    if (!u.getParameter(DYNAMIC_KEY, true)
                            || Long.parseLong(entry.getValue()) >= now) {
                        // 如果消费和生产URL匹配上，则加入到集合
                        if (UrlUtils.isMatch(url, u)) {
                            urls.add(u);
                        }
                    }
                }
            }
            // 反正就是urls不能为空
            if (urls.isEmpty()) {
                urls.add(URLBuilder.from(url)
                        .setProtocol(EMPTY_PROTOCOL)
                        .setAddress(ANYHOST_VALUE)
                        .setPath(toServiceName(key))
                        .addParameter(CATEGORY_KEY, category)
                        .build());
            }
            // 加入到大集合中
            result.addAll(urls);
            if (logger.isInfoEnabled()) {
                logger.info("redis notify: " + key + " = " + urls);
            }
        }
        if (CollectionUtils.isEmpty(result)) {
            return;
        }
        // 调用FailbackRegistry的notify方法
        for (NotifyListener listener : listeners) {
            // 通知监听器 url 的变化结果
            notify(url, listener, result);
        }
    }

    /**
     * 根据传入的path获取服务名称，返回 com.aa.bb
     * @param categoryPath String
     * @return String
     */
    private String toServiceName(String categoryPath) {
        String servicePath = toServicePath(categoryPath);
        return servicePath.startsWith(root) ? servicePath.substring(root.length()) : servicePath;
    }

    /**
     * 根据传入的path获取类型名称，返回 providers、consumers等
     * @param categoryPath String
     * @return String
     */
    private String toCategoryName(String categoryPath) {
        int i = categoryPath.lastIndexOf(PATH_SEPARATOR);
        return i > 0 ? categoryPath.substring(i + 1) : categoryPath;
    }

    /**
     * 通过redis中的key值，解析出 提供的服务 ，返回值为 /dubbo/com.aa.bb
     * @param categoryPath 传入的如/dubbo/com.aa.bb/providers
     * @return String
     */
    private String toServicePath(String categoryPath) {
        int i;
        if (categoryPath.startsWith(root)) {
            i = categoryPath.indexOf(PATH_SEPARATOR, root.length());
        } else {
            i = categoryPath.indexOf(PATH_SEPARATOR);
        }
        return i > 0 ? categoryPath.substring(0, i) : categoryPath;
    }

    /**
     * 获取服务的基础路径，/dubbo/com.aa.bb
     * @param url URL
     * @return String
     */
    private String toServicePath(URL url) {
        return root + url.getServiceInterface();
    }

    /**
     * ROOT + Service + Type 返回 /dubbo/com.aa.bb/providers
     * @param url URL
     * @return String
     */
    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }

    /**
     * redis获取到队列消息后执行的实体
     */
    private class NotifySub extends JedisPubSub {
        public NotifySub() {}

        @Override
        public void onMessage(String key, String msg) {
            if (logger.isInfoEnabled()) {
                logger.info("redis event: " + key + " = " + msg);
            }
            if (msg.equals(REGISTER)
                    || msg.equals(UNREGISTER)) {
                try {
                    doNotify(key);
                } catch (Throwable t) { // TODO Notification failure does not restore mechanism guarantee
                    logger.error(t.getMessage(), t);
                }
            }
        }

        @Override
        public void onPMessage(String pattern, String key, String msg) {
            onMessage(key, msg);
        }

        @Override
        public void onSubscribe(String key, int num) {
        }

        @Override
        public void onPSubscribe(String pattern, int num) {
        }

        @Override
        public void onUnsubscribe(String key, int num) {
        }

        @Override
        public void onPUnsubscribe(String pattern, int num) {
        }

    }

    /**
     * 消费者在订阅服务后的处理线程（如果服务非常多，会有大量的线程创建）
     */
    private class Notifier extends Thread {

        // 服务节点 /dubbo/com.aa.bb
        private final String service;
        private final AtomicInteger connectSkip = new AtomicInteger();
        private final AtomicInteger connectSkipped = new AtomicInteger();

        private volatile boolean first = true;
        private volatile boolean running = true;
        private volatile int connectRandom;

        public Notifier(String service) {
            super.setDaemon(true);
            super.setName("DubboRedisSubscribe");
            this.service = service;
        }

        /**
         * 重置skip次数
         */
        private void resetSkip() {
            connectSkip.set(0);
            connectSkipped.set(0);
            connectRandom = 0;
        }

        /**
         * 判断是否忽略本次对Redis的连接，原则是：连接失败的次数越多，每一轮加大需要忽略的总次数
         * @return boolean
         */
        private boolean isSkip() {
            // 获得需要忽略的连接数，如果超过10，则加上一个10以内的随机数
            int skip = connectSkip.get(); // Growth of skipping times
            if (skip >= 10) { // If the number of skipping times increases by more than 10, take the random number
                if (connectRandom == 0) {
                    connectRandom = ThreadLocalRandom.current().nextInt(10);
                }
                skip = 10 + connectRandom;
            }
            // 自增忽略次数，若忽略次数不够，则继续忽略
            if (connectSkipped.getAndIncrement() < skip) { // Check the number of skipping times
                return true;
            }
            // 增加需要忽略的次数
            connectSkip.incrementAndGet();
            // 重置已忽略次数和随机数
            connectSkipped.set(0);
            connectRandom = 0;
            return false;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    // 如果一直跳过的话，还是会造成死循环的吧？？
                    if (!isSkip()) {
                        try {
                            if (!redisClient.isConnected()) {
                                continue;
                            }
                            try {
                                // 如果是监控中心的订阅
                                if (service.endsWith(ANY_VALUE)) {
                                    if (first) {
                                        first = false;
                                        // 获取分类集合
                                        Set<String> keys = redisClient.scan(service);
                                        if (CollectionUtils.isNotEmpty(keys)) {
                                            for (String s : keys) {
                                                doNotify(s);
                                            }
                                        }
                                        // 由于连接过程允许一定量的失败，调用该方法重置计数器
                                        resetSkip();
                                    }
                                    // 订阅给定模式的通道，当订阅的通道有发布消息时，NotifySub对象的回调方法就能接收到。需要注意的是，订阅方法是阻塞的。
                                    redisClient.psubscribe(new NotifySub(), service);
                                } else {
                                    if (first) {
                                        first = false;
                                        doNotify(service);
                                        resetSkip();
                                    }
                                    // 由于连接过程允许一定量的失败，调用该方法重置计数器
                                    redisClient.psubscribe(new NotifySub(), service + PATH_SEPARATOR + ANY_VALUE); // blocking
                                }
                            } catch (Throwable t) { // Retry another server
                                logger.warn("Failed to subscribe service from redis registry. registry: " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
                                // If you only have a single redis, you need to take a rest to avoid overtaking a lot of CPU resources
                                sleep(reconnectPeriod);
                            }
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                            sleep(reconnectPeriod);
                        }
                    }
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        }

        /**
         * 停止 Notifier，关闭redis订阅相关工作的关键。它是通过设置停止循环标识，以及关闭redis连接实现的。
         */
        public void shutdown() {
            try {
                running = false;
                redisClient.disconnect();
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

    }

}
