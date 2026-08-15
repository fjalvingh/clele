# Login page photos

Every image file in this directory is a candidate for the illustration on the left half of the
login page and the invitation-accept page. One is picked at random each time the page loads
(`frontend/src/utils/loginPhotos.ts`, used by `pages/Login.tsx` and `pages/AcceptInvitation.tsx`).

## Adding a photo

Drop the file in this directory and rebuild the frontend. That is all — there is no list to
maintain: `Login.tsx` discovers the files at build time with Vite's `import.meta.glob`, so the
set of files *is* the configuration.

Removing a photo is the same in reverse: delete the file.

## What to drop in

- **Formats**: `.jpg`, `.jpeg`, `.png`, `.webp`, `.avif` (extensions outside this list are
  ignored — extend the glob in `Login.tsx` if you need another one).
- **Orientation**: the photo is used as a `bg-contain bg-center bg-no-repeat` background on a tall
  half-screen panel, so it is always shown whole — nothing is ever cropped. Any aspect ratio works;
  the panel background (`bg-gray-100`, the same colour as the sign-in half) shows through around
  it, so the further the photo is from a tall portrait shape the more of that border you see.
- **Size**: keep files a few hundred KB. They are bundled into the app and the login page is the
  first thing an unauthenticated visitor downloads. To compress a large original:

  ```sh
  convert big-photo.png -quality 82 -sampling-factor 4:2:0 -strip login-photo.jpg
  ```

  A ~1400x1100 photo lands around 200 KB that way.
- Nothing is drawn on top of the photo — the tagline lives above the sign-in card on the other
  half — so you do not need to leave a quiet area anywhere in the frame.
