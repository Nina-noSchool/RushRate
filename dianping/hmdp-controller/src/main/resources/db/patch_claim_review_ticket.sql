-- 闪赔人工审核工单（2026-11-18，P8）
-- 申诉单进入 MANUAL_REVIEW 后，属主可开工单补充材料/催办；
-- 管理端回执（APPROVED/REJECTED）会驱动状态机 resume 收口。
-- 执行方式（VM 或本机均可）：mysql -uroot -p dingping < patch_claim_review_ticket.sql
CREATE TABLE IF NOT EXISTS tb_claim_review_ticket (
  id bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  claim_no varchar(64) NOT NULL COMMENT '申诉单号',
  user_id bigint NOT NULL COMMENT '开单属主（申诉单 userId）',
  content varchar(500) NOT NULL COMMENT '工单内容（补充材料/催办说明）',
  ticket_status varchar(32) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / RESOLVED',
  resolution varchar(500) DEFAULT NULL COMMENT '人工回执说明（outcome: 说明）',
  created_at datetime NOT NULL COMMENT '创建时间',
  updated_at datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (id) USING BTREE,
  KEY idx_claim_no (claim_no) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='闪赔人工审核工单';
