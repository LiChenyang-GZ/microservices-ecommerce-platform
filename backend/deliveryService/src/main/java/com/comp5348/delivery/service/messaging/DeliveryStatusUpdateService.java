package com.comp5348.delivery.service.messaging;

import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.dto.NotificationMessage;
import com.comp5348.delivery.dto.NotificationRequest;
import com.comp5348.delivery.model.Delivery;
import com.comp5348.delivery.repository.DeliveryRepository;
import com.comp5348.delivery.utils.DeliveryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.time.LocalDateTime;
import java.util.Random;

@Service
public class DeliveryStatusUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryStatusUpdateService.class);

    private final DeliveryRepository deliveryRepository;
    private final RabbitTemplate rabbitTemplate;
    private final PlatformTransactionManager transactionManager;
    private final Random random = new Random(); // 用于生成随机数

    // 模拟配送的常量
    private static final int BASE_WAIT_TIME_MS = 10000; // 基础等待时间：5秒
    private static final double PACKAGE_LOSS_RATE = 0.05; // 5% 的丢包率

    @Autowired
    public DeliveryStatusUpdateService(DeliveryRepository deliveryRepository,
                                       RabbitTemplate rabbitTemplate,
                                       PlatformTransactionManager transactionManager) {
        this.deliveryRepository = deliveryRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionManager = transactionManager;
    }

    // @RabbitListener注解告诉Spring：请一直监听这个队列，一旦有消息进来，就调用这个方法！
    @RabbitListener(queues = RabbitMQConfig.DELIVERY_QUEUE_NAME)
//    @Transactional // 保证每次处理消息时，数据库操作是原子的
    public void processDelivery(Long deliveryId) {
        TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());
        try{
            logger.info("【消息消费者】收到新任务，开始处理配送ID: {}", deliveryId);

            // 1. 根据ID从数据库中获取完整的配送信息
            // orElse(null) 表示如果找不到，就返回null
            Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);

            if (delivery == null) {
                logger.warn("【警告】在数据库中未找到ID为 {} 的配送任务，消息将被丢弃。", deliveryId);
                transactionManager.commit(status); // 提交事务并返回
                return; // 结束处理
            }

            if (!shouldContinueProcessing(delivery)) {
                logger.info("配送任务 {} 已是终态 ({})，无需处理。", deliveryId, delivery.getDeliveryStatus());
                transactionManager.commit(status); // 提交事务并返回
                return;
            }

            // 2. 模拟真实世界中的运输延迟
            try {
                int waitTime = BASE_WAIT_TIME_MS + random.nextInt(2000); // 5-7秒的随机延迟
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 重新设置中断状态
                logger.error("线程在等待时被中断", e);
            }

            // 3. 模拟包裹丢失的可能性
            if (random.nextDouble() < PACKAGE_LOSS_RATE) {
                logger.warn("【模拟丢包】配送ID: {} 的包裹不幸丢失！", deliveryId);
                delivery.setDeliveryStatus(DeliveryStatus.LOST);
            } else {
                // 4. 如果没丢，就推进到下一个状态
                updateDeliveryStatus(delivery);
            }

            // 5. 将更新后的状态保存回数据库
            delivery.setUpdateTime(LocalDateTime.now());
            Delivery savedDelivery = deliveryRepository.saveAndFlush(delivery); // saveAndFlush会立即写入数据库
            transactionManager.commit(status); // !! 在这里手动提交事务 !!
            logger.info("配送ID: {} 的状态已更新为 -> {}，并保存至数据库。", deliveryId, delivery.getDeliveryStatus());

            // 6. 检查 notificationUrl 是否存在，如果存在就发送Webhook通知
            if (savedDelivery.getNotificationUrl() != null && !savedDelivery.getNotificationUrl().isEmpty()) {
                NotificationRequest payload = new NotificationRequest(savedDelivery.getId(), savedDelivery.getDeliveryStatus());
                NotificationMessage message = new NotificationMessage(payload, savedDelivery.getNotificationUrl());

                rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_QUEUE_NAME, message);
                logger.info("已为配送ID: {} 创建Webhook通知任务，并发送到队列: {}", savedDelivery.getId(), RabbitMQConfig.NOTIFICATION_QUEUE_NAME);
            }

            // 7. 关键一步：判断是否需要继续处理
            // 如果状态还不是最终状态（送达或丢失），就把任务ID再扔回队列，等待下一次处理
            if (shouldContinueProcessing(savedDelivery)) {
                rabbitTemplate.convertAndSend(RabbitMQConfig.DELIVERY_QUEUE_NAME, savedDelivery.getId());
                logger.info("配送ID: {} 未完成，重新入队。", savedDelivery.getId());
            } else {
                logger.info("【处理完成】配送ID: {} 已达终态。", savedDelivery.getId());
            }
        } catch (ObjectOptimisticLockingFailureException e) { // <-- 在方法的末尾添加 catch
            // 这就是我们优雅的处理方式！
            logger.warn("【乐观锁冲突】处理配送ID: {} 时发生冲突。这很可能是因为它已被其他操作（如取消）更新。" +
                    "本次消息将被安全地丢弃。", deliveryId);
            if (!status.isCompleted()) {
                transactionManager.rollback(status); // 回滚事务
            }
        }

    }

    /**
     * 这是一个私有辅助方法，用于根据当前状态更新到下一个状态
     */
    private void updateDeliveryStatus(Delivery delivery) {
        DeliveryStatus currentStatus = delivery.getDeliveryStatus();
        switch (currentStatus) {
            case CREATED:
                delivery.setDeliveryStatus(DeliveryStatus.PICKED_UP);
                break;
            case PICKED_UP:
                delivery.setDeliveryStatus(DeliveryStatus.DELIVERING);
                break;
            case DELIVERING:
                delivery.setDeliveryStatus(DeliveryStatus.RECEIVED);
                break;
            default:
                // 如果已经是 DELIVERED 或 LOST 状态，则什么都不做
                logger.info("配送ID: {} 状态为 {}，无需更新。", delivery.getId(), currentStatus);
                break;
        }
    }

    /**
     * 判断是否应继续处理该配送任务
     */
    private boolean shouldContinueProcessing(Delivery delivery) {
        DeliveryStatus status = delivery.getDeliveryStatus();
        return status != DeliveryStatus.RECEIVED
                && status != DeliveryStatus.LOST
                && status != DeliveryStatus.CANCELLED;
    }
}