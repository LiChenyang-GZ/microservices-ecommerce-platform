package com.comp5348.delivery.controller;

import com.comp5348.delivery.dto.DeliveryOrderDTO;
import com.comp5348.delivery.dto.DeliveryRequest;
import com.comp5348.delivery.service.DeliveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController // 告诉Spring这是一个处理RESTful API的控制器
@RequestMapping("/api/deliveries") // 定义这个控制器下所有API的URL前缀
public class DeliveryController {

    private final DeliveryService deliveryService;

    // 通过构造函数注入Service
    @Autowired
    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /**
     * 创建配送任务的API端点
     * @param request 包含配送信息的JSON请求体
     * @return 包含已创建任务信息的响应
     */
    @PostMapping("/create") // 响应POST请求，URL为 /api/deliveries/create
    public ResponseEntity<DeliveryOrderDTO> createDelivery(
            @RequestBody DeliveryRequest request // @RequestBody告诉Spring将请求的JSON内容自动转换成DeliveryRequest对象
    ) {
        // 调用Service层来处理业务逻辑
        DeliveryOrderDTO createdOrder = deliveryService.createDelivery(request);

        // 返回一个HTTP 201 Created响应，并在响应体中包含创建好的订单信息
        return ResponseEntity.status(HttpStatus.CREATED).body(createdOrder);
    }

    /**
     * 批量创建配送任务的API端点。
     */
    @PostMapping("/create/batch")
    public ResponseEntity<List<DeliveryOrderDTO>> createDeliveryBatch(@RequestBody List<DeliveryRequest> requests) {
        List<DeliveryOrderDTO> createdOrders = deliveryService.createDeliveryBatch(requests);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdOrders);
    }

    /**
     * 根据ID获取单个配送任务的API端点。
     */
    @GetMapping("/{deliveryId}")
    public ResponseEntity<DeliveryOrderDTO> getDeliveryOrder(@PathVariable Long deliveryId) {
        DeliveryOrderDTO deliveryOrder = deliveryService.getDeliveryOrder(deliveryId);
        if (deliveryOrder != null) {
            return ResponseEntity.ok(deliveryOrder);
        } else {
            // 使用 404 Not Found 更符合RESTful风格
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 根据客户邮箱获取其所有配送任务的API端点。
     */
    @GetMapping("/all/{email}")
    public ResponseEntity<List<DeliveryOrderDTO>> getAllDeliveryOrders(@PathVariable String email) {
        List<DeliveryOrderDTO> deliveryOrders = deliveryService.getAllDeliveryOrders(email);
        // 即使列表为空，也应该返回 200 OK 和一个空列表，而不是错误
        return ResponseEntity.ok(deliveryOrders);
    }


    @PostMapping("/{deliveryId}/cancel")
    public ResponseEntity<String> cancelDelivery(@PathVariable Long deliveryId) {
        boolean success = deliveryService.cancelDelivery(deliveryId);

        if (success) {
            return ResponseEntity.ok("Delivery " + deliveryId + " has been cancelled successfully.");
        } else {
            // 使用 400 Bad Request 更符合RESTful风格，表示客户端的请求不符合业务规则
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Delivery " + deliveryId + " could not be cancelled. It might be in an invalid state (e.g., already delivering).");
        }
    }
}

