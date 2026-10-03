package jp.co.pillreminder.scheduler;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MedicationPushScheduler {

    private static final Logger log = LoggerFactory.getLogger(MedicationPushScheduler.class);
    private final JdbcTemplate jdbc;
    private final RestClient restClient;

    @Value("${line.bot.channel-token:${LINE_CHANNEL_ACCESS_TOKEN:}}")
    private String channelToken;

    // 通常通知テンプレート（単一）
    private static final List<String> SINGLE_TEMPLATES = List.of(
        "⏰ %s だよ！「%s」のお薬タイム〜！\n\n今日も一日お疲れさま☕️\nコップ1杯のお水と一緒に、忘れずに飲んでね💊\n\n飲んだら下のボタンをポチッと押してね！",
        "⏰ %s だよ！「%s」のリマインド💊\n\nついつい忘れがちな時間だけど、ちゃんと飲もうね！えらい！👏\nお水と一緒にごくっと飲んで、下のボタンを押して教えてね〜！",
        "⏰ %s になったよ！「%s」の時間！\n\n無理せずマイペースにいこう🙌✨\nお薬飲んでしっかり体を労わってあげてね💊\n飲み終わったら下のボタンをタップ！"
    );

    // 通常通知テンプレート（複数）
    private static final List<String> MULTI_TEMPLATES = List.of(
        "⏰ %s だよ！お薬タイム〜！\n\n今回飲むお薬（%d種類）はこちら💊\n%s\n\nコップのお水と一緒に飲んでね☕️\n飲んだら下のボタンをタップして教えてね！",
        "⏰ %s になったよ！お薬リマインド💊\n\n今回のお薬一覧（%d種類）だよ👇\n%s\n\nマイペースにいこう🙌\n飲み終わったら下のボタンで記録してね！"
    );

    // 追いLINE用テンプレート（罪悪感ゼロ・過集中全肯定メッセージ）
    private static final List<String> SNOOZE_TEMPLATES = List.of(
        "ふぅ〜っと一息つこう☕️\n何か別のことに夢中になってたかな？集中できててナイス！✨\n\n今この通知に気づけただけでハナマル満点だよ💮\nもしお薬まだだったら、コップ1杯のお水と一緒に飲もう💊\n%s\n\n飲んだら下のボタンをポチッと教えてね！",
        "お疲れさま〜！マイペースにいこう🙌\n作業ややりたいこと、頑張っててえらい！\n\n今思い出せたらそれだけで大成功だよ✨\n無理のないタイミングで、お水と一緒にごくっと飲んで体を労わろう💊\n%s\n\n飲み終わったら下のボタンをタップしてね！",
        "ちょっとブレイクタイムにしよ🍵\nついつい後回しになっちゃうこと、誰にでもあるから大丈夫！\n\n気付いた今がベストタイミングだよ👏\nお薬飲んでリフレッシュしよう〜！\n%s\n\n下のボタンで記録してね💊"
    );

    public MedicationPushScheduler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.restClient = RestClient.builder().baseUrl("https://api.line.me").build();
    }

    // 毎分実行：定時リマインド ＆ 追いLINE
    @Scheduled(cron = "0 * * * * *")
    public void pushReminders() {
        if (channelToken == null || channelToken.isBlank()) return;
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Tokyo"));
        String nowTimeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"));

        log.info("⏰ スケジューラー実行中 [日本時間: {}]", nowTimeStr);

        // 1. 定時リマインドの送信
        sendRegularReminders(nowTimeStr);

        // 2. 追いLINEの送信（30分前の通知で未服薬のお薬をチェック）
        String snoozeTargetTimeStr = now.minusMinutes(30).format(DateTimeFormatter.ofPattern("HH:mm"));
        sendSnoozeReminders(snoozeTargetTimeStr);
    }

    /**
     * ★ 新機能：毎週日曜の夜 21:00（日本時間）に週間レポートを配信！
     * ※テストしたい時は cron = "0 * * * * *" にすると毎分届くよ！
     */
    @Scheduled(cron = "0 0 21 * * SUN", zone = "Asia/Tokyo")
    public void pushWeeklyReport() {
        if (channelToken == null || channelToken.isBlank()) return;
        log.info("📊 週間服薬レポート配信バッチ開始");

        ZoneId jst = ZoneId.of("Asia/Tokyo");
        LocalDate today = LocalDate.now(jst);
        LocalDate monday = today.minusDays(6); // 月曜日〜日曜日（7日間）

        String startStr = monday.toString();
        String endStr = today.toString();

        // お薬が登録されているアクティブユーザー一覧を取得
        String userSql = """
            SELECT DISTINCT u.id, u.line_user_id
            FROM users u
            JOIN medications m ON m.user_id = u.id
            WHERE m.is_active = true
        """;
        List<Map<String, Object>> users = jdbc.queryForList(userSql);

        for (Map<String, Object> user : users) {
            Integer userId = (Integer) user.get("id");
            String lineUserId = (String) user.get("line_user_id");

            // 直近7日間で服薬記録があるユニーク日数を集計
            String countSql = """
                SELECT COUNT(DISTINCT to_char(l.taken_at + INTERVAL '9 hour', 'YYYY-MM-DD'))
                FROM intake_logs l
                JOIN medications m ON l.medication_id = m.id
                WHERE m.user_id = ?
                  AND to_char(l.taken_at + INTERVAL '9 hour', 'YYYY-MM-DD') BETWEEN ? AND ?
            """;
            Integer takenDays = jdbc.queryForObject(countSql, Integer.class, userId, startStr, endStr);
            if (takenDays == null) takenDays = 0;

            sendWeeklyReportMessage(lineUserId, takenDays);
        }
    }

    // 週間レポートメッセージの生成＆プッシュ送信
    private void sendWeeklyReportMessage(String lineUserId, int takenDays) {
        int percent = (int) Math.round((takenDays / 7.0) * 100);
        String reportText;

        if (takenDays == 7) {
            reportText = """
                📊【今週の服薬レポート】
                今週の達成度: 7日 / 7日 (100%) 💮

                🎉 パーフェクト達成！本当にえらすぎる！！👏
                自分の体をしっかり大切にできてて最高だよ！
                この調子で来週もマイペースにいこうね✨
                """;
        } else if (takenDays >= 5) {
            reportText = String.format("""
                📊【今週の服薬レポート】
                今週の達成度: %d日 / 7日 (%d%%) ✨

                ナイスキープ！しっかり飲めてて素晴らしいよ💪
                体を大事にする習慣、バッチリついてるね！
                来週もこの調子でマイペースにいこう🙌
                """, takenDays, percent);
        } else if (takenDays >= 1) {
            reportText = String.format("""
                📊【今週の服薬レポート】
                今週の達成度: %d日 / 7日 (%d%%) 🌱

                今週もお疲れさま！忙しい日もあったよね。
                記録できた日があるだけでハナマル満点だよ💮
                無理せずマイペースに、体を労わっていこうね☕️
                """, takenDays, percent);
        } else {
            reportText = """
                📊【今週の服薬レポート】
                今週の達成度: 0日 / 7日 (0%) 🌱

                今週もお疲れさま！バタバタと忙しかったかな？
                来週からまたいつでも再開できるから大丈夫☕️
                無理のないペースで、体を大切にしていこうね✨
                """;
        }

        Map<String, Object> body = Map.of(
            "to", lineUserId,
            "messages", List.of(Map.of(
                "type", "text",
                "text", reportText.trim()
            ))
        );

        try {
            restClient.post().uri("/v2/bot/message/push")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(channelToken))
                .body(body)
                .retrieve().toBodilessEntity();
            log.info("週間レポート送信成功: {} ({}日/7日)", lineUserId, takenDays);
        } catch (Exception e) {
            log.error("週間レポート送信失敗: {}", e.getMessage());
        }
    }

    // --- 定時リマインド ---
    private void sendRegularReminders(String timeStr) {
        String sql = """
            SELECT u.line_user_id, m.id, m.name, m.dosage, COALESCE(m.show_name, true) AS show_name
            FROM medications m
            JOIN users u ON m.user_id = u.id
            WHERE to_char(m.notify_time, 'HH24:MI') = ? AND m.is_active = true
        """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, timeStr);
        if (rows.isEmpty()) return;

        Map<String, List<Map<String, Object>>> userMedsMap = rows.stream()
            .collect(Collectors.groupingBy(r -> (String) r.get("line_user_id")));

        for (Map.Entry<String, List<Map<String, Object>>> entry : userMedsMap.entrySet()) {
            sendAggregatedReminder(entry.getKey(), timeStr, entry.getValue());
        }
    }

    // --- 追いLINE（スヌーズ） ---
    private void sendSnoozeReminders(String targetTimeStr) {
        String sql = """
            SELECT u.line_user_id, m.id, m.name, m.dosage, COALESCE(m.show_name, true) AS show_name
            FROM medications m
            JOIN users u ON m.user_id = u.id
            WHERE to_char(m.notify_time, 'HH24:MI') = ? AND m.is_active = true
        """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, targetTimeStr);
        if (rows.isEmpty()) return;

        List<Map<String, Object>> unTakenRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int medId = (Integer) row.get("id");
            String checkSql = "SELECT COUNT(*) FROM intake_logs WHERE medication_id = ? AND taken_at >= (CURRENT_TIMESTAMP - INTERVAL '1 hour')";
            Integer count = jdbc.queryForObject(checkSql, Integer.class, medId);
            if (count == null || count == 0) {
                unTakenRows.add(row);
            }
        }

        if (unTakenRows.isEmpty()) return;

        Map<String, List<Map<String, Object>>> userMedsMap = unTakenRows.stream()
            .collect(Collectors.groupingBy(r -> (String) r.get("line_user_id")));

        for (Map.Entry<String, List<Map<String, Object>>> entry : userMedsMap.entrySet()) {
            sendSnoozeMessage(entry.getKey(), entry.getValue());
        }
    }

    private void sendSnoozeMessage(String lineUserId, List<Map<String, Object>> meds) {
        StringBuilder medListStr = new StringBuilder();
        List<Map<String, Object>> quickReplyItems = new ArrayList<>();
        List<String> allMedIds = meds.stream().map(m -> m.get("id").toString()).toList();
        String allIdsStr = String.join(",", allMedIds);

        for (Map<String, Object> med : meds) {
            Integer medId = (Integer) med.get("id");
            String name = (String) med.get("name");
            String dosage = (String) med.get("dosage");
            boolean showName = (Boolean) med.get("show_name");
            String displayName = showName ? name : "お薬";

            medListStr.append("・").append(displayName).append(" (").append(dosage).append(")\n");

            String label = "✅ " + (displayName.length() > 8 ? displayName.substring(0, 8) + ".." : displayName) + " 飲んだ";
            String pData = "action=intake&medId=" + medId + "&allIds=" + allIdsStr;
            quickReplyItems.add(createPostbackQuickReply(label, pData, displayName + " 飲んだ！"));
        }

        if (meds.size() > 1 && quickReplyItems.size() < 13) {
            quickReplyItems.add(createPostbackQuickReply("✨ 全部飲んだ！", "action=intake_all&medIds=" + allIdsStr, "全部飲んだ！"));
        }

        int idx = ThreadLocalRandom.current().nextInt(SNOOZE_TEMPLATES.size());
        String text = String.format(SNOOZE_TEMPLATES.get(idx), medListStr.toString().trim());

        Map<String, Object> body = Map.of(
            "to", lineUserId,
            "messages", List.of(Map.of(
                "type", "text",
                "text", text,
                "quickReply", Map.of("items", quickReplyItems)
            ))
        );

        try {
            restClient.post().uri("/v2/bot/message/push")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(channelToken))
                .body(body)
                .retrieve().toBodilessEntity();
            log.info("追いLINE送信成功: {} ({}件のお薬)", lineUserId, meds.size());
        } catch (Exception e) {
            log.error("追いLINE送信失敗: {}", e.getMessage());
        }
    }

    private void sendAggregatedReminder(String lineUserId, String timeStr, List<Map<String, Object>> meds) {
        String messageText;
        List<Map<String, Object>> quickReplyItems = new ArrayList<>();

        if (meds.size() == 1) {
            Map<String, Object> med = meds.get(0);
            Integer medId = (Integer) med.get("id");
            String name = (String) med.get("name");
            boolean showName = (Boolean) med.get("show_name");
            String displayName = showName ? name : "お薬";

            int idx = ThreadLocalRandom.current().nextInt(SINGLE_TEMPLATES.size());
            messageText = String.format(SINGLE_TEMPLATES.get(idx), timeStr, displayName);

            String pData = "action=intake&medId=" + medId + "&allIds=" + medId;
            quickReplyItems.add(createPostbackQuickReply("✅ 飲んだ！", pData, displayName + " 飲んだ！"));
        } else {
            StringBuilder medListStr = new StringBuilder();
            List<String> allMedIds = meds.stream().map(m -> m.get("id").toString()).toList();
            String allIdsStr = String.join(",", allMedIds);

            for (Map<String, Object> med : meds) {
                Integer medId = (Integer) med.get("id");
                String name = (String) med.get("name");
                String dosage = (String) med.get("dosage");
                boolean showName = (Boolean) med.get("show_name");
                String displayName = showName ? name : "お薬";

                medListStr.append("・").append(displayName).append(" (").append(dosage).append(")\n");

                String label = "✅ " + (displayName.length() > 8 ? displayName.substring(0, 8) + ".." : displayName) + " 飲んだ";
                String pData = "action=intake&medId=" + medId + "&allIds=" + allIdsStr;
                quickReplyItems.add(createPostbackQuickReply(label, pData, displayName + " 飲んだ！"));
            }

            if (quickReplyItems.size() < 13) {
                quickReplyItems.add(createPostbackQuickReply("✨ 全部飲んだ！", "action=intake_all&medIds=" + allIdsStr, "全部飲んだ！"));
            }

            int idx = ThreadLocalRandom.current().nextInt(MULTI_TEMPLATES.size());
            messageText = String.format(MULTI_TEMPLATES.get(idx), timeStr, meds.size(), medListStr.toString().trim());
        }

        Map<String, Object> body = Map.of(
            "to", lineUserId,
            "messages", List.of(Map.of(
                "type", "text",
                "text", messageText,
                "quickReply", Map.of("items", quickReplyItems)
            ))
        );

        try {
            restClient.post().uri("/v2/bot/message/push")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(channelToken))
                .body(body)
                .retrieve().toBodilessEntity();
            log.info("リマインド送信成功: {} ({}件のお薬)", lineUserId, meds.size());
        } catch (Exception e) {
            log.error("リマインド送信失敗: {}", e.getMessage());
        }
    }

    private Map<String, Object> createPostbackQuickReply(String label, String data, String displayText) {
        return Map.of(
            "type", "action",
            "action", Map.of(
                "type", "postback",
                "label", label,
                "data", data,
                "displayText", displayText
            )
        );
    }
}