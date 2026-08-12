# Login page photos

Every image file in this directory is a candidate for the illustration on the left half of the
login page. One is picked at random each time the page loads (`frontend/src/pages/Login.tsx`).

## Adding a photo

Drop the file in this directory and rebuild the frontend. That is all — there is no list to
maintain: `Login.tsx` discovers the files at build time with Vite's `import.meta.glob`, so the
set of files *is* the configuration.

Removing a photo is the same in reverse: delete the file.

## What to drop in

- **Formats**: `.jpg`, `.jpeg`, `.png`, `.webp`, `.avif` (extensions outside this list are
  ignored — extend the glob in `Login.tsx` if you need another one).
- **Orientation**: the photo is used as a `bg-cover bg-center` background on a tall half-screen
  panel, so portrait or roughly square crops work best; wide landscape shots get cropped hard.
- **Size**: keep files a few hundred KB. They are bundled into the app and the login page is the
  first thing an unauthenticated visitor downloads. To compress a large original:

  ```sh
  convert big-photo.png -quality 82 -sampling-factor 4:2:0 -strip login-photo.jpg
  ```

  A ~1400x1100 photo lands around 200 KB that way.
- The bottom of the panel carries white text over a dark gradient, so anything busy or bright
  down there will fight the caption.
