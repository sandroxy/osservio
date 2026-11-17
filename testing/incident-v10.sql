INSERT INTO environment_facts(incident_id,key,type,text_value,number_value,truncated) SELECT id,'androidTargetSDK','unavailable',NULL,NULL,0 FROM incidents WHERE environment_platform IS NOT NULL;
INSERT INTO environment_facts(incident_id,key,type,text_value,number_value,truncated) SELECT id,'androidMinSDK','unavailable',NULL,NULL,0 FROM incidents WHERE environment_platform IS NOT NULL;
INSERT INTO environment_facts(incident_id,key,type,text_value,number_value,truncated) SELECT id,'androidDebuggable','unavailable',NULL,NULL,0 FROM incidents WHERE environment_platform IS NOT NULL;
PRAGMA user_version=10;
