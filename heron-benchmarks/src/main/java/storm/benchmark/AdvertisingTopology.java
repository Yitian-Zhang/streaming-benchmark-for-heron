/**
 * Copyright 2015, Yahoo Inc.
 * Licensed under the terms of the Apache License 2.0. Please see LICENSE file in the project root for terms.
 */
package storm.benchmark;

import benchmark.common.Utils;
import benchmark.common.advertising.CampaignProcessorCommon;
import benchmark.common.advertising.RedisAdCampaignCache;
import org.apache.log4j.Logger;
import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.kafka.KafkaSpout;
import org.apache.storm.kafka.SpoutConfig;
import org.apache.storm.kafka.StringScheme;
import org.apache.storm.kafka.ZkHosts;
import org.apache.storm.spout.SchemeAsMultiScheme;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * This class is the topology for Heron.
 * It uses KafkaSpout(Storm) to get data from Kafka. And emit data streams into Heron.
 */
public class AdvertisingTopology {

    public static void main(String[] args) throws Exception {
        //  test-topo -conf $CONF_FILE
        TopologyBuilder builder = new TopologyBuilder();

        // Get configuration from localConf.yaml. (in resources)
        String configPath = "localConf.yaml";
        Map commonConfig = Utils.findAndReadConfigFile(configPath, true);
        String zkServerHosts = joinHosts((List<String>) commonConfig.get("zookeeper.servers"),
                Integer.toString((Integer) commonConfig.get("zookeeper.port")));
        String redisServerHost = (String) commonConfig.get("redis.host");
        String kafkaTopic = (String) commonConfig.get("kafka.topic");
        int kafkaPartitions = ((Number) commonConfig.get("kafka.partitions")).intValue();
        int workers = ((Number) commonConfig.get("storm.workers")).intValue();
        int ackers = ((Number) commonConfig.get("storm.ackers")).intValue();
        int cores = ((Number) commonConfig.get("process.cores")).intValue();
//        int parallel = Math.max(1, cores / 7);
        int parallel = 3;
        System.out.println("[zkServerHosts]: " + zkServerHosts + ". " +
                "[redisServerHost]: " + redisServerHost + ". " +
                "[kafkaTopic]: " + kafkaTopic + ". " +
                "[kafkaPartitions]: " + kafkaPartitions + ". " +
                "[workers]: " + workers + ". " +
                "[ackers]: " + ackers + ". " +
                "[cores]: " + cores + ". " +
                "[parallel]: " + parallel);
        // Output example:
//        [zkServerHosts]: 218.195.228.35:2181. [redisServerHost]: 218.195.228.35. [kafkaTopic]: ad-events. [kafkaPartitions]: 1. [workers]: 3. [ackers]: 2. [cores]: 4. [parallel]: 1
//        arg[0] is: AdvertisingTopology

        // Create the KafkaSpout
        ZkHosts hosts = new ZkHosts(zkServerHosts);
        SpoutConfig spoutConfig = new SpoutConfig(hosts, kafkaTopic, "/" + kafkaTopic, UUID.randomUUID().toString());
        spoutConfig.scheme = new SchemeAsMultiScheme(new StringScheme());
        KafkaSpout kafkaSpout = new KafkaSpout(spoutConfig);

        // Set TopologyBuilder which locate in Storm package.
        // The parallelism of the kafkaspout equal to the kafkaPartitions value
        builder.setSpout("ads", kafkaSpout, kafkaPartitions);
        builder.setBolt("event_deserializer", new DeserializeBolt(), parallel).shuffleGrouping("ads");
        builder.setBolt("event_filter", new EventFilterBolt(), parallel).shuffleGrouping("event_deserializer");
        builder.setBolt("event_projection", new EventProjectionBolt(), parallel).shuffleGrouping("event_filter");
        builder.setBolt("redis_join", new RedisJoinBolt(redisServerHost), parallel).shuffleGrouping("event_projection");
        builder.setBolt("campaign_processor", new CampaignProcessor(redisServerHost), parallel * 2)
                .fieldsGrouping("redis_join", new Fields("campaign_id"));

        Config conf = new Config();
        // There is important configuration. If don't set, this topology can not running in Heron.
        // (20181104 - solved the problem of NULLPOINTEREXCEPTION in kafkaSpout)
        List<String> zkHosts = new ArrayList<>();
        zkHosts.add("218.195.228.35");
        Integer zkPort = 2181;
        conf.put("storm.zookeeper.servers", zkHosts);
        conf.put("storm.zookeeper.port", zkPort);
        conf.put("storm.zookeeper.session.timeout", 20000);
        conf.put("storm.zookeeper.connection.timeout", 15000);
        conf.put("storm.zookeeper.retry.times", 5);
        conf.put("storm.zookeeper.retry.interval", 1000);

        // submit topology command in Heron: heron submit xxx/xxx/xxx xxx.jar xxx.AdvertisingTopology AdvertisingTopology arg1 arg2 --verbose
        if (args != null && args.length > 0) {
            System.out.println("arg[0] is: " + args[0]);
            conf.setNumWorkers(workers);
            conf.setNumAckers(ackers);
            StormSubmitter.submitTopology(args[0], conf, builder.createTopology());
        } else {
//            LocalCluster cluster = new LocalCluster();
//            cluster.submitTopology("test", conf, builder.createTopology());
//            backtype.storm.utils.Utils.sleep(10000);
//            cluster.killTopology("test");
//            cluster.shutdown();
            System.out.println("There is no arguments!!!");
        }
    }

    private static String joinHosts(List<String> hosts, String port) {
        String joined = null;
        for (String s : hosts) {
            if (joined == null) {
                joined = "";
            } else {
                joined += ",";
            }

            joined += s + ":" + port;
        }
        return joined;
    }

    /**
     * DeserializeBolt
     * take charge to deserialize JSON string data into tuple for processing in Heron
     */
    public static class DeserializeBolt extends BaseRichBolt {
        OutputCollector _collector;

        @Override
        public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
            _collector = collector;
        }

        @Override
        public void execute(Tuple tuple) {

            JSONObject obj = new JSONObject(tuple.getString(0));
            _collector.emit(tuple, new Values(obj.getString("user_id"),
                    obj.getString("page_id"),
                    obj.getString("ad_id"),
                    obj.getString("ad_type"),
                    obj.getString("event_type"),
                    obj.getString("event_time"),
                    obj.getString("ip_address")));
            _collector.ack(tuple);
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("user_id", "page_id", "ad_id", "ad_type", "event_type", "event_time", "ip_address"));
        }

    }

    /**
     * EventFilterBolt
     * According to the event type (view) to filter events (data)
     */
    public static class EventFilterBolt extends BaseRichBolt {
        OutputCollector _collector;

        @Override
        public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
            _collector = collector;
        }

        @Override
        public void execute(Tuple tuple) {
            if (tuple.getStringByField("event_type").equals("view")) {
                _collector.emit(tuple, tuple.getValues());
            }
            _collector.ack(tuple);
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("user_id", "page_id", "ad_id", "ad_type", "event_type", "event_time", "ip_address"));
        }
    }

    /**
     * EventProjectionBolt
     * Delete some elements in the event tuple, only left <ad_id, event_time>
     */
    public static class EventProjectionBolt extends BaseRichBolt {
        OutputCollector _collector;

        @Override
        public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
            _collector = collector;
        }

        @Override
        public void execute(Tuple tuple) {
            _collector.emit(tuple, new Values(tuple.getStringByField("ad_id"),
                    tuple.getStringByField("event_time")));
            _collector.ack(tuple);
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("ad_id", "event_time"));
        }
    }

    /**
     * RedisJoinBolt
     * Join campaign_id which were stored in Redis at the beginning
     */
    public static class RedisJoinBolt extends BaseRichBolt {
        transient RedisAdCampaignCache redisAdCampaignCache;
        private OutputCollector _collector;
        private String redisServerHost;

        public RedisJoinBolt(String redisServerHost) {
            this.redisServerHost = redisServerHost;
        }

        public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
            _collector = collector;
            redisAdCampaignCache = new RedisAdCampaignCache(redisServerHost);
            this.redisAdCampaignCache.prepare();
        }

        @Override
        public void execute(Tuple tuple) {
            String ad_id = tuple.getStringByField("ad_id");
            String campaign_id = this.redisAdCampaignCache.execute(ad_id);
            if (campaign_id == null) {
                _collector.fail(tuple);
                return;
            }
            _collector.emit(tuple, new Values(campaign_id,
                    tuple.getStringByField("ad_id"),
                    tuple.getStringByField("event_time")));
            _collector.ack(tuple);
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("campaign_id", "ad_id", "event_time"));
        }
    }

    /**
     * CampaignProcessor
     * According to time_window and campaign_window to store the statistics data back into Redis
     */
    public static class CampaignProcessor extends BaseRichBolt {

        private static final Logger LOG = Logger.getLogger(CampaignProcessor.class);

        private OutputCollector _collector;
        transient private CampaignProcessorCommon campaignProcessorCommon;
        private String redisServerHost;

        public CampaignProcessor(String redisServerHost) {
            this.redisServerHost = redisServerHost;
        }

        public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
            _collector = collector;
            campaignProcessorCommon = new CampaignProcessorCommon(redisServerHost);
            this.campaignProcessorCommon.prepare();
        }

        @Override
        public void execute(Tuple tuple) {

            String campaign_id = tuple.getStringByField("campaign_id");
            String event_time = tuple.getStringByField("event_time");

            this.campaignProcessorCommon.execute(campaign_id, event_time);
            _collector.ack(tuple);
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
        }
    }
}
