package com.comp5348.contracts.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 层级：单元测试（纯 JSON 解析，没有数据库、没有 Kafka，全部是真对象，没有 mock）。
 * 防什么：生产者发出缺字段或类型不对的事件时，消费者在入口就拒绝它（之后交给 DLT，规则 6），
 * 而不是带着 null 往下走，几步之后才在别处 NullPointerException。
 */
class EventEnvelopeContractTest {

    // 示例事件放在 contracts/examples/，Go 的 tracking 也读同一批文件，保证两种语言看到的格式一样。
    // Gradle 跑测试时的工作目录是模块目录（backend/contracts），所以相对路径从这里算起。
    private static final Path EXAMPLES = Path.of("examples");

    // 这个 ObjectMapper 只用来在测试里改 JSON（删字段、换类型），不是被测对象。
    private final ObjectMapper json = new ObjectMapper();

    // 提供参数：示例目录里的每一个 .json 文件。@MethodSource 会按名字找到这个方法。
    static Stream<Path> examples() throws IOException {
        // Files.list 列出目录下的文件；try-with-resources 保证目录句柄被关掉。
        try (Stream<Path> files = Files.list(EXAMPLES)) {
            // toList() 先收集成列表，因为 Stream 在 try 结束后就不能再用了。
            return files.filter(p -> p.toString().endsWith(".json")).toList().stream();
        }
    }

    // @ParameterizedTest：同一个测试方法跑 5 次，每次 field 是下面 @ValueSource 里的一个值。
    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = {"eventId", "eventType", "orderId", "occurredAt", "payload"})
    void eventEnvelope_missingRequiredField_failsValidation(String field) throws IOException {
        // 从一个合法的示例开始，读成可修改的 JSON 树。
        ObjectNode event = (ObjectNode) json.readTree(Files.readString(EXAMPLES.resolve("order-events-OrderPaid.json")));
        // 删掉这一次要测的外壳字段。
        event.remove(field);

        // 期望：解析时直接抛 InvalidEventException（消费者据此把消息送进 DLT）。
        // 现有的宽松实现会把缺的字段当成 null，悄悄放过去，所以这里会红。
        assertThatThrownBy(() -> EventCodec.parse(event.toString()))
                .isInstanceOf(InvalidEventException.class);
    }

    // 显式写成 null 和完全不写是两种情况，Jackson 的处理路径也不同，所以单独测一遍。
    @ParameterizedTest(name = "null {0}")
    @ValueSource(strings = {"eventId", "eventType", "orderId", "occurredAt", "payload"})
    void eventEnvelope_nullRequiredField_failsValidation(String field) throws IOException {
        ObjectNode event = (ObjectNode) json.readTree(Files.readString(EXAMPLES.resolve("order-events-OrderPaid.json")));
        // putNull：保留这个键，但值是 JSON 的 null。
        event.putNull(field);

        assertThatThrownBy(() -> EventCodec.parse(event.toString()))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void orderId_sentAsJsonNumber_isRejected() throws IOException {
        ObjectNode event = (ObjectNode) json.readTree(Files.readString(EXAMPLES.resolve("order-events-OrderPaid.json")));
        // 契约规定 orderId 是字符串 "42"。这里模拟某个生产者写成了数字 42。
        // Java 这边 Jackson 默认会把 42 自动转成 "42"，看起来没事；
        // 但 Go 的 json.Unmarshal 遇到数字填进 string 字段会直接报错，tracking 就收不了。
        // 所以 Java 这边也必须拒绝，问题在生产者测试里就暴露，不要等到 Go 那边。
        event.put("orderId", 42);

        assertThatThrownBy(() -> EventCodec.parse(event.toString()))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void payload_notAnObject_isRejected() throws IOException {
        ObjectNode event = (ObjectNode) json.readTree(Files.readString(EXAMPLES.resolve("order-events-OrderPaid.json")));
        // payload 必须是 JSON 对象。字符串（比如有人把对象先 toString 再塞进去）要拒绝。
        event.put("payload", "{\"paymentId\":11}");

        assertThatThrownBy(() -> EventCodec.parse(event.toString()))
                .isInstanceOf(InvalidEventException.class);
    }

    // 契约 README 第 7 节承诺："以后改格式只加字段，旧的消费者照样能读"。
    // 这个测试模拟"生产者已经升级、多发了字段，消费者还是旧版"：外壳和 payload 各多一个字段。
    // 期望：照常解析，多出来的字段被忽略。如果消费者拒绝它，这条正常的消息就会被当成坏消息送进 DLT。
    @Test
    void newFieldAddedByProducer_isIgnoredByOlderConsumer() throws Exception {
        ObjectNode event = (ObjectNode) json.readTree(Files.readString(EXAMPLES.resolve("order-events-OrderPaid.json")));
        // 外壳多一个字段（比如以后加的 schemaVersion）。
        event.put("schemaVersion", 2);
        // payload 多一个字段（比如以后加的 couponCode）。
        ((ObjectNode) event.get("payload")).put("couponCode", "SPRING10");

        // 读外壳：不能抛异常。
        EventEnvelope envelope = EventCodec.parse(event.toString());
        // 读 payload：也不能抛异常，已有的字段照样读得到。
        com.comp5348.contracts.events.payload.OrderPaidPayload payload =
                EventCodec.payload(envelope, com.comp5348.contracts.events.payload.OrderPaidPayload.class);
        assertThat(payload.paymentId()).isEqualTo(11L);
    }

    // 安全网：每个示例文件都能被解析，解析出的 eventType 和文件名对得上，再写回去内容不变。
    // 这个测试本来就该是绿的（不是修 bug），它保证示例文件和 Java 类型一直同步。
    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void everyExample_parsesIntoItsPayloadType_andRoundTrips(Path file) throws Exception {
        String original = Files.readString(file);

        // 解析外壳。
        EventEnvelope envelope = EventCodec.parse(original);
        // 文件名形如 order-events-OrderPaid.json，最后一段就是 eventType。
        String name = file.getFileName().toString().replace(".json", "");
        assertThat(name).endsWith("-" + envelope.eventType());

        // 按 eventType 找到 payload 的 Java 类型，并真的转换一次，字段名不匹配会在这里失败。
        Class<?> payloadType = payloadTypeOf(envelope.eventType());
        Object payload = EventCodec.payload(envelope, payloadType);
        assertThat(payload).isNotNull();

        // 往返：写回 JSON 再比较树（不比较字符串，因为空格和字段顺序可以不同）。
        JsonNode written = json.readTree(EventCodec.write(envelope));
        assertThat(written).isEqualTo(json.readTree(original));
    }

    @Test
    void examples_coverEveryEventType() throws IOException {
        // 每种事件都要有一个示例文件，Go 那边才有完整的测试数据。
        List<String> fileNames = examples().map(p -> p.getFileName().toString()).toList();
        for (OrderEventType t : OrderEventType.values()) {
            assertThat(fileNames).contains(Topics.ORDER_EVENTS + "-" + t.wireName() + ".json");
        }
        for (DeliveryEventType t : DeliveryEventType.values()) {
            assertThat(fileNames).contains(Topics.DELIVERY_EVENTS + "-" + t.wireName() + ".json");
        }
    }

    // 测试用的小工具：wire name → payload 类型。
    private static Class<?> payloadTypeOf(String eventType) {
        for (OrderEventType t : OrderEventType.values()) {
            if (t.wireName().equals(eventType)) {
                return t.payloadType();
            }
        }
        // delivery-events 的事件全部共用一个 payload 类型。
        return com.comp5348.contracts.events.payload.DeliveryEventPayload.class;
    }
}
