#!/bin/bash
# -----------------------------------------------
# This file is the original in Yahoo! Benchmark
# -----------------------------------------------

set -o pipefail
set -o errtrace
set -o nounset
set -o errexit

# defined some commands: lein-LEIN mvn-MVN git-GIT make-MAKE
LEIN=${LEIN:-lein}
MVN=${MVN:-mvn}
GIT=${GIT:-git}
MAKE=${MAKE:-make}

# defined kafka, redis, scala, heron version
KAFKA_VERSION=${KAFKA_VERSION:-"0.8.2.1"}
REDIS_VERSION=${REDIS_VERSION:-"3.0.5"}
SCALA_BIN_VERSION=${SCALA_BIN_VERSION:-"2.10"}
SCALA_SUB_VERSION=${SCALA_SUB_VERSION:-"4"}
# for heron ----------------------------------
HERON_VERSION=${HERON_VERSION:-"0.17.5"}
# STORM_VERSION=${STORM_VERSION:-"0.9.7"}

# defined redis, kafka, heron DIR filename
# STORM_DIR="apache-storm-$STORM_VERSION"
REDIS_DIR="redis-$REDIS_VERSION"
KAFKA_DIR="kafka_$SCALA_BIN_VERSION-$KAFKA_VERSION"
# for heron ----------------------------------
HERON_DIR="apache-heron-$HERON_VERSION" # 后面会用到.tar，因此这里有问题

#Get one of the closet apache mirrors
APACHE_MIRROR=$(curl 'https://www.apache.org/dyn/closer.cgi' |   grep -o '<strong>[^<]*</strong>' |   sed 's/<[^>]*>//g' |   head -1)

ZK_HOST="localhost"
ZK_PORT="2181"
ZK_CONNECTIONS="$ZK_HOST:$ZK_PORT"
TOPIC=${TOPIC:-"ad-events"}
PARTITIONS=${PARTITIONS:-1}
LOAD=${LOAD:-1000} # this load for what?
CONF_FILE=./conf/localConf.yaml #
TEST_TIME=${TEST_TIME:-240} # test time: 240s

pid_match() {
   local VAL=`ps -aef | grep "$1" | grep -v grep | awk '{print $2}'`
   echo $VAL
}

start_if_needed() {
  local match="$1"
  shift
  local name="$1"
  shift
  local sleep_time="$1"
  shift
  local PID=`pid_match "$match"`

  if [[ "$PID" -ne "" ]];
  then
    echo "$name is already running..."
  else
    "$@" &
    sleep $sleep_time
  fi
}

stop_if_needed() {
  local match="$1"
  local name="$2"
  local PID=`pid_match "$match"`
  if [[ "$PID" -ne "" ]];
  then
    kill "$PID"
    sleep 1
    local CHECK_AGAIN=`pid_match "$match"`
    if [[ "$CHECK_AGAIN" -ne "" ]];
    then
      kill -9 "$CHECK_AGAIN"
    fi
  else
    echo "No $name instance found to stop"
  fi
}

fetch_untar_file() {
  local FILE="download-cache/$1"
  local URL=$2
  if [[ -e "$FILE" ]];
  then
    echo "Using cached File $FILE"
  else
	mkdir -p download-cache/
    WGET=`whereis wget`
    CURL=`whereis curl`
    if [ -n "$WGET" ];
    then
      wget -O "$FILE" "$URL"
    elif [ -n "$CURL" ];
    then
      curl -o "$FILE" "$URL"
    else
      echo "Please install curl or wget to continue.";
      exit 1
    fi
  fi
  tar -xzvf "$FILE"
}

create_kafka_topic() {
    local count=`$KAFKA_DIR/bin/kafka-topics.sh --describe --zookeeper "$ZK_CONNECTIONS" --topic $TOPIC 2>/dev/null | grep -c $TOPIC`
    if [[ "$count" = "0" ]];
    then
        $KAFKA_DIR/bin/kafka-topics.sh --create --zookeeper "$ZK_CONNECTIONS" --replication-factor 1 --partitions $PARTITIONS --topic $TOPIC
    else
        echo "Kafka topic $TOPIC already exists"
    fi
}

# running command function
run() {
  OPERATION=$1
  if [ "SETUP" = "$OPERATION" ];
  then
    $GIT clean -fd

    echo 'kafka.brokers:' > $CONF_FILE
    echo 'kafka.brokers:' > $CONF_FILE
    echo '    - "localhost"' >> $CONF_FILE
    echo >> $CONF_FILE
    echo 'zookeeper.servers:' >> $CONF_FILE
    echo '    - "'$ZK_HOST'"' >> $CONF_FILE
    echo >> $CONF_FILE
    echo 'kafka.port: 9092' >> $CONF_FILE
	echo 'zookeeper.port: '$ZK_PORT >> $CONF_FILE
	echo 'redis.host: "localhost"' >> $CONF_FILE
	echo 'kafka.topic: "'$TOPIC'"' >> $CONF_FILE
	echo 'kafka.partitions: '$PARTITIONS >> $CONF_FILE
	echo 'process.hosts: 1' >> $CONF_FILE
	echo 'process.cores: 4' >> $CONF_FILE
	echo 'storm.workers: 1' >> $CONF_FILE
	echo 'storm.ackers: 2' >> $CONF_FILE
	echo 'spark.batchtime: 2000' >> $CONF_FILE

	# need to modified
#    $MVN clean install -Dspark.version="$SPARK_VERSION" -Dkafka.version="$KAFKA_VERSION" -Dflink.version="$FLINK_VERSION" -Dstorm.version="$STORM_VERSION" -Dscala.binary.version="$SCALA_BIN_VERSION" -Dscala.version="$SCALA_BIN_VERSION.$SCALA_SUB_VERSION" -Dapex.version="$APEX_VERSION"
    $MVN clean install -Dkafka.version="$KAFKA_VERSION" -Dscala.binary.version="$SCALA_BIN_VERSION" -Dscala.version="$SCALA_BIN_VERSION.$SCALA_SUB_VERSION"
    #Fetch and build Redis
    REDIS_FILE="$REDIS_DIR.tar.gz"
    fetch_untar_file "$REDIS_FILE" "http://download.redis.io/releases/$REDIS_FILE"

    cd $REDIS_DIR
    $MAKE
    cd ..

    #Fetch Kafka
    KAFKA_FILE="$KAFKA_DIR.tgz"
    fetch_untar_file "$KAFKA_FILE" "$APACHE_MIRROR/kafka/$KAFKA_VERSION/$KAFKA_FILE"

    #Fetch Heron -----------------------------------------
    HERON_FILE="$HERON_DIR.tar.gz"
    fetch_untar_file "$HERON_FILE" "$APACHE_MIRROR/storm/$STORM_DIR/$STORM_FILE" ### 有问题，修改改
    #Fetch Storm
#    STORM_FILE="$STORM_DIR.tar.gz"
#    fetch_untar_file "$STORM_FILE" "$APACHE_MIRROR/storm/$STORM_DIR/$STORM_FILE"

  elif [ "START_ZK" = "$OPERATION" ];
  then
    start_if_needed dev_zookeeper ZooKeeper 10 "$STORM_DIR/bin/storm" dev-zookeeper
  elif [ "STOP_ZK" = "$OPERATION" ];
  then
    stop_if_needed dev_zookeeper ZooKeeper
    rm -rf /tmp/dev-storm-zookeeper
  elif [ "START_REDIS" = "$OPERATION" ];
  then
    start_if_needed redis-server Redis 1 "$REDIS_DIR/src/redis-server"
    cd data
    $LEIN run -n --configPath ../conf/benchmarkConf.yaml
    cd ..
  elif [ "STOP_REDIS" = "$OPERATION" ];
  then
    stop_if_needed redis-server Redis
    rm -f dump.rdb
#  elif [ "START_STORM" = "$OPERATION" ];
#  then
#    start_if_needed daemon.name=nimbus "Storm Nimbus" 3 "$STORM_DIR/bin/storm" nimbus
#    start_if_needed daemon.name=supervisor "Storm Supervisor" 3 "$STORM_DIR/bin/storm" supervisor
#    start_if_needed daemon.name=ui "Storm UI" 3 "$STORM_DIR/bin/storm" ui
#    start_if_needed daemon.name=logviewer "Storm LogViewer" 3 "$STORM_DIR/bin/storm" logviewer
#    sleep 20
#  elif [ "STOP_STORM" = "$OPERATION" ];
#  then
#    stop_if_needed daemon.name=nimbus "Storm Nimbus"
#    stop_if_needed daemon.name=supervisor "Storm Supervisor"
#    stop_if_needed daemon.name=ui "Storm UI"
#    stop_if_needed daemon.name=logviewer "Storm LogViewer"
  elif [ "START_KAFKA" = "$OPERATION" ];
  then
    start_if_needed kafka\.Kafka Kafka 10 "$KAFKA_DIR/bin/kafka-server-start.sh" "$KAFKA_DIR/config/server.properties"
    create_kafka_topic
  elif [ "STOP_KAFKA" = "$OPERATION" ];
  then
    stop_if_needed kafka\.Kafka Kafka
    rm -rf /tmp/kafka-logs/
  elif [ "START_LOAD" = "$OPERATION" ];
  then
    cd data
    start_if_needed leiningen.core.main "Load Generation" 1 $LEIN run -r -t $LOAD --configPath ../$CONF_FILE
    cd ..
  elif [ "STOP_LOAD" = "$OPERATION" ];
  then
    stop_if_needed leiningen.core.main "Load Generation"
    cd data
    $LEIN run -g --configPath ../$CONF_FILE || true
    cd ..

  # START_STORM_TOPOLOGY ----------------------------
  elif [ "START_HERON_TOPOLOGY" = "$OPERATION" ];
  then
    "$STORM_DIR/bin/heron" jar ./heron-benchmarks/target/storm-benchmarks-0.1.0.jar storm.benchmark.AdvertisingTopology test-topo -conf $CONF_FILE
    sleep 15
  elif [ "STOP_HERON_TOPOLOGY" = "$OPERATION" ];
  then
    "$STORM_DIR/bin/heron" kill -w 0 test-topo || true
    sleep 10

  # HERON_TEST commands -------------------------------------------
  elif [ "HERON_TEST" = "$OPERATION" ];
  then
    run "START_ZK" # zk可以手动启动
    run "START_REDIS" # redis可以手动启动
    run "START_KAFKA" # kafka可以手动启动
    run "START_HERON" # heron可以手动启动
    run "START_HERON_TOPOLOGY" # 提交HeronTopology可以手动
    run "START_LOAD" # 这个不知道怎么手动启动, 使用命令启动
    sleep $TEST_TIME
    run "STOP_LOAD"
    run "STOP_HERON_TOPOLOGY"
    run "STOP_HERON"
    run "STOP_KAFKA"
    run "STOP_REDIS"
    run "STOP_ZK"
  # ---------------------------------------------------------------
  elif [ "STOP_ALL" = "$OPERATION" ];
  then
    run "STOP_LOAD"
#    run "STOP_SPARK_PROCESSING"
#    run "STOP_SPARK"
#    run "STOP_FLINK_PROCESSING"
#    run "STOP_FLINK"
#    run "STOP_STORM_TOPOLOGY"
#    run "STOP_STORM"
    run "STOP_HERON_TOPOLOGY"
    run "STOP_HERON"
    run "STOP_KAFKA"
    run "STOP_REDIS"
    run "STOP_ZK"
  else
    if [ "HELP" != "$OPERATION" ];
    then
      echo "UNKOWN OPERATION '$OPERATION'"
      echo
    fi
    echo "Supported Operations:"
    echo "SETUP: download and setup dependencies for running a single node test"
    echo "START_ZK: run a single node ZooKeeper instance on local host in the background"
    echo "STOP_ZK: kill the ZooKeeper instance"
    echo "START_REDIS: run a redis instance in the background"
    echo "STOP_REDIS: kill the redis instance"
    echo "START_KAFKA: run kafka in the background"
    echo "STOP_KAFKA: kill kafka"
    echo "START_LOAD: run kafka load generation"
    echo "STOP_LOAD: kill kafka load generation"
#    echo "START_STORM: run storm daemons in the background"
#    echo "STOP_STORM: kill the storm daemons"
    echo 
#    echo "START_STORM_TOPOLOGY: run the storm test topology"
#    echo "STOP_STORM_TOPOLOGY: kill the storm test topology"
    echo
#    echo "STORM_TEST: run storm test (assumes SETUP is done)"
    # for heron ----------------------------------
    echo "HERON_TEST: run Heron test (assumes SETUP is done)"
    echo "STOP_ALL: stop everything"
    echo
    echo "HELP: print out this message"
    echo
    exit 1
  fi
}

if [ $# -lt 1 ];
then
  run "HELP"
else
  while [ $# -gt 0 ];
  do
    run "$1"
    shift
  done
fi
