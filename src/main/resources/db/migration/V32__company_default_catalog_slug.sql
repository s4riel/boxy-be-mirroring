-- =============================================================================
-- V32__company_default_catalog_slug.sql: Default public catalog URL
-- The seeded company (id 1, LatinaTools) is published at /catalogo/latinaTools.
-- V31 already ran everywhere with the placeholder 'boxy', so it is renamed here instead of editing V31.
-- Only a company still on the placeholder (or without a slug) is touched; a slug someone chose by hand is left alone.
--
-- Also self-heals a database where V31 is recorded as applied but `companies.slug` is missing:
-- the column is created only when absent, so on a healthy database this step does nothing.
-- (Portable on MySQL and MariaDB, which do not share `ADD COLUMN IF NOT EXISTS`.)
-- =============================================================================

SET @has_slug := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'companies' AND COLUMN_NAME = 'slug');
SET @ddl := IF(@has_slug = 0,
               'ALTER TABLE companies ADD COLUMN slug VARCHAR(60) NULL AFTER name, ADD CONSTRAINT uk_companies_slug UNIQUE (slug)',
               'SELECT 1');
PREPARE add_slug FROM @ddl;
EXECUTE add_slug;
DEALLOCATE PREPARE add_slug;

UPDATE companies SET slug = 'latinaTools' WHERE id = 1 AND (slug IS NULL OR slug = 'boxy');
