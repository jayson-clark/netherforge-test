-- The project dimension type (dimension_types/<id>.json) a world its scripts or its netherforge.json made was made
-- with, as the server knows it (basic:deep), so the world isn't loaded while the server lacks it. NULL for any other.
ALTER TABLE worlds ADD COLUMN dimension_type TEXT;
