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
package org.apache.dubbo.rpc.cluster.support.wrapper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.cluster.interceptor.ClusterInterceptor;
import org.apache.dubbo.rpc.cluster.support.AbstractClusterInvoker;

import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.REFERENCE_INTERCEPTOR_KEY;

public abstract class AbstractCluster implements Cluster {

    /**
     * 最终返回的对象是 InterceptorInvokerNode ，该类是内部类，定义在下面
     * @param clusterInvoker 传入的是集群容错子类封装好的Invoker对象，如FailoverClusterInvoker
     * @param key            reference.interceptor的值
     * @return Invoker<T>
     */
    private <T> Invoker<T> buildClusterInterceptors(AbstractClusterInvoker<T> clusterInvoker, String key) {
        AbstractClusterInvoker<T> last = clusterInvoker;
        List<ClusterInterceptor> interceptors = ExtensionLoader.getExtensionLoader(ClusterInterceptor.class).getActivateExtension(clusterInvoker.getUrl(), key);

        // 根据需要包装ClusterInvoker, 使用切面的方式进行拦截器接入
        // 按先后依次加入拦截器
        if (!interceptors.isEmpty()) {
            for (int i = interceptors.size() - 1; i >= 0; i--) {
                final ClusterInterceptor interceptor = interceptors.get(i);
                final AbstractClusterInvoker<T> next = last;
                // 使用内部类进行包装拦截器
                // 先后顺序如: beforeC -> beforeB -> beforeA (spring中还有Around) -> afterA -> afterB -> afterC (spring中还有afterReturn)
                last = new InterceptorInvokerNode<>(clusterInvoker, interceptor, next);
            }
        }
        return last;
    }

    // Directory 指的是服务字典（保存着provider的各种信息）
    @Override
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {
        // 组装调用的Invoker，获取是否指定了reference.interceptor参数，如果为null则默认自动激活所有的ClusterInterceptor拦截
        return buildClusterInterceptors(doJoin(directory), directory.getUrl().getParameter(REFERENCE_INTERCEPTOR_KEY));
    }

    /**
     * 提供用于子类包装Invoker，典型的模板方法设计模式
     * @param directory 服务目录
     * @param <T>
     * @return AbstractClusterInvoker<T>
     * @throws RpcException
     */
    protected abstract <T> AbstractClusterInvoker<T> doJoin(Directory<T> directory) throws RpcException;

    protected class InterceptorInvokerNode<T> extends AbstractClusterInvoker<T> {

        /**
         * 封装的Cluster Invoker，其实就是doJoin传入的那个对象
         */
        private AbstractClusterInvoker<T> clusterInvoker;

        /**
         * 当前包装的拦截器对象
         */
        private ClusterInterceptor interceptor;

        /**
         * 封装在最底层的时候，clusterInvoker = next，直至调用到真正的 clusterInvoker的invoke方法，拦截器递归调用逻辑才算结束
         */
        private AbstractClusterInvoker<T> next;

        public InterceptorInvokerNode(AbstractClusterInvoker<T> clusterInvoker,
                                      ClusterInterceptor interceptor,
                                      AbstractClusterInvoker<T> next) {
            this.clusterInvoker = clusterInvoker;
            this.interceptor = interceptor;
            this.next = next;
        }

        @Override
        public Class<T> getInterface() {
            return clusterInvoker.getInterface();
        }

        @Override
        public URL getUrl() {
            return clusterInvoker.getUrl();
        }

        @Override
        public boolean isAvailable() {
            return clusterInvoker.isAvailable();
        }

        @Override
        public Result invoke(Invocation invocation) throws RpcException {
            Result asyncResult;
            try {
                // 拦截器的具体处理逻辑
                // 有个 intercept() 的默认方法，其为调用 clusterInvoker.invoke(invocation);  从而实现链式调用
                interceptor.before(next, invocation);
                asyncResult = interceptor.intercept(next, invocation);
            } catch (Exception e) {
                // onError callback 如果拦截器实现了ClusterInterceptor.Listener，则调用具体的实现方法
                if (interceptor instanceof ClusterInterceptor.Listener) {
                    ClusterInterceptor.Listener listener = (ClusterInterceptor.Listener) interceptor;
                    listener.onError(e, clusterInvoker, invocation);
                }
                throw e;
            } finally {
                interceptor.after(next, invocation);
            }
            return asyncResult.whenCompleteWithContext((r, t) -> {
                // onResponse callback 如果拦截器实现了ClusterInterceptor.Listener，则调用具体的实现方法
                if (interceptor instanceof ClusterInterceptor.Listener) {
                    ClusterInterceptor.Listener listener = (ClusterInterceptor.Listener) interceptor;
                    if (t == null) {
                        listener.onMessage(r, clusterInvoker, invocation);
                    } else {
                        listener.onError(t, clusterInvoker, invocation);
                    }
                }
            });
        }

        @Override
        public void destroy() {
            // FailoverClusterInvoker
            clusterInvoker.destroy();
        }

        @Override
        public String toString() {
            return clusterInvoker.toString();
        }

        @Override
        protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
            // The only purpose is to build an interceptor chain, so the cluster related logic doesn't matter.
            return null;
        }
    }
}
