/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.dubbo.common.timer;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 * <p>
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 * <p>
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 * <p>
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * 每当实例化和启动时都会创建一个新线程。因此，您应该确保只创建一个实例并在您的应用程序中共享它。
 * 使您的应用程序无响应的常见错误之一是为每个连接创建一个新实例
 *
 * <h3>Implementation Details</h3>
 * <p>
 * {@link HashedWheelTimer} is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 *
 * <p>
 * <a href="https://www.cnblogs.com/luozhiyun/p/12075326.html">时间轮算法（TimingWheel）是如何实现的？</a>
 */
public class HashedWheelTimer implements Timer {

    /**
     * may be in spi?
     */
    public static final String NAME = "hased";

    private static final Logger logger = LoggerFactory.getLogger(HashedWheelTimer.class);

    /**
     * 记录全局的HashedWheelTimer的实例，实例太多的话，则会打印一个错误警告信息
     */
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();

    /**
     * 打印错误警告一次之后则不会再打印了
     */
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();

    /**
     * 超过64个HashedWheelTimer对象则会报警告错误
     */
    private static final int INSTANCE_COUNT_LIMIT = 64;
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");

    /**
     * 定时器的主要工作处理者
     */
    private final Worker worker = new Worker();

    /**
     * 当前定时器的线程对象
     */
    private final Thread workerThread;

    /**
     * 初始化状态
     */
    private static final int WORKER_STATE_INIT = 0;

    /**
     * 已开始状态
     */
    private static final int WORKER_STATE_STARTED = 1;

    /**
     * 被关闭状态
     */
    private static final int WORKER_STATE_SHUTDOWN = 2;

    /**
     * 0 - init, 1 - started, 2 - shut down ，当前Timer的状态
     */
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    private volatile int workerState;

    /**
     * 默认100毫秒一次转动，dubbo是1秒一次
     */
    private final long tickDuration;

    /**
     * 哈希轮对象数组，Dubbo使用了32的长度
     */
    private final HashedWheelBucket[] wheel;

    /**
     * 此值是wheel.length - 1的值，主要用于hash计算桶的索引
     */
    private final int mask;
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);

    /**
     * 等待加入时间轮的任务队列
     */
    private final Queue<HashedWheelTimeout> timeouts = new LinkedBlockingQueue<>();

    /**
     * 等待被取消的任务队列
     */
    private final Queue<HashedWheelTimeout> cancelledTimeouts = new LinkedBlockingQueue<>();
    private final AtomicLong pendingTimeouts = new AtomicLong(0);

    /**
     * 最大的任务数量，默认100，由 FailbackClusterInvoker failbackTasks属性传递过来，默认是-1不限制
     */
    private final long maxPendingTimeouts;

    /**
     * 此时间表示的是时间轮工作线程的启动时间，初始化在Worker对象的run中。
     */
    private volatile long startTime;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration the duration between tick
     * @param unit         the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, -1);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory      a {@link ThreadFactory} that creates a
     *                           background {@link Thread} which is dedicated to
     *                           {@link TimerTask} execution.
     * @param tickDuration       the duration between tick
     * @param unit               the time unit of the {@code tickDuration}
     * @param ticksPerWheel      the size of the wheel
     * @param maxPendingTimeouts The maximum number of pending timeouts after which call to
     *                           {@code newTimeout} will result in
     *                           {@link java.util.concurrent.RejectedExecutionException}
     *                           being thrown. No maximum pending timeouts limit is assumed if
     *                           this value is 0 or negative.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel,
            long maxPendingTimeouts) {

        // 这里有几个细节之处：
        // 1、调用createWheel方法创建的wheel数组一定是2次方数，比如传入的ticksPerWheel是6，那么初始化的wheel长度一定是8。这样做是为了让mask & tick 来计算出槽位；
        // 2、tickDuration用的是纳秒
        // 3、在构造里面并不会里面启动时间轮，而是要等到有第一个任务加入到时间轮的时候才启动。
        // 4、在构造器里面会将工作线程worker封装成workerThread

        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (tickDuration <= 0) {
            throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration);
        }
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }

        // Normalize ticksPerWheel to power of two and initialize the wheel.
        // 初始化当前时间轮的大小，为2的幂次方，方便用于定位数组索引
        wheel = createWheel(ticksPerWheel);
        // 这是一个标示符，用来快速计算任务应该呆的格子。
        // 我们知道，给定一个deadline的定时任务，其应该呆的格子=deadline%wheel.length.但是%操作是个相对耗时的操作，所以使用一种变通的位运算代替：
        // 因为一圈的长度为2的n次方，mask = 2^n-1后低位将全部是1，然后deadline&mast == deadline%wheel.length
        // java中的HashMap在进行hash之后，进行index的hash寻址寻址的算法也是和这个一样的
        mask = wheel.length - 1;

        // 将时间轮转动一次的时间转换为纳秒，dubbo传入的是1秒的纳秒
        // Convert tickDuration to nanos.
        this.tickDuration = unit.toNanos(tickDuration);

        // Prevent overflow.
        // 校验是否存在溢出。即指针转动的时间间隔不能太长而导致tickDuration*wheel.length>Long.MAX_VALUE
        if (this.tickDuration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format(
                    "tickDuration: %d (expected: 0 < tickDuration in nanos < %d",
                    tickDuration, Long.MAX_VALUE / wheel.length));
        }
        // 默认初始化一个单线程
        workerThread = threadFactory.newThread(worker);
        // 最大的任务数量，默认100，由 FailbackClusterInvoker failbackTasks属性传递过来，默认是-1不限制
        this.maxPendingTimeouts = maxPendingTimeouts;

        // 如果HashedWheelTimer实例太多，那么就会打印一个error日志
        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT &&
                WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // This object is going to be GCed and it is assumed the ship has sailed to do a proper shutdown. If
            // we have not yet shutdown then we want to make sure we decrement the active instance count.
            // 将全局的HashedWheelTimer实例计数 - 1，必须传状态不是shutdown。
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }

    /**
     * 创建HashedWheelBucket数组，按照2次幂的规则创建指定大小的数组
     * @param ticksPerWheel 创建的数量要求，默认512，实际Dubbo传入的是32
     * @return HashedWheelBucket[]
     */
    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException(
                    "ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }
        if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException(
                    "ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        }

        // 获取最近的一个2次幂的数值
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        // 创建出指定数量的桶
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i++) {
            wheel[i] = new HashedWheelBucket();// 桶内部对象维护了头尾链表
        }
        return wheel;
    }

    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = ticksPerWheel - 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 2;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 4;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 8;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 16;
        return normalizedTicksPerWheel + 1;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     *
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     */
    public void start() {
        // 首次初始化的时候此方法才有用，否则无用或者被shutdown后抛出异常。主要用于初始化此类的startTime的时间。
        // workerState一开始的时候是0（WORKER_STATE_INIT），然后才会设置为1（WORKER_STATE_STARTED）
        switch (WORKER_STATE_UPDATER.get(this)) {
            case WORKER_STATE_INIT:
                // 使用cas来获取启动调度的权力，只有竞争到的线程允许来进行实例启动
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    // 如果成功设置了workerState，那么就调用workerThread线程
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // Wait until the startTime is initialized by the worker.
        // 等待worker线程初始化时间轮的启动时间
        while (startTime == 0) {
            try {
                // 这里使用countDownLatch来确保调度的线程已经被启动
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    @Override
    public Set<Timeout> stop() {
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                            ".stop() cannot be called from " +
                            TimerTask.class.getSimpleName());
        }

        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }

            return Collections.emptySet();
        }

        try {
            boolean interrupted = false;
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            INSTANCE_COUNTER.decrementAndGet();
        }
        return worker.unprocessedTimeouts();
    }

    @Override
    public boolean isStop() {
        return WORKER_STATE_SHUTDOWN == WORKER_STATE_UPDATER.get(this);
    }

    // 任务放入时间轮
    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        // 1、如果时间轮没有启动，那么就调用start方法启动时间轮，启动时间轮之后会为startTime设置为当前时间；
        // 2、计算延迟时间deadline；
        // 3、将task任务封装到HashedWheelTimeout中，然后添加到timeouts队列中进行缓存。

        if (task == null) {
            throw new NullPointerException("task");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        // 当前正在执行的任务数 +1
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();

        // 如果当前任务数超过了maxPendingTimeouts，则抛出RejectedExecutionException异常
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                    + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                    + "timeouts (" + maxPendingTimeouts + ")");
        }

        // 这里在启动工作线程后，会暂停等待一点点时间，完成startTime的初始化，主要是为了精准的控制线程启动的时间来计算deadline的时间
        // 如果时间轮已启动，则无任何效果
        start();

        // Add the timeout to the timeout queue which will be processed on the next tick.
        // During processing all the queued HashedWheelTimeouts will be added to the correct HashedWheelBucket.
        // 计算到期执行的时间，dubbo传入的是5秒，此处deadline计算的是时间轮启动为止到当前时间5秒后的一个纳秒数
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        // Guard against overflow.
        // 在delay为正数的情况下，deadline是不可能为负数
        // 如果为负数，那么说明超过了long的最大值
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }
        // 这里定时任务不是直接加到对应的格子中，而是先加入到一个队列里，然后等到下一个tick的时候，
        // 会从队列里取出最多100000个任务加入到指定的格子中
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        // Worker会去处理timeouts队列里面的数据
        timeouts.add(timeout);
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    private static void reportTooManyInstances() {
        String resourceType = ClassUtils.simpleClassName(HashedWheelTimer.class);
        logger.error("You are creating too many " + resourceType + " instances. " +
                resourceType + " is a shared resource that must be reused across the JVM," +
                "so that only a few instances are created.");
    }

    // 时间轮的启动在HashedWheelTimer的内部类Worker中。
    // 调用workerThread#start方法会调用Worker的run方法启动时间轮。
    private final class Worker implements Runnable {
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        /**
         * 当前时间轮转动的次数
         */
        private long tick;

        @Override
        public void run() {
            // 1、时间轮运行的时候首先会记录一下启动时间（startTime），然后调用startTimeInitialized释放外层的等待线程；
            // 2、进入do while循环，调用waitForNextTick睡眠等待到下一次的tick指针的跳动，并返回当前时间减去startTime作为deadline；
            // 3、由于mask= wheel.length -1 ，wheel是2的次方数，所以可以直接用tick & mask 计算出此次在wheel中的槽位；
            // 4、调用processCancelledTasks将cancelledTimeouts队列中的任务取出来，并将当前的任务从时间轮中移除；
            // 5、调用transferTimeoutsToBuckets方法将timeouts队列中缓存的数据取出加入到时间轮中；
            // 6、运行目前指针指向的槽位中的bucket链表数据；
            // 7、遍历整个wheel，将过期的bucket放入到unprocessedTimeouts队列中；
            // 8、将timeouts中过期的bucket放入到unprocessedTimeouts队列中

            // Initialize the startTime.
            startTime = System.nanoTime();
            if (startTime == 0) {
                // We use 0 as an indicator for the uninitialized value here, so make sure it's not 0 when initialized.
                startTime = 1;
            }

            // HashedWheelTimer的start方法会继续往下运行
            // Notify the other threads waiting for the initialization at start().
            startTimeInitialized.countDown();

            do {
                // 返回的是当前的nanoTime- startTime，也就是返回的是 每 tick 一次的时间间隔
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    // 算出时间轮的槽位
                    int idx = (int) (tick & mask);
                    // 移除cancelledTimeouts中的bucket，从bucket中移除timeout
                    processCancelledTasks();
                    // 将newTimeout()方法中加入到待处理定时任务队列中的任务加入到指定的格子中
                    HashedWheelBucket bucket = wheel[idx];
                    // 将timeouts队列中缓存的数据取出加入到时间轮中
                    transferTimeoutsToBuckets();
                    // 当指针跳到时间轮的槽位的时间，会将槽位的HashedWheelBucket取出来，然后遍历链表，运行其中到期的任务。
                    bucket.expireTimeouts(deadline);
                    tick++;
                }
                // 校验如果workerState是started状态，那么就一直循环
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            // 所有的过期但未被处理的bucket会在调用stop方法的时候返回unprocessedTimeouts队列中的数据。所以unprocessedTimeouts中的数据只是做一个记录，并不会再次被执行。
            // Fill the unprocessedTimeouts so we can return them from stop() method.
            for (HashedWheelBucket bucket : wheel) {
                bucket.clearTimeouts(unprocessedTimeouts);
            }
            for (; ; ) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                // 如果有没有被处理的timeout，那么加入到unprocessedTimeouts对列中
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }
            // 处理被取消的任务
            processCancelledTasks();
        }

        /**
         * <p>将timeouts队列中缓存的数据取出加入到时间轮中，最多读取10万个HashedWheelTimeout对象
         *
         * <p>然后根据HashedWheelTimeout的deadline延迟时间计算出时间轮需要运行多少次才能运行当前的任务，如果当前的任务延迟时间大于时间轮跑一圈所需要的时间，那么就计算需要跑几圈才能到这个任务运行。
         *
         * <p>最后计算出该任务在时间轮中的槽位，添加到时间轮的链表中
         */
        private void transferTimeoutsToBuckets() {
            // transfer only max. 100000 timeouts per tick to prevent a thread to stale the workerThread when it just
            // adds new timeouts in a loop.
            // 每次tick只处理10w个任务，以免阻塞worker线程
            for (int i = 0; i < 100000; i++) {
                HashedWheelTimeout timeout = timeouts.poll();
                // 当获取到空则代表所有的都处理了
                if (timeout == null) {
                    // all processed
                    break;
                }
                // 处理期间，获取的任务被取消了，跳过
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    // Was cancelled in the meantime.
                    continue;
                }

                // calculated = tick 次数
                long calculated = timeout.deadline / tickDuration;
                // 算剩余的轮数, 只有 timer 走够轮数, 并且到达了 task 所在的 slot, task 才会过期
                timeout.remainingRounds = (calculated - tick) / wheel.length;

                // Ensure we don't schedule for past.
                // 如果任务在timeouts队列里面放久了, 以至于已经过了执行时间, 这个时候就使用当前tick, 也就是放到当前bucket, 此方法调用完后就会被执行
                final long ticks = Math.max(calculated, tick);
                // 算出任务应该插入的 wheel 的 slot, slotIndex = tick 次数 & mask, mask = wheel.length - 1
                int stopIndex = (int) (ticks & mask);

                HashedWheelBucket bucket = wheel[stopIndex];
                // 将timeout加入到bucket链表中
                bucket.addTimeout(timeout);
            }
        }

        private void processCancelledTasks() {
            for (; ; ) {
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * 这个方法就是根据当前的时间计算出跳动到下个时间的间隔时间，并进行sleep操作，然后返回当前时间距离时间轮启动时间的时间段。
         *
         * calculate goal nanoTime from startTime and current tick number,
         * then wait until that goal has been reached.
         *
         * @return Long.MIN_VALUE if received a shutdown request,
         * current time otherwise (with Long.MIN_VALUE changed by +1)
         */
        private long waitForNextTick() {
            // tickDuration这里是1秒
            // tick表示总tick数
            long deadline = tickDuration * (tick + 1);

            for (; ; ) {
                // 当前距离时间轮启动的纳秒数
                final long currentTime = System.nanoTime() - startTime;
                // 计算需要sleep的时间, 之所以加999999后再除10000000,前面是tick=1所以这里需要减去1秒的纳秒数，（+999999,其实是为了上取整）
                // 才能计算准确，还有通过这里可以看到 其实线程是以睡眠一定的时候再来执行下一个ticket的任务的，
                // 这样如果ticket的间隔设置的太小的话，系统会频繁的睡眠然后启动，
                // 其实感觉影响部分的性能，所以为了更好的利用系统资源步长可以稍微设置大点
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;
                // sleepTimeMs小于零表示走到了下一个时间轮位置
                if (sleepTimeMs <= 0) {
                    // 这里不允许返回Long.MIN_VALUE值，因为此值代表时间轮被关闭了
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }

                // Check if we run on windows, as if thats the case we will need
                // to round the sleepTime as workaround for a bug that only affect
                // the JVM if it runs on windows.
                //
                // See https://github.com/netty/netty/issues/356

                // 这里是因为windows平台的定时调度最小单位为10ms，如果不是10ms的倍数，可能会引起sleep时间不准确
                if (isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                }

                try {
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    // 判断是否被关闭了，如果关闭了则返回Long.MIN_VALUE
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    private static final class HashedWheelTimeout implements Timeout {

        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        private final HashedWheelTimer timer;
        private final TimerTask task;
        private final long deadline;

        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization"})
        private volatile int state = ST_INIT;

        /**
         * RemainingRounds will be calculated and set by Worker.transferTimeoutsToBuckets() before the
         * HashedWheelTimeout will be added to the correct HashedWheelBucket.
         */
        long remainingRounds;

        /**
         * This will be used to chain timeouts in HashedWheelTimerBucket via a double-linked-list.
         * As only the workerThread will act on it there is no need for synchronization / volatile.
         */
        HashedWheelTimeout next;
        HashedWheelTimeout prev;

        /**
         * The bucket to which the timeout was added
         */
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean cancel() {
            // only update the state it will be removed from HashedWheelBucket on next tick.
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            // If a task should be canceled we put this to another queue which will be processed on each tick.
            // So this means that we will have a GC latency of max. 1 tick duration which is good enough. This way
            // we can make again use of our MpscLinkedQueue and so minimize the locking / overhead as much as possible.
            timer.cancelledTimeouts.add(this);
            return true;
        }

        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        public int state() {
            return state;
        }

        @Override
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        public void expire() {
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;
            String simpleClassName = ClassUtils.simpleClassName(this.getClass());

            StringBuilder buf = new StringBuilder(192)
                    .append(simpleClassName)
                    .append('(')
                    .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                        .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                        .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                    .append(task())
                    .append(')')
                    .toString();
        }
    }

    /**
     * Bucket that stores HashedWheelTimeouts. These are stored in a linked-list like datastructure to allow easy
     * removal of HashedWheelTimeouts in the middle. Also the HashedWheelTimeout act as nodes themself and so no
     * extra object creation is needed.
     */
    private static final class HashedWheelBucket {

        /**
         * Used for the linked-list datastructure
         */
        private HashedWheelTimeout head;
        private HashedWheelTimeout tail;

        /**
         * Add {@link HashedWheelTimeout} to this bucket.
         */
        void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * Expire all {@link HashedWheelTimeout}s for the given {@code deadline}.
         *
         * 过期并执行格子中的到期任务，tick到该格子的时候，worker线程会调用这个方法，根据deadline和remainingRounds判断任务是否过期。<br>
         *
         * HashedWheelBucket是一个链表，所以我们需要从head节点往下进行遍历。如果链表没有遍历到链表尾部那么就继续往下遍历。<br>
         *
         * 获取的timeout节点节点，如果剩余轮数remainingRounds大于0，那么就说明要到下一圈才能运行，所以将剩余轮数减一；<br>
         *
         * 如果当前剩余轮数小于等于零了，那么就将当前节点从bucket链表中移除，并判断一下当前的时间是否大于timeout的延迟时间，如果是则调用timeout的expire执行任务。<br>
         */
        void expireTimeouts(long deadline) {
            HashedWheelTimeout timeout = head;

            // process all timeouts
            // 遍历格子中的所有定时任务
            while (timeout != null) {
                // 先保存next，因为移除后next将被设置为null
                HashedWheelTimeout next = timeout.next;
                if (timeout.remainingRounds <= 0) {
                    // 从bucket链表中移除当前timeout，并返回链表中下一个timeout
                    next = remove(timeout);
                    // 如果timeout的时间小于当前的时间，那么就调用expire执行task
                    if (timeout.deadline <= deadline) {
                        timeout.expire();
                    } else {
                        // 不可能发生的情况，就是说round已经为0了，deadline却>当前槽的deadline
                        // The timeout was placed into a wrong slot. This should never happen.
                        throw new IllegalStateException(String.format(
                                "timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                // 被取消了，移除
                } else if (timeout.isCancelled()) {
                    next = remove(timeout);
                } else {
                    // 因为当前的槽位已经过了，说明已经走了一圈了，把轮数减一
                    timeout.remainingRounds--;
                }
                // 把指针放置到下一个timeout
                timeout = next;
            }
        }

        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * Clear this bucket and return all not expired / cancelled {@link Timeout}s.
         */
        void clearTimeouts(Set<Timeout> set) {
            for (; ; ) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head = null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }
    
    private static final boolean IS_OS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win");
    
    private boolean isWindows() {
    	return IS_OS_WINDOWS;
    }
}
