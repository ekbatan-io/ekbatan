package io.ekbatan.core.action.persister;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.Id;
import org.junit.jupiter.api.Test;

class PersistableChangesTest {

    // Minimal entity for testing
    static class Item extends Entity<Item, Id<Item>, GenericState> {
        Item(ItemBuilder builder) {
            super(builder);
        }

        @Override
        public ItemBuilder copy() {
            return ItemBuilder.item().copyBase(this);
        }

        static class ItemBuilder extends Entity.Builder<Id<Item>, ItemBuilder, Item, GenericState> {
            static ItemBuilder item() {
                return new ItemBuilder();
            }

            @Override
            public Item build() {
                return new Item(this);
            }
        }
    }

    private Item createItem() {
        return Item.ItemBuilder.item()
                .id(Id.random(Item.class))
                .state(GenericState.ACTIVE)
                .withInitialVersion()
                .build();
    }

    @Test
    void empty_changes_has_no_additions_or_updates() {
        // GIVEN
        var changes = new PersistableChanges<Id<Item>, Item>();

        // WHEN / THEN
        assertThat(changes.additions()).isEmpty();
        assertThat(changes.updates()).isEmpty();
        assertThat(changes.hasChanges()).isFalse();
    }

    @Test
    void add_registers_addition() {
        // GIVEN
        var changes = new PersistableChanges<Id<Item>, Item>();
        var item = createItem();

        // WHEN
        changes.add(item);

        // THEN
        assertThat(changes.additions()).hasSize(1);
        assertThat(changes.additions().get(item.id)).isSameAs(item);
        assertThat(changes.updates()).isEmpty();
        assertThat(changes.hasChanges()).isTrue();
    }

    @Test
    void update_registers_update() {
        // GIVEN
        var changes = new PersistableChanges<Id<Item>, Item>();
        var item = createItem();

        // WHEN
        changes.update(item);

        // THEN
        assertThat(changes.updates()).hasSize(1);
        assertThat(changes.updates().get(item.id)).isSameAs(item);
        assertThat(changes.additions()).isEmpty();
        assertThat(changes.hasChanges()).isTrue();
    }

    @Test
    void add_multiple_items() {
        // GIVEN
        var changes = new PersistableChanges<Id<Item>, Item>();

        // WHEN
        changes.add(createItem());
        changes.add(createItem());
        changes.add(createItem());

        // THEN
        assertThat(changes.additions()).hasSize(3);
    }

    @Test
    void add_rejects_duplicate_id_already_added() {
        // GIVEN
        var changes = new PersistableChanges<Id<Item>, Item>();
        var item = createItem();
        changes.add(item);

        // WHEN / THEN
        assertThatThrownBy(() -> changes.add(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered for addition")
                .hasMessageContaining("Item")
                .hasMessageContaining("composed before they are staged");
    }

    @Test
    void add_rejects_duplicate_id_already_updated() {
        // GIVEN
        var changes = new PersistableChanges<Id<Item>, Item>();
        var item = createItem();
        changes.update(item);

        // WHEN / THEN
        assertThatThrownBy(() -> changes.add(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered for update")
                .hasMessageContaining("Item")
                .hasMessageContaining("composed before they are staged");
    }

    @Test
    void update_rejects_duplicate_id_already_added() {
        // GIVEN
        var changes = new PersistableChanges<Id<Item>, Item>();
        var item = createItem();
        changes.add(item);

        // WHEN / THEN
        assertThatThrownBy(() -> changes.update(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered for addition")
                .hasMessageContaining("Item")
                .hasMessageContaining("composed before they are staged");
    }

    @Test
    void update_rejects_duplicate_id_already_updated() {
        // GIVEN
        var changes = new PersistableChanges<Id<Item>, Item>();
        var item = createItem();
        changes.update(item);

        // WHEN / THEN
        assertThatThrownBy(() -> changes.update(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered for update")
                .hasMessageContaining("Item")
                .hasMessageContaining("composed before they are staged");
    }

    @Test
    void additions_map_is_unmodifiable() {
        // GIVEN
        var changes = new PersistableChanges<Id<Item>, Item>();
        changes.add(createItem());

        // WHEN / THEN
        assertThatThrownBy(() -> changes.additions().put(Id.random(Item.class), createItem()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void updates_map_is_unmodifiable() {
        // GIVEN
        var changes = new PersistableChanges<Id<Item>, Item>();
        changes.update(createItem());

        // WHEN / THEN
        assertThatThrownBy(() -> changes.updates().put(Id.random(Item.class), createItem()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
