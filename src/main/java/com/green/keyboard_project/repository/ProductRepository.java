package com.green.keyboard_project.repository;

import com.green.keyboard_project.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Integer>, JpaSpecificationExecutor<Product> {

    @Override
    @EntityGraph(attributePaths = {"category", "category.parent"})
    Page<Product> findAll(
            Specification<Product> spec,
            Pageable pageable
    );

    @Query("SELECT p FROM Product p " +
            "LEFT JOIN FETCH p.category c " +
            "LEFT JOIN FETCH c.parent " +
            "WHERE p.productId = :productId AND p.isDeleted = false AND p.isActive = true AND p.stockQuantity > 0")
    Optional<Product> findActiveDetailById(@Param("productId") int productId);

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

    @Query("SELECT c.categoryId, c.name, cp.categoryId, COUNT(p) FROM Product p " +
           "JOIN p.category c " +
           "JOIN c.parent cp " +
           "WHERE p.isDeleted = false AND p.isActive = true AND p.stockQuantity > 0 " +
           "GROUP BY c.categoryId, c.name, cp.categoryId, c.displayOrder " +
           "ORDER BY COUNT(p) DESC, c.displayOrder ASC")
    List<Object[]> findActiveSubcategoryMetrics();

    @Query("SELECT c.categoryId, COALESCE(SUM(oi.quantity), 0) FROM OrderItem oi " +
           "JOIN oi.product p " +
           "JOIN p.category c " +
           "WHERE p.isDeleted = false AND p.isActive = true AND p.stockQuantity > 0 AND c.parent IS NOT NULL " +
           "GROUP BY c.categoryId")
    List<Object[]> findOrderQuantityByActiveCategory();

    @Query("SELECT c.categoryId, COUNT(w) FROM Wishlist w " +
           "JOIN w.product p " +
           "JOIN p.category c " +
           "WHERE p.isDeleted = false AND p.isActive = true AND p.stockQuantity > 0 AND c.parent IS NOT NULL " +
           "GROUP BY c.categoryId")
    List<Object[]> findWishlistCountByActiveCategory();
}
