-- A driver's phone reports its position straight to the trip (POST /trips/{id}/location), not through
-- the signed integration inbox, so it has no inbox message to point at. The column was NOT NULL with a
-- foreign key to the inbox, which made every driver-reported position fail with a 500. Integration-fed
-- positions still must name their inbox message; only the driver's own report may leave it empty.
ALTER TABLE fleet_logistics.fleet_vehicle_locations
    ALTER COLUMN integration_message_id DROP NOT NULL;

ALTER TABLE fleet_logistics.fleet_vehicle_locations
    ADD CONSTRAINT ck_fleet_vehicle_location_provenance
        CHECK (integration_message_id IS NOT NULL OR source_system = 'DRIVER_MOBILE');
