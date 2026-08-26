#!/bin/bash
source /opt/qq-bot/.env
export MYSQL_PWD="$DB_PASSWORD"
DB="candybear_db"

echo "=== 归儿在群 437625485 的 user_profile ==="
mysql -u"$DB_USER" -h127.0.0.1 -e "SELECT * FROM user_profiles WHERE user_id='3524398813' AND group_id='437625485'\G" "$DB"

echo ""
echo "=== 归儿在所有群的 user_profile ==="
mysql -u"$DB_USER" -h127.0.0.1 -e "SELECT group_id, message_count_snapshot, last_message_id, profile_text, updated_at FROM user_profiles WHERE user_id='3524398813'\G" "$DB"

echo ""
echo "=== 归儿的会话认知 conversation_beliefs ==="
mysql -u"$DB_USER" -h127.0.0.1 -e "SELECT id, group_id, topic, user_emotion, bot_intent, unresolved_question, is_active, created_at FROM conversation_beliefs WHERE user_id='3524398813' ORDER BY created_at DESC LIMIT 5\G" "$DB"
