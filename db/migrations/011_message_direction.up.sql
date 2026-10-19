-- Direction of a message relative to the uploading phone: 'in' = received by its SIM,
-- 'out' = sent from it. `sender` keeps meaning "the other party" (conversation key) and
-- `receiver` keeps meaning "this phone's line", so existing rows are all 'in'.
ALTER TABLE messages ADD COLUMN direction VARCHAR(3) NOT NULL DEFAULT 'in';
