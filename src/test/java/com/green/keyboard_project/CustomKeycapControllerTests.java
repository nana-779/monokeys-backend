package com.green.keyboard_project;

import com.green.keyboard_project.entity.Category;
import com.green.keyboard_project.entity.Product;
import com.green.keyboard_project.repository.CategoryRepository;
import com.green.keyboard_project.repository.ProductRepository;
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
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    private MockMvc mvc;

    @BeforeEach
    void setup() {
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
        productRepository.save(p);
    }

    @Test
    void uploadCustomKeycapImagesStoresPngAndRejectsInvalidFile() throws Exception {
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
        MockMultipartFile printImg = new MockMultipartFile("image", "print.png", "image/png", pngHeader);
        MockMultipartFile previewImg = new MockMultipartFile("previewImage", "preview.png", "image/png", pngHeader);

        mvc.perform(multipart("/api/custom-keycaps/images")
                        .file(printImg)
                        .file(previewImg))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.imageUrl").value(containsString("/uploads/custom-keycaps/")))
                .andExpect(jsonPath("$.data.previewImageUrl").value(containsString("/uploads/custom-keycaps/")));

        try (var stream = Files.list(tempUploadRoot.resolve("custom-keycaps"))) {
            assertThat(stream.count()).isEqualTo(2);
        }

        MockMultipartFile badFile = new MockMultipartFile("image", "bad.txt", "text/plain", "not-an-image".getBytes());
        mvc.perform(multipart("/api/custom-keycaps/images").file(badFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
