# 토스페이먼츠 개발 설정

## 1단계: 서버 테스트 설정

Java 21과 기존 Gradle Wrapper를 사용합니다. 각자 IntelliJ의 KeyboardProjectApplication → Edit Configurations → Environment variables에 개인 값을 설정하세요. 공유 실행 구성·소스·Git에 키나 비밀번호를 저장하지 마세요. .env.example은 변수 이름 참고용이며 Spring Boot가 자동 로드하지 않습니다. .env.local을 작성해도 별도 로더 없이 백엔드 실행 환경에 전달되지 않습니다.

- DB_USERNAME, DB_PASSWORD: 기존 로컬 MySQL 접속 계정. 각자 입력하며 비밀번호를 출력하지 않습니다.
- TOSS_PAYMENTS_SECRET_KEY: 개발자센터 상단 ‘주문서형·결제창형 연동 키’의 테스트 시크릿 키(test_gsk_ 접두어).
- TOSS_PAYMENTS_TEST_ENABLED: 테스트 키 설정을 검사하려면 true. 기본 false이며 꺼져 있으면 키를 요구하지 않습니다.
- SPRING_PROFILES_ACTIVE: 기존 활성 프로필을 보존하세요. 1단계는 별도 local 프로필을 요구하지 않습니다. 3단계 테스트 주문에는 local이 필요합니다.
- TOSS_PAYMENTS_DEV_ORDERS_ENABLED: 기본 false. 3단계 개발 주문을 사용할 때만 true.

프론트 NEXT_PUBLIC_TOSS_PAYMENTS_CLIENT_KEY와 서버 시크릿은 같은 상단 테스트 키 쌍을 사용해야 합니다. 하단 API 개별 연동 키(test_sk_)·클라이언트 키·운영 키는 서버 테스트 설정에서 거부합니다. 키 누락/형식 오류도 명확한 설정 오류로 종료하며 원문을 출력하지 않습니다. 실제 키 및 공식 예제 키 값을 코드·테스트·문서에 넣지 마세요. 테스트의 fixture_only 문자열은 인증에 사용할 수 없는 임의 검증 데이터입니다.

설정 변경 후 해당 프로젝트 서버를 중지하고 위 개인 실행 구성으로 재시작합니다. 이미 8080 포트를 쓰는 프로그램이 있으면 다른 프로그램을 임의 종료하지 마세요.

DB는 localhost:3306/keyboard_shop을 사용합니다. 기존 스키마가 필요하며 ddl-auto=none, spring.sql.init.mode=never로 DB 구조를 변경하지 않습니다. 이 브랜치에는 DB 생성/마이그레이션이나 회원 기능을 포함하지 않습니다. 기존 테이블을 임의 생성하지 마세요.

### 테스트 환경 분리

개인 키 환경변수가 없는 별도 셸에서 Gradle 테스트를 실행하세요. DB_USERNAME/DB_PASSWORD만 필요한 경우 전달하고 TOSS_PAYMENTS_SECRET_KEY, TOSS_PAYMENTS_TEST_ENABLED, TOSS_PAYMENTS_DEV_ORDERS_ENABLED, SPRING_PROFILES_ACTIVE는 테스트 프로세스에서 제거합니다. 개인 실행 구성 자체의 값을 지우라는 뜻이 아닙니다.

- ./gradlew test --tests '*TossPaymentsTestConfigurationTests'
- ./gradlew clean build

키 부재/잘못된 종류/운영 키 거부/비활성화/안전한 표시를 외부 API 호출 없이 검사합니다. 환경변수 로드 성공은 실제 토스 API 인증·결제 성공을 뜻하지 않습니다.

공식 문서: https://docs.tosspayments.com/sdk/v2/js/payment-widget
