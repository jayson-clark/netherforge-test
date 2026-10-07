/** A small set of 16px stroke icons, inline so the app needs no icon font. */
import { cx } from './cx'
import styles from './Icon.module.css'

const PATHS = {
  play: 'M5 3l8 5-8 5z',
  pause: 'M5 3h2v10H5zM9 3h2v10H9z',
  stop: 'M4 4h8v8H4z',
  // The debugger's: continue (a bar and play), step over (an arc over a dot), into, out of.
  resume: 'M3.5 3v10M7 3l6 5-6 5z',
  stepOver: 'M2.5 9a5.5 5.5 0 0111 0M13.5 5.5V9H10M8 12.5h0',
  stepInto: 'M8 2v7M5 6l3 3 3-3M8 13h0',
  stepOut: 'M8 9V2M5 5l3-3 3 3M8 13h0',
  breakpoint: 'M8 4.5a3.5 3.5 0 110 7 3.5 3.5 0 010-7z',
  save: 'M3 3h8l2 2v8H3zM5 3v3h5V3M5 13V9h6v4',
  saveAll: 'M5 1.5h7l2 2v7.5M2.5 4h7l2 2v8.5h-9zM4.5 4v2.5h4V4M4.5 14.5V11h5v3.5',
  undo: 'M6 4L3 7l3 3M3 7h7a3 3 0 010 6H8',
  redo: 'M10 4l3 3-3 3M13 7H6a3 3 0 000 6h2',
  plus: 'M8 3v10M3 8h10',
  trash: 'M3 5h10M6 5V3h4v2M5 5l1 8h4l1-8',
  edit: 'M3 13l1-3 7-7 2 2-7 7zM10 4l2 2',
  close: 'M4 4l8 8M12 4l-8 8',
  check: 'M3.5 8.5l3 3 6-7',
  chevronRight: 'M6 4l4 4-4 4',
  chevronDown: 'M4 6l4 4 4-4',
  cube: 'M8 2l5 3v6l-5 3-5-3V5zM3 5l5 3 5-3M8 8v6',
  file: 'M4 2h5l3 3v9H4zM9 2v3h3',
  folder: 'M2 4h4l1 1h7v8H2z',
  gear: 'M12.76 6.45 14.47 6.71 14.47 9.29 12.76 9.55 12.46 10.27 13.49 11.67 11.67 13.49 10.27 12.46 9.55 12.76 9.29 14.47 6.71 14.47 6.45 12.76 5.73 12.46 4.33 13.49 2.51 11.67 3.54 10.27 3.24 9.55 1.53 9.29 1.53 6.71 3.24 6.45 3.54 5.73 2.51 4.33 4.33 2.51 5.73 3.54 6.45 3.24 6.71 1.53 9.29 1.53 9.55 3.24 10.27 3.54 11.67 2.51 13.49 4.33 12.46 5.73Z M10 8a2 2 0 11-4 0 2 2 0 014 0z',
  warning: 'M8 2l6 11H2zM8 6v3M8 11v.5',
  error: 'M8 2a6 6 0 100 12A6 6 0 008 2zM6 6l4 4M10 6l-4 4',
  spawn: 'M8 2v8M5 7l3 3 3-3M3 13h10',
  refresh: 'M13 8a5 5 0 11-1.5-3.5M13 3v3h-3',
  code: 'M6 4L2 8l4 4M10 4l4 4-4 4',
  key: 'M8 3l3 5-3 5-3-5z',
  lock: 'M5 7V5a3 3 0 0 1 6 0v2M3.5 7h9v6.5h-9z',
  module: 'M2 3h5v4H2zM9 3h5v4H9zM2 9h5v4H2zM9 9h5v4H9z',
  box: 'M3 3h10v10H3z',
  structure: 'M2 9h6v5H2zM8 9h6v5H8zM5 4h6v5H5z',
  globe: 'M8 2a6 6 0 100 12A6 6 0 008 2zM2 8h12M8 2c2.5 2 2.5 10 0 12M8 2c-2.5 2-2.5 10 0 12',
  leaf: 'M3 13C3 7 7 3 13 3c0 6-4 10-10 10zM3 13l6-6',
  grid: 'M2 3h12v10H2zM2 6.5h12M2 10h12M6 3v10M10 3v10',
  dialog: 'M2 3h12v8H7l-3 3v-3H2zM5 6h6M5 8h4',
  image: 'M2 3h12v10H2zM2 11l4-4 3 3 2-2 3 3M10.5 6a1 1 0 100-.01',
  sound: 'M2 6h3l4-3v10l-4-3H2zM11 5.5a3 3 0 010 5M12.5 4a5 5 0 010 8',
  gem: 'M5 3h6l3 4-6 7-6-7zM2 7h12M6.5 3L8 7l1.5-4M8 7v7',
  recipe: 'M1.5 4.5h7v7h-7zM1.5 8h7M5 4.5v7M10.5 8h4M12.5 6l2 2-2 2',
  arrow: 'M2 8h11M9 4l4 4-4 4',
  arrowLeft: 'M13 8H3M7 4L3 8l4 4',
  arrowRight: 'M3 8h10M9 4l4 4-4 4',
  chevronLeft: 'M10 4L6 8l4 4',
  chevronUp: 'M4 10l4-4 4 4',
  search: 'M7 2.5a4.5 4.5 0 100 9 4.5 4.5 0 000-9zM10.3 10.3L14 14',
  copy: 'M5 5h8v8H5zM3 11V3h8',
  camera: 'M2 5h3l1.5-2h3L11 5h3v8H2zM8 7a2 2 0 100 4 2 2 0 000-4',
  newFile: 'M4 2h5l3 3v9H4zM9 2v3h3M8 7.5v4M6 9.5h4',
  newFolder: 'M2 4h4l1 1h7v8H2zM8 7v4M6 9h4',
  folderOpen: 'M2 4h4l1 1h6v2M2 13l2-6h11l-2 6z',
  collapseAll: 'M3 3h10v10H3zM5.5 8h5',
  list: 'M5 4h9M5 8h9M5 12h9M2 4h.5M2 8h.5M2 12h.5',
  chart: 'M2 13.5h12M4 13V9M7 13V3.5M10 13V7.5M13 13V5.5',
  panelLeft: 'M2 3h12v10H2zM6 3v10',
  panelRight: 'M2 3h12v10H2zM10 3v10',
  panelBottom: 'M2 3h12v10H2zM2 9.5h12',
  more: 'M3.5 8h.5M8 8h.5M12.5 8h.5',
  link: 'M7 9l2-2M6 6.5L4.5 8a2 2 0 003 3L9 9.5M10 9.5L11.5 8a2 2 0 00-3-3L7 6.5',
  slot: 'M3 3h10v10H3zM5 5h6v6H5z',
  sparkles:
    'M6 2l1.2 3.8L11 7l-3.8 1.2L6 12l-1.2-3.8L1 7l3.8-1.2zM12 9l.6 1.4L14 11l-1.4.6L12 13l-.6-1.4L10 11l1.4-.6z',
} as const

export type IconName = keyof typeof PATHS

export function Icon({
  name,
  size = 14,
  className,
}: {
  name: IconName
  size?: number
  className?: string
}) {
  return (
    <svg
      className={cx(styles.icon, className)}
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.4}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={PATHS[name]} />
    </svg>
  )
}
