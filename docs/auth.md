# Authentication, Organisations, Invitations & Mail

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

## Authentication & Authorization

- **Session-cookie auth** via Spring Security (`config/SecurityConfig`). Users are `app_user` rows
  (email + BCrypt `password_hash` + full name + phone) with a set of **permission strings**
  (`app_user_permission`). Permission strings are used **directly as Spring Security authorities**.
- **Permissions are per-organisation** (V37), except `GLOBAL_ADMIN`. Defined as constants in
  `model/Permissions.java` (`GLOBAL` / `PER_ORGANISATION` sets) and mirrored in the frontend
  `api/types.ts` as `GLOBAL_PERMISSIONS` / `ORGANISATION_PERMISSIONS`:
  - `ORG_ADMIN` — "Organisation Admin": organisation-level administration (the Admin Actions
    screen), seeing the organisation's members, **inviting** users to it, removing them, and setting
    their permissions **within it**. This is the *only* permission that grants any of that — a
    separate `USERS_EDIT` ("invite users") existed briefly but granted nothing and was dropped in
    V38. Note what it does *not* grant: creating or editing an account, or attaching an existing
    one — see *Invitations* in this file
  - `PARTS_EDIT` — "Add/edit parts"
  - `GLOBAL_ADMIN` — **global**: add/edit organisations and user accounts, switch into any
    organisation (including the template), and implicitly hold **every** per-organisation
    permission everywhere. That implication is what makes a newly created, memberless organisation
    usable at all (`AppUser.permissionsIn`)
- **Authorities are recomputed from the DB on every request**, for the organisation in force, by
  `config/OrganisationAuthoritiesFilter` (registered `addFilterAfter(SecurityContextHolderFilter)`
  in the session chain, so it runs after the context is loaded and before authorization). It sets
  `global + permissionsIn(currentOrg)` on the `SecurityContextHolder` and deliberately **does not**
  save the context back — the authority set is derived state, valid for one request and one
  organisation; persisting it would re-freeze it. **This is what keeps every existing
  `@PreAuthorize("hasAuthority('…')")` working unchanged**, and what makes permission edits take
  effect immediately rather than at the target user's next login.
  - `AppUserDetailsService` still grants only the *global* permissions at authentication time — the
    per-organisation set is unknown until an organisation is in force.
  - `service/PermissionService.applyAuthorities` re-issues and re-saves the `Authentication`
    (`SecurityContextRepository.saveContext` — required in Spring Security 6, mutating the held
    context is not persisted). It is still called at login (`AuthController`) and on every switch
    (`ProfileController`) because those requests build their response *after* the filter has run.
  - **Why the filter exists**: authorities used to be frozen in the session, which lives for a
    7-day sliding window. A permission granted, revoked, or *introduced by a migration* stayed
    invisible until the user next logged in — sessions created before V37 could never carry
    `ORG_ADMIN`, so their holders were denied the very screens they administer while `/auth/me`
    (which reads permissions live) showed them the navigation. Anything deriving access from the
    session rather than the database will reintroduce this.
- **Login flow**: `POST /api/auth/login` runs the `AuthenticationManager`, persists the
  `SecurityContext` to the HTTP session via `HttpSessionSecurityContextRepository`, returns the
  `UserDTO`. `POST /api/auth/logout` invalidates the session. `GET /api/auth/me` returns the current
  user (401 if anonymous). Auth is loaded by `AppUserDetailsService` (find by email → authorities).
- **Session persistence**: sessions are stored in PostgreSQL via `spring-session-jdbc`
  (`spring.session.store-type: jdbc`, schema owned by Flyway V16 with
  `spring.session.jdbc.initialize-schema: never`), so logins survive an app restart. The timeout is a
  **7-day sliding idle window** (`server.servlet.session.timeout: 7d`) — each request resets it;
  Spring Session reaps expired rows hourly.
- **Enforcement**:
  - All `/api/**` requires an authenticated session **except** `/api/auth/login`, `/api/settings`,
    `/api/invitations/token/**` (answering an invitation — see Invitations) and swagger / api-docs. Static SPA assets + the client-router fallback are public.
  - Specific mutations are gated with method security (`@EnableMethodSecurity` +
    `@PreAuthorize("hasAuthority('…')")`): part mutations (create/update/delete, image
    upload/from-url/delete, quick-add, auto-categorize, OctoPart search/apply) require `PARTS_EDIT`;
    all `/api/users` endpoints require `ORG_ADMIN` (except account create/edit/delete, which require
    `GLOBAL_ADMIN`). `/api/profile/**` (self-service settings) and
    `/api/parts/octopart/usage` are authenticated-only (no specific permission).
  - **Not yet gated** (authenticated-only, no specific permission): categories, locations, specs,
    stock-entry mutations — easy to tighten by adding `@PreAuthorize`.
- **CSRF is disabled** for the API (token-style JSON API; SameSite cookie). Unauthenticated/forbidden
  API calls return JSON `{"error": …}` with status 401/403 (custom entry point / access-denied
  handler) so the SPA can react. `CorsConfig` sets `allowCredentials(true)` so the dev Vite proxy
  origin can send the cookie.
- **Frontend**: `auth/AuthContext` (`AuthProvider` + `useAuth`) loads `/auth/me` on mount and exposes
  `user`, `hasPermission(key)`, `login`, `logout`. `App.tsx` wraps routes in `AuthProvider`, exposes a
  public `/login`, and guards app routes with `RequireAuth` (redirect to `/login`, preserving `from`).
  The sidebar (`components/Layout`) hides permission-gated nav (Users) and shows the current user +
  logout. The Parts page hides New/Edit/Delete/categorize controls without `PARTS_EDIT`.
- **`SecurityConfig` is `@ConditionalOnWebApplication(SERVLET)`; the beans live in
  `SecurityBeansConfig`.** `@EnableWebSecurity` and the `MvcRequestMatcher`-based filter chains need
  Spring MVC, which is absent under the CLI profiles that set `web-application-type: none` (`import`,
  `datasheets`) — without the guard those runners die at startup on a missing
  `mvcHandlerMappingIntrospector`, which is what silently broke the documented Partsbox import
  command. `PasswordEncoder`, `SecurityContextRepository` and `AuthenticationManager` are therefore
  held in a separate always-on `SecurityBeansConfig`: none of them touch MVC, and all are needed in a
  non-web context (the first three by `InvitationService`/`PrintDaemonService`/`AdminUserService` and
  `PermissionService`; `AuthenticationManager` by `AuthController`, which is still component-scanned
  with no web server since `@RestController` is a `@Component` and only the MVC *infrastructure*
  disappears). Adding a bean to `SecurityConfig` that a plain `@Service` depends on will break the
  CLI profiles again.
- **Bootstrap admin** (seeded by migration V10): `admin@clele.local` / `admin` with both permissions.
  **Change this password after first login** (via the Users screen). To regenerate the seed hash use a
  BCrypt hash of the new password (Spring's `BCryptPasswordEncoder`, or `htpasswd -bnBC 10 "" <pw>`).

## Organisations (multi-tenancy)

- **The tenant boundary.** Every `part`, `category`, `location`, `spec_definition`, `tag` and
  `project` carries an `organisation_id` (V36), and so does `part_attachment` (V46 — it used to
  derive one through `part_id`, which stopped holding once several parts could share a row).
  `stock_entry`, `stock_movement`, `part_stock_threshold`, `part_attachment_link`, `project_part`,
  `project_stock`, `part_tag` and `category_spec` deliberately **do not** — they derive their
  organisation through `part_id`/`location_id`/`project_id`, so there is nothing that can drift out
  of sync.
- **`service/CurrentOrganisationService`** is the counterpart to `CurrentUserService` and the single
  source of the tenant: `current()`/`currentId()` read the `currentOrganisationId` **HTTP session
  attribute** (persisted by Spring Session JDBC), falling back to `app_user.last_organisation_id`
  and then the user's first membership; `switchTo(id)` sets both session and the remembered default;
  `selectable()` returns the user's memberships, or **every** organisation for a `GLOBAL_ADMIN`.
  It has **no fallback outside a request** — background jobs
  (`PartCategorizationService`, which captures the id on the request thread in `start()`) and the
  Partsbox importer (`resolveImportOrganisation()`) resolve an organisation explicitly instead.
- **The pattern for scoping** is uniform: inject `CurrentOrganisationService`, pass `currentId()`
  into the repository, stamp `organisation` on create, and load single entities via a
  `findByIdAndOrganisationId` that reports a cross-organisation id as **404, not 403** (another
  tenant's data does not exist as far as this one is concerned).
- **Uniqueness is per-organisation**: `part_number`, `spec_definition.json_name` and
  `LOWER(tag.name)` are composite-unique with `organisation_id`. `app_user.email` stays global.
- **The Template organisation** (`organisation.is_template`, a flag rather than a name — they get
  renamed) holds a blueprint taxonomy. `OrganisationService.create` clones its categories (parents
  first, remapping `parent`), spec definitions, tags and `category_spec` links into the new
  organisation; parts, locations, stock and projects are never cloned. Only a `GLOBAL_ADMIN` may
  select it. `delete` refuses the template and any organisation still holding parts/locations/projects.
- **API**: `GET /api/organisations/selectable` (authenticated — drives the switcher),
  `GET/POST/PUT/DELETE /api/organisations` (`GLOBAL_ADMIN`), `PUT /api/profile/organisation`
  `{organisationId}` (switch). `/auth/me` returns `currentOrganisationId`/`Name` +
  `selectableOrganisations` so the sidebar renders in one round trip (`UserService.toCurrentUserDTO`,
  which also blanks a `lastLocation` belonging to another organisation).
- **Two user screens, deliberately separate.** `UserService`/`UserController` (`/api/users`, the
  **Users** screen) is organisation-scoped: it lists only members of the organisation in force and
  reports/edits only their permissions *there* — that is exactly an `ORG_ADMIN`'s reach.
  `AdminUserService`/`AdminUserController` (`/api/admin/users`, the **All Users** screen) is the
  `GLOBAL_ADMIN` view and crosses every boundary: all accounts, all memberships, all
  per-organisation permissions — and it is the **only** place an account is created, edited or
  deleted (`POST`/`PUT`/`DELETE /api/admin/users`; `POST /api/users` and `PUT/DELETE /api/users/{id}`
  were removed). They are not merged precisely because the first exists to *contain*
  an Organisation Admin, and one over-wide method in a shared controller would silently undo that.
  `AdminUserDTO` carries `memberships[]` (`UserMembershipDTO`: organisation + permissions +
  `implied`); `implied` is true when the permissions come from `GLOBAL_ADMIN` rather than stored
  grants, so the UI renders them read-only (editing them would change nothing).
  - Guardrails in `AdminUserService`: removing a membership also clears the permissions held there
    (otherwise re-adding silently restores access); the **last** organisation cannot be removed
    (409 — delete the account instead); and you cannot strip your **own** `GLOBAL_ADMIN`, since this
    screen is the only place it can be granted and the UI could not undo it; and you cannot delete
    your **own** account. `create` requires at least one organisation — an account in none can sign
    in and see nothing.
- **Frontend**: the switcher lives in the sidebar footer above the current user
  (`components/Layout.tsx`) and again on My Account; both **reload the page** after switching —
  every page fetches on mount, so only a full reload guarantees no stale cross-organisation data.
  `pages/Organisations.tsx` is the `GLOBAL_ADMIN` management screen; `pages/Users.tsx` lists members
  and invitations and holds the Invite dialog; `pages/AllUsers.tsx` is the installation-wide **All Users**
  screen (`GLOBAL_ADMIN`, route `/all-users`) — a table of every account with its organisations,
  and a per-user panel editing account details, global permissions, memberships and the permissions
  within each. Membership and permission changes save **per click** (one call each, since they are
  independent facts about different organisations); account details keep an explicit Save.

## Invitations

How an Organisation Admin brings someone in — and the **only** way they can. They cannot create an
account (an email is unique installation-wide, so that is `GLOBAL_ADMIN` on the All Users screen)
and they cannot attach an existing one by email either: that would let one organisation's admin
conscript another's user without their knowledge. They invite an address; the invitee decides.

- **`organisation_invitation`** (V39) holds email + organisation + inviting user + status
  (`InvitationStatus`: PENDING/ACCEPTED/DECLINED/REVOKED) + `expires_at`, with the permissions the
  invitee will hold on acceptance in `organisation_invitation_permission`. The `token` (32 random
  bytes, URL-safe base64) is the **whole credential** on the mailed link, hence single-use and
  expiring (`app.mail.invitation-expiry-days`, default 14).
- **Two controllers, deliberately split** the same way the two user screens are:
  `InvitationController` (`/api/invitations`, class-level `ORG_ADMIN`) is the inviting side —
  list / `lookup?email=` / create / revoke, all scoped to the organisation in force.
  `InvitationAccessController` (`/api/invitations/token/**`) is the invitee's side and is
  **`permitAll`** in `SecurityConfig`, because whoever follows the link may have no account at all.
  A public method inside the `ORG_ADMIN` controller would be one annotation away from a mistake.
- **`lookup`** answers "who is this address?" for the invite dialog (exists / full name / already a
  member / already invited) so the admin can see they are inviting the person they meant. It is
  readable by any Organisation Admin for an arbitrary address, so it reveals only the display name.
- **Accepting**: `InvitationService.accept` adds the membership and applies the invited permissions.
  If no account exists it creates one first, requiring full name, phone **and** a password (without
  one the account cannot log in). For an **existing** account the request body is *ignored entirely*
  — the token proves control of a mailbox, which is enough to add a membership and nowhere near
  enough to rewrite someone's name or password.
- **Mail is optional.** `MailService` composes the message and hands it to the configured provider
  (see *Outgoing Mail* in this file); with none configured it logs the link instead and reports
  `mailSent: false`, and the invite dialog then shows the link so the admin can pass it on. A send
  failure never fails the invitation — the row is valid and the link works. It also reports
  **`mailError`**, a sentence saying *which* failure it was — no provider configured, or a
  configured one that refused the message (with the server's own wording, e.g. `525 5.7.1
  Unauthorized IP address`) — shown verbatim under the link. Guessing "no mail server configured"
  at a server that is configured and merely rejecting the sending IP costs an afternoon. Set
  `APP_BASE_URL` when
  the app sits behind a proxy that rewrites the host — otherwise the link is derived from the
  request that created the invitation.
- **Frontend**: `pages/Users.tsx` has the **Invite user** dialog (email with a debounced
  `lookup` shown beside it, plus the per-organisation permission checkboxes) and a table of every
  invitation sent, with Withdraw on the outstanding ones. `pages/AcceptInvitation.tsx` is the
  invitee's page at the **public** route `/invite/:token` (registered outside `RequireAuth` in
  `App.tsx`); it asks for name/phone/password only when the invitation reports `newAccount`.

## Outgoing Mail

Mail delivery is behind a provider interface so the email service can be swapped **by
configuration, never by code**. Package `com.clele.parts.mail`:

- **`MailProvider`** — the API: `name()` (the config name), `isConfigured()`, `send(EmailMessage)`,
  throwing `MailSendException`. **`EmailMessage`** is the provider-neutral message (from + fromName,
  to, subject, text, optional html) — `EmailMessage.plain(...)` for the common case.
- **`MailProviderRegistry`** collects every `MailProvider` bean and returns the one named by
  `app.mail.provider`. An **unknown name fails at startup** — falling back silently would mean a
  typo sends mail through the wrong account, or not at all. `none` disables sending. A selected but
  *unconfigured* provider is not an error: `active()` returns empty and `MailService` logs the mail
  (including the invitation link) instead — that is what makes a fresh install and local dev work.
- **Implementations**: `SmtpMailProvider` (`smtp`, the default — Spring's `JavaMailSender`,
  configured under `spring.mail.*`; unconfigured while `spring.mail.host` is blank) and
  `MailerSendMailProvider` (`mailersend` — the MailerSend HTTP API, `POST {base-url}/email` with a
  Bearer token; **202 Accepted** means queued, and its rejection body is reported through verbatim
  because unverified-domain/suppression/quota errors are only fixable at MailerSend).
- **Adding a provider** = one `@Component implements MailProvider` + its settings under
  `app.mail.<name>`. Nothing else changes; `MailService` knows what a mail *says*, never how it
  travels.
- **Config** (`app.mail.*`): `provider` (`MAIL_PROVIDER`, default `smtp`), `from` (`MAIL_FROM`),
  `from-name` (`MAIL_FROM_NAME`), `invitation-expiry-days`, and
  `mailersend.api-key` (`MAILERSEND_API_KEY`) / `mailersend.base-url`. SMTP still takes
  `MAIL_HOST`/`MAIL_PORT`/`MAIL_USERNAME`/`MAIL_PASSWORD` under `spring.mail.*`.
  MailerSend requires the `from` address to be on a domain verified in the MailerSend account.
