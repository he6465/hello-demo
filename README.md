## Health Routine Tracker — Mini Project 2 (MSA/Cloud)

본 문서는 기존 README를 변경하지 않고, 미니프로젝트 2의 요구사항(스프링 클라우드 기반 MSA 전환, SCG 부하분산 및 성능 측정, nginx 캐시 프록시, EC2 배포, CI/CD) 충족을 위한 전용 가이드입니다.

### 목표와 산출물
- **핵심 목표**
  - Spring Cloud를 활용한 MSA 구조 전환: Service Discovery(Eureka), Spring Cloud Gateway(SCG, LB)
  - nGrinder로 SCG Load Balancing Scale-out 성능 리포팅
  - SCG ↔ Service 사이 **nginx 캐시 프록시** 도입 및 효과 검증
  - AWS EC2에서 Cloud Native 환경 구축 및 서비스 운영
  - 선택된 서비스 기준 **CI/CD 파이프라인** 구축 및 무중단 배포 전략 수립
- **최종 산출물**
  - 프로젝트 수행 계획서(WBS 포함), 요구사항 정의서, API 명세(Swagger), 테스트 결과서, 서비스 아키텍처, ERD, 구축 결과 보고서(PPT+PDF)

### 리포지토리 구조 개요
- `backend/`: Spring Boot 기반 백엔드(현 시점 단일 서비스 → 단계적 분리 대상)
- `frontend/`: React + Vite 기반 프런트엔드
- `build-and-run.(sh|bat)`: 로컬/EC2에서 통합 빌드·실행 스크립트
- `EXECUTION_GUIDE.md`: 실행 관련 보조 문서

### 아키텍처(타깃)
- 클라이언트 → SCG(API Gateway + Load Balancing) → nginx Cache Proxy → 개별 Spring Boot 서비스
- Service Discovery(Eureka)로 서비스 인스턴스 동적 등록/탐색
- nGrinder는 SCG 엔드포인트에 부하를 가해 Scale-out 효과 및 캐시 효과 검증

참고 다이어그램(개념):
```
Client(nGrinder) → Spring Cloud Gateway → nginx Cache Proxy → Spring Boot Services
```

> 초기 단계에서는 현재 `backend` 단일 서비스로 시작 후, `user`, `routine`, `social` 등으로 점진 분리합니다.

---

### 로컬 빠른 시작(현재 단일 서비스 기준)
#### 전제 조건
- JDK 17+
- Node.js 18+ (권장 20 LTS)
- Git, Gradle Wrapper, npm

#### 1) 백엔드 실행
```bash
cd backend
./gradlew bootRun   # Windows: gradlew.bat bootRun
```
- 기본 포트는 `application.yml` 설정을 따릅니다.

#### 2) 프런트엔드 실행
```bash
cd frontend
npm ci
npm run dev
```

#### 3) 통합 스크립트(선택)
```bash
# Unix
./build-and-run.sh
# Windows
build-and-run.bat
```

---

### Spring Cloud 전환 가이드(설계/구현 체크리스트)
- **Eureka Server 구성**
  - 별도 모듈 또는 독립 인스턴스로 배치
  - 각 서비스 및 SCG가 Eureka에 등록/조회
- **Spring Cloud Gateway 구성**
  - 라우팅: `lb://{serviceId}` 패턴 사용
  - 필터: 인증/인가, 로깅/추적, Rate Limiting(옵션)
- **서비스 분리**(예시)
  - `user-service`, `routine-service`, `social-service`
  - 공통 모듈 분리(공통 DTO/에러/유틸)
- **환경 분리**
  - `dev`/`prod` 프로파일, `application-{profile}.yml`

예시(SCG 라우팅) — 제안 스니펫:
```yaml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
      routes:
        - id: routine
          uri: lb://routine-service
          predicates:
            - Path=/api/routines/**
        - id: user
          uri: lb://user-service
          predicates:
            - Path=/api/users/**
```

---

### nginx 캐시 프록시 구성(샘플)
SCG 뒤, 서비스 앞에 nginx를 두고 GET 응답 캐싱을 적용합니다. POST/PUT/DELETE 등 변경성 있는 메서드는 캐시 우회합니다.

```nginx
# /etc/nginx/conf.d/scg-cache.conf
proxy_cache_path /var/cache/nginx levels=1:2 keys_zone=scg_cache:200m max_size=2g inactive=10m use_temp_path=off;
map $request_method $no_cache_methods {
  default 0;
  "POST" 1;
  "PUT" 1;
  "PATCH" 1;
  "DELETE" 1;
}

server {
  listen 80;
  server_name _;

  location /api/ {
    proxy_pass http://scg:8080; # SCG 컨테이너/호스트
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    proxy_cache scg_cache;
    proxy_cache_bypass $no_cache_methods;
    proxy_no_cache $no_cache_methods;
    proxy_cache_lock on;
    proxy_cache_valid 200 302 10m;
    proxy_cache_valid 404 1m;

    add_header X-Cache-Status $upstream_cache_status always;
  }
}
```
- 캐시 적중률, 응답시간, 백엔드 QPS 변화를 nGrinder/서버 메트릭과 함께 기록합니다.

---

### nGrinder 부하 테스트 가이드(요구사항 대응)
1) **환경 준비**
- Controller 1대, Agent N대(동일 VPC 권장)
- 보안그룹: Controller UI(포트 80/8080), Agent ↔ Controller 통신 포트 허용

2) **시나리오**
- 대상 엔드포인트: SCG의 `/api/...`
- 케이스
  - A. 단일 서비스 인스턴스, 캐시 미사용
  - B. 멀티 인스턴스(Scale-out), 캐시 미사용
  - C. 멀티 인스턴스 + nginx 캐시 사용
- 보고 포인트: RPS, 평균/95p/99p, 에러율, 백엔드 CPU/메모리, 네트워크, 캐시 적중률

3) **샘플 스크립트(Groovy)**
```groovy
import static net.grinder.script.Grinder.grinder
import net.grinder.plugin.http.HTTPRequest
import net.grinder.plugin.http.HTTPPluginControl
import HTTPClient.CookieModule

HTTPPluginControl.getConnectionDefaults().timeout = 10000
CookieModule.setCookiePolicyHandler(null)

def request = new HTTPRequest()

class TestRunner {
  def test()
  {
    def res = request.GET("http://<SCG_HOST>/api/routines?page=0&size=10")
    grinder.logger.info("status=${res.statusCode}")
    assert res.statusCode == 200
  }
}
```

4) **보고서 정리**
- Scale-out이 이론적 2배 성능에 못 미치는 경우 병목 분석(네트워크, DB, 캐시 미적용 구간 등)과 개선안 기술

---

### AWS EC2 배포 가이드(요약)
- 필수 패키지: `docker`, `docker compose`, `nginx`, `openjdk-17-jre`
- 네트워크: 80/443(nginx), SCG/서비스 내부 포트는 보안그룹으로 제한
- 로그/모니터링: CloudWatch Agent 또는 Prometheus + Grafana(선택)

예시 `docker-compose.yml`(개념 예):
```yaml
version: "3.9"
services:
  eureka:
    image: ghcr.io/your-org/eureka:latest
    ports: ["8761:8761"]
  scg:
    image: ghcr.io/your-org/scg:latest
    depends_on: [eureka]
  routine-service:
    image: ghcr.io/your-org/routine-service:latest
    deploy:
      replicas: 2   # scale-out 예시
    depends_on: [eureka]
  nginx:
    image: nginx:1.25
    volumes:
      - ./nginx/scg-cache.conf:/etc/nginx/conf.d/default.conf:ro
      - /var/cache/nginx:/var/cache/nginx
    ports: ["80:80"]
    depends_on: [scg]
```

---

### CI/CD(예: GitHub Actions) — 선택 서비스 적용 권장
- 트리거: `push`/`pull_request`
- 잡 구성: Lint/Build/Test → Docker Build → 레지스트리 푸시 → EC2 배포(ssh)

예시 워크플로우 스니펫:
```yaml
name: ci-cd-backend
on:
  push:
    paths: ["backend/**"]
jobs:
  build-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - name: Build
        run: |
          cd backend
          ./gradlew clean build -x test
      - name: Test
        run: |
          cd backend
          ./gradlew test
  docker-deploy:
    needs: build-test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Docker login
        run: echo ${{ secrets.REGISTRY_TOKEN }} | docker login ghcr.io -u ${{ github.actor }} --password-stdin
      - name: Build & Push
        run: |
          docker build -f backend/Dockerfile -t ghcr.io/${{ github.repository }}/routine-service:latest ./backend
          docker push ghcr.io/${{ github.repository }}/routine-service:latest
      - name: Deploy to EC2
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            cd /opt/hrt
            docker pull ghcr.io/${{ github.repository }}/routine-service:latest
            docker compose up -d --no-deps routine-service
```

- 무중단 배포: 다중 인스턴스 + nginx/SCG 헬스체크로 순차 갱신 또는 블루/그린 적용

---

### 품질/보안/운영 체크리스트
- 코드 품질: Lint, 단위 테스트, 통합 테스트 기준선 설정
- 보안: 민감정보는 `Secrets Manager`/GitHub Secrets 사용, HTTPS 적용(ACM+ALB 또는 nginx TLS)
- 관측성: 구조적 로그(JSON), 분산추적(OpenTelemetry), 메트릭 수집

---

### API 명세(Swagger)
- SpringDoc(OpenAPI) 사용 시 기본 경로 예: `/swagger-ui/index.html`
- 배포 환경에서는 인증 보호 또는 비활성화 정책 수립

---

### 문서 산출물 체크리스트(템플릿 연계)
- 프로젝트 수행 계획서 / WBS
- 요구사항 정의서(Use Case, 비기능 요구 포함)
- API 명세(Swagger)
- 테스트 결과서(단위/부하/nGrinder 리포트)
- 서비스 아키텍처 & ERD
- 구축 결과 보고서(PPT+PDF, 글꼴 포함)

---

### 커밋/브랜치 전략(권장)
- 브랜치: `main(master)` / `develop` / `feature/*` / `hotfix/*`
- 커밋 메시지 컨벤션: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`

---

### 참고
- 본 README는 미니프로젝트 2 요구사항 달성을 위한 운영 문서입니다. 세부 구현물(예: Eureka/SCG 모듈, Dockerfile/워크플로우, nginx 설정 파일)은 단계적으로 추가됩니다.
