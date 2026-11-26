package com.green.keyboard_project.repository;

import com.green.keyboard_project.entity.ManualBuildItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ManualBuildItemRepository extends JpaRepository<ManualBuildItem, Integer> {
    List<ManualBuildItem> findByManualBuild_ManualBuildIdOrderByManualItemIdAsc(int manualBuildId);

    @Query("""
            select m from ManualBuildItem m
            join fetch m.product
            where m.manualBuild.manualBuildId in :buildIds
            order by m.manualItemId asc
            """)
    List<ManualBuildItem> findWithProductByManualBuildIds(@Param("buildIds") Collection<Integer> buildIds);

    void deleteByManualBuild_ManualBuildId(int manualBuildId);
}
