START TRANSACTION;

-- 先确认将被更新的行
SELECT user_id, group_id, affinity_score AS old_score
FROM user_affinity
WHERE group_id = '1079312807'
ORDER BY user_id;

-- 执行更新
UPDATE user_affinity
SET affinity_score = 100,
    updated_at = NOW()
WHERE group_id = '1079312807';

-- 验证结果
SELECT user_id, group_id, affinity_score AS new_score, updated_at
FROM user_affinity
WHERE group_id = '1079312807'
ORDER BY user_id;

-- 全表范围核对：确认只动了 1079312807
SELECT group_id, COUNT(*) AS cnt
FROM user_affinity
GROUP BY group_id
ORDER BY group_id;

COMMIT;
