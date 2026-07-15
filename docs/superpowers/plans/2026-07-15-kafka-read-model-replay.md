# Kafka Read-Model Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the in-memory Search and Notification read models from retained `post-events` records whenever either consumer process starts.

**Architecture:** Each existing `PostCreatedEventListener` will implement Spring Kafka `ConsumerSeekAware`. The first non-empty partition assignment in a process seeks assigned partitions to the beginning; a process-local atomic guard prevents later rebalances from replaying again. Existing upsert/dedup behavior rebuilds the maps without changing REST or event contracts.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Kafka 4.1.0, JUnit 5, Mockito, AssertJ, Maven, Docker Compose

## Global Constraints

- Keep Kafka topic `post-events` and consumer groups `search-service-group` and `notification-service-group` unchanged.
- Do not add Elasticsearch, another database, Cassandra access, REST endpoints, event fields, or Docker services.
- Replay only events still retained by Kafka.
- Replay only the first non-empty assignment in each process; normal later rebalances must not seek again.
- Keep Search upsert semantics keyed by `postId`.
- Keep Notification deduplication keyed by `eventId` within a process.
- Keep Notification `notificationId` and `createdAt` transient and regenerated during each rebuild.
- Preserve the documented single-instance-per-consumer-service constraint.
- Follow TDD: verify replay tests fail before changing production listeners.
- Do not delete the existing Docker volumes or Kafka topic during runtime verification.

---

## File structure

- Modify `search-service/src/main/java/com/devconnect/search/event/PostCreatedEventListener.java`: own Search Kafka replay-on-initial-assignment behavior.
- Create `search-service/src/test/java/com/devconnect/search/event/PostCreatedEventListenerTests.java`: verify assignment replay and event routing.
- Create `search-service/src/test/java/com/devconnect/search/service/PostSearchServiceTests.java`: protect upsert and case-insensitive search behavior used by replay.
- Modify `notification-service/src/main/java/com/devconnect/notification/event/PostCreatedEventListener.java`: own Notification Kafka replay-on-initial-assignment behavior.
- Create `notification-service/src/test/java/com/devconnect/notification/event/PostCreatedEventListenerTests.java`: verify assignment replay and event routing.
- Create `notification-service/src/test/java/com/devconnect/notification/service/NotificationServiceTests.java`: protect event deduplication and per-user filtering used by replay.
- Modify `README.md`, `docs/ARCHITECTURE.md`, `docs/DATABASE.md`, `docs/EVENTS.md`, and `docs/DEVELOPMENT.md`: document lifecycle, tests, limitations, and verification.

### Task 1: Rebuild the Search index on initial Kafka assignment

**Files:**
- Modify: `search-service/src/main/java/com/devconnect/search/event/PostCreatedEventListener.java`
- Create: `search-service/src/test/java/com/devconnect/search/event/PostCreatedEventListenerTests.java`
- Create: `search-service/src/test/java/com/devconnect/search/service/PostSearchServiceTests.java`

**Interfaces:**
- Consumes: Spring Kafka `ConsumerSeekAware.onPartitionsAssigned(Map<TopicPartition, Long>, ConsumerSeekCallback)`.
- Produces: `PostCreatedEventListener` seeks initial assigned partitions to the beginning exactly once per process.
- Preserves: `PostSearchService.indexPost(String postId, String authorId, String content)` and `search(String keyword)`.

- [ ] **Step 1: Write failing listener and index tests**

Create `search-service/src/test/java/com/devconnect/search/event/PostCreatedEventListenerTests.java`:

```java
package com.devconnect.search.event;

import com.devconnect.search.service.PostSearchService;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerSeekAware.ConsumerSeekCallback;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class PostCreatedEventListenerTests {

    private PostSearchService postSearchService;
    private PostCreatedEventListener listener;

    @BeforeEach
    void setUp() {
        postSearchService = mock(PostSearchService.class);
        listener = new PostCreatedEventListener(postSearchService);
    }

    @Test
    void seeksAllPartitionsToBeginningOnFirstAssignment() {
        ConsumerSeekCallback callback = mock(ConsumerSeekCallback.class);
        TopicPartition first = new TopicPartition("post-events", 0);
        TopicPartition second = new TopicPartition("post-events", 1);

        listener.onPartitionsAssigned(Map.of(first, 2L, second, 4L), callback);

        verify(callback).seekToBeginning(argThat(partitions ->
                partitions.size() == 2 && partitions.containsAll(List.of(first, second))));
    }

    @Test
    void doesNotSeekAgainOnLaterAssignment() {
        ConsumerSeekCallback firstCallback = mock(ConsumerSeekCallback.class);
        ConsumerSeekCallback secondCallback = mock(ConsumerSeekCallback.class);
        TopicPartition partition = new TopicPartition("post-events", 0);

        listener.onPartitionsAssigned(Map.of(partition, 2L), firstCallback);
        listener.onPartitionsAssigned(Map.of(partition, 3L), secondCallback);

        verifyNoInteractions(secondCallback);
    }

    @Test
    void emptyAssignmentDoesNotCloseInitialReplay() {
        ConsumerSeekCallback callback = mock(ConsumerSeekCallback.class);
        TopicPartition partition = new TopicPartition("post-events", 0);

        listener.onPartitionsAssigned(Map.of(), callback);
        listener.onPartitionsAssigned(Map.of(partition, 2L), callback);

        verify(callback).seekToBeginning(List.of(partition));
    }

    @Test
    void indexesPostCreatedEvent() {
        PostCreatedEvent event = new PostCreatedEvent(
                "event-1", "POST_CREATED", "post-1", "u001", "Java role",
                LocalDateTime.parse("2026-07-15T06:52:56.283")
        );

        listener.handlePostCreated(event);

        verify(postSearchService).indexPost("post-1", "u001", "Java role");
    }

    @Test
    void ignoresOtherEventTypes() {
        PostCreatedEvent event = new PostCreatedEvent(
                "event-1", "POST_UPDATED", "post-1", "u001", "Java role",
                LocalDateTime.parse("2026-07-15T06:52:56.283")
        );

        listener.handlePostCreated(event);

        verifyNoInteractions(postSearchService);
    }
}
```

Create `search-service/src/test/java/com/devconnect/search/service/PostSearchServiceTests.java`:

```java
package com.devconnect.search.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostSearchServiceTests {

    private final PostSearchService service = new PostSearchService();

    @Test
    void searchIsCaseInsensitive() {
        service.indexPost("post-1", "u001", "Java Developer");

        assertThat(service.search("java"))
                .singleElement()
                .satisfies(post -> assertThat(post.postId()).isEqualTo("post-1"));
    }

    @Test
    void replayUpsertsTheSamePostId() {
        service.indexPost("post-1", "u001", "Old content");
        service.indexPost("post-1", "u001", "New content");

        assertThat(service.search(""))
                .singleElement()
                .satisfies(post -> assertThat(post.content()).isEqualTo("New content"));
    }
}
```

- [ ] **Step 2: Run Search tests and verify RED**

Run:

```powershell
mvn -pl search-service test
```

Expected: test compilation fails because `PostCreatedEventListener` has no `onPartitionsAssigned(...)` method and does not implement `ConsumerSeekAware`.

- [ ] **Step 3: Implement minimal Search replay behavior**

Replace `search-service/src/main/java/com/devconnect/search/event/PostCreatedEventListener.java` with:

```java
package com.devconnect.search.event;

import com.devconnect.search.service.PostSearchService;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PostCreatedEventListener implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(PostCreatedEventListener.class);

    private final PostSearchService postSearchService;
    private final AtomicBoolean initialReplayPending = new AtomicBoolean(true);

    public PostCreatedEventListener(PostSearchService postSearchService) {
        this.postSearchService = postSearchService;
    }

    @Override
    public void onPartitionsAssigned(
            Map<TopicPartition, Long> assignments,
            ConsumerSeekCallback callback
    ) {
        if (assignments.isEmpty() || !initialReplayPending.compareAndSet(true, false)) {
            return;
        }

        List<TopicPartition> partitions = List.copyOf(assignments.keySet());
        log.info("Rebuilding search index from the beginning of partitions {}", partitions);
        callback.seekToBeginning(partitions);
    }

    @KafkaListener(topics = "${app.kafka.topics.post-events}", groupId = "search-service-group")
    public void handlePostCreated(PostCreatedEvent event) {
        if ("POST_CREATED".equals(event.eventType())) {
            postSearchService.indexPost(event.postId(), event.authorId(), event.content());
        }
    }
}
```

- [ ] **Step 4: Run Search tests and verify GREEN**

Run:

```powershell
mvn -pl search-service test
```

Expected: `Tests run: 7, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit Search replay**

```powershell
git add search-service/src/main/java/com/devconnect/search/event/PostCreatedEventListener.java search-service/src/test/java/com/devconnect/search/event/PostCreatedEventListenerTests.java search-service/src/test/java/com/devconnect/search/service/PostSearchServiceTests.java
git commit -m "fix: rebuild search index from Kafka history"
```

### Task 2: Rebuild notifications on initial Kafka assignment

**Files:**
- Modify: `notification-service/src/main/java/com/devconnect/notification/event/PostCreatedEventListener.java`
- Create: `notification-service/src/test/java/com/devconnect/notification/event/PostCreatedEventListenerTests.java`
- Create: `notification-service/src/test/java/com/devconnect/notification/service/NotificationServiceTests.java`

**Interfaces:**
- Consumes: Spring Kafka `ConsumerSeekAware.onPartitionsAssigned(Map<TopicPartition, Long>, ConsumerSeekCallback)`.
- Produces: `PostCreatedEventListener` seeks initial assigned partitions to the beginning exactly once per process.
- Preserves: `NotificationService.createPostCreatedNotification(String eventId, String authorId, String postId)` and `getNotificationsByUser(String userId)`.

- [ ] **Step 1: Write failing listener and notification tests**

Create `notification-service/src/test/java/com/devconnect/notification/event/PostCreatedEventListenerTests.java`:

```java
package com.devconnect.notification.event;

import com.devconnect.notification.service.NotificationService;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerSeekAware.ConsumerSeekCallback;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class PostCreatedEventListenerTests {

    private NotificationService notificationService;
    private PostCreatedEventListener listener;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        listener = new PostCreatedEventListener(notificationService);
    }

    @Test
    void seeksAllPartitionsToBeginningOnFirstAssignment() {
        ConsumerSeekCallback callback = mock(ConsumerSeekCallback.class);
        TopicPartition first = new TopicPartition("post-events", 0);
        TopicPartition second = new TopicPartition("post-events", 1);

        listener.onPartitionsAssigned(Map.of(first, 2L, second, 4L), callback);

        verify(callback).seekToBeginning(argThat(partitions ->
                partitions.size() == 2 && partitions.containsAll(List.of(first, second))));
    }

    @Test
    void doesNotSeekAgainOnLaterAssignment() {
        ConsumerSeekCallback firstCallback = mock(ConsumerSeekCallback.class);
        ConsumerSeekCallback secondCallback = mock(ConsumerSeekCallback.class);
        TopicPartition partition = new TopicPartition("post-events", 0);

        listener.onPartitionsAssigned(Map.of(partition, 2L), firstCallback);
        listener.onPartitionsAssigned(Map.of(partition, 3L), secondCallback);

        verifyNoInteractions(secondCallback);
    }

    @Test
    void emptyAssignmentDoesNotCloseInitialReplay() {
        ConsumerSeekCallback callback = mock(ConsumerSeekCallback.class);
        TopicPartition partition = new TopicPartition("post-events", 0);

        listener.onPartitionsAssigned(Map.of(), callback);
        listener.onPartitionsAssigned(Map.of(partition, 2L), callback);

        verify(callback).seekToBeginning(List.of(partition));
    }

    @Test
    void createsNotificationForPostCreatedEvent() {
        PostCreatedEvent event = new PostCreatedEvent(
                "event-1", "POST_CREATED", "post-1", "u001", "Java role",
                LocalDateTime.parse("2026-07-15T06:52:56.283")
        );

        listener.handlePostCreated(event);

        verify(notificationService).createPostCreatedNotification("event-1", "u001", "post-1");
    }

    @Test
    void ignoresOtherEventTypes() {
        PostCreatedEvent event = new PostCreatedEvent(
                "event-1", "POST_UPDATED", "post-1", "u001", "Java role",
                LocalDateTime.parse("2026-07-15T06:52:56.283")
        );

        listener.handlePostCreated(event);

        verifyNoInteractions(notificationService);
    }
}
```

Create `notification-service/src/test/java/com/devconnect/notification/service/NotificationServiceTests.java`:

```java
package com.devconnect.notification.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceTests {

    private final NotificationService service = new NotificationService();

    @Test
    void duplicateEventIdCreatesOneNotification() {
        service.createPostCreatedNotification("event-1", "u001", "post-1");
        service.createPostCreatedNotification("event-1", "u001", "post-1");

        assertThat(service.getNotificationsByUser("u001")).hasSize(1);
    }

    @Test
    void notificationsAreFilteredByUser() {
        service.createPostCreatedNotification("event-1", "u001", "post-1");
        service.createPostCreatedNotification("event-2", "u002", "post-2");

        assertThat(service.getNotificationsByUser("u001"))
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.userId()).isEqualTo("u001");
                    assertThat(notification.message()).contains("post-1");
                });
    }
}
```

- [ ] **Step 2: Run Notification tests and verify RED**

Run:

```powershell
mvn -pl notification-service test
```

Expected: test compilation fails because `PostCreatedEventListener` has no `onPartitionsAssigned(...)` method and does not implement `ConsumerSeekAware`.

- [ ] **Step 3: Implement minimal Notification replay behavior**

Replace `notification-service/src/main/java/com/devconnect/notification/event/PostCreatedEventListener.java` with:

```java
package com.devconnect.notification.event;

import com.devconnect.notification.service.NotificationService;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PostCreatedEventListener implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(PostCreatedEventListener.class);

    private final NotificationService notificationService;
    private final AtomicBoolean initialReplayPending = new AtomicBoolean(true);

    public PostCreatedEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void onPartitionsAssigned(
            Map<TopicPartition, Long> assignments,
            ConsumerSeekCallback callback
    ) {
        if (assignments.isEmpty() || !initialReplayPending.compareAndSet(true, false)) {
            return;
        }

        List<TopicPartition> partitions = List.copyOf(assignments.keySet());
        log.info("Rebuilding notification read model from the beginning of partitions {}", partitions);
        callback.seekToBeginning(partitions);
    }

    @KafkaListener(topics = "${app.kafka.topics.post-events}", groupId = "notification-service-group")
    public void handlePostCreated(PostCreatedEvent event) {
        if ("POST_CREATED".equals(event.eventType())) {
            notificationService.createPostCreatedNotification(event.eventId(), event.authorId(), event.postId());
        }
    }
}
```

- [ ] **Step 4: Run Notification tests and verify GREEN**

Run:

```powershell
mvn -pl notification-service test
```

Expected: `Tests run: 7, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit Notification replay**

```powershell
git add notification-service/src/main/java/com/devconnect/notification/event/PostCreatedEventListener.java notification-service/src/test/java/com/devconnect/notification/event/PostCreatedEventListenerTests.java notification-service/src/test/java/com/devconnect/notification/service/NotificationServiceTests.java
git commit -m "fix: rebuild notifications from Kafka history"
```

### Task 3: Update read-model lifecycle documentation

**Files:**
- Modify: `README.md:217,252-258`
- Modify: `docs/ARCHITECTURE.md:41-51,134-144,175-182,201-209`
- Modify: `docs/DATABASE.md:9-16,197-217`
- Modify: `docs/EVENTS.md:75-84`
- Modify: `docs/DEVELOPMENT.md:212-235,316-330`

**Interfaces:**
- Consumes: the replay behavior delivered by Tasks 1 and 2.
- Produces: documentation that distinguishes transient storage from automatic reconstruction using retained Kafka events.

- [ ] **Step 1: Update README test coverage and limitations**

Replace the sentence at the current `README.md:217` with:

```markdown
Các test hiện có bao phủ Flyway migration/seed và create/update user trên H2, User API validation/error contract, Feed-to-User status contract, Cassandra mapping/logged batch, async executor, async MVC response, mapping `BusinessException`, Kafka initial replay của Search/Notification, search upsert và notification deduplication.
```

Replace the Search/Notification limitation bullet with:

```markdown
- Search index, notification và notification deduplication vẫn nằm trong `ConcurrentHashMap`; mỗi process rebuild từ các event còn được Kafka giữ lại, nên API có thể tạm thời trả dữ liệu thiếu trong lúc replay và không thể phục hồi event đã hết retention.
```

- [ ] **Step 2: Update architecture and ownership semantics**

Add these sentences to the service descriptions in `docs/ARCHITECTURE.md`:

```markdown
Khi process khởi động, consumer seek các partition được gán về đầu đúng một lần để rebuild index từ event còn trong Kafka; các rebalance sau đó không tự replay lại.

Khi process khởi động, consumer cũng replay các event còn trong Kafka đúng một lần để dựng lại notification và `processedEventIds`. `notificationId` và `createdAt` được sinh lại trong mỗi lần rebuild.
```

Change the three in-memory rows in the ownership table to state that data is lost locally but rebuilt from retained Kafka events. Add this paragraph under consumer delivery:

```markdown
| Search index | `search-service` | `ConcurrentHashMap<postId, post>` | Có; rebuild từ event Kafka còn retention |
| Notification | `notification-service` | `ConcurrentHashMap<notificationId, notification>` | Có; rebuild từ event Kafka còn retention |
| Processed event IDs | `notification-service` | `ConcurrentHashMap<eventId, boolean>` | Có; rebuild từ event Kafka còn retention |
```

```markdown
Search và Notification chủ động seek về đầu các partition ở lần assignment đầu tiên của mỗi process. Replay khôi phục read model sau restart dù consumer group đã có committed offset. Trong lúc catch-up, API có thể trả kết quả rỗng hoặc chưa đầy đủ; event đã hết Kafka retention không thể được khôi phục. Thiết kế vẫn chỉ đúng khi mỗi consumer service chạy một instance vì mỗi instance chỉ giữ map cục bộ.
```

- [ ] **Step 3: Update database, event, and development lifecycle docs**

Use this persistence wording in `docs/DATABASE.md`:

```markdown
| Search index | Search Service | `ConcurrentHashMap` | Map mất khi restart; rebuild từ event Kafka còn retention. |
| Notification/dedup | Notification Service | `ConcurrentHashMap` | Map mất khi restart; rebuild từ event Kafka còn retention. |

Search/Notification không dùng volume. Mỗi process mới seek Kafka về đầu đúng một lần và dựng lại map từ các event còn retention; dữ liệu vẫn không phải persistent storage của service.
```

Replace the manual replay paragraph in `docs/EVENTS.md` with:

```markdown
Search và Notification implement partition-assignment callback để seek các partition được gán về đầu đúng một lần trong mỗi process. Vì vậy committed offset không ngăn việc rebuild read model sau restart. Search upsert theo `postId`; Notification dựng lại `processedEventIds` theo `eventId`. Các rebalance tiếp theo trong cùng process không replay lại. Cơ chế này chỉ phục hồi event còn trong Kafka retention và chỉ phù hợp mô hình một instance cho mỗi consumer service.
```

Update `docs/DEVELOPMENT.md` so the test table includes the four new test classes, the documented reactor total is `46`, and troubleshooting says to inspect the replay log messages:

```markdown
| `PostCreatedEventListenerTests` | Search | Initial partition replay đúng một lần và routing `POST_CREATED`. |
| `PostSearchServiceTests` | Search | Case-insensitive search và upsert theo `postId`. |
| `PostCreatedEventListenerTests` | Notification | Initial partition replay đúng một lần và routing `POST_CREATED`. |
| `NotificationServiceTests` | Notification | Deduplicate theo `eventId` và filter theo user. |

Tại thời điểm tài liệu này được cập nhật, reactor có 46 test và tất cả đều pass bằng `mvn test`.
```

Add these replay log messages to the troubleshooting checklist:

```text
Rebuilding search index from the beginning of partitions [...]
Rebuilding notification read model from the beginning of partitions [...]
```

Replace the old claim that restart permanently loses Search/Notification results with:

```markdown
Search/Notification mất map cục bộ khi restart nhưng tự replay event còn retention ở lần Kafka assignment đầu tiên. Gọi lại API sau khi consumer catch up; nếu event đã hết retention thì dữ liệu không thể được dựng lại bằng cơ chế demo này.
```

- [ ] **Step 4: Verify documentation consistency**

Run:

```powershell
rg -n "không tự tái tạo|group mới/reset offset|restart Search/Notification làm mất|chưa có test tự động" README.md docs
git diff --check
```

Expected: the stale phrases have no matches and `git diff --check` exits successfully.

- [ ] **Step 5: Commit documentation**

```powershell
git add README.md docs/ARCHITECTURE.md docs/DATABASE.md docs/EVENTS.md docs/DEVELOPMENT.md
git commit -m "docs: document Kafka read model replay"
```

### Task 4: Full verification and Docker acceptance

**Files:**
- Verify only; no source files should change.

**Interfaces:**
- Consumes: Search replay from Task 1, Notification replay from Task 2, and runbook text from Task 3.
- Produces: test and runtime evidence that old and new Kafka events populate both read models.

- [ ] **Step 1: Run the complete Maven reactor**

Run:

```powershell
mvn test
```

Expected: all four modules succeed with `Tests run: 46, Failures: 0, Errors: 0` in aggregate and final `BUILD SUCCESS`.

- [ ] **Step 2: Confirm the working tree contains only expected state**

Run:

```powershell
git status --short
git log -4 --oneline
```

Expected: no uncommitted implementation/documentation files; recent history includes the Search, Notification, documentation, and plan/spec commits.

- [ ] **Step 3: Rebuild and recreate only the two consumer containers**

From Ubuntu WSL at `/mnt/d/devconnect-microservice-demo`:

```bash
docker compose build --no-cache search-service notification-service
docker compose up -d --force-recreate search-service notification-service
docker compose ps search-service notification-service kafka
```

Expected: Kafka and both consumer services are `Up`; application services become healthy.

- [ ] **Step 4: Verify replay logs**

Run:

```bash
docker compose logs --tail=200 search-service notification-service
```

Expected output contains both:

```text
Rebuilding search index from the beginning of partitions [post-events-0]
Rebuilding notification read model from the beginning of partitions [post-events-0]
```

- [ ] **Step 5: Verify retained Search events**

Run:

```bash
curl -sS 'http://localhost:8083/api/search/posts?keyword=Cassandra'
curl -sS 'http://localhost:8083/api/search/posts?keyword=Java'
```

Expected: `Cassandra` returns post IDs `9f84556a-fea2-4ef4-b309-c493b351c4d0` and `fcac8d0b-9741-41cf-b70d-11a34d0926a2`; `Java` returns `c0c23a45-0f86-4b08-806e-6b5303628289`.

- [ ] **Step 6: Verify retained Notification events**

Run:

```bash
curl -sS 'http://localhost:8084/api/notifications/users/u001'
curl -sS 'http://localhost:8084/api/notifications/users/u002'
```

Expected: `u001` has a message containing post ID `9f84556a-fea2-4ef4-b309-c493b351c4d0`; `u002` has a message containing `fcac8d0b-9741-41cf-b70d-11a34d0926a2`.

- [ ] **Step 7: Verify live consumption still works after replay**

Create a new post, retain its ID, and poll both read models for at most 30 seconds:

```bash
RUN_ID="$(date +%s)"
CONTENT="Replay-${RUN_ID}"
CREATE_RESPONSE="$(curl -sS -X POST 'http://localhost:8082/api/feed/posts' -H 'Content-Type: application/json' --data-raw "{\"authorId\":\"u001\",\"content\":\"${CONTENT}\"}")"
POST_ID="$(printf '%s' "$CREATE_RESPONSE" | sed -n 's/.*"postId":"\([^"]*\)".*/\1/p')"
FOUND=false
for attempt in $(seq 1 30); do
  SEARCH_RESPONSE="$(curl -sS "http://localhost:8083/api/search/posts?keyword=${CONTENT}")"
  NOTIFICATION_RESPONSE="$(curl -sS 'http://localhost:8084/api/notifications/users/u001')"
  if printf '%s' "$SEARCH_RESPONSE" | grep -q "$POST_ID" && printf '%s' "$NOTIFICATION_RESPONSE" | grep -q "$POST_ID"; then
    FOUND=true
    break
  fi
  sleep 1
done
printf '%s\n' "$CREATE_RESPONSE" "$SEARCH_RESPONSE" "$NOTIFICATION_RESPONSE"
test -n "$POST_ID"
test "$FOUND" = true
```

Expected: both final `test` commands exit successfully and the same new `postId` appears in Search and Notification responses.

- [ ] **Step 8: Final diff and history check**

Run:

```powershell
git status --short --branch
git log -5 --oneline --decorate
```

Expected: branch is clean and contains all planned commits; no database volume or Kafka topic was deleted.
