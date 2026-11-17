package com.green.keyboard_project.repository;

import com.green.keyboard_project.entity.CartItem;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Integer> {
    Optional<CartItem> findByUserAndProduct(User user, Product product);
}
