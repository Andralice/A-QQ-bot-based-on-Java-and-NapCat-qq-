SELECT COUNT(*) AS total_rows, SUM(CASE WHEN group_id = '1079312807' THEN 1 ELSE 0 END) AS rows_in_group
FROM user_affinity;

SELECT user_id, group_id, affinity_score, message_count_snapshot, updated_at
FROM user_affinity
WHERE group_id = '1079312807'
ORDER BY affinity_score DESC, user_id;
