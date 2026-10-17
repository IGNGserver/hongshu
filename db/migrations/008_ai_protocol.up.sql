ALTER TABLE ai_settings
 ADD COLUMN protocol VARCHAR(40) NOT NULL DEFAULT 'openai_chat_completions' AFTER endpoint;
