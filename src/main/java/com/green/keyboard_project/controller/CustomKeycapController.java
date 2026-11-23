package com.green.keyboard_project.controller;

import com.green.keyboard_project.dto.ApiResponse;
import com.green.keyboard_project.dto.CustomKeycapImageUploadResponse;
import com.green.keyboard_project.service.CustomKeycapImageCleanupService;
import com.green.keyboard_project.service.ProductImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.green.keyboard_project.exceptions.UnauthenticatedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/custom-keycaps")
@RequiredArgsConstructor
public class CustomKeycapController {

    private final ProductImageStorageService imageStorageService;
    private final CustomKeycapImageCleanupService imageCleanupService;

    /**
     * 커스텀 키캡 디자인 이미지(인쇄용 + 캔버스 미리보기) 업로드
     */
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CustomKeycapImageUploadResponse>> uploadCustomKeycapImages(
            @AuthenticationPrincipal Integer userId,
            @RequestPart("image") MultipartFile image,
            @RequestPart(value = "previewImage", required = false) MultipartFile previewImage
    ) {
        if (userId == null) {
            throw new UnauthenticatedException("로그인이 필요합니다.");
        }
        String imageUrl = imageStorageService.storeCustomKeycap(image);
        String previewImageUrl = imageUrl;
        if (previewImage != null && !previewImage.isEmpty()) {
            try {
                previewImageUrl = imageStorageService.storeCustomKeycap(previewImage);
            } catch (RuntimeException e) {
                imageStorageService.deleteByUrl(imageUrl);
                throw e;
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(new CustomKeycapImageUploadResponse(imageUrl, previewImageUrl)));
    }

    /**
     * 장바구니에 연결되지 못한 커스텀 키캡 업로드 고아 이미지 즉시 정리
     */
    @DeleteMapping("/images")
    public ResponseEntity<ApiResponse<Boolean>> deleteUnreferencedCustomKeycapImage(
            @AuthenticationPrincipal Integer userId,
            @RequestParam("imageUrl") String imageUrl
    ) {
        if (userId == null) {
            throw new UnauthenticatedException("로그인이 필요합니다.");
        }
        boolean deleted = imageCleanupService.deleteIfUnreferenced(imageUrl);
        return ResponseEntity.ok(ApiResponse.ok(deleted));
    }
}
