package com.green.keyboard_project.repository;

import com.green.keyboard_project.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Integer> {

    @Query("SELECT p FROM Product p " +
           "LEFT JOIN p.category c " +
           "LEFT JOIN c.parent cp " +
           "WHERE p.isDeleted = false AND p.isActive = true AND p.stockQuantity > 0 AND " +
           "(:category IS NULL OR :category = 'all' OR c.categoryId = :category OR cp.categoryId = :category) AND " +
           "(:subcategory IS NULL OR c.categoryId = :subcategory) AND " +
           "(:keyword IS NULL OR (" +
           "  LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!' OR " +
           "  LOWER(COALESCE(p.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!' OR " +
           "  LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!' OR " +
           "  LOWER(COALESCE(cp.name, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!' OR " +
           "  EXISTS (SELECT 1 FROM ProductSpec ps WHERE ps.productId = p.productId AND (" +
           "    LOWER(COALESCE(ps.layout, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!' OR " +
           "    LOWER(COALESCE(ps.housingMaterial, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!' OR " +
           "    LOWER(COALESCE(ps.keycapMaterial, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!' OR " +
           "    LOWER(COALESCE(ps.switchType, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!'" +
           "  ))" +
           "))")
    Page<Product> findFilteredProducts(@Param("category") String category,
                                       @Param("subcategory") String subcategory,
                                       @Param("keyword") String keyword,
                                       Pageable pageable);

    List<Product> findByIsDeletedFalseAndIsActiveTrueAndStockQuantityGreaterThanOrderByProductIdAsc(int minStock);
}
