**********************************************************
                         项目使用说明
                         2018/10/23
                         Yitian Zhang
**********************************************************
启动Yahoo-benchmark-for-heron的步骤：
1. 手动启动集群zk
2. 手动启动redis
3. 手动启动kafka
4. 手动提交AdvertisingTopology in Heron
5. 执行start-stream.sh命令

这里命令的问题。。。
$STORM_DIR/bin/heron" jar ./heron-benchmarks/target/storm-benchmarks-0.1.0.jar storm.benchmark.AdvertisingTopology test-topo -conf $CONF_FILE