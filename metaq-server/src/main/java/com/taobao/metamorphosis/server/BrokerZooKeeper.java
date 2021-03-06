package com.taobao.metamorphosis.server;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;

import org.I0Itec.zkclient.IZkStateListener;
import org.I0Itec.zkclient.ZkClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.zookeeper.Watcher.Event.KeeperState;

import com.taobao.diamond.manager.DiamondManager;
import com.taobao.diamond.manager.ManagerListener;
import com.taobao.diamond.manager.impl.DefaultDiamondManager;
import com.taobao.gecko.core.util.ConcurrentHashSet;
import com.taobao.metamorphosis.cluster.Broker;
import com.taobao.metamorphosis.network.RemotingUtils;
import com.taobao.metamorphosis.server.utils.MetaConfig;
import com.taobao.metamorphosis.server.utils.TopicConfig;
import com.taobao.metamorphosis.utils.DiamondUtils;
import com.taobao.metamorphosis.utils.MetaZookeeper;
import com.taobao.metamorphosis.utils.ZkUtils;
import com.taobao.metamorphosis.utils.ZkUtils.ZKConfig;


/**
 * Broker与ZK交互，注册broker和topic等
 * 
 * @author boyan
 * @Date 2011-4-25
 * @author wuhua
 * @Date 2011-6-24
 * 
 */
public class BrokerZooKeeper {
    private final MetaConfig config;
    private ZkClient zkClient;

    private ZKConfig zkConfig;

    private String brokerIdPath;

    private final Set<String> topics = new ConcurrentHashSet<String>();

    static final Log log = LogFactory.getLog(BrokerZooKeeper.class);

    private DiamondManager diamondManager;

    private volatile boolean registerBrokerInZkFail = false;

    private MetaZookeeper metaZookeeper;


    public ZkClient getZkClient() {
        return this.zkClient;
    }


    public MetaZookeeper getMetaZookeeper() {
        return this.metaZookeeper;
    }


    public BrokerZooKeeper(final MetaConfig metaConfig) {
        this.config = metaConfig;

        this.zkConfig = metaConfig.getZkConfig();
        if (this.zkConfig == null) {
            this.zkConfig = this.loadZkConfigFromDiamond();
        }
        else if (this.zkConfig.zkConnect == null) {
            boolean zkEnable = this.zkConfig.zkEnable;
            this.zkConfig = this.loadZkConfigFromDiamond();
            this.zkConfig.zkEnable = zkEnable;
        }

        this.start(this.zkConfig);
        this.brokerIdPath = this.metaZookeeper.brokerIdsPathOf(metaConfig.getBrokerId(), metaConfig.getSlaveId());
    }


    private ZKConfig loadZkConfigFromDiamond() {
        ManagerListener managerListener = new ManagerListener() {
            private volatile boolean firstCall = true;


            @Override
            public void receiveConfigInfo(final String configInfo) {
                if (firstCall) {
                    firstCall = false;
                    log.info("It's first call, Receiving new diamond zk config:" + configInfo);
                    return;
                }

                log.info("Receiving new diamond zk config:" + configInfo);
                log.info("Closing zk client");
                try {
                    BrokerZooKeeper.this.unregisterBrokerInZk();
                    BrokerZooKeeper.this.unregisterTopics();
                    BrokerZooKeeper.this.zkClient.close();
                    final Properties properties = new Properties();
                    properties.load(new StringReader(configInfo));
                    final ZKConfig zkConfig = DiamondUtils.getZkConfig(properties);
                    Thread.sleep(zkConfig.zkSyncTimeMs);
                    BrokerZooKeeper.this.start(zkConfig);
                    BrokerZooKeeper.this.reRegisterEveryThing();
                    log.info("Process new diamond zk config successfully");
                }
                catch (final Exception e) {
                    log.error("从diamond加载zk配置失败", e);
                }

            }


            @Override
            public Executor getExecutor() {
                return null;
            }
        };
        // 尝试从diamond获取
        this.diamondManager =
                new DefaultDiamondManager(this.config.getDiamondZKGroup(), this.config.getDiamondZKDataId(),
                    managerListener);

        ZKConfig zkConfig = DiamondUtils.getZkConfig(this.diamondManager, 10000);
        this.diamondManager.addListeners(Arrays.asList(managerListener));
        return zkConfig;
    }


    private void start(final ZKConfig zkConfig) {
        log.info("Initialize zk client...");
        this.zkClient =
                new ZkClient(zkConfig.zkConnect, zkConfig.zkSessionTimeoutMs, zkConfig.zkConnectionTimeoutMs,
                    new ZkUtils.StringSerializer());
        this.zkClient.subscribeStateChanges(new SessionExpireListener());
        this.metaZookeeper = new MetaZookeeper(this.zkClient, zkConfig.zkRoot);
    }


    /**
     * 注册broker到zk
     * 
     * @throws Exception
     */
    public void registerBrokerInZk() throws Exception {
        if (!this.zkConfig.zkEnable) {
            return;
        }
        try {
            log.info("Registering broker " + this.brokerIdPath);
            final String hostName =
                    this.config.getHostName() == null ? RemotingUtils.getLocalAddress() : this.config
                        .getHostName();
            final Broker broker =
                    new Broker(this.config.getBrokerId(), hostName, this.config.getServerPort(),
                        this.config.getSlaveId());

            ZkUtils.createEphemeralPath(this.zkClient, this.brokerIdPath, broker.getZKString());

            // 兼容老客户端，暂时加上
            if (!this.config.isSlave()) {
                ZkUtils.updateEphemeralPath(this.zkClient,
                    this.metaZookeeper.brokerIdsPath + "/" + this.config.getBrokerId(), broker.getZKString());
                log.info("register for old client version " + this.metaZookeeper.brokerIdsPath + "/"
                        + this.config.getBrokerId() + "  succeeded with " + broker);

            }
            log.info("Registering broker " + this.brokerIdPath + " succeeded with " + broker);
            this.registerBrokerInZkFail = false;
        }
        catch (final Exception e) {
            this.registerBrokerInZkFail = true;
            log.error("注册broker失败");
            throw e;
        }
    }


    private void unregisterBrokerInZk() throws Exception {
        if (this.registerBrokerInZkFail) {
            log.warn("上次注册broker未成功,不需要unregister");
            return;
        }
        ZkUtils.deletePath(this.zkClient, this.brokerIdPath);
        // 兼容老客户端，暂时加上.
        if (!this.config.isSlave()) {
            try {
                ZkUtils.deletePath(this.zkClient,
                    this.metaZookeeper.brokerIdsPath + "/" + this.config.getBrokerId());
            }
            catch (final Exception e) {
                // 有slave时是删不掉的,写个空值进去
                ZkUtils.updateEphemeralPath(this.zkClient,
                    this.metaZookeeper.brokerIdsPath + "/" + this.config.getBrokerId(), "");
            }
            log.info("delete broker of old client version " + this.metaZookeeper.brokerIdsPath + "/"
                    + this.config.getBrokerId());
        }
    }


    private void unregisterTopics() throws Exception {
        for (final String topic : BrokerZooKeeper.this.topics) {
            final String brokerTopicPath =
                    this.metaZookeeper.brokerTopicsPathOf(topic, this.config.getBrokerId(),
                        this.config.getSlaveId());
            ZkUtils.deletePath(this.zkClient, brokerTopicPath);

            // 兼容老客户端，暂时加上
            if (!this.config.isSlave()) {
                ZkUtils.deletePath(this.zkClient, this.metaZookeeper.brokerTopicsPath + "/" + topic + "/"
                        + this.config.getBrokerId());
                log.info("delete topic of old client version " + this.metaZookeeper.brokerTopicsPath + "/" + topic
                        + "/" + this.config.getBrokerId());
            }
        }
    }


    /**
     * 注册topic和分区信息到zk
     * 
     * @param topic
     * @throws Exception
     */
    public void registerTopicInZk(final String topic) throws Exception {
        if (!this.topics.add(topic)) {
            return;
        }
        this.registerTopicInZkInternal(topic);
    }


    public ZKConfig getZkConfig() {
        return this.zkConfig;
    }


    public Set<String> getTopics() {
        return this.topics;
    }


    public void reRegisterEveryThing() throws Exception {
        log.info("re-registering broker info in ZK for broker " + BrokerZooKeeper.this.config.getBrokerId());
        BrokerZooKeeper.this.registerBrokerInZk();
        log.info("re-registering broker topics in ZK for broker " + BrokerZooKeeper.this.config.getBrokerId());
        for (final String topic : BrokerZooKeeper.this.topics) {
            BrokerZooKeeper.this.registerTopicInZkInternal(topic);
        }
        log.info("done re-registering broker");
    }


    private void registerTopicInZkInternal(final String topic) throws Exception {
        if (!this.zkConfig.zkEnable) {
            return;
        }
        final String brokerTopicPath =
                this.metaZookeeper.brokerTopicsPathOf(topic, this.config.getBrokerId(), this.config.getSlaveId());
        final TopicConfig topicConfig = this.config.getTopicConfig(topic);
        Integer numParts = topicConfig != null ? topicConfig.getNumPartitions() : this.config.getNumPartitions();
        numParts = numParts == null ? this.config.getNumPartitions() : numParts;
        log.info("Begin registering broker topic " + brokerTopicPath + " with " + numParts + " partitions");

        ZkUtils.createEphemeralPath(this.zkClient, brokerTopicPath, String.valueOf(numParts));

        // 兼容老客户端，暂时加上
        if (!this.config.isSlave()) {
            ZkUtils.createEphemeralPath(this.zkClient, this.metaZookeeper.brokerTopicsPath + "/" + topic + "/"
                    + this.config.getBrokerId(), String.valueOf(numParts));
            log.info("register topic for old client version " + this.metaZookeeper.brokerTopicsPath + "/" + topic
                    + "/" + this.config.getBrokerId());
        }
        log.info("End registering broker topic " + brokerTopicPath);
    }


    public void close() {
        if (this.diamondManager != null) {
            this.diamondManager.close();
        }
        try {
            if (this.zkConfig.zkEnable) {
                this.unregisterBrokerInZk();
                this.unregisterTopics();
            }
        }
        catch (final Exception e) {
            log.warn("error on unregisterBrokerInZk", e);
        }
        if (this.zkClient != null) {
            log.info("Closing zk client...");
            this.zkClient.close();
        }
    }

    private class SessionExpireListener implements IZkStateListener {

        @Override
        public void handleNewSession() throws Exception {
            BrokerZooKeeper.this.reRegisterEveryThing();

        }


        @Override
        public void handleStateChanged(final KeeperState state) throws Exception {
            // do nothing, since zkclient will do reconnect for us.

        }

    }


    /**
     * 重新计算brokerIdPath
     */
    public void resetBrokerIdPath() {
        this.brokerIdPath =
                this.metaZookeeper.brokerIdsPathOf(this.config.getBrokerId(), this.config.getSlaveId());
    }
}
