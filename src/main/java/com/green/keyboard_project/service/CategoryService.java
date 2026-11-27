package com.green.keyboard_project.service;

import com.green.keyboard_project.dto.category.CategoryResponse;
import com.green.keyboard_project.entity.Category;
import com.green.keyboard_project.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public List<CategoryResponse> getAllCategories() {
        // 모든 카테고리를 displayOrder 순으로 한 번의 쿼리로 일괄 조회
        List<Category> allCategories = categoryRepository.findAll(
                Sort.by(Sort.Direction.ASC, "displayOrder")
        );

        // 부모 카테고리와 자식 카테고리를 메모리에서 분리
        List<Category> parents = new ArrayList<>();
        Map<String, List<CategoryResponse.SubCategoryDto>> childrenMap = new HashMap<>();

        for (Category category : allCategories) {
            if (category.getParent() == null) {
                parents.add(category);
            } else {
                String parentsId = category.getParent().getCategoryId();
                childrenMap.computeIfAbsent(parentsId, k -> new ArrayList<>())
                        .add(new CategoryResponse.SubCategoryDto(category.getCategoryId(),
                category.getName()));
            }
        }

        List<CategoryResponse> result = new ArrayList<>();

        // '전체 상품 (all)' 가상 카테고리 추가 (하위 항목: 대분류 4개)
        CategoryResponse allCategory = CategoryResponse.builder()
                .id("all")
                .label("전체 상품")
                .code("ALL PRODUCTS")
                .subcategories(parents.stream()
                        .map(p -> new CategoryResponse.SubCategoryDto(
                                p.getCategoryId(), p.getName()))
                        .collect(Collectors.toList()))
                .build();
        result.add(allCategory);

        // 부모별 하위 카테고리 조립
        for (Category parent : parents) {
            List<CategoryResponse.SubCategoryDto> subList =
                    childrenMap.getOrDefault(parent.getCategoryId(), Collections.emptyList());
            result.add(CategoryResponse.builder()
                    .id(parent.getCategoryId())
                    .label(parent.getName())
                    .code(parent.getCategoryId().toUpperCase())
                    .subcategories(subList)
                    .build());
        }

        return result;
    }
}
