package com.green.keyboard_project.repository.cart;

import com.green.keyboard_project.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Integer> {
    List<CartItem> findByUser_UserId(int userId);
    Optional<CartItem> findByUserAndProduct(User user, Product product);
<<<<<<< HEAD
    // 커스텀 빌드 중복 담기 체크용
    Optional<CartItem> findByUserAndManualBuild(User user, ManualBuild manualBuild);
    Optional<CartItem> findByUserAndAutoBuild(User user, AutoBuild autoBuild);
    // cartItemId로 특정 장바구니 항목 단건 조회
    Optional<CartItem> findByCartItemIdAndUser(int cartItemId, User user);
=======

    Optional<CartItem> findFirstByUserAndProductAndCustomDesignDataIsNull(User user, Product product);

    Optional<CartItem> findFirstByUserAndProduct(User user, Product product);

    List<CartItem> findAllByUserAndProduct(User user, Product product);

    Optional<CartItem> findByCartItemIdAndUser_UserId(int cartItemId, int userId);

>>>>>>> c667a4bad23a699e7320dddba0873fb01ac5ab67
    boolean existsByManualBuild_ManualBuildId(int manualBuildId);
    boolean existsByAutoBuild_AutoBuildId(int autoBuildId);
    void deleteByUser(User user);
}