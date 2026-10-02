-- Explicit LOCAL DEMO registration, never loaded by Flyway or offline startup.
-- Review the code/name/environment before applying. An existing code is not
-- overwritten; do not use this demo inventory as a production discovery claim.
INSERT INTO cmdb_resource
  (resource_code, resource_type, name, environment, status, owner_user_id,
   description, attributes_json)
SELECT 'OBS-OTEL-COLLECTOR', 'MIDDLEWARE', 'OpsPilot Compose Trace Collector',
       'DEVELOPMENT', 'RUNNING', NULL,
       'Optional local Compose Collector trace pipeline; no automatic human assignment',
       '{"component":"otel-collector","signal":"traces","scope":"optional-compose-demo"}'
WHERE NOT EXISTS (SELECT 1 FROM cmdb_resource WHERE resource_code = 'OBS-OTEL-COLLECTOR');
