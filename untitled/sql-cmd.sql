SELECT id, from_id, to_id, type, properties
FROM relationship
WHERE from_id = 1079312807 OR to_id = 1079312807
   OR (properties::text LIKE '%1079312807%')
LIMIT 50;

SELECT '--- entity 1079312807 ---' AS info;
SELECT id, type, name, properties
FROM entity
WHERE id = 1079312807;

SELECT '--- 查 1079312807 群的所有成员 ---' AS info;
SELECT id, type, name, properties
FROM entity
WHERE properties::text LIKE '%1079312807%'
   OR name LIKE '%1079312807%'
LIMIT 50;
