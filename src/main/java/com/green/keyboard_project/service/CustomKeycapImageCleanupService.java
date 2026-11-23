package com.green.keyboard_project.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 장바구니에 연결되지 못한 커스텀 키캡 업로드 고아 이미지를 정리합니다.
 * 추후 OrderItem에 customDesignData가 연동되면 주문 참조 여부도 함께 확인하도록 확장합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomKeycapImageCleanupService {

    private static final String CUSTOM_KEYCAPS_PREFIX = "/uploads/custom-keycaps/";
    private static final Pattern UUID_IMAGE_FILENAME =
            Pattern.compile("^[0-9a-fA-F-]{36}\\.(jpg|png|webp)$");

    private final ProductImageStorageService imageStorageService;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.upload.custom-keycap-orphan-grace-minutes:60}")
    private long orphanGraceMinutes;

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(
            name = "app.upload.custom-keycap-cleanup-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    static class SchedulingConfig {
    }

    /**
     * 장바구니(cart_item.custom_design_data)에 참조되지 않은 커스텀 키캡 이미지를 즉시 삭제합니다.
     */
    @Transactional(readOnly = true)
    public boolean deleteIfUnreferenced(String imageUrl) {
        Optional<String> filenameOpt = extractCustomKeycapFilename(imageUrl);
        if (filenameOpt.isEmpty()) {
            return false;
        }
        String filename = filenameOpt.get();
        if (isReferencedInCart(filename)) {
            return false;
        }
        Path baseDir = imageStorageService.getCustomKeycapDirectory().toAbsolutePath().normalize();
        Path target = baseDir.resolve(filename).normalize();
        if (!target.startsWith(baseDir)) {
            return false;
        }
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new IllegalStateException("커스텀 키캡 이미지를 삭제할 수 없습니다.", e);
        }
    }

    /**
     * 유예 시간(기본 60분)이 지난 미참조 고아 커스텀 키캡 이미지 파일을 주기적으로 정리합니다.
     */
    @Scheduled(
            initialDelayString = "${app.upload.custom-keycap-cleanup-initial-delay-ms:3600000}",
            fixedDelayString = "${app.upload.custom-keycap-cleanup-interval-ms:3600000}"
    )
    @Transactional(readOnly = true)
    public int cleanupOrphanFiles() {
        return cleanupOrphanFilesOlderThan(Duration.ofMinutes(Math.max(0, orphanGraceMinutes)));
    }

    @Transactional(readOnly = true)
    public int cleanupOrphanFilesOlderThan(Duration gracePeriod) {
        Path directory = imageStorageService.getCustomKeycapDirectory();
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(gracePeriod);
        int deletedCount = 0;

        try (var stream = Files.list(directory)) {
            for (Path file : stream.toList()) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String filename = file.getFileName().toString();
                if (!UUID_IMAGE_FILENAME.matcher(filename).matches()) {
                    continue;
                }
                Instant lastModified = Files.getLastModifiedTime(file).toInstant();
                if (lastModified.isAfter(cutoff)) {
                    continue;
                }
                if (isReferencedInCart(filename)) {
                    continue;
                }
                if (Files.deleteIfExists(file)) {
                    deletedCount++;
                }
            }
        } catch (IOException e) {
            log.warn("커스텀 키캡 고아 이미지 정리 중 오류가 발생했습니다.", e);
        }

        if (deletedCount > 0) {
            log.info("커스텀 키캡 고아 이미지 {}건을 정리했습니다.", deletedCount);
        }
        return deletedCount;
    }

    private boolean isReferencedInCart(String filename) {
        Long count = entityManager.createQuery(
                        "SELECT COUNT(c) FROM CartItem c WHERE c.customDesignData LIKE :pattern",
                        Long.class
                )
                .setParameter("pattern", "%" + filename + "%")
                .getSingleResult();
        return count != null && count > 0;
    }

    private Optional<String> extractCustomKeycapFilename(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return Optional.empty();
        }
        String trimmed = imageUrl.trim();
        int hashIdx = trimmed.indexOf('#');
        if (hashIdx >= 0) {
            trimmed = trimmed.substring(0, hashIdx);
        }
        int queryIdx = trimmed.indexOf('?');
        if (queryIdx >= 0) {
            trimmed = trimmed.substring(0, queryIdx);
        }
        int prefixIdx = trimmed.lastIndexOf(CUSTOM_KEYCAPS_PREFIX);
        if (prefixIdx < 0 || trimmed.contains("/uploads/products/")) {
            return Optional.empty();
        }
        String filename = trimmed.substring(prefixIdx + CUSTOM_KEYCAPS_PREFIX.length());
        if (!UUID_IMAGE_FILENAME.matcher(filename).matches()) {
            return Optional.empty();
        }
        return Optional.of(filename);
    }
}
