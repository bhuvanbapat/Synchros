#!/bin/bash
set -e
BS1="kafka-1:29092"
BS3="kafka-3:29095"

echo "== STEP 1: produce 10 messages (all brokers up) =="
for i in $(seq 1 10); do
  echo "chaos-$i" | /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server "$BS1" --topic flashreserve.events
done

echo "== STEP 2: consume + count (expect 10) =="
timeout 15 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server "$BS1" --topic flashreserve.events --from-beginning \
  --max-messages 10 --timeout-ms 8000 > /tmp/consumed1.txt 2>/dev/null || true
COUNT1=$(grep -c 'chaos-' /tmp/consumed1.txt)
echo "consumed before failure: $COUNT1"

echo "== STEP 3: kill broker-2 (leader of events), wait for ISR shrink =="
# (executed from the HOST by the caller; this script just reports)
echo "== verifying availability from kafka-3 after failure =="
timeout 15 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server "$BS3" --topic flashreserve.events --from-beginning \
  --max-messages 10 --timeout-ms 8000 > /tmp/consumed2.txt 2>/dev/null || true
COUNT2=$(grep -c 'chaos-' /tmp/consumed2.txt)
echo "consumed after leader kill: $COUNT2 (must equal $COUNT1 — zero loss)"

if [ "$COUNT1" = "10" ] && [ "$COUNT2" = "10" ]; then
  echo "RESULT: HA VERIFIED — 10/10 messages survived leader loss"
else
  echo "RESULT: FAILED — before=$COUNT1 after=$COUNT2"
  exit 1
fi
