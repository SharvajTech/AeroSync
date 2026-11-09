-- ============================================================================
-- AeroSync Stage 1 - reference airports of the operational network.
--
-- Real public reference data (IATA code, name, city, IANA time zone and
-- approximate aerodrome coordinates). Not synthetic.
--
-- The first nine rows are the airports used by the existing GNN/ML models
-- (aerosync-ml/gnn/graph_dataset.py AIRPORT_ENCODE). The other six exist only
-- in the operational graph and are never fed to the GNN.
--
-- airport_id values are deterministic name-based UUIDs
-- (java.util.UUID.nameUUIDFromBytes("aerosync:airport:" + iata_code)) and must
-- match com.aerosync.synthetic.ReferenceAirports; ReferenceAirportsMigrationTest
-- checks this. ON CONFLICT keeps the migration safe if a row already exists.
-- ============================================================================
INSERT INTO airports (airport_id, iata_code, airport_name, city, timezone, latitude, longitude, status)
VALUES
    ('d16bf4e0-1953-355b-babd-56426f6ff492', 'BOM', 'Chhatrapati Shivaji Maharaj International Airport', 'Mumbai', 'Asia/Kolkata', 19.0896, 72.8656, 'OPERATIONAL'),
    ('23644c75-f0f6-382a-ab69-6c2d986a189e', 'DEL', 'Indira Gandhi International Airport', 'Delhi', 'Asia/Kolkata', 28.5562, 77.1000, 'OPERATIONAL'),
    ('5d202b31-74c9-310b-ae98-1944e9919764', 'BLR', 'Kempegowda International Airport', 'Bengaluru', 'Asia/Kolkata', 13.1986, 77.7066, 'OPERATIONAL'),
    ('1929fee2-ac48-398a-b33e-0f6c0bfdc3b6', 'HYD', 'Rajiv Gandhi International Airport', 'Hyderabad', 'Asia/Kolkata', 17.2403, 78.4294, 'OPERATIONAL'),
    ('d29563ef-7fa4-31e3-bca5-dc383a475489', 'CCU', 'Netaji Subhas Chandra Bose International Airport', 'Kolkata', 'Asia/Kolkata', 22.6547, 88.4467, 'OPERATIONAL'),
    ('9dbb1d4a-b064-3bf2-bf89-aa40acc39cab', 'MAA', 'Chennai International Airport', 'Chennai', 'Asia/Kolkata', 12.9941, 80.1709, 'OPERATIONAL'),
    ('eebd5188-f943-36b4-8eb8-ab18b8338a75', 'AMD', 'Sardar Vallabhbhai Patel International Airport', 'Ahmedabad', 'Asia/Kolkata', 23.0772, 72.6347, 'OPERATIONAL'),
    ('0e2e5b2d-f806-3b1c-9f3d-1179d70a3eef', 'GOI', 'Goa International Airport (Dabolim)', 'Goa', 'Asia/Kolkata', 15.3808, 73.8314, 'OPERATIONAL'),
    ('c13dd7bb-d57c-3f9c-83c4-26ae304764b4', 'PNQ', 'Pune International Airport', 'Pune', 'Asia/Kolkata', 18.5821, 73.9197, 'OPERATIONAL'),
    ('0b824427-c37c-359c-941b-9d67b9d16d40', 'COK', 'Cochin International Airport', 'Kochi', 'Asia/Kolkata', 10.1520, 76.4019, 'OPERATIONAL'),
    ('792d985e-71ed-327c-bf40-bd88556ddc47', 'JAI', 'Jaipur International Airport', 'Jaipur', 'Asia/Kolkata', 26.8242, 75.8122, 'OPERATIONAL'),
    ('dfb38d48-419a-3d72-a3cb-6455f9739d76', 'LKO', 'Chaudhary Charan Singh International Airport', 'Lucknow', 'Asia/Kolkata', 26.7606, 80.8893, 'OPERATIONAL'),
    ('e42646d8-c761-38e9-b957-ccf5bdacd972', 'GAU', 'Lokpriya Gopinath Bordoloi International Airport', 'Guwahati', 'Asia/Kolkata', 26.1061, 91.5859, 'OPERATIONAL'),
    ('008e6d74-7dc4-3ed5-9ffc-f355dcff4389', 'NAG', 'Dr. Babasaheb Ambedkar International Airport', 'Nagpur', 'Asia/Kolkata', 21.0922, 79.0472, 'OPERATIONAL'),
    ('7088cd5b-7f20-3008-8b4e-e7dda921b7ab', 'BBI', 'Biju Patnaik International Airport', 'Bhubaneswar', 'Asia/Kolkata', 20.2444, 85.8178, 'OPERATIONAL')
ON CONFLICT (iata_code) DO NOTHING;
