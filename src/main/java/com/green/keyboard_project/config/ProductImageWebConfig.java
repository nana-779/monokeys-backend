package com.green.keyboard_project.config;

import com.green.keyboard_project.service.ProductImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
@RequiredArgsConstructor
public class ProductImageWebConfig implements WebMvcConfigurer {
    private final ProductImageStorageService imageStorageService;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registerDirectory(registry, "/uploads/products/**", imageStorageService.getUploadDirectory());
        registerDirectory(registry, "/uploads/custom-keycaps/**", imageStorageService.getCustomKeycapDirectory());
    }

    private void registerDirectory(ResourceHandlerRegistry registry, String pattern, Path directory) {
        String location = directory.toUri().toString();
        if (!location.endsWith("/")) location += "/";
        registry.addResourceHandler(pattern).addResourceLocations(location);
    }
}
