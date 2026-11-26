package com.green.keyboard_project.repository;

import com.green.keyboard_project.entity.AutoBuildItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AutoBuildItemRepository extends JpaRepository<AutoBuildItem, Integer> {
    List<AutoBuildItem> findByAutoBuild_AutoBuildIdOrderByAutoItemIdAsc(int autoBuildId);

    @Query("""
            select a from AutoBuildItem a
            join fetch a.product
            where a.autoBuild.autoBuildId in :buildIds
            order by a.autoItemId asc
            """)
    List<AutoBuildItem> findWithProductByAutoBuildIds(@Param("buildIds") Collection<Integer> buildIds);

    void deleteByAutoBuild_AutoBuildId(int autoBuildId);
}
