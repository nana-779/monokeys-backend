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

## 3단계: 개발 전용 테스트 주문

백엔드 실행 환경에 TOSS_PAYMENTS_DEV_ORDERS_ENABLED=true를 추가하고 SPRING_PROFILES_ACTIVE에 local을 포함하세요. 기존 프로필이 있다면 지우지 말고 쉼표로 추가하되 prod 또는 production이 활성화된 환경에서는 이 API를 사용할 수 없습니다. 운영 프로필을 지우거나 운영에서 local을 켜서 테스트하지 마세요. 기본값은 비활성화이며 local과 명시적 true를 모두 요구합니다. 설정 변경 후 해당 서버만 재시작합니다.

- POST /api/dev/toss-orders: 본문/쿼리 없이 요청. 201 ApiResponse로 서버가 생성한 orderId, orderName, amount=10000, currency=KRW, status=PENDING 반환. 입력이 있으면 400.
- GET /api/dev/toss-orders/{orderId}: 저장 주문 조회 200. 잘못된 형식400, 없음/만료404.
- 메모리 저장: ConcurrentHashMap, UUID 및 putIfAbsent, 동기화된 생성으로 중복/한도 보호. 최대1000건,30분 만료. 한도429, 반복 충돌503. 서버 재시작 시 소멸하며 DB Entity·Repository를 사용하지 않습니다.
- 루프백 주소에서만 생성·조회 허용. CORS는 http://localhost:3000만 허용. 해당 생성 POST만 CSRF 검사에서 제외합니다. 기존 다른 경로의 CSRF와 기본 인증 보호는 유지합니다.

이 원격 브랜치는 초기 세팅 기반으로 회원 보안 코드가 없습니다. 회원 기능을 가져오는 대신 개발 주문 활성화 시 최후순위 기본 보호 체인(formLogin/httpBasic)을 포함해 사용자 정의 체인 추가로 다른 경로가 공개되는 것을 방지했습니다. 비활성화 시 기존 Spring Boot 기본 보안이 동작합니다. 향후 팀 공통 인증 설정과 통합할 때 체인 순서·CORS·CSRF를 함께 검토해야 하며 쿠키 인증 구현을 추가한 것은 아닙니다.

프론트에서도 개인 .env.local에 TOSS_PAYMENTS_DEV_ORDERS_ENABLED=true를 설정하고 재시작한 뒤 http://localhost:3000/dev/toss-order에서 확인합니다. 서버 금액 조회 및 SDK 금액 설정 완료까지만 수행합니다.

검증: ./gradlew test --tests '*DevTossOrder*', ./gradlew clean build. 앞 절의 개인 키 없는 테스트 환경을 사용합니다. 테스트는 메모리 저장·동시300건·충돌·만료·한도·입력/조회 오류·루프백/CORS·프로필 차단·기존 기본 보호를 확인합니다.

백엔드 2단계 변경은 없습니다. SDK 설치·초기화는 프론트에서만 수행하므로 빈 커밋이나 불필요한 서버 코드를 만들지 않았습니다. 실제 주문 API는 별도 팀 작업이며 수정/대체하지 않았습니다. 결제 요청·승인·취소·웹훅·결제 DB 저장은 다음 단계입니다. 테스트 주문과 금액 준비 성공은 실제 API 인증·결제 성공이 아닙니다.


## 4단계: 결제수단·약관 UI

기존 /dev/toss-order의 서버 주문 생성·조회·금액 설정 이후 공식 renderPaymentMethods와 renderAgreement를 순서대로 렌더링합니다. 로딩/완료/실패 상태, Strict Mode의 중복 마운트·정리·재시도를 처리합니다. 실제 결제 요청이나 승인 호출은 없습니다. 프론트 자동 테스트는 초기화7+주문준비7+렌더링6=20개입니다.

이 단계의 백엔드는 기존 개발 메모리 주문 생성·조회 API를 그대로 사용하며 Java·DB 변경은 없습니다. 프론트의 공식 UI 렌더링만 추가되었습니다.
