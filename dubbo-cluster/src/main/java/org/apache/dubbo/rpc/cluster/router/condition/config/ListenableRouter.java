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
package org.apache.dubbo.rpc.cluster.router.condition.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.configcenter.ConfigChangeType;
import org.apache.dubbo.common.config.configcenter.ConfigChangedEvent;
import org.apache.dubbo.common.config.configcenter.ConfigurationListener;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;
import org.apache.dubbo.rpc.cluster.router.condition.ConditionRouter;
import org.apache.dubbo.rpc.cluster.router.condition.config.model.ConditionRouterRule;
import org.apache.dubbo.rpc.cluster.router.condition.config.model.ConditionRuleParser;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>Abstract router which listens to dynamic configuration<br><br>
 *
 * <p>ListenableRouter 在 ConditionRouter 基础上添加了动态配置的能力，
 * <p>ListenableRouter 的 process() 方法与 TagRouter 中的 process() 方法类似，<br><br>
 *
 * <p>对于 ConfigChangedEvent.DELETE 事件，直接清空 ListenableRouter 中维护的 ConditionRouterRule 和 ConditionRouter 集合的引用；
 * <p>对于 ADDED、UPDATED 事件，则通过 ConditionRuleParser 解析事件内容，得到相应的 ConditionRouterRule 对象和 ConditionRouter 集合。<br><br>
 *
 * <p>这里的 ConditionRuleParser 同样是以 yaml 文件的格式解析 ConditionRouterRule 的相关配置。<br><br>
 *
 * <p>ConditionRouterRule 中维护了一个 conditions 集合（List<String> 类型），记录了多个 Condition 路由规则，对应生成多个 ConditionRouter 对象。<br><br>
 *
 * <p>ServiceRouter 和 AppRouter 都是简单地继承了 ListenableRouter 抽象类，且没有覆盖 ListenableRouter 的任何方法，两者只有以下两点区别。<br><br>
 * <p>一个是 priority 字段值不同。ServiceRouter 为 140，AppRouter 为 150，也就是说 ServiceRouter 要先于 AppRouter 执行。<br><br>
 * <p>另一个是获取 ConditionRouterRule 配置的 Key 不同。
 * ServiceRouter 使用的 RuleKey 是由 {interface}:[version]:[group] 三部分构成，获取的是一个服务对应的 ConditionRouterRule。
 * AppRouter 使用的 RuleKey 是 URL 中的 application 参数值，获取的是一个服务实例对应的 ConditionRouterRule。
 *
 */
public abstract class ListenableRouter extends AbstractRouter implements ConfigurationListener {
    public static final String NAME = "LISTENABLE_ROUTER";
    private static final String RULE_SUFFIX = ".condition-router";

    private static final Logger logger = LoggerFactory.getLogger(ListenableRouter.class);
    private ConditionRouterRule routerRule;
    private List<ConditionRouter> conditionRouters = Collections.emptyList();

    public ListenableRouter(URL url, String ruleKey) {
        super(url);
        this.force = false;
        this.init(ruleKey);
    }

    @Override
    public synchronized void process(ConfigChangedEvent event) {
        if (logger.isInfoEnabled()) {
            logger.info("Notification of condition rule, change type is: " + event.getChangeType() +
                    ", raw rule is:\n " + event.getContent());
        }

        if (event.getChangeType().equals(ConfigChangeType.DELETED)) {
            routerRule = null;
            conditionRouters = Collections.emptyList();
        } else {
            try {
                routerRule = ConditionRuleParser.parse(event.getContent());
                generateConditions(routerRule);
            } catch (Exception e) {
                logger.error("Failed to parse the raw condition rule and it will not take effect, please check " +
                        "if the condition rule matches with the template, the raw rule is:\n " + event.getContent(), e);
            }
        }
    }

    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        // 检查边界条件，直接返回invokers集合
        if (CollectionUtils.isEmpty(invokers) || conditionRouters.size() == 0) {
            return invokers;
        }

        // We will check enabled status inside each router.
        // 路由规则进行过滤
        for (Router router : conditionRouters) {
            invokers = router.route(invokers, url, invocation);
        }

        return invokers;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public boolean isForce() {
        return (routerRule != null && routerRule.isForce());
    }

    private boolean isRuleRuntime() {
        return routerRule != null && routerRule.isValid() && routerRule.isRuntime();
    }

    private void generateConditions(ConditionRouterRule rule) {
        if (rule != null && rule.isValid()) {
            this.conditionRouters = rule.getConditions()
                    .stream()
                    .map(condition -> new ConditionRouter(condition, rule.isForce(), rule.isEnabled()))
                    .collect(Collectors.toList());
        }
    }

    private synchronized void init(String ruleKey) {
        if (StringUtils.isEmpty(ruleKey)) {
            return;
        }
        String routerKey = ruleKey + RULE_SUFFIX;
        ruleRepository.addListener(routerKey, this);
        String rule = ruleRepository.getRule(routerKey, DynamicConfiguration.DEFAULT_GROUP);
        if (StringUtils.isNotEmpty(rule)) {
            this.process(new ConfigChangedEvent(routerKey, DynamicConfiguration.DEFAULT_GROUP, rule));
        }
    }
}
