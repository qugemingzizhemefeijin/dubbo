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

import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.rpc.cluster.Merger;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SetMerger、ListMerger 和 MapMerger 是针对 Set 、List 和 Map 返回值的 Merger 实现，
 * 它们会将多个 Set（或 List、Map）集合合并成一个 Set（或 List、Map）集合，核心原理与 ArrayMerger 的实现类似。
 */
public class ListMerger implements Merger<List<?>> {

    @Override
    public List<Object> merge(List<?>... items) {
        if (ArrayUtils.isEmpty(items)) {
            // 空结果集时，这就返回空List集合
            return Collections.emptyList();
        }
        // 通过Stream API将传入的所有List集合拍平成一个List集合并返回
        return Stream.of(items).filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

    }

}
