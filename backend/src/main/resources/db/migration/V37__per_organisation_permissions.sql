-- V37: permissions become per-organisation.
--
-- Until now a permission was a property of the user, granted everywhere. With organisations that is
-- wrong: being allowed to edit parts in one organisation says nothing about another. From here on
-- every permission except GLOBAL_ADMIN is held per (user, organisation).
--
--   * app_user_permission              — GLOBAL permissions only. In practice just GLOBAL_ADMIN.
--   * app_user_organisation_permission — per-organisation permissions: ORG_ADMIN, USERS_EDIT,
--                                        PARTS_EDIT.
--
-- GLOBAL_ADMIN is deliberately not stored per organisation: it implies every per-organisation
-- permission everywhere, which is also what lets a freshly created (memberless) organisation be
-- populated at all.

CREATE TABLE app_user_organisation_permission (
    user_id         BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    organisation_id BIGINT      NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
    permission      VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, organisation_id, permission)
);

CREATE INDEX idx_app_user_org_permission_org ON app_user_organisation_permission(organisation_id);

-- Carry every existing non-global permission into each organisation the user belongs to. Nobody
-- gains or loses anything they could do before the split: their old global permission applied
-- everywhere, and they were only ever a member of these organisations.
INSERT INTO app_user_organisation_permission (user_id, organisation_id, permission)
SELECT p.user_id, m.organisation_id, p.permission
  FROM app_user_permission p
  JOIN app_user_organisation m ON m.user_id = p.user_id
 WHERE p.permission <> 'GLOBAL_ADMIN'
ON CONFLICT DO NOTHING;

-- Whoever could manage users becomes Organisation Admin of the organisations they are in — that is
-- the closest equivalent of what USERS_EDIT used to mean, and it keeps the Admin screen reachable.
INSERT INTO app_user_organisation_permission (user_id, organisation_id, permission)
SELECT p.user_id, m.organisation_id, 'ORG_ADMIN'
  FROM app_user_permission p
  JOIN app_user_organisation m ON m.user_id = p.user_id
 WHERE p.permission = 'USERS_EDIT'
ON CONFLICT DO NOTHING;

-- app_user_permission now holds global permissions only.
DELETE FROM app_user_permission WHERE permission <> 'GLOBAL_ADMIN';
