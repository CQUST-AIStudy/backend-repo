-- Manual fix: create V1 tap tables and indexes that failed due to syntax

-- Create indexes (ignore errors if they already exist)
-- tap_user indexes
CREATE UNIQUE INDEX uq_tap_user_username ON tap_user(username);
CREATE INDEX idx_tap_user_role ON tap_user(role);

-- paper
CREATE UNIQUE INDEX uq_paper_arxiv_id ON paper(arxiv_id);

-- document indexes
CREATE INDEX idx_document_sha256 ON document(sha256);
CREATE INDEX idx_document_upload_folder_id ON document(upload_folder_id);
CREATE INDEX idx_document_user_id ON document(user_id);

-- agent_result
CREATE UNIQUE INDEX uq_agent_result_job_id ON agent_result(job_id);
