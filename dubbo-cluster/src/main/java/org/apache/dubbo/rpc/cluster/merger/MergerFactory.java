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

package org.apache.dubbo.rpc.cluster.merger;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.rpc.cluster.Merger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MergerFactory {

    private static final ConcurrentMap<Class<?>, Merger<?>> MERGER_CACHE = new ConcurrentHashMap<Class<?>, Merger<?>>();

    /**
     * Find the merger according to the returnType class, the merger will
     * merge an array of returnType into one
     *
     * 根据returnType找到符合条件的合并器
     *
     * @param returnType the merger will return this type
     * @return the merger which merges an array of returnType into one, return null if not exist
     * @throws IllegalArgumentException if returnType is null
     */
    public static <T> Merger<T> getMerger(Class<T> returnType) {
        if (returnType == null) {
            throw new IllegalArgumentException("returnType is null");
        }

        // 这个方法好像有个问题，就是如果找不到合适的合并器，可能会一直加载loadMergers()方法，不断的覆盖填充MERGER_CACHE

        Merger result;
        // 返回值是Array的话，获取到数组的类型
        if (returnType.isArray()) {
            Class type = returnType.getComponentType();
            result = MERGER_CACHE.get(type);
            if (result == null) {
                loadMergers();
                result = MERGER_CACHE.get(type);
            }
            // 未找到合适的合并器，并且返回类型不是基本类型，则直接走默认的ArrayMerger
            if (result == null && !type.isPrimitive()) {
                result = ArrayMerger.INSTANCE;
            }
        } else {
            result = MERGER_CACHE.get(returnType);
            if (result == null) {
                loadMergers();
                result = MERGER_CACHE.get(returnType);
            }
        }
        return result;
    }

    /**
     * 加载所有支持的合并器
     */
    static void loadMergers() {
        // 获取所有支持Merger接口的子类的名称
        Set<String> names = ExtensionLoader.getExtensionLoader(Merger.class).getSupportedExtensions();
        for (String name : names) {
            Merger m = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(name);
            // 此处将泛型的类型获取到后存储到Map中，key为merge定义在org.apache.dubbo.rpc.cluster.Merger中的名称
            MERGER_CACHE.putIfAbsent(ReflectUtils.getGenericClass(m.getClass()), m);
        }
    }

}
