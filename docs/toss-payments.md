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



## 4·5단계: 공식 UI와 결제 요청·구매자 인증

기존 개발 주문과 공식 renderPaymentMethods/renderAgreement UI를 유지하고 테스트 결제하기 버튼을 연결했습니다. UI 렌더 완료 전에는 요청할 수 없습니다. 서버 GET으로 저장된 주문을 결제 직전에 다시 확인하고 주문번호·상품명·KRW 금액·PENDING을 비교합니다. 15초 조회 제한, 즉시 중복 잠금, 요청 중 주문 재생성 차단, 오류·취소 재시도를 처리합니다. SDK 자체가 진행 중일 때 임의 타임아웃으로 잠금을 해제하지 않습니다.

requestPayment에는 SDK가 지원하는 orderId/orderName/successUrl/failUrl을 전달합니다. 금액은 3단계 setAmount의 서버 금액을 사용합니다. getSelectedPaymentMethod는 이번 흐름에 필요하지 않아 호출하지 않습니다. 공식 SDK가 선택된 결제수단과 약관 검사를 처리하며 카드사·앱 인증 UI를 직접 만들지 않았습니다.

결과 경로는 /dev/toss-order/success와 /dev/toss-order/fail입니다. 두 경로도 개발 모드와 명시적 활성화를 요구합니다. 성공 URL은 요청 직전 sessionStorage에 저장한 주문과 orderId/amount/paymentKey 필수값을 검사하고 서버 주문을 재조회합니다. paymentKey는 로그·저장소·화면에 기록하지 않습니다. URL만으로 토스 인증의 진위를 확정할 수 없으며 최종 승인/결제완료 처리는 하지 않습니다. 실패 코드는 제한된 형식만 표시하고 제공자의 원문 메시지는 출력하지 않습니다.

기존 테스트 키 검사(test_gck_)를 요청 전에도 반복합니다. 테스트 주문은 서버 메모리 저장(최대1000건/30분)이며 실제 DB 주문이 아닙니다. 실제 주문으로 교체할 때는 dev-test-orders.ts의 연결과 서버 주문 검증을 함께 교체해야 합니다. /checkout과 회원·로그인 코드는 변경하지 않았습니다.

### 검증과 수동 확인

- pnpm run test:payments-sdk: 기존20 + 신규9 = 29개 통과.
- node node_modules/typescript/bin/tsc --noEmit: 통과.
- Windows에서 기존 POSIX build 스크립트와 동등하게 NODE_OPTIONS=--no-deprecation을 프로세스에 설정하고 node node_modules/next/dist/bin/next build: 통과. 기존 Google Fonts 다운로드를 위한 네트워크 접근이 필요했습니다.
- 백엔드 기존 결제 설정·개발 주문 테스트22개와 Gradle build 통과. 전체 애플리케이션 MySQL 테스트는 실행하지 않았습니다.

실제 인증 확인: 이 작업 공간을 테스트 키 및 TOSS_PAYMENTS_DEV_ORDERS_ENABLED=true로 localhost:3000에 실행하고, 기존 개발 주문 백엔드(local 프로필+활성화)를 사용하세요. 기존 서버를 임의 종료하지 말고 포트 전환은 사용자가 결정해야 합니다. 개인 .env.local/키는 작업 공간에 복사하거나 커밋하지 않았습니다. 테스트 공개 클라이언트 키는 별도 미리보기 프로세스의 환경변수로만 전달했습니다.

1. /dev/toss-order 접속 → 테스트 주문 준비 → 공식 결제수단·약관 표시.
2. 필수 약관 확인 → 테스트 결제하기 → 공식 결제창/구매자 인증에서 사용자 조작.
3. 성공 시 /dev/toss-order/success에서 서버 주문 일치와 최종 승인 미실행 문구 확인.
4. 취소 시 현재 화면 안내 또는 SDK 동작에 따른 실패 URL 이동 확인. 취소가 항상 failUrl로 이동한다고 가정하지 않습니다.
5. 실패 경로, 연속 클릭, 주문 만료 후 요청 거부를 확인합니다.

별도 포트3001 미리보기에서는 SDK 초기화, 주문 준비 오류 안내, fail?code=USER_CANCEL 표시, 필수값 없는 success 거부를 브라우저로 확인했습니다. 기존 백엔드 CORS가 localhost:3000만 허용하므로3001에서 정상 주문 준비·공식 결제 인증까지 검증하지 않았습니다. 실제 카드사/휴대폰/앱 인증, 성공 리다이렉트, 결제수단별 실패 리다이렉트와 실청구 없음은 사용자 확인이 남아 있습니다. 합성 URL 확인은 실제 토스 인증 성공을 의미하지 않습니다.

최종 승인 API, 결제 DB 저장, 취소 API, 웹훅은 이번 단계에 포함하지 않습니다. Git commit/push/merge/PR을 수행하지 않았습니다.
