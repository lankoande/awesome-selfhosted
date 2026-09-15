-- =====================================================================================
--  Roles de deploiement
--
--  Deux roles, jamais un seul :
--    * le proprietaire des objets (celui qui execute les migrations de schema) ;
--    * le role applicatif, avec lequel l'API et les traitements se connectent. Il ne possede
--      rien : c'est ce qui rend la Row Level Security effective pour lui.
--
--  A executer une fois, avec le proprietaire du schema (celui de COREBANKING_SCHEMA_USER,
--  capable de creer des roles), apres la premiere montee de version — les objets doivent
--  exister pour recevoir les droits. Les partitions creees ensuite par la bascule de journee,
--  et les tables des versions suivantes, heritent des droits par ALTER DEFAULT PRIVILEGES,
--  qui s'attache au role qui execute ce script : c'est pourquoi il s'execute sous le
--  proprietaire, et pas sous un super-utilisateur de passage.
--
--      psql -U <proprietaire> -d corebanking -v app_password='...' -f ops/roles.sql
-- =====================================================================================
CREATE ROLE corebanking_app LOGIN PASSWORD :'app_password';

GRANT USAGE ON SCHEMA public TO corebanking_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO corebanking_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO corebanking_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO corebanking_app;

-- Les objets crees plus tard par le proprietaire (partitions, tables des versions suivantes)
-- recoivent les memes droits.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO corebanking_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO corebanking_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO corebanking_app;
