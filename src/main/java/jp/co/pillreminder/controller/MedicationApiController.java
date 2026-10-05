package jp.co.pillreminder.controller;

import java.sql.Time;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/medications")
public class MedicationApiController {

    private final JdbcTemplate jdbc;

    public MedicationApiController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 1. お薬一覧の取得（残数や頓服フラグも取得）
    @GetMapping
    public List<Map<String, Object>> getMedications(@RequestParam("lineUserId") String lineUserId) {
        String sql = """
            SELECT m.id, m.name,
                   to_char(m.notify_time, 'HH24:MI') AS notify_time,
                   m.dosage,
                   COALESCE(m.show_name, true) AS show_name,
                   COALESCE(m.stock_quantity, 0) AS stock_quantity,
                   COALESCE(m.decrement_amount, 1) AS decrement_amount,
                   COALESCE(m.is_as_needed, false) AS is_as_needed,
                   COALESCE(m.low_stock_alert, 5) AS low_stock_alert
            FROM medications m
            JOIN users u ON m.user_id = u.id
            WHERE u.line_user_id = ? AND m.is_active = true
            ORDER BY m.is_as_needed ASC, m.notify_time ASC
        """;
        return jdbc.queryForList(sql, lineUserId);
    }

    // 2. お薬の新規登録
    @PostMapping
    public ResponseEntity<String> register(@RequestBody Map<String, Object> req) {
        String lineUserId = (String) req.get("lineUserId");
        String name = (String) req.get("name");
        String notifyTimeStr = (String) req.get("notifyTime");
        String dosage = (String) req.getOrDefault("dosage", "1包");
        Boolean showName = req.get("showName") != null ? Boolean.parseBoolean(req.get("showName").toString()) : true;
        Boolean isAsNeeded = req.get("isAsNeeded") != null ? Boolean.parseBoolean(req.get("isAsNeeded").toString()) : false;
        
        int stockQuantity = parseInt(req.get("stockQuantity"), 0);
        int decrementAmount = parseInt(req.get("decrementAmount"), 1);
        int lowStockAlert = parseInt(req.get("lowStockAlert"), 5);

        if (lineUserId == null || name == null) {
            return ResponseEntity.badRequest().body("必須項目が不足しています");
        }

        // 頓服で通知時間が指定されていない場合は 00:00 を設定
        if (notifyTimeStr == null || notifyTimeStr.isBlank()) {
            notifyTimeStr = "00:00";
        }

        jdbc.update("INSERT INTO users (line_user_id) VALUES (?) ON CONFLICT (line_user_id) DO NOTHING", lineUserId);
        Integer userId = jdbc.queryForObject("SELECT id FROM users WHERE line_user_id = ?", Integer.class, lineUserId);

        LocalTime parsedTime = LocalTime.parse(notifyTimeStr);
        jdbc.update(
            """
            INSERT INTO medications (
                user_id, name, notify_time, dosage, show_name,
                stock_quantity, decrement_amount, is_as_needed, low_stock_alert
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            userId, name, Time.valueOf(parsedTime), dosage, showName,
            stockQuantity, decrementAmount, isAsNeeded, lowStockAlert
        );

        return ResponseEntity.ok("登録成功");
    }

    // 3. お薬の更新
    @PutMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable("id") Integer id, @RequestBody Map<String, Object> req) {
        String name = (String) req.get("name");
        String notifyTimeStr = (String) req.get("notifyTime");
        String dosage = (String) req.getOrDefault("dosage", "1包");
        Boolean showName = req.get("showName") != null ? Boolean.parseBoolean(req.get("showName").toString()) : true;
        Boolean isAsNeeded = req.get("isAsNeeded") != null ? Boolean.parseBoolean(req.get("isAsNeeded").toString()) : false;

        int stockQuantity = parseInt(req.get("stockQuantity"), 0);
        int decrementAmount = parseInt(req.get("decrementAmount"), 1);
        int lowStockAlert = parseInt(req.get("lowStockAlert"), 5);

        if (name == null) {
            return ResponseEntity.badRequest().body("必須項目が不足しています");
        }

        if (notifyTimeStr == null || notifyTimeStr.isBlank()) {
            notifyTimeStr = "00:00";
        }

        LocalTime parsedTime = LocalTime.parse(notifyTimeStr);
        jdbc.update(
            """
            UPDATE medications SET
                name = ?, notify_time = ?, dosage = ?, show_name = ?,
                stock_quantity = ?, decrement_amount = ?, is_as_needed = ?, low_stock_alert = ?
            WHERE id = ?
            """,
            name, Time.valueOf(parsedTime), dosage, showName,
            stockQuantity, decrementAmount, isAsNeeded, lowStockAlert, id
        );

        return ResponseEntity.ok("更新成功");
    }

    // 4. 頓服・手動用：いま飲んだ（残数を減らして記録）
    @PostMapping("/{id}/take")
    public ResponseEntity<Map<String, Object>> takeMedication(@PathVariable("id") Integer id) {
        // 服薬ログ追加
        jdbc.update("INSERT INTO intake_logs (medication_id) VALUES (?)", id);
        
        // 残数を decrement_amount 分だけ減らす（0未満にはしない）
        jdbc.update(
            "UPDATE medications SET stock_quantity = GREATEST(0, stock_quantity - COALESCE(decrement_amount, 1)) WHERE id = ?",
            id
        );

        // 更新後の残数を取得
        Map<String, Object> updated = jdbc.queryForMap(
            "SELECT name, stock_quantity, low_stock_alert FROM medications WHERE id = ?",
            id
        );

        return ResponseEntity.ok(updated);
    }

    // 5. お薬の削除
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable("id") Integer id) {
        jdbc.update("UPDATE medications SET is_active = false WHERE id = ?", id);
        return ResponseEntity.ok("削除成功");
    }

    private int parseInt(Object val, int defaultVal) {
        if (val == null) return defaultVal;
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
