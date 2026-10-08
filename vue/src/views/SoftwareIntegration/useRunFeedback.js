import { onBeforeUnmount, ref, watch } from 'vue'

// Each action captures its selection. A later action, navigation (including
// leaving and returning to the same version), or unmount invalidates its message.
export const useRunFeedback = context => {
  const notice = ref(null)
  let generation = 0
  const clear = () => {
    generation += 1
    notice.value = null
  }
  const begin = () => {
    clear()
    const ticket = generation
    const publish = (type, message) => {
      if (ticket === generation) notice.value = { type, message }
    }
    return {
      error: message => publish('danger', message),
      success: message => publish('success', message)
    }
  }
  watch(context, clear, { flush: 'sync' })
  onBeforeUnmount(clear)
  return { notice, begin, clear }
}
