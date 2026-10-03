package jp.co.pillreminder.controller;

import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // 1. お薬一覧の取得
    @GetMapping
    public List<Map<String, Object>> getMedications(@RequestParam("lineUserId") String lineUserId) {
        String sql = """
            SELECT m.id, m.name, to_char(m.notify_time, 'HH24:MI') AS notify_time, m.dosage, COALESCE(m.show_name, true) AS show_name
            FROM medications m
            JOIN users u ON m.user_id = u.id
            WHERE u.line_user_id = ? AND m.is_active = true
            ORDER BY m.notify_time ASC
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

        if (lineUserId == null || name == null || notifyTimeStr == null) {
            return ResponseEntity.badRequest().body("必須項目が不足しています");
        }

        jdbc.update("INSERT INTO users (line_user_id) VALUES (?) ON CONFLICT (line_user_id) DO NOTHING", lineUserId);
        Integer userId = jdbc.queryForObject("SELECT id FROM users WHERE line_user_id = ?", Integer.class, lineUserId);

        LocalTime parsedTime = LocalTime.parse(notifyTimeStr);
        jdbc.update(
            "INSERT INTO medications (user_id, name, notify_time, dosage, show_name) VALUES (?, ?, ?, ?, ?)",
            userId, name, Time.valueOf(parsedTime), dosage, showName
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

        if (name == null || notifyTimeStr == null) {
            return ResponseEntity.badRequest().body("必須項目が不足しています");
        }

        LocalTime parsedTime = LocalTime.parse(notifyTimeStr);
        jdbc.update(
            "UPDATE medications SET name = ?, notify_time = ?, dosage = ?, show_name = ? WHERE id = ?",
            name, Time.valueOf(parsedTime), dosage, showName, id
        );

        return ResponseEntity.ok("更新成功");
    }

    // 4. お薬の削除
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable("id") Integer id) {
        jdbc.update("DELETE FROM medications WHERE id = ?", id);
        return ResponseEntity.ok("削除成功");
    }

    // 5. 服薬履歴の取得（日本時間 +9時間 & 名前非表示）
    @GetMapping("/history")
    public List<Map<String, Object>> getHistory(@RequestParam("lineUserId") String lineUserId) {
        String sql = """
            SELECT l.id,
                   CASE WHEN COALESCE(m.show_name, true) = false THEN 'お薬' ELSE m.name END AS name,
                   to_char(l.taken_at + INTERVAL '9 hour', 'MM/DD HH24:MI') AS taken_at_str
            FROM intake_logs l
            JOIN medications m ON l.medication_id = m.id
            JOIN users u ON m.user_id = u.id
            WHERE u.line_user_id = ?
            ORDER BY l.taken_at DESC
            LIMIT 10
        """;
        return jdbc.queryForList(sql, lineUserId);
    }

    // 6. カレンダー＆ストリーク集計の取得（★新機能！）
    @GetMapping("/calendar")
    public Map<String, Object> getCalendarData(@RequestParam("lineUserId") String lineUserId) {
        String sql = """
            SELECT DISTINCT to_char(l.taken_at + INTERVAL '9 hour', 'YYYY-MM-DD') AS taken_date
            FROM intake_logs l
            JOIN medications m ON l.medication_id = m.id
            JOIN users u ON m.user_id = u.id
            WHERE u.line_user_id = ?
            ORDER BY taken_date DESC
        """;
        List<String> dates = jdbc.queryForList(sql, String.class, lineUserId);
        Set<String> dateSet = new HashSet<>(dates);

        ZoneId jst = ZoneId.of("Asia/Tokyo");
        LocalDate today = LocalDate.now(jst);

        boolean todayDone = dateSet.contains(today.toString());
        int streak = 0;

        if (todayDone) {
            streak = 1;
            LocalDate check = today.minusDays(1);
            while (dateSet.contains(check.toString())) {
                streak++;
                check = check.minusDays(1);
            }
        } else {
            // 今日はまだだが、昨日まで継続しているか
            LocalDate check = today.minusDays(1);
            while (dateSet.contains(check.toString())) {
                streak++;
                check = check.minusDays(1);
            }
        }

        // 今月の達成日数（日本時間基準）
        String currentYearMonth = String.format("%04d-%02d", today.getYear(), today.getMonthValue());
        long monthlyCount = dates.stream()
            .filter(d -> d.startsWith(currentYearMonth))
            .count();

        Map<String, Object> res = new HashMap<>();
        res.put("streak", streak);
        res.put("todayDone", todayDone);
        res.put("monthlyTotalDays", monthlyCount);
        res.put("intakeDates", dates);
        return res;
    }
    /**
     * 手動で服薬ログを記録するAPI（過去日・押し忘れ救済）
     */
    @PostMapping("/manual-intake")
    public ResponseEntity<?> recordManualIntake(@RequestBody Map<String, Object> body) {
        try {
            Integer medId = Integer.parseInt(body.get("medicationId").toString());
            String takenAt = (String) body.get("takenAt"); // "YYYY-MM-DD HH:mm:ss" 形式

            if (takenAt == null || takenAt.isBlank()) {
                jdbc.update("INSERT INTO intake_logs (medication_id) VALUES (?)", medId);
            } else {
                jdbc.update("INSERT INTO intake_logs (medication_id, taken_at) VALUES (?, ?::timestamp)", medId, takenAt);
            }

            return ResponseEntity.ok(Map.of("success", true, "message", "記録を追加しました"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
    
}