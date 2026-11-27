CREATE TABLE attachment_host_allowlist (
    hostname VARCHAR(253) PRIMARY KEY,
    approved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_by VARCHAR(255) NOT NULL,
    evidence VARCHAR(500) NOT NULL
);

INSERT INTO attachment_host_allowlist (hostname, approved_by, evidence) VALUES
 ('oaisdmntprdenmarkeast.blob.core.windows.net', 'migration', 'Previously approved regional attachment host'),
 ('oaisdmntprpolandcentral.blob.core.windows.net', 'migration', 'Previously approved regional attachment host'),
 ('oaisdmntprcentralus.blob.core.windows.net', 'migration', 'Previously approved regional attachment host'),
 ('oaisdmntprukwest.blob.core.windows.net', 'migration', 'Observed in Render attachment diagnostics and explicitly approved by owner for this release');
