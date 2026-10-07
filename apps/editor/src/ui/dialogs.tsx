/**
 * Modal dialogs: `Modal` for any dialog, drawn with react-aria's
 * `ModalOverlay`/`Modal`/`Dialog` (focus kept inside and given back after,
 * Escape or a click outside closes, the rest of the page inert and hidden
 * from screen readers), and dialogs as promises: `await ask.confirm(...)`,
 * `await ask.prompt(...)`. Native `confirm()`/`prompt()` aren't reliable in
 * every Tauri webview, and these are labelled for Playwright and screen
 * readers.
 */
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Dialog, Heading, Modal as AriaModal, ModalOverlay } from 'react-aria-components'
import { create } from 'zustand'
import { Button } from './Button'
import { cx } from './cx'
import { FormError, FormLabel } from './fields'
import { Spacer } from './layout'
import styles from './Modal.module.css'

interface PromptRequest {
  kind: 'prompt'
  title: string
  message?: string
  label: string
  initial: string
  confirmLabel: string
  /** An error message for invalid input, or null. */
  validate?: (value: string) => string | null
  resolve: (value: string | null) => void
}

interface ConfirmRequest {
  kind: 'confirm'
  title: string
  message: string
  confirmLabel: string
  danger?: boolean
  /** A third choice, e.g. "Don't save". */
  alternative?: string
  resolve: (value: boolean | 'alternative') => void
}

type Request = PromptRequest | ConfirmRequest

const useDialogs = create<{ current: Request | null }>(() => ({ current: null }))

function open(request: Request) {
  useDialogs.setState({ current: request })
}

export const ask = {
  prompt(
    options: Omit<PromptRequest, 'kind' | 'resolve' | 'initial' | 'confirmLabel'> & {
      initial?: string
      confirmLabel?: string
    },
  ): Promise<string | null> {
    return new Promise((resolve) =>
      open({ kind: 'prompt', initial: '', confirmLabel: 'OK', ...options, resolve }),
    )
  },
  confirm(
    options: Omit<ConfirmRequest, 'kind' | 'resolve' | 'confirmLabel'> & { confirmLabel?: string },
  ) {
    return new Promise<boolean | 'alternative'>((resolve) =>
      open({ kind: 'confirm', confirmLabel: 'OK', ...options, resolve }),
    )
  },
}

export function Modal({
  title,
  children,
  onClose,
  footer,
  wide,
}: {
  title: string
  children: ReactNode
  onClose: () => void
  footer?: ReactNode
  wide?: boolean
}) {
  return (
    <ModalOverlay
      isOpen
      isDismissable
      onOpenChange={(open) => !open && onClose()}
      className={styles.backdrop}
    >
      <AriaModal className={cx(styles.modal, wide && styles.wide)}>
        <Dialog className={styles.dialog}>
          <Heading slot="title" className={styles.title}>
            {title}
          </Heading>
          <div className={styles.body}>{children}</div>
          {footer && <div className={styles.footer}>{footer}</div>}
        </Dialog>
      </AriaModal>
    </ModalOverlay>
  )
}

export function DialogHost() {
  const current = useDialogs((s) => s.current)
  if (!current) return null
  const close = () => useDialogs.setState({ current: null })
  if (current.kind === 'prompt') {
    return (
      <PromptDialog
        key={current.title + current.initial}
        request={current}
        onDone={(value) => {
          close()
          current.resolve(value)
        }}
      />
    )
  }
  const done = (value: boolean | 'alternative') => {
    close()
    current.resolve(value)
  }
  return (
    <Modal
      title={current.title}
      onClose={() => done(false)}
      footer={
        <>
          {current.alternative && (
            <Button onClick={() => done('alternative')}>{current.alternative}</Button>
          )}
          <Spacer />
          <Button onClick={() => done(false)}>Cancel</Button>
          <Button
            variant={current.danger ? 'danger' : 'primary'}
            autoFocus
            onClick={() => done(true)}
          >
            {current.confirmLabel}
          </Button>
        </>
      }
    >
      <p>{current.message}</p>
    </Modal>
  )
}

function PromptDialog({
  request,
  onDone,
}: {
  request: PromptRequest
  onDone: (value: string | null) => void
}) {
  const [value, setValue] = useState(request.initial)
  const input = useRef<HTMLInputElement>(null)
  const id = useId()
  const error = request.validate?.(value) ?? null
  useEffect(() => input.current?.select(), [])
  const submit = () => {
    if (!error) onDone(value)
  }
  return (
    <Modal
      title={request.title}
      onClose={() => onDone(null)}
      footer={
        <>
          <Spacer />
          <Button onClick={() => onDone(null)}>Cancel</Button>
          <Button variant="primary" disabled={!!error} onClick={submit}>
            {request.confirmLabel}
          </Button>
        </>
      }
    >
      {request.message && <p>{request.message}</p>}
      <FormLabel htmlFor={id}>{request.label}</FormLabel>
      <input
        id={id}
        ref={input}
        autoFocus
        value={value}
        aria-invalid={!!error}
        onChange={(event) => setValue(event.target.value)}
        onKeyDown={(event) => event.key === 'Enter' && submit()}
      />
      {error && value && <FormError>{error}</FormError>}
    </Modal>
  )
}
