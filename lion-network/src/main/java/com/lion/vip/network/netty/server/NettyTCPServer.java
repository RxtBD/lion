package com.lion.vip.network.netty.server;

import com.lion.vip.api.service.BaseService;
import com.lion.vip.api.service.Listener;
import com.lion.vip.api.service.Server;
import com.lion.vip.api.service.ServiceException;
import com.lion.vip.network.netty.codec.PacketDecoder;
import com.lion.vip.network.netty.codec.PacketEncoder;
import com.lion.vip.tools.common.Strings;
import com.lion.vip.tools.thread.ThreadNames;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import static com.lion.vip.tools.Utils.useNettyEpoll;

public abstract class NettyTCPServer extends BaseService implements Server {
    private final Logger LOGGER = LoggerFactory.getLogger(this.getClass());

    public enum State {
        Created,
        Initialized,
        Starting,
        Started,
        Shutdown
    };

    protected final AtomicReference<State> serverState = new AtomicReference<>(State.Created);

    private final String host;
    private final int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public NettyTCPServer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public NettyTCPServer(int port) {
        this.port = port;
        this.host = null;
    }

    public EventLoopGroup getBossGroup() {
        return bossGroup;
    }

    public void setBossGroup(EventLoopGroup bossGroup) {
        this.bossGroup = bossGroup;
    }

    public EventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    public void setWorkerGroup(EventLoopGroup workerGroup) {
        this.workerGroup = workerGroup;
    }

    /**
     * netty 默认的Executor为ThreadPerTaskExecutor
     * 线程池的使用在SingleThreadEventExecutor#doStartThread
     * eventLoop.execute(runnable);
     * 是比较重要的一个方法。在没有启动真正线程时，
     * 它会启动线程并将待执行任务放入执行队列里面。
     * 启动真正线程(startThread())会判断是否该线程已经启动，
     * 如果已经启动则会直接跳过，达到线程复用的目的
     *
     * @return
     */
    protected ThreadFactory getBossThreadFactory() {
        return new DefaultThreadFactory(getBossThreadName());
    }

    protected ThreadFactory getWorkThreadFactory() {
        return new DefaultThreadFactory(getWorkThreadName());
    }

    protected int getBossThreadNum() {
        return 1;
    }

    protected int getWorkThreadNum() {
        return 0;
    }

    protected String getBossThreadName() {
        return ThreadNames.T_BOSS;
    }

    protected String getWorkThreadName() {
        return ThreadNames.T_WORKER;
    }

    protected int getIoRate() {
        return 70;
    }

    public ChannelFactory<? extends ServerChannel> getChannelFactory() {
        return NioServerSocketChannel::new;
    }

    public SelectorProvider getSelectorProvider() {
        return SelectorProvider.provider();
    }

    @Override
    public void init() {
        //期望是Created，要更新为Initialized
        if (!serverState.compareAndSet(State.Created, State.Initialized)) {
            throw new ServiceException("Server already inited");
        }
    }

    @Override
    public boolean isRunning() {
        return serverState.get() == State.Started;
    }

    @Override
    public void stop(Listener listener) {
        if (!serverState.compareAndSet(State.Started, State.Shutdown)) {
            if (listener != null) {
                listener.onFailure(new ServiceException("server was already shutdown"));
            }
            LOGGER.error("{} was already shutdown.", this.getClass().getSimpleName());
            return;
        }

        LOGGER.info("try shutdown {} ...", this.getClass().getSimpleName());

        // 先关闭接收业务的main reactor
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }

        //再关闭处理具体业务的sub reactor
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }

        LOGGER.info("{} shutdown success", this.getClass().getSimpleName());
        if (listener != null) {
            listener.onSuccess(port);
        }
    }

    @Override
    public void start(Listener listener) {
        if (!serverState.compareAndSet(State.Initialized, State.Starting)) {
            throw new ServiceException("server already started or has not init");
        }

        if (useNettyEpoll()) {
            createEpollServer(listener);
        } else {
            createNioServer(listener);
        }
    }

    private void createNioServer(Listener listener) {
        EventLoopGroup bossGroup = getBossGroup();
        EventLoopGroup workerGroup = getWorkerGroup();

        if (bossGroup == null) {
            NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(getBossThreadNum(), getBossThreadFactory());
            nioEventLoopGroup.setIoRatio(100);
            bossGroup = nioEventLoopGroup;
        }

        if (workerGroup == null) {
            NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(getWorkThreadNum(), getWorkThreadFactory());
            nioEventLoopGroup.setIoRatio(0);
            workerGroup = nioEventLoopGroup;
        }

        createServer(listener, bossGroup, workerGroup, getChannelFactory());
    }

    private void createEpollServer(Listener listener) {
        EventLoopGroup bossGroup = getBossGroup();
        EventLoopGroup workerGroup = getWorkerGroup();

        if (bossGroup == null) {
            EpollEventLoopGroup epollEventLoopGroup = new EpollEventLoopGroup(getBossThreadNum(), getBossThreadFactory());
            epollEventLoopGroup.setIoRatio(100);
            bossGroup = epollEventLoopGroup;
        }

        if (workerGroup == null) {
            EpollEventLoopGroup epollEventLoopGroup = new EpollEventLoopGroup(getWorkThreadNum(), getWorkThreadFactory());
            epollEventLoopGroup.setIoRatio(0);
            workerGroup = epollEventLoopGroup;
        }

        createServer(listener, bossGroup, workerGroup, getChannelFactory());
    }

    private void createServer(Listener listener, EventLoopGroup bossGroup, EventLoopGroup workerGroup, ChannelFactory<? extends ServerChannel> channelFactory) {

        /**
         * NioEventLoopGroup 是用来处理IO操作的多线程事件循环器，
         * netty提供了许多不同的EventLoopGroup的实现用来处理不同传输协议。
         * 在一个服务端的应用会有2个NioEventLoopGroup会被使用：
         * （1）boss：用来接收进来的连接；
         * （2）worker：用来处理已经被接受的连接；
         * 一旦boss接收到连接，就会把连接信息注册到worker上。
         * 如何知道多少个线程已被使用，如何映射到已经创建的channels上都需要依赖于EventLoopGroup的实现，
         * 并且可以通过构造函数来配置他们的关系。
         */
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;

        try {
            // ServerBootstrap是一个NIO服务的辅助启动类，也可以在这个服务中直接使用channel
            ServerBootstrap serverBootstrap = new ServerBootstrap();

            //必须设置，否则会报错：java.lang.IllegalStateException: group not set异常
            serverBootstrap.group(bossGroup, workerGroup);

            //ServerSocketChannel以NIO的selector为基础进行实现的，用来接收新的连接
            //通过工厂来获取新的连接
            serverBootstrap.channelFactory(channelFactory);

            /**
             * 这里的事件处理类经常会被用来处理一个最近的已经接收的Channel。
             * ChannelInitializer是一个特殊的处理类，目的是帮助使用者配置一个新的Channel；
             * 也许你想通过增加爱一些处理类 比如NettyServerHandler来配置一个新的Channel，
             * 或者其对应的ChannelPipeline来实现你的网络程序。
             * 当你的程序变得复杂时，可能你会增加更多的处理类到pipeline上，
             * 然后提取这些匿名类到最顶层的类上。
             */
            serverBootstrap.childHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel channel) throws Exception {
                    initPipeline(channel.pipeline());
                }
            });

            initOptions(serverBootstrap);


            /***
             * 绑定端口并启动去接收进来的连接
             */
            InetSocketAddress address = Strings.isBlank(host) ? new InetSocketAddress(port) : new InetSocketAddress(host, port);
            serverBootstrap.bind(address).addListener(future -> {
                if (future.isSuccess()) {
                    serverState.set(State.Started);
                    LOGGER.info("server start success on : {}", port);
                    if (listener != null) {
                        listener.onSuccess(port);
                    }
                } else {
                    LOGGER.info("server start failure on : {}", port, future.cause());
                    if (listener != null) {
                        listener.onFailure(future.cause());
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.info("server start exception, ", e);
            if (listener != null) {
                listener.onFailure(e);
            }
            throw new ServiceException("server start exception, port = " + port, e);
        }

    }

    protected void initPipeline(ChannelPipeline pipeline) {
        pipeline.addLast("decoder", getDecoder());
        pipeline.addLast("encoder", getEncoder());
        pipeline.addLast("handler", getChannelHandler());
    }

    public abstract ChannelHandler getChannelHandler();

    protected ChannelHandler getDecoder() {
        return new PacketDecoder();
    }

    protected ChannelHandler getEncoder() {
        return PacketEncoder.INSTANCE;
    }

    /**
     * 初始化ServerBootstrap其他的配置参数
     * option（）提供给NioServerSocketChannel用来接收进来的连接；
     * childOption() 提供给父管道ServerChannel接收到的连接
     *
     * @param serverBootstrap
     */
    protected void initOptions(ServerBootstrap serverBootstrap) {
//        serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, false);    //使用应用层心跳

        /**
         * 在Netty 4中实现了一个新的ByteBug内存池，它是一个纯Java版本的jemalloc，facebook也在用。
         * 现在netty不会再因为用零填充缓冲区而浪费内存带宽了。不过它不依赖于GC，要小心内存泄漏；
         * 如果忘记在处理程序中释放缓冲区，那么内存使用率会无限地增长。
         * Netty默认不使用内存池，需要在创建客户端或者服务端的时候进行指定。
         */

        serverBootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        serverBootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }

}
