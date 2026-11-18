-- =============================================================================
-- 커스텀 기계식 키보드 이커머스 플랫폼 (keyboard_shop)
-- DDL 초기 세팅 스크립트 (MVP 1·2·3차 통합 최종판 / 총 23개 테이블)
-- RDBMS: MySQL 8.0+ / Engine: InnoDB / Charset: utf8mb4 / Collation: utf8mb4_unicode_ci
-- =============================================================================

-- 1. 스키마 초기화
CREATE SCHEMA IF NOT EXISTS `keyboard_shop`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `keyboard_shop`;

-- =============================================================================
-- Level 0: 독립 마스터 테이블 (부모 테이블 없음)
-- 1. category (카테고리 자기참조 계층형)
-- 2. users (회원 기본)
-- 3. coupon (할인 쿠폰 마스터)
-- =============================================================================

-- 1) category (카테고리 계층형 마스터)
CREATE TABLE IF NOT EXISTS `category` (
    `category_id`   VARCHAR(50)  NOT NULL COMMENT '카테고리 식별 코드 (housing, pcb, switches, keycaps 등)',
    `parent_id`     VARCHAR(50)  NULL     COMMENT '상위 카테고리 코드 (대분류는 NULL)',
    `name`          VARCHAR(100) NOT NULL COMMENT '카테고리 표시 이름',
    `display_order` INT          NOT NULL DEFAULT 0 COMMENT '화면 노출 정렬 순서',
    PRIMARY KEY (`category_id`),
    CONSTRAINT `fk_category_parent`
        FOREIGN KEY (`parent_id`) REFERENCES `category` (`category_id`)
        ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 계층형 카테고리';


-- 2) users (회원 기본 - 소셜 로그인 회원은 password NULL 허용)
CREATE TABLE IF NOT EXISTS `users` (
    `user_id`      INT          NOT NULL AUTO_INCREMENT COMMENT '회원 고유 식별자',
    `email`        VARCHAR(50)  NOT NULL COMMENT '로그인 아이디 (이메일)',
    `password`     VARCHAR(255) NULL     COMMENT '암호화된 비밀번호 (소셜 로그인 회원은 NULL)',
    `name`         VARCHAR(50)  NOT NULL COMMENT '회원 실명',
    `phone_number` VARCHAR(11)  NULL     COMMENT '연락처 (010XXXXXXXX)',
    `role`         ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER' COMMENT '권한 등급',
    `is_deleted`   BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '탈퇴 여부 (Soft Delete 플래그)',
    `deleted_at`   DATETIME     NULL     COMMENT '회원 탈퇴 일시',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입 일시',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '정보 수정 일시',
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uq_users_email` (`email`),
    INDEX `idx_users_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원 정보 및 인증 마스터';


-- 3) coupon (할인 쿠폰 마스터)
CREATE TABLE IF NOT EXISTS `coupon` (
    `coupon_id`           INT          NOT NULL AUTO_INCREMENT COMMENT '쿠폰 고유 식별자',
    `name`                VARCHAR(100) NOT NULL COMMENT '쿠폰명',
    `discount_type`       ENUM('PERCENT', 'FIXED_AMOUNT') NOT NULL COMMENT '할인 방식 (정률/정액)',
    `discount_value`      INT          NOT NULL COMMENT '할인 수치 (예: 10% 또는 5000원)',
    `min_order_amount`    INT          NOT NULL DEFAULT 0 COMMENT '최소 주문 금액 조건',
    `max_discount_amount` INT          NULL     COMMENT '정률 할인 시 최대 할인 한도 금액',
    `valid_from`          DATETIME     NOT NULL COMMENT '사용 가능 시작 일시',
    `valid_until`         DATETIME     NOT NULL COMMENT '사용 가능 종료 일시',
    `is_active`           BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '쿠폰 활성화 여부',
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    PRIMARY KEY (`coupon_id`),
    INDEX `idx_coupon_validity` (`valid_from`, `valid_until`, `is_active`),
    CONSTRAINT `chk_coupon_discount_value` CHECK (`discount_value` > 0),
    CONSTRAINT `chk_coupon_min_order_amount` CHECK (`min_order_amount` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='할인 쿠폰 마스터 정책';


-- =============================================================================
-- Level 1: Level 0 테이블만 참조하는 테이블
-- 4. refresh_token (FK -> users)
-- 5. social_account (FK -> users)
-- 6. address (FK -> users)
-- 7. product (FK -> category)
-- 8. manual_build (FK -> users)
-- 9. auto_build (FK -> users)
-- 10. orders (FK -> users)
-- =============================================================================

-- 4) refresh_token (로그인 갱신용 토큰)
CREATE TABLE IF NOT EXISTS `refresh_token` (
    `refresh_token_id` INT          NOT NULL AUTO_INCREMENT COMMENT '리프레시 토큰 고유 식별자',
    `user_id`          INT          NOT NULL COMMENT '토큰 소유 회원 ID',
    `token_hash`       VARCHAR(64)  NOT NULL COMMENT '해시 처리된 토큰 값 (SHA-256)',
    `expires_at`       DATETIME     NOT NULL COMMENT '토큰 만료 일시',
    `revoked_at`       DATETIME     NULL     COMMENT '토큰 강제 폐기(로그아웃 등) 일시',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '토큰 발급 일시',
    PRIMARY KEY (`refresh_token_id`),
    UNIQUE KEY `uq_refresh_token_hash` (`token_hash`),
    INDEX `idx_refresh_token_user` (`user_id`),
    INDEX `idx_refresh_token_expires` (`expires_at`),
    CONSTRAINT `fk_refresh_token_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='JWT Refresh Token 관리';


-- 5) social_account (소셜 로그인 OAuth2 계정 연동)
CREATE TABLE IF NOT EXISTS `social_account` (
    `social_account_id` INT          NOT NULL AUTO_INCREMENT COMMENT '소셜 연동 고유 식별자',
    `user_id`           INT          NOT NULL COMMENT '연결된 회원 ID',
    `provider`          ENUM('KAKAO', 'NAVER', 'GOOGLE') NOT NULL COMMENT '소셜 제공자',
    `provider_user_id`  VARCHAR(100) NOT NULL COMMENT '소셜 플랫폼 고유 사용자 식별자',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '연동 일시',
    PRIMARY KEY (`social_account_id`),
    UNIQUE KEY `uq_social_provider_user` (`provider`, `provider_user_id`),
    UNIQUE KEY `uq_social_user_provider` (`user_id`, `provider`),
    INDEX `idx_social_account_user` (`user_id`),
    CONSTRAINT `fk_social_account_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='소셜 로그인(OAuth2) 계정 연동';


-- 6) address (회원 배송지 관리)
CREATE TABLE IF NOT EXISTS `address` (
    `address_id`      INT          NOT NULL AUTO_INCREMENT COMMENT '배송지 고유 식별자',
    `user_id`         INT          NOT NULL COMMENT '배송지 소유 회원 ID',
    `address_alias`   VARCHAR(50)  NOT NULL DEFAULT '기본' COMMENT '배송지 별칭 (예: 우리집, 회사)',
    `recipient_name`  VARCHAR(50)  NOT NULL COMMENT '수령인 이름',
    `recipient_phone` VARCHAR(11)  NOT NULL COMMENT '수령인 연락처 (11자리)',
    `postal_code`     VARCHAR(5)   NOT NULL COMMENT '우편번호 (5자리 국가기초구역번호)',
    `road_address`    VARCHAR(255) NOT NULL COMMENT '기본 도로명 주소',
    `detail_address`  VARCHAR(255) NOT NULL COMMENT '상세 주소 (동/호수)',
    `is_default`      BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '기본 배송지 여부',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 일시',
    PRIMARY KEY (`address_id`),
    INDEX `idx_address_user` (`user_id`),
    CONSTRAINT `fk_address_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원별 배송 주소록';


-- 7) product (상품 기본 및 관리자 CRUD - part_type 제거 반영)
CREATE TABLE IF NOT EXISTS `product` (
    `product_id`     INT          NOT NULL AUTO_INCREMENT COMMENT '상품 고유 식별자',
    `category_id`    VARCHAR(50)  NOT NULL COMMENT '소속 카테고리 코드',
    `name`           VARCHAR(200) NOT NULL COMMENT '상품명',
    `base_price`     INT          NOT NULL COMMENT '기본 판매 단가',
    `stock_quantity` INT          NOT NULL DEFAULT 0 COMMENT '보유 재고 수량',
    `thumbnail_url`  VARCHAR(500) NULL     COMMENT '대표 썸네일 이미지 주소',
    `description`    TEXT         NULL     COMMENT '상품 상세 설명',
    `is_active`      BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '판매 활성화/비활성화 상태',
    `version`        INT          NOT NULL DEFAULT 0 COMMENT '재고 동시성 제어용 버전 (Optimistic Lock)',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 일시',
    `is_deleted`     BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '소프트 삭제 여부',
    `deleted_at`     DATETIME     NULL     DEFAULT NULL COMMENT '삭제 일시',
    PRIMARY KEY (`product_id`),
    INDEX `idx_product_category` (`category_id`),
    INDEX `idx_product_cat_active_price` (`category_id`, `is_active`, `base_price`),
    INDEX `idx_product_cat_active_created` (`category_id`, `is_active`, `created_at` DESC),
    CONSTRAINT `chk_product_base_price` CHECK (`base_price` >= 0),
    CONSTRAINT `chk_product_stock` CHECK (`stock_quantity` >= 0),
    CONSTRAINT `fk_product_category`
        FOREIGN KEY (`category_id`) REFERENCES `category` (`category_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 마스터 및 재고';


-- 8) manual_build (수동 커스텀 빌드 마스터)
CREATE TABLE IF NOT EXISTS `manual_build` (
    `manual_build_id`  INT          NOT NULL AUTO_INCREMENT COMMENT '수동 커스텀 빌드 식별자',
    `user_id`          INT          NULL     COMMENT '생성 회원 ID (회원 탈퇴 시 SET NULL 처리)',
    `build_name`       VARCHAR(100) NOT NULL DEFAULT '나만의 수동 커스텀 키보드' COMMENT '빌드 명칭',
    `include_assembly` BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '조립 대행 공임비 신청 여부',
    `assembly_fee`     INT          NOT NULL DEFAULT 20000 COMMENT '조립 공임 단가',
    `total_price`      INT          NOT NULL COMMENT '4개 부품가 + 공임비 총합',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '빌드 생성 일시',
    PRIMARY KEY (`manual_build_id`),
    INDEX `idx_manual_build_user` (`user_id`),
    CONSTRAINT `chk_manual_build_amounts` CHECK (`assembly_fee` >= 0 AND `total_price` >= 0),
    CONSTRAINT `fk_manual_build_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='수동 커스텀 빌드 마스터';


-- 9) auto_build (자동 맞춤 빌드 마스터)
CREATE TABLE IF NOT EXISTS `auto_build` (
    `auto_build_id`       INT          NOT NULL AUTO_INCREMENT COMMENT '자동 맞춤 빌드 식별자',
    `user_id`             INT          NULL     COMMENT '생성 회원 ID (회원 탈퇴 시 SET NULL 처리)',
    `build_name`          VARCHAR(100) NOT NULL DEFAULT '자동 맞춤 커스텀 키보드' COMMENT '빌드 명칭',
    `survey_layout`       VARCHAR(20)  NOT NULL COMMENT '자동 빌드 설문: 배열 (87, 75, 65)',
    `survey_connectivity` VARCHAR(20)  NOT NULL COMMENT '자동 빌드 설문: 연결 (유선, 무선)',
    `survey_sound`        VARCHAR(20)  NOT NULL COMMENT '자동 빌드 설문: 타건음 (thock, clack, silent 등)',
    `survey_keycap`       VARCHAR(20)  NOT NULL COMMENT '자동 빌드 설문: 키캡 (PBT, ABS)',
    `include_assembly`    BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '조립 대행 공임비 신청 여부',
    `assembly_fee`        INT          NOT NULL DEFAULT 20000 COMMENT '조립 공임 단가',
    `total_price`         INT          NOT NULL COMMENT '4개 부품가 + 공임비 총합',
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '빌드 생성 일시',
    PRIMARY KEY (`auto_build_id`),
    INDEX `idx_auto_build_user` (`user_id`),
    CONSTRAINT `chk_auto_build_amounts` CHECK (`assembly_fee` >= 0 AND `total_price` >= 0),
    CONSTRAINT `fk_auto_build_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='자동 맞춤 커스텀 빌드 마스터';


-- 10) orders (주문서 - 회원 전용)
CREATE TABLE IF NOT EXISTS `orders` (
    `order_id`             INT          NOT NULL AUTO_INCREMENT COMMENT '주문 고유 번호',
    `order_number`         VARCHAR(32)  NOT NULL COMMENT '주문 식별 코드 (예: ORD-20261030-XXXX)',
    `user_id`              INT          NOT NULL COMMENT '주문 회원 ID',
    `orderer_name`         VARCHAR(50)  NOT NULL COMMENT '주문자 이름',
    `orderer_phone`        VARCHAR(11)  NOT NULL COMMENT '주문자 연락처',
    `orderer_email`        VARCHAR(100) NOT NULL COMMENT '주문 확인 이메일',
    `order_status`           ENUM('PENDING', 'ORDERED', 'CANCELLED', 'CONFIRMED') NOT NULL DEFAULT 'PENDING' COMMENT '주문 상태',
    `total_product_amount`   INT          NOT NULL DEFAULT 0 COMMENT '상품 총 단가 합계',
    `coupon_discount_amount` INT          NOT NULL DEFAULT 0 COMMENT '주문 시점 확정 쿠폰 할인액',
    `delivery_fee`           INT          NOT NULL DEFAULT 3000 COMMENT '배송비',
    `final_pay_amount`       INT          NOT NULL COMMENT '최종 실 결제 금액',
    `ordered_at`             DATETIME     NULL     COMMENT '결제 승인 완료 일시',
    `created_at`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '주문서 생성 일시',
    PRIMARY KEY (`order_id`),
    UNIQUE KEY `uq_orders_number` (`order_number`),
    INDEX `idx_orders_user` (`user_id`),
    INDEX `idx_orders_user_created` (`user_id`, `created_at` DESC),
    INDEX `idx_orders_status` (`order_status`),
    INDEX `idx_orders_created_at` (`created_at`),
    CONSTRAINT `chk_orders_discount` CHECK (`coupon_discount_amount` >= 0),
    CONSTRAINT `chk_orders_total_product_amount` CHECK (`total_product_amount` >= 0),
    CONSTRAINT `chk_orders_final_pay_amount` CHECK (`final_pay_amount` >= 0),
    CONSTRAINT `fk_orders_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문서 마스터';


-- =============================================================================
-- Level 2: Level 0 및 Level 1을 참조하는 중간 종속 테이블
-- 11. product_spec (FK -> product 1:1)
-- 12. product_image (FK -> product 1:N)
-- 13. manual_build_item (FK -> manual_build, product)
-- 14. auto_build_item (FK -> auto_build, product)
-- 15. cart_item (FK -> users, product, manual_build, auto_build)
-- 16. payment (FK -> orders 1:1)
-- 17. order_delivery (FK -> orders 1:1)
-- 18. user_coupon (FK -> coupon, users, orders)
-- =============================================================================

-- 11) product_spec (부품별 호환성 및 상세 스펙)
CREATE TABLE IF NOT EXISTS `product_spec` (
    `product_id`       INT         NOT NULL COMMENT '1:1 매핑 상품 ID',
    `layout`           VARCHAR(20) NULL     COMMENT '배열 규격 (87, 75, 65 - 하우징/기판 전용, 키캡은 풀배열 공용으로 NULL)',
    `housing_material` VARCHAR(50) NULL     COMMENT '하우징 소재 (알루미늄, 아크릴, 폴리카보네이트, 목재, 플라스틱)',
    `support_pins`     VARCHAR(20) NULL     COMMENT '기판 지원 핀 규격 (3, 5)',
    `is_wireless`      BOOLEAN     DEFAULT FALSE COMMENT '무선(블루투스/2.4G) 지원 여부',
    `switch_pin`       INT         NULL     COMMENT '스위치 핀 수 (3, 5)',
    `switch_type`      VARCHAR(50) NULL     COMMENT '스위치 분류 (linear, tactile, click, silent)',
    `keycap_material`  VARCHAR(50) NULL     COMMENT '키캡 소재 (PBT, ABS, POM)',
    PRIMARY KEY (`product_id`),
    CONSTRAINT `fk_product_spec_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`product_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품별 호환성 및 상세 물리 규격';


-- 12) product_image (상품 다중 갤러리 이미지 - 1:N 매핑)
CREATE TABLE IF NOT EXISTS `product_image` (
    `image_id`      INT          NOT NULL AUTO_INCREMENT COMMENT '이미지 고유 ID',
    `product_id`    INT          NOT NULL COMMENT '상품 ID',
    `image_url`     VARCHAR(500) NOT NULL COMMENT '이미지 URL',
    `display_order` INT          NOT NULL DEFAULT 0 COMMENT '노출 순서',
    PRIMARY KEY (`image_id`),
    UNIQUE KEY `uq_product_image_order` (`product_id`, `display_order`),
    INDEX `idx_product_image_product` (`product_id`, `display_order`),
    CONSTRAINT `fk_product_image_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`product_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 다중 갤러리 이미지';


-- 13) manual_build_item (수동 빌드 구성 부품 목록)
CREATE TABLE IF NOT EXISTS `manual_build_item` (
    `manual_item_id`  INT NOT NULL AUTO_INCREMENT COMMENT '수동 구성 부품 행 식별자',
    `manual_build_id` INT NOT NULL COMMENT '소속 수동 커스텀 빌드 ID',
    `product_id`      INT NOT NULL COMMENT '부품 상품 ID',
    `unit_price`      INT NOT NULL COMMENT '빌드 확정 시점의 부품 단가',
    PRIMARY KEY (`manual_item_id`),
    UNIQUE KEY `uq_manual_build_item_product` (`manual_build_id`, `product_id`),
    INDEX `idx_manual_item_build` (`manual_build_id`),
    INDEX `idx_manual_item_product` (`product_id`),
    CONSTRAINT `chk_manual_item_price` CHECK (`unit_price` >= 0),
    CONSTRAINT `fk_manual_item_build`
        FOREIGN KEY (`manual_build_id`) REFERENCES `manual_build` (`manual_build_id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_manual_item_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`product_id`)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='수동 커스텀 빌드 부품 매핑';


-- 14) auto_build_item (자동 맞춤 빌드 구성 부품 목록)
CREATE TABLE IF NOT EXISTS `auto_build_item` (
    `auto_item_id`  INT NOT NULL AUTO_INCREMENT COMMENT '자동 구성 부품 행 식별자',
    `auto_build_id` INT NOT NULL COMMENT '소속 자동 맞춤 빌드 ID',
    `product_id`    INT NOT NULL COMMENT '부품 상품 ID',
    `unit_price`    INT NOT NULL COMMENT '빌드 확정 시점의 부품 단가',
    PRIMARY KEY (`auto_item_id`),
    UNIQUE KEY `uq_auto_build_item_product` (`auto_build_id`, `product_id`),
    INDEX `idx_auto_item_build` (`auto_build_id`),
    INDEX `idx_auto_item_product` (`product_id`),
    CONSTRAINT `chk_auto_item_price` CHECK (`unit_price` >= 0),
    CONSTRAINT `fk_auto_item_build`
        FOREIGN KEY (`auto_build_id`) REFERENCES `auto_build` (`auto_build_id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_auto_item_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`product_id`)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='자동 맞춤 빌드 부품 매핑';


-- 15) cart_item (단품 / 커스텀 키캡 / 수동·자동 빌드 통합 장바구니)
CREATE TABLE IF NOT EXISTS `cart_item` (
    `cart_item_id`       INT      NOT NULL AUTO_INCREMENT COMMENT '장바구니 항목 식별자',
    `user_id`            INT      NOT NULL COMMENT '소유 회원 ID',
    `product_id`         INT      NULL     COMMENT '단품·커스텀 키캡 상품 ID (빌드 세트일 경우 NULL)',
    `manual_build_id`    INT      NULL     COMMENT '수동 커스텀 세트 구매 시 빌드 ID (그 외 NULL)',
    `auto_build_id`      INT      NULL     COMMENT '자동 맞춤 세트 구매 시 빌드 ID (그 외 NULL)',
    `custom_design_data` TEXT     NULL     COMMENT '커스텀 키캡 디자인 메타데이터 JSON (일반 상품·빌드는 NULL)',
    `quantity`           INT      NOT NULL DEFAULT 1 COMMENT '담은 수량',
    `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '담은 일시',
    `updated_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수량 수정 일시',
    PRIMARY KEY (`cart_item_id`),
    INDEX `idx_cart_item_user` (`user_id`),
    INDEX `idx_cart_item_product` (`product_id`),
    INDEX `idx_cart_item_manual` (`manual_build_id`),
    INDEX `idx_cart_item_auto` (`auto_build_id`),
    CONSTRAINT `chk_cart_item_quantity` CHECK (`quantity` > 0),
    CONSTRAINT `fk_cart_item_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_cart_item_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`product_id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_cart_item_manual`
        FOREIGN KEY (`manual_build_id`) REFERENCES `manual_build` (`manual_build_id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_cart_item_auto`
        FOREIGN KEY (`auto_build_id`) REFERENCES `auto_build` (`auto_build_id`)
        ON DELETE CASCADE,
    CONSTRAINT `chk_cart_item_target`
        CHECK (
            (CASE WHEN `product_id` IS NOT NULL THEN 1 ELSE 0 END)
          + (CASE WHEN `manual_build_id` IS NOT NULL THEN 1 ELSE 0 END)
          + (CASE WHEN `auto_build_id` IS NOT NULL THEN 1 ELSE 0 END) = 1
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='단품, 커스텀키캡, 수동빌드, 자동빌드 통합 장바구니';


-- 16) payment (결제 내역 - orders와 1:1)
CREATE TABLE IF NOT EXISTS `payment` (
    `payment_id`     INT          NOT NULL AUTO_INCREMENT COMMENT '결제 식별자',
    `order_id`       INT          NOT NULL COMMENT '1:1 매핑 주문 ID',
    `payment_method` ENUM('CARD', 'TRANSFER', 'EASY_PAY', 'VIRTUAL_ACCOUNT') NOT NULL COMMENT '결제 수단',
    `pg_provider`    VARCHAR(50)  NOT NULL COMMENT 'PG사 구분 (TOSS, KCP, INICIS 등)',
    `pg_tid`         VARCHAR(100) NULL     COMMENT 'PG사 승인 거래 고유번호 (TID/결제키)',
    `payment_status` ENUM('READY', 'PAID', 'FAILED', 'CANCELLED') NOT NULL DEFAULT 'READY' COMMENT '결제 진행 상태',
    `paid_amount`    INT          NOT NULL COMMENT '승인 금액',
    `paid_at`        DATETIME     NULL     COMMENT '결제 승인 완료 일시',
    `cancelled_at`   DATETIME     NULL     COMMENT '전액/부분 취소 일시',
    PRIMARY KEY (`payment_id`),
    UNIQUE KEY `uq_payment_order_id` (`order_id`),
    UNIQUE KEY `uq_payment_pg_tid` (`pg_tid`),
    CONSTRAINT `chk_payment_paid_amount` CHECK (`paid_amount` >= 0),
    CONSTRAINT `fk_payment_orders`
        FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문 결제 거래 내역 (1:1 매핑)';


-- 17) order_delivery (배송 및 송장 관리 - orders와 1:1)
CREATE TABLE IF NOT EXISTS `order_delivery` (
    `delivery_id`      INT          NOT NULL AUTO_INCREMENT COMMENT '배송 식별자',
    `order_id`         INT          NOT NULL COMMENT '1:1 매핑 주문 ID',
    `delivery_status`  ENUM('PREPARING', 'SHIPPING', 'DELIVERED', 'CANCELLED', 'RETURNED') NOT NULL DEFAULT 'PREPARING' COMMENT '관리자 관리 배송 상태',
    `tracking_company` VARCHAR(50)  NULL     COMMENT '택배사명 (CJ대한통운, 한진 등)',
    `tracking_number`  VARCHAR(100) NULL     COMMENT '운송장 번호',
    `recipient_name`   VARCHAR(50)  NOT NULL COMMENT '수령인 이름 (주문 시점 스냅샷)',
    `recipient_phone`  VARCHAR(20)  NOT NULL COMMENT '수령인 연락처 (주문 시점 스냅샷)',
    `postal_code`      VARCHAR(10)  NOT NULL COMMENT '우편번호 (주문 시점 스냅샷)',
    `road_address`     VARCHAR(255) NOT NULL COMMENT '도로명 주소 (주문 시점 스냅샷)',
    `detail_address`   VARCHAR(255) NOT NULL COMMENT '상세 주소 (주문 시점 스냅샷)',
    `delivery_memo`    VARCHAR(255) NULL     COMMENT '배송 요청 사항',
    `shipped_at`       DATETIME     NULL     COMMENT '출고 일시',
    `delivered_at`     DATETIME     NULL     COMMENT '배송 완료 일시',
    PRIMARY KEY (`delivery_id`),
    UNIQUE KEY `uq_order_delivery_order_id` (`order_id`),
    INDEX `idx_delivery_status` (`delivery_status`),
    INDEX `idx_delivery_tracking` (`tracking_number`),
    CONSTRAINT `fk_order_delivery_orders`
        FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문별 배송 및 주소 스냅샷 (1:1 매핑)';


-- 18) user_coupon (회원 보유 및 주문 사용 쿠폰)
CREATE TABLE IF NOT EXISTS `user_coupon` (
    `user_coupon_id`  INT      NOT NULL AUTO_INCREMENT COMMENT '회원 보유 쿠폰 식별자',
    `coupon_id`       INT      NOT NULL COMMENT '쿠폰 마스터 ID',
    `user_id`         INT      NOT NULL COMMENT '보유 회원 ID',
    `order_id`        INT      NULL     COMMENT '쿠폰이 사용된 주문 ID (미사용 시 NULL, 한 주문당 1개)',
    `is_used`         BOOLEAN  NOT NULL DEFAULT FALSE COMMENT '사용 여부',
    `used_at`         DATETIME NULL     COMMENT '사용 일시',
    `issued_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '발급 일시',
    `discount_amount` INT      NULL     COMMENT '쿠폰 사용 시 확정 할인액',
    PRIMARY KEY (`user_coupon_id`),
    UNIQUE KEY `uq_user_coupon_order` (`order_id`),
    INDEX `idx_user_coupon_user` (`user_id`),
    INDEX `idx_user_coupon_user_used` (`user_id`, `is_used`),
    INDEX `idx_user_coupon_coupon` (`coupon_id`),
    CONSTRAINT `chk_user_coupon_discount` CHECK (`discount_amount` IS NULL OR `discount_amount` >= 0),
    CONSTRAINT `fk_user_coupon_coupon`
        FOREIGN KEY (`coupon_id`) REFERENCES `coupon` (`coupon_id`)
        ON DELETE RESTRICT,
    CONSTRAINT `fk_user_coupon_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_user_coupon_orders`
        FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원별 발급 및 사용 쿠폰 내역';


-- =============================================================================
-- Level 3: 주문 상세 항목 & 환불 테이블
-- 19. order_item (FK -> orders, product, manual_build, auto_build)
-- 20. refund (FK -> payment)
-- =============================================================================

-- 19) order_item (주문 상세 항목 및 가격/명칭/커스텀디자인 스냅샷)
CREATE TABLE IF NOT EXISTS `order_item` (
    `order_item_id`         INT          NOT NULL AUTO_INCREMENT COMMENT '주문 상품 행 식별자',
    `order_id`              INT          NOT NULL COMMENT '소속 주문 ID',
    `product_id`            INT          NULL     COMMENT '대상 단품 ID (빌드 세트일 경우 NULL, 삭제 금지 RESTRICT)',
    `manual_build_id`       INT          NULL     COMMENT '수동 커스텀 세트일 때 빌드 ID (그 외 NULL)',
    `auto_build_id`         INT          NULL     COMMENT '자동 맞춤 세트일 때 빌드 ID (그 외 NULL)',
    `custom_design_data`    TEXT         NULL     COMMENT '커스텀 키캡 디자인 메타데이터 JSON 스냅샷',
    `product_name_snapshot` VARCHAR(200) NOT NULL COMMENT '주문 당시 상품명/빌드명 스냅샷',
    `ordered_price`         INT          NOT NULL COMMENT '주문 당시 결제 단가',
    `quantity`              INT          NOT NULL COMMENT '주문 수량',
    PRIMARY KEY (`order_item_id`),
    INDEX `idx_order_item_orders` (`order_id`),
    INDEX `idx_order_item_product` (`product_id`),
    INDEX `idx_order_item_manual` (`manual_build_id`),
    INDEX `idx_order_item_auto` (`auto_build_id`),
    CONSTRAINT `chk_order_item_ordered_price` CHECK (`ordered_price` >= 0),
    CONSTRAINT `chk_order_item_quantity` CHECK (`quantity` > 0),
    CONSTRAINT `fk_order_item_orders`
        FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`)
        ON DELETE RESTRICT,
    CONSTRAINT `fk_order_item_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`product_id`)
        ON DELETE RESTRICT,
    CONSTRAINT `fk_order_item_manual`
        FOREIGN KEY (`manual_build_id`) REFERENCES `manual_build` (`manual_build_id`)
        ON DELETE RESTRICT,
    CONSTRAINT `fk_order_item_auto`
        FOREIGN KEY (`auto_build_id`) REFERENCES `auto_build` (`auto_build_id`)
        ON DELETE RESTRICT,
    CONSTRAINT `chk_order_item_target`
        CHECK (
            (CASE WHEN `product_id` IS NOT NULL THEN 1 ELSE 0 END)
          + (CASE WHEN `manual_build_id` IS NOT NULL THEN 1 ELSE 0 END)
          + (CASE WHEN `auto_build_id` IS NOT NULL THEN 1 ELSE 0 END) = 1
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문 상세 항목 및 가격 스냅샷';


-- 20) refund (환불 - 1차 전액 취소만 지원, 부분 취소 제외)
CREATE TABLE IF NOT EXISTS `refund` (
    `refund_id`     INT          NOT NULL AUTO_INCREMENT COMMENT '환불 고유 식별자',
    `payment_id`    INT          NOT NULL COMMENT '환불 대상 결제 ID',
    `amount`        INT          NOT NULL COMMENT '실제 환불 금액 (전액 환불)',
    `reason`        VARCHAR(255) NOT NULL COMMENT '환불 사유',
    `status`        ENUM('REQUESTED', 'COMPLETED', 'REJECTED') NOT NULL DEFAULT 'REQUESTED' COMMENT '환불 처리 상태',
    `pg_cancel_tid` VARCHAR(100) NULL     COMMENT 'PG 취소 거래 식별자',
    `requested_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '환불 요청 일시',
    `refunded_at`   DATETIME     NULL     COMMENT '환불 완료 일시',
    PRIMARY KEY (`refund_id`),
    UNIQUE KEY `uq_refund_pg_cancel_tid` (`pg_cancel_tid`),
    INDEX `idx_refund_payment` (`payment_id`),
    INDEX `idx_refund_status` (`status`),
    CONSTRAINT `chk_refund_amount` CHECK (`amount` > 0),
    CONSTRAINT `fk_refund_payment`
        FOREIGN KEY (`payment_id`) REFERENCES `payment` (`payment_id`)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='결제 환불 내역 (1차 전액 취소 전용)';


-- =============================================================================
-- Level 4: 주문 완료 후 파생 및 종속 도메인 테이블
-- 21. review (FK -> product, users, order_item 1:1 매핑)
-- 22. wishlist (FK -> users, product, 복합 유니크 제약)
-- =============================================================================

-- 21) review (실구매 기반 상품 리뷰)
CREATE TABLE IF NOT EXISTS `review` (
    `review_id`     INT          NOT NULL AUTO_INCREMENT COMMENT '리뷰 고유 식별자',
    `product_id`    INT          NOT NULL COMMENT '대상 상품 ID',
    `user_id`       INT          NOT NULL COMMENT '작성 회원 ID',
    `order_item_id` INT          NOT NULL COMMENT '주문 상세 항목 ID (실구매 1회당 1건 검증)',
    `rating`        INT          NOT NULL COMMENT '평점 (1~5점)',
    `content`       TEXT         NOT NULL COMMENT '리뷰 내용',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '작성 일시',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 일시',
    `deleted_at`    DATETIME     NULL     COMMENT '소프트 삭제 일시 (NULL이면 정상)',
    PRIMARY KEY (`review_id`),
    UNIQUE KEY `uq_review_order_item` (`order_item_id`),
    INDEX `idx_review_product` (`product_id`),
    INDEX `idx_review_product_created` (`product_id`, `created_at` DESC),
    INDEX `idx_review_user` (`user_id`),
    CONSTRAINT `chk_review_rating` CHECK (`rating` BETWEEN 1 AND 5),
    CONSTRAINT `fk_review_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`product_id`)
        ON DELETE RESTRICT,
    CONSTRAINT `fk_review_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_review_order_item`
        FOREIGN KEY (`order_item_id`) REFERENCES `order_item` (`order_item_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='실구매 기반 상품 리뷰';


-- 22) wishlist (회원 관심 상품/찜)
CREATE TABLE IF NOT EXISTS `wishlist` (
    `wishlist_id` INT      NOT NULL AUTO_INCREMENT COMMENT '찜 식별자',
    `user_id`     INT      NOT NULL COMMENT '회원 ID',
    `product_id`  INT      NOT NULL COMMENT '찜한 상품 ID',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    PRIMARY KEY (`wishlist_id`),
    UNIQUE KEY `uq_wishlist_user_product` (`user_id`, `product_id`),
    INDEX `idx_wishlist_user` (`user_id`),
    INDEX `idx_wishlist_product` (`product_id`),
    CONSTRAINT `fk_wishlist_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_wishlist_product`
        FOREIGN KEY (`product_id`) REFERENCES `product` (`product_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원 관심 상품 (찜 목록)';


-- =============================================================================
-- Level 5: 리뷰 종속 테이블
-- 23. review_image (FK -> review 1:N)
-- =============================================================================

-- 23) review_image (리뷰 다중 첨부 이미지 - 1:N 매핑)
CREATE TABLE IF NOT EXISTS `review_image` (
    `review_image_id` INT          NOT NULL AUTO_INCREMENT COMMENT '리뷰 이미지 고유 ID',
    `review_id`       INT          NOT NULL COMMENT '리뷰 ID',
    `image_url`       VARCHAR(500) NOT NULL COMMENT '이미지 URL',
    `display_order`   INT          NOT NULL DEFAULT 0 COMMENT '노출 순서',
    PRIMARY KEY (`review_image_id`),
    UNIQUE KEY `uq_review_image_order` (`review_id`, `display_order`),
    INDEX `idx_review_image_review` (`review_id`, `display_order`),
    CONSTRAINT `fk_review_image_review`
        FOREIGN KEY (`review_id`) REFERENCES `review` (`review_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='리뷰 다중 첨부 이미지';


-- =============================================================================
-- 스크립트 실행 검증: 생성된 23개 전체 테이블 목록 확인
-- =============================================================================
SHOW TABLES;