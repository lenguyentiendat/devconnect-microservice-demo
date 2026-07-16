# DevConnect API Gateway and Eureka Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single public Spring Cloud Gateway entry point and Eureka-backed service discovery/load balancing to DevConnect while preserving existing business paths and internal Feed-to-User behavior.

**Architecture:** Add standalone `discovery-server` and WebFlux `api-gateway` Maven modules. Business services register with Eureka; Gateway and Feed fetch the registry and resolve `lb://service-id`/OpenFeign calls through Spring Cloud LoadBalancer. Docker publishes only Gateway and Eureka for application traffic/operations, while explicit Gateway routes keep `/internal/**` private.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Cloud 2025.1.2/5.0.2, Spring Cloud Gateway Server WebFlux, Netflix Eureka, OpenFeign, Spring Cloud LoadBalancer, Actuator, Maven, Docker Compose, JUnit 5.

## Global Constraints

- Keep Java `21`, Spring Boot `4.1.0`, and Spring Cloud BOM `2025.1.2`.
- Preserve public business paths exactly: `/api/users/**`, `/api/feed/**`, `/api/search/**`, `/api/notifications/**`.
- Never route `/internal/**`, downstream `/actuator/**`, Eureka APIs, or arbitrary discovered services through Gateway.
- Use explicit `lb://` routes; keep Discovery Locator disabled.
- Remove `USER_SERVICE_BASE_URL`; Feed OpenFeign must resolve `user-service` by service ID.
- Do not add retry to POST routes, authentication, rate limiting, TLS, circuit breakers, or a Config Server in this change.
- Compose publishes Gateway `8080` and Eureka `8761`; business-service ports are internal only.
- Preserve existing Kafka, Cassandra, PostgreSQL, API response, and parallel-validation behavior.
- Preserve unrelated worktree changes and stage exact paths at every commit.

---

### Task 1: Add the Standalone Eureka Discovery Server

**Files:**
- Modify: `pom.xml`
- Create: `discovery-server/pom.xml`
- Create: `discovery-server/src/test/java/com/devconnect/discovery/DiscoveryServerApplicationTests.java`
- Create: `discovery-server/src/main/java/com/devconnect/discovery/DiscoveryServerApplication.java`
- Create: `discovery-server/src/main/resources/application.yaml`

**Interfaces:**
- Consumes: Spring Cloud BOM `${spring-cloud.version}` from the parent POM.
- Produces: Eureka registry at `http://<host>:8761/eureka/`, dashboard at `/`, and health at `/actuator/health`.

- [ ] **Step 1: Register the module and create its dependency-only POM**

Add the module after the four business modules in root `pom.xml`:

```xml
<module>discovery-server</module>
```

Create `discovery-server/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.devconnect</groupId>
        <artifactId>devconnect-platform-project</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>discovery-server</artifactId>
    <name>discovery-server</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Write the failing server context/config test**

Create `DiscoveryServerApplicationTests.java`:

```java
package com.devconnect.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class DiscoveryServerApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void runsAsStandaloneRegistry() {
        assertEquals("false", environment.getProperty("eureka.client.register-with-eureka"));
        assertEquals("false", environment.getProperty("eureka.client.fetch-registry"));
        assertEquals("8761", environment.getProperty("server.port"));
    }
}
```

- [ ] **Step 3: Run the test and witness RED**

Run:

```powershell
mvn -pl discovery-server test
```

Expected: the test starts but fails to locate a `@SpringBootConfiguration` because
`DiscoveryServerApplication` and application configuration do not exist.

- [ ] **Step 4: Add the Eureka Server application and standalone config**

Create `DiscoveryServerApplication.java`:

```java
package com.devconnect.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@EnableEurekaServer
@SpringBootApplication
public class DiscoveryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}
```

Create `application.yaml`:

```yaml
server:
  port: ${DISCOVERY_SERVER_PORT:8761}

spring:
  application:
    name: discovery-server

eureka:
  instance:
    hostname: ${EUREKA_INSTANCE_HOSTNAME:localhost}
  client:
    register-with-eureka: false
    fetch-registry: false
    service-url:
      defaultZone: http://${eureka.instance.hostname}:${server.port}/eureka/

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never
```

- [ ] **Step 5: Run GREEN verification**

Run:

```powershell
mvn -pl discovery-server test
```

Expected: `DiscoveryServerApplicationTests` passes with zero failures/errors.

- [ ] **Step 6: Commit only Discovery Server files**

```powershell
git add -- pom.xml discovery-server/pom.xml discovery-server/src
git diff --cached --check
git commit -m "feat: add eureka discovery server"
```

---

### Task 2: Add the Explicit-Route API Gateway

**Files:**
- Modify: `pom.xml`
- Create: `api-gateway/pom.xml`
- Create: `api-gateway/src/test/java/com/devconnect/gateway/ApiGatewayApplicationTests.java`
- Create: `api-gateway/src/main/java/com/devconnect/gateway/ApiGatewayApplication.java`
- Create: `api-gateway/src/main/resources/application.yaml`

**Interfaces:**
- Consumes: Eureka service IDs `user-service`, `feed-service`, `search-service`, `notification-service`.
- Produces: public edge on port `8080` with exactly four route definitions and own `/actuator/health`.

- [ ] **Step 1: Register the module and create its POM**

Add after `discovery-server` in root `pom.xml`:

```xml
<module>api-gateway</module>
```

Create `api-gateway/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.devconnect</groupId>
        <artifactId>devconnect-platform-project</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>api-gateway</artifactId>
    <name>api-gateway</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Write failing tests for allowlisted routes and private paths**

Create `ApiGatewayApplicationTests.java`:

```java
package com.devconnect.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false"
        }
)
@AutoConfigureWebTestClient
class ApiGatewayApplicationTests {

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void exposesExactlyFourExplicitLoadBalancedRoutes() {
        Map<String, String> routes = routeDefinitionLocator.getRouteDefinitions()
                .collectList()
                .blockOptional()
                .orElseThrow()
                .stream()
                .collect(Collectors.toMap(
                        RouteDefinition::getId,
                        route -> route.getUri().toString()
                ));

        assertEquals(Map.of(
                "user-service", "lb://user-service",
                "feed-service", "lb://feed-service",
                "search-service", "lb://search-service",
                "notification-service", "lb://notification-service"
        ), routes);
    }

    @Test
    void doesNotExposeInternalEndpoints() {
        webTestClient.get()
                .uri("/internal/users/u001/status")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void returnsNotFoundForUnknownPaths() {
        webTestClient.get()
                .uri("/unknown")
                .exchange()
                .expectStatus().isNotFound();
    }
}
```

- [ ] **Step 3: Run the Gateway tests and witness RED**

Run:

```powershell
mvn -pl api-gateway test
```

Expected: the test fails to locate a `@SpringBootConfiguration` because
`ApiGatewayApplication` and route configuration do not exist.

- [ ] **Step 4: Add the Gateway application**

Create `ApiGatewayApplication.java`:

```java
package com.devconnect.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

- [ ] **Step 5: Add explicit routes, discovery behavior, health, and timeouts**

Create `api-gateway/src/main/resources/application.yaml`:

```yaml
server:
  port: ${API_GATEWAY_PORT:8080}

spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      server:
        webflux:
          discovery:
            locator:
              enabled: false
          routes:
            - id: user-service
              uri: lb://user-service
              predicates:
                - Path=/api/users/**
            - id: feed-service
              uri: lb://feed-service
              predicates:
                - Path=/api/feed/**
            - id: search-service
              uri: lb://search-service
              predicates:
                - Path=/api/search/**
            - id: notification-service
              uri: lb://notification-service
              predicates:
                - Path=/api/notifications/**
          httpclient:
            connect-timeout: ${GATEWAY_CONNECT_TIMEOUT_MS:5000}
            response-timeout: ${GATEWAY_RESPONSE_TIMEOUT:10s}

eureka:
  client:
    register-with-eureka: false
    fetch-registry: true
    service-url:
      defaultZone: ${EUREKA_SERVER_URL:http://localhost:8761/eureka/}
    rest-template-timeout:
      connect-timeout: 5000
      connect-request-timeout: 5000
      socket-timeout: 10000

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never
```

- [ ] **Step 6: Run GREEN Gateway verification**

Run:

```powershell
mvn -pl api-gateway test
```

Expected: three Gateway tests pass; route map contains only four `lb://` entries.

- [ ] **Step 7: Commit only Gateway files**

```powershell
git add -- pom.xml api-gateway/pom.xml api-gateway/src
git diff --cached --check
git commit -m "feat: add eureka backed api gateway"
```

---

### Task 3: Register Business Services and Move OpenFeign to Discovery

**Files:**
- Modify: `user-service/pom.xml`
- Modify: `feed-service/pom.xml`
- Modify: `search-service/pom.xml`
- Modify: `notification-service/pom.xml`
- Modify: `user-service/src/main/resources/application.yaml`
- Modify: `feed-service/src/main/resources/application.yaml`
- Modify: `search-service/src/main/resources/application.yaml`
- Modify: `notification-service/src/main/resources/application.yaml`
- Modify: `user-service/src/test/resources/application.yaml`
- Modify: `feed-service/src/test/java/com/devconnect/feed/FeedServiceApplicationTests.java`

**Interfaces:**
- Consumes: Eureka registry URL `${EUREKA_SERVER_URL:http://localhost:8761/eureka/}`.
- Produces: registered service IDs matching each `spring.application.name`; Feed resolves `user-service` without a URL property.

- [ ] **Step 1: Write the failing Feed LoadBalancer context assertion**

Add the imports and field to `FeedServiceApplicationTests`:

```java
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;

@Autowired
private LoadBalancerClientFactory loadBalancerClientFactory;
```

Change the annotation and assertion:

```java
@SpringBootTest(properties = {
        "spring.cassandra.schema-action=none",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class FeedServiceApplicationTests {
    // existing fields

    @Test
    void contextLoads() {
        assertNotNull(userServiceClient);
        assertNotNull(loadBalancerClientFactory);
    }
}
```

- [ ] **Step 2: Run the test and witness RED**

Run:

```powershell
mvn -pl feed-service -Dtest=FeedServiceApplicationTests test
```

Expected: test compilation fails because `LoadBalancerClientFactory` is not available from an explicit LoadBalancer dependency.

- [ ] **Step 3: Add Eureka Client/Actuator dependencies to every business service**

Add this pair to `user-service`, `search-service`, and `notification-service` POMs:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

Add these three dependencies to `feed-service/pom.xml` after OpenFeign:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 4: Add client registration, health, and timeout configuration**

Append this configuration to User, Search, and Notification application YAML, setting `fetch-registry: false`:

```yaml
server:
  forward-headers-strategy: framework

eureka:
  client:
    register-with-eureka: true
    fetch-registry: false
    service-url:
      defaultZone: ${EUREKA_SERVER_URL:http://localhost:8761/eureka/}
    healthcheck:
      enabled: true
    rest-template-timeout:
      connect-timeout: 5000
      connect-request-timeout: 5000
      socket-timeout: 10000
  instance:
    prefer-ip-address: ${EUREKA_PREFER_IP_ADDRESS:false}
    health-check-url-path: /actuator/health
    status-page-url-path: /actuator/info

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never
```

Merge `server.forward-headers-strategy` under each existing `server` block; do not create a duplicate `server` key.

Add the same block to Feed but set `fetch-registry: true`.

- [ ] **Step 5: Remove Feed's static URL while retaining named-client timeouts**

Change Feed OpenFeign config from:

```yaml
user-service:
  url: ${USER_SERVICE_BASE_URL:http://localhost:8081}
  connectTimeout: 5000
  readTimeout: 5000
```

to:

```yaml
user-service:
  connectTimeout: 5000
  readTimeout: 5000
```

Do not modify `@FeignClient(name = "user-service")` or `UserServiceAdapter`.

- [ ] **Step 6: Disable Eureka in the User integration-test configuration**

Append to `user-service/src/test/resources/application.yaml`:

```yaml
eureka:
  client:
    enabled: false

spring:
  cloud:
    discovery:
      enabled: false
```

Merge `spring.cloud` into the existing `spring` block instead of duplicating it.

- [ ] **Step 7: Run module tests and verify GREEN**

Run:

```powershell
mvn -pl user-service,feed-service,search-service,notification-service test
```

Expected: all existing business tests plus the Feed LoadBalancer assertion pass without contacting Eureka.

- [ ] **Step 8: Prove no static Feed-to-User URL remains**

Run:

```powershell
$matches = rg -n "USER_SERVICE_BASE_URL|openfeign.*url|url:.*user-service" feed-service docker-compose.yml 2>&1
if ($LASTEXITCODE -eq 0) { $matches; exit 1 }
if ($LASTEXITCODE -ne 1) { $matches; exit $LASTEXITCODE }
```

Expected: exit 0 with no matches.

- [ ] **Step 9: Commit business-service discovery wiring**

```powershell
git add -- user-service/pom.xml feed-service/pom.xml search-service/pom.xml notification-service/pom.xml user-service/src/main/resources/application.yaml feed-service/src/main/resources/application.yaml search-service/src/main/resources/application.yaml notification-service/src/main/resources/application.yaml user-service/src/test/resources/application.yaml feed-service/src/test/java/com/devconnect/feed/FeedServiceApplicationTests.java
git diff --cached --check
git commit -m "feat: discover business services with eureka"
```

---

### Task 4: Build the Six-Application Docker Topology

**Files:**
- Create: `discovery-server/Dockerfile`
- Create: `api-gateway/Dockerfile`
- Modify: `user-service/Dockerfile`
- Modify: `feed-service/Dockerfile`
- Modify: `search-service/Dockerfile`
- Modify: `notification-service/Dockerfile`
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: six Maven modules and Eureka URL `http://discovery-server:8761/eureka/`.
- Produces: 11-service Compose graph, public Gateway `8080`, public Eureka `8761`, internal business ports only.

- [ ] **Step 1: Add a failing static Compose assertion before editing Compose**

Run:

```powershell
$compose = Get-Content -Raw -Encoding utf8 docker-compose.yml
if ($compose -notmatch '(?m)^  discovery-server:') { throw 'discovery-server missing' }
if ($compose -notmatch '(?m)^  api-gateway:') { throw 'api-gateway missing' }
```

Expected: FAIL with `discovery-server missing`.

- [ ] **Step 2: Update all existing Dockerfiles for the six-module reactor**

In every existing application Dockerfile, add these POM copies after the four current module POM copies:

```dockerfile
COPY discovery-server/pom.xml ./discovery-server/pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml
```

- [ ] **Step 3: Add the Discovery Server Dockerfile**

Create `discovery-server/Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1.7
FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /workspace

COPY pom.xml ./
COPY user-service/pom.xml ./user-service/pom.xml
COPY feed-service/pom.xml ./feed-service/pom.xml
COPY search-service/pom.xml ./search-service/pom.xml
COPY notification-service/pom.xml ./notification-service/pom.xml
COPY discovery-server/pom.xml ./discovery-server/pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml

RUN mvn -B -ntp -pl discovery-server -am dependency:go-offline
COPY discovery-server/src ./discovery-server/src
RUN mvn -B -ntp -pl discovery-server -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 4: Add the API Gateway Dockerfile**

Create `api-gateway/Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1.7
FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /workspace

COPY pom.xml ./
COPY user-service/pom.xml ./user-service/pom.xml
COPY feed-service/pom.xml ./feed-service/pom.xml
COPY search-service/pom.xml ./search-service/pom.xml
COPY notification-service/pom.xml ./notification-service/pom.xml
COPY discovery-server/pom.xml ./discovery-server/pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml

RUN mvn -B -ntp -pl api-gateway -am dependency:go-offline
COPY api-gateway/src ./api-gateway/src
RUN mvn -B -ntp -pl api-gateway -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 5: Add Discovery Server and Gateway Compose services**

Add `discovery-server` before business services:

```yaml
  discovery-server:
    build:
      context: .
      dockerfile: discovery-server/Dockerfile
    image: devconnect/discovery-server:local
    container_name: devconnect-discovery-server
    init: true
    ports:
      - "8761:8761"
    environment:
      EUREKA_INSTANCE_HOSTNAME: discovery-server
    healthcheck:
      test: ["CMD-SHELL", "bash -c 'exec 3<>/dev/tcp/127.0.0.1/8761'"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 20s
    restart: unless-stopped
```

Add `api-gateway` after business services:

```yaml
  api-gateway:
    build:
      context: .
      dockerfile: api-gateway/Dockerfile
    image: devconnect/api-gateway:local
    container_name: devconnect-api-gateway
    init: true
    ports:
      - "8080:8080"
    depends_on:
      discovery-server:
        condition: service_healthy
    environment:
      EUREKA_SERVER_URL: http://discovery-server:8761/eureka/
    healthcheck:
      test: ["CMD-SHELL", "bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080'"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 20s
    restart: unless-stopped
```

- [ ] **Step 6: Make business ports internal and add Eureka wiring**

Replace each business service's `ports` block with its exact internal port:

```yaml
# user-service
    expose:
      - "8081"

# feed-service
    expose:
      - "8082"

# search-service
    expose:
      - "8083"

# notification-service
    expose:
      - "8084"
```

Add `discovery-server` as a healthy dependency to every business service. Add these environment values to all four:

```yaml
      EUREKA_SERVER_URL: http://discovery-server:8761/eureka/
      EUREKA_PREFER_IP_ADDRESS: "true"
```

Remove `USER_SERVICE_BASE_URL` from Feed. Preserve database, Cassandra, and Kafka environment values.

- [ ] **Step 7: Validate the Compose model and exposure rules**

Run:

```powershell
docker compose config --services
```

Expected exact set of 11 services:

```text
postgres
cassandra
cassandra-init
kafka
kafka-ui
discovery-server
user-service
feed-service
search-service
notification-service
api-gateway
```

Then run:

```powershell
$config = docker compose config | Out-String
foreach ($port in @('3000:8081', '8082:8082', '8083:8083', '8084:8084')) {
    if ($config.Contains($port)) { throw "Direct business port still published: $port" }
}
foreach ($port in @('8080', '8761')) {
    if (-not $config.Contains($port)) { throw "Required edge/registry port missing: $port" }
}
```

Expected: exit 0.

- [ ] **Step 8: Commit Docker topology**

```powershell
git add -- docker-compose.yml user-service/Dockerfile feed-service/Dockerfile search-service/Dockerfile notification-service/Dockerfile discovery-server/Dockerfile api-gateway/Dockerfile
git diff --cached --check
git commit -m "feat: run gateway and eureka in compose"
```

---

### Task 5: Synchronize All Current Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/API.md`
- Modify: `docs/DEVELOPMENT.md`
- Modify: `docs/DOCKER.md`
- Modify: `docs/DATABASE.md`
- Modify: `ASYNC-JAVA.md`
- Create: `docs/GATEWAY-DISCOVERY.md`

**Interfaces:**
- Consumes: verified ports, routes, config keys, module/test counts, and Docker behavior from Tasks 1-4.
- Produces: one consistent operator/developer story where public APIs use port 8080 and internal Feign bypasses Gateway.

- [ ] **Step 1: Add the focused Gateway/Discovery guide**

Create `docs/GATEWAY-DISCOVERY.md` with these exact sections and facts:

```markdown
# API Gateway và Eureka Service Discovery

## 1. Vai trò và ranh giới
## 2. Topology và service IDs
## 3. Explicit Gateway routes
## 4. Eureka registration/fetch matrix
## 5. Feed OpenFeign discovery flow
## 6. Cấu hình environment và timeout
## 7. Chạy bằng Docker Compose
## 8. Chạy trực tiếp trên host
## 9. Kiểm tra registry và Gateway
## 10. Troubleshooting 404/503/registration
## 11. Production hardening
```

The guide must document:

- Gateway `http://localhost:8080` and Eureka `http://localhost:8761`;
- four explicit routes and no `/internal/**` route;
- `EUREKA_SERVER_URL`, `EUREKA_PREFER_IP_ADDRESS`, `API_GATEWAY_PORT`, `DISCOVERY_SERVER_PORT`, `GATEWAY_CONNECT_TIMEOUT_MS`, `GATEWAY_RESPONSE_TIMEOUT`;
- Feed → User OpenFeign uses Eureka directly;
- standalone local Eureka versus three-peer production topology;
- 404 for non-allowlisted paths and 503 when no service instance is available;
- no write retry, auth, TLS, rate limiting, or circuit breaker in this baseline.

- [ ] **Step 2: Update the root overview and documentation index**

Update `README.md` diagrams, service table, quick-start URLs, smoke commands, repository tree, Compose count from 9 to 11, limitations, and technology list. All public business examples must use `http://localhost:8080`.

Update `docs/README.md` to link `GATEWAY-DISCOVERY.md` under development and operations.

- [ ] **Step 3: Update architecture and API contracts**

Update `docs/ARCHITECTURE.md` with:

- Client → Gateway → Eureka-resolved service flow;
- Feed → Eureka/LoadBalancer → User internal flow;
- explicit statement that `UserServiceAdapter` is not Gateway;
- registration and failure semantics;
- hidden business ports and remaining production gaps.

Update `docs/API.md` so every client example uses Gateway `8080`; retain the User internal endpoint as a service-to-service contract and explicitly state it is not routable through Gateway.

- [ ] **Step 4: Update development, Docker, database, and async guides**

Update `docs/DEVELOPMENT.md` with six-module Maven commands, startup order, all Eureka/Gateway variables, test count from fresh verification, host-run order, and troubleshooting.

Update `docs/DOCKER.md` with the 11-service topology, new Dockerfiles, public/internal port table, health dependencies, build commands, logs, and smoke tests.

Update `docs/DATABASE.md` only where it describes service access boundaries: clients use Gateway, while Feed still calls User directly through discovery and never reads PostgreSQL.

Update `ASYNC-JAVA.md` to include Gateway/discovery hops without changing the explanation that OpenFeign and `.join()` are blocking.

- [ ] **Step 5: Scan for stale topology claims**

Run:

```powershell
$currentDocs = @('README.md', 'ASYNC-JAVA.md') + @(
    Get-ChildItem docs -File -Filter '*.md' | ForEach-Object FullName
)
$matches = rg -n "9 Compose service|chưa có.*gateway|USER_SERVICE_BASE_URL|localhost:3000|localhost:8082|localhost:8083|localhost:8084|RestClient|AsyncPostService" $currentDocs 2>&1
if ($LASTEXITCODE -eq 0) { $matches; exit 1 }
if ($LASTEXITCODE -ne 1) { $matches; exit $LASTEXITCODE }
```

Expected: no stale current-document claims. Historical files under `docs/superpowers/` are excluded.

- [ ] **Step 6: Verify local Markdown links**

Run this PowerShell Markdown-link scan against `README.md`, `ASYNC-JAVA.md`, and top-level `docs/*.md`:

```powershell
$files = @('README.md', 'ASYNC-JAVA.md') + @(
    Get-ChildItem docs -File -Filter '*.md' | ForEach-Object FullName
)
$broken = @()
foreach ($file in $files) {
    $content = Get-Content -Raw -Encoding utf8 $file
    foreach ($match in [regex]::Matches($content, '\[[^\]]+\]\(([^)]+)\)')) {
        $target = $match.Groups[1].Value
        if ($target -match '^(https?://|mailto:|#)') { continue }
        $pathOnly = [uri]::UnescapeDataString(($target -split '#')[0])
        if ([string]::IsNullOrWhiteSpace($pathOnly)) { continue }
        $base = Split-Path -Parent $file
        if ([string]::IsNullOrWhiteSpace($base)) { $base = '.' }
        $resolved = Join-Path $base $pathOnly
        if (-not (Test-Path -LiteralPath $resolved)) {
            $broken += "$file -> $target"
        }
    }
}
if ($broken.Count -gt 0) { $broken; exit 1 }
```

Expected: every relative local link resolves.

- [ ] **Step 7: Review and commit exact documentation paths**

Inspect the full diff first because these files already contain preserved documentation changes:

```powershell
git diff -- README.md ASYNC-JAVA.md docs/README.md docs/ARCHITECTURE.md docs/API.md docs/DEVELOPMENT.md docs/DOCKER.md docs/DATABASE.md docs/EVENTS.md docs/GATEWAY-DISCOVERY.md
```

Then stage only current documentation:

```powershell
git add -- README.md ASYNC-JAVA.md docs/README.md docs/ARCHITECTURE.md docs/API.md docs/DEVELOPMENT.md docs/DOCKER.md docs/DATABASE.md docs/EVENTS.md docs/GATEWAY-DISCOVERY.md
git diff --cached --check
git commit -m "docs: explain gateway and eureka application flow"
```

---

### Task 6: Run Repository-Level Automated Verification

**Files:**
- Verify only; modify only if a failed check identifies an in-scope defect.

**Interfaces:**
- Consumes: all implementation and documentation from Tasks 1-5.
- Produces: clean evidence for dependency resolution, tests, configuration, routes, and docs.

- [ ] **Step 1: Run a fresh six-module clean test**

```powershell
$statusBeforeVerification = git status --short
mvn clean test
```

Expected: reactor `BUILD SUCCESS`; all six modules pass with zero failures/errors.

- [ ] **Step 2: Verify resolved Spring Cloud components**

```powershell
mvn -pl discovery-server dependency:tree '-Dincludes=org.springframework.cloud:spring-cloud-starter-netflix-eureka-server'
mvn -pl api-gateway dependency:tree '-Dincludes=org.springframework.cloud:spring-cloud-starter-gateway-server-webflux,org.springframework.cloud:spring-cloud-starter-netflix-eureka-client,org.springframework.cloud:spring-cloud-starter-loadbalancer'
mvn -pl feed-service dependency:tree '-Dincludes=org.springframework.cloud:spring-cloud-starter-openfeign,org.springframework.cloud:spring-cloud-starter-netflix-eureka-client,org.springframework.cloud:spring-cloud-starter-loadbalancer'
```

Expected: each requested starter resolves in compile scope through Spring Cloud `5.0.2`.

- [ ] **Step 3: Verify route and discovery configuration structurally**

```powershell
$gateway = Get-Content -Raw -Encoding utf8 api-gateway/src/main/resources/application.yaml
foreach ($route in @('lb://user-service', 'lb://feed-service', 'lb://search-service', 'lb://notification-service')) {
    if (-not $gateway.Contains($route)) { throw "Missing route: $route" }
}
if ($gateway -match 'Path=/internal') { throw 'Internal endpoint exposed by Gateway' }

$feed = Get-Content -Raw -Encoding utf8 feed-service/src/main/resources/application.yaml
if ($feed.Contains('USER_SERVICE_BASE_URL') -or $feed -match '(?m)^\s+url:') {
    throw 'Feed still uses a static User Service URL'
}
```

Expected: exit 0.

- [ ] **Step 4: Validate Compose and documentation**

```powershell
docker compose config
git diff --check
git status --short
```

Expected: Compose config exits 0; no whitespace errors; only intentional paths remain changed/untracked.

If Maven creates or changes tracked/untracked `target/**` artifacts, compare them with
`$statusBeforeVerification`. Remove only generated untracked target directories after resolving and
confirming they are inside this workspace, and restore only exact generated target paths that were
clean before verification. Never restore source or documentation paths.

---

### Task 7: Build and Smoke-Test the Full Gateway/Discovery Flow

**Files:**
- Verify running system only; do not mutate source unless a reproducible in-scope defect is found.

**Interfaces:**
- Consumes: Docker images, Compose graph, registry, explicit routes, Feed OpenFeign discovery, Kafka consumers.
- Produces: runtime evidence for the final topology and flow report.

- [ ] **Step 1: Build and start the full stack**

```powershell
docker compose up -d --build
docker compose ps -a
```

Expected: infrastructure, six applications, and Kafka UI start; `cassandra-init` exits 0.

- [ ] **Step 2: Wait with a bounded health poll**

```powershell
$deadline = (Get-Date).AddMinutes(5)
$healthy = $false
do {
    try {
        $eurekaHealth = Invoke-RestMethod http://localhost:8761/actuator/health
        $gatewayHealth = Invoke-RestMethod http://localhost:8080/actuator/health
        $healthy = $eurekaHealth.status -eq 'UP' -and $gatewayHealth.status -eq 'UP'
    } catch {
        $healthy = $false
    }
    if (-not $healthy) { Start-Sleep -Seconds 5 }
} while (-not $healthy -and (Get-Date) -lt $deadline)

if (-not $healthy) {
    docker compose ps -a
    docker compose logs --no-color --tail 200 discovery-server api-gateway
    throw 'Gateway/Eureka did not become healthy within 5 minutes'
}
```

Expected: both return status `UP`.

- [ ] **Step 3: Verify Eureka registrations**

```powershell
$registry = Invoke-RestMethod `
  -Uri http://localhost:8761/eureka/apps `
  -Headers @{ Accept = 'application/json' }

$names = @($registry.applications.application | ForEach-Object name)
foreach ($name in @('USER-SERVICE', 'FEED-SERVICE', 'SEARCH-SERVICE', 'NOTIFICATION-SERVICE')) {
    if ($names -notcontains $name) { throw "Missing Eureka registration: $name" }
}
```

Expected: all four business service IDs are registered and `UP`; Gateway is not registered.

- [ ] **Step 4: Verify the public allowlist boundary**

```powershell
try {
    Invoke-WebRequest -Uri http://localhost:8080/internal/users/u001/status -ErrorAction Stop
    throw 'Internal endpoint unexpectedly routed'
} catch {
    if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
}
```

Expected: Gateway returns 404.

- [ ] **Step 5: Verify public User and Feed flow through Gateway**

```powershell
$userId = "gateway-smoke-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$userBody = @{ userId = $userId; status = 'ACTIVE' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/users -ContentType 'application/json' -Body $userBody

$postBody = @{ authorId = $userId; content = 'Gateway Eureka smoke test' } | ConvertTo-Json
$post = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/feed/posts -ContentType 'application/json' -Body $postBody

if ($post.authorId -ne $userId) { throw 'Gateway create-post response mismatch' }
```

Expected: user returns 201 through Gateway; post returns 201 raw `PostResponse`; Feed successfully discovers User without a static URL.

- [ ] **Step 6: Verify eventual consumers through Gateway**

```powershell
$deadline = (Get-Date).AddSeconds(60)
$consumersUpdated = $false
do {
    try {
        $search = Invoke-RestMethod 'http://localhost:8080/api/search/posts?keyword=Gateway'
        $notifications = Invoke-RestMethod "http://localhost:8080/api/notifications/users/$userId"
        $searchJson = $search | ConvertTo-Json -Depth 10 -Compress
        $notificationJson = $notifications | ConvertTo-Json -Depth 10 -Compress
        $consumersUpdated = $searchJson.Contains($post.postId) -and $notificationJson.Contains($userId)
    } catch {
        $consumersUpdated = $false
    }
    if (-not $consumersUpdated) { Start-Sleep -Seconds 3 }
} while (-not $consumersUpdated -and (Get-Date) -lt $deadline)

if (-not $consumersUpdated) { throw 'Search/Notification projections were not updated within 60 seconds' }
```

Expected: search contains the created post and notification contains the created user's event-derived notification.

- [ ] **Step 7: Capture diagnostics and stop without deleting data**

```powershell
docker compose ps -a
docker compose logs --no-color --tail 200 discovery-server api-gateway user-service feed-service search-service notification-service
docker compose down
```

Expected: no registration/routing exceptions in the successful path; named database volumes remain intact.

- [ ] **Step 8: Final repository status and flow handoff**

```powershell
git status --short
git log -8 --oneline
```

Final report must include:

- created modules and exact public/internal ports;
- route allowlist;
- Eureka registration/fetch matrix;
- Feed OpenFeign discovery path;
- clean test and Compose evidence;
- smoke-test evidence;
- a readable external create-post sequence and registration/read flows;
- remaining production gaps (HA Eureka, TLS/auth, rate limiting, circuit breaker, observability, idempotency).
