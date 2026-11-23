package com.green.keyboard_project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.green.keyboard_project.entity.Category;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.repository.CategoryRepository;
import com.green.keyboard_project.repository.ProductRepository;
import com.green.keyboard_project.service.CustomKeycapImageCleanupService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class CustomKeycapControllerTests {

    @TempDir
    static Path tempUploadRoot;

    @DynamicPropertySource
    static void overrideUploadPaths(DynamicPropertyRegistry registry) {
        registry.add("app.upload.directory", () -> tempUploadRoot.resolve("products").toString());
        registry.add("app.upload.custom-keycap-directory", () -> tempUploadRoot.resolve("custom-keycaps").toString());
    }

    @Autowired WebApplicationContext context;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CustomKeycapImageCleanupService imageCleanupService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mvc;
    private Product customProduct;

    @BeforeEach
    void setup() throws Exception {
        Path keycapUploadDir = tempUploadRoot.resolve("custom-keycaps");
        Files.createDirectories(keycapUploadDir);
        try (var stream = Files.list(keycapUploadDir)) {
            for (Path existing : stream.toList()) {
                Files.deleteIfExists(existing);
            }
        }

        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class))
                .build();

        Category keycaps = categoryRepository.save(new Category("keycaps", null, "키캡", 4));
        Category customCat = categoryRepository.save(new Category("keycap-custom", keycaps, "커스텀 풀 컬러 키캡", 19));

        Product p = new Product();
        p.setCategory(customCat);
        p.setName("커스텀 풀 컬러 키캡 (R4)");
        p.setBasePrice(2700);
        p.setStockQuantity(999);
        p.setThumbnailUrl("https://example.com/keycap-r4.jpg");
        p.setActive(true);
        p.setDeleted(false);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        customProduct = productRepository.save(p);
    }

    @Test
    void rejectsUnauthenticatedUpload() throws Exception {
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
        MockMultipartFile printImg = new MockMultipartFile("image", "print.png", "image/png", pngHeader);

        mvc.perform(multipart("/api/custom-keycaps/images").file(printImg))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void uploadCustomKeycapImagesStoresPngAndRejectsInvalidFile() throws Exception {
        Cookie[] cookies = signupAndLogin("keycap-upload@example.com");
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
        MockMultipartFile printImg = new MockMultipartFile("image", "print.png", "image/png", pngHeader);
        MockMultipartFile previewImg = new MockMultipartFile("previewImage", "preview.png", "image/png", pngHeader);

        mvc.perform(multipart("/api/custom-keycaps/images")
                        .file(printImg)
                        .file(previewImg)
                        .cookie(cookies))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.imageUrl").value(containsString("/uploads/custom-keycaps/")))
                .andExpect(jsonPath("$.data.previewImageUrl").value(containsString("/uploads/custom-keycaps/")));

        try (var stream = Files.list(tempUploadRoot.resolve("custom-keycaps"))) {
            assertThat(stream.count()).isEqualTo(2);
        }

        MockMultipartFile badFile = new MockMultipartFile("image", "bad.txt", "text/plain", "not-an-image".getBytes());
        mvc.perform(multipart("/api/custom-keycaps/images")
                        .file(badFile)
                        .cookie(cookies))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void customKeycapCartSeparatesRowsByCustomDesignData() throws Exception {
        Cookie[] cookies = signupAndLogin("keycap-cart@example.com");

        String designA = "{\"keyType\":\"R4\",\"customText\":\"A\"}";
        String designB = "{\"keyType\":\"R4\",\"customText\":\"B\"}";

        mvc.perform(post("/api/cart")
                        .cookie(cookies)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("productId", customProduct.getProductId(), "quantity", 1, "customDesignData", designA))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/cart")
                        .cookie(cookies)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("productId", customProduct.getProductId(), "quantity", 2, "customDesignData", designB))))
                .andExpect(status().isOk());

        String cartJson = mvc.perform(get("/api/cart").cookie(cookies))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(cartJson).get("data");
        int firstCartItemId = data.get(0).get("cartItemId").asInt();
        int secondCartItemId = data.get(1).get("cartItemId").asInt();
        assertThat(firstCartItemId).isNotEqualTo(secondCartItemId);

        mvc.perform(patch("/api/cart/items/" + firstCartItemId)
                        .cookie(cookies)
                        .contentType("application/json")
                        .content("{\"quantity\":3}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/cart").cookie(cookies))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].quantity").value(3));

        mvc.perform(delete("/api/cart/items/" + firstCartItemId).cookie(cookies))
                .andExpect(status().isOk());

        mvc.perform(get("/api/cart").cookie(cookies))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].customDesignData").value(designB));
    }

    @Test
    void deletesUnreferencedImageAndProtectsCartReferencedImage() throws Exception {
        Cookie[] cookies = signupAndLogin("keycap-cleanup@example.com");
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};

        String orphanRes = mvc.perform(multipart("/api/custom-keycaps/images")
                        .file(new MockMultipartFile("image", "orphan.png", "image/png", pngHeader))
                        .cookie(cookies))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orphanUrl = objectMapper.readTree(orphanRes).path("data").path("imageUrl").asText();

        String inCartRes = mvc.perform(multipart("/api/custom-keycaps/images")
                        .file(new MockMultipartFile("image", "in-cart.png", "image/png", pngHeader))
                        .cookie(cookies))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String inCartUrl = objectMapper.readTree(inCartRes).path("data").path("imageUrl").asText();

        String cartDesign = objectMapper.writeValueAsString(
                Map.of("keyType", "R4", "printImageUrl", inCartUrl, "previewImageUrl", inCartUrl));
        mvc.perform(post("/api/cart")
                        .cookie(cookies)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("productId", customProduct.getProductId(), "quantity", 1, "customDesignData", cartDesign))))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/custom-keycaps/images").param("imageUrl", orphanUrl))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/custom-keycaps/images")
                        .param("imageUrl", inCartUrl)
                        .cookie(cookies))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));

        mvc.perform(delete("/api/custom-keycaps/images")
                        .param("imageUrl", orphanUrl)
                        .cookie(cookies))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        try (var stream = Files.list(tempUploadRoot.resolve("custom-keycaps"))) {
            assertThat(stream.count()).isEqualTo(1);
        }
    }

    @Test
    void cleanupOrphanFilesRemovesExpiredUnreferencedFilesOnly() throws Exception {
        Cookie[] cookies = signupAndLogin("keycap-gc@example.com");
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};

        mvc.perform(multipart("/api/custom-keycaps/images")
                        .file(new MockMultipartFile("image", "orphan.png", "image/png", pngHeader))
                        .cookie(cookies))
                .andExpect(status().isCreated());

        String keptRes = mvc.perform(multipart("/api/custom-keycaps/images")
                        .file(new MockMultipartFile("image", "kept.png", "image/png", pngHeader))
                        .cookie(cookies))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String keptUrl = objectMapper.readTree(keptRes).path("data").path("imageUrl").asText();

        String cartDesign = objectMapper.writeValueAsString(
                Map.of("keyType", "R4", "printImageUrl", keptUrl));
        mvc.perform(post("/api/cart")
                        .cookie(cookies)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("productId", customProduct.getProductId(), "quantity", 1, "customDesignData", cartDesign))))
                .andExpect(status().isOk());

        int deleted = imageCleanupService.cleanupOrphanFilesOlderThan(Duration.ZERO);
        assertThat(deleted).isEqualTo(1);

        try (var stream = Files.list(tempUploadRoot.resolve("custom-keycaps"))) {
            assertThat(stream.count()).isEqualTo(1);
        }
    }

    private Cookie[] signupAndLogin(String email) throws Exception {
        mvc.perform(post("/api/users/signup")
                        .contentType("application/json")
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Keyboard123",
                                  "name": "테스트회원",
                                  "phoneNumber": "01012345678",
                                  "defaultAddress": {"zonecode": "04524", "roadAddress": "서울 세종대로", "detailAddress": "101호"},
                                  "termsConsent": {"serviceTerms": true, "privacyPolicy": true}
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated());

        var res = mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"Keyboard123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        return res.getHeaders("Set-Cookie").stream()
                .map(v -> {
                    String[] pair = v.split(";", 2)[0].split("=", 2);
                    return new Cookie(pair[0], pair[1]);
                })
                .toArray(Cookie[]::new);
    }
}
