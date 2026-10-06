package jp.co.pillreminder.controller;

import jp.co.pillreminder.model.Medicine; // ※必要に応じてモデルやエンティティのパスは調整してください
import jp.co.pillreminder.model.IntakeLog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jp.co.pillreminder.model.Medicine;
import jp.co.pillreminder.model.IntakeLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MedicationApiController {

    // 1. ユーザーのお薬一覧を取得
    @GetMapping("/medicines")
    public ResponseEntity<List<Medicine>> getMedicines(@RequestParam String userId) {
        // TODO: サービス層と連携してDBからお薬リストを取得する実装に置き換えてください
        return ResponseEntity.ok(List.of());
    }

    // 2. 新しいお薬を登録
    @PostMapping("/medicines")
    public ResponseEntity<Map<String, Object>> registerMedicine(@RequestBody MedicineRequest request) {
        // TODO: お薬の保存処理をここに記述
        Map<String, Object> response = new HashMap<>();
        response.hax("success", true);
        return ResponseEntity.ok(response);
    }

    // 3. ストリーク（継続日数）情報の取得
    @GetMapping("/streak")
    public ResponseEntity<Map<String, Object>> getStreak(@RequestParam String userId) {
        Map<String, Object> streakData = new HashMap<>();
        streakData.put("streak", 0);
        streakData.put("monthlyCount", 0);
        streakData.put("message", "今日も自分のペースでいこう！");
        return ResponseEntity.ok(streakData);
    }

    // 4. 月別の飲用ログを取得
    @GetMapping("/logs/month")
    public ResponseEntity<Map<String, List<IntakeLogDto>>> getMonthlyLogs(
            @RequestParam String userId,
            @RequestParam int year,
            @RequestParam int month) {
        // key: "YYYY-MM-DD", value: ログのリスト
        Map<String, List<IntakeLogDto>> monthlyLogs = new HashMap<>();
        return ResponseEntity.ok(monthlyLogs);
    }

    // 5. 手動での飲用記録の保存
    @PostMapping("/intake/manual")
    public ResponseEntity<Map<String, Object>> manualIntake(@RequestBody ManualIntakeRequest request) {
        // TODO: 手動記録の保存処理をここに記述
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return ResponseEntity.ok(response);
    }

    // --- リクエスト受信用クラス（DTO）の定義 ---
    public static class MedicineRequest {
        private String userId;
        private String name;
        private String notificationTime;
        // getters and setters
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getNotificationTime() { return notificationTime; }
        public void setNotificationTime(String notificationTime) { this.notificationTime = notificationTime; }
    }

    public static class ManualIntakeRequest {
        private String userId;
        private Long medicineId;
        private String date;
        private String takenTime;
        // getters and setters
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public Long getMedicineId() { return medicineId; }
        public void setMedicineId(Long medicineId) { this.medicineId = medicineId; }
        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getTakenTime() { return takenTime; }
        public void setTakenTime(String takenTime) { this.takenTime = takenTime; }
    }

    public static class IntakeLogDto {
        private String medicineName;
        private String takenTime;
        // constructor, getters and setters
        public IntakeLogDto(String medicineName, String takenTime) {
            this.medicineName = medicineName;
            this.takenTime = takenTime;
        }
        public String getMedicineName() { return medicineName; }
        public String getTakenTime() { return takenTime; }
    }
}
