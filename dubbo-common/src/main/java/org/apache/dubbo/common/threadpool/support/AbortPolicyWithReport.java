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
package org.apache.dubbo.common.threadpool.support;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.event.ThreadPoolExhaustedEvent;
import org.apache.dubbo.common.utils.JVMUtil;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.event.EventDispatcher;

import static java.lang.String.format;
import static org.apache.dubbo.common.constants.CommonConstants.DUMP_DIRECTORY;

/**
 * Abort Policy.
 * Log warn info when abort. <br><br>
 *
 * 在dubbo中可以通过dump.directory指定打印堆栈信息的目录，如果不设置，dubbo将堆栈信息打印到System.getProperty(“user.home”)目录下。设置dump.directory的方式可以有：<br><br>
 * <ul>
 *     <li>1.@Service(parameters = {“dump.directory”,"/tmp"})，这个设置只对单个的服务生效</li>
 *     <li>2.dubbo.application.parameters[dump.directory]=/tmp，这样设置会使整个应用的所有服务都生效</li>
 * </ul>
 *
 *
 */
public class AbortPolicyWithReport extends ThreadPoolExecutor.AbortPolicy {

    protected static final Logger logger = LoggerFactory.getLogger(AbortPolicyWithReport.class);

    /**
     * threadName可以作为日志打印的关键字，默认是Dubbo，可以通过参数“threadname”设置
     */
    private final String threadName;

    /**
     * url是由服务参数组成的，如果是服务端创建AbortPolicyWithReport ，则url是服务端发布服务的url。如果是消费端，则url代表了消费端需要引用的远程服务url
     */
    private final URL url;

    /**
     * 表示最后一次打印堆栈信息的事件，dubbo每10分钟打印一次
     */
    private static volatile long lastPrintTime = 0;

    /**
     * 表示堆栈信息的打印间隔
     */
    private static final long TEN_MINUTES_MILLS = 10 * 60 * 1000;

    private static final String OS_WIN_PREFIX = "win";

    private static final String OS_NAME_KEY = "os.name";

    private static final String WIN_DATETIME_FORMAT = "yyyy-MM-dd_HH-mm-ss";

    private static final String DEFAULT_DATETIME_FORMAT = "yyyy-MM-dd_HH:mm:ss";

    // 守卫的意思
    private static Semaphore guard = new Semaphore(1);

    private static final String USER_HOME = System.getProperty("user.home");

    public AbortPolicyWithReport(String threadName, URL url) {
        this.threadName = threadName;
        this.url = url;
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        String msg = String.format("Thread pool is EXHAUSTED!" +
                " Thread Name: %s, Pool Size: %d (active: %d, core: %d, max: %d, largest: %d), Task: %d (completed: "
                + "%d)," +
                " Executor status:(isShutdown:%s, isTerminated:%s, isTerminating:%s), in %s://%s:%d!",
            threadName, e.getPoolSize(), e.getActiveCount(), e.getCorePoolSize(), e.getMaximumPoolSize(),
            e.getLargestPoolSize(),
            e.getTaskCount(), e.getCompletedTaskCount(), e.isShutdown(), e.isTerminated(), e.isTerminating(),
            url.getProtocol(), url.getIp(), url.getPort());
        // 打印日志，其中属性theadName会打印到日志中
        logger.warn(msg);
        // 调用打印堆栈信息的方法
        dumpJStack();
        dispatchThreadPoolExhaustedEvent(msg);
        throw new RejectedExecutionException(msg);
    }

    /**
     * dispatch ThreadPoolExhaustedEvent
     * @param msg
     */
    public void dispatchThreadPoolExhaustedEvent(String msg) {
        EventDispatcher.getDefaultExtension().dispatch(new ThreadPoolExhaustedEvent(this, msg));
    }

    private void dumpJStack() {
        long now = System.currentTimeMillis();

        //dump every 10 minutes
        // 一个线程池最少间隔10分钟打印一次堆栈信息
        if (now - lastPrintTime < TEN_MINUTES_MILLS) {
            return;
        }

        // guard是信号量，同时只能一个线程打印堆栈
        if (!guard.tryAcquire()) {
            return;
        }

        ExecutorService pool = Executors.newSingleThreadExecutor();
        // 使用java原生线程池打印堆栈信息，这个线程池中只有一个线程，用完就关闭
        pool.execute(() -> {
            // 获得的打印堆栈信息文件的目录
            String dumpPath = getDumpPath();

            SimpleDateFormat sdf;

            String os = System.getProperty(OS_NAME_KEY).toLowerCase();

            // window system don't support ":" in file name
            // 不同系统的时间格式不同
            if (os.contains(OS_WIN_PREFIX)) {
                sdf = new SimpleDateFormat(WIN_DATETIME_FORMAT);
            } else {
                sdf = new SimpleDateFormat(DEFAULT_DATETIME_FORMAT);
            }

            String dateStr = sdf.format(new Date());
            //try-with-resources
            // 打印的文件名为：Dubbo_JStack.log.时间，其中时间到秒
            try (FileOutputStream jStackStream = new FileOutputStream(
                new File(dumpPath, "Dubbo_JStack.log" + "." + dateStr))) {
                // 使用JVMUtil打印，代码见下方
                JVMUtil.jstack(jStackStream);
            } catch (Throwable t) {
                logger.error("dump jStack error", t);
            } finally {
                guard.release();
            }
            // 记录最后一次打印时间
            lastPrintTime = System.currentTimeMillis();
        });
        //must shutdown thread pool ,if not will lead to OOM
        pool.shutdown(); // 关闭线程池

    }

    private String getDumpPath() {
        final String dumpPath = url.getParameter(DUMP_DIRECTORY);
        if (StringUtils.isEmpty(dumpPath)) {
            return USER_HOME;
        }
        final File dumpDirectory = new File(dumpPath);
        if (!dumpDirectory.exists()) {
            if (dumpDirectory.mkdirs()) {
                logger.info(format("Dubbo dump directory[%s] created", dumpDirectory.getAbsolutePath()));
            } else {
                logger.warn(format("Dubbo dump directory[%s] can't be created, use the 'user.home'[%s]",
                        dumpDirectory.getAbsolutePath(), USER_HOME));
                return USER_HOME;
            }
        }
        return dumpPath;
    }
}
