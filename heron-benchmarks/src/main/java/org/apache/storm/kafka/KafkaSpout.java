package org.apache.storm.kafka;

import com.google.common.base.Strings;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.storm.kafka.KafkaUtils.KafkaOffsetMetric;
import org.apache.storm.metric.api.IMetric;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copy from original KafkaSpout.
 */
public class KafkaSpout extends BaseRichSpout {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSpout.class);
    private SpoutConfig _spoutConfig;
    private SpoutOutputCollector _collector;
    private PartitionCoordinator _coordinator;
    private DynamicPartitionConnections _connections;
    private ZkState _state;
    private long _lastUpdateMs = 0L;
    private int _currPartitionIndex = 0;

    public KafkaSpout(SpoutConfig spoutConf) {
        this._spoutConfig = spoutConf;
    }

    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        this._collector = collector;
        String topologyInstanceId = context.getStormId();
        Map stateConf = new HashMap(conf);
        List<String> zkServers = this._spoutConfig.zkServers;
        if (zkServers == null) {
            zkServers = (List)conf.get("storm.zookeeper.servers");
        }

        Integer zkPort = this._spoutConfig.zkPort;
        if (zkPort == null) {
            zkPort = ((Number)conf.get("storm.zookeeper.port")).intValue();
        }

        stateConf.put("transactional.zookeeper.servers", zkServers);
        stateConf.put("transactional.zookeeper.port", zkPort);
        stateConf.put("transactional.zookeeper.root", this._spoutConfig.zkRoot);
        this._state = new ZkState(stateConf);
        this._connections = new DynamicPartitionConnections(this._spoutConfig, KafkaUtils.makeBrokerReader(conf, this._spoutConfig));
        int totalTasks = context.getComponentTasks(context.getThisComponentId()).size();
        if (this._spoutConfig.hosts instanceof StaticHosts) {
            this._coordinator = new StaticCoordinator(this._connections, conf, this._spoutConfig, this._state, context.getThisTaskIndex(), totalTasks, topologyInstanceId);
        } else {
            this._coordinator = new ZkCoordinator(this._connections, conf, this._spoutConfig, this._state, context.getThisTaskIndex(), totalTasks, topologyInstanceId);
        }

        context.registerMetric("kafkaOffset", new IMetric() {
            KafkaOffsetMetric _kafkaOffsetMetric;

            {
                this._kafkaOffsetMetric = new KafkaOffsetMetric(KafkaSpout.this._connections);
            }

            public Object getValueAndReset() {
                List<PartitionManager> pms = KafkaSpout.this._coordinator.getMyManagedPartitions();
                Set<Partition> latestPartitions = new HashSet();
                Iterator var3 = pms.iterator();

                PartitionManager pm;
                while(var3.hasNext()) {
                    pm = (PartitionManager)var3.next();
                    latestPartitions.add(pm.getPartition());
                }

                this._kafkaOffsetMetric.refreshPartitions(latestPartitions);
                var3 = pms.iterator();

                while(var3.hasNext()) {
                    pm = (PartitionManager)var3.next();
                    this._kafkaOffsetMetric.setOffsetData(pm.getPartition(), pm.getOffsetData());
                }

                return this._kafkaOffsetMetric.getValueAndReset();
            }
        }, this._spoutConfig.metricsTimeBucketSizeInSecs);
        context.registerMetric("kafkaPartition", new IMetric() {
            public Object getValueAndReset() {
                List<PartitionManager> pms = KafkaSpout.this._coordinator.getMyManagedPartitions();
                Map concatMetricsDataMaps = new HashMap();
                Iterator var3 = pms.iterator();

                while(var3.hasNext()) {
                    PartitionManager pm = (PartitionManager)var3.next();
                    concatMetricsDataMaps.putAll(pm.getMetricsDataMap());
                }

                return concatMetricsDataMaps;
            }
        }, this._spoutConfig.metricsTimeBucketSizeInSecs);
    }

    public void close() {
        this._state.close();
    }

    public void nextTuple() {
        List<PartitionManager> managers = this._coordinator.getMyManagedPartitions();

        for(int i = 0; i < managers.size(); ++i) {
            try {
                this._currPartitionIndex %= managers.size();
                EmitState state = ((PartitionManager)managers.get(this._currPartitionIndex)).next(this._collector);
                if (state != EmitState.EMITTED_MORE_LEFT) {
                    this._currPartitionIndex = (this._currPartitionIndex + 1) % managers.size();
                }

                if (state != EmitState.NO_EMITTED) {
                    break;
                }
            } catch (FailedFetchException var4) {
                LOG.warn("Fetch failed", var4);
                this._coordinator.refresh();
            }
        }

        long diffWithNow = System.currentTimeMillis() - this._lastUpdateMs;
        if (diffWithNow > this._spoutConfig.stateUpdateIntervalMs || diffWithNow < 0L) {
            this.commit();
        }

    }

    public void ack(Object msgId) {
        KafkaMessageId id = (KafkaMessageId)msgId;
        PartitionManager m = this._coordinator.getManager(id.partition);
        if (m != null) {
            m.ack(id.offset);
        }

    }

    public void fail(Object msgId) {
        KafkaMessageId id = (KafkaMessageId)msgId;
        PartitionManager m = this._coordinator.getManager(id.partition);
        if (m != null) {
            m.fail(id.offset);
        }

    }

    public void deactivate() {
        this.commit();
    }

    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        if (!Strings.isNullOrEmpty(this._spoutConfig.outputStreamId)) {
            declarer.declareStream(this._spoutConfig.outputStreamId, this._spoutConfig.scheme.getOutputFields());
        } else {
            declarer.declare(this._spoutConfig.scheme.getOutputFields());
        }

    }

    private void commit() {
        this._lastUpdateMs = System.currentTimeMillis();
        Iterator var1 = this._coordinator.getMyManagedPartitions().iterator();

        while(var1.hasNext()) {
            PartitionManager manager = (PartitionManager)var1.next();
            manager.commit();
        }

    }

    static enum EmitState {
        EMITTED_MORE_LEFT,
        EMITTED_END,
        NO_EMITTED;

        private EmitState() {
        }
    }

    static class KafkaMessageId implements Serializable {
        public Partition partition;
        public long offset;

        public KafkaMessageId(Partition partition, long offset) {
            this.partition = partition;
            this.offset = offset;
        }
    }
}