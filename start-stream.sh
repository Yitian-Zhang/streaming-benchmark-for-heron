#!/bin/bash

# -----------------------------------------------------
# This file is responsible for starting kafka producer
# Modified from stream-bench.sh for Heron
#
# Author: Yitian
# -----------------------------------------------------

set -o pipefail
set -o errtrace
set -o nounset
set -o errexit

# defined some commands: lein-LEIN mvn-MVN git-GIT make-MAKE
LEIN=${LEIN:-lein}
MVN=${MVN:-mvn}
GIT=${GIT:-git}
MAKE=${MAKE:-make}

# defined some variable
ZK_HOST="218.195.228.35" #192.168.209.137
ZK_PORT="2181"
ZK_CONNECTIONS="$ZK_HOST:$ZK_PORT"
TOPIC=${TOPIC:-"ad-events"}
PARTITIONS=${PARTITIONS:-1}
LOAD=${LOAD:-1000} # used in START_LOAD
CONF_FILE=./conf/localConf.yaml
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
#    $GIT clean -fd

    echo 'kafka.brokers:' > $CONF_FILE
    echo 'kafka.brokers:' > $CONF_FILE
    echo '    - "192.168.209.137"' >> $CONF_FILE
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

  elif [ "START_REDIS" = "$OPERATION" ];
  then
#    start_if_needed redis-server Redis 1 "$REDIS_DIR/src/redis-server"
    cd data
    $LEIN run -n --configPath ../conf/benchmarkConf.yaml
    cd ..
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
  elif [ "HERON_TEST" = "$OPERATION" ];
  then
    run "START_REDIS"
    run "START_LOAD" # 这个不知道怎么手动启动, 使用命令启动
    sleep $TEST_TIME
    run "STOP_LOAD"
  else
    if [ "HELP" != "$OPERATION" ];
    then
      echo "UNKOWN OPERATION '$OPERATION'"
      echo
    fi
    echo "Supported Operations:"
    echo
    echo "START_REDIS: run a redis instance in the background"
    echo "START_LOAD: run kafka load generation"
    echo "STOP_LOAD: kill kafka load generation"
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
