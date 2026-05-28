package com.opsautoagent.domain.codeops.agent.test;

import com.opsautoagent.domain.codeops.agent.patch.FileRewritePatchEntity;
import com.opsautoagent.domain.codeops.agent.testpatch.CodeOpsTestPatchAgentOutput;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class IncidentRegressionScaffoldService {

    public CodeOpsTestPatchAgentOutput generateIfSupported(String repositoryPath,
                                                           EngineeringTaskEntity task,
                                                           Map<String, Object> codeLocalization,
                                                           Map<String, Object> patchGeneration) {
        if (isBlank(repositoryPath) || task == null || !"INCIDENT_TO_FIX".equals(task.getTaskType())) {
            return CodeOpsTestPatchAgentOutput.unavailable("No incident regression scaffold is available for this task.");
        }
        Path repo = Path.of(repositoryPath).toAbsolutePath().normalize();
        if (!looksLikeOrderConcurrencyIncident(repo, task, codeLocalization, patchGeneration)) {
            return CodeOpsTestPatchAgentOutput.unavailable("Incident does not match the order-service concurrency regression scaffold.");
        }
        List<FileRewritePatchEntity> rewrites = List.of(
                FileRewritePatchEntity.builder()
                        .filePath("src/test/java/com/example/order/InventoryConcurrencyTest.java")
                        .newContent(inventoryConcurrencyTest())
                        .reasoning("Use real InventoryRepository and InventoryService APIs to prove concurrent reserve cannot oversell stock.")
                        .build(),
                FileRewritePatchEntity.builder()
                        .filePath("src/test/java/com/example/order/OrderSubmitServiceConcurrencyTest.java")
                        .newContent(orderSubmitServiceConcurrencyTest())
                        .reasoning("Use real OrderRepository, InventoryService and IdempotencyService APIs to prove duplicate requestId creates only one order.")
                        .build());
        return CodeOpsTestPatchAgentOutput.builder()
                .success(true)
                .fallback(false)
                .targetTestFiles(rewrites.stream().map(FileRewritePatchEntity::getFilePath).toList())
                .reasoning(List.of(
                        "Generated deterministic regression tests from repository API contracts after LLM localization selected the order-service incident path.",
                        "The scaffold does not decide the production fix; it only supplies compile-safe tests for the LLM patch."))
                .unifiedDiffPatch("")
                .fileRewrites(rewrites)
                .rawContent("")
                .errorMessage("")
                .costMillis(0L)
                .createTime(LocalDateTime.now())
                .build();
    }

    public List<String> mavenCommandsIfSupported(String repositoryPath, EngineeringTaskEntity task,
                                                 Map<String, Object> codeLocalization,
                                                 Map<String, Object> patchGeneration) {
        Path repo = isBlank(repositoryPath) ? null : Path.of(repositoryPath).toAbsolutePath().normalize();
        if (repo == null || !looksLikeOrderConcurrencyIncident(repo, task, codeLocalization, patchGeneration)) {
            return List.of();
        }
        return List.of(
                "mvn -q -DskipTests compile",
                "mvn -q -Dtest=InventoryConcurrencyTest,OrderSubmitServiceConcurrencyTest test",
                "mvn -q test");
    }

    private boolean looksLikeOrderConcurrencyIncident(Path repo,
                                                     EngineeringTaskEntity task,
                                                     Map<String, Object> codeLocalization,
                                                     Map<String, Object> patchGeneration) {
        if (repo == null || task == null) {
            return false;
        }
        if (!hasOrderServiceApi(repo)) {
            return false;
        }
        String text = (
                value(task.getGoal()) + "\n"
                        + value(task.getContext()) + "\n"
                        + value(codeLocalization) + "\n"
                        + value(patchGeneration)).toLowerCase(Locale.ROOT);
        return text.contains("order")
                && (text.contains("inventory") || text.contains("stock") || text.contains("oversell"))
                && (text.contains("concurr") || text.contains("duplicate") || text.contains("requestid")
                || text.contains("5xx") || text.contains("flashsale") || text.contains("flash sale"));
    }

    private boolean hasOrderServiceApi(Path repo) {
        return contains(repo, "src/main/java/com/example/order/InventoryRepository.java", "void initialize(String skuId, int stock)")
                && contains(repo, "src/main/java/com/example/order/InventoryRepository.java", "int getStock(String skuId)")
                && contains(repo, "src/main/java/com/example/order/InventoryRepository.java", "void updateStock(String skuId, int stock)")
                && contains(repo, "src/main/java/com/example/order/InventoryService.java", "void reserve(String skuId, int quantity)")
                && contains(repo, "src/main/java/com/example/order/InventoryService.java", "int stockOf(String skuId)")
                && contains(repo, "src/main/java/com/example/order/OrderRepository.java", "int countCreatedOrders()")
                && contains(repo, "src/main/java/com/example/order/OrderSubmitRequest.java", "String requestId")
                && contains(repo, "src/main/java/com/example/order/OrderSubmitService.java", "submitFlashSale");
    }

    private boolean contains(Path repo, String filePath, String expected) {
        Path file = repo.resolve(filePath).normalize();
        if (!file.startsWith(repo) || !Files.exists(file) || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8).contains(expected);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String inventoryConcurrencyTest() {
        return """
                package com.example.order;

                import org.junit.jupiter.api.RepeatedTest;

                import java.util.concurrent.CountDownLatch;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                import java.util.concurrent.TimeUnit;
                import java.util.concurrent.atomic.AtomicInteger;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class InventoryConcurrencyTest {

                    @RepeatedTest(5)
                    void concurrentReserveShouldNotOversellSingleStock() throws Exception {
                        InventoryRepository repository = new InventoryRepository();
                        String skuId = "sku-2001";
                        repository.initialize(skuId, 1);
                        InventoryService service = new InventoryService(repository);

                        int threads = 8;
                        CountDownLatch ready = new CountDownLatch(threads);
                        CountDownLatch start = new CountDownLatch(1);
                        CountDownLatch done = new CountDownLatch(threads);
                        AtomicInteger success = new AtomicInteger();
                        AtomicInteger rejected = new AtomicInteger();
                        ExecutorService executor = Executors.newFixedThreadPool(threads);

                        for (int i = 0; i < threads; i++) {
                            executor.submit(() -> {
                                ready.countDown();
                                try {
                                    start.await();
                                    service.reserve(skuId, 1);
                                    success.incrementAndGet();
                                } catch (IllegalStateException e) {
                                    rejected.incrementAndGet();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    done.countDown();
                                }
                            });
                        }

                        assertTrue(ready.await(5, TimeUnit.SECONDS));
                        start.countDown();
                        assertTrue(done.await(10, TimeUnit.SECONDS));
                        executor.shutdownNow();

                        assertEquals(1, success.get());
                        assertEquals(threads - 1, rejected.get());
                        assertEquals(0, service.stockOf(skuId));
                        assertTrue(service.stockOf(skuId) >= 0);
                    }
                }
                """;
    }

    private String orderSubmitServiceConcurrencyTest() {
        return """
                package com.example.order;

                import org.junit.jupiter.api.RepeatedTest;

                import java.math.BigDecimal;
                import java.util.concurrent.CountDownLatch;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                import java.util.concurrent.TimeUnit;
                import java.util.concurrent.atomic.AtomicInteger;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class OrderSubmitServiceConcurrencyTest {

                    @RepeatedTest(5)
                    void duplicateFlashSaleRequestShouldCreateOnlyOneOrder() throws Exception {
                        String skuId = "sku-2001";
                        InventoryRepository inventoryRepository = new InventoryRepository();
                        inventoryRepository.initialize(skuId, 10);
                        InventoryService inventoryService = new InventoryService(inventoryRepository);
                        OrderRepository orderRepository = new OrderRepository();
                        IdempotencyService idempotencyService = new IdempotencyService();
                        OrderSubmitService service = new OrderSubmitService(orderRepository, inventoryService, idempotencyService);

                        int threads = 8;
                        CountDownLatch ready = new CountDownLatch(threads);
                        CountDownLatch start = new CountDownLatch(1);
                        CountDownLatch done = new CountDownLatch(threads);
                        AtomicInteger success = new AtomicInteger();
                        AtomicInteger duplicate = new AtomicInteger();
                        ExecutorService executor = Executors.newFixedThreadPool(threads);

                        for (int i = 0; i < threads; i++) {
                            int userIndex = i;
                            executor.submit(() -> {
                                ready.countDown();
                                try {
                                    start.await();
                                    OrderSubmitRequest request = new OrderSubmitRequest(
                                            "user-" + userIndex,
                                            skuId,
                                            "req-duplicate-001",
                                            1,
                                            new BigDecimal("19.90"));
                                    service.submitFlashSale(request);
                                    success.incrementAndGet();
                                } catch (IllegalStateException e) {
                                    if (e.getMessage() != null && e.getMessage().contains("Duplicate requestId")) {
                                        duplicate.incrementAndGet();
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    done.countDown();
                                }
                            });
                        }

                        assertTrue(ready.await(5, TimeUnit.SECONDS));
                        start.countDown();
                        assertTrue(done.await(10, TimeUnit.SECONDS));
                        executor.shutdownNow();

                        assertEquals(1, success.get());
                        assertEquals(threads - 1, duplicate.get());
                        assertEquals(1, orderRepository.countCreatedOrders());
                        assertEquals(9, inventoryService.stockOf(skuId));
                    }
                }
                """;
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
