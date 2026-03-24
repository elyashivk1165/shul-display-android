-- Add device_info column to devices table
ALTER TABLE devices ADD COLUMN IF NOT EXISTS device_info JSONB DEFAULT '{}';

-- Create device_logs table
CREATE TABLE IF NOT EXISTS device_logs (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    device_id TEXT NOT NULL,
    level TEXT NOT NULL CHECK (level IN ('INFO', 'WARN', 'ERROR', 'CRASH', 'PING')),
    message TEXT NOT NULL,
    stacktrace TEXT,
    extra JSONB DEFAULT '{}',
    app_version TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_device_logs_device_id ON device_logs(device_id);
CREATE INDEX IF NOT EXISTS idx_device_logs_created_at ON device_logs(created_at DESC);

-- RLS policies for device_logs
ALTER TABLE device_logs ENABLE ROW LEVEL SECURITY;
CREATE POLICY anon_insert_logs ON device_logs FOR INSERT TO anon WITH CHECK (true);
CREATE POLICY anon_select_logs ON device_logs FOR SELECT TO anon USING (true);
CREATE POLICY auth_all_logs ON device_logs FOR ALL TO authenticated USING (true) WITH CHECK (true);

-- Add display_name and notes to devices
ALTER TABLE devices ADD COLUMN IF NOT EXISTS display_name TEXT;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS notes TEXT;

-- Add result columns to device_commands
ALTER TABLE device_commands ADD COLUMN IF NOT EXISTS result TEXT;
ALTER TABLE device_commands ADD COLUMN IF NOT EXISTS result_message TEXT;
