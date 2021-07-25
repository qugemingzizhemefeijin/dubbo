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

import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Directory;

/**
 * Cluster 接口有两条继承线：
 * 一条线是 AbstractCluster 抽象类，这条继承线涉及的全部 Cluster 实现类；
 * 另一条线是 MockClusterWrapper 这条线。
 * <br><br>
 * MockClusterWrapper 是 Cluster 对象的包装类，MockClusterWrapper 类会对 Cluster 进行包装，
 * 负责创建 MockClusterInvoker 对象，是 Dubbo Mock 机制的入口。
 *
 */
public class MockClusterWrapper implements Cluster {

    private Cluster cluster;

    /**
     * Wrapper类都会有一个拷贝构造函数
     * @param cluster Cluster
     */
    public MockClusterWrapper(Cluster cluster) {
        this.cluster = cluster;
    }

    @Override
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {
        // 用MockClusterInvoker进行包装
        return new MockClusterInvoker<T>(directory,
                this.cluster.join(directory));
    }

}
