package com.comp5348.delivery.support;

import com.comp5348.contracts.events.Topics;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;

/**
 * 测试这一侧的 Kafka "探针"：直接用 Kafka 官方客户端（kafka-clients）发消息、读消息、看消费进度。
 *
 * 【为什么不用 Spring 的 KafkaTemplate】被测的是服务自己的生产者 / 消费者。测试这边用最朴素的客户端，
 * 就不会和被测代码共用同一套配置，测试看到的就是 topic 里真实有什么。
 */
public final class KafkaProbe {

    /** 契约：每个 topic 3 个分区（docs/wave2-config-contract） */
    public static final int PARTITIONS = 3;

    /** 契约里的四个 topic */
    public static final List<String> ALL_TOPICS = List.of(
            Topics.ORDER_EVENTS, Topics.DELIVERY_EVENTS,
            Topics.deadLetter(Topics.ORDER_EVENTS), Topics.deadLetter(Topics.DELIVERY_EVENTS));

    private final String bootstrap;

    public KafkaProbe(String bootstrap) {
        this.bootstrap = bootstrap;
    }

    /** 建 topic：和 compose 的 kafka-init 一样，3 分区、1 副本。自动建 topic 在容器上是关的，所以必须先建。 */
    public void createTopics(List<String> topics) {
        try (Admin admin = admin()) {
            Set<String> existing = admin.listTopics().names().get();
            List<NewTopic> missing = topics.stream()
                    .filter(t -> !existing.contains(t))
                    .map(t -> new NewTopic(t, PARTITIONS, (short) 1))
                    .toList();
            if (!missing.isEmpty()) {
                admin.createTopics(missing).all().get();
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot create topics " + topics, e);
        }
    }

    /** 删掉一个 topic，并等它真的消失（D6 用：制造"写 DLT 失败"） */
    public void deleteTopic(String topic) {
        try (Admin admin = admin()) {
            admin.deleteTopics(List.of(topic)).all().get();
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> !admin.listTopics().names().get().contains(topic));
        } catch (Exception e) {
            throw new IllegalStateException("cannot delete topic " + topic, e);
        }
    }

    /** 同步发一条消息（等 Kafka 确认），返回它落在哪个分区、哪个 offset */
    public RecordMetadata send(String topic, String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            return producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException("cannot send to " + topic, e);
        }
    }

    /**
     * 把一个 topic 从头读到尾（读到"现在"为止），返回全部消息。
     * 不加入任何消费者组（assign 而不是 subscribe），所以不会影响被测服务的消费进度。
     */
    public List<ConsumerRecord<String, String>> readAll(String topic) {
        List<TopicPartition> partitions = IntStream.range(0, PARTITIONS)
                .mapToObj(p -> new TopicPartition(topic, p)).toList();
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            // endOffsets：每个分区"现在"最后一条之后的位置。读到这里就算读完了
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        return records;
    }

    /** 等到 topic 里出现一条满足条件的消息（最多等 timeout），返回它 */
    public ConsumerRecord<String, String> awaitRecord(String topic, Predicate<ConsumerRecord<String, String>> match,
                                                      Duration timeout) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(timeout).pollInterval(Duration.ofMillis(300)).until(() -> {
            found.clear();
            readAll(topic).stream().filter(match).findFirst().ifPresent(found::add);
            return !found.isEmpty();
        });
        return found.get(0);
    }

    /** 某个订单在某个 topic 里的全部消息（key = orderId，规则 5），按 offset 先后 */
    public List<ConsumerRecord<String, String>> recordsOfOrder(String topic, String orderId) {
        return readAll(topic).stream().filter(r -> orderId.equals(r.key())).toList();
    }

    /** Kafka 默认分区器给这个 key 选哪个分区（murmur2 哈希，对分区数取余） */
    public static int partitionFor(String key) {
        return Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % PARTITIONS;
    }

    private Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
    }
}
