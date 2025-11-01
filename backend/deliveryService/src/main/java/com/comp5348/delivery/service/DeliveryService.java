package com.comp5348.delivery.service;

import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.dto.DeliveryOrderDTO;
import com.comp5348.delivery.dto.DeliveryRequest;
import com.comp5348.delivery.dto.NotificationMessage;
import com.comp5348.delivery.dto.NotificationRequest;
import com.comp5348.delivery.model.Delivery;
import com.comp5348.delivery.repository.DeliveryRepository;
import com.comp5348.delivery.utils.DeliveryStatus;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DeliveryService {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryService.class);

    // 依赖注入：我们需要仓库和RabbitMQ模板
    private final DeliveryRepository deliveryRepository;
    private final RabbitTemplate rabbitTemplate;

    @Autowired
    public DeliveryService(DeliveryRepository deliveryRepository, RabbitTemplate rabbitTemplate) {
        this.deliveryRepository = deliveryRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 创建一个新的配送任务
     * @param request 从API接收到的请求数据
     * @return 用于API响应的DTO对象
     */
    @Transactional // 保证这个方法里的数据库操作是原子性的（要么全成功，要么全失败）
    public DeliveryOrderDTO createDelivery(DeliveryRequest request) {
        logger.info("开始创建新的配送任务，请求者: {}", request.getEmail());

        // 1. 将请求DTO转换为数据库实体 (Entity)
        Delivery newDelivery = new Delivery(request);

        // 2. 将实体保存到数据库
        Delivery savedDelivery = deliveryRepository.save(newDelivery);
        logger.info("配送任务已成功保存到数据库，ID为: {}", savedDelivery.getId());

        // 3. 发送一个消息到RabbitMQ队列，通知后台服务开始处理这个配送任务
        //    我们只发送ID，后台服务可以根据ID去数据库捞取完整信息
        String queueName = "delivery_processing_queue"; // 队列名最好定义在配置文件中
        rabbitTemplate.convertAndSend(queueName, savedDelivery.getId());
        logger.info("已将新任务ID {} 发送到队列 {}", savedDelivery.getId(), queueName);

        rabbitTemplate.convertAndSend(RabbitMQConfig.DELIVERY_QUEUE_NAME, savedDelivery.getId());
        logger.info("已将新任务ID {} 发送到队列 {}", savedDelivery.getId(), RabbitMQConfig.DELIVERY_QUEUE_NAME);

        // 4. 将保存后的实体转换为响应DTO并返回给Controller
        return new DeliveryOrderDTO(savedDelivery);
    }

    /**
     * 取消一个配送任务（验证是否属于指定用户）
     * @param deliveryId 需要被取消的配送任务ID
     * @param userEmail 用户邮箱（用于验证订单是否属于该用户）
     * @return 如果成功取消，返回 true；如果因状态不允许等原因无法取消，返回 false。
     */
    @Transactional
    public boolean cancelDelivery(Long deliveryId, String userEmail) {
        logger.info("收到取消请求，针对配送ID: {}，用户: {}", deliveryId, userEmail);

        // 1. 根据ID查找订单
        Optional<Delivery> deliveryOptional = deliveryRepository.findById(deliveryId);

        // 如果订单根本不存在，直接返回失败
        if (deliveryOptional.isEmpty()) {
            logger.warn("无法取消：数据库中未找到配送ID: {}", deliveryId);
            return false;
        }

        Delivery deliveryToCancel = deliveryOptional.get();
        
        // 2. 验证订单是否属于该用户
        if (!deliveryToCancel.getEmail().equals(userEmail)) {
            logger.warn("无法取消：用户 {} 尝试取消不属于自己的配送订单 {}（属于 {}）", 
                    userEmail, deliveryId, deliveryToCancel.getEmail());
            return false;
        }
        
        DeliveryStatus currentStatus = deliveryToCancel.getDeliveryStatus();

        // 3. 核心业务规则：只有在特定状态下才允许取消
        // 假设：一旦开始派送(DELIVERING)，就无法取消了
        if (currentStatus == DeliveryStatus.CREATED || currentStatus == DeliveryStatus.PICKED_UP) {

            // 4. 执行取消操作
            deliveryToCancel.setDeliveryStatus(DeliveryStatus.CANCELLED);
            deliveryToCancel.setUpdateTime(LocalDateTime.now());
            Delivery savedDelivery = deliveryRepository.save(deliveryToCancel);
            logger.info("配送ID: {} 的状态已成功更新为 -> CANCELLED", savedDelivery.getId());

            // 5. [关键] 发送Webhook通知，告诉Store服务"取消成功了"！
            // 这复用了我们第三阶段的成果，形成了一个完整的闭环
            if (savedDelivery.getNotificationUrl() != null && !savedDelivery.getNotificationUrl().isEmpty()) {
                NotificationRequest payload = new NotificationRequest(savedDelivery.getId(), savedDelivery.getDeliveryStatus());
                NotificationMessage message = new NotificationMessage(payload, savedDelivery.getNotificationUrl());

                rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_QUEUE_NAME, message);
                logger.info("已为配送ID: {} 的取消操作创建Webhook通知", savedDelivery.getId());
            }

            return true; // 返回成功

        } else {
            // 6. 如果状态不满足条件，则拒绝取消
            logger.warn("无法取消配送ID: {}，因为它当前的状态是 {}，已经无法撤销。",
                    deliveryId, currentStatus);
            return false; // 返回失败
        }
    }
    
    /**
     * 取消一个配送任务（不验证用户，用于内部调用）
     * @deprecated 请使用 cancelDelivery(Long, String) 方法
     */
    @Deprecated
    @Transactional
    public boolean cancelDelivery(Long deliveryId) {
        Optional<Delivery> deliveryOptional = deliveryRepository.findById(deliveryId);
        if (deliveryOptional.isEmpty()) {
            return false;
        }
        Delivery deliveryToCancel = deliveryOptional.get();
        DeliveryStatus currentStatus = deliveryToCancel.getDeliveryStatus();
        
        if (currentStatus == DeliveryStatus.CREATED || currentStatus == DeliveryStatus.PICKED_UP) {
            deliveryToCancel.setDeliveryStatus(DeliveryStatus.CANCELLED);
            deliveryToCancel.setUpdateTime(LocalDateTime.now());
            Delivery savedDelivery = deliveryRepository.save(deliveryToCancel);
            
            if (savedDelivery.getNotificationUrl() != null && !savedDelivery.getNotificationUrl().isEmpty()) {
                NotificationRequest payload = new NotificationRequest(savedDelivery.getId(), savedDelivery.getDeliveryStatus());
                NotificationMessage message = new NotificationMessage(payload, savedDelivery.getNotificationUrl());
                rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_QUEUE_NAME, message);
            }
            return true;
        }
        return false;
    }

    /**
     * 批量创建配送任务。
     * @param requests 配送请求列表
     * @return 创建好的配送任务DTO列表
     */
    @Transactional
    public List<DeliveryOrderDTO> createDeliveryBatch(List<DeliveryRequest> requests) {
        List<Delivery> deliveryOrders = new ArrayList<>();

        for (DeliveryRequest request : requests) {
            deliveryOrders.add(new Delivery(request));
        }

        // 一次性保存所有实体，比循环保存效率高
        List<Delivery> savedDeliveries = deliveryRepository.saveAll(deliveryOrders);

        // 为每一个保存成功的实体发送消息，并转换为DTO
        for (Delivery savedDelivery : savedDeliveries) {
            rabbitTemplate.convertAndSend(RabbitMQConfig.DELIVERY_QUEUE_NAME, savedDelivery.getId());
            logger.info("已将批量创建的新任务ID {} 发送到队列 {}", savedDelivery.getId(), RabbitMQConfig.DELIVERY_QUEUE_NAME);
        }

        return savedDeliveries.stream()
                .map(DeliveryOrderDTO::new)
                .collect(Collectors.toList());
    }

    /**
     * 根据ID获取单个配送任务的详细信息（验证是否属于指定用户）
     * @param deliveryId 配送任务ID
     * @param userEmail 用户邮箱（用于验证订单是否属于该用户）
     * @return 配送任务的DTO，如果找不到或不属于该用户则返回null
     */
    @Transactional(readOnly = true)
    public DeliveryOrderDTO getDeliveryOrder(Long deliveryId, String userEmail) {
        Optional<Delivery> deliveryOptional = deliveryRepository.findById(deliveryId);
        
        if (deliveryOptional.isEmpty()) {
            return null;
        }
        
        Delivery delivery = deliveryOptional.get();
        
        // 验证订单是否属于该用户
        if (!delivery.getEmail().equals(userEmail)) {
            logger.warn("User {} attempted to access delivery {} which belongs to {}", 
                    userEmail, deliveryId, delivery.getEmail());
            return null;
        }
        
        return new DeliveryOrderDTO(delivery);
    }
    
    /**
     * 根据ID获取单个配送任务的详细信息（不验证用户，用于内部调用）
     * @deprecated 请使用 getDeliveryOrder(Long, String) 方法
     */
    @Deprecated
    @Transactional(readOnly = true)
    public DeliveryOrderDTO getDeliveryOrder(Long deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .map(DeliveryOrderDTO::new)
                .orElse(null);
    }

    /**
     * 根据客户邮箱获取其所有的配送任务。
     * @param email 客户邮箱
     * @return 配送任务DTO的列表
     */
    @Transactional(readOnly = true)
    public List<DeliveryOrderDTO> getAllDeliveryOrders(String email) {
        List<Delivery> deliveries = deliveryRepository.findByEmail(email);

        // 使用Java Stream API将实体列表转换为DTO列表
        return deliveries.stream()
                .map(DeliveryOrderDTO::new)
                .collect(Collectors.toList());
    }

    /**
     * 根据订单ID取消配送：仅当状态为 CREATED 时允许取消
     */
    @Transactional
    public boolean cancelByOrderId(String orderId) {
        java.util.Optional<Delivery> opt = deliveryRepository.findByOrderId(orderId);
        if (opt.isEmpty()) return false;
        Delivery d = opt.get();
        if (d.getDeliveryStatus() != com.comp5348.delivery.utils.DeliveryStatus.CREATED) {
            return false;
        }
        d.setDeliveryStatus(com.comp5348.delivery.utils.DeliveryStatus.CANCELLED);
        d.setUpdateTime(java.time.LocalDateTime.now());
        Delivery saved = deliveryRepository.save(d);

        // Email notifications are now handled by Store Service via webhook notifications
        // No need to send emails directly from Delivery Service

        // 若有通知URL，发Webhook通知到 StoreService，让其进行退款/回滚库存/邮件
        try {
            if (saved.getNotificationUrl() != null && !saved.getNotificationUrl().isEmpty()) {
                com.comp5348.delivery.dto.NotificationRequest payload = new com.comp5348.delivery.dto.NotificationRequest(saved.getId(), saved.getDeliveryStatus());
                com.comp5348.delivery.dto.NotificationMessage message = new com.comp5348.delivery.dto.NotificationMessage(payload, saved.getNotificationUrl());
                rabbitTemplate.convertAndSend(com.comp5348.delivery.config.RabbitMQConfig.NOTIFICATION_QUEUE_NAME, message);
            }
        } catch (Exception ignore) {}
        return true;
    }
}