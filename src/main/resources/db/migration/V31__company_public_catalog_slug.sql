-- =============================================================================
-- V31__company_public_catalog_slug.sql: Public catalog opt-in
-- A company is reachable by anonymous visitors at /catalogo/{slug} only when it has a slug.
-- NULL = public catalog disabled (the default), so no existing tenant is exposed by accident.
-- =============================================================================

ALTER TABLE companies
    ADD COLUMN slug VARCHAR(60) NULL AFTER name,
        ADD CONSTRAINT uk_companies_slug UNIQUE (slug);

-- Development seed: the seeded company (V2) gets a public catalog.
UPDATE companies SET slug = 'boxy' WHERE id = 1;
