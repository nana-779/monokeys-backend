package com.green.keyboard_project.repository;

import com.green.keyboard_project.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Integer> {
    boolean existsByManualBuild_ManualBuildId(int manualBuildId);

    boolean existsByAutoBuild_AutoBuildId(int autoBuildId);
}
