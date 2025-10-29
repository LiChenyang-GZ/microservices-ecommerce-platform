package com.comp5348.delivery.controller;

import com.comp5348.delivery.dto.DeliveryOrderDTO;
import com.comp5348.delivery.dto.DeliveryRequest;
import com.comp5348.delivery.service.DeliveryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController // 告诉Spring这是一个处理RESTful API的控制器
@RequestMapping("/api/deliveries") // 定义这个控制器下所有API的URL前缀
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
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
     * 根据ID获取单个配送任务的API端点（只能获取自己的订单）
     */
    @GetMapping("/{deliveryId}")
    public ResponseEntity<?> getDeliveryOrder(
            @PathVariable Long deliveryId,
            HttpServletRequest request) {
        // 从过滤器设置的请求属性中获取用户邮箱
        String userEmail = (String) request.getAttribute("userEmail");
        
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Unauthorized: Please login first");
        }
        
        DeliveryOrderDTO deliveryOrder = deliveryService.getDeliveryOrder(deliveryId, userEmail);
        if (deliveryOrder != null) {
            return ResponseEntity.ok(deliveryOrder);
        } else {
            // 返回 404 或 403，根据业务需求。这里返回 404 避免泄露订单是否存在
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Delivery order not found or you don't have permission to access it");
        }
    }

    /**
     * 获取当前登录用户的所有配送任务（不再需要传入 email 参数）
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMyDeliveryOrders(HttpServletRequest request) {
        // 从过滤器设置的请求属性中获取用户邮箱
        String userEmail = (String) request.getAttribute("userEmail");
        
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Unauthorized: Please login first");
        }
        
        List<DeliveryOrderDTO> deliveryOrders = deliveryService.getAllDeliveryOrders(userEmail);
        return ResponseEntity.ok(deliveryOrders);
    }

    /**
     * 兼容旧的 API 端点（已废弃，保留用于向后兼容）
     * @deprecated 请使用 GET /api/deliveries/me
     */
    @Deprecated
    @GetMapping("/all/{email}")
    public ResponseEntity<?> getAllDeliveryOrders(
            @PathVariable String email,
            HttpServletRequest request) {
        // 从过滤器设置的请求属性中获取用户邮箱
        String userEmail = (String) request.getAttribute("userEmail");
        
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Unauthorized: Please login first");
        }
        
        // 验证：只能查询自己的订单
        if (!userEmail.equals(email)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Forbidden: You can only view your own delivery orders");
        }
        
        List<DeliveryOrderDTO> deliveryOrders = deliveryService.getAllDeliveryOrders(userEmail);
        return ResponseEntity.ok(deliveryOrders);
    }

    /**
     * 取消配送任务（只能取消自己的订单）
     */
    @PostMapping("/{deliveryId}/cancel")
    public ResponseEntity<String> cancelDelivery(
            @PathVariable Long deliveryId,
            HttpServletRequest request) {
        // 从过滤器设置的请求属性中获取用户邮箱
        String userEmail = (String) request.getAttribute("userEmail");
        
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Unauthorized: Please login first");
        }
        
        boolean success = deliveryService.cancelDelivery(deliveryId, userEmail);

        if (success) {
            return ResponseEntity.ok("Delivery " + deliveryId + " has been cancelled successfully.");
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Delivery " + deliveryId + " could not be cancelled. It might not belong to you, be in an invalid state, or already be delivering.");
        }
    }
}

