-- ===========================================================================
-- Demo data so the app is usable the moment it boots.
--
-- Fixed UUIDs (not random) so the README, the load test and the frontend can
-- all refer to the same ids. Everything is scheduled relative to "today" in
-- the resource's timezone, so the demo never goes stale.
-- ===========================================================================

INSERT INTO tenants (id, slug, name) VALUES
    ('11111111-1111-1111-1111-111111111111', 'demo-clinic', 'Sunrise Physiotherapy');

INSERT INTO resources (id, tenant_id, name, timezone, opening_time, closing_time, slot_minutes) VALUES
    ('22222222-2222-2222-2222-222222222221', '11111111-1111-1111-1111-111111111111',
     'Dr. Mehta - Physio Room A', 'Asia/Kolkata', '09:00', '17:00', 30),
    ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111',
     'Dr. Rao - Physio Room B',   'Asia/Kolkata', '10:00', '18:00', 45);

INSERT INTO customers (id, tenant_id, name, email, phone) VALUES
    ('33333333-3333-3333-3333-333333333301', '11111111-1111-1111-1111-111111111111', 'Ishita Sharma',  'ishita@example.com',  '+91-9000000001'),
    ('33333333-3333-3333-3333-333333333302', '11111111-1111-1111-1111-111111111111', 'Rohan Verma',    'rohan@example.com',   '+91-9000000002'),
    ('33333333-3333-3333-3333-333333333303', '11111111-1111-1111-1111-111111111111', 'Meera Iyer',     'meera@example.com',   '+91-9000000003'),
    ('33333333-3333-3333-3333-333333333304', '11111111-1111-1111-1111-111111111111', 'Arjun Nair',     'arjun@example.com',   '+91-9000000004'),
    ('33333333-3333-3333-3333-333333333305', '11111111-1111-1111-1111-111111111111', 'Kavya Reddy',    'kavya@example.com',   '+91-9000000005'),
    ('33333333-3333-3333-3333-333333333306', '11111111-1111-1111-1111-111111111111', 'Dev Malhotra',   'dev@example.com',     '+91-9000000006');

-- Three confirmed appointments today in Room A.
INSERT INTO bookings (tenant_id, resource_id, customer_id, starts_at, ends_at, status, origin, confirmed_at)
VALUES
    ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222221', '33333333-3333-3333-3333-333333333301',
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '10:00') AT TIME ZONE 'Asia/Kolkata'),
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '10:30') AT TIME ZONE 'Asia/Kolkata'),
     'CONFIRMED', 'DIRECT', now()),
    ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222221', '33333333-3333-3333-3333-333333333302',
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '11:00') AT TIME ZONE 'Asia/Kolkata'),
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '11:30') AT TIME ZONE 'Asia/Kolkata'),
     'CONFIRMED', 'DIRECT', now()),
    ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222221', '33333333-3333-3333-3333-333333333303',
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '14:00') AT TIME ZONE 'Asia/Kolkata'),
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '14:30') AT TIME ZONE 'Asia/Kolkata'),
     'CONFIRMED', 'DIRECT', now());

-- Three people waiting for anything that opens up in Room A this morning.
-- Arjun has priority 1, so he is offered first even though Kavya joined earlier.
INSERT INTO waitlist_entries (tenant_id, resource_id, customer_id, window_start, window_end, priority)
VALUES
    ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222221', '33333333-3333-3333-3333-333333333304',
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '09:00') AT TIME ZONE 'Asia/Kolkata'),
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '13:00') AT TIME ZONE 'Asia/Kolkata'), 1),
    ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222221', '33333333-3333-3333-3333-333333333305',
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '09:00') AT TIME ZONE 'Asia/Kolkata'),
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '13:00') AT TIME ZONE 'Asia/Kolkata'), 0),
    ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222221', '33333333-3333-3333-3333-333333333306',
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '09:00') AT TIME ZONE 'Asia/Kolkata'),
     (((now() AT TIME ZONE 'Asia/Kolkata')::date + TIME '17:00') AT TIME ZONE 'Asia/Kolkata'), 0);
