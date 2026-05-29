ALTER TABLE report_file
    DROP CHECK chk_report_file_type,
    ADD CONSTRAINT chk_report_file_type
        CHECK (file_type IN ('pdf', 'zip', 'annopdf', 'annodoc'));
