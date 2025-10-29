package com.comp5348.delivery.repository;

import com.comp5348.delivery.model.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


public interface DeliveryRepository extends JpaRepository<Delivery, Long> {
    /**
     * 根据邮箱地址查找所有相关的配送任务。
     * Spring Data JPA会根据方法名自动生成查询。
     * @param email 客户的邮箱
     * @return 配送任务列表
     */
    List<Delivery> findByEmail(String email);
}
