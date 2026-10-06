package io.ekbatan.core.test.repository;

import static io.ekbatan.core.test.model.Dummy.createDummy;
import static java.lang.String.format;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.domain.TypedValue;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.repository.exception.EntityNotFoundException;
import io.ekbatan.core.repository.exception.StaleRecordException;
import io.ekbatan.core.test.model.Dummy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;

public abstract class BaseRepositoryTest {

    protected final JdbcDatabaseContainer<?> db;

    protected final AbstractRepository<Dummy, ?, ?, UUID> repository;

    protected TransactionManager transactionManager;

    public BaseRepositoryTest(JdbcDatabaseContainer<?> db, AbstractRepository<Dummy, ?, ?, UUID> repository) {
        this.db = db;
        this.repository = repository;
    }

    @Test
    void should_add_correctly() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();

        // WHEN
        final var added = repository.add(dummy);
        final var found = repository.findById(dummy.getId().getValue());

        // THEN
        assertThat(added).isNotNull();
        assertThat(added.id).isEqualTo(dummy.id);
        assertThat(added.state).isEqualTo(dummy.state);
        assertThat(added.ownerId).isEqualTo(dummy.ownerId);
        assertThat(added.currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(added.balance).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(added.createdDate).isEqualTo(dummy.createdDate);
        assertThat(added.updatedDate).isEqualTo(dummy.updatedDate);
        assertThat(added.version).isEqualTo(1L);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().id).isEqualTo(dummy.id);
        assertThat(found.orElseThrow().state).isEqualTo(dummy.state);
        assertThat(found.orElseThrow().ownerId).isEqualTo(dummy.ownerId);
        assertThat(found.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(found.orElseThrow().balance).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(found.orElseThrow().createdDate).isEqualTo(dummy.createdDate);
        assertThat(found.orElseThrow().updatedDate).isEqualTo(dummy.updatedDate);
        assertThat(found.orElseThrow().version).isEqualTo(1L);
    }

    @Test
    void should_add_correctly_in_transaction() {
        // GIVEN / WHEN

        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();

        transactionManager.inTransaction(_ -> {
            repository.add(dummy);
        });

        // THEN

        final var fetchedDummy = repository.findById(dummy.getId().getValue());
        assertThat(fetchedDummy).isPresent();
        assertThat(fetchedDummy.orElseThrow().id).isEqualTo(dummy.id);
        assertThat(fetchedDummy.orElseThrow().state).isEqualTo(dummy.state);
        assertThat(fetchedDummy.orElseThrow().ownerId).isEqualTo(dummy.ownerId);
        assertThat(fetchedDummy.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(fetchedDummy.orElseThrow().balance.intValue()).isEqualTo(10);
        assertThat(fetchedDummy.orElseThrow().createdDate).isEqualTo(dummy.createdDate);
        assertThat(fetchedDummy.orElseThrow().updatedDate).isEqualTo(dummy.updatedDate);
    }

    @Test
    void should_rollback_add_in_transaction_upon_exception() {
        // GIVEN / WHEN

        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();

        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                repository.add(dummy);

                throw new RuntimeException();
            });
        } catch (Exception _) {

        }

        // THEN

        final var fetchedDummy = repository.findById(dummy.getId().getValue());
        assertThat(fetchedDummy).isEmpty();
    }

    @Test
    void should_addNoResult() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();

        // WHEN
        repository.addNoResult(dummy);

        // THEN
        final var fetchedDummy = repository.findById(dummy.getId().getValue());
        assertThat(fetchedDummy).isPresent();
        assertThat(fetchedDummy.orElseThrow().id).isEqualTo(dummy.id);
        assertThat(fetchedDummy.orElseThrow().state).isEqualTo(dummy.state);
        assertThat(fetchedDummy.orElseThrow().ownerId).isEqualTo(dummy.ownerId);
        assertThat(fetchedDummy.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(fetchedDummy.orElseThrow().balance).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void should_addNoResult_inTransaction() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.valueOf(100), Instant.now())
                .build();

        // WHEN
        transactionManager.inTransaction(_ -> {
            repository.addNoResult(dummy);
        });

        // THEN
        final var fetchedDummy = repository.findById(dummy.getId().getValue());
        assertThat(fetchedDummy).isPresent();
        assertThat(fetchedDummy.orElseThrow().id).isEqualTo(dummy.id);
        assertThat(fetchedDummy.orElseThrow().state).isEqualTo(dummy.state);
        assertThat(fetchedDummy.orElseThrow().ownerId).isEqualTo(dummy.ownerId);
        assertThat(fetchedDummy.orElseThrow().currency.getCurrencyCode()).isEqualTo("USD");
        assertThat(fetchedDummy.orElseThrow().balance).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    void should_addAll() {

        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (var i = 0; i < 10; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }

        // WHEN
        final var addedDummies = repository.addAll(dummies);

        // THEN
        final var fetchDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchDummies).hasSize(10);
        assertThat(fetchDummies).hasSameElementsAs(addedDummies);
    }

    @Test
    void should_addAll_inTransaction() {

        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 10; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }

        // WHEN
        transactionManager.inTransaction(_ -> {
            repository.addAll(dummies);
        });

        // THEN
        final var fetchDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchDummies).hasSize(10);
    }

    @Test
    void should_rollback_addAll_inTransaction_when_exception() {

        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 10; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                repository.addAll(dummies);
                throw new RuntimeException();
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchDummies).hasSize(0);
    }

    @Test
    void should_addAllNoResult() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 10; i++) {
            dummies.add(createDummy(
                            randomUUID(),
                            Currency.getInstance(i % 2 == 0 ? "EUR" : "USD"),
                            BigDecimal.TEN.add(BigDecimal.valueOf(i)),
                            Instant.now())
                    .build());
        }

        // WHEN
        repository.addAllNoResult(dummies);

        // THEN
        final var fetchDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchDummies).hasSize(10);
        assertThat(fetchDummies).allSatisfy(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isIn("EUR", "USD");
            assertThat(w.balance).isGreaterThanOrEqualTo(BigDecimal.TEN);
        });
    }

    @Test
    void should_addAllNoResult_inTransaction() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(createDummy(
                            randomUUID(),
                            Currency.getInstance(i % 2 == 0 ? "GBP" : "CHF"),
                            BigDecimal.valueOf(100 + i),
                            Instant.now())
                    .build());
        }

        // WHEN
        transactionManager.inTransaction(_ -> {
            repository.addAllNoResult(dummies);
        });

        // THEN
        final var fetchDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchDummies).hasSize(5);
        assertThat(fetchDummies).allSatisfy(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isIn("GBP", "CHF");
            assertThat(w.balance).isGreaterThanOrEqualTo(BigDecimal.valueOf(100));
        });
    }

    @Test
    void should_rollback_addAllNoResult_inTransaction_when_exception() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(
                    createDummy(randomUUID(), Currency.getInstance("JPY"), BigDecimal.valueOf(1000 + i), Instant.now())
                            .build());
        }

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                repository.addAllNoResult(dummies);
                throw new RuntimeException("Simulated transaction failure");
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchDummies).isEmpty();
    }

    @Test
    void should_update() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        repository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(BigDecimal.TWO);

        // WHEN
        final var updatedDummy = repository.update(dummyToUpdate);

        // THEN
        final var fetchedDummy = repository.getById(dummy.getId().getValue());

        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(fetchedDummy.version).isEqualTo(2);

        assertThat(updatedDummy).isNotNull();
        assertThat(updatedDummy.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(updatedDummy.version).isEqualTo(2);
    }

    @Test
    void should_update_inTransaction() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        repository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(BigDecimal.TWO);

        // WHEN
        transactionManager.inTransaction(_ -> {
            repository.update(dummyToUpdate);
        });

        // THEN
        final var fetchedDummy = repository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(fetchedDummy.version).isEqualTo(2);
    }

    @Test
    void should_not_update_inTransaction_when_exception() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        repository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(BigDecimal.TWO);

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                repository.update(dummyToUpdate);

                throw new RuntimeException();
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchedDummy = repository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(dummy.balance);
        assertThat(fetchedDummy.version).isEqualTo(1);
    }

    @Test
    void should_updateNoResult() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        repository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(BigDecimal.TWO);

        // WHEN
        repository.updateNoResult(dummyToUpdate);

        // THEN
        final var fetchedDummy = repository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(fetchedDummy.version).isEqualTo(2);
    }

    @Test
    void should_updateNoResult_inTransaction() {
        // GIVEN
        final var dummy = createDummy(
                        randomUUID(), Currency.getInstance("USD"), new BigDecimal("100.50"), Instant.now())
                .build();
        repository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(new BigDecimal("20.25"));

        // WHEN
        transactionManager.inTransaction(_ -> {
            repository.updateNoResult(dummyToUpdate);
        });

        // THEN
        final var fetchedDummy = repository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(new BigDecimal("80.25"));
        assertThat(fetchedDummy.version).isEqualTo(2);
    }

    @Test
    void should_rollback_updateNoResult_inTransaction_when_exception() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("GBP"), new BigDecimal("50.00"), Instant.now())
                .build();
        repository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(BigDecimal.TEN);

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                repository.updateNoResult(dummyToUpdate);
                throw new RuntimeException("Simulated transaction failure");
            });
        } catch (Exception _) {
        }

        // THEN - Verify the update was rolled back
        final var fetchedDummy = repository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(dummy.balance);
        assertThat(fetchedDummy.version).isEqualTo(1);
    }

    @Test
    void should_throw_StaleRecordException_when_updating_stale_version() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        repository.add(dummy);

        final var dummyToUpdate = dummy.withdraw(BigDecimal.ONE);
        final var dummyToUpdateStale = dummy.withdraw(BigDecimal.TWO);
        repository.updateNoResult(dummyToUpdate);

        // WHEN & THEN
        assertThatThrownBy(() -> repository.updateNoResult(dummyToUpdateStale))
                .isInstanceOf(StaleRecordException.class)
                .hasMessageContaining("was concurrently modified or not found");

        final var fetchedDummy = repository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(dummyToUpdate.balance);
        assertThat(fetchedDummy.version).isEqualTo(2);
    }

    @Test
    void should_throw_StaleRecordException_when_updating_stale_version_with_update_method() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        repository.add(dummy);

        final var dummyToUpdate = dummy.withdraw(BigDecimal.ONE);
        final var dummyToUpdateStale = dummy.withdraw(BigDecimal.TWO);
        repository.update(dummyToUpdate);

        // WHEN & THEN
        assertThatThrownBy(() -> repository.update(dummyToUpdateStale))
                .isInstanceOf(StaleRecordException.class)
                .hasMessageContaining("was concurrently modified or not found");

        final var fetchedDummy = repository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(dummyToUpdate.balance);
        assertThat(fetchedDummy.version).isEqualTo(2);
    }

    @Test
    void should_updateAll() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }
        repository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        final var updatedDummies = repository.updateAll(dummiesToUpdate);

        // THEN
        final var fetchedDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());
        assertThat(fetchedDummies).hasSize(5);
        assertThat(fetchedDummies).hasSameElementsAs(updatedDummies);

        assertThat(updatedDummies).hasSize(5);
        updatedDummies.forEach(w -> {
            assertThat(w.balance).isEqualByComparingTo(BigDecimal.valueOf(9));
            assertThat(w.version).isEqualTo(2);
        });
    }

    @Test
    void should_updateAll_inTransaction() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }
        repository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        transactionManager.inTransaction(_ -> {
            repository.updateAll(dummiesToUpdate);
        });

        // THEN
        final var fetchedDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedDummies).hasSize(5);
        fetchedDummies.forEach(w -> {
            assertThat(w.balance).isEqualByComparingTo(BigDecimal.valueOf(9));
            assertThat(w.version).isEqualTo(2);
        });
    }

    @Test
    void should_throw_optimistic_lock_exception_when_updateAll_with_stale_versions() {
        // GIVEN
        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN, Instant.now())
                .build();
        repository.addAll(List.of(dummy1, dummy2));

        final var dummy1Changed = dummy1.withdraw(BigDecimal.ONE);
        repository.update(dummy1Changed);

        final var dummy1StaleChanged =
                dummy1.withdraw(BigDecimal.ONE); // This will have version 0 but current version is 1
        final var dummy2Changed = dummy2.withdraw(BigDecimal.ONE);

        // WHEN / THEN
        assertThatThrownBy(() -> repository.updateAll(List.of(dummy2Changed, dummy1StaleChanged)))
                .isInstanceOf(StaleRecordException.class)
                .hasMessageContaining("Expected to update 2 records but only updated 1");

        final var fetchedDummies = repository.findAllByIds(
                List.of(dummy1.getId().getValue(), dummy2.getId().getValue()));

        assertThat(fetchedDummies).hasSize(2);

        final var dummy1Fetched =
                fetchedDummies.stream().filter(w -> w.id.equals(dummy1.id)).findFirst();
        assertThat(dummy1Fetched).isPresent();
        assertThat(dummy1Fetched.orElseThrow().balance).isEqualByComparingTo(BigDecimal.valueOf(9));
        assertThat(dummy1Fetched.orElseThrow().version).isEqualTo(2);

        final var dummy2Fetched =
                fetchedDummies.stream().filter(w -> w.id.equals(dummy2.id)).findFirst();
        assertThat(dummy2Fetched).isPresent();
        assertThat(dummy2Fetched.orElseThrow().balance).isEqualByComparingTo(BigDecimal.valueOf(9));
        assertThat(dummy2Fetched.orElseThrow().version).isEqualTo(2);
    }

    /**
     * Regression guard for the batch-update bind types. The multi-row builders must bind each
     * value against its target field, so the field's DataType and its Converter survive; binding
     * from the value's runtime class instead makes jOOQ infer {@code timestamp with time zone}
     * for an {@link Instant} and the database then shifts it by the session's offset.
     *
     * <p>Note this only <em>fails</em> on the defective code when the JVM default zone is not UTC,
     * because that offset is what does the shifting. See the note on
     * {@code should_updateAll_and_update_store_identical_timestamps}.
     */
    @Test
    void should_preserve_exact_timestamps_when_updateAll_batches_multiple_rows() {
        // GIVEN two dummies sharing a fixed created date - two rows, so updateAll cannot take the
        // single-row short-circuit and must go through the batch builder
        final var createdDate = Instant.parse("2026-01-01T10:00:00Z");
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 2; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, createdDate)
                    .build());
        }
        repository.addAll(dummies);

        // WHEN they are updated as a batch
        repository.updateAll(
                dummies.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList());

        // THEN the created date round-trips unshifted
        final var fetchedDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedDummies).hasSize(2);
        fetchedDummies.forEach(w -> assertThat(w.createdDate).isEqualTo(createdDate));
    }

    /**
     * Pins the two write paths together. {@code updateAll} short-circuits a single-element
     * collection to the single-row {@code update}, so a defect confined to the batch builder is
     * invisible unless the two are compared directly on the same input.
     *
     * <p>Both assertions below hold trivially under a UTC JVM. Running the suite with a non-UTC
     * default zone (for example {@code -Duser.timezone=America/New_York}) is what makes this test
     * able to observe the timestamp defect at all. The {@code *TimeZoneIntegrationTest} classes
     * run the JVM outside UTC themselves and check the stored value of both paths, so they catch
     * it on a UTC machine too.
     */
    @Test
    void should_updateAll_and_update_store_identical_timestamps() {
        // GIVEN one dummy that will go through the single-row path and two that will be batched
        final var createdDate = Instant.parse("2026-01-01T10:00:00Z");
        final var single = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, createdDate)
                .build();
        final var batched = new ArrayList<Dummy>();
        for (int i = 0; i < 2; i++) {
            batched.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, createdDate)
                    .build());
        }
        repository.addAll(batched);
        repository.add(single);

        // WHEN one is updated alone and the others as a batch
        repository.updateAll(List.of(single.withdraw(BigDecimal.ONE)));
        repository.updateAll(
                batched.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList());

        // THEN every row holds the same stored created date, whichever path wrote it
        final var singleFetched = repository.getById(single.getId().getValue());
        final var batchedFetched = repository.findAllByIds(
                batched.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(batchedFetched).hasSize(2);
        batchedFetched.forEach(w -> assertThat(w.createdDate).isEqualTo(singleFetched.createdDate));
        assertThat(singleFetched.createdDate).isEqualTo(createdDate);
    }

    /**
     * A batch write in which a nullable non-text column is null in <em>every</em> row leaves the
     * builder with no value to infer a type from. Binding against the target field types the NULL;
     * inferring from the runtime class emits a bare NULL, which Postgres rejects outright with
     * SQLSTATE 42804.
     */
    @Test
    void should_updateAll_when_a_nullable_column_is_null_in_every_row() {
        // GIVEN two dummies that both leave rewardPoints unset
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 2; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }
        repository.addAll(dummies);
        assertThat(dummies).allSatisfy(w -> assertThat(w.rewardPoints).isNull());

        // WHEN they are updated as a batch, so the column is null across the whole batch
        repository.updateAll(
                dummies.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList());

        // THEN the statement succeeds and the column stays null
        final var fetchedDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedDummies).hasSize(2);
        fetchedDummies.forEach(w -> assertThat(w.rewardPoints).isNull());
    }

    /**
     * The {@code aliases} column only reaches the database through a jOOQ {@code Converter}. A
     * write path that binds from the value's runtime class discards that converter along with the
     * field's DataType, so the value never arrives in the shape the column expects.
     */
    @Test
    void should_preserve_converted_values_when_updateAll_batches_multiple_rows() {
        // GIVEN two dummies carrying converter-backed values, and a third to change them to
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 2; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .aliases(List.of("first-" + i, "second-" + i))
                    .rewardPoints(10 + i)
                    .build());
        }
        repository.addAll(dummies);

        // WHEN the converted columns are changed through a batch update
        final var updated = dummies.stream()
                .map(w -> w.copy()
                        .aliases(List.of("renamed-" + w.rewardPoints))
                        .rewardPoints(w.rewardPoints + 100)
                        .build())
                .toList();
        repository.updateAll(updated);

        // THEN the stored values round-trip exactly
        final var fetchedDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedDummies).hasSize(2);
        fetchedDummies.forEach(w -> {
            assertThat(w.rewardPoints).isIn(110, 111);
            assertThat(w.aliases).containsExactly("renamed-" + (w.rewardPoints - 100));
        });
    }

    @Test
    void should_rollback_updateAll_inTransaction_when_exception() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                    .build());
        }
        repository.addAll(dummies);

        final var originalBalances = dummies.stream().collect(Collectors.toMap(w -> w.id, w -> w.balance));

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                repository.updateAll(dummiesToUpdate);
                throw new RuntimeException("Test exception");
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchedDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedDummies).hasSize(5);
        fetchedDummies.forEach(w -> {
            assertThat(w.balance).isEqualByComparingTo(originalBalances.get(w.id));
            assertThat(w.version).isOne();
        });
    }

    @Test
    void should_updateAllNoResult() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(createDummy(
                            randomUUID(),
                            Currency.getInstance(i % 2 == 0 ? "EUR" : "USD"),
                            BigDecimal.TEN.add(BigDecimal.valueOf(i)),
                            Instant.now())
                    .build());
        }
        repository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        repository.updateAllNoResult(dummiesToUpdate);

        // THEN
        final var fetchedDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedDummies).hasSize(5);
        fetchedDummies.forEach(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isIn("EUR", "USD");
            assertThat(w.balance).isGreaterThanOrEqualTo(BigDecimal.valueOf(9)); // 10 - 1, 11 - 1, etc.
            assertThat(w.version).isEqualTo(2);
        });
    }

    @Test
    void should_updateAllNoResult_inTransaction() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 3; i++) {
            dummies.add(createDummy(
                            randomUUID(),
                            Currency.getInstance("GBP"),
                            new BigDecimal("100.00").add(BigDecimal.valueOf(i * 10)),
                            Instant.now())
                    .build());
        }
        repository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(new BigDecimal("5.50"))).toList();

        // WHEN
        transactionManager.inTransaction(_ -> {
            repository.updateAllNoResult(dummiesToUpdate);
        });

        // THEN
        final var fetchedDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedDummies).hasSize(3);
        fetchedDummies.forEach(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isEqualTo("GBP");
            assertThat(w.version).isEqualTo(2);
        });

        final var fetchedMap = fetchedDummies.stream().collect(Collectors.toMap(d -> d.id, d -> d));

        assertThat(fetchedMap.get(dummies.get(0).id).balance)
                .isEqualByComparingTo(new BigDecimal("94.50")); // 100.00 - 5.50
        assertThat(fetchedMap.get(dummies.get(1).id).balance).isEqualByComparingTo(new BigDecimal("104.50"));
        assertThat(fetchedMap.get(dummies.get(2).id).balance).isEqualByComparingTo(new BigDecimal("114.50"));
    }

    @Test
    void should_rollback_updateAllNoResult_inTransaction_when_exception() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 4; i++) {
            dummies.add(createDummy(
                            randomUUID(),
                            Currency.getInstance("JPY"),
                            new BigDecimal("1000").add(BigDecimal.valueOf(i * 100)),
                            Instant.now())
                    .build());
        }
        repository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(new BigDecimal("100"))).toList();

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                repository.updateAllNoResult(dummiesToUpdate);
                throw new RuntimeException("Simulated transaction failure");
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchedDummies = repository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(TypedValue::getValue).toList());

        assertThat(fetchedDummies).hasSize(4);
        fetchedDummies.forEach(w -> {
            assertThat(w.version).isEqualTo(1);
            assertThat(w.balance).isGreaterThanOrEqualTo(new BigDecimal("1000"));
        });
    }

    @Test
    void should_throw_StaleRecordException_when_updatingAll_with_stale_versions() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 3; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), new BigDecimal("50.00"), Instant.now())
                    .build());
        }
        repository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(BigDecimal.TEN)).toList();

        final var firstDummyToUpdate = dummiesToUpdate.getFirst();
        repository.updateNoResult(firstDummyToUpdate);

        // WHEN & THEN
        assertThatThrownBy(() -> repository.updateAllNoResult(dummiesToUpdate))
                .isInstanceOf(StaleRecordException.class);
    }

    @Test
    void should_findById() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        dummy = repository.add(dummy);

        // WHEN
        final var found = repository.findById(dummy.getId().getValue());

        // THEN
        assertThat(found).isPresent();
        assertThat(found.orElseThrow()).isEqualTo(dummy);
    }

    @Test
    void should_return_empty_when_findById_with_non_existent_id() {
        // WHEN
        final var found = repository.findById(randomUUID());

        // THEN
        assertThat(found).isEmpty();
    }

    @Test
    void should_return_empty_when_findById_when_marked_as_deleted() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        repository.add(dummy);

        final var deletedDummy = dummy.delete();
        repository.update(deletedDummy);

        // WHEN
        final var found = repository.findById(dummy.getId().getValue());

        // THEN
        assertThat(found).isEmpty();
    }

    @Test
    void should_getById() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        dummy = repository.add(dummy);

        // WHEN
        final var found = repository.getById(dummy.getId().getValue());

        // THEN
        assertThat(found).isEqualTo(dummy);
    }

    @Test
    void should_throw_EntityNotFoundException_when_getById_with_non_existent_id() {
        // GIVEN
        final var id = randomUUID();

        // WHEN / THEN
        assertThatThrownBy(() -> repository.getById(id))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(format("No entity found with id: %s[id=%s]", Dummy.class.getSimpleName(), id));
    }

    @Test
    void should_throw_EntityNotFoundException_when_getById_when_marked_as_deleted() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        repository.add(dummy);

        final var deletedDummy = dummy.delete();
        repository.update(deletedDummy);

        // WHEN
        final var found = repository.findById(dummy.getId().getValue());

        // THEN
        assertThat(found).isEmpty();
    }

    @Test
    void should_findAllByIds() {
        // GIVEN
        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE, Instant.now())
                .build();
        final var dummy3 = createDummy(randomUUID(), Currency.getInstance("GBP"), BigDecimal.ZERO, Instant.now())
                .build();

        repository.addAll(List.of(dummy1, dummy2, dummy3));

        // WHEN
        final var foundDummies = repository.findAllByIds(
                List.of(dummy1.getId().getValue(), dummy3.getId().getValue(), randomUUID()));

        // THEN
        assertThat(foundDummies).hasSize(2);
        assertThat(foundDummies).extracting(w -> w.id).containsExactlyInAnyOrder(dummy1.id, dummy3.id);
    }

    @Test
    void findAllByIds_should_return_non_DELETED_items() {
        // GIVEN
        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE, Instant.now())
                .build();

        repository.addAll(List.of(dummy1, dummy2));

        final var deletedDummy2 = dummy2.delete();
        repository.update(deletedDummy2);

        // WHEN
        final var foundDummies = repository.findAllByIds(
                List.of(dummy1.getId().getValue(), dummy2.getId().getValue()));

        // THEN
        assertThat(foundDummies).hasSize(1);
        assertThat(foundDummies).extracting(w -> w.id).containsExactly(dummy1.id);
    }

    @Test
    void should_return_empty_list_when_findAllByIds_with_empty_input() {
        // WHEN
        final var foundDummies = repository.findAllByIds(List.of());

        // THEN
        assertThat(foundDummies).isEmpty();
    }

    @Test
    void should_findAll() {
        // GIVEN
        final var dsl =
                DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), transactionManager.dialect);
        dsl.truncate("dummies").execute();

        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE, Instant.now())
                .build();
        final var dummy3 = createDummy(randomUUID(), Currency.getInstance("GBP"), BigDecimal.ZERO, Instant.now())
                .build();
        final var dummy4 = createDummy(randomUUID(), Currency.getInstance("GBP"), BigDecimal.ZERO, Instant.now())
                .build();

        repository.addAll(List.of(dummy1, dummy2, dummy3, dummy4));

        final var deletedDummy3 = dummy3.delete();
        repository.update(deletedDummy3);

        // WHEN
        final var result = repository.findAll();

        // THEN
        assertThat(result).hasSize(3);
        assertThat(result).extracting(w -> w.id).containsExactlyInAnyOrder(dummy1.id, dummy2.id, dummy4.id);
    }

    @Test
    void should_findAll_excludes_deleted() {
        // GIVEN
        final var dsl =
                DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), transactionManager.dialect);
        dsl.truncate("dummies").execute();

        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE, Instant.now())
                .build();

        repository.addAll(List.of(dummy1, dummy2));

        final var deletedDummy1 = dummy1.delete();
        repository.update(deletedDummy1);

        // WHEN
        final var result = repository.findAll();

        // THEN
        assertThat(result).hasSize(1);
        assertThat(result).extracting(w -> w.id).containsExactly(dummy2.id);
    }

    @Test
    void should_existsById() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        dummy = repository.add(dummy);

        // WHEN
        final var exists = repository.existsById(dummy.getId().getValue());
        final var notExists = repository.existsById(randomUUID());

        // THEN
        assertThat(exists).isTrue();
        assertThat(notExists).isFalse();
    }

    @Test
    void should_existsById_excludes_deleted() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        repository.add(dummy);

        final var deletedDummy = dummy.delete();
        repository.update(deletedDummy);

        // WHEN
        final var exists = repository.existsById(dummy.getId().getValue());

        // THEN
        assertThat(exists).isFalse();
    }

    @Test
    void should_count_excludes_deleted() {
        // GIVEN
        final var dsl =
                DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), transactionManager.dialect);
        dsl.truncate("dummies").execute();

        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy3 = createDummy(randomUUID(), Currency.getInstance("GBP"), BigDecimal.TEN, Instant.now())
                .build();

        repository.addAll(List.of(dummy1, dummy2, dummy3));

        repository.update(dummy2.delete());

        // WHEN
        final var count = repository.count();

        // THEN
        assertThat(count).isEqualTo(2);
    }

    @Test
    void should_countWhere_excludes_deleted() {
        // GIVEN
        final var dsl =
                DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), transactionManager.dialect);
        dsl.truncate("dummies").execute();

        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy3 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy4 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN, Instant.now())
                .build();

        repository.addAll(List.of(dummy1, dummy2, dummy3, dummy4));

        repository.update(dummy3.delete());

        // WHEN
        final var count = repository.countWhere(DSL.field("CURRENCY").eq("EUR"));

        // THEN
        assertThat(count).isEqualTo(2);
    }

    @Test
    void should_findOneWhere_excludes_deleted() {
        // GIVEN
        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN, Instant.now())
                .build();

        repository.addAll(List.of(dummy1, dummy2));

        repository.update(dummy1.delete());

        // WHEN
        final var foundDeleted =
                repository.findOneWhere(repository.idField.eq(dummy1.getId().getValue()));
        final var foundActive =
                repository.findOneWhere(repository.idField.eq(dummy2.getId().getValue()));

        // THEN
        assertThat(foundDeleted).isEmpty();
        assertThat(foundActive).isPresent();
        assertThat(foundActive.get().id).isEqualTo(dummy2.id);
    }

    @Test
    void should_findAllWhere_condition_excludes_deleted() {
        // GIVEN
        final var dsl =
                DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), transactionManager.dialect);
        dsl.truncate("dummies").execute();

        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.valueOf(20), Instant.now())
                .build();
        final var dummy3 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.valueOf(30), Instant.now())
                .build();

        repository.addAll(List.of(dummy1, dummy2, dummy3));

        repository.update(dummy1.delete());

        // WHEN
        final var result = repository.findAllWhere(DSL.field("CURRENCY").eq("EUR"));

        // THEN
        assertThat(result).hasSize(1);
        assertThat(result).extracting(w -> w.id).containsExactly(dummy2.id);
    }

    @Test
    void should_existsWhere_returns_true_when_exists() {
        // GIVEN
        final var dsl =
                DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), transactionManager.dialect);
        dsl.truncate("dummies").execute();

        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        repository.add(dummy);

        // WHEN
        final var exists = repository.existsWhere(DSL.field("CURRENCY").eq("EUR"));

        // THEN
        assertThat(exists).isTrue();
    }

    @Test
    void should_existsWhere_returns_false_when_deleted() {
        // GIVEN
        final var dsl =
                DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), transactionManager.dialect);
        dsl.truncate("dummies").execute();

        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN, Instant.now())
                .build();
        repository.add(dummy);

        repository.update(dummy.delete());

        // WHEN
        final var exists = repository.existsWhere(DSL.field("CURRENCY").eq("EUR"));

        // THEN
        assertThat(exists).isFalse();
    }
}
