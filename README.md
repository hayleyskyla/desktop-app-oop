# AgriTourism Philippines — Desktop App

Kotlin **Compose for Desktop** + **SQLite3** port of the original React/Electron app.
Same screens and flows: opening catalog, login/sign-up (Tourist / Farm) with separately configured admin access, dashboards,
farm tours & fruit-picking reservations, marketplace with stock limits, 4-step checkout,
bookings (tickets, receipts, reschedule, cancel, delete, gate check-in, guide assignment),
farmer orders & produce catalog, customer purchase history, farmer stories, notifications with
1-day attendance confirmation, and per-account farm wishlists.

## Run

Requires JDK 17+.

```bash
./gradlew run                 # launch the app
./gradlew packageDistributionForCurrentOS   # build an installer (msi/exe/dmg/deb)
./gradlew releaseArtifacts    # installer(s) + seeded database in build/release
```

## Releases

Every push to `main` runs `.github/workflows/windows-release.yml`, which tests the app,
builds the Windows installer plus the seeded database, and publishes a new GitHub release
with an automatically incremented `vX.Y.Z` tag. Bump the base `version` in
`build.gradle.kts` (for example to `1.2.0`) when you want a minor or major release;
otherwise the patch number is bumped automatically. Releases can also be triggered
manually from the **Actions** tab.

### Code signing

Windows SmartScreen warns about new unsigned executables ("isn't commonly downloaded").
Releases are set up to be signed for free through [SignPath Foundation](https://signpath.org/)
once the project is approved. Until `SIGNPATH_API_TOKEN` is configured, the workflow still
publishes unsigned builds together with SHA-256 checksums.

To enable signing:

1. Apply at <https://signpath.org/apply> and link this repository.
2. Install the [SignPath GitHub App](https://github.com/apps/signpath) for the repository.
3. In SignPath, create an **Open Source Code Signing** project with an artifact configuration
   named `installer` using [`.signpath/artifact-configuration.xml`](.signpath/artifact-configuration.xml).
4. Add the repository secret `SIGNPATH_API_TOKEN`.
5. Add repository variables `SIGNPATH_ORGANIZATION_ID`, `SIGNPATH_PROJECT_SLUG`, and
   `SIGNPATH_SIGNING_POLICY_SLUG`.

The workflow signs the installer, verifies the Authenticode signature, and only then publishes
the release. If Windows still shows a SmartScreen warning, check the SHA-256 hash against
`SHA256SUMS.txt` before choosing **More info → Run anyway**.

## Data

- SQLite file: `~/.agritourism/agritourism.db` (override with `AGRITOURISM_DB=/path/file.db`).
- First launch seeds it from `src/main/resources/seed.json` (farmers, tours, orchards,
  products, bookings, orders, reviews, notifications, cart, favorites, users).
- Delete the `.db` file to reset to the original sample data.
- The app starts signed out. Demo accounts (password `password`): `maria.santos@gmail.com` (tourist)
  and `juan.delacruz@sunnyvalefarm.ph` (farmer). Passwords are stored as salted PBKDF2 hashes.
  Public sign-up offers Tourist and Farm. Login offers only Farm and Tourist, rejects account-type
  mismatches, and routes Admin accounts by their stored role without exposing Admin in the selector.
- Admin credentials are never seeded or shown in the interface. Set `AGRITOURISM_ADMIN_EMAIL` and
  `AGRITOURISM_ADMIN_PASSWORD` in the app process environment before launch; the password must be
  at least 12 characters. On a new database this creates one Admin account. For an existing
  database, this updates an Admin with the configured email or creates a separate Admin if that
  email is unused; existing Admin accounts and their records remain intact. A legacy Admin still
  using the demo password is disabled until securely
  configured. Do not put credential values in source control or logs.
- Marketplace orders use Cash on Delivery only and have Pending, Confirmed, or Cancelled status.
  Stock reservation and order persistence are one SQLite transaction; successful purchases and
  status changes notify the account that placed the order. The customer dashboard includes My
  Purchases, scoped to the signed-in tourist. Tour, fruit-picking and walk-in bookings use Pay at
  the Farm only; booking and order persistence enforce these transaction-specific methods. New
  order lines save product-image and seller-name snapshots for My Purchases; historical lines are
  backfilled only when their catalog product can be matched confidently, otherwise the UI uses a
  neutral image and labels the seller as unavailable. Farmers can confirm or cancel pending orders;
  cancelling restores reserved stock once and notifies the buyer. Admins may also cancel confirmed
  orders.
- Access is role-based: tourists see only their own bookings, orders, cart, favorites and notifications;
  farmers manage only their own farm's bookings, orders and produce; admins manage platform data.
  SQL Database Studio is not available through the application interface. Farmer sign-up creates a new farm profile linked to the account. Tours, orchards and
  products not linked to any farmer account are managed by admins.
- Tourists can review a booking after their visit (checked in or date passed), once per booking; the
  rating is added to that tour's or orchard's average.
- No double bookings: a customer can hold only one active booking per date and time slot. This is
  enforced by a unique index in SQLite as well as in the app.
- New tour bookings must use a date from today through December 31 of next year. The calendar
  disables out-of-window dates, and SQLite validates new or changed bookings while preserving
  historical records.
- Tourists may update the delivery address or cancel a marketplace order only while it is Pending.
  Farmers may confirm or cancel Pending orders; receipt printing is limited to the owning farmer.
- Uploaded destination photos remain in `src/main/resources/images/Tour/` and are referenced in
  place without renaming or copying. Suggested prices remain explicitly labeled as estimates rather
  than official rates, and an Admin can edit prices and listing visibility. Booking availability is
  controlled separately and requires a valid operating-hours range and time slot; SQLite rejects
  bookings outside the configured schedule. The additive startup migration preserves existing tour
  IDs and booking history and only updates a record when its name exactly matches the bundled tour.
- New farmer listings are published immediately. Admins can publish or unpublish tour, orchard, and
  product listings and enable or disable farmer and tourist accounts without deleting their history.
  Product-order history is retained when legacy delivery-stage statuses are migrated to Pending,
  Confirmed, or Cancelled; delivery tracking is no longer exposed in the interface.
- Other farm activities use the existing tour and booking records rather than a separate booking
  system. The Admin Dashboard includes a cumulative user-registration graph based on persisted
  account creation dates; order totals are not described as collected revenue because payment
  settlements are not recorded.
- The SQLite database and its management operations remain available to the application, but SQL Studio has no menu entry or accessible application route.
- Guests can browse the opening page without an account: clicking a picture opens its full description,
  with a button to go to the login page. Tourist reviews and Help & FAQs (`faqs` table) are at the bottom.
- New accounts show a neutral avatar until a profile photo is chosen. The `users.avatar_url` column stores the saved photo URL; uploaded files are copied to an `uploads/` directory next to the database file (by default `~/.agritourism/uploads/`, or beside the file selected by `AGRITOURISM_DB`). The avatar fallback is visual only and does not populate or overwrite `avatar_url`. Farmers can also change their cover photo, edit their short story and description, and
  add their own tours and fruit-picking experiences. Admins can change any farmer's photo from the dashboard.
- Release builds also attach a pre-seeded `agritourism.db` to the GitHub release. Point
  `AGRITOURISM_DB` at that file to run the app against it.

## Images

All bundled images live in `src/main/resources`:
`farmers/*.png` (farmer & user avatars), `fruit_picking/*.jpg` (orchard photos),
`icons/*.png` (window/app icons), `mockups/*` (design references — **View → Design Mockups Gallery**).
Remote Unsplash photos are cached in `~/.agritourism/image-cache`.

## Layout

```
src/main/kotlin/ph/agritourism/
  Main.kt               window + native menu bar (File/Catalog/View/Account)
  data/Database.kt      SQLite schema, seeding, DAO queries
  model/Models.kt       data classes
  ui/AppState.kt        app state + all actions (ported from App.tsx)
  ui/Shell.kt           header, sidebar dock, page router, notifications drawer, toast
  ui/screens/*          pages
  ui/dialogs/Overlays.kt modals (tour/fruit booking, tickets, receipts, dossiers…)
  util/Printing.kt      print / save-as / printable tickets & receipts
```

## License

MIT — see [LICENSE](LICENSE).
