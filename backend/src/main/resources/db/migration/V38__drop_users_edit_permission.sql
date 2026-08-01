-- V38: remove the USERS_EDIT permission.
--
-- USERS_EDIT ("invite users") was carried over from the single-tenant permission set and, since
-- V37 made permissions per-organisation, it granted nothing: seeing the member list, adding a
-- member and changing a member's permissions are all gated on ORG_ADMIN. Rather than keep a
-- checkbox that does nothing until an invitation flow exists, the permission is dropped —
-- organisation administration is exactly ORG_ADMIN.
--
-- No rights are lost: V37 already granted ORG_ADMIN to every USERS_EDIT holder, so everyone who
-- could administer an organisation still can.

DELETE FROM app_user_organisation_permission WHERE permission = 'USERS_EDIT';
DELETE FROM app_user_permission WHERE permission = 'USERS_EDIT';
