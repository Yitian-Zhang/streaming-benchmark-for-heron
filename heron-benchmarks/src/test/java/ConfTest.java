import benchmark.common.Utils;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class ConfTest {

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

    @Test
    public void test() {
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

        System.out.println(zkServerHosts);
        System.out.println(redisServerHost);
        System.out.println(kafkaTopic);
        System.out.println(kafkaPartitions);
        System.out.println(workers);
        System.out.println(ackers);
        System.out.println(cores);
    }
}
