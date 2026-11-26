-- The demo data of the sync does not carry a name of a person any more. The
-- script that seeded it is never edited (the checksum of an applied migration
-- is fixed), so the rows are corrected here.
UPDATE ub_sync_entity
SET entity_id = REPLACE(entity_id, 'SUFEHMI', 'JANEDOE'),
    data = REPLACE(data, 'SUFEHMI', 'JANEDOE')
WHERE entity_id LIKE '%SUFEHMI%' OR data LIKE '%SUFEHMI%';
