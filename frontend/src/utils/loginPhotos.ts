/**
 * The photos shown on the unauthenticated pages (login, invitation acceptance).
 *
 * Every image in `src/assets/login` is a candidate: dropping a file in that directory adds it to
 * the rotation and deleting it removes it — there is no list to keep in sync, the glob below is
 * resolved by Vite at build time. See `src/assets/login/README.md`.
 */
const loginPhotos = Object.entries(
  import.meta.glob<string>('../assets/login/*.{jpg,jpeg,png,webp,avif}', {
    eager: true,
    query: '?url',
    import: 'default',
  }),
)
  .sort(([a], [b]) => a.localeCompare(b))
  .map(([, url]) => url);

/** A random photo, or undefined when the directory is empty. */
export function pickLoginPhoto(): string | undefined {
  if (loginPhotos.length === 0) return undefined;
  return loginPhotos[Math.floor(Math.random() * loginPhotos.length)];
}
