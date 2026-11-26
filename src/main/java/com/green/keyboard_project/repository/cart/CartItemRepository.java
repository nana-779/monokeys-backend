package com.green.keyboard_project.repository.cart;

import com.green.keyboard_project.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Integer> {
    List<CartItem> findByUser_UserId(int userId);

    @Query("""
            select c from CartItem c
            left join fetch c.product
            left join fetch c.manualBuild
            left join fetch c.autoBuild
            where c.user.userId = :userId
            """)
    List<CartItem> findWithDetailsByUserId(@Param("userId") int userId);
    Optional<CartItem> findByUserAndProduct(User user, Product product);

    // 커스텀 빌드 중복 담기 체크용
    Optional<CartItem> findByUserAndManualBuild(User user, ManualBuild manualBuild);
    Optional<CartItem> findByUserAndAutoBuild(User user, AutoBuild autoBuild);
    // cartItemId로 특정 장바구니 항목 단건 조회
    Optional<CartItem> findByCartItemIdAndUser(int cartItemId, User user);

    boolean existsByManualBuild_ManualBuildId(int manualBuildId);
    boolean existsByAutoBuild_AutoBuildId(int autoBuildId);
    void deleteByUser(User user);

    List<CartItem> findAllByUserAndProduct(User user, Product product);
}