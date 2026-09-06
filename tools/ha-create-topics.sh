#!/bin/bash
set -e
BS="kafka-1:29092"
for t in flashreserve.events flashreserve.notifications flashreserve.payments; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BS" --create --if-not-exists \
    --topic "$t" --partitions 1 --replication-factor 3 --config min.insync.replicas=2
done
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BS" --describe
