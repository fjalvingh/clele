-- Drop the category -> spec field mapping.
--
-- It was the "these are the fields a part in this category has" list, filled in on the Categories
-- screen and read back to pre-fill the New Part dialog. Both parts of the UI are gone: specs are
-- now put on a part one at a time, searched by name, because what a part carries is decided per
-- part and not by its category (and with ~1000 definitions the category list was as unusable as
-- the full one). Nothing reads the table any more.
DROP TABLE IF EXISTS category_spec;
