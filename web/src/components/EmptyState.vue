<script setup lang="ts">
defineProps<{
  title: string
  description?: string
  compact?: boolean
}>()
</script>

<template>
  <div class="empty" :class="{ 'empty--compact': compact }">
    <div class="empty__icon" aria-hidden="true">
      <slot name="icon" />
    </div>
    <p class="empty__title">{{ title }}</p>
    <p v-if="description" class="empty__description">{{ description }}</p>
    <div v-if="$slots.actions" class="empty__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-8) var(--space-6);
  text-align: center;
  background: var(--surface);
  border: 1px dashed var(--border);
  border-radius: var(--radius-xl);
}

.empty--compact {
  padding: var(--space-5) var(--space-4);
}

.empty__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  margin-bottom: var(--space-2);
  border-radius: var(--radius-large);
  background: var(--surface-secondary);
  color: var(--text-muted);
}

.empty__title {
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--text-primary);
}

.empty__description {
  max-width: 42ch;
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.empty__actions {
  margin-top: var(--space-3);
}
</style>
