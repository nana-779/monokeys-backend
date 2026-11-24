package com.green.keyboard_project;

import com.green.keyboard_project.dto.CreateProductRequest;
import com.green.keyboard_project.dto.UpdateProductRequest;
import com.green.keyboard_project.entity.Category;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.entity.ProductImage;
import com.green.keyboard_project.entity.User;
import com.green.keyboard_project.repository.CategoryRepository;
import com.green.keyboard_project.repository.ProductImageRepository;
import com.green.keyboard_project.repository.ProductRepository;
import com.green.keyboard_project.security.AuthCookieFactory;
import com.green.keyboard_project.security.JwtTokenProvider;
import com.green.keyboard_project.service.AdminProductService;
import com.green.keyboard_project.service.ProductService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class AdminProductSyncTests {

    @Autowired WebApplicationContext context;
    @Autowired AdminProductService adminProductService;
    @Autowired ProductService productService;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductImageRepository productImageRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AuthCookieFactory cookieFactory;

    private MockMvc mvc;
    private Category subCategory;
    private Cookie adminCookie;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class))
                .build();

        Category parent = categoryRepository.save(new Category("housing", null, "하우징", 1));
        subCategory = categoryRepository.save(new Category("housing-alu", parent, "알루미늄", 1));

        String token = jwtTokenProvider.createAccessToken(9999, User.RoleType.ADMIN);
        adminCookie = new Cookie(cookieFactory.accessCookie(token).getName(), token);
    }

    @Test
    void adminImageCreateAndUpdateSyncsGalleryAndPrioritizesThumbnail() {
        CreateProductRequest createReq = new CreateProductRequest();
        createReq.setCategoryId(subCategory.getCategoryId());
        createReq.setName("테스트 알루미늄 하우징");
        createReq.setBasePrice(150000);
        createReq.setStockQuantity(5);
        createReq.setDescription("초기 설명");

        var created = adminProductService.createProduct(createReq, "http://localhost:8080/uploads/products/first.jpg");
        Product product = productRepository.findById(created.productId()).orElseThrow();

        // 서브 갤러리 이미지 추가 후 대표 이미지 교체 시 0번만 갱신되고 기존 서브 이미지는 유지되어야 함
        ProductImage sub = new ProductImage();
        sub.setProduct(product);
        sub.setImageUrl("http://localhost:8080/uploads/products/sub.jpg");
        sub.setDisplayOrder(1);
        productImageRepository.save(sub);

        UpdateProductRequest updateReq = new UpdateProductRequest();
        updateReq.setName("테스트 알루미늄 하우징");
        adminProductService.updateProduct(created.productId(), updateReq, "http://localhost:8080/uploads/products/updated.jpg");

        List<ProductImage> synced = productImageRepository.findByProduct_ProductIdOrderByDisplayOrderAsc(created.productId());
        assertThat(synced).hasSize(2);
        assertThat(synced.get(0).getImageUrl()).isEqualTo("http://localhost:8080/uploads/products/updated.jpg");
        assertThat(synced.get(1).getImageUrl()).isEqualTo("http://localhost:8080/uploads/products/sub.jpg");

        var detail = productService.getProductById(created.productId());
        assertThat(detail.getGalleryImages()).containsExactly(
                "http://localhost:8080/uploads/products/updated.jpg",
                "http://localhost:8080/uploads/products/sub.jpg"
        );
    }

    @Test
    void productDtoPrioritizesUpdatedThumbnailEvenIfGalleryDiffers() {
        LocalDateTime now = LocalDateTime.now();
        Product p = new Product();
        p.setCategory(subCategory);
        p.setName("직접 썸네일 변경 테스트");
        p.setBasePrice(100000);
        p.setStockQuantity(3);
        p.setThumbnailUrl("http://localhost:8080/uploads/products/new-thumb.jpg");
        p.setActive(true);
        p.setDeleted(false);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        p = productRepository.save(p);

        ProductImage oldPrimary = new ProductImage();
        oldPrimary.setProduct(p);
        oldPrimary.setImageUrl("https://example.com/old-primary.jpg");
        oldPrimary.setDisplayOrder(0);
        productImageRepository.save(oldPrimary);

        var detail = productService.getProductById(p.getProductId());
        assertThat(detail.getGalleryImages()).containsExactly(
                "http://localhost:8080/uploads/products/new-thumb.jpg",
                "https://example.com/old-primary.jpg"
        );
    }

    @Test
    void rejectsProductDescriptionExceeding300CharsOnCreateAndUpdate() throws Exception {
        String tooLong = "가".repeat(301);

        MockMultipartFile createPart = new MockMultipartFile(
                "product",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                ("{\"categoryId\":\"housing-alu\",\"name\":\"길이 테스트\",\"basePrice\":10000,\"stockQuantity\":5,\"description\":\""
                        + tooLong + "\"}").getBytes(StandardCharsets.UTF_8)
        );
        mvc.perform(MockMvcRequestBuilders.multipart("/api/admin/products")
                        .file(createPart)
                        .cookie(adminCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        CreateProductRequest validReq = new CreateProductRequest();
        validReq.setCategoryId(subCategory.getCategoryId());
        validReq.setName("정상 길이 상품");
        validReq.setBasePrice(10000);
        validReq.setStockQuantity(5);
        validReq.setDescription("정상 설명");
        var created = adminProductService.createProduct(validReq, null);

        MockMultipartFile updatePart = new MockMultipartFile(
                "product",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                ("{\"description\":\"" + tooLong + "\"}").getBytes(StandardCharsets.UTF_8)
        );
        mvc.perform(MockMvcRequestBuilders.multipart("/api/admin/products/" + created.productId())
                        .file(updatePart)
                        .with(request -> {
                            request.setMethod("PATCH");
                            return request;
                        })
                        .cookie(adminCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
