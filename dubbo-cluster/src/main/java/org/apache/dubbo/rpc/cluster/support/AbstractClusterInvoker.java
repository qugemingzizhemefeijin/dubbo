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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_LOADBALANCE;
import static org.apache.dubbo.common.constants.CommonConstants.LOADBALANCE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CLUSTER_AVAILABLE_CHECK_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CLUSTER_STICKY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_CLUSTER_STICKY;

/**
 * AbstractClusterInvoker 集群容错顶层抽象类，实现了接口Invoker。构造方法入参必须有Directory对象。
 * <br><br>
 * Directory对象叫做服务目录，持有全部可用的远程服务提供者列表，客户端使用远程服务提供者访问远程服务。
 * 远程服务提供者也实现了Invoker接口，如果远程服务以dubbo协议提供，那么客户端通过Invoker接口的实现类DubboInvoker访问。
 * <br><br>
 * <ul>
 *  <li>1、先生成Invoker对象，根据不同的Cluster实现生成不同类型的ClusterInvoker（这个就是服务引用阶段）</li>
 *  <li>2、调用list从Directory中获取可用的服务列表（从这一步开始真正的调用流程）,接着使用Router接口根据路由规则过滤一部分服务后最终返回服务列表</li>
 *  <li>3、调用select做负载均衡，通过不同的负载均衡策略选出一个服务作为最后的调用</li>
 *  <li>4、调用invoke做RPC调用，对于调用出现异常、成功、失败等情况，每种容错机制会有不同的处理方式</li>
 * </ul>
 *
 */
public abstract class AbstractClusterInvoker<T> implements ClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractClusterInvoker.class);

    /**
     * 服务注册中心对象，RegistryDirectory
     */
    protected Directory<T> directory;

    /**
     * 使用检查服务可用
     */
    protected boolean availablecheck;

    /**
     * 是否被销毁了
     */
    private AtomicBoolean destroyed = new AtomicBoolean(false);

    /**
     * 记录最近的一次粘滞请求的调用对象
     */
    private volatile Invoker<T> stickyInvoker = null;

    public AbstractClusterInvoker() {
    }

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null) {
            throw new IllegalArgumentException("service directory == null");
        }

        // RegistryDirectory
        this.directory = directory;
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        // 当 cluster.availablecheck 为 true 时，应始终在使用前进行检查。默认true
        this.availablecheck = url.getParameter(CLUSTER_AVAILABLE_CHECK_KEY, DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public URL getUrl() {
        return directory.getConsumerUrl();
    }

    public URL getRegistryUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return directory.isAvailable();
    }

    public Directory<T> getDirectory() {
        return directory;
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy();
        }
    }

    @Override
    public boolean isDestroyed() {
        return destroyed.get();
    }

    /**
     * Select a invoker using loadbalance policy.</br>
     * a) Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or,
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * <p>
     * b) Reselection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also
     * guarantees this invoker is available.
     *
     * @param loadbalance load balance policy
     * @param invocation  invocation
     * @param invokers    invoker candidates
     * @param selected    exclude selected invokers or not
     * @return the invoker which will final to do invoke.
     * @throws RpcException exception
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {

        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        String methodName = invocation == null ? StringUtils.EMPTY_STRING : invocation.getMethodName();

        // 开启粘滞连接那么我们可以看到总是访问同一个服务提供者
        boolean sticky = invokers.get(0).getUrl()
                .getMethodParameter(methodName, CLUSTER_STICKY_KEY, DEFAULT_CLUSTER_STICKY);

        //ignore overloaded method
        if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
            stickyInvoker = null;
        }
        //ignore concurrency problem
        if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {
            // 如果需要检查可用性并且保存的粘滞调用对象可用，则直接返回调用对象
            if (availablecheck && stickyInvoker.isAvailable()) {
                return stickyInvoker;
            }
        }
        // 通过负载均衡策略来选择调用的对象
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);

        if (sticky) {
            stickyInvoker = invoker;
        }
        return invoker;
    }

    /**
     * 根据负载均衡策略选择可被调用的对象
     * @param loadbalance 负载均衡策略
     * @param invocation  调用信息
     * @param invokers    可被调用的对象
     * @param selected    排除或不排除选定的调用者（用于重选择，防止一些策略一直路由到某一个Invoker）
     * @return Invoker<T>
     * @throws RpcException
     */
    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {

        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        // 当可被选择的调用者就1个的话，那就直接返回此调用者
        if (invokers.size() == 1) {
            return invokers.get(0);
        }
        // 执行负载均衡的策略获取到选中的调用者
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        //If the `invoker` is in the  `selected` or invoker is unavailable && availablecheck is true, reselect.
        // 这个是说如果上面选择的调用者要么存在于selected集合中，要么是被标记为不可用，但是却要求检查可用性，才会触发重选择
        // 1.selected是为了防止一直被选中某一个；
        // 2.如果选择出来的不可用，需要重置reselect；
        if ((selected != null && selected.contains(invoker))
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) {
            try {
                // 调用重选策略
                Invoker<T> rInvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                if (rInvoker != null) {
                    invoker = rInvoker;
                } else {
                    // 如果还是没选择到，最兜底选择当前调用对象的下一个对象
                    //Check the index of current selected invoker, if it's not the last one, choose the one at index+1.
                    int index = invokers.indexOf(invoker);
                    try {
                        //Avoid collision
                        invoker = invokers.get((index + 1) % invokers.size());
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    /**
     * Reselect, use invokers not in `selected` first, if all invokers are in `selected`,
     * just pick an available one using loadbalance policy. <br><br>
     *
     * 重新选择，首先使用不在`selected`中的调用者，所有调用者都在`selected`中，只需使用负载平衡策略选择一个可用的调用者。
     *
     * @param loadbalance    load balance policy
     * @param invocation     invocation
     * @param invokers       invoker candidates
     * @param selected       exclude selected invokers or not
     * @param availablecheck check invoker available if true
     * @return the reselect result to do invoke
     * @throws RpcException exception
     */
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck) throws RpcException {

        //Allocating one in advance, this list is certain to be used.
        List<Invoker<T>> reselectInvokers = new ArrayList<>(
                invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());

        // First, try picking a invoker not in `selected`.
        // 首先不要在selected中选择调用者
        for (Invoker<T> invoker : invokers) {
            if (availablecheck && !invoker.isAvailable()) {
                continue;
            }

            if (selected == null || !selected.contains(invoker)) {
                reselectInvokers.add(invoker);
            }
        }

        // 如果有可用的，重新调用负载均衡策略来选择调用者
        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        // Just pick an available invoker using loadbalance policy
        // 如果前面未选出合适的调用者，则直接从selected中选择可用的
        if (selected != null) {
            for (Invoker<T> invoker : selected) {
                if ((invoker.isAvailable()) // available first
                        && !reselectInvokers.contains(invoker)) { // 这里selected中存储的invoker还会重复？？
                    reselectInvokers.add(invoker);
                }
            }
        }
        // 重新选择
        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        return null;
    }

    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        // 判断是否destroy
        checkWhetherDestroyed();

        // binding attachments into invocation.
        // 从RpcContext中，查询当前请求有没有携带其他信息
        Map<String, Object> contextAttachments = RpcContext.getContext().getObjectAttachments();
        if (contextAttachments != null && contextAttachments.size() != 0) {
            // 有则加入进去
            ((RpcInvocation) invocation).addObjectAttachments(contextAttachments);
        }

        // 通过Directory 获取到所有可用的invoker
        List<Invoker<T>> invokers = list(invocation);
        // 初始化LoadBalance 的SPI ，默认使用的是 RandomLoadBalance
        LoadBalance loadbalance = initLoadBalance(invokers, invocation);
        // 如果是异步调用，则需要 在Invocation 中增加一个id，用于标识这次Invocation
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        // 执行子类doInvoke方法
        return doInvoke(invocation, invokers, loadbalance);
    }

    protected void checkWhetherDestroyed() {
        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    /**
     * 校验从AbstractClusterInvoker传入的Invoker列表是否为空
     * @param invokers   当前可调用的列表
     * @param invocation 调用对象
     */
    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            throw new RpcException(RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER, "Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getConsumerUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        return directory.list(invocation);
    }

    /**
     * Init LoadBalance.
     * <p>
     * if invokers is not empty, init from the first invoke's url and invocation
     * if invokes is empty, init a default LoadBalance(RandomLoadBalance)
     * </p>
     *
     * @param invokers   invokers
     * @param invocation invocation
     * @return LoadBalance instance. if not need init, return null.
     */
    protected LoadBalance initLoadBalance(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isNotEmpty(invokers)) {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(RpcUtils.getMethodName(invocation), LOADBALANCE_KEY, DEFAULT_LOADBALANCE));
        } else {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(DEFAULT_LOADBALANCE);
        }
    }
}
