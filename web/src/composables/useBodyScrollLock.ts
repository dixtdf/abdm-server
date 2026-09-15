import { onBeforeUnmount, watch, type Ref } from 'vue'

/** Locks page scrolling while a modal, drawer or sheet is open. */
export function useBodyScrollLock(open: Ref<boolean>): void {
  if (typeof document === 'undefined') return

  watch(
    open,
    (value) => {
      document.body.style.overflow = value ? 'hidden' : ''
    },
    { immediate: true },
  )

  onBeforeUnmount(() => {
    document.body.style.overflow = ''
  })
}
