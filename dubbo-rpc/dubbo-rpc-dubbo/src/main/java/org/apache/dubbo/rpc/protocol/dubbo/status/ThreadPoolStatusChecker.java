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
package org.apache.dubbo.rpc.protocol.dubbo.status;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.status.Status;
import org.apache.dubbo.common.status.StatusChecker;
import org.apache.dubbo.common.store.DataStore;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * ThreadPoolStatusChecker
 *
 * <p>这个已经不可用了
 * <p>issue 6625 https://github.com/apache/dubbo/issues/6625
 * <p>issue 8140 https://github.com/apache/dubbo/issues/8140
 *
 */
@Activate
public class ThreadPoolStatusChecker implements StatusChecker {

    @Override
    public Status check() {
        // 获得数据中心
        DataStore dataStore = ExtensionLoader.getExtensionLoader(DataStore.class).getDefaultExtension();
        // 获得线程池集合
        Map<String, Object> executors = dataStore.get(CommonConstants.EXECUTOR_SERVICE_COMPONENT_KEY);

        StringBuilder msg = new StringBuilder();
        // 设置为ok
        Status.Level level = Status.Level.OK;
        // 遍历线程池集合
        for (Map.Entry<String, Object> entry : executors.entrySet()) {
            String port = entry.getKey();
            ExecutorService executor = (ExecutorService) entry.getValue();

            if (executor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor tp = (ThreadPoolExecutor) executor;
                boolean ok = tp.getActiveCount() < tp.getMaximumPoolSize() - 1;
                Status.Level lvl = Status.Level.OK;
                // 如果活跃数量超过了最大的线程数量，则设置warn
                if (!ok) {
                    level = Status.Level.WARN;
                    lvl = Status.Level.WARN;
                }

                if (msg.length() > 0) {
                    msg.append(";");
                }
                // 输出线程池相关信息
                msg.append("Pool status:").append(lvl).append(", max:").append(tp.getMaximumPoolSize()).append(", core:")
                        .append(tp.getCorePoolSize()).append(", largest:").append(tp.getLargestPoolSize()).append(", active:")
                        .append(tp.getActiveCount()).append(", task:").append(tp.getTaskCount()).append(", service port: ").append(port);
            }
        }
        return msg.length() == 0 ? new Status(Status.Level.UNKNOWN) : new Status(level, msg.toString());
    }

}
