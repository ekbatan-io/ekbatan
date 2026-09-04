package io.ekbatan.core.action;

import static io.ekbatan.core.action.TestModel.Builder.testModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.domain.Id;
import io.ekbatan.testsupport.time.VirtualClock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionPlanTest {

    @Test
    void empty_plan_has_no_changes() {
        // GIVEN
        var plan = new ActionPlan();

        // WHEN / THEN
        assertThat(plan.hasChanges()).isFalse();
        assertThat(plan.changes()).isEmpty();
    }

    @Test
    void add_registers_addition() {
        // GIVEN
        var plan = new ActionPlan();
        var clock = new VirtualClock();
        var model = TestModel.createTestModel("test", clock.instant()).build();

        // WHEN
        plan.add(model);

        // THEN
        assertThat(plan.hasChanges()).isTrue();
        assertThat(plan.additions(TestModel.class)).hasSize(1);
        assertThat(plan.additions(TestModel.class).get(model.id)).isSameAs(model);
    }

    @Test
    void add_returns_the_entity() {
        // GIVEN
        var plan = new ActionPlan();
        var clock = new VirtualClock();
        var model = TestModel.createTestModel("test", clock.instant()).build();

        // WHEN
        var result = plan.add(model);

        // THEN
        assertThat(result).isSameAs(model);
    }

    @Test
    void update_registers_update() {
        // GIVEN
        var plan = new ActionPlan();
        var clock = new VirtualClock();
        var model = testModel()
                .id(Id.random(TestModel.class))
                .state(TestState.ACTIVE)
                .name("test")
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        // WHEN
        plan.update(model);

        // THEN
        assertThat(plan.hasChanges()).isTrue();
        assertThat(plan.updates(TestModel.class)).hasSize(1);
    }

    @Test
    void update_returns_next_version() {
        // GIVEN
        var plan = new ActionPlan();
        var clock = new VirtualClock();
        var model = testModel()
                .id(Id.random(TestModel.class))
                .state(TestState.ACTIVE)
                .name("test")
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        // WHEN
        var result = plan.update(model);

        // THEN
        assertThat(result.version).isEqualTo(2L);
    }

    @Test
    void addAll_registers_multiple_additions() {
        // GIVEN
        var plan = new ActionPlan();
        var clock = new VirtualClock();
        var m1 = TestModel.createTestModel("a", clock.instant()).build();
        var m2 = TestModel.createTestModel("b", clock.instant()).build();

        // WHEN
        plan.addAll(List.of(m1, m2));

        // THEN
        assertThat(plan.additions(TestModel.class)).hasSize(2);
    }

    @Test
    void addAll_with_empty_list_returns_empty() {
        // GIVEN
        var plan = new ActionPlan();

        // WHEN
        var result = plan.addAll(List.of());

        // THEN
        assertThat(result).isEmpty();

        // AND
        assertThat(plan.hasChanges()).isFalse();
    }

    @Test
    void addAll_with_null_returns_empty() {
        // GIVEN
        var plan = new ActionPlan();

        // WHEN
        var result = plan.addAll(null);

        // THEN
        assertThat(result).isEmpty();
    }

    @Test
    void updateAll_with_empty_list_returns_empty() {
        // GIVEN
        var plan = new ActionPlan();

        // WHEN
        var result = plan.updateAll(List.of());

        // THEN
        assertThat(result).isEmpty();

        // AND
        assertThat(plan.hasChanges()).isFalse();
    }

    @Test
    void updateAll_with_null_returns_empty() {
        // GIVEN
        var plan = new ActionPlan();

        // WHEN
        var result = plan.updateAll(null);

        // THEN
        assertThat(result).isEmpty();
    }

    @Test
    void additions_returns_empty_for_unregistered_type() {
        // GIVEN
        var plan = new ActionPlan();

        // WHEN / THEN
        assertThat(plan.additions(TestModel.class)).isEmpty();
    }

    @Test
    void updates_returns_empty_for_unregistered_type() {
        // GIVEN
        var plan = new ActionPlan();

        // WHEN / THEN
        assertThat(plan.updates(TestModel.class)).isEmpty();
    }

    @Test
    void changes_map_is_unmodifiable() {
        // GIVEN
        var plan = new ActionPlan();

        // WHEN / THEN
        assertThatThrownBy(() -> plan.changes().put(TestModel.class, null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mixed_types_tracked_separately() {
        // GIVEN
        var plan = new ActionPlan();
        var clock = new VirtualClock();

        // WHEN
        plan.add(TestModel.createTestModel("model", clock.instant()).build());
        plan.add(TestProduct.createTestProduct("product", clock.instant()).build());

        // THEN
        assertThat(plan.additions(TestModel.class)).hasSize(1);
        assertThat(plan.additions(TestProduct.class)).hasSize(1);
        assertThat(plan.changes()).hasSize(2);
    }

    @Test
    void add_same_id_twice_throws() {
        // GIVEN
        var plan = new ActionPlan();
        var clock = new VirtualClock();
        var model = TestModel.createTestModel("test", clock.instant()).build();
        plan.add(model);

        // WHEN / THEN
        assertThatThrownBy(() -> plan.add(model))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered")
                .hasMessageContaining("TestModel")
                .hasMessageContaining("composed before they are staged");
    }
}
