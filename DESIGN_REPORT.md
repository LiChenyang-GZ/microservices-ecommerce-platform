no# System Design and Quality Attributes Report
# 系统设计与质量属性报告

## Executive Summary / 执行摘要

This report addresses how our distributed e-commerce system improves availability and reliability, incorporates various quality attributes, and maintains clean architecture through ORM-based database interface modeling.

本报告阐述我们的分布式电商系统如何提升可用性和可靠性，纳入各种质量属性，以及通过基于ORM的数据库接口建模保持清晰的架构。

---

## 1. Availability and Reliability / 可用性与可靠性

### 1.1 Availability Improvement Mechanisms / 可用性提升机制

#### A. Outbox Pattern for Event Guaranteed Delivery / 事件可靠交付的Outbox模式

**Implementation**: `PaymentOutbox`, `OutboxProcessor`, `OutboxService`

**English**:
The Outbox Pattern ensures that critical events (payment success, delivery failure) are persisted in the database **before** being sent to message queues. This guarantees that even if a service crashes immediately after creating an event, the event will be reprocessed when the service restarts.

**中文**:
Outbox模式确保关键事件（支付成功、配送失败）在发送到消息队列之前先持久化到数据库。这保证了即使服务在创建事件后立即崩溃，事件也会在服务重启时被重新处理。

**Key Features / 关键特性**:
- Events are stored in `payment_outbox` table with status tracking
- Scheduled processor (`@Scheduled`) scans and processes pending events every 5 seconds
- Retry mechanism with maximum retry count (5 attempts)
- Events remain in database until successfully processed

#### B. RabbitMQ Message Persistence / RabbitMQ消息持久化

**Implementation**: `RabbitMQConfig.java` in all services

**English**:
All RabbitMQ queues are configured as **durable** (survive broker restarts), and messages are published with `PERSISTENT` delivery mode. This ensures messages are not lost even if RabbitMQ server restarts.

**中文**:
所有RabbitMQ队列都配置为**持久化**（在代理重启后仍然存在），消息以`PERSISTENT`交付模式发布。这确保了即使RabbitMQ服务器重启，消息也不会丢失。

**Code Example / 代码示例**:
```java
@Bean
public Queue emailQueue() {
    return new Queue(EMAIL_QUEUE_NAME, true); // durable=true
}

rabbitTemplate.setBeforePublishPostProcessors(message -> {
    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    return message;
});
```

#### C. Manual Message Acknowledgment / 手动消息确认

**Implementation**: `EmailQueueConsumer.java`

**English**:
Email Service uses manual acknowledgment mode. Messages are only removed from the queue after successful processing. If the consumer crashes, unacknowledged messages remain in the queue and will be redelivered when the service restarts.

**中文**:
邮件服务使用手动确认模式。消息只有在成功处理后才会从队列中删除。如果消费者崩溃，未确认的消息会保留在队列中，并在服务重启时重新交付。

**Code Example / 代码示例**:
```java
@RabbitListener(queues = RabbitMQConfig.EMAIL_QUEUE_NAME)
public void processEmailMessage(EmailMessage message, Channel channel, Message amqpMessage) {
    try {
        // Process email
        emailService.sendOrderCancelledEmail(...);
        channel.basicAck(deliveryTag, false); // ✅ Acknowledge success
    } catch (Exception e) {
        channel.basicNack(deliveryTag, false, true); // ❌ Reject and requeue
    }
}
```

### 1.2 Reliability Improvement Mechanisms / 可靠性提升机制

#### A. Retry Mechanism with Exponential Backoff / 带指数退避的重试机制

**Implementation**: `DeliveryAdapter.java`, `BankAdapter.java`

**English**:
All external service calls implement retry logic with a maximum of 3 attempts and 1-second delay between retries. This handles transient network failures and temporary service unavailability.

**中文**:
所有外部服务调用都实现了重试逻辑，最多重试3次，每次重试间隔1秒。这可以处理临时网络故障和服务暂时不可用的情况。

**Code Example / 代码示例**:
```java
private static final int MAX_RETRIES = 3;
private static final long RETRY_DELAY_MS = 1000;

for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
        // Call external service
        ResponseEntity<Response> response = restTemplate.postForEntity(url, entity, Response.class);
        return response;
    } catch (Exception e) {
        if (attempt == MAX_RETRIES) {
            return new Response(false, "Service unavailable");
        }
        Thread.sleep(RETRY_DELAY_MS);
    }
}
```

#### B. Compensation Pattern (Saga) / 补偿模式（Saga）

**Implementation**: `CompensationService.java`, `OutboxProcessor.processDeliveryFailed()`

**English**:
When a distributed transaction fails (e.g., delivery creation fails), the system triggers compensation transactions to rollback completed operations. This ensures eventual consistency across services.

**中文**:
当分布式事务失败时（例如，配送创建失败），系统会触发补偿事务来回滚已完成的操作。这确保了跨服务的最终一致性。

**Compensation Flow / 补偿流程**:
1. **Wait Period**: System waits 30 seconds to confirm service failure (not just transient)
2. **Inventory Release**: Release reserved inventory (compensate inventory hold)
3. **Refund Processing**: Refund payment to customer (compensate payment)
4. **Status Update**: Update order status to `CANCELLED_SYSTEM`

**Code Example / 代码示例**:
```java
// Wait 30 seconds before triggering compensation
if (waitSeconds < 30) {
    return false; // Continue waiting
}

// Trigger compensation
compensationService.compensateDeliveryFailed(orderId, reason);
// → Release inventory
// → Process refund
// → Update order status
```

#### C. Optimistic Locking for Concurrency Control / 并发控制的乐观锁

**Implementation**: `WarehouseProduct` entity with `@Version`

**English**:
Optimistic locking prevents race conditions in concurrent order scenarios. Multiple users can attempt to purchase the same product simultaneously, but only one will succeed. Others will receive an `OptimisticLockException` and can retry with updated inventory.

**中文**:
乐观锁防止并发订单场景中的竞争条件。多个用户可以同时尝试购买同一商品，但只有一个人会成功。其他人会收到`OptimisticLockException`，可以使用更新的库存重试。

**Code Example / 代码示例**:
```java
@Entity
public class WarehouseProduct {
    @Version
    private Long version; // Optimistic lock version field
    
    private Integer quantity;
}
```

### 1.3 Partial System Failure Scenarios / 部分系统故障场景

#### Scenario 1: Email Service Failure / 邮件服务故障

**When it fails / 故障时**:
- Email Service crashes after order is placed
- Email messages are sent to RabbitMQ queue

**What happens / 发生什么**:
1. ✅ Messages persist in RabbitMQ queue (durable queue + persistent messages)
2. ✅ Store Service continues operating normally
3. ✅ When Email Service restarts, messages are automatically redelivered
4. ✅ Email Service processes messages from queue and sends emails

**Implementation**:
- Manual acknowledgment ensures messages are not lost
- Durable queues survive RabbitMQ restarts
- Messages remain in queue until acknowledged

#### Scenario 2: Delivery Service Failure / 配送服务故障

**When it fails / 故障时**:
- Delivery Service crashes when Store Service tries to create delivery

**What happens / 发生什么**:
1. ✅ Store Service retries 3 times (with 1-second delay)
2. ✅ If all retries fail, Store Service creates `DELIVERY_FAILED` outbox event
3. ✅ Outbox processor waits 30 seconds (to distinguish transient vs permanent failure)
4. ✅ After 30 seconds, compensation is triggered:
   - Release reserved inventory
   - Process refund
   - Update order status to `CANCELLED_SYSTEM`
5. ✅ If Delivery Service restarts within 30 seconds, delivery can be created normally

**Implementation**:
```java
// Retry logic
for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
        response = deliveryAdapter.createDelivery(request);
        if (response.isSuccess()) return response;
    } catch (Exception e) {
        if (attempt == MAX_RETRIES) {
            // Create DELIVERY_FAILED event
            outboxService.createDeliveryFailedEvent(orderId, e.getMessage());
        }
    }
}
```

#### Scenario 3: Bank Service Failure / 银行服务故障

**When it fails / 故障时**:
- Bank Service crashes during payment processing

**What happens / 发生什么**:
1. ✅ Store Service retries payment 3 times
2. ✅ If payment fails, `PAYMENT_FAILED` outbox event is created
3. ✅ Outbox processor handles failure:
   - Release reserved inventory
   - Update order status to `CANCELLED`
   - Send failure notification email

**Implementation**:
- Payment processing uses retry mechanism
- Payment failures trigger compensation
- Order creation is rolled back if payment fails

#### Scenario 4: RabbitMQ Failure / RabbitMQ故障

**When it fails / 故障时**:
- RabbitMQ server crashes or becomes unavailable

**What happens / 发生什么**:
1. ✅ Events are stored in database (Outbox Pattern)
2. ✅ Store Service continues operating (order creation, payment processing)
3. ✅ When RabbitMQ restarts:
   - Outbox processor scans database for pending events
   - Events are sent to RabbitMQ queue
   - Consumers process messages normally

**Implementation**:
- Outbox Pattern ensures events are not lost
- Scheduled processor handles message sending when RabbitMQ recovers
- No data loss even if message queue is unavailable

#### Scenario 5: Database Failure / 数据库故障

**When it fails / 故障时**:
- Database becomes temporarily unavailable

**What happens / 发生什么**:
1. ⚠️ Transaction fails and rolls back
2. ✅ Retry mechanism can handle transient database failures
3. ⚠️ For critical operations, system waits and retries
4. ✅ Idempotent operations can be safely retried

**Note / 注意**:
- Database failures require infrastructure-level solutions (replication, failover)
- Application-level retry helps with transient failures

---

## 2. Other Quality Attributes / 其他质量属性

### 2.1 Scalability / 可扩展性

**English**:
Our microservices architecture allows horizontal scaling of individual services based on load. Each service can be scaled independently:
- **Store Service**: Can be scaled for high order volumes
- **Email Service**: Can be scaled for bulk email processing
- **Delivery Service**: Can be scaled for delivery processing
- **Bank Service**: Can be scaled for payment processing

**中文**:
我们的微服务架构允许根据负载水平扩展各个服务。每个服务可以独立扩展：
- **Store服务**：可以为高订单量扩展
- **Email服务**：可以为批量邮件处理扩展
- **Delivery服务**：可以为配送处理扩展
- **Bank服务**：可以为支付处理扩展

**Key Mechanisms / 关键机制**:
- **Stateless Services**: Services don't store session state, enabling easy scaling
- **Message Queue Decoupling**: Asynchronous messaging allows services to process at different rates
- **Database Connection Pooling**: Efficient resource utilization

### 2.2 Performance / 性能

**English**:
Performance optimizations include:
1. **Optimistic Locking**: Reduces database contention compared to pessimistic locking
2. **Asynchronous Processing**: Email notifications don't block order creation
3. **Lazy Loading**: Entity relationships use `FetchType.LAZY` to reduce initial data load
4. **Connection Pooling**: Efficient database connection management
5. **Caching**: Potential for caching product catalog and user sessions

**中文**:
性能优化包括：
1. **乐观锁**：与悲观锁相比，减少数据库竞争
2. **异步处理**：邮件通知不阻塞订单创建
3. **懒加载**：实体关系使用`FetchType.LAZY`减少初始数据加载
4. **连接池**：高效的数据库连接管理
5. **缓存**：可以缓存产品目录和用户会话

**Code Example / 代码示例**:
```java
@ManyToOne(fetch = FetchType.LAZY)  // Lazy loading
@JoinColumn(name = "product_id")
private Product product;
```

### 2.3 Maintainability / 可维护性

**English**:
The system follows clear separation of concerns:
- **Controller Layer**: Handles HTTP requests/responses
- **Service Layer**: Contains business logic
- **Repository Layer**: Data access abstraction
- **Adapter Layer**: External service integration
- **Model Layer**: Entity definitions

**中文**:
系统遵循清晰关注点分离：
- **Controller层**：处理HTTP请求/响应
- **Service层**：包含业务逻辑
- **Repository层**：数据访问抽象
- **Adapter层**：外部服务集成
- **Model层**：实体定义

**Code Organization / 代码组织**:
```
storeService/
├── controller/      # REST API endpoints
├── service/         # Business logic
├── repository/      # Data access
├── adapter/         # External service integration
├── model/           # Entity definitions
├── dto/             # Data transfer objects
└── config/          # Configuration classes
```

### 2.4 Security / 安全性

**English**:
Security measures include:
1. **JWT Token Authentication**: Inter-service communication uses JWT tokens
2. **Password Encryption**: BCrypt password hashing
3. **CORS Configuration**: Controlled cross-origin access
4. **Input Validation**: Bean validation on DTOs (`@NotNull`, `@Min`, `@Valid`)

**中文**:
安全措施包括：
1. **JWT令牌认证**：服务间通信使用JWT令牌
2. **密码加密**：BCrypt密码哈希
3. **CORS配置**：控制跨域访问
4. **输入验证**：DTO上的Bean验证（`@NotNull`、`@Min`、`@Valid`）

### 2.5 Testability / 可测试性

**English**:
The architecture supports testing:
- **Service Layer**: Business logic can be tested in isolation (mock dependencies)
- **Repository Layer**: Can use in-memory databases for testing
- **Adapter Layer**: Can mock external service calls
- **Spring Boot Test Support**: Built-in testing framework

**中文**:
架构支持测试：
- **Service层**：业务逻辑可以隔离测试（模拟依赖）
- **Repository层**：可以使用内存数据库进行测试
- **Adapter层**：可以模拟外部服务调用
- **Spring Boot测试支持**：内置测试框架

### 2.6 Trade-offs Between Quality Attributes / 质量属性之间的权衡

#### A. Availability vs Performance / 可用性与性能

**English**:
- **Trade-off**: Outbox Pattern adds database writes before message sending, which slightly reduces performance but dramatically improves reliability
- **Decision**: We prioritize reliability over performance because lost events (e.g., payment success) can cause severe business impact

**中文**:
- **权衡**：Outbox模式在发送消息之前增加数据库写入，略微降低性能但大幅提升可靠性
- **决策**：我们优先考虑可靠性而非性能，因为丢失事件（例如，支付成功）可能造成严重的业务影响

#### B. Consistency vs Availability / 一致性与可用性

**English**:
- **Trade-off**: Distributed transactions (e.g., order creation across multiple services) use eventual consistency rather than strong consistency
- **Decision**: We accept eventual consistency to maintain high availability. Compensation transactions ensure consistency is eventually achieved

**中文**:
- **权衡**：分布式事务（例如，跨多个服务的订单创建）使用最终一致性而不是强一致性
- **决策**：我们接受最终一致性以保持高可用性。补偿事务确保最终实现一致性

#### C. Performance vs Reliability / 性能与可靠性

**English**:
- **Trade-off**: Manual message acknowledgment and retry mechanisms add latency but ensure message delivery
- **Decision**: We prioritize reliability. A lost email notification is worse than a slightly slower response

**中文**:
- **权衡**：手动消息确认和重试机制增加延迟但确保消息交付
- **决策**：我们优先考虑可靠性。丢失邮件通知比稍慢的响应更糟糕

#### D. Scalability vs Complexity / 可扩展性与复杂性

**English**:
- **Trade-off**: Microservices architecture increases complexity but enables independent scaling
- **Decision**: We accept increased complexity to support future growth and independent service scaling

**中文**:
- **权衡**：微服务架构增加复杂性但支持独立扩展
- **决策**：我们接受增加的复杂性以支持未来增长和独立服务扩展

---

## 3. ORM Database Interface Model and Tiered Architecture / ORM数据库接口模型与分层架构

### 3.1 ORM Implementation / ORM实现

**Framework**: **Spring Data JPA** with **Hibernate** as the ORM provider

**English**:
ORM (Object-Relational Mapping) is used throughout the Store Service to abstract database operations. Entities are annotated with JPA annotations (`@Entity`, `@Table`, `@Id`, `@ManyToOne`, etc.) to define database schema and relationships.

**中文**:
ORM（对象关系映射）在整个Store服务中使用，以抽象数据库操作。实体使用JPA注解（`@Entity`、`@Table`、`@Id`、`@ManyToOne`等）来定义数据库模式和关系。

**Key ORM Features Used / 使用的关键ORM特性**:
- **Entity Mapping**: Java classes map to database tables
- **Relationship Mapping**: `@ManyToOne`, `@OneToMany` for entity relationships
- **Optimistic Locking**: `@Version` for concurrency control
- **Lazy Loading**: `FetchType.LAZY` for performance optimization
- **Automatic Schema Generation**: `spring.jpa.hibernate.ddl-auto=update`

### 3.2 Tiered Architecture / 分层架构

**Architecture Layers / 架构层次**:

#### Layer 1: Presentation Layer (Controller) / 表示层（Controller）

**Location**: `controller/` package

**English**:
Controllers handle HTTP requests and responses. They are thin layers that delegate business logic to services and return DTOs.

**中文**:
Controller处理HTTP请求和响应。它们是薄层，将业务逻辑委托给服务并返回DTO。

**Examples / 示例**:
- `StoreController.java`: Product and order endpoints
- `OrderController.java`: Order management endpoints
- `AccountController.java`: User account endpoints

**Responsibilities / 职责**:
- Request validation
- HTTP status code management
- Response formatting
- CORS handling

#### Layer 2: Business Logic Layer (Service) / 业务逻辑层（Service）

**Location**: `service/` package

**English**:
Services contain core business logic. They orchestrate operations across repositories and adapters.

**中文**:
Service包含核心业务逻辑。它们协调跨Repository和Adapter的操作。

**Examples / 示例**:
- `OrderService.java`: Order creation, status updates, cancellation
- `PaymentService.java`: Payment processing logic
- `WarehouseService.java`: Inventory management
- `CompensationService.java`: Saga compensation logic

**Responsibilities / 职责**:
- Business rule enforcement
- Transaction management (`@Transactional`)
- Error handling
- Service orchestration

#### Layer 3: Data Access Layer (Repository) / 数据访问层（Repository）

**Location**: `repository/` package

**English**:
Repositories extend Spring Data JPA's `JpaRepository` interface. They provide data access methods without requiring explicit SQL queries (ORM handles it).

**中文**:
Repository扩展Spring Data JPA的`JpaRepository`接口。它们提供数据访问方法，无需显式SQL查询（ORM处理它）。

**Examples / 示例**:
- `OrderRepository.java`: Order data access
- `ProductRepository.java`: Product queries
- `WarehouseProductRepository.java`: Inventory queries with custom JPQL

**Responsibilities / 职责**:
- Database query abstraction
- Custom query methods
- CRUD operations
- Optimistic locking support

#### Layer 4: Entity Model Layer (Model) / 实体模型层（Model）

**Location**: `model/` package

**English**:
Entity classes represent database tables. ORM annotations define table structure, relationships, and constraints.

**中文**:
实体类表示数据库表。ORM注解定义表结构、关系和约束。

**Examples / 示例**:
- `Order.java`: Order entity with relationships
- `Product.java`: Product entity
- `WarehouseProduct.java`: Inventory entity with optimistic locking
- `Payment.java`: Payment transaction entity
- `Account.java`: User account entity

**ORM Annotations Used / 使用的ORM注解**:
```java
@Entity              // Marks as JPA entity
@Table(name = "...") // Maps to database table
@Id                  // Primary key
@GeneratedValue      // Auto-increment
@ManyToOne           // Many-to-one relationship
@JoinColumn          // Foreign key column
@Version             // Optimistic locking
@Column              // Column mapping
```

#### Layer 5: Integration Layer (Adapter) / 集成层（Adapter）

**Location**: `adapter/` package

**English**:
Adapters encapsulate external service communication. They hide implementation details of HTTP calls, message queues, and protocols.

**中文**:
Adapter封装外部服务通信。它们隐藏HTTP调用、消息队列和协议的实现细节。

**Examples / 示例**:
- `DeliveryAdapter.java`: Delivery Service HTTP client
- `BankAdapter.java`: Bank Service HTTP client
- `EmailAdapter.java`: RabbitMQ email message producer

**Responsibilities / 职责**:
- External service abstraction
- Protocol handling (HTTP, AMQP)
- Retry logic
- Error translation

### 3.3 Architecture Clarity Analysis / 架构清晰度分析

#### ✅ Clear Separation Areas / 清晰分离的领域

**English**:
1. **Controller-Service Separation**: Controllers only handle HTTP concerns; services contain business logic
2. **Service-Repository Separation**: Services don't directly use SQL; repositories handle data access
3. **Adapter Pattern**: External service calls are isolated in adapters
4. **DTO Usage**: Controllers return DTOs, not entities (data transfer layer)

**中文**:
1. **Controller-Service分离**：Controller仅处理HTTP关注点；Service包含业务逻辑
2. **Service-Repository分离**：Service不直接使用SQL；Repository处理数据访问
3. **Adapter模式**：外部服务调用隔离在Adapter中
4. **DTO使用**：Controller返回DTO，不是实体（数据传输层）

**Example / 示例**:
```java
// Controller - HTTP layer
@RestController
public class OrderController {
    @Autowired
    private OrderService orderService;  // ✅ Delegates to service
    
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        OrderDTO order = orderService.createOrder(request);  // ✅ Returns DTO
        return ResponseEntity.ok(OrderResponse.success(order));
    }
}

// Service - Business logic layer
@Service
@Transactional
public class OrderService {
    @Autowired
    private OrderRepository orderRepository;  // ✅ Uses repository
    
    public OrderDTO createOrder(CreateOrderRequest request) {
        // Business logic here
        Order order = orderRepository.save(...);
        return convertToDTO(order);  // ✅ Returns DTO, not entity
    }
}
```

#### ⚠️ Areas Where Architecture May Be Blurred / 架构可能模糊的领域

**English**:
1. **Service Layer Dependency**: Services sometimes directly access other services' repositories (e.g., `OrderService` accesses `AccountRepository`). While this works, it could be improved by having services call other services through interfaces.

2. **Transaction Boundaries**: `@Transactional` annotations are used at service level, but sometimes nested services are called, which can create complex transaction scopes.

3. **DTO Conversion**: DTO conversion logic is sometimes in services, sometimes in controllers. Could be centralized in a mapper layer.

4. **Business Logic in Repositories**: Custom JPQL queries in repositories sometimes contain business logic (e.g., ordering by quantity). This is acceptable but blurs the line slightly.

5. **Inconsistent Relationship Mapping**: Some entities use explicit JPA relationship annotations (`@ManyToOne`), while others use logical foreign keys without annotations (e.g., `Order.userId` vs `Order.product`). This works but creates inconsistency.

**中文**:
1. **Service层依赖**：Service有时直接访问其他服务的Repository（例如，`OrderService`访问`AccountRepository`）。虽然这可以工作，但可以通过让服务通过接口调用其他服务来改进。

2. **事务边界**：`@Transactional`注解在服务层使用，但有时调用嵌套服务，这可能创建复杂的事务范围。

3. **DTO转换**：DTO转换逻辑有时在Service中，有时在Controller中。可以集中在一个Mapper层。

4. **Repository中的业务逻辑**：Repository中的自定义JPQL查询有时包含业务逻辑（例如，按数量排序）。这是可以接受的，但稍微模糊了界限。

**Example of Blur / 模糊示例**:
```java
// OrderService directly accesses AccountRepository
@Service
public class OrderService {
    @Autowired
    private AccountRepository accountRepository;  // ⚠️ Direct repository access
    
    // Could be improved: accountService.getAccountByEmail() instead
}
```

### 3.4 ERD Diagram / ERD图

Based on the Store Service entity models:

```
┌─────────────────────────────────────────────────────────────────┐
│                        STORE SERVICE ERD                         │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────┐
│      Account        │
├─────────────────────┤
│ PK id               │
│    username         │ (UNIQUE)
│    email            │ (UNIQUE)
│    password         │
│    active           │
│    emailVerified    │
│    bankAccountNumber│ (UNIQUE, FK → Bank Service)
└─────────────────────┘
         │
         │ 1:N (via user_id)
         │
┌─────────────────────┐
│       Order         │
├─────────────────────┤
│ PK id               │
│    user_id          │ (FK → Account.id, but no explicit @ManyToOne)
│    product_id       │ (FK → Product.id, @ManyToOne)
│    quantity         │
│    unit_price       │
│    total_amount     │
│    status           │ (OrderStatus enum)
│    delivery_id      │ (FK → Delivery Service, no explicit relationship)
│    inventory_transaction_ids│ (comma-separated String)
│    createdAt        │
│    updatedAt        │
└─────────────────────┘
         │                │
         │                │ N:1 (@ManyToOne)
         │                │
┌─────────────────────┐   ┌─────────────────────┐
│      Product        │   │      Payment         │
├─────────────────────┤   ├─────────────────────┤
│ PK id               │   │ PK id               │
│    name             │   │    order_id          │ (UNIQUE, logical FK → Order.id)
│    price            │   │    bankTxnId         │
│    description      │   │    amount            │
└─────────────────────┘   │    status            │ (PaymentStatus enum)
                          │    errorMessage      │
                          │    createdAt         │
                          │    updatedAt         │
                          └─────────────────────┘

┌─────────────────────┐
│     Warehouse       │
├─────────────────────┤
│ PK id               │
│    name             │
│    location         │
│    version          │ (Optimistic lock @Version)
│    modifyTime       │
└─────────────────────┘
         │
         │ 1:N (@ManyToOne on WarehouseProduct)
         │
┌─────────────────────┐
│  WarehouseProduct   │
├─────────────────────┤
│ PK id               │
│    warehouse_id     │ (FK → Warehouse.id, @ManyToOne)
│    product_id       │ (FK → Product.id, @ManyToOne)
│    quantity         │
│    version          │ (Optimistic lock @Version)
│    modifyTime       │
└─────────────────────┘
         │
         │ 1:N (implicit, via foreign keys)
         │
┌─────────────────────┐
│InventoryTransaction │
├─────────────────────┤
│ PK id               │
│    warehouse_id     │ (FK → Warehouse.id, @ManyToOne)
│    product_id       │ (FK → Product.id, @ManyToOne)
│    quantity         │
│    type             │ (HOLD, UNHOLD, IN, OUT enum)
│    transactionTime  │
└─────────────────────┘

┌─────────────────────┐
│   PaymentOutbox     │
├─────────────────────┤
│ PK id               │
│    order_id         │ (logical FK → Order.id)
│    event_type       │ (PaymentStatus enum as String)
│    payload          │ (JSON String)
│    status           │ (PENDING, PROCESSED, FAILED)
│    retry_count      │
│    createdAt        │
│    processedAt      │
└─────────────────────┘

Relationships / 关系:
- Account 1:N Order (via user_id, but no explicit @OneToMany relationship)
- Product N:1 Order (@ManyToOne on Order.product)
- Order 1:1 Payment (via order_id, but no explicit @OneToOne relationship)
- Warehouse 1:N WarehouseProduct (@ManyToOne on WarehouseProduct.warehouse)
- Product 1:N WarehouseProduct (@ManyToOne on WarehouseProduct.product)
- WarehouseProduct 1:N InventoryTransaction (implicit, via warehouse_id + product_id)
- Order 1:N PaymentOutbox (via order_id, but no explicit relationship)
```

**Note on Relationships / 关系说明**:
- Some relationships use explicit JPA annotations (`@ManyToOne`, `@JoinColumn`)
- Others use logical foreign keys without explicit relationship annotations
- This is acceptable for ORM but slightly blurs the relationship definition

### 3.5 ORM Impact on Architecture / ORM对架构的影响

#### Positive Impacts / 正面影响

**English**:
1. **Reduced Boilerplate**: Spring Data JPA eliminates need for manual SQL queries and JDBC code
2. **Type Safety**: Entity relationships are type-safe at compile time
3. **Automatic Schema Management**: Hibernate can generate/update database schema automatically
4. **Relationship Mapping**: Complex relationships (Many-to-One, One-to-Many) are handled declaratively
5. **Optimistic Locking**: Built-in support for concurrency control

**中文**:
1. **减少样板代码**：Spring Data JPA消除了手动SQL查询和JDBC代码的需要
2. **类型安全**：实体关系在编译时是类型安全的
3. **自动模式管理**：Hibernate可以自动生成/更新数据库模式
4. **关系映射**：复杂关系（多对一、一对多）以声明方式处理
5. **乐观锁**：内置并发控制支持

#### Challenges / 挑战

**English**:
1. **Lazy Loading Pitfalls**: `LazyInitializationException` if entities are accessed outside transaction
2. **N+1 Query Problem**: Can occur if relationships are not properly fetched
3. **Performance Overhead**: ORM abstraction adds some overhead compared to raw SQL
4. **Learning Curve**: Developers need to understand JPA/Hibernate concepts

**中文**:
1. **懒加载陷阱**：如果实体在事务外访问，会出现`LazyInitializationException`
2. **N+1查询问题**：如果关系没有正确获取，可能发生
3. **性能开销**：与原始SQL相比，ORM抽象增加了一些开销
4. **学习曲线**：开发人员需要理解JPA/Hibernate概念

#### Maintained Architecture Separation / 保持的架构分离

**English**:
Despite ORM usage, our architecture maintains clear separation:
- **Models are pure entities**: No business logic in entity classes
- **Repositories are data access only**: Custom queries are acceptable, but complex logic is in services
- **Services orchestrate**: Services use repositories and adapters, maintaining layer boundaries
- **DTOs prevent entity exposure**: Controllers return DTOs, not entities, maintaining presentation layer separation

**中文**:
尽管使用ORM，我们的架构保持清晰分离：
- **Model是纯实体**：实体类中没有业务逻辑
- **Repository仅是数据访问**：自定义查询是可以接受的，但复杂逻辑在Service中
- **Service编排**：Service使用Repository和Adapter，保持层次边界
- **DTO防止实体暴露**：Controller返回DTO，不是实体，保持表示层分离

---

## 4. Conclusion / 结论

### 4.1 Summary / 总结

**English**:
Our distributed e-commerce system successfully implements multiple quality attributes through:
- **Availability**: Outbox Pattern, message persistence, manual acknowledgment
- **Reliability**: Retry mechanisms, compensation patterns, optimistic locking
- **Scalability**: Microservices architecture enables independent scaling
- **Performance**: Async processing, lazy loading, optimistic locking
- **Maintainability**: Clear layer separation, adapter pattern, DTO usage
- **Security**: JWT authentication, password encryption, input validation

**中文**:
我们的分布式电商系统成功实现了多个质量属性：
- **可用性**：Outbox模式、消息持久化、手动确认
- **可靠性**：重试机制、补偿模式、乐观锁
- **可扩展性**：微服务架构支持独立扩展
- **性能**：异步处理、懒加载、乐观锁
- **可维护性**：清晰的层次分离、适配器模式、DTO使用
- **安全性**：JWT认证、密码加密、输入验证

### 4.2 Architecture Quality / 架构质量

**English**:
The use of ORM (Spring Data JPA/Hibernate) enhances development productivity and maintains reasonable architecture separation. While there are minor areas where boundaries could be clearer, the overall architecture follows layered principles with clear responsibilities at each level.

**中文**:
ORM（Spring Data JPA/Hibernate）的使用提高了开发生产力，并保持了合理的架构分离。虽然有些小区域边界可以更清晰，但整体架构遵循分层原则，每个层次都有明确的职责。

### 4.3 Recommendations / 建议

**English**:
1. Consider introducing a dedicated Mapper layer for DTO conversion
2. Use service interfaces to improve testability and reduce direct repository dependencies
3. Implement circuit breakers for external service calls to improve resilience
4. Add monitoring and alerting for outbox event processing delays

**中文**:
1. 考虑引入专用的Mapper层进行DTO转换
2. 使用服务接口提高可测试性并减少直接Repository依赖
3. 为外部服务调用实现断路器以提高弹性
4. 添加监控和告警，用于Outbox事件处理延迟

---

**Report Version / 报告版本**: 1.0  
**Date / 日期**: 2025-11-01  
**System / 系统**: COMP5348 Distributed E-commerce Platform
