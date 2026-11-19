package com.green.keyboard_project;

import com.green.keyboard_project.entity.Category;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.entity.ProductSpec;
import com.green.keyboard_project.entity.User;
import com.green.keyboard_project.entity.Wishlist;
import com.green.keyboard_project.repository.CategoryRepository;
import com.green.keyboard_project.repository.ProductRepository;
import com.green.keyboard_project.repository.ProductSpecRepository;
import com.green.keyboard_project.repository.UserRepository;
import com.green.keyboard_project.repository.WishlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class ProductSearchTests {

    @Autowired WebApplicationContext context;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductSpecRepository productSpecRepository;
    @Autowired UserRepository userRepository;
    @Autowired WishlistRepository wishlistRepository;

    private MockMvc mvc;
    private Category switchesCat;
    private Product linearProduct;
    private Product pbtProduct;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class))
                .build();

        switchesCat = categoryRepository.save(new Category("switches", null, "스위치", 3));
        Category linear = categoryRepository.save(new Category("switch-linear", switchesCat, "리니어", 1));
        Category keycaps = categoryRepository.save(new Category("keycaps", null, "키캡", 4));
        Category pbt = categoryRepository.save(new Category("keycap-pbt", keycaps, "PBT", 1));

        linearProduct = createProduct(linear, "Gateron Oil King 리니어", "깊은 로우피치 타건감 스위치", 6500, 30, true, false);
        saveSpec(linearProduct, null, null, null, "LINEAR");

        pbtProduct = createProduct(pbt, "MONO PBT Minimal Set", "클래식 65% 호환 염료승화 키캡", 49000, 15, true, false);
        saveSpec(pbtProduct, "65%", null, "PBT", null);

        // 품절/삭제 상품은 검색 결과에서 제외되어야 함
        createProduct(linear, "Gateron Oil King 품절판", "재고 0 제외 테스트", 5500, 0, true, false);
        createProduct(linear, "Gateron Oil King 삭제판", "삭제 상품 제외 테스트", 5500, 10, true, true);
    }

    @Test
    void searchesAcrossNameDescriptionCategoryAndSpecsExcludingInactive() throws Exception {
        mvc.perform(get("/api/products").param("keyword", "oil king"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].name").value("Gateron Oil King 리니어"));

        mvc.perform(get("/api/products").param("keyword", "65%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("MONO PBT Minimal Set"));

        mvc.perform(get("/api/products").param("keyword", "스위치"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void supportsPriceSortingAndWildcardEscaping() throws Exception {
        mvc.perform(get("/api/products").param("sort", "price_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].basePrice").value(49000))
                .andExpect(jsonPath("$.data.content[1].basePrice").value(6500));

        // 단독 '_' 입력 시 모든 상품을 긁어오지 않고 리터럴 '_' 매칭만 수행
        mvc.perform(get("/api/products").param("keyword", "___"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void returnsDynamicSearchSuggestionsFromActiveInventoryAndWishlist() throws Exception {
        // 재고 0인 소분류('택타일')와 찜 많은 활성 소분류('리니어' vs '저소음') 생성
        Category silent = categoryRepository.save(new Category("switch-silent", switchesCat, "저소음", 2));
        Category soldOutCat = categoryRepository.save(new Category("switch-tactile", switchesCat, "택타일", 3));
        Product silentProd = createProduct(silent, "Boba Silent Pink", "조용한 저소음", 7000, 20, true, false);
        createProduct(soldOutCat, "Holy Panda Tactile", "품절 테스트", 8000, 0, true, false);

        LocalDateTime now = LocalDateTime.now();
        User u = new User();
        u.setEmail("wish-test@example.com");
        u.setPassword("encodedPw");
        u.setName("테스터");
        u.setRole(User.RoleType.USER);
        u.setDeleted(false);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        userRepository.save(u);

        Wishlist w = new Wishlist();
        w.setUser(u);
        w.setProduct(silentProd);
        w.setCreatedAt(now);
        wishlistRepository.save(w);

        mvc.perform(get("/api/products/search-suggestions").param("limit", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0]").value("저소음"));
    }

    private Product createProduct(
            Category category,
            String name,
            String description,
            int price,
            int stock,
            boolean active,
            boolean deleted
    ) {
        LocalDateTime now = LocalDateTime.now();
        Product p = new Product();
        p.setCategory(category);
        p.setName(name);
        p.setDescription(description);
        p.setBasePrice(price);
        p.setStockQuantity(stock);
        p.setThumbnailUrl("https://example.com/item.jpg");
        p.setActive(active);
        p.setDeleted(deleted);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return productRepository.save(p);
    }

    private void saveSpec(Product product, String layout, String housingMat, String keycapMat, String switchType) {
        ProductSpec spec = new ProductSpec();
        spec.setProduct(product);
        spec.setLayout(layout);
        spec.setHousingMaterial(housingMat);
        spec.setKeycapMaterial(keycapMat);
        spec.setSwitchType(switchType);
        productSpecRepository.save(spec);
    }
}
